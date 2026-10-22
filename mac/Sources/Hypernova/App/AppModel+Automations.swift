import AppKit
import SwiftUI

/// Automations in the menu bar (docs/specs/spec-automations.md §7): one quiet line and a pause
/// switch. Editing happens on the phone, so this is all the Mac does.
@MainActor
extension AppModel {
    /// What the daemon said about automations; nil while it's down or from a daemon older than them.
    /// The snapshot is read more often than the status, so it leads.
    var automations: AutomationsState? {
        guard daemonUp else { return nil }
        var s = snapshot?.automations?.state ?? status?.automations?.state
        if let c = automationsPausedChoice { s?.paused = c }
        return s
    }

    /// The one line under SESSIONS, or nil when there's nothing to say. A run needs you when the
    /// daemon says so or its session waits on you.
    func automationLine(now: Date = Date()) -> AutomationWords.Line? {
        automations.flatMap { a in
            AutomationWords.line(a, now: now) { r in r.needsYou || (session(r.sessionId).map { needsYou($0) } ?? false) }
        }
    }

    /// The running automation's session has a terminal here to open.
    var runningAutomationOpens: Bool {
        guard let id = automations?.running.first?.sessionId, let s = session(id) else { return false }
        return s.term != nil
    }

    /// Pause all (false: resume). Shown at once; the daemon's answer settles it.
    func setAutomationsPaused(_ paused: Bool) {
        guard automations?.paused != paused || automationsPausedChoice != nil else { return }
        withAnimation(.hnExpo(0.25)) { automationsPausedChoice = paused }
        Task {
            do {
                _ = try await DaemonClient.shared.setAutomationsPaused(paused)
                await refresh()
            } catch {
                show("Couldn’t \(paused ? "pause" : "resume") automations. \(error.localizedDescription)")
            }
            withAnimation(.hnExpo(0.25)) { automationsPausedChoice = nil }
        }
    }

    /// A click on the "running" line: the terminal of the run's session.
    func openRunningAutomation(then done: @escaping () -> Void) {
        guard let id = automations?.running.first?.sessionId, let s = session(id), s.term != nil else { return }
        openInTerminal(s, then: done)
    }
}

extension DaemonClient {
    struct PausedBody: Encodable { var paused: Bool }
    struct PausedReply: Decodable { var paused: Bool }
    /// `POST /v1/automations/paused`: pause or resume every automation.
    func setAutomationsPaused(_ paused: Bool) async throws -> Bool {
        try await request("POST", "/v1/automations/paused", body: PausedBody(paused: paused), timeout: 10, as: PausedReply.self).paused
    }
}
