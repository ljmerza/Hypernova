package com.prudnikweb.hypernova.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The terminal's socket follows the active route (docs/PROTOCOL.md, "Shared terminals"): over
 * Tailscale the page opens it itself, on the local route the app opens it with the pinned client.
 */
class TermTicketTest {
    private val ticket = "/v1/terms/t-1/ws?ticket=abc123"

    @Test fun localRouteKeepsTheMacsAddressAndItsPinnedClient() {
        val pinned = OkHttpClient()
        val link = TermTicket.of(ticket, NetRoute("https://192.168.1.20:47323", RouteKind.Local, pinned, "ab".repeat(32)))
        assertEquals("wss://192.168.1.20:47323/v1/terms/t-1/ws?ticket=abc123", link.url)
        assertSame(pinned, link.client)
    }

    @Test fun remoteRouteLeavesTheSocketToThePage() {
        val link = TermTicket.of(ticket, NetRoute.remote("https://mac.tail1234.ts.net"))
        assertEquals("wss://mac.tail1234.ts.net/v1/terms/t-1/ws?ticket=abc123", link.url)
        assertNull(link.client)
    }

    @Test fun anIpv6LocalAddressKeepsItsBrackets() {
        val link = TermTicket.of(ticket, NetRoute(RoutePolicy.baseOf("fd00::5", 47323), RouteKind.Local, OkHttpClient()))
        assertEquals("wss://[fd00::5]:47323/v1/terms/t-1/ws?ticket=abc123", link.url)
    }

    @Test fun aWholeUrlFromTheDaemonMovesToTheRoute() {
        // An older daemon, or one behind a proxy, may hand out a whole URL: the route decides the origin.
        val link = TermTicket.of("https://mac.tail1234.ts.net/v1/terms/t-1/ws?ticket=abc123", NetRoute("https://10.0.0.7:47323", RouteKind.Local, OkHttpClient()))
        assertEquals("wss://10.0.0.7:47323/v1/terms/t-1/ws?ticket=abc123", link.url)
    }

    @Test fun plainHttpBecomesWs() {
        val link = TermTicket.of(ticket, NetRoute.remote("http://10.0.2.2:47321"))
        assertEquals("ws://10.0.2.2:47321/v1/terms/t-1/ws?ticket=abc123", link.url)
    }

    @Test fun theSizeGoesOnTheQueryAsThePageAddsIt() {
        assertEquals("wss://h/v1/terms/t/ws?ticket=k&cols=80&rows=24", TermTicket.sized("wss://h/v1/terms/t/ws?ticket=k", 80, 24))
        assertEquals("wss://h/x?cols=80&rows=24", TermTicket.sized("wss://h/x", 80, 24))
    }
}
