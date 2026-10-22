package com.prudnikweb.hypernova.ui.fx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The calm sky's places (ui/fx/SkyLayout.kt): at every phone width from 360 to 480dp, for every mix
 * of planets, belt, companion and hint, and at two font sizes, no two labels can overlap, a label
 * never covers a planet, the sun or its readout, and a long name is cut to its slot rather than
 * leaving it.
 */
class SkyLayoutTest {
    private val widths = (360..480 step 4).map { it.toFloat() }
    private val lines = listOf(13f to 14f, 17.5f to 19f) // (label line, readout) at font scale 1 and 1.35

    /** Every combination of places taken, belt, companion and hint. */
    private fun forEachSky(block: (w: Float, occ: BooleanArray, belt: Boolean, companion: Boolean, hint: Boolean, g: SkyLayout.Geometry) -> Unit) {
        for (w in widths) for (mask in 0 until 16) for (belt in listOf(false, true)) for (companion in listOf(false, true)) for (hint in listOf(false, true)) for ((line, readout) in lines) {
            val occ = BooleanArray(4) { mask and (1 shl it) != 0 }
            val g = SkyLayout.compute(w, occ, belt, companion, hint, line = line, readout = readout, hintH = line + 5f)
            block(w, occ, belt, companion, hint, g)
        }
    }

    /** Some names: long English, long Russian, short. Widths at 9sp with 0.2em tracking, ~8dp a letter. */
    private val names = listOf("APPLICATION-ALLOW-AVOID", "КУРСОВАЯ-РАБОТА-ПО-АСТРОФИЗИКЕ-И-КОСМОЛОГИИ", "MOON", "ROCKETEER", "X")
    private fun width(name: String) = name.length * 8f

    @Test fun labelsNeverOverlapEachOtherAtAnyPhoneWidth() {
        forEachSky { w, occ, belt, companion, _, g ->
            // The longest name in every slot at once, and the shortest: no pair overlaps either way.
            for (n in names) {
                val placed = (0 until 4).filter { occ[it] }.map { SkyLayout.place(g.labels[it], g.planetX[it], width(n)) }
                for (i in placed.indices) for (j in i + 1 until placed.size) {
                    assertFalse("w=$w occ=${occ.toList()} belt=$belt: ${placed[i]} / ${placed[j]}", placed[i].overlaps(placed[j]))
                }
                g.belt?.let { b -> for (p in placed) assertFalse("belt words under a name at w=$w", b.overlaps(p)) }
                g.companionLabel?.let { c -> for (p in placed) assertFalse("companion's words under a name at w=$w", c.overlaps(p)) }
            }
            assertEquals(belt, g.belt != null)
            assertEquals(companion, g.companion != null)
        }
    }

    @Test fun longNamesAreCutToTheirSlotAndStayOnScreen() {
        forEachSky { w, occ, _, _, _, g ->
            val screen = SkyBox(0f, 0f, w, g.height)
            for (s in 0 until 4) if (occ[s]) for (n in names) {
                val box = SkyLayout.place(g.labels[s], g.planetX[s], width(n))
                assertTrue("$n in slot $s at w=$w: $box not in ${g.labels[s]}", box.inside(g.labels[s]))
                assertTrue("slot $s at w=$w off screen: $box", box.inside(screen))
                assertTrue(box.l >= SkyLayout.MARGIN - 0.01f && box.r <= w - SkyLayout.MARGIN + 0.01f)
            }
            // A short name sits centred over its planet.
            for (s in 0 until 4) if (occ[s]) {
                val box = SkyLayout.place(g.labels[s], g.planetX[s], 30f)
                assertEquals(g.planetX[s], (box.l + box.r) / 2f, 0.01f)
            }
        }
    }

    @Test fun labelsNeverCoverAPlanetTheSunOrTheReadout() {
        forEachSky { w, occ, belt, _, hint, g ->
            val sun = SkyBox(g.sunX - 16f, g.sunY - 16f, g.sunX + 16f, g.sunY + 16f)
            val regions = (0 until 4).filter { occ[it] }.map { g.labels[it] } + listOfNotNull(g.belt, g.companionLabel)
            for (r in regions) {
                for (s in 0 until 4) if (occ[s]) assertFalse("w=$w belt=$belt: $r over planet $s ${g.body(s)}", r.overlaps(g.body(s)))
                assertFalse("w=$w: $r over the sun", r.overlaps(sun))
                assertFalse("w=$w hint=$hint: $r over the readout ${g.readout}", r.overlaps(g.readout))
            }
            // Planets keep clear of the sun, its readout and each other, and stay on screen.
            for (s in 0 until 4) if (occ[s]) {
                assertFalse("planet $s on the sun at w=$w", g.body(s).overlaps(sun))
                assertFalse("planet $s on the readout at w=$w", g.body(s).overlaps(g.readout))
                assertTrue("planet $s off screen at w=$w", g.body(s).l >= 0f && g.body(s).r <= w && g.body(s).t >= 0f && g.body(s).b <= g.height)
                for (o in s + 1 until 4) if (occ[o]) assertFalse("planets $s and $o at w=$w", g.body(s).overlaps(g.body(o)))
            }
            g.companion?.let { c -> assertTrue(c[1] < g.height && c[0] + 32f <= w) }
        }
    }

    @Test fun theUsageRingKeepsClearOfNamesAndPlanets() {
        // The 5-hour window's ring round the sun reaches RING_MAX at its biggest (a red giant).
        forEachSky { w, occ, _, _, _, g ->
            val r = SkyLayout.RING_MAX
            fun clear(b: SkyBox): Boolean {
                // The box's nearest point to the sun's centre is outside the ring.
                val nx = g.sunX.coerceIn(b.l, b.r)
                val ny = g.sunY.coerceIn(b.t, b.b)
                return hypot(nx - g.sunX, ny - g.sunY) > r
            }
            for (s in 0 until 4) if (occ[s]) {
                assertTrue("ring over planet $s at w=$w", clear(g.body(s)))
                assertTrue("ring over slot $s's words at w=$w", clear(g.labels[s]))
            }
            g.belt?.let { assertTrue("ring over the belt's words at w=$w", clear(it)) }
            // Its number sits under it, inside the readout's room.
            assertTrue(g.sunY + SkyLayout.READOUT_TOP_MAX >= g.sunY + r + 4f)
        }
    }

    @Test fun eachPlanetSitsOnItsOrbit() {
        forEachSky { _, _, _, _, _, g ->
            for (s in 0 until 4) {
                val x = g.sunX + g.orbit[s] * cos(g.angle[s])
                val y = g.sunY + g.orbit[s] * sin(g.angle[s]) * SkyLayout.SIN_E
                assertEquals(g.planetX[s], x, 0.01f)
                assertEquals(g.planetY[s], y, 0.01f)
            }
            // Inner orbits are inside outer ones, and the belt is outside them all, on screen.
            for (s in 1 until 4) assertTrue(g.orbit[s] > g.orbit[s - 1])
            assertTrue(g.beltA > g.orbit.max() && g.beltA < g.width / 2f)
        }
    }

    @Test fun theSkyIsShorterWithLessInIt() {
        for (w in widths) {
            fun h(vararg slots: Int, belt: Boolean = false, companion: Boolean = false, hint: Boolean = false) =
                SkyLayout.compute(w, BooleanArray(4) { it in slots }, belt, companion, hint).height
            val sunOnly = h()
            val upper = h(0)
            val full = h(0, 1, 2, 3, belt = true)
            assertTrue(sunOnly < upper)
            assertTrue(upper < h(0, 2))
            assertEquals(h(0, 1, 2, 3), h(0, 2), 0.01f) // a second planet in a row takes no more room
            assertTrue(full <= h(0, 1, 2, 3, belt = true, companion = true))
            assertTrue(h(0, hint = true) > upper)
            // Never more than about a fifth of a phone's height in dp (the old sky took half the screen).
            assertTrue("w=$w: $full", h(0, 1, 2, 3, belt = true, companion = true, hint = true) < 240f)
        }
    }

    @Test fun codexsStarAndTheCompanionShareTheBottomRowWithoutMeeting() {
        for (w in widths) for (mask in 0 until 16) for (belt in listOf(false, true)) for (companion in listOf(false, true)) for (hint in listOf(false, true)) for ((line, readout) in lines) {
            val occ = BooleanArray(4) { mask and (1 shl it) != 0 }
            val g = SkyLayout.compute(w, occ, belt, companion, hint, line = line, readout = readout, hintH = line + 5f, codex = true)
            val c = g.codex!!
            val cl = g.codexLabel!!
            assertEquals(companion, g.companion != null)
            // A star with its moons (its outermost shell, tilted like the view, and a moon's size).
            fun body(x: Float, y: Float) = SkyBox(x - SkyLayout.STAR_REACH - 3f, y - SkyLayout.STAR_REACH * SkyLayout.SIN_E - 3f, x + SkyLayout.STAR_REACH + 3f, y + SkyLayout.STAR_REACH * SkyLayout.SIN_E + 3f)
            val star = body(c[0], c[1])
            val screen = SkyBox(0f, 0f, w, g.height)
            assertTrue("codex's star off screen at w=$w: $star", star.inside(screen))
            assertTrue("codex's words off screen at w=$w: $cl", cl.inside(screen))
            // Room for "CODEX 100%" over "refills in 4h 59m" even on a 360 dp phone with the companion there.
            assertTrue("codex's words too narrow at w=$w: ${cl.width}", cl.width >= 80f)
            val planetWords = (0 until 4).filter { occ[it] }.map { g.labels[it] }
            val others = planetWords + listOfNotNull(g.belt, g.companionLabel, g.readout)
            for (o in others) assertFalse("codex's words over $o at w=$w", cl.overlaps(o))
            for (o in planetWords + listOfNotNull(g.belt, g.companionLabel, cl)) assertFalse("codex's star under $o at w=$w", star.overlaps(o))
            for (s in 0 until 4) if (occ[s]) assertFalse("codex's star on planet $s at w=$w", star.overlaps(g.body(s)))
            g.companion?.let { k ->
                val other = body(k[0], k[1])
                assertFalse("the two stars meet at w=$w", other.overlaps(star))
                assertFalse("the companion over codex's words at w=$w", other.overlaps(cl))
                assertTrue("the companion's words too narrow at w=$w", g.companionLabel!!.width >= 80f)
            }
            // Each keeps its place whether the other is there or not: nothing moves when one comes or goes.
            val alone = SkyLayout.compute(w, occ, belt, companion = false, hint, line = line, readout = readout, hintH = line + 5f, codex = true)
            assertEquals(c[0], alone.codex!![0], 0.001f)
            assertEquals(c[1], alone.codex!![1], 0.001f)
            assertEquals(cl.l, alone.codexLabel!!.l, 0.001f)
            if (companion) {
                val without = SkyLayout.compute(w, occ, belt, companion = true, hint, line = line, readout = readout, hintH = line + 5f, codex = false)
                assertEquals(g.companion!![0], without.companion!![0], 0.001f)
                assertEquals(g.companion!![1], without.companion!![1], 0.001f)
                assertEquals(g.companionLabel!!.r, without.companionLabel!!.r, 0.001f)
                // The row is the same height with one star or two.
                assertEquals(without.height, g.height, 0.001f)
            }
        }
    }

    @Test fun placesDontDependOnWhatElseIsInTheSky() {
        // A planet arriving or leaving moves no other planet (in the same rows).
        for (w in widths) {
            val a = SkyLayout.compute(w, booleanArrayOf(true, false, true, false), belt = true, companion = false, hint = false)
            val b = SkyLayout.compute(w, booleanArrayOf(true, true, true, true), belt = true, companion = false, hint = false)
            for (s in listOf(0, 2)) {
                assertEquals(a.planetX[s], b.planetX[s], 0.001f)
                assertEquals(a.planetY[s], b.planetY[s], 0.001f)
                assertEquals(a.labels[s], b.labels[s])
            }
            assertTrue(abs(a.sunY - b.sunY) < 0.001f)
            assertTrue(hypot(a.sunX - b.sunX, 0f) < 0.001f)
        }
    }
}
