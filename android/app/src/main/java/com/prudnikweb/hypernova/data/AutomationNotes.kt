package com.prudnikweb.hypernova.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R

/**
 * Automations' notifications on the phone: their own channel ("What your automations found, and
 * runs that couldn't start"), a tap that opens the run's session or the automation, and the notes
 * already shown (the last 50 ids, remembered), so a reconnect or a restart never repeats one.
 */
class AutomationNotes(private val context: Context, private val server: String? = null) : AutomationNotifier {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val prefs = context.getSharedPreferences("hypernova", Context.MODE_PRIVATE)

    init {
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.au_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.au_channel_desc)
            },
        )
    }

    private fun seen(): List<String> = prefs.getString(SEEN, null)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    override fun shown(id: String): Boolean = id in seen()

    /** Marks [ids] shown; false when they all were already. */
    override fun firstTime(ids: List<String>): Boolean {
        val had = seen()
        val new = ids.filter { it !in had }
        if (new.isEmpty()) return false
        prefs.edit().putString(SEEN, (had + new).takeLast(50).joinToString(",")).apply()
        return true
    }

    private fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Automations on for this server (data/NotificationPrefs.kt); the note stays in the app's list either way. */
    private fun allowed(): Boolean = NotificationPrefsStore.get(context.storage).allows(server, Notice.Kind.Automation)

    /** [automationId] "" opens the Automations tab (several folded into one). */
    override fun post(key: String, title: String, text: String, sessionId: String?, automationId: String?) {
        if (!canPost() || nm == null || !allowed()) return
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        when {
            sessionId != null -> i.setAction(MainActivity.ACTION_OPEN_SESSION).putExtra(MainActivity.EXTRA_SESSION, sessionId)
            automationId != null -> i.setAction(ACTION_OPEN).putExtra(EXTRA_ID, automationId)
        }
        if (server != null) i.putExtra(MainActivity.EXTRA_SERVER, server)
        val open = PendingIntent.getActivity(context, ("auto:${server.orEmpty()}:$key").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF7FFFA0.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addExtras(AndroidNotifier.silenceable(server, Notice.Kind.Automation))
            .build()
        // One per automation: its next note takes the place of the last.
        runCatching { nm.notify(("automation:${scope()}${automationId ?: key}").hashCode(), n) }
    }

    /**
     * An ask step's question as a notification whose buttons are its answers (answered after the
     * phone is unlocked, data/NotifyActions.kt); a tap opens the automation. One per run, gone once
     * it's answered anywhere ([cancelAsk]).
     */
    override fun postAsk(runId: String, automationId: String?, name: String, ask: RunAsk) {
        if (!canPost() || nm == null || !allowed()) return
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction(ACTION_OPEN).putExtra(EXTRA_ID, automationId.orEmpty())
        if (server != null) i.putExtra(MainActivity.EXTRA_SERVER, server)
        val open = PendingIntent.getActivity(context, ("ask:$runId").hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val until = context.getString(R.string.wf_ask_notif_default, ask.default)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFE8B341.toInt())
            .setContentTitle(ask.question)
            .setContentText(name)
            .setSubText(until)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ask.question).setSummaryText(until))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addExtras(AndroidNotifier.silenceable(server, Notice.Kind.Automation))
            .setTimeoutAfter((ask.until - System.currentTimeMillis()).coerceAtLeast(60_000L))
        for ((k, answer) in ask.answers.take(3).withIndex()) {
            val act = Intent(context, NotifyActions::class.java).setAction(NotifyActions.ACTION_ANSWER)
                .putExtra(NotifyActions.EXTRA_RUN, runId).putExtra(NotifyActions.EXTRA_STEP, ask.stepId).putExtra(NotifyActions.EXTRA_ANSWER, answer)
            if (server != null) act.putExtra(NotifyActions.EXTRA_SERVER, server)
            val pi = PendingIntent.getBroadcast(context, ("ask:$runId:$k").hashCode(), act, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(NotificationCompat.Action.Builder(0, answer, pi).setAuthenticationRequired(true).build())
        }
        runCatching { nm.notify(askKey(runId), b.build()) }
    }

    override fun cancelAsk(runId: String) {
        runCatching { nm?.cancel(askKey(runId)) }
    }

    private fun askKey(runId: String) = ("ask:$runId").hashCode()

    /** Another computer's automation (not the first one's) gets notifications of its own: "<id>:". */
    private fun scope(): String = server?.takeIf { it != Servers.LEGACY_ID }?.let { "$it:" }.orEmpty()

    companion object {
        const val CHANNEL = "automations"
        private const val SEEN = "automationNotesShown"
        /** A notification tap: open this automation ("" for the Automations tab). */
        const val ACTION_OPEN = "com.prudnikweb.hypernova.OPEN_AUTOMATION"
        const val EXTRA_ID = "automation"

        /**
         * Automations' notes go nowhere on this phone: its notifications are off for the app (or not
         * allowed yet), or the Automations channel is (A16).
         */
        fun blocked(context: Context): Boolean {
            if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
            val ch = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)
            return ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE
        }

        /** The system's page for the Automations channel. */
        fun openChannel(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
