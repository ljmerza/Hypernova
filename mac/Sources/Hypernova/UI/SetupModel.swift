import AppKit
import Combine
import ServiceManagement
import SwiftUI

/// The setup wizard's state. Each step detects where things stand, explains, and offers one
/// primary action; the actions themselves live in SetupActions (shared with `--run-step`).
@MainActor
final class SetupModel: ObservableObject {
    static let shared = SetupModel()

    enum Step: Int, CaseIterable, Identifiable {
        case claude, service, connect, tailscale, voice, codex, phone
        var id: Int { rawValue }
        var title: String {
            switch self {
            case .claude: return "Claude Code"
            case .service: return "Background service"
            case .connect: return "Connect Claude Code"
            case .tailscale: return "Tailscale"
            case .voice: return "Voice"
            case .codex: return "Codex"
            case .phone: return "Your phone"
            }
        }
    }

    @Published var current: Step = .claude
    @Published private(set) var detected = false

    // a. Claude Code
    enum ClaudePhase: Equatable { case checking, missing, installing, installFailed(String), needsSignIn, ready }
    @Published var claudePhase: ClaudePhase = .checking
    @Published var claude = ClaudeDetection()
    @Published var claudeLog: [String] = []
    @Published var signInOpened = false
    @Published var claudeError: String?
    /// Signing in through the background service, here in the window (no Terminal): the page to open,
    /// then the code it shows pasted back. Only while the service runs; before, Terminal.
    @Published var signInCode = ""
    @Published var signInBusy = false
    /// The sign-in Setup started (the popover says "in Setup", not "on your phone").
    @Published private(set) var signingInHere = false
    /// The service's sign-in, while one runs.
    var serviceLogin: Account.Login? { status?.account?.login }
    private var installer: StreamingProcess?

    // b. Node.js, the background service's first part (owner's audit M3): the Node.js it runs on
    // now or a stable one; nil means Hypernova downloads its own while it installs the service.
    @Published var node: NodeCandidate?
    @Published var nodeChecked = false
    @Published var nodeRelease: NodeInstaller.Release?

    // c. Background service
    enum ServicePhase: Equatable { case checking, notInstalled, unmanaged, notAnswering, needsUpdate, installing(ServiceInstaller.Progress), failed(String), running }
    @Published var servicePhase: ServicePhase = .checking
    @Published var serviceLogTail: [String] = []
    @Published var health: Health?

    // d–f read the daemon's status.
    @Published var status: LocalStatus?
    @Published var connectBusy = false
    @Published var connectError: String?
    @Published var serveBusy = false
    @Published var serveError: String?
    @Published var tailscaleChecking = false

    // f. Phone
    enum PhonePhase: Equatable { case getApp, pair, paired(String) }
    @Published var phonePhase: PhonePhase = .getApp
    @Published var pair: PairStart?
    @Published var pairError: String?
    @Published var pairBusy = false
    private var knownDeviceIDs: Set<String>?

    // g. Codex (optional): found or not, never installed from here; its switch is the daemon's setting.
    @Published var codexBusy = false
    @Published var codexError: String?
    /// What the daemon says about Codex (nil: not installed here).
    var codex: Codex? { status?.codex }
    /// Codex as Setup found it on this Mac itself, for the Claude Code step before the background
    /// service runs (once it runs, the daemon's word wins).
    @Published var codexFound: CodexDetector.Found?
    /// "Use Codex only" was chosen before the service ran: Codex is turned on once the service answers.
    private var codexOnPending = false

    /// Voice (Soniox, or the Companion), shared with the Lens guide. The rail reads it, so its changes redraw Setup.
    let voice = VoiceModel.shared
    private var voiceWatch: AnyCancellable?

    private var pollTask: Task<Void, Never>?
    private var userJumped = false
    private var observers: [NSObjectProtocol] = []

    init() {
        voiceWatch = voice.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
    }

    // MARK: Derived

    var daemonUp: Bool { health != nil }
    var tailscaleAction: TailscaleAction? { status?.tailscale.map { TailscaleStepLogic.action($0, tsnet: status?.tsnet) } }
    /// The Tailscale app's serve address, or the built-in node's (the daemon's tsnet.ts).
    var phoneBaseURL: String? { status?.tailscale?.baseURL ?? status?.tsnet?.url }
    /// The local network's listener is on: a phone on the same Wi‑Fi pairs without a tailnet.
    var localRoute: Bool { status?.lan?.addresses?.isEmpty == false }
    /// A pairing code can be made: through the tailnet, or the local network alone.
    var canPair: Bool { phoneBaseURL != nil || localRoute }
    /// Where the phone gets the app: the daemon's word (the local network's listener while the built-in node is the only tailnet route).
    var downloadURL: String? { status?.downloadUrl ?? phoneBaseURL.map { $0 + "/download/android" } }
    var downloadIsLocal: Bool { status?.downloadLocal ?? false }
    /// The browser can check the download page's certificate: always, except the LAN listener at the Mac's address
    /// (an older daemon doesn't say, and its LAN download is always at the address).
    var downloadIsTrusted: Bool { status?.downloadTrusted ?? !downloadIsLocal }
    /// The tailnet's download page, offered next to the Wi‑Fi one for a phone that has Tailscale.
    var downloadAnywhereURL: String? { status?.downloadAnywhere }
    /// The download goes through the Mac's name under hypernova.observer (its Let's Encrypt certificate).
    var downloadOnName: Bool { downloadIsLocal && downloadIsTrusted }
    /// "Use a private name for this Mac" (the daemon's nameService setting; on unless turned off). Nil from an older daemon.
    var nameSetting: Bool? { status?.name?.setting }
    /// What became of turning the private name off when it didn't simply work (shown under the switch).
    @Published var nameNote: String?
    func setNameService(_ on: Bool) {
        Task {
            do {
                let r = try await DaemonClient.shared.setNameService(on)
                if let r, let n = r.name, r.released != true {
                    nameNote = "\(n) couldn't be given up just now: its address stays in DNS until Hypernova gets through (it keeps trying), or 90 days unused."
                } else {
                    nameNote = nil
                }
            } catch {
                nameNote = "Hypernova couldn't change this just now (\(error.localizedDescription))."
            }
            if let s = try? await DaemonClient.shared.status(qr: current == .phone) { status = s }
        }
    }
    /// The switch and its line about the name and Let's Encrypt's terms are on screen: the daemon may make the name from now on.
    private var termsReported = false
    func noteNameTermsShown() {
        guard !termsReported, status?.name?.termsShown == false else { return }
        termsReported = true
        Task { try? await DaemonClient.shared.setNameTermsShown() }
    }

    /// The other way to phone access, when there's a choice (owner's audit M2): with Hypernova's own
    /// node in charge and the Tailscale app on this Mac, "Use the Tailscale app"; with the node off
    /// (the setting) while it could run, "Use Hypernova's own". The daemon's `builtInTailscale`.
    var tailscaleChoice: (title: String, builtIn: Bool)? {
        guard daemonUp, let ts = status?.tailscale, let t = status?.tsnet else { return nil }
        if t.mode == "builtIn", ts.installed == true { return ("Use the Tailscale app", false) }
        if t.mode != "system", t.enabled == false, t.available == true { return ("Use Hypernova’s own Tailscale", true) }
        return nil
    }

    func useBuiltInTailscale(_ on: Bool) {
        serveError = nil
        Task {
            do { try await DaemonClient.shared.setBuiltInTailscale(on) } catch { serveError = error.localizedDescription }
            checkTailscale()
        }
    }

    /// Built-in Tailscale: sign the Mac's node out (another account, or a fresh start); a new code follows.
    func tsnetSignOut() {
        Task {
            _ = try? await DaemonClient.shared.tsnetLogout()
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            checkTailscale()
        }
    }

    // MARK: Codex alone

    /// Claude Code isn't on this Mac (or its install didn't finish).
    var claudeMissing: Bool {
        if case .installFailed = claudePhase { return true }
        return claudePhase == .missing
    }
    /// Codex is on this Mac, at any version: the daemon's word once it runs, else what Setup found.
    var codexHere: Bool {
        if daemonUp, let s = status { return s.codex.map { $0.available || $0.path != nil } ?? false }
        return codexFound != nil
    }
    /// Codex can be used here: once the service runs, the daemon's word (installed and new enough);
    /// before, what Setup found itself (the daemon checks the version when it starts).
    var codexUsable: Bool {
        if daemonUp, let s = status { return s.codex?.available == true }
        return codexFound != nil
    }
    /// Here but older than Hypernova needs (the daemon says so): `codex update` first.
    var codexTooOld: Bool { daemonUp && codex?.tooOld == true }
    /// Codex alone (docs/PROTOCOL.md, "Which agent leads"): no Claude Code here, Codex here and usable,
    /// and "Use Codex only" was chosen or Codex is on already. The Claude Code and Connect steps are
    /// skipped then, and done. Codex gone (or one the daemon can't see) skips nothing, so Setup never
    /// finishes with no agent.
    var codexOnly: Bool {
        claudeMissing && codexUsable && (Defaults.store.bool(forKey: Defaults.claudeSkipped) || (daemonUp && codex?.enabled == true))
    }
    /// A step this Mac doesn't need: Claude Code's two, with Codex alone.
    func isSkipped(_ s: Step) -> Bool { (s == .claude || s == .connect) && codexOnly }
    /// Who your phone reaches through Hypernova, for the ledes ("Claude Code", "Codex", "Claude Code and Codex").
    var agentNames: String { codexOnly ? "Codex" : AgentRule(claude: !claudeMissing, codex: daemonUp ? codex : nil).names }

    /// "Use Codex only": Claude Code stays uninstalled, its steps are skipped, Codex is turned on (now,
    /// or once the background service runs), and Setup goes on to what's left. Nothing is installed.
    /// Offered only while Codex is usable (a too-old one asks for `codex update` instead).
    func useCodexOnly() {
        guard codexUsable else { return }
        Defaults.store.set(true, forKey: Defaults.claudeSkipped)
        objectWillChange.send()
        if daemonUp, let c = codex {
            if c.available, !c.enabled { setCodex(true) }
        } else {
            codexOnPending = true
        }
        goOn()
    }

    /// Codex turned on once the service answers, when "Use Codex only" came before it.
    private func applyPendingCodex() {
        guard codexOnPending, daemonUp, let c = codex else { return }
        codexOnPending = false
        if c.available, !c.enabled { setCodex(true) }
    }

    func isDone(_ s: Step) -> Bool {
        switch s {
        case .claude: return claudePhase == .ready || codexOnly
        case .service: return servicePhase == .running || servicePhase == .needsUpdate
        case .connect: return (daemonUp && status?.hooks?.installed == true) || codexOnly
        case .tailscale:
            if case .ready = tailscaleAction { return daemonUp }
            if case .readyBuiltIn = tailscaleAction { return daemonUp }
            return false
        case .voice: return voice.ready || Defaults.store.bool(forKey: Defaults.voiceAsked)
        case .codex: return (daemonUp && codex?.on == true) || Defaults.store.bool(forKey: Defaults.codexAsked)
        case .phone:
            if case .paired = phonePhase { return true }
            return daemonUp && (status?.devices?.count ?? 0) > 0
        }
    }

    /// Setup opens at the first step not done, but never at Codex's unless Codex can be used here:
    /// it's optional, and never in the way of a Mac without it.
    private func opensAt(_ s: Step) -> Bool {
        s != .codex || codex?.available == true
    }

    func isBusy(_ s: Step) -> Bool {
        switch s {
        case .claude: return claudePhase == .installing || claudePhase == .checking
        case .service: if case .installing = servicePhase { return true } else { return false }
        case .connect: return connectBusy
        case .tailscale: return serveBusy
        case .voice: return voice.saving || voice.testing
        case .codex: return codexBusy
        case .phone: return phonePhase == .pair
        }
    }

    func needsAttention(_ s: Step) -> Bool {
        switch s {
        case .claude: if case .installFailed = claudePhase { return true } else { return false }
        case .service: if case .failed = servicePhase { return true } else { return servicePhase == .notAnswering }
        case .connect: return connectError != nil
        case .tailscale: return serveError != nil || (status?.tailscale.flatMap { TailscaleStepLogic.expiringSoon($0, tsnet: status?.tsnet) } != nil)
        case .voice: return voice.stt?.error != nil
        // Only once it's on: a Codex left off (or not installed) never asks for anything.
        case .codex: return codexError != nil || (daemonUp && codex?.enabled == true && codex.flatMap(CodexWords.trouble) != nil)
        case .phone: return pairError != nil
        }
    }

    /// A short status for the rail ("2.1.296", "v25.9.0", "running").
    func trail(_ s: Step) -> String {
        if isSkipped(s) { return "skipped" }
        switch s {
        case .claude:
            switch claudePhase {
            case .checking: return "checking"
            case .missing: return "not installed"
            case .installing: return "installing"
            case .installFailed: return "install failed"
            case .needsSignIn: return "sign in"
            case .ready: return claude.version.map { "v\($0)" } ?? "ready"
            }
        case .service:
            switch servicePhase {
            case .checking: return "checking"
            case .notInstalled, .unmanaged: return "not installed"
            case .notAnswering: return "not answering"
            case .needsUpdate: return "update ready"
            case .installing(.gettingNode(let f?)): return "Node.js \(Int(f * 100))%"
            case .installing(.gettingNode): return "getting Node.js"
            case .installing: return "starting"
            case .failed: return "failed"
            case .running: return "running"
            }
        case .connect:
            if !daemonUp { return "waiting" }
            return status?.hooks?.installed == true ? "connected" : "not connected"
        case .tailscale:
            guard daemonUp, let a = tailscaleAction else { return "waiting" }
            switch a {
            case .install: return "not installed"
            case .openApp(let loggedOut): return loggedOut ? "signed out" : "not running"
            case .enableHTTPS: return "needs HTTPS"
            case .turnOnServe: return "ready to connect"
            case .signInBuiltIn: return "sign in"
            case .builtInWaiting: return "starting"
            case .readyBuiltIn(_, _, let account, _): return account ?? "on, built in"
            case .ready:
                if let d = status?.tailscale.flatMap({ TailscaleStepLogic.expiringSoon($0, tsnet: status?.tsnet) }) {
                    let days = Int(max(0, d.timeIntervalSinceNow) / 86_400)
                    return d.timeIntervalSinceNow <= 0 ? "key expired" : "key expires in \(days)d"
                }
                return "on"
            }
        case .voice:
            if voice.stt?.ready == true { return "Soniox" }
            if voice.companionReady { return "Companion" }
            if voice.stt?.provider != nil { return "Soniox" }
            return Defaults.store.bool(forKey: Defaults.voiceAsked) ? "skipped" : "optional"
        case .codex:
            guard daemonUp else { return "waiting" }
            // Without Claude Code it's the agent here, not an option.
            guard let c = codex else { return claudeMissing ? "not installed" : "optional" }
            if c.tooOld { return "update needed" }
            // Without Claude Code it's the agent here, not an option.
            if !c.enabled { return Defaults.store.bool(forKey: Defaults.codexAsked) ? "skipped" : claudeMissing ? "off" : "optional" }
            if c.problem != nil { return "can’t reach" }
            switch c.account?.signedIn {
            case false?: return "sign in"
            case nil: return "checking"
            default: return c.version.map { "v\($0)" } ?? "on"
            }
        case .phone:
            if case .paired(let n) = phonePhase { return n }
            let n = status?.devices?.count ?? 0
            return n > 0 ? "\(n) paired" : "not paired"
        }
    }

    // MARK: Lifecycle

    /// "Pair a phone…" asks for the pairing code straight away (once Tailscale's address is known).
    private var wantsPairing = false

    func jump(to step: Step, pairing: Bool = false) {
        userJumped = true
        withAnimation(.hnExpo(0.5)) { current = step }
        if step == .phone, pairing, phonePhase != .pair {
            if canPair { goPair() } else { wantsPairing = true }
        }
    }

    func windowOpened() {
        if observers.isEmpty {
            observers.append(NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.appBecameActive() }
            })
            observers.append(NotificationCenter.default.addObserver(forName: .hypernovaWindowClosed, object: nil, queue: .main) { [weak self] n in
                guard (n.object as? String) == "setup" else { return }
                MainActor.assumeIsolated { self?.windowClosed() }
            })
        }
        detectAll(chooseStep: !userJumped)
        userJumped = false
        startPolling()
    }

    private func windowClosed() {
        pollTask?.cancel(); pollTask = nil
        if case .paired = phonePhase { phonePhase = .getApp }
        if phonePhase == .pair { phonePhase = .getApp }
        pair = nil
    }

    private func appBecameActive() {
        guard WindowManager.shared.isOpen("setup") else { return }
        if claudePhase == .needsSignIn || claudePhase == .missing { recheckClaude() }
        // Codex installed in a terminal meanwhile: the Claude Code step offers it.
        if claudeMissing, !daemonUp { Task { codexFound = await Task.detached { CodexDetector.find() }.value } }
        if current == .tailscale { checkTailscale() }
    }

    func detectAll(chooseStep: Bool) {
        Task {
            async let c = Task.detached { ClaudeDetector.detect() }.value
            async let n = Task.detached { NodeDetector.forService() }.value
            async let x = Task.detached { CodexDetector.find() }.value
            async let h = DaemonClient.shared.health()
            let (claudeD, nodeD, codexD, healthD) = await (c, n, x, h)
            health = healthD
            codexFound = codexD
            if healthD != nil { status = try? await DaemonClient.shared.status(); await voice.load() }
            if claudePhase != .installing { applyClaude(claudeD) }
            applyPendingCodex()
            applyNode(nodeD.use)
            await refreshService()
            detected = true
            if wantsPairing {
                wantsPairing = false
                if canPair, phonePhase != .pair { goPair() }
            }
            if chooseStep {
                // Codex here and Claude Code not: the Codex step first, where turning it on settles
                // Claude Code's steps. Otherwise the first step not done.
                let codexFirst = claudeMissing && !codexOnly && daemonUp && codex?.available == true && codex?.enabled != true
                let first: Step = codexFirst ? .codex : Step.allCases.first { !isDone($0) && opensAt($0) } ?? .phone
                withAnimation(.hnExpo(0.5)) { current = first }
            }
        }
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            var tick = 0
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                guard let self, !Task.isCancelled else { return }
                await self.poll(tick: tick)
                tick += 1
            }
        }
    }

    private func poll(tick: Int) async {
        let h = await DaemonClient.shared.health()
        health = h
        if h != nil {
            let fresh = current == .tailscale && tick % 3 == 2
            // On the phone step a download or pairing code is on screen: the daemon publishes the Mac's private name meanwhile.
            if let s = try? await DaemonClient.shared.status(fresh: fresh, qr: current == .phone), s != status {
                status = s
                applyPendingCodex()
                // A sign-in through the service finished: Claude Code is ready.
                if claudePhase == .needsSignIn, ["ok", "limited", "no_credit"].contains(s.account?.state ?? "") { recheckClaude() }
            }
            if current == .phone, phonePhase == .pair { await pairTick() }
            // A Companion installed meanwhile turns up as Found by itself.
            if current == .voice { await voice.load() }
        } else {
            status = nil
        }
        if current == .service || tick % 5 == 0 { await refreshService() }
    }

    // MARK: a. Claude Code

    private func applyClaude(_ d: ClaudeDetection) {
        var d = d
        // The service's word on the account wins: it asks `claude auth status` (an API key in the
        // environment or a key helper counts there too, which ~/.claude.json doesn't show).
        if let a = status?.account?.state {
            if ["ok", "limited", "no_credit"].contains(a) { d.signedIn = true }
            else if a == "logged_out" { d.signedIn = false }
        }
        claude = d
        if d.path == nil { claudePhase = .missing }
        else if d.signedIn == false { claudePhase = .needsSignIn }
        else { claudePhase = .ready }
    }

    /// Signs in through the service, in this window: it runs `claude auth login` without a terminal
    /// and hands back the page to open; the code that page shows comes back here.
    func startSignInHere() {
        signInBusy = true
        claudeError = nil
        signingInHere = true
        Task {
            do {
                _ = try await DaemonClient.shared.startLogin(where: "phone")
                if let s = try? await DaemonClient.shared.status() { status = s }
            } catch DaemonError.server(let code, _, _) where code == 409 {
                // Signed in meanwhile.
                recheckClaude()
            } catch {
                claudeError = error.localizedDescription
            }
            signInBusy = false
        }
    }

    func openSignInPage() {
        guard let u = serviceLogin?.url.flatMap(URL.init(string:)), u.scheme == "https" else { return }
        NSWorkspace.shared.open(u)
    }

    func sendSignInCode() {
        let code = signInCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty else { return }
        signInBusy = true
        claudeError = nil
        Task {
            do {
                try await DaemonClient.shared.sendLoginCode(code)
                signInCode = ""
            } catch {
                claudeError = error.localizedDescription
            }
            signInBusy = false
        }
    }

    func recheckClaude() {
        Task {
            let d = await Task.detached { ClaudeDetector.detect() }.value
            if claudePhase != .installing { applyClaude(d) }
            // No Claude Code: look for Codex again too (installed or updated in a terminal meanwhile).
            if claudeMissing {
                if daemonUp {
                    if let s = try? await DaemonClient.shared.status(fresh: true) { status = s }
                } else {
                    codexFound = await Task.detached { CodexDetector.find() }.value
                }
            }
        }
    }

    func installClaude() {
        claudePhase = .installing
        claudeError = nil
        claudeLog = ["$ curl -fsSL \(Endpoints.claudeInstaller) | bash"]
        let p = StreamingProcess()
        installer = p
        p.start("/bin/bash", ["-c", "set -o pipefail; curl -fsSL \(Endpoints.claudeInstaller) | bash"], onLine: { line in
            Task { @MainActor in
                SetupModel.shared.claudeLog.append(line)
                if SetupModel.shared.claudeLog.count > 400 { SetupModel.shared.claudeLog.removeFirst(100) }
            }
        }, onExit: { code in
            Task { @MainActor in
                let m = SetupModel.shared
                m.installer = nil
                let d = await Task.detached { ClaudeDetector.detect() }.value
                if code == 0, d.path != nil {
                    m.claudeLog.append("✓ Claude Code \(d.version ?? "") is installed")
                    m.applyClaude(d)
                } else {
                    m.claudeLog.append("Installer exited with code \(code)")
                    m.claudePhase = .installFailed(code == 0 ? "The installer finished, but Claude Code isn't where Hypernova expected it." : "The installer stopped with an error (code \(code)). The log above says why; check your internet connection and try again.")
                }
            }
        })
    }

    func openSignIn() {
        guard let path = claude.path else { return }
        Task {
            // `open` can take a moment: off the main thread.
            let failure: String? = await Task.detached {
                do { try ClaudeDetector.openSignInTerminal(claudePath: path); return nil } catch { return error.localizedDescription }
            }.value
            if let failure { claudeError = failure } else { signInOpened = true; claudeError = nil }
        }
    }

    // MARK: b. Node.js (part of the service)

    private func applyNode(_ c: NodeCandidate?) {
        node = c
        nodeChecked = true
        if c == nil, nodeRelease == nil { Task { nodeRelease = try? await NodeInstaller.latestLTS() } }
    }

    // MARK: c. Background service

    func refreshService() async {
        if case .installing = servicePhase { return }
        let h = health
        let st = await Task.detached { ServiceInstaller.detect(health: h) }.value
        if case .installing = servicePhase { return }
        if case .failed = servicePhase, !st.healthy { return }   // keep the error on screen
        if st.healthy {
            servicePhase = !st.plistExists ? .unmanaged : st.updateAvailable ? .needsUpdate : .running
        } else {
            servicePhase = st.plistExists ? .notAnswering : .notInstalled
        }
    }

    func installService() {
        servicePhase = .installing(.copying)
        serviceLogTail = []
        Task {
            do {
                let h = try await SetupActions.installService { step in
                    Task { @MainActor in SetupModel.shared.servicePhase = .installing(step) }
                }
                health = h
                servicePhase = .running
                applyNode(await Task.detached { NodeDetector.forService() }.value.use)
                status = try? await DaemonClient.shared.status()
                applyPendingCodex()
                AppModel.shared.loginItemEnabled = !Paths.testCopy && SMAppService.mainApp.status == .enabled
                await AppModel.shared.refresh()
            } catch {
                servicePhase = .failed(error.localizedDescription)
                serviceLogTail = ServiceInstaller.logTail(14)
            }
        }
    }

    // MARK: d. Connect Claude Code

    func connect() {
        connectBusy = true
        connectError = nil
        Task {
            do { status = try await SetupActions.connectClaudeCode() } catch { connectError = error.localizedDescription }
            connectBusy = false
        }
    }

    // MARK: e. Tailscale

    func checkTailscale() {
        tailscaleChecking = true
        Task {
            if let s = try? await DaemonClient.shared.status(fresh: true) { status = s }
            tailscaleChecking = false
        }
    }

    func turnOnPhoneAccess() {
        serveBusy = true
        serveError = nil
        Task {
            do {
                _ = try await SetupActions.turnOnPhoneAccess()
                status = try? await DaemonClient.shared.status(fresh: true)
            } catch { serveError = error.localizedDescription }
            serveBusy = false
        }
    }

    // MARK: f. Voice

    /// "Not now": voice stays off, Setup remembers it asked, and goes on.
    func skipVoice() {
        Defaults.store.set(true, forKey: Defaults.voiceAsked)
        next()
    }

    // MARK: g. Codex

    /// "Use Codex with Hypernova" on or off (`POST /local/settings {codex}`): the daemon connects to
    /// Codex's own app-server, or lets go of it. Shown at once, then as the daemon has it; the menu bar
    /// follows. Nothing is installed or signed in from here.
    func setCodex(_ on: Bool) {
        guard !codexBusy else { return }
        codexBusy = true
        codexError = nil
        if var s = status, var c = s.codex {
            c.enabled = on
            s.codex = c
            status = s
        }
        Task {
            do { try await DaemonClient.shared.setCodex(enabled: on) } catch { codexError = "Couldn’t change that. \(error.localizedDescription)" }
            if let s = try? await DaemonClient.shared.status() { status = s }
            codexBusy = false
            await AppModel.shared.refresh()
        }
    }

    /// Look again: Codex's account and limits read now (only while it's on), then the status.
    func checkCodex() {
        codexError = nil
        Task {
            if codex?.enabled == true { _ = try? await DaemonClient.shared.refreshCodex() }
            if let s = try? await DaemonClient.shared.status() { status = s }
        }
    }

    /// "Not now": Codex stays as it is, Setup remembers it asked, and goes on.
    func skipCodex() {
        Defaults.store.set(true, forKey: Defaults.codexAsked)
        next()
    }

    // MARK: h. Phone

    func goPair() {
        withAnimation(.hnExpo(0.5)) { phonePhase = .pair }
        startPairing(resetKnown: true)
    }

    func backToGetApp() {
        withAnimation(.hnExpo(0.5)) { phonePhase = .getApp }
        pair = nil
    }

    func startPairing(resetKnown: Bool) {
        pairBusy = true
        pairError = nil
        Task {
            do {
                if resetKnown || knownDeviceIDs == nil {
                    knownDeviceIDs = Set(try await DaemonClient.shared.devices().map(\.id))
                }
                let p = try await DaemonClient.shared.pairStart(forName: true)
                // If Tailscale hiccuped, the daemon may fall back to a development address the phone
                // can't reach. Don't show that; try again on the next tick.
                if let want = phoneBaseURL, let got = p.url.map({ $0.hasSuffix("/") ? String($0.dropLast()) : $0 }), got != want {
                    throw SetupActions.err("Tailscale isn't answering right now, so the pairing code can't carry this Mac's address. Retrying…")
                }
                withAnimation(.hnExpo(0.3)) { pair = p }
            } catch {
                pairError = error.localizedDescription
            }
            pairBusy = false
        }
    }

    /// Every 2 s while the pairing code is up: refresh it before it expires, and look for a new phone.
    private func pairTick() async {
        let expiring = pair?.expiresAt.map { $0.date.timeIntervalSinceNow < 20 } ?? false
        if !pairBusy, expiring || (pair == nil && pairError != nil) {
            startPairing(resetKnown: false)
        }
        guard let known = knownDeviceIDs, let list = try? await DaemonClient.shared.devices() else { return }
        if let new = list.first(where: { !known.contains($0.id) }) {
            NSHapticFeedbackManager.defaultPerformer.perform(.levelChange, performanceTime: .now)
            withAnimation(.hnExpo(0.6)) { phonePhase = .paired(new.displayName) }
            pair = nil
            Defaults.store.set(true, forKey: Defaults.setupCompleted)
            status = try? await DaemonClient.shared.status()
            await AppModel.shared.refresh()
        }
    }

    func copy(_ s: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(s, forType: .string)
    }

    // MARK: Navigation

    /// The next step, past any this Mac doesn't need (Claude Code's, with Codex alone).
    func next() {
        var n = Step(rawValue: current.rawValue + 1)
        while let s = n, isSkipped(s) { n = Step(rawValue: s.rawValue + 1) }
        if let n {
            withAnimation(.hnExpo(0.5)) { current = n }
        } else {
            finish()
        }
    }

    /// On to the first step still to do, after a choice that settles earlier ones (Codex alone); the
    /// next one when everything before is done.
    func goOn() {
        if let s = Step.allCases.first(where: { $0 != current && !isDone($0) && !isSkipped($0) && opensAt($0) }) {
            withAnimation(.hnExpo(0.5)) { current = s }
        } else {
            next()
        }
    }

    func finish() {
        Defaults.store.set(true, forKey: Defaults.setupCompleted)
        WindowManager.shared.close("setup")
    }
}
