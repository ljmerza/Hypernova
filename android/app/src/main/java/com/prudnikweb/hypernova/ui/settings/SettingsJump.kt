package com.prudnikweb.hypernova.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A part of Settings another screen sends the owner to, such as "Turn on in Settings" on a folder
 * outside the projects folder: Home switches to the Settings tab, and Settings scrolls to that part
 * (its list item's key), once. Compose state, written on the main thread.
 */
object SettingsJump {
    /** Settings → Files (Browse outside projects). */
    const val FILES = "files"

    /** Settings → the server's own card (an update that failed): its key with one server on screen. */
    const val SERVER = "mac"

    /** How long an ask stands: a screen that opens later doesn't jump. */
    private const val FRESH_MS = 10_000L

    /** The part asked for; null: nothing to do. */
    var asked by mutableStateOf<String?>(null)
        private set
    private var at by mutableLongStateOf(0L)
    /**
     * The server whose part is asked for (the one on screen when it was asked, a session's own from
     * "All servers"): with All servers each server has its own part (SettingsPlan.keyOf). Null: the one on screen.
     */
    var server by mutableStateOf<String?>(null)
        private set

    fun show(key: String, server: String? = null, now: Long = System.currentTimeMillis()) {
        at = now
        this.server = server
        asked = key
    }

    /** The part still asked for at [now], or null. */
    fun wanted(now: Long = System.currentTimeMillis()): String? = asked?.takeIf { now - at in 0..FRESH_MS }

    /** Settings scrolled to it. */
    fun shown() {
        asked = null
    }
}
