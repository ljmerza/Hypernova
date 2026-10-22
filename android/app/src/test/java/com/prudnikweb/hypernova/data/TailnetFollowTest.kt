package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

/**
 * The phone follows the computer on its tailnet (data/Pairing.kt `TailnetFollow`): paired while the
 * Tailscale app served the phone API, it keeps working once the app is quit and Hypernova's own
 * node serves instead.
 */
class TailnetFollowTest {
    private val fp = "14aa650b9381cf8edbde5b00003197004a720ad4b7d972b07e5775029879da6c"
    // Paired while the Tailscale app served: the app's node is both the base URL and the tailnet target.
    private val viaApp = Pairing(
        "https://macbook-air.tail1234.ts.net:47321", "t", "Mac", null, null, fingerprint = fp,
        tailnetName = "macbook-air.tail1234.ts.net", tailnetIp = "100.64.0.5",
    )
    private val builtIn = TailnetHint("hypernova-macbook-air.tail1234.ts.net", "100.64.0.9", "https://hypernova-macbook-air.tail1234.ts.net:47321", null)

    @Test fun aPairingFromTheTailscaleAppFollowsTheBuiltInNode() {
        val next = TailnetFollow.next(viaApp, builtIn)!!
        assertEquals("hypernova-macbook-air.tail1234.ts.net", next.tailnetName)
        assertEquals("100.64.0.9", next.tailnetIp)
        // The built-in route dials the new node; the base URL (the Tailscale app's) stays as it was.
        val t = TailnetTarget.of(next)!!
        assertEquals("https://hypernova-macbook-air.tail1234.ts.net:47321", t.base)
        assertEquals("hypernova-macbook-air.tail1234.ts.net:47321", t.hostPort)
        assertEquals(viaApp.baseUrl, next.baseUrl)
        // Once followed, the same word changes nothing (no restart loop).
        assertNull(TailnetFollow.next(next, builtIn))
        assertNull(TailnetFollow.next(next, builtIn.copy(name = "HYPERNOVA-MACBOOK-AIR.tail1234.ts.net.")))
    }

    @Test fun nothingChangesWhenTheComputerIsWhereThePairingSays() {
        val paired = Pairing("https://hypernova-macbook-air.tail1234.ts.net:47321", "t", "Mac", null, null, tailnetName = "hypernova-macbook-air.tail1234.ts.net", tailnetIp = "100.64.0.9")
        assertNull(TailnetFollow.next(paired, builtIn))
        assertNull(TailnetFollow.next(paired, null))
    }

    @Test fun onlyTheComputerItselfOnTheSameControlServer() {
        // Another control server would need the user's consent: a new pairing asks for it.
        assertNull(TailnetFollow.next(viaApp, builtIn.copy(control = "https://headscale.example")))
        // Headscale both ways: followed, with the computer's pinned address form.
        val hs = viaApp.copy(controlUrl = "https://headscale.example", baseUrl = "https://100.64.0.1:48321", tailnetName = "mac.hn.test", tailnetIp = "100.64.0.1")
        val moved = TailnetFollow.next(hs, TailnetHint("hypernova-mac.hn.test", "100.64.0.7", "https://100.64.0.7:48321", "https://headscale.example/"))!!
        assertEquals("https://100.64.0.7:48321", TailnetTarget.of(moved)!!.base)
        assertEquals(fp, TailnetTarget.of(moved)!!.fingerprint)
        // A URL that isn't the named computer, or isn't https: ignored. (A name the tailnet doesn't
        // have can't be dialled anyway: the node's proxy tunnels to its own peers only.)
        assertNull(TailnetFollow.next(viaApp, builtIn.copy(url = "https://evil.example:47321")))
        assertNull(TailnetFollow.next(viaApp, builtIn.copy(url = "https://100.64.0.10:47321")))
        assertNull(TailnetFollow.next(viaApp, builtIn.copy(url = "http://hypernova-macbook-air.tail1234.ts.net:47321")))
    }

    @Test fun theSnapshotSaysIt() {
        val h = TailnetHint.parse(JSONObject("""{"name":"hypernova-mac.tail1234.ts.net.","ip":"100.64.0.9","url":"https://hypernova-mac.tail1234.ts.net:47321/","control":null}"""))!!
        assertEquals("hypernova-mac.tail1234.ts.net", h.name)
        assertEquals("https://hypernova-mac.tail1234.ts.net:47321", h.url)
        assertNull(h.control)
        assertNull(TailnetHint.parse(JSONObject("""{"name":"x"}""")))
        assertNull(TailnetHint.parse(null))
        assertEquals(h, Wire.mac(JSONObject("""{"id":"m","name":"Mac","tailnet":{"name":"hypernova-mac.tail1234.ts.net","ip":"100.64.0.9","url":"https://hypernova-mac.tail1234.ts.net:47321"}}""")).tailnet)
    }
}
