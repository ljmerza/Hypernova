package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How often the Local route is looked for again while on the remote one (data/LanRoute.kt). */
class RouteRecheckTest {
    @Test fun backsOffFromTwoMinutesToHalfAnHour() {
        val minutes = (0..7).map { RoutePolicy.recheckDelayMs(it) / 60_000L }
        assertEquals(listOf(2L, 4L, 8L, 16L, 30L, 30L, 30L, 30L), minutes)
        assertEquals(RoutePolicy.RECHECK_MAX_MS, RoutePolicy.recheckDelayMs(1_000))
        assertEquals(RoutePolicy.RECHECK_MS, RoutePolicy.recheckDelayMs(-1))
    }

    @Test fun mobileDataAloneIsNeverProbed() {
        assertFalse(RoutePolicy.shouldProbe(NetKind.Mobile, hotspot = false))
        assertFalse(RoutePolicy.shouldProbe(NetKind.None, hotspot = false))
        assertTrue(RoutePolicy.shouldProbe(NetKind.Wifi, hotspot = false))
        assertTrue(RoutePolicy.shouldProbe(NetKind.Mobile, hotspot = true))
        val c = RouteChooser("https://mac.example.ts.net", 47323)
        assertFalse(c.wantsRecheck(NetKind.Mobile, hotspot = false, known = listOf("192.168.1.20"), hotspotGateways = emptyList()))
    }

    @Test fun aHotspotAppearingStartsTheBackOffOver() {
        val min = 60_000L
        val b = RecheckBackoff(0)
        val net = Any()
        assertFalse(b.due(2 * min - 1, emptyList(), net))
        assertTrue(b.due(2 * min, emptyList(), net))   // next in 4
        assertFalse(b.due(4 * min, emptyList(), net))
        assertTrue(b.due(6 * min, emptyList(), net))   // next in 8
        assertFalse(b.due(8 * min, emptyList(), net))
        // The gateway shows up with the same default network: due now, and 2 minutes after that.
        assertTrue(b.due(8 * min + 1, listOf("192.168.43.1"), net))
        assertFalse(b.due(9 * min, listOf("192.168.43.1"), net))
        assertTrue(b.due(10 * min + 1, listOf("192.168.43.1"), net))
        // Another Wi-Fi network does the same.
        assertTrue(b.due(10 * min + 2, listOf("192.168.43.1"), Any()))
    }

    @Test fun mobileDataWithoutAHotspotNeverSweepsWhateverIsDue() {
        val b = RecheckBackoff(0)
        val c = RouteChooser("https://mac.example.ts.net", 47323)
        assertTrue(b.due(10 * 60_000L, emptyList(), null))
        assertFalse(c.wantsRecheck(NetKind.Mobile, hotspot = false, known = listOf("192.168.1.20"), hotspotGateways = emptyList()))
    }
}
