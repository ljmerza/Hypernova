package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing link (docs/PROTOCOL.md, "Pairing"), with the local listener's `fp`, `lan` and `lp`. */
class PairLinksTest {
    private val fp = "14aa650b9381cf8edbde5b00003197004a720ad4b7d972b07e5775029879da6c"

    @Test fun readsTheAppLink() {
        val l = PairLinks.parse("hypernova://pair?v=1&u=https%3A%2F%2Fmac.tail1234.ts.net%3A47321&c=abc123&n=MacBook%20Air")!!
        assertEquals("https://mac.tail1234.ts.net:47321", l.baseUrl)
        assertEquals("abc123", l.code)
        assertEquals("MacBook Air", l.macName)
        assertNull(l.fingerprint)
        assertFalse(l.hasLan)
        assertEquals(LanInfo.DEFAULT_PORT, l.lanPort)
    }

    @Test fun readsTheListenerFromTheAppLink() {
        val l = PairLinks.parse("Scan me hypernova://pair?v=1&u=https%3A%2F%2Fmac.ts.net%3A47321&c=abc&n=Studio&fp=${fp.uppercase()}&lan=192.168.1.20%2Cfd7a%3A115c%3A%3A1&lp=47323 thanks")!!
        assertEquals(fp, l.fingerprint)
        assertEquals(listOf("192.168.1.20", "fd7a:115c::1"), l.lan)
        assertEquals(47323, l.lanPort)
        assertTrue(l.hasLan)
        // Unencoded commas work too (the Mac writes them plain).
        assertEquals(listOf("10.0.2.2", "10.0.2.3"), PairLinks.parse("hypernova://pair?v=1&u=http://10.0.2.2:49271&c=x&fp=$fp&lan=10.0.2.2,10.0.2.3&lp=49273")!!.lan)
    }

    @Test fun readsTheWebLinkAndTheIntentHandOver() {
        val w = PairLinks.parse("https://mac.ts.net:47321/pair?c=abc&n=Studio&fp=$fp&lan=192.168.1.20&lp=47323")!!
        assertEquals("https://mac.ts.net:47321", w.baseUrl)
        assertEquals("Studio", w.macName)
        assertEquals(fp, w.fingerprint)
        assertEquals(listOf("192.168.1.20"), w.lan)
        val i = PairLinks.parse("intent://pair?v=1&u=https%3A%2F%2Fmac.ts.net%3A47321&c=abc&fp=$fp&lan=192.168.1.20&lp=47323#Intent;scheme=hypernova;end")!!
        assertEquals("abc", i.code)
        assertEquals(47323, i.lanPort)
        assertEquals("mac", PairLinks.parse("https://mac.ts.net:47321/pair?c=abc")!!.macName)
    }

    @Test fun readsTheHypernovaObserverLinkFromItsFragmentExactlyAsTheAppLink() {
        // The Mac's QR code (docs/PROTOCOL.md, "Pairing"): the app link's parameters after the #.
        val params = "v=1&u=https%3A%2F%2Fmac.tail1234.ts.net%3A47321&c=abc123&n=MacBook%20Air&fp=$fp&lan=192.168.1.20%2Cfd7a%3A115c%3A%3A1&lp=47323&ts=mac.tail1234.ts.net&ti=100.64.0.9&ta=you%40example.com&tc=https%3A%2F%2Fheadscale.example"
        val u = PairLinks.parse("https://hypernova.observer/pair#$params")!!
        assertEquals(PairLinks.parse("hypernova://pair?$params"), u)
        assertEquals("https://mac.tail1234.ts.net:47321", u.baseUrl)
        assertEquals("abc123", u.code)
        assertEquals("MacBook Air", u.macName)
        assertEquals(fp, u.fingerprint)
        assertEquals(listOf("192.168.1.20", "fd7a:115c::1"), u.lan)
        assertEquals("you@example.com", u.tailnetAccount)
        assertEquals("https://headscale.example", u.controlUrl)
        // Pasted with words around it, or as the landing page's intent hand-over.
        assertEquals(u, PairLinks.parse("Pair: HTTPS://hypernova.observer/pair#$params thanks"))
        assertEquals(u, PairLinks.parse("intent://pair?$params#Intent;scheme=hypernova;package=com.prudnikweb.hypernova;S.browser_fallback_url=https%3A%2F%2Fhypernova.observer%2Fpair%23get;end"))
        // The same rules as the app link: a version it doesn't know, no base, no code.
        assertNull(PairLinks.parse("https://hypernova.observer/pair#v=2&u=https%3A%2F%2Fm&c=abc"))
        assertNull(PairLinks.parse("https://hypernova.observer/pair#c=abc"))
        assertNull(PairLinks.parse("https://hypernova.observer/pair#u=https%3A%2F%2Fm"))
        assertNull(PairLinks.parse("https://hypernova.observer/pair#get"))
        assertNull(PairLinks.parse("https://hypernova.observer/pair"))
    }

    @Test fun theLinksDomainIsNeverAMac() {
        // A query there (which would have reached its server) is no Mac's pairing page.
        assertNull(PairLinks.parse("https://hypernova.observer/pair?c=abc&n=Mac"))
        assertNull(PairLinks.parse("https://api.hypernova.observer/pair?c=abc"))
        // A computer's own name under it is (the local network's pairing page, names.ts).
        assertEquals("https://l-k3x9mq2z7a.hypernova.observer:47323", PairLinks.parse("https://l-k3x9mq2z7a.hypernova.observer:47323/pair?c=abc")!!.baseUrl)
    }

    @Test fun whichLinkOpenedTheApp() {
        assertEquals(PairLinks.Kind.Pair, PairLinks.kind("hypernova://pair?v=1&u=x&c=y"))
        assertEquals(PairLinks.Kind.Pair, PairLinks.kind("https://hypernova.observer/pair#v=1&u=x&c=y"))
        assertEquals(PairLinks.Kind.Pair, PairLinks.kind("HTTPS://Hypernova.Observer/pair"))
        for (other in listOf(null, "", "hypernova://other", "hypernova://pair/x", "https://hypernova.observer/", "https://hypernova.observer/pairing",
            "https://evil.example/pair#v=1&u=x&c=y", "http://hypernova.observer/pair", "https://hypernova.observer.evil.example/pair", "https://api.hypernova.observer/pair")) {
            assertNull(other, PairLinks.kind(other))
        }
    }

    @Test fun aBadListenerIsLeftOut() {
        // A fingerprint that isn't SHA-256 hex drops the listener; the pairing itself still works.
        val l = PairLinks.parse("hypernova://pair?v=1&u=https://mac.ts.net:47321&c=abc&fp=nope&lan=192.168.1.20&lp=47323")!!
        assertEquals("abc", l.code)
        assertFalse(l.hasLan)
        // A fingerprint without addresses: nothing to try.
        assertFalse(PairLinks.parse("hypernova://pair?v=1&u=https://mac.ts.net:47321&c=abc&fp=$fp")!!.hasLan)
        // A bad port falls back to the default one.
        assertEquals(LanInfo.DEFAULT_PORT, PairLinks.parse("hypernova://pair?v=1&u=https://mac.ts.net:47321&c=abc&fp=$fp&lan=1.2.3.4&lp=99999")!!.lanPort)
        assertNull(PairLinks.parse("hypernova://pair?v=2&u=https://mac.ts.net:47321&c=abc"))
        assertNull(PairLinks.parse("hypernova://pair?v=1&u=ftp://x&c=abc"))
        assertNull(PairLinks.parse("hello"))
        assertNull(PairLinks.parse(null))
    }

    @Test fun readsTheTailnetHints() {
        // ts, ti, ta and tc (docs/PROTOCOL.md, "Tailscale built in"), from the computer's pairing link.
        val l = PairLinks.parse("hypernova://pair?v=1&u=https%3A%2F%2Fhypernova-mac.tail1234.ts.net%3A47321&c=abc&n=Mac&ts=Hypernova-Mac.tail1234.ts.net&ti=100.64.0.9&ta=you%40example.com&tc=https%3A%2F%2Fheadscale.example%3A8080%2F")!!
        assertTrue(l.hasTailnet)
        assertEquals("hypernova-mac.tail1234.ts.net", l.tailnetName)
        assertEquals("100.64.0.9", l.tailnetIp)
        assertEquals("you@example.com", l.tailnetAccount)
        assertEquals("https://headscale.example:8080", l.controlUrl)
        // The web link carries them the same way, after the listener's.
        val w = PairLinks.parse("https://hypernova-mac.tail1234.ts.net:47321/pair?c=abc&n=Mac&fp=$fp&lan=192.168.1.20&lp=47323&ts=hypernova-mac.tail1234.ts.net&ta=you@example.com")!!
        assertTrue(w.hasLan)
        assertEquals("hypernova-mac.tail1234.ts.net", w.tailnetName)
        assertEquals("you@example.com", w.tailnetAccount)
        assertNull(w.controlUrl)
        // A name that isn't one drops all four; an odd account or control server is dropped alone.
        val bad = PairLinks.parse("hypernova://pair?v=1&u=https%3A%2F%2Fm.ts.net%3A47321&c=abc&ts=not%20a%20name&ti=100.64.0.9&ta=you%40example.com")!!
        assertFalse(bad.hasTailnet)
        assertNull(bad.tailnetAccount)
        val odd = PairLinks.parse("hypernova://pair?v=1&u=https%3A%2F%2Fm.ts.net%3A47321&c=abc&ts=m.ts.net&ta=%3Cscript%3E&tc=ftp%3A%2F%2Fx")!!
        assertEquals("m.ts.net", odd.tailnetName)
        assertNull(odd.tailnetAccount)
        assertNull(odd.controlUrl)
        // A link without them says nothing about a tailnet.
        assertFalse(PairLinks.parse("hypernova://pair?v=1&u=https%3A%2F%2Fm.ts.net%3A47321&c=abc")!!.hasTailnet)
    }

    @Test fun aControlServerMustBeHttpsOrAPrivateAddress() {
        // https anywhere; plain http only to a private address (a Headscale on the LAN, a test).
        assertTrue(PairLinks.controlUrlOk("https://headscale.example"))
        assertTrue(PairLinks.controlUrlOk("https://headscale.example:8443"))
        assertTrue(PairLinks.controlUrlOk("http://192.168.0.125:49508"))
        assertTrue(PairLinks.controlUrlOk("http://10.0.2.2:49508"))
        assertTrue(PairLinks.controlUrlOk("http://100.64.0.1:8080"))
        assertTrue(PairLinks.controlUrlOk("http://localhost:8080"))
        assertFalse(PairLinks.controlUrlOk("http://headscale.example:8080"))
        assertFalse(PairLinks.controlUrlOk("http://8.8.8.8"))
        assertFalse(PairLinks.controlUrlOk("https://user:pw@headscale.example"))
        assertFalse(PairLinks.controlUrlOk("https://headscale.example/?x=1"))
        assertFalse(PairLinks.controlUrlOk("ftp://headscale.example"))
        assertFalse(PairLinks.controlUrlOk("https://" + "a".repeat(200)))
        // A link with a bad one keeps the tailnet name and drops the server.
        val l = PairLinks.parse("hypernova://pair?v=1&u=https%3A%2F%2Fm.ts.net%3A47321&c=abc&ts=m.ts.net&tc=http%3A%2F%2Fheadscale.example%3A8080")!!
        assertEquals("m.ts.net", l.tailnetName)
        assertNull(l.controlUrl)
        assertTrue(PairLinks.privateHost("172.16.5.5"))
        assertFalse(PairLinks.privateHost("010.0.0.1"))
        assertFalse(PairLinks.privateHost("192.168.01.5"))
        assertFalse(PairLinks.privateHost("172.32.0.1"))
        assertTrue(PairLinks.privateHost("fd7a:115c:a1e0::1"))
        assertFalse(PairLinks.privateHost("2001:db8::1"))
    }
}
