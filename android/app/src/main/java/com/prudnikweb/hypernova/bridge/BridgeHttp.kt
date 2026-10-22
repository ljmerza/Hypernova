package com.prudnikweb.hypernova.bridge

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder

/**
 * The plain HTTP the bridge speaks with Lens on 127.0.0.1 (docs/PROTOCOL.md, "The phone bridge"),
 * and what it changes on the way to the Mac. A web view's requests are small and one at a time, so
 * a few hundred lines of HTTP/1.1 do; nothing of Android in here (unit-tested).
 *
 * The head is read first and judged (the target, the Host, the Origin, the route, the signature) before
 * a single byte of a body is: a body is read only for a request that may have one, and then capped
 * ([readBody]: the bridge's own routes take a few KB, a signed call's sealed body 1 MB).
 */
class HttpRequest(
    val method: String,
    /** As sent: the path and its query. */
    val target: String,
    val path: String,
    /** The raw query, without its `?`; null when there is none. */
    val query: String?,
    /** Header names in lowercase; a repeated header keeps its first value. */
    val headers: Map<String, String>,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    val isUpgrade: Boolean get() = header("upgrade")?.equals("websocket", ignoreCase = true) == true
    val chunked: Boolean get() = header("transfer-encoding")?.contains("chunked", ignoreCase = true) == true
    /** Whether a body follows the head at all. */
    val hasBody: Boolean get() = chunked || (header("content-length")?.trim()?.toLongOrNull() ?: 0L) > 0L
}

/** A body bigger than the route takes: answered 413, never read. */
class TooLarge(message: String) : IOException(message)

/**
 * A connection's input with an overall deadline as well as one per read: a socket's own timeout
 * counts from the last byte, so a sender trickling a byte at a time would hold its connection for
 * ever. Until [deadline] is cleared, each read may wait only for what's left of it ([setTimeout] is
 * the socket's own), and past it the connection is over ([java.net.SocketTimeoutException]).
 */
class DeadlineInput(private val input: InputStream, private val clock: () -> Long, private val setTimeout: (Int) -> Unit) : InputStream() {
    @Volatile private var until = 0L

    /** Everything read from now on must have come by [at] (epoch ms). */
    fun deadline(at: Long) { until = at }

    /** No overall deadline from now on: [ms] at most between two reads (0: none). */
    fun perRead(ms: Int) {
        until = 0L
        setTimeout(ms)
    }

    private fun arm() {
        val at = until
        if (at == 0L) return
        val left = at - clock()
        if (left <= 0) throw java.net.SocketTimeoutException("deadline passed")
        setTimeout(left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    override fun read(): Int { arm(); return input.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int { arm(); return input.read(b, off, len) }
    override fun available(): Int = input.available()
    override fun close() = input.close()
}

/** Where a request to the bridge goes. */
sealed interface BridgeRoute {
    /** A browser asking before the real request (CORS). */
    data object Preflight : BridgeRoute
    /** `GET /bridge/ping`: that the app is here. */
    data object Ping : BridgeRoute
    /** `POST /bridge/request`: Lens asks to connect, with its half of the key agreement. */
    data object Ask : BridgeRoute
    /** `POST /bridge/request/{id}`: Lens shows the nonce its ask committed to; the owner is asked. */
    data class Reveal(val id: String) : BridgeRoute
    /** `GET /bridge/request/{id}`: how the ask went. */
    data class Poll(val id: String) : BridgeRoute
    /** `GET /bridge/status`: the app's own link to the Mac, for Lens's status card. */
    data object Status : BridgeRoute
    /** `GET` / `PUT /bridge/settings`: the glasses' settings, kept in the app. */
    data object Settings : BridgeRoute
    /** `DELETE /bridge/access`: Lens lets go of its own grant. */
    data object Leave : BridgeRoute
    /** `POST /bridge/open {screen}`, signed: Lens's "Open Hypernova", which its web view can't do by itself (BridgeOpen.kt). */
    data object Open : BridgeRoute
    /** `/v1/…`, signed: carried to the Mac with the phone's credentials. */
    data object Proxy : BridgeRoute
    /**
     * `POST /bridge/shot {name}`, signed: a Telescope picture (`/v1/shots/<name>.png` on the Mac),
     * its name inside the sealed body and the picture sealed back, so nothing on the way learns a
     * name it could fetch from the Mac itself (the Mac serves a picture by its name alone).
     */
    data object Picture : BridgeRoute
    /** `GET /p/<token>/…`: a preview's page, carried only when a Lens with a grant opened that preview here. */
    data class Preview(val token: String) : BridgeRoute
    /** `GET /<file>` with the preview's cookie: that previewed page asking for its own files by absolute path. */
    data class PreviewFile(val token: String) : BridgeRoute
    /** Never carried: the phone's pairing is the app's alone. */
    data object Refused : BridgeRoute
    data object NotFound : BridgeRoute
}

/** Who a request's `Origin` says sent it. */
enum class OriginKind {
    /** No Origin: a native client, or a browser loading a page or a picture (which can't read the answer). */
    None,
    /** Lens in the Even App: `http://127.0.0.1:<port>` (or `localhost`, `[::1]`). */
    Lens,
    /** The bridge's own address: a preview's page, which it serves, asking for its own files. */
    Bridge,
    /** A development server on a private network address; only in a build made for Lens development. */
    DevNetwork,
    /** Anything else, `null` among them (a sandboxed frame, a `data:` or `file:` page). */
    Refused,
}

object BridgeHttp {
    const val MAX_HEAD = 32 * 1024
    /** The ask and the settings are a few hundred bytes; nothing bigger is read for the bridge's own routes. */
    const val MAX_SMALL_BODY = 16 * 1024

    // ─── Reading ────────────────────────────────────────────────────────────

    /**
     * Headers the bridge judges a request by: sent twice (two Hosts, two Origins, two lengths), the
     * request is refused (400), as RFC 9112 says, rather than judged by one and read by the other.
     */
    private val ONCE = setOf("host", "origin", "content-length", "transfer-encoding", "authorization", "cookie", "upgrade", "connection", "sec-websocket-key")

    /**
     * One request's head from [input] (its body, if any, is still in [input]); null when the
     * connection ended before one began.
     */
    fun readHead(input: InputStream): HttpRequest? {
        val first = line(input, MAX_HEAD) ?: return null
        val parts = first.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) throw IOException("bad request line")
        val method = parts[0].uppercase()
        if (method.isEmpty() || !method.all { it in 'A'..'Z' }) throw IOException("bad method")
        val target = parts[1]
        val headers = LinkedHashMap<String, String>()
        var size = first.length
        while (true) {
            val h = line(input, MAX_HEAD) ?: throw IOException("headers cut off")
            if (h.isEmpty()) break
            size += h.length
            if (size > MAX_HEAD) throw IOException("headers too large")
            val i = h.indexOf(':')
            if (i <= 0) continue
            val name = h.substring(0, i).trim().lowercase()
            if (name in ONCE && name in headers) throw IOException("repeated header")
            headers.putIfAbsent(name, h.substring(i + 1).trim())
        }
        if ("content-length" in headers && "transfer-encoding" in headers) throw IOException("both a length and a transfer-encoding")
        val path = target.substringBefore('?').substringBefore('#')
        val query = target.substringAfter('?', "").substringBefore('#').takeIf { target.contains('?') }
        return HttpRequest(method, target, path, query, headers)
    }

    /**
     * Whether a request target is a path on this server, the only form the bridge takes: it starts
     * with one `/`. Anything else ("@host/x", ".host/x", ":8443/x", "//host/x", "http://host/x",
     * "host:443", "*") could name another host once joined to the Mac's address, so it's refused
     * before routing, with or without a cookie.
     */
    fun targetOk(target: String): Boolean =
        target.startsWith("/") && !target.startsWith("//") && target.none { it <= ' ' || it == '\u007f' }

    /**
     * The whole body of a request to the bridge's own routes, at most [max] bytes. A declared length
     * over [max] is refused before anything is read ([TooLarge]), and so is a chunked body once it
     * passes [max].
     */
    fun readBody(input: InputStream, req: HttpRequest, max: Int = MAX_SMALL_BODY): ByteArray {
        if (req.chunked) return ChunkedInput(input, max.toLong()).readBytes()
        val n = declaredLength(req)
        if (n > max) throw TooLarge("body too large")
        return exactly(input, n.toInt())
    }

    /** The declared Content-Length; 0 when there is none. */
    fun declaredLength(req: HttpRequest): Long {
        val v = req.header("content-length") ?: return 0
        val n = v.trim().toLongOrNull() ?: throw IOException("bad content-length")
        if (n < 0) throw IOException("bad content-length")
        return n
    }

    /** A line ending in CRLF (or a bare LF), read as Latin-1, without its end. */
    private fun line(input: InputStream, max: Int): String? {
        val b = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.isEmpty()) null else throw IOException("connection closed mid-line")
            if (c == '\n'.code) return b.toString().removeSuffix("\r")
            b.append(c.toChar())
            if (b.length > max) throw IOException("line too long")
        }
    }

    private fun exactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw IOException("body cut off")
            off += r
        }
        return out
    }

    /** Trailer lines after a chunked body: read and dropped, a few short ones at most. */
    const val MAX_TRAILERS = 16
    private const val MAX_TRAILER_LINE = 1024

    /** A chunked body, decoded as it's read; more than [max] bytes in all is [TooLarge]. */
    private class ChunkedInput(private val input: InputStream, private val max: Long) : InputStream() {
        private var left = 0L
        private var total = 0L
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (left == 0L) {
                val size = line(input, 64)?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: throw IOException("bad chunk")
                if (size < 0) throw IOException("bad chunk")
                if (size == 0L) {
                    var trailers = 0
                    while (line(input, MAX_TRAILER_LINE)?.isNotEmpty() == true) {
                        if (++trailers > MAX_TRAILERS) throw IOException("too many trailers")
                    }
                    done = true
                    return -1
                }
                // Not `total + size > max`: a chunk of 0x7fffffffffffffff bytes would wrap that sum below max.
                if (size > max - total) throw TooLarge("body too large")
                total += size
                left = size
            }
            val r = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (r < 0) throw IOException("body cut off")
            left -= r
            if (left == 0L) line(input, 4)
            return r
        }
    }

    // ─── Who may ask ────────────────────────────────────────────────────────

    /**
     * Who sent a request, by its Origin. The Even App serves Lens from `http://127.0.0.1:<port>`,
     * so that (and `localhost`, `[::1]`) is the only page the bridge takes. `null` is refused: a
     * browser sends it from a sandboxed frame or a `data:` page any public site can make, and from
     * `file:` pages, and such a page could make the ask and read its code. A private network address
     * (a captive portal, a router's page, a stranger's laptop on café Wi-Fi) is refused too, unless
     * [devNetwork]: a build made for Lens development, whose dev server the Even App opens by its LAN
     * address. [port] is the bridge's own: its own pages are previews ([OriginKind.Bridge]).
     */
    fun originKind(origin: String?, port: Int, devNetwork: Boolean = false): OriginKind {
        if (origin == null) return OriginKind.None
        val u = runCatching { URI(origin.trim()) }.getOrNull() ?: return OriginKind.Refused
        // An Origin is a scheme, a host and maybe a port, nothing else.
        if (u.rawUserInfo != null || !u.rawPath.isNullOrEmpty() || u.rawQuery != null || u.rawFragment != null) return OriginKind.Refused
        val host = u.host?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return OriginKind.Refused
        val scheme = u.scheme?.lowercase()
        if (scheme == "http" && u.port in 1..65535 && (host == "127.0.0.1" || host == "localhost" || host == "::1")) {
            return if (u.port == port) OriginKind.Bridge else OriginKind.Lens
        }
        if (devNetwork && (scheme == "http" || scheme == "https") && isPrivateHost(host)) return OriginKind.DevNetwork
        return OriginKind.Refused
    }

    /**
     * Whether [kind] may make this request. Lens may make any. A request with no Origin can't come
     * from a page that reads the answer, so it may ping, load a picture or a preview, or carry a
     * signed call; but the ask, its nonce and its poll are for a page, which always sends its Origin, so they want
     * one. The bridge's own pages (previews) may only ask for their own files.
     */
    fun originFits(route: BridgeRoute, kind: OriginKind): Boolean = when (kind) {
        OriginKind.Refused -> false
        OriginKind.Lens, OriginKind.DevNetwork -> true
        OriginKind.Bridge -> route is BridgeRoute.Preview || route is BridgeRoute.PreviewFile
        OriginKind.None -> route != BridgeRoute.Ask && route !is BridgeRoute.Reveal && route !is BridgeRoute.Poll && route != BridgeRoute.Preflight
    }

    /** What [gate] decided about a request's head. */
    sealed interface Gate {
        /** On to [route]; the page is [kind]. */
        data class Pass(val route: BridgeRoute, val kind: OriginKind) : Gate
        /** Refused before any body is read. */
        data class Stop(val status: Int, val code: String, val message: String) : Gate
    }

    /**
     * Everything the bridge judges from a request's head, in order, before a byte of its body is
     * read: that the target is a path, the Host (DNS rebinding), the route, and whether the page
     * that sent it may make it. The signature comes next, per route (LensBridge).
     */
    fun gate(req: HttpRequest, port: Int, devNetwork: Boolean = false): Gate {
        if (!targetOk(req.target)) return Gate.Stop(400, "bad_request", "Not a path on this phone.")
        if (!hostAllowed(req.header("host"), port)) return Gate.Stop(403, "bridge_host", "Only pages on this phone may use the bridge.")
        val kind = originKind(req.header("origin"), port, devNetwork)
        val route = route(req.method, req.path, previewCookie(req))
        if (!originFits(route, kind)) return Gate.Stop(403, "bridge_origin", "Only Hypernova Lens on this phone may use the bridge.")
        return Gate.Pass(route, kind)
    }

    /**
     * The Host the page asked for must be the bridge's own: a site that points its own name at
     * 127.0.0.1 (DNS rebinding) sends its name here.
     */
    fun hostAllowed(host: String?, port: Int): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        return h == "127.0.0.1:$port" || h == "localhost:$port" || h == "[::1]:$port"
    }

    private fun isPrivateHost(h: String): Boolean {
        val p = h.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 169 && p[1] == 254)
    }

    /**
     * A request as the bridge's log may name it: its method and path, never its query, which may
     * carry a signature (or, from a Lens before signing, its token) and isn't the log's business.
     */
    fun forLog(req: HttpRequest): String = "${req.method} ${req.path.take(80)}"

    /** The query parameter a WebSocket's signature comes in (a page can't set headers on one). */
    const val SIGNATURE_PARAM = "hnb"

    /**
     * A call's signature (BridgeAccess.Signed): `Authorization: HNB3 <kid>.<ts>.<nonce>.<body>.<mac>`, or
     * `?hnb=` on a WebSocket. A bearer token is no signature: the bridge token never travels.
     */
    fun signed(req: HttpRequest): BridgeAccess.Signed? {
        val auth = req.header("authorization")?.trim()
        if (auth != null && auth.startsWith("HNB3 ", ignoreCase = true)) return BridgeAccess.Signed.parse(auth.substring(5))
        if (auth != null) return null
        return BridgeAccess.Signed.parse(queryValue(req.query, SIGNATURE_PARAM))
    }

    /** A signature from a Lens before this protocol (`HNB2`, which sealed nothing): told to update, never served. */
    fun oldSignature(req: HttpRequest): Boolean = req.header("authorization")?.trim()?.startsWith("HNB2 ", ignoreCase = true) == true

    fun queryValue(query: String?, key: String): String? = query?.split('&')?.firstNotNullOfOrNull { p ->
        val k = p.substringBefore('=')
        if (decode(k) == key) decode(p.substringAfter('=', "")) else null
    }

    /** The query less the bridge's own (the signature, and a `token=` if Lens ever sent one): none of it goes on to the Mac. */
    fun withoutBridgeParams(query: String?): String? =
        query?.split('&')?.filter { it.isNotEmpty() && decode(it.substringBefore('=')).let { k -> k != "token" && k != SIGNATURE_PARAM } }
            ?.joinToString("&")?.takeIf { it.isNotEmpty() }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    // ─── Routing ────────────────────────────────────────────────────────────

    private val POLL = Regex("""^/bridge/request/([A-Za-z0-9_-]{8,64})$""")
    /** A Telescope picture's name on the Mac (`/v1/shots/<name>.png`), as `POST /bridge/shot` takes it. */
    val PICTURE_NAME = Regex("""^[A-Za-z0-9_-]{1,128}$""")
    private val PREVIEW = Regex("""^/p/([\w-]{1,128})(/.*)?$""")
    const val PREVIEW_COOKIE = "hn_preview"

    /**
     * Where a request goes. [previewCookie]: the preview cookie it carries, if any, so a path outside
     * `/p/` may be that previewed page asking for its own files by absolute path (as the daemon
     * serves them). Only a preview goes without a signature, by its token, and only to be read
     * ([BridgeRoute.Preview], [BridgeRoute.PreviewFile]; LensBridge carries those only for previews
     * a Lens with a grant opened here): a frame can't sign. A Telescope picture is asked for by a
     * signed call whose sealed body names it ([BridgeRoute.Picture]); `GET /v1/shots/…` itself is a
     * signed call like any other (never an open one: the third review's relay read the name off
     * the wire and fetched the picture from the app unsigned).
     */
    fun route(method: String, path: String, previewCookie: String? = null): BridgeRoute {
        val read = method == "GET" || method == "HEAD"
        return when {
            // Never reached with a target that isn't a path ([targetOk]); refused here too, as a second lock.
            !path.startsWith("/") || path.startsWith("//") -> BridgeRoute.NotFound
            method == "OPTIONS" -> BridgeRoute.Preflight
            path == "/bridge/ping" && read -> BridgeRoute.Ping
            path == "/bridge/request" && method == "POST" -> BridgeRoute.Ask
            method == "POST" && POLL.matches(path) -> BridgeRoute.Reveal(POLL.find(path)!!.groupValues[1])
            method == "GET" && POLL.matches(path) -> BridgeRoute.Poll(POLL.find(path)!!.groupValues[1])
            path == "/bridge/status" && method == "GET" -> BridgeRoute.Status
            path == "/bridge/settings" && (method == "GET" || method == "PUT" || method == "POST") -> BridgeRoute.Settings
            path == "/bridge/access" && method == "DELETE" -> BridgeRoute.Leave
            path == "/bridge/open" && method == "POST" -> BridgeRoute.Open
            path == "/bridge/shot" && method == "POST" -> BridgeRoute.Picture
            path == "/bridge" || path.startsWith("/bridge/") -> BridgeRoute.NotFound
            // Before anything is carried: OkHttp resolves `..` (also written `%2e%2e`, or with `\`) on the
            // way to the Mac, so a path that climbs would reach somewhere other than what was checked here.
            climbs(path) -> BridgeRoute.NotFound
            // The phone's own pairing: forgetting it, its certificate, its glasses. Never Lens's to touch.
            path == "/v1/pair" || path.startsWith("/v1/devices/") || path == "/v1/devices" || path.startsWith("/v1/device/") -> BridgeRoute.Refused
            path.startsWith("/v1/") -> BridgeRoute.Proxy
            // A preview's link is its key (PROTOCOL.md, "Artifacts and previews"), to be looked at only.
            PREVIEW.matches(path) -> if (read) BridgeRoute.Preview(PREVIEW.find(path)!!.groupValues[1]) else BridgeRoute.NotFound
            previewCookie != null && read -> BridgeRoute.PreviewFile(previewCookie)
            else -> BridgeRoute.NotFound
        }
    }

    private val APPROVAL = Regex("""^/v1/approvals/[^/]+$""")
    private val MODE = Regex("""^/v1/sessions/[^/]+/mode$""")

    /** What Lens hears when it asks for one of [phoneOnly]'s steps (403 `needs_phone`), as the Mac says it. */
    const val NEEDS_PHONE = "Do this on your phone: it asks for your fingerprint or screen lock first."

    /**
     * Whether a signed call from Lens asks for what the phone guards with the fingerprint or the
     * screen lock, which the glasses can't give (docs/PROTOCOL.md, "Only on the phone"): an approval
     * answered "always", a switch to Bypass or a session started in it, an automation saved with No
     * asking (`confirmBypass`), Browse outside projects turned on. [body]: the call's opened body. Such
     * a call is refused here (403 `needs_phone`) and never carried; the Mac refuses it too. A body for
     * one of those routes that isn't plain JSON (a key twice, say, which the Mac would read its own
     * way) counts as asking.
     */
    fun phoneOnly(method: String, path: String, body: ByteArray?): Boolean {
        if (method != "POST" && method != "PUT" && method != "PATCH") return false
        val guarded = APPROVAL.matches(path) || MODE.matches(path) || path == "/v1/sessions" ||
            path == "/v1/automations" || path.startsWith("/v1/automations/") || path == "/v1/mac/files"
        if (!guarded || body == null || body.isEmpty()) return false
        val o = runCatching { org.json.JSONObject(String(body, Charsets.UTF_8)) }.getOrNull() ?: return true
        return when {
            APPROVAL.matches(path) -> o.opt("decision") == "always"
            MODE.matches(path) || path == "/v1/sessions" -> o.opt("permissionMode") == "bypassPermissions"
            path == "/v1/mac/files" -> o.opt("browseOutsideProjects") == true
            else -> o.opt("confirmBypass") == true
        }
    }

    /** A dot segment (`.` or `..`, plain or percent-encoded) or a backslash, which URL parsers read as `/`. */
    private fun climbs(path: String): Boolean =
        path.contains('\\') || path.contains("%5c", ignoreCase = true) ||
            path.split('/').any { s -> s.replace("%2e", ".", ignoreCase = true).let { it == "." || it == ".." } }

    /**
     * The Mac's URL for a request: the active route's base with this path and query put in by OkHttp's
     * URL builder, never joined as strings (joined, "@evil.example/x" made the Mac's address the user
     * name of another host). Null unless the result is exactly the base's scheme, host and port,
     * with exactly the path and query that were checked: a builder that resolved or re-encoded
     * anything (a dot segment, a backslash) would carry somewhere other than what [route] judged.
     */
    fun upstreamUrl(base: String, path: String, query: String?): HttpUrl? {
        val b = base.toHttpUrlOrNull() ?: return null
        if (!path.startsWith("/") || path.startsWith("//")) return null
        val u = runCatching { b.newBuilder().encodedPath(path).encodedQuery(query).fragment(null).build() }.getOrNull() ?: return null
        if (u.encodedPath != path || u.encodedQuery != query) return null
        return u.takeIf { it.scheme == b.scheme && it.host == b.host && it.port == b.port && it.username.isEmpty() && it.password.isEmpty() }
    }

    /**
     * A client for carrying calls that never follows a redirect: the Mac's answer, 3xx and its
     * `Location` among it, goes back to Lens as it is. Followed, a redirect would take the phone's
     * credentials (or a preview's cookie) to wherever the answer pointed.
     */
    fun carrying(b: okhttp3.OkHttpClient.Builder): okhttp3.OkHttpClient.Builder = b.followRedirects(false).followSslRedirects(false)

    /** The preview token in a preview's link (`…/p/<token>/<file>`), as the Mac answers `POST /v1/preview`. */
    fun previewToken(url: String): String? {
        val path = url.toHttpUrlOrNull()?.encodedPath ?: url.substringBefore('?').substringBefore('#')
        return PREVIEW.find(path)?.groupValues?.get(1)
    }

    // ─── Headers both ways ──────────────────────────────────────────────────

    const val VIA = "X-Hypernova-Via"
    /** On every answer to a signed call, `sealed`: its body is the answer sealed under the call's key (BridgeSeal), the status and headers inside. */
    const val SEALED = "X-Hypernova-Bridge"
    const val SEALED_VALUE = "sealed"
    const val SEALED_TYPE = "application/x-hnb3"
    const val LENS_VERSION = "X-Hypernova-Lens-Version"

    /** Headers that belong to one connection, never passed on (RFC 9110, 7.6.1), plus the WebSocket handshake's own. */
    private val HOP = setOf(
        "connection", "keep-alive", "proxy-connection", "proxy-authorization", "proxy-authenticate", "te", "trailer", "transfer-encoding", "upgrade",
        "expect", "sec-websocket-key", "sec-websocket-version", "sec-websocket-extensions", "sec-websocket-accept", "sec-websocket-protocol",
    )

    /**
     * What goes to the Mac with a request from Lens: Lens's own headers, less what isn't the Mac's
     * business (its signature, cookies, the page's origin and the host it asked for) and less
     * anything that claims to be the bridge (its marker and version are the bridge's to say), then
     * the phone's credentials and the marker that these come from the glasses.
     */
    fun upstreamHeaders(req: HttpRequest, phoneToken: String, lensVersion: String?): List<Pair<String, String>> {
        // Only what the signature can't cover and the Mac may use; a signed body is always Lens's JSON.
        val out = ArrayList(passOn(req).filter { it.first in SIGNED_PASS })
        if (req.method != "GET" && req.method != "HEAD" && !req.isUpgrade) out += "Content-Type" to JSON
        out += "Authorization" to "Bearer $phoneToken"
        out += VIA to "lens"
        BridgeAccess.cleanVersion(lensVersion)?.let { out += LENS_VERSION to it }
        return out
    }

    /** What a signed body is, whatever its request said: Lens sends JSON, and nothing in between may say otherwise. */
    const val JSON = "application/json; charset=utf-8"

    /** The headers of a signed call that go on to the Mac: none the signature covers, none the Mac acts on but these. */
    private val SIGNED_PASS = setOf("accept", "accept-language", "user-agent")

    /**
     * What goes to the Mac for a link that is its own key (a Telescope picture, a preview): Lens's
     * headers as above, never the phone's credentials or the glasses' marker (nothing there needs
     * them), and for a preview only its own cookie.
     */
    fun openHeaders(req: HttpRequest, preview: Boolean): List<Pair<String, String>> {
        val out = passOn(req)
        if (preview) previewCookie(req)?.let { out += "Cookie" to "$PREVIEW_COOKIE=$it" }
        return out
    }

    /** The preview cookie's value, when the request carries one. */
    fun previewCookie(req: HttpRequest): String? =
        req.header("cookie")?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$PREVIEW_COOKIE=") }
            ?.substringAfter('=')?.takeIf { Regex("""^[\w-]{1,128}$""").matches(it) }

    private fun passOn(req: HttpRequest): ArrayList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for ((k, v) in req.headers) {
            if (k in HOP || k == "authorization" || k == "cookie" || k == "host" || k == "origin" || k == "referer" || k == "content-length") continue
            if (k.startsWith("x-hypernova-") || k.startsWith("x-forwarded-") || k == "forwarded" || k == "tailscale-user-login") continue
            if (k.startsWith("sec-fetch-") || k.startsWith("access-control-request-")) continue
            // OkHttp asks for gzip itself and undoes it; a page's own Accept-Encoding would hand it the raw bytes.
            if (k == "accept-encoding") continue
            out += k to v
        }
        return out
    }

    /**
     * What goes back to Lens with the Mac's answer: its own headers, less the connection's and the
     * Mac's CORS (the bridge says its own, for the page's origin) and cookies (a page has no business
     * with the Mac's).
     */
    fun downstreamHeaders(upstream: List<Pair<String, String>>, preview: Boolean = false): List<Pair<String, String>> =
        upstream.filter { (k, v) ->
            val n = k.lowercase()
            // A preview's own cookie comes back for its page's later requests; no other cookie does.
            val cookie = n == "set-cookie" && !(preview && v.trim().startsWith("$PREVIEW_COOKIE="))
            // The bridge's own marker is the bridge's to give, never the Mac's (or a previewed page's).
            n !in HOP && n != "content-length" && !cookie && !n.startsWith("access-control-") && n != SEALED.lowercase()
        }

    /**
     * CORS for an answer: the page's own origin echoed when it's Lens's, and nothing for anyone else
     * (no Origin, or the bridge's own pages, need none). Never `*`: that would let any page read it.
     * The signature goes as a header, and there are no credentials. The preflight's answer may be
     * kept for two hours (Lens fetches with the default cache mode for that, and every answer says
     * `no-store`), so a call costs one connection, not two.
     */
    fun cors(origin: String?, kind: OriginKind, preflight: HttpRequest? = null): List<Pair<String, String>> {
        val out = arrayListOf("Vary" to "Origin")
        if (origin == null || (kind != OriginKind.Lens && kind != OriginKind.DevNetwork)) return out
        out += "Access-Control-Allow-Origin" to origin
        if (preflight != null) {
            out += "Access-Control-Allow-Methods" to "GET, POST, PUT, DELETE"
            out += "Access-Control-Allow-Headers" to "Authorization, Content-Type"
            out += "Access-Control-Max-Age" to "7200"
            // Chromium asks before a page reaches the phone's own loopback.
            if (preflight.header("access-control-request-private-network") == "true") out += "Access-Control-Allow-Private-Network" to "true"
        }
        // Nothing else: a signed call's answer has its headers inside the seal.
        out += "Access-Control-Expose-Headers" to SEALED
        return out
    }

    // ─── Writing ────────────────────────────────────────────────────────────

    fun reason(status: Int): String = when (status) {
        101 -> "Switching Protocols"; 200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 408 -> "Request Timeout"
        409 -> "Conflict"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; 504 -> "Gateway Timeout"
        else -> "Status"
    }

    /**
     * A response head. [length] null: the body runs until the connection closes. One request per
     * connection ("close"): loopback connections cost nothing, and it keeps the server simple. A
     * WebSocket's 101 says "Upgrade" instead.
     */
    fun head(status: Int, headers: List<Pair<String, String>>, length: Long?, connection: String = "close"): ByteArray = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        for ((k, v) in headers) if (!k.contains('\n') && !v.contains('\n') && !v.contains('\r')) append(k).append(": ").append(v).append("\r\n")
        if (length != null) append("Content-Length: ").append(length).append("\r\n")
        append("Connection: ").append(connection).append("\r\n\r\n")
    }.toByteArray(Charsets.ISO_8859_1)

    /** The daemon's error shape, so Lens reads the bridge's refusals as it reads the Mac's. */
    fun error(code: String, message: String, extra: Map<String, Any> = emptyMap()): ByteArray {
        val e = org.json.JSONObject().put("code", code).put("message", message)
        for ((k, v) in extra) e.put(k, v)
        return org.json.JSONObject().put("error", e).toString().toByteArray()
    }
}

