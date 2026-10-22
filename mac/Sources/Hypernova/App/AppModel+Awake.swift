import AppKit
import SwiftUI

/// Stay awake from the menu bar (docs/PROTOCOL.md, "Stay awake"): the mode, the battery level,
/// and Backpack's one-time setup and its removal.
@MainActor
extension AppModel {
    /// Stay awake as the daemon has it; nil while it's down, or from a daemon older than it.
    var awake: Awake? { daemonUp ? snapshot?.mac?.awake : nil }

    /// Chooses a mode. False when it's Backpack and its setup isn't done: the caller offers it.
    @discardableResult
    func chooseAwake(_ mode: Awake.Mode) -> Bool {
        guard let a = awake else { return true }
        if mode == .backpack && !a.backpackReady && !BackpackSetup.installed { return false }
        // Backpack chosen again starts its 8 hours over; anything else chosen again does nothing.
        guard mode != (awakeChoice ?? a.mode) || mode == .backpack else { return true }
        withAnimation(.hnExpo(0.25)) { awakeChoice = mode }
        Task {
            do {
                applyAwake(try await DaemonClient.shared.setAwake(mode: mode))
            } catch DaemonError.server(_, let code, _) where code == "needs_setup" {
                // The daemon doesn't see the rule (removed meanwhile): set it up again.
                show("Backpack mode needs its one-time setup again.")
            } catch {
                show("Couldn’t change Stay awake. \(error.localizedDescription)")
            }
            withAnimation(.hnExpo(0.25)) { awakeChoice = nil }
        }
        return true
    }

    /// "+2 h" on Backpack, offered in its last ten minutes. The daemon's other guards still apply.
    func extendBackpack() {
        Task {
            do { applyAwake(try await DaemonClient.shared.extendAwake()) } catch {
                show("Couldn’t extend Backpack. \(error.localizedDescription)")
            }
        }
    }

    /// The battery level at which the Mac is let go (5–50%).
    func setMinBattery(_ percent: Int) {
        Task {
            do { applyAwake(try await DaemonClient.shared.setAwake(minBattery: percent)) } catch {
                show("Couldn’t change the battery level. \(error.localizedDescription)")
            }
        }
    }

    /// Backpack's setup: macOS's password prompt once, then Backpack on. The popover goes first
    /// ([dismiss]), as the prompt is a window of its own, and comes back to show how it went.
    func enableBackpack(dismiss: @escaping () -> Void) {
        guard !backpackBusy else { return }
        backpackBusy = true
        dismiss()
        Task {
            // Let the popover close before the prompt takes the screen.
            try? await Task.sleep(nanoseconds: 250_000_000)
            var reopen = true
            do {
                try await BackpackSetup.install(inApp: true)
                applyAwake(try await DaemonClient.shared.setAwake(mode: .backpack))
            } catch BackpackSetup.Failure.cancelled {
                reopen = false
            } catch {
                show("Backpack mode isn’t set up. \(error.localizedDescription)")
            }
            backpackBusy = false
            if reopen { StatusItemController.shared.open() }
        }
    }

    /// Removes Backpack's permission (Settings): Backpack ends first, putting back what it found.
    func removeBackpack(dismiss: @escaping () -> Void) {
        guard !backpackBusy else { return }
        backpackBusy = true
        dismiss()
        Task {
            try? await Task.sleep(nanoseconds: 250_000_000)
            do {
                try await BackpackSetup.uninstall(inApp: true)
                await refresh(status: false)
            } catch BackpackSetup.Failure.cancelled {
            } catch {
                show("Couldn’t remove Backpack mode’s permission. \(error.localizedDescription)")
                StatusItemController.shared.open()
            }
            backpackBusy = false
        }
    }
}
