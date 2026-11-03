package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.live.FakeDaemon
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
import com.prudnikweb.hypernova.desktop.ui.LIST_TAG
import com.prudnikweb.hypernova.desktop.ui.PANE_TAG
import com.prudnikweb.hypernova.desktop.ui.Scaled
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** History in the desktop's two panes, through the real screen and hub, over real mutual TLS on loopback. docs/DECISIONS.md, "The desktop client's History". */
@OptIn(ExperimentalTestApi::class)
class HistoryTest {
    private lateinit var d: FakeDaemon
    private val day = 86_400_000L
    private val at = System.currentTimeMillis()

    private val live = "Stream invoice exports"
    private val cut = "Cut by a restart"
    private val fixed = "Fix the widget crash"
    private val copy = "Landing page copy"
    private val notes = "Old release notes"

    private fun sessions() = listOf(
        session("s1", state = "working", title = live) { put("projectName", "invoices") },
        session("s2", state = "ended", title = cut) { put("endedBy", "mac_restart") },
        session("e1", state = "ended", title = fixed, lastActivityAt = at - 1_000) { put("projectName", "hypernova").put("lastText", "No more crashes on a cold start.").put("cwd", "/home/dev/hypernova") },
        session("e2", state = "ended", title = copy, lastActivityAt = at - 2_000) { put("projectName", "site").put("lastText", "Pushed to main.") },
        session("e3", state = "ended", title = notes, lastActivityAt = at - 3 * day) { put("projectName", "hypernova") },
    )

    private fun run(id: String, time: Long) = session(id, state = "ended", title = "Brief $id", lastActivityAt = time) {
        put("projectName", "brief").put("automation", JSONObject().put("id", "au1").put("name", "Morning brief"))
    }

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

    private fun ComposeUiTest.toHistory() {
        onNodeWithText(Ui.history.text()).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.type(text: String) {
        onNode(hasSetTextAction()).performTextInput(text)
        waitForIdle()
    }

    private fun ComposeUiTest.key(k: Key) {
        onRoot().performKeyInput { pressKey(k) }
        waitForIdle()
    }

    @Test fun itListsWhatEndedAndNothingElseByTheDayItEnded() = runDesktopComposeUiTest(1200, 800) {
        up()
        row(fixed).assertDoesNotExist()
        toHistory()
        row(fixed).assertIsDisplayed()
        row(copy).assertIsDisplayed()
        row(notes).assertIsDisplayed()
        row(live).assertDoesNotExist()
        row(cut).assertDoesNotExist()
        onNode(hasText(Ui.history_trail.format(3))).assertIsDisplayed()
        onNode(hasText(Ui.history_today.text().uppercase())).assertIsDisplayed()
        val top = { t: String -> row(t).getBoundsInRoot().top }
        assertTrue("most recent first", top(fixed) < top(copy) && top(copy) < top(notes))
        render("40-history-none-chosen")
    }

    @Test fun choosingOneShowsItsDetailsAndSaysPlainlyWhatItCantDo() = runDesktopComposeUiTest(1200, 800) {
        up()
        toHistory()
        inPane(Ui.desk_pane_none.text()).assertIsDisplayed()
        choose(fixed)
        row(fixed).assertIsSelected()
        inPane(fixed).assertIsDisplayed()
        inPane("/home/dev/hypernova").assertIsDisplayed()
        inPane(Ui.session_sheet_ran_for.text()).assertIsDisplayed()
        inPane(Ui.state_ended.text().uppercase()).assertIsDisplayed()
        inPane(Ui.desk_pane_history_only.text()).assertIsDisplayed()
        inPane(Ui.desk_pane_phone_only.text()).assertDoesNotExist()
        onNodeWithText(Ui.allow.text()).assertDoesNotExist()
        render("41-history-chosen")
        // Starts nothing, reads nothing: the owner's rule is that only writing in it resumes it.
        assertEquals(0, d.count("POST", "/v1/sessions/e1/resume"))
        assertEquals(0, d.count("GET", "/v1/sessions/e1/events"))
    }

    @Test fun nothingEndedSaysSo() = runDesktopComposeUiTest(1200, 800) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "working", title = live)))
        up()
        toHistory()
        onNode(hasText(Ui.history_empty.text())).assertIsDisplayed()
        onNode(hasSetTextAction()).assertDoesNotExist()
        onNode(hasTestTag(PANE_TAG)).assertDoesNotExist()
    }

    @Test fun eachListKeepsItsOwnChoiceAndSettingsComesBack() = runDesktopComposeUiTest(1200, 800) {
        up()
        choose(live)
        toHistory()
        inPane(live).assertDoesNotExist()
        inPane(Ui.desk_pane_none.text()).assertIsDisplayed()
        choose(copy)
        onNodeWithText(Ui.sessions.text()).performClick()
        waitForIdle()
        inPane(live).assertIsDisplayed()
        toHistory()
        inPane(copy).assertIsDisplayed()
        onNodeWithText(Ui.desk_settings.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.back.text()).performClick()
        waitForIdle()
        inPane(copy).assertIsDisplayed()
    }

    @Test fun theSearchFindsByTitleProjectAndLastLineAndSaysWhenNothing() = runDesktopComposeUiTest(1200, 800) {
        up()
        toHistory()
        type("pushed")
        row(copy).assertIsDisplayed()
        row(fixed).assertDoesNotExist()
        onNode(hasSetTextAction()).performClickToEmpty()
        type("zzzq")
        until("the server's answer") { runCatching { onNode(hasText(Ui.history_no_match.format("zzzq"))).assertExists() }.isSuccess }
        onNode(hasText(Ui.history_no_match.format("zzzq"))).assertIsDisplayed()
    }

    @Test fun fromThreeLettersTheServerLooksInsideAndWhatItFoundShows() = runDesktopComposeUiTest(1200, 800) {
        d.ok(
            "GET", "/v1/history/search",
            JSONObject().put("partial", true).put(
                "results",
                JSONArray().put(JSONObject().put("id", "e3").put("snippet", "the cold start crash").put("role", "assistant").put("matches", 1).put("marks", JSONArray().put(JSONObject().put("start", 4).put("end", 8)))),
            ),
        )
        up()
        toHistory()
        type("co")
        assertEquals("two letters don't ask", 0, d.count("GET", "/v1/history/search"))
        type("ld")
        until("the server's asked") { d.count("GET", "/v1/history/search") == 1 }
        until("its hit") { runCatching { row("the cold start crash").assertExists() }.isSuccess }
        assertEquals("q=cold", d.calls("GET", "/v1/history/search").last().query)
        row("the cold start crash").assertIsDisplayed()
        onNode(hasText(Ui.history_partial.text())).assertIsDisplayed()
        render("42-history-found-inside")
    }

    @Test fun anOlderServerFiltersHereAlone() = runDesktopComposeUiTest(1200, 800) {
        d.on("GET", "/v1/history/search") { FakeDaemon.json(404, JSONObject().put("error", "not_found")) }
        up()
        toHistory()
        type("widget")
        until("the server's asked") { d.count("GET", "/v1/history/search") == 1 }
        until("it's done looking") { runCatching { onNode(hasText(Ui.history_searching.text())).assertDoesNotExist() }.isSuccess }
        row(fixed).assertIsDisplayed()
    }

    @Test fun aProjectChipNarrowsItAndATapOnItAgainShowsAll() = runDesktopComposeUiTest(1200, 800) {
        up()
        toHistory()
        onNode(hasText("site") and hasAnyAncestor(hasTestTag(LIST_TAG))).performClick()
        waitForIdle()
        row(copy).assertIsDisplayed()
        row(fixed).assertDoesNotExist()
        onNode(hasText("site") and hasAnyAncestor(hasTestTag(LIST_TAG))).assertIsSelected().performClick()
        waitForIdle()
        row(fixed).assertIsDisplayed()
    }

    @Test fun anAutomationsRunsFoldIntoOneRowAndThePaneIsItsNewest() = runDesktopComposeUiTest(1200, 800) {
        d.snapshot = snapshot("z2", sessions = sessions() + run("r1", at - 500) + run("r2", at - 900))
        up()
        toHistory()
        row("MORNING BRIEF").assertIsDisplayed()
        row("2 runs").assertIsDisplayed()
        row(Ui.au_session_chip.text().uppercase()).assertIsDisplayed()
        row("Brief r1").assertDoesNotExist()
        choose("MORNING BRIEF")
        inPane("Brief r1").assertIsDisplayed()
        inPane(Ui.desk_pane_runs_phone.format("Morning brief")).assertIsDisplayed()
        onNode(hasText(Ui.history_trail.format(5))).assertIsDisplayed()
    }

    @Test fun theArrowsWalkTheListFromTheSearchToo() = runDesktopComposeUiTest(1200, 800) {
        up()
        toHistory()
        key(Key.DirectionDown)
        inPane(fixed).assertIsDisplayed()
        key(Key.DirectionDown)
        inPane(copy).assertIsDisplayed()
        onNode(hasSetTextAction()).performClick()
        waitForIdle()
        key(Key.DirectionDown)
        inPane(notes).assertIsDisplayed()
        key(Key.DirectionUp)
        inPane(copy).assertIsDisplayed()
        key(Key.Escape)
        inPane(Ui.desk_pane_none.text()).assertIsDisplayed()
    }

    @Test fun whatsResumedElsewhereLeavesTheListAndThePaneFollows() = runDesktopComposeUiTest(1200, 800) {
        up()
        toHistory()
        choose(fixed)
        d.push(FakeDaemon.msg("session") { put("session", session("e1", state = "working", title = fixed) { put("projectName", "hypernova") }) })
        until("back at work") { Core.live?.session("e1")?.busy == true }
        waitForIdle()
        row(fixed).assertDoesNotExist()
        inPane(fixed).assertIsDisplayed()
        inPane(Ui.desk_pane_phone_only.text()).assertIsDisplayed()
    }

    @Test fun narrowIsOnePaneAtATimeAndBackFindsTheList() = runDesktopComposeUiTest(600, 600) {
        up()
        toHistory()
        onNode(hasTestTag(PANE_TAG)).assertDoesNotExist()
        choose(copy)
        inPane(copy).assertIsDisplayed()
        inPane(Ui.desk_pane_history_only.text()).assertIsDisplayed()
        row(fixed).assertDoesNotExist()
        render("43-history-narrow")
        inPane(Ui.back.text()).performClick()
        waitForIdle()
        row(fixed).assertIsDisplayed()
        render("44-history-narrow-list")
    }

    @Test fun theSmallestWindowStillHasTheList() = runDesktopComposeUiTest(520, 420) {
        up()
        toHistory()
        row(fixed).assertExists()
        render("45-history-smallest")
    }

    @Test fun a4kWindowAt2xHasBothPanes() = runDesktopComposeUiTest(3840, 2160) {
        up(scale = 2f)
        toHistory()
        choose(fixed)
        row(fixed).assertIsSelected()
        inPane(fixed).assertIsDisplayed()
        render("46-history-4k")
    }

    /** The field's text, gone: a real person would press its cross. */
    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performClickToEmpty() {
        performKeyInput { pressKey(Key.Escape) }
    }
}
