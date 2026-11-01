package com.prudnikweb.hypernova.data.live

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A scripted daemon (docs/PROTOCOL.md) on plain HTTP: `GET /v1/health` for the router's probe,
 * `GET /v1/snapshot`, the `/v1/ws` socket, and whatever a test [on]s. Everything the phone asks is
 * kept in [requests]; everything it says on the socket in [received].
 */
class FakeDaemon(val name: String = "z2") : AutoCloseable {
    private val server = MockWebServer()

    /** A request the phone made. */
    data class Req(val method: String, val path: String, val body: String, val auth: String?, val headers: Map<String, String> = emptyMap()) {
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
                val r = Req(request.method ?: "GET", request.path ?: "/", request.body.readUtf8(), request.getHeader("Authorization"), request.headers.associate { (k, v) -> k.lowercase() to v })
                requests += r
                if (down) return drop()
                if (r.bare == "/v1/health") return json(200, JSONObject().put("ok", true))
                if (unauthorized) return refusal(401, "unauthorized", "Unknown device")
                routes["${r.method} ${r.bare}"]?.let { return it(r) }
                return when (r.bare) {
                    "/v1/snapshot" -> json(200, snapshot)
                    "/v1/ws" -> socketStatus?.let { refusal(it, "nope", null) } ?: MockResponse().withWebSocketUpgrade(listener)
                    else -> refusal(404, "not_found", "No such route")
                }
            }
        }
        server.start()
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

        fun msg(t: String, build: JSONObject.() -> Unit = {}): JSONObject = JSONObject().put("t", t).apply(build)
    }
}
