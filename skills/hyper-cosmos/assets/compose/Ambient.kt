package com.prudnikweb.hypernova.ui.fx

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * One clock for every ambient animation in the app: the cosmos, the grain, the
 * activity bars, pulsing dots and the terminal cursor all read [seconds].
 *
 * Why one clock: on a 120Hz panel, separate infinite transitions each ask for a
 * frame on every vsync. Here the whole app redraws at most [MotionPrefs.cosmosFps]
 * times a second, every animation lands on the same frame, and the loop sleeps
 * between frames instead of waking on each vsync just to skip it. It stops while
 * the app isn't visible, and freezes under Reduce Motion.
 *
 * Read [seconds] only inside draw / graphicsLayer lambdas, so ticks redraw
 * without recomposing.
 */
@Stable
class AmbientClock(initial: Float = 0f) {
    private val state = mutableFloatStateOf(initial)
    val seconds: Float get() = state.floatValue
    internal fun set(v: Float) { state.floatValue = v }

    /** Triangle wave 0→1→0 with the given period, for meters and pulses. */
    fun tri(period: Float, phase: Float = 0f): Float {
        val x = ((seconds / period + phase) % 1f + 1f) % 1f
        return 1f - abs(2f * x - 1f)
    }

    /** Smooth 0..1 pulse (cosine) with the given period. */
    fun pulse(period: Float, phase: Float = 0f): Float =
        0.5f - 0.5f * cos(2f * PI.toFloat() * (seconds / period + phase))

    /** Hard on/off blink, like a real terminal cursor. */
    fun blink(period: Float): Boolean = ((seconds / (period / 2f)).toInt() and 1) == 0
}

val LocalAmbient = staticCompositionLocalOf { AmbientClock() }

@Composable
fun ProvideAmbientClock(clock: AmbientClock, content: @Composable () -> Unit) {
    val motion = LocalMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock, motion) {
        if (motion.reduced) return@LaunchedEffect
        val frameMs = 1000L / motion.cosmosFps
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Resume from where we paused, so nothing jumps after the app comes back.
            var origin = -1L
            while (true) {
                val now = withFrameNanos { it }
                if (origin < 0) origin = now - (clock.seconds * 1e9f).toLong()
                clock.set((now - origin) / 1e9f)
                // Sleep through the vsyncs we'd only skip. Measured from the frame's own
                // timestamp (System.nanoTime base), so main-thread work after the vsync
                // doesn't push the next tick a whole frame late. ~4ms margin to catch the vsync.
                val sleepMs = (now + frameMs * 1_000_000L - 4_000_000L - System.nanoTime()) / 1_000_000L
                if (sleepMs > 0) delay(sleepMs)
            }
        }
    }
    CompositionLocalProvider(LocalAmbient provides clock, content = content)
}
