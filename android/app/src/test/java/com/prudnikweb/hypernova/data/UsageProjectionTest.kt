package com.prudnikweb.hypernova.data

import com.prudnikweb.hypernova.data.UsageProjection.Tag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection meters' math (data/UsageProjection.kt): where the pace leads by the reset, and the
 * one tag a meter shows (the time to the limit when that comes first, else the reset).
 */
class UsageProjectionTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    @Test fun atRestThereIsNoFaintFillAndTheTagIsTheReset() {
        val p = UsageProjection.of(0.42f, now + 134 * min, now, perMin = null)
        assertEquals(0.42f, p.used, 1e-6f)
        assertNull(p.projected)
        assertEquals(Tag.Reset(134 * min), p.tag)
        assertFalse(p.limitFirst)
        // A pace too small to count is the same as none.
        assertNull(UsageProjection.of(0.42f, now + 134 * min, now, perMin = 0.001f).projected)
    }

    @Test fun aSlowPaceLeadsPartWayAndTheResetStaysTheTag() {
        // 42% + 0.15%/min × 134 min = 62.1% by the reset.
        val p = UsageProjection.of(0.42f, now + 134 * min, now, perMin = 0.15f)
        assertEquals(0.621f, p.projected!!, 1e-3f)
        assertEquals(Tag.Reset(134 * min), p.tag)
        assertEquals(0.15f, p.pace!!, 1e-6f)
    }

    @Test fun aFastPaceThatReachesTheLimitFirstTagsTheTimeToIt() {
        // 42% + 0.8%/min: full in 72.5 minutes, an hour before the reset.
        val p = UsageProjection.of(0.42f, now + 134 * min, now, perMin = 0.8f)
        assertEquals(1f, p.projected!!, 1e-6f)
        assertTrue(p.limitFirst)
        val t = p.tag as Tag.Limit
        assertEquals(72.5f, t.ms / 60_000f, 0.01f)
        assertEquals(134 * min, p.resetIn)
    }

    @Test fun theMacsOwnTimeToTheLimitWins() {
        val p = UsageProjection.of(0.42f, now + 134 * min, now, perMin = 0.8f, etaMs = 47 * min)
        assertEquals(Tag.Limit(47 * min), p.tag)
        // One that isn't before the reset is ignored (the phone works it out).
        val q = UsageProjection.of(0.42f, now + 134 * min, now, perMin = 0.1f, etaMs = 200 * min)
        assertEquals(Tag.Reset(134 * min), q.tag)
    }

    @Test fun justReachingTheLimitAtTheResetIsntFirst() {
        // 50% + 0.5%/min × 100 min = exactly 100% at the reset: it lasts.
        val p = UsageProjection.of(0.5f, now + 100 * min, now, perMin = 0.5f)
        assertEquals(1f, p.projected!!, 1e-4f)
        assertFalse(p.limitFirst)
    }

    @Test fun atTheLimitTheMeterIsFullWithNoPace() {
        val p = UsageProjection.of(0.97f, now + 72 * min, now, perMin = 0.8f, limited = true)
        assertTrue(p.full)
        assertEquals(1f, p.used, 0f)
        assertNull(p.projected)
        assertEquals(Tag.Reset(72 * min), p.tag)
    }

    @Test fun aLimitSoonIsAtLeastAMinuteAndAPastResetReadsZero() {
        val p = UsageProjection.of(0.999f, now + 60 * min, now, perMin = 5f)
        assertEquals(Tag.Limit(60_000L), p.tag)
        val q = UsageProjection.of(0.3f, now - 5_000, now, perMin = null)
        assertEquals(Tag.Reset(0L), q.tag)
    }

    @Test fun aWindowWithNoKnownResetHasNoTag() {
        val p = UsageProjection.of(0.41f, 0L, now, perMin = 0.2f)
        assertEquals(Tag.None, p.tag)
        assertNull(p.projected)
    }

    @Test fun usageWindowsComeFromTheRate() {
        val u = Usage(0.42f, 0.18f, now + 134 * min, now + 61 * 60 * min, rate = UsageRate(0.8f, 0.05f, 47 * min, null))
        val five = UsageProjection.fiveHour(u, now)
        assertEquals(Tag.Limit(47 * min), five.tag)
        val seven = UsageProjection.sevenDay(u, now)
        // 18% + 0.05%/min × 3660 min = 201%: the week's limit comes first too, worked out on the phone.
        assertTrue(seven.limitFirst)
        // Nothing working: no rate, plain meters.
        val idle = UsageProjection.fiveHour(u.copy(rate = null), now)
        assertNull(idle.projected)
        // A limit hit: full, whatever the rate says.
        assertTrue(UsageProjection.fiveHour(u.copy(limitedAt = now), now).full)
    }
}
