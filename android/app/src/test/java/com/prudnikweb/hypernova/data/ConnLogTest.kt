package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connection log's pure parts (data/ConnLog.kt): no secrets in, the newest kept, times, the copy. */
class ConnLogTest {
    @Test fun aUrlBecomesItsHostAndPortAndNothingSecretGetsIn() {
        assertEquals("192.168.0.149:47323", ConnScrub.text("https://192.168.0.149:47323/v1/health?token=abc"))
        assertEquals("failed to reach mac.tail1234.ts.net:47321 now", ConnScrub.text("failed to reach https://mac.tail1234.ts.net:47321/v1/ws?ticket=xyz now"))
        // A pairing link's code goes with the rest of the link.
        assertEquals("pair", ConnScrub.text("hypernova://pair?v=1&u=https%3A%2F%2F100.64.0.1&c=SECRETCODE"))
        // Credentials, tokens and key-like runs are cut.
        assertEquals("Authorization: Bearer …", ConnScrub.text("Authorization: Bearer hn_abcdefghijklmnop"))
        assertEquals("proxy Basic …", ConnScrub.text("proxy Basic aHlwZXJub3ZhOnNlY3JldA=="))
        assertEquals("token … sent", ConnScrub.text("token hn_0123456789abcdef sent"))
        assertEquals("key … here", ConnScrub.text("key tskey-auth-kAbCdEf-123456 here"))
        val fp = "b43b818111636406f6a6115a8574b6141a370d6e2c0821f30f183541dea99714"
        assertEquals("pin b43b81…", ConnScrub.text("pin $fp"))
        // A user:password in a URL never survives either.
        assertEquals("host:1", ConnScrub.text("https://user:pw@host:1/x"))
        assertNull(ConnScrub.text(null))
        assertNull(ConnScrub.text("   "))
        assertEquals(ConnScrub.MAX, ConnScrub.text("word ".repeat(100))!!.length)
    }

    @Test fun hostPortOfABase() {
        assertEquals("192.168.0.149:47323", ConnScrub.hostPort("https://192.168.0.149:47323"))
        assertEquals("[fd00::5]:47323", ConnScrub.hostPort("https://[fd00::5]:47323"))
        assertEquals("mac.ts.net:443", ConnScrub.hostPort("https://mac.ts.net/x?y=1"))
        assertNull(ConnScrub.hostPort(null))
        assertNull(ConnScrub.hostPort("not a url"))
    }

    @Test fun theRingKeepsTheNewestAndOlderOnesGoInFront() {
        val r = ConnRing(3)
        for (i in 1..5) r.add(ConnEntry(i * 1000L, ConnKind.Probe, text = "e$i"))
        assertEquals(listOf("e5", "e4", "e3"), r.newestFirst().map { it.text })
        assertEquals(listOf("e4", "e5"), r.last(2).map { it.text })
        // What was on disk (older) goes in front of what came since, and the cap holds.
        val fresh = ConnRing(4)
        fresh.add(ConnEntry(10_000L, ConnKind.Link, text = "new"))
        fresh.prepend(listOf(ConnEntry(1_000L, ConnKind.Link, text = "a"), ConnEntry(2_000L, ConnKind.Link, text = "b")))
        assertEquals(listOf("new", "b", "a"), fresh.newestFirst().map { it.text })
    }

    @Test fun anEntryGoesToDiskAndBack() {
        val e = ConnEntry(1_700_000_000_123L, ConnKind.Probe, ConnResult.Refused, RouteKind.Local, "192.168.0.149:47323", "connect failed", 12)
        assertEquals(e, ConnText.decode(ConnText.encode(e)))
        val bare = ConnEntry(5L, ConnKind.Wait)
        assertEquals(bare, ConnText.decode(ConnText.encode(bare)))
        assertNull(ConnText.decode("{not json"))
    }

    @Test fun agesInTheScreensWords() {
        val now = 1_000_000_000L
        assertEquals("now", ConnText.ago(now, now - 2_000))
        assertEquals("12s", ConnText.ago(now, now - 12_000))
        assertEquals("4m", ConnText.ago(now, now - 4 * 60_000 - 5_000))
        assertEquals("2h", ConnText.ago(now, now - 2 * 3_600_000))
        assertEquals("3d", ConnText.ago(now, now - 3 * 86_400_000L))
        // A clock that went back says now, never a negative age.
        assertEquals("now", ConnText.ago(now, now + 60_000))
    }

    @Test fun theCopyIsOldestFirstWithoutDotsOrSecrets() {
        val entries = listOf(
            ConnEntry(3_000L, ConnKind.Route, ConnResult.Ok, RouteKind.Local, "192.168.0.149:47323", "Local answered", 40),
            ConnEntry(1_000L, ConnKind.Network, text = "the network changed: Wi-Fi"),
            ConnEntry(2_000L, ConnKind.Probe, ConnResult.NoPeer, RouteKind.Tailscale, "mac.ts.net:47321", "through the built-in node: proxy 502", 812),
        ).map(ConnScrub::entry)
        val text = ConnText.copy(entries, "Hypernova connection log") { "t$it" }
        val lines = text.trim().lines()
        assertEquals("Hypernova connection log", lines[0])
        assertTrue(lines[1].startsWith("t1000  network"))
        assertTrue(lines[2].startsWith("t2000  probe    tailnet   nopeer"))
        assertTrue(lines[2].contains("mac.ts.net:47321  812ms  through the built-in node: proxy 502"))
        assertTrue(lines[3].contains("local") && lines[3].contains("Local answered"))
        assertFalse(text.contains("·"))
    }
}
