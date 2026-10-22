package com.prudnikweb.hypernova

import android.app.Application
import com.prudnikweb.hypernova.data.Core

/** Starts [Core] (pairing + live connection) once per process, for the Activity and the service. */
class HypernovaApp : Application() {
    /** Notifications and widgets word the paired computer as the screens do (data/ComputerWords.kt). */
    override fun getResources(): android.content.res.Resources = com.prudnikweb.hypernova.data.ComputerWords.wrap(super.getResources())

    override fun onCreate() {
        super.onCreate()
        // The paired computer's words ("your Mac", "your server") before anything is said.
        com.prudnikweb.hypernova.data.ComputerWords.load(this)
        Core.init(this)
        // The launcher's long-press menu follows the sessions.
        com.prudnikweb.hypernova.ui.share.LauncherShortcuts.start(this)
        // Hypernova Lens on the glasses reaches the Mac through this app, over loopback (bridge/LensBridge.kt).
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        // A switch kept for turning it off again; nothing listens while it's off.
        if (com.prudnikweb.hypernova.bridge.LensBridge.ENABLED) com.prudnikweb.hypernova.bridge.LensBridge.start(this, version)
    }
}
