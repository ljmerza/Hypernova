package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingsFile
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.HubException
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionMcpReply
import com.prudnikweb.hypernova.data.SessionMcpServer
import com.prudnikweb.hypernova.data.SessionMcpTooOld
import com.prudnikweb.hypernova.ui.home.MenuActions
import com.prudnikweb.hypernova.ui.home.MenuOffer
import com.prudnikweb.hypernova.ui.home.SessionMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private class McpHub(private val base: FakeHub = FakeHub()) : Hub by base {
    var answer: Result<SessionMcpReply> = Result.failure(HubException("nothing yet"))
    var asked = mutableListOf<String>()
    override suspend fun sessionMcp(id: String): Result<SessionMcpReply> {
        asked += id
        return answer
    }
}

/** The Home card's menu › MCP servers (ui/home/SessionMcp.kt): who's offered it, and what the sheet says. */
@RunWith(RobolectricTestRunner::class)
class SessionMcpScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hub = McpHub()
    private val session: Session = Samples.sessions().first { it.id == Samples.ZETA }

    private fun reply(vararg servers: SessionMcpServer, forAll: Int = 0, hidden: Int = 0, state: String = AgentSettingsFile.OK) =
        SessionMcpReply(state, servers.toList(), forAll, hidden, 0)

    private val servers = arrayOf(
        SessionMcpServer("db-tools", "local", "stdio", "uvx", null, null),
        SessionMcpServer("docs", "project", "http", null, "mcp.example.com", null),
        SessionMcpServer("search", "project", "sse", null, null, "monorepo"),
        SessionMcpServer("odd", "project", null, null, null, null),
    )

    @Before fun setUp() = resetPrefs()

    private fun menu(s: Session = session) {
        rule.setContent { Harness(spy) { SessionMenu(hub, s, {}) } }
        rule.waitForIdle()
    }

    private fun openMcp() {
        menu()
        rule.onNodeWithText(str(R.string.as_mcp)).performClick()
        rule.waitForIdle()
    }

    private fun quantity(id: Int, n: Int) = RuntimeEnvironment.getApplication().resources.getQuantityString(id, n, n)
    private fun seen(text: String) = rule.onNodeWithText(text, substring = true).performScrollTo().assertExists()

    @Test fun offeredToAClaudeSessionWithAFolderNotToCodexOrOneWithout() {
        assertTrue(MenuOffer.of(session).mcp)
        assertTrue(MenuOffer.of(session).any)
        assertFalse(MenuOffer.of(session.copy(cwd = null)).mcp)
        assertFalse(MenuOffer.of(session.copy(cwd = " ")).mcp)
        assertFalse(MenuOffer.of(session.copy(agent = AgentKind.CODEX)).mcp)
    }

    @Test fun theMenuHasItAndCodexsDoesnt() {
        menu(session.copy(agent = AgentKind.CODEX))
        rule.onNodeWithText(str(R.string.as_mcp)).assertDoesNotExist()
        assertTrue(hub.asked.isEmpty())
    }

    /** The chat's session sheet lists the same actions: this isn't one of them there. */
    @Test fun theChatsSessionSheetDoesntHaveIt() {
        rule.setContent { Harness(spy) { MenuActions(hub, session, {}, {}) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.session_rename)).assertExists()
        rule.onNodeWithText(str(R.string.as_mcp)).assertDoesNotExist()
    }

    @Test fun theSheetListsEachServerWithWhatItMaySay() {
        hub.answer = Result.success(reply(*servers, forAll = 2, hidden = 1))
        openMcp()
        assertEquals(listOf(session.id), hub.asked)
        seen(session.name)
        seen("db-tools")
        seen(str(R.string.sm_local))
        seen("stdio · uvx")
        seen("docs")
        seen("http · mcp.example.com")
        // Nothing past the transport when that's all that passed, and which folder it's from.
        rule.onNodeWithText("sse").performScrollTo().assertExists()
        seen(str(R.string.sm_from, "monorepo"))
        // A kind the server doesn't name.
        rule.onNodeWithText(str(R.string.sm_other)).performScrollTo().assertExists()
        // The user's only as a count, and it says connection isn't known.
        seen(quantity(R.plurals.sm_for_all, 2))
        seen(quantity(R.plurals.as_hidden, 1))
        seen(str(R.string.sm_note))
        rule.snap("session_mcp_sheet")
    }

    @Test fun anEmptyFolderSaysSoAndStillCountsTheUsers() {
        hub.answer = Result.success(reply(forAll = 3))
        openMcp()
        seen(str(R.string.sm_empty))
        seen(quantity(R.plurals.sm_for_all, 3))
        rule.onNodeWithText(str(R.string.sm_note)).assertDoesNotExist()
    }

    @Test fun anUnreadableClaudeJsonSaysTheListMayBeShort() {
        hub.answer = Result.success(reply(*servers, state = AgentSettingsFile.MALFORMED))
        openMcp()
        seen(str(R.string.as_mcp_trouble))
    }

    @Test fun anOlderServerSaysItNeedsUpdatingWithNoTryAgain() {
        hub.answer = Result.failure(SessionMcpTooOld(str(R.string.sm_too_old)))
        openMcp()
        seen(str(R.string.sm_too_old))
        rule.onNodeWithText(str(R.string.try_again)).assertDoesNotExist()
    }

    @Test fun anotherFailureCanBeTriedAgain() {
        hub.answer = Result.failure(HubException("No network."))
        openMcp()
        seen("No network.")
        hub.answer = Result.success(reply(*servers))
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        rule.waitForIdle()
        assertEquals(2, hub.asked.size)
        seen("db-tools")
    }
}
