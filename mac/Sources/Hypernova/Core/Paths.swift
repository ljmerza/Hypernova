import Foundation

/// Every path Hypernova reads or writes on this Mac (docs/PROTOCOL.md).
enum Paths {
    static let home = FileManager.default.homeDirectoryForCurrentUser
    private static let env = ProcessInfo.processInfo.environment

    /// A development copy pointed at a test daemon: HYPERNOVA_SUPPORT, HYPERNOVA_LOGS or a port is
    /// set. Everything it could manage is its own, so it can never drive the owner's install: its
    /// background service has its own label, with the plist and the log in the test folder (so it
    /// doesn't come back at login either); it never touches the login item, keeps its preferences
    /// apart, leaves Claude Code's settings, ~/.zshrc and Tailscale alone, and never offers to move
    /// itself into /Applications. It manages a service only when fully isolated (isolatedTestCopy).
    static let testCopy = ["HYPERNOVA_SUPPORT", "HYPERNOVA_LOGS", "HYPERNOVA_API_PORT", "HYPERNOVA_LOCAL_PORT"]
        .contains { env[$0]?.isEmpty == false }
    /// A test copy has a test folder of its own only when HYPERNOVA_SUPPORT names one.
    private static let ownSupport = env["HYPERNOVA_SUPPORT"].map { !$0.isEmpty } ?? false
    /// A test copy that can't reach the owner's install by any road: a test folder of its own (not
    /// his, however it's spelled), and both ports moved off Hypernova's (and apart). Only such a
    /// copy manages a background service or drives a daemon from a self-test; anything less would
    /// start or poke a daemon on his ports, or replace and delete his files.
    static let isolatedTestCopy: Bool = {
        let owner: Set = [Endpoints.defaultAPIPort, Endpoints.defaultLocalPort]
        let set = ["HYPERNOVA_API_PORT", "HYPERNOVA_LOCAL_PORT"].allSatisfy { env[$0]?.isEmpty == false }
        return testCopy && ownSupport && set && !owner.contains(Endpoints.apiPort) && !owner.contains(Endpoints.localPort)
            && Endpoints.apiPort != Endpoints.localPort && !holdsOwners(support) && !holdsOwners(logsDir)
    }()

    static let label = testCopy ? "com.prudnikweb.hypernova.daemon.test-\(Endpoints.apiPort)" : "com.prudnikweb.hypernova.daemon"

    /// `<support>` = ~/Library/Application Support/Hypernova (0700).
    /// `HYPERNOVA_SUPPORT` points a development copy at a test daemon's folder (as the daemon itself does).
    static let support = (ownSupport ? env["HYPERNOVA_SUPPORT"] : nil).map { URL(fileURLWithPath: $0, isDirectory: true) }
        ?? ownerSupport
    private static let ownerSupport = home.appendingPathComponent("Library/Application Support/Hypernova", isDirectory: true)
    static let adminToken = support.appendingPathComponent("admin-token")
    static let nodePathRecord = support.appendingPathComponent("node-path")
    static let nodeDir = support.appendingPathComponent("node", isDirectory: true)
    static let appDir = support.appendingPathComponent("app", isDirectory: true)
    static let installedDaemon = appDir.appendingPathComponent("daemon", isDirectory: true)
    static var daemonMain: URL { installedDaemon.appendingPathComponent("src/main.ts") }

    /// A test copy's service lives in its test folder: launchd loads it from there just the same,
    /// and it doesn't come back by itself at the next login.
    static let launchAgents = testCopy ? support.appendingPathComponent("launchd", isDirectory: true)
        : home.appendingPathComponent("Library/LaunchAgents", isDirectory: true)
    static let launchAgent = launchAgents.appendingPathComponent("\(label).plist")
    static let logsDir = (env["HYPERNOVA_LOGS"].flatMap { $0.isEmpty ? nil : URL(fileURLWithPath: $0, isDirectory: true) })
        ?? (testCopy ? support.appendingPathComponent("logs", isDirectory: true) : ownerLogs)
    private static let ownerLogs = home.appendingPathComponent("Library/Logs/Hypernova", isDirectory: true)
    static let daemonLog = logsDir.appendingPathComponent("daemon.log")

    /// A test folder is the owner's support or log folder, or holds one (his home, say), by any
    /// spelling: through a symlink, with `..`, in other letter case (the disk ignores it), or by
    /// another road to the same folder (a firmlink such as /System/Volumes/Data). Installing a
    /// test service there would replace his daemon, and "also delete data" would delete his.
    private static func holdsOwners(_ folder: URL) -> Bool {
        func parts(_ u: URL) -> [String] { u.resolvingSymlinksInPath().standardizedFileURL.pathComponents.map { $0.lowercased() } }
        let mine = parts(folder)
        // What the disk calls this folder, if it exists, to compare with his and their parents.
        let id = try? folder.resolvingSymlinksInPath().resourceValues(forKeys: [.fileResourceIdentifierKey]).fileResourceIdentifier
        for owner in [ownerSupport, ownerLogs] {
            if parts(owner).starts(with: mine) { return true }
            guard let id else { continue }
            let comps = owner.standardizedFileURL.pathComponents
            for n in stride(from: comps.count, through: 1, by: -1) {
                let up = URL(fileURLWithPath: NSString.path(withComponents: Array(comps.prefix(n))), isDirectory: true)
                if let other = try? up.resourceValues(forKeys: [.fileResourceIdentifierKey]).fileResourceIdentifier, id.isEqual(other) { return true }
            }
        }
        return false
    }

    /// Why this copy may not install, restart or remove a background service, if it may not. A
    /// test copy on the owner's support folder would replace his installed daemon; one on his ports
    /// would start a daemon that fights his for them (and, with launchd's KeepAlive, keeps trying).
    static var serviceBlocked: String? {
        guard testCopy, !isolatedTestCopy else { return nil }
        if !ownSupport || holdsOwners(support) {
            return "This test copy uses the real support folder. Set HYPERNOVA_SUPPORT to a test folder before it manages a background service."
        }
        if holdsOwners(logsDir) {
            return "This test copy uses the real log folder. Set HYPERNOVA_LOGS to a test folder (or leave it out) before it manages a background service."
        }
        return "This test copy needs ports of its own. Set HYPERNOVA_API_PORT and HYPERNOVA_LOCAL_PORT to two other free ports (47371 and 47372, say) before it manages a background service."
    }

    /// The overrides a test copy hands to the service it starts, so that daemon uses the same
    /// folder, log and ports. The ports are always written out: without them it would start on
    /// the owner's. (Only an isolated test copy gets this far: see serviceBlocked.)
    static var serviceEnvironment: [String: String] {
        guard testCopy else { return [:] }
        return ["HYPERNOVA_SUPPORT": support.path, "HYPERNOVA_LOGS": logsDir.path,
                "HYPERNOVA_API_PORT": String(Endpoints.apiPort), "HYPERNOVA_LOCAL_PORT": String(Endpoints.localPort)]
    }

    static let claudeConfig = home.appendingPathComponent(".claude.json")
    static let localBin = home.appendingPathComponent(".local/bin", isDirectory: true)

    /// The daemon shipped inside the app bundle (Contents/Resources/daemon).
    static var bundledDaemon: URL? {
        guard let res = Bundle.main.resourceURL else { return nil }
        let d = res.appendingPathComponent("daemon", isDirectory: true)
        return FileManager.default.fileExists(atPath: d.appendingPathComponent("src/main.ts").path) ? d : nil
    }

    /// Written by build.sh next to the bundled daemon; compared with the installed copy to offer updates.
    static let buildStampName = ".hypernova-build"

    static var uid: uid_t { getuid() }
    static var launchDomain: String { "gui/\(uid)" }
    static var serviceTarget: String { "gui/\(uid)/\(label)" }

    /// `~/x` for display.
    static func tilde(_ path: String) -> String {
        let h = home.path
        return path.hasPrefix(h) ? "~" + path.dropFirst(h.count) : path
    }

    static func ensureSupport() throws {
        let fm = FileManager.default
        try fm.createDirectory(at: support, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try? fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: support.path)
    }

    static func readAdminToken() -> String? {
        guard let s = try? String(contentsOf: adminToken, encoding: .utf8) else { return nil }
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}

enum Endpoints {
    static let defaultAPIPort = 47321
    static let defaultLocalPort = 47322
    // HYPERNOVA_API_PORT and HYPERNOVA_LOCAL_PORT talk to a test daemon instead (docs: CLAUDE.md, testing).
    static let apiPort = ProcessInfo.processInfo.environment["HYPERNOVA_API_PORT"].flatMap(Int.init) ?? defaultAPIPort
    static let localPort = ProcessInfo.processInfo.environment["HYPERNOVA_LOCAL_PORT"].flatMap(Int.init) ?? defaultLocalPort
    static let health = URL(string: "http://127.0.0.1:\(apiPort)/v1/health")!
    static let local = URL(string: "http://127.0.0.1:\(localPort)")!
    static let qa = URL(string: "http://127.0.0.1:\(localPort)/qa")!
    static let tailscaleDownload = URL(string: "https://tailscale.com/download/mac")!
    static let tailscaleDNS = URL(string: "https://login.tailscale.com/admin/dns")!
    static let tailscaleMachines = URL(string: "https://login.tailscale.com/admin/machines")!
    static let claudeInstaller = "https://claude.ai/install.sh"
    static let nodeIndex = URL(string: "https://nodejs.org/dist/index.json")!
    static func nodeDist(_ version: String) -> URL { URL(string: "https://nodejs.org/dist/\(version)/")! }
}
