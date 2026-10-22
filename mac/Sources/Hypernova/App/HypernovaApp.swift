import AppKit
import SwiftUI

@main
enum Main {
    static func main() {
        let args = CommandLine.arguments
        if args.contains("--selftest") {
            Fonts.register()
            exit(SelfTest.run())
        }
        if let i = args.firstIndex(of: "--run-step") {
            guard i + 1 < args.count else { print("Usage: Hypernova --run-step <\(RunStep.names.joined(separator: "|"))>"); exit(64) }
            exit(RunStep.run(args[i + 1]))
        }
        if let i = args.firstIndex(of: "--render-iconset"), i + 1 < args.count {
            do { try NovaIcons.writeIconset(to: URL(fileURLWithPath: args[i + 1])); exit(0) } catch { print("render failed: \(error)"); exit(1) }
        }
        if let i = args.firstIndex(of: "--selftest-node-download"), i + 1 < args.count {
            exit(DevTools.nodeDownload(into: URL(fileURLWithPath: args[i + 1])))
        }
        if args.contains("--selftest-stream") { exit(DevTools.streamTest()) }
        if args.contains("--backpack-scripts") { exit(BackpackSetup.printScripts()) }
        if args.contains("--window-capture-selftest") { exit(DevTools.windowCaptureSelfTest()) }
        if let i = args.firstIndex(of: "--snapshots"), i + 1 < args.count {
            Fonts.register()
            exit(DevTools.snapshots(into: URL(fileURLWithPath: args[i + 1])))
        }
        if let i = args.firstIndex(of: "--feed-selftest") {
            exit(DevTools.feedSelfTest(seconds: i + 1 < args.count ? Double(args[i + 1]) ?? 0 : 0, signIn: args.contains("--sign-in"),
                                       open: args.contains("--open")))
        }
        if let i = args.firstIndex(of: "--popover-selftest"), i + 1 < args.count {
            DevTools.popoverSelfTestDir = URL(fileURLWithPath: args[i + 1])
        }
        if let i = args.firstIndex(of: "--lens-guide-selftest"), i + 1 < args.count {
            Fonts.register()
            DevTools.runLensGuideSelfTest(into: URL(fileURLWithPath: args[i + 1]))
        }
        if let i = args.firstIndex(of: "--render-lens-guide"), i + 1 < args.count {
            Fonts.register()
            exit(DevTools.renderLensGuide(into: URL(fileURLWithPath: args[i + 1])))
        }
        if let i = args.firstIndex(of: "--render-popover"), i + 1 < args.count {
            Fonts.register()
            exit(DevTools.renderPopover(into: URL(fileURLWithPath: args[i + 1]), live: args.contains("--live")))
        }
        Fonts.register()
        HypernovaApp.main()
    }
}

struct HypernovaApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        // The menu bar item is AppKit (StatusItemController), so its icon can turn. SwiftUI still
        // needs a scene, and this one keeps the standard app and Edit menus (copy and paste in
        // the Setup and Providers windows) without putting anything in the menu bar.
        MenuBarExtra(isInserted: .constant(false)) {
            EmptyView()
        } label: {
            EmptyView()
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationWillFinishLaunching(_ notification: Notification) {
        // One Hypernova at a time: hand over to the running one.
        // (Right after moving to /Applications the old copy is still quitting; don't hand over to it.)
        // (A test copy runs beside the owner's, on its own daemon.)
        let me = NSRunningApplication.current
        if !Paths.testCopy, !CommandLine.arguments.contains("--moved"), let id = Bundle.main.bundleIdentifier,
           let other = NSRunningApplication.runningApplications(withBundleIdentifier: id).first(where: { $0 != me }) {
            other.activate()
            exit(0)
        }
        NSApp.setActivationPolicy(.accessory)
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        if let dir = DevTools.popoverSelfTestDir {
            Task { @MainActor in exit(await DevTools.runPopoverSelfTest(into: dir)) }
            return
        }
        Task { @MainActor in
            StatusItemController.shared.install()
            AppModel.shared.start()
            // Glasses pairing and "Show on my Mac" open the Lens guide at once, through the
            // model's live feed. Only here: offscreen renders and self-tests leave it closed.
            LensGuideModel.shared.start()
            if let dir = ProcessInfo.processInfo.environment["HYPERNOVA_DEBUG_CAPTURE"] {
                DevTools.liveCapture(into: URL(fileURLWithPath: dir))
            }
            if AppLocation.offerMoveIfNeeded() { return }
            if !Defaults.store.bool(forKey: Defaults.setupCompleted) {
                WindowManager.shared.showSetup()
            }
        }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        // Opening the app again (Finder, Spotlight) with no window up shows Setup, so it never
        // feels like nothing happened.
        if flag { return true }
        Task { @MainActor in WindowManager.shared.showSetup() }
        return false
    }
}
