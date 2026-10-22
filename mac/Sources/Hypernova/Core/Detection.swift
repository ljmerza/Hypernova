import AppKit
import Foundation

// MARK: - Claude Code

struct ClaudeDetection: Equatable {
    var path: String?
    var version: String?
    var signedIn: Bool?
    var account: String?
    var foundBy: String?
}

enum ClaudeDetector {
    static func detect() -> ClaudeDetection {
        var d = ClaudeDetection()
        let fm = FileManager.default
        let local = Paths.localBin.appendingPathComponent("claude").path
        if fm.isExecutableFile(atPath: local) {
            d.path = local; d.foundBy = "~/.local/bin"
        } else {
            let r = Shell.loginShell("command -v claude")
            if r.ok, let line = r.stdout.split(separator: "\n").map(String.init).last(where: { $0.hasPrefix("/") }), fm.isExecutableFile(atPath: line) {
                d.path = line; d.foundBy = "your shell"
            } else if let p = AgentBins.find("claude") {
                d.path = p
                d.foundBy = p.contains("/.nvm/") ? "nvm (npm)" : p.contains("/.volta/") ? "Volta" : p.contains("/.bun/") ? "Bun"
                    : p.contains("/.npm-global/") ? "npm" : "Homebrew"
            }
        }
        if let p = d.path {
            let v = Shell.run(p, ["--version"], timeout: 8)
            if v.ok { d.version = v.stdout.split(separator: " ").first.map(String.init) ?? v.stdout }
        }
        (d.signedIn, d.account) = signInState()
        return d
    }

    /// Claude Code records the signed-in account in ~/.claude.json (`oauthAccount`), or an API key
    /// login as `primaryApiKey`. Neither file nor key → not signed in yet.
    /// An API key helper in Claude Code's settings counts too (it signs every request itself).
    static func signInState() -> (Bool?, String?) {
        let settings = Paths.home.appendingPathComponent(".claude/settings.json")
        if let d = try? Data(contentsOf: settings), let o = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
           let helper = o["apiKeyHelper"] as? String, !helper.isEmpty { return (true, nil) }
        guard let data = try? Data(contentsOf: Paths.claudeConfig) else { return (false, nil) }
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return (nil, nil) }
        if let acct = obj["oauthAccount"] as? [String: Any] {
            return (true, acct["emailAddress"] as? String)
        }
        if obj["primaryApiKey"] != nil { return (true, nil) }
        return (false, nil)
    }

    /// Opens Terminal running `claude`, so the user can sign in (a temporary .command file). Call it
    /// off the main thread: `open` can take a moment.
    static func openSignInTerminal(claudePath: String) throws {
        try AgentBins.openInTerminal(name: "Sign in to Claude Code", binary: claudePath, args: [],
                                     intro: "Hypernova: sign in to Claude Code below.\\n  When Claude Code shows its prompt you are signed in. Type /exit, close this window and go back to Hypernova.")
    }
}

/// Where an agent's command line can be, besides the login shell's PATH: Homebrew, /usr/local, ~/.local/bin,
/// npm's own prefix (~/.npm-global), Volta, Bun, and nvm's versions, newest first (nvm's PATH lives in
/// .zshrc, which `zsh -lc` doesn't read). The daemon looks in the same places (its util.ts).
enum AgentBins {
    static func dirs() -> [String] {
        let home = Paths.home
        var out = ["/opt/homebrew/bin", "/usr/local/bin", Paths.localBin.path,
                   home.appendingPathComponent(".npm-global/bin").path, home.appendingPathComponent(".volta/bin").path, home.appendingPathComponent(".bun/bin").path]
        let nvm = home.appendingPathComponent(".nvm/versions/node")
        if let ds = try? FileManager.default.contentsOfDirectory(atPath: nvm.path) {
            let sorted = ds.compactMap { d -> (NodeVersion, String)? in NodeVersion(d).map { ($0, d) } }.sorted { $0.0 > $1.0 }
            out += sorted.map { nvm.appendingPathComponent($0.1).appendingPathComponent("bin").path }
        }
        return out
    }

    /// The first executable [name] in those places.
    static func find(_ name: String) -> String? {
        dirs().map { ($0 as NSString).appendingPathComponent(name) }.first { FileManager.default.isExecutableFile(atPath: $0) }
    }

    /// Opens Terminal running [binary] with [args] (a temporary .command file), its own folder first on
    /// PATH: an npm-installed agent is `#!/usr/bin/env node`, and the node beside it is the one it was
    /// installed with. Blocks while `open` runs: call it off the main thread.
    static func openInTerminal(name: String, binary: String, args: [String], intro: String) throws {
        func q(_ s: String) -> String { "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'" }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Hypernova – \(name).command")
        let dir = (binary as NSString).deletingLastPathComponent
        let script = """
        #!/bin/zsh -l
        clear
        export PATH=\(q(dir)):"$PATH"
        printf '\\n  \(intro)\\n\\n'
        exec \(([binary] + args).map(q).joined(separator: " "))

        """
        try script.write(to: url, atomically: true, encoding: .utf8)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: url.path)
        let r = Shell.run("/usr/bin/open", ["-a", "Terminal", url.path], timeout: 10)
        if !r.ok { throw NSError(domain: "Hypernova", code: 1, userInfo: [NSLocalizedDescriptionKey: "Couldn't open Terminal: \(r.combined)"]) }
    }
}

// MARK: - Codex

/// OpenAI's Codex, looked for on this Mac by Setup before the background service runs (once it runs,
/// the daemon's word wins: it also checks the version against what Hypernova needs). Never installs
/// or updates anything.
enum CodexDetector {
    struct Found: Equatable { var path: String; var version: String? }

    /// The lines Setup, the Agents window and `--selftest` give for installing Codex (never run from
    /// here). Homebrew has Codex as a cask, which links /opt/homebrew/bin/codex.
    static let installNPM = "npm i -g @openai/codex"
    static let installBrew = "brew install --cask codex"

    static func find() -> Found? {
        let fm = FileManager.default
        var path: String?
        let r = Shell.loginShell("command -v codex")
        if r.ok, let line = r.stdout.split(separator: "\n").map(String.init).last(where: { $0.hasPrefix("/") }), fm.isExecutableFile(atPath: line) {
            path = line
        } else {
            path = AgentBins.find("codex")
        }
        guard let path else { return nil }
        // "codex-cli 0.163.0": the version is the last word.
        let v = Shell.run(path, ["--version"], timeout: 8)
        return Found(path: path, version: v.ok ? v.stdout.split(separator: " ").last.map(String.init) : nil)
    }

    /// Opens Terminal running `codex login`, so you sign Codex in (it opens your browser); a
    /// temporary .command file, as Claude Code's sign-in. Call it off the main thread.
    static func openSignInTerminal(codexPath: String) throws {
        try AgentBins.openInTerminal(name: "Sign in to Codex", binary: codexPath, args: ["login"],
                                     intro: "Hypernova: sign in to Codex below. Your browser opens for it.\\n  When it says you are signed in, close this window and go back to Hypernova.")
    }
}

// MARK: - Node.js

struct NodeVersion: Comparable, CustomStringConvertible {
    var major: Int, minor: Int, patch: Int
    init(major: Int, minor: Int, patch: Int) { self.major = major; self.minor = minor; self.patch = patch }
    init?(_ s: String) {
        let parts = s.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "v")).split(separator: ".").map { Int($0.prefix(while: \.isNumber)) ?? -1 }
        guard parts.count >= 2, parts[0] >= 0 else { return nil }
        major = parts[0]; minor = max(parts[1], 0); patch = parts.count > 2 ? max(parts[2], 0) : 0
    }
    static func < (a: Self, b: Self) -> Bool { (a.major, a.minor, a.patch) < (b.major, b.minor, b.patch) }
    var description: String { "\(major).\(minor).\(patch)" }

    /// The first Node that runs TypeScript directly (type stripping on by default); the daemon's
    /// package.json asks for `>=22.18`.
    static let minimum = NodeVersion(major: 22, minor: 18, patch: 0)
}

struct NodeCandidate: Equatable {
    var path: String
    var source: String
    var version: String?
    var ok: Bool {
        guard let version, let v = NodeVersion(version) else { return false }
        return v >= NodeVersion.minimum
    }
}

enum NodeDetector {
    /// Homebrew's and the official installer's links: they follow upgrades and stay put.
    static let stableLinks = ["/opt/homebrew/bin/node", "/usr/local/bin/node"]

    /// A Node.js that stays where it is: Hypernova's own download, or a link Homebrew or the official
    /// installer keeps current. A version manager's (nvm's ~/.nvm/versions/node/v25.9.0/bin/node)
    /// goes away when that version is uninstalled, and the service with it, so it's the last resort.
    static func isStable(_ path: String) -> Bool {
        path.hasPrefix(Paths.nodeDir.path + "/") || stableLinks.contains(path)
    }

    /// Candidates in priority order: Hypernova's own download (newest first), Homebrew, /usr/local,
    /// the recorded path, then the newest nvm Node.
    static func candidates() -> [NodeCandidate] {
        let fm = FileManager.default
        var list: [(String, String)] = []
        if let dirs = try? fm.contentsOfDirectory(atPath: Paths.nodeDir.path) {
            let sorted = dirs.compactMap { d -> (NodeVersion, String)? in d.hasPrefix("v") ? NodeVersion(d).map { ($0, d) } : nil }.sorted { $0.0 > $1.0 }
            for (_, d) in sorted { list.append((Paths.nodeDir.appendingPathComponent(d).appendingPathComponent("bin/node").path, "downloaded by Hypernova")) }
        }
        list.append(("/opt/homebrew/bin/node", "Homebrew"))
        list.append(("/usr/local/bin/node", "/usr/local"))
        if let rec = try? String(contentsOf: Paths.nodePathRecord, encoding: .utf8) {
            let p = rec.trimmingCharacters(in: .whitespacesAndNewlines)
            if !p.isEmpty { list.append((p, p.contains("/.nvm/") ? "nvm" : "recorded")) }
        }
        let nvm = Paths.home.appendingPathComponent(".nvm/versions/node")
        if let dirs = try? fm.contentsOfDirectory(atPath: nvm.path) {
            let sorted = dirs.compactMap { d -> (NodeVersion, String)? in NodeVersion(d).map { ($0, d) } }.sorted { $0.0 > $1.0 }
            if let newest = sorted.first { list.append((nvm.appendingPathComponent(newest.1).appendingPathComponent("bin/node").path, "nvm")) }
        }
        var seen = Set<String>()
        var out: [NodeCandidate] = []
        for (p, src) in list where !seen.contains(p) {
            seen.insert(p)
            guard let c = candidate(p, source: src) else { continue }
            out.append(c)
        }
        return out
    }

    /// One Node.js, if it's there and starts.
    static func candidate(_ path: String, source: String) -> NodeCandidate? {
        guard FileManager.default.isExecutableFile(atPath: path) else { return nil }
        let r = Shell.run(path, ["--version"], timeout: 6)
        return NodeCandidate(path: path, source: source, version: r.ok ? r.stdout : nil)
    }

    static func find() -> (chosen: NodeCandidate?, all: [NodeCandidate]) {
        let all = candidates()
        return (all.first(where: \.ok), all)
    }

    /// What the background service runs on: the Node.js it runs on now, while that's still there and
    /// new enough (an update never moves a working install); else a stable one. Nil: Hypernova
    /// downloads its own (SetupActions.installService), and only if that fails takes a version
    /// manager's ([fallback]).
    static func forService() -> (use: NodeCandidate?, fallback: NodeCandidate?, all: [NodeCandidate]) {
        let all = candidates()
        if let current = ServiceInstaller.plistNodePath(), let c = candidate(current, source: isStable(current) ? "the service's" : "the service's (nvm)"), c.ok {
            return (c, nil, all)
        }
        return (all.first { $0.ok && isStable($0.path) }, all.first(where: \.ok), all)
    }

    static func record(_ path: String) throws {
        try Paths.ensureSupport()
        try (path + "\n").write(to: Paths.nodePathRecord, atomically: true, encoding: .utf8)
    }
}

// MARK: - Tailscale (local presence only; the daemon owns the real status)

enum TailscaleDetector {
    static let bundleIDs = ["io.tailscale.ipn.macsys", "io.tailscale.ipn.macos"]

    static var appURL: URL? {
        for id in bundleIDs { if let u = NSWorkspace.shared.urlForApplication(withBundleIdentifier: id) { return u } }
        let fallback = URL(fileURLWithPath: "/Applications/Tailscale.app")
        return FileManager.default.fileExists(atPath: fallback.path) ? fallback : nil
    }

    static var cliPath: String? {
        let fm = FileManager.default
        for p in ["/usr/local/bin/tailscale", "/opt/homebrew/bin/tailscale"] where fm.isExecutableFile(atPath: p) { return p }
        if let app = appURL {
            let p = app.appendingPathComponent("Contents/MacOS/Tailscale").path
            if fm.isExecutableFile(atPath: p) { return p }
        }
        return nil
    }

    static func openApp() {
        guard let url = appURL else { NSWorkspace.shared.open(Endpoints.tailscaleDownload); return }
        NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration())
    }
}
