package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.WApproval
import com.prudnikweb.hypernova.widget.WidgetModel

/**
 * What a Home card offers for the approval its session waits on, in place of its model and agents.
 * Tool asks only (the owner's choice), by the widget's rule ([WidgetModel.answerable]): Allow
 * promises the exact command, so it shows only when that shows whole.
 */
sealed interface CardAsk {
    val id: String

    /** A tool's exact command, whole and as it will run ([text]): Deny and Allow. */
    data class Exact(override val id: String, val tool: String, val text: String) : CardAsk

    /** A tool whose command can't show whole here (too long, masked, odd characters): its summary, Deny and Open. */
    data class Partial(override val id: String) : CardAsk

    /** A question or a plan: Answer opens the session. */
    data class Open(override val id: String) : CardAsk

    companion object {
        fun of(a: WApproval?): CardAsk? = when {
            a == null -> null
            a.kind == "tool" -> WidgetModel.answerable(a)?.let { Exact(a.id, a.toolName, it) } ?: Partial(a.id)
            else -> Open(a.id)
        }
    }
}

