// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the new-session sheet starts with (data/NewSessionMemory.kt). */
class NewSessionMemoryTest {
    /** Fork: Settings → New sessions offers Ask, Edits, Plan and Auto; Bypass, Codex's modes or nothing saved start in Ask. */
    @Test fun newSessionsStartInTheChosenModeNeverBypass() {
        assertEquals(listOf("default", "acceptEdits", "plan", "auto"), NewSessionMemory.START_MODES)
        assertEquals("default", NewSessionMemory.startMode("default"))
        assertEquals("acceptEdits", NewSessionMemory.startMode("acceptEdits"))
        assertEquals("plan", NewSessionMemory.startMode("plan"))
        assertEquals("auto", NewSessionMemory.startMode("auto"))
        assertEquals("default", NewSessionMemory.startMode("bypassPermissions"))
        assertEquals("default", NewSessionMemory.startMode(AgentKind.CAN_EDIT))
        assertEquals("default", NewSessionMemory.startMode(""))
        assertEquals("default", NewSessionMemory.startMode(null))
    }

    private val remembered = Remembered(null, null, emptyMap(), mode = "plan", codexMode = AgentKind.CAN_EDIT)

    /** Fork: what the server keeps, one of that agent's own modes; a Bypass, a full access or the other agent's word is the safe one. */
    @Test fun aFormOpensOnTheServersValueNeverWiderThanTheAgentsOwnModes() {
        for (m in listOf("default", "acceptEdits", "plan", "auto")) assertEquals(m, NewSessionMemory.startsIn(AgentKind.CLAUDE, m, remembered))
        for (m in listOf(AgentKind.READ_ONLY, AgentKind.CAN_EDIT)) assertEquals(m, NewSessionMemory.startsIn(AgentKind.CODEX, m, remembered))
        // Whatever a stale or hand-edited value says, and whatever this phone had: not the phone's, the safe one.
        for (m in listOf("bypassPermissions", "dontAsk", AgentKind.CAN_EDIT, AgentKind.READ_ONLY, "danger-full-access", "never", "")) {
            assertEquals(m, "default", NewSessionMemory.startsIn(AgentKind.CLAUDE, m, remembered))
        }
        for (m in listOf("danger-full-access", "never", "bypassPermissions", "plan", "auto", "default", "")) {
            assertEquals(m, AgentKind.READ_ONLY, NewSessionMemory.startsIn(AgentKind.CODEX, m, remembered))
        }
    }

    /** An older server keeps nothing: what this phone had, Claude's old setting and Codex's last mode, each for its own agent. */
    @Test fun anOlderServerOpensOnWhatThePhoneHad() {
        assertEquals("plan", NewSessionMemory.startsIn(AgentKind.CLAUDE, null, remembered))
        assertEquals(AgentKind.CAN_EDIT, NewSessionMemory.startsIn(AgentKind.CODEX, null, remembered))
        // The old setting holding Bypass or a Codex word, by hand: Ask (load's own check).
        val p = com.prudnikweb.hypernova.data.live.FakePlatform().storage
        for (bad in listOf("bypassPermissions", AgentKind.CAN_EDIT)) {
            p.prefs("hypernova").edit().putString("newSession.startMode", bad).apply()
            assertEquals("default", NewSessionMemory.startsIn(AgentKind.CLAUDE, null, NewSessionMemory.load(p)))
        }
    }

    /** The old phone-wide setting: what's left to move, for each server by itself; only a Claude mode that isn't Ask anyway. */
    @Test fun theOldSettingIsToMoveOnceForEachServer() {
        val p = com.prudnikweb.hypernova.data.live.FakePlatform().storage
        assertNull("nothing was set", NewSessionMemory.startModeToMove(p, "z2"))
        fun old(m: String) = p.prefs("hypernova").edit().putString("newSession.startMode", m).apply()
        for (nothing in listOf("default", "bypassPermissions", AgentKind.CAN_EDIT, "danger-full-access", "")) {
            old(nothing)
            assertNull(nothing, NewSessionMemory.startModeToMove(p, "z2"))
        }
        old("auto")
        assertEquals("auto", NewSessionMemory.startModeToMove(p, "z2"))
        assertEquals("auto", NewSessionMemory.startModeToMove(p, "shed"))
        NewSessionMemory.startModeMoved(p, "z2")
        assertNull("once", NewSessionMemory.startModeToMove(p, "z2"))
        assertEquals("each server by itself", "auto", NewSessionMemory.startModeToMove(p, "shed"))
    }

    @Test fun theModelIsRememberedPerProvider() {
        val r = Remembered("/p", "or", mapOf("or" to "z-ai/glm-5.3", "anthropic" to null), "default")
        assertEquals("z-ai/glm-5.3", r.model("or"))
        assertNull(r.model("anthropic"))
        assertNull(r.model(null))
    }

    /** The agent the sheet opens on: the last one used while it's here, else the leading agent (data/AgentLead.kt). */
    @Test fun theAgentIsTheLastOneUsedElseTheLeadingOne() {
        val claude = AgentKind.CLAUDE
        val codex = AgentKind.CODEX
        // Nothing saved yet: the leading agent.
        assertEquals(claude, NewSessionMemory.keptAgent(null, claude, claudeAvailable = true, codexAvailable = true))
        assertEquals(codex, NewSessionMemory.keptAgent(null, codex, claudeAvailable = false, codexAvailable = true))
        // The last one used, while it's still here.
        assertEquals(codex, NewSessionMemory.keptAgent(codex, claude, claudeAvailable = true, codexAvailable = true))
        assertEquals(claude, NewSessionMemory.keptAgent(claude, claude, claudeAvailable = true, codexAvailable = true))
        // Codex gone: Claude Code again. Claude Code gone (Codex alone): Codex, whatever was saved.
        assertEquals(claude, NewSessionMemory.keptAgent(codex, claude, claudeAvailable = true, codexAvailable = false))
        assertEquals(codex, NewSessionMemory.keptAgent(claude, codex, claudeAvailable = false, codexAvailable = true))
        // Neither said to be here (an odd Mac): Claude Code, as before Codex.
        assertEquals(claude, NewSessionMemory.keptAgent(codex, claude, claudeAvailable = false, codexAvailable = false))
        // Through Remembered: an agent that was never saved isn't taken as Claude's.
        val fresh = Remembered(null, null, emptyMap(), "default")
        assertEquals(codex, fresh.agent(codex, claudeAvailable = false, codexAvailable = true))
        assertEquals(claude, fresh.agent(claude, claudeAvailable = true, codexAvailable = true))
        val usedCodex = Remembered(null, null, emptyMap(), "default", agent = codex, agentSaved = true)
        assertEquals(codex, usedCodex.agent(claude, claudeAvailable = true, codexAvailable = true))
    }
}
