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
 * Restart (`claude respawn`) for a `claude agents` job on an older Claude Code than the one installed
 * (docs/PROTOCOL.md, Session.agentOutdated, "Session menu"): offered for that job only.
 */
class RestartOfferTest {
    private val old = OutdatedClaude("2.1.312", "2.1.314")

    private fun session(state: SessionState, phase: Phase, job: String?, outdated: OutdatedClaude?) =
        Session("s1", "s1", "t", "alpha", state, "opus", "default", "", 0L, phase = phase, agentJob = job, agentOutdated = outdated)

    @Test fun anOutdatedJobIsOfferedRestart() {
        assertTrue(MenuOffer.of(session(SessionState.Working, Phase.Working, "j1", old)).restart)
        assertTrue(MenuOffer.of(session(SessionState.Idle, Phase.Idle, "j1", old)).restart)
        // Restart alone is enough for a long-press menu.
        assertTrue(MenuOffer(rename = false, stop = false, delete = false, restart = true).any)
    }

    @Test fun noRestartOtherwise() {
        // On the installed Claude Code (or a Mac that doesn't say): nothing to restart onto.
        assertFalse(MenuOffer.of(session(SessionState.Working, Phase.Working, "j1", null)).restart)
        // Not a claude agents job: nothing to respawn, whatever it says.
        assertFalse(MenuOffer.of(session(SessionState.Working, Phase.Working, null, old)).restart)
        // The rest of the menu is as before.
        assertEquals(MenuOffer(rename = true, stop = true, delete = true), MenuOffer.of(session(SessionState.Working, Phase.Working, "j1", null)))
    }

    @Test fun readsBothVersions() {
        fun wire(extra: String) = Wire.session(JSONObject("""{"id":"s1","title":"t","cwd":"/p/alpha","state":"idle","agentJob":"j1"$extra}"""))
        assertEquals(old, wire(""","agentOutdated":{"runs":"2.1.312","installed":"2.1.314"}""").agentOutdated)
        assertNull(wire("").agentOutdated)
        assertNull(wire(""","agentOutdated":null""").agentOutdated)
        assertNull(wire(""","agentOutdated":{"runs":"2.1.312"}""").agentOutdated)
    }
}
