package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ClaudeState
import com.prudnikweb.hypernova.data.CodexState
import com.prudnikweb.hypernova.data.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The usage cards under Home's sessions (UsageCards.kt, AllUsage): one computer's, or every computer's with "All servers". */
class AllUsageTest {
    /** A computer's agents: what its hub would say. */
    private class FakeHub(val name: String, val claude: ClaudeState?, val codex: CodexState?)

    private val claude = ClaudeState(true, "2.1.0")
    private val noClaude = ClaudeState(false, null)
    private val codexOn = CodexState(available = true, enabled = true, connected = true)

    private fun part(id: String, hub: FakeHub, state: LinkState = LinkState.Connected) =
        ServerPart(id, hub.name, state, hub, sessions = emptyList())

    private fun all(vararg parts: ServerPart<FakeHub>) = AllUsage.all(parts.toList(), { it.claude }, { it.codex })

    private fun shape(slots: List<UsageSlot<FakeHub>>) = slots.map { "${it.name}:${it.agent}" }

    @Test fun oneComputerIsAsBefore() {
        val hub = FakeHub("z2", claude, codexOn)
        val slots = AllUsage.of(hub, hub.claude, hub.codex)
        assertEquals(listOf(UsageAgent.Claude, UsageAgent.Codex), slots.map { it.agent })
        // The same keys as before, no name in the seam, never offline.
        assertEquals(listOf("usage", "codex-usage"), slots.map { it.key })
        assertTrue(slots.all { it.name == null && !it.offline })
        // Claude Code alone: one card, "Usage".
        val alone = AllUsage.of(hub, claude, null).single()
        assertEquals(UsageAgent.Claude, alone.agent)
        assertEquals(R.string.usage, alone.cards.claudeTitle)
    }

    @Test fun everyComputersCardsInTheOrderPairedEachByItsOwnRule() {
        val slots = all(
            part("0", FakeHub("z2", claude, codexOn)),
            part("b", FakeHub("gym", claude, null)),
            part("c", FakeHub("shed", noClaude, codexOn)),
        )
        assertEquals(listOf("z2:Claude", "z2:Codex", "gym:Claude", "shed:Codex"), shape(slots))
        // Each card's hub is its own computer's.
        assertEquals(listOf("z2", "z2", "gym", "shed"), slots.map { it.hub.name })
        assertEquals(listOf("0", "0", "b", "c"), slots.map { it.server })
    }

    @Test fun whoseItIsOnlyWhereBothShowOnThatComputer() {
        val slots = all(part("0", FakeHub("z2", claude, codexOn)), part("b", FakeHub("gym", claude, null)))
        // z2 has both: "Claude usage" and "Codex usage"; gym has Claude alone: "Usage", its name after it.
        assertEquals(R.string.usage_claude, slots[0].cards.claudeTitle)
        assertEquals(R.string.cx_usage, slots[1].cards.codexTitle)
        assertEquals(R.string.usage, slots[2].cards.claudeTitle)
        assertEquals(listOf("z2", "z2", "gym"), slots.map { it.name })
    }

    @Test fun twoComputersOnTheSameAccountEachKeepTheirCard() {
        // Nothing tells two instances on one account apart from two accounts: one card per instance.
        val slots = all(part("0", FakeHub("z2", claude, null)), part("b", FakeHub("gym", claude, null)))
        assertEquals(listOf("z2:Claude", "gym:Claude"), shape(slots))
        assertEquals(2, slots.map { it.key }.toSet().size)
    }

    @Test fun keysAreUniqueAcrossComputers() {
        val slots = all(part("0", FakeHub("z2", claude, codexOn)), part("b", FakeHub("gym", claude, codexOn)))
        assertEquals(listOf("usage:0", "codex-usage:0", "usage:b", "codex-usage:b"), slots.map { it.key })
    }

    @Test fun anOfflineComputersCardsStayAndSaySo() {
        val slots = all(
            part("0", FakeHub("z2", claude, null)),
            part("b", FakeHub("gym", claude, codexOn), LinkState.Offline),
            part("c", FakeHub("shed", claude, null), LinkState.Reconnecting),
            part("d", FakeHub("mini", claude, null), LinkState.Connecting),
        )
        assertEquals(listOf("z2:Claude", "gym:Claude", "gym:Codex", "shed:Claude", "mini:Claude"), shape(slots))
        // As the sessions' cards: reconnecting is still current, connecting (nothing heard yet) isn't.
        assertEquals(listOf(false, true, true, false, true), slots.map { it.offline })
    }

    @Test fun codexsCardNeedsItsState() {
        // Codex in use but its state gone: no Codex card (it would have nothing to draw).
        val hub = FakeHub("z2", claude, codexOn)
        assertEquals(listOf(UsageAgent.Claude), AllUsage.of(hub, hub.claude, null).map { it.agent })
    }

    @Test fun aJumpLandsOnThePickedComputersCardElseTheFirst() {
        val slots = all(
            part("0", FakeHub("z2", claude, null)),
            part("b", FakeHub("gym", claude, codexOn)),
            part("c", FakeHub("shed", noClaude, codexOn)),
        )
        // z2:Claude, gym:Claude, gym:Codex, shed:Codex
        assertEquals(1, AllUsage.jumpTo(slots, UsageAgent.Claude, picked = "b"))
        assertEquals(3, AllUsage.jumpTo(slots, UsageAgent.Codex, picked = "c"))
        // The picked one has no card of that agent: that agent's first.
        assertEquals(2, AllUsage.jumpTo(slots, UsageAgent.Codex, picked = "0"))
        assertEquals(0, AllUsage.jumpTo(slots, UsageAgent.Claude, picked = "c"))
        // No such agent anywhere: the first card; no cards: none.
        assertEquals(0, AllUsage.jumpTo(all(part("0", FakeHub("z2", claude, null))), UsageAgent.Codex, picked = "0"))
        assertEquals(-1, AllUsage.jumpTo(emptyList<UsageSlot<FakeHub>>(), UsageAgent.Claude))
        // One computer: no server, the agent's own card.
        val one = AllUsage.of(FakeHub("z2", claude, codexOn), claude, codexOn)
        assertEquals(1, AllUsage.jumpTo(one, UsageAgent.Codex))
        assertNull(one[1].server)
        assertFalse(one[1].offline)
    }
}
