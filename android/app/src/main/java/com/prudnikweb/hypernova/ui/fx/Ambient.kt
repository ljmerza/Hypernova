package com.prudnikweb.hypernova.ui.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * One clock for every ambient animation in the app: the cosmos, the grain, the activity meters,
 * pulsing dots and the terminal cursor all read it.
 *
 * Why one clock: on a 120Hz panel, separate infinite transitions each ask for a frame on every
 * vsync. Here every animation lands on the same frame, and the loop sleeps between frames instead
 * of waking on each vsync just to skip it.
 *
 * Why it runs only on demand (the owner's phone ran hot): a reader says, by reading
 * [seconds] while it draws, which [Pace] it needs. The clock ticks only for paces that something
 * drew with since their last frame, at the fastest of their rates ([FrameBudget]), and each pace's
 * readers redraw at their own rate: the far sky at 5 frames a second while a working session's
 * chip breathes at 30. Whatever isn't drawn (a screen that's gone, an item scrolled out of a list,
 * a planet meter at rest that doesn't read the clock) asks for nothing, so a screen where nothing
 * moves draws no frames at all. It stops while the app isn't visible, and freezes under Reduce
 * Motion.
 *
 * The film grain has a lane of its own ([grain]): a gentle 8 to 12 new patterns a second
 * ([FrameBudget.grainFps]) that only move the grain's layer: nothing is drawn again, the sky and
 * the blurred bars included,
 * so it stays alive on a chat or Settings where nothing else moves. Where the sky draws anyway
 * (Home) it changes with the sky's frames instead, as before.
 *
 * Read the clock only inside draw / graphicsLayer lambdas, so ticks redraw without recomposing.
 */
@Stable
class AmbientClock(initial: Float = 0f) {
    /**
     * Seconds of animation at the latest tick. A plain field: reading it subscribes to nothing, the
     * pace's counter does that. Every pace sees the same time, so moving from one pace to another
     * never jumps. It pauses while nothing asks for frames and carries on from there.
     */
    private var time = initial
    private val lanes = Lanes()
    private val ticks = Array(Pace.entries.size) { mutableIntStateOf(0) }
    private val grainTick = mutableIntStateOf(0)
    private val grainSteps = GrainSteps()
    /** The loop, while it waits for a reader (nothing on screen moves). */
    private var parked: CancellableContinuation<Unit>? = null

    /** Seconds for a reader that moves at [Pace.Live]. */
    val seconds: Float get() = seconds(Pace.Live)

    /**
     * The latest tick's time, asking for nothing: for a reader that only knows its pace once it
     * has drawn (the sky), and then calls [seconds] with it. A reader that never does gets no frames.
     */
    val now: Float get() = time

    /**
     * Seconds for a reader that moves at [pace]: it redraws on that pace's frames for as long as it
     * keeps reading. Call it only while drawing.
     */
    fun seconds(pace: Pace): Float {
        ticks[pace.ordinal].intValue
        want(pace.ordinal)
        return time
    }

    private fun want(lane: Int) {
        if (lanes.want(lane)) parked?.let { parked = null; it.resume(Unit) }
    }

    /**
     * The backdrop (the whole screen) is moving at a sky pace this frame, so changing the grain's
     * pattern costs no frame of its own: it changes with these frames, 24 times a second. Without
     * this it changes at its own lane's gentler rate.
     */
    fun backdropMoving() { lanes.wide = true }

    /**
     * The grain's pattern, read while drawing. Reading it asks for the grain's own lane: its next
     * pattern comes in an eighth to a twelfth of a second (none on a hot phone or battery saver),
     * and the tick that brings it moves the grain's layer and redraws nothing.
     */
    val grain: Int get() {
        val g = grainTick.intValue
        want(Lanes.GRAIN)
        return g
    }

    /** Triangle wave 0→1→0 with the given period, for meters and pulses. */
    fun tri(period: Float, phase: Float = 0f, pace: Pace = Pace.Live): Float {
        val x = ((seconds(pace) / period + phase) % 1f + 1f) % 1f
        return 1f - abs(2f * x - 1f)
    }

    /** Smooth 0..1 pulse (cosine) with the given period. */
    fun pulse(period: Float, phase: Float = 0f, pace: Pace = Pace.Live): Float =
        0.5f - 0.5f * cos(2f * PI.toFloat() * (seconds(pace) / period + phase))

    /**
     * Hard on/off blink, like a real terminal cursor. On the calm pace: an edge lands within a
     * fifteenth of a second, which nobody sees on a half-second blink.
     */
    fun blink(period: Float): Boolean = ((seconds(Pace.Calm) / (period / 2f)).toInt() and 1) == 0

    /**
     * Runs the clock until cancelled (the app stops being visible). [fps] is each pace's rate now,
     * [grainFps] the grain's own (0: it holds still).
     */
    internal suspend fun run(fps: (Pace) -> Int, grainFps: Int) {
        lanes.rates = IntArray(Lanes.COUNT) { if (it == Lanes.GRAIN) grainFps else fps(Pace.entries[it]) }
        var origin = -1L
        while (true) {
            val top = lanes.topFps()
            if (top == 0) {
                // Nothing on screen moves: no frames until a reader draws again.
                origin = -1L
                suspendCancellableCoroutine { c ->
                    parked = c
                    c.invokeOnCancellation { if (parked === c) parked = null }
                }
                continue
            }
            val now = withFrameNanos { it }
            // Resume from where we paused, so nothing jumps.
            if (origin < 0) origin = now - (time * 1e9f).toLong()
            time = (now - origin) / 1e9f
            val due = lanes.tick(time)
            for (i in ticks.indices) if (due and (1 shl i) != 0) ticks[i].intValue++
            val grainDue = due and (1 shl Lanes.GRAIN) != 0
            if (grainSteps.step(time, grainDue, lanes.wide, moves = grainFps > 0)) {
                grainTick.intValue = grainSteps.index
            } else if (grainDue) {
                // Its frame came on a frame of the sky's that kept the pattern (they change it 24
                // times a second): the grain didn't redraw, so ask again for it, or it would hold
                // still from here if the sky stopped now.
                lanes.want(Lanes.GRAIN)
            }
            lanes.wide = false
            // Sleep through the vsyncs we'd only skip. Measured from the frame's own timestamp
            // (System.nanoTime base), so main-thread work after the vsync doesn't push the next
            // tick a whole frame late. ~4ms margin to catch the vsync.
            val sleepMs = (now + 1_000_000_000L / top - 4_000_000L - System.nanoTime()) / 1_000_000L
            if (sleepMs > 0) delay(sleepMs)
        }
    }
}

/**
 * When the film grain shows a new pattern (pure, unit-tested). On frames the sky draws anyway (the
 * whole screen is redrawn then, so a new pattern costs nothing) it changes 24 times a second, as
 * the grain was designed. Elsewhere it changes on each frame of its own lane, which runs at
 * [FrameBudget.grainFps].
 */
internal class GrainSteps {
    /** The current pattern; each change moves it on by one. */
    var index = 0
        private set
    private var slot = -1

    /**
     * A tick of the clock at [time] seconds: [laneDue] the grain's own frame is due, [wide] the sky
     * drew this frame. False when the pattern stays (so nothing needs redrawing).
     */
    fun step(time: Float, laneDue: Boolean, wide: Boolean, moves: Boolean): Boolean {
        if (!moves) return false
        if (wide) {
            val s = (time * SKY_RATE).toInt()
            if (s == slot) return false
            slot = s
        } else if (!laneDue) return false
        index++
        return true
    }

    private companion object {
        /** New patterns a second while the sky moves anyway: 0stems' 24. */
        const val SKY_RATE = 24f
    }
}

/**
 * Which paces get a frame, with nothing of Compose in it (unit-tested). A pace is wanted from the
 * moment a reader draws with it until its next frame; its readers then redraw and want it again,
 * or are gone and don't. The tick runs at the fastest wanted pace's rate; a pace whose rate is 0
 * (held still by the heat) never gets a frame, and never keeps the tick going.
 */
internal class Lanes(n: Int = COUNT) {
    companion object {
        /** The grain's lane, after the paces' ([Pace.ordinal]). */
        val GRAIN = Pace.entries.size
        /** The paces' lanes and the grain's. */
        val COUNT = Pace.entries.size + 1
    }

    var rates = IntArray(n)
    private val wanted = BooleanArray(n)
    private val last = FloatArray(n) { -1f }
    /** Set by [AmbientClock.backdropMoving] between ticks. */
    var wide = false

    /** A reader drew with pace [i]. True when the clock was idle, so the loop should wake. */
    fun want(i: Int): Boolean {
        if (wanted[i]) return false
        wanted[i] = true
        return rates[i] > 0 && topFps(except = i) == 0
    }

    /** The tick's rate: the fastest pace someone waits for, or 0 to stop. */
    fun topFps(except: Int = -1): Int {
        var top = 0
        for (i in wanted.indices) if (i != except && wanted[i] && rates[i] > top) top = rates[i]
        return top
    }

    /** A tick at [time] seconds: the paces whose frame is due, as bits (pace i = bit i). */
    fun tick(time: Float): Int {
        val top = topFps()
        if (top == 0) return 0
        var due = 0
        for (i in wanted.indices) {
            val r = rates[i]
            if (!wanted[i] || r <= 0) continue
            // Half a tick of slack, so a 15fps pace on a 30fps tick lands on every other tick
            // instead of drifting to every third.
            if (last[i] < 0f || time - last[i] >= 1f / r - 0.5f / top || time < last[i]) {
                wanted[i] = false
                last[i] = time
                due = due or (1 shl i)
            }
        }
        return due
    }
}

val LocalAmbient = staticCompositionLocalOf { AmbientClock() }

@Composable
fun ProvideAmbientClock(clock: AmbientClock, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock, motion) {
        if (motion.reduced) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            clock.run({ FrameBudget.fps(it, motion.heat, motion.powerSave) }, FrameBudget.grainFps(motion.heat, motion.powerSave))
        }
    }
    CompositionLocalProvider(LocalAmbient provides clock, content = content)
}
