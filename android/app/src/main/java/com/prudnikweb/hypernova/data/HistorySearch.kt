package com.prudnikweb.hypernova.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/** A conversation the Mac found by the words inside it: [snippet] around the first hit, the words at [marks]. */
data class TextHit(val id: String, val snippet: String, val marks: List<IntRange>, val fromClaude: Boolean, val matches: Int)

/** The Mac's full-text answer; [partial]: it ran out of time before it had looked in every conversation. */
data class TextHits(val hits: List<TextHit>, val partial: Boolean) {
    fun of(id: String): TextHit? = hits.firstOrNull { it.id == id }
}

/**
 * Finding an old session in History (audit P9: up to 300 conversations over 30 days in one flat
 * list): a search over each one's title, project and last line, a project to narrow it to, and the
 * days they ended on as headers. From 3 characters the Mac also looks inside the conversations
 * (idea HI1, [merge]). Pure, unit-tested; ui/home/History.kt draws it.
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

    /** The Mac is asked to look inside the conversations from this many characters. */
    const val FULL_TEXT_MIN = 3
    /** How long typing has to pause before the Mac is asked. */
    const val FULL_TEXT_DEBOUNCE_MS = 300L

    fun wantsFullText(query: String): Boolean = query.trim().length >= FULL_TEXT_MIN

    /**
     * [local] (what [find] found) and the ended sessions the Mac found by their words ([hits]),
     * each once, most recent first, in [project] only when given. A hit on a session the phone
     * doesn't list in History (one on Home) is left out.
     */
    fun merge(ended: List<Session>, local: List<Session>, hits: TextHits?, project: String? = null): List<Session> {
        if (hits == null || hits.hits.isEmpty()) return local
        val ids = hits.hits.mapTo(HashSet()) { it.id }
        val seen = local.mapTo(HashSet()) { it.id }
        val more = ended.filter { it.id in ids && it.id !in seen && (project == null || it.project == project) }
        if (more.isEmpty()) return local
        return (local + more).sortedByDescending { it.updatedAt }
    }

    /** `GET /v1/history/search`'s answer. */
    fun wire(o: JSONObject): TextHits = TextHits(
        o.arr("results").mapObjects { r ->
            val id = r.str("id") ?: return@mapObjects null
            val snippet = r.str("snippet").orEmpty()
            val raw = r.arr("marks").mapObjects { m -> (m.int("start") ?: return@mapObjects null) to (m.int("end") ?: return@mapObjects null) }
            TextHit(id, snippet, marks(snippet, raw), r.str("role") == "assistant", r.int("matches") ?: 0)
        },
        o.bool("partial"),
    )

    /** The hits' places in [snippet], kept inside it, in order, none empty or overlapping (a newer Mac's slips are dropped, not drawn). */
    fun marks(snippet: String, raw: List<Pair<Int, Int>>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for ((s, e) in raw.sortedBy { it.first }) {
            val start = s.coerceIn(0, snippet.length)
            val end = e.coerceIn(0, snippet.length)
            if (end <= start) continue
            val last = out.lastOrNull()
            if (last != null && start <= last.last) continue
            out += start until end
        }
        return out
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
