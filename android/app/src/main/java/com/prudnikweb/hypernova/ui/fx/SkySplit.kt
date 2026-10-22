package com.prudnikweb.hypernova.ui.fx

import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.onOtherProvider

/**
 * Which body in the sky each live session goes round (docs/DESIGN.md, "The calm sky", "Codex's
 * star"). Pure, unit-tested.
 *
 * - **The sun** has the leading agent's sessions on its own subscription: Claude Code's where it's
 *   installed (data/AgentLead.kt), as moons of their projects' planets. Where Codex leads, Codex's
 *   sessions are the sun's too, planets and moons like Claude's.
 * - **Codex's star** has Codex's sessions while both agents are in use ([codexStar]): told apart
 *   by the session's agent, not its provider (a Codex session's providerId is "codex").
 * - **The companion** has Claude Code's sessions on other providers (OpenRouter, a gateway).
 */
object SkySplit {
    enum class Body { Sun, Codex, Companion }

    /** The body [s] goes round; [codexStar]: Codex has its own star (both agents in use). */
    fun body(s: Session, codexStar: Boolean): Body = when {
        s.codex -> if (codexStar) Body.Codex else Body.Sun
        s.onOtherProvider -> Body.Companion
        else -> Body.Sun
    }

    /** The sun's sessions: [all] itself when every one is the sun's (the same list, so the sun lays nothing out again). */
    fun sun(all: List<Session>, codexStar: Boolean): List<Session> =
        if (all.all { body(it, codexStar) == Body.Sun }) all else all.filter { body(it, codexStar) == Body.Sun }

    /** In use, as a planet is: it needs you, works, or did something in the last day. */
    fun inUse(s: Session, now: Long): Boolean =
        s.phase != Phase.Ended && (s.state == SessionState.NeedsYou || SkyPlanner.working(s) || now - s.updatedAt < SkyPlanner.RECENT_MS)

    /** The moons [of] has now: its sessions in use. */
    fun moons(all: List<Session>, of: Body, codexStar: Boolean, now: Long): List<Session> =
        all.filter { body(it, codexStar) == of && inUse(it, now) }
}
