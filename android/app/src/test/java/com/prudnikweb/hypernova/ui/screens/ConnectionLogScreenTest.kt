package com.prudnikweb.hypernova.ui.screens

import android.content.ClipboardManager
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ConnEntry
import com.prudnikweb.hypernova.data.ConnKind
import com.prudnikweb.hypernova.data.ConnLog
import com.prudnikweb.hypernova.data.ConnResult
import com.prudnikweb.hypernova.data.RouteKind
import com.prudnikweb.hypernova.ui.settings.ConnectionLogScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings → Connection log (ui/settings/ConnectionLog.kt): a probe round as the router logs it
 * (data/ConnLog.kt), drawn newest first, each line said whole to TalkBack, and Copy putting the
 * whole log on the clipboard as text, oldest first, with its header.
 */
@RunWith(RobolectricTestRunner::class)
class ConnectionLogScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private var backs = 0

    @Before fun setUp() = resetPrefs()

    /** A round two and a half minutes ago: every line says "2m" however slowly the test runs. */
    private fun round(t0: Long) {
        ConnLog.add(ConnEntry(t0 - 150_000, ConnKind.Network, ConnResult.Info, null, null, "start: Wi-Fi, same subnet as the Mac", null))
        ConnLog.add(ConnEntry(t0 - 149_800, ConnKind.Probe, ConnResult.Ok, RouteKind.Local, "192.168.1.20:47323", null, 42))
        ConnLog.add(ConnEntry(t0 - 149_700, ConnKind.Probe, ConnResult.Timeout, RouteKind.Remote, "10.0.2.2:47321", "the base URL as it is: no answer in 4s", 4_000))
        ConnLog.add(ConnEntry(t0 - 149_600, ConnKind.Route, ConnResult.Ok, RouteKind.Local, "192.168.1.20:47323", "Local, in 42 ms", 200))
    }

    private fun screen() {
        rule.setContent { Harness(spy) { ConnectionLogScreen(onBack = { backs++ }) } }
        rule.waitForIdle()
    }

    @Test fun aRoundIsDrawnAndCopiedAsText() {
        val t0 = System.currentTimeMillis()
        round(t0)
        rule.mainClock.advanceTimeBy(100)
        screen()
        rule.onNodeWithText("Local, in 42 ms", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("the base URL as it is: no answer in 4s", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("start: Wi-Fi, same subnet as the Mac", useUnmergedTree = true).assertExists()
        // Each line is one sentence for TalkBack, the route and where included.
        rule.onNode(hasContentDescription("Local, in 42 ms, 192.168.1.20:47323", substring = true)).assertExists()

        rule.snap("connection_log")
        rule.onNodeWithText(str(R.string.connlog_copy)).performClick()
        assertEquals(listOf(str(R.string.connlog_copied)), spy.toasts)
        val text = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        val lines = text.lines()
        assertTrue(lines.first(), lines.first().contains(com.prudnikweb.hypernova.BuildConfig.VERSION_NAME))
        // Oldest first.
        val network = lines.indexOfFirst { "same subnet" in it }
        val chosen = lines.indexOfFirst { "Local, in 42 ms" in it }
        assertTrue(text, network in 1 until chosen)
        assertTrue(text, lines.any { "10.0.2.2:47321" in it && "no answer in 4s" in it })
    }

    @Test fun backLeaves() {
        screen()
        rule.onNode(hasContentDescription(str(R.string.back))).performClick()
        assertEquals(1, backs)
    }
}
