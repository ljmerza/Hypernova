package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the new-session sheet carries over (data/NewSessionMemory.kt): never Auto or Bypass. */
class NewSessionMemoryTest {
    @Test fun autoAndBypassAreChosenAgainEachTime() {
        assertEquals("acceptEdits", NewSessionMemory.keptMode("acceptEdits"))
        assertEquals("plan", NewSessionMemory.keptMode("plan"))
        assertEquals("default", NewSessionMemory.keptMode("auto"))
        assertEquals("default", NewSessionMemory.keptMode("bypassPermissions"))
        assertEquals("default", NewSessionMemory.keptMode(null))
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
