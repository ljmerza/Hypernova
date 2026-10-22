package com.prudnikweb.hypernova.bridge

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.data.ActiveRoutes
import com.prudnikweb.hypernova.data.Api
import com.prudnikweb.hypernova.data.ApiException
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.RouteKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The phone bridge (docs/PROTOCOL.md, "The phone bridge"; docs/DECISIONS.md, "Lens talks only to
 * the Hypernova app"): Lens, in the Even App's web view on this phone, talks to this app at
 * 127.0.0.1:47380, and the app carries its calls to the Mac over the route it uses itself (the
 * Mac's own network or Tailscale: data/LanRoute.kt), with this phone's own credentials and the
 * marker `X-Hypernova-Via: lens`. So Lens needs no pairing, token or Mac address of its own, one
 * public Lens build works for everyone, and the glasses keep this app's connection through network
 * changes.
 *
 * Any app on the phone can reach loopback, and any app can take the port while this one doesn't
 * hold it, so Lens asks first and the owner allows it here, by a key both agree without sending it
 * ([BridgeAccess], [BridgeCrypto]), and every call is sealed under a key of its own ([BridgeSeal]),
 * so whatever relays or reads the loopback connection sees only ciphertext. Without a call signed with that key, the bridge answers only its
 * ping, the ask and its poll, and carries only what is its own key, to be looked at: a Telescope
 * picture by its name, and a preview a Lens with a grant opened here ([OpenPreviews]). Only pages
 * of the Even App (Lens, at `http://127.0.0.1:<port>`) may use it ([BridgeHttp.originKind]). The
 * glasses' settings live in this app ([GlassesSettings]) and reach Lens through here too.
 */
object LensBridge {
    /**
     * Whether the bridge starts at all. Off from its first commit until its security reviews'
     * findings were fixed (2026-10-17: an open proxy through preview cookies and odd targets, pages
     * with a null or private origin, bodies read before any check, a feed outliving its revoked
     * token; then a squatter on the port collecting the token, redirects followed, slow senders).
     * On since the second round's fixes (the token agreed and never sent, calls signed); the third
     * round sealed every call and feed (protocol 3).
     */
    const val ENABLED = true

    const val PORT = 47380
    /** What the ping says it speaks (BridgeAccess.PROTOCOL): 4 asks for pictures by name in the seal and keeps settings out of the poll. A Lens for less is told to update. */
    const val PROTOCOL = BridgeAccess.PROTOCOL
    private const val TAG = "LensBridge"
    /**
     * Connections at once, by pool ([ConnectionSlots]): anything without a grant yet, Lens's calls,
     * and its live feeds (two per Lens). Each holds a request head (32 KB at most) and, for the
     * bridge's own routes, a 16 KB body; a carried body streams through, never held; a live feed
     * holds one message from Lens ([FEED_MESSAGE]). So all of them together stay a few tens of MB at
     * worst, and only a few hundred KB for anything without a grant.
     */
    private const val MAX_OPEN = 16
    private const val MAX_CALLS = 16
    private const val MAX_FEEDS = 8
    /**
     * A carried body up to this much (all of Lens's: a little JSON, sealed) is read whole, checked
     * against its signature and opened before the Mac is called; a bigger or chunked one is refused
     * (413): nothing Lens sends is bigger, and a sealed body is opened whole or not at all.
     */
    private const val CHECK_WHOLE = 1024 * 1024
    /** A message from Lens on a live feed is a little JSON or 40 ms of the glasses' audio: 1 MB is plenty. */
    private const val FEED_MESSAGE = 1024 * 1024
    /** A web view sends a request's head in one go: all of it within this, however it trickles. */
    private const val HEAD_DEADLINE_MS = 10_000L
    /** The bridge's own routes' small bodies (the ask, the settings), all of each within this. */
    private const val SMALL_BODY_DEADLINE_MS = 10_000L
    /** Between two reads of a carried body (an upload streams to the Mac as it comes). */
    private const val BODY_TIMEOUT_MS = 15_000

    private lateinit var app: Context
    private lateinit var prefs: android.content.SharedPreferences
    lateinit var access: BridgeAccess
        private set
    private lateinit var previews: OpenPreviews
    private var version = "?"
    @Volatile private var started = false
    private val main = Handler(Looper.getMainLooper())
    private val slots = ConnectionSlots(MAX_OPEN, MAX_CALLS, MAX_FEEDS)
    /** Calls carried for each grant right now, and previews streaming: a revoke ends them at once. */
    private val calls = InFlight()
    /**
     * The id of the port as this app opened it, new at each opening: signed calls name it, so one
     * signed for whoever held the port before (another app, while this one didn't) is no good here.
     */
    @Volatile private var portId = ""

    // ─── What the app's screens show (Compose state, written on the main thread) ───

    /** "Allow Hypernova Lens to connect?" waiting for an answer (the in-app prompt; the notification too). */
    var pending by mutableStateOf<BridgeAccess.Request?>(null)
        private set
    /** The Lenses allowed on this phone, most recently used first (Settings → Glasses). */
    var grants by mutableStateOf<List<BridgeAccess.Grant>>(emptyList())
        private set
    /** Grants with a live feed open right now. */
    var live by mutableStateOf<Set<String>>(emptySet())
        private set
    var settings by mutableStateOf(GlassesSettings())
        private set
    /** The listener can't open its port (another app holds 47380): Settings says so, and a notification while it lasts. */
    var failed by mutableStateOf(false)
        private set
    /** Allowed here, and the glasses haven't collected it yet: Settings says "Waiting for a tap on the glasses". */
    var collecting by mutableStateOf(false)
        private set

    fun start(context: Context, appVersion: String) {
        if (started) return
        started = true
        app = context.applicationContext
        version = appVersion
        prefs = app.getSharedPreferences("glasses", Context.MODE_PRIVATE)
        access = BridgeAccess(PrefsStore(prefs))
        previews = OpenPreviews(load = { prefs.getString("previews", null) }, save = { prefs.edit().putString("previews", it).apply() })
        // Kept by an older version of the app: brought up to date once (GlassesSettings.stored).
        val kept = prefs.getInt("settingsVersion", 1)
        settings = GlassesSettings.stored(prefs.getString("settings", null), kept)
        if (kept < GlassesSettings.STORE_VERSION) {
            prefs.edit().putString("settings", settings.toJson().toString()).putInt("settingsVersion", GlassesSettings.STORE_VERSION).apply()
        }
        // The Start sheet's last choice, from before the glasses heard it (newSessionChosen keeps it since).
        if (settings.newSession == null) {
            val m = com.prudnikweb.hypernova.data.NewSessionMemory.load(app)
            GlassesSettings.NewSession.of(m.provider, m.model(m.provider))?.let { n -> updateSettings { it.copy(newSession = n) } }
        }
        access.onChange = { main.post(::refresh) }
        refresh()
        BridgeNotice.channel(app)
        // A prompt waiting while the app comes to the front shows in the app; when it goes, in a notification.
        Core.scope.launch { snapshotFlow { Core.visible }.collect { visibleChanged(it) } }
        // Grants belong to the pairing they were made under: Forget, or pairing again, ends them.
        Core.scope.launch { snapshotFlow { Core.pairing?.let(::pairingKey) }.collect { pairingChanged(it) } }
        thread(name = "lens-bridge", isDaemon = true) { listen() }
    }

    /** When an ask's notification was last shown, or an ask answered or taken down (main thread). */
    private var noticeAt = 0L

    private fun refresh() {
        val p = access.pending
        if (p?.id != pending?.id) {
            val was = pending
            pending = p
            val now = System.currentTimeMillis()
            // Answered here, by the notification, or run out: the notification goes with it.
            if (was != null) {
                BridgeNotice.cancel(app, was.id)
                noticeAt = now
            }
            if (p != null && !Core.visible) {
                // A new ask within moments of another being shown, answered or taken down (in place
                // of one shown, or right after): its notification comes a moment later, so an Allow
                // meant for the one before can't land on this one.
                if (now - noticeAt >= BridgeNotice.REPLACED_DELAY_MS) notify(p)
                else main.postDelayed({ if (pending?.id == p.id && !Core.visible) notify(p) }, BridgeNotice.REPLACED_DELAY_MS - (now - noticeAt))
            }
        }
        grants = access.grants()
        live = feeds.openGrants()
        val waiting = access.collecting()
        // An Allow nobody collects runs out quietly: look again once it would have.
        if (waiting && !collecting) main.postDelayed(::refresh, BridgeAccess.COLLECT_TTL_MS + 1_000L)
        collecting = waiting
    }

    private fun notify(p: BridgeAccess.Request) {
        noticeAt = System.currentTimeMillis()
        BridgeNotice.ask(app, p)
    }

    /** The app came to the front (or went): a prompt waiting shows in the app instead, or in a notification. */
    fun visibleChanged(visible: Boolean) {
        // "Tap to open Hypernova" did its job however the app came to the front.
        if (visible) BridgeNotice.cancelOpen(app)
        val p = pending ?: return
        if (visible) BridgeNotice.cancel(app, p.id) else notify(p)
    }

    // ─── The owner's answers and choices ────────────────────────────────────

    fun answer(id: String, allow: Boolean) {
        if (!started) return
        val r = access.answer(id, allow) ?: return
        BridgeNotice.cancel(app, id)
        main.post { noticeAt = System.currentTimeMillis() }
        // The Mac lists the glasses (and opens its Lens guide) as soon as they're allowed, and takes
        // them back if it had forgotten them: once Lens collected its grant (see [register]).
        if (r.state == BridgeAccess.State.Allowed) prefs.edit().putBoolean("register", true).apply()
        refresh()
    }

    /** Disconnect in Settings → Glasses: that Lens's key stops working, and all it had going ends at once. */
    fun disconnect(grantId: String) {
        access.revoke(grantId) ?: return
        ended(grantId, 4003, "disconnected on the phone")
        if (access.grants().isEmpty()) forgetOnMac()
        refresh()
    }

    /** The Mac's "Forget" on these glasses (`{t:"glasses.removed"}`): every Lens here lets go too. */
    fun macRemovedGlasses() {
        if (!started) return
        for (g in access.revokeAll()) ended(g.id, 4003, "removed on the Mac")
        previews.clear()
        calls.cancelAll()
        prefs.edit().putBoolean("register", false).apply()
        main.post(::refresh)
    }

    /**
     * Everything a grant had going ends now: its live feeds (with [code]), the calls being carried
     * for it, and its previews (also any streaming).
     */
    private fun ended(grantId: String, code: Int, reason: String) {
        closeFeeds(grantId, code, reason)
        // Its previews first: a preview stream starting this moment then finds its preview gone
        // (openProxy looks again once it's counted), rather than slipping in after the cancel.
        previews.drop(grantId)
        calls.cancel(grantId)
    }

    /**
     * One pairing, by the device id the Mac gave this phone for it (a new one at each pairing), or
     * its token's hash for a pairing from before device ids. Never the token itself.
     */
    private fun pairingKey(p: Pairing): String = p.deviceId ?: BridgeAccess.sha256(p.token).take(24)

    /**
     * This phone's pairing changed (or the app started): grants made under another pairing end,
     * with their feeds, calls and previews, and Lens asks again. A Lens allowed for one computer
     * never silently reaches the next one this phone pairs with (BridgeAccess.stillFor).
     */
    private fun pairingChanged(key: String?) {
        if (!BridgeAccess.stillFor(prefs.getString("computer", null), key)) {
            for (g in access.revokeAll()) ended(g.id, 4003, "this phone was paired again")
            previews.clear()
            calls.cancelAll()
            prefs.edit().putBoolean("register", false).apply()
        }
        prefs.edit().putString("computer", key).apply()
        refresh()
    }

    /** Settings → Glasses changed something: kept, and every Lens hears it on its live feed. */
    fun updateSettings(change: (GlassesSettings) -> GlassesSettings) {
        if (!started) return
        val next = change(settings)
        if (next == settings) return
        settings = next
        prefs.edit().putString("settings", next.toJson().toString()).apply()
        push(next)
    }

    /** A session started from the phone's sheet on [providerId] and [model]: new sessions from the glasses start there too. */
    fun newSessionChosen(providerId: String?, model: String?) {
        val n = GlassesSettings.NewSession.of(providerId, model) ?: return
        updateSettings { it.copy(newSession = n) }
    }

    /** "Practise the gestures on the glasses": Lens plays its guide when it hears this. */
    fun askGuide() = updateSettings { it.copy(guideAsk = System.currentTimeMillis()) }

    /** The last Lens left: the Mac takes the glasses off its list (best effort; it also forgets them with the phone). */
    private fun forgetOnMac() {
        val p = Core.pairing ?: return
        Core.scope.launch { runCatching { Api(p.baseUrl, p.token).delete("/v1/devices/me/glasses") } }
    }

    /**
     * After an Allow, once: the Mac is told these glasses are this phone's (`PUT
     * /v1/devices/me/glasses`), which also takes them back if the Mac had forgotten them. Before the
     * first call is carried, so it can't be refused for that. A Mac from before the bridge (404)
     * has nothing to be told.
     */
    private fun register(p: Pairing, grant: BridgeAccess.Grant) {
        if (!prefs.getBoolean("register", false)) return
        val ok = runBlocking {
            withTimeoutOrNull(6_000) {
                try {
                    Api(p.baseUrl, p.token).put("/v1/devices/me/glasses", JSONObject().put("appVersion", grant.version ?: JSONObject.NULL))
                    true
                } catch (e: ApiException) {
                    e.status == 404
                }
            } ?: false
        }
        if (ok) prefs.edit().putBoolean("register", false).apply()
    }

    // ─── Listening ──────────────────────────────────────────────────────────

    /**
     * Opens the port and serves it; when another app holds it, says so (Settings, a notification)
     * and tries again every few seconds, for as long as this app runs ([PortKeeper]). Each opening
     * gets a new listener id.
     */
    private fun listen() = PortKeeper(
        open = { ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1")) },
        serve = { server, id ->
            portId = id
            Log.i(TAG, "listening on 127.0.0.1:$PORT")
            accept(server)
        },
        onBusy = { e ->
            Log.w(TAG, "can't listen on $PORT: ${e.message}")
            main.post {
                failed = true
                BridgeNotice.portBusy(app)
            }
        },
        onFree = {
            main.post {
                failed = false
                BridgeNotice.portFree(app)
            }
        },
        newId = { BridgeCrypto.b64(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }) },
    ).run()

    /** Takes connections until the port fails (it's then opened again, with a new listener id). */
    private fun accept(server: ServerSocket) {
        while (true) {
            val c = try {
                server.accept()
            } catch (e: Exception) {
                Log.w(TAG, "accept: ${e.message}")
                // One connection that couldn't be taken (out of descriptors for a moment) isn't the
                // port gone: the same socket goes on, and only one that's closed is opened again.
                if (server.isClosed) return
                Thread.sleep(200)
                continue
            }
            // Full of connections without a grant: the oldest still sending its request goes for this one.
            val slot = slots.admit { runCatching { c.close() } }
            if (slot == null) {
                runCatching { c.close() }
                continue
            }
            try {
                thread(name = "lens-bridge-conn", isDaemon = true) {
                    try {
                        serve(c, slot)
                    } catch (e: OutOfMemoryError) {
                        // An Error, not an Exception: uncaught it would end the whole app (its link to the
                        // Mac, approvals, every Lens). This connection goes; what it held is freed.
                        Log.w(TAG, "connection: out of memory, closed")
                    } catch (e: Throwable) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "connection: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        slots.release(slot)
                        runCatching { c.close() }
                    }
                }
            } catch (e: Throwable) {
                // No thread to be had (the system is short of memory): this connection is refused, the next may not be.
                slots.release(slot)
                runCatching { c.close() }
            }
        }
    }

    private class Reply(val out: OutputStream, val cors: List<Pair<String, String>>) {
        /** Once a call's signature checked out: what every answer to it is sealed with (BridgeAccess.Sealer). */
        var sealer: BridgeAccess.Sealer? = null
        fun json(status: Int, body: JSONObject) = bytes(status, body.toString().toByteArray(), "application/json; charset=utf-8")
        /** A status alone, no body (a HEAD's 405). */
        fun status(status: Int) {
            out.write(BridgeHttp.head(status, cors + listOf("Cache-Control" to "no-store"), 0))
            out.flush()
        }
        fun error(status: Int, code: String, message: String, extra: Map<String, Any> = emptyMap()) =
            bytes(status, BridgeHttp.error(code, message, extra), "application/json; charset=utf-8")
        /**
         * An answer, whole: to a signed call, sealed (the status, the content type and the body
         * inside; the outer answer is a 200 that says only `sealed`); else as it is.
         */
        fun bytes(status: Int, body: ByteArray, type: String?) {
            val s = sealer
            if (s != null) {
                val sealed = java.io.ByteArrayOutputStream()
                val w = s.writer(sealed)
                w.head(status, listOfNotNull(type?.let { "Content-Type" to it }))
                w.data(body)
                w.end()
                out.write(BridgeHttp.head(200, sealedHeaders(), sealed.size().toLong()))
                sealed.writeTo(out)
            } else {
                out.write(BridgeHttp.head(status, cors + listOfNotNull(type?.let { "Content-Type" to it }, "Cache-Control" to "no-store"), body.size.toLong()))
                out.write(body)
            }
            out.flush()
        }
        /** The outer head of a sealed answer: nothing of the answer itself. */
        fun sealedHeaders(): List<Pair<String, String>> =
            cors + listOf(BridgeHttp.SEALED to BridgeHttp.SEALED_VALUE, "Content-Type" to BridgeHttp.SEALED_TYPE, "Cache-Control" to "no-store")
    }

    private fun serve(c: Socket, slot: ConnectionSlots.Slot) {
        // The whole head within HEAD_DEADLINE_MS, however it trickles in (a socket's own timeout
        // counts only from the last byte).
        val raw = DeadlineInput(c.getInputStream(), System::currentTimeMillis) { c.soTimeout = it }
        raw.deadline(System.currentTimeMillis() + HEAD_DEADLINE_MS)
        val input = BufferedInputStream(raw)
        val out = c.getOutputStream()
        // The head alone first: everything below is judged before a byte of any body is read.
        val req = try {
            BridgeHttp.readHead(input) ?: return
        } catch (e: IOException) {
            if (e !is SocketTimeoutException) Reply(out, emptyList()).error(400, "bad_request", "Not an HTTP request.")
            return
        }
        val origin = req.header("origin")
        val kind = BridgeHttp.originKind(origin, PORT, BuildConfig.BRIDGE_DEV_ORIGINS)
        // CORS only for Lens's own origin: a refused page can't even read the refusal.
        val reply = Reply(out, BridgeHttp.cors(origin, kind))
        val route = when (val g = BridgeHttp.gate(req, PORT, BuildConfig.BRIDGE_DEV_ORIGINS)) {
            is BridgeHttp.Gate.Pass -> g.route
            is BridgeHttp.Gate.Stop -> {
                // The path only: a query may carry a signature (or, from an old Lens, a token).
                if (BuildConfig.DEBUG) Log.d(TAG, "refused ${BridgeHttp.forLog(req)}: ${g.code} (origin ${origin?.take(80)})")
                return reply.error(g.status, g.code, g.message)
            }
        }
        // A small body (the bridge's own routes) must come whole within a deadline of its own.
        raw.deadline(System.currentTimeMillis() + SMALL_BODY_DEADLINE_MS)
        when (route) {
            BridgeRoute.Preflight -> {
                slots.read(slot)
                out.write(BridgeHttp.head(204, BridgeHttp.cors(origin, kind, req), 0))
                out.flush()
            }
            BridgeRoute.Ping -> {
                slots.read(slot)
                reply.json(200, ping(req))
            }
            BridgeRoute.Ask -> {
                val body = smallBody(input, req, reply) ?: return
                slots.read(slot)
                ask(origin, body, reply)
            }
            is BridgeRoute.Reveal -> {
                val body = smallBody(input, req, reply) ?: return
                slots.read(slot)
                reveal(route.id, body, reply)
            }
            is BridgeRoute.Poll -> {
                slots.read(slot)
                poll(route.id, reply)
            }
            BridgeRoute.NotFound -> reply.error(404, "not_found", "Unknown endpoint.")
            BridgeRoute.Refused -> reply.error(403, "not_for_glasses", "The glasses can’t change this phone’s pairing.")
            is BridgeRoute.Preview, is BridgeRoute.PreviewFile -> {
                val token = if (route is BridgeRoute.Preview) route.token else (route as BridgeRoute.PreviewFile).token
                if (!previews.known(token)) return reply.error(404, "not_found", if (route is BridgeRoute.Preview) "Unknown preview." else "Unknown endpoint.")
                if (!slots.carry(slot)) return reply.error(503, "busy", "Too many files on their way right now.")
                raw.perRead(BODY_TIMEOUT_MS)
                openProxy(c, req, token, reply)
            }
            BridgeRoute.Proxy -> {
                // A sealed answer is a body, which a HEAD can't have (Lens never sends one): 405, bodiless.
                if (req.method == "HEAD") return reply.status(405)
                val grant = grantFor(req, reply) ?: return
                if (!slots.grant(slot, feed = req.isUpgrade)) return reply.error(503, "busy", "The glasses have too many calls open right now.")
                // A POST, PUT or PATCH carries a body, or it's no call of Lens's (OkHttp won't send one without).
                if (!req.isUpgrade && req.method != "GET" && req.method != "DELETE" && !req.hasBody) return reply.error(400, "bad_request", "That call has no body.")
                // Nothing to carry (a GET, a socket, a DELETE without a body): the signature must say so.
                if ((req.isUpgrade || req.method == "GET" || !req.hasBody) && !signedBody(reply, BridgeCrypto.EMPTY_BODY)) return
                raw.perRead(BODY_TIMEOUT_MS)
                val p = Core.pairing ?: return reply.error(503, "not_paired", "Hypernova on this phone isn’t paired with a Mac yet.")
                register(p, grant)
                if (req.isUpgrade) proxySocket(c, raw, input, out, req, grant, p, reply)
                else proxy(c, input, req, grant, p, reply)
            }
            // A Telescope picture, by the name inside the sealed body: fetched from the Mac and sealed back.
            BridgeRoute.Picture -> {
                val grant = grantFor(req, reply) ?: return
                if (!slots.grant(slot, feed = false)) return reply.error(503, "busy", "The glasses have too many calls open right now.")
                if (!req.hasBody || BridgeHttp.declaredLength(req) > BridgeHttp.MAX_SMALL_BODY) return reply.error(400, "bad_request", "Not a picture’s name.")
                raw.perRead(BODY_TIMEOUT_MS)
                val body = sealedBody(input, req, reply) ?: return
                val name = runCatching { JSONObject(String(body)).optString("name") }.getOrNull()?.takeIf { BridgeHttp.PICTURE_NAME.matches(it) }
                    ?: return reply.error(400, "bad_request", "Not a picture’s name.")
                val p = Core.pairing ?: return reply.error(503, "not_paired", "Hypernova on this phone isn’t paired with a Mac yet.")
                register(p, grant)
                proxy(c, input, req, grant, p, reply, picture = name)
            }
            // Lens's "Open Hypernova" (BridgeOpen.kt): the app there and then when it's on screen, else a notification to tap.
            BridgeRoute.Open -> {
                val grant = grantFor(req, reply) ?: return
                if (!slots.grant(slot, feed = false)) return reply.error(503, "busy", "The glasses have too many calls open right now.")
                if (!req.hasBody || BridgeHttp.declaredLength(req) > BridgeHttp.MAX_SMALL_BODY) return reply.error(400, "bad_request", "Say which screen to open.")
                val body = sealedBody(input, req, reply) ?: return
                BridgeOpen.screen(body) ?: return reply.error(400, "bad_screen", "Hypernova on this phone opens only the glasses’ settings.")
                if (BuildConfig.DEBUG) Log.d(TAG, "open for ${grant.name}")
                reply.json(200, JSONObject().put("shown", openApp().wire))
            }
            BridgeRoute.Status, BridgeRoute.Settings, BridgeRoute.Leave -> {
                if (req.method == "HEAD") return reply.status(405)
                val grant = grantFor(req, reply) ?: return
                if (!slots.grant(slot, feed = false)) return reply.error(503, "busy", "The glasses have too many calls open right now.")
                if ((route != BridgeRoute.Settings || req.method == "GET" || !req.hasBody) && !signedBody(reply, BridgeCrypto.EMPTY_BODY)) return
                when (route) {
                    BridgeRoute.Status -> reply.json(200, status())
                    BridgeRoute.Leave -> {
                        main.post { disconnect(grant.id) }
                        reply.json(200, JSONObject().put("ok", true))
                    }
                    else -> settingsRoute(input, req, grant, reply)
                }
            }
        }
    }

    /**
     * The grant whose key signed this call (for this listener, method, path and query, within a
     * minute, its nonce new), and from then on every answer to it is sealed under the call's key;
     * else the refusal, unsealed (there's no key to seal it with): 403 `bridge_access` for no
     * signature or a key not known here (Lens asks again), 401 `bridge_signature` for a known key's
     * signature that isn't good here and now (Lens has the app prove itself again and signs afresh),
     * 403 `update_lens` for a Lens that signs as protocol 2 did (it sealed nothing).
     */
    private fun grantFor(req: HttpRequest, reply: Reply): BridgeAccess.Grant? {
        if (BridgeHttp.oldSignature(req)) {
            reply.error(403, "update_lens", "This Hypernova Lens is older than Hypernova on this phone. Update Lens.")
            return null
        }
        return when (val v = access.verify(BridgeHttp.signed(req), portId, req.method, req.path, req.query)) {
            is BridgeAccess.Verdict.Ok -> {
                reply.sealer = v.sealer
                v.grant
            }
            BridgeAccess.Verdict.NoGrant -> {
                reply.error(403, "bridge_access", "Allow Hypernova Lens in Hypernova on your phone first.")
                null
            }
            BridgeAccess.Verdict.BadSignature -> {
                reply.error(401, "bridge_signature", "That call’s signature isn’t good here. Ask the bridge again, then sign it afresh.")
                null
            }
        }
    }

    /**
     * Whether what a signed call carries hashes to what it signed; else 400 `bad_body` (something
     * between Lens and the bridge changed it) and nothing is done.
     */
    private fun signedBody(reply: Reply, hash: String): Boolean {
        if (reply.sealer?.body == hash) return true
        reply.error(400, "bad_body", "That call’s body isn’t the one it signed.")
        return false
    }

    /**
     * A signed call's body, whole (CHECK_WHOLE at most, never chunked: 413), checked against the hash
     * it signed and opened (BridgeSeal; 400 `bad_seal` for one that isn't sealed under this call's
     * key); null once refused.
     */
    private fun sealedBody(input: InputStream, req: HttpRequest, reply: Reply): ByteArray? {
        val sealer = reply.sealer ?: return null
        if (req.chunked) {
            reply.error(413, "too_large", "That request is too large.")
            return null
        }
        val bytes = try { BridgeHttp.readBody(input, req, CHECK_WHOLE) } catch (e: TooLarge) {
            reply.error(413, "too_large", "That request is too large.")
            return null
        } catch (e: IOException) {
            reply.error(400, "bad_request", "Not an HTTP request.")
            return null
        }
        if (!signedBody(reply, BridgeCrypto.bodyHash(bytes))) return null
        return try { sealer.openBody(bytes) } catch (e: BridgeSeal.Broken) {
            reply.error(400, "bad_seal", "That call’s body isn’t sealed for this call.")
            null
        } catch (e: TooLarge) {
            reply.error(413, "too_large", "That request is too large.")
            null
        }
    }

    /** The body of a request to the bridge's own routes, a few KB at most; null once refused (413). */
    private fun smallBody(input: InputStream, req: HttpRequest, reply: Reply): ByteArray? = try {
        BridgeHttp.readBody(input, req)
    } catch (e: TooLarge) {
        reply.error(413, "too_large", "That request is too large.")
        null
    }

    /**
     * That the app is here, the protocol it speaks and the id of its port now; with Lens's
     * challenge (`?kid=…&n=…`) for a key it knows, the proof that it holds it.
     */
    private fun ping(req: HttpRequest): JSONObject {
        val id = portId
        val o = JSONObject().put("ok", true).put("app", "hypernova").put("version", version).put("bridge", PROTOCOL).put("id", id)
        access.proof(BridgeHttp.queryValue(req.query, "kid"), id, BridgeHttp.queryValue(req.query, "n"))?.let { o.put("proof", it) }
        return o
    }

    private fun ask(origin: String?, body: ByteArray, reply: Reply) {
        val b = runCatching { JSONObject(String(body)) }.getOrNull()
        fun field(k: String) = b?.optString(k)?.takeIf { it.isNotEmpty() }
        when (val r = access.request(origin, field("name"), field("version"), field("key"), field("commit"), b?.optInt("bridge", 0) ?: 0)) {
            is BridgeAccess.Asked.TooMany -> reply.error(429, "too_many_requests", "Too many requests to connect. Try again in a moment.", mapOf("retryAfterSeconds" to r.retryAfterSeconds))
            BridgeAccess.Asked.Refused -> reply.error(400, "update_lens", "This Hypernova Lens is older than Hypernova on this phone. Update Lens.")
            is BridgeAccess.Asked.Ok -> reply.json(
                202,
                JSONObject().put("id", r.request.id).put("key", r.appKey).put("nonce", r.nonce).put("expiresInSeconds", BridgeAccess.REQUEST_TTL_MS / 1000),
            )
        }
    }

    private fun reveal(id: String, body: ByteArray, reply: Reply) {
        val nonce = runCatching { JSONObject(String(body)).optString("nonce") }.getOrNull()
        when (access.reveal(id, nonce)) {
            is BridgeAccess.Revealed.Ok -> reply.json(200, JSONObject().put("state", "pending"))
            BridgeAccess.Revealed.Unknown -> reply.error(404, "no_request", "That request to connect is over. Ask again.")
            BridgeAccess.Revealed.Mismatch -> reply.error(400, "bad_commit", "That isn’t the nonce the request committed to. Ask again.")
        }
    }

    private fun poll(id: String, reply: Reply) {
        when (val r = access.poll(id)) {
            // No settings here: the poll is unsealed (anyone with the ask's id reads it, a relay could rewrite
            // it); Lens fetches them signed and sealed once the owner confirmed (GET /bridge/settings).
            is BridgeAccess.Poll.Allowed -> reply.json(200, JSONObject().put("state", "allowed").put("confirm", r.confirm))
            BridgeAccess.Poll.Pending -> reply.json(200, JSONObject().put("state", "pending"))
            BridgeAccess.Poll.Denied -> reply.json(200, JSONObject().put("state", "denied"))
            BridgeAccess.Poll.Expired -> reply.json(200, JSONObject().put("state", "expired"))
            BridgeAccess.Poll.Unknown -> reply.error(404, "no_request", "That request to connect is over. Ask again.")
        }
    }

    /** When "Tap to open Hypernova" last went up (main thread). */
    private var openNoticeAt = 0L

    /**
     * Opens the app at Settings → Glasses for Lens: there and then while it's on screen (Android lets
     * a visible app start its own activity), else by "Tap to open Hypernova", put up again at most
     * every [BridgeOpen.AGAIN_MS]; on the main thread, as the screens are.
     */
    private fun openApp(): BridgeOpen.Shown {
        val latch = CountDownLatch(1)
        var shown = BridgeOpen.Shown.Nothing
        main.post {
            try {
                val now = System.currentTimeMillis()
                shown = BridgeOpen.how(Core.visible, BridgeNotice.canOpen(app))
                when (shown) {
                    BridgeOpen.Shown.App -> runCatching {
                        app.startActivity(Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_GLASSES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { shown = BridgeOpen.Shown.Nothing }
                    BridgeOpen.Shown.Notification -> if (now - openNoticeAt >= BridgeOpen.AGAIN_MS) {
                        if (BridgeNotice.open(app)) openNoticeAt = now else shown = BridgeOpen.Shown.Nothing
                    }
                    BridgeOpen.Shown.Nothing -> Unit
                }
            } finally {
                latch.countDown()
            }
        }
        latch.await(3, TimeUnit.SECONDS)
        return shown
    }

    /** What Lens's status card says: the Mac this app is paired with, and whether it's reachable now. */
    private fun status(): JSONObject {
        val p = Core.pairing
        val live = Core.live
        val link = when (live?.link?.state) {
            LinkState.Connected -> "connected"
            LinkState.Connecting, LinkState.Reconnecting -> "connecting"
            LinkState.Unpaired -> "unpaired"
            LinkState.Demo -> "demo"
            LinkState.Offline -> "offline"
            null -> if (p == null) "unpaired" else "connecting"
        }
        val route = p?.let { ActiveRoutes.route(it.baseUrl).kind }
        return JSONObject()
            .put("paired", p != null)
            .put("mac", p?.macName ?: JSONObject.NULL)
            .put("link", link)
            .put("route", when (route) { RouteKind.Local -> "local"; RouteKind.Tailscale -> "tailscale"; RouteKind.Remote -> "remote"; null -> JSONObject.NULL })
            .put("app", version)
    }

    private fun settingsRoute(input: InputStream, req: HttpRequest, grant: BridgeAccess.Grant, reply: Reply) {
        if (req.method == "GET") return reply.json(200, JSONObject().put("settings", settings.toJson()))
        if (!req.hasBody) return reply.error(400, "bad_json", "That request was not valid JSON.")
        if (BridgeHttp.declaredLength(req) > BridgeHttp.MAX_SMALL_BODY) return reply.error(413, "too_large", "That request is too large.")
        val body = sealedBody(input, req, reply) ?: return
        val patch = runCatching { JSONObject(String(body)) }.getOrNull() ?: return reply.error(400, "bad_json", "That request was not valid JSON.")
        val latch = CountDownLatch(1)
        var after = settings
        // On the main thread, as Settings' own changes are, so the two never cross.
        main.post {
            updateSettings { it.merge(patch.optJSONObject("settings") ?: patch) }
            after = settings
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        if (BuildConfig.DEBUG) Log.d(TAG, "settings from ${grant.name}: ${patch.keys().asSequence().toList()}")
        reply.json(200, JSONObject().put("settings", after.toJson()))
    }

    // ─── Carrying calls to the Mac ──────────────────────────────────────────

    /**
     * Clients for the bridge's calls: the route's own, never following a redirect (the Mac's 3xx
     * goes back to Lens as it is: BridgeHttp.carrying), with room for Telescope (45 s) and big uploads.
     */
    private val slow = Collections.synchronizedMap(WeakHashMap<OkHttpClient, OkHttpClient>())
    private fun clientFor(c: OkHttpClient): OkHttpClient = slow.getOrPut(c) {
        BridgeHttp.carrying(c.newBuilder()).readTimeout(75, TimeUnit.SECONDS).writeTimeout(2, TimeUnit.MINUTES).build()
    }

    /** And for live feeds: the route's own, never following a redirect either. */
    private val feedClients = Collections.synchronizedMap(WeakHashMap<OkHttpClient, OkHttpClient>())
    private fun feedClientFor(c: OkHttpClient): OkHttpClient = feedClients.getOrPut(c) { BridgeHttp.carrying(c.newBuilder()).build() }

    private fun macUnreachable(reply: Reply, route: com.prudnikweb.hypernova.data.NetRoute) =
        reply.error(502, "mac_unreachable", "Hypernova on your phone can’t reach your Mac right now.", mapOf("route" to if (route.kind == RouteKind.Local) "local" else "tailscale"))

    /**
     * A call from Lens, signed, to the Mac: the URL from the route's own base by OkHttp's builder
     * (the paired Mac's scheme, host and port, never another), the body opened from its seal, the
     * phone's credentials and the glasses' marker added, the answer sealed back. Counted under its
     * grant while it lasts, so a revoke ends it there and then. [picture]: a Telescope picture's
     * name (BridgeRoute.Picture): then `GET /v1/shots/<name>.png`, whatever the request said.
     */
    private fun proxy(c: Socket, input: InputStream, req: HttpRequest, grant: BridgeAccess.Grant, p: Pairing, reply: Reply, picture: String? = null) {
        val route = ActiveRoutes.route(p.baseUrl)
        val method = if (picture != null) "GET" else req.method
        val url = (if (picture != null) BridgeHttp.upstreamUrl(route.base, "/v1/shots/$picture.png", null) else BridgeHttp.upstreamUrl(route.base, req.path, BridgeHttp.withoutBridgeParams(req.query)))
            ?: return reply.error(400, "bad_request", "Not a path on your Mac.")
        val json = BridgeHttp.JSON.toMediaType()
        // A body (every one Lens sends is a little JSON, sealed): read whole, checked against the
        // signature and opened before the Mac is even called. None for a GET or a call without one.
        val opened = if (method == "GET" || !req.hasBody) null else (sealedBody(input, req, reply) ?: return)
        // What the phone guards with a fingerprint never goes from the glasses (BridgeHttp.phoneOnly).
        if (picture == null && BridgeHttp.phoneOnly(method, req.path, opened)) return reply.error(403, "needs_phone", BridgeHttp.NEEDS_PHONE)
        val body = opened?.toRequestBody(json)
        val b = Request.Builder().url(url).method(method, body)
        for ((k, v) in BridgeHttp.upstreamHeaders(req, p.token, grant.version)) if (body != null || !k.equals("content-type", true)) b.addHeader(k, v)
        val call = clientFor(route.client).newCall(b.build())
        val underWay = calls.add(grant.id) {
            call.cancel()
            runCatching { c.close() }
        }
        try {
            // Revoked in the moment since its signature was checked: it never goes.
            if (!access.has(grant.id)) return reply.error(403, "bridge_access", "Allow Hypernova Lens in Hypernova on your phone first.")
            val res: Response = try {
                call.execute()
            } catch (e: IOException) {
                if (call.isCanceled()) return
                return macUnreachable(reply, route)
            }
            res.use { r ->
                // A preview Lens opened: its page and files may come through without a signature from now on.
                if (req.method == "POST" && req.path == "/v1/preview" && r.isSuccessful) {
                    runCatching { JSONObject(r.peekBody(64 * 1024).string()).optString("url") }.getOrNull()
                        ?.let(BridgeHttp::previewToken)?.let { previews.add(it, grant.id) }
                }
                relay(req, r, reply, preview = false)
            }
        } finally {
            calls.remove(underWay)
        }
    }

    /**
     * A link that is its own key (a Telescope picture, a preview Lens opened), to the paired Mac
     * alone and only to be looked at: no body, none of the phone's credentials, and for a preview
     * only its own cookie. A preview's stream counts under the grant that opened it, and ends with it.
     */
    private fun openProxy(c: Socket, req: HttpRequest, previewToken: String?, reply: Reply) {
        if (req.isUpgrade) return reply.error(404, "not_found", "Unknown endpoint.")
        val p = Core.pairing ?: return reply.error(503, "not_paired", "Hypernova on this phone isn’t paired with a Mac yet.")
        val route = ActiveRoutes.route(p.baseUrl)
        val url = BridgeHttp.upstreamUrl(route.base, req.path, BridgeHttp.withoutBridgeParams(req.query)) ?: return reply.error(400, "bad_request", "Not a path on your Mac.")
        val b = Request.Builder().url(url).method(req.method, null)
        for ((k, v) in BridgeHttp.openHeaders(req, previewToken != null)) b.addHeader(k, v)
        val call = clientFor(route.client).newCall(b.build())
        val grant = previewToken?.let(previews::grantOf)
        val underWay = grant?.let {
            calls.add(it) {
                call.cancel()
                runCatching { c.close() }
            }
        }
        try {
            // Its grant revoked in the moment since it was looked up: it never goes.
            if (previewToken != null && !previews.known(previewToken)) return reply.error(404, "not_found", "Unknown preview.")
            val res: Response = try {
                call.execute()
            } catch (e: IOException) {
                if (call.isCanceled()) return
                return macUnreachable(reply, route)
            }
            res.use { relay(req, it, reply, previewToken != null) }
        } finally {
            underWay?.let(calls::remove)
        }
    }

    /**
     * The Mac's answer back to Lens: its status (a 3xx too, never followed here), its headers less
     * the connection's, its body. To a signed call, sealed under the call's key as it streams
     * (BridgeSeal: the head first, the body in pieces, the end marker last; the outer answer says
     * only `sealed`), never held whole; for links that are their own key, as it comes.
     */
    private fun relay(req: HttpRequest, r: Response, reply: Reply, preview: Boolean) {
        val down = BridgeHttp.downstreamHeaders(r.headers.map { it.first to it.second }, preview)
        val body = if (req.method == "HEAD") null else r.body
        val stream = body?.byteStream()
        try {
            val sealer = reply.sealer
            if (sealer != null) {
                reply.out.write(BridgeHttp.head(200, reply.sealedHeaders(), null))
                val w = sealer.writer(reply.out)
                w.head(r.code, down)
                reply.out.flush()
                if (stream != null) {
                    val buf = ByteArray(BridgeSeal.CHUNK)
                    while (true) {
                        val n = stream.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        w.data(buf, 0, n)
                        reply.out.flush()
                    }
                }
                w.end()
            } else {
                val len = body?.contentLength()?.takeIf { it >= 0 } ?: if (body == null) 0L else null
                reply.out.write(BridgeHttp.head(r.code, reply.cors + down, len))
                stream?.let { copy(it, reply.out) }
            }
            reply.out.flush()
        } finally {
            runCatching { stream?.close() }
        }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = from.read(buf)
            if (n < 0) break
            to.write(buf, 0, n)
        }
    }

    // ─── Live feeds ─────────────────────────────────────────────────────────

    /** Every live feed, counted from before the Mac is asked (LiveFeeds). */
    private val feeds = LiveFeeds()

    /** Feeds are ended on a thread of their own: never the main one, and never one apiece. */
    private val ender = Executors.newSingleThreadExecutor { r -> Thread(r, "lens-bridge-end").apply { isDaemon = true } }

    private fun closeFeeds(grantId: String, code: Int, reason: String) {
        val those = feeds.of(grantId)
        if (those.isNotEmpty()) ender.execute { for (f in those) f.end(code, reason) }
    }

    /** `{t:"bridge.settings", settings}`: what Lens hears on its live feed when it opens and at each change. */
    private fun settingsMessage(s: GlassesSettings): ByteArray =
        JSONObject().put("t", "bridge.settings").put("settings", s.toJson()).toString().toByteArray()

    /** The latest settings to every Lens's live feed, from one thread; a feed whose writer is stuck is passed by (it gets them on reconnecting). */
    private val pusher = Latest<ByteArray>("lens-bridge-push") { msg -> for (f in feeds.on("/v1/ws")) f.offer(BridgeSeal.TEXT, msg, 1_000) }

    /** A setting changed: every Lens on its live feed hears it. */
    private fun push(s: GlassesSettings) = pusher.offer(settingsMessage(s))

    /**
     * A WebSocket from Lens (`/v1/ws`, `/v1/stt/ws`): the Mac's socket is opened first, with the
     * phone's credentials, and only once it's up does Lens's handshake get its answer, so a refusal
     * reaches Lens as a socket that never opened. Then messages go both ways, each sealed under
     * the socket's key (BridgeSeal: Lens's opened here and sent to the Mac as text or binary, the
     * Mac's sealed for Lens), the end as a sealed marker with the close code, until either side closes.
     */
    private fun proxySocket(c: Socket, raw: DeadlineInput, input: InputStream, out: OutputStream, req: HttpRequest, grant: BridgeAccess.Grant, p: Pairing, reply: Reply) {
        val key = req.header("sec-websocket-key") ?: return reply.error(400, "bad_upgrade", "Not a WebSocket request.")
        val route = ActiveRoutes.route(p.baseUrl)
        val url = BridgeHttp.upstreamUrl(route.base, req.path, BridgeHttp.withoutBridgeParams(req.query)) ?: return reply.error(400, "bad_request", "Not a path on your Mac.")
        val feed = LiveFeed(grant.id, req.path, out, reply.sealer) { runCatching { c.close() } }
        // Counted among the feeds before the Mac is even asked: a Disconnect while it answers (up to
        // 12 s) finds this one and ends it, rather than missing it and leaving it open for good.
        feeds += feed
        try {
            val opened = CountDownLatch(1)
            var refusedWith = 0
            var up = false
            val b = Request.Builder().url(url)
            for ((k, v) in BridgeHttp.upstreamHeaders(req, p.token, grant.version)) if (!k.equals("content-type", true)) b.addHeader(k, v)
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    up = true
                    opened.countDown()
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { feed.message(BridgeSeal.TEXT, text.toByteArray()) }.onFailure { webSocket.cancel() }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    runCatching { feed.message(BridgeSeal.BINARY, bytes.toByteArray()) }.onFailure { webSocket.cancel() }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, null)
                    // The Mac's codes mean something to Lens (4001: these glasses were forgotten), so they go
                    // through, sealed in the end marker; the app's own end (4003) may be racing this: one wins.
                    feed.finish(code, reason)
                    runCatching { c.close() }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!up) {
                        refusedWith = response?.code ?: 502
                        opened.countDown()
                        return
                    }
                    feed.finish(1011, "the Mac's side closed")
                    runCatching { c.close() }
                }
            }
            val ws = feedClientFor(route.client).newWebSocket(b.build(), listener)
            feed.upstream = ws
            if (!opened.await(12, TimeUnit.SECONDS) || !up) {
                ws.cancel()
                val status = when (refusedWith) { 401, 403 -> refusedWith; 0 -> 504; else -> 502 }
                return reply.error(status, if (status == 403) "glasses_removed" else "mac_unreachable", "Hypernova on your phone couldn’t open the live feed to your Mac.")
            }
            // The grant may have gone while the Mac answered (Disconnect, or the Mac forgot the glasses).
            if (!access.has(grant.id)) {
                ws.cancel()
                return reply.error(403, "bridge_access", "Allow Hypernova Lens in Hypernova on your phone first.")
            }
            // The live feed opens with the glasses' settings, so a Lens that reconnects has what changed meanwhile.
            val first = if (req.path == "/v1/ws") listOf(BridgeSeal.TEXT to settingsMessage(settings)) else emptyList()
            val head = BridgeHttp.head(101, listOf("Upgrade" to "websocket", "Sec-WebSocket-Accept" to WsFrames.accept(key)), null, connection = "Upgrade")
            // Ended in the moment since the check above: it never opens.
            if (!feed.open(head, first)) return ws.cancel()
            raw.perRead(0)
            main.post(::refresh)
            try {
                val whole = WsFrames.Assembler(FEED_MESSAGE)
                val key = reply.sealer!!.key
                var opened = 0L
                // How Lens ended it: a sealed end marker says so; a close without one (or the connection going) was cut.
                var endedWith = 1006
                while (true) {
                    val f = WsFrames.read(input, FEED_MESSAGE) ?: break
                    when (f.opcode) {
                        WsFrames.PING -> feed.frame(WsFrames.PONG, f.payload)
                        WsFrames.PONG -> Unit
                        WsFrames.CLOSE -> {
                            runCatching { feed.frame(WsFrames.CLOSE, WsFrames.closePayload(if (endedWith == 1006) 1000 else endedWith, "")) }
                            break
                        }
                        else -> {
                            val m = whole.add(f) ?: continue
                            // Every message from Lens is sealed (a binary frame); anything else isn't Lens's.
                            if (m.text) throw IOException("an unsealed message")
                            val o = BridgeSeal.openFrame(key, BridgeSeal.FROM_LENS, opened++, m.payload)
                            when (o.kind) {
                                BridgeSeal.TEXT -> ws.send(String(o.payload))
                                BridgeSeal.BINARY -> ws.send(o.payload.toByteString())
                                else -> {
                                    endedWith = WsFrames.closeCode(o.payload).takeIf { it in 1000..4999 && it != 1005 && it != 1006 } ?: 1000
                                    ws.close(endedWith, null)
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (BuildConfig.DEBUG) Log.d(TAG, "feed ${BridgeHttp.forLog(req)}: ${e.message}")
            } finally {
                ws.close(1000, null)
            }
        } finally {
            feeds -= feed
            main.post(::refresh)
        }
    }

    // ─── Where grants are kept ──────────────────────────────────────────────

    private class PrefsStore(private val prefs: android.content.SharedPreferences) : BridgeAccess.Store {
        override fun load(): List<BridgeAccess.Grant> {
            // "grants" held protocol-2 grants (signed with the same key, nothing sealed): dropped, never read; Lens asks again.
            if (prefs.contains("grants")) prefs.edit().remove("grants").apply()
            val a = runCatching { JSONArray(prefs.getString(GRANTS, "[]")) }.getOrNull() ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val hash = o.optString("hash").takeIf { it.length == 64 } ?: return@mapNotNull null
                BridgeAccess.Grant(id, hash, o.optString("name", "Hypernova Lens"), o.optString("version").takeIf { it.isNotEmpty() }, o.optLong("createdAt"), o.optLong("lastUsedAt"))
            }
        }

        override fun save(grants: List<BridgeAccess.Grant>) {
            val a = JSONArray()
            for (g in grants) a.put(JSONObject().put("id", g.id).put("hash", g.hash).put("name", g.name).put("version", g.version ?: "").put("createdAt", g.createdAt).put("lastUsedAt", g.lastUsedAt))
            prefs.edit().putString(GRANTS, a.toString()).apply()
        }

        private companion object {
            /** Grants of protocol 3 (sealed calls); the key is new with it (see [load]). */
            const val GRANTS = "grants3"
        }
    }
}
