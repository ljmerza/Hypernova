package com.prudnikweb.hypernova.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prudnikweb.hypernova.BuildConfig
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/*
 * Tailscale built in (docs/PROTOCOL.md, "Tailscale built in"): the app carries its own Tailscale
 * node (android/tsnet, Go, bound with gomobile) in userspace, with no VpnService, so the user's own
 * VPN keeps working and the phone takes no Tailscale app. The user signs in once, in the browser,
 * with the link the node hands out; the node then dials the paired computer's tailnet name through
 * a small CONNECT proxy on loopback, which OkHttp uses for the built-in route (data/LanRoute.kt).
 */

/** What the node says about itself (android/tsnet/node.go `status`). */
@Immutable
data class TsnetStatus(
    /** Stopped, Starting, NeedsLogin, NeedsMachineAuth, Running, NoState (as Tailscale names them). */
    val state: String,
    val loginUrl: String? = null,
    val dnsName: String? = null,
    val ips: List<String> = emptyList(),
    /** The account the node signed in with ("you@example.com"). */
    val account: String? = null,
    val tailnet: String? = null,
    /** The loopback proxy's port and the secret it takes, once the node runs. */
    val proxyPort: Int = 0,
    val proxyToken: String = "",
    val error: String? = null,
) {
    val running: Boolean get() = state == "Running" && proxyPort > 0 && proxyToken.isNotEmpty()
    val needsLogin: Boolean get() = state == "NeedsLogin"
    /** Signed in, but the tailnet approves new devices and this one isn't yet (device approval). */
    val needsApproval: Boolean get() = state == "NeedsMachineAuth"

    companion object {
        val STOPPED = TsnetStatus("Stopped")
        fun parse(json: String): TsnetStatus = runCatching {
            val o = JSONObject(json)
            TsnetStatus(
                state = o.str("state") ?: "Stopped",
                loginUrl = o.str("loginUrl"),
                dnsName = o.str("dnsName"),
                ips = o.arr("ips").strings(),
                account = o.str("account"),
                tailnet = o.str("tailnet"),
                proxyPort = o.int("proxyPort") ?: 0,
                proxyToken = o.str("proxyToken") ?: "",
                error = o.str("error"),
            )
        }.getOrDefault(STOPPED)
    }
}

/**
 * The embedded node, one per process. The Go side is reached through the generated
 * `com.prudnikweb.hypernova.go.tsnet.Tsnet` class (app/libs/tsnet.aar, which the build requires),
 * by reflection so that the Kotlin here compiles and tests run on a JVM without the library; a
 * build where it failed to load says "not available" and keeps the Local and plain routes.
 */
object Tsnet {
    private const val CLASS = "com.prudnikweb.hypernova.go.tsnet.Tsnet"
    private const val LISTENER = "com.prudnikweb.hypernova.go.tsnet.Listener"
    /** The node's state, for the screens (Compose state) and the route chooser. */
    var status by mutableStateOf(TsnetStatus.STOPPED)
        private set
    /**
     * The sign-in link last opened in the browser: while the node still waits on that very link,
     * the sign-in wasn't finished there (Tailscale's page ends with Connect), and the card says so.
     * Kept on the phone for an hour, so a node that started over meanwhile (Android ended the app
     * while the browser had the sign-in: the link it was finishing belongs to a key that's gone)
     * says so too, instead of asking as if nothing had happened.
     */
    var signInOpened by mutableStateOf<String?>(null)
        private set
    private var prefs: android.content.SharedPreferences? = null

    private fun rememberOpened(url: String?) {
        signInOpened = url
        prefs?.edit()?.apply {
            if (url == null) remove(KEY_OPENED).remove(KEY_OPENED_AT) else putString(KEY_OPENED, url).putLong(KEY_OPENED_AT, System.currentTimeMillis())
        }?.apply()
    }
    private val watchers = CopyOnWriteArrayList<(TsnetStatus) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    /** Start and stop run here, one after the other: a stop can't overtake the start before it. */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "tsnet").apply { isDaemon = true } }
    @Volatile private var started = false
    @Volatile private var startedControl: String? = null
    /** Bumped by every start and stop: a failing start only undoes itself while it is still the latest. */
    private val generation = java.util.concurrent.atomic.AtomicInteger()

    /** The Go library is in this build. */
    val available: Boolean by lazy {
        runCatching { Class.forName(CLASS); true }
            // Debug builds say why the node is missing (a library that didn't load, say). Never in release.
            .onFailure { if (BuildConfig.DEBUG) android.util.Log.w("Hypernova", "tsnet unavailable: $it") }
            .getOrDefault(false)
    }

    /** Hears every change of state (the router re-probes when the node comes up). */
    fun watch(w: (TsnetStatus) -> Unit): () -> Unit {
        watchers.add(w)
        return { watchers.remove(w) }
    }

    /** The control server the running node was started for (null: tailscale.com); null too when stopped. */
    val control: String? get() = if (started) startedControl?.ifEmpty { null } else null

    /** Whether the running node is the one for [controlUrl] (null: tailscale.com). */
    fun serves(controlUrl: String?): Boolean = started && startedControl == (controlUrl ?: "")

    /**
     * One state folder per control server (`files/tsnet/default`, `files/tsnet/<hash>`), so a
     * link naming another server never gets the keys this phone made for tailscale.com, or the
     * other way round.
     */
    fun stateDir(context: Context, controlUrl: String?): File = File(context.applicationContext.filesDir, "tsnet/" + stateKey(controlUrl))

    fun stateKey(controlUrl: String?): String = TsnetPolicy.stateKey(controlUrl)

    /** The node for [controlUrl] has signed in on this phone before (its state is on disk): it comes back by itself. */
    fun signedInBefore(context: Context, controlUrl: String?): Boolean = File(stateDir(context, controlUrl), "tailscaled.state").exists()

    /** Drops the node's keys for [controlUrl] (forgetting a computer that used its own server). Stops it first. */
    fun forget(context: Context, controlUrl: String?) {
        if (serves(controlUrl)) stop()
        // After the stop has run (same worker), so the node isn't writing into the folder being deleted.
        val dir = stateDir(context, controlUrl)
        worker.execute { dir.deleteRecursively() }
    }

    /**
     * Starts the node (once per process) with its state in app-private storage, named after this
     * phone. [controlUrl]: null for tailscale.com, a Headscale URL for self-hosters (from the pairing
     * link's `tc`, once the user agreed to it). A node signed in before comes back without a new
     * sign-in.
     */
    fun start(context: Context, controlUrl: String?): Boolean {
        if (!available) return false
        if (prefs == null) {
            val p = context.applicationContext.getSharedPreferences("hypernova", Context.MODE_PRIVATE)
            prefs = p
            val at = p.getLong(KEY_OPENED_AT, 0)
            if (signInOpened == null && System.currentTimeMillis() - at < OPENED_FOR_MS) signInOpened = p.getString(KEY_OPENED, null)
        }
        if (started && startedControl == (controlUrl ?: "")) return true
        if (started) stop()
        val dir = stateDir(context, controlUrl).apply { mkdirs() }
        // An earlier layout kept one node in files/tsnet itself; nothing shipped with it, so it goes.
        File(context.applicationContext.filesDir, "tsnet/tailscaled.state").delete()
        return try {
            val t = Class.forName(CLASS)
            // Android denies apps the netlink calls Go would use to list interfaces: the app hands
            // them over, now and on every network change (the node's monitor then re-reads them).
            feedNetwork(context.applicationContext, t)
            watchNetwork(context.applicationContext, t)
            val listenerType = Class.forName(LISTENER)
            val listener = java.lang.reflect.Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, m, args ->
                if (m.name == "onStatus") publish(TsnetStatus.parse(args?.get(0) as? String ?: ""))
                null
            }
            t.getMethod("setListener", listenerType).invoke(null, listener)
            val token = generation.incrementAndGet()
            started = true
            startedControl = controlUrl ?: ""
            publish(TsnetStatus("Starting"))
            val host = hostname()
            val key = authKey()
            // The node's start reads state and talks to the network: never on the main thread, and
            // on the one worker a stop also runs on, in order.
            worker.execute {
                try {
                    t.getMethod("start", String::class.java, String::class.java, String::class.java, String::class.java)
                        .invoke(null, dir.absolutePath, host, controlUrl ?: "", key)
                    if (TsnetPolicy.isCurrentStart(token, generation.get())) publish(TsnetStatus.parse(t.getMethod("status").invoke(null) as String))
                } catch (e: Throwable) {
                    val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
                    if (BuildConfig.DEBUG) android.util.Log.w("Hypernova", "tsnet start: $cause")
                    if (TsnetPolicy.isCurrentStart(token, generation.get())) {
                        started = false
                        publish(TsnetStatus("Stopped", error = cause.message ?: cause.toString()))
                    }
                }
            }
            if (BuildConfig.DEBUG) main.postDelayed({
                // Still starting after 25 s: the goroutines say where it sits.
                if (status.state == "Starting") runCatching { android.util.Log.w("Hypernova", "tsnet stuck:\n" + (t.getMethod("dump").invoke(null) as String).take(12000)) }
            }, 25_000)
            true
        } catch (e: Throwable) {
            // A Go error comes back wrapped by reflection: the Go message is what matters.
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            if (BuildConfig.DEBUG) android.util.Log.w("Hypernova", "tsnet start: $cause")
            publish(TsnetStatus("Stopped", error = cause.message ?: cause.toString()))
            false
        }
    }

    /** Starts the node afresh: after a start that failed, or a node Tailscale stopped (its message says why). */
    fun restart(context: Context, controlUrl: String?) {
        stop()
        start(context, controlUrl)
    }

    fun stop() {
        if (!started) return
        // Stopped on purpose (the computer forgotten, a fresh start): a sign-in left open is abandoned.
        if (signInOpened != null) rememberOpened(null)
        generation.incrementAndGet()
        started = false
        publish(TsnetStatus.STOPPED)
        // Closing the node waits for its goroutines: never on the main thread, and after any start
        // still queued before it.
        worker.execute { runCatching { Class.forName(CLASS).getMethod("stop").invoke(null) } }
    }

    /** Asks for a (new) sign-in link; it arrives in the next status. Off the main thread, after any start queued before it. */
    fun login() { worker.execute { call("login") } }

    /**
     * Reads the node's state again (the app is back on screen, say from the browser's sign-in): the
     * listener tells every change as it happens, and this makes sure the screen has the latest.
     */
    fun refresh() {
        if (!started) return
        worker.execute {
            runCatching { publish(TsnetStatus.parse(Class.forName(CLASS).getMethod("status").invoke(null) as String)) }
        }
    }

    /**
     * Another account, or a fresh start: signs out (telling the control server, up to 10 s, so never
     * on the main thread) and starts the node over, waiting for a sign-in (android/tsnet `Logout`).
     */
    fun logout() {
        rememberOpened(null)
        worker.execute { call("logout") }
    }

    /** The destinations the proxy may tunnel to (the paired computer's tailnet name or address, with its port): these and no others. */
    fun setAllowed(hostPorts: List<String>) {
        if (!started) return
        runCatching { Class.forName(CLASS).getMethod("setAllowed", String::class.java).invoke(null, hostPorts.joinToString(",")) }
    }

    /** One more destination, next to the current ones (a pairing under way); the router's next [setAllowed] tidies up. */
    fun allow(hostPort: String) {
        if (!started) return
        runCatching { Class.forName(CLASS).getMethod("allow", String::class.java).invoke(null, hostPort) }
    }

    private var netWatch: android.net.ConnectivityManager.NetworkCallback? = null

    private fun watchNetwork(app: Context, t: Class<*>) {
        if (netWatch != null) return
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) = feedNetwork(app, t)
            override fun onLost(network: android.net.Network) = feedNetwork(app, t)
            override fun onLinkPropertiesChanged(network: android.net.Network, lp: android.net.LinkProperties) = feedNetwork(app, t)
        }
        if (runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess) netWatch = cb
    }

    /** The interfaces (as Java sees them) and the default network's interface and gateway, to the Go side. */
    private fun feedNetwork(app: Context, t: Class<*>) {
        runCatching {
            val list = org.json.JSONArray()
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                val o = JSONObject()
                    .put("name", ni.name).put("index", ni.index).put("mtu", runCatching { ni.mtu }.getOrDefault(1500))
                    .put("up", runCatching { ni.isUp }.getOrDefault(false)).put("loopback", runCatching { ni.isLoopback }.getOrDefault(false))
                    .put("hw", runCatching { ni.hardwareAddress?.joinToString(":") { "%02x".format(it) } }.getOrNull() ?: "")
                val addrs = org.json.JSONArray()
                for (a in ni.interfaceAddresses) addrs.put("${a.address.hostAddress?.substringBefore('%')}/${a.networkPrefixLength}")
                list.put(o.put("addrs", addrs))
            }
            t.getMethod("setInterfaces", String::class.java).invoke(null, list.toString())
            val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
            val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
            val gateway = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is java.net.Inet4Address }?.gateway?.hostAddress ?: ""
            t.getMethod("setDefaultRoute", String::class.java, String::class.java).invoke(null, lp?.interfaceName ?: "", gateway)
        }.onFailure { if (BuildConfig.DEBUG) android.util.Log.w("Hypernova", "tsnet interfaces: $it") }
    }

    private fun call(name: String) {
        if (!started) return
        runCatching { Class.forName(CLASS).getMethod(name).invoke(null) }
            .onFailure { if (BuildConfig.DEBUG) android.util.Log.w("Hypernova", "tsnet $name: $it") }
    }

    private fun publish(s: TsnetStatus) {
        main.post {
            if (s == status) return@post
            status = s
            // Signed in: the opened link has done its job.
            if (s.running && signInOpened != null) rememberOpened(null)
            if (BuildConfig.DEBUG) android.util.Log.d("Hypernova", "tsnet: ${s.state} ${s.dnsName ?: ""} proxy=${s.proxyPort} ${s.error ?: ""}")
            for (w in watchers) runCatching { w(s) }
        }
    }

    /**
     * A pre-auth key for a test control server (Headscale), never for a real account: baked in by
     * the build (HYPERNOVA_TSNET_AUTHKEY), or, in a debug build on an emulator, pushed with adb to
     * /data/local/tmp/hypernova-tsnet-authkey. Empty otherwise, which means the browser sign-in.
     */
    private fun authKey(): String {
        BuildConfig.TSNET_AUTHKEY.takeIf { it.isNotEmpty() }?.let { return it }
        if (!BuildConfig.DEBUG) return ""
        return runCatching { File("/data/local/tmp/hypernova-tsnet-authkey").readText().trim() }.getOrDefault("")
    }

    /**
     * A sign-in link the app may open: https on `login.tailscale.com` (tailscale.com's own), or on
     * the control server's host (Headscale shows its own page there; plain http only if the control
     * URL itself is plain http to a private address). Anything else is refused.
     */
    fun signInUrlOk(url: String, controlUrl: String?): Boolean = TsnetPolicy.signInUrlOk(url, controlUrl)

    /** "hypernova-pixel-9" and the like: the phone's name on the tailnet. */
    fun hostname(): String {
        val raw = "${Build.MANUFACTURER} ${Build.MODEL}".lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        return "hypernova-" + raw.ifEmpty { "phone" }
    }

    /**
     * Opens the sign-in link in the browser (a Custom Tab where there is one), only when it is
     * Tailscale's own sign-in page or the control server the node was started for (see [signInUrlOk]).
     */
    fun openSignIn(context: Context, link: String? = null) {
        val url = link ?: status.loginUrl ?: return
        if (!signInUrlOk(url, control)) {
            if (BuildConfig.DEBUG) android.util.Log.w("Hypernova", "tsnet: refused a sign-in link that isn't Tailscale's or the control server's")
            return
        }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The Custom Tabs extra, honoured by Chrome and most browsers, with no library needed.
        i.putExtra("android.support.customtabs.extra.SESSION", null as android.os.Bundle?)
        try {
            context.startActivity(i)
            rememberOpened(url)
        } catch (_: ActivityNotFoundException) {}
    }

    private const val KEY_OPENED = "tsnetSignInOpened"
    private const val KEY_OPENED_AT = "tsnetSignInOpenedAt"
    private const val OPENED_FOR_MS = 60 * 60_000L

    /** Where a tailnet with device approval approves this phone: tailscale.com's admin console (a computer's own server has its own way). */
    const val ADMIN_MACHINES = "https://login.tailscale.com/admin/machines"
}

/** Where the built-in node stands, in the terms the screens use (see [TsnetPolicy.phase]). */
enum class TsPhase {
    /** This build has no node. */
    Unavailable,
    /** Not started on this phone (never signed in here, or forgotten). */
    Off,
    /** Coming up, or fetching a sign-in link. */
    Starting,
    /** A sign-in link is ready. */
    NeedsLogin,
    /** The link was opened in the browser and the node still waits on it: the sign-in isn't finished there. */
    Finishing,
    /** A link was opened, but the node has a new one now (it started over meanwhile): one more sign-in. */
    Again,
    /** Signed in; the tailnet approves new devices and this one waits (device approval). */
    NeedsApproval,
    /** Signed in; joining the tailnet (the relay, the proxy). */
    Connecting,
    Running,
    /** The node couldn't start. */
    Failed,
}

/** The node's rules with nothing of Android in them (unit-tested): state folders and sign-in links. */
object TsnetPolicy {
    /**
     * Every node state, each with something to show and do: a sign-in to open or finish, an approval
     * to give, a wait with a reason, or nothing (Running). [opened]: the sign-in link last opened in
     * the browser ([Tsnet.signInOpened]).
     */
    fun phase(available: Boolean, s: TsnetStatus, opened: String?): TsPhase = when {
        !available -> TsPhase.Unavailable
        s.running -> TsPhase.Running
        s.state == "Stopped" -> if (s.error != null) TsPhase.Failed else TsPhase.Off
        s.needsApproval -> TsPhase.NeedsApproval
        s.needsLogin -> when {
            s.loginUrl == null -> TsPhase.Starting
            opened == null -> TsPhase.NeedsLogin
            s.loginUrl == opened -> TsPhase.Finishing
            else -> TsPhase.Again
        }
        // Running before its proxy is up, or Starting with the tailnet's name already known (signed in).
        s.state == "Running" || (s.state == "Starting" && (s.dnsName != null || s.account != null)) -> TsPhase.Connecting
        else -> TsPhase.Starting
    }

    /**
     * Whether [host] is on the tailnet: the pairing's tailnet name or address, another name under
     * the same tailnet (a MagicDNS suffix, `tail1234.ts.net` or a Headscale `base_domain`: the
     * computer's earlier node, say), a ts.net name, or a Tailscale address (100.64.0.0/10,
     * fd7a:115c:a1e0::/48). A base URL there that the app reaches as it is goes through the
     * Tailscale app on the phone; a browser reaches it only that way too.
     */
    fun onTailnet(host: String, tailnetName: String?, tailnetIp: String?): Boolean {
        val h = host.trim().removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        val name = tailnetName?.trim()?.trimEnd('.')?.lowercase()
        if (h == name || h == tailnetIp?.trim()?.lowercase()) return true
        val suffix = name?.substringAfter('.', "")?.takeIf { it.contains('.') }
        if (suffix != null && h.endsWith(".$suffix")) return true
        if (h.endsWith(".ts.net")) return true
        val v4 = h.split('.').mapNotNull { it.toIntOrNull() }
        if (v4.size == 4 && h.count { it == '.' } == 3) return v4[0] == 100 && v4[1] in 64..127
        return h.startsWith("fd7a:115c:a1e0:")
    }

    /** A start may publish its outcome only while no later start or stop has bumped the generation. */
    fun isCurrentStart(token: Int, current: Int): Boolean = token == current

    /** "default" for tailscale.com, else 32 hex digits (128 bits) of the control URL's SHA-256 (trimmed, lowercase, no trailing slash). */
    fun stateKey(controlUrl: String?): String {
        val c = controlUrl?.trim()?.trimEnd('/')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return "default"
        return java.security.MessageDigest.getInstance("SHA-256").digest(c.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * A sign-in link the app may open: https on `login.tailscale.com` (tailscale.com's own), or on
     * the control server's host (Headscale shows its own page there; plain http only if the control
     * URL itself is plain http to a private address). Anything else is refused.
     */
    fun signInUrlOk(url: String, controlUrl: String?): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        val host = u.host?.lowercase() ?: return false
        val scheme = u.scheme?.lowercase()
        if (scheme == "https" && host == "login.tailscale.com") return true
        val c = controlUrl?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return false
        if (host != c.host?.lowercase()) return false
        return scheme == "https" || (scheme == "http" && c.scheme.equals("http", true) && PairLinks.privateHost(host.removePrefix("[").removeSuffix("]")))
    }
}

/** OkHttp clients that go through the embedded node's loopback proxy. */
object TsnetTls {
    /**
     * A client for [base] through the proxy on [proxyPort]: with the pin when the base names an
     * address (the tailnet has no HTTPS certificates, so the computer serves its own certificate,
     * the one the pairing code vouched for), the system's trust otherwise (a ts.net name's
     * certificate). [identity]: this phone's device certificate, shown where the server asks.
     */
    fun client(base: String, proxyPort: Int, proxyToken: String, fingerprint: String?, identity: DeviceIdentity?): OkHttpClient {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
        // The proxy's secret, so no other app on the phone can use the tunnel: answered to its 407.
        val credential = okhttp3.Credentials.basic("hypernova", proxyToken)
        val b = OkHttpClient.Builder()
            .proxy(proxy)
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", credential).build()
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
        val host = RoutePolicy.originOf(base)?.host
        if (fingerprint != null && host != null && isAddress(host)) {
            val tm = Pin.trustManager(fingerprint)
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(identity?.let { arrayOf(it.keyManager()) }, arrayOf(tm), SecureRandom())
            b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
        } else if (identity != null) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(arrayOf(identity.keyManager()), null, SecureRandom())
            val tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as java.security.KeyStore?) }
                .trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().first()
            b.sslSocketFactory(ctx.socketFactory, tm)
        }
        return b.build()
    }

    /** An IPv4 or IPv6 literal (the pinned case), not a name. */
    fun isAddress(host: String): Boolean = host.all { it.isDigit() || it == '.' } || host.contains(':')
}
