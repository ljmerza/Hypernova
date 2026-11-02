package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.ui.settings.McpServerScreen
import com.prudnikweb.hypernova.ui.settings.McpServersScreen
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
}
