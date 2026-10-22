package com.prudnikweb.hypernova.ui.automations

import android.content.res.Resources
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.Output
import com.prudnikweb.hypernova.data.Step
import com.prudnikweb.hypernova.data.Then
import com.prudnikweb.hypernova.data.Trigger
import com.prudnikweb.hypernova.data.WorkflowShape
import com.prudnikweb.hypernova.data.timed
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The app's words, as the summary and the run lines need them. On the phone they're the
 * resources ([ResWords]); a unit test reads the same strings file, so it checks the real copy.
 */
interface Words {
    fun s(id: Int, vararg args: Any): String
    fun p(id: Int, n: Int, vararg args: Any): String
    fun arr(id: Int): List<String>
    /** The phone shows 24-hour times. */
    val h24: Boolean
    val locale: Locale
}

class ResWords(private val res: Resources, override val h24: Boolean, override val locale: Locale = Locale.getDefault()) : Words {
    override fun s(id: Int, vararg args: Any): String = res.getString(id, *args)
    override fun p(id: Int, n: Int, vararg args: Any): String = res.getQuantityString(id, n, *args)
    override fun arr(id: Int): List<String> = res.getStringArray(id).toList()
}

/**
 * What the summary needs to know beyond the automation: the Mac's time zone (every time of day is
 * the Mac's), now, and names for the paths and ids it mentions.
 */
class SummaryContext(
    val zone: ZoneId,
    val now: Long,
    val projectName: (String) -> String = { it.trimEnd('/').substringAfterLast('/').ifEmpty { it } },
    /** A session's title by id, for "ask “Parser refactor” to …"; null when it's gone. */
    val sessionName: (String) -> String? = { null },
    val providerName: (String) -> String? = { null },
)

/**
 * The plain-English sentence an automation reads as (spec §6): trigger phrase + ", " + action
 * phrase + "." + then phrase. "Every weekday at 9:00, in hypernova, ask Claude to summarise what
 * changed… Notify me when it's done." Rebuilt as the editor changes; the card shows it too.
 */
object Summary {
    /** Prompts and texts are cut at this many characters, with "…". */
    const val CUT = 120

    fun sentence(w: Words, trigger: Trigger, action: Action, onlyOnce: Boolean, ctx: SummaryContext): String {
        val t = trigger(w, trigger, onlyOnce, ctx)
        val a = action(w, action, trigger, ctx)
        val body = w.s(R.string.au_sum_join, t, a)
        val end = if (endsSentence(a)) body else body + "."
        val then = when ((action as? Action.Start)?.then ?: (action as? Action.Continue)?.then) {
            Then.NOTIFY -> w.s(R.string.au_sum_then_notify)
            Then.SAY -> w.s(R.string.au_sum_then_say)
            else -> null
        }
        return if (then == null) end else "$end $then"
    }

    /**
     * A workflow's sentence: a simple one (one step, outputs `then` can say) exactly as today's;
     * otherwise its trigger, its first step and how many follow, then where the result goes. "When
     * hypernova gets a new pull request on GitHub, in hypernova, ask Claude to review it, then 2 more
     * steps. When it's done, post it to Slack."
     */
    fun flow(w: Words, trigger: Trigger, steps: List<Step>, outputs: List<Output>, onlyOnce: Boolean, ctx: SummaryContext): String {
        WorkflowShape.mirror(steps, outputs)?.let { return sentence(w, trigger, it, onlyOnce, ctx) }
        val t = trigger(w, trigger, onlyOnce, ctx)
        val first = steps.firstOrNull()?.let { action(w, it.action, trigger, ctx) } ?: w.s(R.string.au_sum_unknown_action)
        val more = steps.size - 1
        val body = if (more > 0) w.s(R.string.wf_sum_more, t, first, w.p(R.plurals.wf_more_steps, more, more)) else w.s(R.string.au_sum_join, t, first)
        val end = if (endsSentence(body)) body else "$body."
        val told = outputs.mapNotNull { told(w, it, ctx) }
        if (told.isEmpty()) return end
        return end + " " + w.s(R.string.wf_sum_outputs, andList(w, told))
    }

    /** One output as the sentence says it: "notify me", "post it to Slack", "add it to briefs.md". */
    fun told(w: Words, o: Output, ctx: SummaryContext): String? = when (o) {
        is Output.Notify -> w.s(if (o.glasses) R.string.wf_told_notify_glasses else R.string.wf_told_notify)
        is Output.Say -> w.s(R.string.wf_told_say)
        is Output.Webhook -> w.s(R.string.wf_told_webhook, presetName(w, o.preset))
        is Output.File -> w.s(R.string.wf_told_file, o.path.trimEnd('/').substringAfterLast('/').ifEmpty { o.path })
        is Output.Unknown -> null
    }

    /** A webhook preset's name: "Slack", "ntfy", "a web address". */
    fun presetName(w: Words, preset: String): String = w.s(
        when (preset) {
            Output.NTFY -> R.string.wf_preset_ntfy
            Output.SLACK -> R.string.wf_preset_slack
            Output.DISCORD -> R.string.wf_preset_discord
            Output.TELEGRAM -> R.string.wf_preset_telegram
            else -> R.string.wf_preset_post_said
        },
    )

    /** "a", "a and b", "a, b and c". */
    fun andList(w: Words, items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> w.s(R.string.au_and, items.dropLast(1).joinToString(", "), items.last())
    }

    /** "a", "a or b", "a, b or c". */
    private fun orList(w: Words, items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> w.s(R.string.wf_or, items.dropLast(1).joinToString(", "), items.last())
    }

    /** A phrase already ends a sentence: "…", "?", or a quote that does ("“What changed?”"). */
    internal fun endsSentence(s: String): Boolean {
        val t = s.trimEnd()
        if (t.isEmpty()) return false
        val last = t.last()
        if (last in ".!?…") return true
        if (last == '”' || last == '"') return t.length >= 2 && t[t.length - 2] in ".!?…"
        return false
    }

    // ─── When ──────────────────────────────────────────────────────────────

    fun trigger(w: Words, t: Trigger, onlyOnce: Boolean, ctx: SummaryContext): String {
        val base = when (t) {
            is Trigger.Time -> {
                val at = clock(w, t.at)
                when {
                    t.days.size == 7 -> w.s(R.string.au_sum_daily, at)
                    t.days == Trigger.WEEKDAYS -> w.s(R.string.au_sum_weekdays, at)
                    t.days == WEEKEND -> w.s(R.string.au_sum_weekend, at)
                    else -> w.s(R.string.au_sum_days, dayList(w, t.days), at)
                }
            }
            is Trigger.Once -> once(w, t.at, ctx)
            is Trigger.Every -> {
                val every = w.p(R.plurals.au_sum_every, t.hours, t.hours)
                // Around the clock it keeps one grid every day: "from" its first run of the day, the real
                // time ("Every 2 hours from 1:00", not "from 9:00", which also runs at 1:00).
                val first = EditorRules.firstOfDay(t.from, t.hours)
                val span = when {
                    t.to != null -> w.s(R.string.au_sum_every_between, clock(w, t.from), clock(w, t.to))
                    first != "00:00" -> w.s(R.string.au_sum_every_from, clock(w, first))
                    else -> ""
                }
                val days = when {
                    t.days.size == 7 -> ""
                    t.days == Trigger.WEEKDAYS -> w.s(R.string.au_sum_on_weekdays)
                    t.days == WEEKEND -> w.s(R.string.au_sum_on_weekends)
                    else -> w.s(R.string.au_sum_on_days, dayList(w, t.days))
                }
                every + span + days
            }
            is Trigger.Reset -> {
                val window = window(w, t.window)
                val base = if (t.afterLimit) w.s(R.string.au_sum_reset_after, window) else w.s(R.string.au_sum_reset_any, window)
                if (t.from != null && t.to != null) base + w.s(R.string.au_sum_reset_between, clock(w, t.from), clock(w, t.to)) else base
            }
            is Trigger.Limit -> w.s(R.string.au_sum_limit_hit, window(w, t.window))
            is Trigger.SessionEvent -> {
                val event = when {
                    t.event != Trigger.NEEDS_YOU -> w.s(R.string.au_sum_event_finished)
                    t.after != null -> w.p(R.plurals.au_sum_event_needs_after, t.after, t.after)
                    else -> w.s(R.string.au_sum_event_needs)
                }
                val p = t.projectPath?.let(ctx.projectName)
                when (t.from) {
                    Trigger.AUTOMATED -> if (p != null) w.s(R.string.au_sum_session_auto_in, p, event) else w.s(R.string.au_sum_session_auto_any, event)
                    Trigger.ANY -> if (p != null) w.s(R.string.au_sum_session_all_in, p, event) else w.s(R.string.au_sum_session_all_any, event)
                    else -> if (p != null) w.s(R.string.au_sum_session_mine_in, p, event) else w.s(R.string.au_sum_session_mine_any, event)
                }
            }
            Trigger.Plugged -> w.s(R.string.au_sum_plugged)
            is Trigger.BatteryLow -> w.s(R.string.au_sum_battery, t.below)
            is Trigger.Webhook -> w.s(R.string.wf_sum_webhook)
            is Trigger.Cron -> w.s(R.string.wf_sum_cron, t.expr.trim())
            is Trigger.FileWatch -> {
                val p = ctx.projectName(t.path)
                val glob = t.glob?.trim()?.takeIf { it.isNotEmpty() }
                when (t.event) {
                    Trigger.CREATED -> if (glob != null) w.s(R.string.wf_sum_file_created_glob, glob, p) else w.s(R.string.wf_sum_file_created, p)
                    Trigger.DELETED -> if (glob != null) w.s(R.string.wf_sum_file_deleted_glob, glob, p) else w.s(R.string.wf_sum_file_deleted, p)
                    else -> if (glob != null) w.s(R.string.wf_sum_file_changed_glob, glob, p) else w.s(R.string.wf_sum_file_changed, p)
                }
            }
            is Trigger.Github -> {
                val news = Trigger.GITHUB_EVENTS.filter { it in t.events }.map { e ->
                    w.s(
                        when (e) {
                            Trigger.PR_OPENED -> R.string.wf_gh_pr_opened
                            Trigger.REVIEW_COMMENT -> R.string.wf_gh_review
                            else -> R.string.wf_gh_checks
                        },
                    )
                }
                w.s(R.string.wf_sum_github, ctx.projectName(t.repoPath), orList(w, news.ifEmpty { listOf(w.s(R.string.wf_gh_pr_opened)) }))
            }
            is Trigger.Hook -> {
                val who = w.s(
                    when (t.from) {
                        Trigger.AUTOMATED -> R.string.wf_hook_who_auto
                        Trigger.ANY -> R.string.wf_hook_who_any
                        else -> R.string.wf_hook_who_mine
                    },
                )
                val subject = t.projectPath?.let { w.s(R.string.wf_hook_who_in, who, ctx.projectName(it)) } ?: who
                val what = when (t.event) {
                    Trigger.H_STOP -> w.s(R.string.wf_hook_stop)
                    Trigger.H_STOP_FAILURE -> w.s(R.string.wf_hook_stop_failure)
                    Trigger.H_TOOL_FAILURE -> t.tool?.let { w.s(R.string.wf_hook_tool_failure_named, it) } ?: w.s(R.string.wf_hook_tool_failure)
                    Trigger.H_PRE_COMPACT -> w.s(R.string.wf_hook_pre_compact)
                    Trigger.H_SESSION_END -> w.s(R.string.wf_hook_session_end)
                    else -> t.event
                }
                w.s(R.string.wf_sum_hook, subject, what)
            }
            is Trigger.Unknown -> w.s(R.string.au_sum_unknown_trigger)
        }
        // "Once" is once already; time triggers repeat by nature.
        return if (onlyOnce && !t.timed) w.s(R.string.au_sum_only_once, base) else base
    }

    private val WEEKEND = listOf(6, 7)

    private fun window(w: Words, window: String) =
        w.s(if (window == Trigger.SEVEN_DAY) R.string.au_sum_window_week else R.string.au_sum_window_five)

    /** "Today at 15:30", "Tomorrow at 9:00", "On Fri 17 Oct at 9:00", on the Mac's calendar. */
    fun once(w: Words, at: Long, ctx: SummaryContext): String {
        val day = Instant.ofEpochMilli(at).atZone(ctx.zone)
        val today = Instant.ofEpochMilli(ctx.now).atZone(ctx.zone).toLocalDate()
        val time = clock(w, day.toLocalTime())
        return when (day.toLocalDate()) {
            today -> w.s(R.string.au_sum_once_today, time)
            today.plusDays(1) -> w.s(R.string.au_sum_once_tomorrow, time)
            else -> w.s(R.string.au_sum_once_date, DateTimeFormatter.ofPattern("EEE d MMM", w.locale).format(day), time)
        }
    }

    /** "Mon, Wed and Fri". */
    fun dayList(w: Words, days: List<Int>): String {
        val names = w.arr(R.array.au_day_short)
        val list = days.filter { it in 1..7 }.sorted().map { names[it - 1] }
        return when (list.size) {
            0 -> ""
            1 -> list[0]
            else -> w.s(R.string.au_and, list.dropLast(1).joinToString(", "), list.last())
        }
    }

    /** "09:00" → "9:00", or "9:00 AM" on a 12-hour phone. */
    fun clock(w: Words, hhmm: String): String = runCatching { clock(w, LocalTime.parse(hhmm)) }.getOrDefault(hhmm)

    fun clock(w: Words, t: LocalTime): String = DateTimeFormatter.ofPattern(if (w.h24) "H:mm" else "h:mm a", w.locale).format(t)

    // ─── Do ────────────────────────────────────────────────────────────────

    fun action(w: Words, a: Action, trigger: Trigger, ctx: SummaryContext): String = when (a) {
        is Action.Start -> {
            val project = ctx.projectName(a.projectPath)
            val prompt = cut(a.prompt)
            when {
                prompt.isEmpty() -> w.s(R.string.au_sum_start_empty, project)
                // A word joiner after the slash, so "/release-notes" never breaks after its "/".
                a.skill -> w.s(R.string.au_sum_skill, project, prompt.replaceFirst("/", "/\u2060"))
                // "ask Claude to …", or Codex for a step Codex runs.
                a.providerId == com.prudnikweb.hypernova.data.AgentKind.CODEX_PROVIDER ->
                    verbPhrase(prompt)?.let { w.s(R.string.au_sum_start_verb_codex, project, it) } ?: w.s(R.string.au_sum_start_quote_codex, project, prompt)
                else -> verbPhrase(prompt)?.let { w.s(R.string.au_sum_start_verb, project, it) } ?: w.s(R.string.au_sum_start_quote, project, prompt)
            }
        }
        is Action.Continue -> {
            val target = when (a.target) {
                Action.Continue.TRIGGER -> w.s(R.string.au_sum_target_trigger)
                Action.Continue.WORKING -> w.s(R.string.au_sum_target_working)
                Action.Continue.LIMITED -> w.s(R.string.au_sum_target_limited)
                else -> if (a.target.startsWith(Action.Continue.STEP)) w.s(R.string.wf_sum_target_step) else ctx.sessionName(a.target)?.let { w.s(R.string.au_sum_target_named, cut(it)) } ?: w.s(R.string.au_sum_target_gone)
            }
            val prompt = cut(a.prompt)
            val provider = a.providerId?.let { ctx.providerName(it) ?: it }
            when {
                provider != null && prompt.isEmpty() -> w.s(R.string.au_sum_continue_on, target, provider)
                provider != null -> w.s(R.string.au_sum_continue_on_quote, target, provider, prompt)
                prompt.isEmpty() -> w.s(R.string.au_sum_continue_empty, target)
                else -> verbPhrase(prompt)?.let { w.s(R.string.au_sum_continue_verb, target, it) } ?: w.s(R.string.au_sum_continue_quote, target, prompt)
            }
        }
        is Action.Notify -> cut(a.text).let {
            when {
                a.glasses && it.isEmpty() -> w.s(R.string.au_sum_notify_glasses_empty)
                a.glasses -> w.s(R.string.au_sum_notify_glasses, it)
                it.isEmpty() -> w.s(R.string.au_sum_notify_empty)
                else -> w.s(R.string.au_sum_notify, it)
            }
        }
        is Action.Say -> cut(a.text).let { if (it.isEmpty()) w.s(R.string.au_sum_say_empty) else w.s(R.string.au_sum_say, it) }
        is Action.Ask -> cut(a.question).let { if (it.isEmpty()) w.s(R.string.wf_sum_ask_empty) else w.s(R.string.wf_sum_ask, it) }
        is Action.Shell -> {
            // A zero-width non-joiner between dashes, so "--silent" never draws as one long dash.
            val command = cut(a.command).replace("--", "-\u200C-")
            if (command.isEmpty()) w.s(R.string.wf_sum_shell_empty, ctx.projectName(a.projectPath)) else w.s(R.string.wf_sum_shell, ctx.projectName(a.projectPath), command)
        }
        is Action.Unknown -> w.s(R.string.au_sum_unknown_action)
    }

    /**
     * A prompt that starts with a common imperative verb reads on after "ask Claude to", first
     * letter lowered ("Summarise what changed" → "summarise what changed"); null otherwise, and it
     * goes in quotes. Only the first word decides.
     */
    fun verbPhrase(prompt: String): String? {
        val first = prompt.trimStart().takeWhile { it.isLetter() || it == '\'' || it == '-' }
        if (first.isEmpty() || first.lowercase(Locale.ROOT) !in VERBS) return null
        // Never lower an acronym or a name that happens to follow ("Run CI" keeps "CI").
        return prompt.trimStart().replaceFirstChar { it.lowercaseChar() }
    }

    /** The first line, spaces collapsed, cut at [CUT] characters on a word with "…". */
    fun cut(text: String): String {
        val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.replace(SPACES, " ").orEmpty()
        val more = text.trim().lines().count { it.isNotBlank() } > 1
        if (line.length <= CUT && !more) return line
        if (line.length <= CUT) return if (Summary.endsSentence(line)) line else "$line…"
        val room = line.take(CUT)
        val at = room.lastIndexOf(' ').takeIf { it > CUT / 2 } ?: CUT
        return room.take(at).trimEnd(' ', ',', ';', ':', '.', '—', '-') + "…"
    }

    private val SPACES = Regex("\\s+")

    /** Verbs a prompt to Claude commonly starts with (spec §6: a fixed list of about 60). */
    val VERBS = setOf(
        "summarise", "summarize", "review", "check", "run", "fix", "look", "write", "update", "find",
        "list", "make", "build", "test", "add", "remove", "delete", "clean", "refactor", "explain",
        "read", "draft", "prepare", "compare", "scan", "search", "collect", "gather", "tidy", "sort",
        "rename", "translate", "describe", "analyse", "analyze", "audit", "measure", "count", "report",
        "tell", "show", "plan", "create", "generate", "open", "close", "commit", "push", "pull",
        "merge", "rebase", "deploy", "publish", "ship", "upgrade", "bump", "format", "lint", "document",
        "investigate", "debug", "profile", "benchmark", "watch", "triage", "archive", "finish", "go",
        "continue", "pick", "note", "sum", "verify", "validate", "install", "set", "try", "keep",
    )
}
