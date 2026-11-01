@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.prudnikweb.hypernova.ui.screens

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
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
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
import com.github.takahirom.roborazzi.captureRoboImage
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentBoard
import com.prudnikweb.hypernova.data.Aside
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.DraftFile
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.Usage
import com.prudnikweb.hypernova.data.WAgent
import com.prudnikweb.hypernova.ui.components.EnterKeyPref
import com.prudnikweb.hypernova.ui.session.SessionScreen
import com.prudnikweb.hypernova.ui.session.SessionUsage
import com.prudnikweb.hypernova.ui.session.Todo
import com.prudnikweb.hypernova.ui.session.TodoStatus
import com.prudnikweb.hypernova.ui.session.TopBarChoice
import com.prudnikweb.hypernova.ui.session.TopBarItem
import com.prudnikweb.hypernova.ui.session.TopBarPref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
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

    @After fun tearDown() { AttachDrafts.take(BUSY) }

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

    // ─── MCP calls fold, named by server ───

    private fun pluralStr(id: Int, n: Int): String = context.resources.getQuantityString(id, n, n)

    /** A session asking Home Assistant about the kitchen: three MCP calls, the last still running while [live]. */
    private fun lights(live: Boolean): FakeHub {
        val ha = Samples.session("s-ha", "Kitchen lights", if (live) SessionState.Working else SessionState.Idle, 0, project = "home",
            activity = if (live) "mcp__home-assistant__ha_get_state: light.kitchen" else "Idle")
        fun call(k: String, tool: String, target: String, running: Boolean = false) = Item.Tool(k, tool, target, running = running, server = "home-assistant")
        val items = listOfNotNull(
            Item.User("u1", "Are the kitchen lights on?"),
            Item.Assistant("a1", "Let me check Home Assistant."),
            call("m1", "ha_search", "kitchen"),
            call("m2", "ha_get_state", "light.kitchen_counter"),
            call("m3", "ha_get_state", "light.kitchen", running = live),
            if (live) null else Item.Assistant("a2", "Both kitchen lights are on."),
        )
        return FakeHub(sessions = listOf(ha), approvals = emptyMap(), transcripts = mapOf("s-ha" to items))
    }

    @Test fun aLiveMcpRunFoldsWithItsLatestCallUnderIt() {
        chat(lights(live = true), "s-ha")
        rule.onNodeWithText(pluralStr(R.plurals.run_calls_now, 3).uppercase(), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("home-assistant ×3", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("ha_get_state · light.kitchen", useUnmergedTree = true).assertExists()
        // Folded: the calls' own rows aren't drawn.
        rule.onNodeWithText("light.kitchen_counter", useUnmergedTree = true).assertDoesNotExist()
        rule.snap("chat_mcp_live")
    }

    @Test fun aFinishedMcpRunReadsAsItsServer() {
        chat(lights(live = false), "s-ha")
        rule.onNodeWithText(pluralStr(R.plurals.run_calls_done, 3).uppercase(), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("home-assistant ×3", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("ha_get_state · light.kitchen", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("Both kitchen lights are on.", substring = true).assertExists()
        rule.snap("chat_mcp_done")
    }

    @Test fun aTapOpensTheFoldToEachCall() {
        chat(lights(live = false), "s-ha")
        rule.onNodeWithText("home-assistant ×3", useUnmergedTree = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("light.kitchen_counter", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("light.kitchen", useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("HA_GET_STATE", useUnmergedTree = true).assertCountEquals(2)
        rule.snap("chat_mcp_open")
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

    // ─── The status bar's room: the prompt box always has it ───

    private companion object {
        const val BUSY = "s-busy"
    }

    private val manyTodos = List(11) { Todo("t$it", "To-do $it of the turn's list", null, if (it < 10) TodoStatus.Done else TodoStatus.Active) }

    // Started "later" than now, so their clocks stay at 0s and the pictures hold still.
    private fun barAgent(n: Int) = WAgent(
        "a$n", BUSY, null, null, null, null, "Survey part $n", "Explore", null, true, "running", "Read: src/part$n.kt",
        Samples.NOW + 3_600_000, null, Samples.NOW, null, null, null,
    )

    /** A long turn: a long chat, six agents at work and eleven to-dos. */
    private fun busyHub(): FeatureHub {
        val s = Samples.session(BUSY, "Long turn", SessionState.Working, 0, activity = "Bash: npm test")
        val talk = (1..12).flatMap { listOf(Item.User("u$it", "Question number $it"), Item.Assistant("r$it", "Answer number $it")) }
        return FeatureHub(FakeHub(sessions = listOf(s), approvals = emptyMap(), transcripts = mapOf(BUSY to talk)))
            .apply { board = AgentBoard((1..6).map(::barAgent).associateBy { it.id }, emptyMap()) }
    }

    private lateinit var rootView: View

    /** The busy chat, its status bar opened; [fontScale] as the phone's font size. */
    private fun openedPanel(fontScale: Float = 1f) {
        val hub = busyHub()
        rule.setContent {
            rootView = LocalView.current
            Harness(spy) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    SessionScreen(hub, BUSY, onBack = {}, usageOf = { _, _ -> usage }, todosOf = { Result.success(manyTodos) })
                }
            }
        }
        rule.waitForIdle()
        rule.onNode(hasClickLabel(str(R.string.status_expand_main))).performClick()
        rule.waitForIdle()
    }

    /** The keyboard, [height] of it, as the system would report it to the window. */
    private fun keyboard(height: Dp) {
        rule.runOnUiThread {
            val px = (height.value * rootView.resources.displayMetrics.density).toInt()
            val ime = WindowInsetsCompat.Type.ime()
            val insets = WindowInsetsCompat.Builder().setInsets(ime, Insets.of(0, 0, 0, px)).setVisible(ime, true).build()
            rootView.dispatchApplyWindowInsets(insets.toWindowInsets())
        }
        rule.waitForIdle()
    }

    /**
     * A picture with the bar's clock in it, which reads the real time (0:00 or 0:01, by how slow the
     * machine is): allowed that much of a difference, a digit's pixels, and no more.
     */
    private fun snapPanel(name: String) {
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(
            "src/test/snapshots/$name.png",
            roborazziOptions = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(resultValidator = ThresholdValidator(0.001f))),
        )
    }

    private fun box() = rule.onNode(hasSetTextAction())
    private fun panelToggle() = rule.onNode(hasClickLabel(str(R.string.status_fold_main)))
    private fun panelList() = rule.onNode(hasScrollAction() and hasAnyDescendant(hasContentDescription("Explore", substring = true) or hasContentDescription("To-do", substring = true)))

    /** The box is whole, inside the window and above the keyboard, and the bar ends above it. */
    private fun assertBoxHasRoom(keyboard: Dp = 0.dp) {
        val window = rule.onRoot().getBoundsInRoot()
        val b = box().getUnclippedBoundsInRoot()
        assertTrue("the box's top ${b.top} is on screen", b.top >= 0.dp)
        assertTrue("the box's bottom ${b.bottom} is above the keyboard at ${window.bottom - keyboard}", b.bottom <= window.bottom - keyboard)
        assertTrue("the box is at least a line tall (${b.height})", b.height >= 20.dp)
        rule.onAllNodes(hasClickLabel(str(R.string.status_fold_main))).fetchSemanticsNodes().forEach {
            val barBottom = with(rule.density) { it.boundsInRoot.bottom.toDp() }
            assertTrue("the bar's row ends at $barBottom, above the box at ${b.top}", barBottom <= b.top)
        }
    }

    /** The list stops above the bar's progress line: 8dp clear, the 3dp line, 10dp and the 10dp between the card and the box's stack. */
    private fun assertListClearsTheLine() {
        val listBottom = panelList().getUnclippedBoundsInRoot().bottom
        assertTrue("the list ends at $listBottom, with the line under it, above the box", listBottom + 31.dp <= box().getUnclippedBoundsInRoot().top)
    }

    @Test fun anOpenPanelTakesAboutHalfOfTheRoomAndScrollsToItsLastRow() {
        openedPanel()
        assertBoxHasRoom()
        val headerBottom = rule.onNodeWithText("Long turn").getUnclippedBoundsInRoot().bottom
        val boxTop = box().getUnclippedBoundsInRoot().top
        val panelTop = panelToggle().getUnclippedBoundsInRoot().top
        assertTrue("half of the room or less: the panel starts at $panelTop, between $headerBottom and $boxTop", panelTop - headerBottom >= (boxTop - headerBottom) * 0.4f)
        assertListClearsTheLine()
        snapPanel("chat_status_panel_open")
        // The last to-do is reachable, whole, and stops short of the line.
        panelList().performScrollToNode(hasText("To-do 10", substring = true))
        rule.waitForIdle()
        val last = rule.onNodeWithText("To-do 10", substring = true, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("the last to-do ends at ${last.bottom}, in the list", last.bottom <= panelList().getUnclippedBoundsInRoot().bottom)
        assertListClearsTheLine()
        snapPanel("chat_status_panel_end")
    }

    @Test fun theKeyboardLeavesTheBoxAndSomeOfTheChat() {
        openedPanel()
        keyboard(320.dp)
        assertBoxHasRoom(320.dp)
        assertListClearsTheLine()
        val headerBottom = rule.onNodeWithText("Long turn").getUnclippedBoundsInRoot().bottom
        val boxTop = box().getUnclippedBoundsInRoot().top
        val panelTop = panelToggle().getUnclippedBoundsInRoot().top
        assertTrue("some chat: the panel starts at $panelTop, between $headerBottom and $boxTop", panelTop - headerBottom >= (boxTop - headerBottom) * 0.4f)
        snapPanel("chat_status_panel_keyboard")
        // Its row still folds and opens it.
        panelToggle().performClick()
        rule.waitForIdle()
        panelList().assertDoesNotExist()
        rule.onNode(hasClickLabel(str(R.string.status_expand_main))).performClick()
        rule.waitForIdle()
        panelList().assertExists()
        assertBoxHasRoom(320.dp)
    }

    @Config(qualifiers = "+h560dp")
    @Test fun aShortPhoneWithTheKeyboardKeepsTheBox() {
        openedPanel()
        keyboard(260.dp)
        assertBoxHasRoom(260.dp)
        snapPanel("chat_status_panel_short")
    }

    @Test fun aLargeFontKeepsTheBox() {
        openedPanel(fontScale = 1.3f)
        keyboard(320.dp)
        assertBoxHasRoom(320.dp)
        assertListClearsTheLine()
    }

    @Config(qualifiers = "w816dp-h392dp-land-hdpi")
    @Test fun landscapeWithTheKeyboardKeepsTheBox() {
        openedPanel()
        keyboard(190.dp)
        assertBoxHasRoom(190.dp)
        snapPanel("chat_status_panel_landscape")
    }

    /** Eight lines (the box shows six) and a file in its strip. */
    private fun growTheBox() {
        AttachDrafts.add(BUSY, DraftFile(AttachDrafts.nextId(), "shot.png", isImage = true).apply { failed = "no" })
        box().performTextInput((1..8).joinToString("\n") { "Line $it" })
        rule.waitForIdle()
    }

    @Test fun aGrownBoxWinsOverThePanel() {
        openedPanel()
        keyboard(320.dp)
        val oneLine = box().getUnclippedBoundsInRoot().height
        growTheBox()
        assertBoxHasRoom(320.dp)
        val grown = box().getUnclippedBoundsInRoot().height
        assertTrue("six lines' height, not cut for the panel: $oneLine became $grown", grown >= oneLine * 5.5f)
        snapPanel("chat_status_panel_box_grown")
    }

    @Config(qualifiers = "+h560dp")
    @Test fun aGrownBoxOnAShortPhoneStaysOnScreen() {
        openedPanel()
        keyboard(260.dp)
        growTheBox()
        assertBoxHasRoom(260.dp)
    }

    @Config(qualifiers = "w816dp-h392dp-land-hdpi")
    @Test fun aGrownBoxInLandscapeStaysOnScreen() {
        openedPanel(fontScale = 1.3f)
        keyboard(190.dp)
        growTheBox()
        assertBoxHasRoom(190.dp)
    }
}
