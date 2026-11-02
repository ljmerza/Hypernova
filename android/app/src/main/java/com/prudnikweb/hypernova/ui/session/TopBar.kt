package com.prudnikweb.hypernova.ui.session

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * What the session's top bar (the usage strip on the chat screen, UsageStrip.kt) shows, and in what
 * order: the owner's choice in Settings → Session top bar (owner, 2026-10-31). Pure, unit-tested.
 */
enum class TopBarItem(val key: String) {
    /** The context ring and its percentage. */
    CONTEXT("context"),
    /** The permission mode's glyph and word. */
    MODE("mode"),
    /** The effort ("high"), only for a Claude session that has one (owner, 2026-11-02: apart from the mode). */
    EFFORT("effort"),
    /** The plan's 5-hour window and its reset, only while Claude Code reports one. */
    FIVE_HOUR("fiveHour"),
    /** The plan's week and its reset. */
    SEVEN_DAY("sevenDay"),
    /** The model as a chip. */
    MODEL("model"),
    /** The tokens the session has used. */
    TOKENS("tokens"),
    /** What the session comes to at API prices ("$7.86", "≈$7.86" when worked out here), once there's a price. */
    COST("cost"),
    /** "3 agents" while agents work for it. */
    AGENTS("agents"),
    /** This turn's time ("4m") while it is in a turn. */
    TURN("turn"),
    ;

    companion object {
        fun of(key: String): TopBarItem? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The owner's choice: every item in his [order], and the ones [shown]. Kept as one line
 * ("context,mode,fiveHour,sevenDay,!model,…": a "!" before an item that's off), so a later item
 * the app adds comes in off, at the end, and an unknown one is passed over.
 */
data class TopBarChoice(val order: List<TopBarItem>, val shown: Set<TopBarItem>) {
    /** The items on, in the owner's order. */
    val items: List<TopBarItem> get() = order.filter { it in shown }

    fun set(item: TopBarItem, on: Boolean): TopBarChoice = copy(shown = if (on) shown + item else shown - item)

    /** [item] [by] places earlier (-1) or later (+1) in the order, the others closing up; past an end it stays. */
    fun move(item: TopBarItem, by: Int): TopBarChoice {
        val i = order.indexOf(item)
        val j = i + by
        if (i < 0 || by == 0 || j !in order.indices) return this
        val next = order.toMutableList()
        next.removeAt(i)
        next.add(j, item)
        return copy(order = next)
    }

    fun encode(): String = order.joinToString(",") { if (it in shown) it.key else "!${it.key}" }

    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        /**
         * The owner's default (2026-10-31): the context ring, mode, effort, the 5-hour window
         * (only when reported) and the week; the model, the tokens, the API cost, the agents and the
         * turn's time off, after them.
         */
        val DEFAULT = TopBarChoice(
            TopBarItem.entries.toList(),
            setOf(TopBarItem.CONTEXT, TopBarItem.MODE, TopBarItem.EFFORT, TopBarItem.FIVE_HOUR, TopBarItem.SEVEN_DAY),
        )

        /** [s] as [encode] wrote it; null or empty: [DEFAULT]. */
        fun decode(s: String?): TopBarChoice {
            if (s.isNullOrBlank()) return DEFAULT
            val order = ArrayList<TopBarItem>()
            val shown = HashSet<TopBarItem>()
            for (raw in s.split(',')) {
                val t = raw.trim()
                val off = t.startsWith("!")
                val item = TopBarItem.of(t.removePrefix("!")) ?: continue
                if (item in order) continue
                order += item
                if (!off) shown += item
            }
            // A line from when one item was both ("mode"): the effort stays where it was, as it was.
            if (TopBarItem.EFFORT !in order && TopBarItem.MODE in order) {
                order.add(order.indexOf(TopBarItem.MODE) + 1, TopBarItem.EFFORT)
                if (TopBarItem.MODE in shown) shown += TopBarItem.EFFORT
            }
            for (item in TopBarItem.entries) if (item !in order) order += item
            return TopBarChoice(order, shown)
        }
    }
}

/**
 * What a session has to show right now, for [TopBarChoice.visible]. [plan]: the plan's windows are
 * about this session and known (UsageStrip.planShown); [fiveHour]: Claude Code reports a 5-hour
 * window (Usage.fiveHourReported); [turnStartedAt]: 0 out of a turn.
 */
data class TopBarFacts(
    val codex: Boolean,
    val effort: String?,
    val tokens: Long,
    val agents: Int,
    val turnStartedAt: Long,
    val ended: Boolean,
    val plan: Boolean,
    val fiveHour: Boolean,
    /** What the session comes to at API prices; null where no price applies. */
    val cost: Double? = null,
) {
    /** The effort word: only for a Claude session that has one (Codex shows its mode alone). */
    val effortShown: Boolean get() = !codex && effort != null

    /** In a turn: its time shows ("4m"); nothing when idle or ended. */
    fun inTurn(now: Long): Boolean = !ended && turnStartedAt > 0L && now >= turnStartedAt
}

/**
 * The items the bar draws now, in the owner's order: the chosen ones that have something to say.
 * The 5-hour window only while Claude Code reports one; the plan's windows only while they're
 * about this session; the effort for a Claude session that has one; tokens once some are spent; the cost once there's one; agents while more than none work; the turn's
 * time while in a turn. Nothing drops for room: the bar scrolls sideways instead.
 */
fun TopBarChoice.visible(f: TopBarFacts, now: Long): List<TopBarItem> = items.filter {
    when (it) {
        TopBarItem.CONTEXT, TopBarItem.MODE, TopBarItem.MODEL -> true
        TopBarItem.FIVE_HOUR -> f.plan && f.fiveHour
        TopBarItem.SEVEN_DAY -> f.plan
        TopBarItem.EFFORT -> f.effortShown
        TopBarItem.TOKENS -> f.tokens > 0L
        TopBarItem.COST -> f.cost != null && f.cost > 0.0
        TopBarItem.AGENTS -> f.agents > 0
        TopBarItem.TURN -> f.inTurn(now)
    }
}

/** This turn's time as the bar says it: "<1m", "4m", "1h 12m". */
fun turnShort(ms: Long): String {
    val m = (ms.coerceAtLeast(0L) / 60_000L)
    return when {
        m < 1 -> "<1m"
        m < 60 -> "${m}m"
        else -> "${m / 60}h ${m % 60}m"
    }
}

/**
 * Settings → Session top bar, kept on this phone. One state for the whole app, so a change reaches
 * an open chat at once (as EnterKeyPref).
 */
object TopBarPref {
    private const val PREFS = "hypernova_ui"
    private const val KEY = "session_top_bar"
    private var current: MutableState<TopBarChoice>? = null

    fun state(context: Context): State<TopBarChoice> = holder(context)

    fun set(context: Context, value: TopBarChoice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.encode()).apply()
        holder(context).value = value
    }

    private fun holder(context: Context): MutableState<TopBarChoice> = current
        ?: mutableStateOf(TopBarChoice.decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))).also { current = it }
}
