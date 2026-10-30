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
import androidx.compose.runtime.mutableStateMapOf
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
 * Process-wide state: the paired computers and the live connections to them. It outlives the
 * Activity, so the background service can keep the sockets (and approvals) alive while the app is
 * closed. Screens reach it through [HubViewModel].
 *
 * Several computers can be paired (data/Servers.kt). One is active: [pairing] and [live] are its, and
 * every screen, the widgets and the glasses show it, as they showed the one pairing before. Each of
 * the others has a [LiveHub] of its own too, watched in the background: its link, its approvals and
 * notices as notifications that name it (a tap switches to it), and its needs-you count for the chip
 * and the switcher. Switching makes a watched hub the active one as it is, already linked.
 */
object Core {
    lateinit var app: Application
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: PairingStore
        private set
    lateinit var notifier: Notifier
        private set

    /** The paired computers, in the order they were paired. */
    var servers by mutableStateOf<List<Server>>(emptyList())
        private set
    /** The computer the screens show (null: none paired). */
    var activeId by mutableStateOf<String?>(null)
        private set
    /** A hub per paired computer, by [Server.id]. */
    private val hubs = mutableStateMapOf<String, LiveHub>()

    /** The active computer's pairing. */
    val pairing: Pairing? get() = activeId?.let { id -> servers.firstOrNull { it.id == id }?.pairing }
    /** The active computer's hub. */
    val live: LiveHub? get() = activeId?.let { hubs[it] }
    /** Every paired computer's hub, the active one's first. */
    val allHubs: List<LiveHub> get() = Servers.activeFirst(servers, activeId).mapNotNull { hubs[it.id] }

    /** A computer's hub; the active one's for null (a notification from before there were several). */
    fun hub(server: String?): LiveHub? = Servers.target(server, activeId, hubs.keys)?.let { hubs[it] }

    /** Where an Allow or Deny goes ([Servers.approvalTarget]): the computer named, else the one the approval waits on. */
    fun hubForApproval(server: String?, approvalId: String): LiveHub? =
        Servers.approvalTarget(server, approvalId, activeId, hubs.mapValues { (_, h) -> h.pendingApprovals.map { it.id } })?.let { hubs[it] }

    /** A computer's name: what it calls itself now, else what it was called when paired. */
    fun nameOf(server: String): String? =
        hubs[server]?.mac?.name ?: servers.firstOrNull { it.id == server }?.pairing?.macName

    /** What a notification from [server] says about where it's from: its name, while more than one computer is paired. */
    fun notifyLabel(server: String?): String? = server?.takeIf { servers.size > 1 }?.let(::nameOf)

    /** Each paired computer for the switcher and the chip: the active one first. */
    val statuses: List<ServerStatus>
        get() = Servers.activeFirst(servers, activeId).map { s ->
            val h = hubs[s.id]
            ServerStatus(s.id, h?.mac?.name ?: s.pairing.macName, h?.link?.state ?: LinkState.Connecting, h?.sessions?.let(Servers::needsYou) ?: 0, s.id == activeId)
        }

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
        // The list of computers; the first run after the update makes it from the one pairing kept before.
        val (list, active) = store.servers.load()
        servers = list
        activeId = active
        syncNode()
        for (s in list) startHub(s)
        syncService()
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

    private fun save() = store.servers.save(servers, activeId)

    /** [s]'s hub, made anew (a new pairing, or the computer moved on its tailnet). */
    private fun startHub(s: Server) {
        hubs.remove(s.id)?.close()
        val id = s.id
        val active = { activeId == id }
        hubs[id] = LiveHub(
            app, scope, s.pairing,
            // A watched computer's errors aren't the screen's: only the active one's toast.
            toast = { if (active()) toastSink?.invoke(it) },
            onApproval = { a, session -> notifier.approval(a, session, server = id) },
            onApprovalGone = notifier::cancelApproval,
            // A banner only for the computer on screen; a watched one's news are notifications.
            isVisible = { visible && active() },
            onNotice = { notifier.notice(it, server = id) },
            onAccountFine = { notifier.cancelAccount(id) },
            heldOpen = { ConnectionService.running },
            onTailnetMoved = { next -> tailnetMoved(id, next) },
            serverId = id,
            onScreen = { visible },
            isActive = active,
        ).also { it.start() }
    }

    /**
     * The built-in Tailscale node (Tsnet.kt), for the computers' tailnet: only one this phone signed
     * in before, for a control server the user agreed to, the active computer's first
     * ([Servers.nodePlan]). A first sign-in happens on the pairing screen or from Settings, never by
     * itself (a phone with the Tailscale app needs no second node).
     */
    private fun syncNode() {
        val order = Servers.activeFirst(servers, activeId).map { it.pairing }
        when (val plan = Servers.nodePlan(order, { Tsnet.signedInBefore(app, it) }, Tsnet.control)) {
            is Servers.NodePlan.Start -> Tsnet.start(app, plan.control)
            Servers.NodePlan.Stop -> Tsnet.stop()
            Servers.NodePlan.Leave -> Unit
        }
    }

    /** The user agreed to the active computer's own control server (Settings): kept, and the node may start. */
    fun confirmControl() {
        val id = activeId ?: return
        val p = pairing?.takeIf { it.controlUrl != null && !it.controlConfirmed } ?: return
        val next = p.copy(controlConfirmed = true)
        servers = servers.map { if (it.id == id) Server(id, next) else it }
        save()
        Tsnet.start(app, next.controlUrl)
    }

    /**
     * The computer serves the phone API elsewhere on its tailnet now (TailnetFollow): kept, and its
     * link starts over with the new target (the route chooser is made for one).
     */
    private fun tailnetMoved(id: String, next: Pairing) {
        val s = servers.firstOrNull { it.id == id } ?: return
        if (s.pairing.token != next.token) return
        val moved = Server(id, next)
        servers = servers.map { if (it.id == id) moved else it }
        save()
        syncNode()
        startHub(moved)
        syncService()
    }

    /**
     * A new pairing: remembered (in the place of the same computer paired before, else added) and
     * connected, and it becomes the computer on screen. The others stay paired.
     */
    fun paired(p: Pairing) {
        val (list, id) = Servers.withPairing(servers, p)
        servers = list
        activeId = id
        save()
        disconnected = false
        syncNode()
        startHub(list.first { it.id == id })
        activated(id)
    }

    /**
     * The switcher: [id] becomes the computer the screens show, its hub as it is (linked already, or
     * on its way). The one shown before goes on being watched in the background.
     */
    fun switchTo(id: String) {
        if (id == activeId || servers.none { it.id == id }) return
        activeId = id
        save()
        syncNode()
        activated(id)
    }

    /** [id] is on screen now: its words, its approvals seen (their notifications go), a link at once if it's down. */
    private fun activated(id: String) {
        val h = hubs[id]
        if (h != null) {
            if (h.synced) ComputerWords.set(app, h.mac.platform)
            h.foreground(visible)
        }
        if (visible) notifier.cancelAllApprovals(id)
        syncService()
    }

    /** Forget the active computer on this phone (after "Forget" or "This phone was unpaired"). */
    fun clear() {
        activeId?.let(::forget)
    }

    /**
     * Forget [id] on this phone (its device certificate with it: the computer has revoked it). The
     * next computer left, if any, is shown in its place.
     */
    fun forget(id: String) {
        val was = servers.firstOrNull { it.id == id } ?: return
        hubs.remove(id)?.close()
        val left = servers.filter { it.id != id }
        val next = Servers.activeAfterRemoval(servers, id, activeId)
        val switched = next != activeId
        servers = left
        activeId = next
        save()
        // The key stays while another computer's certificate is for it.
        DeviceCertStore(app, id).clear(dropKey = left.isEmpty())
        // A computer's own control server is forgotten with the last computer on it. The tailscale.com
        // sign-in stays on disk (the user's own account; pairing again needs none).
        val control = was.pairing.controlUrl
        if (control != null && left.none { it.pairing.controlUrl == control }) Tsnet.forget(app, control)
        if (left.isEmpty()) Tsnet.stop() else syncNode()
        notifier.cancelAllApprovals(id)
        if (next == null) {
            // Unpaired: the Mac's words again until the next computer says what it is.
            ComputerWords.clear(app)
            syncService()
        } else if (switched) {
            activated(next)
        } else {
            syncService()
        }
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
            // The active computer's approvals are on screen now; a watched one's stay notifications.
            activeId?.let(notifier::cancelAllApprovals)
        }
        for (h in hubs.values) h.foreground(v)
        syncService()
    }

    /** From the notification's Disconnect action: every computer's link, until the app opens. */
    fun disconnect() {
        disconnected = true
        ConnectionService.stop(app)
        if (!visible) for (h in hubs.values) h.pause()
    }

    /** Whether the background service should be running right now: some computer is still paired with this phone. */
    fun wantsService(): Boolean {
        if (hubs.isEmpty()) return false
        return background && !disconnected && hubs.values.any { it.link.state != LinkState.Unpaired }
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

    fun canPost(): Boolean = Build.VERSION.SDK_INT < 33 ||
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
    fun approval(a: WApproval, s: Session?, failed: Boolean = false, server: String? = null) {
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
    fun answered(id: String) {
        shown.remove(id)
        runCatching { nm?.cancel(id.hashCode()) }
    }

    /**
     * A session finished, waits in its terminal or failed (or a newer app is on the Mac) while
     * Hypernova isn't on screen. One notification per session, replaced by its next one.
     */
    fun notice(n: Notice, server: String? = null) {
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
            val i = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_UPDATE).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (server != null) i.putExtra(MainActivity.EXTRA_SERVER, server)
            PendingIntent.getActivity(context, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else openApp(n.sessionId, keyed(server, n.sessionId ?: n.id).hashCode(), server)
        val notif = NotificationCompat.Builder(context, SESSIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(if (n.kind == Notice.Kind.Error) 0xFFFF7878.toInt() else 0xFF7FFFA0.toInt())
            .setContentTitle(title)
            .setContentText(n.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(open)
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
    fun replied(sessionId: String, server: String? = null) {
        runCatching { nm?.cancel(noticeId(server, sessionId)) }
    }

    /**
     * A reply that didn't get through: the notice says so (a notification answered with words
     * must change, or it keeps turning), and Reply is there again.
     */
    fun replyFailed(sessionId: String, title: String, why: String, server: String? = null) {
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
    fun cancelAccount(server: String? = null) {
        runCatching { nm?.cancel(noticeId(server, AccountWords.NOTICE_ID)) }
    }

    fun cancelApproval(id: String) {
        if (shown.remove(id) != null) runCatching { nm?.cancel(id.hashCode()) }
    }

    /** Every approval notified for [server] goes (its approvals are on screen now, or it's forgotten); null: every one. */
    fun cancelAllApprovals(server: String? = null) {
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
    }
}
