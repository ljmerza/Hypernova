package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.AttachDrafts
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
import com.prudnikweb.hypernova.desktop.ui.BOX_TAG
import com.prudnikweb.hypernova.desktop.ui.ChatDrafts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A message's menu, the phone's own, opened with the other mouse button, through the real screen over [FakeDaemon] on real mutual TLS. */
@OptIn(ExperimentalTestApi::class)
class MessageMenuTest {
    private val shell get() = DesktopRig.shell
    private lateinit var d: FakeDaemon
    private val name = "Stream invoice exports"
    private fun word(key: Str) = DesktopStrings.getString(key)
    private val at = 1_780_000_000_000L

    private fun event(id: String, kind: String, text: String, ts: Long) = JSONObject().put("id", id).put("kind", kind).put("ts", ts).put("text", text)

    @Before fun fresh() {
        DesktopRig.reset()
        d = FakeDaemon("z2", lan = true)
        d.snapshot = snapshot("z2", sessions = listOf(session("s1", state = "idle", title = name) { put("projectName", "invoices").put("cwd", "/home/dev/invoices") }))
        d.ok(
            "GET", "/v1/sessions/s1/events",
            JSONObject().put(
                "events",
                JSONArray()
                    .put(event("u1", "user", "The first question", at))
                    .put(event("r1", "assistant", "The first answer", at + 1_000))
                    .put(event("u2", "user", "The second question", at + 2_000))
                    .put(event("r2", "assistant", "The second answer", at + 3_000)),
            ),
        )
        DesktopRig.main {
            ChatDrafts.set("s1", "")
            AttachDrafts.takeBack("s1")
            shell.settings = false
        }
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
        until("its history") { Core.live?.loadingTranscript("s1") == false && d.count("GET", "/v1/sessions/s1/events") > 0 }
        waitForIdle()
    }

    private fun ComposeUiTest.menuOn(words: String) {
        inPane(words).performMouseInput { rightClick(center) }
        waitForIdle()
    }

    @Test fun theOtherButtonOpensItWithCopyQuoteAndNoReadAloudOrSelect() = runDesktopComposeUiTest(1200, 900) {
        up()
        menuOn("The first answer")
        onNodeWithText(word(Str.chat_copy)).assertIsDisplayed()
        onNodeWithText(word(Str.chat_quote)).assertIsDisplayed()
        // Not here: a voice (LATER L135), and a page to select in, as a mouse selects the words where they are.
        onNodeWithText(word(Str.chat_read)).assertDoesNotExist()
        onNodeWithText(word(Str.chat_select)).assertDoesNotExist()
        // A reply has no rewind of its own.
        onNodeWithText(word(Str.chat_rewind)).assertDoesNotExist()
    }

    @Test fun quoteGoesToTheHeadOfTheBox() = runDesktopComposeUiTest(1200, 900) {
        up()
        menuOn("The first answer")
        onNodeWithText(word(Str.chat_quote)).performClick()
        waitForIdle()
        assertEquals("> The first answer\n\n", ChatDrafts.of("s1"))
        onNode(hasTestTag(BOX_TAG) and hasSetTextAction()).assertTextContains("The first answer", substring = true)
    }

    @Test fun aLaterMessageOffersRewindAndItAsksFirst() = runDesktopComposeUiTest(1200, 900) {
        up()
        menuOn("The second question")
        onNodeWithText(word(Str.chat_rewind)).performClick()
        waitForIdle()
        onNodeWithText(word(Str.chat_rewind_files)).assertIsDisplayed()
        onNodeWithText(word(Str.cancel)).performClick()
        waitForIdle()
        assertEquals("nothing rewound", 0, d.count("POST", "/v1/sessions/s1/rewind"))
    }

    @Test fun theFirstMessageHasNoRewind() = runDesktopComposeUiTest(1200, 900) {
        up()
        menuOn("The first question")
        onNodeWithText(word(Str.chat_copy)).assertIsDisplayed()
        onNodeWithText(word(Str.chat_rewind)).assertDoesNotExist()
    }

    @Test fun copyGoesToTheClipboardAndSaysSo() = runDesktopComposeUiTest(1200, 900) {
        up()
        menuOn("The first answer")
        onNodeWithText(word(Str.chat_copy)).performClick()
        waitForIdle()
        until("it says so") { shell.toast == word(Str.chat_copied) }
    }
}
