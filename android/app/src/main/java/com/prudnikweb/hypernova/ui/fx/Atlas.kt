package com.prudnikweb.hypernova.ui.fx

import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.prudnikweb.hypernova.ui.theme.HN
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Atlas's plate, behind every screen (owner, 2026-11-01; docs/DESIGN.md, "The chart"): an engraved
 * star chart, a conic grid of hairlines round a pole above the screen, field stars, and a Milky Way
 * of stipple with a dotted edge. Pure numbers here, unit-tested; [AtlasChart] draws them.
 */
internal object Atlas {
    /** The field stars left as the week fills: 1 at its start, 0.35 at its end. In steps, so a new reading rarely redraws. */
    fun thin(week: Float): Float {
        val light = 1f - 0.65f * week.coerceIn(0f, 1f).pow(1.5f)
        return (light * THIN_STEPS).roundToInt() / THIN_STEPS.toFloat()
    }

    /** Whether a star of threshold [th] (0..1) shows at [thin]. Some always do: nothing goes dark. */
    fun shows(th: Float, thin: Float): Boolean = th <= thin + 0.1f

    /** How far the grid's pole sits from its centre at [notch], to a whole pixel so its lines don't shimmer. */
    fun poleShift(notch: Int, dp: Float): Float = (TURN_SPAN * dp * sin(notch * 2f * PI.toFloat() / NOTCHES)).roundToInt().toFloat()

    /** The notch for the wall clock [now]: a new one every [NOTCH_MS], looked at when the app comes to the front. */
    fun notch(now: Long): Int = ((now / NOTCH_MS) % NOTCHES).toInt()

    /** The middle of the band at [k] (0..1 across), and its half-width, for a band [h] tall. */
    fun bandCenter(k: Float, h: Float): Float = h * (0.72f + (0.2f - 0.72f) * k) + sin(k * 5.2f + 0.4f) * h * 0.075f
    fun bandWidth(k: Float, h: Float): Float = h * (0.13f + 0.06f * sin(k * 7f + 1f))

    /** The stipple's dots, in [w] × [h] dp, packed in fours (x, y, depth 0..about 1.9, size in dp). The same for the same size. */
    fun stipple(w: Float, h: Float, seed: Int = BAND_SEED): FloatArray {
        val r = Random(seed)
        val n = (w * h / DOT_AREA).toInt()
        val out = FloatArray(n * 4)
        val band = h * BAND_HEIGHT
        for (i in 0 until n) {
            val x = r.nextFloat() * w
            val k = x / w
            val g = (r.nextFloat() + r.nextFloat() + r.nextFloat() + r.nextFloat() - 2f) / 1.05f
            out[i * 4] = x
            out[i * 4 + 1] = bandCenter(k, band) + g * bandWidth(k, band)
            out[i * 4 + 2] = abs(g)
            out[i * 4 + 3] = 0.5f + r.nextFloat() * r.nextFloat() * 0.7f
        }
        return out
    }

    /** The field stars in [w] × [h] dp, packed in fives (x, y, alpha, radius in dp, threshold). As many a screen as the concept's 120 on a 360 × 780 dp phone. */
    fun stars(w: Float, h: Float, seed: Int = STAR_SEED): FloatArray {
        val r = Random(seed)
        val n = (w * h / (360f * 780f) * 120f).toInt().coerceAtLeast(1)
        val out = FloatArray(n * 5)
        for (i in 0 until n) {
            val m = r.nextFloat()
            out[i * 5] = r.nextFloat() * w
            out[i * 5 + 1] = r.nextFloat() * h
            out[i * 5 + 2] = 0.2f + (0.12f + 0.75f * m * m * m) * 0.55f
            out[i * 5 + 3] = 0.3f + (0.35f + 0.65f * m * m) * 0.45f
            out[i * 5 + 4] = r.nextFloat()
        }
        return out
    }

    const val BAND_SEED = 48
    const val STAR_SEED = 12
    private const val THIN_STEPS = 32
    /** dp² of screen per stipple dot. */
    private const val DOT_AREA = 26f
    /** The band's height, of the screen's. */
    const val BAND_HEIGHT = 0.9f
    private const val NOTCHES = 9
    private const val NOTCH_MS = 20 * 60_000L
    /** dp the pole travels either way. */
    private const val TURN_SPAN = 14f
}

/**
 * Draws the plate ([Atlas]). All of it is `Canvas` calls, with no shaders or gradients (minSdk 29, and
 * nothing here can band). It's a lot of dots to draw on one frame and none to keep: [draw] runs only
 * when the size, the week's step or the notch changes, and the layer in [AtlasBackground] keeps the
 * picture between. Its arrays are made once per size.
 */
internal class AtlasChart {
    /** Times [draw] has run, for the test that it's not on every frame. */
    var draws = 0
        private set
    private var w = 0f
    private var h = 0f
    private var dp = 0f
    /** The stipple's points by bucket: [SIZES] × [TONES] lists of x, y, each drawn at once. */
    private var buckets: Array<FloatArray> = emptyArray()
    private var stars = FloatArray(0)
    private val edge = android.graphics.Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint().apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE; isAntiAlias = true }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun ensure(width: Float, height: Float, density: Float) {
        if (width == w && height == h && density == dp) return
        w = width; h = height; dp = density
        val wDp = w / dp
        val hDp = h / dp
        val all = Atlas.stipple(wDp, hDp)
        val lists = Array(SIZES.size * TONES.size) { ArrayList<Float>() }
        for (i in all.indices step 4) {
            val s = SIZES.indices.minBy { abs(SIZES[it] - all[i + 3]) }
            val t = ((all[i + 2] / 1.9f) * TONES.size).toInt().coerceIn(0, TONES.size - 1)
            val list = lists[s * TONES.size + t]
            list.add(all[i] * dp)
            list.add(all[i + 1] * dp)
        }
        buckets = Array(lists.size) { lists[it].toFloatArray() }
        stars = Atlas.stars(wDp, hDp)
        edge.reset()
        val band = h * Atlas.BAND_HEIGHT
        for (side in intArrayOf(-1, 1)) {
            var x = -4f * dp
            edge.moveTo(x, Atlas.bandCenter(x / w, band) + side * Atlas.bandWidth(x / w, band) * 1.25f)
            while (x <= w + 4f * dp) {
                x += 6f * dp
                edge.lineTo(x, Atlas.bandCenter(x / w, band) + side * Atlas.bandWidth(x / w, band) * 1.25f)
            }
        }
    }

    /** The plate, in the window [s] draws: [thin] is [Atlas.thin] of the week, [notch] the grid's turn. */
    fun draw(s: DrawScope, thin: Float, notch: Int) {
        draws++
        ensure(s.size.width, s.size.height, s.density)
        val c = s.drawContext.canvas.nativeCanvas
        val ink = HN.ink.toArgb()
        val stipple = HN.chartLight.toArgb()
        c.drawColor(HN.bg.toArgb())
        // The grid: a hairline a pixel wide at the least, so it doesn't vanish on a low-density phone.
        val hairline = max(1f, 0.4f * dp)
        line.pathEffect = null
        line.strokeWidth = hairline
        line.color = withAlpha(ink, GRID)
        val px = w * 0.3f + Atlas.poleShift(notch, dp)
        val py = -420f * dp
        var r = 360f * dp
        while (r < 340f * dp + h + 200f * dp) {
            c.drawCircle(px, py, r, line)
            r += 44f * dp
        }
        var a = -60f
        while (a <= 60f) {
            val th = (90f + a) * PI.toFloat() / 180f
            val from = 300f * dp
            val to = h + 1400f * dp
            c.drawLine(px + cos(th) * from, py + sin(th) * from, px + cos(th) * to, py + sin(th) * to, line)
            a += 7.5f
        }
        // The band's engraved edge, dotted.
        line.pathEffect = DashPathEffect(floatArrayOf(1f * dp, 2.2f * dp), 0f)
        line.strokeWidth = max(1f, 0.45f * dp)
        line.color = withAlpha(ink, EDGE)
        c.drawPath(edge, line)
        line.pathEffect = null
        // The stipple.
        for (si in SIZES.indices) for (ti in TONES.indices) {
            val pts = buckets[si * TONES.size + ti]
            if (pts.isEmpty()) continue
            dot.strokeWidth = SIZES[si] * dp
            dot.color = withAlpha(stipple, TONES[ti])
            c.drawPoints(pts, dot)
        }
        // The field stars, thinning with the week.
        for (i in stars.indices step 5) {
            if (!Atlas.shows(stars[i + 4], thin)) continue
            fill.color = withAlpha(ink, stars[i + 2])
            c.drawCircle(stars[i] * dp, stars[i + 1] * dp, stars[i + 3] * dp, fill)
        }
    }

    private fun withAlpha(argb: Int, a: Float): Int = (argb and 0xFFFFFF) or ((a * 255f).roundToInt().coerceIn(0, 255) shl 24)

    private companion object {
        /** A square dot's side, dp. */
        val SIZES = floatArrayOf(0.6f, 0.85f, 1.15f)
        /** How bright a dot is, from the band's middle to its edge (the concept's 0.85 × (1 − 0.35 × depth), at its 0.42). */
        val TONES = floatArrayOf(0.34f, 0.28f, 0.22f)
        const val GRID = 0.15f
        const val EDGE = 0.11f
    }
}

/**
 * The plate behind every screen, in place of the starfield. **It reads no clock, so at rest it draws
 * nothing:** one draw when it first shows, then one when the week's step changes (about 21 in a
 * week) or when the app comes back to the front with a new [Atlas.notch]. That notch is the concept's
 * slow turn, taken in jumps nobody watches, so its lines don't sit on the same pixels of an OLED for
 * days and never shimmer as a slow move would. Reduce Motion needs nothing more: nothing moves. Only
 * this feeds the edge bars' blur ([backdrop]), as the far sky did. [strength] steps it back behind a
 * screen of plain text (a chat, onboarding), its dense band otherwise under a line of words; read in
 * the layer, so it redraws nothing.
 */
@Composable
internal fun AtlasBackground(week: Float, modifier: Modifier = Modifier, strength: () -> Float = { 1f }, backdrop: Modifier = Modifier, chart: AtlasChart = remember { AtlasChart() }) {
    val currentWeek = rememberUpdatedState(week)
    val thin by remember { derivedStateOf { Atlas.thin(currentWeek.value) } }
    var notch by remember { mutableIntStateOf(Atlas.notch(System.currentTimeMillis())) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val watch = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_START) notch = Atlas.notch(System.currentTimeMillis()) }
        lifecycle.addObserver(watch)
        onDispose { lifecycle.removeObserver(watch) }
    }
    Spacer(
        modifier
            .fillMaxSize()
            .then(backdrop)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = strength()
            }
            .drawBehind { chart.draw(this, thin, notch) },
    )
}
