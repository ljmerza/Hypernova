package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
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
    private val T = RouteKind.Tailscale
    private val R = RouteKind.Remote

    // ─── A round ────────────────────────────────────────────────────────────

    @Test fun localWinsTheMomentItAnswersWhateverElseIsStillBeingTried() {
        val j = RoundJudge(waitForLocal = true)
        assertEquals(L, j.settle(answered = setOf(L), pending = setOf(T, R)))
        assertEquals(L, j.settle(answered = setOf(L, T), pending = emptySet()))
        assertFalse(j.mayImprove(L, setOf(T, R)))
    }

    @Test fun inTheMacsSubnetTheTailnetWaitsForLocal() {
        // The incident: at home on Wi-Fi, the tailnet answering (or not) must not decide before Local has.
        val j = RoundJudge(waitForLocal = true)
        assertNull(j.settle(answered = setOf(T), pending = setOf(L, R)))
        assertEquals(L, j.settle(answered = setOf(T, L), pending = setOf(R)))
        // Local refused: the tailnet it is.
        assertEquals(T, j.settle(answered = setOf(T), pending = setOf(R)))
    }

    @Test fun elsewhereTheRoundTakesWhatAnswersAndMovesToLocalIfItAnswersLater() {
        val j = RoundJudge(waitForLocal = false)
        assertEquals(T, j.settle(answered = setOf(T), pending = setOf(L, R)))
        assertTrue(j.mayImprove(T, setOf(L, R)))
        assertTrue(j.upgrade(T, L))
        assertFalse(j.upgrade(T, R))
        assertFalse(j.upgrade(L, L))
        // Nothing yet: wait.
        assertNull(j.settle(answered = emptySet(), pending = setOf(L, T)))
    }

    @Test fun theTailnetFailingNeverHoldsLocalBack() {
        val c = RouteChooser("https://mac.tail1234.ts.net:47321", 47323, TailnetTarget("https://mac.tail1234.ts.net:47321", null))
        val min = 60_000L
        // The Mac's node goes to NeedsLogin and back: the tailnet route fails in use again and again.
        var t = 10 * min
        val holds = (1..4).map { c.failedInUse(T, t).also { t += 5_000 } }
        assertEquals(listOf(0L, 8_000L, 16_000L, 32_000L), holds)
        assertEquals(setOf(T), c.demoted(t - 5_000))
        // Local is untouched: tried first, and it wins as soon as it answers.
        val j = RoundJudge(waitForLocal = true, demoted = c.demoted(t - 5_000))
        assertEquals(L, j.settle(answered = setOf(L), pending = setOf(T, R)))
        // The held-back tailnet answering doesn't settle a round while Local is still being tried…
        assertNull(j.settle(answered = setOf(T), pending = setOf(L, R)))
        // …and is still used when nothing else answers.
        assertEquals(T, j.settle(answered = setOf(T), pending = emptySet()))
    }

    @Test fun aRouteThatKeepsFailingIsTriedAfterTheOthersForAWhileThenFirstAgain() {
        val c = RouteChooser("https://mac.ts.net:47321", 47323)
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
        // Held back on purpose (no device certificate there): demoted until then, not counted.
        c.hold(T, t + 60_000)
        assertTrue(T in c.demoted(t + 59_999))
        assertFalse(T in c.demoted(t + 60_000))
    }

    @Test fun aHeldBackLocalWaitsForTheTailnetButIsUsedWhenItIsAllThereIs() {
        val j = RoundJudge(waitForLocal = true, demoted = setOf(L))
        assertNull(j.settle(answered = setOf(L), pending = setOf(T, R)))
        assertEquals(T, j.settle(answered = setOf(L, T), pending = setOf(R)))
        assertEquals(L, j.settle(answered = setOf(L), pending = emptySet()))
        assertFalse(j.upgrade(T, L))
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
        assertEquals(ConnResult.NoName, RoutePolicy.classify(UnknownHostException("Unable to resolve host \"mac.tail1234.ts.net\": No address associated with hostname")).first)
        assertEquals(ConnResult.Unreachable, RoutePolicy.classify(NoRouteToHostException("No route to host")).first)
        assertEquals(ConnResult.Unreachable, RoutePolicy.classify(ConnectException("failed to connect: ENETUNREACH (Network is unreachable)")).first)
        // The pin: another server at the Mac's address, or the Mac's own certificate out of date.
        val wrong = SSLHandshakeException("handshake failed").apply { initCause(CertificateException("pinned certificate: Wrong")) }
        assertEquals(ConnResult.PinWrong, RoutePolicy.classify(wrong).first)
        val expired = SSLHandshakeException("handshake failed").apply { initCause(CertificateException("pinned certificate: Expired")) }
        assertEquals(ConnResult.PinExpired, RoutePolicy.classify(expired).first)
        assertEquals(ConnResult.Tls, RoutePolicy.classify(SSLHandshakeException("Connection closed by peer")).first)
        // The built-in node's proxy: 502 is "couldn't reach the Mac on the tailnet", the rest a refusal.
        assertEquals(ConnResult.NoPeer to "proxy 502", RoutePolicy.classify(IOException("Unexpected response code for CONNECT: 502")))
        assertEquals(ConnResult.ProxyRefused, RoutePolicy.classify(IOException("Unexpected response code for CONNECT: 403")).first)
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
        val tailNoAnswer = RouteTry(T, ConnResult.NoPeer, "mac.ts.net:47321")
        val baseNoName = RouteTry(R, ConnResult.NoName, "mac.ts.net:47321")
        assertEquals(RouteProblem.Both, RouteWords.problem(report(null, localRefused, tailNoAnswer, baseNoName)))
        assertEquals(RouteProblem.LocalOnly, RouteWords.problem(report(null, localRefused, RouteTry(T, ConnResult.Skipped, why = "the built-in node is signed out"), baseNoName)))
        // Never "the tailnet doesn't answer" alone when Local was tried; and not on Wi-Fi it says so.
        assertEquals(RouteProblem.TailnetOnly, RouteWords.problem(report(null, RouteTry(L, ConnResult.Skipped, why = "on mobile data"), tailNoAnswer, baseNoName)))
        assertEquals(RouteProblem.WrongMac, RouteWords.problem(report(null, RouteTry(L, ConnResult.PinWrong), tailNoAnswer)))
        // A round that found a way, or that tried neither, has nothing to say here.
        assertNull(RouteWords.problem(report(L, RouteTry(L, ConnResult.Ok))))
        assertNull(RouteWords.problem(report(null, baseNoName)))
        assertNull(RouteWords.problem(null))
    }

    @Test fun theLogSaysWhyARoundChoseWhatItDid() {
        val r = report(T, RouteTry(L, ConnResult.Refused), RouteTry(T, ConnResult.Ok))
        assertEquals("the built-in node answered; Local refused", RouteWords.summary(r, upgrade = false))
        assertEquals("Local answered after all: moving to it; tailnet answered", RouteWords.summary(report(L, RouteTry(T, ConnResult.Ok), RouteTry(L, ConnResult.Ok)), upgrade = true))
        assertEquals(
            "nothing answered: Local no answer, tailnet not tried (the built-in node is stopped), base URL no such name; the base URL is the fallback",
            RouteWords.summary(report(null, RouteTry(L, ConnResult.Timeout), RouteTry(T, ConnResult.Skipped, why = "the built-in node is stopped"), RouteTry(R, ConnResult.NoName)), upgrade = false),
        )
    }
}
