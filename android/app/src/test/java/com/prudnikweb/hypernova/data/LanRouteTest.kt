package com.prudnikweb.hypernova.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The local network route (docs/PROTOCOL.md, "Local network (direct HTTPS)"): the pin, the choice
 * between the Mac's own address and the base URL, and what the snapshot says about the listener.
 */
class LanRouteTest {
    /** A self-signed P-256 certificate, valid 2026-10-16 to 2036-10-13. */
    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIIBITCByAIJANjgoHu8vVB9MAoGCCqGSM49BAMCMBkxFzAVBgNVBAMMDmh5cGVy
        bm92YS10ZXN0MB4XDTI2MTAxNjIzMzUyMVoXDTM2MTAxMzIzMzUyMVowGTEXMBUG
        A1UEAwwOaHlwZXJub3ZhLXRlc3QwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAARL
        wPHCTrc2ddjaIJVl0MApDo2Bo7X3trLMoMX4FT8aYcXBInQO6g9F30nfLI29FuMC
        QPAmcDofGgADLf+vslUTMAoGCCqGSM49BAMCA0gAMEUCIQDdeKwT4KrfIgLTFEv2
        4sjdNImqJ9VKbtVlt0i+bxCSCAIgTnf+C6gaLV0HBrLxr4acRsPNELzzjWN8GjNa
        njxGiqo=
        -----END CERTIFICATE-----
    """.trimIndent()
    private val cert = CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate
    private val fp = "14aa650b9381cf8edbde5b00003197004a720ad4b7d972b07e5775029879da6c"
    /** 2027-01-01, inside the certificate's dates. */
    private val now = 1_798_761_600_000L

    @Test fun pinAcceptsTheRightCertificate() {
        assertEquals(fp, Pin.fingerprint(cert))
        assertEquals(PinResult.Ok, Pin.check(arrayOf(cert), fp, now))
        // Case doesn't matter: the daemon sends lowercase, a link may not.
        assertEquals(PinResult.Ok, Pin.check(arrayOf(cert), fp.uppercase(), now))
        Pin.trustManager(fp) { now }.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
    }

    @Test fun pinRefusesAWrongCertificate() {
        val other = fp.replaceRange(0, 2, "00")
        assertEquals(PinResult.Wrong, Pin.check(arrayOf(cert), other, now))
        assertEquals(PinResult.Missing, Pin.check(emptyArray(), fp, now))
        assertEquals(PinResult.Missing, Pin.check(null, fp, now))
        try {
            Pin.trustManager(other) { now }.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
            throw AssertionError("a wrong certificate got through")
        } catch (_: CertificateException) {}
    }

    @Test fun pinRefusesAnExpiredCertificate() {
        val after = cert.notAfter.time + 1
        assertEquals(PinResult.Expired, Pin.check(arrayOf(cert), fp, after))
        // Not yet valid counts the same.
        assertEquals(PinResult.Expired, Pin.check(arrayOf(cert), fp, cert.notBefore.time - 1))
        try {
            Pin.trustManager(fp) { after }.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
            throw AssertionError("an expired certificate got through")
        } catch (_: CertificateException) {}
    }

    @Test fun probesOnWifiOrAHotspotNotOnMobile() {
        assertTrue(RoutePolicy.shouldProbe(NetKind.Wifi, hotspot = false))
        assertTrue(RoutePolicy.shouldProbe(NetKind.Mobile, hotspot = true))
        assertFalse(RoutePolicy.shouldProbe(NetKind.Mobile, hotspot = false))
        assertFalse(RoutePolicy.shouldProbe(NetKind.None, hotspot = false))
    }

    @Test fun candidatesAreTheKnownAddressesThenTheHotspotSubnet() {
        val c = RoutePolicy.candidates(listOf("192.168.1.20", " 192.168.1.20", "fd7a:115c::1"), listOf("192.168.43.1"))
        assertEquals("192.168.1.20", c[0])
        assertEquals("fd7a:115c::1", c[1])
        assertEquals("192.168.43.2", c[2])
        assertEquals(2 + 253, c.size)
        assertFalse("192.168.43.1" in c)
        // Two hotspots and the known ones still fit the cap.
        assertEquals(RoutePolicy.MAX_CANDIDATES, RoutePolicy.candidates(emptyList(), listOf("192.168.43.1", "192.168.44.1")).size)
        assertEquals(emptyList<String>(), RoutePolicy.candidates(emptyList(), listOf("not-an-address")))
        assertEquals("https://192.168.1.20:47323", RoutePolicy.baseOf("192.168.1.20", 47323))
        assertEquals("https://[fd7a:115c::1]:47323", RoutePolicy.baseOf("fd7a:115c::1%wlan0", 47323))
    }

    @Test fun theFirstAddressToAnswerWins() = runTest {
        val winner = RoutePolicy.choose(listOf("10.0.0.2", "10.0.0.3", "10.0.0.4")) { a ->
            when (a) {
                "10.0.0.2" -> { delay(300); true }
                "10.0.0.3" -> { delay(50); true }
                else -> { delay(10); false }
            }
        }
        assertEquals("10.0.0.3", winner)
    }

    @Test fun noAnswerMeansTheRemoteRoute() = runTest {
        assertNull(RoutePolicy.choose(listOf("10.0.0.2", "10.0.0.3")) { false })
        assertNull(RoutePolicy.choose(listOf("10.0.0.2")) { throw IllegalStateException("handshake failed") })
        assertNull(RoutePolicy.choose(emptyList()) { true })
        // One that never answers doesn't hold the rest up past the timeout.
        assertNull(RoutePolicy.choose(listOf("10.0.0.2"), timeoutMs = 100) { delay(10_000); true })
    }

    @Test fun theChooserHoldsTheRouteAndKnowsWhenToLookAgain() {
        val c = RouteChooser("https://mac.ts.net:47321", 47323)
        assertEquals(RouteKind.Remote, c.kind)
        assertEquals("https://mac.ts.net:47321", c.base)
        // On mobile data with nothing to try, no re-check.
        assertFalse(c.wantsRecheck(NetKind.Mobile, false, listOf("192.168.1.20"), emptyList()))
        // On Wi-Fi with an address to try, yes, every 2 minutes while remote.
        assertTrue(c.wantsRecheck(NetKind.Wifi, false, listOf("192.168.1.20"), emptyList()))
        assertFalse(c.wantsRecheck(NetKind.Wifi, false, emptyList(), emptyList()))
        assertEquals(120_000L, RoutePolicy.RECHECK_MS)
        // A probe finds the Mac: local, and no re-check while it holds.
        c.settle("192.168.1.20")
        assertEquals(RouteKind.Local, c.kind)
        assertEquals("https://192.168.1.20:47323", c.base)
        assertFalse(c.wantsRecheck(NetKind.Wifi, false, listOf("192.168.1.20"), emptyList()))
        // On the port the Mac answered on (its snapshot's), not the pairing link's: a pairing made
        // with the local listener off has only the default.
        // A route that fails in use is looked for again within seconds, later each time it fails again.
        assertEquals(8_000L, RoutePolicy.failedRecheckMs(0))
        assertEquals(16_000L, RoutePolicy.failedRecheckMs(1))
        assertEquals(RoutePolicy.RECHECK_MS, RoutePolicy.failedRecheckMs(10))
        val moved = RouteChooser("https://mac.ts.net:47321", 47323)
        moved.settle("192.168.1.20", lanPort = 48323)
        assertEquals("https://192.168.1.20:48323", moved.base)
        // The local route fails in use: back to the base URL, and looking again.
        c.localFailed()
        assertEquals(RouteKind.Remote, c.kind)
        assertEquals("https://mac.ts.net:47321", c.base)
        assertTrue(c.wantsRecheck(NetKind.Wifi, false, listOf("192.168.1.20"), emptyList()))
        // A probe with no winner: remote.
        c.settle("192.168.1.20")
        c.settle(null)
        assertEquals(RouteKind.Remote, c.kind)
    }

    // ─── The hotspot sweep ──────────────────────────────────────────────────

    @Test fun theLastAddressToAnswerGoesFirstThenTheKnownThenTheSweep() {
        val c = RouteChooser("https://mac.ts.net:47321", 47323)
        assertEquals(listOf("192.168.1.20"), c.known(listOf("192.168.1.20")))
        // The Mac answered on the hotspot at an address it hadn't reported, then the route was lost.
        c.settle("192.168.43.77")
        c.localFailed()
        val order = RoutePolicy.candidates(c.known(listOf("192.168.1.20")), listOf("192.168.43.1"))
        assertEquals(listOf("192.168.43.77", "192.168.1.20", "192.168.43.2", "192.168.43.3"), order.take(4))
        // Once each, the gateway never.
        assertEquals(order.size, order.toSet().size)
        assertEquals(2 + 252, order.size)
        assertFalse("192.168.43.1" in order)
        // Remembered through a round with no winner, and worth a re-check on its own.
        c.settle(null)
        assertEquals("192.168.43.77", c.known(emptyList()).first())
        assertTrue(c.wantsRecheck(NetKind.Mobile, true, emptyList(), emptyList()))
    }

    @Test fun probesStartInTheCandidatesOrderAFewAtATime() = runTest {
        val started = mutableListOf<String>()
        var running = 0
        var most = 0
        val candidates = (1..10).map { "10.0.0.$it" }
        val winner = RoutePolicy.choose(candidates, timeoutMs = 1_000, parallel = 3) { a ->
            started += a; running++; most = maxOf(most, running)
            try { delay(100); a == "10.0.0.10" } finally { running-- }
        }
        assertEquals("10.0.0.10", winner)
        assertEquals(candidates, started)
        assertEquals(3, most)
    }

    @Test fun aWholeSlash24GetsProbedWithinTheRound() = runTest {
        // Known addresses first (gone), then the hotspot's /24: the Mac is the very last host.
        val candidates = RoutePolicy.candidates(listOf("192.168.1.20", "10.0.0.5"), listOf("192.168.43.1"))
        assertEquals("192.168.43.254", candidates.last())
        // Nobody else answers: each takes the sweep's whole connect timeout to fail.
        val winner = RoutePolicy.choose(candidates) { a -> if (a == "192.168.43.254") { delay(30); true } else { delay(RoutePolicy.SWEEP_CONNECT_MS); false } }
        assertEquals("192.168.43.254", winner)
        // Two waves of 128, the second starting as the first times out.
        assertTrue("found after ${currentTime} ms", currentTime < 2 * RoutePolicy.SWEEP_CONNECT_MS)
    }

    @Test fun evenProbesThatHangTheirWholeTimeLeaveTheLastHostItsTurn() = runTest {
        val candidates = RoutePolicy.candidates(emptyList(), listOf("192.168.43.1"))
        val winner = RoutePolicy.choose(candidates) { a -> if (a == "192.168.43.254") { delay(30); true } else { delay(60_000); true } }
        assertEquals("192.168.43.254", winner)
    }

    @Test fun theSweepHasItsOwnDispatcherAndAShortConnectTimeout() {
        val pinned = PinnedTls.client(fp, identity = null)
        val sweep = PinnedTls.sweep(pinned)
        assertNotSame(pinned.dispatcher, sweep.dispatcher)
        assertTrue(sweep.dispatcher.maxRequests >= RoutePolicy.PARALLEL)
        assertTrue(sweep.dispatcher.maxRequestsPerHost >= 1)
        assertEquals(RoutePolicy.SWEEP_CONNECT_MS.toInt(), sweep.connectTimeoutMillis)
        // The same pin.
        assertSame(pinned.sslSocketFactory, sweep.sslSocketFactory)
        assertSame(pinned.x509TrustManager, sweep.x509TrustManager)
    }

    // ─── Pages in the app's WebView ─────────────────────────────────────────

    private val identity by lazy {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        DeviceIdentity(arrayOf(cert), kp.private)
    }

    @Test fun theWebViewGoesOnOnlyForThePinnedCertificate() {
        val page = LocalPage("https://192.168.1.20:47323", "https://mac.ts.net:47321", fp, identity)
        assertTrue(page.trusts(cert, now))
        // Another certificate, an expired one, or none: refused.
        assertFalse(LocalPage(page.base, page.remoteBase, fp.replaceRange(0, 2, "00"), identity).trusts(cert, now))
        assertFalse(page.trusts(cert, cert.notAfter.time + 1))
        assertFalse(page.trusts(null, now))
    }

    @Test fun theDeviceCertificateGoesToTheListenerAlone() {
        val page = LocalPage("https://192.168.1.20:47323", "https://mac.ts.net:47321", fp, identity)
        assertSame(identity, page.identityFor("192.168.1.20", 47323))
        assertNull(page.identityFor("192.168.1.21", 47323))
        assertNull(page.identityFor("192.168.1.20", 443))
        assertNull(page.identityFor("example.com", 47323))
        assertNull(page.identityFor(null, 47323))
        // IPv6, with or without brackets.
        val v6 = LocalPage("https://[fd12:3456::20]:47323", "https://mac.ts.net:47321", fp, identity)
        assertSame(identity, v6.identityFor("fd12:3456::20", 47323))
        assertSame(identity, v6.identityFor("[FD12:3456::20]", 47323))
        // No certificate yet: nothing to present.
        assertNull(LocalPage(page.base, page.remoteBase, fp, null).identityFor("192.168.1.20", 47323))
    }

    @Test fun pagesOpenOnTheActiveRouteAndOutsideByTheBaseUrl() {
        val lan = "https://192.168.1.20:47323"
        val ts = "https://mac.tailnet.ts.net:47321"
        // A preview the Mac built from either origin opens on the active one, path and query intact.
        assertEquals("$lan/p/AbC_9/index%20page.html?x=1#top", RoutePolicy.rebase("$ts/p/AbC_9/index%20page.html?x=1#top", lan))
        assertEquals("$lan/qa?k=0123", RoutePolicy.rebase("$lan/qa?k=0123", "$lan/"))
        assertEquals("$ts/qa?k=0123", RoutePolicy.rebase("$lan/qa?k=0123", ts))
        assertEquals("$lan/p/t/", RoutePolicy.rebase("/p/t/", lan))
        assertEquals("$lan/", RoutePolicy.rebase("https://192.168.1.20:47323", lan))
        // A browser outside the app gets the base URL; anything not on the listener is left alone.
        val page = LocalPage(lan, ts, fp, identity)
        assertEquals("$ts/p/t/a.html?q=1", page.outside("$lan/p/t/a.html?q=1"))
        assertEquals("https://example.com/x", page.outside("https://example.com/x"))
        assertEquals("$ts/p/t/", page.outside("$ts/p/t/"))
        assertTrue(RoutePolicy.sameOrigin("https://192.168.1.20:47323/p/x", "https://192.168.1.20:47323"))
        assertFalse(RoutePolicy.sameOrigin("https://192.168.1.20:47324/p/x", "https://192.168.1.20:47323"))
        assertFalse(RoutePolicy.sameOrigin("http://192.168.1.20:47323/", "https://192.168.1.20:47323"))
        assertTrue(RoutePolicy.sameOrigin("https://mac.ts.net/x", "https://MAC.ts.net:443"))
        // The terminal's ticket is relative: its socket goes by the base URL (LiveHub.terminal).
        assertEquals("$ts/v1/terms/t1/ws?ticket=k", RoutePolicy.rebase("/v1/terms/t1/ws?ticket=k", ts))
    }

    @Test fun readsTheListenerFromTheSnapshot() {
        val lan = LanInfo.parse(org.json.JSONObject("""{"port":47323,"addresses":["192.168.1.20","fd7a::1"],"fingerprint":"${fp.uppercase()}","deviceCert":false}"""))!!
        assertEquals(47323, lan.port)
        assertEquals(listOf("192.168.1.20", "fd7a::1"), lan.addresses)
        assertEquals(fp, lan.fingerprint)
        assertFalse(lan.deviceCert)
        assertNull(LanInfo.parse(null))
        // Without a fingerprint there is nothing to pin: no listener.
        assertNull(LanInfo.parse(org.json.JSONObject("""{"port":47323,"addresses":["192.168.1.20"]}""")))
    }
}
