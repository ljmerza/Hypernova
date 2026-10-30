package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.Item

/**
 * Fork: the chat's rows. A run of Claude's work between messages (tool rows, terminals, diffs and
 * the thinking between them) with at least [ToolRuns.MIN] tool calls folds into one line ("8 tools ·
 * Read ×3 · Bash ×2"), so the chat reads as messages and replies with the detail a tap away.
 */
internal sealed interface ChatRow {
    val key: String

    /** True when [itemKey] is this row's item, or one of its run's. */
    fun has(itemKey: String): Boolean

    data class One(val item: Item) : ChatRow {
        override val key: String get() = item.key
        override fun has(itemKey: String) = item.key == itemKey
    }

    /** Keyed by its first item, so a run that grows at the tail keeps its place and its open state. */
    data class Run(val items: List<Item>) : ChatRow {
        override val key: String = "run:" + items.first().key
        override fun has(itemKey: String) = items.any { it.key == itemKey }
        val calls: Int get() = items.count(ToolRuns::isCall)
        val failed: Int get() = items.count(ToolRuns::failed)
        val running: Boolean get() = items.any(ToolRuns::running)

        /** Each verb with how often it ran, in the order they first ran: ("Read", 3), ("Bash", 2). */
        val verbs: List<Pair<String, Int>>
            get() {
                val counts = LinkedHashMap<String, Int>()
                for (it in items) ToolRuns.verb(it)?.let { v -> counts[v] = (counts[v] ?: 0) + 1 }
                return counts.map { (v, n) -> v to n }
            }
    }

    /** Fork: the asks still waiting for an answer, stacked in one row at the end (AskStack.kt). */
    data class Asks(val asks: List<Item.Approval>) : ChatRow {
        override val key: String get() = "asks"
        override fun has(itemKey: String) = asks.any { it.key == itemKey }
    }

    /**
     * Fork: Claude Code's own permission dialog in the session's terminal, where the asks go (the
     * last row), as an approval card (TerminalAskCard.kt). Only with no approval waiting: one
     * that is answers the same dialog.
     */
    data class TermAsk(val ask: com.prudnikweb.hypernova.data.TerminalAsk) : ChatRow {
        override val key: String get() = "term-ask"
        override fun has(itemKey: String) = false
    }
}

/** Pure, unit-tested. */
internal object ToolRuns {
    /** Fewer calls than this stay as they are: folding two rows saves next to nothing. */
    const val MIN = 3

    fun isWork(it: Item) = it is Item.Tool || it is Item.Terminal || it is Item.Diff || it is Item.Thinking
    fun isCall(it: Item) = it is Item.Tool || it is Item.Terminal || it is Item.Diff

    fun verb(it: Item): String? = when (it) {
        is Item.Tool -> it.verb
        is Item.Terminal -> "Bash"
        is Item.Diff -> it.verb ?: "Edit"
        else -> null
    }

    fun failed(it: Item) = when (it) {
        is Item.Tool -> it.failed
        is Item.Terminal -> !it.running && (it.exitCode ?: 0) != 0
        else -> false
    }

    fun running(it: Item) = when (it) {
        is Item.Tool -> it.running
        is Item.Terminal -> it.running
        else -> false
    }

    fun rows(items: List<Item>): List<ChatRow> {
        val out = ArrayList<ChatRow>(items.size)
        var i = 0
        while (i < items.size) {
            if (!isWork(items[i])) {
                out += ChatRow.One(items[i])
                i++
                continue
            }
            var j = i
            while (j < items.size && isWork(items[j])) j++
            val run = items.subList(i, j)
            if (run.count(::isCall) >= MIN) out += ChatRow.Run(run.toList()) else run.forEach { out += ChatRow.One(it) }
            i = j
        }
        return out
    }

    /**
     * The replies that carry Claude's name over them: the first after each of the owner's messages
     * (and the first of all). The rest follow on without repeating it.
     */
    fun labelled(items: List<Item>): Set<String> {
        val out = HashSet<String>()
        var named = false
        for (it in items) when (it) {
            is Item.User -> named = false
            is Item.Assistant -> if (!named) {
                out += it.key
                named = true
            }
            else -> Unit
        }
        return out
    }
}
