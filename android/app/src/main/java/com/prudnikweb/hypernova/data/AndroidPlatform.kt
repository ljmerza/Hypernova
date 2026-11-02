package com.prudnikweb.hypernova.data

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Resources
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.ConnectionService
import com.prudnikweb.hypernova.widget.WidgetSync
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Locale

/*
 * The shared data layer's seams (shared: data/Seams.kt) on Android: what the code there called
 * directly while it lived in the app.
 */

/** [res]'s strings, by way of [Str.id] and [Plural.id]; a clock's style needs a [context]. */
class AndroidStrings(private val context: Context?, private val res: Resources) : Strings {
    override fun text(key: Str): String = res.getString(key.id)
    override fun quantityText(key: Plural, quantity: Int): String = res.getQuantityString(key.id, quantity)
    override val locale: Locale get() = res.configuration.locales[0]
    override val plain: Strings get() = ComputerResources.plain(res).let { if (it === res) this else AndroidStrings(context, it) }

    override fun timePattern(withDay: Boolean): String {
        val h24 = DateFormat.is24HourFormat(checkNotNull(context) { "a clock's words need a Context" })
        return DateFormat.getBestDateTimePattern(Locale.getDefault(), (if (withDay) "EEE" else "") + (if (h24) "Hm" else "hm"))
    }
}

/** This context's strings, as its Resources word them now. */
val Context.strings: Strings get() = AndroidStrings(this, resources)

/** These Resources' strings, for words that tell no time ([AndroidStrings]). */
val Resources.strings: Strings get() = AndroidStrings(null, this)

class AndroidPrefs(private val prefs: SharedPreferences) : Prefs {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    override fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun contains(key: String): Boolean = prefs.contains(key)
    override val all: Map<String, *> get() = prefs.all
    override fun edit(): PrefsEditor = Editor(prefs.edit())

    private class Editor(private val e: SharedPreferences.Editor) : PrefsEditor {
        override fun putString(key: String, value: String?): PrefsEditor = apply { e.putString(key, value) }
        override fun putInt(key: String, value: Int): PrefsEditor = apply { e.putInt(key, value) }
        override fun putLong(key: String, value: Long): PrefsEditor = apply { e.putLong(key, value) }
        override fun putBoolean(key: String, value: Boolean): PrefsEditor = apply { e.putBoolean(key, value) }
        override fun remove(key: String): PrefsEditor = apply { e.remove(key) }
        override fun clear(): PrefsEditor = apply { e.clear() }
        override fun apply() = e.apply()
    }
}

class AndroidStorage(context: Context) : Storage {
    private val app = context.applicationContext
    override fun prefs(name: String): Prefs = AndroidPrefs(app.getSharedPreferences(name, Context.MODE_PRIVATE))
    override val filesDir: File get() = app.filesDir
}

/** This app's storage, whichever context asks. */
val Context.storage: Storage get() = AndroidStorage(this)

/** Android's main thread. */
object AndroidMainThread : MainThread {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    override fun post(block: () -> Unit) {
        main.post(block)
    }
}

/** Logcat, in a debug build only. */
object AndroidLog : Log {
    override fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) android.util.Log.d(tag, message)
    }
    override fun w(tag: String, message: String) {
        if (BuildConfig.DEBUG) android.util.Log.w(tag, message)
    }
}

object AndroidAppInfo : AppInfo {
    override val versionCode: Int get() = BuildConfig.VERSION_CODE
    override val versionName: String get() = BuildConfig.VERSION_NAME
}

class AndroidDevice(private val context: Context) : DeviceInfo {
    override val name: String
        get() = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
    override val model: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"
}

class AndroidNetwork(context: Context) : NetworkWatcher {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun hasNetwork(): Boolean {
        val cm = connectivity ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun kind(): NetKind {
        val cm = connectivity ?: return NetKind.None
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetKind.None
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.Wifi
            // A VPN over whatever carries it, mobile data included: a VPN into the Mac's own network
            // (WireGuard, UniFi) reaches its local addresses, so the local route is worth probing.
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetKind.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.Mobile
            else -> NetKind.None
        }
    }

    override fun words(): String {
        val cm = connectivity ?: return "no network"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "no network"
        return capsWords(caps)
    }

    override fun active(): Any? = connectivity?.activeNetwork

    /**
     * Hears a network worth trying again on: a new default network, the same one becoming another
     * kind or reaching the internet, or a VPN coming up or moving to another network under it. A
     * VPN that carries only the home network's addresses needn't become the default network: it
     * has a watch of its own. Signal strength and speed estimates change all
     * the time and aren't news, hence the key. A watch's callbacks come one at a time on one
     * thread, which is the only one reading its key.
     */
    private class NetWatch(private val onChange: () -> Unit) : ConnectivityManager.NetworkCallback() {
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
            onChange()
        }

        // Data Saver or a battery restriction let the app back onto the network.
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            val freed = this.blocked && !blocked
            this.blocked = blocked
            if (freed) onChange()
        }
    }

    override fun watchLink(onChange: () -> Unit): Watch {
        val defaultNet = NetWatch(onChange)
        val vpnNet = NetWatch(onChange)
        runCatching { connectivity?.registerDefaultNetworkCallback(defaultNet) }
        runCatching {
            val vpn = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivity?.registerNetworkCallback(vpn, vpnNet)
        }
        return Watch {
            runCatching { connectivity?.unregisterNetworkCallback(defaultNet) }
            runCatching { connectivity?.unregisterNetworkCallback(vpnNet) }
        }
    }

    override fun watchRoute(changed: (why: String) -> Unit): Watch? {
        val watch = object : ConnectivityManager.NetworkCallback() {
            private var key: Any? = null
            private var addrsOf: Network? = null
            private var addrs: List<String>? = null
            override fun onLost(network: Network) { changed(listOf("down", network), "network lost") }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                changed(listOf(network, TRANSPORTS_WATCHED.filter(caps::hasTransport), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)), "the network changed")
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val a = lp.linkAddresses.map { it.toString() }.sorted()
                // The same network with other addresses (not its first ones arriving): another subnet, say.
                val again = addrsOf == network && addrs?.isNotEmpty() == true && addrs != a
                addrsOf = network
                addrs = a
                if (again) changed("the phone's addresses changed")
            }
            private fun changed(k: Any, why: String) {
                if (k == key) return
                key = k
                changed(why)
            }
        }
        if (runCatching { connectivity?.registerDefaultNetworkCallback(watch) }.isFailure) return null
        return Watch { runCatching { connectivity?.unregisterNetworkCallback(watch) } }
    }

    private companion object {
        /** The kinds of network whose change is worth a try (see [NetWatch]). */
        val TRANSPORTS = intArrayOf(
            NetworkCapabilities.TRANSPORT_VPN, NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET,
        )
        val TRANSPORTS_WATCHED = listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN)

        fun capsWords(caps: NetworkCapabilities): String {
            val under = listOfNotNull(
                "Wi-Fi".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
                "Ethernet".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
                "mobile data".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
            ).joinToString(" and ").ifEmpty { "a network" }
            val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            return (if (vpn) "a VPN over $under" else under) + if (validated) "" else ", not validated yet"
        }
    }
}

class AndroidScreen(context: Context) : ScreenWatcher {
    private val app = context.applicationContext

    override fun watch(onScreenOn: () -> Unit): Watch {
        val screenOn = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onScreenOn()
        }
        runCatching { ContextCompat.registerReceiver(app, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED) }
        return Watch { runCatching { app.unregisterReceiver(screenOn) } }
    }
}

/**
 * The alarm counts real time but doesn't wake the phone: it goes off with the next thing that does
 * (another app's alarm, a message, the screen). It holds no wake lock.
 */
class AndroidRetryAlarm(context: Context) : RetryAlarm {
    private val alarms = context.applicationContext.getSystemService(AlarmManager::class.java)
    private var fire: (() -> Unit)? = null
    private val alarm = AlarmManager.OnAlarmListener { fire?.invoke() }

    override fun set(waitMs: Long, fire: () -> Unit) {
        this.fire = fire
        runCatching { alarms?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + waitMs, ALARM_TAG, alarm, null) }
    }

    override fun cancel() {
        runCatching { alarms?.cancel(alarm) }
    }

    private companion object {
        const val ALARM_TAG = "hypernova:retry"
    }
}

/** The key in AndroidKeyStore: it never leaves. */
object AndroidDeviceKeys : DeviceKeys {
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The key, made once. TLS needs the raw (NONE) digest too: Android's TLS signs the handshake itself. */
    override fun ensure(): Pair<PublicKey, PrivateKey> {
        val ks = keyStore()
        val have = runCatching { ks.getEntry(DeviceIdentity.ALIAS, null) as? KeyStore.PrivateKeyEntry }.getOrNull()
        if (have != null) return have.certificate.publicKey to have.privateKey
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(DeviceIdentity.ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .build(),
        )
        val kp = gen.generateKeyPair()
        return kp.public to kp.private
    }

    override fun privateKey(): PrivateKey? = (keyStore().getEntry(DeviceIdentity.ALIAS, null) as? KeyStore.PrivateKeyEntry)?.privateKey

    override fun delete() {
        keyStore().deleteEntry(DeviceIdentity.ALIAS)
    }
}

/** The foreground service that keeps the sockets alive with the app closed. */
class AndroidBackgroundLink(private val context: Context) : BackgroundLink {
    override val running: Boolean get() = ConnectionService.running
    override fun start() = ConnectionService.start(context)
    override fun stop() = ConnectionService.stop(context)
}

/** Everything the data layer needs, on this phone. */
class AndroidPlatform(context: Context) : Platform {
    private val app = context.applicationContext
    // Read each time: the app's Resources are worded for the computer paired now.
    override val strings: Strings get() = app.strings
    override val storage: Storage = AndroidStorage(app)
    override val ticks: Ticks = Ticks { SystemClock.elapsedRealtime() }
    override val main: MainThread = AndroidMainThread
    override val log: Log = AndroidLog
    override val appInfo: AppInfo = AndroidAppInfo
    override val device: DeviceInfo = AndroidDevice(app)
    override val network: NetworkWatcher = AndroidNetwork(app)
    override val screen: ScreenWatcher = AndroidScreen(app)
    override val deviceKeys: DeviceKeys = AndroidDeviceKeys
    override val speech: SpeechOutput get() = Speaker
    override val background: BackgroundLink = AndroidBackgroundLink(app)
    override fun retryAlarm(): RetryAlarm = AndroidRetryAlarm(app)
    override fun automationNotes(server: String?): AutomationNotifier = AutomationNotes(app, server)
    override fun notifier(): Notifier = AndroidNotifier(app)
    override fun thumbOf(path: String): Any? = Thumbs.get(path)
    override fun started() = WidgetSync.start(app as android.app.Application)
}

/** A web page (claude.ai's usage or billing, the sign-in page) in the phone's browser. */
fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
