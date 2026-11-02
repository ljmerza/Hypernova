package com.prudnikweb.hypernova.ui.session

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.prudnikweb.hypernova.data.CodexState
import com.prudnikweb.hypernova.data.ModelCatalog
import com.prudnikweb.hypernova.data.ModelOption
import com.prudnikweb.hypernova.data.ModelWeek
import com.prudnikweb.hypernova.data.Usage
import com.prudnikweb.hypernova.data.UsageWindow
import kotlin.math.roundToInt

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
    /** The day of the plan's week and its use against an even week ("Day 2/7 +10%"): [WeekPace]. */
    WEEK_PACE("weekPace"),
    /** The branch and the lines changed ("⎇ main +120 −30"), in a repository. */
    GIT("git"),
    /** The week of the model this session runs, when it has one of its own ("FABLE 41% ↻ Tue 20:00"). */
    MODEL_WEEK("modelWeek"),
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
         * (only when reported) and the week; the model, the tokens, the API cost, the agents, the
         * turn's time, the week's pace, git and the model's week off, after them.
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
    /** The week's pace ([weekPaceOf]); null when no week is known that's about this session. */
    val weekPace: WeekPace? = null,
    /** The changes route said where this session's folder stands: a repository, and it answered (rememberTopBarGit). */
    val git: Boolean = false,
    /** The week of the model this session runs, when it has one of its own ([modelWeekOf]). */
    val modelWeek: ModelWeek? = null,
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
 * time while in a turn; the week's pace while a week is known that's about this session; git in a repository; the model's week while the plan's windows show and the model has one of its own. Nothing drops for room: the bar scrolls sideways instead.
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
        TopBarItem.WEEK_PACE -> f.weekPace != null
        TopBarItem.GIT -> f.git
        TopBarItem.MODEL_WEEK -> f.plan && f.modelWeek != null
    }
}

/**
 * Where a week stands, as the bar's "Day 2/7 +10%" says it: the [day] it's on, counted from the
 * reset, and [points], its use less the share of the week gone. A subtraction of two measured
 * numbers, drawn in a neutral colour: no rate, no forecast and no time to the limit
 * (docs/DECISIONS.md, "The week's pace in the top bar").
 */
data class WeekPace(val day: Int, val points: Int) {
    /** "+10%", "-8%", "+0%": always with a sign. */
    val figure: String get() = if (points < 0) "$points%" else "+$points%"
}

private const val DAY_MS = 24 * 3_600_000L
private const val WEEK_MS = 7 * DAY_MS

/**
 * [used] (0..1) of a seven-day window that resets at [resetAt], at [now]. The day is whole days
 * gone rounded up, 1..7, counted in whole milliseconds so the moment a day starts is exact. Null
 * when the reset isn't known or has passed: the numbers are the last week's, and today's aren't in.
 */
fun weekPace(used: Float, resetAt: Long, now: Long): WeekPace? {
    if (resetAt <= 0L || now >= resetAt) return null
    val gone = (WEEK_MS - (resetAt - now)).coerceIn(0L, WEEK_MS)
    val day = ((gone + DAY_MS - 1) / DAY_MS).toInt().coerceIn(1, 7)
    val points = ((used.coerceIn(0f, 1f).toDouble() - gone.toDouble() / WEEK_MS) * 100).roundToInt()
    return WeekPace(day, points)
}

/**
 * The pace of the week about this session: a Codex session's is Codex's own week, as Home's Codex
 * card has it, while signed in; any other's the plan's, only while [plan] (UsageStrip.planShown:
 * on the subscription's own provider, known and not stale). Null otherwise.
 */
fun weekPaceOf(codexSession: Boolean, plan: Boolean, usage: Usage, codex: CodexState?, now: Long): WeekPace? {
    if (codexSession) {
        val week = codex?.takeIf { it.account?.signedIn != false }?.usage?.sevenDay ?: return null
        return weekPace((week.pct / 100.0).toFloat(), week.resetsAt, now)
    }
    if (!plan) return null
    return weekPace(UsageWindow.sevenDay(usage, now).used, usage.sevenDayResetAt, now)
}

/**
 * The week of its own that [model] (an id, an alias or a name, as the bar names the session's model)
 * spends on the plan, matched by family as the model pickers do; null when it has none or isn't
 * known yet. [weeks] (`Usage.models`) are read off Claude Code's /usage screen every 15 minutes,
 * so this can be up to 15 minutes old (docs/PROTOCOL.md, "Per-model weeks").
 */
fun modelWeekOf(weeks: List<ModelWeek>, model: String): ModelWeek? =
    ModelCatalog.week(ModelOption(value = null, name = model, model = model), weeks)

/** A week's short name for the bar: "Fable 5.1" is "Fable". */
val ModelWeek.shortLabel: String get() = label.trim().substringBefore(' ')

/**
 * Where a session stands, for when the git item asks the changes route again (rememberTopBarGit,
 * which says why): [inTurn], [edits] edit tools finished in its transcript, its last [activity], and
 * whether the Mac is [linked].
 */
data class GitMoment(val inTurn: Boolean, val edits: Int, val activity: Long, val linked: Boolean) {
    /**
     * Worth asking again, coming from [before]: the link came back, a tool edited a file in a turn,
     * the turn ended, or, out of a turn, there was activity (a whole turn the phone slept through).
     * Never while unlinked, as a turn starting says nothing new, and nothing else in a turn does.
     */
    fun asksAgainAfter(before: GitMoment): Boolean = linked && when {
        !before.linked -> true
        inTurn -> edits != before.edits
        before.inTurn -> true
        else -> activity != before.activity
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
