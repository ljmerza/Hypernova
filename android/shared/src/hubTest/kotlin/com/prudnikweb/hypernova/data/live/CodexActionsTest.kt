package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.HubException
import com.prudnikweb.hypernova.data.SwitchPreview
import com.prudnikweb.hypernova.data.SwitchRefused
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Codex from the phone (docs/PROTOCOL.md, "Codex (a second agent)", "Switching agents"): turning it
 * on and off, its models, a Codex session's model and effort, and a switch's preview and the switch,
 * the real hub against a [FakeDaemon] that answers with the contract fixtures.
 */
@RunWith(RigRunner::class)
class CodexActionsTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }
    private val res get() = rig.res

    @After fun tearDown() = rig.close()

    private val off = JSONObject().put("available", true).put("enabled", false).put("connected", false).put("version", "0.163.0").put("min", "0.163.0")

    private fun connect(vararg sessions: JSONObject) {
        daemon.snapshot = snapshot(sessions = sessions.toList().ifEmpty { listOf(session("s1")) }) { put("codex", off) }
        rig.connect()
    }

    private fun last(method: String, path: String) = daemon.calls(method, path).last()

    private fun codexSession(id: String) = session(id) { put("agent", "codex").put("providerId", "codex").put("permissionMode", "read-only").put("model", "gpt-test") }

    @Test fun turningCodexOnPostsItAndTheHubHasWhatTheMacSays() {
        connect()
        assertFalse(rig.hub.codex!!.enabled)
        daemon.ok("POST", "/v1/codex", Fixtures.json("rest-codex"))
        val c = rig.call { rig.hub.setCodexEnabled(true) }.getOrThrow()!!
        assertTrue(last("POST", "/v1/codex").json.getBoolean("enabled"))
        assertTrue(c.enabled)
        assertTrue(rig.hub.codex!!.enabled)
        assertTrue(rig.hub.codex!!.canSwitchTo)
        // And off.
        daemon.ok("POST", "/v1/codex", JSONObject().put("codex", off))
        rig.call { rig.hub.setCodexEnabled(false) }
        assertFalse(last("POST", "/v1/codex").json.getBoolean("enabled"))
        assertFalse(rig.hub.codex!!.enabled)
    }

    @Test fun aCodexTooOldIsRefusedInTheMacsWordsAndNothingChanges() {
        connect()
        daemon.on("POST", "/v1/codex") { refusal(409, "codex_too_old", "Codex 0.150.0 is too old: update it to 0.163.0 or newer.") }
        val e = rig.call { rig.hub.setCodexEnabled(true) }.exceptionOrNull()!!
        assertEquals("Codex 0.150.0 is too old: update it to 0.163.0 or newer.", e.message)
        assertFalse(rig.hub.codex!!.enabled)
        // Unreachable: the app's own words for turning it on.
        daemon.down = true
        val away = rig.call { rig.hub.setCodexEnabled(true) }.exceptionOrNull()!!
        assertTrue(away is HubException)
        assertTrue(away.message!!.isNotBlank())
        daemon.down = false
        assertTrue(res.getString(Str.cx_err_turn_on).isNotBlank())
    }

    @Test fun theModelsAreAskedOnceAWhileAndAgainWhenCodexTurnsOn() {
        connect()
        daemon.ok("GET", "/v1/codex/models", Fixtures.json("rest-codex-models"))
        val m = rig.call { rig.hub.codexModels() }.getOrThrow()
        assertEquals(listOf("gpt-test", "gpt-other"), m.map { it.value })
        assertEquals(listOf("low", "high"), m[0].efforts)
        assertTrue(m[0].isDefault)
        // Kept: a sheet opening again doesn't ask.
        rig.call { rig.hub.codexModels() }
        assertEquals(1, daemon.count("GET", "/v1/codex/models"))
        // Codex turned on (the socket says): asked again.
        rig.push(JSONObject().put("t", "codex").put("codex", Fixtures.json("rest-codex").getJSONObject("codex"))) { rig.hub.codex!!.enabled }
        rig.call { rig.hub.codexModels() }
        assertEquals(2, daemon.count("GET", "/v1/codex/models"))
    }

    @Test fun aCodexSessionsModelAndEffortGoTogetherAndItsChipFollowsAtOnce() {
        connect(codexSession("cx1"))
        val answer = Fixtures.json("rest-codex-session-model")
        answer.getJSONObject("session").put("id", "cx1")
        daemon.ok("POST", "/v1/sessions/cx1/model", answer)
        val sw = rig.call { rig.hub.setCodexModel("cx1", "gpt-other", "medium") }.getOrThrow()
        val b = last("POST", "/v1/sessions/cx1/model").json
        assertEquals("gpt-other", b.getString("model"))
        assertEquals("medium", b.getString("effort"))
        assertEquals("gpt-other", sw.model)
        assertFalse(sw.nextTurn)
        assertEquals("gpt-other", rig.hub.session("cx1")!!.model)

        // Codex's default (no model), its effort left to it; the Mac says it applies from the next turn.
        daemon.ok("POST", "/v1/sessions/cx1/model", JSONObject().put("model", "gpt-test").put("applies", "next_turn"))
        val def = rig.call { rig.hub.setCodexModel("cx1", null, null) }.getOrThrow()
        val d = last("POST", "/v1/sessions/cx1/model").json
        assertTrue(d.isNull("model"))
        assertFalse(d.has("effort"))
        assertTrue(def.nextTurn)
        assertEquals("gpt-test", rig.hub.session("cx1")!!.model)

        // An effort the model doesn't offer (400): the Mac's words.
        daemon.on("POST", "/v1/sessions/cx1/model") { refusal(400, "bad_effort", "GPT-Other doesn’t offer high.") }
        assertEquals("GPT-Other doesn’t offer high.", rig.call { rig.hub.setCodexModel("cx1", "gpt-other", "high") }.exceptionOrNull()!!.message)
    }

    @Test fun aSwitchsPreviewAsksForTheAgentAndHowMuchToCarry() {
        connect(codexSession("cx1"))
        daemon.ok("POST", "/v1/sessions/cx1/switch/preview", Fixtures.json("rest-switch-preview"))
        val p = rig.call { rig.hub.switchPreview("cx1", AgentKind.CLAUDE, "recent") }.getOrThrow()
        val b = last("POST", "/v1/sessions/cx1/switch/preview").json
        assertEquals("claude", b.getString("to"))
        assertEquals("recent", b.getString("context"))
        assertEquals(SwitchPreview("User: [Hypernova test] make notes.txt\nCodex: Made notes.txt", 2, 59, 98_304), p)
        daemon.on("POST", "/v1/sessions/cx1/switch/preview") { refusal(409, "same_agent", "It already runs on Codex.") }
        assertEquals("It already runs on Codex.", rig.call { rig.hub.switchPreview("cx1", AgentKind.CODEX, "all") }.exceptionOrNull()!!.message)
    }

    @Test fun aSwitchSendsTheTextAndChoicesAndOpensTheNewSession() {
        connect(codexSession("cx1"))
        val answer = Fixtures.json("rest-switch")
        answer.getJSONObject("session").put("id", "cl1").put("switchedFrom", "cx1")
        daemon.ok("POST", "/v1/sessions/cx1/switch", answer)
        val id = rig.call { rig.hub.switchAgent("cx1", AgentKind.CLAUDE, "User: hi", "sonnet", "high", "acceptEdits") }.getOrThrow()
        assertEquals("cl1", id)
        val b = last("POST", "/v1/sessions/cx1/switch").json
        assertEquals("claude", b.getString("to"))
        assertEquals("User: hi", b.getString("text"))
        assertEquals("sonnet", b.getString("model"))
        assertEquals("high", b.getString("effort"))
        assertEquals("acceptEdits", b.getString("permissionMode"))
        // The new session is the hub's at once, before the socket says so.
        assertEquals("cx1", rig.hub.session("cl1")!!.switchedFrom)
        // The defaults: nothing but where to and the text.
        rig.call { rig.hub.switchAgent("cx1", AgentKind.CLAUDE, "User: hi", null, null, null) }
        assertEquals(setOf("to", "text"), last("POST", "/v1/sessions/cx1/switch").json.keys().asSequence().toSet())
    }

    @Test fun aSwitchTurnedDownSaysWhyWhereItWentAndTheMostItTakes() {
        connect(codexSession("cx1"))
        daemon.on("POST", "/v1/sessions/cx1/switch") { json(413, Fixtures.json("rest-switch-too-large")) }
        val big = rig.call { rig.hub.switchAgent("cx1", AgentKind.CLAUDE, "x".repeat(10), null, null, null) }.exceptionOrNull() as SwitchRefused
        assertEquals("context_too_large", big.code)
        assertEquals(98_304, big.maxBytes)
        assertNull(big.to)
        assertTrue(big.message!!.startsWith("That’s more than 96 KB"))

        daemon.on("POST", "/v1/sessions/cx1/switch") { json(409, Fixtures.json("rest-switch-switched")) }
        val gone = rig.call { rig.hub.switchAgent("cx1", AgentKind.CLAUDE, "x", null, null, null) }.exceptionOrNull() as SwitchRefused
        assertEquals("switched", gone.code)
        assertEquals("00000000-0000-4000-8000-000000000001", gone.to)

        // Not the Mac saying no (it's away): no code to act on.
        daemon.down = true
        val away = rig.call { rig.hub.switchAgent("cx1", AgentKind.CLAUDE, "x", null, null, null) }.exceptionOrNull()!!
        assertFalse(away is SwitchRefused)
        daemon.down = false
    }
}
