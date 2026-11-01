package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TodoNow
import com.prudnikweb.hypernova.data.Wire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A Working card says what it's doing now and for how long (docs/IDEAS.md, HM1 and HM7), and a
 * swipe does what the card's group offers (HM2).
 */
class CardNowTest {
    private fun session(
        state: SessionState = SessionState.Working, phase: Phase = Phase.of(state), activity: String = "Bash: npm test",
        todo: TodoNow? = null, job: String? = null, running: Boolean? = null,
    ) = Session("s1", "s1", "t", "alpha", state, "opus", "default", activity, 0L, phase = phase, todo = todo, agentJob = job, agentRunning = running)

    @Test fun theToDoUnderWayWithItsTally() {
        val line = WorkingLine.of(session(todo = TodoNow("Running the tests", 3, 7)))
        assertEquals(WorkingLine.Todo("Running the tests", 3, 7), line)
        assertEquals("3/7", (line as WorkingLine.Todo).tally)
        assertEquals("Running the tests 3/7", line.said)
    }

    @Test fun elseWhatItDoesNow() {
        // No list, a list with nothing under way, an empty one: the activity line.
        assertEquals(WorkingLine.Doing("Bash: npm test"), WorkingLine.of(session()))
        assertEquals(WorkingLine.Doing("Bash: npm test"), WorkingLine.of(session(todo = TodoNow(null, 7, 7))))
        assertEquals(WorkingLine.Doing("Bash: npm test"), WorkingLine.of(session(todo = TodoNow("  ", 1, 3))))
        assertEquals(WorkingLine.Doing("Bash: npm test"), WorkingLine.of(session(todo = TodoNow("Fix it", 0, 0))))
    }

    @Test fun elapsedInMinutesHoursAndDays() {
        val t = 1_000_000_000L
        val min = 60_000L
        // Unknown, ahead of this phone's clock, or under a minute: nothing.
        assertNull(Elapsed.of(0L, t))
        assertNull(Elapsed.of(t + min, t))
        assertNull(Elapsed.of(t, t + 59_999))
        assertEquals(Elapsed.Minutes(1), Elapsed.of(t, t + min))
        assertEquals(Elapsed.Minutes(12), Elapsed.of(t, t + 12 * min + 30_000))
        assertEquals(Elapsed.Minutes(59), Elapsed.of(t, t + 59 * min + 59_000))
        assertEquals(Elapsed.Hours(1, 0), Elapsed.of(t, t + 60 * min))
        assertEquals(Elapsed.Hours(1, 5), Elapsed.of(t, t + 65 * min))
        assertEquals(Elapsed.Hours(23, 59), Elapsed.of(t, t + (24 * 60 - 1) * min))
        assertEquals(Elapsed.Days(1, 0), Elapsed.of(t, t + 24 * 60 * min))
        assertEquals(Elapsed.Days(2, 3), Elapsed.of(t, t + (2 * 24 + 3) * 60 * min))
    }

    @Test fun elapsedTicksOnTheMinute() {
        val t = 1_000_000_000L
        assertEquals(60_000L, Elapsed.nextTick(t, t))
        assertEquals(15_000L, Elapsed.nextTick(t, t + 12 * 60_000 + 45_000))
        // A clock behind the Mac's: the first minute, not a negative wait.
        assertEquals(60_000L, Elapsed.nextTick(t, t - 5_000))
    }

    @Test fun whatASwipeOffers() {
        // Needs input: open, whatever it is.
        assertEquals(CardSwipe.Open, CardSwipe.of(HomeGroup.NeedsInput, session(SessionState.NeedsYou)))
        // Working: none.
        assertNull(CardSwipe.of(HomeGroup.Working, session(job = "j1", running = true)))
        // Completed: a job whose process still runs stops; one done or stopped is deleted (asked first).
        assertEquals(CardSwipe.Stop, CardSwipe.of(HomeGroup.Completed, session(SessionState.Idle, job = "j1", running = true)))
        assertEquals(CardSwipe.Delete, CardSwipe.of(HomeGroup.Completed, session(SessionState.Idle, job = "j1", running = false)))
        // An older Mac that doesn't say whether it runs: delete, as the menu offers no Stop then.
        assertEquals(CardSwipe.Delete, CardSwipe.of(HomeGroup.Completed, session(SessionState.Idle, job = "j1")))
        // Not a claude agents job, at rest: deleted (owner, 2026-11-01), asked first.
        assertEquals(CardSwipe.Delete, CardSwipe.of(HomeGroup.Completed, session(SessionState.Idle)))
        // Cut short by a restart of the Mac: ended, so no Stop; a job is still deleted.
        assertEquals(CardSwipe.Delete, CardSwipe.of(HomeGroup.Completed, session(SessionState.Idle, Phase.Ended, job = "j1", running = true)))
    }

    @Test fun readsTheToDoAndTheTurn() {
        fun wire(extra: String) = Wire.session(JSONObject("""{"id":"s1","title":"t","cwd":"/p/alpha","state":"working"$extra}"""))
        assertEquals(TodoNow("Running the tests", 3, 7), wire(""","todo":{"active":"Running the tests","done":3,"total":7}""").todo)
        assertEquals(TodoNow(null, 7, 7), wire(""","todo":{"active":null,"done":7,"total":7}""").todo)
        // None, an empty one, or an older Mac.
        assertNull(wire(""","todo":null""").todo)
        assertNull(wire(""","todo":{"active":"x","done":0,"total":0}""").todo)
        assertNull(wire("").todo)
        // The one turn clock, the sheet's This turn and the card's "for 12 min".
        assertEquals(1_700_000_000_000L, wire(""","turnStartedAt":1700000000000""").turnStartedAt)
        assertNull(wire(""","turnStartedAt":null""").turnStartedAt)
        assertNull(wire("").turnStartedAt)
    }
}
