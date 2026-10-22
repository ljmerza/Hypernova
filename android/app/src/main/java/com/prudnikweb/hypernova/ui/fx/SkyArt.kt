package com.prudnikweb.hypernova.ui.fx

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/*
 * The pictures the background sky is made of, drawn pixel by pixel: the stars' sprites and the
 * deep-sky objects (galaxies, nebulae, a cluster, a corner of deep field), as a long exposure
 * would record them. Plain Kotlin with no Android types, so it runs off the main thread (and on
 * a desktop JVM, to look at the results). [Stars] and [DeepSky] turn them into bitmaps and draw them.
 *
 * Everything is light added up per pixel (emission), then turned into colour plus alpha at the
 * end, so drawn over the dark sky each picture shows as the light it records. Very bright parts
 * roll off softly instead of clipping.
 */

/** A picture as non-premultiplied ARGB pixels, row by row. */
internal class Art(val w: Int, val h: Int, val px: IntArray)

/** A colour of light (each channel 0..1, before brightness). */
internal class Rgb(val r: Float, val g: Float, val b: Float) {
    fun mix(o: Rgb, k: Float) = Rgb(r + (o.r - r) * k, g + (o.g - g) * k, b + (o.b - b) * k)
}

private val WHITE = Rgb(1f, 1f, 1f)

/** Light gathered per pixel; [art] turns it into a picture. */
internal class Light(val w: Int, val h: Int) {
    val r = FloatArray(w * h)
    val g = FloatArray(w * h)
    val b = FloatArray(w * h)

    fun add(i: Int, k: Float, c: Rgb) {
        r[i] += k * c.r; g[i] += k * c.g; b[i] += k * c.b
    }

    /** A soft round point of light at ([x], [y]) px, Gaussian of [sigma] px, peak [amp]. */
    fun splat(x: Float, y: Float, sigma: Float, amp: Float, c: Rgb, sx: Float = 1f, sy: Float = 1f, ang: Float = 0f) {
        val reach = 3f * sigma * max(sx, sy)
        val x0 = max(0, floor(x - reach).toInt()); val x1 = min(w - 1, ceil(x + reach).toInt())
        val y0 = max(0, floor(y - reach).toInt()); val y1 = min(h - 1, ceil(y + reach).toInt())
        val ca = cos(ang); val sa = sin(ang)
        val inv = 1f / (sigma * sigma)
        for (py in y0..y1) for (px in x0..x1) {
            val dx = px + 0.5f - x; val dy = py + 0.5f - y
            val u = (dx * ca + dy * sa) / sx; val v = (-dx * sa + dy * ca) / sy
            val k = amp * exp(-(u * u + v * v) * inv)
            if (k > 0.0005f) add(py * w + px, k, c)
        }
    }

    /**
     * The picture. Light up to about 0.6 shows as it is; brighter light rolls off toward full
     * (1 − e^−x), so a galaxy's core or a star's centre never clips into a flat disc.
     */
    fun art(gain: Float = 1f): Art {
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val m = max(r[i], max(g[i], b[i])) * gain
            if (m <= 0.002f) continue
            val a = 1f - exp(-m)
            val k = gain / m
            val cr = (r[i] * k).coerceIn(0f, 1f); val cg = (g[i] * k).coerceIn(0f, 1f); val cb = (b[i] * k).coerceIn(0f, 1f)
            out[i] = ((a * 255f + 0.5f).toInt() shl 24) or ((cr * 255f + 0.5f).toInt() shl 16) or ((cg * 255f + 0.5f).toInt() shl 8) or (cb * 255f + 0.5f).toInt()
        }
        return Art(w, h, out)
    }
}

// ─── Noise ──────────────────────────────────────────────────────────────────

private fun hash2(x: Int, y: Int, seed: Int): Float {
    var h = x * 0x27d4eb2d + y * 0x165667b1 + seed * -0x61c88647
    h = (h xor (h ushr 15)) * 0x2c1b3c6d
    h = (h xor (h ushr 12)) * 0x297a2d39
    h = h xor (h ushr 15)
    return (h ushr 8) / 16777216f
}

private fun vnoise(x: Float, y: Float, seed: Int): Float {
    val fx0 = floor(x); val fy0 = floor(y)
    val xi = fx0.toInt(); val yi = fy0.toInt()
    val fx = x - fx0; val fy = y - fy0
    val ux = fx * fx * (3f - 2f * fx); val uy = fy * fy * (3f - 2f * fy)
    val a = hash2(xi, yi, seed); val b = hash2(xi + 1, yi, seed)
    val c = hash2(xi, yi + 1, seed); val d = hash2(xi + 1, yi + 1, seed)
    return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
}

/** Fractal noise, 0..1 (mostly 0.25..0.75): [oct] octaves of value noise. */
private fun fbm(x: Float, y: Float, seed: Int, oct: Int = 4): Float {
    var sum = 0f; var amp = 0.5f; var f = 1f; var norm = 0f
    for (k in 0 until oct) {
        sum += amp * vnoise(x * f + k * 17.3f, y * f - k * 9.1f, seed + k * 131)
        norm += amp; amp *= 0.5f; f *= 2.03f
    }
    return sum / norm
}

private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val k = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return k * k * (3f - 2f * k)
}

private const val TAU_F = 6.2831855f

// ─── Stars ──────────────────────────────────────────────────────────────────

/**
 * Star colours by surface temperature, as a long exposure records them: from hot blue-white
 * (O/B, 20 000 K and up) through white (A/F) and yellow-white (G, like our Sun) to orange (K) and
 * red-orange (M, 3 000 K). A little more saturated than the eye sees them, as photographs are.
 */
internal val STAR_COLORS = arrayOf(
    Rgb(0.56f, 0.66f, 1f), Rgb(0.74f, 0.8f, 1f), Rgb(0.95f, 0.95f, 1f),
    Rgb(1f, 0.94f, 0.84f), Rgb(1f, 0.8f, 0.56f), Rgb(1f, 0.62f, 0.37f),
)

/**
 * How bright stars come, from the few bright ones to the many faint: each is a sprite drawn
 * once. [core] is the star itself (a Gaussian, dp), [wing] and [wingAmp] the soft halo a lens
 * spreads round it, [bloom] and [bloomAmp] the wide faint glow round a bright one (the sun's
 * bloom, smaller), [spike] and [spikeAmp] its diffraction spikes (the horizontal pair; the
 * vertical one is 0.62 as long, as the sun's are), [half] how far the sprite reaches (dp).
 */
internal class StarTier(
    val core: Float, val wing: Float, val wingAmp: Float, val bloom: Float, val bloomAmp: Float,
    val spike: Float, val spikeAmp: Float, val half: Float,
)

internal val STAR_TIERS = arrayOf(
    StarTier(core = 0.75f, wing = 1.2f, wingAmp = 0.45f, bloom = 3.4f, bloomAmp = 0.1f, spike = 17f, spikeAmp = 0.55f, half = 18f), // bright
    StarTier(core = 0.58f, wing = 0.9f, wingAmp = 0.32f, bloom = 2.2f, bloomAmp = 0.07f, spike = 8f, spikeAmp = 0.26f, half = 9f), // medium
    StarTier(core = 0.46f, wing = 0.7f, wingAmp = 0.2f, bloom = 1.3f, bloomAmp = 0.04f, spike = 0f, spikeAmp = 0f, half = 4f), // faint
    StarTier(core = 0.4f, wing = 0.6f, wingAmp = 0.1f, bloom = 0f, bloomAmp = 0f, spike = 0f, spikeAmp = 0f, half = 2.4f), // very faint
)

/** All the star sprites in one picture: [cell] (x, y, size in px) for each tier × colour. */
internal class StarAtlas(val art: Art, val cell: IntArray, val half: FloatArray)

/**
 * Bakes every star sprite for [dp] px per dp. Each is drawn like the sun ([Solar]'s star()):
 * a core white-hot at its centre and coloured outward, a soft halo in its colour, and for the
 * brighter ones a wide faint bloom and thin diffraction spikes (a long horizontal pair and a
 * shorter vertical one) fading toward their tips. Sprites are symmetric, so one quadrant is
 * computed and mirrored.
 */
internal fun starAtlas(dp: Float): StarAtlas {
    val n = STAR_COLORS.size
    val sizes = IntArray(STAR_TIERS.size) { t -> 2 * ceil(STAR_TIERS[t].half * dp).toInt() + 1 }
    val gap = 2
    val w = STAR_TIERS.indices.maxOf { t -> n * (sizes[t] + gap) }
    val h = sizes.sum() + gap * sizes.size
    val px = IntArray(w * h)
    val cell = IntArray(STAR_TIERS.size * n * 3)
    val half = FloatArray(STAR_TIERS.size) { t -> sizes[t] / 2f }
    var top = 0
    for (t in STAR_TIERS.indices) {
        val tier = STAR_TIERS[t]
        val sz = sizes[t]
        val c0 = sz / 2 // the centre pixel
        val sc = tier.core * dp; val sw = tier.wing * dp; val sb = max(tier.bloom * dp, 1f)
        val spW = max(0.55f, 0.24f * dp) // spike half-width (px): about a pixel wide
        val spL = tier.spike * dp; val spV = spL * 0.62f
        val edge = c0.toFloat()
        for (ci in 0 until n) {
            val col = STAR_COLORS[ci]
            val hot = col.mix(WHITE, 0.82f)
            val left = ci * (sz + gap)
            cell[(t * n + ci) * 3] = left; cell[(t * n + ci) * 3 + 1] = top; cell[(t * n + ci) * 3 + 2] = sz
            for (qy in 0..c0) for (qx in 0..c0) {
                val x = qx.toFloat(); val y = qy.toFloat()
                val r = hypot(x, y)
                // Everything fades out before the sprite's edge, so its square never shows.
                val fade = 1f - smooth(0.62f * edge, edge, r)
                if (fade <= 0f) continue
                val core = exp(-(r * r) / (sc * sc))
                // A Moffat wing: what a real lens spreads a point of light into.
                val wing = tier.wingAmp * (1f + (r / sw) * (r / sw)).pow(-1.6f) + tier.bloomAmp * exp(-(r * r) / (sb * sb))
                var spike = 0f
                if (spL > 0f) {
                    // Thin, bright near the star and fading toward the tip; the vertical pair shorter.
                    val hx = exp(-(y * y) / (spW * spW)) * (1f - smooth(0f, spL, x)).pow(2.2f)
                    val vy = exp(-(x * x) / (spW * spW)) * (1f - smooth(0f, spV, y)).pow(2.2f)
                    spike = tier.spikeAmp * max(hx, vy) * smooth(0.6f * sc, 2.2f * sc, r)
                }
                val l = (wing + spike) * fade
                // White-hot where the core dominates, the star's colour in its halo and spikes.
                val er = core * hot.r + l * col.r; val eg = core * hot.g + l * col.g; val eb = core * hot.b + l * col.b
                val m = max(er, max(eg, eb))
                if (m <= 0.002f) continue
                val a = 1f - exp(-1.6f * m)
                val argb = ((a * 255f + 0.5f).toInt() shl 24) or (((er / m) * 255f + 0.5f).toInt() shl 16) or
                    (((eg / m) * 255f + 0.5f).toInt() shl 8) or ((eb / m) * 255f + 0.5f).toInt()
                // Mirror the quadrant into the other three.
                for (sy in intArrayOf(-1, 1)) for (sx in intArrayOf(-1, 1)) {
                    if ((sx < 0 && qx == 0) || (sy < 0 && qy == 0)) continue
                    px[(top + c0 + sy * qy) * w + left + c0 + sx * qx] = argb
                }
            }
        }
        top += sz + gap
    }
    return StarAtlas(Art(w, h, px), cell, half)
}

// ─── Deep-sky objects ───────────────────────────────────────────────────────

/** The kinds of deep-sky object, in the order they're placed (biggest first). */
internal object Deep {
    const val PILLARS = 0
    const val FIELD = 1
    const val SPIRAL = 2
    const val EDGE_ON = 3
    const val GLOBULAR = 4
    const val ELLIPTICAL = 5
    const val RING = 6
    const val COUNT = 7
}

/**
 * Renders one deep-sky object of [kind] for [dp] px per dp, from [seed] (the sky's own, so each
 * sky has its own objects and keeps them).
 */
internal fun deepArt(kind: Int, seed: Long, dp: Float): Art {
    val rnd = Random(seed xor (kind * 0x9E3779B97F4A7C15uL.toLong()))
    val s = rnd.nextInt()
    return when (kind) {
        Deep.SPIRAL -> spiral(rnd, s, dp)
        Deep.EDGE_ON -> edgeOn(rnd, s, dp)
        Deep.ELLIPTICAL -> elliptical(rnd, s, dp)
        Deep.PILLARS -> pillars(rnd, s, dp)
        Deep.RING -> ring(rnd, s, dp)
        Deep.GLOBULAR -> globular(rnd, s, dp)
        else -> deepField(rnd, s, dp)
    }
}

private fun Random.range(a: Float, b: Float) = a + nextFloat() * (b - a)

private val CORE = Rgb(1f, 0.85f, 0.63f)      // an old population's yellow-white light
private val DISK = Rgb(0.96f, 0.9f, 0.84f)
private val ARM = Rgb(0.62f, 0.74f, 1f)       // young blue stars along the arms
private val HII = Rgb(1f, 0.4f, 0.6f)         // hydrogen glowing where stars are born
private val CLUSTER = Rgb(0.74f, 0.84f, 1f)

/**
 * A spiral galaxy seen nearly face on, as Hubble photographs M74 or M101: a yellow-white core in
 * a smooth glow of old stars, two (sometimes three) blue arms wound as logarithmic spirals that
 * break up into knots and feathery spurs, dark dust lanes along their inner edges and patchy dust
 * between them, and pink star-forming nebulae and blue clusters strung along the arms.
 */
private fun spiral(rnd: Random, seed: Int, dp: Float): Art {
    val d = rnd.range(66f, 84f)
    val n = ceil(d * dp).toInt() or 1
    val l = Light(n, n)
    val rr = n / 2f
    val ang = rnd.range(0f, TAU_F)
    val q = rnd.range(0.66f, 0.95f) // how round it shows: tilted up to about 50°
    val m = if (rnd.nextFloat() < 0.75f) 2 else 3
    val pitch = rnd.range(0.2f, 0.34f) // 12°–20°
    val hand = if (rnd.nextBoolean()) 1f else -1f
    val ph = rnd.range(0f, TAU_F)
    val barred = rnd.nextFloat() < 0.35f
    val ca = cos(ang); val sa = sin(ang)
    val kt = m / tan(pitch)
    for (py in 0 until n) for (px in 0 until n) {
        val u = (px + 0.5f - rr) / rr; val v = (py + 0.5f - rr) / rr
        val x = u * ca + v * sa; val y = (-u * sa + v * ca) / q
        val r = hypot(x, y)
        if (r > 1.05f) continue
        val th = atan2(y, x)
        val bulge = 1.4f * exp(-r / 0.03f) + 0.4f * exp(-r / 0.09f)
        val bar = if (barred) 0.45f * exp(-(x * x) / 0.05f - (y * y) / 0.0018f) else 0f
        val disk = exp(-r / 0.27f) * (1f - smooth(0.6f, 1f, r))
        val lr = ln(max(r, 0.04f))
        // The arms wander a little, more further out, where they break into spurs.
        val wob = (fbm(x * 3.5f + 3f, y * 3.5f + 7f, seed, 3) - 0.5f) * (0.9f + 1.4f * r)
        val psi = m * th * hand - kt * lr + ph
        val wave = (1f + cos(psi + wob)) * 0.5f
        val arm = wave.pow(2.2f) * smooth(0.08f, 0.24f, r)
        // Knots of young stars: the arms are made of them, not drawn as lines.
        val knots = fbm(x * 21f, y * 21f, seed + 5, 3)
        val clumps = 0.25f + 1.9f * knots.pow(2.4f) + 0.35f * fbm(x * 7f, y * 7f, seed + 6, 3)
        // Feathery spurs reaching out from the arms' outer edges.
        val spur = (1f + cos(psi * 1f + wob - 0.9f + 6f * fbm(x * 9f, y * 9f, seed + 8, 2))) * 0.5f
        val spurs = spur.pow(6f) * smooth(0.25f, 0.5f, r) * 0.45f
        // Dust: lanes on the arms' inner, concave edge, and patches between them.
        val lane = ((1f + cos(psi + 0.8f + wob * 1.1f)) * 0.5f).pow(6f) * smooth(0.06f, 0.18f, r) * (1f - smooth(0.72f, 0.95f, r))
        val patch = smooth(0.52f, 0.72f, fbm(x * 12f, y * 12f, seed + 9, 4)) * smooth(0.1f, 0.3f, r) * (1f - smooth(0.6f, 0.9f, r))
        val dust = (lane * (0.6f + 0.8f * fbm(x * 17f, y * 17f, seed + 10, 3)) + 0.55f * patch).coerceAtMost(1f)
        val dk = 1f - 0.88f * dust
        val i = py * n + px
        l.add(i, (bulge + bar) * (1f - 0.3f * dust), CORE)
        l.add(i, disk * 0.22f * dk, DISK)
        l.add(i, disk * 1.05f * (arm * clumps + spurs * clumps) * dk, ARM)
    }
    // Pink nebulae and blue clusters strung along the arms, more of them small.
    for (j in 0 until 90) {
        val k = rnd.nextInt(m)
        val r = 0.16f + 0.7f * rnd.nextFloat().pow(0.85f)
        val th = ((kt * ln(r) - ph + TAU_F * k) / m + rnd.range(-0.16f, 0.16f)) * hand
        val x = cos(th) * r; val y = sin(th) * r * q
        val sx = rr + (x * ca - y * sa) * rr; val sy = rr + (x * sa + y * ca) * rr
        val pink = j % 3 == 0
        val fade = exp(-r / 0.55f)
        val size = max(0.45f, rnd.range(0.003f, 0.009f) * rr)
        l.splat(sx, sy, size, (if (pink) rnd.range(0.45f, 0.9f) else rnd.range(0.35f, 0.75f)) * fade, if (pink) HII else CLUSTER)
    }
    return l.art()
}

/**
 * A spiral galaxy seen edge on (NGC 891, NGC 4565): a thin bright disk with a warm bulge, cut
 * along its length by a dark, mottled dust lane a little off its midplane, with dusty filaments
 * rising out of it.
 */
private fun edgeOn(rnd: Random, seed: Int, dp: Float): Art {
    val len = rnd.range(74f, 94f)
    val n = ceil(len * dp).toInt() or 1
    val l = Light(n, n)
    val rr = n / 2f
    val ang = rnd.range(0.15f, 0.65f) * (if (rnd.nextBoolean()) 1f else -1f)
    val ca = cos(ang); val sa = sin(ang)
    val off = rnd.range(0.006f, 0.016f) * (if (rnd.nextBoolean()) 1f else -1f)
    val thin = rnd.range(0.032f, 0.044f)
    val warmEnd = Rgb(0.8f, 0.86f, 1f)
    for (py in 0 until n) for (px in 0 until n) {
        val u = (px + 0.5f - rr) / rr; val v = (py + 0.5f - rr) / rr
        val x = u * ca + v * sa; val y = -u * sa + v * ca
        val ay = abs(y)
        if (ay > 0.28f) continue
        val ax = abs(x)
        val disk = 1.3f * exp(-ax / 0.36f) * exp(-ay / thin) * (1f - smooth(0.8f, 1f, ax))
        val thick = 0.3f * exp(-ax / 0.3f) * exp(-ay / 0.08f) * (1f - smooth(0.7f, 1f, ax))
        val bulge = 1.05f * exp(-hypot(x, y * 1.2f) / 0.05f) + 0.24f * exp(-hypot(x, y * 1.35f) / 0.15f)
        val dy = y - off
        val lane = exp(-(dy * dy) / (0.015f * 0.015f)) * (1f - smooth(0.62f, 0.95f, ax)) * (0.55f + 0.7f * fbm(x * 11f, y * 26f, seed, 4))
        val fil = smooth(0.5f, 0.8f, fbm(x * 9f, y * 24f, seed + 3, 4)) * exp(-abs(dy) / 0.045f) * (1f - smooth(0.5f, 0.88f, ax))
        val k = (1f - 0.7f * lane.coerceAtMost(1f) - 0.3f * fil).coerceAtLeast(0.12f)
        val i = py * n + px
        l.add(i, disk * 0.95f * k, DISK.mix(warmEnd, smooth(0.1f, 0.7f, ax)))
        l.add(i, (thick + bulge) * k, CORE)
    }
    // A few star-forming knots along the disk.
    for (j in 0 until 10) {
        val x = rnd.range(-0.7f, 0.7f); val y = off + rnd.range(-0.035f, 0.035f)
        l.splat(rr + (x * ca - y * sa) * rr, rr + (x * sa + y * ca) * rr, max(0.5f, 0.006f * rr), rnd.range(0.15f, 0.35f) * exp(-abs(x) / 0.6f), if (j % 2 == 0) HII else CLUSTER)
    }
    return l.art()
}

/**
 * An elliptical galaxy (like M87, smaller): a smooth warm glow with a bright core, fading slowly
 * into a wide halo, with a few of its globular clusters scattered round it as faint points.
 */
private fun elliptical(rnd: Random, seed: Int, dp: Float): Art {
    val d = rnd.range(34f, 44f)
    val n = ceil(d * dp).toInt() or 1
    val l = Light(n, n)
    val rr = n / 2f
    val q = rnd.range(0.62f, 0.92f)
    val ang = rnd.range(0f, TAU_F)
    val ca = cos(ang); val sa = sin(ang)
    val outer = Rgb(1f, 0.78f, 0.58f)
    for (py in 0 until n) for (px in 0 until n) {
        val u = (px + 0.5f - rr) / rr; val v = (py + 0.5f - rr) / rr
        val x = u * ca + v * sa; val y = (-u * sa + v * ca) / q
        val r = hypot(x, y)
        if (r > 1f) continue
        val i0 = 1.3f * (1f + (r / 0.06f) * (r / 0.06f)).pow(-0.85f) * (1f - smooth(0.5f, 1f, r))
        l.add(py * n + px, i0, CORE.mix(outer, smooth(0.05f, 0.5f, r)))
    }
    for (j in 0 until 14) {
        val a = rnd.range(0f, TAU_F); val r = rnd.range(0.22f, 0.85f)
        l.splat(rr + cos(a) * r * rr, rr + sin(a) * r * rr * 0.9f, 0.5f, rnd.range(0.2f, 0.45f), CORE)
    }
    return l.art()
}

/**
 * Star-forming pillars like Hubble's Pillars of Creation, in its teal-and-gold palette: lumpy
 * columns of cold dust rising from below, dark inside and warming toward their edges, lit gold
 * and white-hot on the sides that face the young stars above the frame, their heads boiling off
 * into a golden haze, against glowing blue-green gas.
 */
private fun pillars(rnd: Random, seed: Int, dp: Float): Art {
    val wd = rnd.range(84f, 96f); val hd = wd * rnd.range(1.15f, 1.3f)
    val w = ceil(wd * dp).toInt(); val h = ceil(hd * dp).toInt()
    val l = Light(w, h)
    val count = if (rnd.nextFloat() < 0.6f) 3 else 2
    val x0 = FloatArray(count); val hgt = FloatArray(count); val wb = FloatArray(count); val wt = FloatArray(count)
    val lean = FloatArray(count); val wph = FloatArray(count)
    val tallest = rnd.nextInt(count)
    for (k in 0 until count) {
        x0[k] = (if (count == 3) 0.2f + 0.6f * k / 2f else 0.3f + 0.4f * k) + rnd.range(-0.03f, 0.03f)
        hgt[k] = if (k == tallest) rnd.range(0.76f, 0.88f) else rnd.range(0.42f, 0.64f)
        wb[k] = rnd.range(0.12f, 0.16f) * (if (k == tallest) 1.2f else 0.9f)
        wt[k] = wb[k] * rnd.range(0.32f, 0.44f)
        lean[k] = rnd.range(0.08f, 0.2f) * (if (rnd.nextBoolean()) 1f else -1f)
        wph[k] = rnd.range(0f, TAU_F)
    }
    val aspect = hd / wd
    // How far (x, y) is outside the nearest pillar (in widths of the frame; negative inside), and
    // how far up the pillars it is (0 at the base, 1 at a head), into dOut[0] and dOut[1]. Where
    // two pillars are about as near, their heights blend, so no seam shows between them.
    val dOut = FloatArray(2)
    fun field(x: Float, y: Float) {
        var d = 1f; var sw = 0f; var ws = 0f
        for (k in 0 until count) {
            val s = (1.04f - y) / hgt[k]
            if (s < -0.1f || s > 1.3f) continue
            val sc = s.coerceIn(0f, 0.9f)
            val xc = x0[k] + lean[k] * sc.pow(1.3f) + 0.025f * sin(sc * 5f + wph[k])
            val hw = wb[k] + (wt[k] - wb[k]) * sc.pow(0.85f)
            val dCol = if (s <= 0.9f) abs(x - xc) - hw else 1f
            // The head: a rounded knob a little wider than the neck below it.
            val hr = wt[k] * 1.3f
            val yh = 1.04f - 0.9f * hgt[k]
            val dCap = hypot(x - xc, (y - yh) * aspect * 0.8f) - hr
            val dk = min(dCol, dCap)
            if (dk < d) d = dk
            val wk = exp(-dk.coerceAtLeast(-0.05f) / 0.03f)
            sw += wk * s.coerceIn(0f, 1f); ws += wk
        }
        // Lumpy outlines, larger lumps than ripples.
        d += (fbm(x * 5f, y * 5f * aspect, seed, 4) - 0.5f) * 0.12f + (fbm(x * 26f, y * 26f * aspect, seed + 4, 3) - 0.5f) * 0.022f
        dOut[0] = d; dOut[1] = if (ws > 0f) sw / ws else 0f
    }
    val teal = Rgb(0.24f, 0.64f, 0.66f); val blue = Rgb(0.3f, 0.52f, 0.86f); val fog = Rgb(0.62f, 0.5f, 0.32f)
    val rust = Rgb(0.7f, 0.4f, 0.2f); val gold = Rgb(1f, 0.78f, 0.44f); val hotRim = Rgb(1f, 0.94f, 0.8f)
    val e = 0.012f
    for (py in 0 until h) for (px in 0 until w) {
        val x = (px + 0.5f) / w; val y = (py + 0.5f) / h
        // Everything fades out before the frame's edge, so its rectangle never shows.
        val mask = 1f - smooth(0.32f, 0.98f, hypot((x - 0.5f) / 0.5f, (y - 0.5f) / 0.5f))
        if (mask <= 0f) continue
        field(x, y)
        val d = dOut[0]; val sAt = dOut[1]
        // Edges that face up (toward the stars above) are lit; the undersides of lumps stay dark.
        field(x, y - e)
        val up = ((dOut[0] - d) / e).coerceIn(-1f, 1f)
        val inside = smooth(0.006f, -0.016f, d)
        val lit = smooth(-0.2f, 0.7f, up)
        val rim = exp(-((d + 0.007f) / 0.011f).let { it * it }) * lit * (0.3f + 0.7f * smooth(0.35f, 1f, sAt))
        // Dust lit from outside: dim deep inside, warming toward its edges, where light gets in,
        // and mottled, brighter on the faces that look up toward the stars.
        val body = (0.16f + 0.4f * exp(d / 0.035f) * (0.35f + 0.65f * lit)) * (0.35f + 1.05f * fbm(x * 14f, y * 14f * aspect, seed + 17, 4).pow(1.3f)) * (0.5f + 0.5f * sAt)
        val haze = if (d > 0f) 0.26f * exp(-d / 0.04f) * smooth(0.55f, 1f, sAt) * (0.5f + lit) else 0f
        val gas = (0.1f + 0.7f * fbm(x * 3.2f, y * 3.2f * aspect, seed + 11, 5).pow(1.5f)) * (0.35f + 0.65f * (1f - y))
        val wisp = 0.16f * smooth(0.55f, 0.8f, fbm(x * 7f + 2f, y * 4f, seed + 13, 4))
        val brown = 0.12f * smooth(0.5f, 0.75f, fbm(x * 4f + 5f, y * 4f, seed + 29, 4)) * (1f - y)
        val i = py * w + px
        val out = (1f - 0.94f * inside) * mask
        l.add(i, (gas + wisp) * out, teal.mix(blue, smooth(0.3f, 0.7f, fbm(x * 2f, y * 2f, seed + 19, 3))))
        l.add(i, brown * out, fog)
        l.add(i, body * inside * mask, rust.mix(gold, 0.35f * smooth(0.4f, 0.7f, fbm(x * 6f, y * 6f * aspect, seed + 31, 3))))
        l.add(i, (rim * (0.6f + 0.8f * fbm(x * 22f, y * 22f, seed + 23, 3)) + haze) * mask, gold.mix(hotRim, smooth(0.75f, 1f, sAt) * lit))
    }
    return l.art(0.95f)
}

/**
 * A ring-shaped planetary nebula like M57, as Hubble and Webb show it: a dying star's shell seen
 * end on, a faint blue-green glow filling its middle, a thick ring of gas going from yellow-green
 * on its inside to orange and red outside, combed by radial spokes and broken by small dark knots,
 * a faint red halo of loops beyond it, and the white dwarf at its centre.
 */
private fun ring(rnd: Random, seed: Int, dp: Float): Art {
    val d = rnd.range(30f, 38f)
    val n = ceil(d * dp).toInt() or 1
    val l = Light(n, n)
    val rr = n / 2f
    val q = rnd.range(0.74f, 0.86f)
    val ang = rnd.range(0f, TAU_F)
    val ca = cos(ang); val sa = sin(ang)
    val teal = Rgb(0.45f, 0.78f, 0.82f); val green = Rgb(0.72f, 0.86f, 0.58f)
    val orange = Rgb(0.98f, 0.62f, 0.42f); val red = Rgb(0.92f, 0.36f, 0.32f)
    for (py in 0 until n) for (px in 0 until n) {
        val u = (px + 0.5f - rr) / rr; val v = (py + 0.5f - rr) / rr
        val x = u * ca + v * sa; val y = (-u * sa + v * ca) / q
        val r = hypot(x, y) * 1.05f
        if (r > 1.05f) continue
        val th = atan2(y, x)
        val turb = fbm(x * 5f, y * 5f, seed, 4)
        // Radial spokes: the gas is streaked along the way it flows out.
        val spokes = 0.75f + 0.5f * fbm(cos(th) * 3f + th * 2.5f, r * 6f, seed + 3, 3)
        val knots = smooth(0.6f, 0.82f, fbm(x * 15f + 1f, y * 15f + 2f, seed + 7, 3))
        val inner = 0.26f * (1f - smooth(0.25f, 0.52f, r)) * (0.6f + 0.6f * turb)
        val band = exp(-((r - 0.5f) / 0.12f).let { it * it }) * (0.55f + 0.7f * turb) * spokes * (1f - 0.3f * knots)
        val loops = 0.08f * exp(-((r - 0.82f) / 0.1f).let { it * it }) * (0.3f + 1.2f * smooth(0.45f, 0.75f, fbm(x * 7f, y * 7f, seed + 11, 3)))
        val c = when {
            r < 0.42f -> teal.mix(green, smooth(0.25f, 0.42f, r))
            r < 0.56f -> green.mix(orange, smooth(0.42f, 0.56f, r))
            else -> orange.mix(red, smooth(0.54f, 0.66f, r))
        }
        val i = py * n + px
        l.add(i, (inner + band) * 0.8f, c)
        l.add(i, loops, red)
    }
    l.splat(rr, rr, max(0.55f, 0.012f * rr), 0.8f, Rgb(0.8f, 0.88f, 1f))
    return l.art()
}

/**
 * A globular cluster (M13, 47 Tucanae): hundreds of thousands of old stars, most of them faint,
 * crowding toward a core that blurs into one glow. Yellow-white and orange, with a few bright red
 * giants and hot blue stars among them.
 */
private fun globular(rnd: Random, seed: Int, dp: Float): Art {
    val d = rnd.range(40f, 50f)
    val n = ceil(d * dp).toInt() or 1
    val l = Light(n, n)
    val rr = n / 2f
    val a = rnd.range(0.1f, 0.14f)
    // The unresolved glow of the stars too faint and too crowded to see one by one.
    for (py in 0 until n) for (px in 0 until n) {
        val r = hypot(px + 0.5f - rr, py + 0.5f - rr) / rr
        if (r > 1f) continue
        l.add(py * n + px, 0.7f * (1f + (r / a).let { it * it }).pow(-1.6f) * (1f - smooth(0.6f, 1f, r)), CORE)
    }
    val giant = Rgb(1f, 0.7f, 0.46f); val hb = Rgb(0.76f, 0.83f, 1f)
    val warm = STAR_COLORS[4].mix(WHITE, 0.4f); val pale = STAR_COLORS[3].mix(WHITE, 0.3f)
    val dot = max(0.5f, 0.17f * dp)
    repeat(1500) {
        // Plummer: the radius that holds a fraction u of the stars.
        val u = rnd.range(0.01f, 0.995f)
        val r = a / sqrt(u.pow(-2f / 3f) - 1f)
        if (r > 1f) return@repeat
        val th = rnd.range(0f, TAU_F)
        val b = 0.1f + 0.9f * rnd.nextFloat().pow(6f)
        val kind = rnd.nextFloat()
        val c = when { kind < 0.04f -> giant; kind < 0.12f -> hb; kind < 0.5f -> warm; else -> pale }
        val big = if (kind < 0.04f) 1.4f else 1f
        l.splat(rr + cos(th) * r * rr, rr + sin(th) * r * rr, dot * big * (0.85f + 0.4f * b), b * (if (kind < 0.04f) 1.2f else 0.75f), c)
    }
    return l.art()
}

/**
 * A corner of deep field, as in the Hubble Ultra Deep Field: dozens of galaxies billions of light
 * years away, most of them tiny smudges (red ellipticals, blue clumpy star-forming ones, distant
 * red dots), a few close enough to show a disk or arms, and a foreground star or two with spikes.
 * It fades away from the screen edge it's set against.
 */
private fun deepField(rnd: Random, seed: Int, dp: Float): Art {
    val wd = 128f; val hd = 112f
    val w = ceil(wd * dp).toInt(); val h = ceil(hd * dp).toInt()
    val l = Light(w, h)
    val red = Rgb(1f, 0.62f, 0.42f); val far = Rgb(1f, 0.46f, 0.34f); val blue = Rgb(0.6f, 0.72f, 1f)
    // How visible each point of the patch is: full at the screen edge (x = 0 here; mirrored to
    // the right edge when drawn there), gone toward the middle of the screen.
    fun mask(x: Float, y: Float) = (1f - smooth(0.25f, 0.95f, x)) * (1f - smooth(0.55f, 1f, abs(y - 0.5f) * 2f))
    var placed = 0
    var tries = 0
    while (placed < 78 && tries < 2000) {
        tries++
        val fx = rnd.nextFloat(); val fy = rnd.nextFloat()
        val mk = mask(fx, fy)
        if (rnd.nextFloat() > mk) continue
        placed++
        val size = (0.5f + 6.2f * rnd.nextFloat().pow(4.5f)) * dp // semi-major axis, px
        val q = rnd.range(0.3f, 1f)
        val ang = rnd.range(0f, TAU_F)
        val kind = rnd.nextFloat()
        val cx = fx * w; val cy = fy * h
        val amp = (0.35f + 0.5f * rnd.nextFloat()) * (0.4f + 0.6f * mk)
        when {
            size > 3.2f * dp && kind < 0.55f -> { // a small spiral, near enough to show its disk
                val n = ceil(size * 1.6f).toInt()
                val ca = cos(ang); val sa = sin(ang)
                val ph = rnd.range(0f, TAU_F)
                for (yy in -n..n) for (xx in -n..n) {
                    val px = (cx + xx).toInt(); val py = (cy + yy).toInt()
                    if (px !in 0 until w || py !in 0 until h) continue
                    val x = (xx * ca + yy * sa) / size; val y = (-xx * sa + yy * ca) / (size * q)
                    val r = hypot(x, y)
                    if (r > 1.5f) continue
                    val arm = ((1f + cos(2f * atan2(y, x) - 4f * ln(max(r, 0.08f)) + ph)) * 0.5f).pow(2f)
                    val i = py * w + px
                    l.add(i, amp * 1.4f * exp(-r / 0.09f), CORE)
                    l.add(i, amp * 0.6f * exp(-r / 0.38f) * (0.35f + 0.65f * arm) * (1f - smooth(0.9f, 1.5f, r)), ARM)
                }
            }
            kind < 0.4f -> l.splat(cx, cy, size * 0.45f, amp * 0.9f, red, 1f, q, ang) // elliptical
            kind < 0.75f -> { // clumpy, blue, forming stars
                val k = 1 + rnd.nextInt(3)
                for (j in 0 until k) l.splat(cx + rnd.range(-1f, 1f) * size * 0.6f, cy + rnd.range(-1f, 1f) * size * 0.4f, max(0.5f, size * 0.3f), amp * 0.7f, blue, 1f, q, ang)
            }
            else -> l.splat(cx, cy, max(0.5f, size * 0.3f), amp * 0.8f, far) // a distant red dot
        }
    }
    // A foreground star of our own galaxy, with its spikes.
    val fx = rnd.range(0.08f, 0.35f) * w; val fy = rnd.range(0.25f, 0.75f) * h
    val col = STAR_COLORS[3 + rnd.nextInt(2)]
    l.splat(fx, fy, 0.7f * dp, 1.6f, col.mix(WHITE, 0.7f))
    l.splat(fx, fy, 2f * dp, 0.12f, col)
    l.splat(fx, fy, 9f * dp, 0.3f, col, 1f, 0.06f, 0f)
    l.splat(fx, fy, 6f * dp, 0.3f, col, 0.06f, 1f, 0f)
    return l.art()
}
