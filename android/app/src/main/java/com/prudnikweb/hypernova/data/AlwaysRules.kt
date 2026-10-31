package com.prudnikweb.hypernova.data

/*
 * What "Always" covers (docs/PROTOCOL.md, "Approvals"; IDEAS PA2, PA3), as plain data, unit-tested:
 * where the rule goes and the rule itself, read and widened as Claude Code reads one. The Mac checks
 * it again the same way (daemon/src/always.ts); this is so the sheet can say what's wrong at once.
 */

/** Where an Always rule goes. [wire]: the answer's `scope`. */
enum class AlwaysScope(val wire: String) {
    /** Until the session ends; nothing saved. */
    Session("session"),
    /** The project's own `.claude/settings.local.json` (Claude Code keeps it out of git). */
    Project("project"),
    /** The owner's `~/.claude/settings.json`, every project. */
    Everywhere("everywhere");

    companion object {
        /** The Mac's word for one; null for none or one this app doesn't know. */
        fun of(wire: String?): AlwaysScope? = entries.firstOrNull { it.wire == wire }
    }
}

/** A rule as Claude Code holds it: the tool, and what of it ([content], null for all of it). */
data class AlwaysRule(val tool: String, val content: String?) {
    /** As a settings file writes it: `Bash(npm test:*)`, the content's `\`, `(` and `)` escaped. */
    override fun toString(): String =
        if (content == null) tool else "$tool(${content.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")})"

    /** A command rule's command without its trailing wildcard (`npm test` of `npm test:*` or `npm test *`). */
    private val command: String? get() = content?.let { c -> (if (prefix) c.dropLast(2) else c).trim() }

    /** Already a prefix rule (`npm test:*`, `npm test *`). */
    private val prefix: Boolean get() = content != null && (content.endsWith(":*") || content.endsWith(" *"))

    /**
     * Quick ways to widen it, widest last, never itself: a command as a prefix (`Bash(npm test
     * --prefix daemon:*)`), its first two words as one (`Bash(npm test:*)`), then the whole tool
     * (`Bash`). Other tools with a pattern widen only to the whole tool; a whole tool not at all.
     */
    fun widenings(): List<AlwaysRule> {
        if (content == null) return emptyList()
        val out = ArrayList<AlwaysRule>()
        if (tool == BASH) {
            val cmd = command.orEmpty()
            val words = cmd.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isNotEmpty()) {
                if (!prefix) out += AlwaysRule(tool, "$cmd:*")
                val two = words.take(2).joinToString(" ")
                if (two != cmd) out += AlwaysRule(tool, "$two:*")
            }
        }
        out += AlwaysRule(tool, null)
        return out.distinct().filter { it != this }
    }

    companion object {
        const val BASH = "Bash"
        /** The Mac takes a rule up to this long, and this many at once (daemon/src/always.ts). */
        const val MAX = 2000
        const val MAX_RULES = 20

        private val TOOL = Regex("^[A-Za-z][A-Za-z0-9_-]*$")
        private val CONTROL = Regex("[\\u0000-\\u001f\\u007f]")

        /**
         * [raw] read as Claude Code reads a rule, or null when it isn't one: `Tool` or
         * `Tool(content)`, the content closing the rule; `Tool()` and `Tool(*)` are the whole tool.
         */
        fun parse(raw: String): AlwaysRule? {
            val s = raw.trim()
            if (s.isEmpty() || s.length > MAX || CONTROL.containsMatchIn(s)) return null
            val open = firstUnescaped(s, '(')
            val close = lastUnescaped(s, ')')
            if (open == -1 && close == -1) return if (TOOL.matches(s)) AlwaysRule(s, null) else null
            if (open == -1 || close != s.length - 1 || close <= open) return null
            val tool = s.substring(0, open)
            if (!TOOL.matches(tool)) return null
            val inner = s.substring(open + 1, close)
            if (inner.isBlank() || inner == "*") return AlwaysRule(tool, null)
            // As Claude Code undoes it: \( and \) first, then \\.
            return AlwaysRule(tool, inner.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\"))
        }

        private fun escaped(s: String, i: Int): Boolean {
            var n = 0
            var j = i - 1
            while (j >= 0 && s[j] == '\\') { n++; j-- }
            return n % 2 == 1
        }

        private fun firstUnescaped(s: String, ch: Char): Int {
            for (i in s.indices) if (s[i] == ch && !escaped(s, i)) return i
            return -1
        }

        private fun lastUnescaped(s: String, ch: Char): Int {
            for (i in s.indices.reversed()) if (s[i] == ch && !escaped(s, i)) return i
            return -1
        }
    }
}

/**
 * The Always sheet's state: the rules as typed (Claude Code's suggestion to start with), where
 * they go, and what Claude Code suggested, to go back to. [scope] starts at Claude Code's own
 * ("this project" for a command), else this session.
 */
data class AlwaysDraft(val suggested: List<String>, val texts: List<String>, val scope: AlwaysScope) {
    /** Each field read as a rule (null where it isn't one). */
    val parsed: List<AlwaysRule?> get() = texts.map(AlwaysRule::parse)

    /** Every field holds a rule, there is one at least and not too many: Save can go. */
    val valid: Boolean get() = texts.isNotEmpty() && texts.size <= AlwaysRule.MAX_RULES && parsed.all { it != null }

    /** What the answer sends: the rules as Claude Code writes them, once each. */
    val rules: List<String> get() = parsed.filterNotNull().map(AlwaysRule::toString).distinct()

    /** The field [i] changed by hand. */
    fun edit(i: Int, text: String): AlwaysDraft = copy(texts = texts.mapIndexed { j, t -> if (j == i) text else t })

    /** The field [i] set to a widening (or back to Claude Code's rule). */
    fun set(i: Int, rule: AlwaysRule): AlwaysDraft = edit(i, rule.toString())

    /** Field [i] back to Claude Code's own rule, when it was changed. */
    fun suggestedAt(i: Int): String? = suggested.getOrNull(i)?.takeIf { it != texts.getOrNull(i) }

    companion object {
        fun of(suggested: List<String>, scope: String?): AlwaysDraft =
            AlwaysDraft(suggested, suggested, AlwaysScope.of(scope) ?: AlwaysScope.Session)
    }
}
