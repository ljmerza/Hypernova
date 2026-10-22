import AppKit
import Combine

/// `Hypernova --feed-selftest <seconds>`: checks how the live feed reads the daemon's messages
/// (offline, from canned ones, including a daemon without the account and an account it doesn't
/// understand, the Mac's battery in every shape it can come in, and the Lens guide's and voice's
/// messages), then runs the app's model with its feed for that many seconds against the daemon the
/// environment points at (HYPERNOVA_SUPPORT, HYPERNOVA_API_PORT, HYPERNOVA_LOCAL_PORT, all three,
/// the ports off Hypernova's own) and prints, with the time, every change the menu bar would show.
/// No window, no menu bar item.
/// With `--sign-in` it also presses Sign in once (as the account line does) when the account needs it.
/// With `--open` the model reads as it does while the popover is open (every 6 s with the feed up,
/// not once a minute), to watch how a daemon that stops answering shows on the open popover.
extension DevTools {
    static func feedSelfTest(seconds: Double, signIn: Bool, open: Bool = false) -> Int32 {
        MainActor.assumeIsolated { FeedSelfTest().run(seconds: seconds, signIn: signIn, open: open) }
    }
}

@MainActor
private struct FeedSelfTest {
    func run(seconds: Double, signIn: Bool, open: Bool) -> Int32 {
        var failures = 0
        func check(_ ok: Bool, _ what: String) { print("\(ok ? "ok  " : "FAIL") \(what)"); if !ok { failures += 1 } }

        AutomationSelfTest.run(check)
        WordsSelfTest.run(check)

        let session = #"{"t":"session","session":{"id":"s1","title":"T","cwd":"/tmp","projectName":"tmp","origin":"terminal","state":"working","agentsRunning":2}}"#
        if case .session(let s)? = LiveFeed.decode(session) { check(s.state == "working" && s.agentsBusy == 2, "a session") } else { check(false, "a session") }
        if case .session(let s)? = LiveFeed.decode(session) { check(s.blocked == nil, "a session without a block (an older daemon)") }
        else { check(false, "a session without a block") }
        let limited1 = #"{"t":"session","session":{"id":"s2","state":"idle","blocked":{"kind":"limit","text":"You've hit your session limit · resets 2am","resetsAt":1760007200000,"autoResumeAt":null}}}"#
        if case .session(let s)? = LiveFeed.decode(limited1) {
            check(s.blocked?.kind == "limit" && s.blocked?.isLimit == true && s.blocked?.resetsAt != nil && s.blocked?.autoResumeAt == nil, "a session paused by a limit")
        } else { check(false, "a session paused by a limit") }
        let noModel = #"{"t":"session","session":{"id":"s4","state":"idle","blocked":{"kind":"model","text":"Claude Opus is not available with the Claude Pro plan.","resetsAt":null,"autoResumeAt":null}}}"#
        if case .session(let s)? = LiveFeed.decode(noModel) {
            check(s.blocked?.kind == "model" && s.blocked?.isLimit == false, "a session on a model the plan can't use (not a limit: it waits on you)")
        } else { check(false, "a session on a model the plan can't use") }
        if case .session(let s)? = LiveFeed.decode(#"{"t":"session","session":{"id":"s3","state":"idle","blocked":"soon"}}"#) {
            check(s.id == "s3" && s.blocked?.kind == nil, "a session whose block it can't read still arrives")
        } else { check(false, "a session whose block it can't read still arrives") }
        check(LiveFeed.decode(#"{"t":"event","sessionId":"s1","event":{"id":"e1","kind":"assistant","text":"hi"}}"#) == nil, "transcript events skipped unread")
        check(LiveFeed.decode(#"{"t":"agent","agent":{"id":"a1"}}"#) == nil, "agents skipped (their count rides on the session)")
        check(LiveFeed.decode(#"{"t":"approval.resolved","id":"ap1","sessionId":"s1","decision":"allow","by":"phone"}"#) == .resolved(id: "ap1"), "a resolved approval")
        let signedOut = #"{"t":"account","account":{"state":"logged_out","providerId":"anthropic","plan":null,"limit":null,"reason":"expired","text":"Login expired · Please run /login","since":1760000000000,"sessionId":null,"checkedAt":null,"login":{"state":"waiting_code","url":"https://claude.ai/oauth/authorize","where":"mac","error":null}}}"#
        if case .account(let a)? = LiveFeed.decode(signedOut) {
            check(a.state == "logged_out" && a.needsYou && a.login?.state == "waiting_code" && a.login?.where == "mac", "an account, signed out, signing in")
        } else { check(false, "an account, signed out, signing in") }
        let limited = #"{"t":"account","account":{"state":"limited","limit":{"window":"five_hour","label":"session limit","resetsAt":1760007200000,"autoResumeAt":null,"usingCredits":false}}}"#
        if case .account(let a)? = LiveFeed.decode(limited) {
            check(a.state == "limited" && !a.needsYou && a.limit?.label == "session limit" && a.limit?.resetsAt != nil, "an account at a limit (no dot)")
        } else { check(false, "an account at a limit") }
        if case .account(let a)? = LiveFeed.decode(#"{"t":"account","account":"something new"}"#) { check(a.state == "unknown" && !a.needsYou, "an account it can't read is unknown") }
        else { check(false, "an account it can't read is unknown") }
        let old = #"{"t":"snapshot","snapshot":{"mac":{"name":"M"},"usage":null,"sessions":[],"approvals":[],"projects":[],"providers":[]}}"#
        if case .snapshot(let s)? = LiveFeed.decode(old) { check(s.account == nil, "a snapshot from a daemon without the account") } else { check(false, "a snapshot from a daemon without the account") }
        let odd = #"{"t":"snapshot","snapshot":{"sessions":[],"approvals":[],"account":{"state":"limited","limit":"soon","since":"never"}}}"#
        if case .snapshot(let s)? = LiveFeed.decode(odd) { check(s.account?.state == "limited" && s.account?.limit == nil, "a snapshot whose account has odd fields") }
        else { check(false, "a snapshot whose account has odd fields") }

        // Another provider's balance: what's left under the key's cap is never shown as the account's credit.
        let capped = ProviderBalance(remaining: 3, usedToday: 0.4)
        check(capped.words(under: ProviderStatus(state: "no_credit")) == "$0.40 today", "out of the account's credit: the key's cap isn't shown as what's left")
        check(capped.words(under: ProviderStatus(state: "no_credit", budget: true)) == "$3.00 left", "the key's budget ran out: what's left under it is shown")
        check(capped.words == "$3.00 left" && capped.capLeft(under: ProviderStatus(state: "limited")) == 3, "no trouble, or a rate limit: what's left under the cap")

        // The Mac's battery: in the snapshot, and as its own message.
        func macIn(_ battery: String) -> Snapshot.Mac? {
            let json = #"{"t":"snapshot","snapshot":{"mac":{"id":"m1","name":"M","daemonVersion":"0.5.0"\#(battery)},"sessions":[],"approvals":[]}}"#
            if case .snapshot(let s)? = LiveFeed.decode(json) { return s.mac }
            return nil
        }
        let laptop = macIn(#","battery":{"percent":14,"charging":false,"onAC":false,"minutesLeft":25}"#)
        check(laptop?.battery == Battery(percent: 14, charging: false, onAC: false, minutesLeft: 25) && laptop?.battery?.low == true, "a laptop at 14%, running down: low")
        let desktop = macIn(#","battery":null"#)
        check(desktop?.name == "M" && desktop?.battery == nil, "a Mac without a battery (null): nothing")
        let older = macIn("")
        check(older?.name == "M" && older?.battery == nil, "a daemon without the battery field: nothing")
        let oddBattery = macIn(#","battery":"full""#)
        check(oddBattery?.name == "M" && oddBattery?.daemonVersion == "0.5.0" && oddBattery?.battery == nil, "a battery it can't read sinks nothing else")
        let oddField = macIn(#","battery":{"percent":18.6,"charging":"no","onAC":false,"minutesLeft":"soon"}"#)
        check(oddField?.battery?.shown == 19 && oddField?.battery?.charging == nil && oddField?.battery?.minutesLeft == nil && oddField?.battery?.low == false,
              "odd battery fields drop one by one (and an unknown charging state says nothing)")
        let oddMac = #"{"t":"snapshot","snapshot":{"mac":{"name":7,"battery":{"percent":9,"charging":false,"onAC":false,"minutesLeft":null}},"sessions":[]}}"#
        if case .snapshot(let s)? = LiveFeed.decode(oddMac) { check(s.mac?.name == nil && s.mac?.battery?.low == true, "an odd Mac name keeps the battery") }
        else { check(false, "an odd Mac name keeps the battery") }
        func low(_ p: Double, charging: Bool, onAC: Bool = false) -> Bool { Battery(percent: p, charging: charging, onAC: onAC).low }
        check(low(20, charging: false) && low(20.4, charging: false) && !low(20.6, charging: false) && !low(21, charging: false), "low: at or under 20% as shown")
        check(!low(12, charging: true, onAC: true) && low(12, charging: false, onAC: true), "low: not while charging (plugged in but not charging still counts)")
        // Backpack's "+2 h" (owner, 2026-10-16): only the daemon's `extendable` shows the button.
        let ending = try? JSONDecoder().decode(Awake.self, from: Data(#"{"mode":"backpack","holding":true,"until":1800000600000,"extendable":true}"#.utf8))
        let noExtend = try? JSONDecoder().decode(Awake.self, from: Data(#"{"mode":"backpack","holding":true,"until":1800000600000}"#.utf8))
        check(ending?.extendable == true && noExtend?.extendable == false, "backpack: extendable only when the daemon says so")
        check(ending.flatMap { AwakeWords.ending($0, now: Date(timeIntervalSince1970: 1_800_000_000)) } == "Backpack ends in 10 minutes" && noExtend.flatMap { AwakeWords.ending($0, now: Date(timeIntervalSince1970: 1_800_000_000)) } == nil, "backpack: its last ten minutes say so")
        check(Battery(percent: 140, charging: false, onAC: false).shown == 100 && Battery(percent: 0, charging: false, onAC: false).low, "a percentage outside 0–100 is clamped")
        if case .battery(let b)? = LiveFeed.decode(#"{"t":"battery","battery":{"percent":19,"charging":false,"onAC":false,"minutesLeft":null}}"#) {
            check(b?.shown == 19 && b?.low == true && b?.minutesLeft == nil, "a battery message")
        } else { check(false, "a battery message") }
        check(LiveFeed.decode(#"{"t":"battery","battery":null}"#) == .battery(nil), "a battery message for a Mac without one")
        check(LiveFeed.decode(#"{"t":"battery","battery":{"percent":"low"}}"#) == .battery(nil), "a battery message it can't read hides the battery")
        check(LiveFeed.decode(#"{"t":"battery"}"#) == nil, "a battery message without the field changes nothing")

        // The Lens guide and voice ride on the same feed (the app keeps one connection).
        let place = GuideState(step: "voice", seen: true, done: false)
        check(LiveFeed.decode(#"{"t":"guide","guide":{"step":"voice","seen":true,"done":false}}"#) == .guide(place), "the guide moved")
        check(LiveFeed.decode(#"{"t":"guide.show","guide":{"step":"voice","seen":true,"done":false},"reason":"paired"}"#) == .guideShow(place, reason: "paired"),
              "the guide asked for (glasses paired)")
        check(LiveFeed.decode(#"{"t":"guide","guide":"soon"}"#) == nil, "a guide it can't read changes nothing")
        check(LiveFeed.decode(#"{"t":"stt","stt":{"provider":"soniox","ready":false,"error":"Your Soniox balance is used up."}}"#)
              == .stt(SttState(provider: "soniox", ready: false, error: "Your Soniox balance is used up.", last4: nil)), "voice changed")
        check(LiveFeed.decode(#"{"t":"devices"}"#) == .devices, "a device paired")

        // Codex as a second agent (docs/PROTOCOL.md, "Codex"): nil means not installed, and a Claude-only
        // daemon's sessions and snapshot read as before.
        let codexOn = #"{"t":"codex","codex":{"available":true,"enabled":true,"connected":true,"version":"0.163.0","min":"0.163.0","path":"/opt/homebrew/bin/codex","problem":null,"account":{"signedIn":true,"type":"chatgpt","planType":"plus"},"usage":{"fiveHour":{"pct":34,"resetsAt":1800000000000},"sevenDay":{"pct":100,"resetsAt":0},"limited":true,"observedAt":1799990000000}}}"#
        if case .codex(let c?)? = LiveFeed.decode(codexOn) {
            check(c.on && c.account?.signedIn == true && c.account?.planType == "plus" && c.usage?.fiveHour?.pct == 34 && c.usage?.limited == true, "Codex on, signed in, with its windows")
            check(c.usage?.sevenDay?.pct == 100 && c.usage?.sevenDay?.resetsAt == nil, "a Codex window whose reset is 0: no reset (not one in 1970)")
        } else { check(false, "Codex on, signed in, with its windows") }
        check(LiveFeed.decode(#"{"t":"codex","codex":null}"#) == .codex(nil), "Codex gone from this Mac")
        check(LiveFeed.decode(#"{"t":"codex"}"#) == nil, "a codex message without the field changes nothing")
        if case .codex(let c?)? = LiveFeed.decode(#"{"t":"codex","codex":{"available":"yes","enabled":true,"path":"/x/codex","account":"soon","usage":7}}"#) {
            check(c.enabled && !c.available && c.account == nil && c.usage == nil && c.tooOld, "odd Codex fields drop one by one")
        } else { check(false, "odd Codex fields drop one by one") }
        let withCodex = #"{"t":"snapshot","snapshot":{"sessions":[{"id":"019a","state":"idle","agent":"codex","providerId":"codex","permissionMode":"read-only","switchedFrom":"s1"},{"id":"s1","state":"idle","switchedTo":"019a"}],"approvals":[],"codex":{"available":true,"enabled":false}}}"#
        if case .snapshot(let s)? = LiveFeed.decode(withCodex) {
            let list = s.sessions?.items ?? []
            check(list.count == 2 && list[0].isCodex && list[0].switchedFrom == "s1" && list[1].switchedTo == "019a" && !list[1].isCodex, "a Codex session and the Claude Code one it went on from")
            check(s.codex?.enabled == false && s.codex?.on == false, "Codex installed but off")
        } else { check(false, "a snapshot with Codex") }
        if case .snapshot(let s)? = LiveFeed.decode(old) { check(s.codex == nil, "a snapshot from a daemon without Codex") }
        if case .snapshot(let s)? = LiveFeed.decode(#"{"t":"snapshot","snapshot":{"sessions":[],"codex":"soon"}}"#) { check(s.codex?.on == false, "a Codex it can't read sinks nothing") }
        else { check(false, "a Codex it can't read sinks nothing") }
        check(ModeWords.label("read-only") == "read only" && ModeWords.label("workspace-write") == "can edit", "Codex's sandboxes in words")
        check(CodexWords.trouble(Codex(available: true, enabled: true, connected: true, account: .init(signedIn: false)))?.text == "Codex is signed out", "Codex signed out: one sentence")
        check(CodexWords.trouble(Codex(available: false, enabled: true, version: "0.150.2", min: "0.163.0", path: "/x/codex"))?.text == "Codex needs an update", "Codex too old: needs an update")
        check(CodexWords.trouble(Codex(available: true, enabled: true, connected: true, account: .init(signedIn: true))) == nil, "Codex fine: nothing to say")
        guard seconds > 0 else { return failures == 0 ? 0 : 1 }
        // Only against a test daemon, by the same rule as a test copy's service: its own folder and
        // both ports off Hypernova's. Anything less could reach the owner's daemon (with --sign-in,
        // start a real `claude auth login` on his account).
        guard Paths.isolatedTestCopy else {
            print("live part skipped: set HYPERNOVA_SUPPORT to a test daemon's folder and HYPERNOVA_API_PORT and HYPERNOVA_LOCAL_PORT to its ports (not \(Endpoints.defaultAPIPort) or \(Endpoints.defaultLocalPort)) first")
            return failures == 0 ? 0 : 1
        }

        // Live: the app's own model and feed.
        _ = NSApplication.shared
        NSApp.setActivationPolicy(.prohibited)
        let model = AppModel.shared
        let start = Date()
        var last = ""
        var pressed = false
        func line() -> String {
            var l = "live=\(model.feedLive) up=\(model.daemonUp) sessions=\(model.sessions.count) working=\(model.workingCount) agents=\(model.agentsCount) needsYou=\(model.needsYouCount) account=\(model.account?.state ?? "-")\(model.accountNeedsYou ? " (dot)" : "")"
            l += " status=\(model.status == nil ? "-" : "read") phones=\(model.clientsOnline)"
            if let b = model.snapshot?.mac?.battery {
                l += " battery=\(b.shown)%\(b.charging == true ? " charging" : b.onAC == true ? " on AC" : "")\(model.lowBattery != nil ? " (low: header line)" : "")"
            }
            if let e = model.lastError { l += " error=\"\(e)\"" }
            if let login = model.account?.login { l += " login=\(login.state ?? "?")/\(login.where ?? "?")\(login.url == nil ? "" : " url")" }
            if let a = model.account, let c = AccountLine.content(a, now: Date(), signingIn: model.signingIn) {
                l += " · line \"\(c.title)\(c.detail.map { " · " + $0 } ?? "")\(c.action.map { " [" + $0 + "]" } ?? "")\""
            }
            return l
        }
        let watch = model.objectWillChange.sink { _ in
            DispatchQueue.main.async {
                let l = line()
                if l != last {
                    last = l
                    print(String(format: "%6.2fs  ", Date().timeIntervalSince(start)) + l)
                    fflush(stdout)
                }
                if signIn, !pressed, model.feedLive, model.claudeAccountNeedsYou {
                    pressed = true
                    print(String(format: "%6.2fs  ", Date().timeIntervalSince(start)) + "Sign in (POST /v1/account/login {where: mac})")
                    model.signIn()
                }
            }
        }
        print("daemon at \(Endpoints.local.absoluteString), feed ws://127.0.0.1:\(Endpoints.apiPort)/v1/ws?client=mac, \(Int(seconds)) s\(open ? ", popover open" : "")")
        model.start()
        if open { model.popoverVisible = true }
        RunLoop.main.run(until: Date().addingTimeInterval(seconds))
        watch.cancel()
        check(model.feedLive, "the feed is live at the end")
        print(failures == 0 ? "all good" : "\(failures) failure(s)")
        return failures == 0 ? 0 : 1
    }
}
