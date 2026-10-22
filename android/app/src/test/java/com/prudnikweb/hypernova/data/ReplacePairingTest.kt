package com.prudnikweb.hypernova.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scanning a code while paired: the same computer again, or another one to confirm (audit P12). */
class ReplacePairingTest {
    private val current = Pairing(baseUrl = "https://macbook.tail.ts.net", token = "t", macName = "MacBook Air", macId = "m1", deviceId = "d1", tailnetName = "macbook")

    private fun link(name: String, tailnet: String? = null) = PairLink(baseUrl = "https://x.tail.ts.net", code = "c", macName = name, tailnetName = tailnet)

    @Test fun theSameComputerAgainIsNoReplacement() {
        assertFalse(replacesAnother(current, link("MacBook Air")))
        assertFalse(replacesAnother(current, link("macbook air", tailnet = "macbook")))
    }

    @Test fun anotherComputerIs() {
        assertTrue(replacesAnother(current, link("vps-hel1")))
        // Two Macs both called "MacBook Air" still differ on the tailnet.
        assertTrue(replacesAnother(current, link("MacBook Air", tailnet = "macbook-2")))
    }
}
