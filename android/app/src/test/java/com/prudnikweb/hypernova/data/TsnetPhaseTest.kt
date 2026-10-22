package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every state of the built-in node as the screens show it (data/Tsnet.kt `TsnetPolicy.phase`): each
 * one says what is going on and what to do, and only Running hides the sign-in.
 */
class TsnetPhaseTest {
    private val link = "https://login.tailscale.com/a/abc123"
    private fun phase(s: TsnetStatus, opened: String? = null, available: Boolean = true) = TsnetPolicy.phase(available, s, opened)

    @Test fun everyStateHasAPhase() {
        assertEquals(TsPhase.Unavailable, phase(TsnetStatus("Running", proxyPort = 1, proxyToken = "t"), available = false))
        assertEquals(TsPhase.Off, phase(TsnetStatus.STOPPED))
        assertEquals(TsPhase.Failed, phase(TsnetStatus("Stopped", error = "tailnet requires logging to be enabled")))
        assertEquals(TsPhase.Starting, phase(TsnetStatus("Starting")))
        assertEquals(TsPhase.Starting, phase(TsnetStatus("NoState")))
        // NeedsLogin before the link came: still getting it.
        assertEquals(TsPhase.Starting, phase(TsnetStatus("NeedsLogin")))
        assertEquals(TsPhase.NeedsLogin, phase(TsnetStatus("NeedsLogin", loginUrl = link)))
        assertEquals(TsPhase.NeedsApproval, phase(TsnetStatus("NeedsMachineAuth", account = "you@example.com", dnsName = "hypernova-pixel-9.tail1234.ts.net")))
        assertEquals(TsPhase.Running, phase(TsnetStatus("Running", dnsName = "hypernova-pixel-9.tail1234.ts.net", proxyPort = 40000, proxyToken = "t")))
    }

    @Test fun backFromTheBrowserWithTheSameLinkIsNotFinished() {
        // The node still waits on the very link the browser had: Tailscale's page wasn't finished (it ends with Connect).
        assertEquals(TsPhase.Finishing, phase(TsnetStatus("NeedsLogin", loginUrl = link), opened = link))
        // A new link while one was opened (the node started over, the app ended meanwhile): the one
        // finished in the browser was for a key that's gone, so it says why it asks again.
        assertEquals(TsPhase.Again, phase(TsnetStatus("NeedsLogin", loginUrl = "https://login.tailscale.com/a/new"), opened = link))
    }

    @Test fun signedInButNotThroughYetIsConnectingNotWaitingForTheBrowser() {
        // After the sign-in: Starting with the node's name known, then Running before the proxy is up.
        assertEquals(TsPhase.Connecting, phase(TsnetStatus("Starting", dnsName = "hypernova-pixel-9.tail1234.ts.net", account = "you@example.com")))
        assertEquals(TsPhase.Connecting, phase(TsnetStatus("Running", dnsName = "hypernova-pixel-9.tail1234.ts.net")))
        assertFalse(TsnetStatus("Running").running)
        assertTrue(TsnetStatus("NeedsMachineAuth").needsApproval)
    }

    @Test fun theTailnetIsKnownByItsNamesAndAddresses() {
        assertTrue(TsnetPolicy.onTailnet("hypernova-mac.tail1234.ts.net", null, null))
        assertTrue(TsnetPolicy.onTailnet("Mac.hn.test.", "mac.hn.test", null))
        assertTrue(TsnetPolicy.onTailnet("100.64.0.1", null, null))
        assertTrue(TsnetPolicy.onTailnet("100.127.255.254", null, null))
        assertFalse(TsnetPolicy.onTailnet("100.128.0.1", null, null))
        assertFalse(TsnetPolicy.onTailnet("100.63.0.1", null, null))
        assertTrue(TsnetPolicy.onTailnet("[fd7a:115c:a1e0::1]", null, null))
        assertTrue(TsnetPolicy.onTailnet("10.1.2.3", null, "10.1.2.3"))
        assertFalse(TsnetPolicy.onTailnet("192.168.0.149", null, null))
        assertFalse(TsnetPolicy.onTailnet("hypernova.example.com", "mac.tail1234.ts.net", "100.64.0.9"))
        // Under the tailnet's own MagicDNS suffix (a Headscale base_domain): the tailnet's.
        assertTrue(TsnetPolicy.onTailnet("macbook-air-old.hn.test", "hypernova-mac.hn.test", null))
        assertFalse(TsnetPolicy.onTailnet("hn.test", "hypernova-mac.hn.test", null))
        // A bare one-label suffix is no tailnet ("mac.local" names nothing under "local").
        assertFalse(TsnetPolicy.onTailnet("printer.local", "mac.local", null))
        assertFalse(TsnetPolicy.onTailnet("", null, null))
    }
}
