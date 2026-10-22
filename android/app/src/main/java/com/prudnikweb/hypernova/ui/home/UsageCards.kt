package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentLead
import com.prudnikweb.hypernova.data.ClaudeState
import com.prudnikweb.hypernova.data.CodexState

/**
 * Which usage cards Home shows under its sessions, and what they're called (docs/DESIGN.md, "Codex
 * (a second agent)"; data/AgentLead.kt). Pure, unit-tested.
 *
 * - Claude Code alone (Codex not installed, or not in use): Claude's card, "Usage", as before Codex.
 * - Both in use: "Claude usage" and "Codex usage", and TalkBack names each window's agent
 *   ("Claude 5-hour window", "Codex 5-hour window").
 * - Codex alone (Claude Code isn't installed): Codex's card, "Usage", its windows plain; no Claude
 *   card (it would only wait for numbers that never come).
 */
internal data class UsageCards(val claude: Boolean, val codex: Boolean) {
    /** Both cards show: each says whose it is. */
    val named: Boolean get() = claude && codex

    val claudeTitle: Int get() = if (named) R.string.usage_claude else R.string.usage
    val codexTitle: Int get() = if (named) R.string.cx_usage else R.string.usage
    /** What TalkBack calls each card's windows. */
    val claudeFive: Int get() = if (named) R.string.usage_name_five_claude else R.string.usage_name_five
    val claudeSeven: Int get() = if (named) R.string.usage_name_seven_claude else R.string.usage_name_seven
    val codexFive: Int get() = if (named) R.string.cx_usage_name_five else R.string.usage_name_five
    val codexSeven: Int get() = if (named) R.string.cx_usage_name_seven else R.string.usage_name_seven

    companion object {
        fun of(claude: ClaudeState?, codex: CodexState?): UsageCards {
            val claudeHere = AgentLead.claudeAvailable(claude)
            // Codex's card while it's in use; where Claude Code isn't installed, whenever Codex is
            // (its usage is the sun's, and its first session turns it on).
            val codexCard = AgentLead.codexInUse(codex) || (!claudeHere && codex != null)
            // Claude's wherever it's installed, and on a computer with neither agent (as before Codex).
            return UsageCards(claude = claudeHere || !codexCard, codex = codexCard)
        }
    }
}
