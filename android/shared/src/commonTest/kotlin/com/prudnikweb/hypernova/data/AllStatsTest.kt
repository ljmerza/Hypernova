package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate

/**
 * Statistics with "All servers" (data/AllStats.kt, docs/DECISIONS.md "Combined Statistics in All
 * servers"): several servers' answers for one range, added up into one.
 */
class AllStatsTest {
    private val today = LocalDate.of(2026, 10, 31)
    private val h = 3_600_000L

    /** 30 days ending [last], each with [ms] of work by its index (0 is the oldest). */
    private fun days(last: LocalDate = today, ms: (Int) -> Long): List<StatDay> = (0 until 30).map { i ->
        val m = ms(i)
        StatDay(last.minusDays(29L - i), m, m / 2, m / 1000, if (m > 0) 1 else 0, if (m > 0) 3 else 0, codexMs = 0L)
    }

    private fun stats(
        range: StatsRange = StatsRange.Week,
        days: List<StatDay> = days { 0L },
        claudeMs: Long = 0L,
        tokens: StatTokens = StatTokens(0, 0, 0, 0),
        sessions: Int = 0,
        projects: List<StatProject> = emptyList(),
        tools: List<StatTool> = emptyList(),
        busiest: StatDay? = null,
        streak: Int = 0,
        longest: StatSession? = null,
        claude: StatAgent? = null,
        codex: StatAgent? = null,
        since: Long? = 1_000L,
        countingSince: Long = 1_000L,
        from: Long = 10L,
        to: Long = 20L,
        limits: Int = 0,
    ) = Stats(
        range = range, from = from, to = to, since = since, countingSince = countingSince,
        claudeMs = claudeMs, wallMs = claudeMs / 2, agentMs = claudeMs / 4, tokens = tokens,
        sessions = sessions, prompts = sessions * 3, fromPhone = 2, byVoice = 1, toolCalls = 10, tools = tools,
        filesEdited = 5, agents = 1, approvals = 4, approvalsPhone = 3, approvalsMac = 1,
        limitsFiveHour = limits, limitsWeek = 0, limitsModel = 0,
        busiestDay = busiest, longestSession = longest, streak = streak, projects = projects, days = days,
        claude = claude, codex = codex,
    )

    @Test fun noneIsNothingAndOneIsItself() {
        assertNull(AllStats.combine(emptyList()))
        val one = stats(claudeMs = h)
        assertSame(one, AllStats.combine(listOf(one)))
    }

    @Test fun totalsAreSummed() {
        val a = stats(claudeMs = 2 * h, tokens = StatTokens(1, 2, 3, 4), sessions = 3, limits = 1, since = 5_000L, countingSince = 100L, from = 10L, to = 20L)
        val b = stats(claudeMs = 3 * h, tokens = StatTokens(10, 20, 30, 40), sessions = 4, limits = 2, since = 2_000L, countingSince = 300L, from = 8L, to = 25L)
        val c = AllStats.combine(listOf(a, b))!!
        assertEquals(5 * h, c.claudeMs)
        assertEquals(5 * h / 2, c.wallMs)
        assertEquals(5 * h / 4, c.agentMs)
        assertEquals(StatTokens(11, 22, 33, 44), c.tokens)
        assertEquals(7, c.sessions)
        assertEquals(21, c.prompts)
        assertEquals(4, c.fromPhone)
        assertEquals(2, c.byVoice)
        assertEquals(20, c.toolCalls)
        assertEquals(10, c.filesEdited)
        assertEquals(2, c.agents)
        assertEquals(8, c.approvals)
        assertEquals(6, c.approvalsPhone)
        assertEquals(2, c.approvalsMac)
        assertEquals(3, c.limitsFiveHour)
        // The range's edges are the widest; the first day with work the earliest; counting from the latest.
        assertEquals(8L, c.from)
        assertEquals(25L, c.to)
        assertEquals(2_000L, c.since)
        assertEquals(300L, c.countingSince)
        assertEquals(StatsRange.Week, c.range)
    }

    @Test fun eachDayIsSummedAndTheBusiestDayComesFromTheSums() {
        // a's biggest day is the 20th; b's the 25th; together the 22nd beats both.
        val a = stats(days = days { i -> when (i) { 20 -> 5 * h; 22 -> 3 * h; else -> 0L } }, busiest = StatDay(today.minusDays(9), 5 * h, 0, 0, 0, 0), range = StatsRange.Month)
        val b = stats(days = days { i -> when (i) { 25 -> 4 * h; 22 -> 3 * h; else -> 0L } }, busiest = StatDay(today.minusDays(4), 4 * h, 0, 0, 0, 0), range = StatsRange.Month)
        val c = AllStats.combine(listOf(a, b))!!
        assertEquals(30, c.days.size)
        assertEquals(today, c.days.last().day)
        assertEquals(6 * h, c.days[22].claudeMs)
        assertEquals(2, c.days[22].sessions)
        assertEquals(6 * h / 1000, c.days[22].tokens)
        assertEquals(today.minusDays(7), c.busiestDay?.day)
        assertEquals(6 * h, c.busiestDay?.claudeMs)
        // The days with work: the 20th, 22nd and 25th.
        assertEquals(3, c.days.count { it.claudeMs > 0 })
    }

    @Test fun aWeeksBusiestDayIsInsideTheWeek() {
        val a = stats(days = days { i -> when (i) { 10 -> 9 * h; 27 -> h; else -> 0L } })
        val b = stats(days = days { i -> if (i == 28) 2 * h else 0L })
        val c = AllStats.combine(listOf(a, b))!!
        assertEquals(today.minusDays(1), c.busiestDay?.day)
    }

    @Test fun allKeepsABusierDayFromBeforeTheLast30() {
        val old = LocalDate.of(2026, 8, 1)
        val a = stats(range = StatsRange.All, days = days { i -> if (i == 29) h else 0L }, busiest = StatDay(old, 12 * h, 0, 7, 0, 0))
        val b = stats(range = StatsRange.All, days = days { i -> if (i == 29) 2 * h else 0L }, busiest = StatDay(old, 3 * h, 0, 5, 0, 0))
        val c = AllStats.combine(listOf(a, b))!!
        assertEquals(old, c.busiestDay?.day)
        assertEquals(15 * h, c.busiestDay?.claudeMs)
        assertEquals(12L, c.busiestDay?.tokens)
    }

    @Test fun ytdKeepsABusierDayFromBeforeTheLast30ButNotOneFromLastYear() {
        val inJanuary = LocalDate.of(2027, 1, 10)
        // January 10: of the chart's 30 days only the last 10 are this year's; the busiest of the 30 is last year's.
        val a = stats(range = StatsRange.Ytd, days = days(inJanuary) { i -> when (i) { 5 -> 20 * h; 25 -> h; else -> 0L } })
        val b = stats(range = StatsRange.Ytd, days = days(inJanuary) { i -> if (i == 25) 2 * h else 0L })
        val c = AllStats.combine(listOf(a, b))!!
        assertEquals(inJanuary.minusDays(4), c.busiestDay?.day)
        assertEquals(3 * h, c.busiestDay?.claudeMs)
        // In October a server's own busiest day from before the last 30 still counts.
        val march = LocalDate.of(2026, 3, 1)
        val d = stats(range = StatsRange.Ytd, days = days { i -> if (i == 29) h else 0L }, busiest = StatDay(march, 12 * h, 0, 7, 0, 0))
        val e = stats(range = StatsRange.Ytd, days = days { i -> if (i == 29) h else 0L }, busiest = StatDay(march, 3 * h, 0, 5, 0, 0))
        assertEquals(march, AllStats.combine(listOf(d, e))!!.busiestDay?.day)
    }

    @Test fun olderDaysTakeTheEarliestStartAndTheHoursLineFollowsEachServer() {
        val z2 = stats(since = 100L, tokens = StatTokens(1, 2, 3, 4)).copy(hoursSince = 500L)
        val gym = stats(since = 700L, tokens = StatTokens(1, 1, 1, 1)).copy(hoursSince = 900L)
        val c = AllStats.combine(listOf(z2, gym))!!
        assertEquals(100L, c.since)
        assertEquals(500L, c.hoursSince)
        assertEquals(10L + 4L, c.tokens.total)
        // An older server has no older days: it adds none, and its hours start with it.
        val old = stats(since = 400L)
        val d = AllStats.combine(listOf(z2, old))!!
        assertEquals(100L, d.since)
        assertEquals(400L, d.hoursSince)
        // None has any: no line to say.
        val e = AllStats.combine(listOf(old, stats(since = 200L)))!!
        assertNull(e.hoursSince)
    }

    @Test fun serversADayApartKeepThirtyDays() {
        val a = stats(days = days { i -> if (i == 29) h else 0L })
        val b = stats(days = days(today.plusDays(1)) { i -> if (i == 29) 2 * h else 0L })
        val c = AllStats.combine(listOf(a, b))!!
        assertEquals(30, c.days.size)
        assertEquals(today.plusDays(1), c.days.last().day)
        assertEquals(h, c.days[28].claudeMs)
    }

    @Test fun theStreakIsWorkedOutFromTheSummedDays() {
        // a works on even days, b on odd ones, the last 6 days: each has a streak of 1, together 6.
        val a = stats(days = days { i -> if (i >= 24 && i % 2 == 0) h else 0L }, streak = 1)
        val b = stats(days = days { i -> if (i >= 24 && i % 2 == 1) h else 0L }, streak = 1)
        assertEquals(6, AllStats.combine(listOf(a, b))!!.streak)
        // Today not started yet: to yesterday.
        val c = stats(days = days { i -> if (i in 25..28) h else 0L }, streak = 4)
        val d = stats(days = days { 0L })
        assertEquals(4, AllStats.combine(listOf(c, d))!!.streak)
        // Under a minute on each, a minute together: a day with work.
        val e = stats(days = days { i -> if (i == 29) 40_000L else 0L })
        val f = stats(days = days { i -> if (i == 29) 30_000L else 0L })
        assertEquals(1, AllStats.combine(listOf(e, f))!!.streak)
    }

    @Test fun aStreakLongerThanTheChartIsAtLeastAnyServersOwn() {
        val a = stats(days = days { h }, streak = 45)
        val b = stats(days = days { 0L })
        assertEquals(45, AllStats.combine(listOf(a, b))!!.streak)
    }

    @Test fun projectsOfTheSameNameAreOneProjectSummed() {
        val a = stats(projects = listOf(StatProject("hypernova", "/home/a/hypernova", 3 * h, 30, 3), StatProject("infra", "/home/a/infra", h, 10, 1)))
        val b = stats(projects = listOf(StatProject("hypernova", "/srv/hypernova", 2 * h, 20, 2), StatProject("garden", null, 5 * h, 5, 1)))
        val c = AllStats.combine(listOf(a, b))!!
        assertEquals(listOf("hypernova", "garden", "infra"), c.projects.map { it.name })
        val hn = c.projects.first()
        assertEquals(5 * h, hn.claudeMs)
        assertEquals(50L, hn.tokens)
        assertEquals(5, hn.sessions)
        assertEquals("/home/a/hypernova", hn.path)
    }

    @Test fun atMostEightProjects() {
        val a = stats(projects = (1..8).map { StatProject("a$it", null, it * h, 0, 1) })
        val b = stats(projects = (1..8).map { StatProject("b$it", null, it * h + 1, 0, 1) })
        val c = AllStats.combine(listOf(a, b))!!
        assertEquals(8, c.projects.size)
        assertEquals("b8", c.projects.first().name)
    }

    @Test fun toolsAreSummedByNameEightThenOther() {
        val a = stats(tools = (1..8).map { StatTool("t$it", it * 10) } + StatTool("Other", 3))
        val b = stats(tools = listOf(StatTool("t1", 100), StatTool("x", 1), StatTool("Other", 2)))
        val c = AllStats.combine(listOf(a, b))!!
        assertEquals(StatTool("t1", 110), c.tools.first())
        assertEquals(9, c.tools.size)
        // x (1) falls out of the top 8 (t1, t8 … t2): Other is both servers' Other and x, 3 + 2 + 1.
        assertEquals(listOf("t1", "t8", "t7", "t6", "t5", "t4", "t3", "t2", "Other"), c.tools.map { it.name })
        assertEquals(StatTool("Other", 6), c.tools.last())
    }

    @Test fun theLongestSessionIsAnyServersLongest() {
        val a = stats(longest = StatSession("s1", "Build", "hypernova", 2 * h, 0))
        val b = stats(longest = StatSession("s2", "Deploy", "infra", 3 * h, 0))
        assertEquals("s2", AllStats.combine(listOf(a, b, stats()))!!.longestSession?.id)
    }

    @Test fun agentsAreSummedAndAnOlderServersWorkIsClaudes() {
        val older = stats(claudeMs = 2 * h, tokens = StatTokens(0, 0, 100, 0), sessions = 2)
        val both = stats(claudeMs = 3 * h, sessions = 3, claude = StatAgent(2 * h, 40, 2, 6, 7), codex = StatAgent(h, 10, 1, 3, 3))
        val c = AllStats.combine(listOf(older, both))!!
        assertEquals(StatAgent(4 * h, 140, 4, 12, 17), c.claude)
        assertEquals(StatAgent(h, 10, 1, 3, 3), c.codex)
        assertEquals(StatsAgents.Both, c.agents(codexLeads = false))
        // No server split them: Claude's alone, as an older Mac's answer reads.
        val plain = AllStats.combine(listOf(stats(claudeMs = h), stats(claudeMs = h)))!!
        assertNull(plain.claude)
        assertNull(plain.codex)
        assertEquals(StatsAgents.Claude, plain.agents(codexLeads = false))
    }
}
