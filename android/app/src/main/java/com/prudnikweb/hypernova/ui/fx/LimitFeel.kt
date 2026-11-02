package com.prudnikweb.hypernova.ui.fx

import com.prudnikweb.hypernova.data.Usage
import kotlin.math.abs

/**
 * When the phone thuds for a 5-hour limit: only for one hit as you watch. It came with the sky, which
 * drew the limit (docs/DECISIONS.md, "The sky is gone"); the owner's phone still felt it after the sky
 * was hidden, so it stayed when the sky went. Pure, unit-tested. Keep one per app, in the ViewModel.
 *
 * - A limit found later (a reading over [FRESH_MS] old) isn't felt. Nor under Reduce Motion.
 * - A lifted limit only counts at its reset time or after holding for [HOLD_MS], so a glitch in the
 *   readings doesn't end it. A stale reading of the window that just reset isn't a limit at all.
 * - The same limit coming back (its start time, or its reset still ahead and within [SAME_WINDOW_MS]
 *   of the last one) is never felt twice.
 * - The week's limit outranks the window's: nothing is felt while it holds.
 */
internal class LimitFeel {
    private var limited = false
    private var lastLimitAt = -1L
    private var limitReset = 0L
    private var lastReset = 0L
    private var lift = -1L

    /** A reading of [usage] at [now]: true when this is the moment to feel the limit. */
    fun see(usage: Usage, now: Long, reduced: Boolean): Boolean {
        if (usage.weekLimited) return false
        val at = usage.limitedAt ?: now
        val stale = !limited && lastReset > 0L && now >= lastReset - RESET_SLACK_MS &&
            (at == lastLimitAt || abs(usage.fiveHourResetAt - lastReset) < STALE_WINDOW_MS)
        if (usage.limited && !stale) {
            lift = -1L
            limitReset = usage.fiveHourResetAt
            if (limited) return false
            val same = at == lastLimitAt || (lastReset > now && abs(usage.fiveHourResetAt - lastReset) < SAME_WINDOW_MS)
            limited = true
            lastLimitAt = at
            return now - at < FRESH_MS && !reduced && !same
        }
        if (limited) {
            if (lift < 0) lift = now
            if (now >= limitReset - RESET_SLACK_MS || now - lift >= HOLD_MS) {
                limited = false
                lift = -1L
                lastReset = limitReset
            }
        }
        return false
    }

    private companion object {
        const val FRESH_MS = 3_000L
        const val HOLD_MS = 20_000L
        const val SAME_WINDOW_MS = 120_000L
        const val STALE_WINDOW_MS = 5_000L
        const val RESET_SLACK_MS = 1_500L
    }
}
