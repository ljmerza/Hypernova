// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.components

import android.content.Context
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.IncidentList
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
typealias Incident = com.prudnikweb.hypernova.data.Incident

object Incidents {
    private var cache: List<Incident>? = null
    /** The one shown last, so the next card is another one. */
    private var last: String? = null

    fun all(context: Context): List<Incident> = cache ?: load(context).also { cache = it }

    fun byId(context: Context, id: String?): Incident? = all(context).firstOrNull { it.id == id }

    /** A random incident, never the one shown last (unless it is the only one). Remembers it as shown. */
    fun next(context: Context, random: Random = Random): Incident? {
        val pick = IncidentList.pick(all(context), last, random) ?: return null
        last = pick.id
        return pick
    }

    private fun load(context: Context): List<Incident> = runCatching {
        IncidentList.parse(context.resources.openRawResource(R.raw.incidents).bufferedReader().use { it.readText() })
    }.getOrDefault(emptyList())
}
