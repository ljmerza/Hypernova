package com.prudnikweb.hypernova.ui.fx

import com.prudnikweb.hypernova.data.Usage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitFeelTest {
    private val now = 1_800_000_000_000L
    private val reset = now + 2 * 3_600_000L

    private fun usage(limited: Boolean, at: Long? = now, resetAt: Long = reset, weekLimited: Boolean = false) = Usage(
        fiveHour = if (limited) 1f else 0.4f,
        sevenDay = if (weekLimited) 1f else 0.2f,
        fiveHourResetAt = resetAt,
        sevenDayResetAt = now + 4 * 86_400_000L,
        limitedAt = if (limited) at else null,
        weekLimitedAt = if (weekLimited) now else null,
    )

    @Test fun aLimitHitAsYouWatchIsFeltOnce() {
        val f = LimitFeel()
        assertFalse(f.see(usage(false), now - 60_000, reduced = false))
        assertTrue(f.see(usage(true), now, reduced = false))
        assertFalse(f.see(usage(true), now + 5_000, reduced = false))
    }

    @Test fun aLimitFoundLaterIsNotFelt() {
        assertFalse(LimitFeel().see(usage(true, at = now - 10_000), now, reduced = false))
    }

    @Test fun reduceMotionFeelsNothing() {
        assertFalse(LimitFeel().see(usage(true), now, reduced = true))
    }

    @Test fun aGlitchThatLiftsTheLimitDoesNotFeelItAgain() {
        val f = LimitFeel()
        assertTrue(f.see(usage(true), now, reduced = false))
        assertFalse(f.see(usage(false), now + 1_000, reduced = false))
        assertFalse(f.see(usage(true), now + 2_000, reduced = false))
        // Lifted for long enough to count, then back with the same start: still the same limit.
        assertFalse(f.see(usage(false), now + 3_000, reduced = false))
        assertFalse(f.see(usage(false), now + 30_000, reduced = false))
        assertFalse(f.see(usage(true, at = now), now + 31_000, reduced = false))
    }

    @Test fun theSameWindowRestampedIsNotFeltAgain() {
        val f = LimitFeel()
        assertTrue(f.see(usage(true), now, reduced = false))
        assertFalse(f.see(usage(false), now + 1_000, reduced = false))
        assertFalse(f.see(usage(false), now + 30_000, reduced = false))
        assertFalse(f.see(usage(true, at = now + 31_000, resetAt = reset + 60_000), now + 31_000, reduced = false))
    }

    @Test fun theNextWindowsLimitIsFelt() {
        val f = LimitFeel()
        assertTrue(f.see(usage(true), now, reduced = false))
        // The reset comes: a stale reading of the old window isn't a limit.
        assertFalse(f.see(usage(false), reset, reduced = false))
        assertFalse(f.see(usage(true, at = now), reset + 1_000, reduced = false))
        val later = reset + 3_600_000L
        assertTrue(f.see(usage(true, at = later, resetAt = later + 4 * 3_600_000L), later, reduced = false))
    }

    @Test fun nothingIsFeltWhileTheWeekIsSpent() {
        assertFalse(LimitFeel().see(usage(true, weekLimited = true), now, reduced = false))
    }
}
