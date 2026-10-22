import Foundation
import ServiceManagement

/// What each setup button does, in one place: the wizard, the menu and
/// `Hypernova --run-step <name>` all call these, so the CLI tests the exact same code path.
enum SetupActions {
    static func err(_ m: String) -> NSError { NSError(domain: "Hypernova", code: 1, userInfo: [NSLocalizedDescriptionKey: m]) }

    /// The background service: install, update or repair it. Its Node.js first (NodeDetector.forService):
    /// the one it runs on while that's still there, else a stable one (Hypernova's own download,
    /// Homebrew's, the official installer's); with none, Hypernova downloads the Node.js LTS into its
    /// own folder, and only if that fails does it take a version manager's.
    static func installService(progress: @escaping @Sendable (ServiceInstaller.Progress) -> Void = { _ in }) async throws -> Health {
        let pick = await Task.detached { NodeDetector.forService() }.value
        var nodePath = pick.use?.path
        if nodePath == nil {
            progress(.gettingNode(nil))
            do {
                let path = try await NodeInstaller.install { phase in
                    if case .downloading(let f, _, _) = phase { progress(.gettingNode(f)) }
                }
                nodePath = path
            } catch {
                guard let fallback = pick.fallback else {
                    throw err("Hypernova couldn't get Node.js for the background service. \(error.localizedDescription)")
                }
                // It works until that version is uninstalled; Repair gets another then.
                nodePath = fallback.path
            }
        }
        guard let nodePath else { throw err("Hypernova needs Node.js \(NodeVersion.minimum) or newer.") }
        // Only a first install turns Open at login on. An update or a restart keeps whatever the
        // owner chose since.
        let firstInstall = !FileManager.default.fileExists(atPath: Paths.launchAgent.path)
        let health = try await ServiceInstaller.install(nodePath: nodePath, progress: progress)
        if firstInstall && !Paths.testCopy {
            // The menu bar app comes back after a restart too (only when running from an .app bundle).
            if Bundle.main.bundleURL.pathExtension == "app", SMAppService.mainApp.status != .enabled {
                try? SMAppService.mainApp.register()
            }
            Defaults.store.set(true, forKey: Defaults.loginItemWanted)
        }
        return health
    }

    /// Fetch again: Claude Code's SDK into the installed service now (ClaudeSDK), with the Node.js the
    /// service runs on. The service keeps running meanwhile and uses it once it's there.
    static func fetchClaudeSDK() async -> ClaudeSDK.Outcome {
        // A test copy that isn't isolated would write into the owner's installed service.
        if let why = Paths.serviceBlocked { return .failed(why) }
        guard FileManager.default.fileExists(atPath: Paths.daemonMain.path) else {
            return .failed("The background service isn’t installed yet. Start it in Setup first: it fetches Claude Code’s SDK on the way.")
        }
        let node: String? = await Task.detached {
            if let p = ServiceInstaller.plistNodePath(), FileManager.default.isExecutableFile(atPath: p) { return p }
            return NodeDetector.forService().use?.path
        }.value
        guard let node else { return .failed("There’s no Node.js for the service to fetch Claude Code’s SDK with. Repair the service in Setup first.") }
        return await ClaudeSDK.fetch(into: Paths.installedDaemon, nodePath: node)
    }

    /// Step d: hooks and the status line tap, through the daemon. Returns the fresh status.
    static func connectClaudeCode() async throws -> LocalStatus {
        // The hooks and the status line live in Claude Code's own settings (and the shell function
        // in ~/.zshrc), which a test daemon would point at itself, away from the owner's.
        if Paths.testCopy { throw err(testCopyLeavesClaudeCode) }
        try await DaemonClient.shared.installIntegration()
        return try await DaemonClient.shared.status()
    }

    /// Step e: `tailscale serve` for the phone API, through the daemon.
    static func turnOnPhoneAccess() async throws -> TailscaleStatus {
        // Tailscale's serve routes are the Mac's, not the daemon's: a test daemon's would stay in
        // the owner's tailnet after the test.
        if Paths.testCopy { throw err(testCopyLeavesTailscale) }
        let ts = try await DaemonClient.shared.tailscaleServe()
        if ts.serveOn != true || ts.baseURL == nil {
            throw err("Tailscale didn't turn phone access on. " + (ts.httpsEnabled == false ? "HTTPS certificates are still off for your tailnet." : "Check that Tailscale is running and signed in, then try again."))
        }
        return ts
    }

    static let testCopyLeavesClaudeCode = "This is a test copy (a HYPERNOVA_ variable is set), so it leaves Claude Code's settings and ~/.zshrc to your real Hypernova."
    static let testCopyLeavesTailscale = "This is a test copy (a HYPERNOVA_ variable is set), so it leaves phone access to your real Hypernova: a test daemon's route would stay in your tailnet."

    /// The menu's Uninstall: integration off (through the daemon), LaunchAgent and daemon copy
    /// removed, login item removed, and optionally all data. Returns notes about anything left behind.
    /// A test copy removes only its own service and folder: the integration and the login item
    /// belong to the owner's install.
    static func uninstall(deleteData: Bool, inApp: Bool = false) async -> [String] {
        var notes: [String] = []
        if let why = Paths.serviceBlocked { return [why] }
        let serviceInstalled = FileManager.default.fileExists(atPath: Paths.launchAgent.path)
        if !Paths.testCopy {
            // Only the daemon knows exactly what it added to Claude Code, so make sure it's up.
            if await DaemonClient.shared.health() == nil, serviceInstalled {
                _ = await Task.detached { ServiceInstaller.kickstart() }.value
                _ = await DaemonClient.shared.waitForHealth(seconds: 12)
            }
            do { try await DaemonClient.shared.uninstallIntegration() } catch {
                notes.append("Hypernova couldn't remove its hooks from Claude Code (\(error.localizedDescription)). They point at Hypernova's local address, so they do nothing once the service is gone; install and uninstall again to remove them cleanly.")
            }
            // The Mac's name under hypernova.observer: its records leave DNS now rather than in 90 days.
            do {
                let r = try await DaemonClient.shared.releaseName()
                if r.name != nil, r.released != true {
                    notes.append("Hypernova couldn't give up this Mac's private name (\(r.name ?? "")) under hypernova.observer just now. Its records are removed by themselves after 90 days unused.")
                }
            } catch DaemonError.server(let status, _, _) where status == 404 {
                // A daemon from before private names: nothing to give up.
            } catch {
                notes.append("Hypernova couldn't confirm that this Mac's private name under hypernova.observer was given up (\(error.localizedDescription)). Its records are removed by themselves after 90 days unused.")
            }
        }
        // Backpack's permission, while the service still runs: it puts back the SleepDisabled
        // Backpack found before the rule goes (a test copy leaves the owner's rule alone).
        if BackpackSetup.blocked == nil, BackpackSetup.installed {
            do { try await BackpackSetup.uninstall(inApp: inApp) } catch {
                notes.append("Backpack mode's permission is still in \(BackpackSetup.rulePath) (it lets this account turn sleep off and back on, nothing else). Remove it with: sudo rm \(BackpackSetup.rulePath)")
            }
        }
        await Task.detached { ServiceInstaller.remove() }.value
        if FileManager.default.fileExists(atPath: Paths.launchAgent.path) {
            notes.append("The launch agent at \(Paths.tilde(Paths.launchAgent.path)) couldn't be deleted.")
        }
        if !Paths.testCopy, SMAppService.mainApp.status == .enabled { try? await SMAppService.mainApp.unregister() }
        Defaults.store.set(false, forKey: Defaults.loginItemWanted)
        if deleteData {
            try? FileManager.default.removeItem(at: Paths.support)
            try? FileManager.default.removeItem(at: Paths.logsDir)
        }
        Defaults.store.removeObject(forKey: Defaults.setupCompleted)
        Defaults.store.removeObject(forKey: Defaults.voiceAsked)
        Defaults.store.removeObject(forKey: Defaults.codexAsked)
        Defaults.store.removeObject(forKey: Defaults.claudeSkipped)
        return notes
    }
}

/// `Hypernova --run-step <service|integration|tailscale|uninstall-service|backpack|uninstall-backpack>`:
/// runs one wizard button non-interactively, prints the outcome, exits non-zero on failure.
/// `backpack` and `uninstall-backpack` ask for the admin password (macOS's prompt, through osascript).
enum RunStep {
    static let names = ["service", "integration", "tailscale", "uninstall-service", "backpack", "uninstall-backpack"]

    static func run(_ name: String) -> Int32 {
        @Sendable func p(_ s: String) { print(s); fflush(stdout) }
        final class Box: @unchecked Sendable { var code: Int32 = 1 }
        let box = Box(), sem = DispatchSemaphore(value: 0)
        Task.detached {
            if ["service", "uninstall-service"].contains(name), let why = Paths.serviceBlocked {
                p("FAILED: \(why)")
                box.code = 1; sem.signal(); return
            }
            if ["backpack", "uninstall-backpack"].contains(name), let why = BackpackSetup.blocked {
                p("FAILED: \(why)")
                box.code = 1; sem.signal(); return
            }
            if ["integration", "tailscale"].contains(name), Paths.testCopy {
                p("FAILED: \(name == "integration" ? SetupActions.testCopyLeavesClaudeCode : SetupActions.testCopyLeavesTailscale)")
                box.code = 1; sem.signal(); return
            }
            do {
                switch name {
                case "service":
                    if Paths.testCopy { p("Test copy: its own service, folder and log (\(Paths.tilde(Paths.support.path)))") }
                    p("Installing the background service…")
                    p("  label   \(Paths.label)")
                    p("  plist   \(Paths.launchAgent.path)")
                    p("  daemon  \(Paths.installedDaemon.path)")
                    let h = try await SetupActions.installService { step in
                        switch step {
                        case .gettingNode(let f): if f == nil { p("  getting Node.js (no stable one on this Mac)") }
                        case .copying: p("  copying the daemon")
                        case .fetchingSDK: p("  fetching Claude Code’s SDK from npm (\(ClaudeSDK.downloadSize))")
                        case .writing: p("  writing the launch agent")
                        case .starting: p("  launchctl bootout + bootstrap")
                        case .waiting: p("  waiting for \(Endpoints.health.absoluteString)")
                        }
                    }
                    p("OK: running as \(h.name ?? "?") (protocol \(h.protocol.map(String.init) ?? "?"))")
                    // Never fatal: said, and the service runs without it.
                    let sdk = ClaudeSDK.state()
                    p(sdk.current ? "  Claude Code’s SDK \(sdk.installed ?? "") is there" : "  note: Claude Code’s SDK isn’t there: \(ClaudeSDK.lastProblem ?? "not fetched")")
                case "integration":
                    p("Connecting Claude Code (POST /local/integration/install)…")
                    let s = try await SetupActions.connectClaudeCode()
                    p("\(s.hooks?.installed == true ? "OK" : "FAILED"): hooks \(s.hooks?.installed == true ? "installed" : "NOT installed"), status line \(s.statusLine?.installed == true ? "installed" : "NOT installed")")
                    if s.hooks?.installed != true { box.code = 1; sem.signal(); return }
                case "tailscale":
                    p("Turning on phone access (POST /local/tailscale/serve)…")
                    let ts = try await SetupActions.turnOnPhoneAccess()
                    p("OK: \(ts.baseURL ?? "?")")
                    if let d = TailscaleStepLogic.expiringSoon(ts, tsnet: nil) { p("Note: this Mac's Tailscale key expires \(d.formatted()); disable key expiry at \(Endpoints.tailscaleMachines.absoluteString)") }
                case "backpack":
                    p("Setting up Backpack mode: a sudoers rule for exactly pmset -a disablesleep 0 and 1…")
                    p("  rule    \(BackpackSetup.rulePath)")
                    if BackpackSetup.installed { p("OK: already set up"); break }
                    try await BackpackSetup.install(inApp: false)
                    p("OK: set up (choose Backpack in the menu bar, on the phone or on the glasses)")
                case "uninstall-backpack":
                    p("Removing Backpack mode's rule (\(BackpackSetup.rulePath))…")
                    if !BackpackSetup.installed { p("OK: not set up"); break }
                    if let r = BackpackSetup.pendingRestore() { p("  SleepDisabled back to \(r), as Backpack found it") }
                    try await BackpackSetup.uninstall(inApp: false)
                    p("OK: removed")
                case "uninstall-service":
                    p(Paths.testCopy ? "Uninstalling this test copy's service (launch agent and daemon copy; data kept)…"
                                     : "Uninstalling (integration, launch agent, daemon copy, login item; data kept)…")
                    let notes = await SetupActions.uninstall(deleteData: false)
                    for n in notes { p("  note: \(n)") }
                    p(notes.isEmpty ? "OK: uninstalled" : "Done with notes")
                    if !notes.isEmpty { box.code = 2; sem.signal(); return }
                default:
                    p("Unknown step '\(name)'. Use one of: \(names.joined(separator: ", "))")
                    box.code = 64; sem.signal(); return
                }
                box.code = 0
            } catch {
                p("FAILED: \(error.localizedDescription)")
                box.code = 1
            }
            sem.signal()
        }
        sem.wait()
        return box.code
    }
}
