package com.prudnikweb.hypernova.ui.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every countdown (the sky's "REFILLS IN", the account card's and the meters' "resets in") rounds
 * the same way and looks again exactly when its text changes (ui/fx/Countdown.kt). The owner saw
 * the sky read 5:59 over a card reading 6:06.
 */
class CountdownTest {
    private val at = 1_000_000_000L

    @Test fun secondsRoundUpAndReachZeroOnlyAtTheMoment() {
        assertEquals(360L, Countdown.seconds(at, at - 359_001L))
        assertEquals(360L, Countdown.seconds(at, at - 360_000L))
        assertEquals(361L, Countdown.seconds(at, at - 360_001L))
        assertEquals(1L, Countdown.seconds(at, at - 1L))
        assertEquals(0L, Countdown.seconds(at, at))
        assertEquals(0L, Countdown.seconds(at, at + 5_000L))
    }

    @Test fun twoClocksAFewSecondsApartNoLongerDisagreeOnceBothLookOnTime() {
        // The card looked at 6:06 left and slept 20 s; the sky drew at 5:59. Now the card wakes
        // when its text changes, so at any moment it has looked within the current second.
        var now = at - 366_000L
        while (now < at) {
            val next = Countdown.nextChange(at, now) ?: break
            val woke = now + next
            // At the wake the text is the new one, and it held the old one until just before.
            assertEquals(Countdown.seconds(at, now) - 1L, Countdown.seconds(at, woke))
            assertEquals(Countdown.seconds(at, now), Countdown.seconds(at, woke - 1L))
            now = woke
        }
        assertEquals(at, now)
    }

    @Test fun minutesWakeOnceAMinuteAtTheMinutesEdge() {
        // 12m 30s left reads "12m" until 11m 59s.
        val now = at - (12 * 60 + 30) * 1000L
        assertEquals(31_000L, Countdown.nextChange(at, now))
        // From ten minutes down it counts in seconds ("9:59"), so "10m" lasts one second.
        assertEquals(1_000L, Countdown.nextChange(at, at - 600_000L))
        assertEquals(1_000L, Countdown.nextChange(at, at - 599_000L))
        // Hours and minutes ("4h 12m") also change at each minute: from 4h 12m 59s, a minute on.
        assertEquals(60_000L, Countdown.nextChange(at, at - (4 * 3600 + 12 * 60 + 59) * 1000L))
        assertEquals(1_000L, Countdown.nextChange(at, at - (4 * 3600 + 12 * 60) * 1000L))
        // Days and hours ("2d 4h") at each hour: from 2d 4h 59m 59s, an hour on.
        assertEquals(3_600_000L, Countdown.nextChange(at, at - (2 * 86_400 + 4 * 3600 + 3599) * 1000L))
    }

    @Test fun aFinishedCountdownNeverWakes() {
        assertNull(Countdown.nextChange(at, at))
        assertNull(Countdown.nextChange(at, at + 1L))
        assertEquals(1L, Countdown.nextChange(at, at - 1L))
    }

    @Test fun severalCountdownsWakeForTheSoonestAndAtLeastOnceAMinute() {
        val now = at - 3_600_000L
        // A weekly reset days away and a 5-hour one seconds away: the 5-hour one's next second.
        assertEquals(500L, Countdown.nextChange(now, listOf(now + 3 * 86_400_000L, now + 8_500L)))
        // Nothing to count (no resets, or only past ones): a look a minute.
        assertEquals(60_000L, Countdown.nextChange(now, listOf(null, now - 10L)))
        assertEquals(60_000L, Countdown.nextChange(now, emptyList()))
    }
}
