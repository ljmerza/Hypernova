package com.prudnikweb.hypernova.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.NovaMark
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.ui.components.StatusChip
import com.prudnikweb.hypernova.ui.components.idleMark
import com.prudnikweb.hypernova.ui.components.needsYouMark
import com.prudnikweb.hypernova.ui.components.workingMark
import com.prudnikweb.hypernova.ui.fx.Atlas
import com.prudnikweb.hypernova.ui.fx.AtlasBackground
import com.prudnikweb.hypernova.ui.fx.AtlasChart
import com.prudnikweb.hypernova.ui.theme.HN
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Atlas's plate (fx/Atlas.kt) and marks (components/Marks.kt). Everything it draws from is pinned. */
@RunWith(RobolectricTestRunner::class)
class AtlasTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    @Before fun setUp() {
        resetPrefs()
    }

    @Test fun theSameScreenGetsTheSamePlate() {
        assertArrayEquals(Atlas.stipple(360f, 780f), Atlas.stipple(360f, 780f), 0f)
        assertArrayEquals(Atlas.stars(360f, 780f), Atlas.stars(360f, 780f), 0f)
        // The concept's count: a dot per 26 dp², 120 stars on a 360 × 780 dp phone.
        assertEquals(360 * 780 / 26, Atlas.stipple(360f, 780f).size / 4)
        assertEquals(120, Atlas.stars(360f, 780f).size / 5)
    }

    @Test fun theStarsThinWithTheWeekAndNeverAllGo() {
        val stars = Atlas.stars(360f, 780f)
        fun shown(week: Float) = (stars.indices step 5).count { Atlas.shows(stars[it + 4], Atlas.thin(week)) }
        assertEquals(120, shown(0f))
        assertTrue(shown(0.5f) < shown(0f))
        assertTrue(shown(1f) < shown(0.5f))
        assertTrue("some are left at the week's end", shown(1f) > 30)
    }

    @Test fun theWeekMovesInSteps() {
        assertEquals(Atlas.thin(0.5f), Atlas.thin(0.5005f), 0f)
        assertTrue(Atlas.thin(0.8f) < Atlas.thin(0.2f))
    }

    @Test fun theGridMovesByWholePixelsAndComesRound() {
        for (dp in listOf(1.5f, 2.625f, 3f)) for (n in 0 until 18) {
            val shift = Atlas.poleShift(n, dp)
            assertEquals(shift.toInt().toFloat(), shift, 0f)
            assertTrue(kotlin.math.abs(shift) <= 14f * dp + 1f)
            assertEquals(shift, Atlas.poleShift(n + 9, dp), 0.5f)
        }
        // A notch is a stretch of the wall clock, and goes on.
        assertEquals(Atlas.notch(0L), Atlas.notch(60_000L))
        assertFalse(Atlas.notch(0L) == Atlas.notch(20 * 60_000L))
    }

    @Test fun atRestItDrawsNothing() {
        val chart = AtlasChart()
        val week = mutableFloatStateOf(0.2f)
        rule.setContent { Harness(spy) { AtlasBackground(week.floatValue, Modifier.fillMaxSize(), chart = chart) } }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        val first = chart.draws
        assertEquals(1, first)
        // A minute on the clock: it asked for no frames.
        rule.mainClock.advanceTimeBy(60_000)
        rule.waitForIdle()
        assertEquals(first, chart.draws)
        // A new reading inside the same step redraws nothing.
        week.floatValue = 0.2003f
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertEquals(first, chart.draws)
        // A new step does, once.
        week.floatValue = 0.6f
        rule.waitForIdle()
        rule.onRoot().captureToImage()
        assertEquals(first + 1, chart.draws)
    }

    @Test fun thePlate() {
        val chart = AtlasChart()
        rule.setContent { Harness(spy) { Spacer(Modifier.fillMaxSize().drawBehind { chart.draw(this, Atlas.thin(0.2f), 0) }) } }
        rule.waitForIdle()
        rule.snap("atlas_plate")
    }

    @Test fun theMarksDifferByShapeAtRealSizeAndEnlarged() {
        rule.setContent {
            Harness(spy) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Planet(Activity.Live); Planet(Activity.Alert); Planet(Activity.Rest)
                        NovaMark(size = 11.dp); NovaMark(size = 22.dp); NovaMark(size = 34.dp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip("WORKING", HN.accent, live = true)
                        StatusChip("NEEDS YOU", HN.amber, filled = true)
                        StatusChip("IDLE", HN.inkDim)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusChip("SIGNED OUT", HN.amber)
                        StatusChip("ENDED", HN.inkDim, hollow = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        val big = 80.dp
                        Box(Modifier.size(big)) { Canvas(Modifier.fillMaxSize()) { workingMark(center, size.minDimension / 14f, HN.accent, -60f) } }
                        Box(Modifier.size(big)) { Canvas(Modifier.fillMaxSize()) { needsYouMark(center, size.minDimension / 14f, HN.amber) } }
                        Box(Modifier.size(big)) { Canvas(Modifier.fillMaxSize()) { idleMark(center, size.minDimension / 14f, HN.idle) } }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.snap("atlas_marks")
    }
}
