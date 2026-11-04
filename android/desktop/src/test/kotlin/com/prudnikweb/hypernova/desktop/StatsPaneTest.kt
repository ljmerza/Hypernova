package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.StatWords
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.desktop.DesktopRig.main
import com.prudnikweb.hypernova.desktop.DesktopRig.pairedWith
import com.prudnikweb.hypernova.desktop.DesktopRig.row
import com.prudnikweb.hypernova.desktop.DesktopRig.shell
import com.prudnikweb.hypernova.desktop.DesktopRig.takesCode
import com.prudnikweb.hypernova.desktop.DesktopRig.until
import com.prudnikweb.hypernova.desktop.ui.App
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Statistics on the desktop, the shared parts in the top bar's page, asking a real FakeDaemon over mutual TLS. */
@OptIn(ExperimentalTestApi::class)
class StatsPaneTest {
    private val daemons = mutableListOf<FakeDaemon>()
    private val strings get() = Core.platform.strings
    private val minute = 60_000L

    private fun daemon(name: String, scale: Int = 1): FakeDaemon {
        val d = FakeDaemon(name, lan = true).also { daemons += it }
        d.snapshot = snapshot(name, sessions = listOf(session("s1", state = "working", title = "On $name") { put("projectName", "ledgerline") }))
        d.on("GET", "/v1/stats") { req -> json(200, statsAnswer(req.query.substringAfter("range=", "week"), scale)) }
        return d
    }

    @Before fun fresh() {
        DesktopRig.reset()
        main { shell.statsAll = false }
    }

    @After fun tearDown() {
        main { shell.statsAll = false }
        DesktopRig.reset()
        daemons.forEach { it.close() }
    }

    private fun ComposeUiTest.up(d: FakeDaemon) {
        setContent { App(shell, reduced = true) }
        pairedWith(d)
        waitForIdle()
    }

    private fun ComposeUiTest.open() {
        onNodeWithText(Ui.stats_title.text()).performClick()
        waitForIdle()
    }

    /** What the answer for [range] on [scale] puts in the hero, as TalkBack hears it. */
    private fun ComposeUiTest.heroSays(range: String, scale: Int = 1) {
        val ms = statsAnswer(range, scale).getLong("claudeMs")
        val said = StatWords.timeSaid(strings, ms)
        until("the hero says $said") { onAllNodes(hasContentDescription(said, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theTopBarOpensItWhereTheListWasAndAsksTheServer() = runDesktopComposeUiTest(1400, 900) {
        val d = daemon("z2")
        up(d)
        row("On z2").assertExists()
        open()
        assertEquals(Page.Statistics, shell.page)
        onNode(androidx.compose.ui.test.hasText(Ui.stats_title.text()) and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading)).assertExists()
        heroSays("week")
        assertEquals("range=week", d.calls("GET", "/v1/stats").last().query)
        row("On z2").assertDoesNotExist()
    }

    @Test fun aRangeAsksForItsOwn() = runDesktopComposeUiTest(1400, 900) {
        val d = daemon("z2")
        up(d)
        open()
        heroSays("week")
        onNodeWithText(strings.text(Str.stats_range_ytd)).performClick()
        heroSays("ytd")
        until("asked for YTD") { d.calls("GET", "/v1/stats").any { it.query == "range=ytd" } }
    }

    @Test fun theLogoGoesBackToTheList() = runDesktopComposeUiTest(1400, 900) {
        up(daemon("z2"))
        open()
        onNodeWithContentDescription(strings.text(Str.home_mark_label)).performClick()
        waitForIdle()
        assertEquals(Page.Sessions, shell.page)
        row("On z2").assertExists()
    }

    @Test fun newSessionFromHereGoesToTheList() = runDesktopComposeUiTest(1400, 900) {
        up(daemon("z2"))
        open()
        onNodeWithText(Ui.new_session.text()).performClick()
        waitForIdle()
        assertEquals(Page.Sessions, shell.page)
        assertTrue(shell.newSession)
    }

    private fun ComposeUiTest.twoServers(): Pair<FakeDaemon, FakeDaemon> {
        val a = daemon("z2")
        val b = daemon("gym", scale = 3)
        a.takesCode()
        setContent { App(shell, reduced = true) }
        pairedWith(a)
        waitForIdle()
        pairedWith(b)
        until("both linked") { Core.statuses.size == 2 }
        waitForIdle()
        return a to b
    }

    @Test fun allServersAddsUpAndIsOnlyOfferedHere() = runDesktopComposeUiTest(1400, 900) {
        twoServers()
        onNodeWithText(Ui.servers_all.text()).assertDoesNotExist()
        open()
        heroSays("week", scale = 3)
        onNodeWithText(Ui.servers_all.text()).performClick()
        waitForIdle()
        onNodeWithText(Ui.servers_all.text()).assertIsSelected()
        val ms = statsAnswer("week", 1).getLong("claudeMs") + statsAnswer("week", 3).getLong("claudeMs")
        val said = StatWords.timeSaid(strings, ms)
        until("added up: $said") { onAllNodes(hasContentDescription(said, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        // Its footer names both.
        until("both named") { onAllNodes(androidx.compose.ui.test.hasText(strings.getString(Str.stats_footer_agents, "gym, z2"))).fetchSemanticsNodes().isNotEmpty() || onAllNodes(androidx.compose.ui.test.hasText(strings.getString(Str.stats_footer_agents, "z2, gym"))).fetchSemanticsNodes().isNotEmpty() }
        // A server's pill goes back to that server's.
        onNodeWithText("z2").performClick()
        waitForIdle()
        assertEquals(false, shell.statsAll)
        heroSays("week")
    }

    @Test fun aServerThatRefusesTheRangeIsToldToUpdate() = runDesktopComposeUiTest(1400, 900) {
        val d = daemon("z2")
        d.on("GET", "/v1/stats") { req ->
            if (req.query == "range=ytd") refusal(400, "bad_range", "Ask for today, week, month or all.") else json(200, statsAnswer("week"))
        }
        up(d)
        open()
        heroSays("week")
        onNodeWithText(strings.text(Str.stats_range_ytd)).performClick()
        val words = strings.getQuantityString(com.prudnikweb.hypernova.data.Plural.stats_needs_update, 1, "z2")
        until("says to update") { onAllNodes(androidx.compose.ui.test.hasText(words)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun aPointerOverTheBarsReadsADayAndLeavingGoesBack() = runDesktopComposeUiTest(1400, 900) {
        up(daemon("z2"))
        open()
        heroSays("week")
        val today = StatWords.weekday(strings, java.time.LocalDate.now()).uppercase()
        val first = StatWords.weekday(strings, java.time.LocalDate.now().minusDays(29)).uppercase()
        onNodeWithText(today).assertExists()
        val chart = onNode(hasContentDescription(strings.text(Str.a11y_stats_days).substringBefore(","), substring = true))
        chart.performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(2f, centerY)) }
        waitForIdle()
        onNodeWithText(first).assertExists()
        chart.performMouseInput { moveTo(androidx.compose.ui.geometry.Offset(-200f, centerY)) }
        waitForIdle()
        onNodeWithText(today).assertExists()
        onNodeWithText(first).assertDoesNotExist()
    }

    private fun ComposeUiTest.leftOf(a: String): Float = onNodeWithText(a.uppercase()).getBoundsInRoot().left.value
    private fun ComposeUiTest.topOf(a: String): Float = onNodeWithText(a.uppercase()).getBoundsInRoot().top.value

    /** The Week's hero and the sections, read by where they landed. */
    private fun ComposeUiTest.layoutAt() {
        up(daemon("z2"))
        open()
        heroSays("week")
    }

    @Test fun aWideWindowReadsAcross() = runDesktopComposeUiTest(1600, 1000) {
        layoutAt()
        val days = strings.text(Str.stats_last_days)
        val projects = strings.text(Str.stats_projects)
        val records = strings.text(Str.stats_records)
        val work = strings.text(Str.stats_work)
        assertTrue("the days are beside the hero, not under it", leftOf(days) > 400f)
        assertEquals("projects, records and work on one row", topOf(projects), topOf(records), 1f)
        assertEquals(topOf(projects), topOf(work), 1f)
        assertTrue(leftOf(projects) < leftOf(records) && leftOf(records) < leftOf(work))
    }

    @Test fun aMiddleWindowHasTwoAcross() = runDesktopComposeUiTest(1000, 1000) {
        layoutAt()
        val projects = strings.text(Str.stats_projects)
        val records = strings.text(Str.stats_records)
        assertEquals(topOf(projects), topOf(records), 1f)
        assertTrue(leftOf(records) > leftOf(projects) + 200f)
    }

    @Test fun aNarrowWindowIsOneColumnInTheSameOrder() = runDesktopComposeUiTest(520, 1000) {
        layoutAt()
        val days = strings.text(Str.stats_last_days)
        val projects = strings.text(Str.stats_projects)
        assertTrue(leftOf(days) < 60f)
        assertTrue(topOf(days) < topOf(projects))
    }
}
