package com.prudnikweb.hypernova.ui.session

import android.content.Context
import com.prudnikweb.hypernova.data.ClaudeEffort
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SlashCommand
import com.prudnikweb.hypernova.data.find
import com.prudnikweb.hypernova.ui.home.MenuOffer
import org.json.JSONArray

/*
 * The session sheet's Commands tab (CommandsTab.kt) as plain data, unit-tested: what a command
 * takes after its name and the helper a tap opens for it, the line that's sent, the commands used lately and pinned on this phone,
 * the list in its parts, and which sessions can take a side question or a command from here.
 */

/** What a command takes after its name, from its argument hint. */
internal enum class ArgNeed { None, Optional, Required }

/**
 * "" takes nothing; "[name]" (or a hint that says "optional") may take something; "<model>" must.
 * Any other hint ("(--wizard …)") may.
 */
internal fun argNeed(hint: String): ArgNeed {
    val h = hint.trim()
    return when {
        h.isEmpty() -> ArgNeed.None
        h.startsWith("[") || h.contains("optional", ignoreCase = true) -> ArgNeed.Optional
        h.startsWith("<") -> ArgNeed.Required
        else -> ArgNeed.Optional
    }
}

/** The command as it's sent: "/name", or "/name args" with what was typed after it, trimmed. */
internal fun commandLine(c: SlashCommand, args: String = ""): String =
    "/${c.name}" + args.trim().let { if (it.isEmpty()) "" else " $it" }

/** Whether [c] can go with [args] typed after it: one that must take something waits for it. */
internal fun canSendCommand(c: SlashCommand, args: String): Boolean = argNeed(c.argumentHint) != ArgNeed.Required || args.isNotBlank()

/** A tap on [c] sends it at once (it takes nothing), else opens its argument field first. */
internal fun sendsOnTap(c: SlashCommand): Boolean = argNeed(c.argumentHint) == ArgNeed.None

/**
 * What a tap on a command opens instead of a bare field (IDEAS SC3, SC8; docs/DECISIONS.md,
 * "Argument helpers in the Commands tab"): [Effort] the five levels as chips under it, [Model] the
 * session's model picker, [Rename] the rename sheet with the name filled, [Compact] the Compact
 * sheet with its optional "Keep…" line. [Field]: as before, the field from its argument hint (or
 * nothing, for one that takes nothing).
 */
internal enum class ArgHelper { Field, Effort, Model, Rename, Compact }

/**
 * Which of Claude Code's helpers [s] can take from the tab, each through its own route, not typed:
 * the effort ([ClaudeEffort.changeable], on the subscription only, as the Overview's Effort part),
 * the model (as the Overview's model picker) and the name (Claude Code's /rename, which the Mac
 * types only where it holds a terminal or attaches to a job). [changeable]: the session's model and
 * mode can be changed from the phone (`canChangeModel`, SessionScreen.kt).
 */
internal data class HelperReach(val effort: Boolean, val model: Boolean, val rename: Boolean) {
    companion object {
        fun of(s: Session, changeable: Boolean): HelperReach = HelperReach(
            effort = ClaudeEffort.shown(s) && ClaudeEffort.changeable(s, changeable),
            model = changeable && !s.codex,
            rename = MenuOffer.of(s).rename && s.phase != Phase.Ended && terminalReach(s),
        )
    }
}

/**
 * The helper a tap on [c] opens: only Claude Code's own /effort, /model and /rename (not a skill of
 * the same name), each while [reach] has its route, else their field as before; /compact always.
 */
internal fun argHelper(c: SlashCommand, reach: HelperReach): ArgHelper = when {
    !c.builtin -> ArgHelper.Field
    c.name == "effort" && reach.effort -> ArgHelper.Effort
    c.name == "model" && reach.model -> ArgHelper.Model
    c.name == "rename" && reach.rename -> ArgHelper.Rename
    c.name == "compact" -> ArgHelper.Compact
    else -> ArgHelper.Field
}

/**
 * The Compact sheet's line: "/compact", or "/compact <focus>" with what was typed on its "Keep…"
 * line, trimmed and on one line (a line break would send it early in the terminal).
 */
internal fun compactLine(focus: String): String =
    "/compact" + focus.trim().replace(Regex("\\s+"), " ").let { if (it.isEmpty()) "" else " $it" }

/**
 * Commands the tab doesn't list: /btw has its own field at the top; /logout would sign Claude Code
 * out on the Mac with one tap; /stop is the Session tab's Stop. The "/" list in the box still has them.
 */
internal val NOT_IN_TAB = setOf("btw", "logout", "stop")

/** How many recent commands the tab shows, and keeps. */
internal const val RECENT_SHOWN = 5
private const val RECENT_KEPT = 12

/**
 * The commands this phone sent from the tab, most recent first ([recent], by name), and the ones
 * pinned with a long press, in the order they were pinned ([pinned]). Kept on the phone, for every
 * project: a project lists only those it has.
 */
internal data class CommandPicks(val recent: List<String> = emptyList(), val pinned: List<String> = emptyList()) {
    /** [name] was just sent: first in [recent], once. */
    fun used(name: String): CommandPicks = copy(recent = (listOf(name) + recent.filter { it != name }).take(RECENT_KEPT))

    /** [name] pinned, or unpinned if it was. */
    fun pinToggled(name: String): CommandPicks = copy(pinned = if (name in pinned) pinned - name else pinned + name)

    fun isPinned(name: String): Boolean = name in pinned
}

/**
 * The tab's list in its parts: [recent] (not the pinned ones, [RECENT_SHOWN] at most), [pinned],
 * then [all] the others: the owner's first, then Claude Code's, then those that open a screen in
 * the terminal, each by name. With a [query], one list of the matches, best first ([matching]).
 */
internal data class CommandSections(val recent: List<SlashCommand>, val pinned: List<SlashCommand>, val all: List<SlashCommand>) {
    val isEmpty: Boolean get() = recent.isEmpty() && pinned.isEmpty() && all.isEmpty()
}

internal fun commandSections(list: List<SlashCommand>, picks: CommandPicks, query: String = ""): CommandSections {
    val offered = list.filter { it.name !in NOT_IN_TAB }
    if (query.isNotBlank()) return CommandSections(emptyList(), emptyList(), matching(offered, query.trim().removePrefix("/")))
    // By name, an alias too ("cost" is /usage): the same command once.
    fun named(names: List<String>) = names.mapNotNull { offered.find(it) }.distinct()
    val pinned = named(picks.pinned)
    val recent = named(picks.recent).filter { it !in pinned }.take(RECENT_SHOWN)
    val shown = (pinned + recent).toSet()
    val rest = matching(offered.filter { it !in shown }, "").sortedBy { it.terminal }
    return CommandSections(recent, pinned, rest)
}

/**
 * A side question can go to [s]: Claude Code (not Codex), still open, in a shared terminal Hypernova
 * holds or a `claude agents` job (`claude attach`).
 */
internal fun asideReach(s: Session): Boolean =
    s.phase != Phase.Ended && !s.codex && (s.term != null || s.agentJob != null)

/**
 * A command can go to [s] from the tab: as [asideReach], or a session the Mac runs itself. A
 * command that opens a screen in the terminal ([SlashCommand.terminal]) needs a terminal to open.
 */
internal fun commandReach(s: Session): Boolean = asideReach(s) || (s.phase != Phase.Ended && !s.codex && s.origin == "hypernova")

internal fun terminalReach(s: Session): Boolean = s.term != null || s.agentJob != null

/** [CommandPicks] on this phone. */
internal object CommandPicksStore {
    private const val PREFS = "hypernova"
    private const val RECENT = "sheet.commands.recent"
    private const val PINNED = "sheet.commands.pinned"

    fun load(c: Context): CommandPicks {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return CommandPicks(names(p.getString(RECENT, null)), names(p.getString(PINNED, null)))
    }

    fun save(c: Context, picks: CommandPicks) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(RECENT, JSONArray(picks.recent).toString())
            .putString(PINNED, JSONArray(picks.pinned).toString())
            .apply()
    }

    private fun names(json: String?): List<String> = try {
        json?.let { s -> JSONArray(s).let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } } }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
}
