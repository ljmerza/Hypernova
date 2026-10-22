import Foundation

/// Claude Code's Agent SDK (`@anthropic-ai/claude-agent-sdk`) in the installed service. It's
/// Anthropic's ("All rights reserved"), so the app doesn't carry it (mac/build.sh leaves it out):
/// the Background service step fetches the version the daemon's lockfile pins from npm, once, with
/// the service's own Node.js and the npm beside it, into <support>/app/daemon, checked against the
/// lock's integrity (docs/DECISIONS.md, "Going public"). It never stops anything: offline or with an
/// npm error the service starts all the same, and only what drives Claude Code through it (sessions
/// the service runs itself, the "/" list, Rewind) waits for it, saying so (the daemon's sdk.ts).
///
/// Who hears about it: a Mac with Claude Code. Every Mac fetches it, quietly (it's small), but a
/// missing one is shown only where Claude Code is installed: with Codex alone nothing uses it, so
/// nothing says it's missing.
enum ClaudeSDK {
    static let package = "@anthropic-ai/claude-agent-sdk"
    /// One line build.sh writes beside the bundled daemon: the version Setup fetches.
    static let versionFile = "sdk-version"
    /// What the download weighs (the package itself, about 5 MB unpacked), for the progress line.
    static let downloadSize = "about 2 MB"
    /// The longest one fetch may take.
    static let timeout: TimeInterval = 180

    struct State: Equatable {
        /// The version this copy of the service pins; nil for one from before the fetch (it carried the SDK).
        var wanted: String?
        /// The version in its packages; nil while it isn't there.
        var installed: String?
        var missing: Bool { installed == nil }
        /// The SDK this copy of the service wants is there.
        var current: Bool { installed != nil && (wanted == nil || installed == wanted) }
    }

    enum Outcome: Equatable {
        /// It's there (the version).
        case ready(String)
        /// It isn't, and why, in words.
        case failed(String)
        var problem: String? { if case .failed(let w) = self { return w } else { return nil } }
    }

    static func packageDir(in daemon: URL) -> URL {
        daemon.appendingPathComponent("node_modules/@anthropic-ai/claude-agent-sdk", isDirectory: true)
    }

    static func pinned(in daemon: URL) -> String? {
        let s = (try? String(contentsOf: daemon.appendingPathComponent(versionFile), encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines)
        return s?.isEmpty == false ? s : nil
    }

    static func installedVersion(in daemon: URL) -> String? {
        guard let data = try? Data(contentsOf: packageDir(in: daemon).appendingPathComponent("package.json")),
              let j = try? JSONSerialization.jsonObject(with: data) as? [String: Any], j["name"] as? String == package else { return nil }
        return j["version"] as? String
    }

    /// Two small file reads: cheap enough for every status round.
    static func state(daemon: URL = Paths.installedDaemon) -> State {
        State(wanted: pinned(in: daemon), installed: installedVersion(in: daemon))
    }

    /// Why the last fetch didn't bring it (nil once it's there), for the windows that say so.
    static var lastProblem: String? {
        lock.lock(); defer { lock.unlock() }
        return problem
    }
    private static var problem: String?
    private static let lock = NSLock()
    private static func note(_ o: Outcome) { lock.lock(); problem = o.problem; lock.unlock() }

    /// An app update replaces the installed service with the bundled one, which has no SDK: the one
    /// fetched for the copy being replaced goes into the new copy first. Any version: if the new copy
    /// pins another, the fetch after the copy brings that one, and if it can't, this one keeps working.
    static func carryOver(from old: URL, to new: URL) {
        let fm = FileManager.default
        let src = packageDir(in: old), dst = packageDir(in: new)
        guard installedVersion(in: old) != nil, !fm.fileExists(atPath: dst.path) else { return }
        try? fm.createDirectory(at: dst.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? fm.copyItem(at: src, to: dst)
    }

    /// npm beside [node] (nodejs.org's, Homebrew's and nvm's Node.js all have it), run by that same
    /// Node.js: the npm-cli.js script `npm` links to, or `npm` itself.
    static func npmCommand(node: String) -> (exe: String, prefix: [String])? {
        let bin = (node as NSString).deletingLastPathComponent
        let npm = (bin as NSString).appendingPathComponent("npm")
        let fm = FileManager.default
        if fm.fileExists(atPath: npm) {
            let real = URL(fileURLWithPath: npm).resolvingSymlinksInPath().path
            return real.hasSuffix(".js") ? (node, [real]) : (npm, [])
        }
        // nodejs.org's layout, without its link.
        let cli = ((bin as NSString).deletingLastPathComponent as NSString).appendingPathComponent("lib/node_modules/npm/bin/npm-cli.js")
        return fm.fileExists(atPath: cli) ? (node, [cli]) : nil
    }

    /// `npm install` from the daemon's own lockfile: exact versions, each checked against the lock's
    /// integrity, without development or optional packages (the SDK's own Claude Code binaries) and
    /// running no install scripts, so the one package it adds is the SDK. Nothing is saved to
    /// package.json or the lock. npm's cache is Hypernova's (`<support>/npm-cache`), so a fetch after
    /// an update can come from it offline. Short retries: offline is said in seconds, not minutes.
    static func arguments(cache: URL) -> [String] {
        ["install", "--no-save", "--ignore-scripts", "--no-audit", "--no-fund", "--omit=dev", "--omit=optional",
         "--prefer-offline", "--update-notifier=false", "--fetch-retries=2", "--fetch-retry-mintimeout=2000",
         "--fetch-retry-maxtimeout=8000", "--fetch-timeout=60000", "--loglevel=error", "--cache", cache.path]
    }

    /// The app's own environment (the user's npm settings and proxy too), with that Node.js first on PATH.
    static func environment(node: String) -> [String: String] {
        var env = Shell.baseEnvironment
        env["PATH"] = (node as NSString).deletingLastPathComponent + ":" + (env["PATH"] ?? Shell.basePath)
        env["npm_config_update_notifier"] = "false"
        return env
    }

    static var cache: URL { Paths.support.appendingPathComponent("npm-cache", isDirectory: true) }

    /// The pinned SDK into [daemon], unless the right one is there. Never throws: the service goes on
    /// without it. Blocking parts run off the main thread.
    static func ensure(in daemon: URL, nodePath: String) async -> Outcome {
        let s = await Task.detached { state(daemon: daemon) }.value
        if s.current, let v = s.installed {
            note(.ready(v))
            return .ready(v)
        }
        return await fetch(into: daemon, nodePath: nodePath)
    }

    /// Runs npm in [daemon] now (Fetch again), and says what came of it.
    static func fetch(into daemon: URL, nodePath: String, timeout: TimeInterval = timeout) async -> Outcome {
        let o: Outcome = await Task.detached { run(daemon, nodePath, timeout) }.value
        note(o)
        return o
    }

    private static func run(_ daemon: URL, _ node: String, _ timeout: TimeInterval) -> Outcome {
        let fm = FileManager.default
        guard fm.fileExists(atPath: daemon.appendingPathComponent("package-lock.json").path) else {
            return .failed("This copy of the service has no package lock to fetch Claude Code’s SDK with. Rebuild the app with mac/build.sh.")
        }
        guard let npm = npmCommand(node: node) else {
            return .failed("There’s no npm next to the Node.js the service runs on (\(Paths.tilde(node))), so Claude Code’s SDK couldn’t be fetched.")
        }
        try? fm.createDirectory(at: cache, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let r = Shell.run(npm.exe, npm.prefix + arguments(cache: cache), timeout: timeout, env: environment(node: node), cwd: daemon)
        let after = state(daemon: daemon)
        if r.ok, after.current, let v = after.installed { return .ready(v) }
        return .failed(explain(r, after: after, timeout: timeout))
    }

    /// What stopped npm, in a sentence (its own text only as a short quote).
    static func explain(_ r: ShellResult, after: State, timeout: TimeInterval = timeout) -> String {
        if r.timedOut { return "npm didn’t finish within \(Int(timeout / 60)) minutes. Check the internet connection, then fetch it again." }
        let text = r.combined
        if ["ENOTFOUND", "ECONNREFUSED", "ETIMEDOUT", "EAI_AGAIN", "ECONNRESET", "ENETUNREACH", "EHOSTUNREACH", "ERR_SOCKET_TIMEOUT"].contains(where: text.contains) {
            return "Hypernova couldn’t reach npm: this Mac looks offline (or a proxy is in the way). Fetch it again once it’s online."
        }
        if text.contains("EINTEGRITY") {
            return "The download didn’t match the checksum in the service’s package lock, so npm threw it away. Fetch it again; if it keeps happening, your network may be altering downloads."
        }
        if text.contains("EACCES") || text.contains("EPERM") {
            return "npm wasn’t allowed to write in Hypernova’s folder (\(Paths.tilde(Paths.installedDaemon.path))). Fetch it again; if it keeps happening, check that folder’s owner."
        }
        if r.ok { return "npm finished, but Claude Code’s SDK\(after.wanted.map { " \($0)" } ?? "") isn’t in the service’s folder." }
        // npm's own words: "npm error code E404" and the line after it, kept short.
        let lines = text.split(separator: "\n").map { $0.replacingOccurrences(of: "npm error ", with: "").replacingOccurrences(of: "npm ERR! ", with: "").trimmingCharacters(in: .whitespaces) }
        let code = lines.first { $0.hasPrefix("code ") }.map { String($0.dropFirst(5)) }
        let said = lines.first { !$0.isEmpty && !$0.hasPrefix("code ") && !$0.hasPrefix("at ") && !$0.hasPrefix("A complete log") && !$0.hasPrefix("errno") }
        let quote = [code, said].compactMap { $0 }.joined(separator: ": ")
        return "npm stopped with an error (\(quote.isEmpty ? "exit \(r.status)" : String(quote.prefix(160)))). Fetch it again in a moment."
    }
}
