package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.AllowRule
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Blocked
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.ModelNames
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Secrets
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TerminalAsk
import com.prudnikweb.hypernova.data.WApproval
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/*
 * What a session card says and offers, as plain Kotlin for the tests; ui/SessionsScreen.kt only draws it.
 */

/** The list's groups, as on the phone's Home (HomeScreen.kt, docs/DESIGN.md, "Group seams"). */
enum class Group { NeedsInput, Working, Completed }

fun groupOf(s: Session): Group = when {
    s.state == SessionState.NeedsYou -> Group.NeedsInput
    // Working by the one rule: its agents count, though its main agent is idle.
    s.busy -> Group.Working
    // Idle or done, and what a restart of the server cut short.
    else -> Group.Completed
}

/** The sessions by group, empty groups left out; each A to Z by name, then id, so a card moves only when its group changes (the phone's default sort). */
fun groupsOf(sessions: List<Session>): List<Pair<Group, List<Session>>> {
    val order = compareBy<Session, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }
    return Group.entries.mapNotNull { g ->
        sessions.filter { groupOf(it) == g }.sortedWith(order).takeIf { it.isNotEmpty() }?.let { g to it }
    }
}

/** What a Working card says it's doing: the to-do under way with its tally, else its activity (the phone's CardNow.kt). */
fun doingLine(s: Session): String {
    val t = s.todo
    val active = t?.active?.trim()
    return if (t != null && t.total > 0 && !active.isNullOrEmpty()) "$active ${t.done}/${t.total}" else s.activity
}

/** The sessions' ids in the list's order, which the arrow keys walk. */
fun orderOf(groups: List<Pair<Group, List<Session>>>): List<String> = groups.flatMap { (_, members) -> members.map { it.id } }

/** Where [id]'s row is among the list's items: each group has a seam before its rows. */
fun itemIndexOf(groups: List<Pair<Group, List<Session>>>, id: String): Int? {
    var index = 0
    for ((_, members) in groups) {
        index++
        val at = members.indexOfFirst { it.id == id }
        if (at >= 0) return index + at
        index += members.size
    }
    return null
}

/** The row [step] from [from], stopping at the ends. From nothing, or a row that's gone: the first going down, the last going up. */
fun neighbour(order: List<String>, from: String?, step: Int): String? {
    val at = order.indexOf(from)
    return when {
        order.isEmpty() -> null
        at < 0 -> if (step > 0) order.first() else order.last()
        else -> order[(at + step).coerceIn(0, order.lastIndex)]
    }
}

/** A row of the list's one line, and what it's about. */
data class RowLine(val text: String, val tone: Tone) {
    enum class Tone { Ask, Danger, Quiet }
}

/**
 * What a row says on its line. A row has no pills: its line can be cut, and Allow goes only beside the whole
 * command ([CardOffer]), which is in the pane beside it.
 */
fun rowLine(offer: CardOffer?, s: Session): RowLine = when {
    offer is CardOffer.Whole -> RowLine(AllowRule.oneLine(offer.text), RowLine.Tone.Ask)
    offer is CardOffer.NotWhole -> RowLine(offer.detail, RowLine.Tone.Ask)
    offer is CardOffer.OnPhone -> RowLine(onPhoneWords(offer), RowLine.Tone.Ask)
    s.diedLine -> RowLine(Ui.card_job_died.text(), RowLine.Tone.Danger)
    s.state == SessionState.NeedsYou -> RowLine(s.activity, RowLine.Tone.Ask)
    else -> RowLine(doingLine(s), RowLine.Tone.Quiet)
}

fun onPhoneWords(offer: CardOffer.OnPhone): String = if (offer.plan) Ui.desk_answer_on_phone_plan.text() else Ui.desk_answer_on_phone_question.text()

/** A row has a chip only for what its group's seam doesn't already say: paused, starting, an error, ended. */
fun rowHasChip(s: Session, block: Blocked?): Boolean = block != null || s.phase == Phase.Starting || s.phase == Phase.Error || s.phase == Phase.Ended

/** A line of the pane's facts. */
data class Fact(val label: String, val value: String)

/**
 * What's known of a session without opening it, from what the list already has, in the phone's session sheet's words
 * and rules (SessionSheet's Where it runs, SessionInfoModel's times). Nothing is fetched, so nothing can fail here.
 */
fun factsOf(s: Session, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Fact> = buildList {
    s.cwd?.let { add(Fact(Ui.session_sheet_folder.text(), it)) }
    add(Fact(Ui.model.text(), ModelNames.chip(s.model)))
    add(Fact(Ui.mode.text(), modeWord(s.mode)))
    val runs = when {
        s.term != null -> Ui.session_sheet_runs_terminal
        s.origin == "hypernova" -> Ui.session_sheet_runs_mac
        else -> Ui.session_sheet_runs_watched
    }
    add(Fact(Ui.session_sheet_runs_in.text(), runs.text()))
    add(Fact(Ui.session_sheet_agent.text(), if (s.codex) Ui.cx_agent_codex.text() else Ui.claude.text()))
    if (s.agentsRunning > 0 && s.phase != Phase.Ended) add(Fact(Ui.session_sheet_agents.text(), Ui.agents_running.format(s.agentsRunning)))
    if (s.queued > 0) add(Fact(Ui.queued_label.text(), Ui.queued.format(s.queued)))
    s.contextPct?.let { add(Fact(Ui.st_top_bar_context.text(), "${it.roundToInt()}%")) }
    val ended = s.phase == Phase.Ended
    if (s.createdAt > 0L) {
        add(Fact(Ui.session_sheet_started.text(), clockWords(s.createdAt, now, zone)))
        val ran = ((if (ended) s.updatedAt else now) - s.createdAt).coerceAtLeast(0L)
        add(Fact((if (ended) Ui.session_sheet_ran_for else Ui.session_sheet_running_for).text(), durationWords(ran)))
    }
    val inTurn = s.phase == Phase.Starting || s.phase == Phase.Working || s.phase == Phase.NeedsYou
    if (inTurn && s.turnStartedAt > 0L) add(Fact(Ui.session_sheet_this_turn.text(), durationWords((now - s.turnStartedAt).coerceAtLeast(0L))))
    if (s.updatedAt > 0L) add(Fact(Ui.session_sheet_last_activity.text(), agoWords((now - s.updatedAt).coerceAtLeast(0L))))
}

/** A permission mode in plain words, as the phone's Home says it ("ask first", "accept edits"). */
fun modeWord(mode: String): String = when (mode) {
    "default" -> Ui.mode_label_default.text()
    "acceptEdits" -> Ui.mode_label_accept.text()
    "plan" -> Ui.mode_label_plan.text()
    "auto" -> Ui.mode_label_auto.text()
    "bypassPermissions" -> Ui.mode_label_bypass.text()
    "dontAsk" -> Ui.mode_label_dont_ask.text()
    "read-only" -> Ui.cx_mode_label_read_only.text()
    "workspace-write" -> Ui.cx_mode_label_can_edit.text()
    else -> mode
}

/** A clock time on this computer: today's alone, this week's with its day, older with its date. */
fun clockWords(at: Long, now: Long, zone: ZoneId): String {
    val whenThen = Instant.ofEpochMilli(at).atZone(zone)
    val days = ChronoUnit.DAYS.between(whenThen.toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    val time = DesktopStrings.timePattern(false)
    val pattern = when (days) {
        0L -> time
        in -6L..6L -> "EEE $time"
        else -> "d MMM $time"
    }
    return DateTimeFormatter.ofPattern(pattern, DesktopStrings.locale).format(whenThen)
}

/** How long, as the phone's Countdown words it. */
fun durationWords(ms: Long): String {
    val s = (ms + 999) / 1000
    return when {
        s < 60 -> Ui.dur_s.format(0, s)
        s < 600 -> Ui.dur_s.format(s / 60, s % 60)
        s < 3600 -> Ui.dur_m.format(s / 60)
        s < 86400 -> Ui.dur_hm.format(s / 3600, (s % 3600) / 60)
        else -> Ui.dur_dh.format(s / 86400, (s % 86400) / 3600)
    }
}

/** How long ago, as a phone's Completed card says it. */
fun agoWords(ms: Long): String {
    val minutes = ms / 60_000
    fun said(name: String, n: Long) = DesktopStrings.quantityNamed(name, n.toInt()).format(n)
    return when {
        minutes < 1 -> Ui.meta_ago_now.text()
        minutes < 60 -> said("meta_ago_minutes", minutes)
        minutes < 1440 -> said("meta_ago_hours", minutes / 60)
        else -> said("meta_ago_days", minutes / 1440)
    }
}

/**
 * What a card offers for what its session waits on. **Allow only beside the whole command as it will run**
 * ([AllowRule], the rule of a phone's notification and widget). The phone's own Home card has Allow either way
 * (the owner, 2026-10-30, CardAsk.kt), since a tap on that card opens the chat for the details; there's no chat
 * here to read them in, so this keeps the stricter rule. Deny always, for a tool. The pills are in the pane beside the list.
 */
sealed interface CardOffer {
    val id: String

    /** The whole command, unmasked and uncut ([text]): Deny and Allow. */
    data class Whole(override val id: String, val tool: String, val text: String, val terminal: TerminalAsk? = null) : CardOffer

    /** A tool whose command can't be read whole here: masked and on one line ([detail]), Deny only. */
    data class NotWhole(override val id: String, val tool: String, val detail: String, val terminal: TerminalAsk? = null) : CardOffer

    /** A question or a plan: it says it needs answering on the phone. Nothing to press. */
    data class OnPhone(override val id: String, val plan: Boolean) : CardOffer

    companion object {
        /** [a], the approval [s] waits on, else the permission dialog in its terminal. */
        fun of(a: WApproval?, s: Session): CardOffer? = when {
            a != null -> of(a)
            else -> TerminalAsk.of(s)?.let { of(it) }
        }

        fun of(a: WApproval): CardOffer = when (a.kind) {
            "tool" -> AllowRule.answerable(a)?.let { Whole(a.id, a.toolName, it) }
                ?: NotWhole(a.id, a.toolName, masked(AllowRule.detail(a).first))
            else -> OnPhone(a.id, plan = a.kind == "plan")
        }

        /** Claude Code's own dialog in the terminal: the same rule, on what it shows. */
        fun of(t: TerminalAsk): CardOffer {
            val cmd = t.command?.trim()?.takeIf { it.isNotEmpty() }
            val tool = t.tool.orEmpty()
            val whole = cmd?.let { AllowRule.exactText(it) }
            return if (whole != null) Whole(t.menuId, tool, whole, t) else NotWhole(t.menuId, tool, masked(cmd ?: t.lines.lastOrNull().orEmpty()), t)
        }

        private fun masked(text: String): String = AllowRule.oneLine(Secrets.shown(text.trim(), command = true)).take(AllowRule.DETAIL_MAX)
    }
}

/** Sends a card's Allow or Deny: an approval's through the approval route, a terminal dialog's as its Yes or No. True once the server has it. */
suspend fun answerCard(hub: Hub, sessionId: String, offer: CardOffer, allow: Boolean, say: (String) -> Unit): Boolean {
    val terminal = when (offer) {
        is CardOffer.Whole -> offer.terminal
        is CardOffer.NotWhole -> offer.terminal
        is CardOffer.OnPhone -> return false
    }
    // No Allow for what can't be read, whatever calls this.
    if (allow && offer !is CardOffer.Whole) return false
    if (terminal != null) {
        val r = hub.answerMenu(sessionId, offer.id, if (allow) terminal.allow else terminal.deny)
        r.exceptionOrNull()?.message?.let(say)
        return r.isSuccess
    }
    // A refusal is said by the hub's own toast (Core.toastSink).
    return hub.answerApproval(offer.id, if (allow) Answer.Allow else Answer.Deny)
}
