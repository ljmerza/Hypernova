package com.prudnikweb.hypernova

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.prudnikweb.hypernova.data.Core

/**
 * "Stay connected in the background" after the phone restarts, or after this app is updated: the
 * background connection comes back without the app being opened first, so approvals, the widgets
 * and Lens's bridge work again. Both broadcasts let an app start a foreground service from the
 * background, and a specialUse one is allowed from boot (Android 15 limits only dataSync, media,
 * camera, microphone and phone calls there). Only while a computer is paired and the switch is on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // HypernovaApp has started Core for this process already; this is only in case.
        Core.init(context.applicationContext as Application)
        if (Core.wantsService()) ConnectionService.start(context)
    }
}
