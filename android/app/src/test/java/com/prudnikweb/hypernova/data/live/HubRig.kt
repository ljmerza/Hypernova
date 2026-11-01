package com.prudnikweb.hypernova.data.live

import android.app.AlarmManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.LiveHub
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.Servers
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.WApproval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import org.junit.Assert.fail
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The real [LiveHub] against a [FakeDaemon], on Robolectric's Android (a Context, preferences,
 * the connectivity and alarm services) and a test dispatcher: the hub's own coroutines run only when
 * the test [pump]s, and its delays (the 30 s retry, the watchdog) wait for [advance]. Requests and
 * the socket are real (OkHttp to MockWebServer on loopback), so [until] waits for them in real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HubRig(
    val daemon: FakeDaemon,
    val serverId: String = Servers.LEGACY_ID,
    token: String = "tok-${daemon.name}",
    val deviceId: String? = "dev-1",
    /** Shared by several rigs (several computers), so pumping one runs them all. */
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
    /** Paired as the pairing link names a [FakeDaemon.lan] listener: its fingerprint, 127.0.0.1 and its port. */
    viaLan: Boolean = daemon.lan,
    /** The base URL is that listener, as a pairing link's `u` has been since 2026-10-30 (else the fake's plain-HTTP address, the emulator's kind). */
    baseIsListener: Boolean = viaLan,
) : AutoCloseable {
    val context: Context = RuntimeEnvironment.getApplication().also {
        // A fresh install's words (a Mac's): ComputerWords is process-wide and outlives a test.
        ComputerWords.clear(it)
    }
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val pairing = Pairing(baseUrl = if (baseIsListener) "https://127.0.0.1:${daemon.lanPort}" else daemon.base, token = token, macName = daemon.name, macId = "m-${daemon.name}", deviceId = deviceId).let {
        if (viaLan) it.copy(fingerprint = daemon.fingerprint, lanAddresses = listOf("127.0.0.1"), lanPort = daemon.lanPort) else it
    }
    private val startedAt = System.currentTimeMillis()
    /** The hub's wall clock: real time at the start, then moving with [advance] only. */
    val now: Long get() = startedAt + dispatcher.scheduler.currentTime

    /** The app on screen and showing this computer. */
    @Volatile var visible = true
    /** The background service holds the link open. */
    @Volatile var heldOpen = true

    val toasts = mutableListOf<String>()
    /** Approvals the hub asked to notify (off screen). */
    val notified = mutableListOf<Pair<WApproval, Session?>>()
    /** Approvals whose notification can go. */
    val gone = mutableListOf<String>()
    /** Notices as system notifications (off screen). */
    val notices = mutableListOf<Notice>()
    /** Notices as banners (on screen): [LiveHub.notices]. */
    val banners = mutableListOf<Notice>()

    val hub = LiveHub(
        context, scope, pairing,
        toast = { toasts += it },
        onApproval = { a, s -> notified += a to s },
        onApprovalGone = { gone += it },
        isVisible = { visible },
        onNotice = { notices += it },
        heldOpen = { heldOpen },
        serverId = serverId,
        onScreen = { visible },
        clock = { now },
    )

    init {
        online(true)
        scope.launch { hub.notices.collect { banners += it } }
        pump()
    }

    val connectivity: ConnectivityManager get() = context.getSystemService(ConnectivityManager::class.java)
    val alarms: AlarmManager get() = context.getSystemService(AlarmManager::class.java)

    /** The phone has a network (with the internet) or none. */
    fun online(on: Boolean) {
        val cm = connectivity
        val net = cm.activeNetwork ?: return
        shadowOf(cm).setNetworkCapabilities(net, if (on) wifi() else null)
    }

    /** A validated Wi-Fi network's capabilities. */
    fun wifi(): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        val s = shadowOf(it)
        s.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        s.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        s.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Runs what is due now: the hub's coroutines and the main looper (broadcasts, ConnLog). */
    fun pump() {
        dispatcher.scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        dispatcher.scheduler.runCurrent()
    }

    /** Waits (in real time: requests and the socket are real) until [cond] holds, pumping meanwhile. */
    fun until(what: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            pump()
            if (cond()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (link ${hub.link})")
            Thread.sleep(3)
        }
    }

    /** Lets [ms] pass on every clock the hub reads: its delays, SystemClock and its wall clock ([now]). */
    fun advance(ms: Long) {
        ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
        dispatcher.scheduler.advanceTimeBy(ms)
        pump()
    }

    /** Starts the hub and waits for the socket to open. */
    fun connect(): HubRig {
        val before = daemon.opened.get()
        hub.start()
        until("the socket") { hub.link.state == LinkState.Connected && daemon.opened.get() > before }
        return this
    }

    /** Pushes [msg] and waits until [cond] holds. */
    fun push(msg: org.json.JSONObject, what: String = msg.optString("t"), cond: () -> Boolean) {
        daemon.push(msg)
        until(what, cond = cond)
    }

    /**
     * Waits until the hub has handled everything pushed so far: a ping goes after it, and the hub
     * handles messages in order, so its pong says the rest are done.
     */
    fun sync() {
        val pongs = daemon.received.count { it.contains("\"pong\"") }
        daemon.push(org.json.JSONObject().put("t", "ping"))
        until("the pong") { daemon.received.count { it.contains("\"pong\"") } > pongs }
    }

    /** Runs a suspending call of the hub's to its end, pumping while its request is out. */
    fun <T> call(what: String = "the call", block: suspend CoroutineScope.() -> T): T {
        val d: Deferred<T> = scope.async(block = block)
        until(what) { d.isCompleted }
        @Suppress("OPT_IN_USAGE")
        return d.getCompleted()
    }

    /** The scheduled retry's alarm, if one is set. */
    fun retryAlarm() = shadowOf(alarms).scheduledAlarms.firstOrNull { it.onAlarmListener != null }

    override fun close() {
        runCatching { hub.close() }
        pump()
        scope.cancel()
        daemon.close()
    }
}
