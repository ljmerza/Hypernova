package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 5-hour window shows only while Claude Code reports one (owner, 2026-10-31: Max dropped it).
 * The rule is the Mac's numbers, not a list of plans (data/UsageProjection.kt).
 */
class FiveHourReportedTest {
    private val now = 1_800_000_000_000L
    private val week = Window(62.0, now + 3 * 86_400_000L)
    private val five = Window(14.0, now + 2 * 3_600_000L)

    @Test fun reportedWhileTheMacHasOneOrNothingIsKnownYet() {
        assertTrue(UsageProjection.fiveHourReported(five, week))
        assertTrue(UsageProjection.fiveHourReported(five, null))
        // Nothing known: as before (the meters wait for numbers).
        assertTrue(UsageProjection.fiveHourReported(null, null))
    }

    @Test fun notReportedWhenTheMacHasTheWeekAlone() {
        assertFalse(UsageProjection.fiveHourReported(null, week))
        // A 5-hour limit the account is stopped at says there is one, whatever the numbers.
        assertTrue(UsageProjection.fiveHourReported(null, week, hit = true))
    }

    @Test fun withoutOneTheSunsDiskFollowsTheWeek() {
        val read = Usage(
            fiveHour = 0f, sevenDay = 0.62f, fiveHourResetAt = now + 5 * 3_600_000L, sevenDayResetAt = week.resetsAt,
            known = true, rate = UsageRate(fiveHour = null, sevenDay = 0.02f, fiveHourEtaMs = null, sevenDayEtaMs = 9_000_000L),
        )
        val u = UsageProjection.weekAsDisk(read)
        assertFalse(u.fiveHourReported)
        assertEquals(0.62f, u.fiveHour, 1e-6f)
        assertEquals(week.resetsAt, u.fiveHourResetAt)
        assertNull(u.limitedAt)
        assertEquals(0.02f, u.rate!!.fiveHour!!, 1e-6f)
        assertEquals(9_000_000L, u.rate!!.fiveHourEtaMs)
        // The week's own numbers stay as they were.
        assertEquals(0.62f, u.sevenDay, 1e-6f)
        assertEquals(week.resetsAt, u.sevenDayResetAt)
        // The sun's meter reads the week then.
        assertEquals(UsageProjection.sevenDay(u, now).used, UsageProjection.fiveHour(u, now).used, 1e-6f)
    }

    @Test fun aWeekOnlyCodexPlanSaysItHasNoFiveHourWindowToo() {
        fun codex(u: CodexUsage) = CodexState(available = true, enabled = true, connected = true, account = CodexAccount(true, "chatgpt", "plus"), usage = u)
        assertFalse(AgentLead.codexSunUsage(codex(CodexUsage(null, week, false, now)), now).fiveHourReported)
        assertTrue(AgentLead.codexSunUsage(codex(CodexUsage(five, week, false, now)), now).fiveHourReported)
        // No Codex numbers: nothing to say (not known).
        assertTrue(AgentLead.codexSunUsage(null, now).fiveHourReported)
    }
}
