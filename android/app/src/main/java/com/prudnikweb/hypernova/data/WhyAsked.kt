package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * Why Claude Code asked (IDEAS PA7; docs/PROTOCOL.md, "Why it asked"): an approval's `why` and a
 * terminal dialog's `menu.permission.why`. [kind]: `ask_rule`, `hook`, `outside_folder`, `safety`,
 * `classifier`, `mode`, `no_rule` or `other` (a newer daemon may send another: shown as it is);
 * [text] the line to show; [rule] the rule it names, shown in mono; [source] `claude` (Claude Code
 * said so) or `hypernova` (the daemon's own reading of the rules, labelled as such). Pure, unit-tested.
 */
@Immutable
data class Why(val kind: String, val text: String, val rule: String?, val source: String) {
    /** The daemon's own reading of the rules, not Claude Code's words. */
    val hypernovas: Boolean get() = source == "hypernova"

    /** A run of [text]: [mono] for the rule. */
    @Immutable data class Span(val text: String, val mono: Boolean)

    /**
     * The line in runs, the rule in mono where the text names it; a rule the text doesn't name
     * follows it after " · ".
     */
    fun spans(): List<Span> {
        val r = rule ?: return listOf(Span(text, false))
        val i = text.indexOf(r)
        if (i < 0) return listOf(Span(text, false), Span(" · ", false), Span(r, true))
        return listOfNotNull(
            text.substring(0, i).takeIf { it.isNotEmpty() }?.let { Span(it, false) },
            Span(r, true),
            text.substring(i + r.length).takeIf { it.isNotEmpty() }?.let { Span(it, false) },
        )
    }

    companion object {
        /** From the wire; null without a text (an older daemon sends none). */
        fun wire(o: JSONObject?): Why? {
            if (o == null) return null
            val text = o.str("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return Why(
                kind = o.str("kind")?.takeIf { it.isNotBlank() } ?: "other",
                text = text,
                rule = o.str("rule")?.trim()?.takeIf { it.isNotEmpty() },
                source = if (o.str("source") == "hypernova") "hypernova" else "claude",
            )
        }
    }
}

/** One waiting ask the Permissions tab says why for: its tool ("Bash" reads with "$ "), its command or line. */
@Immutable
data class WhyAsk(val tool: String, val text: String, val why: Why)

/**
 * The asks the Permissions tab's "Why it asked" shows: the session's waiting tool asks that carry a
 * why, then Claude Code's own dialog in its terminal ([dialog]) when it has one. Empty when two or
 * more tool asks wait: "Asks waiting" lists those, each with its why (AllowSeveral.kt).
 */
fun whyAsksOf(waiting: List<WApproval>, dialog: TermMenu.Permission?): List<WhyAsk> {
    val tools = waiting.filter { it.kind == "tool" }.sortedBy { it.createdAt }
    if (tools.size >= 2) return emptyList()
    val asks = tools.mapNotNull { a ->
        a.why?.let { WhyAsk(a.toolName, a.input["command"]?.takeIf { it.isNotBlank() } ?: a.summary.removePrefix("${a.toolName}: ").ifBlank { a.toolName }, it) }
    }
    val d = dialog?.why?.let { w ->
        val bash = dialog.tool?.startsWith("Bash", ignoreCase = true) == true
        WhyAsk(if (bash) "Bash" else dialog.tool.orEmpty(), dialog.command ?: dialog.question, w)
    }
    return asks + listOfNotNull(d)
}
