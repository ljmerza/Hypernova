// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** One rule for "working" (Session.busy; owner's report: the sky, the seam and the card disagreed). */
class SessionBusyTest {
    private fun s(state: SessionState, phase: Phase = Phase.of(state), agents: Int = 0, shells: Int = 0, monitors: Int = 0) =
        Session(hex = "00", id = "s", name = "s", project = "p", state = state, model = "opus", mode = "default", activity = "", updatedAt = 0L, phase = phase, agentsRunning = agents, shellsRunning = shells, monitorsRunning = monitors)

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

    @Test fun aPlainShellAloneIsNotWorkingAndIsMarked() {
        val shell = s(SessionState.Idle, shells = 2)
        assertFalse(shell.busy)
        assertEquals(2, shell.plainShells)
        assertEquals(0, s(SessionState.Idle).plainShells)
    }

    @Test fun aMonitorIsWorkingAndNotInTheMark() {
        // On the wire a Monitor is among the shells, and said apart.
        val monitor = s(SessionState.Idle, shells = 1, monitors = 1)
        assertTrue(monitor.busy)
        assertEquals(0, monitor.plainShells)
    }

    @Test fun bothAreWorkingWithOnlyTheShellInTheMark() {
        val both = s(SessionState.Idle, shells = 3, monitors = 1)
        assertTrue(both.busy)
        assertEquals(2, both.plainShells)
    }

    @Test fun aMonitorNeverOutranksNeedingYouAndAnEndedSessionHasNeither() {
        assertFalse(s(SessionState.NeedsYou, shells = 1, monitors = 1).busy)
        val ended = s(SessionState.Idle, Phase.Ended, shells = 2, monitors = 1)
        assertFalse(ended.busy)
        assertEquals(0, ended.plainShells)
    }

    @Test fun anOlderServerSaysNoMonitorsSoItsShellsAreAllMarked() {
        val older = s(SessionState.Idle, shells = 2)
        assertFalse(older.busy)
        assertEquals(2, older.plainShells)
        // Never below none, whatever a server says.
        assertEquals(0, s(SessionState.Idle, shells = 1, monitors = 2).plainShells)
    }

    @Test fun theOwnersScreenCountsTheSameEverywhere() {
        // Two projects busy through agents and one with Claude at work: three working, one needing you.
        val sessions = listOf(s(SessionState.Idle, agents = 11), s(SessionState.Working, agents = 3), s(SessionState.Idle), s(SessionState.NeedsYou, agents = 1))
        assertEquals(2, sessions.count { it.busy })
        assertEquals(1, sessions.count { it.state == SessionState.NeedsYou })
    }
}
