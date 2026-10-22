import Foundation

/// `Hypernova --selftest`: runs every detection the setup wizard runs and prints what each step
/// would do, without changing anything on this Mac. The steps are Setup's, in its order and words.
enum SelfTest {
    static func run() -> Int32 {
        func p(_ s: String = "") { print(s); fflush(stdout) }
        func step(_ s: SetupModel.Step, _ note: String = "") { p(); p("[\(s.rawValue + 1)/\(SetupModel.Step.allCases.count)] \(s.title)\(note)") }
        func part(_ title: String) { p("  \(title)") }
        func item(_ k: String, _ v: String) { p("    \(k.padding(toLength: 14, withPad: " ", startingAt: 0)) \(v)") }
        func would(_ s: String) { p("    → would: \(s)") }

        // The daemon the ports point at is this copy's own only for the real install or a test copy
        // isolated by the full rule (Paths.isolatedTestCopy). Any other test copy may be pointed at
        // the owner's daemon (on his ports, or with his admin key), so it asks no daemon anything
        // and says so, rather than report his install as its own.
        let ownDaemon = !Paths.testCopy || Paths.isolatedTestCopy
        let notAsked = "not asked: this test copy isn't isolated, so the daemon on port \(Endpoints.apiPort) may be the real one"

        let info = Bundle.main.infoDictionary
        p("Hypernova self-test (dry run: nothing on this Mac is changed)")
        item("app", "\(info?["CFBundleShortVersionString"] as? String ?? "dev") (\(info?["CFBundleVersion"] as? String ?? "-")) at \(Bundle.main.bundlePath)")
        item("fonts", Fonts.register() ? "Manrope + JetBrains Mono registered" : "NOT registered (falls back to system fonts)")
        item("support", Paths.tilde(Paths.support.path) + (FileManager.default.fileExists(atPath: Paths.support.path) ? " (exists)" : " (not created yet)"))
        if Paths.testCopy {
            item("test copy", Paths.serviceBlocked ?? "its own service \(Paths.label) on ports \(Endpoints.apiPort)/\(Endpoints.localPort), log \(Paths.tilde(Paths.daemonLog.path)); no login item, Claude Code's settings and Tailscale left alone")
        }
        if let bd = Paths.bundledDaemon {
            let stamp = (try? String(contentsOf: bd.appendingPathComponent(Paths.buildStampName), encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "no stamp"
            item("bundled daemon", "\(bd.path) [\(stamp)]")
        } else {
            item("bundled daemon", "MISSING (run from dist/Hypernova.app built by mac/build.sh)")
        }

        step(.claude)
        let claude = ClaudeDetector.detect()
        if let path = claude.path {
            item("found", "\(path) via \(claude.foundBy ?? "?")")
            item("version", claude.version ?? "unknown (--version failed)")
            item("signed in", claude.signedIn == true ? "yes\(claude.account.map { " (\($0))" } ?? "")" : claude.signedIn == false ? "no" : "unknown")
            if claude.signedIn == false { would("open Terminal with a temporary .command file running `\(path)` so you can sign in, then re-check") }
            else { would("nothing (ready)") }
        } else {
            item("found", "no (checked ~/.local/bin/claude, `zsh -lc 'command -v claude'`, Homebrew, /usr/local/bin, npm's global prefix, Volta, Bun, nvm)")
            would("run `/bin/bash -c \"curl -fsSL \(Endpoints.claudeInstaller) | bash\"` with a live log, then sign in (through the service once it runs, else Terminal running `claude`)")
            if let f = CodexDetector.find() {
                would("offer \"Use Codex only\" beside Install Claude Code (Codex \(f.version ?? "of unknown version") at \(f.path))")
            } else {
                would("offer Codex instead, as lines to run in a terminal: `\(CodexDetector.installNPM)` or `\(CodexDetector.installBrew)`, then `codex login`")
            }
        }

        // Node.js is the background service's first part (it runs on a stable Node.js, or Hypernova's own).
        step(.service)
        part("Node.js (needs \(NodeVersion.minimum) or newer)")
        let node = NodeDetector.forService()
        if node.all.isEmpty { item("candidates", "none found") }
        for c in node.all {
            let tag = c.path == node.use?.path ? "chosen" : "candidate"
            item(tag, "\(c.path) \(c.version ?? "?") [\(c.source)]\(c.ok ? "" : " too old")\(NodeDetector.isStable(c.path) ? "" : " (versioned: last resort)")")
        }
        if let current = ServiceInstaller.plistNodePath() { item("service runs", current + (ServiceInstaller.missingNode() != nil ? " (GONE: Repair rewrites the service)" : "")) }
        if let use = node.use {
            would("run the service on \(use.path)")
        } else {
            let sem = DispatchSemaphore(value: 0)
            final class Box: @unchecked Sendable { var text = "" }
            let box = Box()
            Task.detached {
                do {
                    let r = try await NodeInstaller.latestLTS()
                    let sha = try await NodeInstaller.expectedSHA256(for: r)
                    box.text = "download \(r.url.absoluteString) (\(r.ltsName) LTS), verify SHA-256 \(sha.prefix(16))…, unpack to \(Paths.tilde(Paths.nodeDir.path))/\(r.version)/"
                } catch { box.text = "download the Node.js LTS from nodejs.org (lookup failed now: \(error.localizedDescription))" }
                sem.signal()
            }
            _ = sem.wait(timeout: .now() + 20)
            would(box.text + (node.fallback.map { "; if that fails, \($0.path)" } ?? ""))
        }
        let nodeForPlist = node.use?.path ?? Paths.nodeDir.appendingPathComponent("<lts>/bin/node").path

        part("The service")
        let health = ownDaemon ? DaemonClient.shared.healthSync() : nil
        let svc = ServiceInstaller.detect(health: health)
        item("launch agent", svc.plistExists ? "\(Paths.tilde(Paths.launchAgent.path)) exists" : "not installed")
        item("launchd", svc.loadedInLaunchd ? "loaded as \(Paths.serviceTarget)" : "not loaded")
        item("daemon copy", svc.installedDaemon ? Paths.tilde(Paths.installedDaemon.path) + (svc.updateAvailable ? " (older than the bundled one)" : "") : "not installed")
        item("health", !ownDaemon ? notAsked : health.map { "ok: \($0.name ?? "?") protocol \($0.protocol.map(String.init) ?? "?")" } ?? "not answering on \(Endpoints.health.absoluteString)")
        let listener = ServiceInstaller.listenerPID(port: Endpoints.apiPort), managed = ServiceInstaller.launchdPID()
        item("port \(Endpoints.apiPort)", listener.map { "held by pid \($0)" + ($0 == managed ? " (the background service)" : " (NOT the background service: install would stop at the port check until it quits)") } ?? "free")
        if let why = Paths.serviceBlocked {
            // This copy refuses to install a service (ServiceInstaller.install), so it says why.
            would("nothing (\(why))")
        } else {
            would("copy \(Paths.bundledDaemon?.path ?? "<bundled daemon>") → \(Paths.tilde(Paths.installedDaemon.path)) (atomic replace)")
            would("write \(Paths.tilde(Paths.launchAgent.path)):")
            for line in ServiceInstaller.plistXML(nodePath: nodeForPlist).split(separator: "\n") { p("        \(line)") }
            for c in ServiceInstaller.launchctlCommands { would(c) }
            would("poll \(Endpoints.health.absoluteString) until {ok: true} (40 s), showing the log tail on failure")
            // SetupActions.installService: only a first install, and never a test copy's.
            would(Paths.testCopy ? "nothing about Open at Login (a test copy leaves the login item to the real install)"
                                 : "on a first install, add Hypernova to Open at Login (macOS may show a Background Items notice)")
        }

        // The steps after this one need the daemon's local API.
        var status: LocalStatus?
        var statusError: String?
        if health != nil {
            let sem = DispatchSemaphore(value: 0)
            final class Box: @unchecked Sendable { var s: LocalStatus?; var e: String? }
            let box = Box()
            Task.detached {
                do { box.s = try await DaemonClient.shared.status() } catch { box.e = error.localizedDescription }
                sem.signal()
            }
            _ = sem.wait(timeout: .now() + 10)
            status = box.s; statusError = box.e
        }

        step(.connect)
        // A test copy refuses this step (SetupActions.connectClaudeCode), so its dry run says so.
        let leaveClaudeCode = "nothing (a test copy leaves Claude Code's settings and ~/.zshrc to the real install)"
        if claude.path == nil {
            // With Codex alone Setup skips it; otherwise it waits for Claude Code.
            would("nothing until Claude Code is installed (with Codex alone, Setup skips this step)")
        } else if !ownDaemon {
            item("status", notAsked)
            would(leaveClaudeCode)
        } else if let s = status {
            item("hooks", s.hooks?.installed == true ? "installed" : "not installed")
            item("status line", s.statusLine?.installed == true ? "installed" : "not installed")
            item("shell", s.shell.map { $0.supported == false ? "not zsh: no shell functions" : $0.installed == true ? "the claude function is in ~/.zshrc" : "no claude function yet" } ?? "unknown")
            would(Paths.testCopy ? leaveClaudeCode : s.hooks?.installed == true ? "nothing (connected)" : "POST \(Endpoints.local.absoluteString)/local/integration/install (Bearer admin token from \(Paths.tilde(Paths.adminToken.path)))")
        } else {
            item("status", statusError ?? "daemon not running; can't read /local/status")
            would(Paths.testCopy ? leaveClaudeCode : "POST \(Endpoints.local.absoluteString)/local/integration/install once the service runs")
        }

        step(.tailscale, " (optional on the same Wi-Fi)")
        item("app", TailscaleDetector.appURL?.path ?? "not installed")
        item("cli", TailscaleDetector.cliPath ?? "not found")
        if let t = status?.tsnet {
            item("built in", (t.mode ?? "unknown") + (t.enabled == false ? ", turned off" : "") + (t.available == false ? ", no helper in this build" : ""))
        }
        if let ts = status?.tailscale {
            item("daemon says", "installed=\(ts.installed.map(String.init) ?? "?") running=\(ts.running.map(String.init) ?? "?") loggedIn=\(ts.loggedIn.map(String.init) ?? "?") https=\(ts.httpsEnabled.map(String.init) ?? "?") serve=\(ts.serveOn.map(String.init) ?? "?")")
            if let u = ts.baseURL { item("url", u) }
            if let k = ts.keyExpiry { item("key expiry", k.date.formatted()) }
            would(Paths.testCopy ? "nothing (a test copy leaves phone access to the real install)" : TailscaleStepLogic.action(ts, tsnet: status?.tsnet).dryRun)
        } else if Paths.testCopy {
            would("nothing (a test copy leaves phone access to the real install)")
        } else {
            would(TailscaleDetector.appURL == nil ? "sign in Hypernova's built-in node (without its helper: open \(Endpoints.tailscaleDownload.absoluteString))" : "read /local/status → tailscale, then POST /local/tailscale/serve")
        }

        // Voice (optional): Soniox with your own key, or Hyperscribe Companion. Nothing is asked here.
        step(.voice, " (optional)")
        if !ownDaemon {
            item("status", notAsked)
        } else if health != nil {
            let sem = DispatchSemaphore(value: 0)
            final class Box: @unchecked Sendable { var s: SttState? }
            let box = Box()
            Task.detached {
                box.s = try? await DaemonClient.shared.request("GET", "/v1/stt", as: SttState.self)
                sem.signal()
            }
            _ = sem.wait(timeout: .now() + 10)
            if let s = box.s {
                item("speech", s.ready ? "ready (\(s.provider ?? "?"))" : s.provider.map { "\($0), not ready" + (s.error.map { ": " + $0 } ?? "") } ?? "off")
            } else {
                item("speech", "not read")
            }
        } else {
            item("status", "daemon not running")
        }
        would("nothing until you choose: connect Soniox with your own key, use Hyperscribe Companion, or Not now")

        // Codex (optional, asked before the phone): only what the daemon found. Hypernova never installs it.
        step(.codex, " (optional)")
        if !ownDaemon {
            item("status", notAsked)
        } else if let s = status {
            if let c = s.codex {
                item("found", "\(c.path ?? "?") \(c.version ?? "unknown version")\(c.tooOld ? " (too old: needs \(c.min ?? "a newer one") or later; `codex update`)" : "")")
                item("for hypernova", c.enabled ? "on, \(c.connected ? "connected to its app-server" : "not connected")" : "off")
                if c.enabled {
                    item("account", c.account.map { $0.signedIn == true ? "signed in (\([$0.type, $0.planType].compactMap { $0 }.joined(separator: ", ")))" : "signed out (`codex login`)" } ?? "not read yet")
                    if let u = c.usage {
                        let w = [u.fiveHour.map { "5-hour \(Int($0.pct.rounded()))%" }, u.sevenDay.map { "week \(Int($0.pct.rounded()))%" }].compactMap { $0 }
                        item("limits", (w.isEmpty ? "no windows" : w.joined(separator: ", ")) + (u.limited ? " (at a limit)" : ""))
                    }
                }
                if let p = c.problem { item("problem", p) }
            } else {
                item("found", "no (or a daemon older than Codex); to use it: `\(CodexDetector.installNPM)` or `\(CodexDetector.installBrew)`, then `codex login`")
            }
        } else {
            item("status", statusError ?? "daemon not running; can't read /local/status")
        }
        // Before the service runs, Setup looks for Codex itself (the Claude Code step's "Use Codex only").
        if let f = CodexDetector.find() { item("on this Mac", "\(f.path) \(f.version ?? "unknown version") (Setup's own look)") }
        else { item("on this Mac", "not found by Setup's own look (login shell, Homebrew, ~/.local/bin, npm's global prefix, Volta, Bun, nvm)") }
        // Which agent leads (docs/PROTOCOL.md, "Codex"): Claude Code while it's here, else Codex.
        let rule = AgentRule(claude: claude.path != nil, codex: ownDaemon ? status?.codex : nil)
        item("leading agent", (rule.leading.map { $0 == .claude ? "Claude Code" : "Codex" } ?? "none yet")
             + (rule.both ? " (Codex in use beside it: meters named CLAUDE and CODEX)" : rule.codexOnly ? " (Codex alone: Claude Code's steps skipped)" : ""))
        if claude.path == nil, rule.codexInstalled || CodexDetector.find() != nil {
            would("offer \"Use Codex only\" on the Claude Code step: skip it and Connect, turn Codex on, nothing installed")
        }
        would("nothing (informational: Setup's switch posts /local/settings {codex}; turned on, it adds the codex function to ~/.zshrc)")

        step(.phone)
        if let s = status {
            item("paired", "\(s.devices?.count ?? 0) device(s)")
            if let u = s.downloadUrl { item("get the app", "QR → \(u)" + (s.apk == nil ? " (no APK in this build: the page has nothing to give)" : "")) }
        }
        would("show a QR of the download page, then POST /local/pair/start and show a QR of its link (refreshed before expiresAt), polling GET /local/devices every 2 s for a new phone")

        p()
        p("Self-test finished. Nothing was changed.")
        return 0
    }
}
