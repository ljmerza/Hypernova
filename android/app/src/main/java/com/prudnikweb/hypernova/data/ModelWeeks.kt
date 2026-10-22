package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A model's own weekly limit ("Fable", "Sonnet") as the Mac sends it: `Usage.models`, read off
 * Claude Code's /usage screen every 15 minutes (docs/PROTOCOL.md, "Per-model weeks"). [pct] 0–100.
 */
@Immutable
data class WModelWeek(val label: String, val pct: Double, val resetsAt: Long?)

/** A model's own week as the app shows it: [pct] 0–1, past its reset already the next week. */
@Immutable
data class ModelWeek(val label: String, val pct: Float, val resetsAt: Long?) {
    /** Used up: Claude Code won't run this model until [resetsAt]. */
    val full: Boolean get() = pct >= 0.999f
}

object ModelWeeks {
    private const val WEEK = 7 * 24 * 3_600_000L
    private const val DAY_ISH = 20 * 3_600_000L

    /** `usage.models` off the wire; missing (an older Mac) is none, a malformed entry is skipped. */
    fun wire(a: JSONArray?): List<WModelWeek> = a.mapObjects { o ->
        val label = o.str("label")?.takeIf { it.isNotBlank() } ?: return@mapObjects null
        WModelWeek(label, o.dbl("pct") ?: return@mapObjects null, o.long("resetsAt")?.takeIf { it > 0 })
    }

    /**
     * What the meters show at [now]: a week past its reset is empty until the next look, its reset
     * a week on (as the Mac rolls it); and a model limit Claude Code hit ("Fable limit") fills that
     * model's week at once, until its reset, rather than waiting for the Mac's next look.
     */
    fun of(wire: List<WModelWeek>, account: Account, now: Long): List<ModelWeek> {
        val hit = account.resting(now)?.takeIf { it.window == "model" && !it.usingCredits }
        return wire.map { m ->
            val r = m.resetsAt
            val over = r != null && r <= now
            val week = ModelWeek(
                m.label,
                if (over) 0f else (m.pct / 100.0).toFloat().coerceIn(0f, 1f),
                if (over) r!! + ((now - r) / WEEK + 1) * WEEK else r,
            )
            if (hit != null && sameFamily(hit.label, m.label)) week.copy(pct = 1f, resetsAt = hit.resetsAt ?: week.resetsAt) else week
        }
    }

    /**
     * A limit's label and a week's name are about the same model when their first words match:
     * Claude Code calls the limit "Fable limit" but may name the week after the model's full name,
     * "Fable 5.1".
     */
    private fun sameFamily(limitLabel: String?, weekLabel: String): Boolean {
        fun family(s: String) = s.trim().substringBefore(' ').lowercase()
        return limitLabel != null && family(weekLabel).isNotEmpty() && family(limitLabel) == family(weekLabel)
    }

    /** How a week's meter ends: a weekday and time more than ~a day off, how long nearer, or at its limit. */
    sealed interface Trail {
        /** "resets Tue 20:00". */
        data class On(val at: Long) : Trail
        /** "resets in 5h 12m". */
        data class In(val ms: Long) : Trail
        /** "limit reached · resets in 2d 4h". */
        data class Limit(val ms: Long) : Trail
        /** No reset said. */
        data object None : Trail
    }

    fun trail(w: ModelWeek, now: Long): Trail {
        val r = w.resetsAt ?: return if (w.full) Trail.Limit(0) else Trail.None
        val left = (r - now).coerceAtLeast(0)
        return when {
            w.full -> Trail.Limit(left)
            left > DAY_ISH -> Trail.On(r)
            else -> Trail.In(left)
        }
    }

    /** "Tue 20:00", or "Tue 8:00 PM" on a 12-hour phone. */
    fun weekdayTime(at: Long, is24: Boolean, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): String {
        val f = SimpleDateFormat(if (is24) "EEE HH:mm" else "EEE h:mm a", locale)
        f.timeZone = zone
        return f.format(Date(at))
    }

    /**
     * The same reset to be said, its day in full and its time apart ("Tuesday" and "3:56 PM"), for
     * TalkBack's "resets on Tuesday at 3:56 PM".
     */
    fun weekdayTimeSaid(at: Long, is24: Boolean, locale: Locale = Locale.getDefault(), zone: TimeZone = TimeZone.getDefault()): Pair<String, String> {
        val day = SimpleDateFormat("EEEE", locale).apply { timeZone = zone }.format(Date(at))
        val time = SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", locale).apply { timeZone = zone }.format(Date(at))
        return day to time
    }
}
