package com.prudnikweb.hypernova.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The bridge's HTTP (BridgeHttp): reading Lens's requests, judging them before any body is read,
 * where each goes, and above all what goes on to the Mac (the phone's credentials and the glasses'
 * marker, never the bridge token or anything that claims to be the bridge, and never another host)
 * and what comes back.
 */
class BridgeHttpTest {
    private val port = 47380

    private fun input(raw: String): InputStream = ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1))

    private fun head(raw: String): HttpRequest = BridgeHttp.readHead(input(raw))!!

    private fun req(method: String = "GET", target: String = "/v1/snapshot", headers: Map<String, String> = emptyMap()): HttpRequest =
        head("$method $target HTTP/1.1\r\n" + headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n")

    /** A request as Lens sends it: the bridge's Host and Lens's origin, plus [extra]. */
    private fun lens(method: String, target: String, extra: Map<String, String> = emptyMap()) =
        req(method, target, mapOf("Host" to "127.0.0.1:$port", "Origin" to "http://127.0.0.1:40123") + extra)

    private fun names(h: List<Pair<String, String>>) = h.map { it.first.lowercase() }
    private fun value(h: List<Pair<String, String>>, name: String) = h.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    // ─── Reading ────────────────────────────────────────────────────────────

    @Test fun readsTheHeadAloneAndTheBodyOnlyWhenAsked() {
        val stream = input("POST /v1/sessions/s1/prompt?x=1&token=hnb_abc HTTP/1.1\r\nHost: 127.0.0.1:47380\r\nContent-Type: application/json\r\nContent-Length: 13\r\nX-Twice: one\r\nx-twice: two\r\n\r\n{\"text\":\"hi\"}")
        val r = BridgeHttp.readHead(stream)!!
        assertEquals("POST", r.method)
        assertEquals("/v1/sessions/s1/prompt", r.path)
        assertEquals("x=1&token=hnb_abc", r.query)
        assertEquals("application/json", r.header("Content-Type"))
        assertEquals("one", r.header("x-twice"))
        assertFalse(r.isUpgrade)
        assertTrue(r.hasBody)
        // The body is still waiting in the stream: nothing of it was read with the head.
        assertEquals(13, stream.available())
        assertEquals("{\"text\":\"hi\"}", String(BridgeHttp.readBody(stream, r)))
    }

    @Test fun readsAChunkedBody() {
        val stream = input("PUT /bridge/settings HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\n{\"a\":\r\n4\r\ntrue\r\n1\r\n}\r\n0\r\n\r\n")
        val r = BridgeHttp.readHead(stream)!!
        assertEquals("{\"a\":true}", String(BridgeHttp.readBody(stream, r)))
    }

    @Test fun refusesWhatIsntHttp() {
        assertNull(BridgeHttp.readHead(ByteArrayInputStream(ByteArray(0))))
        assertTrue(runCatching { head("hello\r\n\r\n") }.isFailure)
        assertTrue(runCatching { head("GET / HTTP/1.1 extra\r\n\r\n") }.isFailure)
        assertTrue(runCatching { head("G\u0000T / HTTP/1.1\r\n\r\n") }.isFailure)
        assertTrue(runCatching { head("GET / HTTP/1.1\r\nX: " + "a".repeat(BridgeHttp.MAX_HEAD + 10) + "\r\n\r\n") }.isFailure)
    }

    @Test fun aBodyTooLargeIsRefusedBeforeAnyOfItIsRead() {
        // 32 MB declared and nothing sent: refused at once, not allocated and waited for.
        val stream = input("POST /bridge/request HTTP/1.1\r\nContent-Length: 33554432\r\n\r\n")
        val r = BridgeHttp.readHead(stream)!!
        assertTrue(runCatching { BridgeHttp.readBody(stream, r) }.exceptionOrNull() is TooLarge)
        // A signed call's body (1 MB at most, LensBridge) the same: refused by its length alone.
        assertTrue(runCatching { BridgeHttp.readBody(stream, head("POST /v1/x HTTP/1.1\r\nContent-Length: ${1024 * 1024 + 1}\r\n\r\n"), 1024 * 1024) }.exceptionOrNull() is TooLarge)
        assertTrue(runCatching { BridgeHttp.readBody(stream, head("POST /bridge/request HTTP/1.1\r\nContent-Length: -1\r\n\r\n")) }.isFailure)
        // A chunked body is cut off once it passes the cap, however its chunks are sized.
        val big = "4000\r\n" + "a".repeat(0x4000) + "\r\n" + "10\r\n" + "b".repeat(16) + "\r\n0\r\n\r\n"
        val chunked = input("PUT /bridge/settings HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n$big")
        assertTrue(runCatching { BridgeHttp.readBody(chunked, BridgeHttp.readHead(chunked)!!) }.exceptionOrNull() is TooLarge)
    }

    /** The finding: `total + size > max` wrapped for a chunk of 0x7fffffffffffffff after one byte, so the cap let it through. */
    @Test fun aHugeChunkCantWrapTheCap() {
        for (max in listOf(BridgeHttp.MAX_SMALL_BODY, 1024 * 1024)) {
            val s = input("POST /v1/x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n7fffffffffffffff\r\nbbbbbbbb")
            assertTrue("max $max", runCatching { BridgeHttp.readBody(s, BridgeHttp.readHead(s)!!, max) }.exceptionOrNull() is TooLarge)
        }
        // The same, as one of the bridge's own small bodies.
        val small = input("PUT /bridge/settings HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n7fffffffffffffff\r\nb")
        assertTrue(runCatching { BridgeHttp.readBody(small, BridgeHttp.readHead(small)!!) }.exceptionOrNull() is TooLarge)
        // Too big for a Long at all: not a chunk.
        val over = input("PUT /bridge/settings HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n10000000000000000\r\nb")
        assertTrue(runCatching { BridgeHttp.readBody(over, BridgeHttp.readHead(over)!!) }.isFailure)
    }

    @Test fun trailersAreFewAndShort() {
        val few = "1\r\na\r\n0\r\n" + (1..BridgeHttp.MAX_TRAILERS).joinToString("") { "X-T$it: v\r\n" } + "\r\n"
        val ok = input("PUT /bridge/settings HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n$few")
        assertEquals("a", String(BridgeHttp.readBody(ok, BridgeHttp.readHead(ok)!!)))
        val many = "1\r\na\r\n0\r\n" + (0..BridgeHttp.MAX_TRAILERS).joinToString("") { "X-T$it: v\r\n" } + "\r\n"
        val tooMany = input("PUT /bridge/settings HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n$many")
        assertTrue(runCatching { BridgeHttp.readBody(tooMany, BridgeHttp.readHead(tooMany)!!) }.isFailure)
        val long = input("PUT /bridge/settings HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\nX: ${"v".repeat(4000)}\r\n\r\n")
        assertTrue(runCatching { BridgeHttp.readBody(long, BridgeHttp.readHead(long)!!) }.isFailure)
    }

    /**
     * The finding: a sender trickling one byte of its head every half second kept its connection
     * (and a slot) for ever, a socket's own timeout counting only from the last byte. The whole
     * head now has a deadline.
     */
    @Test fun aTricklingHeadIsCutOffAtItsDeadline() {
        var now = 1_000_000L
        val timeouts = ArrayList<Int>()
        val trickle = object : InputStream() {
            val head = "GET /bridge/ping HTTP/1.1\r\nX-Pad: ".toByteArray()
            var i = 0
            override fun read(): Int { now += 500; return if (i < head.size) head[i++].toInt() else 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = read().toByte(); return 1 }
        }
        val d = DeadlineInput(trickle, { now }) { timeouts += it }
        d.deadline(now + 10_000)
        val started = now
        val e = runCatching { BridgeHttp.readHead(java.io.BufferedInputStream(d)) }.exceptionOrNull()
        assertTrue("$e", e is java.net.SocketTimeoutException)
        assertTrue(now - started in 10_000..10_500)
        // Each read was given only what was left of the deadline.
        assertTrue(timeouts.first() <= 10_000 && timeouts.last() <= 500)
        // Once the head is in, a carried body has its time between reads instead.
        d.perRead(15_000)
        assertEquals(15_000, timeouts.last())
        now += 60_000
        assertTrue(d.read() >= 0)
    }

    /** Two Hosts (or Origins, lengths…): judged by one and read by the other, once; refused now (RFC 9112). */
    @Test fun aHeaderTheBridgeJudgesBySentTwiceIsRefused() {
        for (h in listOf("Host: 127.0.0.1:47380\r\nHost: evil.example", "Origin: http://127.0.0.1:4000\r\nOrigin: http://evil.example", "Content-Length: 1\r\nContent-Length: 100",
            "Authorization: HNB3 a\r\nAuthorization: HNB3 b", "Transfer-Encoding: chunked\r\nContent-Length: 5", "Cookie: a=1\r\nCookie: b=2")) {
            assertTrue(h, runCatching { head("GET /v1/snapshot HTTP/1.1\r\n$h\r\n\r\n") }.isFailure)
        }
        // Others keep their first value, as before.
        assertEquals("one", head("GET / HTTP/1.1\r\nX-Twice: one\r\nX-Twice: two\r\n\r\n").header("x-twice"))
    }

    // ─── Targets and the Mac's URL ──────────────────────────────────────────

    @Test fun onlyAPathIsATarget() {
        assertTrue(BridgeHttp.targetOk("/v1/snapshot"))
        assertTrue(BridgeHttp.targetOk("/p/abc/index.html?x=1"))
        assertTrue(BridgeHttp.targetOk("/@evil.example/x"))
        for (t in listOf("@evil.example/x", ".evil.example/x", ":8443/x", "//evil.example/x", "http://evil.example/x", "evil.example:443", "*", "", "/a b", "/a\tb")) {
            assertFalse(t, BridgeHttp.targetOk(t))
        }
    }

    @Test fun theMacsUrlIsBuiltNeverJoined() {
        val ts = "https://nikitas-mac.tail1234.ts.net"
        assertEquals("$ts/v1/snapshot", BridgeHttp.upstreamUrl(ts, "/v1/snapshot", null).toString())
        assertEquals("$ts/v1/ws?lang=en", BridgeHttp.upstreamUrl("$ts/", "/v1/ws", "lang=en").toString())
        assertEquals("https://192.168.1.5:47323/p/tok/app.js?v=2", BridgeHttp.upstreamUrl("https://192.168.1.5:47323", "/p/tok/app.js", "v=2").toString())
        assertEquals("http://10.0.2.2:49401/v1/x%20y", BridgeHttp.upstreamUrl("http://10.0.2.2:49401", "/v1/x%20y", null).toString())
        // Joined as strings, each of these named another host (or another port); built, none can.
        for (p in listOf("@evil.example/x", ".evil.example/x", ":8443/x", "//evil.example/x", "@10.0.2.2:49402/local/devices")) {
            assertNull(p, BridgeHttp.upstreamUrl(ts, p, null))
        }
        // In the path, an @ or a host name is only a path on the paired Mac.
        val at = BridgeHttp.upstreamUrl(ts, "/@evil.example/x", "a=@evil.example")!!
        assertEquals("nikitas-mac.tail1234.ts.net", at.host)
        assertEquals(443, at.port)
        assertEquals("", at.username)
        assertNull(BridgeHttp.upstreamUrl("not a url", "/v1/x", null))
    }

    @Test fun aPreviewsTokenIsReadFromItsLink() {
        assertEquals("AbC_12-x", BridgeHttp.previewToken("https://mac.ts.net/p/AbC_12-x/index.html"))
        assertEquals("tok", BridgeHttp.previewToken("https://192.168.1.5:47323/p/tok/"))
        assertEquals("tok", BridgeHttp.previewToken("/p/tok/a/b.html?x=1"))
        assertNull(BridgeHttp.previewToken("https://mac.ts.net/v1/shots/x.png"))
        assertNull(BridgeHttp.previewToken("https://mac.ts.net/x/p/tok/"))
    }

    // ─── Routes ─────────────────────────────────────────────────────────────

    @Test fun routes() {
        assertEquals(BridgeRoute.Preflight, BridgeHttp.route("OPTIONS", "/v1/snapshot"))
        assertEquals(BridgeRoute.Ping, BridgeHttp.route("GET", "/bridge/ping"))
        assertEquals(BridgeRoute.Ask, BridgeHttp.route("POST", "/bridge/request"))
        assertEquals(BridgeRoute.NotFound, BridgeHttp.route("GET", "/bridge/request"))
        assertEquals(BridgeRoute.Poll("AbCdEf0123456789-_xy"), BridgeHttp.route("GET", "/bridge/request/AbCdEf0123456789-_xy"))
        assertEquals(BridgeRoute.Reveal("AbCdEf0123456789-_xy"), BridgeHttp.route("POST", "/bridge/request/AbCdEf0123456789-_xy"))
        assertEquals(BridgeRoute.Status, BridgeHttp.route("GET", "/bridge/status"))
        assertEquals(BridgeRoute.Settings, BridgeHttp.route("GET", "/bridge/settings"))
        assertEquals(BridgeRoute.Settings, BridgeHttp.route("PUT", "/bridge/settings"))
        assertEquals(BridgeRoute.Leave, BridgeHttp.route("DELETE", "/bridge/access"))
        // Lens's "Open Hypernova": a signed POST, nothing else.
        assertEquals(BridgeRoute.Open, BridgeHttp.route("POST", "/bridge/open"))
        assertEquals(BridgeRoute.NotFound, BridgeHttp.route("GET", "/bridge/open"))
        assertEquals(BridgeRoute.NotFound, BridgeHttp.route("POST", "/bridge/open/glasses"))
        assertEquals(BridgeRoute.Proxy, BridgeHttp.route("GET", "/v1/snapshot"))
        assertEquals(BridgeRoute.Proxy, BridgeHttp.route("POST", "/v1/sessions/s1/telescope"))
        assertEquals(BridgeRoute.Proxy, BridgeHttp.route("GET", "/v1/ws"))
        // A Telescope picture: never an open route (the fourth review's relay read its name and fetched it
        // unsigned); `/v1/shots/…` is a signed call like any other, and Lens asks by name inside a sealed body.
        assertEquals(BridgeRoute.Proxy, BridgeHttp.route("GET", "/v1/shots/7288f477c461c8f7b097c8a7.png"))
        assertEquals(BridgeRoute.Proxy, BridgeHttp.route("HEAD", "/v1/shots/7288f477c461c8f7b097c8a7.png"))
        assertEquals(BridgeRoute.Picture, BridgeHttp.route("POST", "/bridge/shot"))
        assertEquals(BridgeRoute.NotFound, BridgeHttp.route("GET", "/bridge/shot"))
        assertTrue(BridgeHttp.PICTURE_NAME.matches("7288f477c461c8f7b097c8a7"))
        assertFalse(BridgeHttp.PICTURE_NAME.matches("../x"))
        // A preview: its exact path, to be looked at only, and with its token named for LensBridge to check.
        assertEquals(BridgeRoute.Preview("abc123"), BridgeHttp.route("GET", "/p/abc123/index.html"))
        assertEquals(BridgeRoute.Preview("abc123"), BridgeHttp.route("GET", "/p/abc123"))
        assertEquals(BridgeRoute.NotFound, BridgeHttp.route("POST", "/p/abc123/api"))
        assertEquals(BridgeRoute.NotFound, BridgeHttp.route("GET", "/assets/app.js"))
        assertEquals(BridgeRoute.PreviewFile("tok"), BridgeHttp.route("GET", "/assets/app.js", previewCookie = "tok"))
        assertEquals(BridgeRoute.NotFound, BridgeHttp.route("POST", "/assets/app.js", previewCookie = "tok"))
        assertEquals(BridgeRoute.NotFound, BridgeHttp.route("GET", "/bridge/other", previewCookie = "tok"))
        // The phone's own pairing is never Lens's to touch.
        for (p in listOf("/v1/pair", "/v1/devices/me", "/v1/devices/me/glasses", "/v1/device/cert", "/v1/devices")) {
            assertEquals(p, BridgeRoute.Refused, BridgeHttp.route("POST", p))
            assertEquals(p, BridgeRoute.Refused, BridgeHttp.route("DELETE", p))
        }
        assertEquals(BridgeRoute.NotFound, BridgeHttp.route("GET", "/v1/../local/status"))
        // A path that climbs, however it's written, never goes on: OkHttp would resolve it on the way.
        for (p in listOf("/v1/x/%2e%2e/devices/me", "/v1/x/%2E./devices/me", "/v1/x/.%2e/pair", "/v1/x\\..\\devices/me", "/v1/x%5c..%5cdevices/me", "/v1/./devices/me", "/p/abc/%2e%2e/%2e%2e/v1/devices/me", "/v1/sessions/..")) {
            assertEquals(p, BridgeRoute.NotFound, BridgeHttp.route("DELETE", p))
            assertEquals(p, BridgeRoute.NotFound, BridgeHttp.route("GET", p, previewCookie = "tok"))
        }
        // Dots inside a name are just names.
        assertEquals(BridgeRoute.Proxy, BridgeHttp.route("GET", "/v1/projects/my..app/files"))
        assertEquals(BridgeRoute.NotFound, BridgeHttp.route("GET", "/local/status"))
        assertEquals(BridgeRoute.NotFound, BridgeHttp.route("GET", "/download/android"))
    }

    /** The finding: a preview cookie once carried any target, even one naming another host, with no token. */
    @Test fun aTargetThatIsntAPathGoesNowhereWithOrWithoutACookie() {
        for (t in listOf("@evil.example/x", ".evil.example/x", ":8443/x", "//evil.example/x", "@10.0.2.2:49402/local/devices")) {
            for (cookie in listOf(null, "hn_preview=x")) {
                val r = lens("GET", t, listOfNotNull(cookie?.let { "Cookie" to it }).toMap())
                val g = BridgeHttp.gate(r, port)
                assertEquals("$t $cookie", BridgeHttp.Gate.Stop(400, "bad_request", "Not a path on this phone."), g)
                // Second lock: the route never carries it either.
                assertEquals(t, BridgeRoute.NotFound, BridgeHttp.route("GET", r.path, BridgeHttp.previewCookie(r)))
            }
        }
    }

    // ─── Who may ask ────────────────────────────────────────────────────────

    @Test fun onlyTheEvenAppsLensOriginIsAPage() {
        assertEquals(OriginKind.None, BridgeHttp.originKind(null, port))
        for (o in listOf("http://127.0.0.1:40123", "http://localhost:5177", "http://[::1]:5000", "http://127.0.0.1:49405")) {
            assertEquals(o, OriginKind.Lens, BridgeHttp.originKind(o, port))
        }
        assertEquals(OriginKind.Bridge, BridgeHttp.originKind("http://127.0.0.1:47380", port))
        // "null": a sandboxed frame or a data: page any public site can make, and file: pages.
        for (o in listOf(
            "null", "https://evil.example", "http://8.8.8.8", "file:///sdcard/x.html", "file://", "data:text/html,x",
            // Private network addresses: a captive portal, a router, a laptop on the same Wi-Fi.
            "http://192.168.1.66", "http://192.168.1.20:5177", "http://10.0.2.2:49405", "http://172.16.0.1:80", "http://169.254.1.1",
            // Loopback, but not as the Even App serves Lens.
            "http://127.0.0.1", "https://127.0.0.1:4000", "http://127.0.0.2:4000", "http://localhost", "ws://127.0.0.1:4000",
            "http://127.0.0.1:4000/path", "http://user@127.0.0.1:4000", "http://127.0.0.1.evil.example:4000", "http://localhost.evil.example:4000",
        )) {
            assertEquals(o, OriginKind.Refused, BridgeHttp.originKind(o, port))
        }
        // A build for Lens development takes its dev server on the LAN; the public build never does.
        assertEquals(OriginKind.DevNetwork, BridgeHttp.originKind("http://192.168.1.20:5177", port, devNetwork = true))
        assertEquals(OriginKind.DevNetwork, BridgeHttp.originKind("http://10.0.2.2:49405", port, devNetwork = true))
        assertEquals(OriginKind.Refused, BridgeHttp.originKind("https://evil.example", port, devNetwork = true))
        assertEquals(OriginKind.Refused, BridgeHttp.originKind("null", port, devNetwork = true))
    }

    @Test fun eachRouteTakesOnlyThePagesThatMayMakeIt() {
        val all = listOf(
            BridgeRoute.Preflight, BridgeRoute.Ping, BridgeRoute.Ask, BridgeRoute.Reveal("x"), BridgeRoute.Poll("x"), BridgeRoute.Status, BridgeRoute.Settings, BridgeRoute.Leave, BridgeRoute.Open,
            BridgeRoute.Proxy, BridgeRoute.Picture, BridgeRoute.Preview("t"), BridgeRoute.PreviewFile("t"), BridgeRoute.Refused, BridgeRoute.NotFound,
        )
        for (r in all) {
            assertTrue("$r", BridgeHttp.originFits(r, OriginKind.Lens))
            assertFalse("$r", BridgeHttp.originFits(r, OriginKind.Refused))
        }
        // No Origin: never a page that reads the answer. Not the ask or its poll, which are a page's.
        assertFalse(BridgeHttp.originFits(BridgeRoute.Ask, OriginKind.None))
        assertFalse(BridgeHttp.originFits(BridgeRoute.Reveal("x"), OriginKind.None))
        assertFalse(BridgeHttp.originFits(BridgeRoute.Poll("x"), OriginKind.None))
        assertFalse(BridgeHttp.originFits(BridgeRoute.Preflight, OriginKind.None))
        for (r in listOf(BridgeRoute.Ping, BridgeRoute.Proxy, BridgeRoute.Status, BridgeRoute.Picture, BridgeRoute.Preview("t"))) assertTrue("$r", BridgeHttp.originFits(r, OriginKind.None))
        // The bridge's own pages are previews: their own files, nothing of the bridge's.
        for (r in all) assertEquals("$r", r is BridgeRoute.Preview || r is BridgeRoute.PreviewFile, BridgeHttp.originFits(r, OriginKind.Bridge))
    }

    @Test fun theAskIsRefusedToAPageWithANullOrAPrivateOrigin() {
        for (o in listOf("null", "http://192.168.1.66", "http://10.0.2.2:49405", "https://evil.example")) {
            val g = BridgeHttp.gate(req("POST", "/bridge/request", mapOf("Host" to "127.0.0.1:$port", "Origin" to o, "Content-Type" to "text/plain")), port)
            assertEquals(o, "bridge_origin", (g as BridgeHttp.Gate.Stop).code)
            assertEquals(403, g.status)
        }
        assertEquals(BridgeHttp.Gate.Pass(BridgeRoute.Ask, OriginKind.Lens), BridgeHttp.gate(lens("POST", "/bridge/request"), port))
        // A request with no Origin can't be a page: no ask.
        assertEquals("bridge_origin", (BridgeHttp.gate(req("POST", "/bridge/request", mapOf("Host" to "127.0.0.1:$port")), port) as BridgeHttp.Gate.Stop).code)
        // DNS rebinding: a site's own name pointed at 127.0.0.1.
        assertEquals("bridge_host", (BridgeHttp.gate(lens("GET", "/bridge/ping", mapOf("Host" to "evil.example:47380")), port) as BridgeHttp.Gate.Stop).code)
    }

    @Test fun theHostMustBeTheBridgesOwn() {
        assertTrue(BridgeHttp.hostAllowed("127.0.0.1:47380", 47380))
        assertTrue(BridgeHttp.hostAllowed("localhost:47380", 47380))
        assertFalse(BridgeHttp.hostAllowed("evil.example:47380", 47380))
        assertFalse(BridgeHttp.hostAllowed("127.0.0.1:47381", 47380))
        assertFalse(BridgeHttp.hostAllowed(null, 47380))
    }

    @Test fun corsEchoesOnlyLensAndNeverAStar() {
        val pre = req("OPTIONS", "/v1/snapshot", mapOf("Origin" to "http://127.0.0.1:4000", "Access-Control-Request-Private-Network" to "true"))
        val h = BridgeHttp.cors("http://127.0.0.1:4000", OriginKind.Lens, pre)
        assertEquals("http://127.0.0.1:4000", value(h, "Access-Control-Allow-Origin"))
        assertEquals("true", value(h, "Access-Control-Allow-Private-Network"))
        assertTrue(value(h, "Access-Control-Allow-Headers")!!.contains("Authorization"))
        assertTrue(value(h, "Access-Control-Allow-Methods")!!.contains("PUT"))
        assertNull(value(h, "Access-Control-Allow-Credentials"))
        assertEquals("Origin", value(h, "Vary"))
        // Lens reads the bridge's marker on each answer; everything else is inside the seal.
        assertEquals(BridgeHttp.SEALED, value(BridgeHttp.cors("http://127.0.0.1:4000", OriginKind.Lens), "Access-Control-Expose-Headers"))
        // Nobody else gets CORS at all: no Origin, a refused one ("null" among them), the bridge's own pages.
        for ((o, k) in listOf(null to OriginKind.None, "null" to OriginKind.Refused, "http://192.168.1.66" to OriginKind.Refused, "http://127.0.0.1:47380" to OriginKind.Bridge)) {
            val c = BridgeHttp.cors(o, k, pre)
            assertNull("$o", value(c, "Access-Control-Allow-Origin"))
            assertNull("$o", value(c, "Access-Control-Allow-Private-Network"))
        }
    }

    // ─── Headers both ways ──────────────────────────────────────────────────

    @Test fun theSignatureComesAsAHeaderOrInTheQueryAndNeverGoesOn() {
        val sig = "yn8jTX6SL2djsVIw.1800000000000.nonce-0001-abcdefgh.${BridgeCrypto.EMPTY_BODY}.uP0DF9q0KjxJWrYbM7n2RQjqWB3tropgBCEIlccHLUY"
        val fromHeader = BridgeHttp.signed(req(headers = mapOf("Authorization" to "HNB3 $sig")))!!
        assertEquals("yn8jTX6SL2djsVIw", fromHeader.kid)
        assertEquals("1800000000000", fromHeader.ts)
        assertEquals("nonce-0001-abcdefgh", fromHeader.nonce)
        assertEquals(BridgeCrypto.EMPTY_BODY, fromHeader.body)
        assertEquals("uP0DF9q0KjxJWrYbM7n2RQjqWB3tropgBCEIlccHLUY", fromHeader.mac)
        // Without the body's hash it's no signature.
        assertNull(BridgeHttp.signed(req(headers = mapOf("Authorization" to "HNB3 yn8jTX6SL2djsVIw.1800000000000.nonce-0001-abcdefgh.uP0DF9q0KjxJWrYbM7n2RQjqWB3tropgBCEIlccHLUY"))))
        // A Lens before sealing (HNB2) signs nothing here, and is told so (LensBridge: 403 update_lens).
        val old = req(headers = mapOf("Authorization" to "HNB2 $sig"))
        assertNull(BridgeHttp.signed(old))
        assertTrue(BridgeHttp.oldSignature(old))
        assertFalse(BridgeHttp.oldSignature(req(headers = mapOf("Authorization" to "HNB3 $sig"))))
        // With an Authorization that isn't a signature, the query's isn't read either.
        assertNull(BridgeHttp.signed(req(target = "/v1/ws?hnb=$sig", headers = mapOf("Authorization" to "Bearer x"))))
        assertEquals(fromHeader, BridgeHttp.signed(req(target = "/v1/ws?a=1&hnb=$sig")))
        // A token is no signature, as a bearer or in the query: it never opens anything.
        assertNull(BridgeHttp.signed(req(headers = mapOf("Authorization" to "Bearer hnb_1"))))
        assertNull(BridgeHttp.signed(req(target = "/v1/ws?token=hnb_2")))
        assertNull(BridgeHttp.signed(req(headers = mapOf("Authorization" to "HNB3 short.1.x.y"))))
        assertNull(BridgeHttp.signed(req()))
        // Neither goes on to the Mac.
        assertEquals("a=1&b=2", BridgeHttp.withoutBridgeParams("a=1&token=hnb_x&b=2&hnb=$sig"))
        assertNull(BridgeHttp.withoutBridgeParams("hnb=$sig"))
        assertNull(BridgeHttp.withoutBridgeParams(null))
        assertEquals("limit=120&output=8192&turn=last", BridgeHttp.withoutBridgeParams("limit=120&output=8192&turn=last"))
    }

    @Test fun upstreamCarriesThePhonesCredentialsAndTheMarker() {
        val r = req(
            "POST", "/v1/sessions/s1/prompt?token=hnb_bridge",
            mapOf(
                "Host" to "127.0.0.1:47380",
                "Origin" to "http://127.0.0.1:5173",
                "Referer" to "http://127.0.0.1:5173/index.html",
                "Authorization" to "Bearer hnb_bridge",
                "Cookie" to "hn_preview=abc; other=1",
                "Content-Type" to "application/json",
                "Content-Length" to "0",
                "Accept" to "application/json",
                "Accept-Encoding" to "gzip, br",
                "Connection" to "keep-alive",
                "Expect" to "100-continue",
                "X-Hypernova-Via" to "mac",
                "X-Hypernova-Lens-Version" to "9.9.9",
                "X-Forwarded-For" to "100.64.0.1",
                "Tailscale-User-Login" to "someone@example.com",
                "Sec-Fetch-Mode" to "cors",
                "Proxy-Authorization" to "Basic x",
                "Range" to "bytes=0-1",
                "X-Anything" to "1",
            ),
        )
        val up = BridgeHttp.upstreamHeaders(r, "phone-token", "0.1.7")
        val n = names(up)
        assertEquals("Bearer phone-token", value(up, "Authorization"))
        assertEquals(1, n.count { it == "authorization" })
        assertEquals("lens", value(up, "X-Hypernova-Via"))
        assertEquals(1, n.count { it == "x-hypernova-via" })
        assertEquals("0.1.7", value(up, "X-Hypernova-Lens-Version"))
        assertEquals(1, n.count { it == "x-hypernova-lens-version" })
        // A signed body is Lens's JSON, whatever the request said; only a few headers the signature
        // doesn't cover go on (something relaying Lens's calls can't add ones the Mac would act on).
        assertEquals(BridgeHttp.JSON, value(up, "Content-Type"))
        assertEquals(1, n.count { it == "content-type" })
        assertEquals("application/json", value(up, "Accept"))
        for (gone in listOf("host", "origin", "referer", "cookie", "content-length", "accept-encoding", "connection", "expect", "x-forwarded-for", "tailscale-user-login", "sec-fetch-mode", "proxy-authorization", "range", "x-anything")) {
            assertFalse(gone, gone in n)
        }
        assertEquals(BridgeHttp.JSON, value(BridgeHttp.upstreamHeaders(req("PUT", "/v1/x", mapOf("Content-Type" to "text/plain")), "t", null), "Content-Type"))
        assertNull(value(BridgeHttp.upstreamHeaders(req("GET", "/v1/x", mapOf("Content-Type" to "text/plain")), "t", null), "Content-Type"))
        // A link that's its own key (a preview's video, say) keeps its page's own headers.
        assertEquals("bytes=0-1", value(BridgeHttp.openHeaders(r, preview = true), "Range"))
        assertFalse(up.any { it.second.contains("hnb_bridge") })
        // A version that isn't one isn't sent.
        assertNull(value(BridgeHttp.upstreamHeaders(r, "t", "1.0\r\nX: y"), "X-Hypernova-Lens-Version"))
    }

    @Test fun openLinksGoWithoutCredentialsAndAPreviewKeepsOnlyItsCookie() {
        val r = req("GET", "/p/abc/app.js", mapOf("Authorization" to "Bearer hnb_bridge", "Cookie" to "x=1; hn_preview=tok-123; y=2", "X-Hypernova-Via" to "lens"))
        val shot = BridgeHttp.openHeaders(r, preview = false)
        assertNull(value(shot, "Authorization"))
        assertNull(value(shot, "X-Hypernova-Via"))
        assertNull(value(shot, "Cookie"))
        val page = BridgeHttp.openHeaders(r, preview = true)
        assertEquals("hn_preview=tok-123", value(page, "Cookie"))
        assertNull(value(page, "Authorization"))
        assertEquals("tok-123", BridgeHttp.previewCookie(r))
        assertNull(BridgeHttp.previewCookie(req(headers = mapOf("Cookie" to "hn_preview=bad value!"))))
    }

    @Test fun downstreamDropsTheConnectionsHeadersCookiesAndTheMacsCors() {
        val from = listOf(
            "Content-Type" to "image/png", "Content-Length" to "12", "Connection" to "keep-alive", "Transfer-Encoding" to "chunked",
            "Set-Cookie" to "hn_preview=tok; Path=/", "Set-Cookie" to "other=1", "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "private, max-age=43200", "X-Speech-Engine" to "soniox", "X-Hypernova-Bridge" to "the Mac's, not the bridge's",
        )
        val n = names(BridgeHttp.downstreamHeaders(from))
        assertEquals(listOf("content-type", "cache-control", "x-speech-engine"), n)
        val page = BridgeHttp.downstreamHeaders(from, preview = true)
        assertEquals(listOf("hn_preview=tok; Path=/"), page.filter { it.first.equals("set-cookie", true) }.map { it.second })
    }

    // ─── Live feeds ─────────────────────────────────────────────────────────

    /** The Mac's side of a feed, as far as ending it goes. */
    private class FakeUpstream : okhttp3.WebSocket {
        var closed = false
        var cancelled = false
        override fun request() = okhttp3.Request.Builder().url("http://127.0.0.1/").build()
        override fun queueSize() = 0L
        override fun send(text: String) = true
        override fun send(bytes: okio.ByteString) = true
        override fun close(code: Int, reason: String?): Boolean { closed = true; return true }
        override fun cancel() { cancelled = true }
    }

    private val accept = "HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray()

    /** The finding: a feed revoked while the Mac was still answering opened anyway, and stayed open for good. */
    @Test fun aFeedEndedWhileTheMacAnswersNeverOpens() {
        val feeds = LiveFeeds()
        val out = java.io.ByteArrayOutputStream()
        val up = FakeUpstream()
        val f = LiveFeed("g1", "/v1/ws", out, null) {}.also { it.upstream = up }
        // Counted before the Mac answers: Disconnect finds it.
        feeds += f
        assertEquals(listOf(f), feeds.of("g1"))
        assertTrue(feeds.openGrants().isEmpty())
        for (x in feeds.of("g1")) x.end(4003, "disconnected on the phone")
        assertFalse(f.open(accept, emptyList()))
        assertEquals(0, out.size())
        assertTrue(up.cancelled)
        assertFalse(f.isOpen)
        assertTrue(feeds.openGrants().isEmpty())
    }

    /** A feed's sealer, as a verified socket gets one (BridgeAccess.verify). */
    private val feedKey = ByteArray(32) { (it * 7 + 3).toByte() }
    private fun sealer() = BridgeAccess.Sealer(BridgeCrypto.callKey(feedKey, "L1", "nonce-feed-0001"), "L1", "nonce-feed-0001", BridgeCrypto.EMPTY_BODY)

    /** What Lens would read from a feed's bytes after the handshake: each sealed message opened in order, and the close frame. */
    private fun unsealed(bytes: ByteArray, headLen: Int, key: ByteArray): List<String> {
        val rest = ByteArrayInputStream(bytes, headLen, bytes.size - headLen)
        var n = 0L
        return generateSequence { WsFrames.read(rest) }.map { f ->
            when (f.opcode) {
                WsFrames.CLOSE -> "close ${WsFrames.closeCode(f.payload)}"
                WsFrames.BINARY -> BridgeSeal.openFrame(key, BridgeSeal.FROM_APP, n++, f.payload).let { o -> if (o.kind == BridgeSeal.END) "end ${WsFrames.closeCode(o.payload)}" else String(o.payload) }
                else -> "unsealed ${f.opcode}"
            }
        }.toList()
    }

    @Test fun anOpenFeedGetsItsAnswerFirstThenWhatWaitedThenItsEndSealedThenItsClose() {
        val feeds = LiveFeeds()
        val out = java.io.ByteArrayOutputStream()
        val up = FakeUpstream()
        val s = sealer()
        val f = LiveFeed("g1", "/v1/ws", out, s) {}.also { it.upstream = up }
        feeds += f
        f.message(BridgeSeal.TEXT, "snapshot".toByteArray())
        assertEquals(0, out.size())
        assertTrue(f.open(accept, listOf(BridgeSeal.TEXT to "settings".toByteArray())))
        assertEquals(setOf("g1"), feeds.openGrants())
        assertEquals(listOf(f), feeds.on("/v1/ws"))
        f.message(BridgeSeal.BINARY, "audio".toByteArray())
        f.end(4003, "disconnected on the phone")
        f.message(BridgeSeal.TEXT, "after".toByteArray())
        // The Mac's own close arriving after: the end was said already, nothing more goes.
        f.finish(4001, "forgotten on the Mac")
        assertTrue(up.closed)
        val bytes = out.toByteArray()
        val headLen = accept.size
        assertArrayEquals(accept, bytes.copyOf(headLen))
        // Every message is a sealed binary frame, in the order written, the end marker before the close.
        assertEquals(listOf("settings", "snapshot", "audio", "end 4003", "close 4003"), unsealed(bytes, headLen, s.key))
        // Nothing of them is readable on the wire.
        val wire = String(bytes, Charsets.ISO_8859_1)
        assertFalse(wire.contains("settings") || wire.contains("snapshot") || wire.contains("audio"))
        // Under another call's key (another nonce), nothing opens.
        assertTrue(runCatching { unsealed(bytes, headLen, BridgeCrypto.callKey(feedKey, "L1", "nonce-feed-0002")) }.isFailure)
        // The call's key sealed this feed: it hands out nothing else (a writer would count from 0 again: an IV twice).
        assertTrue(runCatching { s.writer(java.io.ByteArrayOutputStream()) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { s.feedKey() }.exceptionOrNull() is IllegalStateException)
        val t = sealer()
        t.writer(java.io.ByteArrayOutputStream())
        assertTrue(runCatching { t.writer(java.io.ByteArrayOutputStream()) }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { t.feedKey() }.exceptionOrNull() is IllegalStateException)
        feeds -= f
        assertTrue(feeds.of("g1").isEmpty())
    }

    // ─── Writing ────────────────────────────────────────────────────────────

    @Test fun headsAndErrorsAreWellFormed() {
        val head = String(BridgeHttp.head(403, listOf("A" to "b", "Bad" to "x\r\nInjected: 1"), 7L), Charsets.ISO_8859_1)
        assertTrue(head.startsWith("HTTP/1.1 403 Forbidden\r\n"))
        assertTrue(head.contains("Content-Length: 7\r\n"))
        assertFalse(head.contains("Injected"))
        assertTrue(head.endsWith("Connection: close\r\n\r\n"))
        val err = org.json.JSONObject(String(BridgeHttp.error("bridge_access", "Allow it first.", mapOf("retryAfterSeconds" to 5))))
        assertEquals("bridge_access", err.getJSONObject("error").getString("code"))
        assertEquals(5, err.getJSONObject("error").getInt("retryAfterSeconds"))
    }

    @Test fun websocketFramesRoundTrip() {
        // RFC 6455's own example.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFrames.accept("dGhlIHNhbXBsZSBub25jZQ=="))
        val out = java.io.ByteArrayOutputStream()
        val big = ByteArray(70_000) { (it % 251).toByte() }
        WsFrames.write(out, WsFrames.TEXT, "hello".toByteArray(), mask = byteArrayOf(1, 2, 3, 4))
        WsFrames.write(out, WsFrames.BINARY, big, mask = byteArrayOf(9, 8, 7, 6))
        WsFrames.write(out, WsFrames.TEXT, "par".toByteArray(), fin = false, mask = byteArrayOf(5, 5, 5, 5))
        WsFrames.write(out, WsFrames.PING, "p".toByteArray(), mask = byteArrayOf(1, 1, 1, 1))
        WsFrames.write(out, WsFrames.CONTINUATION, "ts".toByteArray(), mask = byteArrayOf(2, 2, 2, 2))
        WsFrames.write(out, WsFrames.CLOSE, WsFrames.closePayload(4003, "disconnected"))
        val input = ByteArrayInputStream(out.toByteArray())
        val whole = WsFrames.Assembler()
        val got = ArrayList<String>()
        while (true) {
            val f = WsFrames.read(input) ?: break
            when (f.opcode) {
                WsFrames.PING -> got += "ping"
                WsFrames.CLOSE -> got += "close ${WsFrames.closeCode(f.payload)}"
                else -> whole.add(f)?.let { m -> got += if (m.text) String(m.payload) else "binary ${m.payload.size}" ; if (!m.text) assertArrayEquals(big, m.payload) }
            }
        }
        assertEquals(listOf("hello", "binary 70000", "ping", "parts", "close 4003"), got)
        // A control frame may not be fragmented or long.
        val bad = java.io.ByteArrayOutputStream()
        WsFrames.write(bad, WsFrames.PING, ByteArray(200))
        assertTrue(runCatching { WsFrames.read(ByteArrayInputStream(bad.toByteArray())) }.isFailure)
        assertTrue(runCatching { WsFrames.Assembler().add(WsFrames.Frame(true, WsFrames.CONTINUATION, ByteArray(1))) }.isFailure)
        // A frame over the feed's cap ends it before its payload is allocated.
        val huge = java.io.ByteArrayOutputStream()
        WsFrames.write(huge, WsFrames.BINARY, ByteArray(2048), mask = byteArrayOf(1, 2, 3, 4))
        assertTrue(runCatching { WsFrames.read(ByteArrayInputStream(huge.toByteArray()), max = 1024) }.isFailure)
    }

    // ─── The second round's findings ────────────────────────────────────────

    /**
     * The finding: the URL builder resolves (a dot segment, `%2e%2e`, a backslash) or re-encodes
     * what it's given, so the path carried could differ from the one routed. A second lock: the
     * built path and query must be exactly the checked ones, or nothing is carried.
     */
    @Test fun theCarriedPathAndQueryAreExactlyTheCheckedOnes() {
        val ts = "https://nikitas-mac.tail1234.ts.net"
        for (p in listOf("/v1/./x", "/v1/../local/devices", "/v1/%2e%2e/local/devices", "/v1/%2E/x", "/v1/a\\b", "/v1/a|b", "/v1/a^b")) {
            assertNull(p, BridgeHttp.upstreamUrl(ts, p, null))
        }
        assertNull(BridgeHttp.upstreamUrl(ts, "/v1/x", "a='1'"))
        // What a web view sends comes through as it is.
        val u = BridgeHttp.upstreamUrl(ts, "/v1/sessions/s%201/prompt", "a=1&b=%20x&c=&d&e=a+b")!!
        assertEquals("/v1/sessions/s%201/prompt", u.encodedPath)
        assertEquals("a=1&b=%20x&c=&d&e=a+b", u.encodedQuery)
    }

    /** One tiny HTTP server on loopback for the redirect test: answers every request with [answer]. */
    private fun server(answer: (Int) -> String): java.net.ServerSocket {
        val s = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        var n = 0
        kotlin.concurrent.thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                c.use {
                    runCatching { BridgeHttp.readHead(it.getInputStream()) }
                    it.getOutputStream().write(answer(++n).toByteArray())
                    it.getOutputStream().flush()
                }
            }
        }
        return s
    }

    /**
     * The finding: OkHttp follows redirects by default, so the Mac's (or a previewed dev server's)
     * 302 to another host took the request there, the preview's cookie and the glasses' marker with
     * it. The bridge's clients never follow one: the 3xx goes back to Lens as it is.
     */
    @Test fun aCarriedCallNeverFollowsARedirect() {
        val hits = java.util.concurrent.atomic.AtomicInteger()
        val elsewhere = server { hits.incrementAndGet(); "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\nstolen" }
        val location = "http://127.0.0.1:${elsewhere.localPort}/x"
        val mac = server { "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" }
        try {
            val call = okhttp3.Request.Builder().url("http://127.0.0.1:${mac.localPort}/p/tok/index.html")
                .header("Cookie", "${BridgeHttp.PREVIEW_COOKIE}=tok").header(BridgeHttp.VIA, "lens").build()
            val carrying = BridgeHttp.carrying(okhttp3.OkHttpClient.Builder()).build()
            assertFalse(carrying.followRedirects)
            assertFalse(carrying.followSslRedirects)
            carrying.newCall(call).execute().use { r ->
                assertEquals(302, r.code)
                assertEquals(location, r.header("Location"))
                // And it reaches Lens so: the bridge relays the Mac's headers, Location among them.
                assertEquals(location, value(BridgeHttp.downstreamHeaders(r.headers.map { it.first to it.second }), "Location"))
            }
            assertEquals(0, hits.get())
            // What OkHttp would do by itself.
            okhttp3.OkHttpClient().newCall(call).execute().use { assertEquals(200, it.code) }
            assertEquals(1, hits.get())
        } finally {
            mac.close()
            elsewhere.close()
        }
    }

    /**
     * The finding: ending a feed took the writer's lock, and a Lens that stopped reading holds the
     * writer for ever: Disconnect (and the bridge's ender) waited with it. Now the Mac's side goes,
     * then the connection is closed, without waiting for the writer.
     */
    /** The fifth round's bug: the Mac's 4001 and the app's 4003 raced, and Lens got a close with no end marker (1006). Whoever ends first says it, once. */
    @Test fun theEndMarkerGoesOnceWhicheverSideEndsFirst() {
        for (macFirst in listOf(true, false)) {
            val out = java.io.ByteArrayOutputStream()
            val up = FakeUpstream()
            val s = sealer()
            val f = LiveFeed("g1", "/v1/ws", out, s) {}.also { it.upstream = up }
            assertTrue(f.open(accept, emptyList()))
            if (macFirst) { f.finish(4001, "forgotten"); f.end(4003, "disconnected") } else { f.end(4003, "disconnected"); f.finish(4001, "forgotten") }
            val got = unsealed(out.toByteArray(), accept.size, s.key)
            assertEquals("mac first: $macFirst", if (macFirst) listOf("end 4001", "close 4001") else listOf("end 4003", "close 4003"), got)
        }
        // While another thread writes the Mac's end, end() waits for it rather than cutting the connection under it.
        val out = java.io.ByteArrayOutputStream()
        val gate = java.util.concurrent.CountDownLatch(1)
        val slow = object : java.io.OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) { gate.await(2, java.util.concurrent.TimeUnit.SECONDS); out.write(b, off, len) }
        }
        val s = sealer()
        var closed = false
        val f = LiveFeed("g1", "/v1/ws", slow, s) { closed = true }.also { it.upstream = FakeUpstream() }
        gate.countDown()
        assertTrue(f.open(accept, emptyList()))
        val slowGate = java.util.concurrent.CountDownLatch(1)
        val writer = Thread { f.finish(4001, "forgotten") }
        // The first write of finish() blocks until slowGate opens (simulated by re-arming the latch via a new stream isn't possible; so: start, then end() in parallel).
        writer.start()
        Thread.sleep(50)
        f.end(4003, "disconnected")
        writer.join(3000)
        val got = unsealed(out.toByteArray(), accept.size, s.key)
        assertEquals(listOf("end 4001", "close 4001"), got)
        assertTrue(closed)
        slowGate.countDown()
    }

    @Test fun endingAFeedNeverWaitsForAStuckWriter() {
        val jammed = java.util.concurrent.CountDownLatch(1)
        val unjam = java.util.concurrent.CountDownLatch(1)
        var jam = false
        val out = object : java.io.OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (!jam) return
                jammed.countDown()
                unjam.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        var closed = false
        val up = FakeUpstream()
        // Closing the connection is what frees a writer stuck on it.
        val f = LiveFeed("g1", "/v1/ws", out, sealer()) { closed = true; unjam.countDown() }.also { it.upstream = up }
        assertTrue(f.open(accept, emptyList()))
        jam = true
        val writer = Thread { runCatching { f.message(BridgeSeal.BINARY, ByteArray(1000)) } }.apply { start() }
        assertTrue(jammed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        f.end(4003, "disconnected on the phone")
        assertTrue("ended without waiting", System.nanoTime() - t0 < 1_000_000_000L)
        assertTrue(up.closed)
        assertTrue(closed)
        writer.join(5_000)
        assertFalse(writer.isAlive)
        assertFalse(f.isOpen)
        // And a settings push to it gives up rather than wait.
        assertFalse(f.offer(BridgeSeal.TEXT, "x".toByteArray(), 50))
    }

    @Test fun theLogNamesThePathNeverTheQuery() {
        val r = req(target = "/v1/ws?hnb=yn8jTX6SL2djsVIw.1.nonce-0001-abcdefgh.mac&token=hnb_secret")
        assertEquals("GET /v1/ws", BridgeHttp.forLog(r))
    }

    /** Each ask's Allow and Deny are its own PendingIntents: a newer ask can't take over an older one's buttons. */
    @Test fun eachAskHasItsOwnRequestCodes() {
        val codes = (1..200).flatMap { listOf(BridgeNotice.requestCode("ask-$it", true), BridgeNotice.requestCode("ask-$it", false)) }
        assertTrue(codes.all { it >= 100 })
        assertEquals(codes.size, codes.toSet().size)
        assertEquals(BridgeNotice.requestCode("ask-1", true), BridgeNotice.requestCode("ask-1", true))
    }

    @Test fun `what the phone guards with a fingerprint never goes from the glasses`() {
        fun asks(method: String, path: String, body: String?) = BridgeHttp.phoneOnly(method, path, body?.toByteArray())
        // Always, in any spelling JSON allows; Allow, Deny and the session's tools are the glasses' own.
        assertTrue(asks("POST", "/v1/approvals/ap-1", """{"decision":"always"}"""))
        assertTrue(asks("POST", "/v1/approvals/ap-1", """{"decision":"\u0061lways","message":"x"}"""))
        assertFalse(asks("POST", "/v1/approvals/ap-1", """{"decision":"allow"}"""))
        assertFalse(asks("POST", "/v1/approvals/ap-1", """{"decision":"deny","message":"no"}"""))
        assertFalse(asks("POST", "/v1/approvals/ap-1", """{"decision":"session"}"""))
        // A body the Mac might read its own way (a key twice: JSON.parse keeps the last) counts as asking.
        assertTrue(asks("POST", "/v1/approvals/ap-1", """{"decision":"allow","decision":"always"}"""))
        assertTrue(asks("POST", "/v1/approvals/ap-1", "not json"))
        // Bypass: a switch, or a session started in it; the other modes are fine.
        assertTrue(asks("POST", "/v1/sessions/s-1/mode", """{"permissionMode":"bypassPermissions"}"""))
        assertFalse(asks("POST", "/v1/sessions/s-1/mode", """{"permissionMode":"plan"}"""))
        assertTrue(asks("POST", "/v1/sessions", """{"prompt":"hi","projectPath":"/p","permissionMode":"bypassPermissions"}"""))
        assertFalse(asks("POST", "/v1/sessions", """{"prompt":"hi","projectPath":"/p"}"""))
        assertFalse(asks("POST", "/v1/sessions/s-1/prompt", """{"text":"bypassPermissions always"}"""))
        // An automation's No asking, saved new or over an old one.
        assertTrue(asks("POST", "/v1/automations", """{"name":"x","confirmBypass":true}"""))
        assertTrue(asks("PUT", "/v1/automations/a1", """{"name":"x","confirmBypass":true}"""))
        assertFalse(asks("PUT", "/v1/automations/a1", """{"name":"x"}"""))
        // Browse outside projects: on is the phone's, off anyone's.
        assertTrue(asks("POST", "/v1/mac/files", """{"browseOutsideProjects":true}"""))
        assertFalse(asks("POST", "/v1/mac/files", """{"browseOutsideProjects":false}"""))
        // Reading is never refused, nor a call without a body.
        assertFalse(asks("GET", "/v1/mac/files", null))
        assertFalse(asks("POST", "/v1/approvals/ap-1", null))
        assertFalse(asks("GET", "/v1/snapshot", null))
    }
}
