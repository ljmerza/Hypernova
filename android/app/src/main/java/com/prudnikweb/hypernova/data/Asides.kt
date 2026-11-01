package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf

/**
 * Side questions asked from this phone (owner, 2026-10-30: "/btw" from the prompt box, as the
 * session sheet's Commands tab asks it; docs/DECISIONS.md, "/btw from the prompt box"). Claude
 * Code keeps them out of the transcript, so the chat has them from here: each one's "/btw …" line
 * in its place in time among the chat's items ([into]), and its answer in a card over the prompt
 * box until it's swiped away. In the app's memory per session: leaving and reopening the session
 * keeps them, a restart of the app doesn't.
 */
object Asides {
    /** One side question as this phone has it: [chat] when it came from the prompt box, [dismissed] once its card was let go. */
    @Immutable
    data class Entry(val aside: Aside, val chat: Boolean, val dismissed: Boolean = false)

    /** The most kept per session. */
    const val MAX = 20

    /** The key of the "/btw" line a side question puts in the chat (never an event id of the Mac's). */
    const val KEY = "aside:"

    private val bySession = mutableStateMapOf<String, List<Entry>>()

    /** Session [sessionId]'s side questions, oldest first. */
    fun of(sessionId: String): List<Entry> = bySession[sessionId].orEmpty()

    /** The one in the card over the prompt box: the newest from the prompt box, not let go. */
    fun card(sessionId: String): Aside? = of(sessionId).lastOrNull()?.takeIf { it.chat && !it.dismissed }?.aside

    /** The newest, wherever it was asked. */
    fun last(sessionId: String): Aside? = of(sessionId).lastOrNull()?.aside

    /** [a] as it stands now ([chat]: asked from the prompt box; an update keeps where it was asked). */
    fun note(sessionId: String, a: Aside, chat: Boolean = false) {
        bySession[sessionId] = merged(of(sessionId), a, chat)
    }

    /** A rewind replaced session [old] with [to]: its side questions go on there. */
    fun moved(old: String, to: String) {
        val list = bySession.remove(old) ?: return
        bySession[to] = list + of(to)
    }

    /** The card's swipe: [id] stays in the chat and the tab, its card goes. */
    fun dismiss(sessionId: String, id: String) {
        bySession[sessionId] = of(sessionId).map { if (it.aside.id == id) it.copy(dismissed = true) else it }
    }

    /** Forget [id] (a question that was never asked after all). */
    fun remove(sessionId: String, id: String) {
        bySession[sessionId] = of(sessionId).filterNot { it.aside.id == id }
    }

    // ── Pure parts (unit-tested) ─────────────────────────────────────────

    private val BTW = Regex("^/btw(?:\\s+([\\s\\S]*))?$")

    /**
     * The question in what's typed when it's Claude Code's side question ("/btw why is it slow?"),
     * else null: a bare "/btw" (Claude Code only shows how to use it), any other command, or text.
     */
    fun sideQuestion(text: String): String? {
        val m = BTW.find(text.trim()) ?: return null
        return m.groupValues[1].replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
    }

    /** [list] with [a] in it: replaced where it is (keeping how it was asked), or added, oldest first, the newest [MAX]. */
    fun merged(list: List<Entry>, a: Aside, chat: Boolean): List<Entry> {
        val i = list.indexOfFirst { it.aside.id == a.id }
        val out = if (i >= 0) list.toMutableList().also { it[i] = it[i].copy(aside = a) } else list + Entry(a, chat)
        return out.sortedBy { it.aside.askedAt }.takeLast(MAX)
    }

    /** The "/btw …" line [e] puts in the chat: Claude Code's own command, as it was typed. */
    fun event(e: Entry): WEvent.User =
        WEvent.User("$KEY${e.aside.id}", e.aside.askedAt, "/btw ${e.aside.question}", command = CommandRun("/btw", e.aside.question, "local"))

    /**
     * [events] with each side question's line where it falls in time: before the first event after
     * it was asked. The Mac's clock stamps both, so they agree.
     */
    fun into(events: List<WEvent>, asides: List<Entry>): List<WEvent> {
        if (asides.isEmpty()) return events
        val lines = asides.map(::event).sortedBy { it.ts }
        val out = ArrayList<WEvent>(events.size + lines.size)
        var k = 0
        for (e in events) {
            while (k < lines.size && lines[k].ts < e.ts) out += lines[k++]
            out += e
        }
        while (k < lines.size) out += lines[k++]
        return out
    }
}
