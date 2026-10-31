@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Aside
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.data.Usage
import com.prudnikweb.hypernova.ui.components.EnterKeyPref
import com.prudnikweb.hypernova.ui.session.SessionScreen
import com.prudnikweb.hypernova.ui.session.SessionUsage
import com.prudnikweb.hypernova.ui.session.TopBarChoice
import com.prudnikweb.hypernova.ui.session.TopBarItem
import com.prudnikweb.hypernova.ui.session.TopBarPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A session's chat (ui/session/SessionScreen.kt) with a fake hub: its rows, its asks, the box and the top bar. */
@RunWith(RobolectricTestRunner::class)
class SessionScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private var backs = 0

    @Before fun setUp() = resetPrefs()

    /** What the Mac says of the session's usage (the strip's numbers): 42% of the context, Opus 5.5. */
    private val usage = SessionUsage(
        model = "claude-opus-5-5", modelName = "Opus 5.5", contextUsed = 84_000, contextWindow = 200_000, contextPct = 42, contextExact = true,
        input = 12_000, output = 3_400, cacheRead = 60_000, cacheWrite = 8_000, agentTokens = 0, costUsd = null, costExact = false,
    )

    private fun chat(hub: FakeHub, id: String, withUsage: Boolean = true) {
        rule.setContent {
            Harness(spy) {
                SessionScreen(hub, id, onBack = { backs++ }, usageOf = { _, _ -> if (withUsage) usage else null })
            }
        }
        rule.waitForIdle()
    }

    // ─── The rows ───

    @Config(qualifiers = "+h2000dp")
    @Test fun messagesRenderEachAsItsKind() {
        chat(FakeHub(), Samples.BUILD)
        rule.onNodeWithText("The build fails on main, can you fix it?", substring = true).assertExists()
        rule.onNodeWithText("I'll look at the failing test first.", substring = true).assertExists()
        // The Read row, the error line in the terminal and the diff card each name the file.
        rule.onAllNodesWithText("parser.ts", substring = true, useUnmergedTree = true).assertCountEquals(3)
        rule.onNodeWithText("npm run build", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("/effort", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Fixed the type. Running the tests now.", substring = true).assertExists()
        // The header: its name and project.
        rule.onNodeWithText("Fix the build").assertExists()
        rule.snap("chat_rows")
    }

    // ─── The approval card ───

    @Test fun approvalCardAllowAnswersIt() {
        val hub = FakeHub()
        chat(hub, Samples.BUILD)
        rule.onNodeWithText(str(R.string.deny)).assertExists()
        rule.onNodeWithText(str(R.string.always)).assertExists()
        rule.onNodeWithText(str(R.string.allow)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("answer(${Samples.BUILD}, ap-test, Allow)"), hub.calls)
        rule.snap("chat_approval")
    }

    @Test fun approvalCardDenyAnswersIt() {
        val hub = FakeHub()
        chat(hub, Samples.BUILD)
        rule.onNodeWithText(str(R.string.deny)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("answer(${Samples.BUILD}, ap-test, Deny)"), hub.calls)
    }

    @Test fun alwaysOpensTheAlwaysSheetWithTheSuggestedRule() {
        val hub = FakeHub()
        chat(hub, Samples.BUILD)
        rule.onNodeWithText(str(R.string.always)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.always_save)).assertExists()
        rule.onNodeWithText("Bash(npm test *)", substring = true, useUnmergedTree = true).assertExists()
        // Nothing is answered until the rule is saved (and the owner checked).
        assertTrue(hub.calls.isEmpty())
        rule.snap("chat_always_sheet")
    }

    // ─── Claude Code's own dialog in the terminal ───

    @Test fun terminalAskCardAllowPressesYes() {
        val hub = FakeHub()
        chat(hub, Samples.DEPLOY)
        rule.onNodeWithText("kubectl get pods -n prod", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Yes, and don't ask again for kubectl get", substring = true, useUnmergedTree = true).assertExists()
        rule.snap("chat_terminal_ask")
        rule.onNodeWithText(str(R.string.allow)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("answerMenu(${Samples.DEPLOY}, m-deploy, 0, false)"), hub.calls)
    }

    @Test fun terminalAskCardOpensTheTerminal() {
        chat(FakeHub(), Samples.DEPLOY)
        rule.onNodeWithText(str(R.string.menu_open_terminal)).performClick()
        assertEquals(listOf("terminal:${Samples.DEPLOY}"), spy.opened)
    }

    // ─── /btw, a side question ───

    @Test fun btwAsksAsideAndItsCardShows() {
        // A session of its own: side questions are kept app-wide (Asides), and would show in other tests' sheets.
        val btw = Samples.session("s-btw", "Side questions", com.prudnikweb.hypernova.data.SessionState.Idle, 2).copy(agentJob = "j-btw", agentRunning = true)
        val hub = FakeHub(sessions = listOf(btw)).apply { asideAnswer = "The flags lived in config.ts." }
        chat(hub, btw.id)
        rule.onNode(hasSetTextAction()).performTextInput("/btw where were the flags?")
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("askAside(s-btw, where were the flags?)"), hub.calls)
        rule.onNodeWithText("The flags lived in config.ts.", substring = true, useUnmergedTree = true).assertExists()
        rule.snap("chat_btw_aside")
    }

    // ─── The box's Enter key, as Settings has it ───

    @Test fun enterSendsWhenTheSettingIsSend() {
        val hub = FakeHub()
        chat(hub, Samples.ZETA)
        rule.onNode(hasSetTextAction()).performTextInput("ship it")
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("send(${Samples.ZETA}, ship it)"), hub.calls)
    }

    @Test fun enterIsANewLineWhenTheSettingIsNewLine() {
        EnterKeyPref.set(context, EnterKey.NewLine)
        val hub = FakeHub()
        chat(hub, Samples.ZETA)
        rule.onNode(hasSetTextAction()).performTextInput("ship it")
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertTrue(hub.calls.none { it.startsWith("send(") })
    }

    // ─── The top bar, as Settings → Session top bar has it ───

    private fun strip() = rule.onNode(hasContentDescription(str(R.string.usage_name_seven), substring = true))

    private fun stripSaid(): String = strip().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString(" ")

    @Test fun topBarShowsTheDefaultItems() {
        chat(FakeHub(), Samples.ZETA)
        val said = stripSaid()
        assertTrue(said, said.contains(str(R.string.usage_name_five)))
        assertTrue(said, said.contains(str(R.string.usage_name_seven)))
        assertFalse(said, said.contains(str(R.string.meta_model_said, "Opus 5.5")))
        rule.snap("chat_top_bar_default")
    }

    @Test fun topBarHidesFiveHoursWhenNotReported() {
        chat(FakeHub(usage = Samples.usage(fiveHourReported = false)), Samples.ZETA)
        val said = stripSaid()
        assertFalse(said, said.contains(str(R.string.usage_name_five)))
        assertTrue(said, said.contains(str(R.string.usage_name_seven)))
        rule.snap("chat_top_bar_no_five")
    }

    @Test fun topBarFollowsTheChosenItemsAndOrder() {
        // The model on and first; the 5-hour window off.
        val choice = TopBarChoice.DEFAULT.set(TopBarItem.MODEL, true).set(TopBarItem.FIVE_HOUR, false).let { c ->
            (0 until c.order.indexOf(TopBarItem.MODEL)).fold(c) { acc, _ -> acc.move(TopBarItem.MODEL, -1) }
        }
        TopBarPref.set(context, choice)
        chat(FakeHub(), Samples.ZETA)
        val said = stripSaid()
        assertTrue(said, said.startsWith(str(R.string.meta_model_said, "Opus 5.5")))
        assertFalse(said, said.contains(str(R.string.usage_name_five)))
        rule.snap("chat_top_bar_model_first")
    }

    @Test fun topBarOpensTheSessionSheet() {
        chat(FakeHub(), Samples.ZETA)
        strip().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.session_sheet_tab_overview)).assertExists()
    }

    @Test fun backLeaves() {
        chat(FakeHub(), Samples.ZETA)
        rule.onNode(hasContentDescription(str(R.string.back))).performClick()
        assertEquals(1, backs)
    }
}
