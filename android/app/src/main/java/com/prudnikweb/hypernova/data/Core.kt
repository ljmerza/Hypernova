package com.prudnikweb.hypernova.data

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.prudnikweb.hypernova.ConnectionService
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.widget.WidgetSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide state: the pairing and the live connection to the Mac. It outlives the
 * Activity, so the background service can keep the socket (and approvals) alive while the
 * app is closed. Screens reach it through [HubViewModel].
 */
object Core {
    lateinit var app: Application
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: PairingStore
        private set
    lateinit var notifier: Notifier
        private set

    var pairing by mutableStateOf<Pairing?>(null)
        private set
    var live by mutableStateOf<LiveHub?>(null)
        private set
    /** "Stay connected in the background" (default on). */
    var background by mutableStateOf(true)
        private set
    /** True while an Activity is on screen. */
    val visible: Boolean get() = onScreen
    /** State, so the home screen widgets know when they're live. */
    private var onScreen by mutableStateOf(false)
    /** Set by the ViewModel so hub errors can show as toasts. */
    var toastSink: ((String) -> Unit)? = null
    /** "Disconnect" was tapped on the notification: no background link until the app opens. */
    private var disconnected = false

    fun init(a: Application) {
        if (::app.isInitialized) return
        app = a
        ConnLog.init(a)
        announceWrites()
        store = PairingStore(a)
        notifier = Notifier(a)
        background = store.background
        pairing = store.load()
        pairing?.let(::startLive)
        WidgetSync.start(a)
    }

    private val applyPosted = AtomicBoolean(false)

    /**
     * Makes state written outside a snapshot (the hub's, from the socket) reach its readers'
     * [androidx.compose.runtime.snapshotFlow]s. Compose UI does this itself (GlobalSnapshotManager),
     * but only once an Activity shows something; a process the system starts for the background
     * service or a widget never does, and the widgets would stop at their first picture. One
     * notification per burst of writes, on the main thread. Harmless next to the UI's own.
     */
    private fun announceWrites() {
        val main = Handler(Looper.getMainLooper())
        Snapshot.registerGlobalWriteObserver {
            if (applyPosted.compareAndSet(false, true)) {
                main.post {
                    applyPosted.set(false)
                    Snapshot.sendApplyNotifications()
                }
            }
        }
    }

    private fun startLive(p: Pairing) {
        live?.close()
        // The built-in Tailscale node (Tsnet.kt): only one this phone signed in before, for a control
        // server the user agreed to. A first sign-in happens on the pairing screen or from Settings,
        // never by itself (a phone with the Tailscale app needs no second node).
        if (p.tailnetName != null && (p.controlUrl == null || p.controlConfirmed) && Tsnet.signedInBefore(app, p.controlUrl)) Tsnet.start(app, p.controlUrl)
        else if (Tsnet.control != p.controlUrl) Tsnet.stop()
        live = LiveHub(
            app, scope, p,
            toast = { toastSink?.invoke(it) },
            onApproval = notifier::approval,
            onApprovalGone = notifier::cancelApproval,
            isVisible = { visible },
            onNotice = notifier::notice,
            onAccountFine = notifier::cancelAccount,
            heldOpen = { ConnectionService.running },
            onTailnetMoved = ::tailnetMoved,
        ).also { it.start() }
        syncService()
    }

    /** The user agreed to the paired computer's own control server (Settings): kept, and the node may start. */
    fun confirmControl() {
        val p = pairing?.takeIf { it.controlUrl != null && !it.controlConfirmed } ?: return
        val next = p.copy(controlConfirmed = true)
        store.save(next)
        pairing = next
        Tsnet.start(app, next.controlUrl)
    }

    /**
     * The computer serves the phone API elsewhere on its tailnet now (TailnetFollow): kept, and the
     * link starts over with the new target (the route chooser is made for one).
     */
    private fun tailnetMoved(next: Pairing) {
        if (pairing?.token != next.token) return
        store.save(next)
        pairing = next
        startLive(next)
    }

    /** A new pairing: remember it and connect. */
    fun paired(p: Pairing) {
        store.save(p)
        pairing = p
        disconnected = false
        startLive(p)
    }

    /** Forget the Mac on this phone (its device certificate with it: the Mac has revoked it). */
    fun clear() {
        live?.close()
        live = null
        val was = pairing
        store.clear()
        DeviceCertStore(app).clear()
        // The node stops with the pairing. Its tailscale.com sign-in stays on disk (the user's own
        // account; pairing again needs none); a computer's own control server is forgotten with it.
        Tsnet.stop()
        if (was?.controlUrl != null) Tsnet.forget(app, was.controlUrl)
        pairing = null
        notifier.cancelAllApprovals()
        // Unpaired: the Mac's words again until the next computer says what it is.
        ComputerWords.clear(app)
        syncService()
    }

    fun stayConnected(on: Boolean) {
        background = on
        store.background = on
        if (on) disconnected = false
        syncService()
    }

    fun setVisible(v: Boolean) {
        onScreen = v
        if (v) {
            disconnected = false
            notifier.cancelAllApprovals()
        }
        live?.foreground(v)
        syncService()
    }

    /** From the notification's Disconnect action. */
    fun disconnect() {
        disconnected = true
        ConnectionService.stop(app)
        if (!visible) live?.pause()
    }

    /** Whether the background service should be running right now. */
    fun wantsService(): Boolean {
        val l = live ?: return false
        return background && !disconnected && l.link.state != LinkState.Unpaired
    }

    /**
     * Start or stop the service to match [wantsService]. Starting a foreground service is
     * only allowed while the app is visible (Android 12+), so a start waits for that.
     */
    fun syncService() {
        if (!::app.isInitialized) return
        if (wantsService()) {
            if (visible) ConnectionService.start(app)
        } else {
            ConnectionService.stop(app)
        }
    }
}

/** Notification channels, approval notifications and the quiet "connected" notification. */
class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val shown = HashSet<String>()

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

    fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(sessionId: String?, requestCode: Int): PendingIntent {
        val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (sessionId != null) i.setAction(MainActivity.ACTION_OPEN_SESSION).putExtra(MainActivity.EXTRA_SESSION, sessionId)
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
    fun approval(a: WApproval, s: Session?, failed: Boolean = false) {
        if (!canPost() || nm == null) return
        val project = s?.project?.takeIf { it.isNotBlank() }
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
            .setSubText(a.agentId?.let { a.agentLabel?.let { l -> context.getString(R.string.notif_from_agent, l) } ?: context.getString(R.string.notif_from_an_agent) })
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(failed)
            .setAutoCancel(true)
            .setContentIntent(openApp(a.sessionId, a.id.hashCode()))
            .apply {
                if (a.kind == "tool") {
                    addAction(answer(a.id, allow = false))
                    if (exact != null) addAction(answer(a.id, allow = true))
                }
            }
            .build()
        runCatching { nm.notify(a.id.hashCode(), n) }
        shown += a.id
    }

    /** Allow or Deny on an approval's notification, after the phone is unlocked (data/NotifyActions.kt). */
    private fun answer(id: String, allow: Boolean): NotificationCompat.Action {
        val intent = Intent(context, NotifyActions::class.java)
            .setAction(if (allow) NotifyActions.ACTION_ALLOW else NotifyActions.ACTION_DENY)
            .putExtra(NotifyActions.EXTRA_APPROVAL, id)
        val pi = PendingIntent.getBroadcast(context, (id + allow).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Action.Builder(0, context.getString(if (allow) R.string.allow else R.string.deny), pi)
            .setAuthenticationRequired(true)
            .build()
    }

    /** Answered from the notification and the Mac has it: the notification goes. */
    fun answered(id: String) {
        shown.remove(id)
        runCatching { nm?.cancel(id.hashCode()) }
    }

    /**
     * A session finished, waits in its terminal or failed (or a newer app is on the Mac) while
     * Hypernova isn't on screen. One notification per session, replaced by its next one.
     */
    fun notice(n: Notice) {
        if (!canPost() || nm == null) return
        // Its title as a phrase ("cosmos renderer finished"), never "cosmos renderer · Finished". A
        // limit's, the account's, a provider's, an automation's and an update's titles already say it.
        val title = when (n.kind) {
            Notice.Kind.Finished -> context.getString(R.string.notice_title_finished, n.title)
            Notice.Kind.Waiting -> context.getString(R.string.notice_title_waiting, n.title)
            Notice.Kind.Error -> context.getString(R.string.notice_title_error, n.title)
            Notice.Kind.Backpack -> context.getString(R.string.notice_title_backpack, n.title)
            Notice.Kind.Approval, Notice.Kind.Question, Notice.Kind.Plan -> context.getString(R.string.notice_title_needs_you, n.title)
            else -> n.title
        }
        val open = if (n.kind == Notice.Kind.Update && n.text.isNotEmpty()) {
            PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_UPDATE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else openApp(n.sessionId, (n.sessionId ?: n.id).hashCode())
        val notif = NotificationCompat.Builder(context, SESSIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(if (n.kind == Notice.Kind.Error) 0xFFFF7878.toInt() else 0xFF7FFFA0.toInt())
            .setContentTitle(title)
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
            .apply {
                // Reply from the notification (after unlocking): the words go to the session as a
                // prompt, as from its composer.
                if ((n.kind == Notice.Kind.Finished || n.kind == Notice.Kind.Waiting) && n.sessionId != null) addAction(reply(n.sessionId))
                // Backpack's "+2 h" without opening the app first (it extends and stays in the background).
                if (n.action == Notice.ACTION_EXTEND) {
                    val extend = PendingIntent.getActivity(
                        context, 8, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_EXTEND).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    addAction(0, context.getString(R.string.notice_extend), extend)
                }
            }
            .build()
        runCatching { nm.notify(noticeId(n.sessionId ?: n.id), notif) }
    }

    /** One notification per session (or per [Notice.id] without one), replaced by its next. */
    private fun noticeId(key: String) = ("session:$key").hashCode()

    /** Reply on a session's notice: a line of text, sent as a prompt once the phone is unlocked. */
    private fun reply(sessionId: String): NotificationCompat.Action {
        val intent = Intent(context, NotifyActions::class.java).setAction(NotifyActions.ACTION_REPLY).putExtra(NotifyActions.EXTRA_SESSION, sessionId)
        // Mutable: the system adds the typed words to it.
        val pi = PendingIntent.getBroadcast(context, ("reply:$sessionId").hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        // "Tell Claude what's next", or Codex for a Codex session.
        val codex = Core.live?.session(sessionId)?.codex == true
        val input = androidx.core.app.RemoteInput.Builder(NotifyActions.KEY_REPLY).setLabel(context.getString(R.string.notif_reply_hint, com.prudnikweb.hypernova.ui.components.agentName(context.resources, codex))).build()
        return NotificationCompat.Action.Builder(0, context.getString(R.string.notif_reply), pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setAuthenticationRequired(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    /** A reply from a session's notice reached the Mac: the notice goes. */
    fun replied(sessionId: String) {
        runCatching { nm?.cancel(noticeId(sessionId)) }
    }

    /**
     * A reply that didn't get through: the notice says so (a notification answered with words
     * must change, or it keeps turning), and Reply is there again.
     */
    fun replyFailed(sessionId: String, title: String, why: String) {
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
            .setContentIntent(openApp(sessionId, sessionId.hashCode()))
            .addAction(reply(sessionId))
            .build()
        runCatching { nm.notify(noticeId(sessionId), notif) }
    }

    /**
     * The account needs nothing from the owner any more (it's fine, or only resting at a limit
     * that ends by itself): the notification that asked them to sign in or renew goes.
     */
    fun cancelAccount() {
        runCatching { nm?.cancel(noticeId(AccountWords.NOTICE_ID)) }
    }

    fun cancelApproval(id: String) {
        if (shown.remove(id)) runCatching { nm?.cancel(id.hashCode()) }
    }

    fun cancelAllApprovals() {
        for (id in shown) runCatching { nm?.cancel(id.hashCode()) }
        shown.clear()
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
    }
}
