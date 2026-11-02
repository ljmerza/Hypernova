package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryTest {
    @Test fun firstTryAgainAtOnceThenEveryThirtySeconds() {
        assertEquals(0L, Retry.wait(1, 0.95))
        for (n in 2..20) {
            val w = Retry.wait(n, 0.95)
            assertTrue("attempt $n: $w", w in 27_000L..30_000L)
        }
    }

    @Test fun theSpreadNeverGoesPastThirtySeconds() {
        assertEquals(30_000L, Retry.wait(5, 1.0))
        assertEquals(27_000L, Retry.wait(5, 0.9))
        assertEquals(30_000L, Retry.wait(5, 3.0))
    }
}
