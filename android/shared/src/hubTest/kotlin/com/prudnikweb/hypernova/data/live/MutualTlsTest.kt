package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.ActiveRoutes
import com.prudnikweb.hypernova.data.DeviceCertStore
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.data.RouteKind
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * The local route's mutual TLS (docs/PROTOCOL.md, "Local network (direct HTTPS)") against
 * [FakeDaemon]'s listener: a real TLS handshake on loopback, the listener's certificate pinned, the
 * phone's device certificate asked for, issued and presented. The key is [FakeKeyStore]'s (the JVM
 * has no AndroidKeyStore); everything else is the app's own code: LiveHub, LanRouter, PinnedTls,
 * DeviceCertStore.
 */
@RunWith(RigRunner::class)
class MutualTlsTest {
    private val rigs = mutableListOf<HubRig>()

    @Before fun keys() = FakeKeyStore.install()

    @After fun tearDown() {
        rigs.forEach { it.close() }
        FakeKeyStore.reset()
    }

    private fun rig(daemon: FakeDaemon, baseIsListener: Boolean = true, serverId: String = "srv-${daemon.name}") =
        HubRig(daemon, serverId = serverId, baseIsListener = baseIsListener).also { rigs += it }

    private fun FakeDaemon.lanCalls(method: String, path: String) = calls(method, path).filter { it.overLan }

    private fun routeOf(r: HubRig) = ActiveRoutes.route(r.pairing.baseUrl)

    /** Linked over the listener, the socket included, with the newest certificate the daemon issued. */
    private fun linkedWithCert(r: HubRig, what: String) = r.until(what, timeoutMs = 15_000) {
        val d = r.daemon
        val cert = d.issued.lastOrNull()
        r.hub.link.state == LinkState.Connected && cert != null &&
            d.lanCalls("GET", "/v1/ws").any { it.clientCert?.encoded?.contentEquals(cert.encoded) == true }
    }

    @Test fun theFirstConnectionAsksForADeviceCertificateThenComesBackWithIt() {
        val daemon = FakeDaemon("z2", lan = true)
        daemon.snapshot = snapshot("z2", sessions = listOf(session("s1")))
        val r = rig(daemon)
        r.hub.start()
        linkedWithCert(r, "linked with a device certificate")

        // Without a certificate the listener answered the probe and nothing else.
        val first = daemon.lanCalls("GET", "/v1/snapshot").first()
        assertNull(first.clientCert)
        assertTrue(daemon.lanCalls("GET", "/v1/health").isNotEmpty())
        // The request: over the listener, the bearer token its authority, the key's CSR naming this device.
        val asked = daemon.lanCalls("POST", "/v1/device/cert").single()
        assertEquals("Bearer tok-z2", asked.auth)
        val csr = FakeDaemon.Csr.read(Base64.getDecoder().decode(asked.json.getString("csr")))
        assertEquals("dev-1", csr.cn)
        val cert = daemon.issued.single()
        assertArrayEquals(csr.publicKey.encoded, cert.publicKey.encoded)
        // Then the snapshot and the socket with it, over the pinned route.
        assertEquals(cert, daemon.lanCalls("GET", "/v1/snapshot").last().clientCert)
        assertEquals(RouteKind.Local, routeOf(r).kind)
        assertEquals(Mac.VIA_LOCAL, r.hub.mac.via)
        assertEquals(listOf("s1"), r.hub.sessions.map { it.id })
        // Nothing went to the plain base URL: the base is the listener.
        assertTrue(daemon.requests.filter { !it.overLan }.joinToString { it.method + " " + it.path }, daemon.requests.all { it.overLan })
        // Kept for this computer: its chain, then the CA, and the key behind it.
        val id = r.certs().identity()!!
        assertEquals(cert, id.chain[0])
        assertEquals(daemon.deviceCa!!.certificate, id.chain[1])
        assertArrayEquals(cert.encoded, id.keyManager().getCertificateChain("hypernova-device")[0].encoded)
    }

    @Test fun theCertificateIsKeptAndShownFromTheFirstHandshakeNextTime() {
        val daemon = FakeDaemon("z2", lan = true)
        val first = rig(daemon)
        first.hub.start()
        linkedWithCert(first, "the first connection")
        // The app starts again (a new hub for the same computer); the daemon still has the certificate.
        first.hub.close()
        first.pump()
        rigs.remove(first)
        first.scope.cancel()
        val before = daemon.requests.size
        val again = rig(daemon)
        again.hub.start()
        linkedWithCert(again, "linked again")
        val later = daemon.requests.drop(before)
        assertEquals(1, daemon.issued.size)
        assertTrue("no new request", later.none { it.bare == "/v1/device/cert" })
        assertTrue("every request shows it", later.filter { it.bare != "/v1/health" }.all { it.clientCert == daemon.issued.single() })
    }

    @Test fun aServerThatLostTheCertificateIsAskedAgainAndTheNewOneReplacesIt() {
        val daemon = FakeDaemon("z2", lan = true)
        val r = rig(daemon)
        r.hub.start()
        linkedWithCert(r, "the first certificate")
        val old = daemon.issued.single()

        // The daemon forgets it (its CA renewed, the device re-added): the socket closes, and the old
        // certificate gets nowhere but the open routes.
        daemon.forgetCerts()
        daemon.closeSocket(4002, "certificate replaced")
        r.until("a second certificate", timeoutMs = 20_000) {
            if (daemon.issued.isEmpty()) r.advance(1_000)
            daemon.issued.isNotEmpty()
        }
        linkedWithCert(r, "linked with the new one")
        val renewed = daemon.issued.single()
        assertTrue(!renewed.encoded.contentEquals(old.encoded))
        assertEquals(renewed, r.certs().identity()!!.chain[0])
        assertTrue(r.hub.link.state != LinkState.Unpaired)
    }

    @Test fun aCertificateTheServerWontIssueLeavesTheBaseUrlToServe() {
        val daemon = FakeDaemon("z2", lan = true)
        daemon.certRefusal = 400 to "bad_csr"
        val r = rig(daemon, baseIsListener = false)
        r.hub.start()
        r.until("linked over the base URL", timeoutMs = 15_000) {
            r.hub.link.state == LinkState.Connected && daemon.count("POST", "/v1/device/cert") > 0 && routeOf(r).kind == RouteKind.Remote
        }
        assertEquals(Mac.VIA_REMOTE, r.hub.mac.via)
        assertNull(r.certs().identity())
        // The socket is the base URL's, without a certificate.
        assertTrue(!daemon.calls("GET", "/v1/ws").last().overLan)
    }

    @Test fun aServerThatDoesntKnowTheTokenWhenAskedForACertificateUnpairs() {
        val daemon = FakeDaemon("z2", lan = true)
        daemon.certRefusal = 401 to "unauthorized"
        val r = rig(daemon)
        r.hub.start()
        r.until("unpaired") { r.hub.link.state == LinkState.Unpaired }
        assertEquals(1, daemon.count("POST", "/v1/device/cert"))
        assertEquals(0, daemon.opened.get())
    }

    @Test fun aListenerWithAnotherCertificateIsNeverTalkedTo() {
        val daemon = FakeDaemon("z2", lan = true)
        // An impostor at the address the snapshot names: the pin it says isn't the listener's.
        val wrong = "ab".repeat(32)
        daemon.snapshot = snapshot("z2") {
            put("lan", JSONObject().put("port", daemon.lanPort).put("addresses", JSONArray(listOf("127.0.0.1"))).put("fingerprint", wrong).put("deviceCert", true))
        }
        val r = HubRig(daemon, serverId = "srv-z2", viaLan = false).also { rigs += it }
        r.connect()
        r.sync()
        assertEquals(RouteKind.Remote, routeOf(r).kind)
        // The handshake failed on the pin, so not one request reached the listener.
        assertTrue(daemon.requests.none { it.overLan })
        assertNotNull(r.hub.link)
        assertEquals(Mac.VIA_REMOTE, r.hub.mac.via)
    }
}
