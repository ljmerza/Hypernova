package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
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
import com.prudnikweb.hypernova.data.DeleteKind
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.choose
import com.prudnikweb.hypernova.desktop.DesktopRig.inPane
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.render
import com.prudnikweb.hypernova.desktop.DesktopRig.row
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import com.prudnikweb.hypernova.desktop.ui.Scaled
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/** The phone's session menu in the pane, through the real screen and hub. docs/DECISIONS.md, "The desktop client's Remove from list and Rename". */
@OptIn(ExperimentalTestApi::class)
class SessionActionsTest {
    private lateinit var d: FakeDaemon

    private val resting = "Table of contents in PDFs"
    private val working = "Stream invoice exports"
    private val job = "A background job"
    private val held = "In a shared terminal"
    private val elsewhere = "In some other terminal"
    private val ended = "Long finished"

    private fun sessions() = listOf(
        session("s1", state = "idle", title = resting) { put("projectName", "quillpad") },
        session("s2", state = "working", title = working) { put("projectName", "invoices") },
        session("s3", state = "working", title = job) { put("agentJob", "job-3").put("agentRunning", true) },
        session("s4", state = "idle", title = held) { put("term", JSONObject().put("id", "t4")) },
        session("s5", state = "idle", title = elsewhere) { put("origin", "terminal") },
        session("s6", state = "ended", title = ended),
    )

    @Before fun fresh() {
        DesktopRig.reset()
        d = FakeDaemon("z2", lan = true)
        d.snapshot = snapshot("z2", sessions = sessions())
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

    private fun ComposeUiTest.press(word: String) {
        onNodeWithText(word).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.pictureOfTheCard(name: String) {
        waitForIdle()
        val dir = File(System.getProperty("hypernova.renders") ?: "build/renders").apply { mkdirs() }
        ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }

    // ─── What's offered ───

    @Test fun atRestItHasRenameAndRemoveAndNoStop() = runDesktopComposeUiTest(1200, 900) {
        up()
        choose(resting)
        inPane(Ui.session_rename.text()).assertIsDisplayed()
        inPane(Ui.session_remove.text()).assertIsDisplayed()
        inPane(Ui.session_stop.text()).assertDoesNotExist()
        render("50-actions")
    }

    @Test fun atWorkAndNoJobItHasOnlyRename() = runDesktopComposeUiTest(1200, 900) {
        up()
        choose(working)
        inPane(Ui.session_rename.text()).assertIsDisplayed()
        inPane(Ui.session_remove.text()).assertDoesNotExist()
        inPane(Ui.session_stop.text()).assertDoesNotExist()
    }

    /** Rename types /rename into the session (daemon/src/http.ts), which would resume it: History's pane starts nothing. */
    @Test fun inHistoryItHasNoneOfThem() = runDesktopComposeUiTest(1200, 900) {
        up()
        press(Ui.history.text())
        choose(ended)
        inPane(ended).assertIsDisplayed()
        inPane(Ui.session_rename.text()).assertDoesNotExist()
        inPane(Ui.session_remove.text()).assertDoesNotExist()
        inPane(Ui.session_stop.text()).assertDoesNotExist()
    }

    // ─── Stop ───

    @Test fun stopGoesAtOnceForAJobAndARefusalIsTheServersWords() = runDesktopComposeUiTest(1200, 900) {
        d.on("POST", "/v1/sessions/s3/stop") { FakeDaemon.refusal(409, "nope", "It won't stop.") }
        up()
        choose(job)
        press(Ui.session_stop.text())
        until("the stop") { d.count("POST", "/v1/sessions/s3/stop") == 1 }
        until("the refusal") { shell.toast != null }
        assertEquals("It won't stop.", shell.toast)
    }

    // ─── Remove from list ───

    @Test fun removeAsksFirstInThePhonesWordsAndCancelSendsNothing() = runDesktopComposeUiTest(1200, 900) {
        up()
        choose(resting)
        press(Ui.session_remove.text())
        onNode(hasText(Ui.session_delete_q.format(resting))).assertIsDisplayed()
        onNode(hasText(Ui.session_delete_body_plain.text())).assertIsDisplayed()
        // The way out has the focus, so a stray Enter removes nothing.
        onNodeWithText(Ui.cancel.text()).assertIsFocused()
        pictureOfTheCard("51-remove-asks")
        press(Ui.cancel.text())
        assertEquals(0, d.count("DELETE", "/v1/sessions/s1"))
        row(resting).assertExists()
    }

    @Test fun escapePutsTheQuestionAway() = runDesktopComposeUiTest(1200, 900) {
        up()
        choose(resting)
        press(Ui.session_remove.text())
        onNodeWithText(Ui.cancel.text()).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNode(hasText(Ui.session_delete_q.format(resting))).assertDoesNotExist()
        assertEquals(0, d.count("DELETE", "/v1/sessions/s1"))
    }

    @Test fun removingMovesItToHistoryAndThePaneSaysWhereItWent() = runDesktopComposeUiTest(1200, 900) {
        d.ok("DELETE", "/v1/sessions/s1", JSONObject().put("session", session("s1", state = "ended", title = resting) { put("projectName", "quillpad") }))
        up()
        choose(resting)
        press(Ui.session_remove.text())
        press(Ui.session_remove_short.text())
        until("the remove") { d.count("DELETE", "/v1/sessions/s1") == 1 }
        until("ended") { Core.live?.session("s1")?.phase == Phase.Ended }
        waitForIdle()
        row(resting).assertDoesNotExist()
        inPane(resting).assertIsDisplayed()
        inPane(Ui.desk_pane_history_only.text()).assertIsDisplayed()
        press(Ui.history.text())
        row(resting).assertExists()
    }

    @Test fun aTerminalItHoldsSaysItCloses() = runDesktopComposeUiTest(1200, 900) {
        up()
        choose(held)
        press(Ui.session_remove.text())
        onNode(hasText(Ui.session_delete_body_terminal.text())).assertIsDisplayed()
    }

    @Test fun aRunningJobSaysItStops() = runDesktopComposeUiTest(1200, 900) {
        up()
        choose(job)
        press(Ui.session_remove.text())
        onNode(hasText(Ui.session_delete_body_running.text())).assertIsDisplayed()
    }

    @Test fun aTerminalItDoesntHoldSaysWhyAndSendsNothing() = runDesktopComposeUiTest(1200, 900) {
        up()
        choose(elsewhere)
        press(Ui.session_remove.text())
        onNode(hasText(Ui.session_delete_cant_q.format(elsewhere))).assertIsDisplayed()
        onNode(hasText(Ui.session_delete_cant_body.text())).assertIsDisplayed()
        onNodeWithText(Ui.session_remove_short.text()).assertDoesNotExist()
        press(Ui.fl_ok.text())
        assertEquals(0, d.count("DELETE", "/v1/sessions/s5"))
    }

    @Test fun aRefusalToRemoveIsTheServersWords() = runDesktopComposeUiTest(1200, 900) {
        d.on("DELETE", "/v1/sessions/s1") { FakeDaemon.refusal(409, "worktree", "Unpushed work in its worktree.") }
        up()
        choose(resting)
        press(Ui.session_remove.text())
        press(Ui.session_remove_short.text())
        until("the refusal") { shell.toast != null }
        assertEquals("Unpushed work in its worktree.", shell.toast)
        row(resting).assertExists()
    }

    /** Every kind's body, by the phone's own rule ([DeleteKind]), and the same word for word as ui/home/SessionMenu.kt's. */
    @Test fun eachKindHasThePhonesBody() {
        val words = mapOf(
            DeleteKind.Codex to Ui.session_delete_body_codex, DeleteKind.RunningJob to Ui.session_delete_body_running,
            DeleteKind.Terminal to Ui.session_delete_body_terminal, DeleteKind.Forget to Ui.session_delete_body_plain,
            DeleteKind.Job to Ui.session_delete_body,
        )
        for ((kind, word) in words) assertEquals(kind.name, word.text(), removeBody(kind))
    }

    // ─── Rename ───

    @Test fun renameStartsFromTheOldNameAndSavesTheNew() = runDesktopComposeUiTest(1200, 900) {
        d.ok("POST", "/v1/sessions/s1/rename", JSONObject().put("session", session("s1", state = "idle", title = "Contents") { put("projectName", "quillpad") }))
        up()
        choose(resting)
        press(Ui.session_rename.text())
        onNodeWithText(Ui.save.text()).assertIsNotEnabled()
        render("52-rename")
        val field = onNode(hasSetTextAction())
        field.performTextClearance()
        field.performTextInput("  Contents ")
        waitForIdle()
        onNodeWithText(Ui.save.text()).assertIsEnabled().performClick()
        until("the rename") { d.count("POST", "/v1/sessions/s1/rename") == 1 }
        assertEquals("Contents", d.calls("POST", "/v1/sessions/s1/rename").last().json.getString("name"))
        until("the new name") { Core.live?.session("s1")?.name == "Contents" }
        waitForIdle()
        row("Contents").assertExists()
        onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun aRefusedRenameStaysOpenWithTheServersWords() = runDesktopComposeUiTest(1200, 900) {
        d.on("POST", "/v1/sessions/s1/rename") { FakeDaemon.refusal(409, "typing", "There's text in its box.") }
        up()
        choose(resting)
        press(Ui.session_rename.text())
        onNode(hasSetTextAction()).performTextInput("x")
        waitForIdle()
        press(Ui.save.text())
        until("the refusal") { runCatching { inPane("There's text in its box.").assertExists() }.isSuccess }
        onNode(hasSetTextAction()).assertExists()
    }

    @Test fun inAnEmptyNameEnterSavesNothingAndEscapeLeaves() = runDesktopComposeUiTest(1200, 900) {
        up()
        choose(resting)
        press(Ui.session_rename.text())
        val field = onNode(hasSetTextAction())
        field.performTextClearance()
        field.performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(0, d.count("POST", "/v1/sessions/s1/rename"))
        field.performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNode(hasSetTextAction()).assertDoesNotExist()
        inPane(resting).assertIsDisplayed()
    }

    // ─── Rule 3 ───

    /** Nothing here reaches Allow: it stays beside the whole command. */
    @Test fun allowStaysOnlyBesideTheWholeCommand() = runDesktopComposeUiTest(1200, 900) {
        d.snapshot = snapshot(
            "z2", sessions = listOf(session("s9", state = "needs_you", title = "An edit")),
            approvals = listOf(approval("a1", "s9", summary = "Edit: /p/a.kt") { put("toolName", "Edit").put("input", JSONObject().put("file_path", "/p/a.kt").put("old_string", "a").put("new_string", "b")) }),
        )
        up()
        choose("An edit")
        inPane(Ui.deny.text()).assertIsDisplayed()
        onNodeWithText(Ui.allow.text()).assertDoesNotExist()
        inPane(Ui.session_rename.text()).assertIsDisplayed()
    }

    @Test fun narrowAnd4kHaveThemToo() = runDesktopComposeUiTest(3840, 2160) {
        up(scale = 2f)
        choose(resting)
        press(Ui.session_remove.text())
        pictureOfTheCard("53-remove-asks-4k")
        press(Ui.cancel.text())
        assertTrue(d.count("DELETE", "/v1/sessions/s1") == 0)
    }

    @Test fun theSmallestWindowReachesThem() = runDesktopComposeUiTest(520, 420) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = resting)))
        up()
        choose(resting)
        inPane(Ui.session_remove.text()).assertExists()
    }
}
