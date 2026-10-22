package com.prudnikweb.hypernova.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fresh look at /usage when the owner opens his usage (data/UsageRefresh.kt): the first one goes
 * out, then at most one every few minutes, since each starts Claude Code on the Mac.
 */
class UsageRefreshTest {
    private val t = 1_800_000_000_000L

    @Test fun theFirstGoesOutThenOneEveryFewMinutes() {
        assertTrue(UsageRefresh.due(t, last = 0L))
        assertFalse(UsageRefresh.due(t + 1_000, last = t))
        assertFalse(UsageRefresh.due(t + UsageRefresh.EVERY_MS - 1, last = t))
        assertTrue(UsageRefresh.due(t + UsageRefresh.EVERY_MS, last = t))
    }

    @Test fun aClockSetBackDoesNotBlockItForLong() {
        // The phone's clock jumped back an hour: the next one goes out rather than waiting an hour more.
        assertTrue(UsageRefresh.due(t - 3_600_000L, last = t))
    }
}
