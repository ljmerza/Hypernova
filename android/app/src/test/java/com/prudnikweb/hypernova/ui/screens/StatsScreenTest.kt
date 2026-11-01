package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AllStats
import com.prudnikweb.hypernova.data.Link
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.StatDay
import com.prudnikweb.hypernova.data.StatProject
import com.prudnikweb.hypernova.data.StatTokens
import com.prudnikweb.hypernova.data.StatTool
import com.prudnikweb.hypernova.data.Stats
import com.prudnikweb.hypernova.data.StatsRange
import com.prudnikweb.hypernova.data.StatsServer
import com.prudnikweb.hypernova.data.StatsSource
import com.prudnikweb.hypernova.ui.stats.StatsScreen
import com.prudnikweb.hypernova.ui.stats.dayName
import com.prudnikweb.hypernova.ui.stats.hours
import com.prudnikweb.hypernova.ui.stats.hoursSaid
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Statistics (ui/stats/StatsScreen.kt): the totals and the last 30 days as a bar a day. The fake hub
 * has no Mac to ask, so the answers are put where the screen looks first (StatsSource.put). With
 * "All servers" (data/AllStats.kt) every server's are added up: a fake hub with no answer put fails
 * when asked, as a server that doesn't answer does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+h1500dp")
class StatsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private var backs = 0
    private val res get() = context.resources

    @Before fun setUp() = resetPrefs()

    /** 30 fixed days ending 2026-10-31 (so pictures never move): work on most, none on every fifth. */
    private fun stats(range: StatsRange, scale: Int): Stats {
        val first = LocalDate.of(2026, 10, 2)
        val days = (0 until 30).map { i ->
            val ms = if (i % 5 == 4) 0L else (30L + (i * 37L) % 300L) * 60_000L * scale
            StatDay(first.plusDays(i.toLong()), claudeMs = ms, wallMs = ms * 3 / 4, tokens = ms / 10, sessions = if (ms > 0) 2 else 0, prompts = if (ms > 0) 9 else 0)
        }
        val shown = days.takeLast(range.days)
        val ms = shown.sumOf { it.claudeMs }
        val zone = ZoneId.systemDefault()
        return Stats(
            range = range, from = shown.first().day.atStartOfDay(zone).toInstant().toEpochMilli(),
            to = LocalDate.of(2026, 10, 31).atTime(18, 0).atZone(zone).toInstant().toEpochMilli(),
            since = first.atStartOfDay(zone).toInstant().toEpochMilli(), countingSince = first.atStartOfDay(zone).toInstant().toEpochMilli(),
            claudeMs = ms, wallMs = ms * 3 / 4, agentMs = ms / 5,
            tokens = StatTokens(ms / 40, ms / 80, ms / 20, ms / 100),
            sessions = shown.sumOf { it.sessions }, prompts = shown.sumOf { it.prompts }, fromPhone = 12, byVoice = 3,
            toolCalls = 420, tools = listOf(StatTool("Bash", 200), StatTool("Read", 150), StatTool("Edit", 70)),
            filesEdited = 64, agents = 7 * scale, approvals = 40, approvalsPhone = 25, approvalsMac = 15,
            limitsFiveHour = 1, limitsWeek = 0, limitsModel = 0,
            busiestDay = days.maxBy { it.claudeMs }, longestSession = null, streak = 4,
            projects = listOf(
                StatProject("hypernova", "/home/owner/projects/hypernova", ms * 2 / 3, ms / 15, 6),
                StatProject("infra", "/home/owner/projects/infra", ms / 3, ms / 30, 3),
            ),
            days = days,
        )
    }

    private fun screen(hub: FakeHub, servers: List<StatsServer>? = null) {
        rule.setContent { Harness(spy) { StatsScreen(hub, onBack = { backs++ }, servers = servers) } }
        rule.waitForIdle()
    }

    private fun heroSaid(): String =
        rule.onNode(hasContentDescription(res.getString(R.string.stats_claude_worked), substring = true)).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString(" ")

    @Test fun totalsAndTheLast30Days() {
        val hub = FakeHub()
        val week = stats(StatsRange.Week, 1)
        StatsSource.put(hub, StatsRange.Week, week)
        screen(hub)
        val said = heroSaid()
        assertTrue(said, said.startsWith(res.getString(R.string.stats_claude_worked) + " " + hours(res, week.claudeMs)))
        assertTrue(said, said.contains("${week.sessions} sessions, ${week.agents} agents"))
        // The chart: 30 bars, the busiest day named; 24 of the 30 days had work.
        val busiest = week.days.maxBy { it.claudeMs }
        rule.onNode(hasContentDescription(str(R.string.a11y_stats_days, hoursSaid(res, busiest.claudeMs), dayName(busiest.day)), substring = true)).assertExists()
        rule.onNodeWithText(res.getQuantityString(R.plurals.stats_active_days, 24, 24), substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("hypernova", useUnmergedTree = true).assertExists()
        rule.snap("stats_week")
    }

    @Test fun anotherRangeShowsItsOwnTotals() {
        val hub = FakeHub()
        StatsSource.put(hub, StatsRange.Week, stats(StatsRange.Week, 1))
        val month = stats(StatsRange.Month, 2)
        StatsSource.put(hub, StatsRange.Month, month)
        screen(hub)
        rule.onNode(hasText(str(R.string.stats_range_month))).performClick()
        rule.waitForIdle()
        val said = heroSaid()
        assertTrue(said, said.contains(hours(res, month.claudeMs)))
        assertTrue(said, said.contains("${month.sessions} sessions"))
        rule.snap("stats_month")
    }

    @Test fun backLeaves() {
        val hub = FakeHub()
        StatsSource.put(hub, StatsRange.Week, stats(StatsRange.Week, 1))
        screen(hub)
        rule.onNode(hasContentDescription(str(R.string.back))).performClick()
        assertEquals(1, backs)
    }

    // ─── All servers ───────────────────────────────────────────────────────

    /** z2 and gym answer (gym with three times the work), k1 isn't linked: All servers' three. */
    private fun allServers(): Triple<List<StatsServer>, Stats, Stats> {
        val z2 = FakeHub()
        val gym = FakeHub()
        val k1 = FakeHub(link = Link(LinkState.Offline))
        val a = stats(StatsRange.Week, 1)
        val b = stats(StatsRange.Week, 3)
        StatsSource.put(z2, StatsRange.Week, a)
        StatsSource.put(gym, StatsRange.Week, b)
        return Triple(listOf(StatsServer("z2", "z2", z2), StatsServer("gym", "gym", gym), StatsServer("k1", "k1", k1)), a, b)
    }

    @Test fun allServersAddsTheirStatisticsUp() {
        val (servers, a, b) = allServers()
        screen(servers[0].hub as FakeHub, servers)
        // The header says All servers, before the days they cover.
        rule.onNodeWithText(str(R.string.stats_on_server, str(R.string.servers_all), "25 – 31 Oct"), useUnmergedTree = true).assertExists()
        val said = heroSaid()
        assertTrue(said, said.startsWith(res.getString(R.string.stats_claude_worked) + " " + hours(res, a.claudeMs + b.claudeMs)))
        assertTrue(said, said.contains("${a.sessions + b.sessions} sessions, ${a.agents + b.agents} agents"))
        // The chart's days summed: the busiest is the same day (each server's work scales alike), its hours added.
        val busiest = a.days.indices.maxBy { a.days[it].claudeMs + b.days[it].claudeMs }
        val ms = a.days[busiest].claudeMs + b.days[busiest].claudeMs
        rule.onNode(hasContentDescription(str(R.string.a11y_stats_days, hoursSaid(res, ms), dayName(a.days[busiest].day)), substring = true)).assertExists()
        rule.onNodeWithText(res.getQuantityString(R.plurals.stats_active_days, 24, 24), substring = true, useUnmergedTree = true).assertExists()
        // Same-named projects are one, summed.
        val hn = a.projects.first().claudeMs + b.projects.first().claudeMs
        rule.onNodeWithText("hypernova", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(hours(res, hn), useUnmergedTree = true).assertExists()
        // Each server's part, and the one that isn't answering named.
        rule.onNodeWithText(str(R.string.stats_servers).uppercase(), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("gym", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(hours(res, b.claudeMs), useUnmergedTree = true).assertExists()
        rule.onNodeWithText(res.getQuantityString(R.plurals.stats_all_missing, 1, "k1"), useUnmergedTree = true).assertExists()
        rule.snap("stats_all_servers")
        // The footer, past the bottom of this phone: the servers these came from.
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.stats_footer, "z2, gym")))
        rule.onNodeWithText(str(R.string.stats_footer, "z2, gym"), useUnmergedTree = true).assertExists()
    }

    @Test fun allServersAsksEachForTheSameRange() {
        val (servers, _, _) = allServers()
        val a = stats(StatsRange.Month, 1)
        val b = stats(StatsRange.Month, 3)
        StatsSource.put(servers[0].hub, StatsRange.Month, a)
        StatsSource.put(servers[1].hub, StatsRange.Month, b)
        screen(servers[0].hub as FakeHub, servers)
        rule.onNode(hasText(str(R.string.stats_range_month))).performClick()
        rule.waitForIdle()
        val said = heroSaid()
        assertTrue(said, said.contains(hours(res, a.claudeMs + b.claudeMs)))
        assertTrue(said, said.contains("${a.sessions + b.sessions} sessions"))
    }

    @Test fun aServerThatFailsIsLeftOutAndNamed() = runBlocking {
        val (servers, a, b) = allServers()
        // gym is linked but has no answer for Today: asking it fails.
        StatsSource.put(servers[0].hub, StatsRange.Today, stats(StatsRange.Today, 1))
        val got = AllStats.load(servers, StatsRange.Today)
        assertEquals(listOf("z2"), got.each.map { it.first.name })
        assertEquals(listOf("gym", "k1"), got.missing)
        assertEquals(stats(StatsRange.Today, 1).claudeMs, assertNotNullAnd(got.stats).claudeMs)
        // Every linked one answering: added up, k1 still named.
        val week = AllStats.load(servers, StatsRange.Week)
        assertEquals(a.claudeMs + b.claudeMs, assertNotNullAnd(week.stats).claudeMs)
        assertEquals(listOf("k1"), week.missing)
    }

    @Test fun noServerAnsweringSaysSo() {
        val z2 = FakeHub()
        val gym = FakeHub(link = Link(LinkState.Offline))
        screen(z2, listOf(StatsServer("z2", "z2", z2), StatsServer("gym", "gym", gym)))
        rule.onAllNodesWithText(str(R.string.stats_error), useUnmergedTree = true).onFirst().assertExists()
        rule.onNodeWithText(str(R.string.try_again), useUnmergedTree = true).assertExists()
    }

    @Test fun oneServerIsStillOneServers() {
        val hub = FakeHub()
        val week = stats(StatsRange.Week, 1)
        StatsSource.put(hub, StatsRange.Week, week)
        // Fewer than two is never All servers.
        screen(hub, listOf(StatsServer("z2", "z2", hub)))
        rule.onNodeWithText(str(R.string.stats_on_server, str(R.string.servers_all), "25 – 31 Oct"), useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("25 – 31 Oct", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(str(R.string.stats_footer, hub.mac.name), useUnmergedTree = true).assertExists()
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
