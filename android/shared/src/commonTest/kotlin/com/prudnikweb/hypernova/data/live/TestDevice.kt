package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.AppInfo
import com.prudnikweb.hypernova.data.AutomationNotifier
import com.prudnikweb.hypernova.data.BackgroundLink
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.DeviceInfo
import com.prudnikweb.hypernova.data.DeviceKeys
import com.prudnikweb.hypernova.data.Log
import com.prudnikweb.hypernova.data.MainThread
import com.prudnikweb.hypernova.data.NetKind
import com.prudnikweb.hypernova.data.NetworkWatcher
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.Notifier
import com.prudnikweb.hypernova.data.Platform
import com.prudnikweb.hypernova.data.PlayInfo
import com.prudnikweb.hypernova.data.Prefs
import com.prudnikweb.hypernova.data.PrefsEditor
import com.prudnikweb.hypernova.data.RetryAlarm
import com.prudnikweb.hypernova.data.RunAsk
import com.prudnikweb.hypernova.data.ScreenWatcher
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SpeechOutput
import com.prudnikweb.hypernova.data.Storage
import com.prudnikweb.hypernova.data.Strings
import com.prudnikweb.hypernova.data.Ticks
import com.prudnikweb.hypernova.data.WApproval
import com.prudnikweb.hypernova.data.Watch
import okhttp3.Call
import org.junit.runners.BlockJUnit4ClassRunner
import org.junit.runners.model.FrameworkMethod
import org.junit.runner.notification.RunNotifier
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A device off Android for the data layer's tests: every seam of [Platform], in memory. Like
 * Robolectric's Android on the app's side, it starts empty for each test ([RigRunner]).
 */
object TestDevice {
    val storage = MapStorage()
    val network = TestNetwork()
    val screen = TestScreen()
    val keys = TestKeys()
    val alarms = CopyOnWriteArrayList<TestAlarm>()
    /** The clock that counts while the device sleeps. */
    @Volatile var elapsed = 1_000L

    fun reset() {
        storage.clear()
        network.reset()
        screen.reset()
        keys.delete()
        alarms.clear()
        elapsed = 1_000L
    }
}

/** [TestDevice] for each test, as Robolectric gives each a new Android. */
class RigRunner(c: Class<*>) : BlockJUnit4ClassRunner(c) {
    override fun runChild(method: FrameworkMethod, notifier: RunNotifier) {
        TestDevice.reset()
        super.runChild(method, notifier)
    }
}

class MapPrefs : Prefs {
    private val map = ConcurrentHashMap<String, Any>()
    override fun getString(key: String, default: String?): String? = map[key] as? String ?: default
    override fun getInt(key: String, default: Int): Int = map[key] as? Int ?: default
    override fun getLong(key: String, default: Long): Long = map[key] as? Long ?: default
    override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
    override fun contains(key: String): Boolean = map.containsKey(key)
    override val all: Map<String, *> get() = map.toMap()
    override fun edit(): PrefsEditor = object : PrefsEditor {
        private val ops = ArrayList<() -> Unit>()
        private fun put(key: String, value: Any?): PrefsEditor = apply { ops += { if (value == null) map.remove(key) else map[key] = value } }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, null)
        override fun clear(): PrefsEditor = apply { ops.add(0) { map.clear() } }
        override fun apply() = ops.forEach { it() }
    }
}

class MapStorage : Storage {
    private val files = ConcurrentHashMap<String, MapPrefs>()
    override fun prefs(name: String): Prefs = files.getOrPut(name) { MapPrefs() }
    override val filesDir: File by lazy { File.createTempFile("hypernova", "files").apply { delete(); mkdirs(); deleteOnExit() } }
    fun clear() = files.clear()
}

class TestNetwork : NetworkWatcher {
    @Volatile var online = true
    private val link = CopyOnWriteArrayList<() -> Unit>()
    private val route = CopyOnWriteArrayList<(String) -> Unit>()
    private val current = Any()

    override fun hasNetwork(): Boolean = online
    override fun kind(): NetKind = if (online) NetKind.Wifi else NetKind.None
    override fun words(): String = if (online) "Wi-Fi" else "no network"
    override fun active(): Any? = current.takeIf { online }

    override fun watchLink(onChange: () -> Unit): Watch {
        link += onChange
        return Watch { link -= onChange }
    }

    override fun watchRoute(changed: (why: String) -> Unit): Watch? {
        route += changed
        return Watch { route -= changed }
    }

    /** The default network became another kind. */
    fun changed() {
        link.forEach { it() }
        route.forEach { it("the network changed") }
    }

    fun reset() {
        online = true
        link.clear()
        route.clear()
    }
}

class TestScreen : ScreenWatcher {
    private val watching = CopyOnWriteArrayList<() -> Unit>()
    override fun watch(onScreenOn: () -> Unit): Watch {
        watching += onScreenOn
        return Watch { watching -= onScreenOn }
    }
    fun on() = watching.forEach { it() }
    fun reset() = watching.clear()
}

class TestAlarm : RetryAlarm {
    @Volatile var pending: (() -> Unit)? = null
    override fun set(waitMs: Long, fire: () -> Unit) {
        pending = fire
    }
    override fun cancel() {
        pending = null
    }
}

class TestKeys : DeviceKeys {
    private var pair: KeyPair? = null
    @Synchronized override fun ensure(): Pair<PublicKey, PrivateKey> {
        val kp = pair ?: KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().also { pair = it }
        return kp.public to kp.private
    }
    @Synchronized override fun privateKey(): PrivateKey? = pair?.private
    @Synchronized override fun delete() {
        pair = null
    }
}

/** The device's key, as the app's side of the tests has "AndroidKeyStore" on the JVM. */
object FakeKeyStore {
    fun install() = reset()
    fun reset() = TestDevice.keys.delete()
}

/** What a hub's automations ask to notify: remembered as the phone's would be, shown nowhere. */
class TestAutomationNotes : AutomationNotifier {
    private val seen = LinkedHashSet<String>()
    @Synchronized override fun shown(id: String): Boolean = id in seen
    @Synchronized override fun firstTime(ids: List<String>): Boolean = seen.addAll(ids)
    override fun post(key: String, title: String, text: String, sessionId: String?, automationId: String?) = Unit
    override fun postAsk(runId: String, automationId: String?, name: String, ask: RunAsk) = Unit
    override fun cancelAsk(runId: String) = Unit
}

class FakePlatform : Platform {
    // Read each time, as the app's Resources follow the computer paired now.
    override val strings: Strings get() = ComputerWords.wordedFor(XmlStrings, ComputerWords.platform)
    override val storage: Storage get() = TestDevice.storage
    override val ticks: Ticks = Ticks { TestDevice.elapsed }
    override val main: MainThread = MainThread { it() }
    override val log: Log = object : Log {
        override fun d(tag: String, message: String) = Unit
        override fun w(tag: String, message: String) = Unit
    }
    override val appInfo: AppInfo = object : AppInfo {
        override val versionCode: Int = 1
        override val versionName: String = "test"
    }
    override val device: DeviceInfo = object : DeviceInfo {
        override val name: String = "Test Device"
        override val model: String = "Test Model"
    }
    override val network: NetworkWatcher get() = TestDevice.network
    override val screen: ScreenWatcher get() = TestDevice.screen
    override val deviceKeys: DeviceKeys get() = TestDevice.keys
    override val speech: SpeechOutput = object : SpeechOutput {
        override val speaking: Boolean = false
        override fun play(info: PlayInfo, key: String?, label: String?, open: () -> Call, report: (String) -> Unit) = Unit
        override fun stop(why: String) = Unit
        override fun stopPlay(id: String) = Unit
    }
    override val background: BackgroundLink = object : BackgroundLink {
        override val running: Boolean = false
        override fun start() = Unit
        override fun stop() = Unit
    }
    override fun retryAlarm(): RetryAlarm = TestAlarm().also { TestDevice.alarms += it }
    override fun automationNotes(server: String?): AutomationNotifier = TestAutomationNotes()
    override fun notifier(): Notifier = object : Notifier {
        override fun canPost(): Boolean = true
        override fun approval(a: WApproval, s: Session?, failed: Boolean, server: String?) = Unit
        override fun answered(id: String) = Unit
        override fun notice(n: Notice, server: String?) = Unit
        override fun replied(sessionId: String, server: String?) = Unit
        override fun cancelSilenced() = Unit
        override fun replyFailed(sessionId: String, title: String, why: String, server: String?) = Unit
        override fun cancelAccount(server: String?) = Unit
        override fun cancelServerUpdate(server: String?) = Unit
        override fun cancelApproval(id: String) = Unit
        override fun cancelAllApprovals(server: String?) = Unit
    }
    override fun thumbOf(path: String): Any? = null
    override fun started() = Unit
}
