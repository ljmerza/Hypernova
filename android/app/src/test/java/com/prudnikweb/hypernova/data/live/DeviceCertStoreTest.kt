package com.prudnikweb.hypernova.data.live

import android.content.Context
import com.prudnikweb.hypernova.data.Api
import com.prudnikweb.hypernova.data.ApiException
import com.prudnikweb.hypernova.data.DeviceCertStore
import com.prudnikweb.hypernova.data.DeviceIdentity
import com.prudnikweb.hypernova.data.Problem
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.b64
import kotlinx.coroutines.runBlocking
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.security.KeyStore
import java.security.Signature
import java.util.Base64

/**
 * The phone's side of its device certificate (data/DeviceCert.kt): one key in the key store (here
 * [FakeKeyStore]), a request for it, the certificate a computer's CA signs, kept per computer, and
 * gone with that computer. The TLS that presents it: MutualTlsTest.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceCertStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val ca = HeldCertificate.Builder().commonName("Hypernova device CA").certificateAuthority(0).build()

    @Before fun keys() = FakeKeyStore.install()
    @After fun clean() = FakeKeyStore.reset()

    private fun store(server: String = "srv-a") = DeviceCertStore(context, server).also { it.clear(dropKey = false) }

    private fun csr(s: DeviceCertStore, deviceId: String) = FakeDaemon.Csr.read(Base64.getDecoder().decode(s.csr(deviceId)))

    /** The certificate the CA signs for the request's key. */
    private fun signed(c: FakeDaemon.Csr) =
        HeldCertificate.Builder().keyPair(c.publicKey, ca.keyPair.private).commonName(c.cn).signedBy(ca).build().certificate

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Test fun theRequestNamesTheDeviceAndIsForOneKeyMadeOnce() {
        val s = store()
        val a = csr(s, "dev-1")
        val b = csr(s, "dev-1")
        assertEquals("dev-1", a.cn)
        // The same key both times: made once, kept in the key store under its one alias.
        assertArrayEquals(a.publicKey.encoded, b.publicKey.encoded)
        assertEquals(listOf(DeviceIdentity.ALIAS), keyStore().aliases().toList())
        // Another computer asks for the same key's certificate.
        assertArrayEquals(a.publicKey.encoded, csr(store("srv-b"), "dev-9").publicKey.encoded)
    }

    @Test fun nothingToShowUntilACertificateIsInstalledThenItsChainAndTheKey() {
        val s = store()
        assertNull(s.identity())
        val c = csr(s, "dev-1")
        val cert = signed(c)
        s.install(b64(cert.encoded), b64(ca.certificate.encoded), "ab".repeat(32))
        val id = s.identity()!!
        assertEquals(cert, id.chain[0])
        assertEquals(ca.certificate, id.chain[1])
        // The key behind it signs what the certificate's key verifies (TLS's CertificateVerify).
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(id.key); update("hello".toByteArray()); sign() }
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(cert.publicKey); update("hello".toByteArray()); verify(sig) })
        // The key manager presents exactly that, under its one alias, to any server that asks.
        val km = id.keyManager()
        assertEquals(DeviceIdentity.ALIAS, km.chooseClientAlias(arrayOf("EC"), null, null))
        assertArrayEquals(id.chain, km.getCertificateChain(DeviceIdentity.ALIAS))
        assertEquals(id.key, km.getPrivateKey(DeviceIdentity.ALIAS))
        assertNull(km.getCertificateChain("other"))
        assertNull(km.chooseServerAlias("EC", null, null))
        // Kept: a store made again (the app restarted) has it.
        assertEquals(cert, DeviceCertStore(context, "srv-a").identity()!!.chain[0])
    }

    @Test fun aBadAnswerLeavesWhatWasThere() {
        val s = store()
        val cert = signed(csr(s, "dev-1"))
        s.install(b64(cert.encoded), b64(ca.certificate.encoded), null)
        try {
            s.install(b64("not a certificate".toByteArray()), b64(ca.certificate.encoded), null)
            fail("a certificate that doesn't parse is refused")
        } catch (_: Exception) {
        }
        assertEquals(cert, s.identity()!!.chain[0])
    }

    @Test fun eachComputerKeepsItsOwnCertificateAndTheKeyGoesOnlyWithTheLast() {
        val a = store("srv-a")
        val b = store("srv-b")
        val ca2 = HeldCertificate.Builder().commonName("gym's CA").certificateAuthority(0).build()
        val ca1Cert = signed(csr(a, "dev-1"))
        val cb = csr(b, "dev-2")
        val ca2Cert = HeldCertificate.Builder().keyPair(cb.publicKey, ca2.keyPair.private).commonName(cb.cn).signedBy(ca2).build().certificate
        a.install(b64(ca1Cert.encoded), b64(ca.certificate.encoded), null)
        b.install(b64(ca2Cert.encoded), b64(ca2.certificate.encoded), null)
        assertEquals(ca1Cert, a.identity()!!.chain[0])
        assertEquals(ca2Cert, b.identity()!!.chain[0])

        // Forgetting one computer while another still has a certificate for the key: the key stays.
        a.clear(dropKey = false)
        assertNull(a.identity())
        assertEquals(ca2Cert, b.identity()!!.chain[0])
        // The last one: the key goes too, and no certificate is shown without it.
        b.clear()
        assertTrue(keyStore().aliases().toList().isEmpty())
        assertNull(b.identity())
    }

    @Test fun requestPostsTheCsrAndInstallsTheAnswer() {
        FakeDaemon("z2", lan = true).use { d ->
            val s = store()
            runBlocking { s.request(Api(d.base, "tok-z2"), "dev-1") }
            val asked = d.calls("POST", "/v1/device/cert").single()
            assertEquals("Bearer tok-z2", asked.auth)
            assertEquals("dev-1", FakeDaemon.Csr.read(Base64.getDecoder().decode(asked.json.getString("csr"))).cn)
            val id = s.identity()!!
            assertEquals(d.issued.single(), id.chain[0])
            assertEquals(d.deviceCa!!.certificate, id.chain[1])
        }
    }

    @Test fun anAnswerWithoutTheCertificateOrTheCaIsTheServersFault() {
        FakeDaemon("z2").use { d ->
            val s = store()
            for ((body, code) in listOf(JSONObject().put("ca", "x") to "no_cert", JSONObject().put("cert", "x") to "no_ca")) {
                d.ok("POST", "/v1/device/cert", body)
                try {
                    runBlocking { s.request(Api(d.base, "tok-z2"), "dev-1") }
                    fail("$code is an error")
                } catch (e: ApiException) {
                    assertEquals(Problem.Server, e.problem)
                    assertEquals(code, e.code)
                }
            }
            assertNull(s.identity())
            assertNotNull(keyStore().getEntry(DeviceIdentity.ALIAS, null))
        }
    }
}
