package com.prudnikweb.hypernova.data

/**
 * Where a usage window is heading (owner, 2026-10-18: spending drawn, not "written as text and
 * separated with bullets"): what's used, where the current pace leads by the window's reset, and
 * which one short tag the meter shows. Pure, unit-tested; drawn by ui/components/ProjectionMeter.kt
 * and the sun's ring (ui/fx/Solar.kt).
 *
 * - [used]: 0..1 of the window spent.
 * - [projected]: 0..1 where the pace leads by the reset (projected = used + pace × minutes to the
 *   reset, at most 1), only while something works and spends; null at rest (no faint fill).
 * - [tag]: the time to the limit when the pace reaches it before the reset (red, with a notch at the
 *   bar's end), otherwise the reset countdown (dim, with a reset glyph).
 * - [pace]: percent a minute while something spends; [resetIn]: ms to the reset (-1: unknown).
 */
data class UsageProjection(val used: Float, val projected: Float?, val tag: Tag, val pace: Float?, val resetIn: Long = -1L) {
    sealed interface Tag {
        /** At this pace the limit comes in [ms], before the reset. */
        data class Limit(val ms: Long) : Tag
        /** The window resets in [ms] (0 when the reset is due). */
        data class Reset(val ms: Long) : Tag
        /** The window's end isn't known (a per-model week the Mac hasn't dated). */
        data object None : Tag
    }

    /** The limit comes first: the faint fill runs to the end and the end gets the red notch. */
    val limitFirst: Boolean get() = tag is Tag.Limit

    /** The window is spent: the meter is full and red until its reset. */
    val full: Boolean get() = used >= 0.999f

    companion object {
        /** Under this (%/min) the window stands still: no pace (the daemon's own threshold). */
        const val IDLE_PER_MIN = 0.005f

        /**
         * The projection of a window [used] (0..1) that resets at [resetAt] (0: unknown), spent at
         * [perMin] percent a minute (null while nothing works). [etaMs], the Mac's own time to the
         * limit (it sends one only when the limit comes before the reset), is preferred when given,
         * so the phone reads what the Mac reads. [limited]: the limit has been hit.
         */
        fun of(used: Float, resetAt: Long, now: Long, perMin: Float?, etaMs: Long? = null, limited: Boolean = false): UsageProjection {
            val u = if (limited) 1f else used.coerceIn(0f, 1f)
            val resetIn = if (resetAt > 0L) (resetAt - now).coerceAtLeast(0L) else -1L
            val reset: Tag = if (resetIn >= 0L) Tag.Reset(resetIn) else Tag.None
            val pace = perMin?.takeIf { !limited && u < 1f && it >= IDLE_PER_MIN && it.isFinite() }
            if (pace == null || resetIn < 0L) return UsageProjection(u, null, reset, null, resetIn)
            val minutes = resetIn / 60_000f
            val lead = u + pace * minutes / 100f
            // The minutes until the window is full at this pace, if that's before the reset.
            val toLimitMs = ((1f - u) * 100f / pace * 60_000f).toLong()
            val first = etaMs?.takeIf { it in 1 until resetIn } ?: toLimitMs.takeIf { lead >= 1f && it < resetIn }
            return if (first != null) UsageProjection(u, 1f, Tag.Limit(first.coerceAtLeast(60_000L)), pace, resetIn)
            else UsageProjection(u, lead.coerceIn(u, 1f), reset, pace, resetIn)
        }

        /** The 5-hour window of [usage] at [now]. */
        fun fiveHour(usage: Usage, now: Long): UsageProjection =
            of(usage.fiveHour, usage.fiveHourResetAt, now, usage.rate?.fiveHour, usage.rate?.fiveHourEtaMs, usage.limited)

        /** The weekly window of [usage] at [now]. */
        fun sevenDay(usage: Usage, now: Long): UsageProjection =
            of(usage.sevenDay, usage.sevenDayResetAt, now, usage.rate?.sevenDay, usage.rate?.sevenDayEtaMs, usage.weekLimited)
    }
}
