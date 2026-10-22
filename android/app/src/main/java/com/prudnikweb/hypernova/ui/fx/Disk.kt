package com.prudnikweb.hypernova.ui.fx

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random

/**
 * The sun as it really is (docs/DESIGN.md, "The sun is an accretion disk"): a **white dwarf** at
 * the centre, the **accretion disk** round it that is the 5-hour window, and the **donor**, the
 * small cool star that feeds it, on a tilted orbit just outside the rim. Seen from 20° above the
 * disk's plane, like the rest of the system. Everything is drawn in unit space, radius 1 = the
 * disk's rim ([R_OUT] dp), so it draws at any zoom without new gradients.
 *
 * - **The hot disk is the budget left.** In outburst a disk is hot and bright, white-gold at its
 *   inner edge (T ∝ r^-¾, as in any thin disk), gold and orange outward. Its hot part reaches from
 *   the inner edge out to where the cooling front has got to: with the budget spent from the rim
 *   inward, as a dwarf nova's disk cools from its rim in ([hotOut], a share of the disk's width,
 *   set from [DiskModel.hotRadius]). At the reset the heating front sweeps in from the rim
 *   ([hotIn] falling from 1 to 0), where the stream has piled the gas.
 * - **The cool disk is the spent part**: the gas is still there, cool and deep red, thin at its
 *   edge; it thickens as the stream refills it through the wait ([cool]).
 * - **The flow is the spend rate.** While gas flows (something works and spends) **knots** of
 *   hotter gas orbit at Kepler speeds (ω ∝ r^-1.5: the inner edge goes round in [T_IN] s, a white
 *   dwarf's inner disk really does take seconds) and spiral inward at the flow's rate, and the
 *   **inner edge glows** with it (L = G M Ṁ / R: the light is the rate). At rest nothing flows and
 *   nothing moves: a smooth, still disk.
 * - **The donor** goes once round per window (its angle is the window's clock, [DiskModel.donorAngle]),
 *   a dull red star drawn out toward the white dwarf into its Roche point, lit on the side that
 *   faces the disk. Its **stream** curves forward from the point to the rim and lands as the
 *   **bright spot**, ahead of the donor: in quiescence the brightest warm thing in the system (the
 *   refill you can see), in outburst lost in the disk's light.
 *
 * Gradients are cached per colour and per step of the hot region (the step is too small to see),
 * so frames allocate nothing.
 */
internal class AccretionDisk(private val palette: DiskPalette = DiskPalette.Sun) {
    // Knots of hotter gas: how far each is along its inward spiral (0 = the rim, 1 = the inner
    // edge), its angle, its arc length and its brightness.
    private val kS = FloatArray(KNOTS)
    private val kAng = FloatArray(KNOTS)
    private val kLen = FloatArray(KNOTS)
    private val kB = FloatArray(KNOTS)

    /** The hot disk for one hot region (inner and outer limits in 1/[STEPS] steps). */
    private val hotCache = arrayOfNulls<Brush>(8)
    private val hotKeys = IntArray(8) { -1 }
    private var hotNext = 0

    /** The cool, spent disk: deep red, dense inside, thin at its rim (a little warmer where the stream lands). */
    private val coolBrush = Brush.radialGradient(
        0f to Color.Transparent, R_IN / R_OUT - 0.002f to Color.Transparent,
        R_IN / R_OUT to palette.cool.copy(alpha = 0.6f), 0.55f to palette.cool.copy(alpha = 0.42f), 0.9f to palette.coolRim.copy(alpha = 0.3f),
        1f to palette.coolRim.copy(alpha = 0.1f), center = Offset.Zero, radius = 1f,
    )
    /** The inner edge's light, where the gas finally falls onto the white dwarf: white-hot, gold round it. */
    private val glowBrush = Brush.radialGradient(
        0f to palette.glow[0].copy(alpha = 0.9f), 0.3f to palette.glow[1].copy(alpha = 0.35f), 0.6f to palette.glow[2].copy(alpha = 0.1f),
        1f to Color.Transparent, center = Offset.Zero, radius = 1f,
    )
    /** The bright spot where the stream strikes the rim. */
    private val spotBrush = Brush.radialGradient(0f to palette.spot, 0.35f to palette.spot.copy(alpha = 0.45f), 1f to palette.spot.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
    private val donorGlow = Brush.radialGradient(0f to palette.donor.copy(alpha = 0.35f), 0.5f to palette.donor.copy(alpha = 0.1f), 1f to palette.donor.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
    private val donorBody = Brush.radialGradient(0f to lerp(palette.donor, Color.White, 0.3f), 0.5f to palette.donor, 1f to lerp(palette.donor, palette.donorShade, 0.5f), center = Offset(-0.35f, -0.2f), radius = 1.3f)
    private val wdHalo = Brush.radialGradient(0f to WD.copy(alpha = 0.3f), 0.35f to WD.copy(alpha = 0.08f), 1f to WD.copy(alpha = 0f), center = Offset.Zero, radius = 1f)
    /** The disk's light scattered round it (unit space, radius 1 = 2.6 rims): a soft warm bloom, so the system reads as a bright centre at a glance. */
    private val bloom = Brush.radialGradient(0f to palette.bloom[0].copy(alpha = 0.34f), 0.18f to palette.bloom[1].copy(alpha = 0.14f), 0.45f to palette.bloom[2].copy(alpha = 0.04f), 1f to Color.Transparent, center = Offset.Zero, radius = 1f)
    /** The donor's Roche point: its side nearest the white dwarf drawn out into a cusp (unit space, 1 = the donor's radius, the cusp along +x). */
    private val cusp = Path().apply { moveTo(0.64f, -0.77f); quadraticTo(1.25f, -0.2f, 1.45f, 0f); quadraticTo(1.25f, 0.2f, 0.64f, 0.77f); close() }
    private val stream = Path()

    private var strokeKey = -1f
    private var knotW = 0.1f
    private var streamStroke = Stroke(0.05f, cap = StrokeCap.Round)
    private var streamGlow = Stroke(0.15f, cap = StrokeCap.Round)

    init {
        val rnd = Random(0x5eed)
        for (i in 0 until KNOTS) {
            kS[i] = rnd.nextFloat()
            kAng[i] = rnd.nextFloat() * TAU_F
            kLen[i] = 0.35f + 0.5f * rnd.nextFloat()
            kB[i] = 0.45f + 0.55f * rnd.nextFloat()
        }
    }

    /** A knot's radius (unit space) at [s] along its spiral: from the rim in to the inner edge. */
    private fun knotR(s: Float): Float = R_IN_U + (1f - R_IN_U) * (1f - s).pow(1.1f)

    /**
     * Moves the gas on by [dt] seconds at [flow] (0 at rest, 1 at the even pace, see
     * [DiskModel.flow]): each knot orbits at its Kepler speed, prograde (the way the donor and the
     * planets go), and drifts inward by [flow] × [INFLOW] of the disk's width a second; one that
     * reaches the inner edge comes back faint at the rim, as new gas from the stream.
     */
    fun advance(dt: Float, flow: Float) {
        if (flow <= 0f) return
        for (i in 0 until KNOTS) {
            kAng[i] += dt * Kepler.omega(knotR(kS[i]), R_IN_U, T_IN)
            if (kAng[i] > TAU_F * 64f) kAng[i] -= TAU_F * 64f
            kS[i] += dt * flow * INFLOW
            if (kS[i] >= 1f) kS[i] -= 1f
        }
    }

    /** The disk's colour at [k] (0 = the inner edge, 1 = the rim): hottest inside (T ∝ r^-¾). */
    private fun heat(k: Float): Color {
        val x = k.coerceIn(0f, 1f)
        val colors = palette.heat
        for (i in 1 until HEAT_STOPS.size) if (x <= HEAT_STOPS[i]) return lerp(colors[i - 1], colors[i], (x - HEAT_STOPS[i - 1]) / (HEAT_STOPS[i] - HEAT_STOPS[i - 1]))
        return colors.last()
    }

    /** How opaque the hot disk is at [k]: dense and bright inside, thinner toward its rim. */
    private fun density(k: Float): Float = 0.97f - 0.45f * k.coerceIn(0f, 1f).pow(0.8f)

    /** [x] rounded to steps of 1/[STEPS]. */
    private fun step(x: Float): Int = round(x.coerceIn(0f, 1f) * STEPS).toInt()

    /**
     * The hot disk between [lo] and [hi] (shares of the disk's width), as one radial gradient in
     * unit space: the heat ramp by absolute radius, soft edges where the fronts are, nothing
     * elsewhere. Cached per step of its limits.
     */
    private fun hotBrush(lo: Int, hi: Int): Brush? {
        if (hi - lo < 1) return null
        val key = lo * (STEPS + 1) + hi
        for (i in hotCache.indices) if (hotKeys[i] == key) return hotCache[i]
        val w = 1f - R_IN_U
        fun r(k: Float) = R_IN_U + w * k
        val kl = lo / STEPS.toFloat()
        val kh = hi / STEPS.toFloat()
        val e = 0.07f
        val stops = ArrayList<Pair<Float, Color>>(12)
        stops.add(0f to Color.Transparent)
        val rl = r(kl); val rh = r(kh)
        val edgeIn = max(R_IN_U - 0.002f, rl - e)
        stops.add(edgeIn to heat(kl).copy(alpha = 0f))
        stops.add(rl to heat(kl).copy(alpha = density(kl)))
        for (k in HEAT_STOPS) {
            val rr = r(k)
            if (rr > rl + 0.004f && rr < rh - 0.004f) stops.add(rr to heat(k).copy(alpha = density(k)))
        }
        stops.add(rh to heat(kh).copy(alpha = density(kh)))
        val edgeOut = minOf(1f, rh + e)
        if (edgeOut > rh + 0.002f) stops.add(edgeOut to heat(kh).copy(alpha = 0f))
        if (edgeOut < 0.999f) stops.add(1f to Color.Transparent)
        val b = Brush.radialGradient(*stops.toTypedArray(), center = Offset.Zero, radius = 1f)
        hotCache[hotNext] = b; hotKeys[hotNext] = key; hotNext = (hotNext + 1) % hotCache.size
        return b
    }

    private fun ensureStrokes(unit: Float, inv: Float, dp: Float) {
        val key = unit / inv
        if (strokeKey > 0f && kotlin.math.abs(key - strokeKey) <= strokeKey * 0.1f) return
        strokeKey = key
        knotW = max(0.06f, 0.9f * dp * inv / unit)
        streamStroke = Stroke(max(0.04f, 0.7f * dp * inv / unit), cap = StrokeCap.Round)
        streamGlow = Stroke(max(0.12f, 2.2f * dp * inv / unit), cap = StrokeCap.Round)
    }

    /**
     * Draws the system at [c] with [unit] px per rim radius. [alpha] fades all of it; [hotOut] and
     * [hotIn] bound the hot disk (shares of its width); [cool] is the spent disk's density (0 = gone);
     * [glow] (0..1+) lights the inner edge; [flow] shows the knots; [donor] is the donor's angle
     * (screen radians, −π/2 the top), [stream] its stream's brightness and [spot] the bright
     * spot's; [wd] the white dwarf's (0 = not there). [inv] is 1 / the camera's zoom, so hairlines
     * stay hairlines.
     */
    fun draw(
        s: DrawScope, c: Offset, unit: Float, dp: Float, inv: Float, alpha: Float,
        hotOut: Float, hotIn: Float, cool: Float, glow: Float, flow: Float,
        donor: Float, stream: Float, spot: Float, wd: Float, reduced: Boolean,
    ) = with(s) {
        if (alpha <= 0.004f || unit <= 0.5f) return@with
        ensureStrokes(unit, inv, dp)
        val lo = step(hotIn); val hi = step(hotOut)
        withTransform({ translate(c.x, c.y); scale(unit, unit, Offset.Zero) }) {
            // Its light scattered round it, as much as the disk is lit: a full disk is a bright centre, a quiet one barely glows.
            val light = ((hotOut * hotOut - hotIn * hotIn) * (if (hi > lo) 1f else 0f)).coerceIn(0f, 1f)
            if (light > 0.01f) scale(2.6f, 2.0f, Offset.Zero) { drawCircle(bloom, 1f, Offset.Zero, alpha = alpha * (0.35f + 0.65f * light)) }
            // The disk lies in the system's plane: as tall as SIN_E of its width.
            scale(1f, SIN_E, Offset.Zero) {
                if (cool > 0.004f) drawCircle(coolBrush, 1f, Offset.Zero, alpha = (alpha * cool).coerceAtMost(1f))
                hotBrush(lo, hi)?.let { drawCircle(it, 1f, Offset.Zero, alpha = alpha) }
            }
            if (flow > 0.01f && hi > lo) knots(alpha * flow.coerceAtMost(1f), lo / STEPS.toFloat(), hi / STEPS.toFloat())
            // The inner edge's light: brighter the harder the gas flows (and only while there's a hot disk for it to flow through).
            val g = glow.coerceIn(0f, 1.6f) * (if (lo == 0 && hi > 0) 1f else 0f)
            if (g > 0.01f) scale(R_IN_U * 2.4f, R_IN_U * 1.6f, Offset.Zero) { drawCircle(glowBrush, 1f, Offset.Zero, alpha = (alpha * 0.55f * g).coerceAtMost(1f)) }
            // The white dwarf: a point (the size of the Earth), its halo, four faint spikes.
            if (wd > 0.004f) whiteDwarf(alpha * wd, dp / unit * inv)
            // The donor on its orbit, its stream, and the bright spot where the stream lands.
            val da = alpha
            if (da > 0.004f) {
                val dx = DONOR_A_U * cos(donor); val dy = DONOR_A_U * sin(donor) * SIN_E
                if (stream > 0.01f || spot > 0.01f) streamAndSpot(donor, dx, dy, alpha * stream, alpha * spot)
                // Its lit side faces the disk; its Roche point reaches toward the white dwarf.
                val toward = atan2(-dy, -dx)
                withTransform({ translate(dx, dy); rotateRad(toward, Offset.Zero); scale(DONOR_R_U, DONOR_R_U, Offset.Zero) }) {
                    scale(2.6f, 2.6f, Offset.Zero) { drawCircle(donorGlow, 1f, Offset.Zero, alpha = da) }
                    drawPath(cusp, lerp(palette.donor, palette.donorShade, 0.35f), alpha = da)
                    drawCircle(donorBody, 1f, Offset.Zero, alpha = da)
                }
            }
        }
    }

    /** The knots of hotter gas, short arcs along their orbits, only inside the hot disk ([lo]..[hi]). */
    private fun DrawScope.knots(a: Float, lo: Float, hi: Float) {
        val w = 1f - R_IN_U
        for (i in 0 until KNOTS) {
            val s = kS[i]
            val r = knotR(s)
            val k = (r - R_IN_U) / w
            // Out of the rim as it starts in, gone as it falls through the inner edge, and only where the gas is hot.
            val life = (s / 0.12f).coerceIn(0f, 1f) * (1f - ((s - 0.88f) / 0.12f).coerceIn(0f, 1f))
            val inside = ((hi - k) / 0.1f).coerceIn(0f, 1f) * ((k - lo) / 0.1f).coerceIn(0f, 1f)
            val shown = life * inside
            if (shown <= 0.01f) continue
            val col = lerp(heat(k), Color.White, 0.35f)
            val mid = kAng[i]
            var px = 0f; var py = 0f
            for (j in 0..KNOT_STEPS) {
                val u = j / KNOT_STEPS.toFloat()
                val phi = mid + kLen[i] * (u - 0.5f)
                val x = r * cos(phi); val y = r * sin(phi) * SIN_E
                if (j > 0) {
                    val taper = sin(PI_F * (u - 0.5f / KNOT_STEPS))
                    val ka = (a * shown * kB[i] * density(k) * taper).coerceAtMost(1f)
                    if (ka > 0.004f) drawLine(col, Offset(px, py), Offset(x, y), strokeWidth = knotW * (1.3f - 0.5f * k), cap = StrokeCap.Round, alpha = ka)
                }
                px = x; py = y
            }
        }
    }

    /** The stream from the donor's point to the rim, curving forward, and the bright spot where it strikes. */
    private fun DrawScope.streamAndSpot(donor: Float, dx: Float, dy: Float, streamA: Float, spotA: Float) {
        // In the disk's plane: from the point of the donor to the rim ahead of it, bending toward the centre.
        val p0r = DONOR_A_U - DONOR_R_U * 1.4f
        val x0 = p0r * cos(donor); val y0 = p0r * sin(donor)
        val a2 = donor + DiskModel.STREAM_LEAD
        val x2 = cos(a2); val y2 = sin(a2)
        val x1 = (x0 + x2) * 0.5f * 0.7f; val y1 = (y0 + y2) * 0.5f * 0.7f
        if (streamA > 0.01f) {
            stream.rewind()
            stream.moveTo(x0, y0 * SIN_E)
            stream.quadraticTo(x1, y1 * SIN_E, x2, y2 * SIN_E)
            val col = lerp(palette.donor, palette.spot, 0.55f)
            drawPath(stream, col, alpha = 0.18f * streamA, style = streamGlow)
            drawPath(stream, col, alpha = 0.7f * streamA, style = streamStroke)
        }
        if (spotA > 0.01f) {
            withTransform({ translate(x2, y2 * SIN_E); scale(0.26f, 0.2f, Offset.Zero) }) { drawCircle(spotBrush, 1f, Offset.Zero, alpha = spotA) }
            drawCircle(lerp(palette.spot, Color.White, 0.4f), 0.045f, Offset(x2, y2 * SIN_E), alpha = spotA)
        }
    }

    /** The white dwarf at the origin (unit space): a halo, four faint spikes and the point itself. [hair] is one dp in unit space. */
    private fun DrawScope.whiteDwarf(a: Float, hair: Float) {
        val halo = 12f * hair
        scale(halo, halo, Offset.Zero) { drawCircle(wdHalo, 1f, Offset.Zero, alpha = a) }
        val sl = 9f * hair
        val sw = 0.8f * hair
        drawLine(WD, Offset(-sl, 0f), Offset(sl, 0f), sw, StrokeCap.Round, alpha = 0.12f * a)
        drawLine(WD, Offset(0f, -sl * 0.62f), Offset(0f, sl * 0.62f), sw, StrokeCap.Round, alpha = 0.12f * a)
        drawCircle(WD, 3.2f * hair, Offset.Zero, alpha = 0.22f * a)
        drawCircle(WD, 1.7f * hair, Offset.Zero, alpha = a)
    }

    companion object {
        /** The disk's inner edge (dp): the white dwarf's surface, where the gas finally lands. */
        const val R_IN = 2.6f
        /** The disk's rim (dp): the sun's size for the layout, the ring and the readout. */
        const val R_OUT = 13f
        /** The donor's orbit (dp), just outside the rim, inside the usage ring. */
        const val DONOR_A = 19.5f
        const val DONOR_R = 1.7f
        /** The inner edge's orbital period (s). Time runs fast in the disk, as it did round the hole. */
        const val T_IN = 9f
        /** How far along the disk's width a knot drifts in a second at flow 1: across it in twenty seconds. */
        private const val INFLOW = 0.05f
        private const val R_IN_U = R_IN / R_OUT
        private const val DONOR_A_U = DONOR_A / R_OUT
        private const val DONOR_R_U = DONOR_R / R_OUT
        private const val KNOTS = 14
        private const val KNOT_STEPS = 5
        /** The hot region's limits are kept in steps of 1/32 of the width: too small to see, few enough to cache. */
        private const val STEPS = 32
        private const val SIN_E = SkyLayout.SIN_E
        private const val TAU_F = 6.2831855f
        private const val PI_F = 3.1415927f
        /** Where the palette's six heat colours sit across the disk's width (0 = the inner edge, 1 = the rim). */
        private val HEAT_STOPS = floatArrayOf(0f, 0.22f, 0.48f, 0.72f, 0.9f, 1f)
        /** The white dwarf's light: blue-white, as a hot bare core is. */
        val WD = Color(0xFFEEF2FF)
        /** The disk's warm light on what it reaches: the planets' tint, the haze in space. */
        val WARM = Color(0xFFFFB070)
    }
}

/**
 * A disk's colours: its heat ramp from the inner edge to the rim (six stops), the spent gas, the
 * donor, the bright spot, the bloom round it and the inner edge's light. [Sun] is the sun's (gold
 * to red, as a thin disk's temperature has it); [Codex] the second agent's star, its own and
 * calmer: white-hot inside and silver out to its rim, its spent gas the app's ghost grey, all in
 * the design's ink tones (docs/DESIGN.md, "Colour").
 */
internal class DiskPalette(
    val heat: Array<Color>,
    val cool: Color,
    val coolRim: Color,
    val donor: Color,
    val donorShade: Color,
    val spot: Color,
    val bloom: Array<Color>,
    val glow: Array<Color>,
) {
    companion object {
        /** Blue-white at the inner edge (the hottest gas), white and pale gold through the middle, orange, then red only at the rim. */
        val Sun = DiskPalette(
            heat = arrayOf(Color(0xFFEEF4FF), Color(0xFFFFF6E0), Color(0xFFFFD88A), Color(0xFFFF9A48), Color(0xFFE0542A), Color(0xFF9A2A16)),
            cool = Color(0xFF7A2416),
            coolRim = Color(0xFFA8402A),
            donor = Color(0xFF9C4A38),
            donorShade = Color(0xFF3A1810),
            spot = Color(0xFFFFC890),
            bloom = arrayOf(Color(0xFFFFE8C0), Color(0xFFFFC880), Color(0xFFF09050)),
            glow = arrayOf(Color(0xFFFFFBF2), Color(0xFFFFE0A0), Color(0xFFFFB060)),
        )

        /**
         * Codex's star: blue-white at the inner edge, the app's ink through the middle, its muted and
         * dim greys out to the rim; the spent gas in ghost and line-strong greys, so a used-up Codex
         * reads as a dark, quiet ring rather than the sun's red.
         */
        val Codex = DiskPalette(
            heat = arrayOf(Color(0xFFEEF4FF), Color(0xFFF5F2EB), Color(0xFFDCD8CF), Color(0xFFB5B0A6), Color(0xFF7A7468), Color(0xFF45403A)),
            cool = Color(0xFF36322C),
            coolRim = Color(0xFF45403A),
            donor = Color(0xFF7A7468),
            donorShade = Color(0xFF221F1B),
            spot = Color(0xFFF5F2EB),
            bloom = arrayOf(Color(0xFFF5F2EB), Color(0xFFDCD8CF), Color(0xFFB5B0A6)),
            glow = arrayOf(Color(0xFFFFFFFF), Color(0xFFEEF4FF), Color(0xFFB5B0A6)),
        )
    }
}
