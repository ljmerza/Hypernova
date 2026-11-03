package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.AppInfo
import com.prudnikweb.hypernova.data.AutomationNotifier
import com.prudnikweb.hypernova.data.BackgroundLink
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.ConnLog
import com.prudnikweb.hypernova.data.DebugLog
import com.prudnikweb.hypernova.data.DeviceInfo
import com.prudnikweb.hypernova.data.DeviceKeys
import com.prudnikweb.hypernova.data.Log
import com.prudnikweb.hypernova.data.MainThread
import com.prudnikweb.hypernova.data.NetKind
import com.prudnikweb.hypernova.data.NetworkWatcher
import com.prudnikweb.hypernova.data.Notifier
import com.prudnikweb.hypernova.data.Platform
import com.prudnikweb.hypernova.data.PlayInfo
import com.prudnikweb.hypernova.data.RetryAlarm
import com.prudnikweb.hypernova.data.RunAsk
import com.prudnikweb.hypernova.data.ScreenWatcher
import com.prudnikweb.hypernova.data.SpeechOutput
import com.prudnikweb.hypernova.data.Storage
import com.prudnikweb.hypernova.data.Strings
import com.prudnikweb.hypernova.data.Ticks
import com.prudnikweb.hypernova.data.Watch
import okhttp3.Call
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/*
 * The shared data layer's seams (shared: data/Seams.kt) on a desktop JVM. A capability a desktop hasn't got
 * here says so: docs/PLATFORMS.md, "The desktop app", lists each as real, null or a no-op.
 */

/** Which build this is, from what the build put in the resources. */
object DesktopAppInfo : AppInfo {
    private val props: Properties by lazy {
        Properties().apply { DesktopAppInfo::class.java.classLoader.getResourceAsStream("desktop/build.properties")?.use { load(it) } }
    }
    /**
     * As high as it goes: a server offers the phone's APK with the APK's own number ([com.prudnikweb.hypernova.data.LiveHub.appUpdate]),
     * and that's no update for this app, which has no updates yet.
     */
    override val versionCode: Int = Int.MAX_VALUE
    override val versionName: String get() = props.getProperty("version") ?: "0.0.0-dev"
    override val build: String get() = "desktop app"
}

/** This computer's name, and its system, for the server's list of devices. */
class DesktopDevice(
    private val env: (String) -> String? = System::getenv,
    private val hostName: () -> String? = { runCatching { InetAddress.getLocalHost().hostName }.getOrNull() },
) : DeviceInfo {
    override val name: String by lazy {
        val host = env("HOSTNAME")?.takeIf { it.isNotBlank() } ?: env("COMPUTERNAME")?.takeIf { it.isNotBlank() } ?: hostName()?.takeIf { it.isNotBlank() }
        host?.substringBefore('.') ?: System.getProperty("os.name").orEmpty().ifBlank { "Desktop" }
    }
    override val model: String get() = "Desktop, ${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.arch").orEmpty()}".trim()
    override val os: String get() = listOf("os.name", "os.version", "os.arch").joinToString(" ") { System.getProperty(it).orEmpty() }.trim() + ", Java ${System.getProperty("java.version").orEmpty()}"
}

/**
 * The developer's log, on standard error: warnings always, the rest with [debug]
 * (HYPERNOVA_CLIENT_DEBUG=1). Never a secret, as on a phone: nothing in the data layer logs one.
 * Every line also goes to the debug log while it's on (data/DebugLog.kt).
 */
class DesktopLog(private val debug: Boolean = !System.getenv("HYPERNOVA_CLIENT_DEBUG").isNullOrBlank()) : Log {
    override fun d(tag: String, message: String) {
        if (debug) System.err.println("D/$tag: $message")
        if (tag != ConnLog.TAG) DebugLog.d(tag.lowercase()) { message }
    }

    override fun i(tag: String, message: String) {
        if (debug) System.err.println("I/$tag: $message")
        DebugLog.i(tag.lowercase()) { message }
    }

    override fun w(tag: String, message: String) {
        System.err.println("W/$tag: $message")
        DebugLog.w(tag.lowercase()) { message }
    }

    override fun e(tag: String, message: String, t: Throwable?) {
        System.err.println("E/$tag: $message")
        DebugLog.e(tag.lowercase(), t) { message }
    }
}

/**
 * The network, as the JDK can see it: there's no change event and no default-route API that works on every
 * system, so it reads the interfaces, and a watch does that every [POLL_MS]. Virtual bridges don't count:
 * a machine running Docker would otherwise always seem to have a network, and every container that starts
 * would be a network change.
 */
class DesktopNetwork(
    private val pollMs: Long = POLL_MS,
    private val read: () -> List<Pair<String, List<String>>> = ::interfaces,
) : NetworkWatcher {
    private val poller: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "network-watch").apply { isDaemon = true } }
    }
    private val link = CopyOnWriteArrayList<() -> Unit>()
    private val route = CopyOnWriteArrayList<(String) -> Unit>()
    private var ticking: ScheduledFuture<*>? = null
    @Volatile private var last: Any? = null

    private fun now(): List<Pair<String, List<String>>>? = runCatching(read).getOrNull()

    /** It can't tell when the JDK can't read them. */
    override fun hasNetwork(): Boolean = now()?.isNotEmpty() ?: true

    /**
     * [NetKind.Wifi] is what makes the local route worth trying, and Android gives it to Ethernet and a VPN too.
     * No desktop here is on mobile data as far as it can tell, so it's that or nothing.
     */
    override fun kind(): NetKind = if (hasNetwork()) NetKind.Wifi else NetKind.None

    override fun words(): String = when (val n = now()) {
        null -> "a network it can't read"
        else -> if (n.isEmpty()) "no network" else n.joinToString(", ") { it.first }
    }

    override fun active(): Any? = now()?.takeIf { it.isNotEmpty() }

    /**
     * A phone's hotspot hands out .1 addresses, and the router sweeps the /24 behind each for the computer.
     * On a desktop a .1 address is a Docker bridge or a VM's far more often, and hosting a hotspot for its
     * own server is no case worth sweeping 254 addresses for.
     */
    override fun hotspotGateways(): List<String> = emptyList()

    override fun watchLink(onChange: () -> Unit): Watch {
        link += onChange
        ensureTicking()
        return Watch { link -= onChange; idle() }
    }

    override fun watchRoute(changed: (why: String) -> Unit): Watch? {
        route += changed
        ensureTicking()
        // Told at once about the network there is, as the seam asks.
        poller.execute { changed("the network it's on") }
        return Watch { route -= changed; idle() }
    }

    @Synchronized private fun ensureTicking() {
        if (ticking != null) return
        last = now()
        ticking = poller.scheduleWithFixedDelay(::tick, pollMs, pollMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized private fun idle() {
        if (link.isEmpty() && route.isEmpty()) {
            ticking?.cancel(false)
            ticking = null
        }
    }

    /** One look, for the watches ([pollMs] does it by itself). */
    internal fun tick() {
        val seen = now() ?: return
        if (seen == last) return
        last = seen
        link.forEach { it() }
        route.forEach { it("the network changed") }
    }

    companion object {
        const val POLL_MS = 5_000L
        private val VIRTUAL = listOf("docker", "veth", "br-", "virbr", "vmnet", "vboxnet")

        /** Each interface that's up with an address, not a loopback or a virtual bridge, by name: its addresses. */
        fun interfaces(): List<Pair<String, List<String>>> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { n -> n.isUp && !n.isLoopback && VIRTUAL.none { n.name.startsWith(it) } }
            .map { n -> n.name to n.inetAddresses.toList().mapNotNull { it.hostAddress }.sorted() }
            .filter { it.second.isNotEmpty() }
            .sortedBy { it.first }
    }
}

/**
 * The closest thing a desktop has to the screen coming on: this app's window getting the focus, which says
 * its owner is about to look (Main.kt calls [ownerLooked]). A JVM isn't told about the real screen.
 */
class DesktopScreen : ScreenWatcher {
    private val watching = CopyOnWriteArrayList<() -> Unit>()

    override fun watch(onScreenOn: () -> Unit): Watch {
        watching += onScreenOn
        return Watch { watching -= onScreenOn }
    }

    fun ownerLooked() = watching.forEach { it() }
}

/**
 * A clock that goes forward only and counts the time the computer sleeps, which [System.nanoTime] doesn't
 * on Linux: it's nanoTime, plus each time the wall clock moved on further than nanoTime did (a sleep, seen
 * at the next read). A wall clock set forward by hand reads as a sleep too, which only makes a retry come
 * sooner. One set back is ignored.
 */
class SleepAwareClock(
    private val nanos: () -> Long = System::nanoTime,
    private val wall: () -> Long = System::currentTimeMillis,
) : Ticks {
    private var lastNanos = nanos()
    private var lastWall = wall()
    private var elapsed = 0L

    @Synchronized
    override fun elapsed(): Long {
        val n = nanos()
        val w = wall()
        val mono = (n - lastNanos) / 1_000_000
        val real = w - lastWall
        // Anything under a few seconds is the two clocks' own wander.
        elapsed += if (real - mono > SLEPT_MS) real else mono
        lastNanos = n
        lastWall = w
        return elapsed
    }

    private companion object {
        const val SLEPT_MS = 3_000L
    }
}

/**
 * One hub's next attempt: checked every second against a clock that counts sleep ([ticks]), so it's due
 * straight after the computer wakes, where a timer would wait out what it hadn't counted. It doesn't wake the computer.
 */
class DesktopRetryAlarm(private val ticks: Ticks, private val main: MainThread) : RetryAlarm {
    @Volatile private var due: Long? = null
    @Volatile private var fire: (() -> Unit)? = null
    private var check: ScheduledFuture<*>? = null

    @Synchronized
    override fun set(waitMs: Long, fire: () -> Unit) {
        this.fire = fire
        due = ticks.elapsed() + waitMs
        if (check == null) check = TIMER.scheduleWithFixedDelay(::checkDue, CHECK_MS, CHECK_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    override fun cancel() {
        due = null
        fire = null
        check?.cancel(false)
        check = null
    }

    @Synchronized
    private fun checkDue() {
        val d = due ?: return
        if (ticks.elapsed() < d) return
        val f = fire
        cancel()
        if (f != null) main.post(f)
    }

    private companion object {
        const val CHECK_MS = 1_000L
        val TIMER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "retry-alarm").apply { isDaemon = true } }
    }
}

/** Reading aloud isn't built here. It honestly isn't speaking, and a read-aloud the server starts stays silent on this computer. */
class SilentSpeech(private val log: Log) : SpeechOutput {
    override val speaking: Boolean = false
    override fun play(info: PlayInfo, key: String?, label: String?, open: () -> Call, report: (String) -> Unit) {
        log.d("Speech", "this app doesn't read aloud: ignored")
    }
    override fun stop(why: String) = Unit
    override fun stopPlay(id: String) = Unit
}

/**
 * No background service: this app's process is the link, and it holds it for as long as it runs. So it says
 * it's running, and starting and stopping do nothing. Closing the window ends the process.
 */
object ProcessLink : BackgroundLink {
    override val running: Boolean = true
    override fun start() = Unit
    override fun stop() = Unit
}

/**
 * Nothing is shown: this window has no automations to open, so an alert would lead nowhere (docs/DECISIONS.md, "Tray
 * alerts on the desktop client"). The rule that a note is told once still holds (what's marked shown stays marked), so
 * a later job's alerts won't repeat what this one has already passed over.
 */
class QuietAutomationNotes(private val log: Log, storage: Storage, server: String?) : AutomationNotifier {
    private val prefs = storage.prefs("hypernova")
    private val key = "automationNotesSeen" + (server?.let { ".$it" } ?: "")

    private fun seen(): List<String> = prefs.getString(key, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    override fun shown(id: String): Boolean = id in seen()

    override fun firstTime(ids: List<String>): Boolean {
        val had = seen()
        val new = ids.filter { it !in had }
        if (new.isEmpty()) return false
        prefs.edit().putString(key, (had + new).takeLast(50).joinToString(",")).apply()
        return true
    }

    override fun post(key: String, title: String, text: String, sessionId: String?, automationId: String?) {
        log.d("Notify", "an automation's note, not shown: this window has no automations")
    }
    override fun postAsk(runId: String, automationId: String?, name: String, ask: RunAsk) {
        log.d("Notify", "an automation's question, not shown: this window has no automations")
    }
    override fun cancelAsk(runId: String) = Unit
}

/** Everything the data layer needs, on this computer, its files under [home]. */
class DesktopPlatform(
    home: Path = DataFolder.of().toPath(),
    override val log: Log = DesktopLog(),
    /** Null: AWT's tray, once Main.kt has given it its icon. */
    surface: AlertSurface? = null,
    keychain: KeychainChoice = Keychains.forThisSystem(home),
) : Platform, AutoCloseable {
    /** Each paired server's token. First, as the folder's settings go through it on their way to the disk. */
    val tokens = DesktopTokens(keychain, log)
    val folder: DesktopStorage = DesktopStorage(home, log, mapOf(TOKENS_PREFS to tokens))
    val keyFile: Path = home.resolve("device-key.json")
    val screenWatch = DesktopScreen()
    /** The picture a tray icon shows: the window's, set by Main.kt, as a test has neither. */
    var trayImage: () -> java.awt.Image = { java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB) }
    val tray: AwtTray? = if (surface == null) AwtTray(log) { trayImage() } else null
    val alerts = TrayNotifier(log, folder, surface ?: checkNotNull(tray))

    // Read each time, as a phone's Resources follow the computer paired now.
    override val strings: Strings get() = ComputerWords.wordedFor(DesktopStrings, ComputerWords.platform)
    override val storage: Storage get() = folder
    override val ticks: Ticks = SleepAwareClock()
    override val main: MainThread = MainThread { SwingUtilities.invokeLater(it) }
    override val appInfo: AppInfo = DesktopAppInfo
    override val device: DeviceInfo = DesktopDevice()
    override val network: NetworkWatcher = DesktopNetwork()
    override val screen: ScreenWatcher get() = screenWatch
    val keys = DesktopKeys(keyFile, log, keychain)
    override val deviceKeys: DeviceKeys get() = keys
    init {
        tokens.attach(folder, keys, main::post)
    }
    override val speech: SpeechOutput = SilentSpeech(log)
    override val background: BackgroundLink = ProcessLink
    override fun retryAlarm(): RetryAlarm = DesktopRetryAlarm(ticks, main)
    override fun automationNotes(server: String?): AutomationNotifier = QuietAutomationNotes(log, folder, server)
    override fun notifier(): Notifier = alerts
    override fun thumbOf(path: String): Any? = null
    override fun started() = Unit

    override fun close() = folder.close()

}
