package com.prudnikweb.hypernova.data

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.components.readableLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * The real hub: one paired Mac's daemon, over REST and one WebSocket.
 *
 * - Connects with `GET /v1/snapshot`, then keeps `/v1/ws` open. The socket pushes everything.
 * - Can't reach the server: tries again at once, then every 30 s, on screen or behind it (see
 *   [backoff], [Retry]); at once when the network changes
 *   (a VPN coming up too), the screen comes on or the app returns, unless a try that has just
 *   begun is still under way. Never without a network, never off screen without the background
 *   service, and never by waking the phone. [link] says honestly how it stands.
 * - A 401 anywhere means the Mac forgot this phone: [link] becomes Unpaired and retrying stops.
 *
 * All state is Compose snapshot state, written on the main thread only.
 */
class LiveHub(
    context: Context,
    private val scope: CoroutineScope,
    val pairing: Pairing,
    private val toast: (String) -> Unit,
    /** A new approval while the app isn't on screen (from the socket or a fresh snapshot). */
    private val onApproval: (WApproval, Session?) -> Unit,
    /** An approval is no longer pending: its notification can go. */
    private val onApprovalGone: (String) -> Unit = {},
    private val isVisible: () -> Boolean = { true },
    /** A session finished, waits in its terminal or failed while the app isn't on screen. */
    private val onNotice: (Notice) -> Unit = {},
    /** A rewind replaced this session (docs/PROTOCOL.md, "Rewind"): its notification can go. */
    private val onSessionReplaced: (String) -> Unit = {},
    /** The account needs nothing from the owner any more: its notification can go. */
    private val onAccountFine: () -> Unit = {},
    /** No update of this server's has failed (any more): its notification can go. */
    private val onServerUpdateFine: () -> Unit = {},
    /**
     * True while the background service holds the link open. Off screen without it ("Stay
     * connected" is off, or the process was started for a widget), a failed attempt waits for the
     * app to open instead of trying again: Android soon pauses a process like that anyway.
     */
    private val heldOpen: () -> Boolean = { true },
    /** Which paired computer this is (data/Servers.kt): its offline queue, certificate and notifications are its own. */
    val serverId: String = Servers.LEGACY_ID,
    /**
     * True while the app is on screen, whichever computer it shows: how soon a failed attempt goes
     * again. [isVisible] is whether it shows this one (banners, not notifications; approvals seen).
     */
    private val onScreen: () -> Boolean = isVisible,
    /** This is the computer the screens show (not one watched in the background): the app's words follow it. */
    private val isActive: () -> Boolean = { true },
    /** The wall clock (epoch ms). Tests pass their own, so the watchdog and the usage timers can be driven. */
    private val clock: () -> Long = System::currentTimeMillis,
) : Hub {
    private val app = context.applicationContext
    // Read each time, worded for this hub's own computer (ComputerWords.wordedFor): kept from when the
    // hub was made, they said "Mac" for a Linux server until the app restarted.
    private val res: android.content.res.Resources
        get() = ComputerWords.wordedFor(app.resources, macInfo?.platform ?: if (isActive()) ComputerWords.platform else Mac.DARWIN)
    private val api = Api(pairing.baseUrl, pairing.token)
    private val now get() = clock()
    /** This phone's device certificate for the Mac's local listener (data/DeviceCert.kt). */
    private val certs = DeviceCertStore(context, serverId)
    /**
     * The route to the Mac (data/LanRoute.kt): its own address on this network, or the base URL.
     * A change of route reconnects: the socket is bound to the old one.
     */
    private val router = LanRouter(context, scope, pairing, certs) { r ->
        // An attempt waiting on the route takes it as it comes; anything else reconnects over it.
        if (!closed && !paused && link.state != LinkState.Unpaired && choosing == null) {
            ConnLog.add(ConnKind.Link, ConnResult.Info, r.kind, ConnScrub.hostPort(r.base), "the route changed: reconnecting over it")
            attempt = 0
            connect()
        }
    }
    /** The attempt waiting on the router's round ([LanRouter.prepare]), if one is: it takes the route as it comes. */
    private var choosing: Any? = null
    /** The route the current attempt (and its socket) went by. */
    private var attemptRoute: RouteKind? = null
    /** Asked the Mac for a device certificate this connection (once; the next snapshot says if it took). */
    private var certAsked = false

    override var link by mutableStateOf(Link(LinkState.Connecting))
        private set
    private var macInfo by mutableStateOf<MacInfo?>(null)
    /** The Mac's battery as last heard (the snapshot, then `{t:"battery"}`); null without one. */
    private var macBattery by mutableStateOf<MacBattery?>(null)
    /** Stay awake as last heard (the snapshot, then `{t:"awake"}`); null from a Mac too old to say. */
    private var macAwake by mutableStateOf<MacAwake?>(null)
    /** Browse outside projects as last heard (the snapshot, then `{t:"files"}`); null from a Mac too old to have it. */
    private var macBrowse by mutableStateOf<Boolean?>(null)
    /** The server updating itself as last heard (the snapshot, then `{t:"update"}`); null from a daemon too old to. */
    private var macUpdate by mutableStateOf<ServerUpdate?>(null)
    /** Read aloud: the Mac's voice setting and this phone's own reads (SpeechLink.kt). */
    override val speech = PhoneSpeech(pairing, api, send = { ws?.send(it) == true }, labelOf = { id -> id?.let { i -> sessions.firstOrNull { it.id == i }?.project } })

    private val noticeFlow = MutableSharedFlow<Notice>(extraBufferCapacity = 16)
    override val notices: SharedFlow<Notice> = noticeFlow
    /** Automations (data/AutomationsLive.kt): the snapshot's, then the socket's; their notes as banners or notifications. */
    override val automations = LiveAutomations(context, api, isVisible, banner = { noticeFlow.tryEmit(it) }, fail = { e, r -> handle(e, r) }, sessionOf = { session(it) }, server = serverId)
    override val appUpdate: AppUpdate?
        get() {
            val m = macInfo ?: return null
            val code = m.appVersionCode ?: return null
            if (code <= BuildConfig.VERSION_CODE) return null
            return AppUpdate(m.appVersionName ?: "", code, pairing.baseUrl.trimEnd('/') + "/download/android", server = serverId)
        }
    override val mac: Mac
        get() = Mac(
            name = macInfo?.name ?: pairing.macName,
            online = link.state == LinkState.Connected,
            via = when (router.kind) {
                RouteKind.Local -> Mac.VIA_LOCAL
                RouteKind.Remote -> Mac.VIA_REMOTE
            },
            url = router.route.base,
            daemonVersion = macInfo?.daemonVersion,
            claudeVersion = macInfo?.claudeVersion,
            battery = macBattery,
            awake = macAwake,
            platform = macInfo?.platform ?: Mac.DARWIN,
            browseOutsideProjects = macBrowse,
            update = macUpdate,
            unavailable = macInfo?.unavailable.orEmpty(),
        )

    private var raw: Map<String, WSession> = emptyMap()
    override var sessions by mutableStateOf<List<Session>>(emptyList())
        private set
    private var approvals by mutableStateOf<Map<String, WApproval>>(emptyMap())
    /** Approvals waiting for an answer (the home screen widget shows the oldest). */
    val pendingApprovals: Collection<WApproval> get() = approvals.values
    private var resolved by mutableStateOf<Map<String, Resolved>>(emptyMap())
    /** What this phone answered, shown at once (before the Mac confirms). */
    private var answered by mutableStateOf<Map<String, Resolution>>(emptyMap())
    override var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    override val projectsRoot: String? get() = macInfo?.projectsRoot
    override var providers by mutableStateOf<List<Provider>>(emptyList())
        private set
    private var wusage: WUsage? = null
    override var usage by mutableStateOf(Usage(0f, 0f, now + 5 * HOUR, now + 7 * DAY, known = false))
        private set
    override var account by mutableStateOf(Account.Ok)
        private set
    /** The Mac has told this hub about the account at least once. */
    private var accountHeard = false
    /** Codex on the Mac (data/Codex.kt): null where it isn't installed, and from an older Mac. */
    override var codex by mutableStateOf<CodexState?>(null)
        private set
    /** Claude Code on the Mac (data/AgentLead.kt): null from an older Mac, where it always was. */
    override var claude by mutableStateOf<ClaudeState?>(null)
        private set

    // Per-session event lists, so a screen only observes its own transcript.
    private val events = HashMap<String, MutableState<List<WEvent>>>()
    /** Prompts this phone sent, shown until the Mac's copy of each arrives (see [Sent]). */
    private val pending = HashMap<String, MutableState<List<Sent>>>()
    /**
     * Per session, the Mac's events that already stood in for one of those copies. Each does so
     * once, also when a reload or a resent event brings it back.
     */
    private val confirmed = HashMap<String, MutableSet<String>>()
    /** When this phone last answered a session's terminal menu. */
    private val menuAnswered = HashMap<String, Long>()
    private val loading = mutableStateMapOf<String, Boolean>()
    /** How many of a session's last events the phone asks for (more with each "Show earlier"). */
    private val eventLimits = HashMap<String, Int>()
    /** The Mac has events before the ones loaded. */
    private val earlier = mutableStateMapOf<String, Boolean>()
    private val loadingMore = mutableStateMapOf<String, Boolean>()
    private val buffered = HashMap<String, MutableList<WEvent>>()
    /** Events the Mac took back while a session's history loaded: not in it once it lands. */
    private val goneWhileLoading = HashMap<String, MutableSet<String>>()
    private val loaded = HashSet<String>()
    private var openId: String? = null
    private val loadErrors = mutableStateMapOf<String, String>()
    /** Approvals already announced (or seen on screen), so each notifies once. */
    private val seenApprovals = HashSet<String>()
    /** True once a snapshot has loaded: approvals not in it aren't pending. */
    private var settled by mutableStateOf(false)
    /** Sessions and usage are the Mac's (a snapshot has loaded), not empty placeholders. */
    val synced: Boolean get() = settled
    private val itemCaches = HashMap<String, Items.Cache>()
    /** Prompts typed while the Mac was away (data/OfflineQueue.kt), kept across restarts. */
    private val offline = OfflineQueue(PrefsQueueStore(app.getSharedPreferences("hypernova", Context.MODE_PRIVATE), Servers.scoped("offlineQueue", serverId)))
    private var flushing = false
    private var knownIds: Set<String>? = null
    private val revealed = HashSet<String>()
    private var seq = 0L

    /** Per session, its agents and workflows (docs/PROTOCOL.md, "Agents and workflows"). */
    private val boards = HashMap<String, MutableState<AgentBoard>>()
    /** Agents' approvals waiting out [AGENT_ASK_DELAY] before they notify. */
    private val held = HashSet<String>()

    private fun slot(id: String) = events.getOrPut(id) { mutableStateOf(emptyList()) }
    private fun boardSlot(id: String) = boards.getOrPut(id) { mutableStateOf(AgentBoard.Empty) }
    private fun pendingSlot(id: String) = pending.getOrPut(id) { mutableStateOf(emptyList()) }

    // ─── Connection ────────────────────────────────────────────────────────

    private var ws: WebSocket? = null
    private var loop: Job? = null
    private var watchdog: Job? = null
    private var usageTimer: Job? = null
    private var attempt = 0
    private var everConnected = false
    private var lastMessageAt = 0L
    /** When the Mac was last heard from (epoch ms, 0 if never): "Last updated" on the widget. */
    val lastHeardAt: Long get() = lastMessageAt
    private var closed = false
    /** Disconnected from the notification: no socket until the app comes back. */
    private var paused = false
    /** A failed attempt's next one is scheduled ([schedule]); nothing is in flight. */
    private var waiting = false
    /** When the last attempt started ([SystemClock.elapsedRealtime]). */
    private var triedAt = 0L

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val alarms = app.getSystemService(AlarmManager::class.java)

    /**
     * Hears a network worth trying again on: a new default network, the same one becoming another
     * kind or reaching the internet, or a VPN coming up or moving to another network under it. A
     * VPN that carries only the home network's addresses needn't become the default network: it
     * has a watch of its own. Signal strength and speed estimates change all
     * the time and aren't news, hence the key. A watch's callbacks come one at a time on one
     * thread, which is the only one reading its key.
     */
    private inner class NetWatch : ConnectivityManager.NetworkCallback() {
        private var key: List<Any>? = null
        private var blocked = false

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val k = listOf(
                network,
                TRANSPORTS.filter(caps::hasTransport),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            )
            if (k == key) return
            key = k
            scope.launch { networkChanged() }
        }

        // Data Saver or a battery restriction let the app back onto the network.
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            val freed = this.blocked && !blocked
            this.blocked = blocked
            if (freed) scope.launch { networkChanged() }
        }
    }
    private val defaultNet = NetWatch()
    private val vpnNet = NetWatch()

    /**
     * The screen came on: the phone is awake anyway, and its owner may be about to look. Not within
     * [RETRY_CAP_ON_SCREEN] of the last attempt, so a screen switched on and off again and again
     * doesn't ask again and again.
     */
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            scope.launch {
                if (waiting && SystemClock.elapsedRealtime() - triedAt >= RETRY_CAP_ON_SCREEN) connect("the screen came on")
            }
        }
    }

    /** The scheduled attempt's alarm (see [schedule]), on the main thread. */
    private val alarm = AlarmManager.OnAlarmListener { if (waiting) connect("the next try") }

    fun start() {
        runCatching { connectivity?.registerDefaultNetworkCallback(defaultNet) }
        runCatching {
            val vpn = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity?.registerNetworkCallback(vpn, vpnNet)
        }
        runCatching { ContextCompat.registerReceiver(app, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED) }
        ConnLog.add(ConnKind.Link, ConnResult.Info, text = "starting the link to ${pairing.macName}")
        router.start()
        connect("start")
    }

    override fun close() {
        closed = true
        // Another Mac, or none: what this one was reading stops.
        if (Speaker.now != null) Speaker.stop("closed")
        unschedule()
        loop?.cancel(); watchdog?.cancel(); usageTimer?.cancel()
        ws?.cancel(); ws = null
        unwatch()
    }

    /** Stops hearing the network and the screen: this hub won't connect again. */
    private fun unwatch() {
        router.close()
        runCatching { connectivity?.unregisterNetworkCallback(defaultNet) }
        runCatching { connectivity?.unregisterNetworkCallback(vpnNet) }
        runCatching { app.unregisterReceiver(screenOn) }
    }

    override fun retry() {
        if (closed || paused || link.state == LinkState.Unpaired || link.state == LinkState.Connected) return
        connect("Retry now")
    }

    override fun foreground(visible: Boolean) {
        if (!visible) return
        // A window may have reset while the app was away: show it fresh at once.
        recomputeUsage()
        if (paused) { paused = false; attempt = 0; connect("the app is back"); return }
        when {
            link.state == LinkState.Offline || link.state == LinkState.Reconnecting -> { attempt = 0; if (!freshTry()) connect("the app is back") }
            // Back from the background: a socket that has been silent for 30 s (pings come every
            // 25 s) is probably dead even if nobody noticed yet. Reconnect now, not in 70 s.
            link.state == LinkState.Connected && now - lastMessageAt > 30_000 -> { attempt = 0; connect("the app is back and the socket was silent") }
        }
        // On screen, every pending approval counts as seen: no notification for it later. Only this
        // computer's, when the screens show it: one watched in the background still notifies.
        if (isVisible()) seenApprovals.addAll(approvals.keys)
    }

    /** "Disconnect" from the background notification: close the socket until the app opens again. */
    fun pause() {
        if (closed || link.state == LinkState.Unpaired) return
        ConnLog.add(ConnKind.Link, ConnResult.Info, text = "disconnected from the notification until the app opens")
        paused = true
        unschedule()
        loop?.cancel(); watchdog?.cancel()
        ws?.cancel(); ws = null
        link = Link(LinkState.Offline, link.problem)
    }

    /**
     * Whatever failed before may work on this network: try at once, the backoff from the start (a
     * VPN that has just come up can take a few seconds to find the Mac). A few small requests per
     * change, next to all the phone does on one anyway. One switch between wifi and mobile data is
     * several changes (a VPN's network follows the one under it, then each is validated), so a try that has only just begun is left to finish; should it fail, the next
     * comes after a second.
     */
    private fun networkChanged() {
        if (closed || paused || !(onScreen() || heldOpen())) return
        if (link.state != LinkState.Offline && link.state != LinkState.Reconnecting) return
        attempt = 0
        if (!freshTry()) connect("the network changed")
    }

    /**
     * A try started under [FRESH_TRY] ago is still under way (asking for the snapshot or opening
     * the socket): starting another now would only throw it away. The screen coming on and the
     * app opening come a moment apart, and so do a network's changes.
     */
    private fun freshTry(): Boolean =
        !waiting && link.state != LinkState.Connected && (loop?.isActive == true || ws != null) &&
            SystemClock.elapsedRealtime() - triedAt < FRESH_TRY

    private fun hasNetwork(): Boolean {
        val cm = connectivity ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * One attempt: the route first (a probe round, Local first, unless one has just settled:
     * [LanRouter.prepare]), then the snapshot and the socket over it. Nothing answering at all
     * fails at once with what was tried, without asking the base URL again.
     */
    private fun connect(why: String = "connecting") {
        if (closed || paused || link.state == LinkState.Unpaired) return
        unschedule()
        loop?.cancel()
        ws?.cancel(); ws = null
        triedAt = SystemClock.elapsedRealtime()
        // Offline stays Offline while a retry is in flight ("trying again now"), so the card
        // doesn't flicker away and back.
        link = if (link.state == LinkState.Offline) link.copy(retrying = true, retryAt = null)
        else Link(if (everConnected) LinkState.Reconnecting else LinkState.Connecting, link.problem, tried = link.tried, failedOver = link.failedOver)
        attemptRoute = null
        loop = scope.launch {
            try {
                val mine = Any()
                choosing = mine
                val report = try { router.prepare(why) } finally { if (choosing === mine) choosing = null }
                if (report.winner == null) throw ApiException(Problem.Unreachable, code = NO_ROUTE)
                val route = router.route
                attemptRoute = route.kind
                ConnLog.add(ConnKind.Link, ConnResult.Info, route.kind, ConnScrub.hostPort(route.base), "$why: asking for the snapshot")
                val snap = api.get("/v1/snapshot")
                val parsed = withContext(Dispatchers.Default) { Wire.snapshot(snap) }
                applySnapshot(parsed)
                openSocket()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                fail(Net.classify(e))
            }
        }
    }

    private fun fail(error: ApiException) {
        if (closed || paused) return
        var e = error
        val over = attemptRoute
        val noRoute = e.code == NO_ROUTE
        // The log: why the link went, over which route (never a token or a key; ConnLog scrubs).
        if (!noRoute) ConnLog.add(ConnKind.Link, linkResult(e), over, ConnScrub.hostPort(router.route.base), linkWords(e))
        if (e.problem == Problem.Unauthorized) {
            // The Mac's local listener wants this phone's device certificate (none yet, or the Mac
            // lost it): ask for one over that route (the token's way to a first certificate), then
            // connect again with it. Asked already and still refused: Local is held back a while
            // and the next try goes another way; the token itself is fine. Any other 401 is the
            // Mac having forgotten this phone.
            if (e.code == "device_cert_required") {
                if (over == RouteKind.Local && !certAsked) { obtainCert(); return }
                router.hold(RouteKind.Local, CERT_HOLD, "the Mac still wants a device certificate")
                e = ApiException(Problem.Unreachable, e.status, e.code, cause = e)
            } else { unpaired(); return }
        }
        // The route in use failed (the Mac moved, the Wi-Fi or the VPN went): it
        // falls back, and the next try runs a round, Local first. A route that keeps failing is
        // tried after the others for a while: that route only.
        if (!noRoute && over != null) router.failed(over)
        certAsked = false
        ws?.cancel(); ws = null
        watchdog?.cancel()
        unschedule()
        loop?.cancel()
        attempt++
        val online = hasNetwork()
        val problem = if (online) e.problem else Problem.NoNetwork
        // No network at all: nothing to try until one comes ([NetWatch]). Off screen without the
        // background service ([heldOpen]): the app tries again when it opens.
        if (!online || !(onScreen() || heldOpen())) {
            link = Link(LinkState.Offline, problem, tried = router.report?.takeIf { noRoute }, failedOver = over.takeIf { !noRoute })
            ConnLog.add(ConnKind.Wait, ConnResult.Info, text = if (!online) "no network: waiting for one" else "off screen without the background connection: trying again when the app opens")
            return
        }
        val wait = backoff()
        val state = if (everConnected && attempt < 3) LinkState.Reconnecting else LinkState.Offline
        link = Link(state, problem, retryAt = now + wait, tried = router.report?.takeIf { noRoute }, failedOver = over.takeIf { !noRoute })
        ConnLog.add(ConnKind.Wait, ConnResult.Info, text = "next try in ${(wait + 500) / 1000}s (attempt $attempt)")
        schedule(wait)
    }

    /** A failure's verdict for the log. */
    private fun linkResult(e: ApiException): ConnResult = when (e.problem) {
        Problem.NotRunning -> ConnResult.Refused
        Problem.NoName -> ConnResult.NoName
        Problem.Tls -> ConnResult.Tls
        Problem.Server, Problem.Unauthorized -> ConnResult.Http
        else -> e.cause?.let { RoutePolicy.classify(it).first } ?: ConnResult.Failed
    }

    /** A failure in a few words for the log: the problem, the status and the daemon's code. */
    private fun linkWords(e: ApiException): String = listOfNotNull(
        "failed: ${e.problem.name}",
        e.status.takeIf { it > 0 }?.let { "HTTP $it" },
        e.code,
        e.cause?.let { RoutePolicy.classify(it).second },
    ).joinToString(", ")

    /** The wait after the [attempt]th failure in a row ([Retry]: at once, then every 30 s). */
    private fun backoff(): Long = Retry.wait(attempt, Random.nextDouble(0.9, 1.0))

    /**
     * The next attempt, [wait] ms from now, by two clocks. The coroutine's stops while the phone
     * sleeps, so in a pocket it could take hours to get there. The alarm counts real time but
     * doesn't wake the phone either: it goes off with the next thing that does (another app's
     * alarm, a message, the screen). Neither holds a wake lock.
     */
    private fun schedule(wait: Long) {
        waiting = true
        loop = scope.launch {
            delay(wait)
            connect("the next try")
        }
        runCatching { alarms?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + wait, ALARM_TAG, alarm, null) }
    }

    /** Drops the scheduled attempt's alarm; its coroutine goes with [loop]. */
    private fun unschedule() {
        if (!waiting) return
        waiting = false
        runCatching { alarms?.cancel(alarm) }
    }

    /** The Mac forgot this phone: nothing is tried again until it's paired anew (a new hub). */
    private fun unpaired() {
        link = Link(LinkState.Unpaired, Problem.Unauthorized)
        unschedule()
        loop?.cancel(); watchdog?.cancel()
        ws?.cancel(); ws = null
        unwatch()
    }

    private fun openSocket() {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    if (ws !== webSocket) return@launch
                    attempt = 0
                    everConnected = true
                    lastMessageAt = now
                    link = Link(LinkState.Connected)
                    ConnLog.add(ConnKind.Socket, ConnResult.Ok, attemptRoute, ConnScrub.hostPort(router.route.base), "the live socket is open")
                    // Prompts typed while the Mac was away go now, in order.
                    flushOffline()
                    startWatchdog(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Parse on OkHttp's thread; apply on the main thread.
                val msg = runCatching { parse(JSONObject(text)) }.getOrNull()
                scope.launch {
                    if (ws !== webSocket) return@launch
                    lastMessageAt = now
                    if (msg != null) handle(webSocket, msg)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    if (ws !== webSocket) return@launch
                    ConnLog.add(ConnKind.Socket, ConnResult.Closed, attemptRoute, text = "the Mac closed the socket: $code${reason.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}")
                    fail(ApiException(Problem.Unreachable))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                scope.launch {
                    if (ws !== webSocket) return@launch
                    val (result, detail) = RoutePolicy.classify(t)
                    ConnLog.add(ConnKind.Socket, if (status != null) ConnResult.Http else result, attemptRoute, text = listOfNotNull("the socket failed", status?.let { "HTTP $it" }, detail).joinToString(": "))
                    if (status == 401) unpaired() else fail(Net.classify(t))
                }
            }
        }
        ws = api.socket("/v1/ws", listener)
    }

    /** The daemon pings every 25 s; if nothing arrives for 70 s the socket is dead. */
    private fun startWatchdog(socket: WebSocket) {
        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(10_000)
                if (ws === socket && now - lastMessageAt > 70_000) {
                    ConnLog.add(ConnKind.Socket, ConnResult.Timeout, attemptRoute, text = "nothing from the Mac for 70s: the socket is dead")
                    socket.cancel()
                    fail(ApiException(Problem.Unreachable))
                    return@launch
                }
            }
        }
    }

    // ─── Messages ──────────────────────────────────────────────────────────

    private sealed interface Msg {
        class Snap(val s: Snapshot, val speech: JSONObject? = null) : Msg
        class SessionMsg(val s: WSession) : Msg
        /** [replacedBy]: a rewind's copy took its place (docs/PROTOCOL.md, "Rewind"). */
        class Removed(val id: String, val replacedBy: String? = null) : Msg
        /** A `claude agents` job died (data/JobDied.kt): said once, as a session stopping on an error is. */
        class Died(val d: JobDied) : Msg
        class Event(val sessionId: String, val e: WEvent) : Msg
        /** An event the Mac took back (a notice that no longer holds). */
        class EventRemoved(val sessionId: String, val id: String) : Msg
        class Approval(val a: WApproval) : Msg
        class ApprovalResolved(val id: String, val sessionId: String?, val decision: String, val by: String?) : Msg
        class UsageMsg(val u: WUsage?) : Msg
        /** [superseded]: a workflow step's earlier attempt (the step ran again as a new agent). */
        class AgentMsg(val a: WAgent, val superseded: Boolean) : Msg
        class WorkflowMsg(val w: WWorkflow) : Msg
        class Projects(val p: List<Project>) : Msg
        class Providers(val p: List<Provider>) : Msg
        class AccountMsg(val a: Account) : Msg
        class BatteryMsg(val b: MacBattery?) : Msg
        class AwakeMsg(val a: MacAwake?) : Msg
        /** Browse outside projects turned on or off (the sessions it changes come as their own messages). */
        class FilesMsg(val browseOutsideProjects: Boolean) : Msg
        /** The server updating itself: the setting, waiting, installing (data/ServerUpdate.kt). */
        class UpdateMsg(val u: ServerUpdate) : Msg
        class SleepSoonMsg(val s: SleepSoon) : Msg
        class BackpackEndingMsg(val s: BackpackEnding) : Msg
        /** Read aloud: the setting, a stop (SpeechLink.kt). */
        class SpeechMsg(val o: JSONObject) : Msg
        /** An automation, a run, a note, the counts (data/Automations.kt). */
        class AutoMsgs(val m: AutoMsg) : Msg
        /** The Mac's local listener: its addresses changed (null: the listener is off). */
        class LanMsg(val lan: LanInfo?) : Msg
        /** Codex turned on or off, connected or not, its account or its windows (null: not installed). */
        class CodexMsg(val c: CodexState?) : Msg
        /** Claude Code as the Mac has it: sessions in `claude agents` turned on or off. */
        class ClaudeMsg(val c: ClaudeState) : Msg
        /** The app the Mac hands out changed (a release fetched, a build put there, none any more): `app` null for none. */
        class AppMsg(val app: JSONObject?) : Msg
        data object Ping : Msg
    }

    private fun parse(o: JSONObject): Msg? = when (o.str("t")) {
        "snapshot" -> o.obj("snapshot")?.let { Msg.Snap(Wire.snapshot(it), it.obj("speech")) }
        "session" -> o.obj("session")?.let { Msg.SessionMsg(Wire.session(it)) }
        "session.removed" -> o.str("id")?.let { Msg.Removed(it, o.str("replacedBy")?.takeIf { to -> to.isNotBlank() }) }
        "job.died" -> JobDied.parse(o)?.let { Msg.Died(it) }
        "event" -> {
            val sid = o.str("sessionId")
            val ev = o.obj("event")
            if (sid != null && ev != null) Msg.Event(sid, Wire.event(ev)) else null
        }
        "event.removed" -> removedEvent(o)?.let { (sid, id) -> Msg.EventRemoved(sid, id) }
        "approval" -> o.obj("approval")?.let { Msg.Approval(Wire.approval(it)) }
        "approval.resolved" -> o.str("id")?.let { Msg.ApprovalResolved(it, o.str("sessionId"), o.str("decision") ?: "deny", o.str("by")) }
        "usage" -> Msg.UsageMsg(Wire.usage(o.obj("usage")))
        "agent" -> o.obj("agent")?.let { j -> Wire.agent(j)?.let { Msg.AgentMsg(it, j.bool("superseded")) } }
        "workflow" -> o.obj("workflow")?.let(Wire::workflow)?.let { Msg.WorkflowMsg(it) }
        "projects" -> Msg.Projects(o.arr("projects").mapObjects(Wire::project))
        "providers" -> Msg.Providers(o.arr("providers").mapObjects(Wire::provider))
        "account" -> o.obj("account")?.let { Msg.AccountMsg(Wire.account(it)) }
        "codex" -> Msg.CodexMsg(runCatching { CodexWire.state(o.obj("codex")) }.getOrNull())
        "claude" -> ClaudeState.parse(o.obj("claude"))?.let { Msg.ClaudeMsg(it) }
        "battery" -> Msg.BatteryMsg(Wire.battery(o.obj("battery")))
        "awake" -> Msg.AwakeMsg(MacAwake.parse(o.obj("awake")))
        "files" -> Msg.FilesMsg(o.obj("files")?.bool("browseOutsideProjects") == true)
        "update" -> ServerUpdate.parse(o.obj("update"))?.let { Msg.UpdateMsg(it) }
        "mac_sleep_soon" -> SleepSoon.parse(o)?.let { Msg.SleepSoonMsg(it) }
        "mac_backpack_ending" -> BackpackEnding.parse(o)?.let { Msg.BackpackEndingMsg(it) }
        "speech", "speech.stop" -> Msg.SpeechMsg(o)
        "automation", "automation.removed", "automations", "run", "note" -> AutomationWire.message(o)?.let { Msg.AutoMsgs(it) }
        "ping" -> Msg.Ping
        "lan" -> Msg.LanMsg(LanInfo.parse(o.obj("lan")))
        "app" -> Msg.AppMsg(o.obj("app"))
        else -> null
    }

    private fun handle(socket: WebSocket, m: Msg) {
        when (m) {
            is Msg.Snap -> {
                applySnapshot(m.s)
                speech.snapshot(m.speech)
                // A resync: the open session may have missed events while we were away.
                openId?.let { load(it) }
            }
            is Msg.SessionMsg -> {
                val old = raw[m.s.id]
                raw = raw + (m.s.id to m.s)
                publish()
                if (old != null) changed(old, m.s)
                if (old != null) heldLeft(old, m.s)
            }
            is Msg.Removed -> {
                // First: a screen on it goes to what replaced it the moment the session goes.
                m.replacedBy?.let { replaced(m.id, it) }
                raw = raw - m.id
                publish()
            }
            is Msg.Died -> {
                val n = m.d.notice(session(m.d.sessionId)?.name ?: raw[m.d.sessionId]?.title ?: res.getString(R.string.untitled_session), res.getString(R.string.notice_job_died))
                if (noticed.add(n.id)) notice(n)
            }
            is Msg.Event -> appendEvent(m.sessionId, m.e)
            is Msg.EventRemoved -> dropEvent(m.sessionId, m.id)
            is Msg.Approval -> {
                approvals = approvals + (m.a.id to m.a)
                publish()
                announce(listOf(m.a), fresh = true)
            }
            // An earlier attempt no longer counts: off the board, as the Mac's own list leaves it out.
            is Msg.AgentMsg -> boardSlot(m.a.sessionId).let { it.value = if (m.superseded) it.value.without(m.a.id) else it.value.with(m.a) }
            is Msg.WorkflowMsg -> boardSlot(m.w.sessionId).let { it.value = it.value.with(m.w) }
            is Msg.ApprovalResolved -> {
                approvals = approvals - m.id
                resolved = resolved + (m.id to Resolved(m.decision, m.by))
                publish()
                onApprovalGone(m.id)
            }
            is Msg.UsageMsg -> { wusage = m.u; recomputeUsage() }
            is Msg.Projects -> projects = sortProjects(m.p)
            is Msg.Providers -> {
                val before = providers
                providers = sortProviders(m.p)
                // New keys or models: their lists are asked for again.
                if (before.map { it.id to it.models } != providers.map { it.id to it.models }) modelCache = null
                publish()
                providerNotices(before)
            }
            is Msg.AccountMsg -> setAccount(m.a, live = true)
            is Msg.CodexMsg -> codexChanged(m.c)
            is Msg.ClaudeMsg -> claude = m.c
            is Msg.BatteryMsg -> setBattery(m.b)
            is Msg.AwakeMsg -> macAwake = m.a
            is Msg.FilesMsg -> macBrowse = m.browseOutsideProjects
            is Msg.UpdateMsg -> setServerUpdate(m.u)
            // A newer app now on the Mac, without reconnecting: the banner (App.kt) and Settings follow
            // [appUpdate]; in the background, the notification once per version.
            is Msg.AppMsg -> macInfo?.let { info ->
                macInfo = Wire.withApp(info, m.app)
                offerUpdate()
            }
            is Msg.SleepSoonMsg -> sleepSoon(m.s)
            is Msg.BackpackEndingMsg -> backpackEnding(m.s)
            is Msg.SpeechMsg -> speech.message(m.o)
            is Msg.AutoMsgs -> automations.apply(m.m)
            // Addresses only: the message says nothing about the certificate.
            is Msg.LanMsg -> router.lan = m.lan?.copy(deviceCert = router.lan?.deviceCert ?: true)
            Msg.Ping -> socket.send("""{"t":"pong"}""")
        }
    }

    /**
     * The Mac's local listener, from the snapshot or `{t:"lan"}`: the router gets the addresses,
     * and a Mac that holds no device certificate for this phone is asked for one (right after
     * pairing, or after it was lost on either side). The router then tries again with it.
     */
    private fun setLan(lan: LanInfo?) {
        router.lan = lan
        if (lan != null && !lan.deviceCert) obtainCert()
    }

    /** `POST /v1/device/cert` once per connection; the router then uses the certificate. */
    private fun obtainCert(): Boolean {
        val deviceId = pairing.deviceId ?: return false
        if (certAsked) return false
        certAsked = true
        scope.launch {
            try {
                certs.request(api, deviceId)
                ConnLog.add(ConnKind.Link, ConnResult.Ok, RouteKind.Local, text = "the Mac issued a device certificate")
                router.identityChanged()
                if (link.state != LinkState.Connected) connect("with the new device certificate")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The Mac doesn't know this token (it forgot this phone): unpaired, and no asking again.
                if (Net.classify(e).problem == Problem.Unauthorized) { if (!closed) unpaired(); return@launch }
                // Next connection asks again; meanwhile the remote route is the one to use.
                if (BuildConfig.DEBUG) android.util.Log.w("Hypernova", "device cert: ${e.message}")
                // Not linked (the local route wanted the certificate first): another route instead.
                if (link.state != LinkState.Connected) {
                    router.hold(RouteKind.Local, CERT_HOLD, "no device certificate from the Mac")
                    connect("trying without Local")
                }
            }
        }
        return true
    }

    /**
     * Approvals not announced before: a system notification, or a banner while the app is open. One
     * from an agent waits [AGENT_ASK_DELAY] first and says nothing if it's settled by then (agents
     * often settle their own, or ask again differently); the main agent's say so at once. Either
     * way its card is in the session at once. [fresh]: it just arrived; else (a snapshot) it has
     * waited since it was made.
     */
    private fun announce(list: Collection<WApproval>, fresh: Boolean = false) {
        for (a in list) {
            if (a.id in seenApprovals) continue
            if (a.agentId != null) {
                val waited = if (fresh) 0L else (now - a.createdAt).coerceIn(0L, AGENT_ASK_DELAY)
                if (waited < AGENT_ASK_DELAY) {
                    hold(a.id, AGENT_ASK_DELAY - waited)
                    continue
                }
            }
            notifyApproval(a)
        }
    }

    private fun hold(id: String, wait: Long) {
        if (!held.add(id)) return
        scope.launch {
            delay(wait)
            held.remove(id)
            // Answered meanwhile (here, on the Mac, or by the agent's own run ending): nothing to say.
            val a = approvals[id] ?: return@launch
            if (id in answered || id in resolved) return@launch
            notifyApproval(a)
        }
    }

    private fun notifyApproval(a: WApproval) {
        if (!seenApprovals.add(a.id)) return
        if (!isVisible()) { onApproval(a, session(a.sessionId)); return }
        val kind = when (a.kind) { "question" -> Notice.Kind.Question; "plan" -> Notice.Kind.Plan; else -> Notice.Kind.Approval }
        val text = a.agentLabel?.let { res.getString(R.string.notice_agent_asks, it, a.summary) } ?: a.summary
        noticeFlow.tryEmit(Notice("a:${a.id}", kind, a.sessionId, session(a.sessionId)?.name ?: a.summary, text))
    }

    /** A banner while the app is open, a system notification while it isn't. */
    private fun notice(n: Notice) {
        if (isVisible()) noticeFlow.tryEmit(n) else onNotice(n)
    }

    private val noticed = HashSet<String>()

    /**
     * A session moved on: it finished its turn, is waiting on something only its terminal shows,
     * or failed. Finishing and waiting are checked again a moment later, so a turn that carries
     * on (or an approval arriving with the wait) doesn't notify. A turn that ends with agents it
     * started still at work hasn't finished the job: the last agent's report starts a turn of its
     * own, and that one's end says so, once. A session a limit or a sign-out stopped says nothing
     * of its own: the account's one notice covers every session it stopped (see [setAccount]).
     */
    private fun changed(old: WSession, new: WSession) {
        if (old.state == new.state) return
        val id = new.id
        fun later(kind: Notice.Kind, still: (WSession) -> Boolean) = scope.launch {
            delay(1500)
            val s = raw[id] ?: return@launch
            if (!still(s) || !noticed.add("$kind:$id:${s.lastActivityAt}")) return@launch
            val name = session(id)?.name ?: s.title
            val text = when (kind) {
                Notice.Kind.Finished -> s.lastText?.let(::readableLine) ?: res.getString(R.string.notice_finished)
                // A question in words: the question itself, not "waiting in the terminal".
                else -> s.question ?: s.menu?.title ?: res.getString(R.string.notice_waiting)
            }
            notice(Notice("$kind:$id:${s.lastActivityAt}", kind, id, name, text))
        }
        when (new.state) {
            "idle" -> if (old.state == "working") later(Notice.Kind.Finished) { it.state == "idle" && it.agentsRunning == 0 && it.blocked == null }
            "needs_you" -> later(Notice.Kind.Waiting) { s -> s.state == "needs_you" && approvals.values.none { it.sessionId == id } }
            "error" -> if (new.blocked == null) notice(Notice("error:$id:${new.lastActivityAt}", Notice.Kind.Error, id, session(id)?.name ?: new.title, new.lastText ?: res.getString(R.string.activity_error)))
        }
    }

    /**
     * A newer app on the Mac while Hypernova is in the background: a notification, once per
     * version. (On screen, the app shows a banner from [appUpdate] itself: see [updateNoticed].)
     * With Updates off for this server the version stays unsaid, not used up: the app's banner still
     * has it, and so does another server whose Updates are on.
     */
    private fun offerUpdate() {
        val u = appUpdate ?: return
        if (isVisible() || !NotificationPrefsStore.get(app).allows(serverId, Notice.Kind.Update) || !updateNoticed(app, u.versionCode)) return
        onNotice(updateNotice(app, u))
    }

    /**
     * The server's own update as last heard. One that failed is news nobody else would bring (owner,
     * 2026-11-01): a banner or a notification once per release, and once more when the server gives
     * up on it; the row in Settings says it for as long as it holds.
     */
    private fun setServerUpdate(u: ServerUpdate?) {
        macUpdate = u
        val f = u?.failure
        if (f == null) {
            if (u?.state == ServerUpdate.IDLE) onServerUpdateFine()
            return
        }
        if (serverUpdateFailNoticed(app, serverId, f)) notice(serverUpdateFailedNotice(res, macInfo?.name ?: pairing.macName, f))
    }

    /**
     * Approvals a snapshot no longer has, their sessions still needing the owner: the daemon restarted
     * and each ask went back to Claude Code's own dialog (docs/DECISIONS.md, "Servers update without
     * waiting"). The approval's notification has just gone, and the dialog's card comes with no
     * change of state to announce it, so it's said once more.
     */
    private fun askedAgain(was: Collection<WApproval>) {
        for (id in was.filter { it.id !in approvals }.map { it.sessionId }.toSet()) {
            val s = raw[id] ?: continue
            if (s.state != "needs_you" || approvals.values.any { it.sessionId == id }) continue
            val kind = Notice.Kind.Waiting
            if (!noticed.add("$kind:$id:${s.lastActivityAt}")) continue
            notice(Notice("$kind:$id:${s.lastActivityAt}", kind, id, session(id)?.name ?: s.title, s.question ?: s.menu?.title ?: res.getString(R.string.notice_waiting)))
        }
    }

    private fun applySnapshot(s: Snapshot) {
        // A server that updated itself (or was updated) since the last snapshot: a line in the connection log.
        ServerUpdate.versionChange(macInfo?.daemonVersion, s.mac.daemonVersion)?.let { ConnLog.add(ConnKind.Mac, ConnResult.Info, text = it) }
        macInfo = s.mac
        // A Linux server's words instead of a Mac's, on every screen (data/ComputerWords.kt): the
        // active computer's (Core says the next one's when the switcher changes it).
        if (isActive()) ComputerWords.set(app, s.mac.platform)
        setBattery(s.mac.battery)
        macAwake = s.mac.awake
        macBrowse = s.mac.browseOutsideProjects
        setServerUpdate(s.mac.update)
        raw = s.sessions.associateBy { it.id }
        // A rewind on another phone while this one was away (docs/PROTOCOL.md, "Rewind").
        for ((old, to) in s.replaced) replaced(old, to)
        val before = approvals
        approvals = s.approvals.associateBy { it.id }
        for (gone in before.keys - approvals.keys) onApprovalGone(gone)
        settled = true
        projects = sortProjects(s.projects)
        providers = sortProviders(s.providers)
        wusage = s.usage
        if (knownIds == null) knownIds = raw.keys.toSet()
        // Other sessions reload their history next time they open.
        loaded.retainAll(setOfNotNull(openId))
        // The agents first: whether Claude Code's account is worth a word depends on them.
        codexChanged(s.codex)
        claude = s.claude
        // An older daemon doesn't report the account: as far as this phone knows, it's fine.
        setAccount(s.account ?: Account.Ok, live = false, publish = false)
        publish()
        recomputeUsage()
        // Approvals created while we were away arrive here, not as {t:"approval"}.
        announce(s.approvals)
        askedAgain(before.values)
        offerUpdate()
        // After the sessions, so a note found on reconnect can open its run's session.
        automations.applySnapshot(s.automations)
        setLan(s.lan)
    }

    /**
     * The account changed: sessions' lines and the sun follow it, and a new episode (a limit, a
     * sign-out) is announced once, however many sessions it stopped. A plain limit is a banner
     * only: in the background the permanent notification already leads with it. What needs the
     * owner is a notification too, and it goes once nothing needs the owner any more. One already
     * on screen when the app opens ([live] false, from a snapshot) has the home card to say it, so
     * no banner.
     */
    private fun setAccount(a: Account, live: Boolean, publish: Boolean = true) {
        val old = account
        // Fine again, or only resting at a limit that ends by itself: the notification asking the
        // owner to sign in or renew goes. Also at the first word from the Mac, for one an earlier
        // run of the app left in the shade.
        if (!a.needsYou && (old.needsYou || !accountHeard)) onAccountFine()
        accountHeard = true
        if (a == old) return
        account = a
        if (publish) {
            // Blocked sessions name the account's limit in their line.
            if (raw.values.any { it.blocked != null }) publish()
            recomputeUsage()
        }
        if (a.episode == old.episode) return
        // Claude Code's account: nothing to say where it isn't installed (a Mac with Codex alone).
        // While Codex is in use too, its words say it's Claude Code's, not every session's.
        if (!AgentLead.claudeAvailable(claude)) return
        val n = AccountWords.notice(app, a, mac.name, both = AgentLead.both(claude, codex)) ?: return
        if (!accountNoticed(app, a.episode)) return
        when {
            isVisible() -> if (live) noticeFlow.tryEmit(n)
            n.kind == Notice.Kind.Account -> onNotice(n)
        }
    }

    /**
     * The Mac's battery changed (null: it has none, and nothing is ever said about it). Unplugged
     * at [BATTERY_WARN]% or less, the owner hears once, as a banner or, with the app in the
     * background, a notification: when it runs out the Mac sleeps, and every session on it stops.
     * It's said again only after the Mac has been on the charger or back above [BATTERY_REARM]%,
     * which the phone remembers, so neither a reconnect nor a restart of the app repeats it.
     */
    private fun setBattery(b: MacBattery?) {
        macBattery = b
        if (b == null) return
        val prefs = app.getSharedPreferences("hypernova", Context.MODE_PRIVATE)
        // Each computer's battery its own (data/Servers.kt).
        val key = Servers.scoped(BATTERY_WARNED, serverId)
        val warned = prefs.getBoolean(key, false)
        if (!b.draining || b.percent > BATTERY_REARM) {
            if (warned) prefs.edit().remove(key).apply()
            return
        }
        if (warned || b.percent > BATTERY_WARN) return
        prefs.edit().putBoolean(key, true).apply()
        // Amber and "Needs you", as an approval: only the owner can plug it in.
        notice(Notice("battery", Notice.Kind.Power, null, mac.name, res.getString(R.string.notice_mac_battery, b.percent)))
    }

    /**
     * The Mac lets itself sleep in about two minutes (a guard: the battery, the heat, Backpack's
     * hours): a banner, or a notification with the app in the background. About the battery, it
     * takes the place of the battery's own notice (the same id): one says what happens next.
     */
    private fun sleepSoon(s: SleepSoon) {
        val text = AwakeWords.sleepSoon(res, s.inSeconds.toLong(), s.reason, s.percent ?: macBattery?.percent, s.stillAwake, s.otherApp)
        notice(Notice(if (s.reason == MacAwake.BATTERY) "battery" else "mac_sleep", Notice.Kind.Power, null, mac.name, text))
    }

    /**
     * Backpack ends in about ten minutes: a banner (or, in the background, a notification) with a
     * "+2 h" button. It is the one notice for this end; extending moves the end on and the Mac's own
     * sleep warning later is a different notice.
     */
    private fun backpackEnding(b: BackpackEnding) {
        val mins = ((b.inSeconds + 30) / 60).coerceAtLeast(1)
        val text = res.getQuantityString(R.plurals.notice_backpack_ends, mins, mins)
        notice(Notice("backpack-ending", Notice.Kind.Backpack, null, mac.name, text, action = Notice.ACTION_EXTEND))
    }

    private fun sortProjects(p: List<Project>) = p.sortedByDescending { it.lastActivityAt ?: 0L }
    private fun sortProviders(p: List<Provider>) = p.sortedWith(compareByDescending<Provider> { it.builtIn }.thenBy { it.name.lowercase() })

    // ─── Sessions ──────────────────────────────────────────────────────────

    private fun publish() {
        sessions = raw.values.sortedByDescending { it.lastActivityAt }.map(::toSession)
        settleOnMenus()
        for (id in pending.keys) reconcile(id)
    }

    private fun toSession(w: WSession): Session {
        val phase = when (w.state) {
            "starting" -> Phase.Starting
            "working" -> Phase.Working
            "needs_you" -> Phase.NeedsYou
            "idle" -> Phase.Idle
            "error" -> Phase.Error
            "ended" -> Phase.Ended
            else -> Phase.Unknown
        }
        val ask = askOf(w)
        val state = when {
            ask != null || phase == Phase.NeedsYou -> SessionState.NeedsYou
            phase == Phase.Working || phase == Phase.Starting -> SessionState.Working
            else -> SessionState.Idle
        }
        val activity = when {
            ask != null -> ask.summary.ifBlank { res.getString(R.string.activity_needs_you) }
            phase == Phase.NeedsYou -> w.activity ?: res.getString(R.string.activity_needs_you)
            w.stoppedForAway -> res.getString(R.string.activity_paused_away)
            phase == Phase.Starting -> w.activity ?: res.getString(R.string.activity_starting)
            phase == Phase.Working -> w.activity ?: res.getString(R.string.activity_working)
            phase == Phase.Error -> w.lastText ?: res.getString(R.string.activity_error)
            phase == Phase.Ended -> w.lastText ?: res.getString(R.string.activity_ended)
            else -> w.lastText ?: res.getString(R.string.activity_waiting)
        }
        val provider = providers.firstOrNull { it.id == w.providerId }
        // Stopped by a limit or a sign-out: say that, not Claude Code's error line as if it were Claude's.
        // A limit whose reset has passed no longer stops it, even before the Mac clears it.
        val blockedLine = w.blocked
            ?.takeIf { state == SessionState.Idle && phase != Phase.Ended }
            ?.takeIf { (it.kind != Blocked.LIMIT && it.kind != Blocked.MODEL) || it.resetsAt == null || it.resetsAt > now || it.resume?.armed == true || it.resume?.ready == true }
            ?.let { AccountWords.activity(app, it, account, provider?.name, p = provider) }
        val model = w.model ?: provider?.models?.default?.takeIf { provider.kind != "anthropic" } ?: res.getString(R.string.model_default)
        return Session(
            hex = w.id.filter { it.isLetterOrDigit() }.take(2).lowercase().ifEmpty { "··" },
            id = w.id,
            name = w.title.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: res.getString(R.string.untitled_session),
            project = w.projectName,
            state = state,
            model = model,
            mode = w.permissionMode,
            bypassAvailable = w.bypassAvailable,
            effort = w.effort,
            // One plain line: markdown marks would show up literally in mono.
            activity = blockedLine ?: readableLine(activity).orEmpty(),
            updatedAt = w.lastActivityAt,
            isNew = knownIds?.contains(w.id) == false,
            phase = phase,
            cwd = w.cwd,
            origin = w.origin,
            stoppedForAway = w.stoppedForAway,
            queued = w.queued,
            providerId = w.providerId,
            contextPct = w.contextPct?.toFloat(),
            costUsd = w.costUsd,
            term = w.term,
            menu = w.menu,
            createdAt = w.createdAt,
            agentsRunning = w.agentsRunning,
            blocked = w.blocked,
            endedBy = w.endedBy,
            automation = w.automation,
            browsable = w.browsable,
            outsideProjects = w.outsideProjects,
            agent = w.agent,
            switchedFrom = w.switchedFrom,
            switchedTo = w.switchedTo,
            agentJob = w.agentJob,
            agentRunning = w.agentRunning,
            agentOutdated = w.agentOutdated,
            died = w.died,
            question = w.question,
            blockedCalls = w.blockedCalls,
            turnStartedAt = w.turnStartedAt ?: 0L,
            todo = w.todo,
        )
    }

    /** The approval [w] waits on: one it names, else any for it (the card, its state and its Home card agree). */
    private fun askOf(w: WSession): WApproval? =
        w.pendingApprovalIds.firstNotNullOfOrNull { approvals[it] } ?: approvals.values.firstOrNull { it.sessionId == w.id }

    override fun approval(sessionId: String): WApproval? = raw[sessionId]?.let(::askOf)

    override fun session(id: String): Session? = sessions.firstOrNull { it.id == id }

    /** Sessions a rewind replaced, this run: old id to new. */
    private val replacements = mutableStateMapOf<String, String>()

    override fun replacedBy(id: String): String? = replacements[id]

    /**
     * A rewind's copy replaced session [old] (docs/PROTOCOL.md, "Rewind"): what this phone keeps under
     * the old id goes on under [to], and the old id's notification goes (its Reply would still reach
     * [to], but it would open a conversation that moved on). Once per id per run; a snapshot says it
     * again after every reconnect, and it's safe when there's nothing left to move.
     */
    private fun replaced(old: String, to: String) {
        if (replacements[old] == to) return
        replacements[old] = to
        // The same card, not a new one: no reveal.
        knownIds = knownIds?.plus(to)
        onSessionReplaced(old)
        SessionCarry.moved(old, to)
        offline.moved(old, to)
        // Its last activity, known before this says so; else nothing that could hide a reply.
        ReadState.moved(app, serverId, old, to, raw[to]?.lastActivityAt ?: 0L)
    }

    override fun consumeReveal(s: Session): Boolean = s.isNew && revealed.add(s.id)

    private class Cached(val events: List<WEvent>, val pending: List<Sent>, val approvals: Map<String, WApproval>, val resolved: Map<String, Resolved>, val answered: Map<String, Resolution>, val settled: Boolean, val active: Boolean, val shared: Boolean, val queue: List<WQueued>, val step: Boolean, val board: AgentBoard, val waiting: Int, val items: List<Item>, val held: List<WQueued> = emptyList(), val asides: List<Asides.Entry> = emptyList())
    private val cache = HashMap<String, Cached>()

    override fun transcript(id: String): List<Item> {
        // Side questions asked from this phone have their "/btw …" line in the chat, in time (data/Asides.kt).
        val asides = Asides.of(id)
        val ev = slot(id).value
        val pend = pendingSlot(id).value
        val ap = approvals
        val rs = resolved
        val an = answered
        val st = settled
        val s = sessions.firstOrNull { it.id == id }
        val phase = s?.phase
        val active = phase == null || phase == Phase.Working || phase == Phase.Starting || phase == Phase.NeedsYou
        val shared = s?.term != null
        val queue = raw[id]?.queue.orEmpty()
        // Claude Code's own queue (a shared or terminal session), or an SDK session's runner.
        val step = shared || s?.origin == "terminal"
        val board = boardSlot(id).value
        val waitingNow = offline.version
        // Fork: prompts the Mac holds until Claude Code's input box is back (data/Held.kt).
        val held = raw[id]?.held.orEmpty()
        val c = cache[id]
        // The queue compared by value: the session arrives anew with every change of activity.
        if (c != null && c.events === ev && c.pending === pend && c.approvals === ap && c.resolved === rs && c.answered === an && c.settled == st && c.active == active && c.shared == shared && c.queue == queue && c.step == step && c.board === board && c.waiting == waitingNow && c.held == held && c.asides === asides) return c.items
        val all = if (asides.isEmpty()) ev else Asides.into(ev, asides)
        // Only events that changed (new instances) are converted again; streaming text and
        // 64 KB Bash outputs arrive several times a second.
        val built = Items.build(all, ap, rs, an, id, raw[id]?.cwd, now, settled = st, cache = itemCaches.getOrPut(id) { Items.Cache() }, active = active, board = board)
        // What waits on this phone for the Mac comes last, under everything.
        // Fork: held prompts before those, just above the asks waiting at the end (ui/session/AskStack.kt).
        val items = withCopies(built, all, pend, shared, queue, step) + Held.items(held, ev, queue) + offline.of(id).map(Waiting::item)
        cache[id] = Cached(ev, pend, ap, rs, an, st, active, shared, queue, step, board, waitingNow, items, held, asides)
        return items
    }

    override fun agents(sessionId: String): AgentBoard = boardSlot(sessionId).value

    /** An agent's transcript as this phone has it, and the Mac's cursor for what came after. */
    private class AgentLog(val cursor: String?, val events: List<WEvent>)
    /** The few agents opened lately (an open screen asks every 1.5 s while its agent runs). */
    private val agentLogs = object : LinkedHashMap<String, AgentLog>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AgentLog>?) = size > 4
    }

    /**
     * While the agent runs, only what changed since the last answer comes over the wire (usually
     * nothing, or a line or two), merged into what this phone has; the same list comes back when
     * nothing did, so its screen doesn't rebuild. Once it has ended, the whole of it once more, so
     * nothing it did can be missed. An older Mac ignores the cursor and sends it whole every time.
     */
    override suspend fun agentEvents(sessionId: String, agentId: String): Result<List<WEvent>> = try {
        val key = "$sessionId/$agentId"
        val prev = agentLogs[key]?.takeIf { boardSlot(sessionId).value.agents[agentId]?.running == true }
        val after = prev?.cursor?.let { "&after=${Api.q(it)}" }.orEmpty()
        val o = api.get("/v1/sessions/$sessionId/agents/${Api.q(agentId)}/events?limit=300$after", max = 32L * 1024 * 1024)
        // Just started, no transcript yet: nothing kept, so the next answer is whole.
        if (o.has("starting") && o.bool("starting")) {
            agentLogs.remove(key)
            throw AgentStarting()
        }
        val log = withContext(Dispatchers.Default) {
            val got = o.arr("events").mapObjects(Wire::event)
            val full = prev == null || !o.has("full") || o.bool("full")
            AgentLog(o.str("cursor"), if (full) got else mergeEvents(prev!!.events, got))
        }
        agentLogs[key] = log
        Result.success(log.events)
    } catch (e: CancellationException) { throw e } catch (e: AgentStarting) { Result.failure(e) } catch (e: Throwable) { Result.failure(handle(e, R.string.err_agent)) }

    /**
     * [changed] events into [old]: one it has is replaced where it is, a new one goes at the end (the
     * Mac sends them in the transcript's order). The task stays first; the rest is kept to its last 600.
     */
    private fun mergeEvents(old: List<WEvent>, changed: List<WEvent>): List<WEvent> {
        if (changed.isEmpty()) return old
        val out = old.toMutableList()
        val at = HashMap<String, Int>(out.size * 2)
        out.forEachIndexed { i, e -> at[e.id] = i }
        for (e in changed) {
            val i = at[e.id]
            if (i != null) out[i] = e else { at[e.id] = out.size; out += e }
        }
        return if (out.size > 600) listOf(out.first()) + out.takeLast(599) else out
    }

    /** An agent screen's items, and what they were built from. */
    private class AgentItems(
        val events: List<WEvent>, val running: Boolean, val children: Map<String, List<WAgent>>, val workflows: Map<String, WWorkflow>,
        val approvals: Map<String, WApproval>, val resolved: Map<String, Resolved>, val answered: Map<String, Resolution>, val settled: Boolean, val items: List<Item>,
    )
    private val agentItems = HashMap<String, AgentItems>()

    override fun agentTranscript(sessionId: String, agentId: String, events: List<WEvent>): List<Item> {
        val board = boardSlot(sessionId).value
        val ap = approvals
        val rs = resolved
        val an = answered
        val st = settled
        val running = board.agents[agentId]?.running == true
        // Built again only when something it shows changed. The board changes about once a second
        // while any of the session's agents works, but this screen draws only whether this agent
        // runs and the cards of agents started by agents (by value: few, usually none).
        val key = "$sessionId/$agentId"
        agentItems[key]?.let { c ->
            if (c.events === events && c.running == running && c.workflows === board.workflows && c.children == board.children &&
                c.approvals === ap && c.resolved === rs && c.answered === an && c.settled == st
            ) return c.items
        }
        // Its own approvals, still waiting, at the end (answerable there too).
        val mine = ap.filterValues { it.agentId == agentId }
        val items = Items.build(events, mine, rs, an, sessionId, raw[sessionId]?.cwd, now, settled = st, active = running, board = board, orphans = false)
        if (agentItems.size > 8) agentItems.clear()
        agentItems[key] = AgentItems(events, running, board.children, board.workflows, ap, rs, an, st, items)
        return items
    }

    /**
     * The session's agents and workflows, whole (the socket then keeps them current). An older
     * Mac has no such route: the session draws its calls as before.
     */
    private fun loadAgents(id: String) {
        scope.launch {
            try {
                val o = api.get("/v1/sessions/$id/agents", max = 8L * 1024 * 1024)
                val board = withContext(Dispatchers.Default) {
                    AgentBoard(o.arr("agents").mapObjects(Wire::agent).associateBy { it.id }, o.arr("workflows").mapObjects(Wire::workflow).associateBy { it.id })
                }
                // What the socket brought meanwhile may be newer than the list: an agent that moved on
                // or ended since keeps that.
                val cur = boardSlot(id).value
                fun newer(a: WAgent, b: WAgent?) = b == null || a.lastActivityAt > b.lastActivityAt || (!a.running && b.running)
                fun newer(a: WWorkflow, b: WWorkflow?) = b == null || (!a.running && b.running)
                boardSlot(id).value = AgentBoard(
                    board.agents + cur.agents.filter { (k, a) -> newer(a, board.agents[k]) },
                    board.workflows + cur.workflows.filter { (k, w) -> newer(w, board.workflows[k]) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (Net.classify(e).problem == Problem.Unauthorized) unpaired()
            }
        }
    }

    /**
     * The transcript with this phone's copies of its prompts, and what waits in the session's
     * queue at the end (see [queuedTail]). A copy typed into a shared terminal sits where it was
     * sent (see [place]): it doesn't move when it settles, when later events arrive or when the
     * terminal moves on to another session, only down below the Mac's copy of a prompt sent
     * before it. Elsewhere one waiting for the Mac stays at the end. While its request is out,
     * [shared] (the session now) guesses which it will be.
     */
    private fun withCopies(items: List<Item>, events: List<WEvent>, copies: List<Sent>, shared: Boolean, queue: List<WQueued>, step: Boolean): List<Item> {
        val waiting = queuedTail(copies, queue, step, events)
        val sent = if (waiting.isEmpty()) copies else copies.filter { it.stage != Stage.Queued }
        val out = placeCopies(items, events, sent, shared)
        return if (waiting.isEmpty()) out else out + waiting
    }

    private fun placeCopies(items: List<Item>, events: List<WEvent>, copies: List<Sent>, shared: Boolean): List<Item> {
        if (copies.isEmpty()) return items
        fun inPlace(c: Sent) = c.typed ?: shared
        if (copies.none(::inPlace)) return items + copies.map { it.item }
        val at = HashMap<String, Int>(events.size * 2)
        events.forEachIndexed { i, e -> at[e.id] = i }
        // The index of the event each copy follows: -1 puts it first, MAX_VALUE last.
        val placed = copies.map { c ->
            c.item to if (inPlace(c)) place(c, events) { at[it] ?: -1 } else Int.MAX_VALUE
        }.sortedBy { it.second }
        val out = ArrayList<Item>(items.size + copies.size)
        var k = 0
        for (item in items) {
            // Items keep their events' order; an approval with no event yet comes last.
            val i = at[item.key] ?: Int.MAX_VALUE
            while (k < placed.size && placed[k].second < i) out += placed[k++].first
            out += item
        }
        while (k < placed.size) out += placed[k++].first
        return out
    }

    /**
     * What waits for Claude, drawn last, under its live output, as the terminal draws its queue:
     * copies of prompts that just left the queue (the Mac's copy is on its way), then the
     * session's queue in order, this phone's copy standing in for each prompt it sent, then
     * copies the queue doesn't show yet. Prompts queued on the Mac show the same way. [step]:
     * Claude Code's queue, which Claude reads when the step it's on finishes; else an SDK
     * session's, which waits for the whole reply.
     */
    private fun queuedTail(copies: List<Sent>, queue: List<WQueued>, step: Boolean, events: List<WEvent>): List<Item> {
        val queued = copies.filter { it.stage == Stage.Queued }
        if (queue.isEmpty() && queued.isEmpty()) return emptyList()
        val matched = queueMatches(copies, queue)
        val inQueue = matched.values.mapTo(HashSet()) { it.item.key }
        val out = ArrayList<Item>(queue.size + queued.size)
        for (c in queued) if (c.seen && c.item.key !in inQueue) out += c.item
        for (q in queue) {
            val c = matched[q.id]
            // A copy still on its way shows itself, where it was sent. One of this phone's sends
            // whose copy has gone (the Mac's copy replaced it, or it came back) and a prompt whose
            // Mac copy is in already are leaving: the queue's update is a moment behind.
            if (c != null) { if (c.stage == Stage.Queued) out += c.item }
            else if (q.clientId !in sentIds && !delivered(q, events)) out += macQueued(q, step)
        }
        for (c in queued) if (!c.seen && c.item.key !in inQueue) out += c.item
        return out
    }

    /**
     * [q] has reached Claude: the Mac's copy of it is among the last [events], logged no earlier
     * than it was queued, while the queue's own update is a moment behind.
     */
    private fun delivered(q: WQueued, events: List<WEvent>): Boolean {
        for (i in events.indices.reversed().take(DELIVERED_LOOKBACK)) {
            val e = events[i] as? WEvent.User ?: continue
            if (q.clientId != null) {
                if (e.clientId == q.clientId) return true
                continue
            }
            // Claude Code stamps it with the time it was queued, give or take: not an older prompt with the same text.
            if (e.ts >= q.at - DELIVERED_SLACK && sameText(e.text, q)) return true
        }
        return false
    }

    /** A prompt queued on the Mac (or by another phone), drawn like this phone's own. */
    private fun macQueued(q: WQueued, step: Boolean) = Item.User(
        key = "queued:${q.id}",
        text = q.text,
        status = if (step) SendStatus.QueuedStep else SendStatus.QueuedReply,
        files = q.files,
        command = if (q.files.isEmpty()) CommandRun.typed(q.text) else null,
    )

    /**
     * Which of this phone's [copies] each entry of [queue] is (by entry id): the send it names, or,
     * for one that names none (the daemon couldn't tell), a copy on its way or queued with the
     * same prompt, oldest first. An entry naming a send that isn't this phone's is someone else's.
     */
    private fun queueMatches(copies: List<Sent>, queue: List<WQueued>): Map<String, Sent> {
        if (queue.isEmpty() || copies.isEmpty()) return emptyMap()
        val out = HashMap<String, Sent>()
        val used = HashSet<String>()
        for (q in queue) {
            val cid = q.clientId ?: continue
            copies.firstOrNull { it.clientId == cid }?.let { out[q.id] = it; used += it.item.key }
        }
        for (q in queue) {
            if (q.clientId != null) continue
            copies.firstOrNull { it.item.key !in used && it.stage != Stage.Settled && queuedAs(it, q) }?.let { out[q.id] = it; used += it.item.key }
        }
        return out
    }

    /** [q] is [c]'s prompt as the queue shows it: the same files, and the same text (cut at 300 characters there). */
    private fun queuedAs(c: Sent, q: WQueued): Boolean =
        c.item.files.map { it.path } == q.files.map { it.path } && sameText(c.item.text, q)

    /** [text] is [q]'s, which the queue cuts at [QUEUE_TEXT] characters. */
    private fun sameText(text: String, q: WQueued): Boolean {
        val mine = sendText(text)
        val theirs = sendText(q.text)
        return mine == theirs || (theirs.endsWith("…") && q.text.length >= QUEUE_TEXT && mine.startsWith(theirs.dropLast(1).trimEnd()))
    }

    /** Whether Claude is on something: a queued prompt that isn't in the queue may still get there. */
    private fun busy(s: WSession) = s.state == "working" || s.state == "starting" || s.state == "needs_you"

    /**
     * This phone's copies against the session's queue, at every change. One the queue shows is
     * Queued (a send Claude Code queued though Claude seemed done). A Queued one the queue no
     * longer shows has left it: it waits [GRACE] for the Mac's copy, which comes with or right
     * after that change, and then settles where it is. One the queue never showed (Claude Code
     * that doesn't log its queue) settles a moment after the session stops working.
     */
    private fun reconcile(sessionId: String) {
        val p = pending[sessionId] ?: return
        val s = raw[sessionId] ?: return
        if (p.value.none { it.stage == Stage.Queued || it.stage == Stage.Posted }) return
        val inQueue = queueMatches(p.value, s.queue).values.mapTo(HashSet()) { it.item.key }
        val next = p.value.map { c ->
            val there = c.item.key in inQueue
            when {
                there && c.stage == Stage.Posted -> c.copy(seen = true).at(Stage.Queued)
                there && c.stage == Stage.Queued && !c.seen -> c.copy(seen = true)
                else -> c
            }
        }
        if (next != p.value) p.value = next
        for (c in next) {
            if (c.stage != Stage.Queued || c.item.key in inQueue) continue
            if (c.seen) graceFor(sessionId, c.item.key, GRACE) else if (!busy(s)) graceFor(sessionId, c.item.key, IDLE_GRACE)
        }
    }

    /** Copies waiting out their grace (see [reconcile]), by key. */
    private val graces = HashSet<String>()
    /** This phone's sends (their clientIds), so a queue entry naming one is never taken for someone else's. */
    private val sentIds = HashSet<String>()

    private fun graceFor(sessionId: String, key: String, ms: Long) {
        if (!graces.add(key)) return
        scope.launch {
            delay(ms)
            graces.remove(key)
            val p = pending[sessionId] ?: return@launch
            val c = p.value.firstOrNull { it.item.key == key && it.stage == Stage.Queued } ?: return@launch
            val s = raw[sessionId]
            // Back in the queue meanwhile, or never in it and Claude is at work again: still waiting.
            if (s != null && (queueMatches(p.value, s.queue).values.any { it.item.key == key } || (!c.seen && busy(s)))) return@launch
            settleQueued(sessionId, key)
        }
    }

    /**
     * A queued copy whose prompt left without the Mac logging it (yet): shown as sent, where it is
     * now (last), not where it was sent, so it doesn't jump.
     */
    private fun settleQueued(sessionId: String, key: String) {
        val p = pending[sessionId] ?: return
        val list = slot(sessionId).value
        val macNow = maxOf(list.maxOfOrNull { it.ts } ?: 0L, raw[sessionId]?.lastActivityAt ?: 0L)
        p.value = p.value.map { if (it.item.key == key && it.stage == Stage.Queued) it.copy(after = list.lastOrNull()?.id, macAt = macNow).at(Stage.Settled) else it }
    }

    /**
     * Queued prompts that didn't reach Claude ([u]: back in the terminal's input box, or dropped),
     * as the notice at [i] in [list] names them: this phone's copies of them go, and their text
     * and files go back into the session's message box.
     */
    private fun unsend(sessionId: String, u: Unsent, list: List<WEvent>, i: Int) {
        val p = pending[sessionId] ?: return
        var copies = p.value
        val back = ArrayList<Sent>()
        for (q in u.prompts) {
            val k = if (q.clientId != null) copies.indexOfFirst { it.clientId == q.clientId }
            else copies.indexOfFirst { it.stage == Stage.Queued && loggedAfter(it, list, anchorIn(it, list), i) && sendText(it.item.text) == sendText(q.text) }
            if (k < 0) continue
            back += copies[k]
            copies = copies.filterIndexed { j, _ -> j != k }
        }
        if (back.isEmpty()) return
        p.value = copies
        for (c in back) {
            AttachDrafts.giveBack(sessionId, c.item.text)
            AttachDrafts.restore(sessionId, c.item.files.map { a -> DraftFile(AttachDrafts.nextId(), a.name, a.isImage).apply { attachment = a; thumb = Thumbs.get(a.path) } })
        }
        toast(res.getString(if (u.returned) R.string.queued_returned else R.string.queued_dropped))
    }

    override fun sending(id: String): Boolean = pendingSlot(id).value.any { it.stage == Stage.InFlight || it.stage == Stage.Posted }

    override fun loadingTranscript(id: String): Boolean = loading[id] == true

    override fun transcriptError(id: String): String? = loadErrors[id]

    override fun reloadTranscript(id: String) {
        loadErrors.remove(id)
        load(id)
    }

    override fun opened(id: String) {
        openId = id
        if (id !in loaded) load(id)
    }

    private fun load(id: String) {
        if (loading[id] == true) return
        loading[id] = true
        buffered[id] = mutableListOf()
        goneWhileLoading[id] = HashSet()
        loadAgents(id)
        scope.launch {
            try {
                // Long sessions with big tool outputs: allow a large history; each output only its
                // last 8 KB (what a card shows), so a long session loads quickly.
                val limit = eventLimits[id] ?: EVENTS_PAGE
                val o = api.get(eventsPath(id, limit), max = 32L * 1024 * 1024)
                val history = withContext(Dispatchers.Default) { o.arr("events").mapObjects(Wire::event) }
                earlier[id] = history.size >= limit
                var list = history
                buffered.remove(id)?.forEach { list = upsert(list, it) }
                goneWhileLoading.remove(id)?.let { list = withoutEvents(list, it) }
                slot(id).value = list
                loaded += id
                loadErrors.remove(id)
                // Prompts this phone sent that the Mac already has: drop their copies, one event
                // for each (two identical "/effort"s need two), in the order the Mac logged them,
                // as if they had arrived one by one. Events that confirmed a copy before don't again.
                pending[id]?.let { p ->
                    var copies = p.value
                    for (i in list.indices) {
                        if (copies.isEmpty()) break
                        copies = confirm(id, copies, list, i)
                    }
                    if (copies !== p.value) p.value = copies
                }
                // Queued prompts that came back or were dropped while this phone wasn't looking.
                list.forEachIndexed { i, e -> if (e is WEvent.Notice && e.unsent != null) unsend(id, e.unsent, list, i) }
                settleWarned(id, list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                buffered.remove(id)
                goneWhileLoading.remove(id)
                val ex = Net.classify(e)
                if (ex.problem == Problem.Unauthorized) unpaired()
                else {
                    val msg = message(ex, R.string.err_history)
                    loadErrors[id] = msg
                    if (slot(id).value.isNotEmpty()) toast(msg)
                }
            } finally {
                loading.remove(id)
            }
        }
    }

    private fun eventsPath(id: String, limit: Int) = "/v1/sessions/$id/events?limit=$limit&output=$EVENT_OUTPUT"

    override fun hasEarlier(id: String): Boolean = earlier[id] == true

    override fun loadingEarlier(id: String): Boolean = loadingMore[id] == true

    /**
     * The history before what's loaded: the last [EVENTS_PAGE] more events, of which the ones
     * before the first shown go in front (any Mac answers this; no cursor needed). A history the
     * Mac rewrote meanwhile (a compact, a rewind) is taken whole.
     */
    override fun loadEarlier(id: String) {
        if (loadingMore[id] == true || loading[id] == true) return
        val limit = (eventLimits[id] ?: EVENTS_PAGE) + EVENTS_PAGE
        loadingMore[id] = true
        scope.launch {
            try {
                val o = api.get(eventsPath(id, limit), max = 32L * 1024 * 1024)
                val got = withContext(Dispatchers.Default) { o.arr("events").mapObjects(Wire::event) }
                val have = slot(id).value
                val first = have.firstOrNull()?.id
                val at = if (first == null) -1 else got.indexOfFirst { it.id == first }
                slot(id).value = when {
                    first == null -> got
                    at >= 0 -> got.subList(0, at) + have
                    // Not there any more: what the Mac has now, plus anything newer this phone holds.
                    else -> got + have.filter { h -> got.none { it.id == h.id } && h.ts >= (got.lastOrNull()?.ts ?: 0L) }
                }
                eventLimits[id] = limit
                earlier[id] = got.size >= limit
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(handle(e, R.string.err_history).message.orEmpty())
            } finally {
                loadingMore.remove(id)
            }
        }
    }

    /** The Mac took event [id] back: off the session's list, and off a history still on its way. */
    private fun dropEvent(sessionId: String, id: String) {
        goneWhileLoading[sessionId]?.add(id)
        val s = events[sessionId] ?: return
        val next = withoutEvents(s.value, listOf(id))
        if (next !== s.value) s.value = next
    }

    private fun appendEvent(sessionId: String, e: WEvent) {
        buffered[sessionId]?.add(e)
        val s = slot(sessionId)
        var list = upsert(s.value, e)
        // Live events keep the list from growing without end, but never undo a "Show earlier".
        val keep = maxOf(400, (eventLimits[sessionId] ?: 0) + EVENTS_PAGE)
        if (list.size > keep) list = list.takeLast(keep)
        s.value = list
        val p = pending[sessionId] ?: return
        when {
            // The Mac logged a prompt this phone sent: drop the optimistic copy.
            e is WEvent.User -> {
                val next = confirm(sessionId, p.value, list, list.indexOfLast { it.id == e.id })
                if (next !== p.value) p.value = next
            }
            e is WEvent.Notice && e.unsent != null -> unsend(sessionId, e.unsent, list, list.indexOfLast { it.id == e.id })
            e is WEvent.Notice && warning(e) -> settleWarned(sessionId, list)
        }
    }

    /**
     * [list]`[i]` may be the Mac's copy of a prompt this phone sent ([copies], in the order they
     * went): returns the copies without it. Prompts sent after it can't have been logged before
     * it, so copies of those drawn above this event follow it from now on.
     */
    private fun confirm(sessionId: String, copies: List<Sent>, list: List<WEvent>, i: Int): List<Sent> {
        val e = list.getOrNull(i) as? WEvent.User ?: return copies
        val used = confirmed.getOrPut(sessionId) { HashSet() }
        if (e.id in used) return copies
        val k = match(copies, e, list, i, knownCommands(raw[sessionId]?.cwd))
        if (k < 0) return copies
        used += e.id
        fun above(c: Sent) = place(c, list) { a -> list.indexOfLast { it.id == a } } < i
        return copies.mapIndexedNotNull { j, c ->
            when {
                j == k -> null
                j > k && above(c) -> c.copy(after = e.id)
                else -> c
            }
        }
    }

    /**
     * Which of [copies] [e] (at [i] in [list]) is the Mac's copy of, or -1. Copies still on their
     * way come first: a settled one may have landed unlogged (a picker closed with Esc) while the
     * same command sent again is about to be logged. Then the first sent: Claude Code logs
     * prompts in the order they were typed, so two identical ones confirm in turn. [known]: the
     * project's "/" list, for a command's other names.
     */
    private fun match(copies: List<Sent>, e: WEvent.User, list: List<WEvent>, i: Int, known: List<SlashCommand>?): Int {
        // The Mac names the send it came from: that copy, whatever its text became on the way.
        // One that names a send this phone doesn't have isn't its own.
        e.clientId?.let { cid -> return copies.indexOfFirst { it.clientId == cid } }
        var settled = -1
        for ((k, c) in copies.withIndex()) {
            if (!confirms(c, e, list, i, known)) continue
            if (c.stage != Stage.Settled) return k
            if (settled < 0) settled = k
        }
        return settled
    }

    /**
     * [e], at [i] in [list], is the Mac's copy of [c]: the same prompt, logged after [c] went (an
     * old identical "/effort" in the history can't stand in for a new one). A settled copy may
     * have landed without being logged, so it only takes a prompt logged before any other since
     * it went, or soon after it went: not the same command typed on the Mac much later.
     */
    private fun confirms(c: Sent, e: WEvent.User, list: List<WEvent>, i: Int, known: List<SlashCommand>?): Boolean {
        if (!sameSend(c.item, e, known)) return false
        val a = anchorIn(c, list)
        if (!loggedAfter(c, list, a, i)) return false
        if (c.stage != Stage.Settled || e.ts - c.macAt <= LATE) return true
        return (0 until i).none { j -> list[j] is WEvent.User && loggedAfter(c, list, a, j) }
    }

    /** Where [c]'s anchor is in [list]: -1 when it has none (nothing was loaded) or it's gone (cut off). */
    private fun anchorIn(c: Sent, list: List<WEvent>): Int = c.after?.let { a -> list.indexOfLast { it.id == a } } ?: -1

    /**
     * Whether [list]`[i]` was logged after [c] went: it comes after [c]'s anchor ([a]), or, with
     * the anchor unknown or gone, the Mac stamped it later than [Sent.macAt]. Only then is time
     * compared, and Mac time with Mac time.
     */
    private fun loggedAfter(c: Sent, list: List<WEvent>, a: Int, i: Int): Boolean =
        if (a >= 0) i > a else list[i].ts > c.macAt

    /**
     * The index in [list] of the event [c] is drawn after: its anchor ([indexOf] it), or when
     * that's gone the last event the Mac logged before [c] went; -1 for none.
     */
    private fun place(c: Sent, list: List<WEvent>, indexOf: (String) -> Int): Int {
        val a = c.after?.let(indexOf) ?: -1
        return if (a >= 0) a else list.indexOfLast { it.ts <= c.macAt }
    }

    /** One of Claude Code's own warnings ("Unknown command: /effot…"), not a command's output nor a queued prompt dropped. */
    private fun warning(e: WEvent.Notice) = e.command == null && e.unsent == null && (e.tone == "warn" || e.tone == "error")

    /** Claude Code warned about something after [c] went: whatever [c] was, it has landed. */
    private fun warned(c: Sent, list: List<WEvent>): Boolean {
        val a = anchorIn(c, list)
        return list.indices.any { i -> val e = list[i]; e is WEvent.Notice && warning(e) && loggedAfter(c, list, a, i) }
    }

    /**
     * A warning arrived: copies typed into the terminal before it settle at once. An unknown
     * command leaves nothing else; anything that is logged later still confirms its copy.
     */
    private fun settleWarned(sessionId: String, list: List<WEvent>) {
        val p = pending[sessionId] ?: return
        for (c in p.value) if (c.stage == Stage.Queued && landing(c) && warned(c, list)) settleQueued(sessionId, c.item.key)
        val next = p.value.map { if (it.stage == Stage.Posted && it.typed == true && warned(it, list)) it.at(Stage.Settled) else it }
        if (next != p.value) p.value = next
    }

    /**
     * Typed into its terminal and not known to wait there: on its way, or a command the queue
     * never showed (Claude Code runs many at once, even while Claude works).
     */
    private fun landing(c: Sent) = c.typed == true && (c.stage == Stage.Posted || (c.stage == Stage.Queued && !c.seen && c.item.command != null))

    /**
     * The Mac's copy of a prompt this phone sent: the same text and the same files, or the same
     * command under another of its names on the project's "/" list ([known]): Claude Code logs
     * "/cost" as "/usage".
     */
    private fun sameSend(mine: Item.User, e: WEvent.User, known: List<SlashCommand>?): Boolean {
        if (mine.files.map { it.path } != e.files.map { it.path }) return false
        if (sameSentText(mine.text, e.text)) return true
        val a = mine.command?.takeUnless { it.bash } ?: return false
        val b = e.command?.takeUnless { it.bash } ?: return false
        return a.args.trim().replace(SPACES, " ") == b.args.trim().replace(SPACES, " ") && sameCommand(a.name, b.name, known)
    }

    /**
     * A prompt as the Mac logs it: runs of whitespace are one space (a command's arguments are
     * logged apart from its name), and "! ls" is "!ls" (bash mode drops the space).
     */
    private fun sendText(text: String): String {
        val t = text.trim().replace(SPACES, " ")
        return if (t.startsWith("!")) "!" + t.substring(1).trimStart() else t
    }

    /** Moves the copies at [from] to [to]: [key]'s, or (with none) all the session's typed into its terminal. */
    private fun restage(sessionId: String, key: String?, from: Stage, to: Stage) {
        val p = pending[sessionId] ?: return
        fun hit(c: Sent) = c.stage == from && (if (key != null) c.item.key == key else c.typed == true)
        if (p.value.none(::hit)) return
        p.value = p.value.map { if (hit(it)) it.at(to) else it }
    }

    /** Whatever was typed into the session's terminal has done its thing (it opened a menu, say): it settles. */
    private fun settleLanded(sessionId: String) {
        val p = pending[sessionId] ?: return
        for (c in p.value) if (c.stage == Stage.Queued && landing(c)) settleQueued(sessionId, c.item.key)
        restage(sessionId, null, Stage.Posted, Stage.Settled)
    }

    /**
     * The prompt's request came back: [typed] says whether it went into the session's shared
     * terminal (or to its SDK run). That is decided now and kept: /clear and /resume move the
     * terminal to another session a second or two later. [queued]: Claude was working, so it
     * waits in a queue until Claude can take it; Claude Code may hold it for minutes, so it
     * waits as Queued, with no clock (see [reconcile]). Otherwise a typed copy the Mac hasn't
     * logged within [SETTLE] settles: plenty of commands never reach the transcript (a picker
     * closed with Esc, an unknown command, a fullscreen view). One that opened a menu, whose menu
     * was answered since, or that Claude Code already warned about, has done its thing. An SDK
     * session logs a prompt it runs at once.
     */
    private fun posted(sessionId: String, key: String, typed: Boolean, queued: Boolean) {
        val p = pending[sessionId] ?: return
        // Gone if the Mac logged it before the request came back.
        val c = p.value.firstOrNull { it.item.key == key } ?: return
        if (queued) {
            p.value = p.value.map { if (it.item.key == key) it.copy(typed = typed).at(Stage.Queued) else it }
            reconcile(sessionId)
            return
        }
        val done = typed && (raw[sessionId]?.menu != null || (menuAnswered[sessionId] ?: 0L) >= c.item.sentAt || warned(c, slot(sessionId).value))
        p.value = p.value.map { if (it.item.key == key) it.copy(typed = typed).at(if (done) Stage.Settled else Stage.Posted) else it }
        if (done) return
        scope.launch {
            delay(SETTLE)
            restage(sessionId, key, Stage.Posted, Stage.Settled)
        }
    }

    /** A session's terminal shows a menu: the prompt that opened it (/model, /effort…) has landed. */
    private fun settleOnMenus() {
        for ((id, p) in pending) {
            if (p.value.none(::landing)) continue
            if (raw[id]?.menu != null) settleLanded(id)
        }
    }

    /** Same id replaces the previous version (streaming text grows); new ids append. */
    private fun upsert(list: List<WEvent>, e: WEvent): List<WEvent> {
        for (i in list.indices.reversed()) {
            if (list[i].id == e.id) return list.toMutableList().also { it[i] = e }
        }
        return list + e
    }

    private fun applySession(o: JSONObject): WSession? {
        val s = o.obj("session")?.let { runCatching { Wire.session(it) }.getOrNull() } ?: return null
        val old = raw[s.id]
        raw = raw + (s.id to s)
        publish()
        if (old != null) heldLeft(old, s)
        return s
    }

    /** Fork: held prompts this phone took back itself (by the Mac's id): leaving, they aren't sent. */
    private val heldRemoved = HashSet<String>()

    /**
     * Fork: held prompts the Mac no longer holds. Each becomes this phone's copy of a sent prompt,
     * where the chat is now, under the send's own name, so it goes the way any send does: the
     * Mac's copy replaces it, Claude Code's queue shows it, or the notice that it wasn't sent gives
     * its text back. One that none of those claims within [HELD_LINGER] goes.
     */
    private fun heldLeft(old: WSession, s: WSession) {
        val gone = Held.left(old.held, s.held, heldRemoved)
        if (gone.isEmpty()) return
        val list = slot(s.id).value
        val p = pendingSlot(s.id)
        val macAt = maxOf(list.maxOfOrNull { it.ts } ?: 0L, s.lastActivityAt)
        for (h in gone) {
            val cid = h.clientId
            // Its event is in already: nothing to stand in for.
            if (cid != null && list.asReversed().take(DELIVERED_LOOKBACK).any { (it as? WEvent.User)?.clientId == cid }) continue
            if (cid != null) sentIds += cid
            val item = Held.item(h).copy(key = "local-${seq++}", status = SendStatus.Sending, heldId = null, sentAt = now)
            p.value = p.value + Sent(item, after = list.lastOrNull()?.id, stage = Stage.Posted, macAt = macAt, typed = true, clientId = cid)
            scope.launch {
                delay(HELD_LINGER)
                p.value = p.value.filterNot { it.item.key == item.key && it.stage == Stage.Posted }
            }
        }
    }

    // ─── Actions ───────────────────────────────────────────────────────────

    /** A send that failed: [network] says nothing answered (the request may or may not have arrived), else the Mac said no. */
    private class SendFailed(message: String, val status: Int, val code: String?, val network: Boolean) : HubException(message)

    override suspend fun send(sessionId: String, text: String, files: List<Attachment>, replace: Boolean): Result<String> {
        val t = text.trim()
        if (t.isEmpty() && files.isEmpty()) return Result.success(sessionId)
        // The Mac is away: the prompt waits on the phone and goes when it's back (OfflineQueue).
        if (!replace && (link.state == LinkState.Connecting || link.state == LinkState.Reconnecting || link.state == LinkState.Offline)) {
            hold(sessionId, t, files, null)
            return Result.success(sessionId)
        }
        val clientId = UUID.randomUUID().toString()
        val r = sendNow(sessionId, t, files, replace, clientId)
        // Nothing answered: the send may even have arrived. It waits, under the same name, so sending it again is safe.
        val e = r.exceptionOrNull()
        if (!replace && e is SendFailed && e.network) {
            hold(sessionId, t, files, clientId)
            if (link.state == LinkState.Connected) scope.launch { delay(RETRY_HELD); flushOffline() }
            return Result.success(sessionId)
        }
        return r
    }

    private fun hold(sessionId: String, text: String, files: List<Attachment>, clientId: String?) {
        val s = raw[sessionId]
        offline.add(sessionId, text, files, cwd = s?.cwd, mode = s?.permissionMode, id = clientId ?: UUID.randomUUID().toString())
    }

    /** Sends the prompts that waited for the Mac, oldest first, one at a time, each under its own name. */
    private fun flushOffline() {
        if (flushing) return
        flushing = true
        scope.launch {
            try {
                var round = 0
                while (link.state == LinkState.Connected && round < FLUSH_ROUNDS) {
                    var again = false
                    val skip = HashSet<String>()
                    while (link.state == LinkState.Connected) {
                        val w = offline.due().firstOrNull { it.sessionId !in skip } ?: break
                        if (!offline.sending(w.id)) { skip += w.sessionId; continue }
                        val r = sendNow(w.sessionId, w.text, w.files, false, w.id)
                        val e = r.exceptionOrNull()
                        when {
                            e == null -> offline.sent(w.id)
                            // The conversation went on in another session: it waits here, to edit or remove.
                            e is SessionSwitched -> { offline.failed(w.id); skip += w.sessionId }
                            e !is SendFailed -> { offline.failed(w.id); skip += w.sessionId; again = true }
                            // The Mac has no such session any more.
                            e.status == 404 && e.code == "no_session" -> { offline.failed(w.id); offline.markEnded(w.id) }
                            // A file it was meant to carry is gone: the words go without it.
                            e.code == "attachment_gone" -> { offline.failed(w.id); offline.dropFiles(w.id); toast(res.getString(R.string.waiting_files_gone)) }
                            // Nothing answered: the link is going; the next connect sends it.
                            e.network -> { offline.failed(w.id); return@launch }
                            // The session can't take it now (an approval is open, the terminal is busy): later.
                            else -> {
                                offline.failed(w.id); skip += w.sessionId; again = true
                                if (round == 0) toast(e.message.orEmpty())
                            }
                        }
                    }
                    if (!again) break
                    round++
                    delay(RETRY_HELD)
                }
            } finally {
                flushing = false
            }
        }
    }

    override fun waiting(sessionId: String): List<Item.User> = offline.of(sessionId).map(Waiting::item)
    override fun waitingCount(sessionId: String): Int = offline.count(sessionId)
    override fun orphanedPrompts(): List<Item.User> = offline.orphans().map(Waiting::item)
    override fun editWaiting(waitingId: String, text: String): Boolean = offline.edit(waitingId, text)
    override fun removeWaiting(waitingId: String) { offline.remove(waitingId) }

    override fun heldCount(sessionId: String): Int = raw[sessionId]?.held?.size ?: 0

    override suspend fun editHeld(sessionId: String, heldId: String, text: String): Result<Unit> = try {
        applySession(api.put("/v1/sessions/$sessionId/held/$heldId", JSONObject().put("text", text)))
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.held_err_edit)) }

    override suspend fun removeHeld(sessionId: String, heldId: String): Result<Unit> {
        // Before the call: the Mac's session update may come over the socket first.
        heldRemoved += heldId
        return try {
            applySession(api.delete("/v1/sessions/$sessionId/held/$heldId"))
            Result.success(Unit)
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            heldRemoved -= heldId
            Result.failure(handle(e, R.string.held_err_remove))
        }
    }

    override suspend fun startFromWaiting(waitingId: String): Result<String> {
        val w = offline.get(waitingId) ?: return Result.failure(HubException(res.getString(R.string.err_start)))
        val cwd = w.cwd ?: return Result.failure(HubException(res.getString(R.string.waiting_no_folder)))
        if (!offline.sending(waitingId)) return Result.failure(HubException(res.getString(R.string.err_start)))
        val r = start(NewSessionRequest(projectPath = cwd, newProject = null, prompt = w.text, providerId = null, model = null, permissionMode = w.mode ?: "default", files = w.files))
        if (r.isSuccess) offline.sent(waitingId) else offline.failed(waitingId)
        return r
    }

    /** The request itself. [t] is trimmed; [clientId] names the send on the Mac (the offline queue's id for one that waited). */
    private suspend fun sendNow(sessionId: String, t: String, files: List<Attachment>, replace: Boolean, clientId: String): Result<String> {
        // With files the daemon puts @"path" mentions first, so Claude Code reads it as a prompt, not a command.
        val local = Item.User("local-${seq++}", t, status = SendStatus.Sending, files = files, command = if (files.isEmpty()) CommandRun.typed(t) else null, sentAt = now)
        // This send's name: the Mac's queue entry and its copy of the prompt carry it back.
        sentIds += clientId
        // Read before the POST: /clear or /resume can move the terminal to another session before it returns.
        val wasShared = raw[sessionId]?.term != null
        val p = pendingSlot(sessionId)
        val known = slot(sessionId).value
        // The Mac's time now, as far as its own clock has told us: what it last logged or did.
        val macAt = maxOf(known.maxOfOrNull { it.ts } ?: 0L, raw[sessionId]?.lastActivityAt ?: 0L)
        p.value = p.value + Sent(local, after = known.lastOrNull()?.id, stage = Stage.InFlight, macAt = macAt, clientId = clientId)
        fun drop() { p.value = p.value.filter { it.item.key != local.key } }
        var ok = false
        try {
            val body = JSONObject().put("text", t).put("clientId", clientId)
            if (files.isNotEmpty()) body.put("attachments", JSONArray(files.map { it.path }))
            if (replace) body.put("replace", true)
            // Fork: while Claude waits for an answer the Mac holds it rather than refusing it (data/Held.kt).
            else body.put("hold", true)
            val o = api.post("/v1/sessions/$sessionId/prompt", body)
            val s = applySession(o)
            ok = true
            // Held: the session's `held` shows it now, not this copy.
            if (o.bool("held")) drop()
            // Forked (a terminal session still open): the new session's events carry the prompt.
            else if (s != null && s.id != sessionId) drop() else {
                val cur = s ?: raw[sessionId]
                val typed = wasShared || cur?.term != null
                // An older daemon doesn't say; an SDK session with prompts waiting has queued this one too.
                val queued = if (o.has("queued")) o.bool("queued") else !typed && (cur?.queued ?: 0) > 0
                posted(sessionId, local.key, typed, queued)
            }
            return Result.success(s?.id ?: sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Unsent text in the session's terminal: the screen shows it and offers to replace it.
            val pending = (e as? ApiException)?.takeIf { it.code == "typing" }?.error?.str("pending")
            if (pending != null) return Result.failure(TerminalBusy(e.message ?: "", pending))
            // The conversation went on with the other agent (docs/PROTOCOL.md, "Switching agents"): the chat goes there.
            val moved = (e as? ApiException)?.takeIf { it.code == "switched" }?.error?.str("to")?.takeIf { it.isNotBlank() }
            if (moved != null) return Result.failure(SessionSwitched(e.message ?: res.getString(R.string.cx_went_on), moved))
            val ex = Net.classify(e)
            val err = handle(e, R.string.err_send)
            return Result.failure(SendFailed(err.message.orEmpty(), ex.status, ex.code, network = ex.problem == Problem.Unreachable || ex.problem == Problem.NoNetwork))
        } finally {
            // Failed or cancelled: the bubble goes (and the composer gets the text back).
            if (!ok) drop()
        }
    }

    override suspend fun upload(file: Upload): Result<Attachment> = try {
        val o = api.upload("/v1/attachments?name=${Api.q(file.name)}", file)
        Result.success(Wire.attachment(o.obj("attachment") ?: throw ApiException(Problem.Server, 200, "no_attachment")) ?: throw ApiException(Problem.Server, 200, "no_attachment"))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_upload)) }

    override fun interrupt(sessionId: String) {
        scope.launch {
            try { api.post("/v1/sessions/$sessionId/interrupt") } catch (e: CancellationException) { throw e } catch (e: Throwable) { toast(handle(e, R.string.err_interrupt).message.orEmpty()) }
        }
    }

    override suspend fun resume(sessionId: String): Result<String> = try {
        // A terminal session that's still open continues in a fork: {session, forked: true}.
        val s = applySession(api.post("/v1/sessions/$sessionId/continue"))
        // The daemon clears the parent's stoppedForAway; until its update arrives, hide Continue here.
        raw[sessionId]?.let { if (it.stoppedForAway) { raw = raw + (sessionId to it.copy(stoppedForAway = false)); publish() } }
        Result.success(s?.id ?: sessionId)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_continue)) }

    /** The providers' models as the Mac last listed them, and when; dropped when the providers change. */
    @Volatile private var modelCache: Pair<Long, List<ModelList>>? = null

    override suspend fun models(): Result<List<ModelList>> {
        modelCache?.let { (at, list) ->
            // A list with a part the Mac couldn't read is asked for again sooner.
            val fresh = if (list.any { it.error != null }) 60_000L else 10 * 60_000L
            if (System.currentTimeMillis() - at < fresh) return Result.success(list)
        }
        return try {
            val list = ModelCatalog.wire(api.get("/v1/models"))
            modelCache = System.currentTimeMillis() to list
            Result.success(list)
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            // An older Mac doesn't know the request: no list, and the sheet offers what it knows.
            if (Net.classify(e).status == 404) Result.success(emptyList()) else Result.failure(handle(e, R.string.err_models))
        }
    }

    override suspend fun searchHistory(query: String): Result<TextHits?> = try {
        Result.success(HistorySearch.wire(api.get("/v1/history/search?q=${Api.q(query.trim())}")))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) {
        // An older Mac doesn't know the request: History filters on the phone. Nothing is said either way.
        if (Net.classify(e).status == 404) Result.success(null) else Result.failure(e)
    }

    private val commandCache = HashMap<String, Pair<Long, List<SlashCommand>>>()

    override suspend fun commands(cwd: String?): Result<List<SlashCommand>> {
        val key = cwd.orEmpty()
        commandCache[key]?.let { (at, list) -> if (System.currentTimeMillis() - at < 5 * 60_000L) return Result.success(list) }
        return try {
            val list = Wire.commands(api.get("/v1/commands" + (if (cwd != null) "?project=${Api.q(cwd)}" else "")))
            commandCache[key] = System.currentTimeMillis() to list
            Result.success(list)
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_commands)) }
    }

    override suspend fun askAside(sessionId: String, question: String): Result<Aside> = try {
        val a = AsideWire.of(api.post("/v1/sessions/$sessionId/btw", JSONObject().put("question", question.trim())))
        if (a != null) Result.success(a) else Result.failure(HubException(res.getString(R.string.aside_err_ask)))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.aside_err_ask)) }

    override suspend fun aside(sessionId: String): Result<Aside?> = try {
        Result.success(AsideWire.of(api.get("/v1/sessions/$sessionId/btw")))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.aside_err_read)) }

    override suspend fun permissionRules(sessionId: String): Result<PermissionRules> = try {
        Result.success(PermissionRulesWire.of(api.get("/v1/sessions/$sessionId/permissions")))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.rules_err)) }

    override suspend fun answerMenu(sessionId: String, menuId: String, choice: Int?, alt: Boolean): Result<Unit> = try {
        // Whatever opened the menu has landed, logged or not (a picker closed with Esc isn't).
        menuAnswered[sessionId] = now
        settleLanded(sessionId)
        val body = JSONObject().put("menuId", menuId)
        if (choice == null) body.put("cancel", true) else body.put("choice", choice).put("alt", alt)
        api.post("/v1/sessions/$sessionId/menu", body)
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_generic)) }

    override suspend fun menuStep(sessionId: String, menuId: String, move: Int?, step: Int?): Result<Unit> = try {
        val body = JSONObject().put("menuId", menuId)
        if (step != null) body.put("step", step) else body.put("move", if ((move ?: 1) < 0) -1 else 1)
        api.post("/v1/sessions/$sessionId/menu", body)
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_generic)) }

    override suspend fun extendAwake(): Result<Unit> = try {
        val r = api.post("/v1/mac/awake", JSONObject().put("extend", true))
        MacAwake.parse(r.obj("awake"))?.let { macAwake = it }
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_awake)) }

    override suspend fun setBrowseOutsideProjects(on: Boolean): Result<Unit> = try {
        val r = api.post("/v1/mac/files", JSONObject().put("browseOutsideProjects", on))
        macBrowse = r.obj("files")?.bool("browseOutsideProjects") ?: on
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_browse_setting)) }

    override suspend fun setAutoUpdate(on: Boolean): Result<Unit> = try {
        val r = api.post("/v1/mac/update", JSONObject().put("autoUpdate", on))
        setServerUpdate(ServerUpdate.parse(r.obj("update")) ?: macUpdate?.copy(auto = on))
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_auto_update)) }

    override suspend fun setAwake(mode: String): Result<Unit> = try {
        val r = api.post("/v1/mac/awake", JSONObject().put("mode", mode))
        MacAwake.parse(r.obj("awake"))?.let { macAwake = it }
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_awake)) }

    override suspend fun setMode(sessionId: String, mode: String): Result<Boolean> = try {
        val r = api.post("/v1/sessions/$sessionId/mode", JSONObject().put("permissionMode", mode))
        applySession(r)
        Result.success(r.str("applies") == "next_turn")
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_generic)) }

    override suspend fun setModel(sessionId: String, model: String?, confirmPaid: Boolean): Result<ModelSwitch> = try {
        val body = JSONObject().put("model", model ?: JSONObject.NULL)
        if (confirmPaid) body.put("confirmPaid", true)
        val r = api.post("/v1/sessions/$sessionId/model", body)
        applySession(r)
        val applied = r.str("model")?.takeIf { it.isNotBlank() } ?: model
        // The chip follows at once; the Mac's broadcast says the same a moment later.
        if (applied != null) raw[sessionId]?.let { if (it.model != applied) { raw = raw + (sessionId to it.copy(model = applied)); publish() } }
        Result.success(ModelSwitch(applied, nextTurn = r.str("applies").let { it == "next_turn" || it == "on_resume" }))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) {
        val ex = Net.classify(e)
        when {
            ex.status == 409 && ex.code == "confirm_paid" -> Result.failure(PaidModel(ex.serverMessage ?: res.getString(R.string.model_paid_q)))
            // A Mac from before the call: a shared terminal still takes Claude Code's own command.
            ex.status == 404 && ex.code == "not_found" && raw[sessionId]?.term != null ->
                send(sessionId, "/model ${model ?: "default"}").map { ModelSwitch(model, nextTurn = false) }
            else -> Result.failure(handle(e, R.string.err_model))
        }
    }

    override suspend fun setEffort(sessionId: String, effort: String): Result<Boolean> = try {
        val r = api.post("/v1/sessions/$sessionId/effort", JSONObject().put("effort", effort))
        applySession(r)
        Result.success(r.str("applies") == "next_turn")
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_effort)) }

    override fun answer(sessionId: String, item: Item.Approval, answer: Answer) {
        answerAsync(item.approvalId ?: return, answer)
    }

    /**
     * [answer] by the approval's id. Completes with true once the Mac has the answer (or someone
     * answered first), false when it didn't get through: the home screen widget's Allow and Deny
     * wait for it.
     */
    fun answerAsync(id: String, answer: Answer): Deferred<Boolean> {
        val body = JSONObject()
        val shown: Resolution = when (answer) {
            Answer.Allow -> { body.put("decision", "allow"); Resolution.Allowed }
            Answer.Always -> { body.put("decision", "always"); Resolution.AllowedAlways }
            is Answer.AlwaysAs -> {
                body.put("decision", "always").put("scope", answer.scope.wire).put("rules", org.json.JSONArray(answer.rules))
                Resolution.AllowedAlways
            }
            Answer.Session -> { body.put("decision", "session"); Resolution.AllowedSession }
            Answer.Deny -> { body.put("decision", "deny"); Resolution.Denied }
            Answer.ApprovePlan -> { body.put("decision", "allow"); Resolution.PlanApproved }
            Answer.KeepPlanning -> { body.put("decision", "deny").put("message", res.getString(R.string.plan_keep_message)); Resolution.KeptPlanning }
            is Answer.Answers -> {
                body.put("decision", "allow").put("answers", JSONObject(answer.answers as Map<*, *>))
                Resolution.Answered
            }
        }
        answered = answered + (id to shown)
        return scope.async {
            try {
                api.post("/v1/approvals/$id", body)
                approvals = approvals - id
                publish()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val ex = Net.classify(e)
                if (ex.status == 409) {
                    // Someone else (the Mac, the terminal, the timeout) answered first:
                    // a neutral line until the Mac's own word on how arrives (it replaces this).
                    answered = answered - id
                    approvals = approvals - id
                    if (id !in resolved) resolved = resolved + (id to Resolved("elsewhere", null))
                    publish()
                    toast(res.getString(R.string.err_already_answered))
                    true
                } else {
                    answered = answered - id
                    toast(handle(e, R.string.err_answer).message.orEmpty())
                    false
                }
            }
        }
    }

    override suspend fun answerApproval(id: String, answer: Answer): Boolean = answerAsync(id, answer).await()

    override fun waitingAsks(sessionId: String): List<WApproval> =
        approvals.values.filter { it.sessionId == sessionId && it.id !in answered }.sortedBy { it.createdAt }

    override suspend fun allowSeveral(sessionId: String, ids: List<String>): Result<Int> {
        // Shown as allowed at once, as a single Allow is; put back if the Mac didn't get it.
        answered = answered + ids.associateWith { Resolution.Allowed }
        return try {
            val r = api.post("/v1/sessions/$sessionId/allow", JSONObject().put("ids", org.json.JSONArray(ids)))
            approvals = approvals - ids.toSet()
            publish()
            Result.success(r.optJSONArray("allowed")?.length() ?: ids.size)
        } catch (e: CancellationException) {
            answered = answered - ids.toSet()
            throw e
        } catch (e: Throwable) {
            answered = answered - ids.toSet()
            Result.failure(handle(e, R.string.err_answer))
        }
    }

    // ─── The Home card's menu (docs/PROTOCOL.md, "Session menu") ───

    override suspend fun renameSession(sessionId: String, name: String): Result<Unit> = try {
        applySession(api.post("/v1/sessions/$sessionId/rename", JSONObject().put("name", name.trim())))
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_rename)) }

    override suspend fun stopSession(sessionId: String): Result<Unit> = try {
        applySession(api.post("/v1/sessions/$sessionId/stop"))
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_stop_session)) }

    override suspend fun respawnSession(sessionId: String): Result<Unit> = try {
        applySession(api.post("/v1/sessions/$sessionId/respawn"))
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_restart_session)) }

    // One after another on the Mac, each up to half a minute: the slow client's two minutes. The
    // sessions' new state comes over the socket.
    override suspend fun respawnOutdated(sessionIds: List<String>): Result<List<JobRestart>> = try {
        Result.success(OutdatedJobs.results(api.postSlow("/v1/jobs/respawn", JSONObject().put("ids", org.json.JSONArray(sessionIds)))))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_restart_jobs)) }

    override suspend fun deleteSession(sessionId: String): Result<Unit> = try {
        api.delete("/v1/sessions/$sessionId")
        // The Mac sends session.removed as well; the card goes now, not when that arrives.
        raw = raw - sessionId
        publish()
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_delete_session)) }

    override suspend fun start(req: NewSessionRequest): Result<String> = try {
        val body = JSONObject().put("prompt", req.prompt.trim()).put("permissionMode", req.permissionMode)
        if (req.newProject != null) body.put("newProject", JSONObject().put("name", req.newProject.trim()).put("git", true))
        else body.put("projectPath", req.projectPath)
        // Codex (data/Codex.kt): its own model and effort; the providers are Claude Code's.
        if (req.agent == AgentKind.CODEX) {
            body.put("agent", AgentKind.CODEX)
            req.effort?.let { body.put("effort", it) }
        } else {
            req.providerId?.let { body.put("providerId", it) }
            // Claude Code's effort (`--effort`); none: its own default.
            req.effort?.let { body.put("effort", it) }
        }
        req.model?.let { body.put("model", it) }
        if (req.files.isNotEmpty()) body.put("attachments", JSONArray(req.files.map { it.path }))
        val s = applySession(api.post("/v1/sessions", body)) ?: throw ApiException(Problem.Server, 200, "no_session")
        Result.success(s.id)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_start)) }

    override suspend fun terminal(sessionId: String): Result<TermTicket> = try {
        val o = api.post("/v1/sessions/$sessionId/terminal")
        // The socket follows the active route like everything else. On the local route the app
        // opens it with the pinned client and the device certificate (the terminal page's own
        // WebSocket can do neither), so the ticket carries that client (TermTicket).
        Result.success(TermTicket.of(o.getString("url"), api.route()))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_terminal)) }

    override suspend fun openOnMac(sessionId: String): Result<Pair<String, Boolean>> = try {
        val o = api.post("/v1/sessions/$sessionId/open")
        Result.success((o.str("app") ?: "Terminal") to o.bool("already"))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_open_on_mac)) }

    // ─── Codex, a second agent (docs/PROTOCOL.md, "Codex (a second agent)"; data/Codex.kt) ───

    /** Codex as the Mac has it now: turned on or off, its account, its windows. */
    private fun codexChanged(c: CodexState?) {
        // Turned on, or installed: its models are asked for again.
        if (c?.enabled != codex?.enabled || c?.available != codex?.available) codexModelCache = null
        codex = c
    }

    override suspend fun setClaudeAgents(on: Boolean): Result<ClaudeState?> = try {
        val c = ClaudeState.parse(api.post("/v1/claude", JSONObject().put("agents", on)).obj("claude"))
        if (c != null) claude = c
        Result.success(c)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.ag_claude_agents_err)) }

    override suspend fun setCodexEnabled(on: Boolean): Result<CodexState?> = try {
        val c = CodexWire.state(api.post("/v1/codex", JSONObject().put("enabled", on)).obj("codex"))
        codexChanged(c)
        Result.success(c)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, if (on) R.string.cx_err_turn_on else R.string.cx_err_turn_off)) }

    /** Codex's models as the Mac last listed them, and when; dropped when Codex turns on or off. */
    @Volatile private var codexModelCache: Pair<Long, List<CodexModel>>? = null

    override suspend fun codexModels(): Result<List<CodexModel>> {
        codexModelCache?.let { (at, list) -> if (System.currentTimeMillis() - at < 10 * 60_000L) return Result.success(list) }
        return try {
            val list = CodexWire.models(api.get("/v1/codex/models"))
            codexModelCache = System.currentTimeMillis() to list
            Result.success(list)
        } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.cx_err_models)) }
    }

    override suspend fun setCodexModel(sessionId: String, model: String?, effort: String?): Result<ModelSwitch> = try {
        val body = JSONObject().put("model", model ?: JSONObject.NULL)
        effort?.let { body.put("effort", it) }
        val r = api.post("/v1/sessions/$sessionId/model", body)
        applySession(r)
        val applied = r.str("model")?.takeIf { it.isNotBlank() } ?: model
        // The chip follows at once; the Mac's broadcast says the same a moment later.
        if (applied != null) raw[sessionId]?.let { if (it.model != applied) { raw = raw + (sessionId to it.copy(model = applied)); publish() } }
        Result.success(ModelSwitch(applied, nextTurn = r.str("applies") == "next_turn"))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_model)) }

    override suspend fun switchPreview(sessionId: String, to: String, context: String): Result<SwitchPreview> = try {
        Result.success(CodexWire.preview(api.post("/v1/sessions/$sessionId/switch/preview", JSONObject().put("to", to).put("context", context))))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.cx_err_preview)) }

    override suspend fun switchAgent(sessionId: String, to: String, text: String, model: String?, effort: String?, mode: String?): Result<String> = try {
        val body = JSONObject().put("to", to).put("text", text)
        model?.let { body.put("model", it) }
        effort?.let { body.put("effort", it) }
        mode?.let { body.put("permissionMode", it) }
        val s = applySession(api.post("/v1/sessions/$sessionId/switch", body)) ?: throw ApiException(Problem.Server, 200, "no_session")
        Result.success(s.id)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) {
        val ex = Net.classify(e)
        val err = handle(e, R.string.cx_err_switch)
        // What the Mac said no to, and where it points (the session it went on in, the most it takes).
        if (ex.problem == Problem.Server && ex.code != null) Result.failure(SwitchRefused(err.message.orEmpty(), ex.code, ex.error?.str("to"), ex.error?.int("maxBytes")))
        else Result.failure(err)
    }

    // ─── Account ───────────────────────────────────────────────────────────

    /** An answer that carries the account: apply it now rather than wait for the socket's copy. */
    private fun accountFrom(o: JSONObject) {
        o.obj("account")?.let { runCatching { Wire.account(it) }.getOrNull() }?.let { setAccount(it, live = true) }
    }

    override suspend fun checkAccount(): Result<Unit> = try {
        accountFrom(api.post("/v1/account/check"))
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_check_account)) }

    override suspend fun signIn(onMac: Boolean): Result<Unit> = try {
        accountFrom(api.post("/v1/account/login", JSONObject().put("where", if (onMac) "mac" else "phone")))
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        // Signed in meanwhile (on the Mac, say): look again rather than show an error.
        if ((e as? ApiException)?.code == "not_needed") { checkAccount(); Result.success(Unit) }
        else Result.failure(handle(e, R.string.err_sign_in))
    }

    override suspend fun sendSignInCode(code: String): Result<Unit> = try {
        api.post("/v1/account/login/code", JSONObject().put("code", code.trim()))
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_sign_in_code)) }

    override suspend fun cancelSignIn(): Result<Unit> = try {
        api.delete("/v1/account/login")
        // The Mac broadcasts the account without its sign-in; until then, take it off here.
        account.login?.let { setAccount(account.copy(login = null), live = true) }
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_generic)) }

    override suspend fun switchProvider(sessionId: String, providerId: String): Result<String> = try {
        val s = applySession(api.post("/v1/sessions/$sessionId/provider", JSONObject().put("providerId", providerId)))
        Result.success(s?.id ?: sessionId)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_switch_provider)) }

    // ─── A session's blocked calls (data/Blocked.kt) ─────────────────────

    override suspend fun blocked(sessionId: String): Result<List<BlockedCall>> = try {
        Result.success(BlockedWire.list(api.get("/v1/sessions/$sessionId/blocked")))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.blocked_err_load)) }

    override suspend fun retryBlocked(sessionId: String, id: String): Result<List<BlockedCall>> = try {
        val r = api.post("/v1/sessions/$sessionId/blocked/$id/retry")
        applySession(r)
        Result.success(BlockedWire.list(r))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.blocked_err_retry)) }

    override suspend fun dismissBlocked(sessionId: String, id: String): Result<List<BlockedCall>> = try {
        Result.success(BlockedWire.list(api.delete("/v1/sessions/$sessionId/blocked/$id")))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.blocked_err_dismiss)) }

    // ─── Carrying on after a limit, other providers (data/Providers.kt) ───

    override suspend fun autoContinue(sessionId: String, on: Boolean): Result<Unit> = try {
        applySession(api.post("/v1/sessions/$sessionId/auto-continue", JSONObject().put("on", on)))
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.resume_failed)) }

    override suspend fun continueAfterLimit(sessionId: String): Result<String> = try {
        val s = applySession(api.post("/v1/sessions/$sessionId/continue", JSONObject().put("reason", "limit")))
        Result.success(s?.id ?: sessionId)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_continue)) }

    override suspend fun checkProvider(id: String): Result<Provider> = try {
        val p = Wire.provider(api.post("/v1/providers/$id/check").obj("provider") ?: throw ApiException(Problem.Server, 200, "no_provider"))
        providers = sortProviders(providers.filter { it.id != p.id } + p)
        publish()
        Result.success(p)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_generic)) }

    /** A provider's new trouble, or its credit back: a banner or a notification, once per episode. */
    private fun providerNotices(before: List<Provider>) {
        for (p in providers) {
            val paused = raw.values.count { it.providerId == p.id && it.blocked != null }
            ProviderWords.notice(app, before.firstOrNull { it.id == p.id }, p, paused)?.let { notice(it) }
        }
    }

    override suspend fun files(path: String): Result<Listing> = try {
        val o = api.get("/v1/files?path=${Api.q(path)}")
        val entries = o.arr("entries").mapObjects(Wire::entry)
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
        Result.success(Listing(o.str("path") ?: path, entries, o.optBoolean("truncated"), o.optInt("total", entries.size)))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handleFile(e, R.string.err_files)) }

    override suspend fun folders(path: String?, hidden: Boolean): Result<Folders> = try {
        val q = listOfNotNull(path?.let { "path=${Api.q(it)}" }, "hidden=1".takeIf { hidden }).joinToString("&")
        Result.success(FolderPick.wire(api.get("/v1/folders" + if (q.isEmpty()) "" else "?$q")))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handleFile(e, R.string.err_files)) }

    override suspend fun file(path: String): Result<FileBytes> = try {
        // Attachments can be up to 30 MB.
        Result.success(api.bytes("/v1/file?path=${Api.q(path)}", max = 32L * 1024 * 1024))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handleFile(e, R.string.err_file)) }

    override suspend fun download(source: String, target: java.io.File, resume: DownloadResume?, onStart: (String?) -> Unit, onProgress: (Long, Long) -> Unit): Result<Downloaded> = try {
        val from = if (source.startsWith("http")) source else "/v1/file?path=${Api.q(source)}&download=1"
        Result.success(api.download(from, target, resume, onStart, onProgress))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handleFile(e, R.string.err_download)) }

    override suspend fun preview(path: String?, port: Int?): Result<String> = try {
        val body = JSONObject()
        if (path != null) body.put("path", path)
        if (port != null) body.put("port", port)
        val url = api.post("/v1/preview", body).str("url") ?: throw ApiException(Problem.Server, 200, "no_url")
        Result.success(page(url))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_preview)) }

    override suspend fun saveProvider(id: String?, input: ProviderInput): Result<ProviderSaved> = try {
        val o = if (id == null) api.post("/v1/providers", input.toJson()) else api.put("/v1/providers/$id", input.toJson())
        val p = Wire.provider(o.obj("provider") ?: throw ApiException(Problem.Server, 200, "no_provider"))
        providers = sortProviders(providers.filter { it.id != p.id } + p)
        Result.success(ProviderSaved(p, ProviderWire.check(o.obj("check")), o.bool("keyDropped")))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handleProvider(e, R.string.err_provider_save)) }

    override suspend fun checkNewProvider(kind: String, baseUrl: String?, apiKey: String?, id: String?): Result<ProviderCheck> = try {
        val body = JSONObject().put("kind", kind)
        baseUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("baseUrl", it) }
        apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { body.put("apiKey", it) }
        id?.let { body.put("id", it) }
        val c = ProviderWire.check(api.post("/v1/providers/check", body).obj("check")) ?: throw ApiException(Problem.Server, 200, "no_check")
        Result.success(c)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handleProvider(e, R.string.err_provider_check)) }

    override suspend fun deleteProvider(id: String): Result<Unit> = try {
        api.delete("/v1/providers/$id")
        providers = providers.filter { it.id != id }
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_provider_delete)) }

    /** Like [handle], but a refusal keeps the Mac's code, so the provider editor puts it under its field. */
    private fun handleProvider(e: Throwable, fallback: Int): HubException {
        val ex = Net.classify(e)
        if (ex.problem != Problem.Server) return handle(e, fallback)
        return ProviderRefused(ex.code, ex.serverMessage?.takeIf { it.isNotBlank() } ?: res.getString(fallback))
    }

    override suspend fun agentSettings(): Result<AgentSettingsReply> = try {
        Result.success(AgentSettingsReply.wire(api.get("/v1/agents/settings")))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) {
        // An older server doesn't know the request: it's the server that needs updating, not the phone trying again.
        Result.failure(if (Net.classify(e).status == 404) AgentSettingsTooOld(res.getString(R.string.as_too_old)) else handle(e, R.string.as_err))
    }

    override suspend fun checklist(): Result<String> = try {
        val url = api.get("/v1/qa").str("url") ?: throw ApiException(Problem.Server, 200, "no_url")
        Result.success(page(url))
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(handle(e, R.string.err_checklist)) }

    /** `DELETE /v1/devices/me`: the Mac forgets this phone. */
    suspend fun forget(): Result<Unit> = try {
        api.delete("/v1/devices/me")
        Result.success(Unit)
    } catch (e: CancellationException) { throw e } catch (e: Throwable) { Result.failure(Net.classify(e)) }

    /**
     * A page the Mac handed out (a preview, the checklist; relative, or on either of its origins) on
     * the route active now: the in-app WebView carries the local route's pin and device certificate
     * (ui/web/WebScreen.kt), and its "Open in browser" goes by the base URL.
     */
    private fun page(url: String): String = RoutePolicy.rebase(url, api.url(""))

    /** A failure becomes a [HubException] with a human message; a 401 also unpairs. */
    private fun handle(e: Throwable, fallback: Int): HubException {
        val ex = Net.classify(e)
        if (ex.problem == Problem.Unauthorized) unpaired()
        return HubException(message(ex, fallback))
    }

    /** Like [handle], but keeps what the Mac said (status, code, sizes) for the files screens to explain. */
    private fun handleFile(e: Throwable, fallback: Int): FileFailure {
        val ex = Net.classify(e)
        if (ex.problem == Problem.Unauthorized) unpaired()
        return FileFailure.of(ex, message(ex, fallback)).let { if (ex.problem != Problem.Server && !hasNetwork()) FileFailure(it.message.orEmpty(), Problem.NoNetwork, ex.status, ex.code) else it }
    }

    private fun message(ex: ApiException, fallback: Int): String = when (ex.problem) {
        Problem.Server -> ex.serverMessage?.takeIf { it.isNotBlank() } ?: res.getString(fallback)
        else -> problemText(res, if (!hasNetwork()) Problem.NoNetwork else ex.problem)
    }

    // ─── Usage ─────────────────────────────────────────────────────────────

    /**
     * The sun follows the official 5-hour numbers. A window whose reset time has passed is
     * fresh again (0%) until Claude Code reports new numbers, and the limit lifts on time
     * even if nothing runs to report it. A 5-hour or weekly limit Claude Code actually hit counts
     * whatever the status line last said (it can lag behind), so the sun dies when Claude stops;
     * not one Claude runs past on usage credits. Signed out, the numbers are "stale": the last
     * ones known, not measured, and the sun is eclipsed instead (see the home screen).
     */
    private fun recomputeUsage() {
        val w = wusage
        val t = now
        val official = w != null && w.source == "official"
        val f5 = w?.fiveHour?.takeIf { official }
        val f7 = w?.sevenDay?.takeIf { official }
        val over5 = f5 != null && f5.resetsAt in 1..t
        val over7 = f7 != null && f7.resetsAt in 1..t
        // A limit whose reset has passed is over, before the Mac says so: the card, the sessions'
        // lines and the permanent notification move on at the reset, not at the next message.
        val a = account
        if (a.state == Account.LIMITED && a.limit?.resetsAt?.let { it <= t } == true) {
            account = Account.Ok.copy(plan = a.plan, checkedAt = a.checkedAt)
            if (raw.values.any { it.blocked != null }) publish()
        }
        val hit = account.resting(t)?.takeIf { !it.usingCredits && it.resetsAt != null }
        val hit5 = hit?.takeIf { it.window == "five_hour" }?.resetsAt
        val hit7 = hit?.takeIf { it.window == "seven_day" }?.resetsAt
        val p5 = if (hit5 != null) 1f else if (f5 == null || over5) 0f else (f5.pct / 100.0).toFloat().coerceIn(0f, 1f)
        val p7 = if (hit7 != null) 1f else if (f7 == null || over7) 0f else (f7.pct / 100.0).toFloat().coerceIn(0f, 1f)
        val r5 = hit5 ?: if (f5 != null && !over5 && f5.resetsAt > 0) f5.resetsAt else t + 5 * HOUR
        val r7 = hit7 ?: if (f7 != null && !over7 && f7.resetsAt > 0) f7.resetsAt else t + 7 * DAY
        val prev = usage
        val lim5 = p5 >= 0.999f
        val lim7 = p7 >= 0.999f
        val since = account.since?.takeIf { hit != null }
        // The week and no 5-hour window (Max, 2026-10-31): nothing labelled 5H, the sun on the week.
        // The Mac's own windows decide, signed out too (its last ones), not a list of plans.
        val reported = UsageProjection.fiveHourReported(w?.fiveHour, w?.sevenDay, hit5 != null)
        val read = Usage(
            fiveHour = p5, sevenDay = p7, fiveHourResetAt = r5, sevenDayResetAt = r7,
            limitedAt = if (lim5 && reported) prev.limitedAt ?: since ?: w?.observedAt ?: t else null,
            weekLimitedAt = if (lim7) prev.weekLimitedAt ?: since ?: w?.observedAt ?: t else null,
            known = if (reported) (f5 != null && !over5) || hit5 != null else (f7 != null && !over7) || hit7 != null,
            stale = w?.source == "stale",
            models = ModelWeeks.of(w?.models.orEmpty(), account, t),
            rate = w?.rate.takeIf { official },
        )
        usage = if (reported) read else UsageProjection.weekAsDisk(read)
        // Wake up at the next reset so the disk ignites on time (and the account's limit ends with it).
        // In short steps against the wall clock: a delay doesn't count while the phone sleeps, so one
        // long delay of hours went off long after the reset, and the sun sat at "93% · 0:00".
        usageTimer?.cancel()
        val next = (listOfNotNull(f5?.resetsAt?.takeIf { it > t }, f7?.resetsAt?.takeIf { it > t }, account.limit?.resetsAt?.takeIf { it > t }) +
            w?.models.orEmpty().mapNotNull { it.resetsAt?.takeIf { r -> r > t } }).minOrNull()
        if (next != null) usageTimer = scope.launch {
            while (now < next + 500) delay((next + 500 - now).coerceAtMost(10_000L))
            recomputeUsage()
        }
    }

    /**
     * A prompt this phone sent, shown until the Mac's copy of it arrives; [stage] is how far it
     * has got. [after] is the event it follows in the transcript: the one that was last when it
     * went, or the Mac's copy of a prompt sent before it (which it can't come before). [macAt] is
     * the Mac's time when it went, as near as this phone can tell (the newest event or activity
     * then): the phone's own clock may be minutes off the Mac's. [typed]: it went into the
     * session's shared terminal rather than an SDK run, as the session was when the request came
     * back (null until then); /clear and /resume move the terminal to another session soon after.
     * [clientId] is this send's name on the Mac. [seen]: queued, and the session's queue has
     * shown it (so not showing it any more means it left).
     */
    private data class Sent(
        val item: Item.User,
        val after: String?,
        val stage: Stage,
        val macAt: Long,
        val typed: Boolean? = null,
        val clientId: String? = null,
        val seen: Boolean = false,
    ) {
        fun at(s: Stage) = copy(
            stage = s,
            item = item.copy(
                status = when (s) {
                    Stage.InFlight, Stage.Posted -> SendStatus.Sending
                    Stage.Queued -> if (typed == false) SendStatus.QueuedReply else SendStatus.QueuedStep
                    Stage.Settled -> null
                },
            ),
        )
    }

    private enum class Stage {
        /** The request hasn't come back yet. */
        InFlight,
        /** Typed into its terminal (or run by its SDK session): the Mac should log it any moment. */
        Posted,
        /**
         * Claude was working, so it waits in a queue until Claude can take it: Claude Code's
         * (until the step Claude is on finishes) or an SDK session's (until the reply ends). Drawn
         * last, under Claude's live output, as the terminal draws its queue.
         */
        Queued,
        /**
         * Typed into a terminal that didn't log it in time, or showed a menu or a warning for it,
         * or left the queue unlogged: shown as sent. The Mac may still log it late (Claude was busy).
         */
        Settled,
    }

    companion object {
        /** Events a session's history loads at first, and more each "Show earlier". */
        /** Held prompts that the Mac couldn't take yet are tried again after this, a few times. */
        private const val RETRY_HELD = 20_000L
        private const val FLUSH_ROUNDS = 4
        private const val EVENTS_PAGE = 200
        /** A tool's output in a loaded history: its last 8 KB (PROTOCOL: `output=`). */
        private const val EVENT_OUTPUT = 8192
        private const val HOUR = 3_600_000L
        private const val DAY = 24 * HOUR
        /** The screen coming on retries at once only this long after the last try ([Retry.EVERY]). */
        private const val RETRY_CAP_ON_SCREEN = Retry.EVERY
        /** How long a try that has just begun is left alone by the network or the app (see [freshTry]). */
        private const val FRESH_TRY = 3_000L
        private const val ALARM_TAG = "hypernova:retry"
        /** An attempt's error code when no route answered the round ([LanRouter.prepare]): nothing was asked of the base URL. */
        private const val NO_ROUTE = "no_route"
        /** How long Local is tried after the others while the Mac won't take this phone's device certificate there. */
        private const val CERT_HOLD = 60_000L
        /** The kinds of network whose change is worth a try (see [NetWatch]). */
        private val TRANSPORTS = intArrayOf(
            NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET,
        )
        /** How long a typed prompt may take to reach the transcript before its copy settles. */
        private const val SETTLE = 8_000L
        /** How long a queued copy waits for the Mac's copy once its prompt has left the queue. */
        private const val GRACE = 15_000L
        /** How long one the queue never showed waits once the session has stopped working (a turn ends before the next starts). */
        private const val IDLE_GRACE = 3_000L
        /** How much of a queued prompt's text the session's queue carries. */
        private const val QUEUE_TEXT = 300
        /**
         * How long an agent's approval waits before it notifies (the owner's call, item 23): agents
         * often settle their own, and its card is in the session at once anyway.
         */
        private const val AGENT_ASK_DELAY = 10_000L
        /** How far back a queued prompt's own event may be, among the last events. */
        private const val DELIVERED_LOOKBACK = 20
        /** Fork: how long a held prompt the Mac just typed in waits, as this phone's copy, for the Mac's own. */
        private const val HELD_LINGER = 15_000L
        /** How much earlier than its queue entry Claude Code may stamp a queued prompt's own entry. */
        private const val DELIVERED_SLACK = 5_000L
        /**
         * How long after it went (by the Mac's clock) a settled copy's prompt may still be logged
         * behind other prompts. Past that, the same command typed on the Mac is a new one.
         */
        private const val LATE = 3 * 60_000L
        private val SPACES = Regex("\\s+")
        /** The Mac's battery, unplugged, at which the owner hears about it once ([setBattery]). */
        private const val BATTERY_WARN = 20
        /** Back above this (or on the charger), the next drop to [BATTERY_WARN]% is said again. */
        private const val BATTERY_REARM = 25
        private const val BATTERY_WARNED = "macBatteryWarned"
    }
}

private val WHITESPACE = Regex("\\s+")

/**
 * Whether the Mac logged [theirs] as the prompt this phone sent as [mine]: the same text with
 * whitespace not counting at all. The Mac's copy can gain or lose a space on the way: a hook of
 * Claude Code's that rewrites prompts (a spellcheck plugin made "clip board" "clipboard"), or bash
 * mode, which logs "! ls" as "!ls".
 */
internal fun sameSentText(mine: String, theirs: String): Boolean = mine.replace(WHITESPACE, "") == theirs.replace(WHITESPACE, "")

/** `{t:"event.removed", sessionId, id}`: the session and the event the Mac took back; null when either is missing. */
internal fun removedEvent(o: JSONObject): Pair<String, String>? {
    val sid = o.str("sessionId")?.takeIf { it.isNotBlank() } ?: return null
    val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
    return sid to id
}

/** [list] without the events the Mac took back ([gone], by id): a notice that no longer holds. */
internal fun withoutEvents(list: List<WEvent>, gone: Collection<String>): List<WEvent> =
    if (gone.isEmpty() || list.none { it.id in gone }) list else list.filterNot { it.id in gone }

/**
 * The offline card's sentence for [link]: what the last round tried when nothing answered ("Your
 * Mac doesn't answer on this Wi‑Fi"), the route that failed in use when one answered and then
 * failed, else the problem's own words.
 */
fun linkProblemText(res: android.content.res.Resources, link: Link): String {
    val p = link.problem
    if (p == Problem.NoNetwork || p == Problem.Unauthorized || p == Problem.Server || p == Problem.Cleartext) return problemText(res, p)
    when (RouteWords.problem(link.tried)) {
        RouteProblem.LocalOnly -> return res.getString(R.string.problem_route_local)
        RouteProblem.WrongMac -> return res.getString(R.string.problem_route_wrong_mac)
        null -> Unit
    }
    return when (link.failedOver) {
        RouteKind.Local -> res.getString(R.string.problem_route_local)
        RouteKind.Remote -> problemWords(res, p)
        null -> problemText(res, p)
    }
}

/** One sentence per problem, with what to do about it. */
fun problemText(res: android.content.res.Resources, p: Problem?): String = problemWords(res, p)

private fun problemWords(res: android.content.res.Resources, p: Problem?): String = when (p) {
    Problem.NoNetwork -> res.getString(R.string.problem_no_network)
    Problem.NoName -> res.getString(R.string.problem_no_name)
    Problem.Unreachable, null -> res.getString(R.string.problem_unreachable)
    Problem.NotRunning -> res.getString(R.string.problem_not_running)
    Problem.Tls -> res.getString(R.string.problem_tls)
    Problem.Cleartext -> res.getString(R.string.problem_cleartext)
    Problem.Unauthorized -> res.getString(R.string.problem_unpaired)
    Problem.CodeExpired -> res.getString(R.string.problem_code_expired)
    Problem.Server -> res.getString(R.string.problem_server)
}
