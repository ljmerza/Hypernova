package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import com.prudnikweb.hypernova.ui.settings.McpEditScreen
import com.prudnikweb.hypernova.ui.settings.McpServerScreen
import com.prudnikweb.hypernova.ui.settings.McpServersScreen
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Answers by path, from JSON as the server sends it. Keeps every call. */
internal class ExtHub(private val base: FakeHub = FakeHub()) : Hub by base {
    val answers = linkedMapOf<String, Result<JSONObject>>()
    val calls = mutableListOf<Triple<String, String, JSONObject?>>()
    override suspend fun agentExt(method: String, path: String, body: JSONObject?, slow: Boolean): Result<JSONObject> {
        calls += Triple(method, path, body)
        return answers.entries.firstOrNull { (k, _) -> "$method $path".startsWith(k) }?.value ?: Result.failure(AgentSettingRefused("no_answer", "no answer for $method $path"))
    }
    fun on(prefix: String, json: String) { answers[prefix] = Result.success(JSONObject(json)) }
    fun fails(prefix: String, e: Throwable) { answers[prefix] = Result.failure(e) }
}

internal const val MCP_LIST = """{"agent":"claude","state":"ok","folders":[{"id":"home","name":"~"},{"id":"f1","name":"parser"}],"hidden":1,"foldersNotRead":0,"servers":[
 {"name":"github","scope":"user","transport":"stdio","summary":"npx"},
 {"name":"odd-one","scope":"settings","transport":"stdio","summary":"uvx"},
 {"name":"db-tools","scope":"local","folder":"f1","project":"parser","transport":"stdio","summary":"uvx"},
 {"name":"docs","scope":"project","folder":"f1","project":"parser","transport":"http","summary":"docs.example.com","approval":"pending"}]}"""
internal const val MCP_LIVE = """{"agent":"claude","folder":"home","servers":[
 {"name":"github","status":"connected","source":"user","serverName":"github-mcp","version":"2.1.0","tools":[{"name":"create_issue","destructive":true},{"name":"list_issues","readOnly":true}]},
 {"name":"plugin:context7:context7","status":"needs-auth","source":"plugin","tools":[],"setup":{"transport":"http","url":"https://mcp.example.com/mcp?key=•••","envNames":[],"headerNames":[],"hidden":0}},
 {"name":"broken","status":"failed","source":"user","error":"spawn ENOENT","tools":[]}]}"""
internal const val MCP_TOOLS = """{"agent":"claude","name":"github","serverName":"github-mcp","instructions":"Use it for issues.","moreTools":0,
 "tools":[{"name":"create_issue","description":"Opens an issue.","annotations":{"title":"Create issue","destructive":true,"idempotent":false},"params":[{"name":"title","type":"string","required":true,"description":"What to call it"},{"name":"labels","type":"array of string","required":false,"default":"[]","choices":["bug","idea"]}],"schema":"{\"type\":\"object\"}"},{"name":"list_issues","annotations":{"readOnly":true},"params":[]}],
 "prompts":[{"name":"triage","description":"Sorts issues.","args":["repo"]}],"resources":[{"name":"readme","uri":"gh://readme"}]}"""
internal const val MCP_PAGE = """{"agent":"claude","name":"github","scope":"user","where":"~/.claude.json","transport":"stdio","command":"npx","args":["-y","@acme/mcp","--token","•••"],"envNames":["GITHUB_TOKEN"],"headerNames":[],"hidden":0,"hash":"h1","editable":true}"""

/** Agents & providers › an agent's settings › MCP servers (ui/settings/McpScreens.kt). */
@RunWith(RobolectricTestRunner::class)
class McpScreensTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = ExtHub()
    private val opened = mutableListOf<String>()

    @Before fun setUp() = resetPrefs()

    private fun list() {
        rule.setContent { Harness(spy) { McpServersScreen(hub, "claude", onBack = {}, onOpen = { n, s, f -> opened += "$n $s $f" }) } }
        rule.waitForIdle()
    }

    private fun page(name: String = "github", scope: String = "user", folder: String? = "home") {
        rule.setContent { Harness(spy) { McpServerScreen(hub, "claude", name, scope, folder, onBack = {}) } }
        rule.waitForIdle()
    }

    private fun seen(text: String) = rule.onNodeWithText(text, substring = true).performScrollTo().assertExists()

    @Config(qualifiers = "+h1800dp")
    @Test fun theListGroupsByWhereItsDefinedAndSaysHowEachIsDoing() {
        hub.on("GET /v1/agents/mcp?", MCP_LIST)
        hub.on("GET /v1/agents/mcp/status", MCP_LIVE)
        list()
        seen("github")
        seen(str(R.string.mcp_s_connected))
        seen("db-tools")
        seen("docs")
        seen(str(R.string.mcp_s_waiting))
        seen("plugin:context7:context7")
        seen(str(R.string.mcp_s_needs_auth))
        rule.snap("mcp_list")
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aTapOpensThatServerWithItsScopeAndFolder() {
        hub.on("GET /v1/agents/mcp?", MCP_LIST)
        hub.on("GET /v1/agents/mcp/status", MCP_LIVE)
        list()
        rule.onNodeWithText("docs").performScrollTo().performClick()
        rule.onNodeWithText("plugin:context7:context7").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("docs project f1", "plugin:context7:context7 plugin home"), opened)
    }

    @Test fun theListStandsWithoutItsStatusAndSaysWhy() {
        hub.on("GET /v1/agents/mcp?", MCP_LIST)
        hub.fails("GET /v1/agents/mcp/status", AgentSettingRefused("sdk_missing", "Claude Code’s SDK isn’t installed."))
        list()
        seen("Claude Code’s SDK isn’t installed.")
        seen("github")
        rule.onAllNodes(androidx.compose.ui.test.hasText(str(R.string.mcp_s_unknown), substring = true)).onFirst().performScrollTo().assertExists()
    }

    @Test fun anOlderServerSaysSoWithoutAnOfferToTryAgain() {
        hub.fails("GET /v1/agents/mcp", AgentSettingsTooOld("This server needs updating."))
        list()
        seen("This server needs updating.")
        rule.onNodeWithText(str(R.string.try_again)).assertDoesNotExist()
    }

    @Test fun aChosenFolderAsksForThatFoldersStatus() {
        hub.on("GET /v1/agents/mcp?", MCP_LIST)
        hub.on("GET /v1/agents/mcp/status", MCP_LIVE)
        list()
        rule.onNodeWithText(str(R.string.mcp_folder)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("parser").performClick()
        rule.waitForIdle()
        assertTrue(hub.calls.any { it.second.contains("/status") && it.second.contains("folder=f1") })
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aServersPageSaysWhereHowItsStatusAndItsTools() {
        hub.on("GET /v1/agents/mcp/server", MCP_PAGE)
        hub.on("GET /v1/agents/mcp/status", MCP_LIVE)
        page()
        seen(str(R.string.mcp_scope_user_body))
        seen("~/.claude.json")
        seen("npx")
        seen("--token")
        seen("•••")
        seen("GITHUB_TOKEN")
        seen(str(R.string.mcp_values_stay))
        seen("github-mcp 2.1.0")
        seen("create_issue")
        rule.snap("mcp_server")
    }

    @Config(qualifiers = "+h2000dp")
    @Test fun aToolOpensToWhatItsForItsHintsAndItsParameters() {
        hub.on("GET /v1/agents/mcp/server", MCP_PAGE)
        hub.on("GET /v1/agents/mcp/status", MCP_LIVE)
        hub.on("GET /v1/agents/mcp/tools", MCP_TOOLS)
        page()
        seen("Opens an issue.")
        seen("Use it for issues.")
        seen("triage (repo)")
        seen("gh://readme")
        rule.onNodeWithText("create_issue").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onAllNodes(androidx.compose.ui.test.isRoot()).onLast().snap("mcp_tool")
        rule.onNodeWithText("Create issue").assertExists()
        rule.onNodeWithText(str(R.string.mcp_h_destructive)).assertExists()
        rule.onNodeWithText("What to call it").assertExists()
        rule.onNodeWithText("string · " + str(R.string.mcp_param_required)).assertExists()
        rule.onNodeWithText(str(R.string.mcp_param_default, "[]")).assertExists()
        rule.onNodeWithText("bug · idea").assertExists()
        rule.onNodeWithText(str(R.string.mcp_schema_show)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("""{"type":"object"}""").assertExists()
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun aServerThatCantBeAskedShowsTheNamesItHasAndSaysWhy() {
        hub.on("GET /v1/agents/mcp/server", MCP_PAGE)
        hub.on("GET /v1/agents/mcp/status", MCP_LIVE)
        hub.on("GET /v1/agents/mcp/tools", """{"agent":"claude","name":"github","whyNot":"approval","tools":[],"prompts":[],"resources":[]}""")
        page()
        seen("create_issue")
        seen(str(R.string.mcp_why_approval))
    }

    @Test fun aFailedServersPageSaysItsError() {
        hub.on("GET /v1/agents/mcp/server", MCP_PAGE.replace("\"github\"", "\"broken\""))
        hub.on("GET /v1/agents/mcp/status", MCP_LIVE)
        page("broken")
        seen(str(R.string.mcp_s_failed))
        seen("spawn ENOENT")
    }

    // ─── Adding and managing ───

    private fun lock(secure: Boolean = true, pass: Boolean = true) {
        org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceSecure(secure)
        OwnerCheck.prompt = { ok, fail -> if (pass) ok() else fail(null) }
    }
    @org.junit.After fun tearDown() { OwnerCheck.prompt = null }

    private fun form(name: String? = null, scope: String? = null, folder: String? = null, back: () -> Unit = {}) {
        rule.setContent { Harness(spy) { McpEditScreen(hub, "claude", name, scope, folder, onBack = back) } }
        rule.waitForIdle()
    }
    private fun type(label: String, text: String) = rule.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription, listOf(label))).performScrollTo().performTextInput(text)
    private fun tap(text: String) { rule.onNodeWithText(text).performScrollTo().performClick(); rule.waitForIdle() }
    private fun sent(method: String, path: String) = hub.calls.last { it.first == method && it.second.startsWith(path) }.third!!

    @Config(qualifiers = "+h1800dp")
    @Test fun addingAStdioServerShowsItsWholeCommandThenAsksForTheFingerprint() {
        hub.on("GET /v1/agents/mcp?", MCP_LIST)
        hub.on("POST /v1/agents/mcp/servers", """{"ok":true}""")
        lock()
        var left = false
        form(back = { left = true })
        type(str(R.string.mcp_f_name), "tiny")
        type(str(R.string.mcp_command), "npx")
        type(str(R.string.mcp_args), "-y\n@acme/mcp")
        tap(str(R.string.mcp_pair_add))
        type(str(R.string.mcp_pair_name), "API_TOKEN")
        type(str(R.string.mcp_pair_value), "the-secret")
        rule.onRoot().snap("mcp_add")
        tap(str(R.string.mcp_f_save))
        // Nothing yet: the confirm first, with the exact command.
        assertTrue(hub.calls.none { it.first == "POST" })
        val ask = spy.confirms.single()
        assertTrue(ask.body, ask.body.contains("npx -y @acme/mcp"))
        assertTrue(ask.danger)
        ask.onConfirm()
        rule.waitForIdle()
        val body = sent("POST", "/v1/agents/mcp/servers")
        assertEquals(true, body.getBoolean("confirmRun"))
        assertEquals("user", body.getString("scope"))
        assertEquals("the-secret", body.getJSONArray("env").getJSONObject(0).getString("value"))
        assertEquals("@acme/mcp", body.getJSONArray("args").getString(1))
        assertTrue(left)
    }

    @Test fun aCancelledFingerprintSendsNothing() {
        hub.on("GET /v1/agents/mcp?", MCP_LIST)
        lock(pass = false)
        form()
        type(str(R.string.mcp_f_name), "tiny")
        type(str(R.string.mcp_command), "npx")
        tap(str(R.string.mcp_f_save))
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertTrue(hub.calls.none { it.first == "POST" })
    }

    @Test fun aPhoneWithNoLockCantAddOne() {
        hub.on("GET /v1/agents/mcp?", MCP_LIST)
        lock(secure = false)
        form()
        type(str(R.string.mcp_f_name), "tiny")
        type(str(R.string.mcp_command), "npx")
        tap(str(R.string.mcp_f_save))
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertTrue(hub.calls.none { it.first == "POST" })
        assertEquals(listOf(str(R.string.check_no_lock)), spy.toasts)
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun anHttpServerNeedsNoFingerprintAndAProjectOneNamesItsFolder() {
        hub.on("GET /v1/agents/mcp?", MCP_LIST)
        hub.on("POST /v1/agents/mcp/servers", """{"ok":true}""")
        form()
        type(str(R.string.mcp_f_name), "web")
        tap(str(R.string.mcp_f_project))
        tap("parser")
        tap("HTTP")
        type(str(R.string.mcp_url), "https://mcp.example.com/mcp")
        tap(str(R.string.mcp_f_save))
        assertEquals(0, spy.confirms.size)
        val body = sent("POST", "/v1/agents/mcp/servers")
        assertEquals("project", body.getString("scope"))
        assertEquals("f1", body.getString("folder"))
        assertEquals("http", body.getString("transport"))
        assertFalse(body.has("confirmRun"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun editingShowsNamesAndMasksAndSendsThemBackAsTheyAreWithoutAFingerprint() {
        hub.on("GET /v1/agents/mcp?", MCP_LIST)
        hub.on("GET /v1/agents/mcp/server", MCP_PAGE)
        hub.on("PUT /v1/agents/mcp/servers", """{"ok":true}""")
        form("github", "user", "home")
        seen("GITHUB_TOKEN")
        seen(str(R.string.mcp_value_kept))
        tap(str(R.string.mcp_f_save))
        assertEquals(0, spy.confirms.size)
        val body = sent("PUT", "/v1/agents/mcp/servers")
        assertEquals("h1", body.getString("expected"))
        val pair = body.getJSONArray("env").getJSONObject(0)
        assertEquals("GITHUB_TOKEN", pair.getString("name"))
        assertTrue(pair.getBoolean("keep"))
        assertFalse(pair.has("value"))
        assertEquals("•••", body.getJSONArray("args").getString(3))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun changingWhatRunsAsksForTheFingerprintAndAFailureStaysOnTheForm() {
        hub.on("GET /v1/agents/mcp?", MCP_LIST)
        hub.on("GET /v1/agents/mcp/server", MCP_PAGE)
        hub.fails("PUT /v1/agents/mcp/servers", AgentSettingRefused("file_changed", "That server changed on the server."))
        lock()
        var left = false
        form("github", "user", "home", back = { left = true })
        type(str(R.string.mcp_command), "x")
        tap(str(R.string.mcp_f_save))
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        seen("That server changed on the server.")
        assertFalse(left)
    }

    private fun livePage(status: String, extra: String = "") {
        hub.on("GET /v1/agents/mcp/status", """{"folder":"f1","servers":[{"name":"docs","status":"$status","source":"project","tools":[]}]}""")
        hub.on("GET /v1/agents/mcp/tools", """{"name":"docs","whyNot":"approval","tools":[],"prompts":[],"resources":[]}""")
        hub.on("GET /v1/agents/mcp/server", """{"agent":"claude","name":"docs","scope":"project","folder":"f1","project":"parser","where":"parser/.mcp.json","transport":"stdio","command":"uvx","args":["docs-mcp"],"envNames":[],"headerNames":[],"hidden":0,"hash":"h2","editable":true$extra}""")
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun approvingAProjectsServerShowsWhatItRunsAndAsksForTheFingerprint() {
        livePage("waiting", ""","approval":"pending"""")
        hub.on("POST /v1/agents/mcp/approval", """{"ok":true,"approval":"approved"}""")
        lock()
        page("docs", "project", "f1")
        tap(str(R.string.mcp_approve))
        val ask = spy.confirms.single()
        assertTrue(ask.body, ask.body.contains("uvx docs-mcp"))
        ask.onConfirm()
        rule.waitForIdle()
        val b = sent("POST", "/v1/agents/mcp/approval")
        assertTrue(b.getBoolean("approved"))
        assertTrue(b.getBoolean("confirmRun"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun turningOneDownAsksNothing() {
        livePage("waiting", ""","approval":"pending"""")
        hub.on("POST /v1/agents/mcp/approval", """{"ok":true}""")
        page("docs", "project", "f1")
        tap(str(R.string.mcp_reject))
        assertEquals(0, spy.confirms.size)
        assertFalse(sent("POST", "/v1/agents/mcp/approval").getBoolean("approved"))
        assertFalse(sent("POST", "/v1/agents/mcp/approval").has("confirmRun"))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun turningOffTurningOnAndReconnectingNeedNoFingerprint() {
        hub.on("GET /v1/agents/mcp/server", MCP_PAGE)
        hub.on("GET /v1/agents/mcp/status", MCP_LIVE)
        hub.on("GET /v1/agents/mcp/tools", MCP_TOOLS)
        hub.on("POST /v1/agents/mcp/toggle", """{"ok":true}""")
        hub.on("POST /v1/agents/mcp/reconnect", """{"ok":true,"server":null}""")
        page()
        tap(str(R.string.mcp_reconnect))
        assertEquals("github", sent("POST", "/v1/agents/mcp/reconnect").getString("name"))
        tap(str(R.string.mcp_disable))
        assertFalse(sent("POST", "/v1/agents/mcp/toggle").getBoolean("enabled"))
        assertEquals("home", sent("POST", "/v1/agents/mcp/toggle").getString("folder"))
        assertEquals(0, spy.confirms.size)
        assertTrue(spy.toasts.contains(str(R.string.mcp_turned_off)))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun removingConfirmsThenSendsItsVersionAndARefusalIsSaid() {
        hub.on("GET /v1/agents/mcp/server", MCP_PAGE)
        hub.on("GET /v1/agents/mcp/status", MCP_LIVE)
        hub.on("GET /v1/agents/mcp/tools", MCP_TOOLS)
        hub.fails("POST /v1/agents/mcp/servers/remove", AgentSettingRefused("file_changed", "It changed."))
        page()
        tap(str(R.string.mcp_remove))
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertEquals("h1", sent("POST", "/v1/agents/mcp/servers/remove").getString("expected"))
        seen("It changed.")
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun signingInOpensThePageAndThePastedAddressFinishesIt() {
        hub.on("GET /v1/agents/mcp/server", """{"agent":"claude","name":"web","scope":"user","where":"~/.claude.json","transport":"http","url":"https://x.example/m","envNames":[],"headerNames":[],"hidden":0,"hash":"h3","editable":true}""")
        hub.on("GET /v1/agents/mcp/status", """{"folder":"home","servers":[{"name":"web","status":"needs-auth","source":"user","tools":[]}]}""")
        hub.on("GET /v1/agents/mcp/tools", """{"name":"web","whyNot":"needs_auth","tools":[],"prompts":[],"resources":[]}""")
        hub.on("POST /v1/agents/mcp/login/finish", """{"ok":true}""")
        hub.on("POST /v1/agents/mcp/login", """{"ok":true,"done":false,"login":"L1","url":"https://auth.example/authorize"}""")
        page("web")
        seen(str(R.string.mcp_why_auth))
        tap(str(R.string.mcp_sign_in))
        rule.onNodeWithText(str(R.string.mcp_login_open)).performClick()
        rule.waitForIdle()
        val opened = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals("https://auth.example/authorize", opened?.dataString)
        type(str(R.string.mcp_login_address), "http://localhost:1234/callback?code=c")
        rule.onNodeWithText(str(R.string.mcp_login_finish)).performClick()
        rule.waitForIdle()
        val b = sent("POST", "/v1/agents/mcp/login/finish")
        assertEquals("L1", b.getString("login"))
        assertEquals("http://localhost:1234/callback?code=c", b.getString("address"))
        assertTrue(spy.toasts.contains(str(R.string.mcp_signed_in)))
    }

    @Test fun addIsOfferedAndUndoSaysWhatItDoesAskingForTheCheckWhenItRunsSomething() {
        hub.on("GET /v1/agents/mcp?", MCP_LIST.replaceFirst("{", """{"undo":{"op":"remove","name":"github","scope":"user","folder":null,"needsCheck":true,"command":"npx -y @acme/mcp"},"""))
        hub.on("GET /v1/agents/mcp/status", MCP_LIVE)
        hub.on("POST /v1/agents/mcp/undo", """{"ok":true}""")
        lock()
        list()
        seen(str(R.string.mcp_add))
        seen(str(R.string.mcp_undo_remove, "github"))
        rule.onNodeWithText(str(R.string.mcp_undo_remove, "github"), substring = true).performClick()
        rule.waitForIdle()
        assertTrue(spy.confirms.single().body.contains("npx -y @acme/mcp"))
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertTrue(sent("POST", "/v1/agents/mcp/undo").getBoolean("confirmRun"))
    }

    // ─── Codex: one list, one scope ───

    private val codexList = """{"agent":"codex","state":"ok","folders":[{"id":"home","name":"~"}],"hidden":0,"foldersNotRead":0,"scopes":["user"],"undo":null,
      "servers":[{"name":"fetch","scope":"user","transport":"http","summary":"mcp.example.com","enabled":true}]}"""
    private val codexPage = """{"agent":"codex","name":"fetch","scope":"user","where":"~/.codex/config.toml","transport":"http","url":"https://mcp.example.com/m","envNames":[],"headerNames":["X-Key"],"hidden":0,"bearerEnv":"MY_TOKEN","toolsOff":["wipe"],"startupTimeout":20,"enabled":true,"authStatus":"not_logged_in","hash":"h9","editable":true}"""

    @Test fun anOldCodexIsSaidInPlainWordsWithBothVersions() {
        hub.fails("GET /v1/agents/mcp?", AgentSettingRefused("codex_too_old", "The Codex on the server is version 0.39.0. This needs 0.163.0 or newer, so update Codex there and try again."))
        rule.setContent { Harness(spy) { McpServersScreen(hub, "codex", onBack = {}, onOpen = { _, _, _ -> }) } }
        rule.waitForIdle()
        seen("version 0.39.0")
        seen("0.163.0 or newer")
        seen(str(R.string.try_again))
    }

    @Test fun codexHasNoFolderToPick() {
        hub.on("GET /v1/agents/mcp?", codexList)
        hub.on("GET /v1/agents/mcp/status", """{"folder":"home","servers":[{"name":"fetch","status":"connected","source":"user","tools":[]}]}""")
        rule.setContent { Harness(spy) { McpServersScreen(hub, "codex", onBack = {}, onOpen = { n, s, f -> opened += "$n $s $f" }) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.mcp_folder)).assertDoesNotExist()
        seen("fetch")
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun codexsPageSaysItsBearerVariableAndToolListsAndOffersItsSignIn() {
        hub.on("GET /v1/agents/mcp/server", codexPage)
        hub.on("GET /v1/agents/mcp/status", """{"folder":"home","servers":[{"name":"fetch","status":"needs-auth","source":"user","tools":[]}]}""")
        hub.on("GET /v1/agents/mcp/tools", """{"name":"fetch","whyNot":"needs_auth","tools":[],"prompts":[],"resources":[]}""")
        rule.setContent { Harness(spy) { McpServerScreen(hub, "codex", "fetch", "user", "home", onBack = {}) } }
        rule.waitForIdle()
        seen("~/.codex/config.toml")
        seen("MY_TOKEN")
        seen("wipe")
        seen("20 s")
        seen(str(R.string.mcp_sign_in))
        seen(str(R.string.mcp_actions_note_codex))
        rule.onRoot().snap("mcp_server_codex")
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun codexsFormHasNoScopeAndNoSseAndSendsItsBearerVariable() {
        hub.on("GET /v1/agents/mcp?", codexList)
        hub.on("POST /v1/agents/mcp/servers", """{"ok":true}""")
        rule.setContent { Harness(spy) { McpEditScreen(hub, "codex", null, null, null, onBack = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.mcp_f_project)).assertDoesNotExist()
        rule.onNodeWithText("SSE").assertDoesNotExist()
        type(str(R.string.mcp_f_name), "web")
        tap("HTTP")
        type(str(R.string.mcp_url), "https://x.example/m")
        type(str(R.string.mcp_bearer), "MY_TOKEN")
        rule.onNodeWithText(str(R.string.mcp_f_oauth)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.mcp_f_client_secret)).assertDoesNotExist()
        tap(str(R.string.mcp_f_save))
        val b = sent("POST", "/v1/agents/mcp/servers")
        assertEquals("codex", b.getString("agent"))
        assertEquals("user", b.getString("scope"))
        assertEquals("MY_TOKEN", b.getString("bearerEnv"))
        assertFalse(b.has("oauth"))
    }
}
