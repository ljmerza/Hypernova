package com.prudnikweb.hypernova.data

import org.json.JSONObject
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

/** One real case of an agent with too much freedom, for the think-twice card. The list and how to add one: ui/components/Incidents.kt. */
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

/** `res/raw/incidents.json`'s cases, and which to show. */
object IncidentList {
    fun parse(text: String): List<Incident> {
        val arr = JSONObject(text).getJSONArray("incidents")
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val sources = o.getJSONArray("sources").let { a -> (0 until a.length()).map { j -> a.getJSONObject(j).let { Incident.Source(it.getString("title"), it.getString("url")) } } }
            // A card needs a story to link to: skip one with no source rather than show it bare.
            if (sources.isEmpty()) return@mapNotNull null
            Incident(o.getString("id"), o.getString("tool"), o.getString("when"), o.getString("headline"), o.getString("what"), sources, o.optString("notes"))
        }
    }

    /** A random one, never the one shown last (unless it is the only one). */
    fun pick(list: List<Incident>, last: String?, random: Random = Random): Incident? = list.filter { it.id != last }.ifEmpty { list }.randomOrNull(random)
}
