package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.AllowRule
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Secrets
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TerminalAsk
import com.prudnikweb.hypernova.data.WApproval

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

/**
 * What a card offers for what its session waits on. **Allow only beside the whole command as it will run**
 * ([AllowRule], the rule of a phone's notification and widget). The phone's own Home card has Allow either way
 * (the owner, 2026-10-30, CardAsk.kt), since a tap on that card opens the chat for the details; there's no chat
 * here to read them in, so this keeps the stricter rule. Deny always, for a tool.
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
