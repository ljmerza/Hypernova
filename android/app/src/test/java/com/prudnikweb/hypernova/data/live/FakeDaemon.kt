package com.prudnikweb.hypernova.data.live

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * A scripted daemon (docs/PROTOCOL.md) on plain HTTP: `GET /v1/health` for the router's probe,
 * `GET /v1/snapshot`, the `/v1/ws` socket, and whatever a test [on]s. Everything the phone asks is
 * kept in [requests]; everything it says on the socket in [received]. [port]: 0, any free one.
 *
 * With [lan], also the daemon's local listener (docs/PROTOCOL.md, "Local network (direct HTTPS)"),
 * as daemon/src/lan.ts and http.ts run it: HTTPS on loopback with its own self-signed certificate
 * (the phone pins [fingerprint]), a client certificate asked for but not required, and a device CA
 * that signs the phone's request at `POST /v1/device/cert`. Without a certificate this CA signed
 * (the latest one, for the device it names), only health, pairing and the certificate route answer
 * there (401 `device_cert_required`), and the socket's upgrade is refused. The snapshot then says
 * the listener (`lan`), with `deviceCert` true once a certificate is out.
 */
class FakeDaemon(val name: String = "z2", val lan: Boolean = false, port: Int = 0) : AutoCloseable {
    private val server = MockWebServer()
    private val lanServer: MockWebServer? = if (lan) MockWebServer() else null

    /** The listener's own certificate (self-signed, as the daemon's is), which the phone pins. */
    val listenerCert: HeldCertificate? = if (lan) HeldCertificate.Builder().commonName("Hypernova $name").addSubjectAlternativeName("127.0.0.1").build() else null
    /** The device CA that signs the phones' certificates. */
    val deviceCa: HeldCertificate? = if (lan) HeldCertificate.Builder().commonName("Hypernova device CA").certificateAuthority(0).build() else null
    /** Lowercase hex SHA-256 of the listener's certificate (DER), as the pairing link and the snapshot say it. */
    val fingerprint: String? get() = listenerCert?.let { sha256(it.certificate.encoded) }
    /** The listener's port. */
    val lanPort: Int get() = lanServer?.port ?: error("no listener")
    /** The device certificates issued, newest last; only the newest gets in (a new one replaces the old). */
    val issued = CopyOnWriteArrayList<X509Certificate>()
    /** The certificate route refuses with this status and code (null: it issues). */
    @Volatile var certRefusal: Pair<Int, String>? = null
    /** Forget the issued certificates (the server lost them): the snapshot says `deviceCert: false` again. */
    fun forgetCerts() = issued.clear()

    /** A request the phone made; [overLan]: on the listener, with the [clientCert] its handshake presented (if any). */
    data class Req(val method: String, val path: String, val body: String, val auth: String?, val overLan: Boolean = false, val clientCert: X509Certificate? = null, val headers: Map<String, String> = emptyMap()) {
        val bare: String get() = path.substringBefore('?')
        val json: JSONObject get() = if (body.isBlank()) JSONObject() else JSONObject(body)
        val query: String get() = path.substringAfter('?', "")
    }

    /** The snapshot `GET /v1/snapshot` answers with. */
    @Volatile var snapshot: JSONObject = snapshot()
    /** Nothing answers: every request's connection closes without a reply (the server asleep, off the network). */
    @Volatile var down = false
    /** Every request but the health probe is refused with 401 (the server forgot this phone). */
    @Volatile var unauthorized = false
    /** The socket's upgrade is refused with this status (null: it opens). */
    @Volatile var socketStatus: Int? = null

    val requests = CopyOnWriteArrayList<Req>()
    val received = CopyOnWriteArrayList<String>()
    private val routes = ConcurrentHashMap<String, (Req) -> MockResponse>()
    @Volatile var socket: WebSocket? = null
        private set
    /** How many sockets the phone has opened. */
    val opened = AtomicInteger()

    val base: String get() = server.url("/").toString().trimEnd('/')

    init {
        server.dispatcher = object : Dispatcher() {
            // Down: each request is read (so the tries can be counted), then its connection closes
            // unanswered. (DISCONNECT_AT_START from dispatch() would answer 200: MockWebServer reads it from peek().)
            override fun dispatch(request: RecordedRequest): MockResponse {
                val hs = request.handshake
                val r = Req(
                    request.method ?: "GET", request.path ?: "/", request.body.readUtf8(), request.getHeader("Authorization"),
                    overLan = hs != null, clientCert = hs?.peerCertificates?.firstOrNull() as? X509Certificate,
                    headers = request.headers.associate { (k, v) -> k.lowercase() to v },
                )
                requests += r
                if (down) return drop()
                if (r.bare == "/v1/health") return json(200, JSONObject().put("ok", true))
                // The listener: a certified device, or one of the routes open to anyone there (http.ts, openOnLan).
                if (r.overLan && !certified(r.clientCert) && !openOnLan(r)) {
                    return if (r.bare == "/v1/ws") MockResponse().setResponseCode(401)
                    else refusal(401, "device_cert_required", "This route needs your device certificate on the local network.")
                }
                if (unauthorized) return refusal(401, "unauthorized", "Unknown device")
                routes["${r.method} ${r.bare}"]?.let { return it(r) }
                if (lan && r.method == "POST" && r.bare == "/v1/device/cert") return issue(r)
                return when (r.bare) {
                    "/v1/snapshot" -> json(200, withLan(snapshot))
                    "/v1/ws" -> socketStatus?.let { refusal(it, "nope", null) } ?: MockResponse().withWebSocketUpgrade(listener)
                    else -> refusal(404, "not_found", "No such route")
                }
            }
        }
        // A fixed [port] only where a picture shows the address (0: any free one).
        server.start(port)
        lanServer?.let { l ->
            l.dispatcher = server.dispatcher
            l.useHttps(listenerContext().socketFactory, false)
            // Asked for, not required: a phone without one still reaches health, pairing and the certificate route.
            l.requestClientAuth()
            l.start()
        }
    }

    private fun openOnLan(r: Req): Boolean =
        (r.method == "GET" && r.bare == "/v1/health") || (r.method == "POST" && (r.bare == "/v1/pair" || r.bare == "/v1/device/cert"))

    /** Signed by the device CA, and the latest one issued (lan.ts, lanDevice). */
    private fun certified(c: X509Certificate?): Boolean {
        val ca = deviceCa ?: return false
        if (c == null) return false
        val signed = runCatching { c.verify(ca.certificate.publicKey); true }.getOrDefault(false)
        return signed && issued.lastOrNull()?.encoded?.contentEquals(c.encoded) == true
    }

    private fun withLan(snap: JSONObject): JSONObject {
        if (!lan || snap.has("lan")) return snap
        return JSONObject(snap.toString()).put(
            "lan",
            JSONObject().put("port", lanPort).put("addresses", JSONArray(listOf("127.0.0.1"))).put("fingerprint", fingerprint).put("deviceCert", issued.isNotEmpty()),
        )
    }

    /**
     * `POST /v1/device/cert {csr}`: the request's signature checked with its own key, then a
     * certificate for that key, CN as the request names it, signed by the device CA
     * (daemon/src/tls.ts, issueDeviceCert). Answers `{cert, ca, fingerprint}`, base64 DER.
     */
    private fun issue(r: Req): MockResponse {
        certRefusal?.let { (status, code) -> return refusal(status, code, "No certificate for you") }
        val csr = runCatching { Csr.read(Base64.getDecoder().decode(r.json.getString("csr"))) }.getOrNull()
            ?: return refusal(400, "bad_csr", "That isn't a certificate request.")
        // HeldCertificate keeps a private key with the certificate; the phone's never leaves the phone, so any stands in.
        val stand = KeyPairGenerator.getInstance("EC").generateKeyPair().private
        val cert = HeldCertificate.Builder().keyPair(csr.publicKey, stand).commonName(csr.cn).signedBy(deviceCa!!).build().certificate
        issued += cert
        return json(
            200,
            JSONObject().put("cert", b64(cert.encoded)).put("ca", b64(deviceCa.certificate.encoded)).put("fingerprint", fingerprint),
        )
    }

    /** The listener's TLS: its own certificate, and any client chain let through to the routes, which judge it ([certified]): Node's rejectUnauthorized false. */
    private fun listenerContext(): SSLContext {
        val km = HandshakeCertificates.Builder().heldCertificate(listenerCert!!).build().keyManager
        val tm = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("a server")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = throw java.security.cert.CertificateException("a server")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = throw java.security.cert.CertificateException("a server")
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(deviceCa!!.certificate)
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(km), arrayOf(tm), SecureRandom()) }
    }

    /** Just enough of a PKCS#10 request: its subject's CN and its key, its signature checked. */
    class Csr(val cn: String, val publicKey: java.security.PublicKey) {
        companion object {
            /** One DER element at [at]: its tag, where its body starts and where it ends. */
            private class Tlv(val tag: Int, val start: Int, val bodyAt: Int, val end: Int)

            private fun tlv(b: ByteArray, at: Int): Tlv {
                val tag = b[at].toInt() and 0xff
                var i = at + 1
                val first = b[i++].toInt() and 0xff
                val len = if (first < 0x80) first else (0 until (first and 0x7f)).fold(0) { acc, _ -> (acc shl 8) or (b[i++].toInt() and 0xff) }
                return Tlv(tag, at, i, i + len)
            }

            private fun children(b: ByteArray, t: Tlv): List<Tlv> {
                val out = ArrayList<Tlv>()
                var i = t.bodyAt
                while (i < t.end) tlv(b, i).also { out += it; i = it.end }
                return out
            }

            fun read(der: ByteArray): Csr {
                val top = tlv(der, 0)
                val (info, _, sig) = children(der, top)
                val (version, subject, spki) = children(der, info)
                check(version.tag == 0x02 && der[version.bodyAt].toInt() == 0) { "version 0" }
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der.copyOfRange(spki.start, spki.end)))
                // BIT STRING: its first byte counts the unused bits (none).
                val signature = der.copyOfRange(sig.bodyAt + 1, sig.end)
                val ok = Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(der, info.start, info.end - info.start); verify(signature) }
                check(ok) { "the request's signature doesn't verify" }
                // Subject: SEQUENCE { SET { SEQUENCE { OID 2.5.4.3, UTF8String } } }
                val atv = children(der, children(der, children(der, subject).single()).single())
                val value = atv[1]
                return Csr(String(der, value.bodyAt, value.end - value.bodyAt, Charsets.UTF_8), key)
            }
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            opened.incrementAndGet()
        }
        override fun onMessage(webSocket: WebSocket, text: String) { received += text }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
    }

    /** Answer `METHOD /path` (no query) with [answer]. */
    fun on(method: String, path: String, answer: (Req) -> MockResponse) { routes["$method $path"] = answer }

    /** Answer `METHOD /path` with 200 and [body]. */
    fun ok(method: String, path: String, body: JSONObject = JSONObject()) = on(method, path) { json(200, body) }

    /** Pushes a message over the live socket. */
    fun push(msg: JSONObject) {
        val s = socket ?: error("no socket open")
        check(s.send(msg.toString())) { "the socket is closed" }
    }

    /** The server closes the socket (a restart of the daemon, say). */
    fun closeSocket(code: Int = 1001, reason: String = "going away") {
        socket?.close(code, reason)
    }

    /** The phone's requests to [method] [path] (no query), in order. */
    fun calls(method: String, path: String): List<Req> = requests.filter { it.method == method && it.bare == path }

    fun count(method: String, path: String): Int = calls(method, path).size

    override fun close() {
        runCatching { socket?.cancel() }
        runCatching { server.shutdown() }
        runCatching { lanServer?.shutdown() }
    }

    companion object {
        /** No answer at all: the connection closes once the request is read (nothing answered, for the phone). */
        fun drop() = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)

        fun json(status: Int, body: JSONObject) = MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json").setBody(body.toString())

        /** The daemon's `{error: {code, message}}` with [status]. */
        fun refusal(status: Int, code: String, message: String?, extra: JSONObject.() -> Unit = {}) = json(
            status,
            JSONObject().put("error", JSONObject().put("code", code).apply { if (message != null) put("message", message) }.apply(extra)),
        )

        fun session(
            id: String,
            state: String = "idle",
            title: String = "Session $id",
            lastActivityAt: Long = 1_000L,
            more: JSONObject.() -> Unit = {},
        ): JSONObject = JSONObject()
            .put("id", id).put("title", title).put("cwd", "/home/me/proj-$id").put("projectName", "proj-$id")
            .put("origin", "hypernova").put("state", state).put("permissionMode", "default")
            .put("createdAt", 500L).put("lastActivityAt", lastActivityAt)
            .apply(more)

        fun approval(
            id: String,
            sessionId: String,
            summary: String = "Bash: ls",
            kind: String = "tool",
            more: JSONObject.() -> Unit = {},
        ): JSONObject = JSONObject()
            .put("id", id).put("sessionId", sessionId).put("kind", kind).put("toolName", "Bash")
            .put("summary", summary).put("createdAt", 900L).put("input", JSONObject().put("command", "ls"))
            .apply(more)

        fun usage(fivePct: Double, sevenPct: Double, fiveReset: Long, sevenReset: Long): JSONObject = JSONObject()
            .put("source", "official")
            .put("fiveHour", JSONObject().put("pct", fivePct).put("resetsAt", fiveReset))
            .put("sevenDay", JSONObject().put("pct", sevenPct).put("resetsAt", sevenReset))

        fun snapshot(
            name: String = "z2",
            sessions: List<JSONObject> = emptyList(),
            approvals: List<JSONObject> = emptyList(),
            usage: JSONObject? = null,
            platform: String = "linux",
            more: JSONObject.() -> Unit = {},
        ): JSONObject = JSONObject()
            .put("mac", JSONObject().put("id", "m-$name").put("name", name).put("daemonVersion", "1.0.0").put("platform", platform))
            .put("sessions", JSONArray(sessions))
            .put("approvals", JSONArray(approvals))
            .put("projects", JSONArray())
            .put("providers", JSONArray())
            .apply { if (usage != null) put("usage", usage) }
            .apply(more)

        fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

        fun msg(t: String, build: JSONObject.() -> Unit = {}): JSONObject = JSONObject().put("t", t).apply(build)
    }
}
