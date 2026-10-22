import AppKit
import SwiftUI

/// Hidden developer flags (not part of the product UI).
///   --selftest-node-download <dir>   downloads + verifies + unpacks the Node LTS into <dir>
///   --snapshots <dir>                renders the windows offscreen to PNGs (real data where available)
///   --render-popover <dir> [--live]  the menu bar popover and icon on sample data (App/PopoverRender.swift)
///   --popover-selftest <dir>         the real status item and popover on sample data, with checks and CPU
///   --feed-selftest [seconds] [--sign-in] [--open]  how the live feed reads the daemon's messages; then, against
///                                    a test daemon (HYPERNOVA_SUPPORT and both ports set, off 47321/47322), the model's
///                                    changes as they arrive, optionally pressing Sign in once, or reading at the open
///                                    popover's rhythm (App/FeedSelfTest.swift)
///   --render-lens-guide <dir>        the "Hypernova Lens" guide window in each step and voice state (App/LensGuideRender.swift)
///   --lens-guide-selftest <dir>      the guide against a test daemon (HYPERNOVA_SUPPORT and both ports set, off
///                                    47321/47322): the app's one live feed, "Show on my Mac", steps, Done
///   --backpack-scripts               what Backpack mode's setup and removal run as root, and the AppleScript around
///                                    them (Core/Backpack.swift), printed, never run
///   --window-capture-selftest        Telescope's window source trusts a process by the program the kernel loaded, not its
///                                    argv[0], and never a system or apps folder (App/WindowCaptureSelfTest.swift)
enum DevTools {
    static func nodeDownload(into dir: URL) -> Int32 {
        final class Box: @unchecked Sendable { var code: Int32 = 1; var lastPct = -10 }
        let box = Box(), sem = DispatchSemaphore(value: 0)
        Task.detached {
            do {
                let path = try await NodeInstaller.install(into: dir) { phase in
                    switch phase {
                    case .resolving: print("resolving the latest LTS…")
                    case .downloading(let f, let done, let total):
                        let pct = Int(f * 100)
                        if pct >= box.lastPct + 10 || done == total { box.lastPct = pct; print("downloading \(pct)% (\(Format.megabytes(done)) of \(Format.megabytes(total)))") }
                    case .verifying: print("verifying SHA-256 against SHASUMS256.txt…")
                    case .extracting: print("unpacking…")
                    case .done(let v): print("done: \(v)")
                    }
                    fflush(stdout)
                }
                print("OK: \(path)")
                box.code = 0
            } catch {
                print("FAILED: \(error.localizedDescription)")
            }
            sem.signal()
        }
        sem.wait()
        return box.code
    }

    /// `--selftest-stream`: StreamingProcess with a child that keeps the pipe open.
    static func streamTest() -> Int32 {
        let start = Date(), sem = DispatchSemaphore(value: 0)
        final class Box: @unchecked Sendable { var lines: [String] = []; var code: Int32 = -99; let lock = NSLock() }
        let box = Box()
        let p = StreamingProcess()
        p.start("/bin/bash", ["-c", "echo one; printf 'two\\rthree\\n'; (sleep 6) & echo four; printf 'five-no-newline'; exit 3"], onLine: { l in
            box.lock.lock(); box.lines.append(l); box.lock.unlock()
        }, onExit: { c in box.code = c; sem.signal() })
        let ok = sem.wait(timeout: .now() + 10) == .success
        let t = Date().timeIntervalSince(start)
        print("finished=\(ok) exit=\(box.code) after \(String(format: "%.1f", t))s lines=\(box.lines)")
        return ok && box.code == 3 && box.lines == ["one", "two", "three", "four", "five-no-newline"] && t < 4 ? 0 : 1
    }

    static func snapshots(into dir: URL) -> Int32 {
        MainActor.assumeIsolated { renderSnapshots(into: dir) }
    }

    @MainActor
    private static func renderSnapshots(into dir: URL) -> Int32 {
        _ = NSApplication.shared
        NSApp.setActivationPolicy(.prohibited)
        SnapshotMode.active = true
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        func spin(_ s: Double) { RunLoop.main.run(until: Date().addingTimeInterval(s)) }
        func render<V: View>(_ name: String, _ size: CGSize?, _ view: V) {
            let content = view.environment(\.colorScheme, .dark)
            let r: ImageRenderer<AnyView>
            if let size { r = ImageRenderer(content: AnyView(content.frame(width: size.width, height: size.height))) }
            else { r = ImageRenderer(content: AnyView(content)) }
            r.scale = 2
            guard let cg = r.cgImage, let png = NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:]) else { print("render failed: \(name)"); return }
            try? png.write(to: dir.appendingPathComponent(name + ".png"))
            print("wrote \(dir.appendingPathComponent(name + ".png").path)")
        }

        let setup = SetupModel.shared
        setup.detectAll(chooseStep: false)
        AppModel.shared.start()
        spin(5)

        let size = CGSize(width: 860, height: 600)
        for step in SetupModel.Step.allCases {
            setup.current = step
            spin(0.2)
            render("setup-\(step.rawValue + 1)-\(step)", size, SetupView().environmentObject(setup))
        }

        // States that are hard to reach on this Mac, with stand-in data.
        let real = (setup.claudePhase, setup.claudeLog, setup.node, setup.servicePhase)
        setup.current = .claude
        setup.claudePhase = .installing
        setup.claudeLog = ["$ curl -fsSL https://claude.ai/install.sh | bash", "Setting up Claude Code...", "Downloading the native build (2.1.296)", "✓ Claude Code successfully installed!", "Location: ~/.local/bin/claude"]
        render("variant-claude-installing", size, SetupView().environmentObject(setup))
        (setup.claudePhase, setup.claudeLog) = (real.0, real.1)
        // No stable Node.js on this Mac: the service's first line downloads Hypernova's own.
        setup.current = .service
        setup.node = nil
        setup.servicePhase = .installing(.gettingNode(0.42))
        render("variant-service-getting-node", size, SetupView().environmentObject(setup))
        setup.node = real.2
        setup.servicePhase = .installing(.starting)
        render("variant-service-installing", size, SetupView().environmentObject(setup))
        (setup.claudePhase, setup.claudeLog, setup.node, setup.servicePhase) = real

        setup.current = .phone
        if setup.phoneBaseURL != nil {
            setup.goPair()   // POST /local/pair/start: a single-use code that simply expires
            spin(2.5)
            render("variant-phone-pair", size, SetupView().environmentObject(setup))
            setup.phonePhase = .paired("Nothing Phone (3)")
            render("variant-phone-paired", size, SetupView().environmentObject(setup))
            setup.phonePhase = .getApp
        }

        render("popover", nil, MenuPopover().environmentObject(AppModel.shared))
        let down = AppModel()
        down.simulateServiceDownForSnapshot()
        render("popover-service-down", nil, MenuPopover().environmentObject(down))
        let providers = ProvidersModel()
        providers.load()
        spin(1.5)
        render("providers", CGSize(width: 720, height: 680), ProvidersView(model: providers))
        providers.newOpenRouter()
        render("providers-add-openrouter", CGSize(width: 720, height: 680), ProvidersView(model: providers))
        for running in [true, false] {
            let img = NovaIcons.menuBar(running: running)
            if let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 72, pixelsHigh: 72, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) {
                rep.size = NSSize(width: 18, height: 18)
                NSGraphicsContext.saveGraphicsState()
                NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
                NSColor.white.setFill(); NSRect(x: 0, y: 0, width: 18, height: 18).fill()
                img.draw(in: NSRect(x: 0, y: 0, width: 18, height: 18))
                NSGraphicsContext.restoreGraphicsState()
                try? rep.representation(using: .png, properties: [:])?.write(to: dir.appendingPathComponent("menubar-icon-\(running ? "running" : "off")@4x.png"))
            }
        }
        return 0
    }

    // MARK: Live capture (HYPERNOVA_DEBUG_CAPTURE=<dir>): the running app photographs its own
    // windows (no Screen Recording permission needed for your own windows), opens its own status
    // item to capture the real popover, then opens Providers and captures that too.

    @MainActor
    static func liveCapture(into dir: URL) {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        Task { @MainActor in
            func wait(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
            var report: [String] = []
            @MainActor func dump(_ tag: String) {
                for w in NSApp.windows {
                    report.append("[\(tag)] \(type(of: w)) visible=\(w.isVisible) key=\(w.isKeyWindow) frame=\(NSStringFromRect(w.frame)) title=\(w.title)")
                }
            }
            await wait(6)
            dump("launch")
            if let setup = NSApp.windows.first(where: { $0.identifier?.rawValue == "setup" }) { capture(setup, dir.appendingPathComponent("live-setup.png")) }

            if let button = statusButton() {
                report.append("status item: found (\(NSStringFromRect(button.window?.frame ?? .zero)))")
                button.performClick(nil)
                await wait(3)
                dump("popover")
                if let pop = StatusItemController.shared.debugPanel, pop.isVisible {
                    capture(pop, dir.appendingPathComponent("live-popover.png"))
                } else {
                    report.append("popover: didn't open")
                }
                button.performClick(nil)
            } else {
                report.append("status item: NOT found")
            }
            await wait(1)
            let providers = ProvidersModel()
            WindowManager.shared.showProviders(model: providers)
            await wait(3)
            if let w = NSApp.windows.first(where: { $0.identifier?.rawValue == "providers" }) { capture(w, dir.appendingPathComponent("live-providers.png")) }
            providers.newOpenRouter()
            await wait(1.5)
            if let w = NSApp.windows.first(where: { $0.identifier?.rawValue == "providers" }) { capture(w, dir.appendingPathComponent("live-providers-editor.png")) }
            providers.cancel()
            dump("providers")
            try? report.joined(separator: "\n").write(to: dir.appendingPathComponent("report.txt"), atomically: true, encoding: .utf8)
        }
    }

    /// The AppKit item's button. (SwiftUI keeps a hidden status item of its own, off screen, so
    /// searching the windows for a status button could find that one instead.)
    @MainActor
    private static func statusButton() -> NSStatusBarButton? { StatusItemController.shared.debugButton }

    @MainActor
    private static func capture(_ w: NSWindow, _ url: URL) {
        guard let v = w.contentView?.superview ?? w.contentView else { return }
        if let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds) {
            v.cacheDisplay(in: v.bounds, to: rep)
            try? rep.representation(using: .png, properties: [:])?.write(to: url)
        }
        // Also a CALayer render, which sees layer-backed SwiftUI content cacheDisplay can miss.
        if let layer = v.layer {
            let scale = w.backingScaleFactor
            let size = CGSize(width: v.bounds.width * scale, height: v.bounds.height * scale)
            if let ctx = CGContext(data: nil, width: Int(size.width), height: Int(size.height), bitsPerComponent: 8, bytesPerRow: 0,
                                   space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) {
                ctx.scaleBy(x: scale, y: scale)
                if !v.isFlipped { } else { ctx.translateBy(x: 0, y: v.bounds.height); ctx.scaleBy(x: 1, y: -1) }
                layer.render(in: ctx)
                if let img = ctx.makeImage(), let png = NSBitmapImageRep(cgImage: img).representation(using: .png, properties: [:]) {
                    try? png.write(to: url.deletingPathExtension().appendingPathExtension("layer.png"))
                }
            }
        }
    }
}
