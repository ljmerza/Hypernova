package com.prudnikweb.hypernova.ui.share

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** "Open the new-session sheet", asked by a launcher shortcut or by Share to Hypernova. */
data class NewSessionLaunch(val projectPath: String?, val id: Long)

/**
 * The hand-over from outside the app (a launcher shortcut, the share sheet) to the screens: the
 * activity records it here and the app opens the new-session sheet once it has a Mac. Process-wide
 * state, because the share screen and the app run in the same process, and the files and text
 * it carries are already in the app's own drafts by then.
 */
object ShareLaunch {
    const val ACTION_NEW_SESSION = "com.prudnikweb.hypernova.NEW_SESSION"
    const val EXTRA_PROJECT = "project"

    /** Set to open the sheet; the app clears it once it has. */
    var newSession by mutableStateOf<NewSessionLaunch?>(null)

    private var seq = 0L
    private var text: String? = null

    fun owns(intent: Intent) = intent.action == ACTION_NEW_SESSION

    /** [path]: the project's folder on the Mac, or null for the sheet's own choice. */
    fun openNewSession(path: String?, sharedText: String? = null) {
        text = sharedText?.takeIf { it.isNotBlank() }
        newSession = NewSessionLaunch(path, ++seq)
    }

    fun handle(intent: Intent) {
        val path = intent.getStringExtra(EXTRA_PROJECT)
        intent.removeExtra(EXTRA_PROJECT)
        openNewSession(path)
    }

    /** The shared text waiting for the sheet's box (taken once). */
    fun takeText(): String? = text.also { text = null }
}
