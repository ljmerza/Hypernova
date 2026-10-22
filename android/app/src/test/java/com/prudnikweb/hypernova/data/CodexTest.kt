package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Codex as a second agent (docs/PROTOCOL.md, "Codex (a second agent)", "Switching agents";
 * data/Codex.kt): what the Mac sends is read with defaults, and a Mac without Codex (or an older
 * one) reads exactly as before.
 */
class CodexTest {
    private fun session(extra: String) = Wire.session(JSONObject("""{"id":"s1","title":"t","cwd":"/p/alpha","state":"idle"$extra}"""))

    @Test fun aClaudeSessionCarriesNoAgent() {
        val s = session("")
        assertEquals(AgentKind.CLAUDE, s.agent)
        assertNull(s.switchedFrom)
        assertNull(s.switchedTo)
    }

    @Test fun readsACodexSessionAndItsSwitch() {
        val s = session(""","agent":"codex","providerId":"codex","permissionMode":"workspace-write","switchedFrom":"c1","switchedTo":null""")
        assertEquals(AgentKind.CODEX, s.agent)
        assertEquals("c1", s.switchedFrom)
        assertNull(s.switchedTo)
        assertTrue(AgentKind.codexMode(s.permissionMode))
        assertTrue(Session("s1", "s1", "t", "alpha", SessionState.Idle, "gpt-5.5", s.permissionMode, "", 0L, agent = s.agent).codex)
    }

    @Test fun noCodexOnAMacWithoutIt() {
        // Not installed (null) and an older Mac (absent) read the same: no Codex anywhere.
        assertNull(Wire.snapshot(JSONObject("""{"codex":null}""")).codex)
        assertNull(Wire.snapshot(JSONObject("{}")).codex)
    }

    @Test fun readsCodexWithItsAccountAndWindows() {
        val c = Wire.snapshot(
            JSONObject(
                """{"codex":{"available":true,"enabled":true,"connected":false,"version":"0.163.0","min":"0.163.0","path":"/x/codex","problem":null,
                "account":{"signedIn":true,"type":"apiKey","planType":null},
                "usage":{"fiveHour":null,"sevenDay":{"pct":41.5,"resetsAt":1700000000000},"limited":false,"observedAt":5}}}""",
            ),
        ).codex!!
        assertTrue(c.available && c.enabled && c.canSwitchTo)
        assertFalse(c.connected)
        assertEquals(true, c.account?.paid)
        assertNull(c.usage?.fiveHour)
        assertEquals(41.5, c.usage?.sevenDay?.pct ?: 0.0, 0.001)
        assertEquals(1700000000000L, c.usage?.sevenDay?.resetsAt)
        // Off: installed, but nothing to switch to yet.
        assertFalse(CodexWire.state(JSONObject("""{"available":true,"enabled":false}"""))!!.canSwitchTo)
    }

    @Test fun readsCodexModels() {
        val list = CodexWire.models(
            JSONObject(
                """{"models":[{"value":"gpt-5.5","name":"GPT-5.5","description":"Fast","efforts":["low","medium","high"],"defaultEffort":"medium","isDefault":true},
                {"name":"no value"},{"value":"gpt-other","efforts":null,"isDefault":false}]}""",
            ),
        )
        assertEquals(listOf("gpt-5.5", "gpt-other"), list.map { it.value })
        assertEquals(listOf("low", "medium", "high"), list[0].efforts)
        assertEquals("gpt-other", list[1].name)
        assertEquals(emptyList<String>(), list[1].efforts)
        // Null is Codex's default.
        assertEquals("gpt-5.5", list.pick(null)?.value)
        assertEquals("gpt-other", list.pick("gpt-other")?.value)
        assertNull(list.pick("gone"))
    }

    @Test fun readsASwitchPreview() {
        val p = CodexWire.preview(JSONObject("""{"text":"User: hi\nCodex: hello","messages":2,"bytes":25,"maxBytes":98304}"""))
        assertEquals(2, p.messages)
        assertEquals(98304, p.maxBytes)
        assertEquals(CodexWire.SWITCH_MAX_BYTES, CodexWire.preview(JSONObject("""{"text":""}""")).maxBytes)
    }

    @Test fun theNewSessionSheetKeepsCodexModes() {
        assertEquals(AgentKind.CAN_EDIT, NewSessionMemory.keptCodexMode(AgentKind.CAN_EDIT))
        assertEquals(AgentKind.READ_ONLY, NewSessionMemory.keptCodexMode(AgentKind.READ_ONLY))
        assertEquals(AgentKind.READ_ONLY, NewSessionMemory.keptCodexMode("bypassPermissions"))
        assertEquals(AgentKind.READ_ONLY, NewSessionMemory.keptCodexMode(null))
    }
}
