package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.OutdatedClaude
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.Wire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Restart session ([RestartKind]; docs/PROTOCOL.md, "Session menu"; DECISIONS, 2026-11-01): wherever
 * something runs that Hypernova can restart, a job on an older Claude Code included.
 */
class RestartOfferTest {
    private val old = OutdatedClaude("2.1.312", "2.1.314")

    private fun session(state: SessionState, phase: Phase, job: String?, outdated: OutdatedClaude? = null, running: Boolean? = null, agent: String = com.prudnikweb.hypernova.data.AgentKind.CLAUDE) =
        Session("s1", "s1", "t", "alpha", state, "opus", "default", "", 0L, phase = phase, agentJob = job, agentOutdated = outdated, agentRunning = running, agent = agent)
    private val link = com.prudnikweb.hypernova.data.TermLink("t1", mac = 0, phone = 0)

    @Test fun anOutdatedJobIsOfferedRestart() {
        assertTrue(MenuOffer.of(session(SessionState.Working, Phase.Working, "j1", old)).restart)
        assertTrue(MenuOffer.of(session(SessionState.Idle, Phase.Idle, "j1", old)).restart)
        // Restart alone is enough for a long-press menu.
        assertTrue(MenuOffer(rename = false, stop = false, delete = false, restart = true).any)
    }

    @Test fun restartIsOfferedWhereItCanBeDone() {
        // A job whose process runs, on the installed Claude Code too.
        assertEquals(RestartKind.Job, RestartKind.of(session(SessionState.Working, Phase.Working, "j1")))
        assertEquals(RestartKind.Job, RestartKind.of(session(SessionState.NeedsYou, Phase.NeedsYou, "j1")))
        assertEquals(RestartKind.Job, RestartKind.of(session(SessionState.Idle, Phase.Idle, "j1", running = true)))
        assertEquals(RestartKind.Job, RestartKind.of(session(SessionState.Idle, Phase.Idle, "j1", old)))
        // A shared terminal, at rest or at work, however it was started.
        assertEquals(RestartKind.Terminal, RestartKind.of(session(SessionState.Idle, Phase.Idle, null).copy(term = link, origin = "terminal")))
        assertEquals(RestartKind.Terminal, RestartKind.of(session(SessionState.Working, Phase.Working, null).copy(term = link, origin = "hypernova")))
        // A terminal it doesn't hold: there, and it says why.
        assertEquals(RestartKind.OpenElsewhere, RestartKind.of(session(SessionState.Idle, Phase.Idle, null).copy(origin = "terminal")))
        assertTrue(MenuOffer.of(session(SessionState.Idle, Phase.Idle, null).copy(origin = "terminal")).restart)
    }

    @Test fun noRestartWhereNothingRuns() {
        // A job with no process: its next start loads them.
        assertEquals(RestartKind.None, RestartKind.of(session(SessionState.Idle, Phase.Idle, "j1", running = false)))
        assertEquals(RestartKind.None, RestartKind.of(session(SessionState.Idle, Phase.Idle, "j1")))
        // Ended, whatever it says; a phone's session with no terminal, whose next prompt starts a claude.
        assertEquals(RestartKind.None, RestartKind.of(session(SessionState.Idle, Phase.Ended, "j1", running = true)))
        assertEquals(RestartKind.None, RestartKind.of(session(SessionState.Idle, Phase.Ended, null).copy(term = link, origin = "terminal")))
        assertEquals(RestartKind.None, RestartKind.of(session(SessionState.Idle, Phase.Idle, null).copy(origin = "hypernova")))
        // Codex, terminal or not.
        val codex = session(SessionState.Idle, Phase.Idle, null, agent = com.prudnikweb.hypernova.data.AgentKind.CODEX)
        assertEquals(RestartKind.None, RestartKind.of(codex))
        assertEquals(RestartKind.None, RestartKind.of(codex.copy(term = link)))
        assertFalse(MenuOffer.of(codex.copy(term = link)).restart)
    }

    @Test fun itAsksFirstOnlyWhereSomethingWouldStop() {
        assertTrue(RestartKind.asksFirst(session(SessionState.Working, Phase.Working, null)))
        // An approval waiting.
        assertTrue(RestartKind.asksFirst(session(SessionState.NeedsYou, Phase.NeedsYou, null)))
        // Its main agent idle, agents still running.
        assertTrue(RestartKind.asksFirst(session(SessionState.Idle, Phase.Idle, null).copy(agentsRunning = 1)))
        // A terminal open on it on the server.
        assertTrue(RestartKind.asksFirst(session(SessionState.Idle, Phase.Idle, null).copy(term = link.copy(mac = 1))))
        // At rest and unwatched: it just goes. The phone's own view doesn't count, it comes back.
        assertFalse(RestartKind.asksFirst(session(SessionState.Idle, Phase.Idle, null).copy(term = link)))
        assertFalse(RestartKind.asksFirst(session(SessionState.Idle, Phase.Idle, null).copy(term = link.copy(phone = 1))))
        assertFalse(RestartKind.asksFirst(session(SessionState.Idle, Phase.Idle, "j1", running = true)))
    }

    @Test fun readsBothVersions() {
        fun wire(extra: String) = Wire.session(JSONObject("""{"id":"s1","title":"t","cwd":"/p/alpha","state":"idle","agentJob":"j1"$extra}"""))
        assertEquals(old, wire(""","agentOutdated":{"runs":"2.1.312","installed":"2.1.314"}""").agentOutdated)
        assertNull(wire("").agentOutdated)
        assertNull(wire(""","agentOutdated":null""").agentOutdated)
        assertNull(wire(""","agentOutdated":{"runs":"2.1.312"}""").agentOutdated)
    }
}
