package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.TerminalAsk
import com.prudnikweb.hypernova.data.WApproval
import com.prudnikweb.hypernova.widget.WidgetModel

/**
 * What a Home card offers for the approval its session waits on, in place of its model and agents.
 * Tool asks only (the owner's choice). The exact command shows when it fits whole (the widget's
 * rule, [WidgetModel.answerable]); Allow is there either way (the owner, 2026-10-30).
 */
sealed interface CardAsk {
    val id: String

    /** A tool's exact command, whole and as it will run ([text]): Deny and Allow. */
    data class Exact(override val id: String, val tool: String, val text: String) : CardAsk

    /** A tool whose command can't show whole here (too long, masked, odd characters): its summary, Deny and Allow. */
    data class Partial(override val id: String) : CardAsk

    /** A question or a plan: Answer opens the session. */
    data class Open(override val id: String) : CardAsk

    /**
     * Fork: Claude Code's own permission dialog in the session's terminal ([TerminalAsk]; [id] is its
     * menu's): the tool and command as the terminal shows them, Deny and Allow, answered there
     * through the menu route. Its other options are the chat's.
     */
    data class Terminal(override val id: String, val ask: TerminalAsk) : CardAsk

    companion object {
        fun of(a: WApproval?): CardAsk? = when {
            a == null -> null
            a.kind == "tool" -> WidgetModel.answerable(a)?.let { Exact(a.id, a.toolName, it) } ?: Partial(a.id)
            else -> Open(a.id)
        }

        /** [s]'s approval, else the permission dialog its terminal shows (none while an approval waits). */
        fun of(a: WApproval?, s: Session): CardAsk? = of(a) ?: TerminalAsk.of(s)?.let { Terminal(it.menuId, it) }
    }
}
