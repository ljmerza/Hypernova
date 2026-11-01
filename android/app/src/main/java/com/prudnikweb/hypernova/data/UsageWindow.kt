package com.prudnikweb.hypernova.data

/**
 * A usage window as its meter shows it: how much is used and when it resets, both facts. No guess
 * at when it runs out (owner, 2026-11-01: that estimate wasn't accurate; docs/DECISIONS.md). Pure,
 * unit-tested; drawn by ui/components/UsageMeter.kt and the sun's ring (ui/fx/Solar.kt).
 *
 * - [used]: 0..1 of the window spent.
 * - [resetIn]: ms to the reset (0 when the reset is due, -1 when its end isn't known: a per-model
 *   week the Mac hasn't dated).
 */
data class UsageWindow(val used: Float, val resetIn: Long = -1L) {
    /** The window is spent: the meter is full and red until its reset. */
    val full: Boolean get() = used >= 0.999f

    companion object {
        /** A window [used] (0..1) that resets at [resetAt] (0: unknown). [limited]: the limit has been hit. */
        fun of(used: Float, resetAt: Long, now: Long, limited: Boolean = false): UsageWindow = UsageWindow(
            used = if (limited) 1f else used.coerceIn(0f, 1f),
            resetIn = if (resetAt > 0L) (resetAt - now).coerceAtLeast(0L) else -1L,
        )

        /**
         * Claude Code reports a 5-hour window: the rule for every 5H on the phone (owner, 2026-10-31:
         * Max dropped it; shown only when Claude Code reports one, no list of plans). False only when
         * the Mac has the week and no 5-hour window, and no 5-hour limit holds ([hit]); with nothing
         * known yet, as before (true).
         */
        fun fiveHourReported(fiveHour: Window?, sevenDay: Window?, hit: Boolean = false): Boolean =
            hit || fiveHour != null || sevenDay == null

        /**
         * [u] without a 5-hour window ([Usage.fiveHourReported] false): the week in the 5-hour slot
         * too, so the sun's disk (and the widget's star) follow the week, as where Codex leads on a
         * week-only plan (AgentLead.codexSunUsage). Never a 5-hour limit: the week's is the low state.
         */
        fun weekAsDisk(u: Usage): Usage = u.copy(
            fiveHour = u.sevenDay,
            fiveHourResetAt = u.sevenDayResetAt,
            limitedAt = null,
            rate = u.rate?.copy(fiveHour = u.rate.sevenDay),
            fiveHourReported = false,
        )

        /** The 5-hour window of [usage] at [now]. */
        fun fiveHour(usage: Usage, now: Long): UsageWindow =
            of(usage.fiveHour, usage.fiveHourResetAt, now, usage.limited)

        /** The weekly window of [usage] at [now]. */
        fun sevenDay(usage: Usage, now: Long): UsageWindow =
            of(usage.sevenDay, usage.sevenDayResetAt, now, usage.weekLimited)
    }
}
