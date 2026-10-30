package com.prudnikweb.hypernova.data

import com.prudnikweb.hypernova.ui.home.chipVariants
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Claude Code's reasoning effort (data/Effort.kt; docs/PROTOCOL.md, "A session's effort"): the
 * levels the phone offers, what's remembered, which sessions can change theirs, what the Mac sends,
 * and the new-session sheet's chips with the Effort chip among them.
 */
class EffortTest {
    private fun session(phase: Phase = Phase.Idle, agent: String = AgentKind.CLAUDE) =
        Session("00", "s", "s", "p", SessionState.Idle, "claude-opus-5-5", "default", "", 0L, phase = phase, agent = agent)

    @Test fun theLevelsAreClaudeCodesLowestFirst() {
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), ClaudeEffort.LEVELS)
    }

    /** A remembered level this build doesn't know (or none) starts on Claude Code's default: nothing is sent. */
    @Test fun onlyAKnownLevelIsKept() {
        assertEquals("xhigh", ClaudeEffort.known("xhigh"))
        assertEquals("max", ClaudeEffort.known("max"))
        assertNull(ClaudeEffort.known("ultracode"))
        assertNull(ClaudeEffort.known(""))
        assertNull(ClaudeEffort.known(null))
        // Before any session from here: the default.
        assertNull(Remembered(null, null, emptyMap(), "default").effort)
    }

    /** As the mode: an open Claude Code session the Mac can type into; never Codex's, never an ended one. */
    @Test fun theEffortChangesWhereTheModeDoesButNotForCodexOrEnded() {
        assertTrue(ClaudeEffort.changeable(session(), modeChangeable = true))
        assertFalse(ClaudeEffort.changeable(session(), modeChangeable = false))
        assertFalse(ClaudeEffort.changeable(session(agent = AgentKind.CODEX), modeChangeable = true))
        assertFalse(ClaudeEffort.changeable(session(phase = Phase.Ended), modeChangeable = true))
    }

    @Test fun theSessionsEffortIsReadAndAnOlderMacSaysNone() {
        val base = """{"id":"s1","title":"t","cwd":"/p/alpha","state":"idle""""
        assertEquals("high", Wire.session(JSONObject("$base,\"effort\":\"high\"}")).effort)
        assertNull(Wire.session(JSONObject("$base,\"effort\":null}")).effort)
        assertNull(Wire.session(JSONObject("$base}")).effort)
    }

    /** The model gives way first (its provider's name), then the effort (its label), then the mode (its glyph). */
    @Test fun theChipsGiveWayModelThenEffortThenMode() {
        val model = listOf(160, 100)
        val effort = listOf(110, 60)
        val mode = listOf(120, 40)
        assertEquals(Triple(0, 0, 0), chipVariants(model, effort, mode, 390))
        assertEquals(Triple(1, 0, 0), chipVariants(model, effort, mode, 330))
        assertEquals(Triple(1, 1, 0), chipVariants(model, effort, mode, 280))
        assertEquals(Triple(1, 1, 1), chipVariants(model, effort, mode, 200))
        // Nothing fits: the narrowest of all, the project's name is cut.
        assertEquals(Triple(1, 1, 1), chipVariants(model, effort, mode, 50))
        // No effort chip (Codex): as before, the model then the mode.
        assertEquals(Triple(1, 0, 0), chipVariants(model, emptyList(), mode, 220))
        assertEquals(Triple(1, 0, 1), chipVariants(model, emptyList(), mode, 140))
    }
}
