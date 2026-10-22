package com.prudnikweb.hypernova.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.prudnikweb.hypernova.ui.fx.DiskModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The widgets' pictures, drawn once per change into bitmaps (a widget can't run the app's
 * Compose canvas): the sun as the home sky draws it ([star], the accretion disk of `fx/Disk.kt`), and the
 * activity meter's planet ([planet], after `planetSphere()`). Both keep the app's numbers, so
 * the widget's disk at 72% is the same disk the sky shows at 72%. Nothing here moves: a widget
 * is a still photograph, so the disk is drawn as it is at rest.
 */
internal object WidgetArt {
    // Tokens (ui/theme/Tokens.kt), as ints: android.graphics has no Compose colours.
    const val ACCENT = 0xFF7FFFA0.toInt()
    const val ACCENT_BRIGHT = 0xFFB6FFC9.toInt()
    const val AMBER = 0xFFE8B341.toInt()
    const val INK = 0xFFF5F2EB.toInt()
    const val INK_DIM = 0xFF7A7468.toInt()
    const val INK_GHOST = 0xFF45403A.toInt()

    /** What state the sun is in: its life follows the 5-hour window (DESIGN.md, "The sun's life"). */
    enum class Look {
        /** The disk, hot out to where the budget left puts the cooling front. */
        Live,
        /** No official numbers yet: a full disk, a little faint. */
        Unknown,
        /** The 5-hour limit: quiescence, a dim red disk and its bright spot until the stream refills it. */
        Limited,
        /** The weekly limit: the disk's low state, the white dwarf's faint point alone until the stream returns. */
        WeekLimited,
        /** The Mac can't be reached: the disk as last seen, drained of colour. */
        Offline,
        /** No Mac: a faint point. */
        Unpaired,
    }

    // ─── The sun: the accretion disk ────────────────────────────────────────

    /** The disk's colours, hottest inside (Disk.kt's HEAT_STOPS and HEAT_COLORS). */
    private val heatStops = floatArrayOf(0f, 0.22f, 0.48f, 0.72f, 0.9f, 1f)
    private val heatColors = intArrayOf(0xFFEEF4FF.toInt(), 0xFFFFF6E0.toInt(), 0xFFFFD88A.toInt(), 0xFFFF9A48.toInt(), 0xFFE0542A.toInt(), 0xFF9A2A16.toInt())
    private val COOL = 0xFF7A2416.toInt()
    private val COOL_RIM = 0xFFA8402A.toInt()
    private val SPOT = 0xFFFFC890.toInt()
    private val WD = 0xFFEEF2FF.toInt()

    // The disk's size, as Disk.kt has it: inner edge 2.6, rim 13 (dp in the sky, unit space here).
    private const val R_IN = 2.6f
    private const val R_OUT = 13f
    private const val R_IN_U = R_IN / R_OUT
    /** The disk's plane seen from 20° above (SkyLayout.SIN_E). */
    private const val SIN_E = 0.342f

    fun heat(k: Float): Int {
        val x = k.coerceIn(0f, 1f)
        for (i in 1 until heatStops.size) if (x <= heatStops[i]) return lerp(heatColors[i - 1], heatColors[i], (x - heatStops[i - 1]) / (heatStops[i] - heatStops[i - 1]))
        return heatColors.last()
    }

    /**
     * The sun at 5-hour usage [u] in a square bitmap [px] wide: the accretion disk of the sky
     * (docs/DESIGN.md, "The sun is an accretion disk"), a tilted ellipse whose hot part is the
     * budget left ([DiskModel.hotSpan]: by area, so the last tenth is a thin inner ring), the
     * spent part outside it cool and deep red, the white dwarf a point at the centre. At the
     * limit a dim red disk with the bright spot on its rim; at the weekly limit the point alone.
     * Around it, a few faint background stars. As an [icon] (a small mark beside the meters) the
     * disk fills more of its square and stands alone. Nothing moves: a still disk, as at rest.
     */
    fun star(px: Int, u: Float, look: Look, icon: Boolean = false): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val s = px.toFloat()
        val c = s / 2f
        // The rim, as a share of the square; the bloom and the white dwarf's halo fade inside it.
        val rim = s * (if (icon) 0.40f else 0.31f)
        if (!icon) starField(cv, s, look)
        when (look) {
            // The weekly limit, the low state: the stream has stopped and the disk has drained; the white dwarf a faint point alone.
            Look.WeekLimited -> whiteDwarf(cv, c, s, rim, 0.45f)
            Look.Unpaired -> whiteDwarf(cv, c, s, rim, 0.3f)
            // Quiescence: a dim, cool disk, the white dwarf at its centre and the bright spot on its rim.
            Look.Limited -> disk(cv, c, s, rim, left = 0f, cool = 0.8f, a = 1f, spot = 1f, desat = 0f)
            else -> {
                val left = if (look == Look.Unknown) 1f else 1f - u.coerceIn(0f, 1f)
                var a = 1f
                if (look == Look.Unknown) a = 0.7f
                if (look == Look.Offline) a = 0.55f
                disk(cv, c, s, rim, left = left, cool = 0.8f, a = a, spot = 0f, desat = if (look == Look.Offline) 0.85f else 0f)
            }
        }
        return bmp
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    /**
     * The disk, in unit space (radius 1 = the rim) scaled to [rim] px wide and SIN_E as tall:
     * the warm bloom, the cool spent disk, the hot disk out to where the budget left puts the
     * cooling front, the bright spot (at the limit), and the white dwarf.
     */
    private fun disk(cv: Canvas, c: Float, s: Float, rim: Float, left: Float, cool: Float, a: Float, spot: Float, desat: Float) {
        fun tone(col: Int) = if (desat > 0f) desaturate(col, desat) else col
        val hotOut = DiskModel.hotSpan(R_IN, R_OUT, left)
        // Its light scattered round it, as much as the disk is lit.
        val light = (hotOut * hotOut).coerceIn(0f, 1f)
        val bloomR = min(rim * 2.6f, s / 2f * 0.97f)
        cv.drawOval(
            RectF(c - bloomR, c - bloomR * 0.78f, c + bloomR, c + bloomR * 0.78f),
            shade(radial(
                c, c, bloomR,
                intArrayOf(alpha(tone(0xFFFFE8C0.toInt()), 0.34f * a * (0.35f + 0.65f * light)), alpha(tone(0xFFFFC880.toInt()), 0.14f * a * (0.35f + 0.65f * light)), alpha(tone(0xFFF09050.toInt()), 0.04f * a * light), 0),
                floatArrayOf(0f, 0.18f, 0.45f, 1f),
            )),
        )
        cv.save()
        cv.translate(c, c)
        cv.scale(rim, rim * SIN_E)
        // The spent disk: cool, deep red, dense inside, thin at its edge. Where it is all that is
        // left (the limit), it is dim.
        val coolA = a * cool * (if (hotOut <= 0.001f) 0.85f else 1f)
        cv.drawCircle(0f, 0f, 1f, solid(Color.BLACK).also {
            it.shader = RadialGradient(
                0f, 0f, 1f,
                intArrayOf(0, 0, alpha(tone(COOL), 0.6f * coolA), alpha(tone(COOL), 0.42f * coolA), alpha(tone(COOL_RIM), 0.3f * coolA), alpha(tone(COOL_RIM), 0.1f * coolA)),
                floatArrayOf(0f, R_IN_U - 0.002f, R_IN_U, 0.55f, 0.9f, 1f), Shader.TileMode.CLAMP,
            )
        })
        if (hotOut > 0.01f) {
            val w = 1f - R_IN_U
            val rh = R_IN_U + w * hotOut
            val edge = min(1f, rh + 0.07f)
            val cs = ArrayList<Int>(); val ps = ArrayList<Float>()
            cs.add(0); ps.add(0f)
            cs.add(alpha(tone(heat(0f)), 0f)); ps.add(R_IN_U - 0.002f)
            cs.add(alpha(tone(heat(0f)), 0.97f * a)); ps.add(R_IN_U)
            for (k in heatStops) {
                val r = R_IN_U + w * k
                if (r > R_IN_U + 0.004f && r < rh - 0.004f) { cs.add(alpha(tone(heat(k)), (0.97f - 0.45f * Math.pow(k.toDouble(), 0.8).toFloat()) * a)); ps.add(r) }
            }
            cs.add(alpha(tone(heat(hotOut)), (0.97f - 0.45f * Math.pow(hotOut.toDouble(), 0.8).toFloat()) * a)); ps.add(rh)
            if (edge > rh + 0.002f) { cs.add(alpha(tone(heat(hotOut)), 0f)); ps.add(edge) }
            if (edge < 0.999f) { cs.add(0); ps.add(1f) }
            fill.shader = RadialGradient(0f, 0f, 1f, cs.toIntArray(), ps.toFloatArray(), Shader.TileMode.CLAMP)
            fill.color = Color.BLACK
            cv.drawCircle(0f, 0f, 1f, fill)
            fill.shader = null
        }
        // The bright spot where the stream strikes the rim (ahead of the donor, which sits at the
        // top at the reset: the DiskModel.STREAM_LEAD past it), the brightest warm thing in a quiet disk.
        if (spot > 0.01f) {
            val ang = -PI.toFloat() / 2f + DiskModel.STREAM_LEAD
            val sx = cos(ang); val sy = sin(ang)
            cv.save()
            cv.translate(sx, sy)
            cv.scale(0.26f, 0.26f)
            cv.drawCircle(0f, 0f, 1f, shade(radial(0f, 0f, 1f, intArrayOf(alpha(SPOT, 0.9f * spot), alpha(SPOT, 0.4f * spot), 0), floatArrayOf(0f, 0.35f, 1f))))
            cv.restore()
            cv.drawCircle(sx, sy, 0.05f, solid(alpha(lerp(SPOT, Color.WHITE, 0.4f), spot)))
        }
        cv.restore()
        // The white dwarf at the centre: a point, its halo and four faint spikes.
        whiteDwarf(cv, c, s, rim, a * (if (desat > 0f) 0.8f else 1f), halo = true)
    }

    /** The white dwarf: a point (the size of the Earth), a halo and two faint spikes. Alone, it is the weekly limit. */
    private fun whiteDwarf(cv: Canvas, c: Float, s: Float, rim: Float, a: Float, halo: Boolean = true) {
        val hair = rim / R_OUT
        val h = min(12f * hair, s / 2f * 0.97f)
        if (halo) cv.drawCircle(c, c, h, shade(radial(c, c, h, intArrayOf(alpha(WD, 0.3f * a), alpha(WD, 0.08f * a), alpha(WD, 0f)), floatArrayOf(0f, 0.35f, 1f))))
        stroke.shader = null
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = max(0.8f * hair, 1f)
        stroke.color = alpha(WD, 0.12f * a)
        cv.drawLine(c - 9f * hair, c, c + 9f * hair, c, stroke)
        cv.drawLine(c, c - 5.6f * hair, c, c + 5.6f * hair, stroke)
        cv.drawCircle(c, c, 3.2f * hair, solid(alpha(WD, 0.22f * a)))
        cv.drawCircle(c, c, max(1.7f * hair, 1.2f), solid(alpha(WD, a)))
    }

    /** [fill] with a gradient, at full strength (a shader is multiplied by the paint's alpha). */
    private fun shade(s: Shader): Paint {
        fill.shader = s
        fill.color = Color.BLACK
        return fill
    }

    /** [fill] with a flat colour. */
    private fun solid(c: Int): Paint {
        fill.shader = null
        fill.color = c
        return fill
    }

    /**
     * A handful of faint background stars: the cosmos the sun sits in. Seeded, so the same
     * widget always has the same sky. Kept clear of the sun's glow, and dimmer the further
     * the sky has drained (offline, unpaired).
     */
    private fun starField(cv: Canvas, s: Float, look: Look) {
        // The week's low state leaves the far sky be: only our disk has gone out.
        val dim = when (look) { Look.Offline, Look.Unpaired -> 0.4f; else -> 1f }
        var seed = 0x5EED1234L
        fun rnd(): Float { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) and 0xFFFFFF).toFloat() / 0xFFFFFF }
        repeat(14) { i ->
            val x = s * (0.06f + 0.88f * rnd())
            val y = s * (0.06f + 0.88f * rnd())
            val dx = x - s / 2f; val dy = y - s / 2f
            if (dx * dx + dy * dy < (s * 0.26f) * (s * 0.26f)) return@repeat
            val anchor = i % 7 == 0
            val col = if (anchor) ACCENT_BRIGHT else lerp(ACCENT, INK, rnd() * 0.6f)
            val a = (if (anchor) 0.55f else 0.14f + 0.22f * rnd()) * dim
            val rr = s * (if (anchor) 0.0085f else 0.004f + 0.003f * rnd())
            cv.drawCircle(x, y, rr, solid(alpha(col, a)))
            if (anchor) {
                cv.drawCircle(x, y, rr * 4f, solid(alpha(col, 0.08f * dim)))
            }
        }
    }

    // ─── The planet ─────────────────────────────────────────────────────────

    enum class Mood {
        /** Mint, caught mid-turn: its meridians off-centre with a faint trail of where they were. */
        Working,
        /** Amber, still, in the sky's focus bracket. */
        NeedsYou,
        /** Grey and still. */
        Idle,
    }

    /**
     * The activity meter's planet in a square bitmap [px] wide: a sphere lit from the upper left,
     * its rim, and meridians. [bracket] adds the amber focus bracket the sky draws around a moon
     * that needs you (only where there's room for it: the session rows).
     */
    fun planet(px: Int, mood: Mood, bracket: Boolean = true): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val s = px.toFloat()
        val c = s / 2f
        val roomy = bracket && px >= 40
        // The sphere fills the square when it's the small meter; in a row it leaves room for the bracket and halo.
        val r = if (roomy) s * 0.32f else s / 2f - max(1f, s * 0.05f)
        val sw = max(r * 0.12f, 1f)
        when (mood) {
            Mood.Working -> {
                if (roomy) halo(cv, c, r, ACCENT, 0.16f)
                // Caught turning: the meridians where they are, and two fainter ones where they just were.
                sphere(cv, c, r, ACCENT, 0.62f, alpha = 1f, stroke = sw, ghosts = true)
            }
            Mood.NeedsYou -> {
                if (roomy) halo(cv, c, r, AMBER, 0.2f)
                sphere(cv, c, r, AMBER, PI.toFloat() / 4f, alpha = 1f, stroke = sw)
                if (roomy) focusBracket(cv, c, r * 1.42f, max(1f, s * 0.035f))
            }
            Mood.Idle -> sphere(cv, c, r, INK_DIM, PI.toFloat() / 4f, alpha = 0.85f, stroke = sw)
        }
        return bmp
    }

    private fun halo(cv: Canvas, c: Float, r: Float, col: Int, a: Float) {
        cv.drawCircle(c, c, r * 1.55f, shade(radial(c, c, r * 1.55f, intArrayOf(alpha(col, a), alpha(col, a * 0.4f), alpha(col, 0f)), floatArrayOf(0.55f, 0.75f, 1f))))
    }

    /** planetSphere(): lit from up and to the left, meridians as half ellipses r·sin λ wide. */
    private fun sphere(cv: Canvas, c: Float, r: Float, col: Int, spin: Float, alpha: Float, stroke: Float, ghosts: Boolean = false) {
        val lx = -0.7071f; val ly = -0.7071f
        val litX = c + lx * r * 0.64f; val litY = c + ly * r * 0.64f
        cv.drawCircle(c, c, r, shade(RadialGradient(litX, litY, r * 1.7f, intArrayOf(alpha(col, 0.75f * alpha), alpha(col, 0.22f * alpha), alpha(col, 0.04f * alpha)), null, Shader.TileMode.CLAMP)))
        this.stroke.shader = null
        this.stroke.strokeWidth = stroke
        this.stroke.strokeCap = Paint.Cap.BUTT
        fun meridians(at: Float, k: Float) {
            for (m in 0 until 4) {
                val lon = at + m * PI.toFloat() / 2f
                val depth = cos(lon)
                if (depth <= 0.05f) continue
                val half = r * sin(lon)
                val w = abs(half)
                val a = depth * (0.55f + 0.495f * (half / r) * lx).coerceIn(0.1f, 0.9f) * alpha * k
                this.stroke.color = alpha(col, a)
                cv.drawArc(RectF(c - max(w, 0.25f), c - r, c + max(w, 0.25f), c + r), if (half >= 0f) -90f else 90f, 180f, false, this.stroke)
            }
        }
        if (ghosts) {
            meridians(spin - 0.34f, 0.16f)
            meridians(spin - 0.17f, 0.34f)
        }
        meridians(spin, 1f)
        this.stroke.color = alpha(col, 0.45f * alpha)
        cv.drawCircle(c, c, r, this.stroke)
    }

    /** Four corner ticks around a body that needs you (the sky's contracting focus bracket, at rest). */
    private fun focusBracket(cv: Canvas, c: Float, b: Float, w: Float) {
        stroke.shader = null
        stroke.strokeWidth = w
        stroke.strokeCap = Paint.Cap.SQUARE
        stroke.color = alpha(AMBER, 0.85f)
        val l = b * 0.42f
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) {
            val x = c + sx * b; val y = c + sy * b
            cv.drawLine(x, y, x - sx * l, y, stroke)
            cv.drawLine(x, y, x, y - sy * l, stroke)
        }
    }

    // ─── Colour helpers ─────────────────────────────────────────────────────

    /**
     * A radial gradient whose stops run from 0 to 1: Compose holds the first colour before the
     * first stop by itself, so the ported stops (the chromosphere's start at 0.62) get it added.
     */
    private fun radial(x: Float, y: Float, r: Float, colors: IntArray, stops: FloatArray): RadialGradient {
        var cs = colors
        var ps = stops
        if (ps.first() > 0f) { cs = intArrayOf(cs.first()) + cs; ps = floatArrayOf(0f) + ps }
        if (ps.last() < 1f) { cs += cs.last(); ps += 1f }
        return RadialGradient(x, y, max(r, 0.5f), cs, ps, Shader.TileMode.CLAMP)
    }

    fun alpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((((c ushr 24) and 0xFF) * a.coerceIn(0f, 1f)).toInt() shl 24)

    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a ushr shift) and 0xFF) + (((b ushr shift) and 0xFF) - ((a ushr shift) and 0xFF)) * k).toInt() and 0xFF
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun desaturate(c: Int, k: Float): Int {
        val r = (c ushr 16) and 0xFF; val g = (c ushr 8) and 0xFF; val b = c and 0xFF
        val l = (0.3f * r + 0.59f * g + 0.11f * b).toInt()
        return lerp(c, (0xFF shl 24) or (l shl 16) or (l shl 8) or l, k)
    }
}
