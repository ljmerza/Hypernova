package com.prudnikweb.hypernova.ui.fx

import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Which body each session goes round (ui/fx/SkySplit.kt): by its agent, not by its provider. */
class SkySplitTest {
    private val now = 10 * 86_400_000L
    private fun s(id: String, provider: String? = "anthropic", agent: String = AgentKind.CLAUDE, state: SessionState = SessionState.Idle, at: Long = now - 60_000L, phase: Phase = Phase.of(state)) =
        Session(hex = id, id = id, name = id, project = "p", state = state, model = "", mode = "default", activity = "", updatedAt = at, providerId = provider, agent = agent, phase = phase)

    private val claude = s("c")
    private val router = s("r", provider = "openrouter")
    private val codex = s("x", provider = AgentKind.CODEX_PROVIDER, agent = AgentKind.CODEX)

    @Test fun aCodexSessionIsNeverTheCompanions() {
        // Its providerId is "codex", but it's the other agent's, not another provider's.
        assertEquals(SkySplit.Body.Codex, SkySplit.body(codex, codexStar = true))
        assertEquals(SkySplit.Body.Sun, SkySplit.body(codex, codexStar = false))
        assertEquals(SkySplit.Body.Companion, SkySplit.body(router, codexStar = true))
        assertEquals(SkySplit.Body.Sun, SkySplit.body(claude, codexStar = true))
    }

    @Test fun bothInUseCodexGoesRoundItsOwnStar() {
        val all = listOf(claude, router, codex)
        assertEquals(listOf(claude), SkySplit.sun(all, codexStar = true))
        assertEquals(listOf(codex), SkySplit.moons(all, SkySplit.Body.Codex, codexStar = true, now = now))
        assertEquals(listOf(router), SkySplit.moons(all, SkySplit.Body.Companion, codexStar = true, now = now))
    }

    @Test fun whereCodexLeadsItsSessionsAreTheSunsPlanets() {
        // A Codex-only project gets a planet: its sessions are planned with the sun's.
        val all = listOf(codex, router)
        assertEquals(listOf(codex), SkySplit.sun(all, codexStar = false))
        assertEquals(listOf("p"), SkyPlanner.plan(SkySplit.sun(all, codexStar = false), now).planetNames)
        assertEquals(emptyList<Session>(), SkySplit.moons(all, SkySplit.Body.Codex, codexStar = false, now = now))
    }

    @Test fun theSameListComesBackWhenEverySessionIsTheSuns() {
        val all = listOf(claude, s("c2"))
        assertSame(all, SkySplit.sun(all, codexStar = true))
    }

    @Test fun onlySessionsInUseAreMoons() {
        val old = s("old", provider = AgentKind.CODEX_PROVIDER, agent = AgentKind.CODEX, at = now - 2 * 86_400_000L)
        val busy = s("busy", provider = AgentKind.CODEX_PROVIDER, agent = AgentKind.CODEX, state = SessionState.Working, at = now - 2 * 86_400_000L)
        val ended = s("end", provider = AgentKind.CODEX_PROVIDER, agent = AgentKind.CODEX, phase = Phase.Ended)
        assertEquals(listOf(busy), SkySplit.moons(listOf(old, busy, ended), SkySplit.Body.Codex, codexStar = true, now = now))
    }
}
