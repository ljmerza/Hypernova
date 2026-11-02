package com.prudnikweb.hypernova.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 *
 * "All servers" ([all]): Home lists every hub's sessions together; the active one stays the one
 * picked last, for the other tabs, except while a session of another is open from Home ([visit],
 * undone by [backToPicked]), so that session's screens are its own computer's.
 */
object Core {
    /** The platform it was started on ([init]). */
    lateinit var platform: Platform
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
    /**
     * The one last picked in the switcher (or paired), the one kept between runs. It is [activeId]
     * except while a session of another computer is open from Home's "All servers" ([visit]).
     */
    private var picked: String? = null
    /** "All servers" chosen in the switcher, kept between runs; it shows only while [all]. */
    private var allChosen by mutableStateOf(false)
    /** Home shows every paired computer's sessions together (two or more paired, "All servers" chosen). */
    val all: Boolean get() = Servers.allShown(allChosen, servers.size)
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

    /** Each paired computer with its hub, in the order they were paired: Home's "All servers". */
    val everyHub: List<Pair<ServerStatus, LiveHub>>
        get() = servers.mapNotNull { s ->
            val h = hubs[s.id] ?: return@mapNotNull null
            ServerStatus(s.id, h.mac.name.ifBlank { s.pairing.macName }, h.link.state, Servers.needsYou(h.sessions), s.id == activeId) to h
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

    fun init(p: Platform) {
        if (::platform.isInitialized) return
        platform = p
        ConnLog.init(p.storage.filesDir, p.main, p.log)
        announceWrites()
        store = PairingStore(p.storage)
        notifier = p.notifier()
        background = store.background
        // The list of computers; the first run after the update makes it from the one pairing kept before.
        val (list, active) = store.servers.load()
        servers = list
        activeId = active
        picked = active
        allChosen = store.servers.all
        for (s in list) startHub(s)
        syncService()
        p.started()
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
        val main = platform.main
        Snapshot.registerGlobalWriteObserver {
            if (applyPosted.compareAndSet(false, true)) {
                main.post {
                    applyPosted.set(false)
                    Snapshot.sendApplyNotifications()
                }
            }
        }
    }

    /** The one picked is what's kept (a visit from "All servers" isn't). */
    private fun save() = store.servers.save(servers, picked)

    /** [id] becomes the computer on screen and the one picked. */
    private fun pick(id: String?) {
        activeId = id
        picked = id
    }

    private fun chooseAll(on: Boolean) {
        if (allChosen == on) return
        allChosen = on
        store.servers.all = on
    }

    /** [s]'s hub, made anew (a new pairing, or the same computer paired again). */
    private fun startHub(s: Server) {
        hubs.remove(s.id)?.close()
        val id = s.id
        val active = { activeId == id }
        hubs[id] = LiveHub(
            platform, scope, s.pairing,
            // A watched computer's errors aren't the screen's: only the active one's toast.
            toast = { if (active()) toastSink?.invoke(it) },
            onApproval = { a, session -> notifier.approval(a, session, server = id) },
            onApprovalGone = notifier::cancelApproval,
            // A banner only for the computer on screen; a watched one's news are notifications.
            isVisible = { visible && active() },
            onNotice = { notifier.notice(it, server = id) },
            onSessionReplaced = { notifier.replied(it, server = id) },
            onAccountFine = { notifier.cancelAccount(id) },
            onServerUpdateFine = { notifier.cancelServerUpdate(id) },
            heldOpen = { platform.background.running },
            serverId = id,
            onScreen = { visible },
            isActive = active,
        ).also { it.start() }
    }

    /**
     * A new pairing: remembered (in the place of the same computer paired before, else added) and
     * connected, and it becomes the computer on screen. The others stay paired.
     */
    fun paired(p: Pairing) {
        val (list, id) = Servers.withPairing(servers, p)
        servers = list
        pick(id)
        // The new one on screen alone, not among all of them.
        chooseAll(false)
        save()
        disconnected = false
        startHub(list.first { it.id == id })
        activated(id)
    }

    /**
     * The switcher: [id] becomes the computer the screens show, its hub as it is (linked already, or
     * on its way). The one shown before goes on being watched in the background.
     */
    fun switchTo(id: String) {
        if (servers.none { it.id == id }) return
        // One computer picked: Home is that one's alone again.
        chooseAll(false)
        if (id == activeId && id == picked) return
        val changed = id != activeId
        pick(id)
        save()
        if (changed) activated(id)
    }

    /**
     * The switcher's "All servers": Home shows every paired computer's sessions together; History,
     * Automations, Settings and Statistics stay on the one picked last.
     */
    fun showAll() {
        if (servers.size < 2) return
        chooseAll(true)
        backToPicked()
    }

    /**
     * From Home's "All servers": a session of [id] opens, so [id] is the computer on screen for its
     * screens (its chat, sheet, terminal), without becoming the one picked. [backToPicked] undoes it
     * once Home is back.
     */
    fun visit(id: String) {
        if (servers.none { it.id == id }) return
        if (!all) return switchTo(id)
        if (id == activeId) return
        activeId = id
        activated(id)
    }

    /** Home is back in "All servers": the one picked last is the computer on screen again. */
    fun backToPicked() {
        val p = picked?.takeIf { id -> servers.any { it.id == id } } ?: return
        if (p == activeId) return
        activeId = p
        activated(p)
    }

    /** [id] is on screen now: its words, its approvals seen (their notifications go), a link at once if it's down. */
    private fun activated(id: String) {
        val h = hubs[id]
        if (h != null) {
            if (h.synced) ComputerWords.set(platform.storage, h.mac.platform)
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
        if (servers.none { it.id == id }) return
        hubs.remove(id)?.close()
        val left = servers.filter { it.id != id }
        // After the one picked (a visit from "All servers" ends with it).
        val next = Servers.activeAfterRemoval(servers, id, picked ?: activeId)
        val switched = next != activeId
        servers = left
        pick(next)
        save()
        // The key stays while another computer's certificate is for it.
        DeviceCertStore(platform.storage, platform.deviceKeys, id).clear(dropKey = left.isEmpty())
        notifier.cancelAllApprovals(id)
        NotificationPrefsStore.set(platform.storage, NotificationPrefsStore.get(platform.storage).without(id))
        if (next == null) {
            // Unpaired: the Mac's words again until the next computer says what it is.
            ComputerWords.clear(platform.storage)
            syncService()
        } else if (switched) {
            activated(next)
        } else {
            syncService()
        }
    }

    /** Settings' notification switches (data/NotificationPrefs.kt): kept, and what they now silence goes from the shade. */
    fun setNotifications(p: NotificationPrefs) {
        NotificationPrefsStore.set(platform.storage, p)
        notifier.cancelSilenced()
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
        platform.background.stop()
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
        if (!::platform.isInitialized) return
        if (wantsService()) {
            if (visible) platform.background.start()
        } else {
            platform.background.stop()
        }
    }
}
