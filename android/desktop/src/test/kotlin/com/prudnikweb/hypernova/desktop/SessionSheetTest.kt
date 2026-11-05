package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.choose
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import com.prudnikweb.hypernova.desktop.ui.SHEET_TAG
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The session sheet, the phone's own content, through the real screen over [FakeDaemon] on real mutual TLS: beside the chat in a wide window, over it in a narrow
 * one. Bypass is chosen each time, behind its think-twice card.
 */
@OptIn(ExperimentalTestApi::class)
class SessionSheetTest {
    private val shell get() = DesktopRig.shell
    private lateinit var d: FakeDaemon
    private val name = "Stream invoice exports"
    private fun word(key: Str) = DesktopStrings.getString(key)

    @Before fun fresh() {
        DesktopRig.reset()
        d = FakeDaemon("z2", lan = true)
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = name) { put("projectName", "invoices").put("cwd", "/home/dev/invoices").put("model", "claude-opus") }))
        d.ok("GET", "/v1/sessions/s1/usage", JSONObject().put("usage", JSONObject().put("model", "claude-opus").put("context", JSONObject().put("pct", 42).put("exact", true))))
    }

    @After fun tearDown() {
        DesktopRig.reset()
        d.close()
    }

    private fun ComposeUiTest.up() {
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { (Core.live?.sessions?.size ?: 0) >= 1 }
        waitForIdle()
        choose(name)
        waitForIdle()
    }

    private fun ComposeUiTest.openSheet() {
        val said = DesktopStrings.getString(Str.chat_context_said, 42)
        until("the strip") { runCatching { onNode(hasContentDescription(said, substring = true)).assertIsDisplayed() }.isSuccess }
        onNode(hasContentDescription(said, substring = true)).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.tab(key: Str) {
        onNodeWithText(word(key)).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.inSheet(text: String) = onNode(hasText(text) and hasAnyAncestor(hasTestTag(SHEET_TAG)))

    @Test fun aWideWindowHasItBesideTheChatAndItsTabsWork() = runDesktopComposeUiTest(1700, 1100) {
        up()
        openSheet()
        inSheet(word(Str.session_sheet_tab_overview)).assertIsDisplayed()
        tab(Str.session_sheet_title)
        inSheet(word(Str.session_sheet_id)).performScrollTo().assertIsDisplayed()
        // Both can be read: the chat's still here.
        onNodeWithText(word(Str.prompt_hint), substring = true).assertExists()
    }

    @Test fun aNarrowWindowHasItOverTheChatWithTheSameContent() = runDesktopComposeUiTest(900, 800) {
        up()
        openSheet()
        onNode(hasTestTag(SHEET_TAG)).assertDoesNotExist()
        onNodeWithText(word(Str.session_sheet_tab_overview)).assertIsDisplayed()
        onNodeWithText(word(Str.chat_close)).performClick()
        waitForIdle()
        onNodeWithText(word(Str.session_sheet_tab_overview)).assertDoesNotExist()
    }

    @Test fun theAppsMessageShowsOnACardOverTheWindowAndAClickPutsItAway() = runDesktopComposeUiTest(900, 800) {
        val words = "The terminal didn’t answer. Try again in a moment."
        val onTheCard = hasText(words) and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
        up()
        openSheet()
        shell.say(words)
        until("it's on the card") { runCatching { onNode(onTheCard).assertIsDisplayed() }.isSuccess }
        onNode(onTheCard).performClick()
        waitForIdle()
        onNode(onTheCard).assertDoesNotExist()
        assertEquals(null, shell.toast)
        onNodeWithText(word(Str.session_sheet_tab_overview)).assertIsDisplayed()
    }

    @Test fun closeAndEscapePutItAway() = runDesktopComposeUiTest(1700, 1100) {
        up()
        openSheet()
        inSheet(word(Str.chat_close)).performClick()
        waitForIdle()
        onNodeWithText(word(Str.session_sheet_tab_overview)).assertDoesNotExist()
        openSheet()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithText(word(Str.session_sheet_tab_overview)).assertDoesNotExist()
        // The session's still chosen: Escape only put the sheet away.
        assertEquals("s1", shell.chosen(Core.activeId.orEmpty()))
    }

    @Test fun bypassIsOfferedBehindItsThinkTwice() = runDesktopComposeUiTest(1700, 1100) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = name) { put("projectName", "invoices").put("cwd", "/home/me/invoices").put("bypassAvailable", true) }))
        up()
        openSheet()
        tab(Str.session_sheet_permissions)
        inSheet(word(Str.mode_bypass)).assertIsDisplayed()
    }

    @Test fun aRefusedModeChangeSaysWhyInTheSheet() = runDesktopComposeUiTest(1700, 1100) {
        val words = "The terminal didn’t answer. Try again in a moment."
        d.on("POST", "/v1/sessions/s1/mode") { FakeDaemon.refusal(409, "terminal_busy", words) }
        up()
        openSheet()
        tab(Str.session_sheet_permissions)
        inSheet(word(Str.mode_plan)).performClick()
        until("it says why") { runCatching { inSheet(words).assertIsDisplayed() }.isSuccess }
    }

    @Test fun aModeChangeGoesToTheServer() = runDesktopComposeUiTest(1700, 1100) {
        d.ok("POST", "/v1/sessions/s1/mode", JSONObject())
        up()
        openSheet()
        tab(Str.session_sheet_permissions)
        inSheet(word(Str.mode_plan)).performClick()
        until("the mode") { d.count("POST", "/v1/sessions/s1/mode") == 1 }
    }

    @Test fun anAgentsRowSaysItsOnThePhone() = runDesktopComposeUiTest(1700, 1100) {
        d.ok(
            "GET", "/v1/sessions/s1/agents",
            JSONObject().put("agents", JSONArray()).put("workflows", JSONArray()),
        )
        up()
        openSheet()
        tab(Str.session_sheet_agents)
        inSheet(word(Str.session_sheet_no_agents)).assertIsDisplayed()
    }

    @Test fun theMcpTabAsksTheServerAndSaysItsAnswer() = runDesktopComposeUiTest(1700, 1100) {
        up()
        openSheet()
        tab(Str.session_sheet_tab_mcp)
        until("the ask") { d.count("GET", "/v1/sessions/s1/mcp/live") > 0 }
    }
}
