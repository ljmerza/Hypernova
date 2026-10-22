package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in Tailscale route (docs/PROTOCOL.md, "Tailscale built in"): where the node dials the
 * computer, and the chooser's order: Local, then Tailscale, then the base URL as it is.
 */
class TailscaleRouteTest {
    private val fp = "14aa650b9381cf8edbde5b00003197004a720ad4b7d972b07e5775029879da6c"
    private fun pairing(base: String, name: String? = "hypernova-mac.tail1234.ts.net", ip: String? = "100.64.0.9", fingerprint: String? = fp) =
        Pairing(base, "t", "Mac", null, null, fingerprint = fingerprint, tailnetName = name, tailnetIp = ip)

    @Test fun theTargetIsTheBaseUrlWhenItIsOnTheTailnet() {
        // The usual case: the computer serves its API on its tailnet name (a ts.net certificate).
        val t = TailnetTarget.of(pairing("https://hypernova-mac.tail1234.ts.net:47321"))!!
        assertEquals("https://hypernova-mac.tail1234.ts.net:47321", t.base)
        assertEquals("hypernova-mac.tail1234.ts.net:47321", t.hostPort)
        // Without HTTPS certificates (Headscale), the base is the tailnet address and the certificate is pinned.
        val pinned = TailnetTarget.of(pairing("https://100.64.0.9:47321"))!!
        assertEquals("https://100.64.0.9:47321", pinned.base)
        assertEquals(fp, pinned.fingerprint)
        assertTrue(TsnetTls.isAddress("100.64.0.9"))
        assertTrue(TsnetTls.isAddress("fd7a:115c:a1e0::9"))
        assertFalse(TsnetTls.isAddress("hypernova-mac.tail1234.ts.net"))
    }

    @Test fun theTargetIsTheTailnetNameWhenTheBaseIsElsewhere() {
        // A computer paired by a public address (direct HTTPS) that is also on a tailnet: the node dials the name on the same port.
        val t = TailnetTarget.of(pairing("https://abc.hypernova.observer:47321"))!!
        assertEquals("https://hypernova-mac.tail1234.ts.net:47321", t.base)
        // A pairing that says nothing about a tailnet has no target.
        assertNull(TailnetTarget.of(pairing("https://abc.hypernova.observer:47321", name = null, ip = null)))
        // A development base (plain http) with a name still gets the name over https.
        assertEquals("https://hypernova-mac.tail1234.ts.net:47321", TailnetTarget.of(pairing("http://10.0.2.2:47321"))!!.base)
    }

    @Test fun theChooserPrefersLocalThenTailscaleThenTheBase() {
        val remote = "https://hypernova-mac.tail1234.ts.net:47321"
        val c = RouteChooser(remote, 47323, TailnetTarget(remote, fp))
        assertEquals(RouteKind.Remote, c.kind)
        c.settle(null, tailscale = true)
        assertEquals(RouteKind.Tailscale, c.kind)
        assertEquals(remote, c.base)
        // A local address beats the tailnet.
        c.settle("192.168.1.20", tailscale = true)
        assertEquals(RouteKind.Local, c.kind)
        assertEquals("https://192.168.1.20:47323", c.base)
        // Neither answers: the base URL as it is.
        c.settle(null, tailscale = false)
        assertEquals(RouteKind.Remote, c.kind)
        // The tailnet route failing in use falls back to the base URL too.
        c.settle(null, tailscale = true)
        c.localFailed()
        assertEquals(RouteKind.Remote, c.kind)
        // Without a tailnet target, "answered through the node" means nothing.
        val plain = RouteChooser(remote, 47323)
        plain.settle(null, tailscale = true)
        assertEquals(RouteKind.Remote, plain.kind)
    }

    @Test fun reChecksWhileTheNodeRunsAndTheRouteIsNotItYet() {
        val remote = "https://hypernova-mac.tail1234.ts.net:47321"
        val c = RouteChooser(remote, 47323, TailnetTarget(remote, fp))
        // On mobile data with no local addresses: only the node gives a reason to look again.
        assertFalse(c.wantsRecheck(NetKind.Mobile, false, emptyList(), emptyList(), nodeRunning = false))
        assertTrue(c.wantsRecheck(NetKind.Mobile, false, emptyList(), emptyList(), nodeRunning = true))
        c.settle(null, tailscale = true)
        // On the tailnet route, local addresses are still worth a try; the node isn't news any more.
        assertFalse(c.wantsRecheck(NetKind.Mobile, false, emptyList(), emptyList(), nodeRunning = true))
        assertTrue(c.wantsRecheck(NetKind.Wifi, false, listOf("192.168.1.20"), emptyList(), nodeRunning = true))
        c.settle("192.168.1.20")
        assertFalse(c.wantsRecheck(NetKind.Wifi, false, listOf("192.168.1.20"), emptyList(), nodeRunning = true))
    }

    @Test fun readsTheNodeStatus() {
        val s = TsnetStatus.parse("""{"state":"Running","dnsName":"hypernova-pixel.tail1234.ts.net","ips":["100.64.0.2"],"account":"you@example.com","tailnet":"tail1234.ts.net","https":true,"proxyPort":41234,"proxyToken":"abc"}""")
        assertTrue(s.running)
        assertEquals(41234, s.proxyPort)
        assertEquals("you@example.com", s.account)
        val login = TsnetStatus.parse("""{"state":"NeedsLogin","loginUrl":"https://login.tailscale.com/a/abc","https":false}""")
        assertTrue(login.needsLogin)
        assertFalse(login.running)
        assertEquals("https://login.tailscale.com/a/abc", login.loginUrl)
        // Running without a proxy yet isn't a route.
        assertFalse(TsnetStatus.parse("""{"state":"Running"}""").running)
        assertEquals("Stopped", TsnetStatus.parse("not json").state)
    }

    @Test fun theProxyIsToldTheHostPortWithBracketsForIPv6() {
        assertEquals("hypernova-mac.tail1234.ts.net:47321", TailnetTarget("https://hypernova-mac.tail1234.ts.net:47321", null).hostPort)
        assertEquals("100.64.0.9:47321", TailnetTarget("https://100.64.0.9:47321", fp).hostPort)
        assertEquals("[fd7a:115c:a1e0::9]:47321", TailnetTarget("https://[fd7a:115c:a1e0::9]:47321", fp).hostPort)
    }

    @Test fun oneStateFolderPerControlServer() {
        assertEquals("default", TsnetPolicy.stateKey(null))
        assertEquals("default", TsnetPolicy.stateKey("  "))
        val a = TsnetPolicy.stateKey("https://headscale.example")
        assertEquals(32, a.length)
        // The same server however it's written; another server another folder.
        assertEquals(a, TsnetPolicy.stateKey("HTTPS://headscale.example/"))
        assertNotEquals(a, TsnetPolicy.stateKey("https://other.example"))
        assertNotEquals("default", a)
    }

    @Test fun aStartThatFailsLateOnlyUndoesItselfWhileItIsTheLatest() {
        assertTrue(TsnetPolicy.isCurrentStart(3, 3))
        // A stop, or a newer start, bumped the generation since.
        assertFalse(TsnetPolicy.isCurrentStart(3, 4))
    }

    @Test fun onlyTailscalesOrTheControlServersSignInLinkOpens() {
        assertTrue(TsnetPolicy.signInUrlOk("https://login.tailscale.com/a/abc123", null))
        assertTrue(TsnetPolicy.signInUrlOk("https://login.tailscale.com/a/abc123", "https://headscale.example"))
        assertFalse(TsnetPolicy.signInUrlOk("http://login.tailscale.com/a/abc123", null))
        assertFalse(TsnetPolicy.signInUrlOk("https://evil.example/login", null))
        assertFalse(TsnetPolicy.signInUrlOk("https://login.tailscale.com.evil.example/", null))
        // Headscale shows its own sign-in page on the control host: https, or http only when the control URL is http to a private address.
        assertTrue(TsnetPolicy.signInUrlOk("https://headscale.example/register/abc", "https://headscale.example"))
        assertTrue(TsnetPolicy.signInUrlOk("http://192.168.0.125:49508/register/abc", "http://192.168.0.125:49508"))
        assertFalse(TsnetPolicy.signInUrlOk("http://headscale.example/register/abc", "https://headscale.example"))
        assertFalse(TsnetPolicy.signInUrlOk("https://other.example/register/abc", "https://headscale.example"))
        assertFalse(TsnetPolicy.signInUrlOk("not a url", null))
    }

    @Test fun theNodeStatusCarriesTheProxySecret() {
        val s = TsnetStatus.parse("""{"state":"Running","proxyPort":41234,"proxyToken":"0123abcd"}""")
        assertTrue(s.running)
        assertEquals("0123abcd", s.proxyToken)
        // A proxy without a secret is not a route.
        assertFalse(TsnetStatus.parse("""{"state":"Running","proxyPort":41234}""").running)
    }
}
