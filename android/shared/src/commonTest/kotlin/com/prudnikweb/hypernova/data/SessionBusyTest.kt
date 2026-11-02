package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One rule for "working" (Session.busy; owner's report: the sky, the seam and the card disagreed). */
class SessionBusyTest {
    private fun s(state: SessionState, phase: Phase = Phase.of(state), agents: Int = 0) =
        Session(hex = "00", id = "s", name = "s", project = "p", state = state, model = "opus", mode = "default", activity = "", updatedAt = 0L, phase = phase, agentsRunning = agents)

    @Test fun claudeAtWorkOrStartingIsWorking() {
        assertTrue(s(SessionState.Working).busy)
        assertTrue(s(SessionState.Working, Phase.Starting).busy)
    }

    @Test fun agentsAtWorkKeepAnIdleSessionWorking() {
        assertTrue(s(SessionState.Idle, agents = 11).busy)
        assertFalse(s(SessionState.Idle).busy)
    }

    @Test fun needsYouOutranksIt() {
        assertFalse(s(SessionState.NeedsYou, agents = 3).busy)
    }

    @Test fun anEndedSessionNeverWorks() {
        assertFalse(s(SessionState.Idle, Phase.Ended, agents = 2).busy)
    }

    @Test fun theOwnersScreenCountsTheSameEverywhere() {
        // Two projects busy through agents and one with Claude at work: three working, one needing you.
        val sessions = listOf(s(SessionState.Idle, agents = 11), s(SessionState.Working, agents = 3), s(SessionState.Idle), s(SessionState.NeedsYou, agents = 1))
        assertEquals(2, sessions.count { it.busy })
        assertEquals(1, sessions.count { it.state == SessionState.NeedsYou })
    }
}
