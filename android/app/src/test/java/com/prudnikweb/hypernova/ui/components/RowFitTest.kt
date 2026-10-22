package com.prudnikweb.hypernova.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * What a row of drawn parts shows in the room it has (ui/components/FitRow.kt): everything at its
 * widest, then the steps of its give-way order until it fits.
 */
class RowFitTest {
    // The chat's strip: the model's chip (whole, its family), the cost, the tokens.
    private val strip = listOf(intArrayOf(70, 42), intArrayOf(46), intArrayOf(39))
    private val stripOrder = intArrayOf(0, 2, 0, 1)

    @Test fun withRoomEverythingShowsAtItsWidest() {
        // 70 + 46 + 39 and two gaps of 8.
        assertArrayEquals(intArrayOf(0, 0, 0), RowFit.choose(strip, 8, 171, stripOrder))
        assertArrayEquals(intArrayOf(0, 0, 0), RowFit.choose(strip, 8, 400, stripOrder))
    }

    @Test fun theChipShortensFirstThenTheTokensGoThenTheChipTheCostLast() {
        // 42 + 46 + 39 and two gaps.
        assertArrayEquals(intArrayOf(1, 0, 0), RowFit.choose(strip, 8, 170, stripOrder))
        assertArrayEquals(intArrayOf(1, 0, 0), RowFit.choose(strip, 8, 143, stripOrder))
        assertArrayEquals(intArrayOf(1, 0, -1), RowFit.choose(strip, 8, 142, stripOrder))
        assertArrayEquals(intArrayOf(1, 0, -1), RowFit.choose(strip, 8, 96, stripOrder))
        assertArrayEquals(intArrayOf(-1, 0, -1), RowFit.choose(strip, 8, 95, stripOrder))
        assertArrayEquals(intArrayOf(-1, 0, -1), RowFit.choose(strip, 8, 46, stripOrder))
        assertArrayEquals(intArrayOf(-1, -1, -1), RowFit.choose(strip, 8, 45, stripOrder))
    }

    @Test fun aPartWithNothingToShowTakesNoRoomAndNoGap() {
        // No cost yet (a rewind's new session): the chip and the tokens, one gap.
        val noCost = listOf(intArrayOf(70, 42), intArrayOf(), intArrayOf(39))
        assertArrayEquals(intArrayOf(0, -1, 0), RowFit.choose(noCost, 8, 117, stripOrder))
        assertArrayEquals(intArrayOf(1, -1, 0), RowFit.choose(noCost, 8, 116, stripOrder))
        assertArrayEquals(intArrayOf(1, -1, -1), RowFit.choose(noCost, 8, 88, stripOrder))
    }

    @Test fun aStepNamingAPartAlreadyOutDoesNothing() {
        // The card's footer: the model's chip, then the mode (glyph and words, the glyph alone).
        val footer = listOf(intArrayOf(44), intArrayOf(82, 12))
        val order = intArrayOf(1, 0, 1, 1, 0)
        assertArrayEquals(intArrayOf(0, 0), RowFit.choose(footer, 8, 134, order))
        assertArrayEquals(intArrayOf(0, 1), RowFit.choose(footer, 8, 133, order))
        assertArrayEquals(intArrayOf(-1, 1), RowFit.choose(footer, 8, 63, order))
        assertArrayEquals(intArrayOf(-1, -1), RowFit.choose(footer, 8, 11, order))
        // Never back from out to its widest.
        assertArrayEquals(intArrayOf(-1, -1), RowFit.choose(footer, 8, 0, order))
    }
}
