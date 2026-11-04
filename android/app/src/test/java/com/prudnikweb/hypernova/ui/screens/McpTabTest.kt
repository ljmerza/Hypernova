package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.ui.session.SessionSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val GET = "GET /v1/sessions/${Samples.ZETA}/mcp/live"
private const val POST = "POST /v1/sessions/${Samples.ZETA}/mcp/live"
private const val TOOLS = "GET /v1/sessions/${Samples.ZETA}/mcp/live/tools"

private fun row(name: String, status: String, actions: String, more: String = "") =
    """{"name":"$name","status":"$status","source":"local","toolCount":${if (status == "connected") 2 else "null"},"actions":[$actions],"signIn":false$more}"""
private val CONNECTED = row("many", "connected", "\"reconnect\",\"disable\"", ""","transport":"stdio","command":"uvx"""")
private val BROKEN = row("broken", "failed", "\"reconnect\",\"disable\"")
private fun reply(kind: String, vararg rows: String, live: Boolean = true, agent: String = "claude", more: String = "") =
    """{"agent":"$agent","kind":"$kind","live":$live,"folder":"f1","servers":[${rows.joinToString(",")}]$more}"""
private fun acted(action: String, name: String, outcome: String, said: String?, after: String) =
    """{"ok":true,"action":"$action","name":"$name","outcome":"$outcome",${if (said != null) "\"said\":\"$said\"," else ""}"server":null,"reply":$after}"""

/** The session sheet's MCP tab (ui/session/McpTab.kt): that it says what the server read back, each kind's words, and nothing that didn't happen. */
@RunWith(RobolectricTestRunner::class)
class McpTabTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = ExtHub()
    private val session = Samples.sessions().first { it.id == Samples.ZETA }

    @Before fun setUp() = resetPrefs()

    private fun open() {
        rule.setContent { Harness(spy) { SessionSheet(hub, session, usage = null, onModel = {}, onSwitchAgent = {}, onRetried = {}, onDismiss = {}) } }
        rule.waitForIdle()
        rule.onNode(hasText(str(R.string.session_sheet_tab_mcp)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performScrollTo().performClick()
        rule.waitForIdle()
    }
    private fun seen(text: String) = rule.onNodeWithText(text, substring = true, useUnmergedTree = true).performScrollTo().assertExists()
    /** A word that's also inside longer words on the tab. */
    private fun word(text: String) = rule.onNodeWithText(text, useUnmergedTree = true).performScrollTo().assertExists()
    private fun gone(text: String) = rule.onNodeWithText(text, substring = true, useUnmergedTree = true).assertDoesNotExist()
    private fun tap(text: String) {
        rule.onNodeWithText(text, useUnmergedTree = true).performScrollTo().performClick()
        rule.waitForIdle()
    }
    private fun asked(prefix: String) = hub.calls.filter { "${it.first} ${it.second}".startsWith(prefix) }
    /** What was asked of the session: the list, the action, the tools. */
    private val posts get() = asked(POST).map { it.third!!.getString("action") to it.third!!.getString("name") }

    @Config(qualifiers = "+h1800dp")
    @Test fun aTerminalSessionShowsEachServerWithItsStatusAndAsksOnce() {
        hub.on(GET, reply("terminal", CONNECTED, BROKEN, row("off", "disabled", "\"enable\""), row("auth", "needs-auth", "\"reconnect\",\"disable\"", ""","signIn":true""").replace(""""signIn":false,"signIn":true""", """"signIn":true""")))
        open()
        seen(str(R.string.smt_note_terminal))
        word("many"); word(str(R.string.mcp_s_connected)); seen("uvx"); word(str(R.string.smt_tools_count, 2))
        word("broken"); word(str(R.string.mcp_s_failed))
        word("off"); word(str(R.string.mcp_s_disabled)); word(str(R.string.mcp_enable))
        word("auth"); word(str(R.string.mcp_s_needs_auth)); word(str(R.string.mcp_sign_in))
        seen(str(R.string.open_terminal)); seen(str(R.string.smt_settings))
        assertEquals("the tab opens /mcp there once", 1, asked(GET).size)
        assertEquals("opening it changes nothing", 0, asked(POST).size)
        seen(str(R.string.smt_note_terminal))
        rule.snap("mcp_tab_terminal")
    }

    @Test fun reconnectSaysWhatWasReadBack() {
        hub.on(GET, reply("terminal", CONNECTED))
        hub.on(POST, acted("reconnect", "many", "done", "Successfully reconnected to many", reply("terminal", CONNECTED)))
        open()
        gone(str(R.string.smt_did_reconnect))
        tap(str(R.string.mcp_reconnect))
        assertEquals(listOf("reconnect" to "many"), posts)
        seen(str(R.string.smt_did_reconnect))
    }

    @Test fun aFailedReconnectSaysSoWithClaudeCodesWordsAndKeepsTheRowsItReadBack() {
        hub.on(GET, reply("terminal", CONNECTED))
        hub.on(POST, acted("reconnect", "many", "failed", "Failed to reconnect to many: CONNECTION_CLOSED", reply("terminal", row("many", "failed", "\"reconnect\",\"disable\""))))
        open()
        tap(str(R.string.mcp_reconnect))
        seen(str(R.string.smt_failed_reconnect)); seen("CONNECTION_CLOSED")
        word(str(R.string.mcp_s_failed))
        gone(str(R.string.smt_did_reconnect))
    }

    @Test fun disableAndEnableFollowTheStateReadBack() {
        hub.on(GET, reply("terminal", CONNECTED))
        hub.handler = { m, _, body ->
            if (m != "POST") null
            else if (body!!.getString("action") == "disable") Result.success(org.json.JSONObject(acted("disable", "many", "done", null, reply("terminal", row("many", "disabled", "\"enable\"")))))
            else Result.success(org.json.JSONObject(acted("enable", "many", "done", null, reply("terminal", CONNECTED))))
        }
        open()
        tap(str(R.string.mcp_disable))
        seen(str(R.string.smt_did_disable))
        word(str(R.string.mcp_s_disabled))
        gone(str(R.string.mcp_reconnect))
        tap(str(R.string.mcp_enable))
        seen(str(R.string.smt_did_enable))
        assertEquals(listOf("disable" to "many", "enable" to "many"), posts)
    }

    @Test fun whatIsntKnownToHaveHappenedIsntSaidToHave() {
        hub.on(GET, reply("terminal", CONNECTED))
        hub.on(POST, acted("reconnect", "many", "unconfirmed", null, reply("terminal", CONNECTED)))
        open()
        tap(str(R.string.mcp_reconnect))
        seen(str(R.string.smt_unconfirmed))
        gone(str(R.string.smt_did_reconnect))
    }

    @Test fun claudeCodesOwnWordsForAnotherSessionsOffAreSaid() {
        val words = "many is still available in this session, but another session disabled it"
        hub.on(GET, reply("terminal", row("many", "disabled", "\"enable\"")))
        hub.on(POST, acted("enable", "many", "no_change", words, reply("terminal", CONNECTED)))
        open()
        tap(str(R.string.mcp_enable))
        seen(words)
        gone(str(R.string.smt_did_enable))
    }

    @Test fun offForNewSessionsIsSaidAndKeepOnOnlySaysThat() {
        val off = row("many", "connected", "\"enable\",\"disable\"", ""","offForNew":true""")
        hub.on(GET, reply("terminal", off))
        hub.on(POST, acted("enable", "many", "new_sessions", null, reply("terminal", CONNECTED)))
        open()
        seen(str(R.string.smt_off_for_new))
        tap(str(R.string.smt_keep_on))
        seen(str(R.string.smt_new_sessions))
    }

    @Test fun aRefusalIsTheServersWords() {
        val words = "Claude is showing something in the terminal (a question or a menu). Open the terminal to answer it, then try again."
        hub.on(GET, reply("terminal", CONNECTED))
        hub.fails(POST, AgentSettingRefused("waiting", words))
        open()
        tap(str(R.string.mcp_reconnect))
        seen(words)
        seen(str(R.string.open_terminal))
    }

    @Test fun aTerminalThatCantBeReadSaysWhyWithNoRowsAndTheTerminalLink() {
        val words = "There’s unsent text in this session’s terminal on the server. Send or clear it there first."
        hub.on(GET, reply("terminal", more = ""","unread":{"code":"typing","words":"$words"}"""))
        open()
        seen(words)
        gone(str(R.string.smt_empty))
        tap(str(R.string.open_terminal))
        assertEquals("terminal:${Samples.ZETA}", spy.opened.last())
    }

    @Test fun aJobIsTheSameAsATerminal() {
        hub.on(GET, reply("job", CONNECTED))
        open()
        seen(str(R.string.smt_note_terminal))
        seen(str(R.string.mcp_reconnect))
        seen(str(R.string.open_terminal))
    }

    @Test fun inATurnItsThatTurnsConnections() {
        hub.on(GET, reply("turn", CONNECTED))
        open()
        seen(str(R.string.smt_note_turn))
        seen(str(R.string.mcp_reconnect))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun atRestItSaysNothingIsConnectedOffersNoReconnectAndCountsFromItsNextTurn() {
        val still = row("many", "connected", "\"disable\"")
        hub.on(GET, reply("rest", still, row("waits", "waiting", ""), live = false))
        hub.on(POST, acted("disable", "many", "next_turn", null, reply("rest", row("many", "disabled", "\"enable\""), live = false)))
        open()
        seen(str(R.string.smt_note_rest))
        gone(str(R.string.mcp_reconnect))
        seen(str(R.string.smt_held_rest))
        seen(str(R.string.smt_note_rest))
        rule.snap("mcp_tab_rest")
        tap(str(R.string.mcp_disable))
        seen(str(R.string.smt_next_turn_disable))
        gone(str(R.string.smt_did_disable))
    }

    @Test fun aTerminalNotSharedHasNoActionsAndSaysWhatTheListIs() {
        hub.on(GET, reply("watched", row("many", "connected", ""), live = false))
        open()
        seen(str(R.string.smt_note_watched))
        gone(str(R.string.mcp_reconnect)); gone(str(R.string.mcp_disable))
        seen(str(R.string.smt_settings))
    }

    @Test fun anEndedSessionHasNoActionsEither() {
        hub.on(GET, reply("ended", row("many", "connected", ""), live = false))
        open()
        seen(str(R.string.smt_note_ended))
        gone(str(R.string.mcp_reconnect)); gone(str(R.string.mcp_disable))
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun codexSaysWhatsDifferent() {
        hub.on(GET, reply("codex", row("fetch", "connected", "\"disable\""), row("down", "failed", "\"reconnect\""), agent = "codex"))
        hub.on(POST, acted("disable", "fetch", "done", null, reply("codex", row("fetch", "disabled", "\"enable\""), agent = "codex")))
        open()
        seen(str(R.string.smt_note_codex))
        rule.snap("mcp_tab_codex")
        tap(str(R.string.mcp_disable))
        seen(str(R.string.smt_did_disable_codex))
        tap(str(R.string.smt_settings))
        assertEquals("mcpServers:codex", spy.opened.last())
    }

    @Test fun aCodexThreadNotLoadedSaysSo() {
        hub.on(GET, reply("codex", row("fetch", "connected", "\"disable\""), live = false, agent = "codex"))
        hub.on(POST, acted("disable", "fetch", "next_turn", null, reply("codex", row("fetch", "disabled", "\"enable\""), live = false, agent = "codex")))
        open()
        seen(str(R.string.smt_note_codex_idle))
        tap(str(R.string.mcp_disable))
        seen(str(R.string.smt_next_turn_disable_codex))
    }

    @Test fun aNameTheTerminalCutGoesToTheTerminalInstead() {
        hub.on(GET, reply("terminal", row("srv-00-long…", "connected", "", ""","cut":true""")))
        open()
        seen(str(R.string.smt_cut))
        gone(str(R.string.mcp_reconnect)); gone(str(R.string.mcp_disable))
    }

    @Test fun aServerNotInTheSessionGoesToSettings() {
        hub.on(GET, reply("terminal", row("no", "rejected", "")))
        open()
        seen(str(R.string.smt_held))
        gone(str(R.string.mcp_reconnect))
        tap(str(R.string.smt_settings))
        assertEquals("mcpServers:claude", spy.opened.last())
    }

    @Test fun signingInThenReconnectingInTheSessionIsTheActionReadBack() {
        val auth = """{"name":"web","status":"needs-auth","source":"local","toolCount":null,"actions":["reconnect","disable"],"signIn":true}"""
        hub.on(GET, reply("terminal", auth))
        hub.on("POST /v1/agents/mcp/login/finish", """{"ok":true}""")
        hub.on("POST /v1/agents/mcp/login", """{"done":false,"login":"l1","url":"http://127.0.0.1/authorize"}""")
        hub.on(POST, acted("reconnect", "web", "done", null, reply("terminal", row("web", "connected", "\"reconnect\",\"disable\""))))
        open()
        tap(str(R.string.mcp_sign_in))
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("http://localhost:1234/callback?code=x")
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.mcp_login_finish), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals("the login was for this folder", "f1", asked("POST /v1/agents/mcp/login").first().third!!.getString("folder"))
        assertEquals("signing in doesn't reach the session by itself", listOf("reconnect" to "web"), posts)
        seen(str(R.string.smt_did_reconnect))
    }

    @Test fun signingInAtRestSaysItsNextTurnAndAsksNoSessionToReconnect() {
        val auth = """{"name":"web","status":"needs-auth","source":"local","toolCount":null,"actions":["disable"],"signIn":true}"""
        hub.on(GET, reply("rest", auth, live = false))
        hub.on("POST /v1/agents/mcp/login/finish", """{"ok":true}""")
        hub.on("POST /v1/agents/mcp/login", """{"done":false,"login":"l1","url":"http://127.0.0.1/authorize"}""")
        open()
        tap(str(R.string.mcp_sign_in))
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("http://localhost:1234/callback?code=x")
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.mcp_login_finish), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertTrue(posts.isEmpty())
        assertEquals("it looks again", 2, asked(GET).filter { !it.second.contains("/tools") }.size)
    }

    @Test fun itsToolsOpenFromItsCountAndAskOnlyThen() {
        hub.on(TOOLS, """{"name":"many","live":true,"tools":[{"name":"echo","readOnly":true},{"name":"wipe","destructive":true}]}""")
        hub.on(GET, reply("terminal", CONNECTED))
        open()
        assertEquals("reading them opens screens in the terminal", 0, asked(TOOLS).size)
        tap(str(R.string.smt_tools_count, 2))
        assertEquals(1, asked(TOOLS).size)
        assertTrue(asked(TOOLS).first().second.endsWith("?name=many"))
        seen("echo"); seen(str(R.string.mcp_a_read_only))
        seen("wipe"); seen(str(R.string.mcp_a_destructive))
        tap("wipe")
        seen(str(R.string.smt_tool_names))
    }

    @Test fun toolsThatCameWithTheAnswerNeedNoAsking() {
        hub.on(GET, reply("turn", """{"name":"many","status":"connected","source":"user","toolCount":1,"tools":[{"name":"echo"}],"actions":["reconnect","disable"],"signIn":false}"""))
        open()
        tap(str(R.string.smt_one_tool))
        seen("echo")
        assertEquals(0, asked(TOOLS).size)
    }

    @Test fun anOlderServerSaysItNeedsUpdatingAndOffersNoRetry() {
        hub.fails(GET, AgentSettingsTooOld(str(R.string.as_edit_too_old)))
        open()
        seen(str(R.string.as_edit_too_old))
        gone(str(R.string.try_again))
        gone(str(R.string.smt_settings))
    }

    @Test fun anotherFailureCanBeTriedAgain() {
        hub.fails(GET, AgentSettingRefused("no_session", "That session no longer exists."))
        open()
        seen("That session no longer exists.")
        hub.on(GET, reply("terminal", CONNECTED))
        tap(str(R.string.try_again))
        word("many")
    }

    @Test fun checkAgainReadsAfresh() {
        hub.on(GET, reply("watched", row("many", "connected", ""), live = false))
        open()
        assertFalse(asked(GET).last().second.contains("fresh"))
        tap(str(R.string.smt_check))
        assertTrue(asked(GET).last().second.contains("fresh=1"))
    }

    @Test fun noServersIsSaid() {
        hub.on(GET, reply("terminal"))
        open()
        seen(str(R.string.smt_empty))
    }

    @Config(qualifiers = "w320dp-h800dp")
    @Test fun theTabRowScrollsToItOnANarrowPhone() {
        hub.on(GET, reply("terminal", CONNECTED))
        open()
        seen(str(R.string.smt_seam).uppercase())
        rule.snap("mcp_tab_narrow")
    }
}
