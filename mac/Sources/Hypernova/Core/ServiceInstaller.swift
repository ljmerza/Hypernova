import Foundation

/// Installs the daemon as a per-user LaunchAgent: copies the bundled daemon into
/// <support>/app/daemon, fetches Claude Code's SDK into it (ClaudeSDK: never fatal), writes
/// ~/Library/LaunchAgents/com.prudnikweb.hypernova.daemon.plist, (re)loads it with launchctl and
/// waits for /v1/health. A test copy (Paths.testCopy) does the same under its own label, with the
/// plist in its test folder.
enum ServiceInstaller {
    struct State: Equatable {
        var plistExists: Bool
        var installedDaemon: Bool
        var healthy: Bool
        var daemonName: String?
        var updateAvailable: Bool
        var loadedInLaunchd: Bool
    }

    static func detect(health: Health?) -> State {
        let fm = FileManager.default
        let plist = fm.fileExists(atPath: Paths.launchAgent.path)
        let installed = fm.fileExists(atPath: Paths.daemonMain.path)
        let loaded = Shell.run("/bin/launchctl", ["print", Paths.serviceTarget], timeout: 5).ok
        return State(plistExists: plist, installedDaemon: installed, healthy: health != nil, daemonName: health?.name, updateAvailable: updateAvailable(), loadedInLaunchd: loaded)
    }

    /// This copy of the app carries a newer service than the one installed (their build stamps
    /// differ). Two small file reads, so the menu bar can ask on every status refresh.
    static func updateAvailable() -> Bool {
        guard FileManager.default.fileExists(atPath: Paths.daemonMain.path), let bundled = Paths.bundledDaemon else { return false }
        let a = try? String(contentsOf: bundled.appendingPathComponent(Paths.buildStampName), encoding: .utf8)
        let b = try? String(contentsOf: Paths.installedDaemon.appendingPathComponent(Paths.buildStampName), encoding: .utf8)
        return a != nil && a != b
    }

    /// The Node.js the installed LaunchAgent starts (its first program argument), if there is one.
    static func plistNodePath() -> String? {
        guard let data = try? Data(contentsOf: Paths.launchAgent),
              let plist = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
              let args = plist["ProgramArguments"] as? [String], let node = args.first, !node.isEmpty else { return nil }
        return node
    }

    /// The installed LaunchAgent's Node.js is gone (removed with its nvm version, say): launchd
    /// relaunches a missing binary every 10 s, and a restart reuses the same plist. Its path, or nil.
    static func missingNode() -> String? {
        guard let node = plistNodePath() else { return nil }
        return FileManager.default.isExecutableFile(atPath: node) ? nil : node
    }

    static func pathEnvironment(nodePath: String) -> String {
        let nodeDir = (nodePath as NSString).deletingLastPathComponent
        var seen = Set<String>()
        // /usr/sbin and /sbin too: the daemon runs system tools such as scutil (the Mac's name).
        return [nodeDir, Paths.localBin.path, "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin"]
            .filter { seen.insert($0).inserted }
            .joined(separator: ":")
    }

    static func plist(nodePath: String) -> [String: Any] {
        [
            "Label": Paths.label,
            "ProgramArguments": [nodePath, Paths.daemonMain.path],
            "WorkingDirectory": Paths.installedDaemon.path,
            "RunAtLoad": true,
            "KeepAlive": true,
            "ThrottleInterval": 10,
            "ProcessType": "Interactive",
            "EnvironmentVariables": [
                "PATH": pathEnvironment(nodePath: nodePath),
                "HOME": Paths.home.path,
                "LANG": "en_US.UTF-8",
            ].merging(Paths.serviceEnvironment) { _, test in test },
            "StandardOutPath": Paths.daemonLog.path,
            "StandardErrorPath": Paths.daemonLog.path,
        ]
    }

    static func plistXML(nodePath: String) -> String {
        guard let d = try? PropertyListSerialization.data(fromPropertyList: plist(nodePath: nodePath), format: .xml, options: 0) else { return "" }
        return String(decoding: d, as: UTF8.self)
    }

    /// The commands `install` runs, for the self-test's dry run.
    static var launchctlCommands: [String] {
        ["launchctl bootout \(Paths.serviceTarget)   (errors ignored)",
         "launchctl bootstrap \(Paths.launchDomain) \(Paths.tilde(Paths.launchAgent.path))"]
    }

    static func err(_ m: String) -> NSError { NSError(domain: "Hypernova.Service", code: 1, userInfo: [NSLocalizedDescriptionKey: m]) }

    /// Replaces <support>/app/daemon with the bundled daemon in one rename.
    static func copyDaemon() throws {
        guard let source = Paths.bundledDaemon else { throw err("This copy of Hypernova doesn't include the background service. Rebuild it with mac/build.sh.") }
        let fm = FileManager.default
        try Paths.ensureSupport()
        try fm.createDirectory(at: Paths.appDir, withIntermediateDirectories: true)
        let staging = Paths.appDir.appendingPathComponent(".daemon-\(UUID().uuidString)")
        try fm.copyItem(at: source, to: staging)
        // From a downloaded disk image every file in the app carries the quarantine flag, and the copy
        // keeps it: Gatekeeper would then stop the service's own helpers (node-pty's, the Tailscale
        // node) the first time Node runs them, after the app itself was already allowed to open.
        _ = Shell.run("/usr/bin/xattr", ["-dr", "com.apple.quarantine", staging.path], timeout: 30)
        // The bundle has no Claude Code SDK (ClaudeSDK): the one the copy being replaced has goes on
        // in the new one, so an update never loses it, offline or not.
        ClaudeSDK.carryOver(from: Paths.installedDaemon, to: staging)
        if fm.fileExists(atPath: Paths.installedDaemon.path) {
            _ = try fm.replaceItemAt(Paths.installedDaemon, withItemAt: staging, backupItemName: nil, options: [])
        } else {
            try fm.moveItem(at: staging, to: Paths.installedDaemon)
        }
        if fm.fileExists(atPath: staging.path) { try? fm.removeItem(at: staging) }
    }

    static func writePlist(nodePath: String) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: Paths.launchAgents, withIntermediateDirectories: true)
        try fm.createDirectory(at: Paths.logsDir, withIntermediateDirectories: true)
        let data = try PropertyListSerialization.data(fromPropertyList: plist(nodePath: nodePath), format: .xml, options: 0)
        try data.write(to: Paths.launchAgent, options: .atomic)
        try? fm.setAttributes([.posixPermissions: 0o644], ofItemAtPath: Paths.launchAgent.path)
    }

    static func bootout() {
        guard Paths.serviceBlocked == nil else { return }
        _ = Shell.run("/bin/launchctl", ["bootout", Paths.serviceTarget], timeout: 15)
        // Wait until launchd has really let go, or bootstrap fails with "Input/output error".
        for _ in 0..<20 {
            if !Shell.run("/bin/launchctl", ["print", Paths.serviceTarget], timeout: 5).ok { return }
            Thread.sleep(forTimeInterval: 0.25)
        }
    }

    static func bootstrap() throws {
        if let why = Paths.serviceBlocked { throw err(why) }
        var last = ShellResult(status: 0, stdout: "", stderr: "")
        for attempt in 0..<4 {
            last = Shell.run("/bin/launchctl", ["bootstrap", Paths.launchDomain, Paths.launchAgent.path], timeout: 15)
            if last.ok { return }
            Thread.sleep(forTimeInterval: 0.6 * Double(attempt + 1))
        }
        throw err("launchd didn't accept the background service (\(last.combined.isEmpty ? "exit \(last.status)" : last.combined)).")
    }

    /// The pid listening on a local TCP port, if any.
    static func listenerPID(port: Int) -> Int32? {
        let r = Shell.run("/usr/sbin/lsof", ["-nP", "-iTCP:\(port)", "-sTCP:LISTEN", "-t"], timeout: 8)
        return r.stdout.split(separator: "\n").first.flatMap { Int32($0.trimmingCharacters(in: .whitespaces)) }
    }

    /// The pid launchd reports for the service while it runs.
    static func launchdPID() -> Int32? {
        let r = Shell.run("/bin/launchctl", ["print", Paths.serviceTarget], timeout: 5)
        guard r.ok else { return nil }
        for line in r.stdout.split(separator: "\n") {
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("pid = "), let pid = Int32(t.dropFirst(6)) { return pid }
        }
        return nil
    }

    static func kickstart() -> Bool {
        guard Paths.serviceBlocked == nil else { return false }
        return Shell.run("/bin/launchctl", ["kickstart", "-k", Paths.serviceTarget], timeout: 15).ok
    }

    static func logTail(_ lines: Int = 20) -> [String] {
        guard let s = try? String(contentsOf: Paths.daemonLog, encoding: .utf8) else { return [] }
        return Array(s.split(separator: "\n", omittingEmptySubsequences: true).suffix(lines).map(String.init))
    }

    enum Progress: Equatable {
        /// Downloading Hypernova's own Node.js (the fraction while it downloads), when there's no
        /// stable one to run on.
        case gettingNode(Double?)
        case copying
        /// Fetching Claude Code's SDK from npm into the copy (ClaudeSDK), when it isn't there yet.
        case fetchingSDK
        case writing, starting, waiting
        /// Its place in the order the steps run.
        var rank: Int {
            switch self {
            case .gettingNode: return 0
            case .copying: return 1
            case .fetchingSDK: return 2
            case .writing: return 3
            case .starting: return 4
            case .waiting: return 5
            }
        }
    }

    /// The whole step c. Blocking parts run off the main thread. Claude Code's SDK comes after the
    /// copy and before the start, and whatever becomes of it the service starts (ClaudeSDK says why
    /// it's missing, where that matters).
    static func install(nodePath: String, progress: @escaping @Sendable (Progress) -> Void) async throws -> Health {
        if let why = Paths.serviceBlocked { throw err(why) }
        progress(.copying)
        try await Task.detached {
            try copyDaemon()
            try NodeDetector.record(nodePath)
        }.value
        if !ClaudeSDK.state().current { progress(.fetchingSDK) }
        _ = await ClaudeSDK.ensure(in: Paths.installedDaemon, nodePath: nodePath)
        progress(.writing)
        try await Task.detached { try writePlist(nodePath: nodePath) }.value
        progress(.starting)
        try await Task.detached {
            bootout()
            try bootstrap()
        }.value
        progress(.waiting)
        if let h = await DaemonClient.shared.waitForHealth(seconds: 40) {
            // Make sure it's *our* service answering, not a copy started by hand (which would hold the
            // port and leave the background service crash-looping behind it).
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            let (listener, managed) = await Task.detached { (listenerPID(port: Endpoints.apiPort), launchdPID()) }.value
            if let listener, listener != managed {
                throw err("Another copy of the service (process \(listener), started outside Hypernova, maybe in a Terminal window) is using port \(Endpoints.apiPort), so the background service can't take over. Quit that copy, then try again.")
            }
            return h
        }
        let tail = logTail(12).joined(separator: "\n")
        throw err("The background service started but isn't answering yet." + (tail.isEmpty ? "" : "\n\nLast lines of its log:\n" + tail))
    }

    /// Removes the LaunchAgent and the installed daemon copy.
    static func remove() {
        guard Paths.serviceBlocked == nil else { return }
        bootout()
        try? FileManager.default.removeItem(at: Paths.launchAgent)
        try? FileManager.default.removeItem(at: Paths.appDir)
    }
}
