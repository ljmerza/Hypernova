package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentLead
import com.prudnikweb.hypernova.data.ClaudeState
import com.prudnikweb.hypernova.data.CodexState
import com.prudnikweb.hypernova.data.Servers

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

/** Whose a usage card is. */
internal enum class UsageAgent { Claude, Codex }

/**
 * One usage card under Home's sessions: [agent]'s on [hub], titled as [cards] has it. [server] and
 * [name]: the computer it's on, with "All servers" (null with one computer: no name in its seam).
 * [live]: that computer's link is current ([Servers.fresh]); otherwise its card is dimmed, its name
 * saying offline, as its sessions' cards are.
 */
internal class UsageSlot<H>(
    val server: String?,
    val name: String?,
    val hub: H,
    val agent: UsageAgent,
    val cards: UsageCards,
    val live: Boolean = true,
) {
    /** The list's key: "usage" and "codex-usage" with one computer, as before; with several, the computer's id after it. */
    val key: String get() {
        val base = if (agent == UsageAgent.Claude) "usage" else "codex-usage"
        return if (server == null) base else "$base:$server"
    }
    /** Its computer isn't linked: the numbers are the last it sent. */
    val offline: Boolean get() = server != null && !live
}

/**
 * Which usage cards Home's Sessions list ends with, in which order (docs/DECISIONS.md, "All
 * servers on Home"). Pure, generic in the hub ([H]: a Hub in the app, anything in tests).
 */
internal object AllUsage {
    /** One computer's: Claude's before Codex's, as [UsageCards.of] has them (Codex's only with its state). */
    fun <H> of(
        hub: H, claude: ClaudeState?, codex: CodexState?,
        server: String? = null, name: String? = null, live: Boolean = true,
    ): List<UsageSlot<H>> {
        val cards = UsageCards.of(claude, codex)
        return listOfNotNull(
            if (cards.claude) UsageSlot(server, name, hub, UsageAgent.Claude, cards, live) else null,
            if (cards.codex && codex != null) UsageSlot(server, name, hub, UsageAgent.Codex, cards, live) else null,
        )
    }

    /**
     * "All servers": each computer's cards in the order paired (the switcher's, and the order
     * [AllHome.cards] takes them in), each by its own [UsageCards.of]. Two computers on the same
     * account each keep their card (one per instance, the owner's words); an offline one's stay.
     */
    fun <H> all(parts: List<ServerPart<H>>, claude: (H) -> ClaudeState?, codex: (H) -> CodexState?): List<UsageSlot<H>> =
        parts.flatMap { p -> of(p.hub, claude(p.hub), codex(p.hub), p.id, p.name, Servers.fresh(p.state)) }

    /**
     * Where a "jump to usage" lands in [slots]: [agent]'s card on [picked] (the computer picked
     * last; null with one computer), else [agent]'s first, else the first card. -1: there are none.
     */
    fun <H> jumpTo(slots: List<UsageSlot<H>>, agent: UsageAgent, picked: String? = null): Int {
        val mine = slots.indexOfFirst { it.agent == agent && it.server == picked }
        if (mine >= 0) return mine
        val first = slots.indexOfFirst { it.agent == agent }
        return if (first >= 0) first else if (slots.isEmpty()) -1 else 0
    }
}
