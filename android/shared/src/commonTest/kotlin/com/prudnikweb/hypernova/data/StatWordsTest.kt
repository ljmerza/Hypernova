package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** How Statistics says its numbers (data/StatWords.kt), with the words the app's files have for them. */
class StatWordsTest {
    private val res = object : Strings {
        override val locale: Locale = Locale.US
        override fun text(key: Str) = when (key) {
            Str.stats_m -> "%1\$dm"
            Str.stats_h -> "%1\$dh"
            Str.stats_hm -> "%1\$dh %2\$dm"
            Str.stats_k -> "%1\$sk"
            Str.stats_mn -> "%1\$sM"
            Str.stats_bn -> "%1\$sB"
            Str.stats_said_hm -> "%1\$s %2\$s"
            Str.stats_since -> "since %1\$s"
            Str.stats_span -> "%1\$s – %2\$s"
            else -> key.name
        }
        override fun quantityText(key: Plural, quantity: Int) = when (key) {
            Plural.stats_said_hours -> if (quantity == 1) "%d hour" else "%d hours"
            Plural.stats_said_minutes -> if (quantity == 1) "%d minute" else "%d minutes"
            else -> key.name
        }
        override fun timePattern(withDay: Boolean) = "HH:mm"
    }

    private val min = 60_000L

    @Test fun aStretchOfWork() {
        assertEquals("0m", StatWords.time(res, 0))
        assertEquals("0m", StatWords.time(res, 59_999))
        assertEquals("26m", StatWords.time(res, 26 * min))
        assertEquals("5h", StatWords.time(res, 300 * min))
        assertEquals("12h 3m", StatWords.time(res, 723 * min))
        assertEquals("99h 59m", StatWords.time(res, (99 * 60 + 59) * min))
        // Long enough that the minutes are noise.
        assertEquals("152h", StatWords.time(res, (152 * 60 + 11) * min))
        assertEquals("0m", StatWords.time(res, -5))
    }

    @Test fun aStretchOfWorkSaid() {
        assertEquals("0 minutes", StatWords.timeSaid(res, 0))
        assertEquals("1 minute", StatWords.timeSaid(res, min))
        assertEquals("1 hour", StatWords.timeSaid(res, 60 * min))
        assertEquals("5 hours 10 minutes", StatWords.timeSaid(res, 310 * min))
    }

    @Test fun bigCountsReadShort() {
        for ((n, said) in listOf(
            0L to "0", 999L to "999", 1_000L to "1k", 1_234L to "1.2k", 9_950L to "10k", 12_345L to "12k", 542_000L to "542k",
            999_400L to "999k", 999_960L to "1M", 1_956_000L to "2M", 4_200_000L to "4.2M", 17_000_000L to "17M",
            3_600_000_000L to "3.6B", 12_000_000_000L to "12B", 5_000_000_000_000L to "5000B",
        )) assertEquals("$n", said, StatWords.count(res, n))
    }

    @Test fun aCountThatStaysWhole() {
        assertEquals("738", StatWords.whole(res, 738))
        assertEquals("1,402", StatWords.whole(res, 1_402))
    }

    private fun at(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun covering(range: StatsRange, from: Long, to: Long, since: Long? = null) = Stats(
        range = range, from = from, to = to, since = since, countingSince = 0L, claudeMs = 0L, wallMs = 0L, agentMs = 0L,
        tokens = StatTokens(0, 0, 0, 0), sessions = 0, prompts = 0, fromPhone = 0, byVoice = 0, toolCalls = 0, tools = emptyList(),
        filesEdited = 0, agents = 0, approvals = 0, approvalsPhone = 0, approvalsMac = 0, limitsFiveHour = 0, limitsWeek = 0, limitsModel = 0,
        busiestDay = null, longestSession = null, streak = 0, projects = emptyList(), days = emptyList(),
    )

    private fun covers(s: Stats) = StatWords.covers(res, s, ZoneOffset.UTC)

    @Test fun whatARangeCovers() {
        val now = at(2026, 10, 31) + 18 * 60 * min
        assertEquals("31 Oct", covers(covering(StatsRange.Today, at(2026, 10, 31), now)))
        assertEquals("25 – 31 Oct", covers(covering(StatsRange.Week, at(2026, 10, 25), now)))
        assertEquals("2 Sep – 1 Oct", covers(covering(StatsRange.Month, at(2026, 9, 2), at(2026, 10, 1))))
        assertEquals("since 1 Jan", covers(covering(StatsRange.Ytd, at(2026, 1, 1), now)))
        assertEquals("since 24 Sep 2026", covers(covering(StatsRange.All, at(2026, 9, 24), now)))
        // Older days reach further back than the first day counted.
        assertEquals("since 8 Jan 2026", covers(covering(StatsRange.All, at(2026, 9, 24), now, since = at(2026, 1, 8))))
    }

    private fun day(d: Int, ms: Long) = StatDay(LocalDate.of(2026, 10, d), ms, 0L, 0L, 0, 0)

    @Test fun aStreakStartsCountingBackFromItsLastDay() {
        assertEquals(LocalDate.of(2026, 10, 28), StatWords.streakStart(listOf(day(30, 60 * min), day(31, min)), 4))
        // Today hasn't started: it runs to yesterday.
        assertEquals(LocalDate.of(2026, 10, 27), StatWords.streakStart(listOf(day(30, 60 * min), day(31, 59_000L)), 4))
        assertNull(StatWords.streakStart(listOf(day(31, min)), 0))
        assertNull(StatWords.streakStart(emptyList(), 3))
    }
}
