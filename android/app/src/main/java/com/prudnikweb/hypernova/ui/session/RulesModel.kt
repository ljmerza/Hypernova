package com.prudnikweb.hypernova.ui.session

import androidx.annotation.StringRes
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.PermissionRules
import com.prudnikweb.hypernova.data.RuleSource

/*
 * The Permissions tab's rules in effect (RulesPart.kt) as plain data, unit-tested: the sources
 * with rules or a problem in Claude Code's precedence order, the ones with no file, the counts in
 * the seam, and what is folded.
 */

/** Rules counted by kind, in the order Claude Code weighs them: deny, then ask, then allow. */
internal data class RuleCounts(val deny: Int = 0, val ask: Int = 0, val allow: Int = 0) {
    val total: Int get() = deny + ask + allow
    operator fun plus(o: RuleCounts) = RuleCounts(deny + o.deny, ask + o.ask, allow + o.allow)
}

internal fun countsOf(s: RuleSource): RuleCounts = if (s.ok) RuleCounts(s.deny.size, s.ask.size, s.allow.size) else RuleCounts()

/**
 * One source as a foldable row. [key]: stable while the sheet is open (two managed drop-ins share
 * a source). [inEffect]: false for a file whose rules the managed policy sets aside. [path]: as
 * shown, the session's folder taken off the project's two.
 */
internal data class RuleGroup(val key: String, val source: RuleSource, val counts: RuleCounts, val inEffect: Boolean, val path: String?) {
    /** Something to open: rules, a default mode or extra folders. A broken file only says so. */
    val expandable: Boolean get() = source.ok && (counts.total > 0 || source.defaultMode != null || source.additionalDirectories.isNotEmpty())
}

/**
 * What the part shows: [groups] (sources with rules, a setting or a problem, highest precedence
 * first), [missing] (sources with no file, said in one quiet line), [counts] (only rules in
 * effect) and whether the managed policy keeps rules to itself.
 */
internal data class RulesView(val groups: List<RuleGroup>, val missing: List<RuleSource>, val counts: RuleCounts, val managedOnly: Boolean) {
    val none: Boolean get() = counts.total == 0
}

internal fun rulesView(r: PermissionRules, cwd: String?): RulesView {
    val groups = ArrayList<RuleGroup>()
    val missing = ArrayList<RuleSource>()
    var counts = RuleCounts()
    r.sources.forEachIndexed { i, s ->
        val inEffect = !r.managedOnly || s.source == RuleSource.MANAGED
        val c = countsOf(s)
        if (inEffect) counts += c
        when {
            s.state == RuleSource.MISSING -> missing += s
            // A file read with nothing in it is no news: no row.
            s.ok && c.total == 0 && s.defaultMode == null && s.additionalDirectories.isEmpty() -> Unit
            else -> groups += RuleGroup("$i:${s.source}:${s.path.orEmpty()}", s, c, inEffect, shownPath(s.path, cwd))
        }
    }
    return RulesView(groups, missing, counts, r.managedOnly)
}

/** A project file's path from the session's folder (`.claude/settings.json`); others as they are. */
internal fun shownPath(path: String?, cwd: String?): String? {
    if (path == null) return null
    val base = cwd?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return path
    return if (path.startsWith("$base/")) path.removePrefix("$base/") else path
}

/** A source's name, as the row says it. */
@StringRes
internal fun sourceLabel(source: String): Int = when (source) {
    RuleSource.MANAGED -> R.string.rules_source_managed
    RuleSource.SESSION -> R.string.rules_source_session
    RuleSource.LOCAL -> R.string.rules_source_local
    RuleSource.PROJECT -> R.string.rules_source_project
    else -> R.string.rules_source_user
}

/** Why a source has no rules to show, or null when it was read. */
@StringRes
internal fun stateLabel(state: String): Int? = when (state) {
    RuleSource.OK -> null
    RuleSource.MISSING -> R.string.rules_state_missing
    RuleSource.INVALID -> R.string.rules_state_invalid
    RuleSource.TOO_BIG -> R.string.rules_state_too_big
    else -> R.string.rules_state_unreadable
}

/**
 * What is open: the part as a whole ([open], folded at first), and inside it each source by its
 * key ([expanded], none at first). A source stays expanded under a folded part, for when it opens.
 */
internal data class RulesFold(val open: Boolean = false, val expanded: Set<String> = emptySet()) {
    fun toggled(): RulesFold = copy(open = !open)
    fun toggled(key: String): RulesFold = copy(expanded = if (key in expanded) expanded - key else expanded + key)
    fun shows(key: String): Boolean = open && key in expanded
}
