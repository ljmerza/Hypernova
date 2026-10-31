package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.ui.session.TopBarItem.AGENTS
import com.prudnikweb.hypernova.ui.session.TopBarItem.CONTEXT
import com.prudnikweb.hypernova.ui.session.TopBarItem.FIVE_HOUR
import com.prudnikweb.hypernova.ui.session.TopBarItem.MODE
import com.prudnikweb.hypernova.ui.session.TopBarItem.MODEL
import com.prudnikweb.hypernova.ui.session.TopBarItem.SEVEN_DAY
import com.prudnikweb.hypernova.ui.session.TopBarItem.TOKENS
import com.prudnikweb.hypernova.ui.session.TopBarItem.TURN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session's top bar (ui/session/TopBar.kt): the owner's choice of parts and their order
 * (Settings → Session top bar), kept as one line, and which parts draw given the session and the
 * plan (the 5-hour window only while Claude Code reports one; owner, 2026-10-31).
 */
class TopBarTest {
    private val now = 1_800_000_000_000L
    private val facts = TopBarFacts(
        codex = false, effort = "high", tokens = 7_400_000, agents = 0, turnStartedAt = 0L, ended = false, plan = true, fiveHour = true,
    )

    @Test fun theDefaultIsContextModeThenThePlansWindows() {
        val d = TopBarChoice.DEFAULT
        assertEquals(listOf(CONTEXT, MODE, FIVE_HOUR, SEVEN_DAY), d.items)
        // The rest are there to turn on, off, after them.
        assertEquals(listOf(CONTEXT, MODE, FIVE_HOUR, SEVEN_DAY, MODEL, TOKENS, AGENTS, TURN), d.order)
        assertTrue(d.isDefault)
    }

    @Test fun theFiveHourWindowShowsOnlyWhileClaudeCodeReportsOne() {
        val d = TopBarChoice.DEFAULT
        assertEquals(listOf(CONTEXT, MODE, FIVE_HOUR, SEVEN_DAY), d.visible(facts, now))
        // Max from 2026-10-31: the week alone.
        assertEquals(listOf(CONTEXT, MODE, SEVEN_DAY), d.visible(facts.copy(fiveHour = false), now))
        // A session on another provider, or no numbers yet: no plan windows at all.
        assertEquals(listOf(CONTEXT, MODE), d.visible(facts.copy(plan = false), now))
    }

    @Test fun agentsOnlyWhileAnyWorkAndTheTurnsTimeOnlyInATurn() {
        val all = TopBarChoice.DEFAULT.set(AGENTS, true).set(TURN, true).set(TOKENS, true)
        assertEquals(listOf(CONTEXT, MODE, FIVE_HOUR, SEVEN_DAY, TOKENS), all.visible(facts, now))
        val busy = facts.copy(agents = 3, turnStartedAt = now - 4 * 60_000L)
        assertEquals(listOf(CONTEXT, MODE, FIVE_HOUR, SEVEN_DAY, TOKENS, AGENTS, TURN), all.visible(busy, now))
        // Ended: no turn; no tokens spent yet (a rewind's new session): none.
        assertEquals(listOf(CONTEXT, MODE, FIVE_HOUR, SEVEN_DAY, AGENTS), all.visible(busy.copy(ended = true, tokens = 0), now))
    }

    @Test fun theEffortFollowsTheModeOnlyForAClaudeSessionWithOne() {
        assertTrue(facts.effortShown)
        assertFalse(facts.copy(effort = null).effortShown)
        // Codex shows its mode alone.
        assertFalse(facts.copy(codex = true).effortShown)
    }

    @Test fun movingAndTurningOffKeepTheOwnersOrder() {
        val c = TopBarChoice.DEFAULT.move(SEVEN_DAY, -1).move(SEVEN_DAY, -1).set(MODE, false).set(MODEL, true)
        assertEquals(listOf(CONTEXT, SEVEN_DAY, FIVE_HOUR, MODEL), c.items)
        // At an end it stays.
        assertEquals(c, c.move(CONTEXT, -1))
        assertEquals(c, c.move(TURN, 1))
        assertFalse(c.isDefault)
    }

    @Test fun itIsKeptAsOneLineAndReadBack() {
        val c = TopBarChoice.DEFAULT.move(TURN, -7).set(TURN, true).set(FIVE_HOUR, false)
        val line = c.encode()
        assertEquals("turn,context,mode,!fiveHour,sevenDay,!model,!tokens,!agents", line)
        assertEquals(c, TopBarChoice.decode(line))
        // Nothing kept yet: the default.
        assertEquals(TopBarChoice.DEFAULT, TopBarChoice.decode(null))
        assertEquals(TopBarChoice.DEFAULT, TopBarChoice.decode(""))
    }

    @Test fun anOlderLineGetsNewPartsOffAtTheEndAndUnknownOnesPassedOver() {
        val c = TopBarChoice.decode("sevenDay,context,later,context,!mode")
        assertEquals(listOf(SEVEN_DAY, CONTEXT, MODE, FIVE_HOUR, MODEL, TOKENS, AGENTS, TURN), c.order)
        assertEquals(setOf(SEVEN_DAY, CONTEXT), c.shown)
    }

    @Test fun theTurnsTimeIsShort() {
        assertEquals("<1m", turnShort(20_000))
        assertEquals("4m", turnShort(4 * 60_000L + 30_000))
        assertEquals("1h 12m", turnShort(72 * 60_000L))
        assertEquals("<1m", turnShort(-5))
    }

    @Test fun theSheetOpensOnAgentsFromTheCount() {
        assertEquals(SheetTab.Agents, openingTab(atBlocked = false, atAgents = true))
        // The blocked mark wins: it's the more urgent.
        assertEquals(SheetTab.Permissions, openingTab(atBlocked = true, atAgents = true))
    }
}
