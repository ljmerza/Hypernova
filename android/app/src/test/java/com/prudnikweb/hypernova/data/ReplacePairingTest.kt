package com.prudnikweb.hypernova.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scanning a code while paired: the same computer again, or another one to confirm (audit P12). */
class ReplacePairingTest {
    private val current = Pairing(baseUrl = "http://10.0.2.2:47321", token = "t", macName = "MacBook Air", macId = "m1", deviceId = "d1")

    private fun link(name: String) = PairLink(baseUrl = "http://10.0.2.2:47321", code = "c", macName = name)

    @Test fun theSameComputerAgainIsNoReplacement() {
        assertFalse(replacesAnother(current, link("MacBook Air")))
        assertFalse(replacesAnother(current, link("macbook air")))
    }

    @Test fun anotherComputerIs() {
        assertTrue(replacesAnother(current, link("vps-hel1")))
    }
}
