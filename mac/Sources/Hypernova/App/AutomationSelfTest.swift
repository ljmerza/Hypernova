import Foundation

/// Offline checks for automations (run from `--feed-selftest`): the status and snapshot shapes in
/// every form they can come in, and the words of the popover's line.
enum AutomationSelfTest {
    @MainActor
    static func run(_ check: (Bool, String) -> Void) {
        func dec<T: Decodable>(_ json: String) -> T? { try? JSONDecoder().decode(T.self, from: Data(json.utf8)) }
        let now = Date()
        let in90 = Int((now.timeIntervalSince1970 + 5400) * 1000)
        let ago3m = Int((now.timeIntervalSince1970 - 185) * 1000)

        // /local/status.automations
        let st: LocalStatus? = dec(#"{"automations":{"paused":false,"count":3,"next":{"id":"au_1","name":"Morning brief","at":\#(in90)},"running":[]}}"#)
        check(st?.automations?.state.count == 3 && st?.automations?.state.upcoming.first?.name == "Morning brief", "automations: the status line's data")
        let old: LocalStatus? = dec(#"{"daemonVersion":"0.3.0"}"#)
        check(old != nil && old?.automations == nil, "automations: an older daemon's status has none")
        let odd: LocalStatus? = dec(#"{"automations":{"paused":"yes","count":"x","next":5,"running":"no"}}"#)
        check(odd != nil && odd?.automations?.state.count == 0, "automations: an odd shape never sinks the status")
        let run: LocalStatus? = dec(#"{"automations":{"paused":false,"count":2,"next":null,"running":[{"id":"au_1","name":"Morning brief","sessionId":"s9","startedAt":\#(ago3m)},{"junk":1}]}}"#)
        check(run?.automations?.state.running.first?.sessionId == "s9", "automations: a running one with its session")

        // Snapshot.automations: the list gives the next three and the ones working.
        let snap: Snapshot? = dec(#"{"automations":{"paused":true,"list":[{"id":"a","name":"A","enabled":true,"nextAt":\#(in90 + 1000)},{"id":"b","name":"B","enabled":false,"nextAt":\#(in90)},{"id":"c","name":"C","enabled":true,"nextAt":null,"lastRun":{"state":"running","sessionIds":["s1"],"startedAt":\#(ago3m)}}]}}"#)
        let ss = snap?.automations?.state
        check(ss?.paused == true && ss?.count == 3 && ss?.enabled == 2, "automations: snapshot counts and pause")
        check(ss?.upcoming.map(\.name) == ["A"], "automations: a turned-off one never counts as next")
        check(ss?.running.first?.sessionId == "s1", "automations: a run working in the snapshot")

        // The session's tag.
        let tagged: Session? = dec(#"{"id":"s1","automation":{"id":"au_1","name":"Morning brief","runId":"ru_1"}}"#)
        check(tagged?.automation?.name == "Morning brief", "automations: a session started by one")

        // The words.
        func line(_ s: AutomationsState) -> AutomationWords.Line? { AutomationWords.line(s, now: now) }
        check(line(AutomationsState()) == nil, "automations: none, no line")
        check(line(AutomationsState(paused: true, count: 2))?.text == "Automations paused" && line(AutomationsState(paused: true, count: 2))?.action == "Resume", "automations: paused, with Resume")
        let one = AutomationsState(count: 1, upcoming: [.init(id: "x", name: "Morning brief", at: now.addingTimeInterval(120))])
        check(line(one)?.text == "Morning brief" && line(one)?.figure?.glyph == .clock && line(one)?.figure?.text.isEmpty == false, "automations: the next run, its time after a clock")
        let far = AutomationsState(count: 1, upcoming: [.init(id: "x", name: "Brief", at: now.addingTimeInterval(3 * 86_400))])
        check(line(far)?.figure?.text.split(separator: " ").count ?? 0 >= 2, "automations: a day past today is named (\(line(far)?.figure?.text ?? ""))")
        let running = AutomationsState(count: 1, running: [.init(id: "x", name: "Morning brief", sessionId: "s", startedAt: now.addingTimeInterval(-185))])
        check(line(running)?.text == "Morning brief" && line(running)?.figure?.glyph == .timer && line(running)?.figure?.text == "3m", "automations: running, its minutes after a stopwatch (\(line(running)?.said ?? ""))")
        var waiting = running; waiting.running[0].needsYou = true
        check(line(waiting)?.needsYou == true && line(running)?.needsYou == false, "automations: a run that needs you says so")
        let two = AutomationsState(count: 2, running: [.init(id: "x", name: "A", sessionId: "s", startedAt: nil), .init(id: "y", name: "B", sessionId: "t", startedAt: nil)])
        check(line(two)?.text == "2 automations running", "automations: two running")
        var off = one; off.enabled = 0
        check(line(off) == nil, "automations: all off, no line")
        check(line(AutomationsState(count: 1, eventNames: ["Carry on after the limit"]))?.said == "Carry on after the limit, ready", "automations: only events, ready")
        check(AutomationWords.trail(AutomationsState(paused: true, count: 2), now: now) == "paused" && AutomationWords.trail(one, now: now).hasPrefix("1 on, next "), "automations: Settings says it as the phone does (\(AutomationWords.trail(one, now: now)))")
        for l in [line(one), line(running), line(two), line(AutomationsState(paused: true, count: 2))] { check(!(l?.said ?? "").contains("·"), "automations: no \"·\" in \"\(l?.said ?? "")\"") }

        workflows(check)
    }
}

extension AutomationSelfTest {
    /// Workflows (docs/PROTOCOL.md, "Workflows"): features, asks, steps, guards, outputs, runs and what a save sends.
    @MainActor
    static func workflows(_ check: (Bool, String) -> Void) {
        func dec<T: Decodable>(_ json: String) -> T? { try? JSONDecoder().decode(T.self, from: Data(json.utf8)) }
        func enc<T: Encodable>(_ v: T) -> [String: Any] { ((try? JSONSerialization.jsonObject(with: JSONEncoder().encode(v))) as? [String: Any]) ?? [:] }
        let until = Int((Date().timeIntervalSince1970 + 540) * 1000)

        // features and asks ride the snapshot; an older daemon has neither and behaves as today.
        let snap: Snapshot? = dec(#"{"automations":{"paused":false,"features":["steps","ask"],"list":[{"id":"au_a","name":"Review","enabled":true,"nextAt":null,"lastRun":{"id":"ru_1","state":"needs_you","sessionIds":[],"ask":{"stepId":"st_q","question":"Post this review?","answers":["Approve","Skip"],"default":"Skip","until":\#(until)}}}]}}"#)
        let st = snap?.automations?.state
        check(st?.features == ["steps", "ask"] && st?.workflows == true, "workflows: features read from the snapshot")
        check(st?.asks.count == 1 && st?.asks.first?.answers == ["Approve", "Skip"] && st?.asks.first?.stepId == "st_q" && st?.asks.first?.runId == "ru_1", "workflows: a run waiting at an ask is an attention item")
        let old: Snapshot? = dec(#"{"automations":{"paused":false,"list":[{"id":"au_a","name":"A","enabled":true,"lastRun":{"id":"ru_1","state":"running"}}]}}"#)
        check(old?.automations?.state.features == nil && old?.automations?.state.workflows == false && old?.automations?.state.asks.isEmpty == true, "workflows: an older daemon has no features and no asks")
        let half: Snapshot? = dec(#"{"automations":{"list":[{"id":"a","name":"A","lastRun":{"id":"r","state":"needs_you","ask":{"question":"?"}}}]}}"#)
        check(half?.automations?.state.asks.isEmpty == true, "workflows: an ask without answers or a step is ignored")

        // The words.
        let item = st!.asks[0]
        check(WorkflowWords.askCountdown(item, now: Date()) == "Skip in 9m", "workflows: the default and its countdown in one line (\(WorkflowWords.askCountdown(item, now: Date())))")
        check(WorkflowWords.askCountdown(item, now: item.until.addingTimeInterval(-90)) == "Skip in 90s", "workflows: seconds in the last two minutes")
        check(WorkflowWords.guardText(nil) == "unless the step before failed" && WorkflowWords.guardText(WFGuard(on: "answer", text: "Approve")) == "only if the answer is Approve", "workflows: guards in words")

        // A full automation, a run with steps.
        let a: WFAutomation? = dec(#"{"id":"au_1","name":"Ship","enabled":true,"trigger":{"kind":"webhook","last4":"9f3c","createdAt":1,"url":"http://127.0.0.1:47322/x","command":"hn"},"steps":[{"id":"st_1","kind":"shell","if":null,"projectPath":"/p","command":"npm test","timeoutSec":60},{"id":"st_2","kind":"ask","if":{"on":"failed"},"question":"Fix?","answers":["Yes","No"],"default":"No","timeoutMin":10}],"outputs":[{"id":"ou_1","kind":"webhook","preset":"slack","host":"hooks.slack.com","last4":"Xq7Z","text":null}],"budget":{"minutes":30,"windowPct":null}}"#)
        check(a?.steps?.count == 2 && a?.editable == true && a?.budget?.minutes == 30 && a?.outputs?.first?.last4 == "Xq7Z", "workflows: steps, outputs and budget decode")
        let future: WFAutomation? = dec(#"{"id":"au_2","name":"X","trigger":{"kind":"quantum"},"steps":[{"id":"s","kind":"teleport"}]}"#)
        check(future != nil && future?.editable == false, "workflows: a step kind this app doesn't know is kept, not edited")
        check(WorkflowWords.sentence(a!).contains("then 1 more step.") && WorkflowWords.sentence(a!).contains("post it to Slack"), "workflows: the card's sentence (\(WorkflowWords.sentence(a!)))")
        let r: WFRun? = dec(#"{"id":"ru","state":"failed","reason":"exit","steps":[{"id":"st_1","kind":"shell","state":"done","exit":0,"result":"ok"},{"id":"st_2","kind":"shell","state":"failed","reason":"exit","exit":1,"result":"npm ERR! boom"},{"id":"st_3","kind":"say","state":"skipped","reason":"failed_before"}],"outputs":[{"id":"ou_1","kind":"webhook","state":"failed","error":"Slack answered 404."}]}"#)
        check(r?.failedStepIndex == 1 && r?.active == false, "workflows: Retry from the step that failed")
        check(WorkflowWords.step(r!.steps![1], now: Date()).text == "Exited 1: npm ERR! boom" && WorkflowWords.step(r!.steps![2], now: Date()).text == "Skipped: a step before it failed", "workflows: what came of each step")
        check(WorkflowWords.output(r!.outputs![0]).text == "Didn’t go: Slack answered 404.", "workflows: what became of an output")
        check(WorkflowWords.answered(WFAnswer(text: "Approve", by: "mac")) == "Answered Approve on the Mac" && WorkflowWords.answered(WFAnswer(text: "Skip", by: "timeout")) == "Nobody answered: taken as Skip", "workflows: who answered")

        // What a save sends.
        var d = WFDraft(a!)
        d.trigger = .make("file"); d.trigger.path = "/p/docs"
        let input = d.input(confirmShell: true, confirmBypass: false, confirmPaid: false)
        let j = enc(input)
        let steps = j["steps"] as? [[String: Any]]
        check(steps?.first?["if"] is NSNull && steps?.first?["command"] as? String == "npm test" && steps?.first?["prompt"] == nil, "workflows: a shell step is sent as a shell step, its first guard null")
        check((j["trigger"] as? [String: Any])?["glob"] is NSNull && (j["trigger"] as? [String: Any])?["event"] as? String == "created", "workflows: a file watch with no pattern sends glob null")
        check(j["confirmShell"] as? Bool == true && j["confirmBypass"] == nil, "workflows: confirmShell is sent, confirmBypass only when asked")
        let hook = enc(WFTrigger.make("webhook"))
        check(hook.keys.sorted() == ["kind"], "workflows: a webhook trigger is sent as its kind alone")
        var out = WFOutput.make("webhook"); out.url = "https://hooks.slack.com/services/T/B/x"; out.preset = "slack"
        let oj = enc(out)
        check(oj["url"] as? String != nil && oj["last4"] == nil && oj["host"] == nil, "workflows: a webhook output sends its address once, never host or last four")
        out.url = nil; out.last4 = "Xq7Z"
        check(enc(out)["url"] == nil, "workflows: a kept webhook output sends no address")
        check(input.needsOwner.shell && !input.needsOwner.bypass, "workflows: a shell step asks Touch ID or the password")
        var bypass = d; bypass.steps = [WFStep.make(WFStep.start)]; bypass.steps[0].permissionMode = "bypassPermissions"
        check(bypass.input(confirmShell: false, confirmBypass: false, confirmPaid: false).needsOwner.bypass, "workflows: No asking asks Touch ID or the password")
        check(WFStep.make(WFStep.notify, after: []).if == nil, "workflows: the first step has no guard")
        for l in [WorkflowWords.sentence(a!), WorkflowWords.guardText(WFGuard(on: "ok"))] { check(!l.contains("·"), "workflows: no \"·\" in \"\(l)\"") }

        // An earlier step's result (step.output): a chip per earlier step with one, by number; a command gets its variable.
        let flow = [WFStep(id: "st_a", kind: WFStep.start), WFStep(id: "st_n", kind: WFStep.notify), WFStep(id: "st-q", kind: WFStep.ask)]
        let time = WFTrigger.make("time")
        let text = WorkflowWords.placeholders(for: .text, steps: flow, index: 3, trigger: time, stepOutput: true)
        check(text.contains("{step:st_a}") && text.contains("{step:st-q}") && !text.contains("{step:st_n}"), "workflows: an earlier step's result as a chip, a notification's not (\(text))")
        check(!WorkflowWords.placeholders(for: .text, steps: flow, index: 3, trigger: time).contains { $0.hasPrefix("{step:") }, "workflows: step results only when the daemon offers them")
        check(!WorkflowWords.placeholders(for: .bypassPrompt, steps: flow, index: 3, trigger: time, stepOutput: true).contains { $0.hasPrefix("{step:") }, "workflows: no step result in a No asking prompt")
        let cmd = WorkflowWords.placeholders(for: .command, steps: flow, index: 3, trigger: time, stepOutput: true)
        check(cmd.contains("\"$HYPERNOVA_STEP_st_q\"") && !cmd.contains { $0.hasPrefix("{step:") }, "workflows: a command reads a step's result from its environment (\(cmd))")
        check(WorkflowWords.chip("{step:st-q}", steps: flow) == "Step 3’s result" && WorkflowWords.chip("\"$HYPERNOVA_STEP_st_a\"", steps: flow) == "Step 1’s result" && WorkflowWords.chip("{prev}", steps: flow) == "{prev}", "workflows: a step's chip reads by its number")
        check(WorkflowWords.placeholder("{step:st_gone}", steps: flow).hasPrefix("A step that isn’t before this one"), "workflows: a step that's gone says so")

        // A start step on Codex (providerId "codex"): its sandbox, no allow rules, a model or null; shown as Codex.
        var cx = WFStep.make(WFStep.start, project: "/p", providerId: WFStep.codexProvider)
        check(cx.isCodex && cx.permissionMode == "read-only" && cx.allowedTools == [], "workflows: a new Codex step starts read only, with no rules")
        cx.permissionMode = "workspace-write"; cx.allowedTools = ["Bash(git log:*)"]
        cx.runOn(codex: false, claude: "anthropic")
        check(!cx.isCodex && cx.providerId == "anthropic" && cx.permissionMode == "dontAsk" && cx.allowedTools == [] && cx.model == nil, "workflows: a Codex step moved back to Claude Code takes Claude Code's modes")
        cx.runOn(codex: true, claude: "anthropic")
        let cj = enc(cx)
        check(cj["providerId"] as? String == "codex" && cj["permissionMode"] as? String == "read-only" && (cj["allowedTools"] as? [Any])?.isEmpty == true && cj["model"] is NSNull, "workflows: a Codex step is sent with its sandbox, no rules and model null")
        check(WorkflowWords.stepTitle(cx) == "Start a Codex session" && WorkflowWords.stepTitle(WFStep.make(WFStep.start)) == "Start a session", "workflows: a Codex step reads as Codex, not as a removed provider")
        let skipped = WFRunStep(id: "", state: "skipped", reason: "no_provider")
        check(WorkflowWords.reason(skipped, codex: true).text == "Codex isn’t set up on this Mac" && WorkflowWords.reason(skipped).text == "Its provider is gone", "workflows: Codex off says so; a provider gone as before")
    }
}
