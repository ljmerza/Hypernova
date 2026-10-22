package com.prudnikweb.hypernova.ui.stats

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.StatDay
import com.prudnikweb.hypernova.ui.fx.LocalAmbient
import com.prudnikweb.hypernova.ui.fx.Pace
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val TAU = (2 * PI).toFloat()
/** The disc seen from above at a slant: its height over its width. */
private const val TILT = 0.5f
/** The galaxy's long axis, turned a little off the horizontal (radians). */
private const val POSITION_ANGLE = -0.3f
/** Where the arms start (in units of the disc's radius) and how tightly they wind. */
private const val ARM_START = 0.12f
private const val PITCH_TAN = 0.42f
/** One slow turn: enough to feel alive, never enough to watch (a degree in 5 s). */
private const val TURN_SECONDS = 1800f
/** The disc's glow and dust are painted once, at this fraction of the screen's resolution: they're soft. */
private const val ART_SCALE = 0.5f
/** A day that's not in the chosen range stays, faintly and smaller. */
private const val OUT_OF_RANGE = 0.3f

/** The arm's angle at radius [r] (in disc radii): a logarithmic spiral. */
private fun spiral(r: Float): Float = ln(r / ARM_START) / PITCH_TAN

/**
 * The last 30 days as a spiral galaxy: each day is a star on one of two arms, the oldest near the
 * core and today at the tip of an arm, where a galaxy grows. A star's size is how long Claude
 * worked that day, its light (dim mint → mint → white-hot) how many tokens it used. Days outside
 * the chosen range ([lit]: how many of the latest days are in it) stay, small and faint. Tap a
 * star to read its day ([selected], [onSelect]).
 *
 * The disc turns once in half an hour on the shared ambient clock, from where it stood when it
 * appeared (so today's star starts near the top), and its stars scintillate a little; under
 * Reduce Motion it's one still frame. The arms, dust and bulge are painted once into a bitmap
 * that each frame only turns into place; the stars' glows are cached gradients, so a frame
 * allocates nothing.
 */
@Composable
fun Galaxy(
    days: List<StatDay>,
    lit: Int,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalMotion.current.reduced
    val clock = LocalAmbient.current
    // Switching ranges lights days in or out, newest first, as one sweep along the arms.
    val litAnim by animateFloatAsState(lit.toFloat(), if (reduced) snap() else tween(Motion.SECTION, easing = Motion.Expo), label = "lit")
    // The first numbers bring the stars up out of the dark (once: a refresh only moves what changed).
    val ignite = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { ignite.animateTo(1f, tween(1100, easing = Motion.Expo)) }
    val art = remember { GalaxyArt() }
    DisposableEffect(art) { onDispose { art.release() } }
    val model = remember(days) { GalaxyModel(days) }
    val measurer = rememberTextMeasurer()
    val todayLabel = stringResource(R.string.stats_today_tick).uppercase()
    val todayLayout = remember(measurer, todayLabel) { measurer.measure(todayLabel, Type.label.copy(color = HN.inkMuted)) }
    val select = rememberUpdatedState(onSelect)
    val shown = remember { floatArrayOf(Float.NaN) }
    val prev = stringResource(R.string.a11y_stats_prev_day)
    val next = stringResource(R.string.a11y_stats_next_day)
    Canvas(
        modifier
            .semantics {
                contentDescription = description
                val i = days.indexOfFirst { it.day == selected }
                customActions = listOfNotNull(
                    days.getOrNull(i - 1)?.let { d -> CustomAccessibilityAction(prev) { onSelect(d.day); true } },
                    days.getOrNull(i + 1)?.let { d -> CustomAccessibilityAction(next) { onSelect(d.day); true } },
                )
            }
            .pointerInput(model) {
                detectTapGestures { p -> model.hit(p, 24.dp.toPx())?.let { select.value(days[it].day) } }
            },
    ) {
        // Seconds since the galaxy appeared: read here, in the draw, so the clock never recomposes.
        // On the slow pace: it turns once in half an hour, a few frames a second is plenty.
        if (shown[0].isNaN()) shown[0] = clock.seconds(Pace.Slow)
        val t = if (reduced) 0f else clock.seconds(Pace.Slow) - shown[0]
        model.draw(this, art, t, litAnim, ignite.value, days.indexOfFirst { it.day == selected }, todayLayout)
    }
}

/**
 * The disc itself, without the days: a halo, two arms of glowing gas, dust and a bright bulge,
 * seen face on and painted once per size into a bitmap.
 */
private class GalaxyArt {
    private var bitmap: Bitmap? = null
    private var builtFor = -1
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun release() {
        bitmap?.recycle()
        bitmap = null
        builtFor = -1
    }

    /** The art for a disc of radius [big] px on screen; null while there's no room. */
    fun forRadius(big: Float, dp: Float): Bitmap? {
        val key = big.toInt()
        if (key <= 0) return null
        if (key == builtFor) bitmap?.let { return it }
        release()
        val rs = big * ART_SCALE
        val d = ceil(rs * 2.3f).toInt()
        val bmp = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val o = d / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val half = PI.toFloat()
        // A point on an arm at radius [r] (disc radii), pushed [across] disc radii off its line,
        // toward the outside of the curve when positive.
        val pt = FloatArray(2)
        fun onArm(r: Float, arm: Int, across: Float) {
            val a = spiral(r) + arm * half
            val a2 = spiral(r + 0.01f) + arm * half
            val tx = (r + 0.01f) * cos(a2) - r * cos(a)
            val ty = (r + 0.01f) * sin(a2) - r * sin(a)
            val tl = hypot(tx, ty).coerceAtLeast(1e-6f)
            // The normal that points away from the core.
            pt[0] = o + (r * cos(a) + ty / tl * across) * rs
            pt[1] = o + (r * sin(a) - tx / tl * across) * rs
        }
        fun armPath(r0: Float, r1: Float, arm: Int, across: Float): Path {
            val path = Path()
            for (k in 0..10) {
                onArm(r0 + (r1 - r0) * k / 10f, arm, across)
                if (k == 0) path.moveTo(pt[0], pt[1]) else path.lineTo(pt[0], pt[1])
            }
            return path
        }
        // How wide an arm is at radius [r] (disc radii): narrow near the core, spreading outward.
        fun width(r: Float) = 0.035f + 0.075f * r

        // The disc's faint body, between the arms.
        p.shader = RadialGradient(o, o, rs * 1.15f, intArrayOf(HN.star.copy(alpha = 0.09f).toArgb(), HN.star.copy(alpha = 0.035f).toArgb(), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        cv.drawCircle(o, o, rs * 1.15f, p)
        p.shader = null

        // Two arms of glowing gas: soft blurred strokes along the spiral in short bands (a band can
        // only have one width), widening outward and fading at the core and the rim. A wide faint
        // pass, then a brighter core of the arm.
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val bands = 14
        for (pass in 0 until 2) {
            for (arm in 0 until 2) {
                for (b in 0 until bands) {
                    val r0 = 0.07f + 1.03f * b / bands
                    val r1 = 0.07f + 1.03f * (b + 1) / bands
                    val mid = (r0 + r1) / 2f
                    p.strokeWidth = rs * width(mid) * (if (pass == 0) 2.4f else 0.9f)
                    p.maskFilter = BlurMaskFilter(p.strokeWidth * 0.5f, BlurMaskFilter.Blur.NORMAL)
                    val fade = (1f - mid.pow(1.8f).coerceIn(0f, 1f)) * (0.45f + 0.55f * (mid / 0.22f).coerceAtMost(1f))
                    p.color = (if (pass == 0) HN.star.copy(alpha = 0.1f * fade) else HN.accentBright.copy(alpha = 0.12f * fade)).toArgb()
                    cv.drawPath(armPath(r0, r1, arm, 0f), p)
                }
            }
        }

        // Dust lanes along the inside of each arm, as a real spiral's dark seams.
        for (arm in 0 until 2) {
            for (b in 2 until bands) {
                val r0 = 0.07f + 1.03f * b / bands
                val r1 = 0.07f + 1.03f * (b + 1) / bands
                val mid = (r0 + r1) / 2f
                p.strokeWidth = rs * width(mid) * 0.45f
                p.maskFilter = BlurMaskFilter(p.strokeWidth * 0.6f, BlurMaskFilter.Blur.NORMAL)
                p.color = HN.bg.copy(alpha = 0.38f * (1f - mid.coerceAtMost(1f) * 0.6f)).toArgb()
                cv.drawPath(armPath(r0, r1, arm, -width(mid) * 0.7f), p)
            }
        }
        p.maskFilter = null
        p.style = Paint.Style.FILL

        // Stars across the arms, thickest along their line, and a sprinkle over the disc. Seeded:
        // always this galaxy.
        val rnd = Random(30_011)
        val dot = dp * ART_SCALE
        repeat(1500) { k ->
            val onArmStar = k < 1200
            val x: Float
            val y: Float
            val r: Float
            if (onArmStar) {
                // More of them inward, as the disc's light falls off.
                r = 0.08f + 1.02f * rnd.nextFloat().let { it * (0.45f + 0.55f * it) }
                onArm(r, if (rnd.nextBoolean()) 0 else 1, gauss(rnd) * width(r) * 1.3f)
                x = pt[0]
                y = pt[1]
            } else {
                r = 1.05f * sqrt(rnd.nextFloat())
                val a = rnd.nextFloat() * TAU
                x = o + r * cos(a) * rs
                y = o + r * sin(a) * rs
            }
            val warm = rnd.nextFloat() < 0.28f
            val bright = onArmStar && rnd.nextFloat() < 0.07f
            val alpha = (if (onArmStar) 0.12f + 0.42f * rnd.nextFloat() else 0.06f + 0.16f * rnd.nextFloat()) * (1f - 0.45f * r.coerceAtMost(1f))
            p.color = (if (warm) HN.ink else if (bright) HN.accentBright else HN.star).copy(alpha = if (bright) (alpha * 1.5f).coerceAtMost(0.85f) else alpha).toArgb()
            cv.drawCircle(x, y, dot * (if (bright) 0.95f else 0.35f + 0.35f * rnd.nextFloat()), p)
        }

        // Knots of young stars on the arms' outer edges: small soft clumps.
        p.maskFilter = BlurMaskFilter(dot * 2.2f, BlurMaskFilter.Blur.NORMAL)
        repeat(36) {
            val r = 0.22f + 0.85f * rnd.nextFloat()
            onArm(r, if (rnd.nextBoolean()) 0 else 1, width(r) * (0.2f + 0.6f * rnd.nextFloat()))
            p.color = HN.accentBright.copy(alpha = 0.18f + 0.18f * rnd.nextFloat()).toArgb()
            cv.drawCircle(pt[0], pt[1], dot * (1.6f + 1.6f * rnd.nextFloat()), p)
        }
        p.maskFilter = null

        // The bulge: a soft warm-white heart with a mint halo, the brightest thing in the disc. (A
        // shader is drawn at the paint's own alpha, which the last knot left low.)
        p.color = android.graphics.Color.WHITE
        p.shader = RadialGradient(
            o, o, rs * 0.46f,
            intArrayOf(HN.ink.toArgb(), HN.ink.copy(alpha = 0.85f).toArgb(), HN.accentBright.copy(alpha = 0.5f).toArgb(), HN.star.copy(alpha = 0.2f).toArgb(), HN.star.copy(alpha = 0.06f).toArgb(), 0),
            floatArrayOf(0f, 0.05f, 0.16f, 0.36f, 0.66f, 1f), Shader.TileMode.CLAMP,
        )
        cv.drawCircle(o, o, rs * 0.46f, p)
        p.shader = null

        bitmap = bmp
        builtFor = key
        return bmp
    }

    private fun gauss(r: Random): Float = (r.nextFloat() + r.nextFloat() + r.nextFloat() - 1.5f) / 1.5f
}

/** The days as stars: what doesn't change from frame to frame, and the frame's own scratch. */
private class GalaxyModel(val days: List<StatDay>) {
    private val n = days.size
    // Each day: its radius in the disc and its angle there, its size (dp) and heat (0..1), its twinkle.
    private val sr = FloatArray(n)
    private val sa = FloatArray(n)
    private val sizeDp = FloatArray(n)
    private val heat = FloatArray(n)
    private val twinkle = FloatArray(n)
    /** The biggest days get diffraction spikes, as bright stars do in a telescope. */
    private val spiky = BooleanArray(n)
    /** Where each star was last drawn (for taps), and its radius in px. */
    private val pos = FloatArray(n * 2)
    private val rad = FloatArray(n)
    /** Where "TODAY" goes this frame. */
    private val label = FloatArray(2)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    /** Glows by heat, in 1/16 steps, as unit-radius gradients moved into place with [matrix]. */
    private val glows = arrayOfNulls<RadialGradient>(17)
    private var backdrop: RadialGradient? = null

    init {
        val maxMs = days.maxOfOrNull { it.claudeMs }?.takeIf { it > 0 } ?: 1L
        val maxTok = days.maxOfOrNull { it.tokens }?.takeIf { it > 0 } ?: 1L
        for (i in 0 until n) {
            val d = days[i]
            val rnd = Random(d.day.toEpochDay())
            // Oldest near the core, today at an arm's tip; days alternate between the two arms, a
            // little off the arm's line, as stars are.
            val u = 0.16f + 0.82f * (if (n > 1) i.toFloat() / (n - 1) else 1f)
            val r = ARM_START + (1f - ARM_START) * u
            val arm = if ((n - 1 - i) % 2 == 0) 0f else PI.toFloat()
            sr[i] = r + 0.03f * (rnd.nextFloat() * 2f - 1f) * (if (i == n - 1) 0f else 1f)
            sa[i] = spiral(r) + arm + 0.05f * (rnd.nextFloat() * 2f - 1f)
            sizeDp[i] = if (d.claudeMs <= 0) 1f else 1.7f + 5.2f * sqrt(d.claudeMs.toFloat() / maxMs)
            heat[i] = if (d.tokens <= 0) 0f else (ln1p(d.tokens.toFloat() / maxTok * 50f) / ln(51f)).coerceIn(0f, 1f)
            twinkle[i] = rnd.nextFloat() * TAU
        }
        val bySize = days.indices.filter { days[it].claudeMs > 0 }.sortedByDescending { days[it].claudeMs }
        for (i in bySize.take(3)) if (sizeDp[i] >= 4.5f) spiky[i] = true
    }

    /** The index of the star under [p], or null; taps reach a little past small stars. */
    fun hit(p: Offset, reach: Float): Int? {
        var best: Int? = null
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            val d = hypot(p.x - pos[i * 2], p.y - pos[i * 2 + 1])
            if (d < max(reach, rad[i] * 2.2f) && d < bestD) {
                best = i
                bestD = d
            }
        }
        return best
    }

    private fun glow(step: Int): RadialGradient = glows[step] ?: run {
        val c = heatColor(step / 16f)
        RadialGradient(
            0f, 0f, 1f,
            intArrayOf(c.toArgb(), c.copy(alpha = 0.85f).toArgb(), c.copy(alpha = 0.26f).toArgb(), c.copy(alpha = 0.06f).toArgb(), c.copy(alpha = 0f).toArgb()),
            floatArrayOf(0f, 0.1f, 0.28f, 0.56f, 1f), Shader.TileMode.CLAMP,
        ).also { glows[step] = it }
    }

    fun draw(scope: DrawScope, art: GalaxyArt, t: Float, lit: Float, ignite: Float, selected: Int, today: TextLayoutResult) = with(scope) {
        val w = size.width
        val h = size.height
        val dp = density
        val cx = w / 2f
        val cy = h / 2f
        // The disc's radius: as wide as the frame allows, and tall enough once slanted and turned.
        val vertical = sqrt(sin(POSITION_ANGLE) * sin(POSITION_ANGLE) + TILT * TILT * cos(POSITION_ANGLE) * cos(POSITION_ANGLE))
        val big = min(w / 2f - 30 * dp, (h / 2f - 22 * dp) / vertical)
        if (big <= 0f) return@with
        // Against the arms' winding, so they trail as a real spiral's do.
        val spin = -t / TURN_SECONDS * TAU
        val cpa = cos(POSITION_ANGLE)
        val spa = sin(POSITION_ANGLE)

        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            // A soft dark field behind the disc, so the sky's own stars step back from it.
            paint.shader = backdrop ?: RadialGradient(0f, 0f, 1f, intArrayOf(HN.bg.copy(alpha = 0.7f).toArgb(), HN.bg.copy(alpha = 0.45f).toArgb(), 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP).also { backdrop = it }
            val field = big * 1.3f
            matrix.setScale(field, field)
            paint.shader.setLocalMatrix(matrix)
            paint.alpha = 255
            nc.save()
            nc.translate(cx, cy)
            nc.scale(1f, 0.62f)
            nc.drawCircle(0f, 0f, field, paint)
            nc.restore()
            paint.shader = null

            // The disc: turned in its own plane, slanted, then turned on the screen.
            art.forRadius(big, dp)?.let { bmp ->
                matrix.setTranslate(-bmp.width / 2f, -bmp.height / 2f)
                matrix.postRotate(spin * 180f / PI.toFloat())
                matrix.postScale(1f / ART_SCALE, TILT / ART_SCALE)
                matrix.postRotate(POSITION_ANGLE * 180f / PI.toFloat())
                matrix.postTranslate(cx, cy)
                art.paint.alpha = (255 * (0.55f + 0.45f * ignite)).toInt()
                nc.drawBitmap(bmp, matrix, art.paint)
            }

            // The days.
            for (i in 0 until n) {
                val a = sa[i] + spin
                val x = sr[i] * cos(a)
                val y = sr[i] * sin(a) * TILT
                val sx = cx + (x * cpa - y * spa) * big
                val sy = cy + (x * spa + y * cpa) * big
                pos[i * 2] = sx
                pos[i * 2 + 1] = sy
                // In range: full light. The newest [lit] days are; the edge eases along the arm.
                val inRange = (lit - (n - 1 - i)).coerceIn(0f, 1f)
                val light = (OUT_OF_RANGE + (1f - OUT_OF_RANGE) * inRange) * ignite
                val sc = if (t == 0f) 1f else 1f + 0.06f * sin(t * 1.3f + twinkle[i]) * sin(t * 0.37f + twinkle[i] * 2f)
                val rr = sizeDp[i] * dp * (0.6f + 0.4f * inRange) * (0.5f + 0.5f * ignite)
                rad[i] = rr
                if (days[i].claudeMs <= 0) {
                    // A day without work: an ember.
                    paint.color = HN.inkDim.toArgb()
                    paint.alpha = (0.6f * light * 255).toInt()
                    nc.drawCircle(sx, sy, rr, paint)
                    continue
                }
                val hs = heat[i]
                val g = glow((hs * 16f).toInt().coerceIn(0, 16))
                val bloom = rr * (3f + 1.4f * hs) * sc
                matrix.setScale(bloom, bloom)
                matrix.postTranslate(sx, sy)
                g.setLocalMatrix(matrix)
                paint.shader = g
                paint.alpha = (light * 255).toInt()
                nc.drawCircle(sx, sy, bloom, paint)
                paint.shader = null
                if (spiky[i] && inRange > 0f) {
                    // Diffraction spikes, faint, along the frame's axes.
                    paint.color = heatColor(hs).toArgb()
                    paint.alpha = (0.4f * light * inRange * sc * 255).toInt()
                    paint.strokeWidth = 0.8f * dp
                    val len = rr * 3.6f * sc
                    nc.drawLine(sx - len, sy, sx + len, sy, paint)
                    nc.drawLine(sx, sy - len * 0.7f, sx, sy + len * 0.7f, paint)
                }
                // A white-hot heart, hotter on the days that burned the most tokens.
                paint.color = lerp(heatColor(hs), HN.ink, 0.7f).toArgb()
                paint.alpha = (light * (0.45f + 0.55f * hs) * 255).toInt()
                nc.drawCircle(sx, sy, max(0.9f * dp, rr * 0.34f), paint)
            }
        }

        // The selected day: the sky's mint focus frame.
        if (selected in 0 until n) {
            val sx = pos[selected * 2]
            val sy = pos[selected * 2 + 1]
            val half = rad[selected] + 7 * dp
            val arm = 3.5f * dp
            val c = HN.accent.copy(alpha = 0.9f * ignite)
            val sw = 1.1f * dp
            for (k in 0 until 4) {
                val dx = if (k % 2 == 0) -1f else 1f
                val dy = if (k < 2) -1f else 1f
                val x0 = sx + dx * half
                val y0 = sy + dy * half
                drawLine(c, Offset(x0, y0), Offset(x0 - dx * arm, y0), sw)
                drawLine(c, Offset(x0, y0), Offset(x0, y0 - dy * arm), sw)
            }
        }

        // "TODAY" beside the newest star, on the side away from the core, like a star chart's name.
        if (n > 0) {
            val i = n - 1
            val sx = pos[i * 2]
            val sy = pos[i * 2 + 1]
            val vx = sx - cx
            val vy = sy - cy
            val len = hypot(vx, vy).coerceAtLeast(1f)
            val ux = vx / len
            val uy = vy / len
            val gap = rad[i] + 10 * dp + (if (selected == i) 4 * dp else 0f)
            val lw = today.size.width.toFloat()
            val lh = today.size.height.toFloat()
            // Alignment blends with the direction, so the label glides round the star as it turns.
            fun place(dx: Float, dy: Float) {
                label[0] = sx + dx * gap - lw * (0.5f - 0.5f * (dx * 1.4f).coerceIn(-1f, 1f))
                label[1] = sy + dy * gap - lh * (0.5f - 0.5f * (dy * 1.4f).coerceIn(-1f, 1f))
            }
            place(ux, uy)
            // Near a side the label would be pushed onto its star: it bends smoothly toward above
            // the star (below, when there's no room above), as the sky's labels do.
            val over = max(max(-label[0], label[0] + lw - w), 0f)
            val k = (over / (lw * 0.5f)).coerceIn(0f, 1f)
            if (k > 0f) {
                val vy = if (sy - gap - lh >= 0f) -1f else 1f
                val dx = ux * (1f - k)
                val dy = uy * (1f - k) + vy * k
                val dl = hypot(dx, dy).coerceAtLeast(1e-3f)
                place(dx / dl, dy / dl)
            }
            drawText(today, topLeft = Offset(label[0].coerceIn(0f, w - lw), label[1].coerceIn(0f, h - lh)), alpha = 0.9f * ignite)
        }
    }
}

/** A day's light by its tokens: dim mint, mint, then white-hot. */
internal fun heatColor(h: Float): Color =
    if (h < 0.5f) lerp(HN.accentDim, HN.accent, h * 2f) else lerp(HN.accent, Color(0xFFF2FFF5), (h - 0.5f) * 2f)
