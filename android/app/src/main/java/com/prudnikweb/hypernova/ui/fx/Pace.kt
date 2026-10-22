package com.prudnikweb.hypernova.ui.fx

import kotlin.math.max

/**
 * How fast something on screen moves, and so how often it needs a new frame. Each reader of the
 * [AmbientClock] says which pace it needs; the frame rate for that pace comes from [FrameBudget],
 * which lowers it on battery saver and as the phone warms.
 *
 * Why paces at all: the owner's phone ran hot with the whole app redrawing 30 times a second on
 * every screen. Most of the sky moves a dp a second or less, and a screen where nothing works
 * needs no frames at all.
 */
enum class Pace {
    /** Something is happening: a session works (its planet turns), a planet arrives, a timeline plays, a voice speaks. */
    Live,
    /** Something signals in place: a needs-you bracket drawing in, a star near its limit, a black hole's turning disk. */
    Calm,
    /**
     * The far sky (stars and nebulae drift about a dp a second) and other things that barely move:
     * the calm sky at rest, where nothing moves but a young sun's slow breath.
     */
    Slow,
}

/** What the phone's temperature lets the app afford (from [HeatPolicy]), mildest first. */
object Heat {
    /** Everything as designed. */
    const val COOL = 0
    /** Lower frame rates and a slower grain. Battery saver gets its frame rates, with a still grain. */
    const val WARM = 1
    /** As warm, and the far sky holds still and the tilt parallax stops. */
    const val HOT = 2
    /** As hot, and the edge bars swap their live blur for a still frosted fill. */
    const val CRITICAL = 3
}

/** Frames per second for each [Pace], and which effects stay on, by heat. Pure, unit-tested. */
object FrameBudget {
    /** Frames per second for [pace]; 0 means it holds still (no frames of its own). */
    fun fps(pace: Pace, heat: Int, powerSave: Boolean): Int {
        // Battery saver gets what a warm phone gets: 20 frames a second for the live sky, as before.
        val level = max(heat, if (powerSave) Heat.WARM else Heat.COOL)
        return when (pace) {
            Pace.Live -> when (level) { Heat.COOL -> 30; Heat.WARM -> 20; Heat.HOT -> 15; else -> 10 }
            // The sky on screen stays smooth: 15 looked laggy to the owner on Home (2026-10-17). The
            // savings come from screens that draw nothing; this steps down only as the phone heats.
            // Never under 10: the planet and the chips look broken below it, hot or not.
            Pace.Calm -> when (level) { Heat.COOL -> 30; Heat.WARM -> 20; Heat.HOT -> 15; else -> 10 }
            Pace.Slow -> when (level) { Heat.COOL -> 5; Heat.WARM -> 4; else -> 0 }
        }
    }

    /**
     * New grain patterns a second on screens where the sky holds still (a chat, Settings), from the
     * grain's own lane ([AmbientClock.grain]); 0 holds it still. The owner wanted it alive
     * everywhere (it froze there after the heat work), and a gentle rate is enough for that: each
     * new pattern only moves the grain's layer and redraws nothing, the sky and the blurred bars
     * included. Still under
     * battery saver and on a hot phone, where every frame counts.
     */
    fun grainFps(heat: Int, powerSave: Boolean): Int = when {
        powerSave -> 0
        heat == Heat.COOL -> 12
        heat == Heat.WARM -> 8
        else -> 0
    }

    /** The film grain changes its pattern at all (its own lane, and the sky's frames); otherwise it's a still texture. */
    fun grainMoves(heat: Int, powerSave: Boolean): Boolean = grainFps(heat, powerSave) > 0

    /** The phone's tilt moves the sky's layers (a gyroscope at 30Hz, and frames to show it). */
    fun parallax(heat: Int): Boolean = heat < Heat.HOT

    /** The edge bars blur what scrolls under them live; past this, a still frosted fill of the same shape. */
    fun liveBlur(heat: Int): Boolean = heat < Heat.CRITICAL
}

/**
 * The heat level from what Android says about the phone's temperature (API 29+): its thermal
 * status, and on API 30+ the forecast headroom, where 1.0 is the point at which the system starts
 * throttling hard (THERMAL_STATUS_SEVERE). The headroom moves before the status does, so the app
 * can step down early; it steps back up only once the headroom has fallen a margin below the
 * threshold, so a phone hovering at one doesn't flap between looks.
 */
object HeatPolicy {
    // android.os.PowerManager.THERMAL_STATUS_*: copied so this stays testable on the JVM.
    const val STATUS_NONE = 0
    const val STATUS_LIGHT = 1
    const val STATUS_MODERATE = 2
    const val STATUS_SEVERE = 3

    /** Headroom at which each level starts (WARM, HOT, CRITICAL). */
    private val UP = floatArrayOf(0.85f, 0.95f, 1.0f)
    /** Headroom it must fall under to leave that level again. */
    private val DOWN = floatArrayOf(0.78f, 0.88f, 0.93f)

    fun level(status: Int, headroom: Float, previous: Int): Int {
        val fromStatus = when {
            // LIGHT is how a phone in normal use reads most of the time (the owner's did, at 47 °C):
            // stepping down there froze the grain and slowed the sky for nothing.
            status >= STATUS_SEVERE + 1 -> Heat.CRITICAL
            status == STATUS_SEVERE -> Heat.HOT
            status == STATUS_MODERATE -> Heat.WARM
            else -> Heat.COOL
        }
        val fromHeadroom = if (headroom.isNaN()) previous else if (headroom < 0f) Heat.COOL else {
            val up = UP.count { headroom >= it }
            val down = DOWN.count { headroom >= it }
            when {
                up > previous -> up
                down < previous -> down
                else -> previous
            }
        }
        return max(fromStatus, fromHeadroom).coerceIn(Heat.COOL, Heat.CRITICAL)
    }
}
