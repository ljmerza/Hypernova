package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.AgentBoard
import com.prudnikweb.hypernova.data.AgentState
import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.WAgent
import com.prudnikweb.hypernova.data.WPhase
import com.prudnikweb.hypernova.data.WWorkflow
import com.prudnikweb.hypernova.data.WorkflowView
import com.prudnikweb.hypernova.data.workflowView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the status bar says and how far it fills (ui/session/StatusModel.kt). */
class StatusModelTest {
    private fun working(activity: String, todos: List<Todo> = emptyList(), reads: Int = 0, agents: Int = 0) =
        BarInput(Phase.Working, SessionState.Working, activity, agentsRunning = agents, todos = todos, trailingReads = reads)

    private fun todo(n: Int, s: TodoStatus) = Todo("$n", "t$n", null, s)

    private fun workflow(finished: Int, started: Int, state: AgentState = AgentState.Running) = WorkflowView(
        "wf_1", "review-changes", null, state, 0, null, null, agentCount = started, running = started - finished, phases = emptyList(), finished = finished,
    )

    private fun wfAgent(id: String, status: String) = WAgent(
        id = id, sessionId = "s", toolUseId = null, parentAgentId = null, workflowId = "wf_1", phase = "Review", label = "review:$id",
        type = "workflow-subagent", model = null, background = true, status = status, activity = null,
        startedAt = 0, endedAt = null, lastActivityAt = 0, tokens = null, toolUses = null, result = null,
    )

    private fun wwf(agentCount: Int, done: Int, failed: Int) = WWorkflow(
        id = "wf_1", sessionId = "s", toolUseId = null, name = "review-changes", summary = null, status = "running",
        phases = listOf(WPhase("Review", null)), agentCount = agentCount, done = done, failed = failed, startedAt = 0, endedAt = null, tokens = null, toolUses = null,
    )

    private fun viewOf(w: WWorkflow, agents: List<WAgent>) = AgentBoard(agents.associateBy { it.id }, mapOf(w.id to w)).workflowView(w, emptySet())

    @Test fun aRunningWorkflowShowsItsNameAndItsAgentsDoneOfStarted() {
        // The main agent is idle while the workflow's agents work.
        val alone = statusBar(BarInput(Phase.Idle, SessionState.Idle, "", agentsRunning = 6, workflow = workflow(1, 7)))!!
        assertEquals(Say(SayKind.Workflow, "review-changes"), alone.say)
        assertEquals(BarTone.Working, alone.tone)
        assertEquals("review-changes", alone.workflow)
        assertTrue(alone.hasWorkflow)
        assertFalse(alone.hasTodos)
        assertEquals(1, alone.done)
        assertEquals(7, alone.total)
        assertEquals(1f / 7, alone.progress!!, 0.0001f)
        assertFalse(alone.canStop)
        // The main agent works too: the workflow still wins the line, and Stop stays for the main agent.
        val both = statusBar(working("Bash: npm test", agents = 6).copy(workflow = workflow(1, 7)))!!
        assertEquals(Say(SayKind.Workflow, "review-changes"), both.say)
        assertTrue(both.canStop)
        // Between its phases none of its agents runs, and the session is still busy with it.
        assertEquals(SayKind.Workflow, statusBar(BarInput(Phase.Idle, SessionState.Idle, "", workflow = workflow(3, 3)))!!.say.kind)
    }

    @Test fun aWorkflowJustStartedHasNoTallyYet() {
        val m = statusBar(BarInput(Phase.Idle, SessionState.Idle, "", agentsRunning = 1, workflow = workflow(0, 0)))!!
        assertTrue(m.hasWorkflow)
        assertFalse(m.counted)
        assertNull(m.progress)
    }

    @Test fun noWorkflowLeavesTheBarAsItWas() {
        val todos = listOf(todo(1, TodoStatus.Done), todo(2, TodoStatus.Active))
        val m = statusBar(working("Edit: src/App.kt", todos))!!
        assertEquals(Say(SayKind.Editing, "App.kt"), m.say)
        assertNull(m.workflow)
        assertFalse(m.hasWorkflow)
        assertTrue(m.hasTodos)
        assertEquals(1, m.done)
        assertEquals(2, m.total)
        assertEquals(m, statusBar(working("Edit: src/App.kt", todos).copy(workflow = null)))
    }

    @Test fun aWorkflowWinsOverTheTodos() {
        val todos = listOf(todo(1, TodoStatus.Done), todo(2, TodoStatus.Done), todo(3, TodoStatus.Active))
        val m = statusBar(working("Thinking", todos, agents = 4).copy(workflow = workflow(2, 6)))!!
        assertEquals(SayKind.Workflow, m.say.kind)
        assertFalse(m.hasTodos)
        assertEquals(2, m.done)
        assertEquals(6, m.total)
        // Waiting for you still says what it waits on, amber; the tally stays the workflow's.
        val a = Item.Approval("k", "Bash", "npm test", "", kind = ApprovalKind.Tool)
        val asking = statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "", agentsRunning = 4, pending = a, todos = todos, workflow = workflow(2, 6)))!!
        assertEquals(BarTone.NeedsYou, asking.tone)
        assertEquals(Say(SayKind.WaitApprove, "npm test"), asking.say)
        assertEquals("review-changes", asking.workflow)
        assertEquals(6, asking.total)
    }

    @Test fun aFinishedWorkflowGivesTheBarBack() {
        for (end in listOf(AgentState.Done, AgentState.Failed, AgentState.Stopped)) {
            val todos = listOf(todo(1, TodoStatus.Done), todo(2, TodoStatus.Pending))
            val m = statusBar(working("Thinking", todos).copy(workflow = workflow(7, 7, end)))!!
            assertEquals(SayKind.Thinking, m.say.kind)
            assertNull(m.workflow)
            assertEquals(1, m.done)
            assertEquals(2, m.total)
            // Nothing left running and the main agent idle: no bar.
            assertNull(statusBar(BarInput(Phase.Idle, SessionState.Idle, "", workflow = workflow(7, 7, end))))
        }
    }

    @Test fun theRunningWorkflowIsTheNewestCardThatStillRuns() {
        val old = Item.WorkflowCard("c1", workflow(7, 7, AgentState.Done).copy(id = "wf_old"))
        val live = Item.WorkflowCard("c2", workflow(1, 3).copy(id = "wf_live"))
        val later = Item.WorkflowCard("c3", workflow(2, 2, AgentState.Stopped).copy(id = "wf_later"))
        assertEquals("wf_live", runningWorkflow(listOf(Item.User("u", "go"), old, live, Item.Tool("t", "Read", "a"), later))?.id)
        assertNull(runningWorkflow(listOf(old, later)))
        assertNull(runningWorkflow(emptyList()))
    }

    @Test fun aWorkflowCountsItsAgentsFinishedHoweverTheyEndedAndNotARetrysEarlierAttempt() {
        // The retried step's first attempt is off the board (its `superseded` message), so only these are left.
        val agents = listOf(wfAgent("a1", "done"), wfAgent("a2", "error"), wfAgent("a3", "stopped"), wfAgent("a4", "running"))
        // The daemon counts the done and the failed, not the stopped: the board counts those itself.
        val v = viewOf(wwf(agentCount = 4, done = 1, failed = 1), agents)
        assertEquals(3, v.finished)
        assertEquals(4, v.agentCount)
        // The daemon's totals can be ahead of the agents this phone has (older ones only counted there).
        val w = viewOf(wwf(agentCount = 9, done = 6, failed = 1), agents)
        assertEquals(7, w.finished)
        assertEquals(9, w.agentCount)
    }

    @Test fun aWorkflowWithAFailedAgentStillReachesAllFinished() {
        // Between phases (or before its summary is written) the run is still running with every agent ended.
        val agents = listOf(wfAgent("a1", "done"), wfAgent("a2", "error"), wfAgent("a3", "done"), wfAgent("a4", "stopped"))
        val v = viewOf(wwf(agentCount = 4, done = 2, failed = 1), agents)
        val m = statusBar(BarInput(Phase.Idle, SessionState.Idle, "", agentsRunning = 1, workflow = v))!!
        assertEquals(SayKind.Workflow, m.say.kind)
        assertEquals(4, m.done)
        assertEquals(4, m.total)
        assertEquals(1f, m.progress!!, 0f)
    }

    @Test fun anIdleSessionHasNoBar() {
        assertNull(statusBar(BarInput(Phase.Idle, SessionState.Idle, "waiting for your next prompt")))
        assertNull(statusBar(BarInput(Phase.Ended, SessionState.Idle, "")))
    }

    @Test fun editingSaysTheFileNotThePath() {
        val m = statusBar(working("Edit: /Users/n/app/SessionScreen.kt"))!!
        assertEquals(Say(SayKind.Editing, "SessionScreen.kt"), m.say)
        assertEquals(BarTone.Working, m.tone)
        assertTrue(m.canStop)
    }

    @Test fun stopShowsOnlyWhereItCanReachTheSession() {
        assertTrue(statusBar(working("Bash: npm test"))!!.canStop)
        // A terminal session the Mac only watches: the Mac would answer 409.
        assertFalse(statusBar(working("Bash: npm test").copy(stoppable = false))!!.canStop)
    }

    @Test fun shellCommandsInBothSpellings() {
        assertEquals(Say(SayKind.Running, "npm test"), statusBar(working("Bash: npm test"))!!.say)
        assertEquals(Say(SayKind.Running, "npm test"), statusBar(working("$ npm test"))!!.say)
    }

    @Test fun severalReadsInARowAreCounted() {
        assertEquals(Say(SayKind.Reading, "a.kt"), statusBar(working("Read: src/a.kt", reads = 1))!!.say)
        assertEquals(Say(SayKind.ReadingMany, n = 3), statusBar(working("Read: src/a.kt", reads = 3))!!.say)
    }

    @Test fun thinkingWritingAndUnknownLines() {
        assertEquals(SayKind.Thinking, statusBar(working("Thinking"))!!.say.kind)
        assertEquals(SayKind.Writing, statusBar(working("Writing"))!!.say.kind)
        assertEquals(SayKind.Working, statusBar(working(""))!!.say.kind)
        assertEquals(SayKind.Working, statusBar(working("some odd sentence: with a colon"))!!.say.kind)
        assertEquals(Say(SayKind.Tool, "create issue"), statusBar(working("mcp__github__create_issue: x"))!!.say)
    }

    @Test fun agentsAreCountedWhileTheyWorkAndWhenTheyWorkAlone() {
        assertEquals(Say(SayKind.Agents, n = 2), statusBar(working("Task: review", agents = 2))!!.say)
        val alone = statusBar(BarInput(Phase.Idle, SessionState.Idle, "", agentsRunning = 2))!!
        assertEquals(Say(SayKind.Agents, n = 2), alone.say)
        // Nothing of the main agent to stop.
        assertFalse(alone.canStop)
    }

    @Test fun anApprovalMakesItAmberAndNamesWhatToApprove() {
        val a = Item.Approval("k", "Bash", "git push origin main", "", kind = ApprovalKind.Tool)
        val m = statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "x", pending = a))!!
        assertEquals(BarTone.NeedsYou, m.tone)
        assertEquals(Say(SayKind.WaitApprove, "git push origin main"), m.say)
        assertFalse(m.canStop)
        val e = Item.Approval("k", "Edit", "/a/b/Model.kt", "")
        assertEquals(Say(SayKind.WaitApprove, "Edit Model.kt"), statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "", pending = e))!!.say)
        val q = Item.Approval("k", "AskUserQuestion", "", "", kind = ApprovalKind.Question)
        assertEquals(SayKind.WaitAnswer, statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "", pending = q))!!.say.kind)
    }

    @Test fun needsYouWithoutTheItemUsesTheDaemonsWords() {
        val m = statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "wants to run git push origin main"))!!
        assertEquals(Say(SayKind.WaitApprove, "run git push origin main"), m.say)
        assertEquals(SayKind.WaitYou, statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, ""))!!.say.kind)
    }

    @Test fun anErrorIsRed() {
        val m = statusBar(BarInput(Phase.Error, SessionState.Idle, ""))!!
        assertEquals(BarTone.Error, m.tone)
        assertFalse(m.canStop)
    }

    @Test fun progressIsIndeterminateWithoutTodosAndTheShareDoneWith() {
        assertNull(statusBar(working("Thinking"))!!.progress)
        val todos = listOf(todo(1, TodoStatus.Done), todo(2, TodoStatus.Done), todo(3, TodoStatus.Active), todo(4, TodoStatus.Pending))
        val m = statusBar(working("Thinking", todos))!!
        assertEquals(2, m.done)
        assertEquals(4, m.total)
        assertEquals(0.5f, m.progress!!, 0.0001f)
        assertNull(progress(0, 0))
        assertEquals(1f, progress(9, 3)!!, 0f)
    }

    @Test fun readsAreCountedFromTheTailAndThinkingDoesNotBreakThem() {
        val items = listOf(
            Item.User("u", "go"),
            Item.Tool("1", "Read", "a"), Item.Thinking("t", 10), Item.Tool("2", "Read", "b"), Item.Tool("3", "Read", "c"),
        )
        assertEquals(3, trailingReads(items))
        assertEquals(0, trailingReads(items + Item.Tool("4", "Grep", "x")))
        assertEquals(0, trailingReads(emptyList()))
    }

    @Test fun theClockReadsMinutesAndSeconds() {
        assertEquals("0:00", clockText(-5))
        assertEquals("0:07", clockText(7_400))
        assertEquals("12:34", clockText(754_000))
        assertEquals("1:02:03", clockText(3_723_000))
    }

    @Test fun aRedBarFoldsAfterThirtySecondsUnlessTapped() {
        assertEquals(30_000L, ERROR_SHOW_MS)
        assertFalse(errorFolded(expired = false, kept = false))
        assertTrue(errorFolded(expired = true, kept = false))
        // A tap keeps it, even past the time; tapping again lets it go.
        assertFalse(errorFolded(expired = true, kept = true))
        assertFalse(errorFolded(expired = false, kept = true))
    }
}
