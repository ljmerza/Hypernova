package com.prudnikweb.hypernova.ui.stats

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The last 30 days' bar chart without drawing (ui/stats/DayBars.kt): scale, heights, slots, touches, labels. */
class DayBarsTest {
    private val hour = 3_600_000L
    private val minute = 60_000L

    @Test fun theScaleIsARoundFigureAtOrOverTheBusiestDay() {
        assertEquals(1, DayBars.scaleHours(0L))
        assertEquals(1, DayBars.scaleHours(-5L))
        assertEquals(1, DayBars.scaleHours(17 * minute))
        assertEquals(1, DayBars.scaleHours(hour))
        assertEquals(2, DayBars.scaleHours(hour + 1))
        assertEquals(8, DayBars.scaleHours(7 * hour + 30 * minute))
        assertEquals(10, DayBars.scaleHours(9 * hour + 17 * minute))
        assertEquals(24, DayBars.scaleHours(21 * hour))
        // Sessions side by side add up past a day's 24 hours: then tens.
        assertEquals(30, DayBars.scaleHours(25 * hour))
        assertEquals(40, DayBars.scaleHours(31 * hour))
    }

    @Test fun barsAreTheirHoursOverTheScale() {
        val h = DayBars.heights(listOf(0L, 5 * hour, 10 * hour, 2 * hour + 30 * minute))
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 0.25f), h, 1e-6f)
        // The busiest day under a round figure doesn't reach the top.
        assertArrayEquals(floatArrayOf(0.9f), DayBars.heights(listOf(9 * hour)), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), DayBars.heights(listOf(0L, 0L)), 0f)
        assertEquals(0, DayBars.heights(emptyList()).size)
    }

    @Test fun aTouchPicksTheDayUnderIt() {
        // 30 slots of 10 px.
        assertEquals(0, DayBars.indexAt(0f, 300f, 30))
        assertEquals(0, DayBars.indexAt(9.9f, 300f, 30))
        assertEquals(1, DayBars.indexAt(10f, 300f, 30))
        assertEquals(15, DayBars.indexAt(155f, 300f, 30))
        assertEquals(29, DayBars.indexAt(299f, 300f, 30))
        // A drag past an end keeps the end's day.
        assertEquals(29, DayBars.indexAt(300f, 300f, 30))
        assertEquals(29, DayBars.indexAt(420f, 300f, 30))
        assertEquals(0, DayBars.indexAt(-0.5f, 300f, 30))
        assertEquals(0, DayBars.indexAt(-40f, 300f, 30))
        assertEquals(-1, DayBars.indexAt(10f, 300f, 0))
        assertEquals(-1, DayBars.indexAt(10f, 0f, 30))
    }

    @Test fun eachBarIsCentredInItsSlot() {
        val w = 300f
        val bw = DayBars.barWidth(w, 30)
        assertEquals(6.2f, bw, 1e-4f)
        for (i in 0 until 30) {
            val left = DayBars.barLeft(i, w, 30)
            assertEquals(DayBars.centre(i, w, 30), left + bw / 2f, 1e-4f)
            assertTrue(left >= i * 10f && left + bw <= (i + 1) * 10f)
        }
    }

    @Test fun theRangeLightsTheLatestDays() {
        // A week of 30 days: the last 7.
        assertEquals(0f, DayBars.lit(22, 30, 7f), 0f)
        assertEquals(1f, DayBars.lit(23, 30, 7f), 0f)
        assertEquals(1f, DayBars.lit(29, 30, 7f), 0f)
        // Today alone.
        assertEquals(0f, DayBars.lit(28, 30, 1f), 0f)
        assertEquals(1f, DayBars.lit(29, 30, 1f), 0f)
        // Between ranges the edge is part-way along one bar.
        assertEquals(0.5f, DayBars.lit(22, 30, 7.5f), 1e-6f)
        assertEquals(1f, DayBars.lit(0, 30, 30f), 0f)
    }

    @Test fun theBusiestDayIsTheLatestOfTheMost() {
        assertEquals(-1, DayBars.busiest(emptyList()))
        assertEquals(-1, DayBars.busiest(listOf(0L, 0L)))
        assertEquals(1, DayBars.busiest(listOf(hour, 3 * hour, 2 * hour)))
        assertEquals(2, DayBars.busiest(listOf(3 * hour, 0L, 3 * hour)))
    }

    @Test fun labelsAreTheFirstTheMiddleAndToday() {
        assertArrayEquals(intArrayOf(0, 14, 29), DayBars.labelDays(30))
        assertArrayEquals(intArrayOf(0, 1, 2), DayBars.labelDays(3))
        assertArrayEquals(intArrayOf(0, 1), DayBars.labelDays(2))
        assertArrayEquals(intArrayOf(0), DayBars.labelDays(1))
        assertEquals(0, DayBars.labelDays(0).size)
    }

    @Test fun theEndsHoldTheEdgesTheMiddleSitsUnderItsBar() {
        // 30 slots of 10 px; day 14's middle is at 145.
        val lefts = DayBars.labelLefts(30, 300f, floatArrayOf(40f, 40f, 44f), 8f)
        assertArrayEquals(floatArrayOf(0f, 125f, 256f), lefts, 1e-4f)
    }

    @Test fun aCrowdedMiddleLabelGivesWay() {
        // Large fonts on a narrow card: the middle would touch the first.
        val lefts = DayBars.labelLefts(30, 120f, floatArrayOf(50f, 50f, 40f), 8f)
        assertEquals(0f, lefts[0], 0f)
        assertTrue(lefts[1].isNaN())
        assertEquals(80f, lefts[2], 0f)
    }

    @Test fun oneDayIsTodayAloneUnderItsBar() {
        assertArrayEquals(floatArrayOf(125f), DayBars.labelLefts(1, 300f, floatArrayOf(50f), 8f), 0f)
    }
}
