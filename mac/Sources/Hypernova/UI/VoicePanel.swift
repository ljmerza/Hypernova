import AppKit
import SwiftUI

/// Voice, as Setup's Voice step and the Lens guide's voice step both show it: connect Soniox
/// (a key, tried with a moment of silence), or use Hyperscribe Companion when it runs here.
/// One panel and one model (App/VoiceModel.swift), so the two windows can't disagree. The Mac
/// doesn't listen: there is no "hold to try" here (docs/specs/spec-voice.md, D14).
///
/// The guide puts its own title and lede (`lead`) over the Companion, beside the card; Setup, whose
/// scaffold has a title already, stacks the card and the Companion.
struct VoicePanel<Lead: View>: View {
    var context: VoiceModel.Context
    @ViewBuilder var lead: Lead

    var body: some View {
        switch context {
        case .guide:
            HStack(alignment: .top, spacing: 36) {
                VStack(alignment: .leading, spacing: 0) {
                    lead
                    if VoiceModel.companionOffered { VoiceCompanion(context: context).padding(.top, 26) }
                }
                .frame(width: 300, alignment: .leading)
                VoiceCard(context: context)
                    .frame(maxWidth: .infinity)
            }
        case .setup:
            VStack(alignment: .leading, spacing: 18) {
                VoiceCard(context: context)
                    .frame(maxWidth: 520)
                if VoiceModel.companionOffered { VoiceCompanion(context: context) }
            }
        }
    }
}

extension VoicePanel where Lead == EmptyView {
    init(context: VoiceModel.Context) { self.init(context: context) { EmptyView() } }
}

// MARK: - Soniox

/// Connect Soniox: paste the key, the daemon tries it with Soniox, then keeps it (never shown again,
/// only its last four characters).
struct VoiceCard: View {
    @EnvironmentObject private var m: VoiceModel
    @ObservedObject private var speaking = Speaking.shared
    var context: VoiceModel.Context

    var body: some View {
        let s = m.stt
        let connected = s?.provider != nil
        let ready = s?.ready == true
        GlassCard(needsYou: connected && !ready) {
            VStack(alignment: .leading, spacing: 12) {
                Seam(label: connected ? "Soniox" : "Connect Soniox",
                     trail: ready ? (s?.last4.map { "connected, key ••••\($0)" } ?? "connected") : connected ? "needs attention" : "real-time speech",
                     activity: ready ? .live : connected ? .alert : .rest)
                if let error = s?.error {
                    Text(error).font(HNFont.small).foregroundStyle(Color.hnAmber).fixedSize(horizontal: false, vertical: true)
                }
                if ready {
                    Text(context == .setup
                         ? "Hold the mic in a session, say what to do, let go: your words are in the box, ready to send."
                         : "Hold the temple and talk: Lens streams it through this Mac to Soniox, and the words come back as you speak.")
                        .font(HNFont.small).foregroundStyle(Color.hnMuted).fixedSize(horizontal: false, vertical: true)
                    if m.confirmingRemove { RemoveKeyConfirm() } else {
                        HStack(spacing: 8) {
                            CompactPill(title: m.testing ? "Testing…" : "Test", kind: .outline) { m.test() }
                            CompactPill(title: "Remove key", kind: .ghost) { withAnimation(.hnExpo(0.3)) { m.confirmingRemove = true } }
                        }
                    }
                } else {
                    if connected {
                        // A key Soniox stopped taking, or an account out of credit: try again once it's fixed.
                        if m.confirmingRemove { RemoveKeyConfirm() } else {
                            HStack(spacing: 8) {
                                CompactPill(title: m.testing ? "Testing…" : "Test again", kind: .outline) { m.test() }
                                CompactPill(title: "Remove key", kind: .ghost) { withAnimation(.hnExpo(0.3)) { m.confirmingRemove = true } }
                            }
                        }
                    }
                    HNField(label: connected ? "Or a new key" : "API key", text: $m.keyDraft, placeholder: "Paste your Soniox key", mono: true, secure: true)
                        .onSubmit { m.connect(in: context) }
                    HStack(spacing: 12) {
                        ActionPill(title: m.saving ? "Checking…" : connected ? "Use this key" : "Connect", busy: m.saving,
                                   disabled: m.keyDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) { m.connect(in: context) }
                        Button(connected ? "Open console.soniox.com ↗" : "Get a key at console.soniox.com ↗") { NSWorkspace.shared.open(VoiceModel.sonioxConsole) }
                            .buttonStyle(LinkStyle())
                            .fixedSize()
                    }
                    if !connected {
                        Text("Hypernova tries the key with a moment of silence before keeping it, so you know it works. The key stays on this Mac.")
                            .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                    }
                }
                // Which voice reads replies aloud (set on the phone, Settings → Voice): one quiet line.
                if let e = speaking.engine {
                    Text("Replies are read aloud with \(e.line).")
                        .font(HNFont.sans(11)).foregroundStyle(Color.hnDim).fixedSize(horizontal: false, vertical: true)
                }
                if let r = m.result {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Group { if r.ok { CheckIcon(size: 11) } else { CrossIcon(size: 10) } }
                            .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 4 }
                        Text(r.text).font(HNFont.small).foregroundStyle(r.ok ? Color.hnAccent : Color.hnDanger).fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        }
        .animation(.hnExpo(0.4), value: m.stt)
        .task { if speaking.sample == nil { await speaking.load() } }
    }
}

/// "Remove key" asks first, as forgetting a device does: the key can't be shown again, so removing
/// it by mistake means fetching it from Soniox's console.
private struct RemoveKeyConfirm: View {
    @EnvironmentObject private var m: VoiceModel

    /// The owner's dialog: glass, an eyebrow and a title, one line, Keep on the left and Remove on
    /// the right, red only as a thin accent.
    var body: some View {
        GlassDialogCard(dialog: GlassDialog(
            eyebrow: "Voice", title: "Remove the Soniox key?", line: "Voice stops until you add one. The key can't be shown again.",
            accent: .hnDanger, secondary: "Keep", primary: "Remove",
            confirm: { _ in m.remove() },
            cancel: { withAnimation(.hnExpo(0.3)) { m.confirmingRemove = false } }))
            .transition(.opacity)
    }
}

// MARK: - Hyperscribe Companion

/// "or Hyperscribe Companion": found by itself (it runs here with its tailnet route, or is installed
/// as an app), so nothing is asked of the person.
struct VoiceCompanion: View {
    @EnvironmentObject private var m: VoiceModel
    var context: VoiceModel.Context

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("or").hnLabel(.hnGhost)
            // Installed as an app: open it. Already running here (from source): just its name and
            // that it was found. Otherwise its setup page, labelled as such: it has no app to
            // download yet.
            if m.companionApp != nil || !m.companionReady {
                Button(m.companionApp != nil ? "Open Hyperscribe Companion" : "Hyperscribe Companion on GitHub ↗") { m.openCompanion() }
                    .buttonStyle(LinkStyle(color: .hnMuted))
                    .help(m.companionApp != nil ? "Opens Hyperscribe Companion on this Mac." : "Its source and setup steps. There’s no app to download yet.")
            } else {
                HStack(spacing: 8) {
                    Text("Hyperscribe Companion").font(HNFont.sans(12, .semibold)).foregroundStyle(Color.hnMuted)
                    StatusChip(text: "Found", color: .hnAccent)
                }
            }
            Text(line)
                .font(HNFont.sans(12)).foregroundStyle(Color.hnDim)
                .frame(maxWidth: 340, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var line: String {
        switch (context, m.companionReady) {
        case (.setup, true): return "It is set up on this Mac. Your phone and glasses use it while Soniox isn’t connected."
        case (.setup, false): return "It recognises speech on this Mac itself instead. Hypernova finds it by itself once it runs."
        case (.guide, true): return "It runs on this Mac, so Lens uses it whenever Soniox isn’t connected."
        case (.guide, false): return "It recognises speech on this Mac itself instead. Lens finds it through Hypernova."
        }
    }
}
