package com.prudnikweb.hypernova.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Finding an old session in History (audit P9: up to 300 conversations over 30 days in one flat
 * list): a search over each one's title, project and last line, a project to narrow it to, and the
 * days they ended on as headers. Pure, unit-tested; ui/home/History.kt draws it.
 */
object HistorySearch {
    /** Chips for projects at most; the rest are reached by typing. */
    const val MAX_PROJECTS = 8

    /**
     * The ended sessions [query] finds, most recent first, in [project] only when given. Every
     * word of the query has to be in the title, the project or the last line ([Fuzzy]); when some
     * sessions have each word as a word, the ones with only scattered letters are left out.
     */
    fun find(ended: List<Session>, query: String, project: String? = null): List<Session> {
        val pool = ended.filter { project == null || it.project == project }.sortedByDescending { it.updatedAt }
        val words = Fuzzy.words(query)
        if (words.isEmpty()) return pool
        val scored = pool.mapNotNull { s ->
            val text = "${s.name} ${s.project} ${s.activity}"
            var weakest = Int.MAX_VALUE
            for (w in words) weakest = minOf(weakest, Fuzzy.word(w, text) ?: return@mapNotNull null)
            s to weakest
        }
        return Fuzzy.keepStrong(scored)
    }

    /** The projects to offer as chips: only with two or more, the busiest first (then by name), [MAX_PROJECTS] at most. */
    fun projects(ended: List<Session>, max: Int = MAX_PROJECTS): List<String> {
        val counts = ended.groupingBy { it.project }.eachCount()
        if (counts.size < 2) return emptyList()
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(max).map { it.key }
    }

    /** The day [t] falls on, on this phone's clock. */
    fun day(t: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

    /** A day's header: [today] or [yesterday] in words, else "Mon 13 Oct" (with the year when it isn't this one). */
    fun label(day: LocalDate, now: LocalDate, today: String, yesterday: String, locale: Locale): String = when (day) {
        now -> today
        now.minusDays(1) -> yesterday
        else -> day.format(DateTimeFormatter.ofPattern(if (day.year == now.year) "EEE d MMM" else "EEE d MMM yyyy", locale))
    }
}
