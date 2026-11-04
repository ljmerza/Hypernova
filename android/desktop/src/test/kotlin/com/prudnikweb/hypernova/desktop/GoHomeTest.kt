package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.choose
import com.prudnikweb.hypernova.desktop.DesktopRig.main
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.row
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import com.prudnikweb.hypernova.desktop.ui.LIST_TAG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The logo on the desktop (owner, 2026-11-03: "reset back to the [...] home screen"), through the real screens over mutual TLS. */
@OptIn(ExperimentalTestApi::class)
class GoHomeTest {
    private lateinit var d: FakeDaemon
    private val first = "Session 00"

    @Before fun fresh() {
        DesktopRig.reset()
        d = FakeDaemon("z2", lan = true)
        d.snapshot = snapshot("z2", sessions = (0 until 30).map { i -> session("s%02d".format(i), state = "working", title = "Session %02d".format(i)) { put("projectName", "ledgerline") } })
    }

    @After fun tearDown() {
        DesktopRig.reset()
        d.close()
    }

    private fun ComposeUiTest.up() {
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        until("the sessions") { (Core.live?.sessions?.size ?: 0) >= 30 }
        waitForIdle()
    }

    private fun ComposeUiTest.mark() {
        onNodeWithContentDescription(Core.platform.strings.text(Str.home_mark_label)).performClick()
        waitForIdle()
    }

    private fun assertHome() {
        assertEquals(Page.Sessions, shell.page)
        assertFalse(shell.settings)
        assertFalse(shell.newSession)
        assertFalse(shell.toTop)
        assertNull(shell.chosen(Core.activeId!!))
    }

    @Test fun fromASessionItEmptiesThePaneAndTheListIsAtItsTop() = runDesktopComposeUiTest(1200, 800) {
        up()
        choose(first)
        onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(LIST_TAG))).performScrollToIndex(25)
        waitForIdle()
        row(first).assertDoesNotExist()
        mark()
        assertHome()
        row(first).assertExists()
    }

    @Test fun fromHistoryAndFromNewSessionItComesBack() = runDesktopComposeUiTest(1200, 800) {
        up()
        onNodeWithText(Ui.history.text()).performClick()
        waitForIdle()
        assertEquals(Page.History, shell.page)
        mark()
        assertHome()
        main { shell.newSession = true }
        waitForIdle()
        mark()
        assertHome()
    }

    @Test fun fromSettingsItsOwnLogoGoesToo() = runDesktopComposeUiTest(1200, 800) {
        up()
        main { shell.settings = true }
        waitForIdle()
        mark()
        assertHome()
        row(first).assertExists()
    }

    @Test fun inOnePaneTheListComesBackFromAChosenSession() = runDesktopComposeUiTest(600, 800) {
        up()
        choose(first)
        row(first).assertDoesNotExist()
        mark()
        assertHome()
        row(first).assertExists()
    }

    @Test fun theServerOnScreenStays() = runDesktopComposeUiTest(1200, 800) {
        up()
        val id = Core.activeId
        mark()
        assertEquals(id, Core.activeId)
    }
}
