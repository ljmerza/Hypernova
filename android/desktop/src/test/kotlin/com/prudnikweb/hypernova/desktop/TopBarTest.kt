package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
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
import com.prudnikweb.hypernova.ui.session.TopBarChoice
import com.prudnikweb.hypernova.ui.session.TopBarItem
import com.prudnikweb.hypernova.ui.session.TopBarPref
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The session's top bar, the phone's own, and its choice in Settings, through the real screen over [FakeDaemon] on real mutual TLS. */
@OptIn(ExperimentalTestApi::class)
class TopBarTest {
    private val shell get() = DesktopRig.shell
    private lateinit var d: FakeDaemon
    private val name = "Stream invoice exports"
    private fun word(key: Str) = DesktopStrings.getString(key)

    private fun usage(pct: Int) = d.ok(
        "GET", "/v1/sessions/s1/usage",
        JSONObject().put("usage", JSONObject().put("model", "claude-opus").put("context", JSONObject().put("pct", pct).put("exact", true))),
    )

    @Before fun fresh() {
        DesktopRig.reset()
        d = FakeDaemon("z2", lan = true)
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = name) { put("projectName", "invoices").put("cwd", "/home/dev/invoices") }))
        DesktopRig.main {
            DesktopRig.shell
            TopBarPref.set(TopBarChoice.DEFAULT)
            shell.settings = false
        }
    }

    @After fun tearDown() {
        DesktopRig.main { TopBarPref.set(TopBarChoice.DEFAULT) }
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

    @Test fun theBarIsDrawnOnceTheServerAnswers() = runDesktopComposeUiTest(1400, 1000) {
        usage(42)
        up()
        // The strip is one sentence to a screen reader, and to a test.
        val said = DesktopStrings.getString(Str.chat_context_said, 42)
        until("the context's share") { runCatching { onNode(hasContentDescription(said, substring = true)).assertIsDisplayed() }.isSuccess }
    }

    @Test fun withNoAnswerThereIsNoBar() = runDesktopComposeUiTest(1400, 1000) {
        up()
        until("the ask") { d.count("GET", "/v1/sessions/s1/usage") > 0 }
        waitForIdle()
        inPane("%", substring = true).assertDoesNotExist()
    }

    @Test fun aFullContextOffersCompactAndADoubleClickSendsOnce() = runDesktopComposeUiTest(1400, 1000) {
        usage(90)
        d.ok("POST", "/v1/sessions/s1/prompt", JSONObject().put("session", session("s1", state = "working")))
        up()
        until("Compact") { runCatching { inPane(word(Str.chat_compact)).assertIsDisplayed() }.isSuccess }
        // Both in one go: on a loaded machine two calls can land further apart than a double click.
        inPane(word(Str.chat_compact)).performMouseInput {
            click()
            click()
        }
        until("the prompt") { d.count("POST", "/v1/sessions/s1/prompt") > 0 }
        waitForIdle()
        assertEquals(1, d.count("POST", "/v1/sessions/s1/prompt"))
    }

    @Test fun theChoiceIsInSettingsAndReachesTheBar() = runDesktopComposeUiTest(1400, 1000) {
        usage(42)
        up()
        DesktopRig.main { shell.settings = true }
        waitForIdle()
        onNodeWithText(word(Str.st_top_bar_desc)).performScrollTo().assertIsDisplayed()
        onNodeWithText(word(Str.st_top_bar_model)).performScrollTo().performClick()
        waitForIdle()
        assertTrue("the switch took", TopBarItem.MODEL in TopBarPref.state().value.items)
    }
}
