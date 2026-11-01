package com.prudnikweb.hypernova.data.live

import android.content.Context
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.data.ApiException
import com.prudnikweb.hypernova.data.PairLink
import com.prudnikweb.hypernova.data.PairLinks
import com.prudnikweb.hypernova.data.Pairer
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.Problem
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pairing end to end (data/Pairing.kt, [Pairer.pair]; docs/PROTOCOL.md, "Pairing") against
 * [FakeDaemon]: `POST /v1/pair` over the computer's own listener first (its certificate pinned from
 * the link's `fp`), then the link's base URL, and what each refusal means. The daemon's answer is
 * the contract fixture itself (protocol/fixtures/rest-pair.json).
 */
@RunWith(RobolectricTestRunner::class)
class PairTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val answer: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResource("fixtures/rest-pair.json")!!.readText())
    }

    private fun FakeDaemon.pairs() = ok("POST", "/v1/pair", answer)

    /** The link as `hypernova pair` gives it, through the app's own parser. */
    private fun link(d: FakeDaemon, base: String = d.base, fp: String? = d.fingerprint, code: String = "c0de-1234"): PairLink {
        val lan = if (fp != null) "&fp=$fp&lan=127.0.0.1&lp=${d.lanPort}" else ""
        return PairLinks.parse("hypernova://pair?v=1&u=$base&c=$code&n=z2$lan")!!
    }

    private fun pair(l: PairLink): Pairing = runBlocking { Pairer.pair(context, l) }

    private fun refused(l: PairLink): ApiException = try {
        pair(l)
        fail("refused")
        error("unreachable")
    } catch (e: ApiException) {
        e
    }

    @Test fun withoutAListenerTheBaseUrlPairsAndSaysWhoThisPhoneIs() {
        FakeDaemon("z2").use { d ->
            d.pairs()
            val p = pair(link(d, fp = null))
            val req = d.calls("POST", "/v1/pair").single()
            // No token yet: the code is the authority.
            assertEquals(null, req.auth)
            val body = req.json
            assertEquals("c0de-1234", body.getString("code"))
            assertEquals(Pairer.deviceName(context), body.getString("deviceName"))
            assertTrue(body.getString("deviceName").isNotBlank())
            assertTrue(body.getString("model").isNotBlank())
            assertEquals(BuildConfig.VERSION_NAME, body.getString("appVersion"))
            // This install's own id, the same every time (pairing again replaces its old device).
            assertEquals(Pairer.installId(context), body.getString("installId"))
            assertEquals(Pairing(d.base, "device-token", "server", "mac-id", "device-id"), p)

            pair(link(d, fp = null))
            assertEquals(body.getString("installId"), d.calls("POST", "/v1/pair")[1].json.getString("installId"))
        }
    }

    @Test fun theListenerIsTriedFirstAndItsAddressBecomesTheBase() {
        FakeDaemon("z2", lan = true).use { d ->
            d.pairs()
            // An emulator-era plain-http `u`: the listener that answered is the base kept.
            val p = pair(link(d))
            val req = d.calls("POST", "/v1/pair").single()
            assertTrue("over the pinned listener", req.overLan)
            // No device certificate yet: pairing is open on the listener.
            assertEquals(null, req.clientCert)
            assertEquals("https://127.0.0.1:${d.lanPort}", p.baseUrl)
            assertEquals(d.fingerprint, p.fingerprint)
            assertEquals(listOf("127.0.0.1"), p.lanAddresses)
            assertEquals(d.lanPort, p.lanPort)
            assertEquals("device-token", p.token)
            assertEquals("device-id", p.deviceId)
        }
    }

    @Test fun anHttpsBaseIsKeptAsItIs() {
        FakeDaemon("z2", lan = true).use { d ->
            d.pairs()
            val base = "https://127.0.0.1:${d.lanPort}"
            assertEquals(base, pair(link(d, base = base)).baseUrl)
            assertTrue(d.calls("POST", "/v1/pair").single().overLan)
        }
    }

    @Test fun aCodeTheServerRefusesIsExpiredAndNotTriedElsewhere() {
        for (status in listOf(400, 401, 403, 404, 410)) {
            FakeDaemon("z2").use { d ->
                d.on("POST", "/v1/pair") { refusal(status, "bad_code", "That code is no good") }
                val e = refused(link(d, fp = null))
                assertEquals("HTTP $status", Problem.CodeExpired, e.problem)
                assertEquals(status, e.status)
            }
        }
        // Over the listener: its refusal is final, the base URL isn't asked.
        FakeDaemon("z2", lan = true).use { d ->
            d.on("POST", "/v1/pair") { refusal(403, "pairing_closed", "No code is being shown") }
            val e = refused(link(d))
            assertEquals(Problem.CodeExpired, e.problem)
            assertEquals("pairing_closed", e.code)
            assertTrue(d.calls("POST", "/v1/pair").all { it.overLan })
        }
    }

    @Test fun tooManyWrongCodesFromHereIsSaidWithItsWait() {
        FakeDaemon("z2", lan = true).use { d ->
            d.on("POST", "/v1/pair") { refusal(429, "too_many_tries", "Too many wrong codes") { put("retryAfterSeconds", 420) } }
            val e = refused(link(d))
            assertEquals("too_many_tries", e.code)
            assertEquals(429, e.status)
            assertEquals(420, e.error?.optInt("retryAfterSeconds"))
            assertTrue("the base URL isn't asked", d.calls("POST", "/v1/pair").all { it.overLan })
        }
    }

    @Test fun aListenerThatIsntTheOneTheLinkPinsIsPassedOverForTheBaseUrl() {
        FakeDaemon("z2", lan = true).use { d ->
            d.pairs()
            val p = pair(link(d, fp = "ab".repeat(32)))
            val req = d.calls("POST", "/v1/pair").single()
            assertTrue("the base URL", !req.overLan)
            // Nobody answered on the listener: the base stays the link's.
            assertEquals(d.base, p.baseUrl)
        }
    }

    @Test fun nobodyAnsweringIsUnreachableNotABadCode() {
        // The listener pinned wrong and the base URL down.
        FakeDaemon("z2", lan = true).use { d ->
            d.down = true
            assertEquals(Problem.Unreachable, refused(link(d, fp = "ab".repeat(32))).problem)
        }
        // The base is the listener itself and it doesn't answer: nothing else is tried.
        FakeDaemon("z2", lan = true).use { d ->
            d.pairs()
            val e = refused(link(d, base = "https://127.0.0.1:${d.lanPort}", fp = "ab".repeat(32)))
            assertEquals(Problem.Unreachable, e.problem)
            assertEquals(0, d.count("POST", "/v1/pair"))
        }
    }

    @Test fun anAnswerWithoutATokenIsTheServersFault() {
        FakeDaemon("z2").use { d ->
            d.ok("POST", "/v1/pair", JSONObject().put("deviceId", "d"))
            val e = refused(link(d, fp = null))
            assertEquals(Problem.Server, e.problem)
            assertEquals("no_token", e.code)
        }
    }
}
