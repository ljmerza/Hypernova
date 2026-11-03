package com.prudnikweb.hypernova.desktop.ui

import com.prudnikweb.hypernova.ui.theme.HN
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Atlas's plate (docs/DESIGN.md, "The chart"), behind every screen: the app's fx/Atlas.kt, whose numbers
 * are the same and whose drawing is on `android.graphics`, so here on Compose's own canvas calls. It reads
 * no clock: drawn when it first shows or the window changes size, kept in its layer otherwise. It doesn't
 * move and there's no grain over it: both are only there on a phone, for its OLED and its small screen.
 */
internal class AtlasChart {
    private var w = 0f
    private var h = 0f
    private var dp = 0f
    private var buckets: Array<List<Offset>> = emptyArray()
    private var stars = FloatArray(0)
    private var edge = Path()

    private fun ensure(width: Float, height: Float, density: Float) {
        if (width == w && height == h && density == dp) return
        w = width; h = height; dp = density
        val wDp = w / dp
        val hDp = h / dp
        val all = stipple(wDp, hDp)
        val lists = Array(SIZES.size * TONES.size) { ArrayList<Offset>() }
        for (i in all.indices step 4) {
            val s = SIZES.indices.minBy { abs(SIZES[it] - all[i + 3]) }
            val t = ((all[i + 2] / 1.9f) * TONES.size).toInt().coerceIn(0, TONES.size - 1)
            lists[s * TONES.size + t].add(Offset(all[i] * dp, all[i + 1] * dp))
        }
        buckets = Array(lists.size) { lists[it] }
        stars = stars(wDp, hDp)
        val path = Path()
        val band = h * BAND_HEIGHT
        for (side in intArrayOf(-1, 1)) {
            var x = -4f * dp
            path.moveTo(x, bandCenter(x / w, band) + side * bandWidth(x / w, band) * 1.25f)
            while (x <= w + 4f * dp) {
                x += 6f * dp
                path.lineTo(x, bandCenter(x / w, band) + side * bandWidth(x / w, band) * 1.25f)
            }
        }
        edge = path
    }

    fun draw(s: DrawScope, thin: Float) = with(s) {
        ensure(size.width, size.height, density)
        drawRect(HN.bg)
        val hairline = max(1f, 0.4f * dp)
        val grid = HN.ink.copy(alpha = GRID)
        val pole = Offset(w * 0.3f, -420f * dp)
        var r = 360f * dp
        val stroke = Stroke(hairline)
        while (r < 340f * dp + h + 200f * dp + w) {
            drawCircle(grid, r, pole, style = stroke)
            r += 44f * dp
        }
        var a = -60f
        while (a <= 60f) {
            val th = (90f + a) * PI.toFloat() / 180f
            val from = 300f * dp
            val to = h + w + 1400f * dp
            drawLine(grid, pole + Offset(cos(th) * from, sin(th) * from), pole + Offset(cos(th) * to, sin(th) * to), hairline)
            a += 7.5f
        }
        drawPath(
            edge, HN.ink.copy(alpha = EDGE),
            style = Stroke(max(1f, 0.45f * dp), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f * dp, 2.2f * dp))),
        )
        for (si in SIZES.indices) for (ti in TONES.indices) {
            val pts = buckets[si * TONES.size + ti]
            if (pts.isEmpty()) continue
            drawPoints(pts, PointMode.Points, HN.chartLight.copy(alpha = TONES[ti]), SIZES[si] * dp, StrokeCap.Square)
        }
        for (i in stars.indices step 5) {
            if (stars[i + 4] > thin + 0.1f) continue
            drawCircle(HN.ink.copy(alpha = stars[i + 2]), stars[i + 3] * dp, Offset(stars[i] * dp, stars[i + 1] * dp))
        }
    }

    private companion object {
        val SIZES = floatArrayOf(0.6f, 0.85f, 1.15f)
        val TONES = floatArrayOf(0.34f, 0.28f, 0.22f)
        const val GRID = 0.15f
        const val EDGE = 0.11f
        const val BAND_SEED = 48
        const val STAR_SEED = 12
        const val DOT_AREA = 26f
        const val BAND_HEIGHT = 0.9f

        fun bandCenter(k: Float, h: Float): Float = h * (0.72f + (0.2f - 0.72f) * k) + sin(k * 5.2f + 0.4f) * h * 0.075f
        fun bandWidth(k: Float, h: Float): Float = h * (0.13f + 0.06f * sin(k * 7f + 1f))

        fun stipple(w: Float, h: Float): FloatArray {
            val r = Random(BAND_SEED)
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

        fun stars(w: Float, h: Float): FloatArray {
            val r = Random(STAR_SEED)
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
    }
}

/** The stars left as the week fills: 1 at its start, 0.35 at its end, in the app's steps. */
internal fun chartThin(week: Float): Float {
    val light = 1f - 0.65f * week.coerceIn(0f, 1f).pow(1.5f)
    return (light * 32).roundToInt() / 32f
}

/** The plate, stepped back behind a screen of text as a layer's alpha ([strength]). */
@Composable
internal fun AtlasBackground(week: Float, modifier: Modifier = Modifier, strength: Float = 1f) {
    val chart = remember { AtlasChart() }
    val thin = chartThin(week)
    Spacer(
        modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = strength
            }
            .drawBehind { chart.draw(this, thin) },
    )
}
