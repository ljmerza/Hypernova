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
        val l = PairLinks.parse("hypernova://pair?v=1&u=https%3A%2F%2Fmac.example.com%3A47321&c=abc123&n=MacBook%20Air")!!
        assertEquals("https://mac.example.com:47321", l.baseUrl)
        assertEquals("abc123", l.code)
        assertEquals("MacBook Air", l.macName)
        assertNull(l.fingerprint)
        assertFalse(l.hasLan)
        assertEquals(LanInfo.DEFAULT_PORT, l.lanPort)
    }

    @Test fun readsTheListenerFromTheAppLink() {
        val l = PairLinks.parse("Scan me hypernova://pair?v=1&u=https%3A%2F%2Fmac.example.com%3A47321&c=abc&n=Studio&fp=${fp.uppercase()}&lan=192.168.1.20%2Cfd00%3A%3A1&lp=47323 thanks")!!
        assertEquals(fp, l.fingerprint)
        assertEquals(listOf("192.168.1.20", "fd00::1"), l.lan)
        assertEquals(47323, l.lanPort)
        assertTrue(l.hasLan)
        // Unencoded commas work too (the Mac writes them plain).
        assertEquals(listOf("10.0.2.2", "10.0.2.3"), PairLinks.parse("hypernova://pair?v=1&u=http://10.0.2.2:49271&c=x&fp=$fp&lan=10.0.2.2,10.0.2.3&lp=49273")!!.lan)
    }

    @Test fun readsTheWebLinkAndTheIntentHandOver() {
        val w = PairLinks.parse("https://mac.example.com:47321/pair?c=abc&n=Studio&fp=$fp&lan=192.168.1.20&lp=47323")!!
        assertEquals("https://mac.example.com:47321", w.baseUrl)
        assertEquals("Studio", w.macName)
        assertEquals(fp, w.fingerprint)
        assertEquals(listOf("192.168.1.20"), w.lan)
        val i = PairLinks.parse("intent://pair?v=1&u=https%3A%2F%2Fmac.example.com%3A47321&c=abc&fp=$fp&lan=192.168.1.20&lp=47323#Intent;scheme=hypernova;end")!!
        assertEquals("abc", i.code)
        assertEquals(47323, i.lanPort)
        assertEquals("mac", PairLinks.parse("https://mac.example.com:47321/pair?c=abc")!!.macName)
    }

    @Test fun readsTheHypernovaObserverLinkFromItsFragmentExactlyAsTheAppLink() {
        // The Mac's QR code (docs/PROTOCOL.md, "Pairing"): the app link's parameters after the #.
        val params = "v=1&u=https%3A%2F%2Fmac.example.com%3A47321&c=abc123&n=MacBook%20Air&fp=$fp&lan=192.168.1.20%2Cfd00%3A%3A1&lp=47323"
        val u = PairLinks.parse("https://hypernova.observer/pair#$params")!!
        assertEquals(PairLinks.parse("hypernova://pair?$params"), u)
        assertEquals("https://mac.example.com:47321", u.baseUrl)
        assertEquals("abc123", u.code)
        assertEquals("MacBook Air", u.macName)
        assertEquals(fp, u.fingerprint)
        assertEquals(listOf("192.168.1.20", "fd00::1"), u.lan)
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
        val l = PairLinks.parse("hypernova://pair?v=1&u=https://mac.example.com:47321&c=abc&fp=nope&lan=192.168.1.20&lp=47323")!!
        assertEquals("abc", l.code)
        assertFalse(l.hasLan)
        // A fingerprint without addresses: nothing to try.
        assertFalse(PairLinks.parse("hypernova://pair?v=1&u=https://mac.example.com:47321&c=abc&fp=$fp")!!.hasLan)
        // A bad port falls back to the default one.
        assertEquals(LanInfo.DEFAULT_PORT, PairLinks.parse("hypernova://pair?v=1&u=https://mac.example.com:47321&c=abc&fp=$fp&lan=1.2.3.4&lp=99999")!!.lanPort)
        assertNull(PairLinks.parse("hypernova://pair?v=2&u=https://mac.example.com:47321&c=abc"))
        assertNull(PairLinks.parse("hypernova://pair?v=1&u=ftp://x&c=abc"))
        assertNull(PairLinks.parse("hello"))
        assertNull(PairLinks.parse(null))
    }

    @Test fun anOlderComputersTailnetHintsAreIgnored() {
        // ts, ti, ta and tc came from a computer with Tailscale; this app has no use for them, and
        // the link pairs as if they weren't there.
        val plain = PairLinks.parse("hypernova://pair?v=1&u=https%3A%2F%2Fmac.example.com%3A47321&c=abc&n=Mac&fp=$fp&lan=192.168.1.20&lp=47323")!!
        val hinted = PairLinks.parse("hypernova://pair?v=1&u=https%3A%2F%2Fmac.example.com%3A47321&c=abc&n=Mac&fp=$fp&lan=192.168.1.20&lp=47323&ts=hypernova-mac.tail1234.ts.net&ti=100.64.0.9&ta=you%40example.com&tc=https%3A%2F%2Fheadscale.example")!!
        assertEquals(plain, hinted)
        assertTrue(hinted.hasLan)
    }
}
