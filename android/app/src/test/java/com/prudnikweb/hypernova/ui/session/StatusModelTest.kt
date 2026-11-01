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
        // As Claude Code's footer has it: "main + 2 agents", the main agent's own words for its row.
        val both = statusBar(working("Task: review", agents = 2))!!
        assertEquals(Say(SayKind.MainAndAgents, n = 2), both.say)
        assertEquals(Say(SayKind.MainWaiting), both.main)
        assertTrue(both.hasAgents)
        assertEquals(2, both.agents)
        val alone = statusBar(BarInput(Phase.Idle, SessionState.Idle, "", agentsRunning = 2))!!
        assertEquals(Say(SayKind.MainAndAgents, n = 2), alone.say)
        assertEquals(Say(SayKind.MainWaiting), alone.main)
        // Nothing of the main agent to stop.
        assertFalse(alone.canStop)
    }

    @Test fun anAgentsWaitingLineIsSaidAsItIs() {
        // No colon, so it never reads as a tool's name: an older app shows the daemon's words too.
        for (line in listOf("Waiting for its background command", "Waiting for its 2 background commands", "Waiting for the agents it started", "Waiting for its background work")) {
            assertEquals(Say(SayKind.Raw, line), agentSay(line))
        }
    }

    private fun plain(id: String, type: String = "general-purpose", label: String = "Add routing helpers", activity: String? = "Edit: android/Servers.kt", status: String = "running", tokens: Long? = 81_200, workflowId: String? = null, startedAt: Long = 0) = WAgent(
        id = id, sessionId = "s", toolUseId = null, parentAgentId = null, workflowId = workflowId, phase = null, label = label,
        type = type, model = null, background = true, status = status, activity = activity,
        startedAt = startedAt, endedAt = null, lastActivityAt = 0, tokens = tokens, toolUses = null, result = null,
    )

    @Test fun theMainAgentWorkingWithAgentsKeepsItsOwnWordsForItsRow() {
        val m = statusBar(working("Edit: src/App.kt", agents = 3))!!
        assertEquals(Say(SayKind.MainAndAgents, n = 3), m.say)
        assertEquals(Say(SayKind.Editing, "App.kt"), m.main)
        assertTrue(m.canStop)
        // No agents: the line is the main agent's, as it was.
        val solo = statusBar(working("Edit: src/App.kt"))!!
        assertEquals(Say(SayKind.Editing, "App.kt"), solo.say)
        assertFalse(solo.hasAgents)
    }

    @Test fun theToDosAreNeverCountedAsAgents() {
        val todos = (1..10).map { todo(it, if (it <= 7) TodoStatus.Done else TodoStatus.Pending) }
        val m = statusBar(working("Thinking", todos, agents = 3))!!
        assertEquals(3, m.agents)
        assertEquals(Say(SayKind.MainAndAgents, n = 3), m.say)
        assertTrue(m.hasTodos)
        assertEquals(7, m.done)
        assertEquals(10, m.total)
    }

    @Test fun theBoardsAgentsCountWhenItKnowsMoreThanTheSession() {
        val agents = barAgents(AgentBoard(listOf(plain("a1"), plain("a2"), plain("a3")).associateBy { it.id }, emptyMap()))
        val m = statusBar(BarInput(Phase.Idle, SessionState.Idle, "", agentsRunning = 1, agents = agents))!!
        assertEquals(Say(SayKind.MainAndAgents, n = 3), m.say)
    }

    @Test fun aRunningWorkflowWinsOverPlainAgents() {
        val agents = barAgents(AgentBoard(mapOf("a1" to plain("a1")), emptyMap()))
        val m = statusBar(working("Thinking", agents = 4).copy(workflow = workflow(1, 3), agents = agents))!!
        assertEquals(Say(SayKind.Workflow, "review-changes"), m.say)
        assertFalse(m.hasAgents)
        assertEquals(0, m.agents)
    }

    @Test fun barAgentsListTheAgentsAtWorkOutsideARunningWorkflowAsClaudeCodesFooterDoes() {
        val board = AgentBoard(
            listOf(
                plain("a2", startedAt = 20),
                plain("a1", type = "Explore", label = "Explore", activity = "Read: src/linux-cli.test.ts", startedAt = 10),
                plain("a3", status = "done"),
                plain("a4", type = "", label = "Check the docs", activity = null, tokens = null, startedAt = 30),
                wfAgent("w1", "running"),
            ).associateBy { it.id },
            mapOf("wf_1" to wwf(agentCount = 1, done = 0, failed = 0)),
        )
        val list = barAgents(board, asking = setOf("a2"))
        assertEquals(listOf("a1", "a2", "a4"), list.map { it.id })
        assertEquals(BarAgent("a1", "Explore", null, Say(SayKind.Reading, "linux-cli.test.ts"), 10, 81_200), list[0])
        assertEquals("general-purpose", list[1].kind)
        assertEquals("Add routing helpers", list[1].task)
        assertEquals(Say(SayKind.Editing, "Servers.kt"), list[1].doing)
        assertTrue(list[1].asking)
        // No kind: what it's for stands in, once.
        assertEquals("Check the docs", list[2].kind)
        assertNull(list[2].task)
        assertNull(list[2].doing)
        assertNull(list[2].tokens)
    }

    @Test fun anAgentsActivityReadsInWordsOrAsTheMacSaidIt() {
        assertEquals(Say(SayKind.Running, "./gradlew build"), agentSay("Bash: ./gradlew build"))
        assertEquals(Say(SayKind.Running, "npm test"), agentSay("$ npm test"))
        assertEquals(Say(SayKind.Searching, "agentsRunning"), agentSay("Grep: agentsRunning"))
        assertEquals(Say(SayKind.Raw, "Checking live daemon appInfo state"), agentSay("Checking live daemon appInfo state"))
        assertEquals(Say(SayKind.Raw, "Agent"), agentSay("Agent: review the parser"))
    }

    @Test fun askingAgentsComeFromTheChatsCards() {
        fun view(id: String, asking: Boolean) = com.prudnikweb.hypernova.data.AgentView(id, id, "Explore", AgentState.Running, null, 0, null, null, null, null, asking = asking)
        val items = listOf(Item.User("u", "go"), Item.AgentCard("c1", view("a1", false), listOf(view("a2", true))), Item.AgentCard("c2", view("a3", true)))
        assertEquals(setOf("a2", "a3"), askingAgents(items))
    }

    @Test fun aWorkflowsTokensAreTheSumOverItsAgentsOrTheMacsTotalIfMore() {
        fun view(id: String, tokens: Long?) = com.prudnikweb.hypernova.data.AgentView(id, id, "workflow-subagent", AgentState.Running, null, 0, null, tokens, null, null)
        fun wf(tokens: Long?, vararg agents: com.prudnikweb.hypernova.data.AgentView) = WorkflowView(
            "wf_1", "arc-responsive-ui", null, AgentState.Running, 0, null, tokens, agentCount = agents.size, running = agents.size,
            phases = listOf(com.prudnikweb.hypernova.data.PhaseView("Build", null, agents.take(1), 0, com.prudnikweb.hypernova.data.PhaseState.Live)),
            finished = 0, loose = agents.drop(1),
        )
        assertEquals(181_100L, workflowTokens(wf(100_000, view("a", 120_000), view("b", 61_100))))
        assertEquals(500_000L, workflowTokens(wf(500_000, view("a", 120_000))))
        assertEquals(42L, workflowTokens(wf(null, view("a", 42), view("b", null))))
        assertNull(workflowTokens(wf(null, view("a", null))))
    }

    @Test fun timesReadAsClaudeCodesFooterWritesThem() {
        assertEquals("0s", ccDuration(-3))
        assertEquals("42s", ccDuration(42_900))
        assertEquals("4m12s", ccDuration(252_000))
        assertEquals("19m08s", ccDuration(1_148_000))
        assertEquals("1h02m", ccDuration(3_725_000))
        assertEquals("2d03h", ccDuration((51 * 3600 + 59 * 60) * 1000L))
    }

    @Test fun tokensReadAsClaudeCodesFooterWritesThem() {
        assertEquals("980", ccTokens(980))
        assertEquals("1.0k", ccTokens(1_000))
        assertEquals("8.4k", ccTokens(8_400))
        assertEquals("81.0k", ccTokens(81_000))
        assertEquals("81.2k", ccTokens(81_249))
        assertEquals("181.1k", ccTokens(181_100))
        assertEquals("1.0m", ccTokens(999_950))
        assertEquals("1.2m", ccTokens(1_234_567))
    }

    @Test fun tokensAreSpokenInWholeWords() {
        assertEquals(SpokenTokens(TokenScale.Units, "980", 980), spokenTokens(980))
        assertEquals(SpokenTokens(TokenScale.Thousands, "81", 81), spokenTokens(81_200))
        assertEquals(SpokenTokens(TokenScale.Thousands, "8", 8), spokenTokens(8_400))
        assertEquals(SpokenTokens(TokenScale.Millions, "1.2", 1), spokenTokens(1_234_567))
        assertEquals(SpokenTokens(TokenScale.Millions, "2", 2), spokenTokens(1_999_999))
    }

    @Test fun waitingAloneHasNoBarAgentsOrOpenTodosKeepIt() {
        // Only waiting on the owner: no bar (owner, 2026-10-30); the planet and the ask say it.
        assertNull(statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "x")))
        val a = Item.Approval("k", "Bash", "git push origin main", "", kind = ApprovalKind.Tool)
        assertNull(statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "x", pending = a)))
        assertNull(statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "x", todos = listOf(todo(1, TodoStatus.Done)))))
        assertEquals(BarTone.NeedsYou, statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "x", agentsRunning = 1))?.tone)
        assertEquals(BarTone.NeedsYou, statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "x", todos = listOf(todo(1, TodoStatus.Pending))))?.tone)
    }

    // The wording when the bar shows while it waits (agents beside it keep the bar).
    @Test fun anApprovalMakesItAmberAndNamesWhatToApprove() {
        val a = Item.Approval("k", "Bash", "git push origin main", "", kind = ApprovalKind.Tool)
        val m = statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "x", agentsRunning = 1, pending = a))!!
        assertEquals(BarTone.NeedsYou, m.tone)
        assertEquals(Say(SayKind.WaitApprove, "git push origin main"), m.say)
        assertFalse(m.canStop)
        val e = Item.Approval("k", "Edit", "/a/b/Model.kt", "")
        assertEquals(Say(SayKind.WaitApprove, "Edit Model.kt"), statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "", agentsRunning = 1, pending = e))!!.say)
        val q = Item.Approval("k", "AskUserQuestion", "", "", kind = ApprovalKind.Question)
        assertEquals(SayKind.WaitAnswer, statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "", agentsRunning = 1, pending = q))!!.say.kind)
    }

    @Test fun needsYouWithoutTheItemUsesTheDaemonsWords() {
        val m = statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "wants to run git push origin main", agentsRunning = 1))!!
        assertEquals(Say(SayKind.WaitApprove, "run git push origin main"), m.say)
        assertEquals(SayKind.WaitYou, statusBar(BarInput(Phase.NeedsYou, SessionState.NeedsYou, "", agentsRunning = 1))!!.say.kind)
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

    // ─── How much room the opened bar may take ───

    /** A phone's numbers in dp: the bar's 40 of fade, the folded bar's 71, a row's 52. */
    private fun panelMax(remaining: Int, header: Int) = statusPanelMaxPx(remaining, header, columnTopPx = 40, foldedPx = 71, oneRowPx = 52)

    @Test fun thePanelTakesHalfOfTheRoomBetweenTheHeaderAndTheBox() {
        // 364 left under the bar's 40 of fade, a 100 header: half of the 304 between them.
        assertEquals(152, panelMax(remaining = 364, header = 100))
        // A grown box or the keyboard leaves less, and the panel's cap comes down with it.
        assertEquals(140, panelMax(remaining = 340, header = 100))
    }

    @Test fun aSmallRoomStillShowsTheFoldedBarAndARow() {
        // Half of 180 is 90, but the bar and a row fit.
        assertEquals(123, panelMax(remaining = 240, header = 100))
        // Only 90 clear of the header: all of it, no more, its fade off the header.
        assertEquals(90, panelMax(remaining = 190, header = 100))
    }

    @Test fun noRoomUnderTheHeaderLeavesTheFoldedBarOverItOrNothing() {
        // Landscape with the keyboard: Stop over the header's words, never the box's room.
        assertEquals(71, panelMax(remaining = 86, header = 100))
        assertEquals(71, panelMax(remaining = 71, header = 100))
        // Not even that: the box wins outright.
        assertEquals(0, panelMax(remaining = 70, header = 100))
        assertEquals(0, panelMax(remaining = 0, header = 0))
    }

    @Test fun beforeTheHeaderIsMeasuredItCountsForNothing() {
        assertEquals(220, panelMax(remaining = 400, header = 0))
    }

    @Test fun thePanelNeverAsksForMoreThanIsLeft() {
        for (remaining in 0..1200) for (header in listOf(0, 60, 100, 140)) {
            assertTrue("$remaining under a $header header", panelMax(remaining, header) <= remaining)
        }
    }
}
