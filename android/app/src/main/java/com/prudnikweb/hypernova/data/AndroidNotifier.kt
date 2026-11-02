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

/** Notification channels, approval notifications and the quiet "connected" notification. */
class AndroidNotifier(private val context: Context) : Notifier {
    private val nm = context.getSystemService(NotificationManager::class.java)
    /** The approvals notified, each with the computer it's waiting on ("" for none named). */
    private val shown = HashMap<String, String>()

    init {
        nm?.createNotificationChannel(
            NotificationChannel(APPROVALS, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_desc)
            },
        )
        nm?.createNotificationChannel(
            NotificationChannel(SESSIONS, context.getString(R.string.notif_sessions_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_sessions_channel_desc)
            },
        )
        nm?.createNotificationChannel(
            NotificationChannel(CONNECTION, context.getString(R.string.notif_connection_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_connection_channel_desc)
                setShowBadge(false)
            },
        )
    }

    override fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Opens the app ([server] made the active computer first, when one is named), at [sessionId] if given. */
    private fun openApp(sessionId: String?, requestCode: Int, server: String? = null): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (sessionId != null) i.setAction(MainActivity.ACTION_OPEN_SESSION).putExtra(MainActivity.EXTRA_SESSION, sessionId)
        if (server != null) i.putExtra(MainActivity.EXTRA_SERVER, server)
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * A key that is [server]'s own (data/Servers.kt): the first computer's as it always was, another's
     * with its id, so two computers' notifications (their batteries, their accounts) never replace
     * each other, nor share a tap.
     */
    private fun keyed(server: String?, key: String): String = if (server == null || server == Servers.LEGACY_ID) key else "$server:$key"

    /**
     * The small line naming where it's from, while several computers are paired: "z2", or
     * "z2, from implement:fix" for an agent's ask. Null with one computer: [agent] alone, as before.
     */
    private fun from(server: String?, agent: String?, anAgent: Boolean): String? {
        val name = Core.notifyLabel(server)
        return when {
            name == null -> agent?.let { context.getString(R.string.notif_from_agent, it) } ?: context.getString(R.string.notif_from_an_agent).takeIf { anAgent }
            agent != null -> context.getString(R.string.notif_server_from_agent, name, agent)
            anAgent -> context.getString(R.string.notif_server_from_an_agent, name)
            else -> name
        }
    }

    /**
     * "0stems needs you" (a question, a plan in their words): opens the session. One from an agent
     * the main agent started names it, "From implement:fix" (LiveHub only calls this for one still
     * waiting after 10 seconds: agents often settle their own). Words only: a notification can't
     * draw, so they read as a phrase, never "·"-joined.
     *
     * A tool's request carries Deny, and Allow when its exact command shows whole (the widget's
     * rule, [WidgetModel.answerable], kept short enough for the expanded notification); both ask
     * for the phone to be unlocked first, and the lock screen shows neither the command nor the
     * buttons. A question or a plan only opens the session ([setAuthenticationRequired] needs
     * Android 12; before it, the buttons go to the expanded, unlocked notification only).
     */
    override fun approval(a: WApproval, s: Session?, failed: Boolean, server: String?) {
        if (!canPost() || nm == null) return
        if (!NotificationPrefsStore.get(context.storage).allows(server, Notice.Kind.Approval)) return
        // The session's name, as on its card (owner, 2026-10-31); its folder only when it has none.
        val untitled = context.getString(R.string.untitled_session)
        val project = (s?.name?.takeIf { it.isNotBlank() && it != untitled } ?: s?.project)?.takeIf { it.isNotBlank() }
        val title = when (a.kind) {
            "question" -> project?.let { context.getString(R.string.notif_question, it) } ?: context.getString(R.string.notif_question_plain)
            "plan" -> project?.let { context.getString(R.string.notif_plan, it) } ?: context.getString(R.string.notif_plan_plain)
            else -> project?.let { context.getString(R.string.notif_tool, it) } ?: context.getString(R.string.notif_tool_plain)
        }
        val exact = com.prudnikweb.hypernova.widget.WidgetModel.answerable(a, NOTIFY_MAX)?.takeIf { it.lines().size <= NOTIFY_LINES }
        val big = listOfNotNull(
            context.getString(R.string.notif_answer_failed).takeIf { failed },
            exact ?: a.summary,
            a.reason,
        ).joinToString("\n")
        // What the lock screen shows: who asks, never what.
        val public = NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFE8B341.toInt())
            .setContentTitle(title)
            .setContentText(context.getString(R.string.notif_unlock_to_see))
            .build()
        val n = NotificationCompat.Builder(context, APPROVALS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFE8B341.toInt())
            .setContentTitle(title)
            .setContentText(if (failed) context.getString(R.string.notif_answer_failed) else a.summary)
            // Who asks beyond the session: another computer's name, an agent the main agent started.
            .setSubText(from(server, a.agentLabel.takeIf { a.agentId != null }, anAgent = a.agentId != null))
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(failed)
            .setAutoCancel(true)
            .setContentIntent(openApp(a.sessionId, a.id.hashCode(), server))
            .addExtras(silenceable(server, Notice.Kind.Approval))
            .apply {
                if (a.kind == "tool") {
                    addAction(answer(a.id, allow = false, server))
                    if (exact != null) addAction(answer(a.id, allow = true, server))
                }
            }
            .build()
        runCatching { nm.notify(a.id.hashCode(), n) }
        shown[a.id] = server.orEmpty()
    }

    /** Allow or Deny on an approval's notification, after the phone is unlocked (data/NotifyActions.kt). */
    private fun answer(id: String, allow: Boolean, server: String?): NotificationCompat.Action {
        val intent = Intent(context, NotifyActions::class.java)
            .setAction(if (allow) NotifyActions.ACTION_ALLOW else NotifyActions.ACTION_DENY)
            .putExtra(NotifyActions.EXTRA_APPROVAL, id)
        if (server != null) intent.putExtra(NotifyActions.EXTRA_SERVER, server)
        val pi = PendingIntent.getBroadcast(context, (id + allow).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, context.getString(if (allow) R.string.allow else R.string.deny), pi)
            .setAuthenticationRequired(true)
            .build()
    }

    /** Answered from the notification and the Mac has it: the notification goes. */
    override fun answered(id: String) {
        shown.remove(id)
        runCatching { nm?.cancel(id.hashCode()) }
    }

    /**
     * A session finished, waits in its terminal or failed (or a newer app is on the Mac) while
     * Hypernova isn't on screen. One notification per session, replaced by its next one; one that's
     * silenced (data/NotificationPrefs.kt) still takes the last one's place, so a "waiting" doesn't
     * stay behind once a session has moved on to something the owner turned off.
     */
    override fun notice(n: Notice, server: String?) {
        if (!canPost() || nm == null) return
        if (!NotificationPrefsStore.get(context.storage).allows(server, n.kind)) {
            runCatching { nm.cancel(noticeId(server, n.sessionId ?: n.id)) }
            return
        }
        // Its title as a phrase ("cosmos renderer finished"), never "cosmos renderer · Finished". A
        // limit's, the account's, a provider's, an automation's and an update's titles already say it.
        val title = when (n.kind) {
            Notice.Kind.Finished -> context.getString(R.string.notice_title_finished, n.title)
            Notice.Kind.Waiting -> context.getString(R.string.notice_title_waiting, n.title)
            Notice.Kind.Error -> context.getString(R.string.notice_title_error, n.title)
            Notice.Kind.Backpack -> context.getString(R.string.notice_title_backpack, n.title)
            Notice.Kind.Approval, Notice.Kind.Question, Notice.Kind.Plan, Notice.Kind.Power -> context.getString(R.string.notice_title_needs_you, n.title)
            else -> n.title
        }
        val open = if (n.kind == Notice.Kind.Update && n.text.isNotEmpty()) {
            val i = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_UPDATE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (server != null) i.putExtra(MainActivity.EXTRA_SERVER, server)
            PendingIntent.getActivity(context, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else if (n.kind == Notice.Kind.ServerUpdateFailed) {
            val i = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_SERVER_UPDATE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (server != null) i.putExtra(MainActivity.EXTRA_SERVER, server)
            // Each computer's its own: extras don't tell two PendingIntents apart.
            PendingIntent.getActivity(context, ("server-update:" + keyed(server, n.id)).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else openApp(n.sessionId, keyed(server, n.sessionId ?: n.id).hashCode(), server)
        val notif = NotificationCompat.Builder(context, SESSIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(if (n.kind == Notice.Kind.Error || n.kind == Notice.Kind.ServerUpdateFailed) 0xFFFF7878.toInt() else 0xFF7FFFA0.toInt())
            .setContentTitle(title)
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addExtras(silenceable(server, n.kind))
            // Another computer's (while several are paired): its name under the title.
            .setSubText(Core.notifyLabel(server))
            .apply {
                // Reply from the notification (after unlocking): the words go to the session as a
                // prompt, as from its composer.
                if ((n.kind == Notice.Kind.Finished || n.kind == Notice.Kind.Waiting) && n.sessionId != null) addAction(reply(n.sessionId, server))
                // Backpack's "+2 h" without opening the app first (it extends and stays in the background).
                if (n.action == Notice.ACTION_EXTEND) {
                    val i = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_EXTEND).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if (server != null) i.putExtra(MainActivity.EXTRA_SERVER, server)
                    val extend = PendingIntent.getActivity(context, 8, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(0, context.getString(R.string.notice_extend), extend)
                }
            }
            .build()
        runCatching { nm.notify(noticeId(server, n.sessionId ?: n.id), notif) }
    }

    /** One notification per session (or per [Notice.id] without one), replaced by its next; each computer's its own. */
    private fun noticeId(server: String?, key: String) = ("session:" + keyed(server, key)).hashCode()

    /** Reply on a session's notice: a line of text, sent as a prompt (to [server]) once the phone is unlocked. */
    private fun reply(sessionId: String, server: String?): NotificationCompat.Action {
        val intent = Intent(context, NotifyActions::class.java).setAction(NotifyActions.ACTION_REPLY).putExtra(NotifyActions.EXTRA_SESSION, sessionId)
        if (server != null) intent.putExtra(NotifyActions.EXTRA_SERVER, server)
        // Mutable: the system adds the typed words to it.
        val pi = PendingIntent.getBroadcast(context, ("reply:" + keyed(server, sessionId)).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        // "Tell Claude what's next", or Codex for a Codex session.
        val codex = Core.hub(server)?.session(sessionId)?.codex == true
        val input = androidx.core.app.RemoteInput.Builder(NotifyActions.KEY_REPLY).setLabel(context.getString(R.string.notif_reply_hint, com.prudnikweb.hypernova.ui.components.agentName(context.resources, codex))).build()
        return NotificationCompat.Action.Builder(0, context.getString(R.string.notif_reply), pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    /** A reply from a session's notice reached the Mac: the notice goes. */
    override fun replied(sessionId: String, server: String?) {
        runCatching { nm?.cancel(noticeId(server, sessionId)) }
    }

    /**
     * What the switches now silence goes from the shade: read from Android's own list, each with the
     * server and kind it was posted with ([silenceable]), so it holds for one posted before the
     * process last ended. The permanent notification, the voice's and a failed reply carry neither.
     */
    override fun cancelSilenced() {
        val manager = nm ?: return
        val prefs = NotificationPrefsStore.get(context.storage)
        val active = runCatching { manager.activeNotifications }.getOrNull() ?: return
        for (sbn in active) {
            val extras = sbn.notification.extras
            val kind = extras.getString(EXTRA_KIND)?.let { k -> Notice.Kind.entries.firstOrNull { it.name == k } } ?: continue
            if (prefs.allows(extras.getString(EXTRA_SERVER), kind)) continue
            runCatching { manager.cancel(sbn.tag, sbn.id) }
            shown.keys.removeAll { it.hashCode() == sbn.id }
        }
    }

    /**
     * A reply that didn't get through: the notice says so (a notification answered with words
     * must change, or it keeps turning), and Reply is there again. Whatever the switches say: it
     * answers what the owner just did, and the words would be lost without it.
     */
    override fun replyFailed(sessionId: String, title: String, why: String, server: String?) {
        if (!canPost() || nm == null) return
        val notif = NotificationCompat.Builder(context, SESSIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF7878.toInt())
            .setContentTitle(title)
            .setContentText(why)
            .setStyle(NotificationCompat.BigTextStyle().bigText(why))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(sessionId, keyed(server, sessionId).hashCode(), server))
            .setSubText(Core.notifyLabel(server))
            .addAction(reply(sessionId, server))
            .build()
        runCatching { nm.notify(noticeId(server, sessionId), notif) }
    }

    /**
     * The account needs nothing from the owner any more (it's fine, or only resting at a limit
     * that ends by itself): the notification that asked them to sign in or renew goes.
     */
    override fun cancelAccount(server: String?) {
        runCatching { nm?.cancel(noticeId(server, AccountWords.NOTICE_ID)) }
    }

    /** That server's update went through after all, or the setting was turned off: the failure's notification goes. */
    override fun cancelServerUpdate(server: String?) {
        runCatching { nm?.cancel(noticeId(server, SERVER_UPDATE_NOTICE)) }
    }

    override fun cancelApproval(id: String) {
        if (shown.remove(id) != null) runCatching { nm?.cancel(id.hashCode()) }
    }

    /** Every approval notified for [server] goes (its approvals are on screen now, or it's forgotten); null: every one. */
    override fun cancelAllApprovals(server: String?) {
        val ids = shown.filter { server == null || it.value == server }.keys.toList()
        for (id in ids) {
            runCatching { nm?.cancel(id.hashCode()) }
            shown.remove(id)
        }
    }

    /** The service's permanent notification: Mac name, a calm summary, and Disconnect. */
    fun connection(title: String, text: String, disconnect: PendingIntent): android.app.Notification =
        NotificationCompat.Builder(context, CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF7FFFA0.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(null, 1))
            .addAction(0, context.getString(R.string.notif_disconnect), disconnect)
            .build()

    companion object {
        const val APPROVALS = "approvals"
        const val SESSIONS = "sessions"
        const val CONNECTION = "connection"
        const val CONNECTION_ID = 4732
        /** An Allow on a notification needs the whole command in its expanded view: this many characters, this many lines. */
        const val NOTIFY_MAX = 240
        const val NOTIFY_LINES = 4
        private const val EXTRA_KIND = "hypernova.kind"
        private const val EXTRA_SERVER = "hypernova.server"

        /** What a notification carries so [cancelSilenced] can tell whose it is and what kind. */
        fun silenceable(server: String?, kind: Notice.Kind): android.os.Bundle = android.os.Bundle().apply {
            putString(EXTRA_KIND, kind.name)
            if (server != null) putString(EXTRA_SERVER, server)
        }
    }
}
