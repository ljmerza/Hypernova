package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The last 30 days' sums (data/DayChart.kt): the behaviour docs/DESIGN.md asks for, with nothing drawn. */
class DayChartTest {
    private val h = 3_600_000L
    private val today = LocalDate.of(2026, 10, 31)

    private fun chart(range: StatsRange = StatsRange.Month, last: LocalDate = today, n: Int = 30, ms: (Int) -> Long = { 0L }) =
        DayChart((0 until n).map { i -> StatDay(last.minusDays(n - 1L - i), ms(i), 0L, 0L, 0, 0) }, range)

    private fun oneBig(hours: Double) = chart(ms = { i -> if (i == 3) (hours * h).toLong() else 0L })

    @Test fun theScaleIsARoundFigureAtOrOverTheBusiestDay() {
        for ((hours, top) in listOf(0.0 to 1, 0.5 to 1, 1.0 to 1, 1.2 to 2, 5.0 to 5, 5.4 to 6, 6.1 to 8, 8.5 to 10, 11.0 to 12, 13.0 to 16, 17.0 to 20, 20.5 to 24, 24.0 to 24, 24.5 to 30, 31.0 to 40, 90.0 to 90)) {
            assertEquals("$hours", top, oneBig(hours).topHours)
        }
    }

    @Test fun barsAreTheirHoursOverTheScale() {
        val c = chart(ms = { i -> if (i == 0) 10 * h else if (i == 1) 5 * h else 0L })
        assertEquals(10, c.topHours)
        assertEquals(1f, c.height(0), 0.001f)
        assertEquals(0.5f, c.height(1), 0.001f)
        assertEquals(0f, c.height(2), 0.001f)
    }

    @Test fun aTouchPicksTheDayUnderIt() {
        val c = chart()
        assertEquals(0, c.dayAt(0f))
        assertEquals(0, c.dayAt(0.03f))
        assertEquals(1, c.dayAt(0.04f))
        assertEquals(15, c.dayAt(0.5f))
        assertEquals(29, c.dayAt(0.999f))
        // Dragged past either end, it stays on that end's day.
        assertEquals(0, c.dayAt(-0.4f))
        assertEquals(29, c.dayAt(1.4f))
    }

    @Test fun eachBarIsCentredInItsSlot() {
        val c = chart()
        assertEquals(0.5f / 30f, c.centre(0), 0.0001f)
        assertEquals(29.5f / 30f, c.centre(29), 0.0001f)
        for (i in 0 until 30) assertEquals(i, c.dayAt(c.centre(i)))
    }

    @Test fun theRangeLightsTheLatestDays() {
        fun lit(r: StatsRange) = chart(r).let { c -> (0 until 30).filter(c::inRange) }
        assertEquals(listOf(29), lit(StatsRange.Today))
        assertEquals((23..29).toList(), lit(StatsRange.Week))
        assertEquals((0..29).toList(), lit(StatsRange.Month))
        assertEquals((0..29).toList(), lit(StatsRange.All))
    }

    @Test fun ytdLightsOnlyThisYearsDays() {
        val c = chart(StatsRange.Ytd, last = LocalDate.of(2027, 1, 10))
        assertEquals((20..29).toList(), (0 until 30).filter(c::inRange))
        assertEquals((0..29).toList(), chart(StatsRange.Ytd).let { y -> (0 until 30).filter(y::inRange) })
    }

    @Test fun theBusiestDayIsTheLatestOfTheMost() {
        val c = chart(ms = { i -> if (i == 4 || i == 9) 3 * h else h })
        assertEquals(today.minusDays(20), c.busiest?.day)
        assertNull(chart().busiest)
    }

    @Test fun onlyDaysWithHoursCountAsWork() {
        // A day only the older summary knows has sessions and no hours: no bar, not a day with work.
        val days = listOf(StatDay(today.minusDays(1), 2 * h, 0L, 0L, 1, 1), StatDay(today, 0L, 0L, 0L, 3, 0))
        val c = DayChart(days, StatsRange.Week)
        assertEquals(1, c.daysWithWork)
        assertEquals(0f, c.height(1), 0.001f)
    }

    @Test fun theEndsHoldTheEdgesTheMiddleSitsUnderItsBar() {
        val c = chart()
        val a = c.axis(firstW = 0.1f, middleW = 0.1f, todayW = 0.12f, gap = 0.02f)
        assertEquals(0f, a.first)
        assertEquals(0.88f, a.today, 0.0001f)
        assertEquals(c.centre(14) - 0.05f, a.middle!!, 0.0001f)
    }

    @Test fun labelsAreTheFirstTheMiddleAndToday() {
        assertEquals(today.minusDays(29), chart().days.first().day)
        // On 30 days ending the 31st: the 16th, as a person would say the middle of the chart.
        assertEquals(LocalDate.of(2026, 10, 16), chart().let { it.days[it.middleDay].day })
    }

    @Test fun aCrowdedMiddleLabelGivesWay() {
        // A narrow window, or a large font: each label takes much of the width.
        val a = chart().axis(firstW = 0.4f, middleW = 0.3f, todayW = 0.3f, gap = 0.02f)
        assertNull(a.middle)
        assertEquals(0f, a.first)
        assertEquals(0.7f, a.today, 0.0001f)
    }

    @Test fun oneDayIsTodayAloneUnderItsBar() {
        val a = chart(StatsRange.Today, n = 1).axis(firstW = 0.2f, middleW = 0.2f, todayW = 0.2f, gap = 0.02f)
        assertNull(a.first)
        assertNull(a.middle)
        assertEquals(0.4f, a.today, 0.0001f)
    }

    @Test fun noDaysAtAllDrawNothingAndAskNothing() {
        val c = DayChart(emptyList(), StatsRange.Week)
        assertEquals(0, c.daysWithWork)
        assertEquals(1, c.topHours)
        assertFalse(c.inRange(0))
        assertTrue(c.busiest == null)
    }
}
