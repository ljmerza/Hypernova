package com.prudnikweb.hypernova.ui.screens

import com.prudnikweb.hypernova.data.ChangesRepo
import android.os.Looper
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.ui.changes.ChangesEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * What changed (ui/changes/ChangesScreen.kt): the session header's chip, its window with the files,
 * Show all, one file's diff and back, and Commit. Its requests go to the active computer
 * ([ChangesRepo] reads [Core]'s pairing), here a [FakeDaemon] answering with the contract fixtures.
 */
@RunWith(RobolectricTestRunner::class)
class ChangesScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val mac = FakeDaemon("z2")

    @Before fun setUp() {
        resetPrefs()
        AndroidCore.init(context as android.app.Application)
        mac.ok("GET", "/v1/sessions/${Samples.ZETA}/changes", ScreenFixtures.json("rest-changes"))
        mac.ok("GET", "/v1/sessions/${Samples.ZETA}/changes/diff", ScreenFixtures.json("rest-changes-diff"))
        Core.paired(Pairing(mac.base, "tok-z2", "z2", "m-z2", "dev-1"))
        val deadline = System.nanoTime() + 10_000_000_000L
        while (Core.live?.link?.state != LinkState.Connected) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("not linked")
            Thread.sleep(3)
        }
    }

    @After fun tearDown() {
        for (s in Core.servers.toList()) Core.forget(s.id)
        shadowOf(Looper.getMainLooper()).idle()
        mac.close()
    }

    private val session: Session get() = Samples.sessions().first { it.id == Samples.ZETA }.copy(cwd = "/home/me/Projects/app-parser-fix")

    private fun entry(hub: FakeHub, s: Session = session) {
        rule.setContent { Harness(spy) { ChangesEntry(hub, s) } }
        waitFor("the summary") { mac.count("GET", "/v1/sessions/${Samples.ZETA}/changes") >= 1 }
        rule.waitForIdle()
    }

    private fun waitFor(what: String, cond: () -> Boolean) = rule.waitUntil(10_000) { shadowOf(Looper.getMainLooper()).idle(); cond() }

    private fun chip() = rule.onNodeWithText(context.resources.getQuantityString(R.plurals.changes_files, 1, 1))

    @Test fun theChipCountsTheFilesAndOpensTheListThenADiffWithItsLines() {
        entry(FakeHub())
        rule.waitUntil(10_000) { rule.onAllNodesWithText(context.resources.getQuantityString(R.plurals.changes_files, 1, 1)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("+1").assertExists()
        assertEquals("only the session's own", "", mac.calls("GET", "/v1/sessions/${Samples.ZETA}/changes").last().query)
        chip().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("parser.ts", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText(str(R.string.changes_title)).assertExists()
        rule.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/changes_list.png")

        rule.onAllNodesWithText("parser.ts", useUnmergedTree = true).onFirst().performClick()
        waitFor("the diff") { mac.count("GET", "/v1/sessions/${Samples.ZETA}/changes/diff") == 1 }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("export const parse = (s: string) => s.trim()", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("path=parser.ts", mac.calls("GET", "/v1/sessions/${Samples.ZETA}/changes/diff").single().query)
        rule.waitForIdle()
        captureScreenRoboImage("src/test/snapshots/changes_diff.png")
        // Back: the list again, then closed.
        rule.onAllNodes(hasContentDescription(str(R.string.back))).onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.changes_title)).assertExists()
    }

    @Test fun showAllAsksForEverythingInTheFolder() {
        entry(FakeHub())
        rule.waitUntil(10_000) { rule.onAllNodesWithText(context.resources.getQuantityString(R.plurals.changes_files, 1, 1)).fetchSemanticsNodes().isNotEmpty() }
        chip().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(str(R.string.changes_show_all), substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(str(R.string.changes_show_all), substring = true, useUnmergedTree = true).onFirst().performClick()
        waitFor("everything") { mac.calls("GET", "/v1/sessions/${Samples.ZETA}/changes").any { it.query == "all=1" } }
    }

    @Test fun commitAsksTheSessionToCommitWhatItChanged() {
        val hub = FakeHub()
        entry(hub)
        rule.waitUntil(10_000) { rule.onAllNodesWithText(context.resources.getQuantityString(R.plurals.changes_files, 1, 1)).fetchSemanticsNodes().isNotEmpty() }
        chip().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText(str(R.string.changes_commit)).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText(str(R.string.changes_commit)).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(listOf("send(${Samples.ZETA}, ${str(R.string.changes_commit_prompt_files, "parser.ts")})"), hub.calls)
    }

    @Test fun aSessionAtWorkIsNotAskedUntilItRests() {
        val busy = session.copy(state = SessionState.Working, phase = com.prudnikweb.hypernova.data.Phase.of(SessionState.Working), turnStartedAt = Samples.NOW)
        rule.setContent { Harness(spy) { ChangesEntry(FakeHub(), busy) } }
        rule.waitForIdle()
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, mac.count("GET", "/v1/sessions/${Samples.ZETA}/changes"))
    }
}
