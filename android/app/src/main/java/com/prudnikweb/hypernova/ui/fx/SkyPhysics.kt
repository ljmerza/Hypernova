package com.prudnikweb.hypernova.ui.fx

import kotlin.math.pow
import kotlin.math.sqrt

private const val TAU = 6.2831855f
private const val PI_F = 3.1415927f

/**
 * Kepler's third law for everything that goes round something in the sky: T² ∝ a³, so an orbit
 * [a] takes (a / a0)^1.5 times as long as one at [a0]. The moons' shells, the disk's knots and the
 * donor's orbit all read it. Pure, unit-tested.
 */
object Kepler {
    /** The period (s) of an orbit of radius [a], given that one of radius [a0] takes [t0] s. */
    fun period(a: Float, a0: Float, t0: Float): Float = t0 * (a / a0).pow(1.5f)

    /** Angular speed (rad/s) on an orbit of radius [a], given that one of radius [a0] takes [t0] s. */
    fun omega(a: Float, a0: Float, t0: Float): Float = TAU / period(a, a0, t0)
}

/**
 * The sun is a white dwarf with an accretion disk fed by a companion (docs/DESIGN.md, "The sun is
 * an accretion disk"), and the disk is the 5-hour window: its hot part is the budget left. These
 * are the numbers the drawing reads. Pure, unit-tested.
 */
object DiskModel {
    /**
     * The hot disk's outer radius with [left] (0..1) of the budget left, between the inner edge
     * [rIn] and the rim [rOut]. A disk's mass grows with its area, so what's left sets the area,
     * not the radius: the last tenth of the budget is a thin inner ring, not a tenth of the width.
     */
    fun hotRadius(rIn: Float, rOut: Float, left: Float): Float =
        sqrt(rIn * rIn + (rOut * rOut - rIn * rIn) * left.coerceIn(0f, 1f))

    /** [hotRadius] as a share of the disk's width (0 = the inner edge, 1 = the rim). */
    fun hotSpan(rIn: Float, rOut: Float, left: Float): Float = ((hotRadius(rIn, rOut, left) - rIn) / (rOut - rIn)).coerceIn(0f, 1f)

    /**
     * Where the donor is on its orbit: it goes once round per window, so its place is the window's
     * clock, like the ring's time arc. In screen radians: −π/2 is the top (the reset), increasing
     * clockwise. [resetIn] ms to the reset out of a [window] ms long; unknown (< 0) or due, the top.
     */
    fun donorAngle(resetIn: Long, window: Long): Float {
        if (resetIn < 0L || window <= 0L) return -PI_F / 2f
        val gone = (1f - resetIn.toFloat() / window).coerceIn(0f, 1f)
        // Due: back at the top, where it started (the same place, not a turn further round).
        if (gone >= 0.9999f) return -PI_F / 2f
        return -PI_F / 2f + TAU * gone
    }

    /**
     * The stream from the donor lands on the rim ahead of the donor (in the orbital sense): gas
     * leaving the donor keeps its orbital motion, so it curves forward as it falls and strikes the
     * disk's edge as the bright spot, some way ahead of the line between the stars.
     */
    const val STREAM_LEAD = 0.9f

    /** The spend pace (percent a minute) that would spend the whole window exactly by its reset. */
    const val EVEN_PACE = 100f / 300f

    /**
     * How hard the gas flows for a spend [pace] (percent a minute; null at rest): 1 at the even
     * pace, up to 2.5 when spending much faster, 0 when nothing works. The flow is what moves in
     * the disk (its knots spiral in) and what lights its inner edge.
     */
    fun flow(pace: Float?): Float = if (pace == null || pace <= 0f || !pace.isFinite()) 0f else (pace / EVEN_PACE).coerceIn(0f, 2.5f)
}

/**
 * The week's limit as a real cataclysmic variable has it (owner, 2026-10-18: "Switch to the
 * physically honest one"): a **VY Sculptoris low state**. The donor stops feeding the disk; with
 * no stream the disk drains onto the white dwarf until nothing of it is left, and the white dwarf
 * is a faint point on its own, the donor still on its orbit. Nothing else in the sky changes:
 * no star burns out, nothing ends. At the week's reset the stream comes back, refills the disk,
 * and the disk ignites: the 5-hour reset's outburst. The meter says how long the wait is; the
 * sky only shows that there is one.
 *
 * These are the numbers the drawing reads, as shares (0..1) of what each was before the drain or
 * will be after the refill, for a progress k (0..1) through it. Pure, unit-tested.
 */
object LowState {
    /** Seconds the drain takes when it happens in front of you: one short, calm transition, then still. */
    const val DRAIN = 4f
    /** Seconds a low state found later (the app wasn't watching) takes to settle in: no drain to watch. */
    const val SETTLE = 1.4f
    /** Seconds the returning stream takes to refill the disk at the week's reset, before the disk ignites. */
    const val REFILL = 1.2f
    /** The white dwarf's light in the low state, as a share of its usual: a faint point. */
    const val DWARF = 0.35f

    /** What the sun is: the disk, the 5-hour window's quiet disk, or the week's low state. */
    enum class Sun { Disk, Quiet, Low }

    /** The week's low state outranks the 5-hour window's quiet disk when both limits hold. */
    fun sun(fiveLimited: Boolean, weekLimited: Boolean): Sun = when {
        weekLimited -> Sun.Low
        fiveLimited -> Sun.Quiet
        else -> Sun.Disk
    }

    /**
     * How far through the drain the sky is, [seconds] after the low state began: over [DRAIN] when
     * it was [watched], over [SETTLE] when it was found later, at once under Reduce Motion.
     */
    fun drained(seconds: Float, watched: Boolean, reduced: Boolean): Float =
        if (reduced) 1f else smooth(seconds / (if (watched) DRAIN else SETTLE))

    /** How far the returning stream has refilled the disk, [seconds] after the week's reset. */
    fun refilled(seconds: Float, reduced: Boolean): Float = if (reduced) 1f else smooth(seconds / REFILL)

    // ── Through the drain (k: 0 the low state begins, 1 it has settled) ──

    /** The stream: it goes first (the donor stops feeding the disk), out by a third of the way. */
    fun stream(k: Float): Float = 1f - smooth(k / 0.35f)

    /** The hot disk: it drains onto the white dwarf next. */
    fun hot(k: Float): Float = 1f - smooth(k / 0.55f)

    /** The cool gas: it thins after the hot gas has begun to go, to nothing at the end. */
    fun gas(k: Float): Float = 1f - smooth((k - 0.2f) / 0.8f)

    /** The white dwarf: it dims last, as the disk round it goes out, to [DWARF]. */
    fun dwarf(k: Float): Float = 1f - (1f - DWARF) * smooth((k - 0.5f) / 0.5f)

    // ── Through the refill at the week's reset (k: 0 the stream returns, 1 the disk ignites) ──

    /** The stream comes back first. */
    fun refillStream(k: Float): Float = smooth(k / 0.3f)

    /** The cool gas piles up behind it, all of it by the ignition. */
    fun refillGas(k: Float): Float = smooth((k - 0.1f) / 0.9f)

    /** The white dwarf brightens with the disk's gas round it, to its usual light by the ignition. */
    fun refillDwarf(k: Float): Float = DWARF + (1f - DWARF) * smooth(k)

    private fun smooth(x: Float): Float { val k = x.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }
}
