package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/*
 * Claude Code's permission rules (docs/PROTOCOL.md, "Permission rules"): what the server sends and what a change sends back. The
 * server decides what's valid, what's hidden and what loosens: nothing here holds a copy of any of it.
 */

/**
 * One rule. [index] is its place in the file's list, which a change names along with the file's hash. [text] has a numbered
 * placeholder where a secret stays on the server, and an edit that keeps one keeps the secret. [problem]: Claude Code would skip
 * it. [overriddenBy]: a list that wins over it whatever the call, through the rule [overrides].
 */
@Immutable
data class RuleRow(val index: Int, val text: String, val masked: Boolean, val problem: String?, val warning: String?, val broad: String?, val overriddenBy: String?, val overrides: String?)

/** A list as the file has it. [rules] null: something else is there, and Claude Code skips the whole file. [others]: entries that aren't text. */
@Immutable
data class RuleListView(val rules: List<RuleRow>?, val others: Int)

/** What Undo would do. [needsConfirm]: it would let more through, and [change] says what. */
@Immutable
data class RulesUndo(val needsConfirm: Boolean, val change: String)

/** `GET /v1/agents/rules`. [supported] false: this agent has no such lists ([rulesFiles]: Codex's own rule files, by count). */
@Immutable
data class AgentRulesReply(val supported: Boolean, val where: String, val state: String, val whyNot: String?, val hash: String?, val lists: Map<String, RuleListView>?, val undo: RulesUndo?, val rulesFiles: Int?) {
    fun list(name: String): RuleListView = lists?.get(name) ?: RuleListView(emptyList(), 0)
    /** Whether anything can be changed: a file that's a link or read-only is only shown. */
    val editable: Boolean get() = supported && whyNot == null && lists != null

    companion object {
        const val ALLOW = "allow"
        const val ASK = "ask"
        const val DENY = "deny"
        /** Weighed in this order: deny beats ask, ask beats allow. */
        val LISTS = listOf(ALLOW, ASK, DENY)
        const val CONFIRM_NEEDED = "confirm_needed"
        const val BAD_RULE = "bad_rule"

        fun wire(o: JSONObject): AgentRulesReply {
            val lists = o.obj("lists")?.let { l -> LISTS.associateWith { n -> l.obj(n)?.let(::listOf) ?: RuleListView(emptyList(), 0) } }
            return AgentRulesReply(
                supported = o.bool("supported"),
                where = o.str("where").orEmpty(),
                state = o.str("state") ?: "ok",
                whyNot = o.str("whyNot"),
                hash = o.str("hash"),
                lists = lists,
                undo = o.obj("undo")?.let { u -> RulesUndo(u.bool("needsConfirm"), u.str("change").orEmpty()) },
                rulesFiles = o.int("rulesFiles"),
            )
        }

        private fun listOf(o: JSONObject) = RuleListView(
            rules = o.arr("rules")?.mapObjects { r -> RuleRow(r.int("index") ?: 0, r.str("text").orEmpty(), r.bool("masked"), r.str("problem"), r.str("warning"), r.str("broad"), r.str("overriddenBy"), r.str("overrides")) },
            others = o.int("others") ?: 0,
        )
    }
}

/** A change to a rule, as the server takes it. */
@Immutable
sealed interface RuleChange {
    val list: String
    data class Add(override val list: String, val text: String) : RuleChange
    data class Edit(override val list: String, val index: Int, val text: String) : RuleChange
    data class Delete(override val list: String, val index: Int) : RuleChange
    data class Move(override val list: String, val index: Int, val to: String) : RuleChange

    fun wire(): JSONObject = when (this) {
        is Add -> JSONObject().put("op", "add").put("list", list).put("text", text)
        is Edit -> JSONObject().put("op", "edit").put("list", list).put("index", index).put("text", text)
        is Delete -> JSONObject().put("op", "delete").put("list", list).put("index", index)
        is Move -> JSONObject().put("op", "move").put("list", list).put("index", index).put("to", to)
    }
}

/** What came of a change. [changed] false: it was already so. */
@Immutable
data class RuleChanged(val changed: Boolean, val hash: String?, val loosened: Boolean) {
    companion object {
        fun wire(o: JSONObject) = RuleChanged(o.bool("changed"), o.str("hash"), o.bool("loosened"))
    }
}

suspend fun Hub.agentRules(agent: String): Result<AgentRulesReply> =
    agentExt("GET", "/v1/agents/rules" + McpWire.query("agent" to agent)).map(AgentRulesReply::wire)

/** [confirmed]: the owner has read what the server said it does. [asTyped]: keep a rule the server says Claude Code would skip. */
suspend fun Hub.changeRule(agent: String, change: RuleChange, expected: String?, confirmed: Boolean = false, asTyped: Boolean = false): Result<RuleChanged> =
    agentExt("PUT", "/v1/agents/settings", JSONObject().put("agent", agent).put("rule", change.wire()).put("expected", expected ?: JSONObject.NULL).also { o ->
        if (confirmed) o.put("confirmed", true)
        if (asTyped) o.put("asTyped", true)
    }).map(RuleChanged::wire)

suspend fun Hub.undoRule(agent: String, expected: String, confirmed: Boolean = false): Result<JSONObject> =
    agentExt("POST", "/v1/agents/settings/undo", JSONObject().put("agent", agent).put("expected", expected).also { if (confirmed) it.put("confirmed", true) })

/** The rows of [rows] that hold [query], as the server shows them: a hidden secret can't be searched for. */
fun rulesMatching(rows: List<RuleRow>, query: String): List<RuleRow> {
    val q = query.trim()
    return if (q.isEmpty()) rows else rows.filter { it.text.contains(q, ignoreCase = true) }
}

/** The form's helper for the common kinds. [Text] is the rule as typed, for whatever these don't cover. */
enum class RuleKind { Command, ReadFiles, EditFiles, Site, Mcp, Text }

/** What the form's fields make. Blank fields make nothing: a whole tool has to be typed, so it's never a slip. */
object RuleDraft {
    fun text(kind: RuleKind, main: String, extra: String = "", prefix: Boolean = false): String {
        val m = main.trim()
        val e = extra.trim()
        if (kind == RuleKind.Text) return m
        if (m.isEmpty()) return ""
        return when (kind) {
            RuleKind.Command -> if (prefix) "Bash($m *)" else "Bash($m)"
            RuleKind.ReadFiles -> "Read($m)"
            RuleKind.EditFiles -> "Edit($m)"
            RuleKind.Site -> "WebFetch(domain:${siteOf(m)})"
            RuleKind.Mcp -> "mcp__${m}__${e.ifEmpty { "*" }}"
            RuleKind.Text -> m
        }
    }

    /** A pasted address down to its site. */
    private fun siteOf(s: String): String = s.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
}
