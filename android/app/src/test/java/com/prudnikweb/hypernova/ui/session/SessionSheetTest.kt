package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.AgentBoard
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TermLink
import com.prudnikweb.hypernova.data.WAgent
import com.prudnikweb.hypernova.data.WPhase
import com.prudnikweb.hypernova.data.WWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the session sheet shows (ui/session/SessionSheetModel.kt) and the model id it names. */
class SessionSheetTest {
    private fun agent(id: String, status: String = "running", at: Long = 0L, workflow: String? = null, parent: String? = null) =
        WAgent(id, "s", "call-$id", parent, workflow, null, "label-$id", "Explore", null, false, status, null, at, null, at, null, null, null)

    private fun workflow(id: String, status: String = "running", count: Int = 0, done: Int = 0, at: Long = 0L) =
        WWorkflow(id, "s", "call-$id", "wf-$id", null, status, listOf(WPhase("survey", null)), count, done, 0, at, null, null, null)

    private fun board(agents: List<WAgent>, workflows: List<WWorkflow> = emptyList()) =
        AgentBoard(agents.associateBy { it.id }, workflows.associateBy { it.id })

    private fun session(phase: Phase = Phase.Working, agents: Int = 0, origin: String? = "hypernova", term: TermLink? = null, agentJob: String? = null) =
        Session("00", "s", "s", "p", SessionState.Working, "claude-opus-5-5", "default", "", 0L, phase = phase, origin = origin, term = term, agentsRunning = agents, agentJob = agentJob)

    @Test fun aClaudeAgentsJobsModeChangesWithoutATerminalUntilItEnds() {
        assertTrue(jobModeChangeable(session(origin = "terminal", agentJob = "feedbeef")))
        assertFalse(jobModeChangeable(session(phase = Phase.Ended, origin = "terminal", agentJob = "feedbeef")))
        // A plain terminal session the Mac only watches: still not from here.
        assertFalse(jobModeChangeable(session(origin = "terminal")))
    }

    @Test fun onlyAgentsAtWorkAreListedOldestFirst() {
        val a = sheetAgents(board(listOf(agent("b", at = 20), agent("a", at = 10), agent("done", status = "done"), agent("err", status = "error"))))
        assertEquals(listOf("a", "b"), a.loose.map { it.id })
        assertEquals(2, a.running)
        assertTrue(a.workflows.isEmpty())
    }

    @Test fun anAgentsOwnAgentsAreListedToo() {
        val a = sheetAgents(board(listOf(agent("a", at = 1), agent("child", at = 2, parent = "a"))))
        assertEquals(listOf("a", "child"), a.loose.map { it.id })
    }

    @Test fun aRunningWorkflowCountsItsAgentsDoneOfStarted() {
        val b = board(
            listOf(agent("w1", workflow = "w"), agent("w2", status = "done", workflow = "w"), agent("w3", status = "done", workflow = "w"), agent("x")),
            listOf(workflow("w", count = 7, done = 1)),
        )
        val a = sheetAgents(b)
        val w = a.workflows.single()
        assertEquals("wf-w", w.name)
        // The phone has two done, more than the daemon's one; the daemon has started seven, more than the phone's three.
        assertEquals(2, w.done)
        assertEquals(7, w.started)
        assertEquals(listOf("w1"), w.agents.map { it.id })
        // Its agents aren't listed again outside it.
        assertEquals(listOf("x"), a.loose.map { it.id })
        assertEquals(2, a.running)
    }

    @Test fun theStartedCountGrowsWithTheBoard() {
        val b = board(listOf(agent("w1", workflow = "w"), agent("w2", workflow = "w")), listOf(workflow("w", count = 1, done = 0)))
        assertEquals(2, sheetAgents(b).workflows.single().started)
    }

    @Test fun aWorkflowBetweenPhasesStaysWithNoAgentsAtWork() {
        val b = board(listOf(agent("w1", status = "done", workflow = "w")), listOf(workflow("w", count = 1, done = 1)))
        val w = sheetAgents(b).workflows.single()
        assertTrue(w.agents.isEmpty())
        assertEquals(1, w.done)
    }

    @Test fun aFinishedWorkflowIsNotListedNorAreItsAgentsLoose() {
        val b = board(listOf(agent("w1", status = "done", workflow = "w")), listOf(workflow("w", status = "done", count = 1, done = 1)))
        assertTrue(sheetAgents(b).isEmpty)
        // An agent still running in a finished workflow (an older daemon's late word) shows on its own.
        val late = board(listOf(agent("w1", workflow = "w")), listOf(workflow("w", status = "done")))
        assertEquals(listOf("w1"), sheetAgents(late).loose.map { it.id })
    }

    @Test fun workflowsAreInTheOrderTheyStarted() {
        val b = board(emptyList(), listOf(workflow("late", at = 20), workflow("early", at = 10)))
        assertEquals(listOf("early", "late"), sheetAgents(b).workflows.map { it.id })
    }

    @Test fun theCountIsTheSessionsOrTheBoardsWhicheverKnowsMore() {
        val two = sheetAgents(board(listOf(agent("a"), agent("b"))))
        assertEquals(3, agentsAtWork(session(agents = 3), two))
        assertEquals(2, agentsAtWork(session(agents = 1), two))
        assertEquals(0, agentsAtWork(session(agents = 0), sheetAgents(AgentBoard.Empty)))
        assertEquals(0, agentsAtWork(session(phase = Phase.Ended, agents = 3), two))
    }

    @Test fun whereItRuns() {
        assertEquals(RunsIn.Terminal, runsIn(session(term = TermLink("t", 0, 1))))
        assertEquals(RunsIn.Terminal, runsIn(session(origin = "terminal", term = TermLink("t", 1, 0))))
        assertEquals(RunsIn.Mac, runsIn(session(origin = "hypernova")))
        assertEquals(RunsIn.Watched, runsIn(session(origin = "terminal")))
    }

    @Test fun theModelIdFollowsASwitchBeforeTheUsageDoes() {
        val u = SessionUsage("claude-sonnet-4-5", "Sonnet 4.5", null, null, null, false, 0, 0, 0, 0, 0, null, false)
        // Before the Mac has answered, and while the usage is still about the model before /model.
        assertEquals("claude-opus-5-5", modelId(null, "claude-opus-5-5", "Default"))
        assertEquals("claude-opus-5-5", modelId(u, "claude-opus-5-5", "Default"))
        // A session nobody has named a model for yet: the usage's.
        assertEquals("claude-sonnet-4-5", modelId(u, "Default", "Default"))
    }
}
