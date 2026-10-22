package com.prudnikweb.hypernova.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** A widget's counts as the app's count marks: a point of light before each, a gap between, no "·". */
class WidgetCountsTest {
    @Test fun eachCountLeadsWithItsPointAndTheyStandAGapApart() {
        assertEquals("● 2 working  ● 1 needs you", WidgetCounts.plain(listOf("2 working", "1 needs you")))
        assertEquals("● 1 needs you", WidgetCounts.plain(listOf("1 needs you")))
        assertEquals("", WidgetCounts.plain(emptyList()))
    }

    @Test fun neverADot() {
        assertFalse('·' in WidgetCounts.plain(listOf("2 working", "1 needs you")))
    }
}
