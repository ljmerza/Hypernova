package com.prudnikweb.hypernova.bridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * "Open Hypernova" from Lens, and the links that open Settings → Glasses
 * (`https://hypernova.observer/glasses`, `hypernova://glasses`).
 *
 * Lens runs in the Even App's web view, which hands no link of another app's to Android, and the Even
 * Hub SDK has no way to open one; so Lens asks this app through the bridge, `POST /bridge/open
 * {screen: "glasses"}` (signed and sealed: docs/PROTOCOL.md, "The phone bridge"). Android lets an app
 * start an activity only while it's on screen itself, so the app is opened there and then only when
 * it already is; otherwise a heads-up notification, "Tap to open Hypernova", does it on the owner's
 * tap ([BridgeNotice.open]).
 */
object BridgeOpen {
    /** The screens Lens may ask for: only the glasses' own settings. */
    val SCREENS = setOf("glasses")

    /** How long "open Settings → Glasses" stands: a request older than this (the app was busy elsewhere, not paired yet) is dropped. */
    const val FRESH_MS = 60_000L

    /** A second ask within this of one already shown only says so again: a Lens that asks in a loop can't stack notifications or keep one sounding. */
    const val AGAIN_MS = 2_000L

    /** What came of an ask, as the answer says it (`shown`). */
    enum class Shown(val wire: String) {
        /** The app was on screen: it went to Settings → Glasses. */
        App("app"),
        /** A heads-up notification asks the owner to tap it. */
        Notification("notification"),
        /** Neither (the app's notifications are off): Lens says to open Hypernova. */
        Nothing("nothing"),
    }

    /** The screen a sealed body names (`{screen}`), when it's one Lens may ask for; null otherwise. */
    fun screen(body: ByteArray): String? =
        runCatching { JSONObject(String(body, Charsets.UTF_8)).optString("screen") }.getOrNull()?.takeIf { it in SCREENS }

    /** How the app opens: there and then while it's on screen (Android allows that), else by a notification it may post. */
    fun how(visible: Boolean, canNotify: Boolean): Shown = when {
        visible -> Shown.App
        canNotify -> Shown.Notification
        else -> Shown.Nothing
    }

    // ─── Settings → Glasses, once (Compose state, written on the main thread) ───

    /** When Settings → Glasses was last asked for (a link, the notification, Lens with the app on screen); 0: nothing to do. */
    var glassesAsked by mutableLongStateOf(0L)
        private set

    /** Home switches to Settings and Settings scrolls to Glasses ([glassesWanted]); Settings then calls [glassesShown]. */
    fun showGlasses(now: Long = System.currentTimeMillis()) { glassesAsked = now }

    fun glassesShown() { glassesAsked = 0L }

    /** An ask for Settings → Glasses that still stands at [now]. */
    fun glassesWanted(now: Long = System.currentTimeMillis()): Boolean = glassesAsked > 0 && now - glassesAsked in 0..FRESH_MS
}
