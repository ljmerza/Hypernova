package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a usage meter shows (data/UsageWindow.kt): how much is used and when it resets, both facts.
 * No estimate of when it runs out (owner, 2026-11-01), whatever the spend rate says.
 */
class UsageWindowTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    @Test fun aWindowIsWhatsUsedAndWhenItResets() {
        val p = UsageWindow.of(0.42f, now + 134 * min, now)
        assertEquals(0.42f, p.used, 1e-6f)
        assertEquals(134 * min, p.resetIn)
        assertFalse(p.full)
    }

    @Test fun whileWorkRunsTheWindowIsTheSame() {
        // The owner's case: 12% of a week just after it reset, at a pace that would "run out" in 2 days.
        val working = Usage(0.12f, 0.12f, now + 134 * min, now + 6 * 1440 * min, rate = UsageRate(0.8f, 0.03f))
        val idle = working.copy(rate = null)
        assertEquals(UsageWindow.fiveHour(idle, now), UsageWindow.fiveHour(working, now))
        assertEquals(UsageWindow.sevenDay(idle, now), UsageWindow.sevenDay(working, now))
        assertEquals(UsageWindow(0.12f, 6 * 1440 * min), UsageWindow.sevenDay(working, now))
    }

    @Test fun aUsedUpWindowIsFullUntilItsReset() {
        val p = UsageWindow.of(0.97f, now + 72 * min, now, limited = true)
        assertTrue(p.full)
        assertEquals(1f, p.used, 0f)
        assertEquals(72 * min, p.resetIn)
        // Used up by the numbers, with no limit reported yet.
        assertTrue(UsageWindow.of(1f, now + 72 * min, now).full)
        // A limit Claude Code reported, on either window.
        val u = Usage(0.42f, 0.18f, now + 134 * min, now + 3660 * min, rate = UsageRate(0.8f, 0.05f))
        assertTrue(UsageWindow.fiveHour(u.copy(limitedAt = now), now).full)
        assertTrue(UsageWindow.sevenDay(u.copy(weekLimitedAt = now), now).full)
        assertFalse(UsageWindow.sevenDay(u.copy(limitedAt = now), now).full)
    }

    @Test fun aPastResetReadsZeroAndAnUnknownOneReadsNothing() {
        assertEquals(0L, UsageWindow.of(0.3f, now - 5_000, now).resetIn)
        assertEquals(-1L, UsageWindow.of(0.41f, 0L, now).resetIn)
    }

    @Test fun anOlderDaemonsTimeToTheLimitIsIgnored() {
        val old = JSONObject("""{"fiveHour":{"pct":12,"resetsAt":$now},"sevenDay":null,"source":"official","observedAt":$now,
            "rate":{"fiveHour":0.8,"sevenDay":null,"fiveHourEtaMs":6600000,"sevenDayEtaMs":null,"fiveHourLimitAt":$now,"sevenDayLimitAt":null,"working":true}}""")
        assertEquals(UsageRate(0.8f, null), Wire.usage(old)!!.rate)
    }

    @Test fun aDaemonWithoutTheTimeToTheLimitOrAnyRateIsRead() {
        val new = JSONObject("""{"fiveHour":null,"sevenDay":null,"source":"none","observedAt":null,"rate":{"fiveHour":0.8,"sevenDay":0.05,"working":true}}""")
        assertEquals(UsageRate(0.8f, 0.05f), Wire.usage(new)!!.rate)
        assertNull(Wire.usage(JSONObject("""{"fiveHour":null,"sevenDay":null,"source":"none"}"""))!!.rate)
    }
}
