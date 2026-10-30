package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

/**
 * Local first, always (data/LanRoute.kt): how a probe round settles, how a route that fails in use
 * is held back (that route only), how often Local is looked for again, and what the offline card
 * says about what was tried.
 */
class LocalFirstTest {
    private val L = RouteKind.Local
    private val R = RouteKind.Remote

    // ─── A round ────────────────────────────────────────────────────────────

    @Test fun localWinsTheMomentItAnswersWhateverElseIsStillBeingTried() {
        val j = RoundJudge(waitForLocal = true)
        assertEquals(L, j.settle(answered = setOf(L), pending = setOf(R)))
        assertEquals(L, j.settle(answered = setOf(L, R), pending = emptySet()))
        assertFalse(j.mayImprove(L, setOf(R)))
    }

    @Test fun inTheMacsSubnetTheBaseUrlWaitsForLocal() {
        // At home on Wi-Fi, the base URL answering (or not) must not decide before Local has.
        val j = RoundJudge(waitForLocal = true)
        assertNull(j.settle(answered = setOf(R), pending = setOf(L)))
        assertEquals(L, j.settle(answered = setOf(R, L), pending = emptySet()))
        // Local refused: the base URL it is.
        assertEquals(R, j.settle(answered = setOf(R), pending = emptySet()))
    }

    @Test fun elsewhereTheRoundTakesWhatAnswersAndMovesToLocalIfItAnswersLater() {
        val j = RoundJudge(waitForLocal = false)
        assertEquals(R, j.settle(answered = setOf(R), pending = setOf(L)))
        assertTrue(j.mayImprove(R, setOf(L)))
        assertTrue(j.upgrade(R, L))
        assertFalse(j.upgrade(L, R))
        assertFalse(j.upgrade(L, L))
        // Nothing yet: wait.
        assertNull(j.settle(answered = emptySet(), pending = setOf(L, R)))
    }

    @Test fun theBaseUrlFailingNeverHoldsLocalBack() {
        val c = RouteChooser("http://10.0.2.2:47321", 47323)
        val min = 60_000L
        // The base URL fails in use again and again.
        var t = 10 * min
        val holds = (1..4).map { c.failedInUse(R, t).also { t += 5_000 } }
        assertEquals(listOf(0L, 8_000L, 16_000L, 32_000L), holds)
        assertEquals(setOf(R), c.demoted(t - 5_000))
        // Local is untouched: tried first, and it wins as soon as it answers.
        val j = RoundJudge(waitForLocal = true, demoted = c.demoted(t - 5_000))
        assertEquals(L, j.settle(answered = setOf(L), pending = setOf(R)))
        // The held-back base URL answering doesn't settle a round while Local is still being tried…
        assertNull(j.settle(answered = setOf(R), pending = setOf(L)))
        // …and is still used when nothing else answers.
        assertEquals(R, j.settle(answered = setOf(R), pending = emptySet()))
    }

    @Test fun aRouteThatKeepsFailingIsTriedAfterTheOthersForAWhileThenFirstAgain() {
        val c = RouteChooser("http://10.0.2.2:47321", 47323)
        val t0 = 1_000_000L
        // One failure is a hiccup: tried first again at once.
        assertEquals(0L, c.failedInUse(L, t0))
        assertTrue(c.demoted(t0).isEmpty())
        // Again within minutes: 8 s after the others.
        assertEquals(8_000L, c.failedInUse(L, t0 + 30_000))
        assertEquals(setOf(L), c.demoted(t0 + 30_000))
        assertTrue(c.demoted(t0 + 38_000).isEmpty())
        // Up to 2 minutes.
        var t = t0 + 40_000
        repeat(10) { c.failedInUse(L, t); t += 1_000 }
        assertEquals(RoutePolicy.RECHECK_MS, c.failedInUse(L, t))
        // Quiet for longer than the window: a failure is a hiccup again.
        assertEquals(0L, c.failedInUse(L, t + RouteChooser.HEALTH_WINDOW_MS + 1))
        // Held back on purpose: demoted until then, not counted.
        c.hold(R, t + 60_000)
        assertTrue(R in c.demoted(t + 59_999))
        assertFalse(R in c.demoted(t + 60_000))
    }

    @Test fun aHeldBackLocalWaitsForTheBaseUrlButIsUsedWhenItIsAllThereIs() {
        val j = RoundJudge(waitForLocal = true, demoted = setOf(L))
        assertNull(j.settle(answered = setOf(L), pending = setOf(R)))
        assertEquals(R, j.settle(answered = setOf(L, R), pending = emptySet()))
        assertEquals(L, j.settle(answered = setOf(L), pending = emptySet()))
        assertFalse(j.upgrade(R, L))
    }

    // ─── Looking for Local again ────────────────────────────────────────────

    @Test fun inTheMacsSubnetLocalIsLookedForEvery30SecondsUpToTwoMinutes() {
        assertEquals(listOf(30L, 60L, 120L, 120L), (0..3).map { RoutePolicy.nearRecheckMs(it) / 1000 })
        val b = RecheckBackoff(0)
        val net = Any()
        val s = 1_000L
        // Arriving in the Mac's subnet: due at once, then after 30 s, 60 s, 2 minutes.
        assertTrue(b.due(30 * s, emptyList(), net, near = true))
        assertFalse(b.due(59 * s, emptyList(), net, near = true))
        assertTrue(b.due(60 * s, emptyList(), net, near = true))
        assertFalse(b.due(119 * s, emptyList(), net, near = true))
        assertTrue(b.due(120 * s, emptyList(), net, near = true))
        assertFalse(b.due(239 * s, emptyList(), net, near = true))
        assertTrue(b.due(240 * s, emptyList(), net, near = true))
        assertTrue(b.due(360 * s, emptyList(), net, near = true))
    }

    @Test fun theSameSubnetIsAnIPv4Matter() {
        val phone = listOf("192.168.0.207" to 24)
        assertTrue(RoutePolicy.sameSubnet(phone, listOf("192.168.0.149")))
        assertTrue(RoutePolicy.sameSubnet(phone, listOf("fd00::1", "192.168.0.149")))
        assertFalse(RoutePolicy.sameSubnet(phone, listOf("192.168.1.149")))
        assertFalse(RoutePolicy.sameSubnet(phone, emptyList()))
        assertTrue(RoutePolicy.sameSubnet(listOf("10.1.2.3" to 16), listOf("10.1.200.4")))
        assertFalse(RoutePolicy.sameSubnet(listOf("10.1.2.3" to 24), listOf("10.1.200.4")))
        // A point-to-point link (mobile data's /32) and nonsense say nothing.
        assertFalse(RoutePolicy.sameSubnet(listOf("10.1.2.3" to 32), listOf("10.1.2.3")))
        assertFalse(RoutePolicy.sameSubnet(listOf("x" to 24), listOf("192.168.0.1")))
    }

    // ─── What a failure says ────────────────────────────────────────────────

    @Test fun failuresAreReadForWhatTheySayAboutTheRoute() {
        assertEquals(ConnResult.Refused, RoutePolicy.classify(ConnectException("failed to connect to /192.168.0.149 (port 47323) after 3ms: isConnected failed: ECONNREFUSED (Connection refused)")).first)
        assertEquals(ConnResult.Timeout, RoutePolicy.classify(SocketTimeoutException("timeout")).first)
        assertEquals(ConnResult.Timeout, RoutePolicy.classify(SocketTimeoutException("failed to connect to /10.0.0.2 (port 47323) from /10.0.2.16 (port 40000) after 4000ms")).first)
        assertEquals(ConnResult.NoName, RoutePolicy.classify(UnknownHostException("Unable to resolve host \"mac.example.com\": No address associated with hostname")).first)
        assertEquals(ConnResult.Unreachable, RoutePolicy.classify(NoRouteToHostException("No route to host")).first)
        assertEquals(ConnResult.Unreachable, RoutePolicy.classify(ConnectException("failed to connect: ENETUNREACH (Network is unreachable)")).first)
        // The pin: another server at the Mac's address, or the Mac's own certificate out of date.
        val wrong = SSLHandshakeException("handshake failed").apply { initCause(CertificateException("pinned certificate: Wrong")) }
        assertEquals(ConnResult.PinWrong, RoutePolicy.classify(wrong).first)
        val expired = SSLHandshakeException("handshake failed").apply { initCause(CertificateException("pinned certificate: Expired")) }
        assertEquals(ConnResult.PinExpired, RoutePolicy.classify(expired).first)
        assertEquals(ConnResult.Tls, RoutePolicy.classify(SSLHandshakeException("Connection closed by peer")).first)
        assertEquals(ConnResult.Closed, RoutePolicy.classify(java.net.SocketException("Connection reset")).first)
        assertEquals(ConnResult.Failed, RoutePolicy.classify(IllegalStateException("odd")).first)
        assertEquals(ConnResult.PinWrong, RoutePolicy.worst(listOf(ConnResult.Timeout, ConnResult.PinWrong, ConnResult.Refused)))
        assertEquals(ConnResult.Refused, RoutePolicy.worst(listOf(ConnResult.Timeout, ConnResult.Refused)))
        assertNull(RoutePolicy.worst(emptyList()))
    }

    // ─── What the offline card says ─────────────────────────────────────────

    private fun report(winner: RouteKind?, vararg tries: RouteTry) = RouteReport(0, NetKind.Wifi, near = true, tries = tries.toList(), winner = winner)

    @Test fun theCardNamesTheRoutesActuallyTried() {
        val localRefused = RouteTry(L, ConnResult.Refused, "192.168.0.149:47323")
        val baseNoAnswer = RouteTry(R, ConnResult.Timeout, "10.0.2.2:47321")
        assertEquals(RouteProblem.LocalOnly, RouteWords.problem(report(null, localRefused, baseNoAnswer)))
        assertEquals(RouteProblem.WrongMac, RouteWords.problem(report(null, RouteTry(L, ConnResult.PinWrong), baseNoAnswer)))
        // A round that found a way, or that never tried Local, has nothing to say here.
        assertNull(RouteWords.problem(report(L, RouteTry(L, ConnResult.Ok))))
        assertNull(RouteWords.problem(report(null, RouteTry(L, ConnResult.Skipped, why = "no network"), baseNoAnswer)))
        assertNull(RouteWords.problem(report(null, baseNoAnswer)))
        assertNull(RouteWords.problem(null))
    }

    @Test fun theLogSaysWhyARoundChoseWhatItDid() {
        val r = report(R, RouteTry(L, ConnResult.Refused), RouteTry(R, ConnResult.Ok))
        assertEquals("the base URL answered as it is; Local refused", RouteWords.summary(r, upgrade = false))
        assertEquals("Local answered after all: moving to it; base URL answered", RouteWords.summary(report(L, RouteTry(R, ConnResult.Ok), RouteTry(L, ConnResult.Ok)), upgrade = true))
        assertEquals(
            "nothing answered: Local not tried (no network), base URL no such name; the base URL is the fallback",
            RouteWords.summary(report(null, RouteTry(L, ConnResult.Skipped, why = "no network"), RouteTry(R, ConnResult.NoName)), upgrade = false),
        )
    }
}
