package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.choose
import com.prudnikweb.hypernova.desktop.DesktopRig.inPane
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.row
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import com.prudnikweb.hypernova.desktop.ui.LIST_TAG
import com.prudnikweb.hypernova.desktop.ui.Scaled
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The list and the chosen session beside it, through the real screen and hub, over real mutual TLS on loopback. docs/DECISIONS.md, "The desktop client's two panes". */
@OptIn(ExperimentalTestApi::class)
class PanesTest {
    private lateinit var d: FakeDaemon
    private val others = mutableListOf<FakeDaemon>()

    private val migrate = "Migrate the payments table"
    private val stream = "Stream invoice exports"
    private val toc = "Table of contents in PDFs"
    private val command = "npm run db:migrate -- --env staging"

    private fun sessions() = listOf(
        session("s1", state = "needs_you", title = migrate) { put("projectName", "ledgerline").put("activity", "Bash: npm run") },
        session("s2", state = "working", title = stream) {
            put("projectName", "invoices").put("cwd", "/home/dev/invoices").put("activity", "Bash: gh run watch").put("model", "claude-opus-5-5").put("agentsRunning", 2)
            put("todo", JSONObject().put("active", "Running the soak test").put("done", 3).put("total", 7))
        },
        session("s3", state = "idle", title = toc) { put("projectName", "quillpad").put("activity", "Done. Headings link to their pages.") },
    )

    private fun allowable() = approval("a1", "s1", summary = "Bash: npm run") { put("toolName", "Bash").put("input", JSONObject().put("command", command)) }

    @Before fun fresh() {
        DesktopRig.reset()
        d = FakeDaemon("z2", lan = true)
        d.snapshot = snapshot("z2", sessions = sessions(), approvals = listOf(allowable()))
    }

    @After fun tearDown() {
        DesktopRig.reset()
        d.close()
        others.forEach { it.close() }
    }

    private fun ComposeUiTest.up(scale: Float? = null) {
        setContent { if (scale != null) Scaled(scale) { App(shell, reduced = true) } else App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { (Core.live?.sessions?.size ?: 0) >= 3 }
        waitForIdle()
    }

    private fun ComposeUiTest.key(k: Key) {
        onRoot().performKeyInput { pressKey(k) }
        waitForIdle()
    }

    // ─── Choosing ───

    @Test fun nothingChosenSaysSoAndOffersNothing() = runDesktopComposeUiTest(1200, 800) {
        up()
        inPane(Ui.desk_pane_none.text()).assertIsDisplayed()
        onNodeWithText(Ui.allow.text()).assertDoesNotExist()
        onNodeWithText(Ui.deny.text()).assertDoesNotExist()
        row(migrate).assertIsNotSelected()
    }

    @Test fun choosingShowsThatSessionAndAnotherReplacesIt() = runDesktopComposeUiTest(1200, 800) {
        up()
        choose(stream)
        row(stream).assertIsSelected()
        inPane(stream).assertIsDisplayed()
        inPane("INVOICES").assertIsDisplayed()
        inPane("Running the soak test 3/7").assertIsDisplayed()
        inPane("/home/dev/invoices").assertIsDisplayed()
        inPane("Opus 5.5").assertIsDisplayed()
        inPane(Ui.agents_running.format(2)).assertIsDisplayed()
        inPane(Ui.state_working.text().uppercase()).assertIsDisplayed()
        // And says what isn't here, rather than a panel with nothing in it.
        inPane(Ui.desk_pane_phone_only.text()).assertIsDisplayed()
        inPane(Ui.desk_pane_none.text()).assertDoesNotExist()
        onNodeWithText(Ui.allow.text()).assertDoesNotExist()

        choose(toc)
        row(toc).assertIsSelected()
        row(stream).assertIsNotSelected()
        inPane(toc).assertIsDisplayed()
        inPane(stream).assertDoesNotExist()
    }

    @Test fun theListKeepsItsGroups() = runDesktopComposeUiTest(1200, 800) {
        up()
        choose(stream)
        val top = { t: String -> onNode(hasText(t) and hasAnyAncestor(hasTestTag(LIST_TAG))).getBoundsInRoot().top }
        val needs = top(Ui.group_needs.text().uppercase())
        val working = top(Ui.group_working.text().uppercase())
        val done = top(Ui.group_done.text().uppercase())
        assertTrue(needs < working && working < done)
    }

    @Test fun allowIsBesideTheWholeCommandAndAnswersTheChosenOne() = runDesktopComposeUiTest(1200, 800) {
        d.snapshot = snapshot(
            "z2",
            sessions = sessions() + session("s4", state = "needs_you", title = "Another ask"),
            approvals = listOf(allowable(), approval("a4", "s4", summary = "Bash: rm") { put("toolName", "Bash").put("input", JSONObject().put("command", "rm -rf build")) }),
        )
        d.ok("POST", "/v1/approvals/a1")
        up()
        // Nothing to press beside a row, whose line can be cut.
        onNodeWithText(Ui.allow.text()).assertDoesNotExist()
        row(migrate).assertExists()
        choose(migrate)
        inPane("$ $command").assertIsDisplayed()
        inPane(Ui.allow.text()).performClick()
        until("the answer") { d.count("POST", "/v1/approvals/a1") == 1 }
        assertEquals("allow", d.calls("POST", "/v1/approvals/a1").last().json.getString("decision"))
        assertEquals("the other one's still waiting", 0, d.count("POST", "/v1/approvals/a4"))
    }

    @Test fun aLongCommandIsWholeInThePaneAndCutInTheRow() = runDesktopComposeUiTest(1000, 900) {
        val long = "echo " + (1..30).joinToString(" ") { "word$it" }
        d.snapshot = snapshot("z2", sessions = sessions(), approvals = listOf(approval("a1", "s1", summary = "Bash: echo") { put("toolName", "Bash").put("input", JSONObject().put("command", long)) }))
        up()
        choose(migrate)
        inPane("$ $long").assertIsDisplayed()
        inPane(Ui.allow.text()).assertIsDisplayed()
        // The same words on one line of the row, however many the pane takes.
        val inRow = row(long).getBoundsInRoot()
        val whole = inPane("$ $long").getBoundsInRoot()
        assertTrue("a row is three short lines: ${inRow.bottom - inRow.top}", inRow.bottom - inRow.top < 130.dp)
        assertTrue("the command wraps: ${whole.bottom - whole.top}", whole.bottom - whole.top > 40.dp)
    }

    @Test fun whatCantBeReadWholeSaysWhyInThePane() = runDesktopComposeUiTest(1200, 800) {
        d.snapshot = snapshot("z2", sessions = sessions(), approvals = listOf(approval("a1", "s1", summary = "Edit: /p/a.kt") { put("toolName", "Edit").put("input", JSONObject().put("file_path", "/p/a.kt").put("old_string", "a").put("new_string", "b")) }))
        up()
        choose(migrate)
        inPane(Ui.desk_not_whole.text()).assertIsDisplayed()
        inPane(Ui.deny.text()).assertIsDisplayed()
        onNodeWithText(Ui.allow.text()).assertDoesNotExist()
    }

    @Test fun moreThanOneAskSaysHowManyMore() = runDesktopComposeUiTest(1200, 800) {
        d.snapshot = snapshot(
            "z2", sessions = sessions(),
            approvals = listOf(allowable(), approval("a2", "s1", summary = "Bash: ls") { put("toolName", "Bash").put("input", JSONObject().put("command", "ls")) }),
        )
        up()
        choose(migrate)
        inPane(Ui.desk_more_asks.format(1)).assertIsDisplayed()
    }

    @Test fun whatStopsItIsSaid() = runDesktopComposeUiTest(1200, 800) {
        d.snapshot = snapshot(
            "z2",
            sessions = sessions() + session("s5", state = "idle", title = "Resting") {
                put("blocked", JSONObject().put("kind", "limit").put("text", "You've hit your session limit").put("resetsAt", System.currentTimeMillis() + 3_600_000))
            },
        )
        up()
        choose("Resting")
        inPane(Ui.state_paused.text().uppercase()).assertIsDisplayed()
        inPane("You've hit your session limit").assertIsDisplayed()
    }

    // ─── The list stays put ───

    private fun many() = (1..30).map { session("m%02d".format(it), state = "working", title = "Session number %02d".format(it)) { put("activity", "Bash: step $it") } }

    /** The rows showing, and where. A list keeps a row or so past its edges that nobody can see. */
    private fun ComposeUiTest.boundsOf(names: List<String>): Map<String, Rect> =
        names.filter { n -> runCatching { row(n).assertIsDisplayed() }.isSuccess }.associateWith { row(it).getBoundsInRoot().let { b -> Rect(b.left.value, b.top.value, b.right.value, b.bottom.value) } }

    @Test fun choosingDoesntMoveARow() = runDesktopComposeUiTest(1200, 700) {
        d.snapshot = snapshot("z2", sessions = many())
        up()
        onNode(hasScrollToIndexAction()).performScrollToIndex(12)
        waitForIdle()
        val names = (1..30).map { "Session number %02d".format(it) }
        val before = boundsOf(names)
        assertTrue("some rows on screen: ${before.keys}", before.size in 3..12)
        // The last one showing too, by what shows of it: a row half under the edge stays where it is.
        for (name in listOf(before.keys.elementAt(1), before.keys.elementAt(before.size / 2), before.keys.last())) {
            row(name).performMouseInput { click(Offset(width / 2f, 4f)) }
            waitForIdle()
            inPane(name).assertExists()
            assertEquals("after choosing $name", before, boundsOf(names))
        }
    }

    @Test fun aRowsHeightIsTheSameChosenOrNot() = runDesktopComposeUiTest(1200, 800) {
        up()
        val before = boundsOf(listOf(migrate, stream, toc))
        choose(stream)
        assertEquals(before, boundsOf(listOf(migrate, stream, toc)))
    }

    @Test fun nothingTheOtherSessionsDoTouchesTheChoice() = runDesktopComposeUiTest(1200, 800) {
        up()
        choose(toc)
        // Another session comes to need an answer: the pane stays on what was chosen, and no Allow appears.
        d.push(msg("session") { put("session", session("s2", state = "needs_you", title = stream)) })
        d.push(msg("approval") { put("approval", approval("a2", "s2", summary = "Bash: ls") { put("toolName", "Bash").put("input", JSONObject().put("command", "ls")) }) })
        until("the other ask") { Core.live?.approval("s2") != null }
        waitForIdle()
        row(toc).assertIsSelected()
        inPane(toc).assertIsDisplayed()
        onNodeWithText(Ui.allow.text()).assertDoesNotExist()
    }

    @Test fun theChosenOneChangingGroupIsFollowed() = runDesktopComposeUiTest(1200, 800) {
        up()
        choose(stream)
        d.push(msg("session") { put("session", session("s2", state = "needs_you", title = stream)) })
        d.push(msg("approval") { put("approval", approval("a2", "s2", summary = "Bash: ls") { put("toolName", "Bash").put("input", JSONObject().put("command", "ls -l")) }) })
        until("its ask") { Core.live?.approval("s2") != null }
        waitForIdle()
        row(stream).assertIsSelected()
        inPane("$ ls -l").assertIsDisplayed()
        inPane(Ui.allow.text()).assertIsDisplayed()
    }

    // ─── It goes ───

    @Test fun theChosenOneVanishingSaysSoAndChoosesNothingElse() = runDesktopComposeUiTest(1200, 800) {
        up()
        choose(migrate)
        inPane(Ui.allow.text()).assertIsDisplayed()
        d.push(msg("session.removed") { put("id", "s1") })
        until("it gone") { Core.live?.session("s1") == null }
        waitForIdle()
        inPane(Ui.desk_pane_gone.text()).assertIsDisplayed()
        inPane(Ui.desk_pane_gone_body.text()).assertIsDisplayed()
        onNodeWithText(Ui.allow.text()).assertDoesNotExist()
        row(stream).assertIsNotSelected()
        row(toc).assertIsNotSelected()
        // And another can be chosen.
        choose(toc)
        inPane(toc).assertIsDisplayed()
        inPane(Ui.desk_pane_gone.text()).assertDoesNotExist()
    }

    @Test fun whenEverySessionGoesItStillSaysWhatHappened() = runDesktopComposeUiTest(1200, 800) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = "The only one")))
        up0()
        choose("The only one")
        d.push(msg("session.removed") { put("id", "s1") })
        until("it gone") { Core.live?.session("s1") == null }
        waitForIdle()
        inPane(Ui.desk_pane_gone.text()).assertIsDisplayed()
        onNodeWithText(Ui.desk_no_sessions.text()).assertIsDisplayed()
    }

    private fun ComposeUiTest.up0() {
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        until("the session") { (Core.live?.sessions?.size ?: 0) >= 1 }
        waitForIdle()
    }

    @Test fun noSessionsAtAllIsOnePlainCardWithNoPane() = runDesktopComposeUiTest(1200, 800) {
        d.snapshot = snapshot("z2")
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
        onNodeWithText(Ui.desk_no_sessions.text()).assertIsDisplayed()
        onNodeWithText(Ui.desk_pane_none.text()).assertDoesNotExist()
    }

    @Test fun aRewindsCopyGoesOnAsTheChosenOne() = runDesktopComposeUiTest(1200, 800) {
        up()
        choose(toc)
        d.push(msg("session") { put("session", session("s3b", state = "idle", title = "$toc again")) })
        d.push(msg("session.removed") { put("id", "s3").put("replacedBy", "s3b") })
        until("the copy") { Core.live?.session("s3") == null && Core.live?.session("s3b") != null }
        waitForIdle()
        inPane("$toc again").assertIsDisplayed()
        row("$toc again").assertIsSelected()
    }

    @Test fun offlineKeepsThePaneAsLastHeard() = runDesktopComposeUiTest(1200, 900) {
        up()
        choose(migrate)
        d.down = true
        d.closeSocket()
        until("offline", timeoutMs = 90_000) { Core.live?.link?.state == LinkState.Offline }
        waitForIdle()
        onNodeWithText(Ui.cant_reach.format("z2")).assertIsDisplayed()
        inPane("$ $command").assertIsDisplayed()
        row(migrate).assertIsSelected()
    }

    // ─── A narrow window ───

    @Test fun narrowIsOnePaneAtATimeAndBackIsWhereItWas() = runDesktopComposeUiTest(600, 500) {
        d.snapshot = snapshot("z2", sessions = many())
        up()
        // Only the list, with nothing to say about a pane that isn't there.
        onNodeWithText(Ui.desk_pane_none.text()).assertDoesNotExist()
        onNode(hasScrollToIndexAction()).performScrollToIndex(14)
        waitForIdle()
        val names = (1..30).map { "Session number %02d".format(it) }
        val before = boundsOf(names)
        val name = before.keys.elementAt(1)
        row(name).performClick()
        waitForIdle()
        // Only the session, with a way back.
        inPane(name).assertIsDisplayed()
        inPane(Ui.back.text()).assertIsDisplayed()
        row(name).assertDoesNotExist()
        inPane(Ui.back.text()).performClick()
        waitForIdle()
        inPane(name).assertDoesNotExist()
        assertEquals("the list as it was", before, boundsOf(names))
        onNodeWithText(Ui.desk_pane_none.text()).assertDoesNotExist()
    }

    @Test fun narrowAndTheSessionGoesThereIsStillAWayBack() = runDesktopComposeUiTest(600, 500) {
        up()
        choose(toc)
        d.push(msg("session.removed") { put("id", "s3") })
        until("it gone") { Core.live?.session("s3") == null }
        waitForIdle()
        inPane(Ui.desk_pane_gone.text()).assertIsDisplayed()
        inPane(Ui.back.text()).performClick()
        waitForIdle()
        row(stream).assertIsDisplayed()
    }

    @Test fun theSmallestWindowStillAnswers() = runDesktopComposeUiTest(520, 420) {
        d.ok("POST", "/v1/approvals/a1")
        up()
        choose(migrate)
        inPane(Ui.allow.text()).performClick()
        until("the answer") { d.count("POST", "/v1/approvals/a1") == 1 }
    }

    // ─── The scale ───

    @Test fun a4kWindowAt2xHasBothPanes() = runDesktopComposeUiTest(3840, 2160) {
        up(scale = 2f)
        choose(migrate)
        row(migrate).assertIsDisplayed()
        inPane("$ $command").assertIsDisplayed()
        val pill = inPane(Ui.allow.text()).getBoundsInRoot()
        val rowBox = row(migrate).getBoundsInRoot()
        assertTrue("the pane is beside the list", pill.left > rowBox.right)
    }

    @Test fun aClickLandsOnTheRowAtEveryScale() = runDesktopComposeUiTest(3840, 2160) {
        up(scale = 3f)
        choose(stream)
        row(stream).assertIsSelected()
        inPane(stream).assertIsDisplayed()
    }

    @Test fun zoomedUpANarrowWindowIsOnePane() = runDesktopComposeUiTest(1920, 1080) {
        // 640 dp wide at 3 times: what's narrow goes by dp, not pixels.
        up(scale = 3f)
        onNodeWithText(Ui.desk_pane_none.text()).assertDoesNotExist()
        choose(stream)
        row(stream).assertDoesNotExist()
        inPane(Ui.back.text()).assertIsDisplayed()
    }

    // ─── The keys ───

    @Test fun theArrowsChooseAndEscapeLetsGo() = runDesktopComposeUiTest(1200, 800) {
        up()
        key(Key.DirectionDown)
        row(migrate).assertIsSelected()
        inPane("$ $command").assertIsDisplayed()
        key(Key.DirectionDown)
        row(stream).assertIsSelected()
        inPane(stream).assertIsDisplayed()
        key(Key.DirectionUp)
        row(migrate).assertIsSelected()
        key(Key.Escape)
        row(migrate).assertIsNotSelected()
        inPane(Ui.desk_pane_none.text()).assertIsDisplayed()
    }

    @Test fun theArrowsBringAnOffScreenRowIntoView() = runDesktopComposeUiTest(1200, 500) {
        d.snapshot = snapshot("z2", sessions = many())
        up()
        repeat(12) { key(Key.DirectionDown) }
        row("Session number 12").assertIsSelected()
        row("Session number 12").assertIsDisplayed()
        inPane("Session number 12").assertIsDisplayed()
    }

    @Test fun tabGoesFromTheListToThePills() = runDesktopComposeUiTest(1200, 800) {
        d.ok("POST", "/v1/approvals/a1")
        up()
        key(Key.DirectionDown)
        key(Key.Tab)
        onNodeWithText(Ui.desk_settings.text()).assertIsFocused()
        key(Key.Tab)
        onNodeWithText(Ui.desk_pair_another.text()).assertIsFocused()
        key(Key.Tab)
        inPane(Ui.deny.text()).assertIsFocused()
        key(Key.Tab)
        inPane(Ui.allow.text()).assertIsFocused()
        key(Key.Enter)
        until("the answer") { d.count("POST", "/v1/approvals/a1") == 1 }
        assertEquals("allow", d.calls("POST", "/v1/approvals/a1").last().json.getString("decision"))
    }

    @Test fun theArrowsStillWorkAfterAnAnswerTookThePillsAway() = runDesktopComposeUiTest(1200, 800) {
        d.ok("POST", "/v1/approvals/a1")
        up()
        choose(migrate)
        repeat(4) { key(Key.Tab) }
        inPane(Ui.allow.text()).assertIsFocused()
        key(Key.Enter)
        until("the answer") { d.count("POST", "/v1/approvals/a1") == 1 }
        d.push(msg("approval.resolved") { put("id", "a1").put("sessionId", "s1").put("decision", "allow").put("by", "this") })
        until("the ask gone") { Core.live?.approval("s1") == null }
        waitForIdle()
        key(Key.DirectionDown)
        row(stream).assertIsSelected()
    }

    @Test fun theKeysStillWorkAfterARefusalBroughtThePillsBack() = runDesktopComposeUiTest(600, 600) {
        d.on("POST", "/v1/approvals/a1") { FakeDaemon.refusal(500, "boom", "No") }
        up()
        choose(migrate)
        // Settings, Pair another, Back, Deny, Allow.
        repeat(5) { key(Key.Tab) }
        inPane(Ui.allow.text()).assertIsFocused()
        key(Key.Enter)
        until("the refusal") { shell.toast != null }
        waitForIdle()
        inPane(Ui.desk_answer_failed.text()).assertIsDisplayed()
        key(Key.Escape)
        row(migrate).assertIsDisplayed()
    }

    @Test fun anArrowOnAPillDoesntPressItAndEnterOnAnotherSessionPressesNothing() = runDesktopComposeUiTest(1200, 800) {
        d.ok("POST", "/v1/approvals/a1")
        up()
        choose(migrate)
        repeat(3) { key(Key.Tab) }
        inPane(Ui.deny.text()).assertIsFocused()
        key(Key.DirectionDown)
        row(stream).assertIsSelected()
        key(Key.Enter)
        waitForIdle()
        assertEquals(0, d.count("POST", "/v1/approvals/a1"))
    }

    @Test fun narrowTheArrowsMoveAMarkAndEnterOpens() = runDesktopComposeUiTest(600, 600) {
        up()
        key(Key.DirectionDown)
        // Still the list.
        row(migrate).assertIsDisplayed()
        key(Key.DirectionDown)
        row(stream).assertIsDisplayed()
        key(Key.Enter)
        row(stream).assertDoesNotExist()
        inPane(stream).assertIsDisplayed()
        key(Key.Escape)
        inPane(stream).assertDoesNotExist()
        row(stream).assertIsDisplayed()
    }

    // ─── Other screens and servers ───

    @Test fun eachServerKeepsItsOwnChoice() = runDesktopComposeUiTest(1200, 800) {
        val b = FakeDaemon("juniper", lan = true).also { others += it }
        b.snapshot = snapshot("juniper", sessions = listOf(session("j1", state = "idle", title = "On juniper")))
        up()
        choose(toc)
        pairedWith(b)
        until("juniper") { Core.live?.session("j1") != null }
        waitForIdle()
        inPane(Ui.desk_pane_none.text()).assertIsDisplayed()
        choose("On juniper")
        onNodeWithText("z2", substring = true).performClick()
        until("z2 again") { Core.live?.session("s3") != null }
        waitForIdle()
        inPane(toc).assertIsDisplayed()
        row(toc).assertIsSelected()
    }

    @Test fun settingsComesBackToTheSameChoice() = runDesktopComposeUiTest(1200, 800) {
        up()
        choose(toc)
        onNodeWithText(Ui.desk_settings.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.back.text()).performClick()
        waitForIdle()
        inPane(toc).assertIsDisplayed()
        row(toc).assertIsSelected()
        // And the keys are back too.
        key(Key.DirectionUp)
        row(stream).assertIsSelected()
    }
}
