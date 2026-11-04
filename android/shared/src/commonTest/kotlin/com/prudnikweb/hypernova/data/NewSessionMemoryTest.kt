// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the new-session sheet starts with (data/NewSessionMemory.kt). */
class NewSessionMemoryTest {
    /** Fork: a form opens on Ask, Edits, Plan or Auto; Bypass, Codex's modes or nothing said start in Ask. */
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

    private val remembered = Remembered(null, null, emptyMap(), codexMode = AgentKind.CAN_EDIT)

    /** Fork: what the server says from the agent's own file, one of that agent's own modes; a Bypass, a full access or the other agent's word is the safe one. */
    @Test fun aFormOpensOnTheServersValueNeverWiderThanTheAgentsOwnModes() {
        for (m in listOf("default", "acceptEdits", "plan", "auto")) assertEquals(m, NewSessionMemory.startsIn(AgentKind.CLAUDE, m, remembered))
        for (m in listOf(AgentKind.READ_ONLY, AgentKind.CAN_EDIT)) assertEquals(m, NewSessionMemory.startsIn(AgentKind.CODEX, m, remembered))
        // Whatever an older server's stale value says: the safe one. The daemon checks too (start-modes.ts).
        for (m in listOf("bypassPermissions", "dontAsk", AgentKind.CAN_EDIT, AgentKind.READ_ONLY, "danger-full-access", "never", "")) {
            assertEquals(m, "default", NewSessionMemory.startsIn(AgentKind.CLAUDE, m, remembered))
        }
        for (m in listOf("danger-full-access", "never", "bypassPermissions", "plan", "auto", "default", "")) {
            assertEquals(m, AgentKind.READ_ONLY, NewSessionMemory.startsIn(AgentKind.CODEX, m, remembered))
        }
    }

    /** An older server says nothing: Ask, and for Codex this device's last mode, its own agent's. */
    @Test fun anOlderServerOpensOnAskAndCodexOnItsLastMode() {
        assertEquals("default", NewSessionMemory.startsIn(AgentKind.CLAUDE, null, remembered))
        assertEquals(AgentKind.CAN_EDIT, NewSessionMemory.startsIn(AgentKind.CODEX, null, remembered))
    }

    private val storage = com.prudnikweb.hypernova.data.live.FakePlatform().storage
    private fun prefs() = storage.prefs("hypernova")

    /** What v0.27.0 and the phone before it left: dropped, whatever it was, and nothing else with it. */
    @Test fun theOldSettingAndItsListGoAtTheNextStart() {
        for (old in listOf("auto", "plan", "default", "bypassPermissions", "workspace-write", "")) {
            prefs().edit().putString("newSession.startMode", old).putString("newSession.startModeOwed", "z2,shed").putString("newSession.project", "/p").apply()
            NewSessionMemory.forgetOldStartMode(storage)
            assertTrue(old, prefs().all.keys.none { it.startsWith("newSession.startMode") })
            assertEquals(old, "/p", prefs().getString("newSession.project", null))
        }
        prefs().edit().putString("newSession.startModeOwed", "z2").apply()
        NewSessionMemory.forgetOldStartMode(storage)
        assertTrue("the list alone", prefs().all.keys.none { it.startsWith("newSession.startMode") })
        NewSessionMemory.forgetOldStartMode(storage)
    }

    @Test fun theModelIsRememberedPerProvider() {
        val r = Remembered("/p", "or", mapOf("or" to "z-ai/glm-5.3", "anthropic" to null))
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
        val fresh = Remembered(null, null, emptyMap())
        assertEquals(codex, fresh.agent(codex, claudeAvailable = false, codexAvailable = true))
        assertEquals(claude, fresh.agent(claude, claudeAvailable = true, codexAvailable = true))
        val usedCodex = Remembered(null, null, emptyMap(), agent = codex, agentSaved = true)
        assertEquals(codex, usedCodex.agent(claude, claudeAvailable = true, codexAvailable = true))
    }
}
