package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.SessionState
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
