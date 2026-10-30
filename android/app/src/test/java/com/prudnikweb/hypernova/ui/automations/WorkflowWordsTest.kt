package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AutoMode
import com.prudnikweb.hypernova.data.Budget
import com.prudnikweb.hypernova.data.BudgetUsed
import com.prudnikweb.hypernova.data.Guard
import com.prudnikweb.hypernova.data.Output
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.data.RunOutput
import com.prudnikweb.hypernova.data.RunStep
import com.prudnikweb.hypernova.data.Step
import com.prudnikweb.hypernova.data.StepAnswer
import com.prudnikweb.hypernova.data.Then
import com.prudnikweb.hypernova.data.Trigger
import com.prudnikweb.hypernova.data.WorkflowShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Workflows in the phone's words, read from the real strings: the sentence a workflow reads as,
 * the new triggers in it, a step's condition, what came of each step and output on a run's
 * timeline, and the placeholders' one-line explanations. Never a "·" between fields.
 */
class WorkflowWordsTest {
    private val zone: ZoneId = ZoneId.of("Europe/Moscow")
    private val now = ZonedDateTime.of(LocalDate.of(2026, 10, 16), LocalTime.of(11, 40), zone).toInstant().toEpochMilli()
    private val w = XmlWords()
    private val ctx = SummaryContext(zone, now, projectName = { it.substringAfterLast('/') })
    private val hyper = "/Users/me/Programming/hypernova"
    private fun start(prompt: String) = Action.Start(hyper, prompt, "anthropic", null, AutoMode.ALLOWED, emptyList(), Then.NONE)
    private fun say(t: Trigger, steps: List<Step>, outputs: List<Output> = emptyList()) = Summary.flow(w, t, steps, outputs, false, ctx).replace("⁠", "")

    @Test fun aSimpleOneReadsExactlyAsToday() {
        val t = Trigger.Time("09:00", Trigger.WEEKDAYS)
        val steps = listOf(Step("st_0", null, start("Run the tests")))
        assertEquals(Summary.sentence(w, t, start("Run the tests").copy(then = Then.NOTIFY), false, ctx), Summary.flow(w, t, steps, WorkflowShape.outputsOfThen(Then.NOTIFY), false, ctx))
        assertEquals("Every weekday at 9:00, in hypernova, ask Claude to run the tests. Notify me when it's done.", say(t, steps, WorkflowShape.outputsOfThen(Then.NOTIFY)))
    }

    @Test fun aWorkflowReadsAsItsFirstStepAndWhatFollows() {
        val gh = Trigger.Github(hyper, listOf(Trigger.PR_OPENED), 10)
        val steps = listOf(Step("a", null, start("Review the pull request")), Step("b", null, Action.Ask("Post this review?")), Step("c", Guard(Guard.ANSWER, "Approve"), Action.Shell(hyper, "gh pr review")))
        assertEquals(
            "When hypernova gets a new pull request on GitHub, in hypernova, ask Claude to review the pull request, then 2 more steps. When it's done, post it to Slack and add it to log.md.",
            say(gh, steps, listOf(Output.Webhook("o", null, Output.SLACK), Output.File("f", null, "$hyper/log.md"))),
        )
        assertEquals(
            "When a script or a webhook fires it, in hypernova, run “npm run build”. When it's done, notify me and add it to log.md.",
            say(Trigger.Webhook(), listOf(Step("a", null, Action.Shell(hyper, "npm run build"))), listOf(Output.Notify("o", null), Output.File("f", null, "$hyper/log.md"))),
        )
        assertEquals("On the schedule “*/15 9-18 * * 1-5”, ask me “Ship it?”", say(Trigger.Cron("*/15 9-18 * * 1-5"), listOf(Step("a", null, Action.Ask("Ship it?")))))
    }

    @Test fun theNewTriggersInWords() {
        fun t(x: Trigger) = Summary.trigger(w, x, false, ctx)
        assertEquals("When a file matching docs/** changes in hypernova", t(Trigger.FileWatch(hyper, "docs/**", Trigger.CHANGED)))
        assertEquals("When a file is added in hypernova", t(Trigger.FileWatch(hyper, null, Trigger.CREATED)))
        assertEquals("When a file matching *.md is deleted in hypernova", t(Trigger.FileWatch(hyper, "*.md", Trigger.DELETED)))
        assertEquals("When hypernova gets a review on my pull requests or failed checks on my pull requests on GitHub", t(Trigger.Github(hyper, listOf(Trigger.CHECKS_FAILED, Trigger.REVIEW_COMMENT), 5)))
        assertEquals("When one of my sessions in hypernova has a Bash call fail", t(Trigger.Hook(Trigger.H_TOOL_FAILURE, "Bash", hyper, Trigger.MINE)))
        assertEquals("When any session is about to compact its context", t(Trigger.Hook(Trigger.H_PRE_COMPACT, null, null, Trigger.ANY)))
        assertEquals("When an automated session ends a turn", t(Trigger.Hook(Trigger.H_STOP, null, null, Trigger.AUTOMATED)))
        // Fires on events: "only the next time" applies.
        assertTrue(Summary.trigger(w, Trigger.Webhook(), true, ctx) != Summary.trigger(w, Trigger.Webhook(), false, ctx))
        assertTrue("cron is timed: no 'next time only'", Summary.trigger(w, Trigger.Cron("0 9 * * *"), true, ctx).startsWith("On the schedule"))
    }

    @Test fun conditionsInWords() {
        assertEquals("unless the step before failed", WorkflowWords.guard(w, null))
        assertEquals("only if the step before failed", WorkflowWords.guard(w, Guard(Guard.FAILED)))
        assertEquals("only if the step before went fine", WorkflowWords.guard(w, Guard(Guard.OK)))
        assertEquals("only if the step before had nothing new", WorkflowWords.guard(w, Guard(Guard.QUIET)))
        assertEquals("only if the step before had news", WorkflowWords.guard(w, Guard(Guard.NOT_QUIET)))
        // It looks at the whole reply or output, not the two sentences a row shows.
        assertEquals("only if the step before mentions “error”", WorkflowWords.guard(w, Guard(Guard.CONTAINS, " error ")))
        assertEquals("only if the step before mentions certain words", WorkflowWords.guard(w, Guard(Guard.CONTAINS, "")))
        assertEquals("only if the answer is Approve", WorkflowWords.guard(w, Guard(Guard.ANSWER, "Approve")))
        assertEquals("only if something this app doesn't know yet", WorkflowWords.guard(w, Guard("moon")))
    }

    @Test fun aRunsStepsInWords() {
        fun line(s: RunStep) = WorkflowWords.step(w, s, now)
        assertEquals(RunLine("Not yet", RunTone.Pending), line(RunStep("a", Step.START, RunStep.PENDING)))
        assertEquals(RunLine("Working for 2m 00s", RunTone.Active), line(RunStep("a", Step.START, Run.RUNNING, startedAt = now - 120_000)))
        assertEquals(RunLine("Waiting for your answer", RunTone.Alert), line(RunStep("b", Step.ASK, Run.NEEDS_YOU)))
        assertEquals(RunLine("Answered Approve", RunTone.Ok), line(RunStep("b", Step.ASK, Run.DONE, answer = StepAnswer("Approve", "phone", now))))
        assertEquals("Answered Skip on the Mac", line(RunStep("b", Step.ASK, Run.DONE, answer = StepAnswer("Skip", "mac", now))).text)
        assertEquals("Nobody answered: taken as Skip", line(RunStep("b", Step.ASK, Run.DONE, answer = StepAnswer("Skip", StepAnswer.TIMEOUT, now))).text)
        assertEquals(RunLine("Exited 0: ✓ built in 41.2s", RunTone.Ok), line(RunStep("c", Step.SHELL, Run.DONE, exit = 0, result = "building…\n✓ built in 41.2s\n")))
        assertEquals(RunLine("Exited 1: npm ERR! Missing script: build", RunTone.Failed), line(RunStep("c", Step.SHELL, Run.FAILED, "exit", exit = 1, result = "npm ERR! Missing script: build")))
        assertEquals(RunLine("Stopped: it ran past its time", RunTone.Failed), line(RunStep("c", Step.SHELL, Run.FAILED, "timeout")))
        assertEquals(RunLine("Skipped: its condition didn't hold", RunTone.Skipped), line(RunStep("d", Step.NOTIFY, Run.SKIPPED, RunStep.GUARD)))
        assertEquals(RunLine("Skipped: a step before it failed", RunTone.Skipped), line(RunStep("d", Step.NOTIFY, Run.SKIPPED, RunStep.FAILED_BEFORE)))
        assertEquals(RunLine("Not reached", RunTone.Skipped), line(RunStep("d", Step.NOTIFY, Run.SKIPPED, RunStep.NOT_REACHED)))
        assertEquals(RunLine("Sent", RunTone.Ok), line(RunStep("d", Step.NOTIFY, Run.DONE, result = "hi")))
        assertEquals(RunLine("Nothing new", RunTone.Quiet), line(RunStep("a", Step.START, Run.DONE, quiet = true, result = "Nothing new.")))
        assertEquals("Failed: API Error: 529", line(RunStep("a", Step.START, Run.FAILED, "error", result = "API Error: 529")).text)
        assertEquals("Stopped by you", line(RunStep("a", Step.START, Run.STOPPED, "by_user")).text)
        assertEquals("From the run it retried", line(RunStep("a", Step.START, Run.DONE, result = "ok", reused = true)).late)
        // A command the daemon lost to its own restart; one a signal killed.
        assertEquals(RunLine("Stopped: Hypernova restarted while it ran", RunTone.Stopped), line(RunStep("c", Step.SHELL, Run.STOPPED, "ended")))
        assertEquals("Failed: Killed by SIGKILL", line(RunStep("c", Step.SHELL, Run.FAILED, "error", result = "Killed by SIGKILL")).text)
    }

    @Test fun aRunsOutputsAndItsNewReasons() {
        assertEquals(RunLine("Sent", RunTone.Ok), WorkflowWords.output(w, RunOutput("o", "webhook", RunOutput.SENT)))
        assertEquals(RunLine("Didn't go: Slack answered 404.", RunTone.Failed), WorkflowWords.output(w, RunOutput("o", "webhook", RunOutput.FAILED, "Slack answered 404.")))
        assertEquals(RunLine("Not sent", RunTone.Skipped), WorkflowWords.output(w, RunOutput("o", "file", RunOutput.SKIPPED)))
        assertEquals(RunLine("Sending", RunTone.Active), WorkflowWords.output(w, RunOutput("o", "webhook", RunOutput.PENDING)))
        assertEquals(RunLine("Trying again: Slack answered 503.", RunTone.Active), WorkflowWords.output(w, RunOutput("o", "webhook", RunOutput.PENDING, "Slack answered 503.")))
        assertEquals("Slack", WorkflowWords.outputName(w, Output.Webhook("o", null, Output.SLACK), "webhook"))
        assertEquals("log.md", WorkflowWords.outputName(w, Output.File("f", null, "$hyper/log.md"), "file"))
        assertEquals("A webhook", WorkflowWords.outputName(w, null, "webhook"))
        fun run(reason: String, result: String? = null) = Run("r", "a", "x", "manual", now, null, if (reason == "budget") Run.STOPPED else Run.FAILED, reason, null, emptyList(), now, now, result = result)
        assertEquals(RunLine("Stopped: it reached its budget", RunTone.Stopped), RunWords.line(w, run("budget"), now, zone))
        assertEquals(RunLine("Failed: npm ERR! Missing script", RunTone.Failed), RunWords.line(w, run("exit", "npm ERR! Missing script"), now, zone).copy(late = null))
    }

    @Test fun whichStepRetryStartsFrom() {
        fun r(vararg states: String) = Run("r", "a", "x", "manual", now, null, Run.FAILED, null, null, emptyList(), now, now, steps = states.mapIndexed { i, s -> RunStep("s$i", Step.START, s) })
        assertEquals(1, RunWords.failedStep(r(Run.DONE, Run.FAILED, Run.SKIPPED)))
        assertEquals(0, RunWords.failedStep(r(Run.STOPPED, Run.SKIPPED)))
        assertNull(RunWords.failedStep(r(Run.DONE, Run.DONE)))
        assertNull(RunWords.failedStep(r()))
    }

    @Test fun stepLabelsAndPlaceholders() {
        assertEquals("Review the pull request", WorkflowWords.detail(w, start("Review the pull request")))
        assertEquals("npm test", WorkflowWords.detail(w, Action.Shell(hyper, "\nnpm test\nnpm run lint")))
        assertEquals("Its earlier session: Fix it", WorkflowWords.detail(w, Action.Continue("step:a", "Fix it", Then.NONE)))
        assertEquals("Run a shell command", WorkflowWords.kindTitle(w, Action.Shell(hyper, "x")))
        assertEquals("Ask me", WorkflowWords.kindTitle(w, Step.ASK))
        // A run's step named from the automation as it is now; a notify by its kind; one that's gone, by its kind.
        assertEquals("Post this review?", WorkflowWords.stepName(w, RunStep("b", Step.ASK, Run.DONE), Step("b", null, Action.Ask("Post this review?"))))
        assertEquals("Notify me", WorkflowWords.stepName(w, RunStep("d", Step.NOTIFY, Run.DONE), Step("d", null, Action.Notify("Tests failing: {prev}"))))
        assertEquals("Run a shell command", WorkflowWords.stepName(w, RunStep("x", Step.SHELL, Run.DONE), null))
        assertEquals("{prev}: what the step before said, two sentences, or why it failed", WorkflowWords.placeholder(w, "{prev}"))
        assertEquals("\$HYPERNOVA_PAYLOAD: what fired it", WorkflowWords.placeholder(w, "\"\$HYPERNOVA_PAYLOAD\""))
        assertEquals("Write the question, 200 characters at most.", WorkflowWords.issue(w, "bad_question"))
        assertEquals("Paste its address.", WorkflowWords.issue(w, "url_needed"))
    }

    @Test fun cronFromATimeOfDay() {
        assertEquals("0 9 * * 1-5", TriggerWords.cronOf(Trigger.Time("09:00", Trigger.WEEKDAYS)))
        assertEquals("30 7 * * *", TriggerWords.cronOf(Trigger.Time("07:30", Trigger.ALL_DAYS)))
        assertEquals("0 10 * * 0,6", TriggerWords.cronOf(Trigger.Time("10:00", listOf(6, 7))))
        assertEquals("0 9-19/2 * * 1-5", TriggerWords.cronOf(Trigger.Every(2, "09:00", "19:00", Trigger.WEEKDAYS)))
        assertEquals("0 1/2 * * *", TriggerWords.cronOf(Trigger.Every(2, "09:00", null, Trigger.ALL_DAYS)))
        assertNull(TriggerWords.cronOf(Trigger.Plugged))
        assertEquals("curl -fsS -X POST -H 'Authorization: Bearer hnf_x' --data 'it is done' 'http://127.0.0.1:47322/f'", TriggerWords.curl("http://127.0.0.1:47322/f", "hnf_x"))
    }

    @Test fun anEarlierStepsResultInWords() {
        assertEquals("Step 2's result", WorkflowWords.chip(w, "{step:st_a}", 2))
        assertEquals("Step 1's result", WorkflowWords.chip(w, "\"\$HYPERNOVA_STEP_st_a\"", 1))
        assertEquals("{prev}", WorkflowWords.chip(w, "{prev}", null))
        assertEquals("\$HYPERNOVA_PREV", WorkflowWords.chip(w, "\"\$HYPERNOVA_PREV\"", null))
        assertEquals("Step 2's result: what it said in two sentences, a command's last lines, or your answer", WorkflowWords.placeholder(w, "{step:st_a}", 2))
        assertTrue(WorkflowWords.placeholder(w, "\"\$HYPERNOVA_STEP_st_a\"", 1).startsWith("Step 1's whole result for the command"))
        assertTrue(WorkflowWords.placeholder(w, "{step:st_gone}", null).startsWith("A step that isn't before this one"))
    }

    @Test fun aSessionStillNeedingMe() {
        fun t(x: Trigger) = Summary.trigger(w, x, false, ctx)
        assertEquals("When any session needs me", t(Trigger.SessionEvent(Trigger.NEEDS_YOU, null, Trigger.MINE)))
        assertEquals("When any session still needs me after 10 minutes", t(Trigger.SessionEvent(Trigger.NEEDS_YOU, null, Trigger.MINE, after = 10)))
        assertEquals("When any session still needs me after 1 minute", t(Trigger.SessionEvent(Trigger.NEEDS_YOU, null, Trigger.MINE, after = 1)))
        assertEquals("only needs you waits", "When any session finishes", t(Trigger.SessionEvent(Trigger.FINISHED, null, Trigger.MINE, after = 10)))
    }

    @Test fun aRunsBudget() {
        fun lines(u: BudgetUsed, b: Budget?) = RunWords.budget(w, u, b).map { it.text to it.used }
        assertEquals(listOf("12 of 30 min" to 0.4f), lines(BudgetUsed(12.0, null), Budget(30, null)))
        assertEquals(listOf("3 of 30 min" to 0.1f, "2.4 of 10 points" to 0.24f), lines(BudgetUsed(3.0, 2.4), Budget(30, 10)))
        assertEquals("points only", listOf("2 of 10 points" to 0.2f), lines(BudgetUsed(7.0, 2.0), Budget(null, 10)))
        assertEquals("reached", listOf("31 of 30 min" to 1f), lines(BudgetUsed(31.0, null), Budget(30, null)))
        assertEquals("no budget on it now", listOf("4 min at work" to null, "1.5 points of the 5-hour window" to null), lines(BudgetUsed(4.0, 1.5), null))
        for ((text, _) in lines(BudgetUsed(3.0, 2.4), Budget(30, 10))) assertTrue(" · " !in text)
    }

    @Test fun noDotJoinedWords() {
        for ((name, text) in XmlWords.strings) if (name.startsWith("wf_")) assertTrue("$name joins with a dot: $text", " · " !in text)
    }
}
