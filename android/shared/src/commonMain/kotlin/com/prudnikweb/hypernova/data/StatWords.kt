package com.prudnikweb.hypernova.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * How Statistics says its numbers and dates. Written from what the screen has to say (docs/DECISIONS.md, "Statistics,
 * written new"), with no drawing in it, so both apps read the same and it's tested without a screen.
 */
object StatWords {
    private const val MINUTE_MS = 60_000L
    private const val THOUSAND = 1_000L

    /** From here the minutes are noise beside the hours: `152h`. */
    private const val HOURS_ALONE = 100L

    /** Whole minutes, never below none. */
    private fun minutes(ms: Long): Long = (ms / MINUTE_MS).coerceAtLeast(0L)

    /** A stretch of work: `26m`, `5h`, `12h 3m`, `152h`. */
    fun time(res: Strings, ms: Long): String {
        val all = minutes(ms)
        val h = all / 60
        val m = all % 60
        return when {
            h == 0L -> res.getString(Str.stats_m, m)
            m == 0L || h >= HOURS_ALONE -> res.getString(Str.stats_h, h)
            else -> res.getString(Str.stats_hm, h, m)
        }
    }

    /** The same for TalkBack: `5 hours 10 minutes`, `26 minutes`. */
    fun timeSaid(res: Strings, ms: Long): String {
        val all = minutes(ms)
        val h = (all / 60).toInt()
        val m = (all % 60).toInt()
        val hours = res.getQuantityString(Plural.stats_said_hours, h, h)
        val mins = res.getQuantityString(Plural.stats_said_minutes, m, m)
        return when {
            h == 0 -> mins
            m == 0 -> hours
            else -> res.getString(Str.stats_said_hm, hours, mins)
        }
    }

    /** A count too big to read whole: `640`, `1.2k`, `17M`, `3.6B`. A decimal only under ten, and only if it isn't `.0`. */
    fun count(res: Strings, n: Long): String {
        if (n < THOUSAND) return n.coerceAtLeast(0L).toString()
        val units = listOf(Str.stats_k, Str.stats_mn, Str.stats_bn)
        var unit = THOUSAND
        var i = 0
        while (i < units.lastIndex && n >= unit * THOUSAND) {
            unit *= THOUSAND
            i++
        }
        // Tenths, rounded, then up a unit if that's reached it: 999,960 is 1M, not 1000k.
        var tenths = (n * 10 + unit / 2) / unit
        if (tenths >= THOUSAND * 10 && i < units.lastIndex) {
            i++
            tenths /= THOUSAND
        }
        val whole = tenths / 10
        val figure = if (whole >= 10 || tenths % 10 == 0L) ((tenths + 5) / 10).toString() else "$whole${decimal(res)}${tenths % 10}"
        return res.getString(units[i], figure)
    }

    /** A count that stays whole, grouped: `1,402`. */
    fun whole(res: Strings, n: Long): String = String.format(res.locale, "%,d", n)

    private fun decimal(res: Strings): Char = java.text.DecimalFormatSymbols.getInstance(res.locale).decimalSeparator

    private fun pattern(res: Strings, p: String): DateTimeFormatter = DateTimeFormatter.ofPattern(p, res.locale)

    /** `31 Oct`. */
    fun date(res: Strings, day: LocalDate): String = day.format(pattern(res, "d MMM"))

    /** `2 Oct 2026`. */
    fun dateWithYear(res: Strings, day: LocalDate): String = day.format(pattern(res, "d MMM yyyy"))

    /** `Sat 31 Oct`. */
    fun weekday(res: Strings, day: LocalDate): String = day.format(pattern(res, "EEE d MMM"))

    private fun local(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    /**
     * What a range covers, for under the title: today's date; `25 – 31 Oct`, or `28 Sep – 4 Oct` across a month; YTD
     * `since 1 Jan`; All `since 24 Sep 2026`, from the first day any work is known (older days included).
     */
    fun covers(res: Strings, s: Stats, zone: ZoneId = ZoneId.systemDefault()): String {
        val from = local(s.from, zone)
        val to = local(s.to, zone)
        return when (s.range) {
            StatsRange.Today -> date(res, to)
            StatsRange.Ytd -> res.getString(Str.stats_since, date(res, to.withDayOfYear(1)))
            StatsRange.All -> res.getString(Str.stats_since, dateWithYear(res, local(s.since ?: s.from, zone)))
            StatsRange.Week, StatsRange.Month -> {
                val first = if (from.month == to.month && from.year == to.year) from.dayOfMonth.toString() else date(res, from)
                res.getString(Str.stats_span, first, date(res, to))
            }
        }
    }

    /** The first day of a streak of [streak] days, by the days it was counted from (Statistics' `days`). A streak runs to the last day, or to the one before while that one hasn't started. */
    fun streakStart(days: List<StatDay>, streak: Int): LocalDate? {
        val last = days.lastOrNull() ?: return null
        if (streak <= 0) return null
        val end = if (last.claudeMs >= MINUTE_MS) last.day else last.day.minusDays(1)
        return end.minusDays(streak - 1L)
    }
}
