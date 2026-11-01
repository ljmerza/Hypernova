package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Account
import com.prudnikweb.hypernova.data.AgentSettingsFile
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.ShownSetting
import com.prudnikweb.hypernova.data.ApiException
import com.prudnikweb.hypernova.data.Balance
import com.prudnikweb.hypernova.data.ProviderCheck
import com.prudnikweb.hypernova.data.ProviderInput
import com.prudnikweb.hypernova.data.ProviderModels
import com.prudnikweb.hypernova.data.ProviderRefused
import com.prudnikweb.hypernova.data.StatsRange
import com.prudnikweb.hypernova.data.StatsSource
import com.prudnikweb.hypernova.data.refusedRange
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * What Settings asks of the daemon through the real hub (docs/PROTOCOL.md, "Providers", "Account",
 * "Statistics"): the providers' routes, the account's check and sign-in, the testing checklist,
 * Forget and Statistics. The daemon answers with the contract's own fixtures (protocol/fixtures),
 * so what's asserted here is what the real daemon sends.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsCallsTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }

    @After fun tearDown() = rig.close()

    /** A contract fixture (protocol/fixtures/[name].json, on the test classpath). */
    private fun fixture(name: String): JSONObject =
        JSONObject(javaClass.classLoader!!.getResource("fixtures/$name.json")!!.readText())

    private fun answer(method: String, path: String, name: String, status: Int = 200) = daemon.on(method, path) { json(status, fixture(name)) }

    private fun connect() {
        daemon.snapshot = snapshot()
        rig.connect()
    }

    private fun last(method: String, path: String): FakeDaemon.Req = daemon.calls(method, path).last()

    // ─── The agents' settings (docs/PROTOCOL.md, "Agent settings") ───

    @Test fun agentSettingsAreReadFromTheRouteAndParsedPerAgent() {
        connect()
        answer("GET", "/v1/agents/settings", "rest-agent-settings")
        val r = rig.call { rig.hub.agentSettings() }.getOrThrow()
        assertEquals(1, daemon.count("GET", "/v1/agents/settings"))
        assertEquals("gpt-6.1-sol", r.of(AgentSettingsReply.CODEX).values.first().text)
        assertEquals(listOf("home-assistant"), r.of(AgentSettingsReply.CLAUDE).claude?.mcpServers)
        assertEquals(listOf("home-assistant", "github", "db-tools", "docs"), r.of(AgentSettingsReply.CLAUDE).mcp?.servers?.map { it.name })
    }

    // ─── A session's MCP servers (docs/PROTOCOL.md, "A session's MCP servers") ───

    @Test fun aSessionsMcpServersAreReadFromTheRoute() {
        connect()
        answer("GET", "/v1/sessions/sess-plan/mcp", "rest-session-mcp")
        val r = rig.call { rig.hub.sessionMcp("sess-plan") }.getOrThrow()
        assertEquals(1, daemon.count("GET", "/v1/sessions/sess-plan/mcp"))
        assertEquals(listOf("db-tools", "docs"), r.servers.map { it.name })
        assertEquals(2, r.forAllProjects)
    }

    @Test fun aSessionsMcpServersOnAnOlderServerSayItNeedsUpdatingButAGoneSessionDoesnt() {
        connect()
        daemon.on("GET", "/v1/sessions/old/mcp") { json(404, JSONObject("""{"error":{"code":"not_found","message":"Unknown endpoint."}}""")) }
        assertTrue(rig.call { rig.hub.sessionMcp("old") }.exceptionOrNull() is com.prudnikweb.hypernova.data.SessionMcpTooOld)
        daemon.on("GET", "/v1/sessions/gone/mcp") { json(404, JSONObject("""{"error":{"code":"no_session","message":"That session no longer exists."}}""")) }
        val gone = rig.call { rig.hub.sessionMcp("gone") }.exceptionOrNull()
        assertFalse(gone is com.prudnikweb.hypernova.data.SessionMcpTooOld)
        assertEquals("That session no longer exists.", gone?.message)
    }

    @Test fun aFilesTroubleIsThatAgentsStateAndAnUnknownOneReadsAsUnreadable() {
        connect()
        daemon.on("GET", "/v1/agents/settings") {
            json(200, JSONObject("""{"scope":"user","claude":{"state":"malformed","values":[],"summary":null},"codex":{"state":"missing","values":[],"summary":null}}"""))
        }
        val r = rig.call { rig.hub.agentSettings() }.getOrThrow()
        assertEquals(AgentSettingsFile.MALFORMED, r.claude.state)
        assertEquals(AgentSettingsFile.MISSING, r.codex.state)
        assertNull(r.claude.claude)
        assertEquals(emptyList<ShownSetting>(), r.codex.values)
    }

    @Test fun anOlderServerWithoutTheRouteSaysItNeedsUpdating() {
        connect()
        daemon.on("GET", "/v1/agents/settings") { refusal(404, "not_found", "Unknown endpoint.") }
        val e = rig.call { rig.hub.agentSettings() }.exceptionOrNull()
        assertTrue(e is AgentSettingsTooOld)
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.as_too_old), e?.message)
    }

    @Test fun anotherFailureOfTheSettingsRouteIsNotCalledOld() {
        connect()
        daemon.on("GET", "/v1/agents/settings") { refusal(500, "internal", "Something went wrong.") }
        val e = rig.call { rig.hub.agentSettings() }.exceptionOrNull()
        assertNotNull(e)
        assertFalse(e is AgentSettingsTooOld)
    }

    private val gateway = ProviderInput(
        name = " Gateway ", kind = "custom", baseUrl = " https://gateway.example.com/v1 ", apiKey = " gw-key-0123 ",
        models = ProviderModels(default = "gateway-large", haiku = "gateway-small"), paid = true,
    )

    // ─── Providers ───

    @Test fun aNewProviderIsPostedAndKeptWithTheCheckItsKeyGot() {
        connect()
        answer("POST", "/v1/providers", "rest-provider-save")
        val saved = rig.call { rig.hub.saveProvider(null, gateway) }.getOrThrow()
        // What went: trimmed, the models by slot, paid said; never an id.
        val b = last("POST", "/v1/providers").json
        assertEquals("Gateway", b.getString("name"))
        assertEquals("custom", b.getString("kind"))
        assertEquals("https://gateway.example.com/v1", b.getString("baseUrl"))
        assertEquals("gw-key-0123", b.getString("apiKey"))
        assertEquals(JSONObject().put("default", "gateway-large").put("haiku", "gateway-small").toString(), b.getJSONObject("models").toString())
        assertTrue(b.getBoolean("paid"))
        assertFalse(b.has("id"))
        // What came back: the provider (in the hub's list at once), its check, the key kept.
        assertEquals("prov-gateway", saved.provider.id)
        assertEquals(ProviderCheck("ok", "Key works. 2 models.", listOf("gateway-large", "gateway-small"), null, 1_800_000_000_000L), saved.check)
        assertFalse(saved.keyDropped)
        assertEquals(listOf("prov-gateway"), rig.hub.providers.filter { !it.builtIn }.map { it.id })
    }

    @Test fun anEditIsAPutToItsIdAndSaysWhenTheKeyStayedBehind() {
        connect()
        answer("PUT", "/v1/providers/prov-gateway", "rest-provider-edit")
        val saved = rig.call { rig.hub.saveProvider("prov-gateway", gateway.copy(baseUrl = "https://other.example.com", apiKey = null)) }.getOrThrow()
        val b = last("PUT", "/v1/providers/prov-gateway").json
        // No new key typed: none sent (the saved one stays, or is dropped by the server on a new host).
        assertFalse(b.has("apiKey"))
        assertEquals("https://other.example.com", b.getString("baseUrl"))
        assertTrue(saved.keyDropped)
        assertFalse(saved.provider.hasKey)
        assertEquals(0, daemon.count("POST", "/v1/providers"))
    }

    @Test fun aRefusedProviderKeepsTheServersCodeForItsField() {
        connect()
        answer("POST", "/v1/providers", "rest-provider-refused", status = 400)
        val e = rig.call { rig.hub.saveProvider(null, gateway.copy(baseUrl = "http://gateway.example.com")) }.exceptionOrNull()
        assertTrue("a ProviderRefused, not $e", e is ProviderRefused)
        assertEquals("insecure_url", (e as ProviderRefused).code)
        assertEquals("The key would travel unencrypted. Use the https:// address.", e.message)
        assertTrue(rig.hub.providers.none { it.id == "prov-gateway" })
    }

    @Test fun checkWithoutSavingSendsWhatWasTypedAndReadsTheVerdict() {
        connect()
        answer("POST", "/v1/providers/check", "rest-provider-check")
        val c = rig.call { rig.hub.checkNewProvider("custom", " https://gateway.example.com ", "  ", "prov-gateway") }.getOrThrow()
        val b = last("POST", "/v1/providers/check").json
        assertEquals("custom", b.getString("kind"))
        assertEquals("https://gateway.example.com", b.getString("baseUrl"))
        // A blank key isn't sent: the server uses the saved one, at its own host only.
        assertFalse(b.has("apiKey"))
        assertEquals("prov-gateway", b.getString("id"))
        assertTrue(c.ok)
        assertEquals(listOf("gateway-large", "gateway-small"), c.models)
        // Refused: its code goes under the field.
        daemon.on("POST", "/v1/providers/check") { refusal(400, "missing_url", "A custom provider needs a base URL.") }
        val e = rig.call { rig.hub.checkNewProvider("custom", null, "k", null) }.exceptionOrNull() as ProviderRefused
        assertEquals("missing_url", e.code)
        assertFalse(last("POST", "/v1/providers/check").json.has("baseUrl"))
    }

    @Test fun aBalanceReadNowReplacesTheProviderInTheList() {
        daemon.snapshot = snapshot { put("providers", org.json.JSONArray().put(fixture("rest-providers").getJSONArray("providers").getJSONObject(1).put("balance", JSONObject.NULL))) }
        rig.connect()
        assertNull(rig.hub.providers.single { it.id == "prov-openrouter" }.balance)
        answer("POST", "/v1/providers/prov-openrouter/check", "rest-provider-balance")
        val p = rig.call { rig.hub.checkProvider("prov-openrouter") }.getOrThrow()
        assertEquals(Balance(4.2, 0.31, 37, 50, 1_800_000_000_000L, null), p.balance)
        assertEquals(p.balance, rig.hub.providers.single { it.id == "prov-openrouter" }.balance)
        assertEquals(1, rig.hub.providers.count { it.id == "prov-openrouter" })
    }

    @Test fun deleteTakesItOffTheListAndAFailureSaysTheServersWords() {
        daemon.snapshot = snapshot { put("providers", fixture("rest-providers").getJSONArray("providers")) }
        rig.connect()
        assertTrue(rig.hub.providers.any { it.id == "prov-gateway" })
        daemon.on("DELETE", "/v1/providers/prov-openrouter") { refusal(404, "no_provider", "That provider isn’t set up on this server.") }
        val e = rig.call { rig.hub.deleteProvider("prov-openrouter") }.exceptionOrNull()!!
        assertEquals("That provider isn’t set up on this server.", e.message)
        assertTrue(rig.hub.providers.any { it.id == "prov-openrouter" })
        answer("DELETE", "/v1/providers/prov-gateway", "rest-provider-delete")
        assertTrue(rig.call { rig.hub.deleteProvider("prov-gateway") }.isSuccess)
        assertEquals(1, daemon.count("DELETE", "/v1/providers/prov-gateway"))
        assertTrue(rig.hub.providers.none { it.id == "prov-gateway" })
    }

    // ─── The account ───

    @Test fun aCheckAppliesTheAccountItAnswersWith() {
        connect()
        assertTrue(rig.hub.account.ok)
        answer("POST", "/v1/account/check", "rest-account-check")
        assertTrue(rig.call { rig.hub.checkAccount() }.isSuccess)
        assertEquals(1, daemon.count("POST", "/v1/account/check"))
        assertEquals(Account.LOGGED_OUT, rig.hub.account.state)
        assertEquals("not_logged_in", rig.hub.account.reason)
    }

    @Test fun signingInOnThePhoneThenItsPageThenTheCodeThenCancel() {
        connect()
        answer("POST", "/v1/account/login", "rest-account-login")
        assertTrue(rig.call { rig.hub.signIn(onMac = false) }.isSuccess)
        assertEquals("phone", last("POST", "/v1/account/login").json.getString("where"))
        // Applied from the answer, before the socket's copy.
        assertEquals(Account.Login("starting", null, false, null), rig.hub.account.login)
        // The page to open, from the socket.
        val page = fixture("ws-account-sign-in")
        rig.push(page) { rig.hub.account.login?.state == "waiting_code" }
        assertEquals(page.getJSONObject("account").getJSONObject("login").getString("url"), rig.hub.account.login?.url)
        // The code as copied, trimmed.
        answer("POST", "/v1/account/login/code", "rest-account-login-code")
        assertTrue(rig.call { rig.hub.sendSignInCode("  AuthCode0123#FakeState1x  ") }.isSuccess)
        assertEquals("AuthCode0123#FakeState1x", last("POST", "/v1/account/login/code").json.getString("code"))
        // Cancel: the sign-in is off the phone at once, before the server's account arrives.
        answer("DELETE", "/v1/account/login", "rest-account-login-cancel")
        assertTrue(rig.call { rig.hub.cancelSignIn() }.isSuccess)
        assertEquals(1, daemon.count("DELETE", "/v1/account/login"))
        assertNull(rig.hub.account.login)
        assertEquals(Account.LOGGED_OUT, rig.hub.account.state)
    }

    @Test fun signingInOnTheServerSaysMac() {
        connect()
        daemon.ok("POST", "/v1/account/login", JSONObject().put("account", fixture("rest-account-login").getJSONObject("account").apply { getJSONObject("login").put("where", "mac") }))
        assertTrue(rig.call { rig.hub.signIn(onMac = true) }.isSuccess)
        assertEquals("mac", last("POST", "/v1/account/login").json.getString("where"))
        assertEquals(true, rig.hub.account.login?.onMac)
    }

    @Test fun signedInMeanwhileLooksAgainRatherThanFail() {
        connect()
        daemon.on("POST", "/v1/account/login") { refusal(409, "not_needed", "Claude Code is already signed in on this server.") }
        daemon.ok("POST", "/v1/account/check", JSONObject().put("account", JSONObject().put("state", "ok").put("plan", "max")))
        assertTrue(rig.call { rig.hub.signIn(onMac = false) }.isSuccess)
        assertEquals(1, daemon.count("POST", "/v1/account/check"))
        assertEquals("max", rig.hub.account.plan)
        assertTrue(rig.hub.account.ok)
    }

    @Test fun aCodeWithNoSignInRunningSaysTheServersWords() {
        connect()
        answer("POST", "/v1/account/login/code", "rest-account-login-code-refused", status = 409)
        val e = rig.call { rig.hub.sendSignInCode("AuthCode#state") }.exceptionOrNull()!!
        assertEquals("No sign-in is running. Start it again.", e.message)
    }

    // ─── The testing checklist, Forget ───

    @Test fun theChecklistIsItsPageOnTheRouteInUse() {
        connect()
        answer("GET", "/v1/qa", "rest-qa")
        // The server names its own loopback address; the phone opens it where it reaches the server.
        assertEquals("${daemon.base}/qa?k=qa-key", rig.call { rig.hub.checklist() }.getOrThrow())
        daemon.on("GET", "/v1/qa") { json(200, JSONObject()) }
        assertTrue(rig.call { rig.hub.checklist() }.isFailure)
    }

    @Test fun forgetAsksTheServerToForgetThisPhone() {
        connect()
        daemon.ok("DELETE", "/v1/devices/me", JSONObject().put("ok", true))
        assertTrue(rig.call { rig.hub.forget() }.isSuccess)
        assertEquals("Bearer tok-z2", last("DELETE", "/v1/devices/me").auth)
    }

    // ─── Statistics ───

    @Test fun statisticsAreAskedForTheirRangeAndKeptForAMoment() {
        connect()
        answer("GET", "/v1/stats", "rest-stats")
        val s = rig.call { StatsSource.load(rig.hub, StatsRange.Week) }.getOrThrow()
        val req = last("GET", "/v1/stats")
        assertEquals("range=week", req.query)
        assertEquals("Bearer tok-z2", req.auth)
        assertEquals(600_000L, s.claudeMs)
        assertEquals(30, s.days.size)
        // Shown at once next time, while a fresh one comes; another range isn't.
        assertEquals(s, StatsSource.cached(rig.hub, StatsRange.Week))
        assertTrue(StatsSource.fresh(rig.hub, StatsRange.Week))
        assertNull(StatsSource.cached(rig.hub, StatsRange.Month))
        rig.call { StatsSource.load(rig.hub, StatsRange.All) }
        assertEquals("range=all", last("GET", "/v1/stats").query)
    }

    @Test fun statisticsTheServerRefusesAreItsWords() {
        connect()
        answer("GET", "/v1/stats", "rest-stats-bad-range", status = 400)
        val e = rig.call { StatsSource.load(rig.hub, StatsRange.Today) }.exceptionOrNull() as ApiException
        assertEquals("bad_range", e.code)
        assertEquals("Ask for today, week, month, ytd or all.", e.serverMessage)
        assertTrue(e.refusedRange)
        assertNull(StatsSource.cached(rig.hub, StatsRange.Today))
    }
}
