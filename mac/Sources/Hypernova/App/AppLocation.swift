import AppKit

/// Offers once to move Hypernova into /Applications, so the login item and Launch Services
/// always find the same copy. Translocated (quarantined, run-in-place) copies need it most.
@MainActor
enum AppLocation {
    static var bundleURL: URL { Bundle.main.bundleURL }
    static var isInApplications: Bool {
        let p = bundleURL.resolvingSymlinksInPath().path
        return p.hasPrefix("/Applications/") || p.hasPrefix(Paths.home.appendingPathComponent("Applications").path + "/")
    }
    static var isTranslocated: Bool { bundleURL.path.contains("/AppTranslocation/") }

    /// Returns true when it relaunched from /Applications (the caller should do nothing else).
    static func offerMoveIfNeeded() -> Bool {
        guard bundleURL.pathExtension == "app", !isInApplications else { return false }
        if Defaults.store.bool(forKey: Defaults.moveOfferDeclined), !isTranslocated { return false }
        if ProcessInfo.processInfo.environment["HYPERNOVA_NO_MOVE"] != nil { return false }
        // Moving would replace the owner's copy in /Applications.
        if Paths.testCopy { return false }

        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Move Hypernova to Applications?"
        alert.informativeText = "Hypernova lives in your menu bar and opens when you log in. Keeping it in the Applications folder makes sure it always starts from the same place."
        alert.addButton(withTitle: "Move to Applications")
        alert.addButton(withTitle: "Not now")
        alert.icon = NSApp.applicationIconImage
        guard alert.runModal() == .alertFirstButtonReturn else {
            Defaults.store.set(true, forKey: Defaults.moveOfferDeclined)
            return false
        }
        let dest = URL(fileURLWithPath: "/Applications/Hypernova.app")
        let fm = FileManager.default
        do {
            if fm.fileExists(atPath: dest.path) {
                // Stop a running copy from there first, then move the old one to the Trash.
                for app in NSRunningApplication.runningApplications(withBundleIdentifier: Bundle.main.bundleIdentifier ?? "")
                where app != NSRunningApplication.current && app.bundleURL?.standardizedFileURL == dest.standardizedFileURL {
                    app.terminate()
                }
                try fm.trashItem(at: dest, resultingItemURL: nil)
            }
            try fm.copyItem(at: bundleURL, to: dest)
            _ = Shell.run("/usr/bin/xattr", ["-dr", "com.apple.quarantine", dest.path], timeout: 20)
        } catch {
            let fail = NSAlert()
            fail.messageText = "Hypernova couldn't move itself."
            fail.informativeText = "\(error.localizedDescription)\n\nYou can drag Hypernova into Applications in Finder later. Setup continues from here."
            fail.runModal()
            return false
        }
        // A real move: the original goes to the Trash when we're allowed to (not a translocated or
        // disk-image copy). Then relaunch from /Applications and quit this copy.
        if !isTranslocated, fm.isWritableFile(atPath: bundleURL.deletingLastPathComponent().path) {
            try? fm.trashItem(at: bundleURL, resultingItemURL: nil)
        }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/open")
        p.arguments = ["-n", dest.path, "--args", "--moved"]
        try? p.run()
        NSApp.terminate(nil)
        return true
    }
}
