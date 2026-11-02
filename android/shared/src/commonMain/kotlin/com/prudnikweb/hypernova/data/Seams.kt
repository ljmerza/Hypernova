package com.prudnikweb.hypernova.data

import okhttp3.Call
import java.io.File
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Locale

/*
 * What the data layer asks of the platform it runs on, and nothing more (docs/PLATFORMS.md, "The shared
 * module"). The Android app implements these in app/.../data/AndroidPlatform.kt, the tests in
 * shared/src/fixtures. They are small on purpose: each is what the code here called on Android before,
 * under the same names where it can be, so the code reads as it did.
 */

/**
 * The app's strings ([Str], [Plural]; R.string and R.plurals on Android), in the words they are worded
 * in ([ComputerWords]). [getString] and [getQuantityString] format as Android's Resources do.
 */
interface Strings {
    /** A string as it is, not formatted. */
    fun text(key: Str): String
    fun quantityText(key: Plural, quantity: Int): String
    val locale: Locale

    /** These strings without a rewording of the paired computer's ([ComputerWords.wordedFor] starts from them). */
    val plain: Strings get() = this

    /** The phone's own style of clock, as a [java.text.SimpleDateFormat] pattern: a time, or a day and a time. */
    fun timePattern(withDay: Boolean): String

    fun getString(key: Str): String = text(key)
    fun getString(key: Str, vararg args: Any?): String = String.format(locale, text(key), *args)
    fun getQuantityString(key: Plural, quantity: Int): String = quantityText(key, quantity)
    fun getQuantityString(key: Plural, quantity: Int, vararg args: Any?): String = String.format(locale, quantityText(key, quantity), *args)
}

/** One file of small settings, kept on this device; SharedPreferences on Android, with its names. */
interface Prefs {
    fun getString(key: String, default: String?): String?
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getBoolean(key: String, default: Boolean): Boolean
    fun contains(key: String): Boolean
    val all: Map<String, *>
    fun edit(): PrefsEditor
}

interface PrefsEditor {
    fun putString(key: String, value: String?): PrefsEditor
    fun putInt(key: String, value: Int): PrefsEditor
    fun putLong(key: String, value: Long): PrefsEditor
    fun putBoolean(key: String, value: Boolean): PrefsEditor
    fun remove(key: String): PrefsEditor
    fun clear(): PrefsEditor
    fun apply()
}

/** Where this app keeps things on the device. */
interface Storage {
    fun prefs(name: String): Prefs
    /** The app's private folder. */
    val filesDir: File
}

/** A clock that only goes forward and counts while the device sleeps: SystemClock.elapsedRealtime on Android. */
fun interface Ticks {
    fun elapsed(): Long
}

/** The main thread, where all Compose state of the hubs is written. */
fun interface MainThread {
    fun post(block: () -> Unit)
}

/** The developer's log: logcat in a debug build on Android, nothing in a release. Never a secret, as ever. */
interface Log {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
}

/** Which build this is. */
interface AppInfo {
    val versionCode: Int
    val versionName: String
}

/** How this device is named when it pairs with a computer. */
interface DeviceInfo {
    /** The name the computer shows for it, e.g. "Nothing Phone (3)". */
    val name: String
    /** Its maker and model. */
    val model: String
}

/** Stops a watch ([NetworkWatcher], [ScreenWatcher]). */
fun interface Watch {
    fun stop()
}

/** The device's network, for a hub and its router. */
interface NetworkWatcher {
    /** The default network reaches the internet, as far as the device says. True when it can't tell. */
    fun hasNetwork(): Boolean
    /** The default network's kind, for the route choice. */
    fun kind(): NetKind
    /** The default network in a few words, for the log: "Wi-Fi", "a VPN over mobile data, not validated yet". */
    fun words(): String
    /** Which network is the default one, as something to compare; null with none. */
    fun active(): Any?

    /**
     * [onChange] hears a network worth trying again on: a new default network, the same one becoming
     * another kind or reaching the internet, a VPN coming up or moving, or the app let back onto the
     * network. Signal strength and the like aren't news.
     */
    fun watchLink(onChange: () -> Unit): Watch

    /**
     * [changed] hears the default network come, go, become another kind or get other addresses, with
     * why for the log; it's told at once about the network there is. Null: no watch could be made.
     */
    fun watchRoute(changed: (why: String) -> Unit): Watch?
}

/** The screen coming on (the device is awake anyway, and its owner may be about to look). */
fun interface ScreenWatcher {
    fun watch(onScreenOn: () -> Unit): Watch
}

/** One hub's next attempt, by a clock that counts while the device sleeps and never wakes it. */
interface RetryAlarm {
    fun set(waitMs: Long, fire: () -> Unit)
    fun cancel()
}

/** The device's key for its certificates (data/DeviceCert.kt), made once and never leaving where it's kept. */
interface DeviceKeys {
    /** The key, made if it isn't there. */
    fun ensure(): Pair<PublicKey, PrivateKey>
    fun privateKey(): PrivateKey?
    fun delete()
}

/** This device reading aloud, for the hubs (the screens use what's behind it directly). */
interface SpeechOutput {
    val speaking: Boolean
    fun play(info: PlayInfo, key: String?, label: String?, open: () -> Call, report: (String) -> Unit)
    fun stop(why: String = "phone")
    fun stopPlay(id: String)
}

/** Automations' notifications (their own channel on Android), for one paired computer. */
interface AutomationNotifier {
    fun shown(id: String): Boolean
    /** Marks [ids] shown; false when they all were already. */
    fun firstTime(ids: List<String>): Boolean
    /** [automationId] "" opens the Automations tab (several folded into one). */
    fun post(key: String, title: String, text: String, sessionId: String?, automationId: String?)
    fun postAsk(runId: String, automationId: String?, name: String, ask: RunAsk)
    fun cancelAsk(runId: String)
}

/** The background link's keeper: on Android, the foreground service that keeps the sockets alive. */
interface BackgroundLink {
    /** It holds the link open now. */
    val running: Boolean
    fun start()
    fun stop()
}

/** The system's notifications: approvals, a session's news, the account's and a server's (data/Core.kt). */
interface Notifier {
    /** The system lets this app post them. */
    fun canPost(): Boolean
    /** An approval waits: one naming what asks, Allow and Deny where it can, on [server]. */
    fun approval(a: WApproval, s: Session?, failed: Boolean = false, server: String? = null)
    /** Answered from the notification and the computer has it: the notification goes. */
    fun answered(id: String)
    /** A session finished, waits or failed (or a newer app is on the computer): one per session, replaced by its next. */
    fun notice(n: Notice, server: String? = null)
    /** A reply from a session's notice reached the computer: the notice goes. */
    fun replied(sessionId: String, server: String? = null)
    /** What the switches now silence goes from the shade. */
    fun cancelSilenced()
    /** A reply that didn't get through: the notice says so, and Reply is there again. */
    fun replyFailed(sessionId: String, title: String, why: String, server: String? = null)
    /** The account needs nothing from the owner any more: its notification goes. */
    fun cancelAccount(server: String? = null)
    /** That server's update went through after all, or the setting was turned off: its notification goes. */
    fun cancelServerUpdate(server: String? = null)
    fun cancelApproval(id: String)
    /** Every approval notified for [server] goes; null: every one. */
    fun cancelAllApprovals(server: String? = null)
}

/** Everything the data layer needs from the platform, one place to hand it over. */
interface Platform {
    /** The app's words as the app shows them now. */
    val strings: Strings
    val storage: Storage
    val ticks: Ticks
    val main: MainThread
    val log: Log
    val appInfo: AppInfo
    val device: DeviceInfo
    val network: NetworkWatcher
    val screen: ScreenWatcher
    val deviceKeys: DeviceKeys
    val speech: SpeechOutput
    val background: BackgroundLink
    /** A hub's own alarm. */
    fun retryAlarm(): RetryAlarm
    fun automationNotes(server: String?): AutomationNotifier
    fun notifier(): Notifier
    /** The preview this device already has of an attached picture, by its path on the computer ([DraftFile.thumb]). */
    fun thumbOf(path: String): Any?
    /** [Core] has started: what follows it on this platform (the home screen widgets on Android) can too. */
    fun started()
}
