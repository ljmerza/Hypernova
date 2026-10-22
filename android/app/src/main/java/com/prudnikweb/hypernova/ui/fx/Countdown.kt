package com.prudnikweb.hypernova.ui.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/**
 * Every countdown in the app counts the same way: the sky's "REFILLS IN 5:59", the account card's
 * "resets in 5:59", the meters' "resets in 2h 11m". The owner saw the sky say 5:59 while the card
 * under it said 6:06: the card only looked at the clock every 20 seconds. Now each one rounds the
 * same way ([seconds]) and looks again exactly when its text would change ([nextChange]), so two
 * countdowns to the same moment always read the same. Pure, unit-tested.
 */
object Countdown {
    /**
     * Whole seconds left until [at], rounded up: it reads 0:01 until the moment itself and 0:00 only
     * from then on, so nothing says "0:00" while there's still time left.
     */
    fun seconds(at: Long, now: Long): Long = if (at <= now) 0L else (at - now + 999L) / 1000L

    /**
     * The step [formatDuration] counts [s] seconds in: seconds under ten minutes ("9:59"), minutes
     * under a day ("12m", "4h 12m"), hours after that ("2d 4h").
     */
    private fun step(s: Long): Long = when {
        s < 600 -> 1L
        s < 86_400 -> 60L
        else -> 3_600L
    }

    /**
     * Milliseconds from [now] until a countdown to [at] next reads differently, or null once it
     * reads 0:00 for good. Waking then (and not every 20 or 30 seconds) is what keeps a card in step
     * with the sky, and costs a minute-long countdown one wake a minute.
     */
    fun nextChange(at: Long, now: Long): Long? {
        val s = seconds(at, now)
        if (s <= 0L) return null
        // The text holds while the seconds left stay at or above the start of their step; it
        // changes the moment they fall below it, which is when the time left reaches (start − 1) s.
        val start = s / step(s) * step(s)
        return (at - now) - (start - 1L) * 1000L
    }

    /** The soonest change among several countdowns (nulls and past ones ignored), capped at [most] ms. */
    fun nextChange(now: Long, at: List<Long?>, most: Long = 60_000L): Long {
        var soonest = most
        for (a in at) {
            val n = a?.let { nextChange(it, now) } ?: continue
            if (n < soonest) soonest = n
        }
        return soonest.coerceAtLeast(1L)
    }
}

/**
 * The wall clock for a screen that shows countdowns to [at]: it moves exactly when one of them
 * would read differently (see [Countdown]), and at least once a minute.
 */
@Composable
fun rememberCountdownNow(vararg at: Long?): Long {
    val ends = at.toList()
    val now by produceState(System.currentTimeMillis(), ends) {
        while (true) {
            value = System.currentTimeMillis()
            // A few ms past the change, so the new text is already true when it's drawn.
            delay(Countdown.nextChange(value, ends) + 5L)
        }
    }
    return now
}
