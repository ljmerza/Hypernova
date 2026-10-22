package com.prudnikweb.hypernova.ui.fx

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Depth parallax for the sky: as you turn or tilt the phone, its far layers shift a little
 * against the near ones, as if the screen were a window onto real depth.
 *
 * - The motion comes from the game rotation vector (gyroscope and accelerometer fused, no
 *   compass, so no jumps near metal) at 30Hz, or from the accelerometer's tilt where a phone
 *   has no gyroscope. It's measured against a rest pose that slowly follows the phone (over a
 *   few seconds), so whatever way you hold it is "straight" and only moving it shifts the sky.
 * - [x] and [y] run −1..1 (1 = turned 20° from rest). The drawing eases toward them every frame,
 *   so the sky glides at the frame rate, and hand tremor is smoothed away.
 * - The sensor is on only while the sky is visible and the app is in front; otherwise the
 *   layers ease back to centre.
 */
internal class Parallax {
    var x = 0f
        private set
    var y = 0f
        private set
    private var tx = 0f
    private var ty = 0f
    /**
     * Bumped when the phone moves while the sky was at rest. The sky reads it while drawing, so a
     * tilt redraws it at once even when it's drawing a few frames a second; from then on it glides
     * at the live rate until it has caught up ([gliding]).
     */
    val moved = mutableIntStateOf(0)
    private var resting = true

    /** Still easing toward the phone's tilt by more than a quarter of a dp. */
    val gliding: Boolean get() = abs(tx - x) > SETTLED || abs(ty - y) > SETTLED

    private fun nudge() {
        if (resting && gliding) { resting = false; moved.intValue++ }
    }
    private var gx = Float.NaN
    private var gy = 0f
    private var gz = 0f
    private var restR = Float.NaN
    private var restP = 0f
    private var lastNs = 0L
    private var lastT = -1f
    // Rest pose for the rotation vector, as a quaternion (w, x, y, z).
    private val rest = FloatArray(4)
    private var hasRest = false
    private val q = FloatArray(4)

    /** A game-rotation-vector sample: the phone's rotation relative to its rest pose. */
    fun onRotation(values: FloatArray, ns: Long) {
        val dt = if (lastNs == 0L) 0.033f else ((ns - lastNs) / 1e9f).coerceIn(0f, 0.25f)
        lastNs = ns
        SensorManager.getQuaternionFromVector(q, values)
        if (!hasRest) { q.copyInto(rest); hasRest = true }
        // Shortest way round, then ease the rest pose toward where the phone is now.
        val dot = rest[0] * q[0] + rest[1] * q[1] + rest[2] * q[2] + rest[3] * q[3]
        val sg = if (dot < 0f) -1f else 1f
        val k = 1f - exp(-dt / 2.5f)
        var n = 0f
        for (i in 0 until 4) { rest[i] += (q[i] * sg - rest[i]) * k; n += rest[i] * rest[i] }
        n = sqrt(n)
        for (i in 0 until 4) rest[i] /= n
        // rel = conj(rest) ⊗ q: the turn from the rest pose, in the phone's own axes.
        val aw = rest[0]; val ax = -rest[1]; val ay = -rest[2]; val az = -rest[3]
        val bw = q[0] * sg; val bx = q[1] * sg; val by = q[2] * sg; val bz = q[3] * sg
        var rw = aw * bw - ax * bx - ay * by - az * bz
        var rx = aw * bx + ax * bw + ay * bz - az * by
        var ry = aw * by - ax * bz + ay * bw + az * bx
        if (rw < 0f) { rw = -rw; rx = -rx; ry = -ry }
        // Small angles: twice the vector part. Turning the right edge away (about the phone's
        // vertical axis) moves the far sky left; tipping the top toward you moves it up.
        tx = (-2f * ry / MAX_TILT).coerceIn(-1f, 1f)
        ty = (-2f * rx / MAX_TILT).coerceIn(-1f, 1f)
        nudge()
    }

    fun onSample(ax: Float, ay: Float, az: Float, ns: Long) {
        val dt = if (lastNs == 0L) 0.04f else ((ns - lastNs) / 1e9f).coerceIn(0f, 0.25f)
        lastNs = ns
        if (gx.isNaN()) { gx = ax; gy = ay; gz = az } else {
            val k = 1f - exp(-dt / 0.08f)
            gx += (ax - gx) * k; gy += (ay - gy) * k; gz += (az - gz) * k
        }
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (g < 1f) return // free fall or garbage: keep the last tilt
        val roll = asin((gx / g).coerceIn(-1f, 1f))
        val pitch = atan2(gy, gz)
        if (restR.isNaN()) { restR = roll; restP = pitch }
        val kr = 1f - exp(-dt / 2.5f)
        restR += (roll - restR) * kr
        restP += wrap(pitch - restP) * kr
        // Same directions as the rotation vector. Tipping the top toward you moves gravity from
        // the screen's normal toward its top edge, so the pitch rises; the far sky moves up (−).
        tx = ((roll - restR) / MAX_TILT).coerceIn(-1f, 1f)
        ty = (-wrap(pitch - restP) / MAX_TILT).coerceIn(-1f, 1f)
        nudge()
    }

    /** Called each frame with the ambient clock: glide toward the latest tilt. */
    fun step(t: Float) {
        val dt = if (lastT < 0f) 0f else (t - lastT).coerceIn(0f, 0.25f)
        lastT = t
        val k = 1f - exp(-dt * 7f)
        x += (tx - x) * k
        y += (ty - y) * k
        resting = !gliding
    }

    /** The sensor stopped: forget the rest pose, and let the layers drift back to centre. */
    fun reset() {
        tx = 0f; ty = 0f; gx = Float.NaN; restR = Float.NaN; lastNs = 0L; hasRest = false
        nudge() // glide back to centre
    }

    private fun wrap(a: Float): Float {
        var d = a
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return d
    }

    private companion object {
        const val MAX_TILT = 0.35f // radians, about 20°
        /** A full tilt shifts the far sky ~12dp, so this is about a quarter of a dp. */
        const val SETTLED = 0.02f
    }
}

/**
 * The sky's parallax, listening to the accelerometer only while [visible] says the sky is in
 * view and the app is resumed. Off under Reduce Motion ([enabled] false).
 */
@Composable
internal fun rememberParallax(enabled: Boolean, visible: () -> Boolean): Parallax {
    val parallax = remember { Parallax() }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled, lifecycle) {
        parallax.reset()
        if (!enabled) return@LaunchedEffect
        val sm = context.getSystemService(SensorManager::class.java) ?: return@LaunchedEffect
        val rotation = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val sensor = rotation ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { visible() }.distinctUntilChanged().collectLatest { on ->
                if (!on) { parallax.reset(); return@collectLatest }
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        if (rotation != null) parallax.onRotation(e.values, e.timestamp) else parallax.onSample(e.values[0], e.values[1], e.values[2], e.timestamp)
                    }
                    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, sensor, if (rotation != null) 33_333 else 40_000)
                try { awaitCancellation() } finally { sm.unregisterListener(listener); parallax.reset() }
            }
        }
    }
    return parallax
}
