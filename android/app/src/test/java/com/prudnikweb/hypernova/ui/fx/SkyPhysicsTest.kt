package com.prudnikweb.hypernova.ui.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

class SkyPhysicsTest {
    // ─── Kepler ─────────────────────────────────────────────────────────────

    @Test fun `the reference orbit keeps its period`() {
        assertEquals(6f, Kepler.period(9f, 9f, 6f), 1e-5f)
    }

    @Test fun `T squared goes as a cubed`() {
        // Four times the radius: eight times the period (4^1.5 = 8).
        assertEquals(48f, Kepler.period(36f, 9f, 6f), 1e-3f)
        val t1 = Kepler.period(13f, 9f, 6f)
        val t2 = Kepler.period(17f, 9f, 6f)
        assertEquals((17f / 13f).toDouble().let { it * it * it }, (t2 / t1).toDouble().let { it * it }, 1e-3)
    }

    @Test fun `inner orbits are faster`() {
        assertTrue(Kepler.omega(9f, 9f, 6f) > Kepler.omega(12.6f, 9f, 6f))
        assertTrue(Kepler.omega(12.6f, 9f, 6f) > Kepler.omega(16.2f, 9f, 6f))
        assertEquals((2 * PI / 6).toFloat(), Kepler.omega(9f, 9f, 6f), 1e-5f)
    }

    // ─── The disk ───────────────────────────────────────────────────────────

    @Test fun `a full budget is the whole disk and an empty one its inner edge`() {
        assertEquals(11.5f, DiskModel.hotRadius(2.6f, 11.5f, 1f), 1e-5f)
        assertEquals(2.6f, DiskModel.hotRadius(2.6f, 11.5f, 0f), 1e-5f)
        assertEquals(1f, DiskModel.hotSpan(2.6f, 11.5f, 1f), 1e-5f)
        assertEquals(0f, DiskModel.hotSpan(2.6f, 11.5f, 0f), 1e-5f)
        // Out of range is held to the disk.
        assertEquals(11.5f, DiskModel.hotRadius(2.6f, 11.5f, 1.4f), 1e-5f)
        assertEquals(2.6f, DiskModel.hotRadius(2.6f, 11.5f, -0.2f), 1e-5f)
    }

    @Test fun `what is left sets the hot disk's area, not its width`() {
        val rIn = 2.6f; val rOut = 11.5f
        val half = DiskModel.hotRadius(rIn, rOut, 0.5f)
        // Half the budget: half the disk's area (annulus from the inner edge).
        assertEquals((rOut * rOut - rIn * rIn) / 2f, half * half - rIn * rIn, 1e-3f)
        // So the hot radius is more than halfway out: the last tenth is a thin ring.
        assertTrue(half > (rIn + rOut) / 2f)
        assertEquals(sqrt(rIn * rIn + (rOut * rOut - rIn * rIn) * 0.1f), DiskModel.hotRadius(rIn, rOut, 0.1f), 1e-5f)
    }

    @Test fun `the hot radius grows with the budget`() {
        var last = 0f
        for (i in 0..20) {
            val r = DiskModel.hotRadius(2.6f, 11.5f, i / 20f)
            assertTrue(r > last)
            last = r
        }
    }

    // ─── The donor's clock ──────────────────────────────────────────────────

    @Test fun `the donor sits at the top at the reset and when the reset is unknown`() {
        val top = (-PI / 2).toFloat()
        assertEquals(top, DiskModel.donorAngle(-1L, 5 * 3_600_000L), 1e-5f)
        assertEquals(top, DiskModel.donorAngle(0L, 5 * 3_600_000L), 1e-5f)
        assertEquals(top, DiskModel.donorAngle(5 * 3_600_000L, 5 * 3_600_000L), 1e-5f)
        assertEquals(top, DiskModel.donorAngle(1000L, 0L), 1e-5f)
    }

    @Test fun `the donor goes once round per window, clockwise from the top`() {
        val w = 5 * 3_600_000L
        // A quarter gone: the right-hand side (0 rad); half gone: the bottom (+π/2).
        assertEquals(0f, DiskModel.donorAngle(w * 3 / 4, w), 1e-4f)
        assertEquals((PI / 2).toFloat(), DiskModel.donorAngle(w / 2, w), 1e-4f)
        assertEquals(PI.toFloat(), DiskModel.donorAngle(w / 4, w), 1e-4f)
        // A reading from a longer window than ours is held at the start.
        assertEquals((-PI / 2).toFloat(), DiskModel.donorAngle(2 * w, w), 1e-4f)
    }

    // ─── The flow ───────────────────────────────────────────────────────────

    @Test fun `the flow is one at the even pace, nothing at rest, and capped`() {
        assertEquals(0f, DiskModel.flow(null), 0f)
        assertEquals(0f, DiskModel.flow(0f), 0f)
        assertEquals(0f, DiskModel.flow(Float.NaN), 0f)
        assertEquals(1f, DiskModel.flow(DiskModel.EVEN_PACE), 1e-5f)
        assertEquals(0.36f, DiskModel.flow(0.12f), 1e-3f)
        assertEquals(2.5f, DiskModel.flow(9f), 1e-5f)
        assertTrue(abs(DiskModel.flow(0.35f) - 1.05f) < 1e-3f)
    }

    // ─── The week's low state ───────────────────────────────────────────────

    @Test fun `the week's low state outranks the 5-hour window's quiet disk`() {
        assertEquals(LowState.Sun.Disk, LowState.sun(fiveLimited = false, weekLimited = false))
        assertEquals(LowState.Sun.Quiet, LowState.sun(fiveLimited = true, weekLimited = false))
        assertEquals(LowState.Sun.Low, LowState.sun(fiveLimited = false, weekLimited = true))
        // Both limits hold: the week's state wins.
        assertEquals(LowState.Sun.Low, LowState.sun(fiveLimited = true, weekLimited = true))
    }

    @Test fun `the drain is short when watched, quicker when found later, at once under Reduce Motion`() {
        assertEquals(0f, LowState.drained(0f, watched = true, reduced = false), 0f)
        assertEquals(1f, LowState.drained(LowState.DRAIN, watched = true, reduced = false), 1e-6f)
        assertEquals(1f, LowState.drained(60f, watched = true, reduced = false), 0f)
        // Found later it settles in SETTLE seconds, sooner than a watched drain.
        assertTrue(LowState.SETTLE < LowState.DRAIN)
        assertEquals(1f, LowState.drained(LowState.SETTLE, watched = false, reduced = false), 1e-6f)
        assertTrue(LowState.drained(1f, watched = false, reduced = false) > LowState.drained(1f, watched = true, reduced = false))
        assertEquals(1f, LowState.drained(0f, watched = true, reduced = true), 0f)
        // One short transition: a few seconds, then still.
        assertTrue(LowState.DRAIN <= 5f)
        var last = -1f
        for (i in 0..40) {
            val k = LowState.drained(i * 0.1f, watched = true, reduced = false)
            assertTrue(k >= last)
            last = k
        }
    }

    @Test fun `the stream goes out first, then the hot disk, the cool gas, the white dwarf dims last`() {
        // Before: everything as it was.
        assertEquals(1f, LowState.stream(0f), 0f)
        assertEquals(1f, LowState.hot(0f), 0f)
        assertEquals(1f, LowState.gas(0f), 0f)
        assertEquals(1f, LowState.dwarf(0f), 0f)
        // After: no stream, no disk, the white dwarf a faint point, never out.
        assertEquals(0f, LowState.stream(1f), 0f)
        assertEquals(0f, LowState.hot(1f), 0f)
        assertEquals(0f, LowState.gas(1f), 0f)
        assertEquals(LowState.DWARF, LowState.dwarf(1f), 1e-6f)
        assertTrue(LowState.DWARF > 0f && LowState.DWARF < 0.5f)
        // In order: when each is half gone.
        fun half(f: (Float) -> Float, to: Float = 0f): Float {
            var k = 0f
            while (k < 1f && f(k) > (1f + to) / 2f) k += 0.005f
            return k
        }
        val stream = half(LowState::stream)
        val hot = half(LowState::hot)
        val gas = half(LowState::gas)
        val dwarf = half(LowState::dwarf, LowState.DWARF)
        assertTrue("stream $stream before hot $hot", stream < hot)
        assertTrue("hot $hot before gas $gas", hot < gas)
        assertTrue("gas $gas before the white dwarf $dwarf", gas <= dwarf)
        // Each only ever goes down through the drain.
        for (i in 1..100) {
            val a = (i - 1) / 100f; val b = i / 100f
            assertTrue(LowState.stream(b) <= LowState.stream(a))
            assertTrue(LowState.hot(b) <= LowState.hot(a))
            assertTrue(LowState.gas(b) <= LowState.gas(a))
            assertTrue(LowState.dwarf(b) <= LowState.dwarf(a))
            assertTrue(LowState.dwarf(b) >= LowState.DWARF)
        }
    }

    @Test fun `at the week's reset the stream comes back first and the disk is full of gas by the ignition`() {
        assertEquals(0f, LowState.refilled(0f, reduced = false), 0f)
        assertEquals(1f, LowState.refilled(LowState.REFILL, reduced = false), 1e-6f)
        assertEquals(1f, LowState.refilled(0f, reduced = true), 0f)
        // It starts where the low state left off…
        assertEquals(0f, LowState.refillStream(0f), 0f)
        assertEquals(0f, LowState.refillGas(0f), 0f)
        assertEquals(LowState.DWARF, LowState.refillDwarf(0f), 1e-6f)
        // …and ends where the outburst begins: the stream on, all the gas, the white dwarf at its usual light.
        assertEquals(1f, LowState.refillStream(1f), 0f)
        assertEquals(1f, LowState.refillGas(1f), 0f)
        assertEquals(1f, LowState.refillDwarf(1f), 1e-6f)
        // The stream leads the gas it brings.
        for (i in 1..19) assertTrue(LowState.refillStream(i / 20f) >= LowState.refillGas(i / 20f))
        assertTrue(LowState.refillStream(0.2f) > LowState.refillGas(0.2f))
        // Kept short.
        assertTrue(LowState.REFILL <= 2f)
    }
}
