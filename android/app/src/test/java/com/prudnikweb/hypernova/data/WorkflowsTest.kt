package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Workflows on the wire (docs/PROTOCOL.md, "Automations", "Workflows"): an automation with steps,
 * guards, outputs and a budget, a run with its steps, its question and its outputs, a note with
 * the question, the new triggers, and a save's body in each shape. And the old-client rules
 * (daemon/src/automations/contract.ts: mirrorAction, stepOfAction, outputsOfThen), pinned.
 */
class WorkflowsTest {
    private val hyper = "/Users/me/Programming/hypernova"
    private fun start(prompt: String = "Review it", mode: String = AutoMode.ALLOWED, then: String = Then.NONE) = Action.Start(hyper, prompt, "anthropic", null, mode, emptyList(), then)

    // ─── The old-client rules ─────────────────────────────────────────────

    @Test fun thenAsOutputs() {
        assertEquals(listOf(Output.Notify("ou_0", null, false)), WorkflowShape.outputsOfThen(Then.NOTIFY))
        assertEquals(listOf(Output.Say("ou_0", null)), WorkflowShape.outputsOfThen(Then.SAY))
        assertEquals(emptyList<Output>(), WorkflowShape.outputsOfThen(Then.NONE))
    }

    @Test fun anActionIsOneStep() {
        val (step, outs) = WorkflowShape.stepOfAction(start(then = Then.NOTIFY))
        assertEquals("st_0", step.id)
        assertNull(step.guard)
        assertEquals(Then.NONE, (step.action as Action.Start).then)
        assertEquals(WorkflowShape.outputsOfThen(Then.NOTIFY), outs)
        // A notify or say step tells by itself: no outputs.
        assertEquals(emptyList<Output>(), WorkflowShape.stepOfAction(Action.Notify("hi")).second)
    }

    @Test fun mirrorShowsASimpleOneExactly() {
        val one = listOf(Step("st_0", null, start()))
        assertEquals(start(then = Then.NOTIFY), WorkflowShape.mirror(one, WorkflowShape.outputsOfThen(Then.NOTIFY)))
        assertEquals(start(then = Then.SAY), WorkflowShape.mirror(one, WorkflowShape.outputsOfThen(Then.SAY)))
        assertEquals(start(then = Then.NONE), WorkflowShape.mirror(one, emptyList()))
        val cont = Action.Continue(Action.Continue.WORKING, "go on", Then.NONE)
        assertEquals(cont.copy(then = Then.NOTIFY), WorkflowShape.mirror(listOf(Step("st_0", null, cont)), WorkflowShape.outputsOfThen(Then.NOTIFY)))
        assertEquals(Action.Notify("hi"), WorkflowShape.mirror(listOf(Step("st_0", null, Action.Notify("hi"))), emptyList()))
        assertFalse(WorkflowShape.isWorkflow(one, emptyList()))
    }

    @Test fun mirrorRefusesWhatAnOldClientCantShow() {
        val one = listOf(Step("st_0", null, start()))
        // Two steps; a guard; a step:<id> target; outputs then can't say; a notify step with outputs.
        assertNull(WorkflowShape.mirror(one + Step("st_1", null, Action.Notify("x")), emptyList()))
        assertNull(WorkflowShape.mirror(listOf(Step("st_0", Guard(Guard.FAILED), start())), emptyList()))
        assertNull(WorkflowShape.mirror(listOf(Step("st_0", null, Action.Continue("step:st_9", "x", Then.NONE))), emptyList()))
        assertNull(WorkflowShape.mirror(one, listOf(Output.Notify("ou_0", "custom text"))))
        assertNull(WorkflowShape.mirror(one, listOf(Output.Notify("ou_0", null, glasses = true))))
        assertNull(WorkflowShape.mirror(one, listOf(Output.Notify("ou_0"), Output.Say("ou_1"))))
        assertNull(WorkflowShape.mirror(one, listOf(Output.Webhook("ou_0", preset = Output.SLACK))))
        assertNull(WorkflowShape.mirror(one, listOf(Output.File("ou_0", path = "$hyper/log.md"))))
        assertNull(WorkflowShape.mirror(listOf(Step("st_0", null, Action.Notify("x"))), WorkflowShape.outputsOfThen(Then.NOTIFY)))
        assertNull(WorkflowShape.mirror(listOf(Step("st_0", null, Action.Shell(hyper, "npm test"))), emptyList()))
        assertNull(WorkflowShape.mirror(listOf(Step("st_0", null, Action.Ask("Go?"))), emptyList()))
        assertTrue(WorkflowShape.isWorkflow(listOf(Step("st_0", null, Action.Ask("Go?"))), emptyList()))
    }

    @Test fun anAutomationFromAnOlderDaemonReadsAsOneStep() {
        val o = JSONObject("""{"id":"au_1","name":"Brief","enabled":true,"onlyOnce":false,"trigger":{"kind":"time","at":"09:00","days":[1,2,3,4,5]},
            "action":{"kind":"start","projectPath":"$hyper","prompt":"Sum up","providerId":"anthropic","model":null,"permissionMode":"dontAsk","allowedTools":[],"then":"notify"}}""")
        val a = AutomationWire.automation(o)!!
        assertNull("no steps from a daemon without workflows", a.steps)
        assertEquals(1, WorkflowShape.stepsOf(a).size)
        assertEquals(WorkflowShape.outputsOfThen(Then.NOTIFY), WorkflowShape.outputsOf(a))
        // Its save stays today's shape: an action, nothing of steps.
        val body = AutomationWire.json(a.input())
        assertTrue(body.has("action"))
        assertFalse(body.has("steps"))
        assertFalse(body.has("outputs"))
    }

    @Test fun featuresAbsentEmptyOrListed() {
        assertNull(AutomationWire.fromSnapshot(JSONObject("""{"automations":{"paused":false,"list":[]}}"""))!!.features)
        val none = AutomationWire.fromSnapshot(JSONObject("""{"automations":{"paused":false,"list":[],"features":[]}}"""))!!
        assertEquals(emptySet<String>(), none.features)
        assertFalse(none.has(Features.STEPS))
        val some = AutomationWire.fromSnapshot(JSONObject("""{"automations":{"paused":false,"list":[],"features":["steps","ask","trigger.cron","what.next"]}}"""))!!
        assertTrue(some.has(Features.STEPS) && some.has(Features.T_CRON))
        assertFalse(some.has(Features.SHELL))
        assertTrue("a name it doesn't know is kept, and ignored", "what.next" in some.features!!)
    }

    // ─── An automation with steps ─────────────────────────────────────────

    private val workflow = JSONObject(
        """{"id":"au_rv","name":"Review before merge","enabled":true,"onlyOnce":false,"template":"review","paid":false,"problem":"no_gh",
        "trigger":{"kind":"github","repoPath":"$hyper","events":["pr_opened","checks_failed"],"every":15},
        "action":{"kind":"workflow","steps":3},
        "steps":[
          {"id":"st_a","if":null,"kind":"start","projectPath":"$hyper","prompt":"Review {payload}","providerId":"anthropic","model":"sonnet","permissionMode":"dontAsk","allowedTools":["Bash(gh pr view:*)"]},
          {"id":"st_b","if":null,"kind":"ask","question":"Post it? {prev}","answers":["Approve","Skip","Later"],"default":"Later","timeoutMin":20},
          {"id":"st_c","if":{"on":"answer","text":"Approve"},"kind":"shell","projectPath":"$hyper","command":"gh pr review","timeoutSec":120},
          {"id":"st_d","if":{"on":"contains","text":"error"},"kind":"continue","target":"step:st_a","prompt":"Fix it","providerId":null}
        ],
        "outputs":[
          {"id":"ou_a","kind":"webhook","preset":"slack","host":"hooks.slack.com","last4":"Xq7Z","text":null},
          {"id":"ou_b","kind":"file","path":"$hyper/log.md","text":"{prev}"},
          {"id":"ou_c","kind":"pager","text":"x"}
        ],
        "budget":{"minutes":30,"windowPct":null},
        "lastRun":{"id":"ru_1","automationId":"au_rv","name":"Review before merge","fired":{"by":"github","at":1000,"sessionId":null,"detail":"PR #12 opened by alice"},
          "state":"needs_you","reason":null,"until":null,"sessionIds":["s1"],"startedAt":1000,"endedAt":null,"late":0,"missed":0,"lateWhy":null,
          "result":null,"quiet":false,"skippedTools":[],"permissionMode":"dontAsk","depth":0,"retryOf":null,
          "steps":[
            {"id":"st_a","kind":"start","state":"done","reason":null,"startedAt":1000,"endedAt":5000,"result":"Looks good.","quiet":false,"sessionIds":["s1"],"exit":null,"answer":null,"reused":false},
            {"id":"st_b","kind":"ask","state":"needs_you","reason":null,"startedAt":5000,"endedAt":null,"result":null,"quiet":false,"sessionIds":[],"exit":null,"answer":null,"reused":false},
            {"id":"st_c","kind":"shell","state":"pending","reason":null,"startedAt":null,"endedAt":null,"result":null,"quiet":false,"sessionIds":[],"exit":null,"answer":null,"reused":false},
            {"id":"st_d","kind":"continue","state":"pending"}
          ],
          "step":1,"ask":{"stepId":"st_b","question":"Post it? Looks good.","answers":["Approve","Skip","Later"],"default":"Later","until":605000},
          "outputs":[],"retryFrom":null,"budgetUsed":{"minutes":1.5,"windowPct":null}}}""",
    )

    @Test fun anAutomationWithSteps() {
        val a = AutomationWire.automation(workflow)!!
        assertEquals(Trigger.Github(hyper, listOf(Trigger.PR_OPENED, Trigger.CHECKS_FAILED), 15), a.trigger)
        assertTrue("the old client's mirror is a kind this app reads as unknown", a.action is Action.Unknown)
        val steps = a.steps!!
        assertEquals(listOf("st_a", "st_b", "st_c", "st_d"), steps.map { it.id })
        assertEquals(Action.Ask("Post it? {prev}", listOf("Approve", "Skip", "Later"), "Later", 20), steps[1].action)
        assertEquals(Guard(Guard.ANSWER, "Approve"), steps[2].guard)
        assertEquals(Action.Shell(hyper, "gh pr review", 120), steps[2].action)
        assertEquals(Action.Continue("step:st_a", "Fix it", Then.NONE, null), steps[3].action)
        assertEquals(Output.Webhook("ou_a", null, Output.SLACK, "hooks.slack.com", "Xq7Z"), a.outputs[0])
        assertEquals(Output.File("ou_b", "{prev}", "$hyper/log.md"), a.outputs[1])
        assertTrue("an output kind it doesn't know is kept", a.outputs[2] is Output.Unknown)
        assertEquals(Budget(30, null), a.budget)
        assertEquals("no_gh", a.problem)
        assertEquals(AutoMode.ALLOWED, a.mode)
        val r = a.lastRun!!
        assertEquals("PR #12 opened by alice", r.firedDetail)
        assertEquals(1, r.step)
        assertEquals(RunAsk("st_b", "Post it? Looks good.", listOf("Approve", "Skip", "Later"), "Later", 605_000L), r.ask)
        assertEquals(listOf(Run.DONE, Run.NEEDS_YOU, RunStep.PENDING, RunStep.PENDING), r.steps.map { it.state })
        assertEquals("Looks good.", r.steps[0].result)
        assertEquals(BudgetUsed(1.5, null), r.budgetUsed)
        val st = AutomationsState(false, listOf(a))
        assertEquals(listOf("ru_1"), st.asks.map { it.id })
    }

    @Test fun theSaveOfAWorkflow() {
        val a = AutomationWire.automation(workflow)!!
        val typed = a.input().copy(outputs = listOf(Output.Webhook("ou_a", "Done: {prev}", Output.SLACK, "hooks.slack.com", "Xq7Z", url = "https://hooks.slack.com/services/T/B/new")), confirmShell = true)
        val body = AutomationWire.json(typed)
        assertFalse("the new shape sends no action", body.has("action"))
        val steps = body.getJSONArray("steps")
        assertEquals(4, steps.length())
        val first = steps.getJSONObject(0)
        assertFalse("a step has no then", first.has("then"))
        assertTrue(first.isNull("if"))
        assertEquals("answer", steps.getJSONObject(2).getJSONObject("if").getString("on"))
        val out = body.getJSONArray("outputs").getJSONObject(0)
        assertEquals("https://hooks.slack.com/services/T/B/new", out.getString("url"))
        assertFalse("its host and last4 are the Mac's", out.has("last4"))
        assertEquals(30, body.getJSONObject("budget").getInt("minutes"))
        assertTrue(body.getBoolean("confirmShell"))
        // Saved again without a new secret: none is sent (the Mac keeps the one it has).
        val kept = AutomationWire.json(a.input()).getJSONArray("outputs").getJSONObject(0)
        assertFalse(kept.has("url"))
        assertEquals("ou_a", kept.getString("id"))
        // A webhook trigger saves as its kind alone: the Mac keeps its token.
        val hook = AutomationWire.json(a.input().copy(trigger = Trigger.Webhook("9f3c", 1L, "http://127.0.0.1:47322/x", "hypernova automation fire au_rv")))
        assertEquals("""{"kind":"webhook"}""", hook.getJSONObject("trigger").toString())
        // No budget: null.
        assertTrue(AutomationWire.json(a.input().copy(budget = null)).isNull("budget"))
    }

    @Test fun theDraftKeepsItsViewButNeverASecret() {
        val a = AutomationWire.automation(workflow)!!
        val input = a.input().copy(
            trigger = Trigger.Webhook("9f3c", 7L, "http://127.0.0.1:47322/local/automations/au_rv/fire", "hypernova automation fire au_rv"),
            outputs = listOf(Output.Webhook("ou_a", null, Output.TELEGRAM, "api.telegram.org", "AAAA", botToken = "123456:secret", chatId = "@me")),
        )
        val draft = AutomationWire.draft(input).toString()
        assertFalse("a typed secret never goes into the saved draft", draft.contains("123456:secret"))
        val back = AutomationWire.input(JSONObject(draft))
        assertEquals(input.trigger, back.trigger)
        assertEquals(Output.Webhook("ou_a", null, Output.TELEGRAM, "api.telegram.org", "AAAA"), back.outputs.single())
        assertEquals(input.steps, back.steps)
        assertEquals(input.budget, back.budget)
    }

    @Test fun aSessionTriggersWait() {
        val t = Trigger.SessionEvent(Trigger.NEEDS_YOU, null, Trigger.MINE, after = 10)
        assertEquals(t, AutomationWire.trigger(AutomationWire.json(t)))
        assertEquals(10, AutomationWire.json(t).getInt("after"))
        // Taken off: it goes as null, so the Mac doesn't keep the one it had; a finish never waits.
        assertTrue(AutomationWire.json(t.copy(after = null)).isNull("after"))
        assertTrue(AutomationWire.json(t.copy(event = Trigger.FINISHED)).isNull("after"))
        // From an older daemon: no wait.
        assertNull((AutomationWire.trigger(JSONObject("""{"kind":"session","event":"needs_you","projectPath":null,"from":"mine"}""")) as Trigger.SessionEvent).after)
        assertTrue(Features.T_AFTER in Features.ALL && Features.STEP_OUTPUT in Features.ALL)
    }

    @Test fun anEarlierStepsResultByItsId() {
        assertEquals("{step:st_a}", WorkflowShape.stepRef("st_a"))
        assertEquals(listOf("st_a", "st-b"), WorkflowShape.stepRefs("{step:st_a} and {step:st-b} then {step:st_a} {step:bad id}"))
        val steps = listOf(Step("a", null, Action.Notify("x")), Step("b", null, Action.Ask("Go?")), Step("c", null, Action.Shell("/p", "x")))
        assertEquals(listOf("b"), WorkflowShape.resultSteps(steps, 2).map { it.id })
        assertEquals(listOf("b", "c"), WorkflowShape.resultSteps(steps, 9).map { it.id })
    }

    @Test fun theNewTriggersRoundTrip() {
        val triggers = listOf(
            Trigger.Cron("*/15 9-18 * * 1-5"),
            Trigger.FileWatch(hyper, "docs/**", Trigger.CREATED),
            Trigger.FileWatch(hyper, null, Trigger.DELETED),
            Trigger.Github(hyper, listOf(Trigger.REVIEW_COMMENT), 5),
            Trigger.Hook(Trigger.H_TOOL_FAILURE, "Bash", hyper, Trigger.ANY),
            Trigger.Hook(Trigger.H_PRE_COMPACT, null, null, Trigger.MINE),
        )
        for (t in triggers) assertEquals(t, AutomationWire.trigger(AutomationWire.json(t)))
        val w = AutomationWire.trigger(JSONObject("""{"kind":"webhook","last4":"9f3c","createdAt":5,"url":"http://127.0.0.1:47322/local/automations/au_1/fire","command":"hn fire"}"""))
        assertEquals(Trigger.Webhook("9f3c", 5L, "http://127.0.0.1:47322/local/automations/au_1/fire", "hn fire"), w)
        assertTrue(Trigger.Cron("0 9 * * *").timed)
        assertTrue(AutomationWire.trigger(JSONObject("""{"kind":"lunar","phase":"full"}""")) is Trigger.Unknown)
    }

    @Test fun answersFlagsAndNotes() {
        val note = AutomationWire.note(JSONObject("""{"id":"n1","automationId":"au_rv","runId":"ru_1","name":"Review","text":"Post it?","to":"phone","at":5,
            "ask":{"stepId":"st_b","question":"Post it?","answers":["Approve","Skip"],"default":"Skip","until":9,"runId":"ru_1"}}"""))!!
        assertEquals(RunAsk("st_b", "Post it?", listOf("Approve", "Skip"), "Skip", 9L), note.ask)
        assertNull(AutomationWire.note(JSONObject("""{"id":"n2","name":"x","text":"y","to":"phone","at":1}"""))!!.ask)
        val answered = WorkflowWire.runStep(JSONObject("""{"id":"st_b","kind":"ask","state":"done","answer":{"text":"Skip","by":"timeout","at":7},"reused":true}"""))!!
        assertEquals(StepAnswer("Skip", StepAnswer.TIMEOUT, 7L), answered.answer)
        assertTrue(answered.reused)
        // A run kept from before steps: none.
        assertEquals(emptyList<RunStep>(), AutomationWire.run(JSONObject("""{"id":"ru_9","state":"done"}"""))!!.steps)
    }

    @Test fun whatSavingAsksFor() {
        val shell = AutomationInput("x", true, false, Trigger.Webhook(), start(), null, steps = listOf(Step("st_0", null, start()), Step("st_1", null, Action.Shell(hyper, "npm test"))))
        assertTrue(shell.shell)
        assertFalse(shell.bypass)
        val bypass = shell.copy(steps = listOf(Step("st_0", null, start(mode = AutoMode.NO_ASKING))))
        assertTrue(bypass.bypass)
        assertFalse(bypass.shell)
        // Every step's provider counts toward "costs money"; a continue aimed at an earlier step's session doesn't name one.
        val paid = shell.copy(steps = listOf(Step("st_0", null, start()), Step("st_1", null, start().copy(providerId = "openrouter"))))
        assertEquals("openrouter", paid.paidProvider("anthropic"))
        val stepTarget = shell.copy(steps = listOf(Step("st_0", null, start()), Step("st_1", null, Action.Continue("step:st_0", "go", Then.NONE))))
        assertNull(stepTarget.paidProvider("anthropic", providerOf = { "openrouter" }))
    }

    @Test fun newIdsAreTheContractsShape() {
        var n = 0L
        val ids = listOf(1L, 1L, 0xABCDEF12L).iterator()
        val id = WorkflowShape.newId("st_", listOf("st_00000001"), random = { ids.next().also { n++ } })
        assertEquals("st_abcdef12", id)
        assertEquals("a taken id is drawn again", 3L, n)
        assertTrue(WorkflowShape.validId(id))
        assertFalse(WorkflowShape.validId("has space"))
    }

    @Test fun aDraftInTheNewShape() {
        val old = AutomationInput("x", true, false, Trigger.Time("09:00", Trigger.WEEKDAYS), start(then = Then.SAY), null)
        val f = old.flow()
        assertEquals(1, f.steps!!.size)
        assertEquals(listOf(Output.Say("ou_0", null)), f.outputs)
        assertEquals(f, f.flow())
        assertEquals(f.steps, old.flowSteps)
    }
}
