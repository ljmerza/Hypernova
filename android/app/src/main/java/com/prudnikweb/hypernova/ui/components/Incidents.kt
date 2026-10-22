package com.prudnikweb.hypernova.ui.components

import android.content.Context
import com.prudnikweb.hypernova.R
import org.json.JSONObject
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/**
 * Real cases where an AI agent with too much freedom did damage, for the think-twice card
 * (ThinkTwice.kt). They live in `res/raw/incidents.json`; each one was checked against two
 * reputable reports, and the first source is what "Read the story" opens.
 *
 * To add one: append an object to that file with the same fields (id, tool, when as YYYY-MM,
 * headline, what in two plain sentences, sources with two or more reports, notes for maintainers
 * on what is disputed). Tell it without exaggeration, and with "says" wording where it rests on
 * one person's account. Nothing else changes: the card picks from whatever the file holds.
 */
data class Incident(
    val id: String,
    val tool: String,
    /** "2025-07". */
    val whenYm: String,
    val headline: String,
    val what: String,
    val sources: List<Source>,
    /** For whoever maintains the list; never shown. */
    val notes: String,
) {
    data class Source(val title: String, val url: String)

    /** "July 2025", in the phone's language. */
    fun month(locale: Locale = Locale.getDefault()): String = runCatching {
        val ym = YearMonth.parse(whenYm)
        "${ym.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }} ${ym.year}"
    }.getOrDefault(whenYm)
}

object Incidents {
    private var cache: List<Incident>? = null
    /** The one shown last, so the next card is another one. */
    private var last: String? = null

    fun all(context: Context): List<Incident> = cache ?: load(context).also { cache = it }

    fun byId(context: Context, id: String?): Incident? = all(context).firstOrNull { it.id == id }

    /** A random incident, never the one shown last (unless it is the only one). Remembers it as shown. */
    fun next(context: Context, random: Random = Random): Incident? {
        val list = all(context)
        val pool = list.filter { it.id != last }.ifEmpty { list }
        val pick = pool.randomOrNull(random) ?: return null
        last = pick.id
        return pick
    }

    private fun load(context: Context): List<Incident> = runCatching {
        val text = context.resources.openRawResource(R.raw.incidents).bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("incidents")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val sources = o.getJSONArray("sources").let { a -> (0 until a.length()).map { j -> a.getJSONObject(j).let { Incident.Source(it.getString("title"), it.getString("url")) } } }
            // A card needs a story to link to: skip one with no source rather than show it bare.
            if (sources.isEmpty()) return@mapNotNull null
            Incident(o.getString("id"), o.getString("tool"), o.getString("when"), o.getString("headline"), o.getString("what"), sources, o.optString("notes"))
        }
    }.getOrDefault(emptyList())
}
