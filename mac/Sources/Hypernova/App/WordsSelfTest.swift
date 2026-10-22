import Foundation

/// Offline checks for the popover's words and rules (run from `--feed-selftest`): the one design
/// across phone, Mac and glasses (Android's phrases, no "·"-joined fields), the working rule, phone
/// access through Hypernova's own Tailscale node, and the service's Node.js.
enum WordsSelfTest {
    @MainActor
    static func run(_ check: (Bool, String) -> Void) {
        func dec<T: Decodable>(_ json: String) -> T? { try? JSONDecoder().decode(T.self, from: Data(json.utf8)) }
        let now = Date()
        func ms(_ s: Double) -> Int { Int((now.timeIntervalSince1970 + s) * 1000) }
        func noDot(_ s: String?, _ what: String) { check(s.map { !$0.contains("·") } ?? false, "no \"·\" in \(what): \"\(s ?? "nil")\"") }

        // Model chips and mode tags (Android: data/ModelNames.kt, HomeScreen.kt modeLabel).
        check(ModelNames.chip("opus") == "opus" && ModelNames.chip("openai/gpt-5") == "gpt-5" && ModelNames.chip("anthropic/claude-sonnet-4.5") == "sonnet-4.5"
              && ModelNames.chip("claude-opus-5-5") == "opus-5-5", "model chips as the phone's")
        check(ModelNames.family("Opus 5.5") == "Opus" && ModelNames.family("sonnet-4.5") == "sonnet" && ModelNames.family("opus") == nil, "model families as the phone's")
        check(ModeWords.label("default") == "ask first" && ModeWords.label("acceptEdits") == "accept edits" && ModeWords.label("dontAsk") == "allowed only"
              && ModeWords.label("bypassPermissions") == "bypass" && ModeWords.label(nil) == nil, "mode tags as the phone's")

        // The SESSIONS seam's marks: needs first, no agents count, "all quiet" when none.
        check(SessionWords.counts(needs: 1, working: 2).map(\.text) == ["1 needs you", "2 working"], "count marks: needs first")
        check(SessionWords.counts(needs: 2, working: 0).map(\.text) == ["2 need you"] && SessionWords.counts(needs: 0, working: 0).isEmpty, "count marks: plural, and none when quiet")
        check(SessionWords.hex("c7f2a9") == "c7" && SessionWords.hex("--") == "··", "the session stamp's id")

        // Rows: the phone's activity lines.
        func session(_ extra: String) -> Session? { dec(#"{"id":"s1","title":"T","cwd":"/Users/you/Programming/p","projectName":"p",\#(extra)}"#) }
        let idle = session(#""state":"idle""#)!
        check(SessionWords.line(idle, approval: nil, provider: nil, account: nil, now: now).text == "waiting for your next prompt", "idle: waiting for your next prompt")
        let replied = session(###""state":"idle","lastText":"## Done\nThe build is green.""###)!
        check(SessionWords.line(replied, approval: nil, provider: nil, account: nil, now: now).text == "Done", "idle: the reply's first plain line")
        let away = session(#""state":"idle","stoppedForAway":true"#)!
        check(SessionWords.line(away, approval: nil, provider: nil, account: nil, now: now).text == "paused after 10 minutes without an answer", "away: the phone's words")
        check(SessionWords.line(session(#""state":"error""#)!, approval: nil, provider: nil, account: nil, now: now).text == "stopped on an error", "error: stopped on an error")
        let home: Session = dec(#"{"id":"s2","cwd":"\#(Paths.home.path)","projectName":"you","state":"idle"}"#)!
        check(SessionWords.project(home) == "Home", "a session in the home folder is HOME")
        let push: Approval = dec(#"{"id":"a1","sessionId":"s1","kind":"tool","toolName":"Bash","summary":"Bash: git push origin main","input":{"command":"git push origin main"}}"#)!
        let needs = session(#""state":"needs_you""#)!
        let nl = SessionWords.line(needs, approval: push, provider: nil, account: nil, now: now)
        check(nl.text == "Bash: git push origin main" && nl.tone == .amber, "needs you: what it waits on, in amber")

        // Blocked: the eclipse and the phone's phrases (Android: data/Notices.kt AccountWords.activity).
        let in1h = ms(3600)
        let limit = session(#""state":"idle","blocked":{"kind":"limit","text":"You've hit your session limit · resets 2am","resetsAt":\#(in1h)}"#)!
        let ll = SessionWords.line(limit, approval: nil, provider: nil, account: nil, now: now)
        check(ll.eclipse && ll.text.hasPrefix("session limit until ") && !ll.text.contains("then"), "limit: \(ll.text)")
        noDot(ll.text, "a limit")
        let auto = session(#""state":"idle","blocked":{"kind":"limit","resetsAt":\#(in1h),"autoResumeAt":\#(in1h + 60_000)}"#)!
        let al = SessionWords.line(auto, approval: nil, provider: nil, account: nil, now: now).text
        check(al.hasSuffix(", then carries on"), "limit carrying on by itself: \(al)")
        let ready = session(#""state":"idle","blocked":{"kind":"limit","resetsAt":\#(ms(-60)),"resume":{"state":"ready"}}"#)!
        check(SessionWords.line(ready, approval: nil, provider: nil, account: nil, now: now).text == "session limit over, ready to continue", "limit over, ready to continue")
        let noModel = session(#""state":"idle","blocked":{"kind":"model","text":"Fable 5 requires usage credits."}"#)!
        check(SessionWords.line(noModel, approval: nil, provider: nil, account: nil, now: now).text == "this model isn’t available", "a model the plan can't use")
        let out = session(#""state":"idle","blocked":{"kind":"logged_out"}"#)!
        check(SessionWords.line(out, approval: nil, provider: nil, account: nil, now: now).text == "signed out on the Mac", "signed out on the Mac")
        let or: Provider = dec(#"{"id":"or","name":"OpenRouter","kind":"openrouter","status":{"state":"no_credit"},"balance":{"remaining":0}}"#)!
        let onOR = session(#""state":"idle","providerId":"or","blocked":{"kind":"provider","text":"API Error: 402"}"#)!
        let pl = SessionWords.line(onOR, approval: nil, provider: or, account: nil, now: now)
        check(pl.text == "OpenRouter is out of credits" && pl.tone == .amber && pl.eclipse, "a provider out of credits: \(pl.text)")
        let armed = session(#""state":"idle","providerId":"or","blocked":{"kind":"provider","resume":{"state":"armed","at":\#(ms(600))}}"#)!
        check(SessionWords.line(armed, approval: nil, provider: or, account: nil, now: now).text.hasPrefix("OpenRouter carries on by itself at "), "a provider's limit carrying on by itself")
        let limited = ProviderStatus(state: "limited", resetsAt: FlexDate(now.addingTimeInterval(1500)))
        check(ProviderWords.sentence("OpenRouter", limited, now: now).hasPrefix("OpenRouter is rate limited until "), "a provider's rate limit until a time")
        let removed = session(#""state":"idle","providerId":"gone","blocked":{"kind":"provider","text":"OpenRouter was removed. Move it to another provider."}"#)!
        check(SessionWords.line(removed, approval: nil, provider: nil, account: nil, now: now).text == "OpenRouter was removed. Move it to another provider.", "a removed provider: the daemon's own words")

        // The account card: until, not "back at"; no "·".
        let acct: Account = dec(#"{"state":"limited","limit":{"window":"five_hour","label":"session limit","resetsAt":\#(in1h),"autoResumeAt":null}}"#)!
        let card = AccountLine.content(acct, now: now, signingIn: false)
        check(card?.title.hasPrefix("Session limit until ") == true && card?.amber == false, "the account card: \(card?.title ?? "nil")")
        noDot(card.map { $0.title + " " + ($0.detail ?? "") }, "the account card")

        // Meters: Android's labels, a model's week as its chip and a weekday, the sentence.
        let u: Usage = dec(#"{"fiveHour":{"pct":64,"resetsAt":\#(ms(4320))},"sevenDay":{"pct":100,"resetsAt":\#(ms(3 * 86_400))},"models":[{"label":"Fable","pct":41,"resetsAt":\#(ms(2 * 86_400))},{"label":"Sonnet","pct":3,"resetsAt":\#(ms(5 * 3600))}],"rate":{"fiveHour":0.8,"fiveHourEtaMs":2700000,"fiveHourLimitAt":\#(ms(2700))},"source":"official","observedAt":\#(ms(-20))}"#)!
        let meters = UsageSection.meters(u, now: now)
        check(meters.map(\.label) == ["5 hours", "7 days", "7 days", "7 days"] && meters.map(\.chip) == [nil, nil, "Fable", "Sonnet"], "meter labels: 5 HOURS, 7 DAYS, a model's chip before 7 DAYS")
        if case .resetOn? = meters[2].reading.tag {} else { check(false, "a model's week more than a day off: a weekday and time") }
        if case .reset? = meters[3].reading.tag {} else { check(false, "a model's week within the day: a countdown") }
        if case .limit(let s)? = meters[0].reading.tag { check(abs(s - 2700) < 2, "the limit tag counts from now to the daemon's limitAt") } else { check(false, "the limit before the reset") }
        check(meters[1].reading.atLimit, "a spent week (its percentage red)")
        check(meters[0].reading.sentence.hasPrefix("5-hour window 64% used, spending 0.8% a minute: at this pace you reach the limit in about 45 minutes"), "VoiceOver, as Android's: \(meters[0].reading.sentence)")
        check(meters[2].reading.sentence.contains("resets on "), "VoiceOver says a weekday reset as one: \(meters[2].reading.sentence)")

        // The working rule: needs you outranks working; a session whose agents run is working.
        let m = AppModel()
        let snap: Snapshot = dec(#"{"sessions":[{"id":"a","state":"needs_you","agentsRunning":2},{"id":"b","state":"idle","agentsRunning":1},{"id":"c","state":"working"},{"id":"d","state":"idle"}],"approvals":[]}"#)!
        m.loadSample(health: Health(ok: true), snapshot: snap, status: nil)
        check(m.needsYouCount == 1 && m.workingCount == 2, "the working rule: 1 needs you, 2 working (\(m.needsYouCount), \(m.workingCount))")

        // Phone access through Hypernova's own Tailscale node counts as on (owner's audit M1).
        let builtIn: LocalStatus = dec(#"{"tailscale":{"installed":false,"running":false,"loggedIn":false,"serveOn":false,"url":null},"tsnet":{"mode":"builtIn","available":true,"enabled":true,"helper":{"state":"Running","listening":"https://hypernova-mac.tailnet.ts.net:47321","account":"you@example.com"},"url":"https://hypernova-mac.tailnet.ts.net:47321"},"hooks":{"installed":true},"devices":[{"id":"d1","name":"Phone"}]}"#)!
        m.loadSample(health: Health(ok: true), snapshot: snap, status: builtIn)
        check(TailscaleStepLogic.isOn(m.tailscaleAction) && m.phoneBaseURL == "https://hypernova-mac.tailnet.ts.net:47321", "the built-in node: phone access on, its address for pairing")
        check(Paths.testCopy || m.setupNext == nil, "the built-in node: no \"Turn on phone access\" notice")
        check(m.keyExpiry == nil, "the built-in node: no Tailscale app key-expiry warning")
        let wifi: LocalStatus = dec(#"{"tailscale":{"installed":true,"running":true,"loggedIn":false},"tsnet":{"mode":"off","enabled":false,"available":true},"hooks":{"installed":true},"devices":[],"lan":{"port":47323,"addresses":["192.168.1.24"]}}"#)!
        m.loadSample(health: Health(ok: true), snapshot: snap, status: wifi)
        check(m.localRoute && m.phoneBaseURL == nil && (Paths.testCopy || m.setupNext == .phone), "no tailnet but this Wi‑Fi: pair (not \"turn on phone access\")")

        // Words without "·" (DESIGN.md): Stay awake, the battery, the voice.
        let awake = Awake(mode: .backpack, holding: true, lidClosedOk: true, backpackReady: true, until: now.addingTimeInterval(5 * 3600), lidClosed: true)
        check(AwakeWords.trail(awake, now: now) == "awake with the lid closed", "Stay awake's trail as a phrase")
        noDot(AwakeWords.line(awake, battery: nil, now: now)?.text, "Backpack's line")
        let sleeping = Awake(mode: .backpack, holding: true, reason: "battery", sleepAt: now.addingTimeInterval(120))
        check(AwakeWords.line(sleeping, battery: Battery(percent: 15, charging: false, onAC: false), now: now)?.text == "Your Mac will sleep in 2 minutes: battery 15%", "sleep soon, as the phone says it")
        let low = Battery(percent: 18, charging: false, onAC: false)
        check(BatteryWords.lead(low) + " " + BatteryWords.rest(low) == "Your Mac is at 18%. Plug it in or its sessions stop.", "the battery sentence, the same everywhere")

        // Which agent leads (docs/PROTOCOL.md, "Codex"): Claude Code while it's here, else Codex once
        // it can be used; both in use only with Codex on beside Claude Code.
        let codexOn = Codex(available: true, enabled: true, connected: true, version: "0.163.0", min: "0.163.0", path: "/opt/homebrew/bin/codex")
        let codexOff = Codex(available: true, enabled: false, version: "0.163.0", min: "0.163.0", path: "/opt/homebrew/bin/codex")
        let codexOld = Codex(available: false, enabled: false, version: "0.150.2", min: "0.163.0", path: "/opt/homebrew/bin/codex")
        check(AgentRule(claude: true, codex: nil).leading == .claude && !AgentRule(claude: true, codex: nil).both, "Claude Code alone leads, nothing beside it")
        check(AgentRule(claude: true, codex: codexOn).leading == .claude && AgentRule(claude: true, codex: codexOn).both, "both in use: Claude Code leads")
        check(AgentRule(claude: true, codex: codexOff).leading == .claude && !AgentRule(claude: true, codex: codexOff).both, "Codex installed but off: Claude Code alone")
        check(AgentRule(claude: false, codex: codexOn).leading == .codex && AgentRule(claude: false, codex: codexOn).codexOnly, "no Claude Code: Codex leads, alone")
        check(AgentRule(claude: false, codex: codexOff).leading == .codex && !AgentRule(claude: false, codex: codexOff).codexOnly, "no Claude Code, Codex off: Codex leads, not in use")
        check(AgentRule(claude: false, codex: codexOld).leading == nil && !AgentRule(claude: false, codex: codexOld).none, "no Claude Code, Codex too old: nothing leads, but an agent is here")
        check(AgentRule(claude: false, codex: nil).leading == nil && AgentRule(claude: false, codex: nil).none, "neither: no agent")
        check(AgentRule(claude: true, codex: codexOn).names == "Claude Code and Codex" && AgentRule(claude: false, codex: codexOff).names == "Codex"
              && AgentRule(claude: true, codex: codexOff).names == "Claude Code" && AgentRule(claude: false, codex: nil).names == "your agents", "who the phone reaches, in words (neutral with neither)")
        check(!AgentWords.uninstallMore(AgentRule(claude: false, codex: codexOn)).contains("Claude Code") && AgentWords.uninstallMore(AgentRule(claude: false, codex: codexOn)).contains("the codex function")
              && AgentWords.uninstallMore(AgentRule(claude: true, codex: nil)).contains("hooks and status line tap from Claude Code") && !AgentWords.uninstallMore(AgentRule(claude: true, codex: nil)).contains("codex")
              && AgentWords.uninstallLine(AgentRule(claude: false, codex: nil)) == "The background service stops.", "Uninstall names only the agents this Mac has")
        // Claude Code's absence has to be proven: any sign of it wins over the daemon's "not here".
        let hooksIn: LocalStatus = dec(#"{"claude":{"path":null,"version":null},"hooks":{"installed":true},"devices":[{"id":"d1"}]}"#)!
        let present = AppModel()
        present.loadSample(health: Health(ok: true), snapshot: nil, status: hooksIn)
        check(present.claudeAvailable, "the daemon misses Claude Code but its hooks are in: it's here")
        let ownSession: Snapshot = dec(#"{"sessions":[{"id":"s1","state":"idle"}],"approvals":[],"claude":{"available":false,"version":null}}"#)!
        present.loadSample(health: Health(ok: true), snapshot: ownSession, status: nil)
        check(present.claudeAvailable, "the daemon misses Claude Code but a session of its own is known: it's here")
        let codexSession: Snapshot = dec(#"{"sessions":[{"id":"019a","state":"idle","agent":"codex"}],"approvals":[],"claude":{"available":false,"version":null}}"#)!
        present.loadSample(health: Health(ok: true), snapshot: codexSession, status: nil)
        check(!present.claudeAvailable, "only Codex's sessions, and the daemon says no Claude Code: Codex alone")
        // Setup's "Use Codex only" stands only while Codex can be used: a Codex gone, too old or unseen by the daemon skips nothing.
        func setup(_ codexJSON: String) -> SetupModel {
            let m = SetupModel()
            m.claudePhase = .missing
            m.health = Health(ok: true)
            m.status = dec(#"{"claude":{"path":null,"version":null},"hooks":{"installed":false},"devices":[],"codex":\#(codexJSON)}"#)
            return m
        }
        check(setup(#"{"available":true,"enabled":true,"path":"/x/codex"}"#).codexOnly && setup(#"{"available":true,"enabled":true,"path":"/x/codex"}"#).isSkipped(.connect),
              "Setup: no Claude Code, Codex on and usable: Claude Code's steps skipped")
        check(!setup(#"{"available":false,"enabled":true,"version":"0.150.2","min":"0.163.0","path":"/x/codex"}"#).codexOnly && setup(#"{"available":false,"enabled":true,"path":"/x/codex"}"#).codexTooOld,
              "Setup: Codex too old: nothing skipped (update it first)")
        check(!setup("null").codexOnly && !setup("null").isSkipped(.claude), "Setup: the daemon sees no Codex: nothing skipped")
        // The model's rule from what the daemon says: the snapshot's `claude` first, then the status's path, then (an older daemon) installed.
        let soloSnap: Snapshot = dec(#"{"sessions":[],"approvals":[],"claude":{"available":false,"version":null},"codex":{"available":true,"enabled":true,"connected":true,"path":"/x/codex","account":{"signedIn":false}}}"#)!
        let soloStatus: LocalStatus = dec(#"{"claude":{"path":null,"version":null},"hooks":{"installed":false},"devices":[{"id":"d1"}],"codex":{"available":true,"enabled":true,"path":"/x/codex"}}"#)!
        let solo = AppModel()
        solo.loadSample(health: Health(ok: true), snapshot: soloSnap, status: soloStatus)
        check(!solo.claudeAvailable && solo.agents.leading == .codex && solo.agents.codexOnly, "the snapshot says no Claude Code: Codex alone")
        check(solo.setupNext != .connect, "Codex alone: no \"Connect Claude Code\" notice (\(solo.setupNext.map { "\($0)" } ?? "none"))")
        check(solo.accountTrouble == "Codex is signed out" && solo.accountNeedsYou && !solo.claudeAccountNeedsYou, "Codex alone, signed out: the dot, in Codex's words")
        let soloOff: LocalStatus = dec(#"{"claude":{"path":null,"version":null},"hooks":{"installed":false},"devices":[{"id":"d1"}],"codex":{"available":true,"enabled":false,"path":"/x/codex"}}"#)!
        solo.loadSample(health: Health(ok: true), snapshot: nil, status: soloOff)
        check(!solo.claudeAvailable && (Paths.testCopy || solo.setupNext == .codex), "no Claude Code, Codex off: Setup's Codex step is the notice")
        let neither: LocalStatus = dec(#"{"claude":{"path":null,"version":null},"hooks":{"installed":false},"devices":[{"id":"d1"}],"codex":null}"#)!
        solo.loadSample(health: Health(ok: true), snapshot: nil, status: neither)
        check(Paths.testCopy || solo.setupNext == .claude, "no agent at all: install one")
        let older: Snapshot = dec(#"{"sessions":[],"approvals":[],"account":{"state":"logged_out"}}"#)!
        solo.loadSample(health: Health(ok: true), snapshot: older, status: nil)
        check(solo.claudeAvailable && solo.accountTrouble == "Claude Code is signed out", "a daemon older than Codex: Claude Code is there, as always")
        if case .snapshot(let s)? = LiveFeed.decode(#"{"t":"snapshot","snapshot":{"sessions":[],"claude":"soon","mac":{"name":"M"}}}"#) {
            check(s.mac?.name == "M" && s.claude?.available == nil, "a `claude` it can't read sinks nothing (and says nothing)")
        } else { check(false, "a `claude` it can't read sinks nothing") }
        let both = AppModel()
        let bothSnap: Snapshot = dec(#"{"sessions":[],"approvals":[],"claude":{"available":true,"version":"2.1.296"},"account":{"state":"logged_out"},"codex":{"available":true,"enabled":true,"connected":true,"path":"/x/codex","account":{"signedIn":false}}}"#)!
        both.loadSample(health: Health(ok: true), snapshot: bothSnap, status: nil)
        check(both.agents.both && both.accountTrouble == "Claude Code is signed out", "both in use, Claude Code signed out: its trouble, not the app's (Codex's sign-out stays a sentence)")

        // The usage meters' names: Claude Code's wear CLAUDE beside Codex (both in use), nothing alone;
        // Codex's wear CODEX beside Claude Code, nothing alone.
        let named = UsageSection.meters(u, now: now, named: true)
        check(named.map(\.chip) == ["Claude", "Claude", "Fable", "Sonnet"] && named[0].reading.name == "Claude 5-hour window" && named[1].reading.name == "Claude weekly window",
              "both in use: CLAUDE before 5 HOURS and 7 DAYS, a model's week keeps its own chip")
        check(meters[0].reading.name == "5-hour window" && meters[1].reading.name == "Weekly window", "Claude Code alone: the windows as they always were")
        var codexUsed = codexOn
        codexUsed.usage = Codex.Limits(fiveHour: UsageWindow(pct: 34, resetsAt: FlexDate(now.addingTimeInterval(9600))), sevenDay: UsageWindow(pct: 61, resetsAt: nil))
        check(UsageSection.codexMeters(codexUsed, now: now).map(\.chip) == ["Codex", "Codex"], "beside Claude Code: CODEX before Codex's windows")
        check(UsageSection.codexMeters(codexUsed, now: now, chip: false).map(\.chip) == [nil, nil], "Codex alone: its windows plain")
        check(UsageSection.shows(AgentRule(claude: false, codex: codexOff)) == false && UsageSection.shows(AgentRule(claude: false, codex: codexOn)), "USAGE only with an agent to measure")
        check(AgentWords.nothingRunning(AgentRule(claude: false, codex: codexOn), shell: true).contains("Type codex") && !AgentWords.nothingRunning(AgentRule(claude: false, codex: codexOn), shell: true).contains("claude"),
              "Codex alone, nothing running: type codex")
        check(AgentWords.nothingRunning(AgentRule(claude: true, codex: nil), shell: true) == "Nothing running. Type claude in a terminal, or start a session from your phone.", "Claude Code alone: as before")

        // The account card names Claude Code beside Codex; alone it reads as before.
        let signedOut: Account = dec(#"{"state":"logged_out","reason":"expired"}"#)!
        check(AccountLine.content(signedOut, now: now, signingIn: false)?.title == "Signed out", "Claude Code alone, signed out: as before")
        check(AccountLine.content(signedOut, now: now, signingIn: false, named: true)?.title == "Claude Code is signed out", "both in use: Claude Code is signed out")
        check(CodexWords.account(.init(signedIn: true, type: "chatgpt", planType: "plus")) == "Signed in with ChatGPT Plus" && CodexWords.account(.init(signedIn: true, type: "apiKey")) == "Signed in with an API key"
              && CodexWords.account(.init(signedIn: false)) == nil, "Codex's account in words, never the email")
        check(CodexWords.account(.init(signedIn: true, type: "chatgpt", planType: "prolite")) == "Signed in with ChatGPT Pro Lite"
              && CodexWords.planName("enterprise") == "Enterprise" && CodexWords.planName("someday") == "Someday", "ChatGPT's plans as OpenAI names them (prolite is Pro Lite)")
        let ask: Approval = dec(#"{"id":"q1","kind":"question"}"#)!
        check(ask.headline(codex: true) == "Codex has a question" && ask.headline == "Claude Code has a question", "a question without words of its own: its agent's")

        // The service's Node.js (owner's audit M3): a version manager's is the last resort.
        check(!NodeDetector.isStable(Paths.home.appendingPathComponent(".nvm/versions/node/v25.9.0/bin/node").path), "an nvm Node.js isn't stable")
        check(NodeDetector.isStable("/opt/homebrew/bin/node") && NodeDetector.isStable(Paths.nodeDir.appendingPathComponent("v24.11.1/bin/node").path), "Homebrew's and Hypernova's own are")
        check(ServiceInstaller.Progress.gettingNode(nil).rank < ServiceInstaller.Progress.copying.rank, "Node.js comes first in the service's steps")
        // Claude Code's SDK (ClaudeSDK): fetched after the copy, before the start; offline in words, never npm's stack.
        check(ServiceInstaller.Progress.copying.rank < ServiceInstaller.Progress.fetchingSDK.rank && ServiceInstaller.Progress.fetchingSDK.rank < ServiceInstaller.Progress.writing.rank,
              "Claude Code's SDK comes after the copy and before the start")
        check(ClaudeSDK.State(wanted: "0.3.296", installed: nil).missing && !ClaudeSDK.State(wanted: "0.3.296", installed: "0.3.290").current
              && ClaudeSDK.State(wanted: nil, installed: "0.3.290").current, "an SDK is current at the pinned version, or any one when nothing is pinned")
        let offline = ClaudeSDK.explain(ShellResult(status: 1, stdout: "", stderr: "npm error code ECONNREFUSED\nnpm error syscall connect"), after: .init(wanted: "1.0.0", installed: nil))
        check(offline.contains("offline") && !offline.contains("syscall"), "an npm that can't reach the registry says offline: \"\(offline)\"")
        let other = ClaudeSDK.explain(ShellResult(status: 1, stdout: "", stderr: "npm error code E404\nnpm error 404 Not Found - GET https://registry.npmjs.org/x"), after: .init(wanted: "1.0.0", installed: nil))
        check(other.contains("E404: 404 Not Found"), "another npm error quotes its code and line: \"\(other)\"")
        check(ClaudeSDK.arguments(cache: URL(fileURLWithPath: "/tmp/c")).starts(with: ["install", "--no-save", "--ignore-scripts"])
              && ClaudeSDK.arguments(cache: URL(fileURLWithPath: "/tmp/c")).contains("--omit=optional"), "npm installs from the lock, saves nothing, runs no scripts, leaves the SDK's binaries out")
    }
}
