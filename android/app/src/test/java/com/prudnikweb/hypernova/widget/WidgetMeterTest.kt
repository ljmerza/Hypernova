package com.prudnikweb.hypernova.widget

import com.prudnikweb.hypernova.data.UsageProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widgets' projection meters (widget/WidgetMeter.kt and WidgetModel.UsageView): what a row
 * shows in its room, the bar's levels, and the pace and the limit's time carried in the model.
 */
class WidgetMeterTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun view(used: Float, pace: Float?, limitAt: Long = 0L, resetIn: Long = 121 * min) = WidgetModel.UsageView(
        fiveHour = used, sevenDay = 0.18f, fiveResetAt = now + resetIn, sevenResetAt = now + 61 * 60 * min,
        known = true, limited = false, weekLimited = false, fivePace = pace, fiveLimitAt = limitAt,
    )

    @Test fun theTagGoesFirstThenTheBarNeverThePercentage() {
        // A square sun widget, 130dp: "62%" (23dp), "16:40" after its glyph (~45dp).
        assertEquals(WidgetMeter.Fit.All, WidgetMeter.fit(130f, 23f, 45f))
        // The least room for all three: the bar's 24dp and two gaps.
        assertEquals(WidgetMeter.Fit.All, WidgetMeter.fit(23f + 7f + 24f + 7f + 45f, 23f, 45f))
        assertEquals(WidgetMeter.Fit.NoTag, WidgetMeter.fit(23f + 7f + 24f + 7f + 44f, 23f, 45f))
        assertEquals(WidgetMeter.Fit.NoTag, WidgetMeter.fit(23f + 7f + 24f, 23f, 45f))
        assertEquals(WidgetMeter.Fit.Percent, WidgetMeter.fit(23f + 7f + 23f, 23f, 45f))
        assertEquals(WidgetMeter.Fit.Percent, WidgetMeter.fit(10f, 23f, 45f))
    }

    @Test fun atRestTheFaintFillFoldsIntoTheUsedPart() {
        assertEquals(420 to 420, WidgetMeter.levels(UsageProjection.of(0.42f, now + 134 * min, now, perMin = null)))
    }

    @Test fun aPaceRunsTheFaintFillOnAndTheLimitFirstRunsItToTheEnd() {
        // 42% + 0.15%/min × 121 min = 60.15% by the reset.
        val slow = view(0.42f, 0.15f).five(now, live = true)
        assertEquals(420 to 602, WidgetMeter.levels(slow))
        assertFalse(slow.limitFirst)
        assertEquals(0L, WidgetMeter.limitAt(slow, now))
        // 62% at 0.5%/min: full in 76 minutes, 45 before the reset.
        val fast = view(0.62f, 0.5f).five(now, live = true)
        assertEquals(620 to 1000, WidgetMeter.levels(fast))
        assertTrue(fast.limitFirst)
        assertEquals(now + 76 * min, WidgetMeter.limitAt(fast, now))
    }

    @Test fun thePaceCountsOnlyOnALivePicture() {
        // A stale picture can't say whether anything still works: a plain meter, the reset its tag.
        val stale = view(0.62f, 0.5f, limitAt = now + 76 * min).five(now, live = false)
        assertNull(stale.projected)
        assertFalse(stale.limitFirst)
        assertEquals(UsageProjection.Tag.Reset(121 * min), stale.tag)
    }

    @Test fun theLimitsTimeKeptInTheModelIsTheOneDrawn() {
        // The Mac's own time to the limit (here 70 minutes, a little sooner than the pace alone says).
        val p = view(0.62f, 0.5f, limitAt = now + 70 * min).five(now, live = true)
        assertEquals(UsageProjection.Tag.Limit(70 * min), p.tag)
        // Once that moment has passed, the pace alone decides again.
        val later = view(0.62f, 0.5f, limitAt = now - min).five(now, live = true)
        assertEquals(UsageProjection.Tag.Limit(76 * min), later.tag)
    }

    @Test fun theLimitsTimeIsAWholeMinuteAndThePaceAHundredth() {
        val p = UsageProjection.of(0.62f, now + 121 * min, now, perMin = 0.5f, etaMs = 75 * min + 40_000L)
        assertEquals(now + 76 * min, WidgetMeter.limitAt(p, now))
        assertEquals(0.5f, WidgetMeter.pace(UsageProjection.of(0.4f, now + 100 * min, now, perMin = 0.5031f))!!, 1e-6f)
        assertNull(WidgetMeter.pace(UsageProjection.of(0.4f, now + 100 * min, now, perMin = null)))
    }

    @Test fun aResetThatHasPassedTakesThePaceWithIt() {
        val v = view(0.62f, 0.5f, limitAt = now + 76 * min, resetIn = -min).at(now)
        assertNull(v.fivePace)
        assertEquals(0L, v.fiveLimitAt)
        assertEquals(0f, v.fiveHour, 0f)
    }
}
