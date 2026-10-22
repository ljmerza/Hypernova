import AppKit
import ImageIO
import SwiftUI
import UniformTypeIdentifiers

/// `Hypernova --render-popover <dir> [--live]`: draws the menu bar popover in its main states with
/// sample data, plus the menu bar icon's frames, to PNGs (and the icon's motion to GIFs), without
/// touching the daemon. The samples are protocol-shaped JSON run through the app's own decoders.
/// `--live` adds one render from a running daemon (point it at a test one with HYPERNOVA_SUPPORT,
/// HYPERNOVA_API_PORT and HYPERNOVA_LOCAL_PORT).
extension DevTools {
    static func renderPopover(into dir: URL, live: Bool) -> Int32 {
        MainActor.assumeIsolated { PopoverRender(dir: dir).run(live: live) }
    }

    /// `Hypernova --popover-selftest <dir>`: the real status item and panel, on sample data (the
    /// daemon is never asked), for about 15 seconds: the icon turns while sessions work and stops
    /// on its rest frame after, the badge shows, the panel opens under the icon at its content's
    /// height and closes again. Prints each check and the CPU it used, and saves the open panel.
    /// Runs inside the normal app lifecycle (NSApp.run), so timings and CPU are the real ones;
    /// the app delegate starts it instead of the usual launch when this is set.
    nonisolated(unsafe) static var popoverSelfTestDir: URL?

    @MainActor
    static func runPopoverSelfTest(into dir: URL) async -> Int32 { await PopoverSelfTest(dir: dir).run() }
}

@MainActor
private struct PopoverSelfTest {
    let dir: URL

    func run() async -> Int32 {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var failures = 0
        func check(_ ok: Bool, _ what: String) { print("\(ok ? "ok  " : "FAIL") \(what)"); if !ok { failures += 1 } }
        func spin(_ s: Double) async { try? await Task.sleep(nanoseconds: UInt64(s * 1_000_000_000)) }
        func cpu() -> Double {
            var u = rusage(); getrusage(RUSAGE_SELF, &u)
            return Double(u.ru_utime.tv_sec + u.ru_stime.tv_sec) + Double(u.ru_utime.tv_usec + u.ru_stime.tv_usec) / 1e6
        }
        let samples = Samples(now: Date())
        let model = AppModel.shared
        let c = StatusItemController.shared

        _ = samples.model(sessions: [samples.hyperscribeIdle], usage: samples.usage(23, 41), into: model)
        c.install()
        await spin(0.5)
        guard let button = c.debugButton else { print("FAIL no status button"); return 1 }
        // SwiftUI's hidden MenuBarExtra keeps the app and Edit menus (paste in Setup and
        // Providers) without a second item in the menu bar.
        // (The hidden one SwiftUI keeps sits off screen.)
        let statusWindows = NSApp.windows.filter { w in String(describing: type(of: w)).contains("StatusBar") && NSScreen.screens.contains { $0.frame.intersects(w.frame) } }.count
        check(statusWindows == 1, "one item in the menu bar (\(statusWindows))")
        let paste = NSApp.mainMenu?.items.contains { $0.submenu?.items.contains { $0.action == #selector(NSText.paste(_:)) } == true } == true
        check(paste, "the Edit menu is there (paste)")
        check(button.image === MenuBarFrames.rest(badge: false), "idle: the still planet")
        check(!c.debugAnimating, "idle: no timer")

        // Work starts: it turns.
        _ = samples.model(sessions: [samples.novaWorking, samples.stemsWorking], usage: samples.usage(64, 52), into: model)
        await spin(0.3)
        var seen = Set<ObjectIdentifier>()
        for _ in 0..<40 { await spin(0.05); if let i = button.image { seen.insert(ObjectIdentifier(i)) } }
        check(seen.count >= 6, "working: turning (\(seen.count) distinct frames in 2 s)")
        var t0 = cpu()
        await spin(4)
        var t1 = cpu()
        print("     cpu while turning, popover closed: \(String(format: "%.1f", (t1 - t0) / 4 * 100))% of one core")
        check(button.accessibilityLabel()?.contains("2 sessions working") == true, "working: label says so (\(button.accessibilityLabel() ?? ""))")

        // The screen locks while it works: nobody sees it, so it stops; unlocked, it turns again.
        c.debugScreen(locked: true)
        await spin(0.3)
        check(!c.debugAnimating, "locked: no timer while sessions work")
        c.debugScreen(locked: false)
        await spin(0.3)
        check(c.debugAnimating, "unlocked: turning again")

        // Work stops: it slows onto the rest frame and the timer stops.
        _ = samples.model(sessions: [samples.hyperscribeIdle], usage: samples.usage(64, 52), into: model)
        await spin(3.2)
        check(button.image === MenuBarFrames.rest(badge: false), "stopped: back on the rest frame")
        check(!c.debugAnimating, "stopped: timer gone")
        t0 = cpu(); await spin(2); t1 = cpu()
        print("     cpu while still: \(String(format: "%.2f", (t1 - t0) / 2 * 100))%")

        // Something needs you: the badge.
        _ = samples.model(sessions: [samples.novaNeedsYou], approvals: [samples.gitPushJSON], usage: samples.usage(71, 55), into: model)
        await spin(0.3)
        check(button.image === MenuBarFrames.rest(badge: true), "needs you: badge")
        check(button.title.isEmpty, "needs you (one): no count")

        // Signed out: the dot, though nothing is waiting, and the words for it.
        _ = samples.model(sessions: [samples.hyperscribeIdle], usage: samples.usage(64, 52, source: "stale"), account: samples.signedOut, into: model)
        await spin(0.3)
        check(button.image === MenuBarFrames.rest(badge: true), "signed out: the dot")
        check(button.title.isEmpty, "signed out: no count")
        check(button.accessibilityLabel()?.contains("signed out") == true, "signed out: label says so (\(button.accessibilityLabel() ?? ""))")
        // Codex alone, signed out: the dot, in Codex's words; Claude Code's account (which the daemon
        // still reads as signed out) isn't said on a Mac without it.
        _ = samples.model(sessions: [samples.codexIdle], usage: nil, account: samples.signedOut, codex: samples.codex(signedIn: false), claude: false, into: model)
        await spin(0.3)
        let soloLabel = button.accessibilityLabel() ?? ""
        check(button.image === MenuBarFrames.rest(badge: true) && soloLabel.contains("Codex is signed out") && !soloLabel.contains("Claude"), "Codex alone, signed out: the dot, in Codex's words (\(soloLabel))")
        _ = samples.model(sessions: [samples.codexIdle], usage: nil, account: samples.signedOut, codex: samples.codex(), claude: false, into: model)
        await spin(0.3)
        check(button.image === MenuBarFrames.rest(badge: false), "Codex alone and signed in: no dot (Claude Code's sign-out is nothing here)")
        // At a limit it lifts by itself: no dot.
        _ = samples.model(sessions: [samples.hyperscribeIdle], usage: samples.usage(100, 58), account: samples.sessionLimit, into: model)
        await spin(0.3)
        check(button.image === MenuBarFrames.rest(badge: false), "session limit: no dot")
        _ = samples.model(sessions: [samples.novaNeedsYou], approvals: [samples.gitPushJSON], usage: samples.usage(71, 55), into: model)
        await spin(0.3)

        // The popover.
        c.open()
        await spin(1.0)
        guard let panel = c.debugPanel else { print("FAIL no panel"); return 1 }
        check(panel.isVisible, "popover: open")
        check(model.popoverVisible, "popover: the model knows (fast polling)")
        if let bw = button.window {
            let b = bw.convertToScreen(button.convert(button.bounds, to: nil))
            let barBottom = min(b.minY, bw.screen?.visibleFrame.maxY ?? b.minY)
            check(abs(panel.frame.maxY - (barBottom - 5)) < 1.5, "popover: hangs 5pt under the menu bar (top \(panel.frame.maxY), bar bottom \(barBottom))")
            check(panel.frame.minX <= b.midX && panel.frame.maxX >= b.midX, "popover: under the icon")
        }
        let fit = c.debugFittingHeight
        check(abs(panel.frame.height - min(fit, c.debugMaxHeight)) < 1.5, "popover: sized to its content (\(Int(panel.frame.height)) for \(Int(fit)), max \(Int(c.debugMaxHeight)))")
        t0 = cpu(); await spin(2); t1 = cpu()
        print("     cpu with the popover open (sky, grain, meters): \(String(format: "%.1f", (t1 - t0) / 2 * 100))%")
        if let v = panel.contentView, let rep = v.bitmapImageRepForCachingDisplay(in: v.bounds) {
            v.cacheDisplay(in: v.bounds, to: rep)
            let url = dir.appendingPathComponent("selftest-live-panel.png")
            try? rep.representation(using: .png, properties: [:])?.write(to: url)
            print("     wrote \(url.path)")
        }
        // An answer from here folds the card and grows/shrinks the panel with it.
        let before = panel.frame.height
        _ = samples.model(sessions: [samples.novaNeedsYou], usage: samples.usage(71, 55), into: model)
        await spin(0.6)
        check(panel.frame.height < before - 40, "popover: follows its content when the approval goes (\(Int(before)) → \(Int(panel.frame.height)))")
        check(abs(panel.frame.maxY - (c.debugAnchorTop ?? 0)) < 1.5, "popover: the top edge stays put")
        // The battery runs low: the header's line shows and the panel grows by it; charging, it goes.
        let calm = panel.frame.height
        _ = samples.model(sessions: [samples.novaNeedsYou], usage: samples.usage(71, 55), battery: samples.battery(14, minutesLeft: 25), into: model)
        await spin(0.8)
        check(model.lowBattery?.shown == 14, "battery low: the model says so")
        check(panel.frame.height > calm + 10 && panel.frame.height < calm + 40, "battery low: the header's line shows (\(Int(calm)) → \(Int(panel.frame.height)))")
        _ = samples.model(sessions: [samples.novaNeedsYou], usage: samples.usage(71, 55), battery: samples.battery(14, charging: true, onAC: true), into: model)
        await spin(0.8)
        check(model.lowBattery == nil && abs(panel.frame.height - calm) < 1.5, "battery charging: the line goes (\(Int(panel.frame.height)))")

        c.close()
        await spin(0.3)
        check(!panel.isVisible, "popover: closed")
        check(!model.popoverVisible, "popover: the model knows (slow polling)")
        print(failures == 0 ? "all good" : "\(failures) failure(s)")
        return failures == 0 ? 0 : 1
    }
}

@MainActor
private struct PopoverRender {
    let dir: URL
    let now = Date()

    func run(live: Bool) -> Int32 {
        _ = NSApplication.shared
        NSApp.setActivationPolicy(.prohibited)
        SnapshotMode.active = true
        do { try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true) } catch {
            print("can't create \(dir.path): \(error.localizedDescription)"); return 1
        }
        var failed = 0
        func popover(_ name: String, _ model: AppModel, page: MenuPopover.Page = .main, pairing: PopoverPairing? = nil) {
            let view = PopoverScene { MenuPopover(page: page, pairing: pairing).environmentObject(model) }
            if !write(name, view) { failed += 1 }
        }
        let s = Samples(now: now)

        // 1. Idle: two quiet sessions, a phone paired.
        popover("popover-1-idle", s.model(sessions: [s.hyperscribeIdle, s.stemsIdle], usage: s.usage(23, 41)))
        // 2. Working: three sessions, two of them busy (one open on the phone), spending fast enough
        // to reach the 5-hour limit before its reset.
        popover("popover-2-working", s.model(sessions: [s.novaWorking, s.stemsWorking, s.hyperscribeIdle], usage: s.usage(64, 52, pace5: 0.8, pace7: 0.008)))
        // 3. One approval waiting.
        popover("popover-3-approval", s.model(sessions: [s.novaNeedsYou, s.stemsWorking, s.hyperscribeIdle], approvals: [s.gitPushJSON], usage: s.usage(71, 55)))
        // 4. The service isn't answering.
        let down = AppModel()
        down.loadSample(health: nil, snapshot: nil, status: nil)
        popover("popover-4-service-down", down)
        // 5. A question, a plan, and an approval just answered (folded), with an "all this session" offer.
        popover("popover-5-questions", s.model(sessions: [s.novaNeedsYou, s.stemsAsks, s.hyperscribeIdle], approvals: [s.question, s.plan, s.webFetch],
                                              usage: s.usage(48, 33), answered: [AppModel.Answered(approval: s.gitPush, decision: "allow", at: now)]))
        // 6–7. Pairing: the code, then a device that just paired.
        let pairModel = s.model(sessions: [s.novaWorking], usage: s.usage(23, 41))
        popover("popover-6-pair", pairModel, page: .pair, pairing: PopoverPairing(sample: .showing, pair: s.pairStart))
        popover("popover-7-paired", pairModel, page: .pair, pairing: PopoverPairing(sample: .paired("Nothing Phone (3)")))
        // 8. Settings.
        popover("popover-8-settings", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), devices: [s.phone, s.tablet]), page: .settings)
        // Settings' Files part with Browse outside projects on (off above).
        popover("popover-8b-settings-browse-on", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), devices: [s.phone, s.tablet], browseOutside: true), page: .settings)
        // 9. Notices: a newer service bundled, no phone yet, the Tailscale key about to expire; nothing running.
        popover("popover-9-notices", s.model(sessions: [], usage: nil, devices: [], keyExpiresInDays: 5, serviceUpdate: true))
        // 10–13. The account: signed out (usage not measured), at the session limit (and a session
        // on a model that needs usage credits), signing in from here, and a plan that ended.
        popover("popover-10-signed-out", s.model(sessions: [s.novaSignedOut, s.hyperscribeIdle], usage: s.usage(64, 52, source: "stale"), account: s.signedOut))
        popover("popover-11-limit", s.model(sessions: [s.novaLimited, s.stemsNoModel, s.hyperscribeIdle], usage: s.usage(100, 58), account: s.sessionLimit))
        popover("popover-12-signing-in", s.model(sessions: [s.hyperscribeIdle], usage: s.usage(64, 52, source: "stale"), account: s.signingIn))
        popover("popover-13-plan-ended", s.model(sessions: [s.novaWorking], approvals: [s.gitPushJSON], usage: s.usage(12, 30, source: "stale"), account: s.planEnded))
        // 14–16. The Mac's battery: low and running down while sessions work (the header's amber
        // line), the same on the Settings page, and plugged in but not charging at 9%.
        popover("popover-14-battery-low", s.model(sessions: [s.novaWorking, s.stemsWorking, s.hyperscribeIdle], usage: s.usage(64, 52), battery: s.battery(14, minutesLeft: 25)))
        popover("popover-15-battery-settings", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), devices: [s.phone, s.tablet], battery: s.battery(14, minutesLeft: 25)), page: .settings)
        popover("popover-16-battery-weak-charger", s.model(sessions: [s.novaNeedsYou, s.hyperscribeIdle], approvals: [s.gitPushJSON], usage: s.usage(71, 55), battery: s.battery(9, onAC: true)))
        // 17–22. Stay awake: Backpack in a closed bag; Backpack chosen before its setup (the offer);
        // the battery guard counting down; let go at 14%; Settings' part; a desktop Mac (no Backpack).
        popover("popover-17-backpack", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(64, 52), battery: s.battery(62, minutesLeft: 250),
                                                awake: s.awake("backpack", lidClosedOk: true, ready: true, until: 6 * 3600 + 25 * 60)))
        StayAwakeSection.sampleOffer = true
        popover("popover-18-backpack-offer", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), awake: s.awake("working")))
        StayAwakeSection.sampleOffer = false
        popover("popover-19-sleep-soon", s.model(sessions: [s.novaWorking, s.stemsWorking], usage: s.usage(64, 52), battery: s.battery(15, minutesLeft: 22),
                                                 awake: s.awake("backpack", lidClosed: true, lidClosedOk: true, ready: true, until: 5 * 3600, reason: "battery", sleepIn: 118)))
        popover("popover-20-let-go", s.model(sessions: [s.hyperscribeIdle], usage: s.usage(64, 52), battery: s.battery(14, minutesLeft: 20),
                                             awake: s.awake("working", holding: false, reason: "battery")))
        popover("popover-21-awake-settings", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), devices: [s.phone, s.tablet],
                                                     awake: s.awake("backpack", ready: true, until: 7 * 3600)), page: .settings)
        popover("popover-22-desktop", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 41), battery: "null",
                                              awake: s.awake("working", laptop: false)))
        // 23. Let go at 15% while Amphetamine's SleepDisabled keeps the Mac awake: said, not "sleeping".
        popover("popover-23-let-go-amphetamine", s.model(sessions: [s.hyperscribeIdle], usage: s.usage(64, 52), battery: s.battery(15, minutesLeft: 22),
                                                         awake: s.awake("backpack", holding: false, lidClosed: true, lidClosedOk: true, ready: true, until: 5 * 3600,
                                                                        reason: "battery", otherApp: "Amphetamine")))

        // 24. Set up before voice existed, and never answered: the one quiet offer.
        VoiceModel.shared.loadSample(stt: nil)
        popover("popover-24-voice-notice", s.model(sessions: [s.hyperscribeIdle, s.stemsIdle], usage: s.usage(23, 41), devices: [s.phone]))
        VoiceModel.shared.endSample()

        // 25–27. Carrying on after a limit, and other providers: Claude Code carries on by itself at
        // 2:00 (Don't), one turned off (Continue automatically); OpenRouter out of credits with its
        // balance (Add credits) beside a session that keeps working there; a rate limit the Mac
        // carries on from by itself, and credit back (Continue).
        popover("popover-25-auto-continue", s.model(sessions: [s.novaArmed, s.stemsOff, s.docsOnOpenRouter], usage: s.usage(100, 58), account: s.sessionLimit, providers: [s.openRouter()]))
        popover("popover-26-provider-credits", s.model(sessions: [s.widgetOutOfCredits, s.novaWorking], usage: s.usage(41, 30), providers: [s.openRouter(status: s.outOfCredits, remaining: "0")]))
        popover("popover-27-provider-rate", s.model(sessions: [s.docsRateLimited, s.widgetReady, s.hyperscribeIdle], usage: s.usage(41, 30), providers: [s.openRouter(status: s.rateLimited)]))

        // 28–32. Automations: the next run (one quiet line), one working (its session wears AUTO, the
        // line counts its minutes), two at once, paused (with Resume), and Settings' switch.
        popover("popover-28-automation-next", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 41), automations: s.autoNext))
        popover("popover-29-automation-running", s.model(sessions: [s.briefRunning, s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 41), automations: s.autoRunning(1)))
        popover("popover-30-automation-two", s.model(sessions: [s.briefRunning, s.prsRunning, s.hyperscribeIdle], usage: s.usage(41, 30), automations: s.autoRunning(2)))
        popover("popover-31-automation-paused", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 41), automations: s.autoPaused))
        popover("popover-32-automation-settings", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), devices: [s.phone, s.tablet], automations: s.autoNext), page: .settings)
        popover("popover-33-automation-settings-paused", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), devices: [s.phone, s.tablet], automations: s.autoPaused), page: .settings)
        popover("popover-34-automation-waiting", s.model(sessions: [s.hyperscribeIdle], usage: s.usage(23, 41), automations: s.autoEventOnly))
        popover("popover-35-automation-old-daemon", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 41)))

        // 60–61. Workflows: a run waiting at an ask step is an attention card (the answers as buttons, the
        // default and its countdown in one line), next to an approval; Settings with its Edit button.
        popover("popover-60-ask", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 41), automations: s.autoAsk))
        popover("popover-61-ask-and-approval", s.model(sessions: [s.novaNeedsYou, s.hyperscribeIdle], approvals: [s.gitPushJSON], usage: s.usage(48, 33), automations: s.autoAsk))
        popover("popover-62-workflows-settings", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), devices: [s.phone, s.tablet], automations: s.autoAsk), page: .settings)
        failed += WorkflowRender(dir: dir, now: now).run()

        // 36. A reply being read aloud (on the phone, or the glasses through it): the wave on its row.
        Speaking.shared.loadSample(["s-scribe": 0.85])
        popover("popover-36-speaking", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 41)))
        Speaking.shared.endSample()

        // 37–38. The weeks some models have of their own, under the week: Sonnet and Fable, then Fable
        // nearly used up (amber) while the 5-hour window is calm.
        popover("popover-37-model-weeks", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 41, models: s.modelWeeks(fable: 41))))
        popover("popover-38-fable-near-limit", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 62, models: s.modelWeeks(fable: 91))))

        // 39–44. Spending as projection meters: idle (no pace, so no faint fill: the reset tag); a slow
        // pace (a short faint fill); a fast one that still ends before 100% (a long faint fill); one that
        // reaches the limit before the reset (the faint fill to the end, the red notch, "45m"); the
        // same near the limit (amber), above the per-model weeks (plain: no pace); at the limit.
        let usageStates: [(String, AppModel)] = [
            ("popover-39-usage-idle", s.model(sessions: [s.hyperscribeIdle, s.stemsIdle], usage: s.usage(38, 47))),
            ("popover-40-usage-slow-pace", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(38, 47, pace5: 0.12, pace7: 0.002))),
            ("popover-41-usage-fast-pace", s.model(sessions: [s.novaWorking, s.stemsWorking], usage: s.usage(22, 47, pace5: 0.85, pace7: 0.006))),
            ("popover-42-usage-limit-before-reset", s.model(sessions: [s.novaWorking, s.stemsWorking], usage: s.usage(64, 52, pace5: 0.8, pace7: 0.008))),
            ("popover-43-usage-near-limit", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(86, 81, models: s.modelWeeks(fable: 91), pace5: 0.35, pace7: 0.02))),
            ("popover-44-usage-at-limit", s.model(sessions: [s.novaLimited, s.docsOnOpenRouter], usage: s.usage(100, 58, pace7: 0.001), account: s.sessionLimit, providers: [s.openRouter()])),
        ]
        for (name, m) in usageStates {
            popover(name, m)
            // What VoiceOver reads, one sentence a meter.
            for meter in UsageSection.meters(m.usage, now: now) { print("    \(meter.label): \(meter.reading.sentence)") }
        }
        // 45. Other providers' trouble under Usage: a chip by the name, the time it lifts on the right.
        popover("popover-45-usage-provider-limited", s.model(sessions: [s.docsRateLimited, s.novaWorking], usage: s.usage(41, 30, pace5: 0.2),
                                                             providers: [s.openRouter(status: s.rateLimitedLong)]))

        // 46–57. One design across phone, Mac and glasses (owner's audit, 2026-10-18): Hypernova's own
        // Tailscale node serving (phone access on, its account and node as chips) and Settings with
        // it; a code that pairs over this Wi‑Fi alone; the Tailscale app signed out with the local
        // route on; no phone paired yet with more sessions than fit; agents, a queued prompt and a
        // session in the home folder; an automation's run that needs you; OpenRouter refusing its
        // key (Fix key); the service's Node.js gone (Repair); and the glass dialogs.
        popover("popover-46-built-in-tailscale", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 41), devices: [s.phoneWithGlasses], tailnet: .builtIn))
        popover("popover-47-built-in-settings", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), devices: [s.phoneWithGlasses, s.tablet], tailnet: .builtIn, glasses: true), page: .settings)
        popover("popover-48-pair-on-wifi", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), devices: [], tailnet: .none, lan: true),
                page: .pair, pairing: PopoverPairing(sample: .showing, pair: s.pairStartLocal, localOnly: true))
        popover("popover-49-signed-out-wifi", s.model(sessions: [s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 41), tailnet: .appSignedOut, lan: true))
        popover("popover-50-signed-out-wifi-settings", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), tailnet: .appSignedOut, lan: true), page: .settings)
        popover("popover-51-no-phone-many", s.model(sessions: s.many, usage: s.usage(38, 47), devices: []))
        popover("popover-52-agents-home", s.model(sessions: [s.novaAgents, s.homeIdle, s.stemsIdle], usage: s.usage(38, 47, models: s.modelWeeks(fable: 41))))
        popover("popover-53-automation-needs-you", s.model(sessions: [s.briefNeedsYou, s.hyperscribeIdle], usage: s.usage(23, 41), automations: s.autoRunning(1)))
        popover("popover-54-provider-bad-key", s.model(sessions: [s.widgetBadKey, s.novaWorking], usage: s.usage(41, 30), providers: [s.openRouter(status: s.badKey)]))
        let gone = AppModel()
        gone.loadSample(health: nil, snapshot: nil, status: nil, nodeGone: Paths.home.appendingPathComponent(".nvm/versions/node/v25.9.0/bin/node").path)
        popover("popover-55-node-gone", gone)
        let forget = GlassDialog(eyebrow: "Forget a phone", title: "Forget Pixel Tablet?", line: "It can't reach this Mac until it pairs again.",
                                 more: "Its token stops working at once, on Tailscale and on the local network. Glasses that come through it stop too.",
                                 accent: .hnDanger, secondary: "Keep", primary: "Forget")
        popover("popover-56-forget-dialog", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), devices: [s.phoneWithGlasses, s.tablet], dialog: forget, glasses: true), page: .settings)
        let uninstall = s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), devices: [s.phone])
        uninstall.askUninstall()
        popover("popover-57-uninstall-dialog", uninstall, page: .settings)

        // 58–59. The weekly limit (owner, 2026-10-18: the honest low state): the meters are the
        // source of truth, the week's 100% in red with when it comes back, the account's line says
        // it; with the 5-hour window spent too, the week's limit is the one the account names.
        for (name, m) in [
            ("popover-58-week-limit", s.model(sessions: [s.novaWeekLimited, s.docsOnOpenRouter, s.hyperscribeIdle], usage: s.usage(64, 100), account: s.weekLimit, providers: [s.openRouter()])),
            ("popover-59-week-and-session-limit", s.model(sessions: [s.novaWeekLimited, s.hyperscribeIdle], usage: s.usage(100, 100), account: s.weekLimit)),
        ] {
            popover(name, m)
            for meter in UsageSection.meters(m.usage, now: now) { print("    \(meter.label): \(meter.reading.sentence)") }
        }

        // 63–68. Codex as a second agent: a Codex session at work (its CODEX chip, its sandbox's tag, no
        // terminal yet but it opens) with Codex's windows under Claude's; a conversation that went on in
        // Codex (the Claude Code session it left is off the list); Codex's week used up (a plan with only
        // the week); Codex signed out (a sentence, amber); Codex installed but off: exactly as before.
        let codexStates: [(String, AppModel)] = [
            ("popover-63-codex", s.model(sessions: [s.codexWorking, s.novaWorking, s.hyperscribeIdle], usage: s.usage(38, 47, pace5: 0.12, pace7: 0.002), codex: s.codex())),
            ("popover-64-codex-switched", s.model(sessions: [s.codexContinued, s.claudeSwitched, s.hyperscribeIdle], usage: s.usage(23, 41), codex: s.codex(five: 8, week: 22))),
            ("popover-65-codex-week-limit", s.model(sessions: [s.codexIdle, s.novaWorking], usage: s.usage(38, 47), codex: s.codex(five: nil, week: 100, limited: true))),
            ("popover-66-codex-signed-out", s.model(sessions: [s.codexIdle, s.hyperscribeIdle], usage: s.usage(23, 41), codex: s.codex(signedIn: false))),
            ("popover-67-codex-off", s.model(sessions: [s.codexIdle, s.novaWorking, s.hyperscribeIdle], usage: s.usage(23, 41), codex: s.codex(enabled: false))),
            ("popover-68-codex-too-old", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41),
                                                 codex: s.codex(problem: "Hypernova needs Codex 0.163.0 or later (this one is 0.150.2).", version: "0.150.2"))),
        ]
        for (name, m) in codexStates {
            popover(name, m)
            if let c = m.codex { for meter in UsageSection.codexMeters(c, now: now) { print("    \(meter.label) [\(meter.chip ?? "")]: \(meter.reading.sentence)") } }
        }
        // 69–76. The agents (docs/PROTOCOL.md, "Which agent leads"). Codex alone (no Claude Code here):
        // its meters plain, no CODEX chips, nothing of Claude Code's (the daemon still says Claude's
        // account is signed out: never shown); Codex alone asking to run a command; Codex alone signed
        // out (the account's card with Sign in, the dot); Codex alone and off (Setup's notice, no
        // USAGE); no agent at all; both in use: Claude's meters wear CLAUDE, its sign-out names Claude
        // Code, a Claude limit beside Codex's windows; Codex alone on an API key.
        let agentStates: [(String, AppModel)] = [
            ("popover-69-codex-only", s.model(sessions: [s.codexWorking, s.codexDocs], usage: nil, account: s.signedOut, codex: s.codex(), claude: false)),
            ("popover-70-codex-only-approval", s.model(sessions: [s.codexNeedsYou, s.codexDocs], approvals: [s.codexCommand], usage: nil, codex: s.codex(five: 71, week: 64), claude: false)),
            ("popover-71-codex-only-signed-out", s.model(sessions: [s.codexIdle], usage: nil, account: s.signedOut, codex: s.codex(signedIn: false), claude: false)),
            ("popover-72-codex-only-off", s.model(sessions: [], usage: nil, codex: s.codex(enabled: false), claude: false)),
            ("popover-73-no-agent", s.model(sessions: [], usage: nil, codex: nil, claude: false)),
            ("popover-74-both", s.model(sessions: [s.novaWorking, s.codexWorking, s.hyperscribeIdle], usage: s.usage(38, 47, models: s.modelWeeks(fable: 41), pace5: 0.12, pace7: 0.002), codex: s.codex())),
            ("popover-75-both-claude-signed-out", s.model(sessions: [s.novaSignedOut, s.codexWorking], usage: s.usage(64, 52, source: "stale"), account: s.signedOut, codex: s.codex())),
            ("popover-76-both-claude-limit", s.model(sessions: [s.novaLimited, s.codexWorking], usage: s.usage(100, 58), account: s.sessionLimit, codex: s.codex(five: 12, week: 30))),
            ("popover-77-codex-only-api-key", s.model(sessions: [s.codexIdle], usage: nil, codex: s.codex(apiKey: true), claude: false)),
        ]
        for (name, m) in agentStates {
            popover(name, m)
            let r = m.agents
            print("    agents: leading \(r.leading.map { "\($0)" } ?? "none"), both \(r.both), codex only \(r.codexOnly), tooltip trouble \(m.accountTrouble ?? "none")")
        }
        popover("popover-78-codex-only-settings", s.model(sessions: [s.codexWorking], usage: nil, codex: s.codex(), claude: false), page: .settings)

        // The Agents & providers window: Claude Code alone (Codex not installed: the lines to run),
        // Codex installed but off, both in use, Codex alone (providers are Claude Code's), Codex on an
        // API key and too old beside a signed-out Claude Code.
        let agentWindows: [(String, AppModel)] = [
            ("agents-1-claude-only", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41))),
            ("agents-2-codex-off", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), codex: s.codex(enabled: false))),
            ("agents-3-both", s.model(sessions: [s.novaWorking, s.codexWorking], usage: s.usage(23, 41), codex: s.codex())),
            ("agents-4-codex-only", s.model(sessions: [s.codexWorking], usage: nil, codex: s.codex(), claude: false)),
            ("agents-5-api-key-claude-signed-out", s.model(sessions: [s.novaSignedOut], usage: s.usage(64, 52, source: "stale"), account: s.signedOut, codex: s.codex(apiKey: true))),
            ("agents-6-codex-too-old", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), codex: s.codex(enabled: false, version: "0.150.2"))),
            ("agents-7-codex-signed-out", s.model(sessions: [s.novaWorking], usage: s.usage(23, 41), codex: s.codex(signedIn: false))),
        ]
        for (name, m) in agentWindows {
            // Taller than the window (760pt, which scrolls), so the whole list shows.
            let view = ProvidersView(model: ProvidersModel(sample: s.providerList), app: m).frame(width: 720, height: 1300)
            if !write(name, view) { failed += 1 }
        }

        // Setup's Codex step: not installed (one low-key card), found but off, on and signed in, on but
        // signed out, too old.
        failed += CodexSetupRender(dir: dir).run([
            ("setup-codex-1-not-installed", "null"),
            ("setup-codex-2-found-off", s.codex(enabled: false)),
            ("setup-codex-3-on", s.codex()),
            ("setup-codex-4-signed-out", s.codex(signedIn: false)),
            ("setup-codex-5-too-old", s.codex(enabled: false, problem: "Hypernova needs Codex 0.163.0 or later (this one is 0.150.2).", version: "0.150.2")),
        ])
        // Setup on a Mac with Codex and no Claude Code: the Claude Code step offers "Use Codex only"
        // (with the service running, and before it, from what Setup found itself); with Codex on, the
        // Claude Code and Connect steps are skipped (the rail says so) and the ledes speak of Codex.
        failed += CodexSetupRender(dir: dir).codexOnly([
            ("setup-codex-only-1-offer", .claude, true, s.codex(enabled: false)),
            ("setup-codex-only-2-offer-before-service", .claude, false, "null"),
            ("setup-codex-only-3-codex-step", .codex, true, s.codex(enabled: false)),
            ("setup-codex-only-4-codex-on", .codex, true, s.codex()),
            ("setup-codex-only-5-claude-step-chosen", .claude, true, s.codex()),
            ("setup-codex-only-6-connect-skipped", .connect, true, s.codex()),
            ("setup-codex-only-7-service", .service, true, s.codex()),
            // A Codex too old to stand in: update it first (no "Use Codex only", nothing skipped).
            ("setup-codex-only-8-too-old", .claude, true, s.codex(enabled: false, version: "0.150.2")),
        ])

        if live { failed += renderLive() }
        failed += renderIcons()
        print(failed == 0 ? "done: \(dir.path)" : "done with \(failed) failure(s)")
        return failed == 0 ? 0 : 1
    }

    /// One render from a real daemon, through the same calls the app makes.
    private func renderLive() -> Int {
        let m = AppModel()
        var done = false
        Task { @MainActor in await m.refresh(); done = true }
        let deadline = Date().addingTimeInterval(20)
        while !done && Date() < deadline { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
        print("live: daemon \(m.daemonUp ? "up" : "down") at \(Endpoints.local.absoluteString), \(m.sessions.count) live session(s), \(m.approvals.count) approval(s)\(m.lastError.map { ", error: \($0)" } ?? "")")
        return write("popover-live", PopoverScene { MenuPopover().environmentObject(m) }) ? 0 : 1
    }

    // MARK: Writing

    @discardableResult
    private func write<V: View>(_ name: String, _ view: V) -> Bool {
        let r = ImageRenderer(content: view.environment(\.colorScheme, .dark))
        r.scale = 2
        guard let cg = r.cgImage, let png = NSBitmapImageRep(cgImage: cg).representation(using: .png, properties: [:]) else {
            print("render failed: \(name)"); return false
        }
        let url = dir.appendingPathComponent(name + ".png")
        do { try png.write(to: url) } catch { print("write failed: \(url.path)"); return false }
        print("wrote \(url.path)")
        return true
    }

    // MARK: The menu bar icon

    private static let lightBar = NSColor(calibratedWhite: 0.93, alpha: 1)
    private static let darkBar = NSColor(calibratedRed: 0.13, green: 0.12, blue: 0.11, alpha: 1)

    /// A contact sheet of every frame on light and dark menu bars, close-ups, and GIFs of the motion.
    private func renderIcons() -> Int {
        var failed = 0
        struct Cell { var label: String; var frame: MenuBarFrames.Frame; var title: String? = nil }
        var cells: [Cell] = [Cell(label: "off", frame: .hollow), Cell(label: "idle", frame: .spin(0, badge: false))]
        for i in 0..<MenuBarFrames.spinCount { cells.append(Cell(label: i == 0 ? "turning: a quarter turn, 75 ms a frame →" : "", frame: .spin(i, badge: false))) }
        cells.append(Cell(label: "needs you →", frame: .spin(0, badge: true)))
        cells.append(Cell(label: "", frame: .spin(3, badge: true)))
        cells.append(Cell(label: "", frame: .spin(0, badge: true), title: "2"))
        for i in 0..<MenuBarFrames.breathCount { cells.append(Cell(label: i == 0 ? "Reduce Motion, working: breathing →" : "", frame: .breath(i, badge: false))) }

        // 4×: each 18pt icon is 72 px, drawn fresh at that size.
        let k: CGFloat = 4, cellW: CGFloat = 32, rowH: CGFloat = 26, labelH: CGFloat = 16, pad: CGFloat = 10
        let width = pad * 2 + CGFloat(cells.count) * cellW + 12
        let height = pad + labelH + rowH * 2 + pad
        let sheet = bitmap(width: width * k, height: height * k) { cg in
            cg.scaleBy(x: k, y: k)
            cg.setFillColor(NSColor(calibratedWhite: 0.06, alpha: 1).cgColor)
            cg.fill(CGRect(x: 0, y: 0, width: width, height: height))
            for (row, (bar, ink)) in [(Self.lightBar, NSColor.black), (Self.darkBar, NSColor.white)].enumerated() {
                let y = height - pad - labelH - rowH * CGFloat(row + 1)
                cg.setFillColor(bar.cgColor)
                cg.fill(CGRect(x: pad, y: y, width: width - pad * 2, height: rowH))
                for (i, c) in cells.enumerated() {
                    let x = pad + CGFloat(i) * cellW + (cellW - 18) / 2 - (c.title == nil ? 0 : 4)
                    if let t = tinted(c.frame, ink) { cg.draw(t, in: CGRect(x: x, y: y + (rowH - 18) / 2, width: 18, height: 18)) }
                    if let title = c.title {
                        NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(cgContext: cg, flipped: false)
                        (title as NSString).draw(at: NSPoint(x: x + 19, y: y + 5.5), withAttributes: [.font: NSFont.monospacedDigitSystemFont(ofSize: 13, weight: .medium), .foregroundColor: ink])
                        NSGraphicsContext.restoreGraphicsState()
                    }
                }
            }
            NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(cgContext: cg, flipped: false)
            for (i, c) in cells.enumerated() where !c.label.isEmpty {
                (c.label as NSString).draw(at: NSPoint(x: pad + CGFloat(i) * cellW + 3, y: height - pad - labelH + 4),
                                           withAttributes: [.font: NSFont.systemFont(ofSize: 7), .foregroundColor: NSColor(calibratedWhite: 0.75, alpha: 1)])
            }
            NSGraphicsContext.restoreGraphicsState()
        }
        if !save(sheet, "menubar-icons@4x.png") { failed += 1 }

        // Single frames, large, for a close look.
        let singles: [(String, MenuBarFrames.Frame)] = [("menubar-off", .hollow), ("menubar-idle", .spin(0, badge: false)),
                                                        ("menubar-working-mid", .spin(MenuBarFrames.spinCount / 2, badge: false)), ("menubar-needs-you", .spin(0, badge: true))]
        for (name, frame) in singles {
            for (tone, bar, ink) in [("light", Self.lightBar, NSColor.black), ("dark", Self.darkBar, NSColor.white)] {
                let side: CGFloat = 26
                let one = bitmap(width: side * 8, height: side * 8) { cg in
                    cg.scaleBy(x: 8, y: 8)
                    cg.setFillColor(bar.cgColor); cg.fill(CGRect(x: 0, y: 0, width: side, height: side))
                    if let t = tinted(frame, ink, px: 144) { cg.draw(t, in: CGRect(x: 4, y: 4, width: 18, height: 18)) }
                }
                if !save(one, "\(name)-\(tone)@8x.png") { failed += 1 }
            }
        }

        // The motion, as the timer plays it: idle, a session starts (spin-up), works, stops
        // (slows to the rest frame), a request arrives (badge), work with the badge, the service
        // goes away. 75 ms per frame, at 4×.
        var story: [MenuBarFrames.Frame] = []
        var motion = IconMotion()
        let dt = IconMotion.tick
        func add(_ seconds: Double, spinning: Bool, badge: Bool = false, hollow: Bool = false) {
            for _ in 0..<Int((seconds / dt).rounded()) {
                if hollow { motion.stop() } else { motion.step(dt: dt, spinning: spinning) }
                story.append(hollow ? .hollow : .spin(motion.frame, badge: badge))
            }
        }
        add(1.0, spinning: false)
        add(4.0, spinning: true)
        add(3.5, spinning: false)
        add(1.2, spinning: false, badge: true)
        add(3.0, spinning: true, badge: true)
        add(2.5, spinning: false, badge: true)
        add(1.2, spinning: false, hollow: true)
        for (tone, bar, ink) in [("dark", Self.darkBar, NSColor.white), ("light", Self.lightBar, NSColor.black)] {
            let frames = story.compactMap { f in
                bitmap(width: 26 * 4, height: 26 * 4) { cg in
                    cg.scaleBy(x: 4, y: 4)
                    cg.setFillColor(bar.cgColor); cg.fill(CGRect(x: 0, y: 0, width: 26, height: 26))
                    if let t = tinted(f, ink) { cg.draw(t, in: CGRect(x: 4, y: 4, width: 18, height: 18)) }
                }
            }
            if !gif(frames, delay: dt, name: "menubar-motion-\(tone).gif") { failed += 1 }
        }
        return failed
    }

    /// A frame in one colour, as the menu bar draws a template image.
    private func tinted(_ frame: MenuBarFrames.Frame, _ color: NSColor, px: Int = 72) -> CGImage? {
        guard let mask = MenuBarFrames.cgImage(frame, px: px) else { return nil }
        return bitmap(width: CGFloat(px), height: CGFloat(px)) { cg in
            cg.clip(to: CGRect(x: 0, y: 0, width: px, height: px), mask: mask)
            cg.setFillColor(color.cgColor)
            cg.fill(CGRect(x: 0, y: 0, width: px, height: px))
        }
    }

    private func bitmap(width: CGFloat, height: CGFloat, _ draw: (CGContext) -> Void) -> CGImage? {
        guard let cg = CGContext(data: nil, width: Int(width), height: Int(height), bitsPerComponent: 8, bytesPerRow: 0,
                                 space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        draw(cg)
        return cg.makeImage()
    }

    private func save(_ img: CGImage?, _ name: String) -> Bool {
        guard let img, let png = NSBitmapImageRep(cgImage: img).representation(using: .png, properties: [:]) else { print("render failed: \(name)"); return false }
        let url = dir.appendingPathComponent(name)
        do { try png.write(to: url) } catch { print("write failed: \(url.path)"); return false }
        print("wrote \(url.path)")
        return true
    }

    private func gif(_ frames: [CGImage], delay: Double, name: String) -> Bool {
        let url = dir.appendingPathComponent(name)
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.gif.identifier as CFString, frames.count, nil) else { return false }
        CGImageDestinationSetProperties(dest, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFLoopCount: 0]] as CFDictionary)
        for f in frames {
            CGImageDestinationAddImage(dest, f, [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: delay, kCGImagePropertyGIFUnclampedDelayTime: delay]] as CFDictionary)
        }
        let ok = CGImageDestinationFinalize(dest)
        print(ok ? "wrote \(url.path)" : "render failed: \(name)")
        return ok
    }
}

/// The popover as it sits on screen: rounded, ringed, shadowed, over a stand-in desktop.
private struct PopoverScene<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 16, style: .continuous)
        content
            .clipShape(shape)
            .overlay(shape.strokeBorder(Color.hnInk.opacity(0.11), lineWidth: 1))
            .overlay(alignment: .top) {
                LinearGradient(colors: [.clear, Color.hnInk.opacity(0.25), .clear], startPoint: .leading, endPoint: .trailing).frame(height: 1)
            }
            .shadow(color: .black.opacity(0.5), radius: 20, y: 10)
            .padding(32)
            .background(FauxDesktop())
    }
}

// MARK: - Sample data

/// Sessions, approvals and status in the daemon's own JSON shapes (docs/PROTOCOL.md).
@MainActor
private struct Samples {
    let now: Date

    private func ms(_ secondsFromNow: Double) -> Int { Int((now.timeIntervalSince1970 + secondsFromNow) * 1000) }

    private func session(_ id: String, _ title: String, _ project: String, state: String, activity: String? = nil, ago: Double,
                         mac: Int = 1, phone: Int = 0, shared: Bool = true, away: Bool = false, approvals: [String] = [], blocked: String = "null", provider: String = "anthropic", automation: String = "null",
                         model: String = "opus", mode: String = "default", lastText: String? = nil, agents: Int = 0, queued: Int = 0, cwd: String? = nil,
                         agent: String? = nil, switchedFrom: String? = nil, switchedTo: String? = nil) -> String {
        // Only Codex's sessions and switched ones carry these, as the daemon sends them.
        let extra = [agent.map { #""agent":"\#($0)","# }, switchedFrom.map { #""switchedFrom":"\#($0)","# }, switchedTo.map { #""switchedTo":"\#($0)","# }].compactMap { $0 }.joined()
        return """
        {"id":"\(id)",\(extra)"title":"\(title)","cwd":"\(cwd ?? "/Users/you/Programming/\(project)")","projectName":"\(project)","origin":"terminal",
         "state":"\(state)","providerId":"\(provider)","model":"\(model)","permissionMode":"\(mode)",
         "createdAt":\(ms(-ago - 1800)),"lastActivityAt":\(ms(-ago)),"lastText":\(lastText.map { "\"\($0)\"" } ?? "null"),
         "activity":\(activity.map { "\"\($0)\"" } ?? "null"),"pendingApprovalIds":[\(approvals.map { "\"\($0)\"" }.joined(separator: ","))],
         "stoppedForAway":\(away),"contextPct":31,"costUsd":null,"queue":[],"queued":\(queued),"agentsRunning":\(agents),
         "term":\(shared ? "{\"id\":\"t-\(id)\",\"mac\":\(mac),\"phone\":\(phone)}" : "null"),"menu":null,"blocked":\(blocked),"automation":\(automation)}
        """
    }

    var novaWorking: String { session("s-nova", "Spin the menu bar planet while sessions work", "hypernova", state: "working", activity: "Bash: swift build -c release", ago: 3, mode: "acceptEdits") }
    var novaNeedsYou: String { session("s-nova", "Ship the Android build to the phone", "hypernova", state: "needs_you", ago: 40, approvals: ["ap-push"]) }
    var stemsWorking: String { session("s-stems", "Fix the grain overlay in Safari", "0stems", state: "working", activity: "Edit: src/fx/GrainField.tsx", ago: 8, mac: 0, phone: 1, model: "sonnet") }
    var stemsAsks: String { session("s-stems", "Plan the stem export", "0stems", state: "needs_you", ago: 70, mac: 0, phone: 1) }
    var stemsIdle: String { session("s-stems", "Fix the grain overlay in Safari", "0stems", state: "idle", ago: 2 * 3600 + 600, mac: 0, away: true, model: "sonnet") }
    var hyperscribeIdle: String { session("s-scribe", "Draft the release notes for 1.2", "hyperscribe", state: "idle", ago: 14 * 60, shared: false, mode: "plan") }
    /// Its main agent idle while two agents it started work on, with a prompt queued behind them.
    var novaAgents: String {
        session("s-agents", "Agent cards", "hypernova", state: "idle", ago: 4 * 60,
                lastText: "I’ve started an Explore agent on the build, and a workflow that surveys the agent code.", agents: 2, queued: 1)
    }
    /// A session in the home folder: its project is HOME.
    var homeIdle: String { session("s-home", "Tidy the dotfiles", "you", state: "idle", ago: 26 * 60, model: "sonnet", mode: "acceptEdits", cwd: Paths.home.path) }
    /// On OpenRouter, whose key was refused: Fix key.
    var widgetBadKey: String {
        session("s-widget", "Port the widget", "0stems", state: "idle", ago: 4 * 60,
                blocked: #"{"kind":"provider","text":"API Error: 401 No auth credentials found","resetsAt":null,"autoResumeAt":null,"resume":null}"#, provider: "or", model: "openai/gpt-5")
    }
    var briefNeedsYou: String { session("s-brief", "Morning brief, Fri 16 Oct", "hypernova", state: "needs_you", activity: "Bash: gh pr list --state open", ago: 40, automation: autoRef) }
    // Automations (docs/specs/spec-automations.md §3): the sessions they started, and `/local/status.automations`.
    private var autoRef: String { #"{"id":"au_a1b2c3d4e5","name":"Morning brief","runId":"ru_0123456789"}"# }
    var briefRunning: String { session("s-brief", "Morning brief, Fri 16 Oct", "hypernova", state: "working", activity: "Bash: git log --since=yesterday", ago: 4, automation: autoRef) }
    var prsRunning: String { session("s-prs", "Pull requests, Fri 16 Oct", "0stems", state: "working", activity: "Bash: gh pr list", ago: 9, mac: 0,
                                     automation: #"{"id":"au_f6e5d4c3b2","name":"Pull requests","runId":"ru_9876543210"}"#) }
    var autoNext: String { #"{"paused":false,"count":3,"next":{"id":"au_a1b2c3d4e5","name":"Morning brief","at":\#(ms(95 * 60))},"running":[]}"# }
    func autoRunning(_ n: Int) -> String {
        let runs = [#"{"id":"au_a1b2c3d4e5","name":"Morning brief","sessionId":"s-brief","startedAt":\#(ms(-185))}"#,
                    #"{"id":"au_f6e5d4c3b2","name":"Pull requests","sessionId":"s-prs","startedAt":\#(ms(-40))}"#]
        return #"{"paused":false,"count":3,"next":{"id":"au_9","name":"Weekly debrief","at":\#(ms(30 * 3600))},"running":[\#(runs.prefix(n).joined(separator: ","))]}"#
    }
    /// A daemon with workflows (`features`) and a run waiting at an ask step, from the snapshot's list.
    var autoAsk: String {
        #"{"paused":false,"features":["steps","ask","shell","budget","trigger.webhook","trigger.cron","trigger.file","trigger.github","trigger.hook","output.webhook","output.file"],"list":[{"id":"au_rev","name":"Review before merge","enabled":true,"nextAt":null,"lastRun":{"id":"ru_rev1","state":"needs_you","startedAt":\#(ms(-240)),"sessionIds":["s-rev"],"ask":{"stepId":"st_ask","question":"Post this review? Looks good: one nit on parser.ts line 88, where a range is off by one.","answers":["Approve","Skip"],"default":"Skip","until":\#(ms(6 * 60 + 20))}}}]}"#
    }
    var autoPaused: String { #"{"paused":true,"count":3,"next":null,"running":[]}"# }
    /// Only automations that wait for an event (a reset, a plug): no time to name, from the snapshot's list.
    var autoEventOnly: String { #"{"paused":false,"list":[{"id":"au_r1","name":"Carry on after the limit","enabled":true,"nextAt":null,"lastRun":null}]}"# }

    var novaLimited: String {
        session("s-nova", "Ship the Android build to the phone", "hypernova", state: "idle", ago: 6 * 60,
                blocked: #"{"kind":"limit","text":"You've hit your session limit · resets 2am (Europe/Moscow)","resetsAt":\#(ms(72 * 60)),"autoResumeAt":null}"#)
    }
    var novaWeekLimited: String {
        session("s-nova", "Ship the Android build to the phone", "hypernova", state: "idle", ago: 6 * 60,
                blocked: #"{"kind":"limit","text":"You've hit your weekly limit · resets Tue 2pm (Europe/Moscow)","resetsAt":\#(ms(3 * 86_400 + 5 * 3600)),"autoResumeAt":null}"#)
    }
    var stemsNoModel: String {
        session("s-stems", "Plan the stem export", "0stems", state: "idle", ago: 25 * 60, mac: 0,
                blocked: #"{"kind":"model","text":"Fable 5 requires usage credits. Turn them on at claude.ai/settings/usage","resetsAt":null,"autoResumeAt":null}"#)
    }
    // Carrying on after a limit, and OpenRouter's own trouble (docs/PROTOCOL.md, "Auto-continue").
    var novaArmed: String {
        session("s-nova", "Ship the Android build to the phone", "hypernova", state: "idle", ago: 6 * 60,
                blocked: #"{"kind":"limit","text":"You've hit your session limit · resets 2am (Europe/Moscow)","resetsAt":\#(ms(72 * 60)),"autoResumeAt":\#(ms(73 * 60)),"resume":{"state":"armed","at":\#(ms(73 * 60)),"by":"claude","canCancel":true,"canArm":false,"why":null}}"#)
    }
    var stemsOff: String {
        session("s-stems", "Plan the stem export", "0stems", state: "idle", ago: 7 * 60, mac: 0,
                blocked: #"{"kind":"limit","text":"You've hit your session limit · resets 2am (Europe/Moscow)","resetsAt":\#(ms(72 * 60)),"autoResumeAt":null,"resume":{"state":"off","at":\#(ms(72 * 60)),"by":"claude","canCancel":false,"canArm":true,"why":"cancelled"}}"#)
    }
    var widgetOutOfCredits: String {
        session("s-widget", "Port the widget", "0stems", state: "idle", ago: 4 * 60,
                blocked: #"{"kind":"provider","text":"API Error: 402 Insufficient credits. Add more using https://openrouter.ai/settings/credits","resetsAt":null,"autoResumeAt":null,"resume":null}"#, provider: "or", model: "openai/gpt-5")
    }
    var docsOnOpenRouter: String { session("s-docs", "Translate the docs", "prudnikov-site", state: "working", activity: "Edit: content/about.md", ago: 5, mac: 0, provider: "or", model: "openai/gpt-5") }
    var docsRateLimited: String {
        session("s-docs", "Translate the docs", "prudnikov-site", state: "idle", ago: 60, mac: 0,
                blocked: #"{"kind":"provider","text":"API Error: Request rejected (429) · Rate limit exceeded: free-models-per-min.","resetsAt":null,"autoResumeAt":\#(ms(80)),"resume":{"state":"armed","at":\#(ms(80)),"by":"hypernova","canCancel":true,"canArm":false,"why":null}}"#, provider: "or", model: "openai/gpt-5")
    }
    var widgetReady: String {
        session("s-widget", "Port the widget", "0stems", state: "idle", ago: 3 * 60,
                blocked: #"{"kind":"provider","text":"API Error: 402 Insufficient credits.","resetsAt":null,"autoResumeAt":null,"resume":{"state":"ready","at":\#(ms(-30)),"by":"hypernova","canCancel":false,"canArm":false,"why":"back"}}"#, provider: "or", model: "openai/gpt-5")
    }
    func openRouter(status: String = "null", remaining: String = "4.2") -> String {
        #"{"id":"or","name":"OpenRouter","kind":"openrouter","baseUrl":"https://openrouter.ai/api","hasKey":true,"models":{"default":"openai/gpt-5"},"builtIn":false,"status":\#(status),"balance":{"remaining":\#(remaining),"usedToday":0.31,"freeLeft":37,"freeLimit":50,"checkedAt":\#(ms(-20)),"error":null}}"#
    }
    var outOfCredits: String { #"{"state":"no_credit","text":"API Error: 402 Insufficient credits.","since":\#(ms(-240)),"resetsAt":null,"budget":false}"# }
    var badKey: String { #"{"state":"bad_key","text":"API Error: 401 No auth credentials found","since":\#(ms(-240)),"resetsAt":null,"budget":false}"# }
    var rateLimited: String { #"{"state":"limited","text":"API Error: Request rejected (429)","since":\#(ms(-60)),"resetsAt":\#(ms(60)),"budget":false}"# }
    var rateLimitedLong: String { #"{"state":"limited","text":"API Error: Request rejected (429)","since":\#(ms(-60)),"resetsAt":\#(ms(25 * 60)),"budget":false}"# }

    var novaSignedOut: String {
        session("s-nova", "Ship the Android build to the phone", "hypernova", state: "idle", ago: 9 * 60,
                blocked: #"{"kind":"logged_out","text":"Login expired · Please run /login","resetsAt":null,"autoResumeAt":null}"#)
    }

    // Codex as a second agent (docs/PROTOCOL.md, "Codex", "Switching agents"): a thread's id is a
    // uuid v7, its provider "codex", its mode a sandbox.
    /// Started from the phone, so no terminal yet: a click opens one (`codex resume`).
    var codexWorking: String {
        session("019a3f2e-7c41-7b2d-9e10-4f6a8c2d1b3e", "Port the parser to Rust", "hypernova", state: "working", activity: "Bash: cargo test -p parser", ago: 6,
                shared: false, provider: "codex", model: "gpt-5.3-codex", mode: "workspace-write", agent: "codex")
    }
    var codexIdle: String {
        session("019a3f2e-7c41-7b2d-9e10-4f6a8c2d1b3e", "Port the parser to Rust", "hypernova", state: "idle", ago: 9 * 60, shared: false, provider: "codex",
                model: "gpt-5.3-codex", mode: "read-only", lastText: "All 214 parser tests pass on the Rust port.", agent: "codex")
    }
    /// A conversation that went on in Codex: the Claude Code session it came from stays off the list.
    var claudeSwitched: String {
        session("s-review", "Review the sync protocol", "0stems", state: "idle", ago: 20 * 60, mac: 0, lastText: "Here is the review.", switchedTo: "019a40b1-2d3e-7f4a-8b5c-6d7e8f9a0b1c")
    }
    var codexContinued: String {
        session("019a40b1-2d3e-7f4a-8b5c-6d7e8f9a0b1c", "Review the sync protocol", "0stems", state: "idle", ago: 2 * 60, mac: 0, provider: "codex", model: "gpt-5.3-codex",
                mode: "read-only", lastText: "Picked up from Claude’s review: the retry loop can drop a frame.", agent: "codex", switchedFrom: "s-review")
    }
    /// A Mac with Codex alone: a second Codex session, idle, started from the phone.
    var codexDocs: String {
        session("019a41c2-5e6f-7a8b-9c0d-1e2f3a4b5c6d", "Write the changelog", "hyperscribe", state: "idle", ago: 25 * 60, shared: false, provider: "codex",
                model: "gpt-5.3-codex", mode: "read-only", lastText: "The changelog for 1.2 is in CHANGELOG.md.", agent: "codex")
    }
    var codexNeedsYou: String {
        session("019a3f2e-7c41-7b2d-9e10-4f6a8c2d1b3e", "Port the parser to Rust", "hypernova", state: "needs_you", ago: 30, shared: false, approvals: ["ap-cargo"],
                provider: "codex", model: "gpt-5.3-codex", mode: "workspace-write", agent: "codex")
    }
    /// Codex asking to run a command (no Always, no "this session": Codex has neither).
    var codexCommand: String {
        #"{"id":"ap-cargo","sessionId":"019a3f2e-7c41-7b2d-9e10-4f6a8c2d1b3e","createdAt":\#(ms(-30)),"expiresAt":\#(ms(570)),"kind":"tool","toolName":"Bash","summary":"Bash: cargo publish --dry-run","input":{"command":"cargo publish --dry-run"},"reason":"Codex wants to run a command outside the workspace sandbox.","canAlways":false,"canSession":false,"questions":null,"plan":null}"#
    }
    /// Built in, and OpenRouter: the Agents & providers window's list.
    var providerList: [Provider] {
        [decode(#"{"id":"anthropic","name":"Anthropic","kind":"anthropic","baseUrl":null,"hasKey":false,"models":null,"builtIn":true,"status":null,"balance":null}"#), decode(openRouter())]
    }

    /// `Snapshot.codex` / `/local/status.codex`: on, signed in on ChatGPT Plus, its 5-hour window and
    /// week as given (nil: the plan has none), resetting in 2h 40m and 4d 6h.
    func codex(enabled: Bool = true, five: Double? = 34, week: Double? = 61, limited: Bool = false, signedIn: Bool = true, problem: String? = nil, version: String = "0.163.0", apiKey: Bool = false) -> String {
        let available = version >= "0.163.0"
        let w5 = five.map { #"{"pct":\#($0),"resetsAt":\#(ms(160 * 60))}"# } ?? "null"
        let w7 = week.map { #"{"pct":\#($0),"resetsAt":\#(ms(4 * 86_400 + 6 * 3600))}"# } ?? "null"
        // The account and windows are read only while it's on and reachable.
        let reads = enabled && available
        let account = reads ? #"{"signedIn":\#(signedIn),"type":\#(signedIn ? (apiKey ? "\"apiKey\"" : "\"chatgpt\"") : "null"),"planType":\#(signedIn && !apiKey ? "\"plus\"" : "null")}"# : "null"
        // An API key has no windows.
        let usage = reads && signedIn && !apiKey ? #"{"fiveHour":\#(w5),"sevenDay":\#(w7),"limited":\#(limited),"observedAt":\#(ms(-30))}"# : "null"
        return #"{"available":\#(available),"enabled":\#(enabled),"connected":\#(enabled && available),"version":"\#(version)","min":"0.163.0","path":"/opt/homebrew/bin/codex","problem":\#(problem.map { "\"\($0)\"" } ?? "null"),"account":\#(account),"usage":\#(usage)}"#
    }

    // The account (docs/PROTOCOL.md, "Account").
    var signedOut: String {
        #"{"state":"logged_out","providerId":"anthropic","plan":"max","limit":null,"reason":"expired","text":"Login expired · Please run /login","since":\#(ms(-600)),"sessionId":"s-nova","checkedAt":\#(ms(-30)),"login":null}"#
    }
    var sessionLimit: String {
        #"{"state":"limited","providerId":"anthropic","plan":"max","limit":{"window":"five_hour","label":"session limit","resetsAt":\#(ms(72 * 60)),"autoResumeAt":\#(ms(72 * 60)),"usingCredits":false},"reason":null,"text":"You've hit your session limit · resets 2am (Europe/Moscow)","since":\#(ms(-360)),"sessionId":"s-nova","checkedAt":null,"login":null}"#
    }
    /// The weekly limit, ending when the sample week resets (3d 5h).
    var weekLimit: String {
        #"{"state":"limited","providerId":"anthropic","plan":"max","limit":{"window":"seven_day","label":"weekly limit","resetsAt":\#(ms(3 * 86_400 + 5 * 3600)),"autoResumeAt":null,"usingCredits":false},"reason":null,"text":"You've hit your weekly limit · resets Tue 2pm (Europe/Moscow)","since":\#(ms(-3600)),"sessionId":"s-nova","checkedAt":null,"login":null}"#
    }
    var signingIn: String {
        #"{"state":"logged_out","providerId":"anthropic","plan":null,"limit":null,"reason":"not_logged_in","text":null,"since":\#(ms(-900)),"sessionId":null,"checkedAt":\#(ms(-10)),"login":{"state":"waiting_code","url":"https://claude.ai/oauth/authorize?code=true","where":"mac","error":null}}"#
    }
    var planEnded: String {
        #"{"state":"no_subscription","providerId":"anthropic","plan":null,"limit":null,"reason":"ended","text":"Please run /login · API Error: 403 Request not allowed","since":\#(ms(-3600)),"sessionId":"s-nova","checkedAt":\#(ms(-60)),"login":null}"#
    }

    var gitPush: Approval { decode(gitPushJSON) }
    var gitPushJSON: String {
        """
        {"id":"ap-push","sessionId":"s-nova","createdAt":\(ms(-40)),"expiresAt":\(ms(560)),"kind":"tool","toolName":"Bash",
         "summary":"Bash: git push origin main","input":{"command":"git push origin main","description":"Push the release to GitHub"},
         "reason":null,"canAlways":true,"canSession":false,"questions":null,"plan":null}
        """
    }
    var question: String {
        ("""
        {"id":"ap-q","sessionId":"s-stems","createdAt":\(ms(-70)),"expiresAt":\(ms(530)),"kind":"question","toolName":"AskUserQuestion",
         "summary":"AskUserQuestion","input":{},"reason":null,"canAlways":false,"canSession":false,
         "questions":[{"question":"Which format should stems export in by default?","header":"Format","multiSelect":false,
                       "options":[{"label":"WAV 24-bit","description":"Lossless"},{"label":"FLAC","description":"Smaller"}]}],"plan":null}
        """)
    }
    var plan: String {
        ("""
        {"id":"ap-plan","sessionId":"s-nova","createdAt":\(ms(-30)),"expiresAt":\(ms(570)),"kind":"plan","toolName":"ExitPlanMode",
         "summary":"ExitPlanMode","input":{},"reason":null,"canAlways":false,"canSession":false,"questions":null,
         "plan":"# Move pairing into the menu bar\\n\\n1. A fresh code every time\\n2. Watch for the new device"}
        """)
    }
    var webFetch: String {
        ("""
        {"id":"ap-web","sessionId":"s-nova","createdAt":\(ms(-10)),"expiresAt":\(ms(590)),"kind":"tool","toolName":"WebFetch",
         "summary":"WebFetch: https://developer.apple.com/documentation/appkit/nsstatusitem","input":{"url":"https://developer.apple.com/documentation/appkit/nsstatusitem","prompt":"How do status items animate?"},
         "reason":"Claude wants to fetch a page outside your allow list.","canAlways":true,"canSession":true,"questions":null,"plan":null}
        """)
    }

    /// The Mac's battery as the daemon sends it (`Snapshot.mac.battery`). The samples' MacBook Air
    /// is at 76% on its own power unless a render says otherwise, so every other state shows that a
    /// healthy battery says nothing.
    func battery(_ percent: Double, charging: Bool = false, onAC: Bool = false, minutesLeft: Int? = nil) -> String {
        #"{"percent":\#(percent),"charging":\#(charging),"onAC":\#(onAC),"minutesLeft":\#(minutesLeft.map(String.init) ?? "null")}"#
    }

    /// Stay awake as the daemon sends it (`Snapshot.mac.awake`). The samples' MacBook Air is held
    /// While working (a phone is connected) unless a render says otherwise. [until] and [sleepIn]
    /// are seconds from now.
    func awake(_ mode: String, holding: Bool = true, lidClosed: Bool = false, lidClosedOk: Bool = false, ready: Bool = false, until: Double? = nil,
               reason: String? = nil, sleepIn: Double? = nil, laptop: Bool = true, minBattery: Int = 15, otherApp: String? = nil) -> String {
        let u = until.map { String(ms($0)) } ?? "null", at = sleepIn.map { String(ms($0)) } ?? "null"
        let by = otherApp != nil ? "\"other\"" : lidClosedOk ? "\"backpack\"" : "null"
        return #"{"mode":"\#(mode)","holding":\#(holding),"lidClosedOk":\#(lidClosedOk),"backpackReady":\#(ready),"until":\#(u),"reason":\#(reason.map { "\"\($0)\"" } ?? "null"),"sleepAt":\#(at),"lidClosed":\#(laptop ? String(lidClosed) : "null"),"laptop":\#(laptop),"minBattery":\#(minBattery),"sleepDisabledBy":\#(by),"otherApp":\#(otherApp.map { "\"\($0)\"" } ?? "null")}"#
    }

    /// Usage as the daemon sends it: the 5-hour window resets in 1h 12m, the week in 3d 5h, read 20 s
    /// ago. [pace5] and [pace7] (%/min) add the rate block it sends while something works, with the
    /// time to the limit worked out as daemon/src/usage-rate.ts does (none when the reset comes first).
    func usage(_ five: Double, _ week: Double, source: String = "official", models: String = "[]", pace5: Double? = nil, pace7: Double? = nil) -> String {
        let fiveReset = 72.0 * 60, weekReset = 3.0 * 86_400 + 5 * 3600, readAgo = 20.0
        func one(_ pct: Double, _ pace: Double?, _ resetIn: Double) -> (pace: String, eta: String) {
            guard let p = pace, pct < 100 else { return ("null", "null") }
            if p < 0.005 { return ("0", "null") }
            let eta = (100 - pct) / p * 60
            if eta >= resetIn + readAgo { return ("\(p)", "null") }
            return ("\(p)", "\(max(60_000, Int((eta / 60).rounded()) * 60_000))")
        }
        let f = one(five, pace5, fiveReset), w = one(week, pace7, weekReset)
        let rate = pace5 == nil && pace7 == nil ? "null"
            : #"{"fiveHour":\#(f.pace),"sevenDay":\#(w.pace),"fiveHourEtaMs":\#(f.eta),"sevenDayEtaMs":\#(w.eta),"working":true}"#
        return """
        {"fiveHour":{"pct":\(five),"resetsAt":\(ms(fiveReset))},"sevenDay":{"pct":\(week),"resetsAt":\(ms(weekReset))},"models":\(models),"rate":\(rate),"source":"\(source)","observedAt":\(ms(-readAgo))}
        """
    }
    /// The weeks /usage shows for Sonnet and Fable (docs/PROTOCOL.md, "Per-model weeks"), Fable's ending sooner than the week.
    func modelWeeks(fable: Double, sonnet: Double = 3) -> String {
        #"[{"label":"Sonnet","pct":\#(sonnet),"resetsAt":\#(ms(3 * 86_400 + 5 * 3600))},{"label":"Fable","pct":\#(fable),"resetsAt":\#(ms(2 * 86_400 + 9 * 3600))}]"#
    }

    var phone: String { #"{"id":"d-phone","name":"Nothing Phone (3)","model":"A024","appVersion":"1.0","createdAt":\#(ms(-9 * 86_400)),"lastSeenAt":\#(ms(-25))}"# }
    /// A phone with Hypernova Lens connecting through it.
    var phoneWithGlasses: String { #"{"id":"d-phone","name":"Nothing Phone (3)","model":"A024","appVersion":"1.0","createdAt":\#(ms(-9 * 86_400)),"lastSeenAt":\#(ms(-25)),"glasses":{"since":\#(ms(-2 * 86_400)),"seenAt":\#(ms(-600))}}"# }
    var tablet: String { #"{"id":"d-tab","name":"Pixel Tablet","model":"Pixel Tablet","appVersion":"1.0","createdAt":\#(ms(-3 * 86_400)),"lastSeenAt":\#(ms(-2 * 86_400))}"# }

    /// Seven sessions: more than the popover lists.
    var many: [String] {
        [novaWorking, stemsWorking, hyperscribeIdle, homeIdle,
         session("s-m5", "Read the release checklist", "prudnikov-site", state: "idle", ago: 3 * 3600, model: "sonnet"),
         session("s-m6", "Backup cron", "home-server", state: "idle", ago: 9 * 86_400),
         session("s-m7", "Sources chapter", "thesis-notes", state: "idle", ago: 2 * 86_400)]
    }

    /// A code with only the local network's route: no tailnet address (the daemon's placeholder base).
    var pairStartLocal: PairStart {
        PairStart(code: "K7Q2M9", link: "https://hypernova.observer/pair#v=1&u=http%3A%2F%2F10.0.2.2%3A47321&c=K7Q2M9&n=MacBook%20Air&fp=ab12cd34&lan=192.168.1.24&lp=47323",
                  expiresAt: FlexDate(now.addingTimeInterval(9 * 60 + 41)), url: "http://10.0.2.2:47321")
    }

    var pairStart: PairStart {
        PairStart(code: "K7Q2M9", link: "https://hypernova.observer/pair#v=1&u=https%3A%2F%2Fmacbook-air.tailnet.ts.net%3A47321&c=K7Q2M9&n=MacBook%20Air",
                  expiresAt: FlexDate(now.addingTimeInterval(9 * 60 + 41)), url: "https://macbook-air.tailnet.ts.net:47321")
    }

    /// The Tailscale app serving (the samples' usual), signed out, or not installed.
    enum Tailnet { case app, appSignedOut, builtIn, none }

    func model(sessions: [String], approvals: [String] = [], usage: String?, devices: [String]? = nil, keyExpiresInDays: Double = 140,
               serviceUpdate: Bool = false, answered: [AppModel.Answered] = [], account: String? = nil, battery: String? = nil, awake: String? = nil,
               providers: [String] = [], automations: String? = nil, tailnet: Tailnet = .app, lan: Bool = false, nodeGone: String? = nil,
               dialog: GlassDialog? = nil, glasses: Bool = false, browseOutside: Bool = false, codex: String? = nil, claude: Bool = true, into target: AppModel? = nil) -> AppModel {
        let base = "https://macbook-air.tailnet.ts.net:47321"
        let tailscale: String = switch tailnet {
        case .app:
            #"{"installed":true,"cliPath":"/Applications/Tailscale.app/Contents/MacOS/Tailscale","running":true,"loggedIn":true,"dnsName":"macbook-air.tailnet.ts.net","ip":"100.101.102.103","httpsEnabled":true,"serveOn":true,"url":"\#(base)","keyExpiry":"\#(ISO8601DateFormatter().string(from: now.addingTimeInterval(keyExpiresInDays * 86_400)))"}"#
        case .appSignedOut:
            #"{"installed":true,"cliPath":"/Applications/Tailscale.app/Contents/MacOS/Tailscale","running":true,"loggedIn":false,"dnsName":null,"ip":null,"httpsEnabled":null,"serveOn":false,"url":null,"keyExpiry":null}"#
        case .builtIn, .none:
            #"{"installed":false,"cliPath":null,"running":false,"loggedIn":false,"dnsName":null,"ip":null,"httpsEnabled":null,"serveOn":false,"url":null,"keyExpiry":null}"#
        }
        let tsnet: String = switch tailnet {
        case .builtIn:
            #"{"mode":"builtIn","reason":"Hypernova runs its own Tailscale node; nothing to install.","available":true,"enabled":true,"helper":{"state":"Running","dnsName":"hypernova-macbook-air.tailnet.ts.net","account":"you@example.com","listening":"https://hypernova-macbook-air.tailnet.ts.net:47321","tls":"cert"},"url":"https://hypernova-macbook-air.tailnet.ts.net:47321","controlUrl":null}"#
        case .appSignedOut:
            #"{"mode":"off","reason":"Built-in Tailscale is off in the settings.","available":true,"enabled":false,"helper":null,"url":null,"controlUrl":null}"#
        default:
            #"{"mode":"system","reason":"The Tailscale app serves the phone API.","available":true,"enabled":true,"helper":null,"url":null,"controlUrl":null}"#
        }
        let lanJSON = lan ? #"{"port":47323,"addresses":["192.168.1.24"],"fingerprint":"ab12cd34","deviceCert":false}"# : "null"
        let snapshot: Snapshot = decode("""
        {"mac":{"id":"m1","name":"MacBook Air","daemonVersion":"0.4.0","claudeVersion":\(claude ? "\"2.1.296\"" : "null"),"projectsRoot":"/Users/you/Programming","app":null,
                "battery":\(battery ?? self.battery(76, minutesLeft: 290)),"awake":\(awake ?? self.awake("working"))},
         "usage":\(usage ?? #"{"fiveHour":null,"sevenDay":null,"source":"none","observedAt":null}"#),
         "automations":\(automations.flatMap { $0.contains("\"list\"") ? $0 : nil } ?? "null"),
         "sessions":[\(sessions.joined(separator: ","))],"approvals":[\(approvals.joined(separator: ","))],"projects":[],"providers":[\(providers.joined(separator: ","))],
         "account":\(account ?? #"{"state":"ok","providerId":"anthropic","plan":"max","limit":null,"reason":null,"text":null,"since":null,"sessionId":null,"checkedAt":null,"login":null}"#),
         "codex":\(codex ?? "null"),"claude":{"available":\(claude),"version":\(claude ? "\"2.1.296\"" : "null")}}
        """)
        let devs = devices ?? [phone]
        let status: LocalStatus = decode("""
        {"daemonVersion":"0.4.0","apiPort":47321,"claude":\(claude ? #"{"path":"/Users/you/.local/bin/claude","version":"2.1.296"}"# : #"{"path":null,"version":null}"#),
         "tailscale":\(tailscale),"tsnet":\(tsnet),"lan":\(lanJSON),
         "hooks":{"installed":\(claude)},"statusLine":{"installed":\(claude),"supported":true},"shell":{"installed":true,"supported":true},
         "devices":[\(devs.joined(separator: ","))],"connectedClients":\(devs.isEmpty ? 0 : 1),"sessions":[],"approvals":[],"usage":null,
         "projectsRoot":"/Users/you/Programming","files":{"browseOutsideProjects":\(browseOutside)},
         "automations":\(automations.flatMap { $0.contains("\"list\"") ? nil : $0 } ?? "null"),"codex":\(codex ?? "null")}
        """)
        let m = target ?? AppModel()
        // `/local/devices` lists glasses that come through a phone as an entry of their own.
        let list: [Device]? = glasses ? status.devices?.items.flatMap { d -> [Device] in
            guard d.hasGlasses else { return [d] }
            return [d, Device(id: "glasses-\(d.id)", name: "Even glasses", model: "Even G2", appVersion: "0.1.10", createdAt: d.glasses?.since, lastSeenAt: d.glasses?.seenAt, client: "lens", via: d.id)]
        } : nil
        m.loadSample(health: Health(ok: true, protocol: 1, name: "MacBook Air"), snapshot: snapshot, status: status,
                     serviceUpdate: serviceUpdate, answered: answered, dialog: dialog, deviceList: list)
        return m
    }

    private func decode<T: Decodable>(_ json: String) -> T {
        do { return try JSONDecoder().decode(T.self, from: Data(json.utf8)) } catch {
            fatalError("sample didn't decode as \(T.self): \(error)")
        }
    }
}
