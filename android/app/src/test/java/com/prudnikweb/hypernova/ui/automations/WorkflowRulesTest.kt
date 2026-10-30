package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AutoMode
import com.prudnikweb.hypernova.data.AutomationInput
import com.prudnikweb.hypernova.data.Budget
import com.prudnikweb.hypernova.data.Features
import com.prudnikweb.hypernova.data.Guard
import com.prudnikweb.hypernova.data.Output
import com.prudnikweb.hypernova.data.Step
import com.prudnikweb.hypernova.data.Then
import com.prudnikweb.hypernova.data.Trigger
import com.prudnikweb.hypernova.data.WorkflowShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workflow editor's rules that don't need the screen: where the Mac's `field` lands, which
 * placeholders a text can hold and where they go, the conditions a step can have, and what keeps a
 * workflow from saving (the daemon's codes, before the round trip).
 */
class WorkflowRulesTest {
    private val hyper = "/Users/me/Programming/hypernova"
    private fun start(prompt: String = "Review it", mode: String = AutoMode.ALLOWED) = Action.Start(hyper, prompt, "anthropic", null, mode, emptyList(), Then.NONE)
    private fun flow(vararg steps: Step, outputs: List<Output> = emptyList(), trigger: Trigger = Trigger.Time("09:00", Trigger.WEEKDAYS), budget: Budget? = null) =
        AutomationInput("Review", true, false, trigger, steps.first().action, null, steps = steps.toList(), outputs = outputs, budget = budget)
    private fun codes(d: AutomationInput, features: Set<String> = Features.ALL) = WorkflowRules.check(d, features).map { "${it.key}:${it.code}" + (it.sub?.let { s -> ".$s" } ?: "") }

    // ─── Where the Mac's field lands ──────────────────────────────────────

    @Test fun fieldPaths() {
        assertEquals("steps[2]", FieldPath.key("steps[2].command"))
        assertEquals("command", FieldPath.sub("steps[2].command"))
        assertEquals("if", FieldPath.sub("steps[1].if.text"))
        assertEquals("allowedTools", FieldPath.sub("steps[0].allowedTools[3]"))
        assertEquals("steps[1]", FieldPath.key("steps[1]"))
        assertNull(FieldPath.sub("steps[1]"))
        assertEquals("outputs[0]", FieldPath.key("outputs[0].url"))
        assertEquals("url", FieldPath.sub("outputs[0].url"))
        assertEquals(FieldPath.WHEN, FieldPath.key("trigger.expr"))
        assertEquals("expr", FieldPath.sub("trigger.expr"))
        assertEquals(FieldPath.WHEN, FieldPath.key("trigger"))
        assertEquals(FieldPath.BUDGET, FieldPath.key("budget.minutes"))
        assertEquals(FieldPath.NAME, FieldPath.key("name"))
        assertEquals("steps[0]", FieldPath.key("action.prompt"))
        assertEquals("steps", FieldPath.key("steps"))
        assertEquals(FieldPath.OTHER, FieldPath.key("fromStep"))
        // No field (an older daemon): the block its code belongs to.
        assertEquals(FieldPath.WHEN, FieldPath.key(null, "bad_cron"))
        assertEquals(FieldPath.NAME, FieldPath.key(null, "bad_name"))
        assertEquals("steps[0]", FieldPath.key(null, "rule_too_broad"))
        assertEquals(FieldPath.OTHER, FieldPath.key(null, "too_many"))
        assertEquals(2, FieldPath.index("steps[2]"))
        assertEquals(0, FieldPath.index("outputs[0]"))
        assertNull(FieldPath.index("budget"))
    }

    // ─── Placeholders ─────────────────────────────────────────────────────

    @Test fun placeholdersByTriggerAndTheStepsBefore() {
        val steps = listOf(Step("a", null, start()), Step("b", null, Action.Shell(hyper, "npm test")), Step("c", null, Action.Ask("Go?")), Step("d", null, Action.Notify("x")))
        val time = Trigger.Time("09:00", Trigger.WEEKDAYS)
        assertEquals(listOf("{project}", "{time}"), Placeholders.offered(time, steps, 0, Placeholders.Where.Prompt))
        assertEquals(listOf("{project}", "{prev}", "{time}"), Placeholders.offered(time, steps, 1, Placeholders.Where.Prompt))
        assertEquals(listOf("{project}", "{prev}", "{exit}", "{output}", "{answer}", "{time}"), Placeholders.offered(time, steps, 3, Placeholders.Where.Text))
        assertEquals("outputs see every step", Placeholders.offered(time, steps, 3, Placeholders.Where.Text), Placeholders.offered(time, steps, 4, Placeholders.Where.Output))
        assertEquals(listOf("{project}", "{session}", "{result}", "{ask}", "{time}"), Placeholders.offered(Trigger.SessionEvent(Trigger.NEEDS_YOU, null, Trigger.MINE), steps, 0, Placeholders.Where.Text))
        assertEquals(listOf("{project}", "{payload}", "{time}"), Placeholders.offered(Trigger.Webhook(), steps, 0, Placeholders.Where.Text))
        assertEquals(listOf("{project}", "{session}", "{result}", "{payload}", "{time}"), Placeholders.offered(Trigger.Hook(Trigger.H_STOP, null, null, Trigger.MINE), steps, 0, Placeholders.Where.Text))
        assertEquals(listOf("{project}", "{battery}", "{time}"), Placeholders.offered(Trigger.Plugged, steps, 0, Placeholders.Where.Text))
        assertEquals(listOf("{project}", "{week}", "{time}"), Placeholders.offered(Trigger.Reset(Trigger.SEVEN_DAY, false), steps, 0, Placeholders.Where.Text))
    }

    @Test fun outsideWordsStayOutOfCommandsAndNoAsking() {
        val steps = listOf(Step("a", null, start()), Step("b", null, Action.Ask("Go?")), Step("c", null, Action.Shell(hyper, "x")))
        // A command holds only the safe ones (filled as quoted shell words); the rest are its environment.
        assertEquals(listOf("{project}", "{answer}", "{time}"), Placeholders.offered(Trigger.Webhook(), steps, 2, Placeholders.Where.Shell))
        // No asking can't take another session's words.
        assertEquals(listOf("{project}", "{answer}", "{time}"), Placeholders.offered(Trigger.Webhook(), steps, 2, Placeholders.Where.Prompt, bypass = true))
        assertEquals(listOf("{prev}", "{payload}"), Placeholders.used("Review {prev} for {payload} and {prev} again"))
    }

    @Test fun anEarlierStepsResult() {
        val steps = listOf(Step("st_a", null, start()), Step("st_n", null, Action.Notify("x")), Step("st_q", null, Action.Ask("Go?")), Step("st-s", null, Action.Shell(hyper, "x")))
        val time = Trigger.Time("09:00", Trigger.WEEKDAYS)
        // Only when the Mac offers it; each earlier step with a result (a notification has none).
        assertEquals(listOf("{project}", "{prev}", "{answer}", "{time}"), Placeholders.offered(time, steps, 3, Placeholders.Where.Text))
        assertEquals(listOf("{project}", "{prev}", "{answer}", "{step:st_a}", "{step:st_q}", "{time}"), Placeholders.offered(time, steps, 3, Placeholders.Where.Text, features = Features.ALL))
        assertEquals("an output sees every step", listOf("{step:st_a}", "{step:st_q}", "{step:st-s}"), Placeholders.offered(time, steps, 4, Placeholders.Where.Output, features = Features.ALL).filter { it.startsWith("{step:") })
        // Outside words: never in No asking, never in a command (its environment instead, "-" read as "_").
        assertTrue(Placeholders.offered(time, steps, 3, Placeholders.Where.Prompt, bypass = true, features = Features.ALL).none { it.startsWith("{step:") })
        assertTrue(Placeholders.offered(time, steps, 4, Placeholders.Where.Shell, features = Features.ALL).none { it.startsWith("{step:") })
        assertEquals(Placeholders.ENV + listOf("\"\$HYPERNOVA_STEP_st_a\"", "\"\$HYPERNOVA_STEP_st_q\""), Placeholders.env(steps, 3, Features.ALL))
        assertEquals("\"\$HYPERNOVA_STEP_st_s\"", WorkflowShape.stepEnv("st-s"))
        assertEquals(Placeholders.ENV, Placeholders.env(steps, 3, setOf(Features.STEPS)))
        assertEquals(listOf("{prev}", "{step:st_a}"), Placeholders.used("Got {prev} and {step:st_a}, {step:st_a}"))
        assertTrue(Placeholders.outside("{step:st_a}") && !Placeholders.outside("{answer}"))
    }

    @Test fun anEarlierStepsResultIsCheckedBeforeSave() {
        val a = Step("st_a", null, start())
        assertEquals(emptyList<String>(), codes(flow(a, Step("st_b", null, Action.Notify("Got {step:st_a}")), outputs = listOf(Output.Notify("o", "{step:st_a}")))))
        assertEquals("a later step", listOf("steps[0]:bad_placeholder.prompt"), codes(flow(Step("st_a", null, start("Again {step:st_b}")), Step("st_b", null, start()))))
        assertEquals("a notification has none", listOf("steps[2]:bad_placeholder.question"), codes(flow(a, Step("st_n", null, Action.Notify("x")), Step("st_q", null, Action.Ask("Ok? {step:st_n}")))))
        assertEquals("one that's gone", listOf("steps[1]:bad_placeholder.text"), codes(flow(a, Step("st_b", null, Action.Notify("Got {step:st_zz}")))))
        assertEquals(listOf("steps[1]:bad_placeholder.prompt"), codes(flow(a, Step("st_b", null, Action.Continue("step:st_a", "Your review: {step:st_zz}", Then.NONE)))))
        assertEquals("No asking can't take it", listOf("steps[1]:bad_placeholder.prompt"), codes(flow(a, Step("st_b", null, start("Fix {step:st_a}", mode = AutoMode.NO_ASKING)))))
        assertEquals("a command reads it from its environment", listOf("steps[1]:bad_placeholder.command"), codes(flow(a, Step("st_b", null, Action.Shell(hyper, "echo {step:st_a}")))))
        assertEquals(listOf("outputs[0]:bad_placeholder.text"), codes(flow(a, outputs = listOf(Output.Notify("o", "{step:st_zz}")))))
    }

    @Test fun aPlaceholderGoesAtTheCursor() {
        assertEquals("Tests failing: {prev}" to 21, Placeholders.insert("Tests failing:", 14, 14, "{prev}"))
        assertEquals("Tests failing: {prev}" to 21, Placeholders.insert("Tests failing: ", 15, 15, "{prev}"))
        assertEquals("{prev}" to 6, Placeholders.insert("", 0, 0, "{prev}"))
        // Over a selection, and touching a word before it.
        assertEquals("Ask {session} now" to 13, Placeholders.insert("Ask them now", 4, 8, "{session}"))
        assertEquals("Ask them {session}now" to 18, Placeholders.insert("Ask themnow", 8, 8, "{session}"))
        // Inside quotes or brackets it doesn't add a space.
        assertEquals("echo \"{answer}\"" to 14, Placeholders.insert("echo \"\"", 6, 6, "{answer}"))
        // A cursor past the end (the text changed under it) is held to the text.
        assertEquals("x {time}" to 8, Placeholders.insert("x", 9, 9, "{time}"))
    }

    // ─── Conditions ───────────────────────────────────────────────────────

    @Test fun theConditionsAStepCanHave() {
        val steps = listOf(Step("a", null, start()), Step("b", null, Action.Ask("Go?", listOf("Ship", "Wait", "Never"), "Wait")), Step("c", null, Action.Notify("x")))
        assertEquals(emptyList<Guard?>(), GuardOptions.offered(Features.ALL, steps, 0))
        assertEquals("without steps on the Mac, none", emptyList<Guard?>(), GuardOptions.offered(emptySet(), steps, 2))
        assertEquals(emptyList<Guard?>(), GuardOptions.offered(null, steps, 2))
        assertEquals(listOf(null, Guard(Guard.FAILED), Guard(Guard.OK), Guard(Guard.NOT_QUIET), Guard(Guard.QUIET), Guard(Guard.CONTAINS, "")), GuardOptions.offered(Features.ALL, steps, 1))
        val afterAsk = GuardOptions.offered(Features.ALL, steps, 2)
        assertEquals(listOf(Guard(Guard.ANSWER, "Ship"), Guard(Guard.ANSWER, "Wait"), Guard(Guard.ANSWER, "Never")), afterAsk.takeLast(3))
    }

    // ─── What keeps it from saving ────────────────────────────────────────

    @Test fun aGoodWorkflowSaves() {
        val d = flow(
            Step("a", null, start()),
            Step("b", null, Action.Ask("Post this review? {prev}")),
            Step("c", Guard(Guard.ANSWER, "approve"), Action.Shell(hyper, "gh pr review \"\$n\" --body-file {project}/r.md")),
            Step("d", Guard(Guard.CONTAINS, "error"), Action.Continue("step:a", "Fix it: {prev}", Then.NONE)),
            outputs = listOf(Output.Webhook("o", null, Output.SLACK, url = "https://hooks.slack.com/services/T/B/x"), Output.File("f", null, "$hyper/log.md")),
            budget = Budget(30, null),
        )
        assertEquals(emptyList<String>(), codes(d))
    }

    @Test fun stepsAndConditions() {
        assertEquals(listOf("steps[0]:bad_guard.if"), codes(flow(Step("a", Guard(Guard.FAILED), start()))))
        assertEquals(listOf("steps[1]:guard_text.if"), codes(flow(Step("a", null, start()), Step("b", Guard(Guard.CONTAINS, " "), Action.Notify("x")))))
        assertEquals("an answer with no ask before it", listOf("steps[1]:bad_guard.if"), codes(flow(Step("a", null, start()), Step("b", Guard(Guard.ANSWER, "Approve"), Action.Notify("x")))))
        assertEquals("not one of its answers", listOf("steps[2]:bad_guard.if"), codes(flow(Step("a", null, start()), Step("q", null, Action.Ask("Go?")), Step("b", Guard(Guard.ANSWER, "Maybe"), Action.Notify("x")))))
        assertEquals(listOf("steps:bad_steps"), codes(flow(*Array(6) { Step("s$it", null, start()) })))
        assertEquals(listOf("steps[0]:no_prompt.prompt"), codes(flow(Step("a", null, start(prompt = " ")))))
        assertEquals(listOf("steps[0]:project_needed.projectPath"), codes(flow(Step("a", null, start().copy(projectPath = "")))))
        assertEquals("No asking can't take another session's words", listOf("steps[0]:bad_placeholder.prompt"), codes(flow(Step("a", null, start(prompt = "Do {payload}", mode = AutoMode.NO_ASKING)))))
        assertEquals(listOf("steps[0]:rule_too_broad.allowedTools"), codes(flow(Step("a", null, start().copy(allowedTools = listOf("Bash"))))))
        assertEquals("a step that isn't earlier", listOf("steps[0]:bad_target.target"), codes(flow(Step("a", null, Action.Continue("step:b", "go", Then.NONE)), Step("b", null, start()))))
        assertEquals("the session that fired it needs a session's trigger", listOf("steps[0]:bad_target.target"), codes(flow(Step("a", null, Action.Continue(Action.Continue.TRIGGER, "go", Then.NONE)))))
        assertEquals(emptyList<String>(), codes(flow(Step("a", null, Action.Continue(Action.Continue.TRIGGER, "go", Then.NONE)), trigger = Trigger.Hook(Trigger.H_STOP, null, null, Trigger.MINE))))
        assertEquals(listOf("steps[0]:text_needed.text"), codes(flow(Step("a", null, Action.Notify("")))))
    }

    @Test fun asksAndCommands() {
        assertEquals(listOf("steps[0]:bad_question.question"), codes(flow(Step("a", null, Action.Ask(" ")))))
        assertEquals(listOf("steps[0]:bad_answers.answers"), codes(flow(Step("a", null, Action.Ask("Go?", listOf("Yes"), "Yes")))))
        assertEquals("the same answer twice, any case", listOf("steps[0]:bad_answers.answers"), codes(flow(Step("a", null, Action.Ask("Go?", listOf("Yes", "yes"), "yes")))))
        assertEquals(listOf("steps[0]:bad_answers.answers"), codes(flow(Step("a", null, Action.Ask("Go?", listOf("A", "B", "C", "D"), "D")))))
        assertEquals(listOf("steps[0]:bad_answers.default"), codes(flow(Step("a", null, Action.Ask("Go?", listOf("A", "B"), "C")))))
        assertEquals(listOf("steps[0]:bad_timeout.timeoutMin"), codes(flow(Step("a", null, Action.Ask("Go?", timeoutMin = 90)))))
        assertEquals("not offered", listOf("steps[0]:bad_step"), codes(flow(Step("a", null, Action.Ask("Go?"))), features = setOf(Features.STEPS)))
        assertEquals(listOf("steps[0]:no_command.command"), codes(flow(Step("a", null, Action.Shell(hyper, " ")))))
        assertEquals("outside words reach a command only as its environment", listOf("steps[0]:bad_placeholder.command"), codes(flow(Step("a", null, Action.Shell(hyper, "echo {payload}")))))
        assertEquals(listOf("steps[0]:bad_timeout.timeoutSec"), codes(flow(Step("a", null, Action.Shell(hyper, "x", 601)))))
        assertEquals(listOf("steps[0]:bad_step"), codes(flow(Step("a", null, Action.Shell(hyper, "x"))), features = setOf(Features.STEPS)))
    }

    @Test fun outputs() {
        fun out(o: Output) = codes(flow(Step("a", null, start()), outputs = listOf(o)))
        assertEquals(listOf("outputs[0]:url_needed.url"), out(Output.Webhook("o", null, Output.SLACK)))
        assertEquals("kept on the Mac already", emptyList<String>(), out(Output.Webhook("o", null, Output.SLACK, "hooks.slack.com", "Xq7Z")))
        assertEquals(listOf("outputs[0]:insecure_url.url"), out(Output.Webhook("o", null, Output.POST, url = "http://example.com/hook")))
        assertEquals("Slack's own address", listOf("outputs[0]:bad_url.url"), out(Output.Webhook("o", null, Output.SLACK, url = "https://example.com/hook")))
        assertEquals(emptyList<String>(), out(Output.Webhook("o", null, Output.DISCORD, url = "https://discordapp.com/api/webhooks/1/x")))
        assertEquals(emptyList<String>(), out(Output.Webhook("o", null, Output.NTFY, url = "https://ntfy.example.org/topic")))
        assertEquals(listOf("outputs[0]:url_needed.botToken"), out(Output.Webhook("o", null, Output.TELEGRAM)))
        assertEquals(listOf("outputs[0]:bad_bot_token.botToken"), out(Output.Webhook("o", null, Output.TELEGRAM, botToken = "nope", chatId = "@me")))
        assertEquals(listOf("outputs[0]:bad_chat.chatId"), out(Output.Webhook("o", null, Output.TELEGRAM, botToken = "123456789:AAbbCCddEEffGGhhIIjjKKll", chatId = "me")))
        assertEquals(emptyList<String>(), out(Output.Webhook("o", null, Output.TELEGRAM, botToken = "123456789:AAbbCCddEEffGGhhIIjjKKll", chatId = "-100123")))
        assertEquals(listOf("outputs[0]:bad_path.path"), out(Output.File("f", null, "$hyper/")))
        assertEquals(listOf("outputs[0]:bad_text.text"), out(Output.Notify("n", "x".repeat(501))))
        assertEquals(listOf("outputs:bad_outputs"), codes(flow(Step("a", null, start()), outputs = listOf(Output.Notify("1"), Output.Notify("2"), Output.Notify("3")))))
        assertEquals(listOf("budget:bad_budget"), codes(flow(Step("a", null, start()), budget = Budget(721, null))))
    }

    @Test fun theNewTriggers() {
        assertEquals(listOf("when:bad_cron.expr"), codes(flow(Step("a", null, start()), trigger = Trigger.Cron(" "))))
        assertEquals(listOf("when:project_needed.path"), codes(flow(Step("a", null, start()), trigger = Trigger.FileWatch("", null, Trigger.CHANGED))))
        assertEquals("four ** at most", listOf("when:bad_glob.glob"), codes(flow(Step("a", null, start()), trigger = Trigger.FileWatch(hyper, "**/a/**/b/**/c/**/d/**", Trigger.CHANGED))))
        assertEquals(emptyList<String>(), codes(flow(Step("a", null, start()), trigger = Trigger.FileWatch(hyper, "{src,test}/**/*.ts", Trigger.CHANGED))))
        assertEquals(listOf("when:bad_trigger.events"), codes(flow(Step("a", null, start()), trigger = Trigger.Github(hyper, emptyList(), 10))))
        assertEquals(listOf("when:project_needed.repoPath"), codes(flow(Step("a", null, start()), trigger = Trigger.Github("", listOf(Trigger.PR_OPENED), 10))))
        assertEquals(listOf("name:bad_name"), codes(flow(Step("a", null, start())).copy(name = " ")))
        // byKey keeps the first problem of each block.
        val byKey = WorkflowRules.byKey(WorkflowRules.check(flow(Step("a", null, start(prompt = "")), Step("b", null, Action.Notify(""))).copy(name = ""), Features.ALL))
        assertEquals(setOf("name", "steps[0]", "steps[1]"), byKey.keys)
        assertTrue(WorkflowRules.presetHost(Output.SLACK, "https://hooks.slack.com/services/x"))
    }

    @Test fun aNewTriggerKeepsTheFolderAlreadyNamed() {
        val zone = java.time.ZoneId.of("Europe/Moscow")
        val time = Trigger.Time("09:00", Trigger.WEEKDAYS)
        // From a time of day: the editor's project; cron reads on from the time.
        assertEquals(Trigger.FileWatch(hyper, null, Trigger.CHANGED), defaultTrigger(WhenKind.File, time, 0L, zone, hyper))
        assertEquals(Trigger.Github(hyper, listOf(Trigger.PR_OPENED), 10), defaultTrigger(WhenKind.Github, time, 0L, zone, hyper))
        assertEquals(Trigger.Cron("0 9 * * 1-5"), defaultTrigger(WhenKind.Cron, time, 0L, zone, hyper))
        // A folder a trigger already named wins over the editor's.
        val other = "/Users/me/Programming/0stems"
        assertEquals(Trigger.Github(other, listOf(Trigger.PR_OPENED), 10), defaultTrigger(WhenKind.Github, Trigger.FileWatch(other, "*.md", Trigger.CREATED), 0L, zone, hyper))
        assertEquals(Trigger.Hook(Trigger.H_TOOL_FAILURE, null, other, Trigger.MINE), defaultTrigger(WhenKind.Hook, Trigger.SessionEvent(Trigger.FINISHED, other, Trigger.MINE), 0L, zone, hyper))
        // A session's trigger names a folder only when one was named: any project otherwise.
        assertEquals(Trigger.SessionEvent(Trigger.FINISHED, null, Trigger.MINE), defaultTrigger(WhenKind.Finished, time, 0L, zone, hyper))
        assertEquals(Trigger.Webhook(), defaultTrigger(WhenKind.Webhook, time, 0L, zone, hyper))
    }

    @Test fun theWorkflowTemplatesAreOfferedOnlyWhenTheMacCanRunThem() {
        val today = Templates.offered(emptySet(), null).map { it.id }
        assertTrue(Templates.REVIEW !in today && Templates.FIX_TESTS !in today)
        assertTrue("empty features: none of them", Templates.offered(emptySet(), emptySet()).none { it.needs.isNotEmpty() })
        val steps = Templates.offered(emptySet(), setOf(Features.STEPS)).map { it.id }
        assertTrue(Templates.FIX_TESTS in steps && Templates.REVIEW !in steps)
        val all = Templates.offered(emptySet(), Features.ALL).map { it.id }
        assertTrue(all.containsAll(listOf(Templates.FIX_TESTS, Templates.REVIEW, Templates.DIGEST, Templates.TEAM)))
        // The review's command holds no placeholder: what fired it and Claude's review are read from its environment.
        assertEquals(emptyList<String>(), Placeholders.used(Templates.REVIEW_COMMAND))
        assertTrue(Templates.REVIEW_COMMAND.contains("\"\$HYPERNOVA_PAYLOAD\""))
        assertTrue(Templates.REVIEW_COMMAND.contains("--body \"\$HYPERNOVA_STEP_${Templates.REVIEW_STEP}\""))
        assertTrue("no file in between", ".hypernova" !in Templates.REVIEW_COMMAND)
        // Without {step:<id>} on the Mac the review can't post; without "after", Tell my team can't wait.
        assertTrue(Templates.REVIEW !in Templates.offered(emptySet(), Features.ALL - Features.STEP_OUTPUT).map { it.id })
        assertTrue(Templates.TEAM !in Templates.offered(emptySet(), Features.ALL - Features.T_AFTER).map { it.id })
    }
}
