import AppKit
import SwiftUI

/// Pairing from the menu bar popover. Every time it opens it asks the daemon for a fresh code, and
/// each code pairs exactly one device, so a second phone (or anything else that speaks the
/// protocol) pairs the same way. While it's open it watches for the new device every 2 s and
/// renews the code before it expires.
@MainActor
final class PopoverPairing: ObservableObject {
    enum Phase: Equatable {
        case starting
        /// Phone access (Tailscale serve) isn't on, so a code couldn't carry this Mac's address.
        case needsAccess
        case showing
        case paired(String)
        case failed(String)
    }

    @Published private(set) var phase: Phase = .starting
    @Published private(set) var pair: PairStart?
    /// The code carries only the local network's route (no tailnet address yet): it pairs a phone on
    /// the same Wi‑Fi.
    @Published private(set) var localOnly = false
    private var known: Set<String>?
    private var loop: Task<Void, Never>?
    private var fetching = false
    private var lastAttempt = Date.distantPast
    /// Offscreen renders: a fixed phase, no daemon.
    private var sample = false

    init() {}
    init(sample phase: Phase, pair: PairStart? = nil, localOnly: Bool = false) {
        sample = true
        self.phase = phase
        self.pair = pair
        self.localOnly = localOnly
    }

    func begin() {
        guard !sample else { return }
        loop?.cancel()
        pair = nil
        known = nil
        phase = .starting
        loop = Task { [weak self] in
            await self?.fetchCode()
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard let self, !Task.isCancelled else { return }
                await self.tick()
            }
        }
    }

    func end() {
        loop?.cancel()
        loop = nil
        // The code stops pairing once nobody can see it.
        if let code = pair?.code { Task { try? await DaemonClient.shared.pairCancel(code) } }
    }

    private func fetchCode() async {
        guard !fetching else { return }
        fetching = true
        lastAttempt = Date()
        defer { fetching = false }
        let model = AppModel.shared
        if model.status == nil { await model.refresh() }
        // The tailnet's address (the Tailscale app's or the built-in node's), or else the local
        // network's route alone: a phone on the same Wi‑Fi pairs through it (docs/PROTOCOL.md,
        // "Local network").
        let base = model.phoneBaseURL
        guard base != nil || model.localRoute else {
            if phase != .needsAccess { withAnimation(.hnExpo(0.3)) { phase = .needsAccess } }
            return
        }
        do {
            // Remember who's paired already, so the new device is the one that wasn't.
            if known == nil { known = Set(try await DaemonClient.shared.devices().map(\.id)) }
            let p = try await DaemonClient.shared.pairStart()
            // If Tailscale hiccuped, the daemon falls back to an address the phone can't reach.
            if let base, let got = p.url.map({ $0.hasSuffix("/") ? String($0.dropLast()) : $0 }), got != base {
                throw SetupActions.err("Tailscale isn’t answering right now, so the code can’t carry this Mac’s address. Trying again…")
            }
            // The code it replaces stops pairing now.
            if let old = pair?.code, old != p.code { Task { try? await DaemonClient.shared.pairCancel(old) } }
            withAnimation(.hnExpo(0.3)) { pair = p; localOnly = base == nil; phase = .showing }
        } catch {
            withAnimation(.hnExpo(0.3)) { phase = .failed(error.localizedDescription) }
        }
    }

    private func tick() async {
        switch phase {
        case .showing:
            if let exp = pair?.expiresAt?.date, exp.timeIntervalSinceNow < 20 { await fetchCode() }
            guard let known, let list = try? await DaemonClient.shared.devices() else { return }
            if let new = list.first(where: { !known.contains($0.id) }) {
                NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
                withAnimation(.hnExpo(0.6)) { phase = .paired(new.displayName); pair = nil }
                Defaults.store.set(true, forKey: Defaults.setupCompleted)
                await AppModel.shared.refresh()
            }
        case .needsAccess, .failed:
            // Try again calmly: phone access may come on, or Tailscale may answer again.
            if Date().timeIntervalSince(lastAttempt) >= 6 { await fetchCode() }
        case .starting, .paired:
            break
        }
    }

    func copyLink() {
        guard let link = pair?.link else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(link, forType: .string)
    }
}
