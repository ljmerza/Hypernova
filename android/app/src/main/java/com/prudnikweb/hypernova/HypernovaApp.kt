// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova

import android.app.Application
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.id
import com.prudnikweb.hypernova.data.storage
import com.prudnikweb.hypernova.data.strings

/** Starts [Core] (pairing + live connection) once per process, for the Activity and the service. */
class HypernovaApp : Application() {
    /** Notifications and widgets word the paired computer as the screens do (data/ComputerWords.kt). */
    override fun getResources(): android.content.res.Resources = com.prudnikweb.hypernova.data.ComputerResources.wrap(super.getResources())

    override fun onCreate() {
        // Before anything draws: what the shared drawing asks of this app (ui/theme, AndroidUi).
        com.prudnikweb.hypernova.ui.theme.AndroidUi.install(
            manrope = R.font.manrope, mono = R.font.jetbrains_mono,
            stringId = { it.id }, pluralId = { it.id }, words = { it.strings },
            diagram = { com.prudnikweb.hypernova.ui.components.MermaidBlock(it) },
        )
        super.onCreate()
        Core.applicationStarted()
        // The paired computer's words ("your Mac", "your server") before anything is said.
        com.prudnikweb.hypernova.data.ComputerWords.load(this.storage)
        AndroidCore.init(this)
        // The launcher's long-press menu follows the sessions.
        com.prudnikweb.hypernova.ui.share.LauncherShortcuts.start(this)
    }
}
