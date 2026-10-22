package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageRateTest {
    @Test fun paceReadsInTenthsOrHundredths() {
        assertEquals("0.8", ratePerMin(0.8f))
        assertEquals("0.05", ratePerMin(0.05f))
        assertEquals("1.2", ratePerMin(1.2f))
    }

    @Test fun noPaceIsNoText() {
        assertNull(ratePerMin(null))
        assertNull(ratePerMin(0f))
    }
}
