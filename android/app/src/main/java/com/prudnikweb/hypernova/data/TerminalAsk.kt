package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable

/**
 * Fork: Claude Code's own permission dialog on a session's shared terminal ([TermMenu.permission]:
 * an ask rule over auto mode, an ask its hook handed back), as an approval. Allow is its first
 * option (Yes), Deny its last No, and the others ("Yes, and don't ask again for …") stay as they
 * are. Answered through the menu route (arrow keys and Enter in that terminal), never a hook.
 * Null for any other menu, which keeps its menu card. Pure, unit-tested.
 */
@Immutable
data class TerminalAsk(
    val menuId: String,
    /** The dialog's title ("Bash command"); null when it has none. */
    val tool: String?,
    /** What it would run, as the terminal shows it; null when it shows none. */
    val command: String?,
    /** Its other words (what it's for, the rule that brought it), the question last. */
    val lines: List<String>,
    /** The menu's option for Allow and for Deny. */
    val allow: Int,
    val deny: Int,
    /** Options besides Allow and Deny, with their place in the menu. */
    val extras: List<Extra>,
) {
    @Immutable data class Extra(val choice: Int, val label: String, val detail: String?)

    /** A Bash dialog: the command reads with "$ ", as the approval card's does. */
    val isBash: Boolean get() = tool?.startsWith("Bash", ignoreCase = true) == true

    companion object {
        /** [s]'s terminal shows one: only a shared terminal Claude Code runs in (Codex's TUI has none). */
        fun of(s: Session): TerminalAsk? = if (s.term == null || s.codex) null else of(s.menu)

        fun of(menu: TermMenu?): TerminalAsk? {
            val p = menu?.permission ?: return null
            val allow = menu.options.indexOfFirst { it.label.startsWith("Yes", ignoreCase = true) }
            val deny = menu.options.indexOfLast { it.label.startsWith("No", ignoreCase = true) }
            if (allow != 0 || deny < 0) return null
            val extras = menu.options.mapIndexedNotNull { i, o -> if (i == allow || i == deny) null else Extra(i, o.label, o.detail) }
            return TerminalAsk(menu.id, p.tool, p.command, menu.body, allow, deny, extras)
        }
    }
}
