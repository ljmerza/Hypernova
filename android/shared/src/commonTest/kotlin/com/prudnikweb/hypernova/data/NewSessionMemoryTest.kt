// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val remembered = Remembered(null, null, emptyMap(), codexMode = AgentKind.CAN_EDIT)

    /** Fork: what the server keeps, one of that agent's own modes; a Bypass, a full access or the other agent's word is the safe one. */
    @Test fun aFormOpensOnTheServersValueNeverWiderThanTheAgentsOwnModes() {
        for (m in listOf("default", "acceptEdits", "plan", "auto")) assertEquals(m, NewSessionMemory.startsIn(AgentKind.CLAUDE, m, "plan", remembered))
        for (m in listOf(AgentKind.READ_ONLY, AgentKind.CAN_EDIT)) assertEquals(m, NewSessionMemory.startsIn(AgentKind.CODEX, m, null, remembered))
        // Whatever a stale or hand-edited value says, and whatever this phone had: not the phone's, the safe one.
        for (m in listOf("bypassPermissions", "dontAsk", AgentKind.CAN_EDIT, AgentKind.READ_ONLY, "danger-full-access", "never", "")) {
            assertEquals(m, "default", NewSessionMemory.startsIn(AgentKind.CLAUDE, m, "plan", remembered))
        }
        for (m in listOf("danger-full-access", "never", "bypassPermissions", "plan", "auto", "default", "")) {
            assertEquals(m, AgentKind.READ_ONLY, NewSessionMemory.startsIn(AgentKind.CODEX, m, null, remembered))
        }
    }

    /** An older server keeps nothing: the old setting only if it's owed it, else Ask; Codex's last mode, its own agent's. */
    @Test fun anOlderServerOpensOnWhatItsOwedElseAsk() {
        assertEquals("plan", NewSessionMemory.startsIn(AgentKind.CLAUDE, null, "plan", remembered))
        assertEquals("not owed: a server paired later", "default", NewSessionMemory.startsIn(AgentKind.CLAUDE, null, null, remembered))
        assertEquals("never a Claude mode", AgentKind.CAN_EDIT, NewSessionMemory.startsIn(AgentKind.CODEX, null, "plan", remembered))
        assertEquals("default", NewSessionMemory.startsIn(AgentKind.CLAUDE, null, "bypassPermissions", remembered))
    }

    private val storage = com.prudnikweb.hypernova.data.live.FakePlatform().storage
    private fun prefs() = storage.prefs("hypernova")
    private fun old(m: String) = prefs().edit().putString("newSession.startMode", m).apply()
    private fun owedAt(id: String) = NewSessionMemory.startModeToMove(storage, id)

    /** Those paired at the first start are owed it, each once; a server paired after that never is. */
    @Test fun onlyServersPairedAtTheFirstStartAreOwedTheOldSetting() {
        old("auto")
        NewSessionMemory.noteStartModeServers(storage, listOf("z2", "shed"))
        assertEquals("auto", owedAt("z2"))
        assertEquals("auto", owedAt("shed"))
        assertNull("paired after the first start", owedAt("new"))
        // The next start, with it paired by then: it doesn't join.
        NewSessionMemory.noteStartModeServers(storage, listOf("z2", "shed", "new"))
        assertNull(owedAt("new"))
        NewSessionMemory.startModeMoved(storage, "z2")
        assertNull("once", owedAt("z2"))
        assertEquals("each by itself", "auto", owedAt("shed"))
        assertEquals("kept while one's owed", "auto", prefs().getString("newSession.startMode", null))
        // The last one: the old value and the list are gone, and stay gone.
        NewSessionMemory.startModeMoved(storage, "shed")
        assertNull(owedAt("shed"))
        assertTrue(prefs().all.keys.none { it.startsWith("newSession.startMode") })
        NewSessionMemory.noteStartModeServers(storage, listOf("z2", "shed", "new"))
        assertTrue(prefs().all.keys.none { it.startsWith("newSession.startMode") })
    }

    @Test fun aForgottenServerLeavesTheListAndTheLastOneTakesTheOldValue() {
        old("plan")
        NewSessionMemory.noteStartModeServers(storage, listOf("z2", "shed"))
        NewSessionMemory.noteStartModeServers(storage, listOf("shed"))
        assertNull(owedAt("z2"))
        assertEquals("plan", owedAt("shed"))
        NewSessionMemory.noteStartModeServers(storage, emptyList())
        assertTrue(prefs().all.keys.none { it.startsWith("newSession.startMode") })
    }

    /** Nothing paired at the first start, or nothing worth moving: nobody's owed it, and it goes. */
    @Test fun nothingToMoveClearsTheOldValueAtOnce() {
        old("auto")
        NewSessionMemory.noteStartModeServers(storage, emptyList())
        assertNull(owedAt("new"))
        assertTrue(prefs().all.keys.none { it.startsWith("newSession.startMode") })
        for (nothing in listOf("default", "bypassPermissions", AgentKind.CAN_EDIT, "danger-full-access", "")) {
            old(nothing)
            NewSessionMemory.noteStartModeServers(storage, listOf("z2"))
            assertNull(nothing, owedAt("z2"))
            assertTrue(nothing, prefs().all.keys.none { it.startsWith("newSession.startMode") })
        }
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
