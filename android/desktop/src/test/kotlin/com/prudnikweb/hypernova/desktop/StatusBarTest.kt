package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.choose
import com.prudnikweb.hypernova.desktop.DesktopRig.inPane
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import com.prudnikweb.hypernova.desktop.ui.PANE_TAG
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The status bar over the box, the phone's own, through the real screen over [FakeDaemon] on real mutual TLS. */
@OptIn(ExperimentalTestApi::class)
class StatusBarTest {
    private val shell get() = DesktopRig.shell
    private lateinit var d: FakeDaemon
    private val name = "Stream invoice exports"
    private fun word(key: Str) = DesktopStrings.getString(key)

    private fun working(more: JSONObject.() -> Unit = {}) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "working", title = name) {
            put("projectName", "invoices").put("cwd", "/home/dev/invoices").put("phase", "working").put("activity", "Bash: npm test && npm run build --workspace invoices")
            put("turnStartedAt", System.currentTimeMillis() - 90_000)
            more()
        }))
    }

    @Before fun fresh() {
        DesktopRig.reset()
        d = FakeDaemon("z2", lan = true)
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

    @Test fun aTurnHasTheBarWithItsStop() = runDesktopComposeUiTest(1400, 1000) {
        working()
        up()
        until("the bar") { runCatching { inPane(word(Str.status_stop)).assertIsDisplayed() }.isSuccess }
        inPane(word(Str.status_stop)).performClick()
        until("the interrupt") { d.count("POST", "/v1/sessions/s1/interrupt") > 0 }
        inPane(word(Str.status_stopping)).assertIsDisplayed()
        assertEquals("a second Esc opens Claude Code's rewind menu", 1, d.count("POST", "/v1/sessions/s1/interrupt"))
    }

    @Test fun theBarOpensToTheWholeCommand() = runDesktopComposeUiTest(1400, 1000) {
        working()
        up()
        until("the bar") { runCatching { inPane(word(Str.status_stop)).assertIsDisplayed() }.isSuccess }
        // The last: the card over the chat says what it's doing too, and the bar is under it.
        onAllNodes(hasText("npm test", substring = true) and hasAnyAncestor(hasTestTag(PANE_TAG))).onLast().performClick()
        until("its details") { runCatching { inPane(word(Str.status_detail_turn)).assertIsDisplayed() }.isSuccess }
        inPane("$ npm test && npm run build --workspace invoices").assertIsDisplayed()
    }

    @Test fun itsTodosAreCountedAndListed() = runDesktopComposeUiTest(1400, 1000) {
        d.ok(
            "GET", "/v1/sessions/s1/todos",
            JSONObject().put("todos", JSONArray().put(JSONObject().put("text", "Parse the rows").put("status", "completed")).put(JSONObject().put("text", "Write the file").put("status", "in_progress"))),
        )
        working()
        up()
        until("the tally") { runCatching { inPane("1/2").assertIsDisplayed() }.isSuccess }
        inPane("1/2").performClick()
        until("the list") { runCatching { inPane("Parse the rows").assertIsDisplayed() }.isSuccess }
    }

    @Test fun noTurnNoBar() = runDesktopComposeUiTest(1400, 1000) {
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = name)))
        up()
        inPane(word(Str.status_stop)).assertDoesNotExist()
    }
}
