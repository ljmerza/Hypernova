import AppKit
import CoreGraphics
import Foundation
import ScreenCaptureKit

/// Telescope's window source (docs/PROTOCOL.md, "Telescope"): a picture of the Mac app a session
/// is building, for the glasses. It's taken here rather than by the daemon because macOS gives
/// Screen Recording to an app the owner can find and name in System Settings, and the daemon is a
/// node process under launchd (it would show there as "node", and lose the permission with each
/// Node update).
///
/// The daemon asks on the live feed (`{t: "telescope.window", job, pids, roots, ask}`): it names
/// the app by the processes running a program built in the session's folder (never by bundle id,
/// which any folder could claim). This app holds Screen Recording, so it doesn't take the daemon's
/// word for it: each process counts only when the program the kernel loaded for it
/// (`proc_pidpath`, not the argv[0] a process can set itself) is inside one of `roots`, and a root
/// that is a folder of the Mac's or of installed apps counts for nothing. Only windows owned by the
/// processes left are ever looked at; the largest normal window is captured (a menu bar app's panel
/// when it has no other) and handed back on the local port (`POST /local/telescope/<job>`), where it
/// becomes a token link.
///
/// Screen Recording is asked for once ever, and only when the owner named the source himself
/// (`ask`): when the telescope picks by itself, a Mac without the permission just says so.
enum WindowCapture {
    private struct Job: Decodable {
        let job: String
        let pids: [Int32]
        /// The folders the processes' programs must run from: the session's, Xcode's build products of
        /// its projects. Absent (a daemon from before the check): nothing is captured.
        let roots: [String]?
        let ask: Bool
    }

    private struct Answer: Encodable {
        var png: String?
        var app: String?
        var title: String?
        var error: String?
        var asked: Bool?
    }

    private static let askedKey = "telescope.askedScreenRecording"
    /// The longer side of a picture, at most: plenty for the glasses and the phone's card.
    private static let maxSide: CGFloat = 2560

    /// A request from the daemon, straight off the feed.
    static func handle(_ text: String) {
        guard let data = text.data(using: .utf8), let job = try? JSONDecoder().decode(Job.self, from: data),
              job.job.range(of: "^[0-9a-f]{24}$", options: .regularExpression) != nil else { return }
        Task.detached { await answer(job.job, capture(job)) }
    }

    private static func capture(_ job: Job) async -> Answer {
        // Checked before anything else, the permission included: a request naming no app the session
        // builds never even puts the Screen Recording question on the Mac.
        let pids = Set(allowed(job.pids, roots: job.roots ?? []))
        if pids.isEmpty { return Answer(error: "no_app") }
        guard CGPreflightScreenCaptureAccess() else {
            var asked = false
            // Kept with the app's other preferences (a test copy's apart from the owner's).
            if job.ask && !Defaults.store.bool(forKey: askedKey) {
                Defaults.store.set(true, forKey: askedKey)
                // macOS shows its own question (once ever) and the switch in System Settings.
                asked = await MainActor.run { _ = CGRequestScreenCaptureAccess(); return true }
            }
            return Answer(error: "no_permission", asked: asked)
        }
        do {
            let content = try await SCShareableContent.excludingDesktopWindows(true, onScreenWindowsOnly: true)
            let mine = content.windows.filter { w in
                guard let app = w.owningApplication else { return false }
                return pids.contains(app.processID)
            }
            .filter { $0.frame.width >= 80 && $0.frame.height >= 60 }
            let appName = mine.first?.owningApplication?.applicationName
            // A normal window first (layer 0); a menu bar app may have only its panel.
            let area = { (w: SCWindow) in w.frame.width * w.frame.height }
            guard let window = mine.filter({ $0.windowLayer == 0 }).max(by: { area($0) < area($1) }) ?? mine.max(by: { area($0) < area($1) }) else {
                return Answer(app: appName, error: "no_window")
            }
            let filter = SCContentFilter(desktopIndependentWindow: window)
            let config = SCStreamConfiguration()
            let scale = CGFloat(filter.pointPixelScale)
            let k = min(1, maxSide / max(window.frame.width * scale, window.frame.height * scale))
            config.width = max(1, Int(window.frame.width * scale * k))
            config.height = max(1, Int(window.frame.height * scale * k))
            config.showsCursor = false
            config.ignoreShadowsSingleWindow = true
            let image = try await SCScreenshotManager.captureImage(contentFilter: filter, configuration: config)
            guard let png = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) else {
                return Answer(app: appName, error: "failed")
            }
            return Answer(png: png.base64EncodedString(), app: window.owningApplication?.applicationName, title: window.title)
        } catch {
            return Answer(error: CGPreflightScreenCaptureAccess() ? "failed" : "no_permission", asked: false)
        }
    }

    // MARK: Whose windows

    /// The pids whose real executable lies inside one of `roots` (and only roots that may hold an
    /// app being built). Checked here, right before any window is looked at, because the daemon's
    /// list comes over a socket and a process's own name for itself (argv[0]) is whatever it chose.
    static func allowed(_ pids: [Int32], roots: [String]) -> [Int32] {
        let home = realPath(NSHomeDirectory()) ?? NSHomeDirectory()
        let ok = roots.compactMap { realPath($0) }.filter { acceptableRoot($0, home: home) }
        guard !ok.isEmpty else { return [] }
        return pids.filter { pid in
            guard pid > 0, pid != getpid(), let exe = executable(of: pid), let real = realPath(exe) else { return false }
            return ok.contains { real.hasPrefix($0 + "/") }
        }
    }

    /// The program the kernel runs for a process, as a path (nil when it's gone or not ours to see).
    static func executable(of pid: Int32) -> String? {
        var buf = [CChar](repeating: 0, count: Int(MAXPATHLEN) * 4)
        let n = proc_pidpath(pid, &buf, UInt32(buf.count))
        guard n > 0 else { return nil }
        return String(cString: buf)
    }

    /// A folder that may hold an app a session builds: never the disk's root, the home folder or a
    /// folder above it, nor the Mac's own or installed apps' folders (a "session" there would name
    /// every app in them). Xcode's build products are the one place under ~/Library that counts.
    static func acceptableRoot(_ root: String, home: String) -> Bool {
        guard root.hasPrefix("/"), root != "/", root != home, !home.hasPrefix(root + "/") else { return false }
        let derived = home + "/Library/Developer/Xcode/DerivedData/"
        if root.hasPrefix(derived) { return root.hasSuffix("/Build/Products") && root.dropFirst(derived.count).split(separator: "/").count == 3 }
        let system = ["/Applications", "/System", "/Library", "/usr", "/bin", "/sbin", "/opt", home + "/Applications", home + "/Library"]
        return !system.contains { root == $0 || root.hasPrefix($0 + "/") }
    }

    private static func realPath(_ path: String) -> String? {
        guard let r = realpath(path, nil) else { return nil }
        defer { free(r) }
        return String(cString: r)
    }

    private static func answer(_ job: String, _ a: Answer) async {
        _ = try? await DaemonClient.shared.request("POST", "/local/telescope/\(job)", body: a, timeout: 15, as: EmptyResponse.self)
    }
}
