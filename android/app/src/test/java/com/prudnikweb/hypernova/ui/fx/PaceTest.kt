package com.prudnikweb.hypernova.ui.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame budget (ui/fx/Pace.kt) and the ambient clock's scheduling (ui/fx/Ambient.kt): which
 * rate each pace gets by heat, how the heat level follows the phone, and which paces get a frame.
 */
class PaceTest {
    @Test fun aCoolPhoneGetsTheDesignedRates() {
        assertEquals(30, FrameBudget.fps(Pace.Live, Heat.COOL, powerSave = false))
        assertEquals(30, FrameBudget.fps(Pace.Calm, Heat.COOL, powerSave = false))
        assertEquals(5, FrameBudget.fps(Pace.Slow, Heat.COOL, powerSave = false))
        assertTrue(FrameBudget.grainMoves(Heat.COOL, powerSave = false))
        assertTrue(FrameBudget.parallax(Heat.COOL))
        assertTrue(FrameBudget.liveBlur(Heat.COOL))
    }

    @Test fun batterySaverIsAsBeforeTwentyFramesAndStillGrain() {
        assertEquals(20, FrameBudget.fps(Pace.Live, Heat.COOL, powerSave = true))
        assertFalse(FrameBudget.grainMoves(Heat.COOL, powerSave = true))
        // Battery saver doesn't make a hot phone any cooler than it is.
        assertEquals(FrameBudget.fps(Pace.Live, Heat.HOT, false), FrameBudget.fps(Pace.Live, Heat.HOT, true))
    }

    @Test fun ratesStepDownAsThePhoneWarms() {
        for (pace in Pace.entries) {
            var last = Int.MAX_VALUE
            for (heat in Heat.COOL..Heat.CRITICAL) {
                val fps = FrameBudget.fps(pace, heat, powerSave = false)
                assertTrue("$pace at $heat: $fps after $last", fps <= last)
                last = fps
            }
        }
        // Live stays live (never frozen); the far sky holds still from HOT on.
        assertTrue(FrameBudget.fps(Pace.Live, Heat.CRITICAL, false) > 0)
        assertEquals(0, FrameBudget.fps(Pace.Slow, Heat.HOT, false))
        assertTrue(FrameBudget.grainMoves(Heat.WARM, false))
        assertFalse(FrameBudget.grainMoves(Heat.HOT, false))
        assertFalse(FrameBudget.parallax(Heat.HOT))
        assertTrue(FrameBudget.liveBlur(Heat.HOT))
        assertFalse(FrameBudget.liveBlur(Heat.CRITICAL))
    }

    @Test fun thermalStatusMapsToLevels() {
        assertEquals(Heat.COOL, HeatPolicy.level(HeatPolicy.STATUS_NONE, Float.NaN, Heat.COOL))
        assertEquals(Heat.COOL, HeatPolicy.level(HeatPolicy.STATUS_LIGHT, Float.NaN, Heat.COOL))
        assertEquals(Heat.WARM, HeatPolicy.level(HeatPolicy.STATUS_MODERATE, Float.NaN, Heat.COOL))
        assertEquals(Heat.HOT, HeatPolicy.level(HeatPolicy.STATUS_SEVERE, Float.NaN, Heat.COOL))
        assertEquals(Heat.CRITICAL, HeatPolicy.level(4, Float.NaN, Heat.COOL)) // CRITICAL
        assertEquals(Heat.CRITICAL, HeatPolicy.level(6, Float.NaN, Heat.COOL)) // SHUTDOWN
        // The status falling brings the level down with it (the system has its own hysteresis).
        assertEquals(Heat.COOL, HeatPolicy.level(HeatPolicy.STATUS_NONE, -1f, Heat.HOT))
        // A headroom that reads NaN says nothing: the level stays.
        assertEquals(Heat.HOT, HeatPolicy.level(HeatPolicy.STATUS_NONE, Float.NaN, Heat.HOT))
    }

    @Test fun headroomStepsDownEarlyAndBackUpWithAMargin() {
        // Rising: 0.75 warm, 0.9 hot, 1.0 critical.
        assertEquals(Heat.COOL, HeatPolicy.level(0, 0.5f, Heat.COOL))
        assertEquals(Heat.WARM, HeatPolicy.level(0, 0.86f, Heat.COOL))
        assertEquals(Heat.HOT, HeatPolicy.level(0, 0.96f, Heat.WARM))
        assertEquals(Heat.CRITICAL, HeatPolicy.level(0, 1.05f, Heat.HOT))
        // Falling a little under a threshold keeps the level, so it doesn't flap...
        assertEquals(Heat.WARM, HeatPolicy.level(0, 0.80f, Heat.WARM))
        assertEquals(Heat.HOT, HeatPolicy.level(0, 0.90f, Heat.HOT))
        // ...a margin under it lets it go.
        assertEquals(Heat.COOL, HeatPolicy.level(0, 0.6f, Heat.WARM))
        assertEquals(Heat.WARM, HeatPolicy.level(0, 0.8f, Heat.HOT))
        // The status and the headroom: the warmer of the two.
        assertEquals(Heat.WARM, HeatPolicy.level(HeatPolicy.STATUS_MODERATE, 0.3f, Heat.COOL))
        assertEquals(Heat.HOT, HeatPolicy.level(HeatPolicy.STATUS_LIGHT, 0.95f, Heat.WARM))
        // A device that can't forecast: the status alone.
        assertEquals(Heat.WARM, HeatPolicy.level(HeatPolicy.STATUS_MODERATE, -1f, Heat.CRITICAL))
    }

    private fun lanes(heat: Int = Heat.COOL, powerSave: Boolean = false) = Lanes().apply {
        rates = IntArray(Lanes.COUNT) {
            if (it == Lanes.GRAIN) FrameBudget.grainFps(heat, powerSave) else FrameBudget.fps(Pace.entries[it], heat, powerSave)
        }
    }

    private fun bit(p: Pace) = 1 shl p.ordinal

    @Test fun nothingReadingMeansNoFrames() {
        val l = lanes()
        assertEquals(0, l.topFps())
        assertEquals(0, l.tick(1f))
    }

    @Test fun theFirstReaderWakesAnIdleClockAndLaterOnesDont() {
        val l = lanes()
        assertTrue(l.want(Pace.Slow.ordinal))
        // Already ticking for the far sky: a chip that starts breathing doesn't need to wake it.
        assertFalse(l.want(Pace.Live.ordinal))
        assertFalse(l.want(Pace.Live.ordinal))
        assertEquals(30, l.topFps())
    }

    @Test fun eachPaceRedrawsAtItsOwnRateOnTheFastestTick() {
        val l = lanes()
        var live = 0; var calm = 0; var slow = 0
        var t = 0f
        // Two seconds of 30fps ticks, every reader drawing again after each of its frames.
        repeat(60) {
            l.want(Pace.Live.ordinal); l.want(Pace.Calm.ordinal); l.want(Pace.Slow.ordinal)
            t += 1f / 30f
            val due = l.tick(t)
            if (due and bit(Pace.Live) != 0) live++
            if (due and bit(Pace.Calm) != 0) calm++
            if (due and bit(Pace.Slow) != 0) slow++
        }
        // Calm runs the same 30 as Live on a cool phone now (the owner found 15 laggy on Home).
        assertEquals(60, live)
        assertEquals(60, calm)
        assertTrue("slow got $slow frames", slow in 9..11)
    }

    @Test fun aReaderThatStopsDrawingStopsTheClock() {
        val l = lanes()
        l.want(Pace.Calm.ordinal)
        assertEquals(bit(Pace.Calm), l.tick(0.1f))
        // Its frame came and it didn't draw again (its screen is gone): nothing waits any more.
        assertEquals(0, l.topFps())
        assertEquals(0, l.tick(0.2f))
    }

    @Test fun aPaceWaitsForItsFrameWhileAFasterOneTicks() {
        val l = lanes()
        l.want(Pace.Slow.ordinal)
        assertEquals(bit(Pace.Slow), l.tick(0f))
        l.want(Pace.Slow.ordinal)
        l.want(Pace.Live.ordinal)
        // 33 ms later the far sky's next frame isn't due yet, but it still waits for one.
        assertEquals(bit(Pace.Live), l.tick(0.033f))
        assertEquals(5, l.topFps())
        // A fifth of a second on, it gets it.
        assertEquals(bit(Pace.Slow), l.tick(0.2f) and bit(Pace.Slow))
    }

    @Test fun aHeldPaceNeverKeepsTheClockGoing() {
        val l = lanes(Heat.HOT) // the far sky holds still
        assertFalse(l.want(Pace.Slow.ordinal))
        assertEquals(0, l.topFps())
        assertEquals(0, l.tick(1f))
        // A live reader still gets its frames (15 a second when hot), and the held pace none.
        assertTrue(l.want(Pace.Live.ordinal))
        assertEquals(15, l.topFps())
        assertEquals(bit(Pace.Live), l.tick(1.1f))
    }

    // ─── The grain's own lane ────────────────────────────────────────────

    @Test fun theGrainMovesGentlyOnACoolOrWarmPhoneAndHoldsStillWhenHotOrSaving() {
        assertEquals(12, FrameBudget.grainFps(Heat.COOL, powerSave = false))
        assertEquals(8, FrameBudget.grainFps(Heat.WARM, powerSave = false))
        assertEquals(0, FrameBudget.grainFps(Heat.HOT, powerSave = false))
        assertEquals(0, FrameBudget.grainFps(Heat.CRITICAL, powerSave = false))
        assertEquals(0, FrameBudget.grainFps(Heat.COOL, powerSave = true))
        // Always gentler than the sky's frames, so it never sets the pace of a moving screen.
        for (heat in Heat.COOL..Heat.CRITICAL) {
            assertTrue(FrameBudget.grainFps(heat, false) <= FrameBudget.fps(Pace.Calm, heat, false))
        }
    }

    @Test fun onAStillScreenTheGrainAloneTicksAtItsOwnRateAndWakesNothingElse() {
        val l = lanes()
        // A chat at rest: only the grain draws, and it wakes the idle clock.
        assertTrue(l.want(Lanes.GRAIN))
        assertEquals(12, l.topFps())
        val g = GrainSteps()
        var t = 0f
        var patterns = 0
        repeat(24) {
            t += 1f / 12f
            val due = l.tick(t)
            // Its frames are its own: no pace (sky, chips) is due.
            assertEquals(1 shl Lanes.GRAIN, due)
            if (g.step(t, laneDue = true, wide = false, moves = true)) patterns++
            // It drew the new pattern, so it asks for the next.
            l.want(Lanes.GRAIN)
        }
        assertEquals(24, patterns)
        assertEquals(24, g.index)
    }

    @Test fun theGrainLaneTakesTurnsOnAFasterTick() {
        val l = lanes()
        var grain = 0
        var t = 0f
        // A working session's chip at 30 fps beside the grain: every second or third of its frames
        // (12 a second doesn't divide 30), so 10 to 15 new patterns a second.
        repeat(60) {
            l.want(Pace.Live.ordinal); l.want(Lanes.GRAIN)
            t += 1f / 30f
            if (l.tick(t) and (1 shl Lanes.GRAIN) != 0) grain++
        }
        assertTrue("grain got $grain frames in 2 s", grain in 20..30)
    }

    @Test fun aHotPhoneOrBatterySaverGivesTheGrainNoFrames() {
        for (l in listOf(lanes(Heat.HOT), lanes(Heat.COOL, powerSave = true))) {
            assertFalse(l.want(Lanes.GRAIN))
            assertEquals(0, l.topFps())
            assertEquals(0, l.tick(1f))
        }
        val g = GrainSteps()
        assertFalse(g.step(1f, laneDue = true, wide = true, moves = false))
        assertEquals(0, g.index)
    }

    @Test fun whileTheSkyDrawsTheGrainChangesWithItTwentyFourTimesASecond() {
        val g = GrainSteps()
        var changes = 0
        var t = 0f
        // Home: the sky draws 30 frames a second; the grain's own lane is due on some of them.
        repeat(30) { i ->
            t += 1f / 30f
            if (g.step(t, laneDue = i % 3 == 0, wide = true, moves = true)) changes++
        }
        assertTrue("$changes changes in a second", changes in 23..25)
        // The sky stops (another screen): the lane carries on alone.
        assertTrue(g.step(t + 0.1f, laneDue = true, wide = false, moves = true))
        assertFalse(g.step(t + 0.15f, laneDue = false, wide = false, moves = true))
    }
}
