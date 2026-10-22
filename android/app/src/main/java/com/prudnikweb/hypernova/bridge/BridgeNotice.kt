package com.prudnikweb.hypernova.bridge

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R

/**
 * "Allow Hypernova Lens to connect?" as a notification, with Allow and Deny on it, for when the ask
 * comes while this app isn't on screen (it usually is: Lens is open in the Even App). Answered from
 * the notification itself, the app stays in the background. The in-app prompt is the same question
 * (ui/glasses/BridgePrompt.kt).
 *
 * Allow gives a Lens full use of the Mac, so it never works from a locked phone: Android asks to
 * unlock before it goes through (API 31+), the lock screen shows neither the code nor the buttons,
 * and a locked phone's Allow that gets through anyway (older Android) only opens the app.
 */
object BridgeNotice {
    private const val CHANNEL = "glasses"
    private const val PORT_CHANNEL = "glasses_port"
    private const val ID = 4738
    private const val PORT_ID = 4739
    private const val OPEN_ID = 4740
    /**
     * A new ask in place of one the owner may be looking at: its notification comes this much later
     * (and the in-app prompt's Allow wakes this much later: BridgePrompt.kt), so a tap meant for the
     * one before can't allow the new one.
     */
    const val REPLACED_DELAY_MS = 1_500L

    fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.glasses_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.glasses_channel_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(PORT_CHANNEL, context.getString(R.string.glasses_port_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.glasses_port_channel_desc)
            },
        )
    }

    private fun canPost(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Another app holds the glasses' port (47380), so Lens can't reach this app: said while it
     * lasts (the bridge keeps trying), quietly. Lens never sends that app anything it could use
     * (bridge/BridgeCrypto.kt), but the glasses can't connect meanwhile.
     */
    fun portBusy(context: Context) {
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            context, 10, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.glasses_port_busy_body, LensBridge.PORT)
        val n = NotificationCompat.Builder(context, PORT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF7FFFA0.toInt())
            .setContentTitle(context.getString(R.string.glasses_port_busy_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(PORT_ID, n) }
    }

    /** The port is this app's again. */
    fun portFree(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(PORT_ID) }
    }

    fun ask(context: Context, r: BridgeAccess.Request) {
        if (!canPost(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // Each ask's Allow and Deny are its own (their data names the ask, their request code too):
        // a newer ask's notification can't reuse, and so redirect, an older one's buttons.
        fun action(allow: Boolean) = PendingIntent.getBroadcast(
            context, requestCode(r.id, allow),
            Intent(context, BridgeActions::class.java)
                .setAction(if (allow) BridgeActions.ALLOW else BridgeActions.DENY)
                .setData(android.net.Uri.parse("hypernova-bridge://ask/${r.id}"))
                .putExtra(BridgeActions.EXTRA_ID, r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context, 9, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.glasses_ask_body, r.code)
        // What a locked phone shows: that something asks, no code and no buttons.
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF7FFFA0.toInt())
            .setContentTitle(context.getString(R.string.glasses_ask_title))
            .setContentText(context.getString(R.string.glasses_ask_locked))
            .build()
        val allow = NotificationCompat.Action.Builder(0, context.getString(R.string.glasses_allow), action(true))
            // Whoever holds a locked phone mustn't be able to give a Lens the Mac: unlock first.
            .setAuthenticationRequired(true)
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF7FFFA0.toInt())
            .setContentTitle(context.getString(R.string.glasses_ask_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            // Gone by itself when the ask runs out (Lens asks again if it's still waiting).
            .setTimeoutAfter(BridgeAccess.REQUEST_TTL_MS)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .addAction(0, context.getString(R.string.glasses_deny), action(false))
            .addAction(allow)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    /** Whether [open] can be seen at all: notifications allowed for the app, and for the glasses' channel. */
    fun canOpen(context: Context): Boolean {
        if (!canPost(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return nm.areNotificationsEnabled() && nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Lens's "Open Hypernova" (`POST /bridge/open`, BridgeOpen.kt) while this app isn't on screen:
     * Android starts an app from the background only on the owner's tap, so a heads-up notification
     * asks for one, and the tap opens Settings → Glasses. Nothing private in it (the lock screen may
     * show it as it is); gone by itself after a minute, or when the app comes to the front.
     */
    fun open(context: Context): Boolean {
        if (!canOpen(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        val tap = PendingIntent.getActivity(
            context, 11,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_GLASSES).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF7FFFA0.toInt())
            .setContentTitle(context.getString(R.string.glasses_open_title))
            .setContentText(context.getString(R.string.glasses_open_body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(BridgeOpen.FRESH_MS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(tap)
            .build()
        return runCatching { nm.notify(OPEN_ID, n) }.isSuccess
    }

    fun cancelOpen(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(OPEN_ID) }
    }

    fun cancel(context: Context, @Suppress("UNUSED_PARAMETER") id: String) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }

    /** A PendingIntent request code of the ask [id]'s own, for its Allow or its Deny (never 9, 10 or 11: those open the app). */
    fun requestCode(id: String, allow: Boolean): Int = 100 + ((id.hashCode() and 0x0fffffff) shl 1) + (if (allow) 1 else 0)
}

/** Allow and Deny on the notification: reached only through its own PendingIntents (not exported). */
class BridgeActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val allow = intent.action == ALLOW
        // Android 12 and later ask for the unlock before sending Allow at all (setAuthenticationRequired),
        // and the keyguard may still read as locked for a moment after it: checked again here, the
        // owner's Allow would be lost. Android before 12 sends it from the lock screen as it is: then
        // it only opens the app, which asks again once the phone is unlocked (the ask stays, as does
        // the notification).
        if (allow && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && locked(context)) {
            runCatching {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            return
        }
        LensBridge.answer(id, allow = allow)
        BridgeNotice.cancel(context, id)
    }

    private fun locked(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: true

    companion object {
        const val ALLOW = "com.prudnikweb.hypernova.bridge.ALLOW"
        const val DENY = "com.prudnikweb.hypernova.bridge.DENY"
        const val EXTRA_ID = "id"
    }
}
