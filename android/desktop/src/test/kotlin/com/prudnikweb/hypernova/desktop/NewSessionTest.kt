package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.graphics.toAwtImage
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.NewSessionMemory
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.inPane
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.platform
import com.prudnikweb.hypernova.desktop.DesktopRig.render
import com.prudnikweb.hypernova.desktop.DesktopRig.row
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import com.prudnikweb.hypernova.desktop.ui.LIST_TAG
import com.prudnikweb.hypernova.desktop.ui.PANE_TAG
import com.prudnikweb.hypernova.desktop.ui.Scaled
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** New session in the pane, through the real screen and hub. docs/DECISIONS.md, "The desktop client's New session". */
@OptIn(ExperimentalTestApi::class)
class NewSessionTest {
    private lateinit var d: FakeDaemon
    private val path = "/home/dev/invoices"
    private val other = "/home/dev/quillpad"

    private fun project(p: String, at: Long) = JSONObject().put("path", p).put("name", p.substringAfterLast('/')).put("lastActivityAt", at).put("sessionCount", 1)
    private fun projects() = JSONArray().put(project(path, 2_000)).put(project(other, 1_000))
    private fun started(title: String = "New one") = JSONObject().put("session", session("n1", state = "working", title = title) { put("projectName", "invoices") })
    private fun both(enabled: Boolean = true) = JSONObject().put("available", true).put("enabled", enabled)

    private fun world(more: JSONObject.() -> Unit = {}) = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = "An old one"))) {
        put("projects", projects())
        put("projectsRoot", "/home/dev")
        more()
    }

    @Before fun fresh() {
        DesktopRig.reset()
        NewSessionMemory.draft = ""
        platform.storage.prefs("hypernova").edit().clear().apply()
        d = FakeDaemon("z2", lan = true)
        d.snapshot = world()
        d.ok("POST", "/v1/sessions", started())
    }

    @After fun tearDown() {
        DesktopRig.reset()
        d.close()
    }

    private fun ComposeUiTest.up(scale: Float? = null) {
        setContent { if (scale != null) Scaled(scale) { App(shell, reduced = true) } else App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { Core.live?.sessions?.isNotEmpty() == true }
        waitForIdle()
    }

    private fun ComposeUiTest.open() {
        onNodeWithText(Ui.new_session.text()).performClick()
        waitForIdle()
        until("the box") { runCatching { box().assertIsFocused() }.isSuccess }
    }

    /** The prompt: the box of the pane, and on the folder pages the first there. */
    private fun ComposeUiTest.box() = onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(PANE_TAG)))

    private fun ComposeUiTest.press(word: String) {
        inPane(word).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.write(text: String) {
        box().performTextInput(text)
        waitForIdle()
    }

    private fun sent(): JSONObject = d.calls("POST", "/v1/sessions").last().json

    private fun ComposeUiTest.pictureOfTheCard(name: String) {
        waitForIdle()
        val dir = File(System.getProperty("hypernova.renders") ?: "build/renders").apply { mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    // ─── Starting ───

    @Test fun itOpensInThePaneOnTheLatestFolderAndStartWaitsForWords() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        inPane("~/invoices").assertExists()
        inPane(Ui.start.text()).assertIsNotEnabled()
        inPane(Ui.mode_title_default.text()).assertIsSelected()
        render("60-new-session")
        write("Fix the export")
        inPane(Ui.start.text()).assertIsEnabled()
    }

    @Test fun startSendsTheFolderPromptAndModeAndTheSessionIsChosen() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        write("Fix the export")
        press(Ui.start.text())
        until("it started") { d.count("POST", "/v1/sessions") == 1 }
        val b = sent()
        assertEquals("Fix the export", b.getString("prompt"))
        assertEquals(path, b.getString("projectPath"))
        assertEquals("default", b.getString("permissionMode"))
        assertFalse("nothing asked of the model", b.has("model"))
        assertFalse(b.has("agent"))
        until("the session") { Core.live?.session("n1") != null }
        waitForIdle()
        row("New one").assertIsSelected()
        inPane("New one").assertIsDisplayed()
        box().assertDoesNotExist()
        assertEquals("", NewSessionMemory.draft)
    }

    /** It goes as typed, the lines after a skill too, and the server makes them the skill's arguments (docs/FEATURES.md, N8). */
    @Test fun aSkillAndEveryLineAfterItGoTogether() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        write("/review-pr check the auth")
        box().performKeyInput { keyDown(Key.ShiftLeft); pressKey(Key.Enter); keyUp(Key.ShiftLeft) }
        waitForIdle()
        write("and the tests")
        press(Ui.start.text())
        until("it started") { d.count("POST", "/v1/sessions") == 1 }
        assertEquals("/review-pr check the auth\nand the tests", sent().getString("prompt"))
    }

    @Test fun enterStartsItAndTwoEntersStartOne() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        write("Fix the export")
        box().performKeyInput { pressKey(Key.Enter); pressKey(Key.Enter) }
        until("it started") { d.count("POST", "/v1/sessions") >= 1 }
        waitForIdle()
        assertEquals(1, d.count("POST", "/v1/sessions"))
    }

    @Test fun enterInAnEmptyBoxDoesNothing() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        box().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(0, d.count("POST", "/v1/sessions"))
    }

    @Test fun aRefusalIsTheServersWordsAndTheWordsStay() = runDesktopComposeUiTest(1200, 900) {
        d.on("POST", "/v1/sessions") { FakeDaemon.refusal(400, "bad_project", "That folder isn't there.") }
        up()
        open()
        write("Fix the export")
        press(Ui.start.text())
        until("the refusal") { runCatching { inPane("That folder isn't there.").assertExists() }.isSuccess }
        box().assertExists()
        inPane("Fix the export").assertExists()
    }

    @Test fun cancelKeepsTheWordsForNextTime() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        write("Half a thought")
        press(Ui.cancel.text())
        box().assertDoesNotExist()
        assertEquals("Half a thought", NewSessionMemory.draft)
        open()
        inPane("Half a thought").assertExists()
    }

    @Test fun escapePutsItAwayAndARowChosenDoesToo() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        box().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        box().assertDoesNotExist()
        open()
        row("An old one").performClick()
        waitForIdle()
        box().assertDoesNotExist()
        inPane("An old one").assertIsDisplayed()
    }

    @Test fun theArrowsMoveInTheBoxNotTheList() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        write("Two")
        box().performKeyInput { pressKey(Key.DirectionUp); pressKey(Key.DirectionDown) }
        waitForIdle()
        box().assertExists()
        assertEquals(null, shell.chosen(Core.activeId!!))
    }

    @Test fun nextTimeItOpensOnTheFolderLastUsed() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        press(Ui.ns_change.text())
        inPane("quillpad").performClick()
        waitForIdle()
        write("Contents")
        press(Ui.start.text())
        until("it started") { d.count("POST", "/v1/sessions") == 1 }
        assertEquals(other, sent().getString("projectPath"))
        until("the form gone") { !shell.newSession }
        waitForIdle()
        open()
        inPane("~/quillpad").assertExists()
    }

    // ─── Modes ───

    @Test fun bypassIsNotOfferedAndSaysWhere() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        inPane(Ui.mode_title_bypass.text()).assertDoesNotExist()
        inPane(Ui.desk_bypass_on_phone.text()).assertIsDisplayed()
        for (m in listOf(Ui.mode_title_default, Ui.mode_title_accept, Ui.mode_title_plan, Ui.mode_title_auto)) inPane(m.text()).assertExists()
    }

    @Test fun aMemoryOfBypassNeverStartsThere() = runDesktopComposeUiTest(1200, 900) {
        platform.storage.prefs("hypernova").edit().putString("newSession.startMode", "bypassPermissions").apply()
        up()
        open()
        write("Go")
        press(Ui.start.text())
        until("it started") { d.count("POST", "/v1/sessions") == 1 }
        assertEquals("default", sent().getString("permissionMode"))
    }

    @Test fun autoAsksToThinkTwiceFirstAndKeepAskingKeepsIt() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        press(Ui.mode_title_auto.text())
        onNodeWithText(Ui.think_ai_mistakes.text()).assertIsDisplayed()
        onNodeWithText(Ui.think_keep.text()).assertIsFocused()
        pictureOfTheCard("61-think-twice")
        onNodeWithText(Ui.think_keep.text()).performClick()
        waitForIdle()
        inPane(Ui.mode_title_default.text()).assertIsSelected()
        press(Ui.mode_title_auto.text())
        onNodeWithText(Ui.think_use_auto.text()).performClick()
        waitForIdle()
        inPane(Ui.mode_title_auto.text()).assertIsSelected()
        write("Go")
        press(Ui.start.text())
        until("it started") { d.count("POST", "/v1/sessions") == 1 }
        assertEquals("auto", sent().getString("permissionMode"))
    }

    @Test fun planNeedsNoSecondThought() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        press(Ui.mode_title_plan.text())
        inPane(Ui.mode_title_plan.text()).assertIsSelected()
        write("Go")
        press(Ui.start.text())
        until("it started") { d.count("POST", "/v1/sessions") == 1 }
        assertEquals("plan", sent().getString("permissionMode"))
    }

    // ─── Agents ───

    @Test fun whereOnlyClaudeCodeIsThereThereIsNoChoiceOfAgent() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        inPane(Ui.cx_agent_codex.text()).assertDoesNotExist()
    }

    @Test fun codexHasOnlyItsTwoModesAndIsNamed() = runDesktopComposeUiTest(1200, 900) {
        d.snapshot = world { put("codex", both()) }
        up()
        open()
        press(Ui.cx_agent_codex.text())
        inPane(Ui.cx_mode_read_only.text()).assertIsSelected()
        inPane(Ui.cx_mode_can_edit.text()).assertExists()
        inPane(Ui.mode_title_auto.text()).assertDoesNotExist()
        inPane(Ui.desk_bypass_on_phone.text()).assertDoesNotExist()
        press(Ui.cx_mode_can_edit.text())
        render("62-new-session-codex")
        write("Go")
        press(Ui.start.text())
        until("it started") { d.count("POST", "/v1/sessions") == 1 }
        val b = sent()
        assertEquals("codex", b.getString("agent"))
        assertEquals("workspace-write", b.getString("permissionMode"))
        assertFalse(b.has("providerId"))
    }

    @Test fun whereOnlyCodexIsThereItGoesToCodex() = runDesktopComposeUiTest(1200, 900) {
        d.snapshot = world { put("codex", both()).put("claude", JSONObject().put("available", false)) }
        up()
        open()
        inPane(Ui.claude.text()).assertDoesNotExist()
        write("Go")
        press(Ui.start.text())
        until("it started") { d.count("POST", "/v1/sessions") == 1 }
        assertEquals("codex", sent().getString("agent"))
        assertTrue(sent().getString("permissionMode") in listOf("read-only", "workspace-write"))
    }

    // ─── Folders ───

    @Test fun aNewProjectGoesByItsName() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        press(Ui.ns_change.text())
        inPane(Ui.new_project.text()).performClick()
        waitForIdle()
        val field = box()
        field.performTextInput("bad name!")
        waitForIdle()
        inPane(Ui.new_project_invalid.text()).assertIsDisplayed()
        inPane(Ui.ns_use_name.text()).assertIsNotEnabled()
        field.performTextClearance()
        field.performTextInput("ledger")
        waitForIdle()
        press(Ui.ns_use_name.text())
        inPane(Ui.new_project_creates.format("~/ledger").substringBefore("~"), substring = true).assertExists()
        write("Set it up")
        press(Ui.start.text())
        until("it started") { d.count("POST", "/v1/sessions") == 1 }
        val b = sent()
        assertEquals("ledger", b.getJSONObject("newProject").getString("name"))
        assertFalse(b.has("projectPath"))
    }

    @Test fun theFolderBrowserGoesInAndUsesTheOneItsOn() = runDesktopComposeUiTest(1200, 900) {
        d.on("GET", "/v1/folders") { req ->
            val inner = req.query.contains("deep") || req.query.contains("%2Fdeep")
            FakeDaemon.json(
                200,
                if (inner) JSONObject().put("path", "/home/dev/deep").put("parent", "/home/dev").put("home", "/home/dev").put("folders", JSONArray())
                else JSONObject().put("path", "/home/dev").put("home", "/home/dev").put("folders", JSONArray().put(JSONObject().put("name", "deep").put("path", "/home/dev/deep"))),
            )
        }
        up()
        open()
        press(Ui.ns_change.text())
        inPane(Ui.ns_browse.text()).performClick()
        waitForIdle()
        until("the folders") { runCatching { inPane("deep").assertExists() }.isSuccess }
        render("63-folder-browser")
        inPane("deep").performClick()
        until("inside it") { runCatching { inPane(Ui.ns_use_folder.format("deep")).assertExists() }.isSuccess }
        press(Ui.ns_use_folder.format("deep"))
        write("Go")
        press(Ui.start.text())
        until("it started") { d.count("POST", "/v1/sessions") == 1 }
        assertEquals("/home/dev/deep", sent().getString("projectPath"))
    }

    @Test fun theProjectsAreFoundByAFewLetters() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        press(Ui.ns_change.text())
        render("64-project-picker")
        box().performTextInput("quil")
        waitForIdle()
        inPane("quillpad").assertExists()
        inPane("invoices").assertDoesNotExist()
    }

    // ─── When it can't ───

    @Test fun whileTheServerIsAwayStartWaitsAndSaysSo() = runDesktopComposeUiTest(1200, 900) {
        up()
        open()
        write("Words")
        d.down = true
        d.closeSocket()
        until("away", timeoutMs = 90_000) { Core.live?.link?.state != com.prudnikweb.hypernova.data.LinkState.Connected }
        waitForIdle()
        inPane(Ui.start_mac_away.text()).assertExists()
        inPane(Ui.start.text()).assertIsNotEnabled()
    }

    @Test fun aSubscriptionAtRestSaysWhyAndNothingStartsElsewhereUnsaid() = runDesktopComposeUiTest(1200, 900) {
        d.snapshot = world { put("account", JSONObject().put("state", "logged_out")) }
        up()
        open()
        inPane(Ui.new_session_add_provider.format(Ui.why_signed_out.format("z2"))).assertExists()
    }

    // ─── Sizes ───

    @Test fun narrowIsOnePaneWithAWayBack() = runDesktopComposeUiTest(600, 600) {
        up()
        open()
        row("An old one").assertDoesNotExist()
        render("65-new-session-narrow")
        press(Ui.back.text())
        row("An old one").assertExists()
    }

    @Test fun theSmallestWindowStillStarts() = runDesktopComposeUiTest(520, 420) {
        up()
        open()
        write("Go")
        box().performKeyInput { pressKey(Key.Enter) }
        until("it started") { d.count("POST", "/v1/sessions") == 1 }
    }

    @Test fun a4kWindowAt2xHasBothPanes() = runDesktopComposeUiTest(3840, 2160) {
        up(scale = 2f)
        open()
        onNode(hasText("An old one") and hasAnyAncestor(hasTestTag(LIST_TAG))).assertIsDisplayed()
        render("66-new-session-4k")
    }
}
