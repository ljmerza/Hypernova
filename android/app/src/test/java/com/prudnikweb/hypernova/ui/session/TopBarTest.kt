package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.ui.session.TopBarItem.AGENTS
import com.prudnikweb.hypernova.ui.session.TopBarItem.CONTEXT
import com.prudnikweb.hypernova.ui.session.TopBarItem.FIVE_HOUR
import com.prudnikweb.hypernova.ui.session.TopBarItem.GIT
import com.prudnikweb.hypernova.ui.session.TopBarItem.COST
import com.prudnikweb.hypernova.ui.session.TopBarItem.EFFORT
import com.prudnikweb.hypernova.ui.session.TopBarItem.MODE
import com.prudnikweb.hypernova.ui.session.TopBarItem.MODEL
import com.prudnikweb.hypernova.ui.session.TopBarItem.MODEL_WEEK
import com.prudnikweb.hypernova.ui.session.TopBarItem.SEVEN_DAY
import com.prudnikweb.hypernova.ui.session.TopBarItem.TOKENS
import com.prudnikweb.hypernova.ui.session.TopBarItem.TURN
import com.prudnikweb.hypernova.ui.session.TopBarItem.WEEK_PACE
import com.prudnikweb.hypernova.data.CodexAccount
import com.prudnikweb.hypernova.data.CodexState
import com.prudnikweb.hypernova.data.CodexUsage
import com.prudnikweb.hypernova.data.ModelWeek
import com.prudnikweb.hypernova.data.Usage
import com.prudnikweb.hypernova.data.Window
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
        assertEquals(listOf(CONTEXT, MODE, EFFORT, FIVE_HOUR, SEVEN_DAY), d.items)
        // The rest are there to turn on, off, after them.
        assertEquals(listOf(CONTEXT, MODE, EFFORT, FIVE_HOUR, SEVEN_DAY, MODEL, TOKENS, COST, AGENTS, TURN, WEEK_PACE, GIT, MODEL_WEEK), d.order)
        assertTrue(d.isDefault)
    }

    @Test fun theFiveHourWindowShowsOnlyWhileClaudeCodeReportsOne() {
        val d = TopBarChoice.DEFAULT
        assertEquals(listOf(CONTEXT, MODE, EFFORT, FIVE_HOUR, SEVEN_DAY), d.visible(facts, now))
        // Max from 2026-10-31: the week alone.
        assertEquals(listOf(CONTEXT, MODE, EFFORT, SEVEN_DAY), d.visible(facts.copy(fiveHour = false), now))
        // A session on another provider, or no numbers yet: no plan windows at all.
        assertEquals(listOf(CONTEXT, MODE, EFFORT), d.visible(facts.copy(plan = false), now))
    }

    @Test fun agentsOnlyWhileAnyWorkAndTheTurnsTimeOnlyInATurn() {
        val all = TopBarChoice.DEFAULT.set(AGENTS, true).set(TURN, true).set(TOKENS, true)
        assertEquals(listOf(CONTEXT, MODE, EFFORT, FIVE_HOUR, SEVEN_DAY, TOKENS), all.visible(facts, now))
        val busy = facts.copy(agents = 3, turnStartedAt = now - 4 * 60_000L)
        assertEquals(listOf(CONTEXT, MODE, EFFORT, FIVE_HOUR, SEVEN_DAY, TOKENS, AGENTS, TURN), all.visible(busy, now))
        // Ended: no turn; no tokens spent yet (a rewind's new session): none.
        assertEquals(listOf(CONTEXT, MODE, EFFORT, FIVE_HOUR, SEVEN_DAY, AGENTS), all.visible(busy.copy(ended = true, tokens = 0), now))
    }

    @Test fun theEffortFollowsTheModeOnlyForAClaudeSessionWithOne() {
        assertTrue(facts.effortShown)
        assertFalse(facts.copy(effort = null).effortShown)
        // Codex shows its mode alone.
        assertFalse(facts.copy(codex = true).effortShown)
    }

    @Test fun aLineFromWhenModeAndEffortWereOneKeepsTheEffortWhereItWas() {
        val on = TopBarChoice.decode("context,mode,sevenDay")
        assertEquals(listOf(CONTEXT, MODE, EFFORT, SEVEN_DAY), on.order.take(4))
        assertTrue(EFFORT in on.shown)
        val off = TopBarChoice.decode("context,!mode,sevenDay")
        assertEquals(listOf(CONTEXT, MODE, EFFORT, SEVEN_DAY), off.order.take(4))
        assertFalse(EFFORT in off.shown)
        assertFalse(COST in on.shown)
    }

    @Test fun theEffortAndTheCostDrawOnlyWithSomethingToSay() {
        val all = TopBarChoice(TopBarItem.entries.toList(), setOf(MODE, EFFORT, COST))
        assertEquals(listOf(MODE, EFFORT), all.visible(facts, now))
        assertEquals(listOf(MODE, EFFORT, COST), all.visible(facts.copy(cost = 7.86), now))
        assertEquals(listOf(MODE), all.visible(facts.copy(codex = true), now))
        assertEquals(listOf(MODE), all.visible(facts.copy(effort = null, cost = 0.0), now))
    }

    @Test fun movingAndTurningOffKeepTheOwnersOrder() {
        val c = TopBarChoice.DEFAULT.move(SEVEN_DAY, -1).move(SEVEN_DAY, -1).set(MODE, false).set(MODEL, true)
        assertEquals(listOf(CONTEXT, SEVEN_DAY, EFFORT, FIVE_HOUR, MODEL), c.items)
        // At an end it stays.
        assertEquals(c, c.move(CONTEXT, -1))
        assertEquals(c, c.move(c.order.last(), 1))
        assertFalse(c.isDefault)
    }

    @Test fun itIsKeptAsOneLineAndReadBack() {
        val c = TopBarChoice.DEFAULT.move(TURN, -9).set(TURN, true).set(FIVE_HOUR, false)
        val line = c.encode()
        assertEquals("turn,context,mode,effort,!fiveHour,sevenDay,!model,!tokens,!cost,!agents,!weekPace,!git,!modelWeek", line)
        assertEquals(c, TopBarChoice.decode(line))
        // Nothing kept yet: the default.
        assertEquals(TopBarChoice.DEFAULT, TopBarChoice.decode(null))
        assertEquals(TopBarChoice.DEFAULT, TopBarChoice.decode(""))
    }

    @Test fun anOlderLineGetsNewPartsOffAtTheEndAndUnknownOnesPassedOver() {
        val c = TopBarChoice.decode("sevenDay,context,later,context,!mode")
        assertEquals(listOf(SEVEN_DAY, CONTEXT, MODE, EFFORT, FIVE_HOUR, MODEL, TOKENS, COST, AGENTS, TURN, WEEK_PACE, GIT, MODEL_WEEK), c.order)
        assertEquals(setOf(SEVEN_DAY, CONTEXT), c.shown)
    }

    // ─── The week's pace ───

    private val hour = 3_600_000L
    private val day = 24 * hour
    private val week = 7 * day

    @Test fun theWeeksPaceIsTheDayAndTheUseLessTheWeekGone() {
        // Two days and a bit in, at 40%: "Day 3/7", an even week's 30.
        assertEquals(WeekPace(3, 10), weekPace(0.40f, now + week - (2 * day + 2 * hour + 24 * 60_000L), now))
        // Half a week gone, a quarter used.
        assertEquals(WeekPace(4, -25), weekPace(0.25f, now + week / 2, now))
    }

    @Test fun theWeeksPaceJustAfterAReset() {
        // The moment it reset: day 1, not 0, and nothing gone yet.
        assertEquals(WeekPace(1, 0), weekPace(0f, now + week, now))
        assertEquals(WeekPace(1, 0), weekPace(0f, now + week - 1, now))
        assertEquals(WeekPace(1, 3), weekPace(0.03f, now + week - 60_000L, now))
    }

    @Test fun theWeeksPaceChangesDayAtTheDaysEdge() {
        // Exactly one day gone is still day 1, a moment later day 2 (never 2 from rounding at the edge).
        assertEquals(1, weekPace(0.1f, now + week - day, now)!!.day)
        assertEquals(2, weekPace(0.1f, now + week - day - 1, now)!!.day)
        // Exactly three days: a share of 3/7 times 7 in floating point could tip this to 4.
        assertEquals(3, weekPace(0.1f, now + week - 3 * day, now)!!.day)
    }

    @Test fun theWeeksPaceInTheLastHour() {
        assertEquals(WeekPace(7, 0), weekPace(0.995f, now + hour, now))
        assertEquals(WeekPace(7, -99), weekPace(0f, now + hour, now))
        assertEquals(WeekPace(7, 1), weekPace(1f, now + hour, now))
    }

    @Test fun theWeeksPaceAtNoneAndAtAllOfTheWeek() {
        assertEquals(WeekPace(4, -50), weekPace(0f, now + week / 2, now))
        assertEquals(WeekPace(4, 50), weekPace(1f, now + week / 2, now))
        // Out of range stays in it.
        assertEquals(WeekPace(4, 50), weekPace(1.4f, now + week / 2, now))
        assertEquals(WeekPace(4, -50), weekPace(-0.2f, now + week / 2, now))
    }

    @Test fun aWeekPastItsResetOrWithoutOneHasNoPace() {
        assertEquals(null, weekPace(0.5f, now, now))
        assertEquals(null, weekPace(0.5f, now - hour, now))
        assertEquals(null, weekPace(0.5f, 0L, now))
        // A reset further off than a week (clocks disagreeing): nothing gone yet, day 1.
        assertEquals(WeekPace(1, 50), weekPace(0.5f, now + 2 * week, now))
    }

    @Test fun theWeeksPaceAlwaysHasASign() {
        assertEquals("+10%", WeekPace(2, 10).figure)
        assertEquals("-8%", WeekPace(2, -8).figure)
        assertEquals("+0%", WeekPace(2, 0).figure)
        // Under half a point below even is even, never "-0%".
        assertEquals("+0%", weekPace(0.496f, now + week / 2, now)!!.figure)
    }

    private val planUsage = Usage(fiveHour = 0.2f, sevenDay = 0.25f, fiveHourResetAt = now + hour, sevenDayResetAt = now + week / 2)

    @Test fun theWeeksPaceIsThePlansOnlyWhileItsAboutThisSession() {
        assertEquals(WeekPace(4, -25), weekPaceOf(codexSession = false, plan = true, planUsage, null, now))
        // Another provider, unknown or stale (planShown).
        assertEquals(null, weekPaceOf(codexSession = false, plan = false, planUsage, null, now))
        // A week limit hit reads full, as the 7-day meter beside it.
        assertEquals(WeekPace(4, 50), weekPaceOf(codexSession = false, plan = true, planUsage.copy(weekLimitedAt = now), null, now))
    }

    @Test fun aCodexSessionsPaceIsCodexsOwnWeek() {
        val usage = CodexUsage(fiveHour = null, sevenDay = Window(40.0, now + week / 2), limited = false, observedAt = now)
        val codex = CodexState(available = true, enabled = true, connected = true, usage = usage)
        // Never the Claude plan's week, whatever that says.
        assertEquals(WeekPace(4, -10), weekPaceOf(codexSession = true, plan = true, planUsage, codex, now))
        assertEquals(null, weekPaceOf(codexSession = true, plan = true, planUsage, null, now))
        assertEquals(null, weekPaceOf(codexSession = true, plan = true, planUsage, codex.copy(usage = usage.copy(sevenDay = null)), now))
        // Signed out: the last numbers, no longer measured.
        assertEquals(null, weekPaceOf(codexSession = true, plan = true, planUsage, codex.copy(account = CodexAccount(false, null, null)), now))
    }

    // ─── Git ───

    @Test fun gitDrawsOnlyWhenOnAndTheRouteSaidARepository() {
        assertFalse(GIT in TopBarChoice.DEFAULT.items)
        val on = TopBarChoice.DEFAULT.set(GIT, true)
        assertFalse(GIT in on.visible(facts, now))
        assertTrue(GIT in on.visible(facts.copy(git = true), now))
        assertFalse(GIT in TopBarChoice.DEFAULT.visible(facts.copy(git = true), now))
    }

    private val calm = GitMoment(inTurn = false, edits = 3, activity = 100L, linked = true)
    private val turn = calm.copy(inTurn = true)

    @Test fun gitAsksAgainWhenATurnEndsOrAToolEditsAFile() {
        assertTrue("the turn ended", calm.asksAgainAfter(turn))
        assertTrue("a tool edited a file", turn.copy(edits = 4).asksAgainAfter(turn))
        assertTrue("a turn the phone slept through", calm.copy(activity = 200L).asksAgainAfter(calm))
        assertTrue("back from a lost link", calm.asksAgainAfter(calm.copy(linked = false)))
    }

    @Test fun gitDoesNotAskForATurnStartingOrItsOtherActivity() {
        assertFalse("nothing changed", calm.asksAgainAfter(calm))
        assertFalse("a turn starting", turn.asksAgainAfter(calm))
        assertFalse("a turn's activity moves all the time", turn.copy(activity = 200L).asksAgainAfter(turn))
        assertFalse("no link, no asking", calm.copy(linked = false).asksAgainAfter(turn))
        assertFalse("no link, even after an edit", turn.copy(edits = 4, linked = false).asksAgainAfter(turn))
    }

    @Test fun theWeeksPaceDrawsOnlyWhenOnAndKnown() {
        assertFalse(WEEK_PACE in TopBarChoice.DEFAULT.items)
        val on = TopBarChoice.DEFAULT.set(WEEK_PACE, true)
        assertFalse(WEEK_PACE in on.visible(facts, now))
        assertTrue(WEEK_PACE in on.visible(facts.copy(weekPace = WeekPace(2, 10)), now))
        assertFalse(WEEK_PACE in TopBarChoice.DEFAULT.visible(facts.copy(weekPace = WeekPace(2, 10)), now))
    }

    // ─── This model's week ───

    private val weeks = listOf(ModelWeek("Fable 5.1", 0.41f, now + 2 * day), ModelWeek("Sonnet", 0.2f, null))

    @Test fun theModelsWeekGoesByItsFamily() {
        for (model in listOf("claude-fable-5-1", "claude-fable-5-1[1m]", "Fable 5.1", "fable", "anthropic/claude-fable-5-1")) {
            assertEquals(model, "Fable 5.1", modelWeekOf(weeks, model)?.label)
        }
        assertEquals("Sonnet", modelWeekOf(weeks, "claude-sonnet-4-5-20250929")?.label)
    }

    @Test fun aModelWithoutAWeekOfItsOwnHasNone() {
        assertEquals(null, modelWeekOf(weeks, "claude-opus-5-5"))
        assertEquals(null, modelWeekOf(weeks, "gpt-5"))
        // Not known yet: the session's word for a model nobody has named.
        assertEquals(null, modelWeekOf(weeks, "Default"))
        assertEquals(null, modelWeekOf(weeks, ""))
        assertEquals(null, modelWeekOf(emptyList(), "claude-fable-5-1"))
    }

    @Test fun theModelsWeekIsLabelledShort() {
        assertEquals("Fable", weeks[0].shortLabel)
        assertEquals("Sonnet", weeks[1].shortLabel)
    }

    @Test fun theModelsWeekDrawsOnlyWhenOnAboutThisSessionAndThere() {
        assertFalse(MODEL_WEEK in TopBarChoice.DEFAULT.items)
        val on = TopBarChoice.DEFAULT.set(MODEL_WEEK, true)
        assertFalse(MODEL_WEEK in on.visible(facts, now))
        assertTrue(MODEL_WEEK in on.visible(facts.copy(modelWeek = weeks[0]), now))
        // Another provider's session, or a plan that's stale or unknown.
        assertFalse(MODEL_WEEK in on.visible(facts.copy(modelWeek = weeks[0], plan = false), now))
        assertFalse(MODEL_WEEK in TopBarChoice.DEFAULT.visible(facts.copy(modelWeek = weeks[0]), now))
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
