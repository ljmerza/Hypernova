import AppKit
import SwiftUI

/// `Hypernova --render-lens-guide <dir>`: the "Hypernova Lens" window in each step and voice state,
/// plus Settings with glasses paired, on sample data (the daemon is never asked). The window is
/// drawn in a real, offscreen window so its key field renders as it does on screen.
extension DevTools {
    static func renderLensGuide(into dir: URL) -> Int32 {
        MainActor.assumeIsolated { LensGuideRender(dir: dir).run() }
    }

    /// `Hypernova --lens-guide-selftest <dir>`: against the daemon HYPERNOVA_SUPPORT,
    /// HYPERNOVA_API_PORT and HYPERNOVA_LOCAL_PORT point at (a test one, never the owner's): the
    /// app's live feed connects, as the Mac app's one feed, "Show on my Mac" from a phone opens the
    /// real window at the phone's step, a step taken on the phone moves it, and Done there closes it.
    /// No status item, no Setup. Prints each check, saves the open window, exits 1 on a failure, and
    /// 2 without a test daemon: it marks the guide done and opens windows in every Mac app on the
    /// daemon it reaches, so never the owner's.
    static func runLensGuideSelfTest(into dir: URL) -> Never {
        guard Paths.isolatedTestCopy else {
            print("set HYPERNOVA_SUPPORT to a test daemon's folder and HYPERNOVA_API_PORT and HYPERNOVA_LOCAL_PORT to its ports (not \(Endpoints.defaultAPIPort) or \(Endpoints.defaultLocalPort)) first")
            exit(2)
        }
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        Task { @MainActor in exit(await LensGuideSelfTest(dir: dir).run()) }
        app.run()
        exit(1)
    }
}

@MainActor
private struct LensGuideSelfTest {
    let dir: URL
    private struct Shown: Decodable { var macs: Int }
    private struct Moved: Decodable { var guide: GuideState }

    func run() async -> Int32 {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var failures = 0
        func check(_ ok: Bool, _ what: String) { print("\(ok ? "ok  " : "FAIL") \(what)"); if !ok { failures += 1 } }
        func until(_ seconds: Double, _ ok: () -> Bool) async -> Bool {
            let end = Date().addingTimeInterval(seconds)
            while Date() < end { if ok() { return true }; try? await Task.sleep(nanoseconds: 50_000_000) }
            return ok()
        }
        func phone<T: Decodable>(_ path: String, _ body: [String: AnyEncodable], _ type: T.Type) async -> T? {
            // What Lens sends; the admin token reaches the same routes.
            try? await DaemonClient.shared.request("POST", path, body: body, as: type)
        }
        print("daemon at \(Endpoints.local.absoluteString), api port \(Endpoints.apiPort)")
        // What launch will find: glasses that paired while the app was away, and a guide not seen yet.
        let before = try? await DaemonClient.shared.request("GET", "/v1/guide", as: Moved.self)
        let glassesWaiting = (try? await DaemonClient.shared.devices())?.contains(where: \.isLens) == true && before?.guide.seen == false && before?.guide.done == false
        // The app's own model and its one feed, as at launch.
        let app = AppModel.shared
        app.start()
        let model = LensGuideModel.shared
        model.start()
        check(await until(8) { app.feedLive }, "the live feed connects")
        if glassesWaiting {
            check(await until(4) { WindowManager.shared.isOpen(LensGuideModel.windowID) }, "glasses that paired while the app was away open the guide at launch")
            WindowManager.shared.close(LensGuideModel.windowID)
            _ = await until(2) { !WindowManager.shared.isOpen(LensGuideModel.windowID) }
        } else {
            print("     (no unseen guide with glasses paired: the launch catch-up not checked)")
        }
        _ = await phone("/v1/guide", ["step": "about", "done": false], Moved.self)
        let shown = await phone("/v1/guide/show", ["step": "voice"], Shown.self)
        check(shown?.macs == 1, "the daemon counts this app's one feed as the Mac app's (macs: \(shown.map { String($0.macs) } ?? "none"))")
        check(await until(3) { WindowManager.shared.isOpen(LensGuideModel.windowID) }, "“Show on my Mac” opens the window")
        check(await until(2) { model.step == .voice }, "at the phone's step (\(model.step.rawValue))")
        try? await Task.sleep(nanoseconds: 1_200_000_000)
        if let w = NSApp.windows.first(where: { $0.identifier?.rawValue == LensGuideModel.windowID }), let v = w.contentView?.superview ?? w.contentView,
           let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds) {
            v.cacheDisplay(in: v.bounds, to: rep)
            let url = dir.appendingPathComponent("selftest-lens-guide-window.png")
            try? rep.representation(using: .png, properties: [:])?.write(to: url)
            print("     wrote \(url.path)")
        }
        _ = await phone("/v1/guide", ["step": "gestures"], Moved.self)
        check(await until(3) { model.step == .gestures }, "a step taken on the phone moves the window")
        if let devices = try? await DaemonClient.shared.devices(), let glasses = devices.first(where: \.isLens) {
            check(glasses.displayName == "Hypernova Lens", "glasses are named Hypernova Lens, as on the phone (\(glasses.displayName))")
        } else {
            print("     (no glasses paired with this daemon: naming not checked)")
        }
        let done = await phone("/v1/guide", ["done": true], Moved.self)
        check(done?.guide.done == true, "Done on the phone")
        check(await until(3) { !WindowManager.shared.isOpen(LensGuideModel.windowID) }, "… closes the window")
        print(failures == 0 ? "all good" : "\(failures) failure(s)")
        return failures == 0 ? 0 : 1
    }

}

@MainActor
private struct LensGuideRender {
    let dir: URL

    func run() -> Int32 {
        _ = NSApplication.shared
        NSApp.setActivationPolicy(.prohibited)
        SnapshotMode.active = true
        do { try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true) } catch {
            print("can't create \(dir.path): \(error.localizedDescription)"); return 1
        }
        var failed = 0
        // Its own voice model, so a render's state never leaks into the shared one.
        let voice = VoiceModel()
        let m = LensGuideModel(voice: voice)
        let size = NSSize(width: 780, height: 560)
        func guide(_ name: String, _ step: LensGuideModel.Step, _ stt: SttState?, key: String = "", result: (String, Bool)? = nil,
                   confirm: Bool = false, companionApp: URL? = nil, companionURL: String? = nil, steps: [LensGuideModel.Step] = LensGuideModel.Step.allCases) {
            voice.loadSample(stt: stt, keyDraft: key, result: result, confirmingRemove: confirm, companionApp: companionApp, companionURL: companionURL)
            m.loadSample(step: step, steps: steps)
            if !capture(name, size: size, LensGuideView().environmentObject(m)) { failed += 1 }
        }
        let off: SttState? = nil
        let ready = SttState(provider: "soniox", ready: true, error: nil, last4: "4f2a")
        let companion = "wss://macbook-air.tailnet.ts.net:8443/stt"
        guide("lens-guide-1-about", .about, off)
        guide("lens-guide-2-gestures", .gestures, off)
        guide("lens-guide-3-voice", .voice, off)
        guide("lens-guide-4-voice-key", .voice, off, key: "sx_9b1c0f7a2e3d4c5b6a7f8e9d0c1b2a3f")
        guide("lens-guide-5-voice-ready", .voice, ready, result: ("Soniox heard the test. Voice is ready.", true))
        guide("lens-guide-6-voice-no-credit", .voice, SttState(provider: "soniox", ready: false, error: "Your Soniox balance is used up. Add credit at console.soniox.com.", last4: "4f2a"))
        guide("lens-guide-7-voice-refused", .voice, off, key: "sx_wrong", result: ("Soniox didn’t accept that key. Copy it again from console.soniox.com.", false))
        guide("lens-guide-8-voice-busy", .voice, ready, result: ("Saved. Soniox is at its limit of streams at once. Try again in a moment.", false))
        guide("lens-guide-9-voice-remove", .voice, ready, confirm: true)
        // The Companion installed as an app and running for Lens: "Open Hyperscribe Companion".
        guide("lens-guide-10-voice-companion-app", .voice, off, companionApp: URL(fileURLWithPath: "/Applications/Hyperscribe Companion.app"), companionURL: companion)
        // Running here from source (no app): its name, and that Lens uses it.
        guide("lens-guide-11-voice-companion-running", .voice, off, companionURL: companion)
        // Voice was ready when the guide opened: two steps, Gestures ends with Done; the header chip
        // still opens the voice step.
        let two: [LensGuideModel.Step] = [.about, .gestures]
        guide("lens-guide-12-two-steps-about", .about, ready, steps: two)
        guide("lens-guide-13-two-steps-gestures", .gestures, ready, steps: two)

        // Setup's Voice step (6 of 7): nothing yet, a key being checked, connected, refused, and the
        // Companion found with no key. The Mac never listens, so there is no "try it" state.
        let setup = setupSample()
        func setupVoice(_ name: String, _ stt: SttState?, key: String = "", saving: Bool = false, result: (String, Bool)? = nil, companionURL: String? = nil) {
            VoiceModel.shared.loadSample(stt: stt, keyDraft: key, result: result, saving: saving, companionApp: nil, companionURL: companionURL)
            if !capture(name, size: NSSize(width: 860, height: 600), SetupView().environmentObject(setup)) { failed += 1 }
        }
        setupVoice("setup-voice-1-empty", off)
        setupVoice("setup-voice-2-checking", off, key: "sx_9b1c0f7a2e3d4c5b6a7f8e9d0c1b2a3f", saving: true)
        setupVoice("setup-voice-3-connected", ready, result: ("Connected. Your phone gets a mic in the composer.", true))
        setupVoice("setup-voice-4-refused", off, key: "sx_wrong", result: ("Soniox didn’t accept that key. Copy it again from console.soniox.com.", false))
        setupVoice("setup-voice-5-companion-found", off, companionURL: companion)
        // After Not now: the rail says skipped.
        Defaults.store.set(true, forKey: Defaults.voiceAsked)
        setupVoice("setup-voice-6-skipped", off)
        Defaults.store.removeObject(forKey: Defaults.voiceAsked)
        VoiceModel.shared.endSample()

        // Settings with a phone and the glasses paired: "Hypernova Lens", with its own glyph.
        if !capture("lens-settings-devices", size: nil, settings()) { failed += 1 }
        print(failed == 0 ? "done: \(dir.path)" : "done with \(failed) failure(s)")
        return failed == 0 ? 0 : 1
    }

    /// Setup on sample data: everything before Voice done, on the Voice step, the daemon never asked.
    private func setupSample() -> SetupModel {
        let setup = SetupModel()
        let now = Date().timeIntervalSince1970 * 1000
        let status = """
        {"daemonVersion":"0.4.0","apiPort":47321,"tailscale":{"installed":true,"running":true,"loggedIn":true,"dnsName":"macbook-air.tailnet.ts.net",
         "httpsEnabled":true,"serveOn":true,"url":"https://macbook-air.tailnet.ts.net:47321"},"hooks":{"installed":true},
         "devices":[],"connectedClients":0,"sessions":[],"approvals":[],"usage":null,"projectsRoot":"/Users/you/Programming","now":\(now)}
        """
        setup.claudePhase = .ready
        setup.claude = ClaudeDetection(path: "/Users/you/.local/bin/claude", version: "2.1.296", signedIn: true, account: nil, foundBy: nil)
        setup.node = NodeCandidate(path: "/usr/local/bin/node", source: "/usr/local", version: "v24.4.0")
        setup.servicePhase = .running
        setup.health = Health(ok: true, protocol: 1, name: "MacBook Air")
        setup.status = try? JSONDecoder().decode(LocalStatus.self, from: Data(status.utf8))
        setup.current = .voice
        return setup
    }

    private func settings() -> some View {
        let now = Date().timeIntervalSince1970 * 1000
        let status = """
        {"daemonVersion":"0.4.0","apiPort":47321,"tailscale":{"installed":true,"running":true,"loggedIn":true,"dnsName":"macbook-air.tailnet.ts.net",
         "httpsEnabled":true,"serveOn":true,"url":"https://macbook-air.tailnet.ts.net:47321"},"hooks":{"installed":true},
         "devices":[{"id":"d-phone","name":"Nothing Phone (3)","model":"A024","createdAt":\(now - 9 * 86_400_000),"lastSeenAt":\(now - 25_000)},
                    {"id":"d-lens","name":"Even G2 · Lens","model":"Even G2 · Hypernova Lens","client":"lens","createdAt":\(now - 600_000),"lastSeenAt":\(now - 40_000)}],
         "connectedClients":2,"sessions":[],"approvals":[],"usage":null,"projectsRoot":"/Users/you/Programming"}
        """
        let snapshot = #"{"mac":{"id":"m1","name":"MacBook Air","daemonVersion":"0.4.0"},"sessions":[],"approvals":[]}"#
        let model = AppModel()
        model.loadSample(health: Health(ok: true, protocol: 1, name: "MacBook Air"),
                         snapshot: try? JSONDecoder().decode(Snapshot.self, from: Data(snapshot.utf8)),
                         status: try? JSONDecoder().decode(LocalStatus.self, from: Data(status.utf8)))
        return MenuPopover(page: .settings).environmentObject(model)
            .frame(width: 360)
            .background(Color.hnBg)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .padding(24)
            .background(Color(hex: 0x2A2622))
    }

    /// Draws the view in an offscreen window (AppKit-backed controls included); ImageRenderer if that fails.
    private func capture<V: View>(_ name: String, size: NSSize?, _ view: V) -> Bool {
        let root = view.environment(\.colorScheme, .dark)
        let host = NSHostingView(rootView: root)
        let fitting = size ?? host.fittingSize
        host.frame = NSRect(origin: .zero, size: fitting)
        let w = NSWindow(contentRect: NSRect(origin: NSPoint(x: -20_000, y: -20_000), size: fitting), styleMask: [.borderless], backing: .buffered, defer: false)
        w.appearance = NSAppearance(named: .darkAqua)
        w.contentView = host
        w.orderFrontRegardless()
        RunLoop.main.run(until: Date().addingTimeInterval(0.35))
        host.layoutSubtreeIfNeeded()
        defer { w.orderOut(nil) }
        var cg: CGImage?
        if let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) {
            host.cacheDisplay(in: host.bounds, to: rep)
            cg = rep.cgImage
        }
        if cg == nil {
            let r = ImageRenderer(content: root.frame(width: fitting.width, height: fitting.height))
            r.scale = 2
            cg = r.cgImage
        }
        guard let cg, let png = NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:]) else { print("render failed: \(name)"); return false }
        let url = dir.appendingPathComponent(name + ".png")
        do { try png.write(to: url) } catch { print("write failed: \(url.path)"); return false }
        print("wrote \(url.path)")
        return true
    }
}
