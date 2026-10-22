package com.prudnikweb.hypernova.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Cron as the Mac reads it (docs/PROTOCOL.md, "Workflows", Cron), for the demo's check and next
 * times (a real Mac answers `POST /v1/automations/check` itself): minute, hour, day of the month,
 * month, day of the week; `*`, `a-b`, `a,b`, a step (`a-b/n`, or every n from the first); `jan`–`dec`, `sun`–`sat` (0 and 7 are
 * Sunday); `@hourly`, `@daily`, `@weekly`, `@monthly`. With both day fields narrowed, either one
 * matching is enough. Pure.
 */
object Cron {
    /** A schedule read, or why not ([error]: which field, in words). */
    class Parsed(
        val minutes: Set<Int>,
        val hours: Set<Int>,
        val days: Set<Int>,
        val months: Set<Int>,
        /** 0 (Sunday) to 6. */
        val weekdays: Set<Int>,
        val daysNarrowed: Boolean,
        val weekdaysNarrowed: Boolean,
    )

    class Bad(message: String) : Exception(message)

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val DAYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
    private val MACROS = mapOf("@hourly" to "0 * * * *", "@daily" to "0 0 * * *", "@midnight" to "0 0 * * *", "@weekly" to "0 0 * * 0", "@monthly" to "0 0 1 * *")
    /** Two runs closer than this are too often (the Mac's `too_often`). */
    const val MIN_GAP_MS = 5 * 60_000L

    fun parse(expr: String): Parsed {
        val e = MACROS[expr.trim().lowercase()] ?: expr.trim()
        val f = e.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (f.size != 5) throw Bad("A schedule has five fields: minute, hour, day, month, weekday.")
        val minutes = field(f[0], 0, 59, null, "minute")
        val hours = field(f[1], 0, 23, null, "hour")
        val days = field(f[2], 1, 31, null, "day")
        val months = field(f[3], 1, 12, MONTHS, "month", base = 1)
        val weekdays = field(f[4], 0, 7, DAYS, "weekday").map { it % 7 }.toSet()
        return Parsed(minutes, hours, days, months, weekdays, f[2] != "*", f[4] != "*")
    }

    private fun field(s: String, lo: Int, hi: Int, names: List<String>?, what: String, base: Int = 0): Set<Int> {
        val out = sortedSetOf<Int>()
        for (part in s.lowercase().split(',')) {
            if (part.isEmpty()) throw Bad("The $what field has an empty part.")
            val (range, stepText) = part.split('/').let { if (it.size > 2) throw Bad("The $what field has two steps.") else it[0] to it.getOrNull(1) }
            val step = stepText?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw Bad("The $what field's step isn't a number.") } ?: 1
            val (a, b) = when {
                range == "*" -> lo to hi
                '-' in range -> range.split('-').let { if (it.size != 2) throw Bad("The $what field's range is malformed.") else value(it[0], names, what, base) to value(it[1], names, what, base) }
                else -> value(range, names, what, base).let { v -> v to if (stepText != null) hi else v }
            }
            if (a < lo || b > hi || a > b) throw Bad("The $what field goes from $lo to $hi.")
            var v = a
            while (v <= b) { out += v; v += step }
        }
        return out
    }

    private fun value(s: String, names: List<String>?, what: String, base: Int): Int {
        s.toIntOrNull()?.let { return it }
        val i = names?.indexOf(s.take(3)) ?: -1
        if (i < 0 || s.length != 3) throw Bad("“$s” isn't a $what.")
        return i + base
    }

    private fun dayMatches(p: Parsed, d: LocalDate): Boolean {
        if (d.monthValue !in p.months) return false
        val dom = d.dayOfMonth in p.days
        val dow = (d.dayOfWeek.value % 7) in p.weekdays
        return when {
            p.daysNarrowed && p.weekdaysNarrowed -> dom || dow
            p.daysNarrowed -> dom
            p.weekdaysNarrowed -> dow
            else -> true
        }
    }

    /** The next [n] due times after [after] (epoch ms), on the clock of [zone]; fewer when none come within about a year. */
    fun next(p: Parsed, after: Long, zone: ZoneId, n: Int = 3): List<Long> {
        val out = ArrayList<Long>(n)
        val start = Instant.ofEpochMilli(after).atZone(zone)
        var day = start.toLocalDate()
        val last = day.plusDays(400)
        while (out.size < n && !day.isAfter(last)) {
            if (dayMatches(p, day)) {
                for (h in p.hours) for (m in p.minutes) {
                    val t = ZonedDateTime.of(day, LocalTime.of(h, m), zone)
                    if (t.isAfter(start) && out.size < n) {
                        val ms = t.toInstant().toEpochMilli()
                        if (out.lastOrNull() != ms) out += ms
                    }
                }
            }
            day = day.plusDays(1)
        }
        return out
    }

    /** Two of its runs fall closer together than five minutes. */
    fun tooOften(p: Parsed, from: Long, zone: ZoneId): Boolean {
        val times = next(p, from, zone, 40)
        return times.zipWithNext().any { (a, b) -> b - a < MIN_GAP_MS }
    }
}
