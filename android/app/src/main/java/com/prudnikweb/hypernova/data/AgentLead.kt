package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * Claude Code on the computer (the snapshot's `claude`, docs/PROTOCOL.md, Snapshot): installed or
 * not, and its version. Null from an older daemon, where it always was.
 */
@Immutable
data class ClaudeState(
    val available: Boolean,
    val version: String?,
    /** Sessions live in `claude agents` (the Mac's settings.claudeAgents); null from a Mac that doesn't say. */
    val agents: Boolean? = null,
) {
    companion object {
        fun parse(o: JSONObject?): ClaudeState? = o?.let {
            ClaudeState(it.optBoolean("available", true), it.str("version")?.takeIf { v -> v.isNotBlank() }, if (it.has("agents")) it.optBoolean("agents") else null)
        }
    }
}

/**
 * Which agent leads, and which are in use (docs/PROTOCOL.md, "Codex (a second agent)", "Which agent
 * leads"): one rule in every client, here in one place. Pure, unit-tested.
 *
 * - **Claude Code leads** wherever it's installed (and on an older Mac, which doesn't say: it always
 *   had it). **Codex leads** only where Claude Code isn't installed and Codex is. The sun, the
 *   widget's star and a new session without a choice follow the leading agent.
 * - **Codex in use**: turned on for Hypernova ([CodexState.enabled]).
 * - **Both in use**: Claude Code here and Codex on. Only then does anything say which agent it is
 *   (a second star, "Claude usage" and "Codex usage", the widget's second readout): a Mac with
 *   Claude Code alone looks exactly as it did before Codex.
 */
object AgentLead {
    /** Claude Code is on this computer: true unless the Mac says it isn't. */
    fun claudeAvailable(claude: ClaudeState?): Boolean = claude?.available != false

    /** Codex leads: Claude Code isn't installed here, and Codex is. */
    fun codexLeads(claude: ClaudeState?, codex: CodexState?): Boolean = !claudeAvailable(claude) && codex?.available == true

    /** Codex is in use with Hypernova (turned on in Setup, from the phone, or by its first session). */
    fun codexInUse(codex: CodexState?): Boolean = codex?.enabled == true

    /** Both agents in use: Claude Code here, and Codex on. The only case where the screens name the agents. */
    fun both(claude: ClaudeState?, codex: CodexState?): Boolean = claudeAvailable(claude) && codexInUse(codex)

    /** The agent a new session goes to without a choice ([AgentKind.CLAUDE] or [AgentKind.CODEX]). */
    fun leading(claude: ClaudeState?, codex: CodexState?): String = if (codexLeads(claude, codex)) AgentKind.CODEX else AgentKind.CLAUDE

    /**
     * The window Codex's star (or, where Codex leads, the sun) is lit by: the 5-hour window, else the
     * week where a plan has only that. Null while Codex has sent no numbers.
     */
    fun codexWindow(u: CodexUsage?): Window? = u?.fiveHour ?: u?.sevenDay

    /** How long [codexWindow] is (ms): five hours, or a week when it's the week. */
    fun codexWindowMs(u: CodexUsage?): Long = if (u?.fiveHour != null) 5 * 3_600_000L else 7 * 86_400_000L

    /** How much of [codexWindow] is spent (0..1): a limited Codex reads full. Null without numbers. */
    fun codexUsed(u: CodexUsage?): Float? {
        val w = codexWindow(u) ?: return null
        val pct = (w.pct / 100.0).toFloat().coerceIn(0f, 1f)
        return if (u?.limited == true && codexLimitedWindow(u) === w) 1f else pct
    }

    /**
     * The window [u] is limited by, when it is: the one at 100% (the week first, as it outranks), or
     * the star's own window when Codex says limited without one at 100%.
     */
    fun codexLimitedWindow(u: CodexUsage?): Window? {
        if (u == null) return null
        u.sevenDay?.takeIf { it.pct >= 100.0 }?.let { return it }
        u.fiveHour?.takeIf { it.pct >= 100.0 }?.let { return it }
        return if (u.limited) codexWindow(u) else null
    }

    /**
     * Codex's limits as the sun's [Usage], for a Mac where Codex leads: its 5-hour window as the
     * disk (the week where a plan has only that), its week as the far sky's; no pace (the Mac sends
     * none for Codex); a used-up window full and limited, the week's as the low state. Signed out,
     * the numbers are the last ones known ([Usage.stale]). Without numbers: not known yet.
     */
    fun codexSunUsage(c: CodexState?, now: Long): Usage {
        val u = c?.usage
        val five = u?.fiveHour
        val week = u?.sevenDay
        val disk = five ?: week
        if (u == null || disk == null) return Usage(0f, 0f, now + 5 * 3_600_000L, now + 7 * 86_400_000L, known = false, stale = c?.account?.signedIn == false)
        val limited = codexLimitedWindow(u)
        // When the limit began: when the Mac read these numbers (stable, so the sky doesn't see a new limit each time).
        val at = u.observedAt ?: 1L
        val weekFull = week != null && limited === week
        val fiveFull = five != null && (limited === five || (limited != null && !weekFull))
        fun pct(w: Window?) = ((w?.pct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        return Usage(
            fiveHour = if (fiveFull || (five == null && weekFull)) 1f else pct(disk),
            sevenDay = if (weekFull) 1f else pct(week ?: five),
            fiveHourResetAt = disk.resetsAt,
            sevenDayResetAt = (week ?: disk).resetsAt,
            limitedAt = if (fiveFull) at else null,
            weekLimitedAt = if (weekFull) at else null,
            known = true,
            stale = c.account?.signedIn == false,
            fiveHourReported = five != null,
        )
    }
}

/** Claude Code is on the paired computer (an older Mac doesn't say: it always was). */
val Hub.claudeAvailable: Boolean get() = AgentLead.claudeAvailable(claude)

/** Codex leads here: Claude Code isn't installed, Codex is (the sun and new sessions are Codex's). */
val Hub.codexLeads: Boolean get() = AgentLead.codexLeads(claude, codex)

/** Codex is turned on for Hypernova. */
val Hub.codexInUse: Boolean get() = AgentLead.codexInUse(codex)

/** Claude Code and Codex both in use: the screens name the agents (and only then). */
val Hub.bothAgents: Boolean get() = AgentLead.both(claude, codex)

/** The agent a new session goes to without a choice. */
val Hub.leadingAgent: String get() = AgentLead.leading(claude, codex)
