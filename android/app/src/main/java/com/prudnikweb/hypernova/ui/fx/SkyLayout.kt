package com.prudnikweb.hypernova.ui.fx

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A rectangle in dp (left, top, right, bottom). */
data class SkyBox(val l: Float, val t: Float, val r: Float, val b: Float) {
    val width: Float get() = r - l
    val height: Float get() = b - t
    fun overlaps(o: SkyBox): Boolean = min(r, o.r) - max(l, o.l) > 0.01f && min(b, o.b) - max(t, o.t) > 0.01f
    fun inside(o: SkyBox): Boolean = l >= o.l - 0.01f && r <= o.r + 0.01f && t >= o.t - 0.01f && b <= o.b + 0.01f
}

/**
 * Where everything in the calm sky sits (docs/DESIGN.md, "The calm sky"), in dp from the band's top
 * left corner. Pure, unit-tested: labels sit in fixed slots that can't overlap, at any phone width.
 *
 * Four places for planets round the sun: 0 upper right, 1 upper left (behind the sun, on the inner
 * orbits), 2 lower right, 3 lower left (in front of it, on the outer ones). The upper planets' names
 * sit above them, the lower ones' below the sun's readout; each name keeps to its half of the
 * screen, so two names can never meet. The quiet projects' belt is the outermost ring, its "+5 quiet"
 * in the middle of the top row. The companion star (other providers) and Codex's star (the second
 * agent, while both are in use) share a row of their own at the bottom: the companion on its right
 * with its words to its left, as it always was, Codex's star mirrored on its left with its words to
 * its right; each keeps its side whether the other is there or not, so neither moves when the other
 * comes or goes, and each one's words keep to its own half while both are there. The band is only
 * as tall as what's in it: no lower planets, no lower row.
 */
object SkyLayout {
    const val SLOTS = SkyPlanner.MAX_PLANETS
    /** The view looks down 20° on the system's plane: an orbit shows 0.342 as tall as it's wide. */
    const val SIN_E = 0.342f
    /** Side margins of the label rows. */
    const val MARGIN = 14f
    const val TOP_PAD = 6f
    const val BOTTOM_PAD = 8f
    /** How far a planet reaches with its moons, for clearances. */
    const val REACH = 16f
    const val GAP = 5f
    /** Half the clear middle of the top row while the belt's words sit there. */
    const val BELT_HALF = 40f
    /** Between the two halves of a row. */
    const val MID = 6f
    /** The sun's centre never sits higher than this: the disk's glow and the donor's orbit need the room. */
    const val SUN_MIN_Y = 52f
    /**
     * The readout's top below the sun's centre at its biggest: under the usage ring of a red giant
     * (16dp × 1.55 + 6dp, its time arc 4dp out) and 5dp more.
     */
    const val READOUT_TOP_MAX = 40f
    /** The usage ring's reach round the sun at its biggest (with its time arc and notch). */
    const val RING_MAX = 35f
    /** Half the widest the readout or the hint under it gets ("Tap the sun for your statistics", "REFILLS IN 4H 59M"). */
    const val READOUT_HALF = 100f
    /** A bottom-row star's centre in from the band's edge, and its words' edge from its centre (clear of its outermost moons, 32 dp). */
    const val STAR_IN = 34f
    const val STAR_WORDS = 40f
    /** How far a bottom-row star reaches with its moons (its outermost shell, 32 dp). */
    const val STAR_REACH = 32f

    /** Each place's offset from the sun: x as a share of the width, y in dp (up is negative). */
    private val DX = floatArrayOf(0.24f, -0.30f, 0.36f, -0.40f)
    private val DY = floatArrayOf(-18f, -22f, 20f, 24f)

    fun upper(slot: Int): Boolean = slot < 2

    /**
     * The layout for a band [width] dp wide. [occupied]: which places have a planet (entering,
     * leaving or there); [belt]: quiet projects; [companion]: the other providers' star; [hint]:
     * the line under the readout ("Tap the sun…"). [line] is one line of the 9sp label text, in dp
     * (it grows with the font size), [readout] the readout's height and [hintH] the hint's.
     * [codex]: Codex's star (both agents in use).
     */
    fun compute(
        width: Float, occupied: BooleanArray, belt: Boolean, companion: Boolean, hint: Boolean,
        line: Float = 13f, readout: Float = 14f, hintH: Float = 18f, codex: Boolean = false,
    ): Geometry {
        val label = line * 2f
        val cx = width / 2f
        val anyUpper = occupied[0] || occupied[1]
        val anyLower = occupied[2] || occupied[3]
        // The sun sits under the top row (when there is one) and the upper planets.
        val sunY = if (anyUpper || belt) {
            max(SUN_MIN_Y, TOP_PAD + label + GAP + REACH - min(DY[0], DY[1]))
        } else SUN_MIN_Y
        val px = FloatArray(SLOTS) { cx + DX[it] * width }
        val py = FloatArray(SLOTS) { sunY + DY[it] }
        // Each orbit passes through its planet: a·cosθ = dx, a·sinθ·SIN_E = dy.
        val a = FloatArray(SLOTS) { hypot(px[it] - cx, (py[it] - sunY) / SIN_E) }
        val theta = FloatArray(SLOTS) { atan2((py[it] - sunY) / SIN_E, px[it] - cx) }
        val readoutBottom = sunY + READOUT_TOP_MAX + readout + (if (hint) hintH else 0f)
        val lowerTop = readoutBottom + GAP
        val left = SkyBox(MARGIN, 0f, cx - MID, 0f)
        val right = SkyBox(cx + MID, 0f, width - MARGIN, 0f)
        val beltHalf = if (belt) BELT_HALF else MID
        val regions = Array(SLOTS) { s ->
            val side = if (px[s] >= cx) right else left
            if (upper(s)) {
                val b = py[s] - REACH - GAP
                // Clear of the belt's words in the middle of the row.
                val l = if (side === right) cx + beltHalf else side.l
                val r = if (side === left) cx - beltHalf else side.r
                SkyBox(l, b - label, r, b)
            } else SkyBox(side.l, lowerTop, side.r, lowerTop + label)
        }
        var bottom = if (anyLower) lowerTop + label else readoutBottom
        val beltBox = if (belt) SkyBox(cx - BELT_HALF + MID, TOP_PAD, cx + BELT_HALF - MID, TOP_PAD + line) else null
        // The bottom row: the companion on its right, its words to its left; Codex's star on its
        // left, its words to its right. Alone in the row, a star's words may take all of it beyond
        // its moons; with both there, each keeps to its half.
        var star: FloatArray? = null
        var starLabel: SkyBox? = null
        var codexStar: FloatArray? = null
        var codexLabel: SkyBox? = null
        if (companion || codex) {
            val top = bottom + GAP
            if (companion) {
                val sx = width - MARGIN - STAR_IN
                star = floatArrayOf(sx, top + label / 2f)
                starLabel = SkyBox(if (codex) cx + MID else MARGIN, top, sx - STAR_WORDS, top + label)
            }
            if (codex) {
                val sx = MARGIN + STAR_IN
                codexStar = floatArrayOf(sx, top + label / 2f)
                codexLabel = SkyBox(sx + STAR_WORDS, top, if (companion) cx - MID else width - MARGIN, top + label)
            }
            bottom = top + label
        }
        // The belt: just outside the outermost orbit, as wide as the screen allows.
        val beltA = min(a.max() + 12f, cx - 6f)
        return Geometry(
            width = width, height = bottom + BOTTOM_PAD, sunX = cx, sunY = sunY,
            planetX = px, planetY = py, orbit = a, angle = theta, labels = regions,
            readout = SkyBox(cx - min(READOUT_HALF, cx - MARGIN), sunY + 15f, cx + min(READOUT_HALF, cx - MARGIN), readoutBottom),
            belt = beltBox, beltA = beltA, companion = star, companionLabel = starLabel,
            codex = codexStar, codexLabel = codexLabel,
        )
    }

    /**
     * Where a label [w] dp wide goes in [region]: centred over its planet at [anchorX] where it fits,
     * pushed in from the region's edge where it doesn't. A label is never wider than its region
     * (its text is cut short to fit), so it never leaves it.
     */
    fun place(region: SkyBox, anchorX: Float, w: Float): SkyBox {
        val ww = min(w, region.width)
        val l = (anchorX - ww / 2f).coerceIn(region.l, region.r - ww)
        return SkyBox(l, region.t, l + ww, region.b)
    }

    class Geometry(
        val width: Float,
        /** The band's height: the list may start right under it. */
        val height: Float,
        val sunX: Float,
        val sunY: Float,
        val planetX: FloatArray,
        val planetY: FloatArray,
        /** Each place's orbit (semi-major axis, dp) and the planet's angle on it (radians, 0 = right). */
        val orbit: FloatArray,
        val angle: FloatArray,
        /** The box each place's label must keep to. */
        val labels: Array<SkyBox>,
        /** Where the sun's readout (and the hint under it) may be, at the sun's biggest. */
        val readout: SkyBox,
        val belt: SkyBox?,
        val beltA: Float,
        /** The companion's centre (x, y), or null. */
        val companion: FloatArray?,
        val companionLabel: SkyBox?,
        /** Codex's star's centre (x, y), or null, and the box its words keep to. */
        val codex: FloatArray? = null,
        val codexLabel: SkyBox? = null,
    ) {
        /** A planet's reach, as a box. */
        fun body(slot: Int): SkyBox = SkyBox(planetX[slot] - REACH, planetY[slot] - REACH, planetX[slot] + REACH, planetY[slot] + REACH)
    }
}
