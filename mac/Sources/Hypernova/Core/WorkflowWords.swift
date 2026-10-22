import Foundation

/// Workflows in words, the phone's (android/.../WorkflowWords.kt, RunWords.kt, strings_workflows.xml):
/// a step's guard, what a step does on one line, what came of each step and output, what a
/// placeholder fills in with, and the editor's problems. Pure, so a test can read every line, and
/// never "·"-joined.
enum WorkflowWords {
    enum Tone { case ok, quiet, skipped, failed, stopped, active, alert, pending }
    struct Line: Equatable { var text: String; var tone: Tone; var late: String? = nil }

    // MARK: Steps

    static func guardText(_ g: WFGuard?) -> String {
        guard let g else { return "unless the step before failed" }
        switch g.on {
        case WFGuard.ok: return "only if the step before went fine"
        case WFGuard.failed: return "only if the step before failed"
        case WFGuard.quiet: return "only if the step before had nothing new"
        case WFGuard.notQuiet: return "only if the step before had news"
        case WFGuard.contains:
            if let t = g.text?.trimmingCharacters(in: .whitespaces), !t.isEmpty { return "only if the step before mentions “\(t)”" }
            return "only if the step before mentions certain words"
        case WFGuard.answer: return "only if the answer is \(g.text ?? "")"
        default: return "only if something this app doesn’t know yet"
        }
    }

    /// The conditions a step can have, in words, for its pick row: the first is the no-condition rule.
    static func guardChoices(answers: [String]) -> [WFGuard?] {
        var out: [WFGuard?] = [nil, WFGuard(on: WFGuard.ok), WFGuard(on: WFGuard.failed), WFGuard(on: WFGuard.quiet), WFGuard(on: WFGuard.notQuiet), WFGuard(on: WFGuard.contains, text: "")]
        out += answers.map { WFGuard(on: WFGuard.answer, text: $0) }
        return out
    }

    /// A step's title in the editor: its kind's, or Codex's for a start step Codex runs.
    static func stepTitle(_ s: WFStep) -> String { s.isCodex ? "Start a Codex session" : kindTitle(s.kind) }

    /// The automation's first session runs on Codex: what its run-level reasons are about.
    static func startsOnCodex(_ steps: [WFStep]?) -> Bool { (steps ?? []).first { $0.kind == WFStep.start }?.isCodex == true }

    static func kindTitle(_ kind: String) -> String {
        switch kind {
        case WFStep.start: return "Start a session"
        case WFStep.continue: return "Continue a session"
        case WFStep.ask: return "Ask me"
        case WFStep.shell: return "Run a shell command"
        case WFStep.notify: return "Notify me"
        case WFStep.say: return "Read aloud on my glasses"
        default: return "Something this app doesn’t know yet"
        }
    }

    static func cut(_ s: String, _ n: Int = 90) -> String {
        let one = s.split(whereSeparator: \.isNewline).joined(separator: " ").trimmingCharacters(in: .whitespaces)
        if one.count <= n { return one }
        let head = String(one.prefix(n))
        let at = head.lastIndex(of: " ").map { String(head[..<$0]) } ?? head
        return at.trimmingCharacters(in: CharacterSet(charactersIn: ",.:; ")) + "…"
    }

    /// What a step does, on one line: its prompt, question or command cut short.
    static func detail(_ s: WFStep) -> String? {
        let v: String?
        switch s.kind {
        case WFStep.start, WFStep.continue:
            let p = cut(s.prompt ?? "")
            if s.kind == WFStep.continue {
                let who: String
                switch s.target ?? "" {
                case "trigger": who = "The session that fired it"
                case "working": who = "Every session at work"
                case "limited": who = "Every session a limit stopped"
                case let t where t.hasPrefix("step:"): who = "Its earlier session"
                default: who = "A session"
                }
                v = p.isEmpty ? who : "\(who): \(p)"
            } else { v = p }
        case WFStep.ask: v = cut(s.question ?? "")
        case WFStep.shell: v = (s.command ?? "").split(whereSeparator: \.isNewline).first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }).map { $0.trimmingCharacters(in: .whitespaces) }
        default: v = cut(s.text ?? "")
        }
        return (v?.isEmpty ?? true) ? nil : v
    }

    /// A run's step on its timeline: its name from the automation as it is now, or its kind's title.
    static func stepName(_ rs: WFRunStep, def: WFStep?) -> String {
        guard let def else { return kindTitle(rs.kind ?? "") }
        if def.kind == WFStep.notify || def.kind == WFStep.say { return kindTitle(def.kind) }
        return detail(def).map { cut($0, 56) } ?? kindTitle(def.kind)
    }

    // MARK: Runs

    static func duration(_ seconds: Double) -> String {
        let s = Int(max(0, seconds))
        if s < 60 { return "\(s)s" }
        if s < 3600 { return "\(s / 60)m \(String(format: "%02d", s % 60))s" }
        return "\(s / 3600)h \(String(format: "%02d", (s % 3600) / 60))m"
    }

    static func ran(_ s: WFRunStep, now: Date) -> Double? {
        guard let a = s.startedAt else { return nil }
        let end = s.endedAt ?? now.timeIntervalSince1970 * 1000
        return max(0, end - a) / 1000
    }

    private static func lastLine(_ text: String) -> String? {
        text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.last(where: { !$0.isEmpty }).map { cut($0, 80) }
    }

    /// What came of a run's step, for its row: running, waiting for an answer, an answer and who gave
    /// it, a command's exit, a result, or why it didn't run. [codex]: Codex runs it (its reasons say so).
    static func step(_ s: WFRunStep, now: Date, codex: Bool = false) -> Line {
        let took = ran(s, now: now).map(duration)
        var line: Line
        switch s.state {
        case "pending": line = Line(text: "Not yet", tone: .pending)
        case "running": line = Line(text: took.map { "Working for \($0)" } ?? "Working", tone: .active)
        case "waiting": line = Line(text: s.reason == "target_busy" ? "Waiting for that session to be free" : "Waiting for your limit to reset", tone: .active)
        case "needs_you": line = Line(text: s.kind == WFStep.ask ? "Waiting for your answer" : "Needs you", tone: .alert)
        case "done":
            if let a = s.answer { line = Line(text: answered(a), tone: .ok) }
            else if s.kind == WFStep.shell {
                let said = s.result.flatMap(lastLine)
                line = Line(text: said.map { "Exited \(s.exit ?? 0): \($0)" } ?? "Exited \(s.exit ?? 0)", tone: s.quiet == true ? .quiet : .ok)
            }
            else if s.quiet == true { line = Line(text: "Nothing new.", tone: .quiet) }
            else if s.kind == WFStep.notify || s.kind == WFStep.say { line = Line(text: "Sent", tone: .ok) }
            else { line = Line(text: s.result ?? "Done", tone: .ok) }
        case "failed":
            if s.reason == "exit" || (s.kind == WFStep.shell && (s.exit ?? 0) != 0 && s.exit != nil) {
                let said = s.result.flatMap(lastLine)
                line = Line(text: said.map { "Exited \(s.exit ?? 1): \($0)" } ?? "Exited \(s.exit ?? 1)", tone: .failed)
            } else if s.reason == "timeout" && s.kind == WFStep.shell { line = Line(text: "Stopped: it ran past its time", tone: .failed) }
            else { line = reason(s, codex: codex) }
        case "stopped":
            line = s.kind == WFStep.shell && s.reason == "ended" ? Line(text: "Stopped: Hypernova restarted while it ran", tone: .stopped) : reason(s, codex: codex)
        case "skipped":
            switch s.reason {
            case "guard": line = Line(text: "Skipped: its condition didn’t hold", tone: .skipped)
            case "failed_before": line = Line(text: "Skipped: a step before it failed", tone: .skipped)
            case "not_reached": line = Line(text: "Not reached", tone: .skipped)
            default: line = reason(s, codex: codex)
            }
        default: line = Line(text: s.state, tone: .quiet)
        }
        if s.reused == true { line.late = "From the run it retried" }
        return line
    }

    /// A step that failed, stopped or was skipped for one of the run's reasons.
    static func reason(_ s: WFRunStep, codex: Bool = false) -> Line {
        let tone: Tone = s.state == "failed" ? .failed : s.state == "skipped" ? .skipped : .stopped
        let text: String
        switch s.reason ?? "" {
        case "no_project": text = "Its project folder is gone"
        case "outside_projects": text = "Its folder is outside your projects folder"
        case "no_session": text = "The session it works in is gone"
        case "no_provider": text = codex ? "Codex isn’t set up on this Mac" : "Its provider is gone"
        case "no_model": text = "Its model isn’t offered any more"
        case "start_failed": text = codex ? "Codex didn’t start" : "Claude Code didn’t start"
        case "signed_out" where codex: text = "Codex is signed out on the Mac"
        case "budget": text = "Stopped: it reached its budget"
        case "by_user": text = "Stopped by you"
        case "away": text = "Stopped: nobody answered"
        case "timeout": text = "Stopped: it ran past its time"
        case "limited": text = "Stopped: your usage limit is reached"
        case "target_busy": text = "That session stayed busy"
        case "error": text = s.result.map { "Failed: \($0)" } ?? "Failed"
        default:
            if let r = s.result, !r.isEmpty { text = s.state == "failed" ? "Failed: \(r)" : r }
            else { text = s.state == "failed" ? "Failed" : s.state == "skipped" ? "Skipped" : "Stopped" }
        }
        return Line(text: text, tone: tone)
    }

    /// "Approve", "Approve, on your glasses", "Nobody answered: taken as Skip".
    static func answered(_ a: WFAnswer) -> String {
        switch a.by {
        case "timeout": return "Nobody answered: taken as \(a.text)"
        case "glasses": return "Answered \(a.text) on your glasses"
        case "mac": return "Answered \(a.text) on the Mac"
        default: return "Answered \(a.text)"
        }
    }

    static func outputName(_ o: WFOutput?, kind: String) -> String {
        guard let o else {
            switch kind { case "notify": return "Notify me"; case "say": return "Read aloud"; case "webhook": return "A webhook"; case "file": return "A file"; default: return kind }
        }
        switch o.kind {
        case WFOutput.notify: return o.glasses == true ? "Notify me and my glasses" : "Notify me"
        case WFOutput.say: return "Read aloud"
        case WFOutput.webhook: return presetName(o.preset)
        case WFOutput.file: return ((o.path ?? "") as NSString).lastPathComponent.isEmpty ? "A file" : (o.path! as NSString).lastPathComponent
        default: return o.kind
        }
    }

    static func presetName(_ p: String?) -> String {
        switch p { case "ntfy": return "ntfy"; case "slack": return "Slack"; case "discord": return "Discord"; case "telegram": return "Telegram"; default: return "A web address" }
    }

    /// What became of an output: sent, didn't go (and why), or not sent.
    static func output(_ o: WFRunOutput) -> Line {
        switch o.state {
        case "pending": return Line(text: o.error.map { "Trying again: \($0)" } ?? "Sending", tone: .active)
        case "sent": return Line(text: "Sent", tone: .ok)
        case "failed": return Line(text: o.error.map { "Didn’t go: \($0)" } ?? "Didn’t go", tone: .failed)
        default: return Line(text: "Not sent", tone: .skipped)
        }
    }

    /// The run as a whole, one line, for the editor's RUNS rows.
    /// [codex]: its first session is Codex's (the account it's gated on).
    static func run(_ r: WFRun, now: Date, codex: Bool = false) -> Line {
        switch r.state {
        case "queued": return Line(text: "In line", tone: .active)
        case "waiting": return Line(text: "Waiting for your limit to reset", tone: .active)
        case "running": return Line(text: "Working", tone: .active)
        case "needs_you": return Line(text: r.ask.map { "Asking: \($0.question)" } ?? "Needs you", tone: .alert)
        case "done": return r.quiet == true ? Line(text: "Nothing new.", tone: .quiet) : Line(text: r.result ?? "Done", tone: .ok)
        default:
            let failing = r.steps?.first { $0.state == r.state }
            if let f = failing { return Line(text: step(f, now: now, codex: codex).text, tone: step(f, now: now, codex: codex).tone) }
            return reason(WFRunStep(id: "", state: r.state, reason: r.reason, result: r.result), codex: codex)
        }
    }

    // MARK: Asks

    /// "Skip in 9m", seconds only in the last two minutes.
    static func askCountdown(_ ask: WFAskItem, now: Date) -> String {
        let left = max(0, ask.until.timeIntervalSince(now))
        let t: String = left < 120 ? "\(Int(left.rounded(.up)))s" : "\(Int((left / 60).rounded(.up)))m"
        return "\(ask.default) in \(t)"
    }

    static func askSaid(_ ask: WFAskItem, now: Date) -> String {
        "\(ask.name) asks: \(ask.question). If nobody answers, \(askCountdown(ask, now: now))."
    }

    /// A refused answer, calmly: answered already, by whom when the daemon said.
    static func notAsking(_ run: WFRun?) -> String {
        if let a = run?.steps?.first(where: { $0.answer != nil })?.answer { return "It was answered already: \(answered(a))" }
        return "It was answered already."
    }

    // MARK: Placeholders and problems

    /// `{step:<id>}`: an earlier step's result in a text (quoted in a prompt, as `{prev}` is).
    static func stepRef(_ id: String) -> String { "{step:\(id)}" }
    /// A command's way to an earlier step's whole result: its variable, quoted ("-" in an id reads "_").
    static func stepEnv(_ id: String) -> String { "\"$HYPERNOVA_STEP_\(id.replacingOccurrences(of: "-", with: "_"))\"" }
    /// The number (from 1) of the step [token] names, as `{step:<id>}` or its variable; nil for any other.
    static func stepNumber(_ token: String, steps: [WFStep]) -> Int? {
        guard token.hasPrefix("{step:") || token.hasPrefix("\"$HYPERNOVA_STEP_") else { return nil }
        return steps.firstIndex { stepRef($0.id) == token || stepEnv($0.id) == token }.map { $0 + 1 }
    }
    /// A chip's words: "Step 2’s result" for an earlier step's, the placeholder itself otherwise.
    static func chip(_ token: String, steps: [WFStep]) -> String {
        if let n = stepNumber(token, steps: steps) { return "Step \(n)’s result" }
        return token.replacingOccurrences(of: "\"", with: "")
    }

    static func placeholder(_ token: String, steps: [WFStep] = []) -> String {
        if token.hasPrefix("{step:") || token.hasPrefix("\"$HYPERNOVA_STEP_") {
            guard let n = stepNumber(token, steps: steps) else { return "A step that isn’t before this one any more: take it out, or move that step up" }
            return token.hasPrefix("{step:") ? "Step \(n)’s result: what it said in two sentences, a command’s last lines, or your answer"
                : "Step \(n)’s whole result for the command: the whole reply, a command’s output, or your answer"
        }
        switch token {
        case "{project}": return "{project}: the project’s name"
        case "{session}": return "{session}: the title of the session that fired it"
        case "{result}": return "{result}: that session’s last reply, two sentences"
        case "{ask}": return "{ask}: what that session asks, like Bash: git push"
        case "{battery}": return "{battery}: how full the Mac’s battery is"
        case "{time}": return "{time}: when it runs, like 9:00"
        case "{week}": return "{week}: your week from Statistics"
        case "{prev}": return "{prev}: what the step before said, two sentences, or why it failed"
        case "{exit}": return "{exit}: the last command’s exit code"
        case "{output}": return "{output}: the last command’s output"
        case "{answer}": return "{answer}: the answer you gave"
        case "{payload}": return "{payload}: what fired it: a webhook’s body, the files that changed, GitHub’s news"
        case "\"$HYPERNOVA_PREV\"": return "$HYPERNOVA_PREV: what the step before said"
        case "\"$HYPERNOVA_PAYLOAD\"": return "$HYPERNOVA_PAYLOAD: what fired it"
        case "\"$HYPERNOVA_OUTPUT\"": return "$HYPERNOVA_OUTPUT: the last command’s output"
        case "\"$HYPERNOVA_ANSWER\"": return "$HYPERNOVA_ANSWER: the answer you gave"
        default: return "Filled in when it runs"
        }
    }

    enum Place { case prompt, bypassPrompt, command, text }

    /// The placeholders a place can hold: a command the safe ones and its environment; a No asking prompt leaves out another session's words.
    /// [stepOutput]: the daemon offers `{step:<id>}`, so each earlier step with a result is one ("Step 2’s result"; a command's variable).
    static func placeholders(for place: Place, steps: [WFStep], index: Int, trigger: WFTrigger, stepOutput: Bool = false) -> [String] {
        let before = steps.prefix(index)
        let hasShell = before.contains { $0.kind == WFStep.shell }
        let hasAsk = before.contains { $0.kind == WFStep.ask }
        let hasPrev = before.contains { $0.kind != WFStep.notify && $0.kind != WFStep.say }
        let results = stepOutput ? before.filter { $0.isKnown && $0.kind != WFStep.notify && $0.kind != WFStep.say } : []
        switch place {
        case .command:
            var out = ["{project}", "{time}", "{battery}"]
            if hasShell { out.append("{exit}") }
            if hasAsk { out.append("{answer}") }
            if hasPrev { out.append("\"$HYPERNOVA_PREV\"") }
            if trigger.kind != "time" && trigger.kind != "cron" { out.append("\"$HYPERNOVA_PAYLOAD\"") }
            if hasShell { out.append("\"$HYPERNOVA_OUTPUT\"") }
            return out + results.map { stepEnv($0.id) }
        case .bypassPrompt:
            var out = ["{project}", "{time}", "{battery}", "{week}"]
            if hasShell { out.append("{exit}") }
            if hasAsk { out.append("{answer}") }
            return out
        default:
            var out = ["{project}", "{time}", "{battery}", "{week}"]
            if trigger.kind == "session" || trigger.kind == "hook" { out += ["{session}", "{result}"] }
            if trigger.kind == "session" { out.append("{ask}") }
            if hasPrev { out.append("{prev}") }
            if hasShell { out += ["{exit}", "{output}"] }
            if hasAsk { out.append("{answer}") }
            if ["webhook", "file", "github", "hook"].contains(trigger.kind) { out.append("{payload}") }
            return out + results.map { stepRef($0.id) }
        }
    }

    /// An editor problem in words: the daemon's code, or this app's own.
    static func issue(_ code: String, _ daemon: String? = nil) -> String {
        switch code {
        case "bad_name": return "Give it a name, 60 characters at most."
        case "project_needed": return "Pick the project."
        case "no_prompt": return "Write what it should do."
        case "text_needed": return "Write what to say."
        case "provider_needed": return "Pick where it runs."
        case "bad_target": return "That session isn’t there to continue."
        case "bad_placeholder": return "Words from another session or from outside can’t go there. A command reads them from its environment."
        case "bad_rule": return "That isn’t a rule Claude Code reads."
        case "rule_too_broad": return "That rule allows too much."
        case "bad_guard": return "That condition doesn’t fit here."
        case "guard_text": return "Write the words to look for."
        case "bad_question": return "Write the question, 200 characters at most."
        case "bad_answers": return "Give 2 or 3 different answers, 24 characters each at most."
        case "bad_timeout": return "Pick how long it waits."
        case "no_command": return "Write the command to run."
        case "bad_step": return "Your Mac can’t run this step yet. Update Hypernova on the Mac, or pick another."
        case "bad_steps": return "A workflow has 1 to 5 steps."
        case "bad_outputs": return "Tell two places at most."
        case "bad_output": return "Your Mac can’t send there yet."
        case "bad_text": return "500 characters at most."
        case "url_needed": return "Paste its address."
        case "bad_url": return "That isn’t this service’s address."
        case "insecure_url": return "Use an https address."
        case "bad_bot_token": return "That isn’t a bot token from @BotFather."
        case "bad_chat": return "A chat is a number or @channel."
        case "bad_path": return "Pick a file inside the project."
        case "secret_file": return "That file holds secrets. Pick another."
        case "bad_budget": return "A budget is 1 to 720 minutes, or 1 to 100 points of the window."
        case "bad_cron": return "That isn’t a schedule: five fields, like */15 9-18 * * 1-5."
        case "too_often": return "That runs more often than every 5 minutes."
        case "not_github": return "That project has no GitHub remote."
        case "no_gh": return "GitHub’s gh is missing or signed out on the Mac."
        case "bad_glob": return "That pattern doesn’t read: 200 characters and four ** at most."
        case "no_project": return "That project folder isn’t there."
        case "outside_projects": return "That folder is outside your projects folder."
        case "bad_trigger": return "That moment isn’t one your Mac can watch for."
        case "has_steps": return "This automation has steps. Edit it in a newer Hypernova."
        case "steps_changed": return "Its steps changed since that run. Retry it from the start."
        case "not_asking": return "That was answered already."
        case "bad_answer": return "That isn’t one of its answers."
        case "confirm_shell": return "A command needs Touch ID or your password first."
        case "no_glasses": return "No glasses are paired to read aloud on."
        case "too_many": return "That’s 50 automations, the most there can be."
        default: return daemon ?? "Couldn’t save it."
        }
    }

    // MARK: The card

    static func triggerPhrase(_ t: WFTrigger) -> String {
        func clock(_ s: String?) -> String { s ?? "" }
        switch t.kind {
        case "webhook": return "When a script or a webhook fires it"
        case "cron": return "On the schedule “\(t.expr ?? "")”"
        case "file":
            let folder = ((t.path ?? "") as NSString).lastPathComponent
            let what = t.event == "deleted" ? "is deleted" : t.event == "changed" ? "changes" : "is added"
            if let g = t.glob, !g.isEmpty { return "When a file matching \(g) \(what) in \(folder)" }
            return "When a file \(what) in \(folder)"
        case "github":
            let m = ["pr_opened": "a new pull request", "review_comment": "a review on my pull requests", "checks_failed": "failed checks on my pull requests"]
            let list = (t.events ?? []).compactMap { m[$0] }
            let said = list.count > 1 ? list.dropLast().joined(separator: ", ") + " or " + list.last! : (list.first ?? "news")
            return "When \(((t.repoPath ?? "") as NSString).lastPathComponent) gets \(said) on GitHub"
        case "hook":
            let who = t.from == "automated" ? "an automated session" : t.from == "any" ? "any session" : "one of my sessions"
            let ev: String
            switch t.event ?? "" {
            case "Stop": ev = "ends a turn"
            case "StopFailure": ev = "stops on an API error"
            case "PostToolUseFailure": ev = t.tool.map { "has a \($0) call fail" } ?? "has a tool call fail"
            case "PreCompact": ev = "is about to compact its context"
            default: ev = "ends"
            }
            return "When \(who) \(ev)"
        case "time": return "At \(clock(t.at.flatMap { if case .clock(let s) = $0 { return s } else { return nil } }))"
        case "once": return "Once, at a set time"
        case "every": return "Every \(t.hours ?? 1) hours"
        case "reset": return "When your usage window resets"
        case "limit": return "When you reach a usage limit"
        case "session": return t.event == "needs_you" ? "When a session needs you" : "When a session finishes"
        case "power": return t.event == "low" ? "When the battery runs low" : "When the Mac is plugged in"
        default: return "When something this app doesn’t know yet happens"
        }
    }

    /// "When …, in hypernova, run “npm run build”, then 2 more steps. When it’s done, post it to Slack and add it to RELEASES.md."
    static func sentence(_ a: WFAutomation) -> String {
        var out = triggerPhrase(a.trigger)
        let steps = a.steps ?? []
        if let s = steps.first {
            let first: String
            let proj = ((s.projectPath ?? "") as NSString).lastPathComponent
            switch s.kind {
            case WFStep.shell: first = (s.command ?? "").isEmpty ? "in \(proj), run a command" : "in \(proj), run “\(cut(s.command ?? "", 40))”"
            case WFStep.ask: first = (s.question ?? "").isEmpty ? "ask me" : "ask me “\(cut(s.question ?? "", 40))”"
            case WFStep.start:
                let on = s.isCodex ? " in Codex" : ""
                first = (s.prompt ?? "").isEmpty ? (s.isCodex ? "start a Codex session" : "start a session") : "start “\(cut(s.prompt ?? "", 40))”\(on)"
            case WFStep.continue: first = "continue a session"
            case WFStep.say: first = "read “\(cut(s.text ?? "", 40))” aloud"
            default: first = "tell me “\(cut(s.text ?? "", 40))”"
            }
            out += ", " + first
            if steps.count > 1 { out += ", then \(steps.count - 1) more step\(steps.count == 2 ? "" : "s")" }
        }
        out += "."
        let told: [String] = (a.outputs ?? []).map {
            switch $0.kind {
            case WFOutput.notify: return $0.glasses == true ? "notify me and my glasses" : "notify me"
            case WFOutput.say: return "read it aloud on my glasses"
            case WFOutput.webhook: return "post it to \(presetName($0.preset))"
            default: return "add it to \(outputName($0, kind: "file"))"
            }
        }
        if !told.isEmpty { out += " When it’s done, " + (told.count > 1 ? told.dropLast().joined(separator: ", ") + " and " + told.last! : told[0]) + "." }
        return out
    }

    static func stepsTag(_ n: Int) -> String { "\(n) step\(n == 1 ? "" : "s")" }
}
