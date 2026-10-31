package com.prudnikweb.hypernova.ui.components

import androidx.compose.runtime.Immutable
import com.prudnikweb.hypernova.data.Question

/**
 * An AskUserQuestion card's answers, one question at a time (owner, 2026-10-31: with two questions
 * he "didn't see the submit button" and couldn't move between them). [at] is the question shown;
 * Back, Next and the step dots move it and keep every answer. Send goes once every question has
 * one: an option (single-select), at least one (multi-select), or "Other" with words in it.
 * One question alone is one step: no Back or Next. Pure, unit-tested.
 */
@Immutable
internal data class QuestionSteps(
    val questions: List<Question>,
    val at: Int = 0,
    /** Chosen option labels per question index. */
    val chosen: Map<Int, Set<String>> = emptyMap(),
    /** The questions whose "Other…" is ticked. */
    val otherOn: Set<Int> = emptySet(),
    /** The words typed under "Other…", per question (kept while it's unticked). */
    val other: Map<Int, String> = emptyMap(),
) {
    val count: Int get() = questions.size

    /** Several questions: shown as steps, with Back and Next. */
    val stepped: Boolean get() = count > 1

    val current: Question? get() = questions.getOrNull(at)
    val isFirst: Boolean get() = at <= 0
    val isLast: Boolean get() = at >= count - 1

    /** Ticks [label] on the current question; a single-select one keeps only it (and unticks Other). */
    fun pick(label: String): QuestionSteps {
        val q = current ?: return this
        val sel = chosen[at].orEmpty()
        val next = when {
            q.multiSelect && label in sel -> sel - label
            q.multiSelect -> sel + label
            else -> setOf(label)
        }
        return copy(chosen = chosen + (at to next), otherOn = if (q.multiSelect) otherOn else otherOn - at)
    }

    /** Ticks or unticks "Other…" on the current question; ticked on a single-select one, it's the answer. */
    fun toggleOther(): QuestionSteps {
        val q = current ?: return this
        val on = at in otherOn
        return copy(
            otherOn = if (on) otherOn - at else otherOn + at,
            chosen = if (!q.multiSelect && !on) chosen + (at to emptySet()) else chosen,
        )
    }

    fun type(text: String): QuestionSteps = copy(other = other + (at to text))

    fun next(): QuestionSteps = if (isLast) this else copy(at = at + 1)
    fun back(): QuestionSteps = if (isFirst) this else copy(at = at - 1)
    fun jump(i: Int): QuestionSteps = if (i in questions.indices) copy(at = i) else this

    /** Question [i]'s answer: its ticked options in the order offered, then Other's words. */
    fun labels(i: Int): List<String> {
        val q = questions.getOrNull(i) ?: return emptyList()
        val sel = chosen[i].orEmpty()
        return q.options.map { it.label }.filter { it in sel } +
            listOfNotNull(other[i]?.trim()?.takeIf { i in otherOn && it.isNotEmpty() })
    }

    fun answered(i: Int): Boolean = labels(i).isNotEmpty()

    /** Every question has an answer: Send can go. */
    val complete: Boolean get() = count > 0 && questions.indices.all(::answered)

    /** The first question still without an answer, null when none. */
    val firstUnanswered: Int? get() = questions.indices.firstOrNull { !answered(it) }

    /**
     * What goes to the daemon (docs/PROTOCOL.md, ApprovalAnswer): question text → its answer as
     * it is (single-select), or a multi-select's labels joined by ", " the way Claude Code joins
     * them (one with ", " or a quote in it goes in quotes, so the list still reads back).
     */
    fun answers(): Map<String, String> = questions.indices.mapNotNull { i ->
        val l = labels(i)
        when {
            l.isEmpty() -> null
            questions[i].multiSelect -> questions[i].question to join(l)
            else -> questions[i].question to l.first()
        }
    }.toMap()

    companion object {
        fun join(labels: List<String>): String = labels.joinToString(", ") { if (it.contains(", ") || it.contains('"')) quote(it) else it }

        private fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
            append('"')
        }
    }
}
