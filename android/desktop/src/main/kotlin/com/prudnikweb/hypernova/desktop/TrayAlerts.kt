package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Log
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.NotificationPrefsStore
import com.prudnikweb.hypernova.data.Notifier
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.Storage
import com.prudnikweb.hypernova.data.WApproval
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/*
 * The tray alert (docs/DECISIONS.md, "Tray alerts on the desktop client"): when something needs input and the
 * window isn't what the owner is looking at. What's worth an alert is decided where it is on a phone, in LiveHub and
 * NotificationPrefs. This only says it, and never what the request would run.
 */

/** Where an alert can appear: the system's tray here, a stand-in in a test. */
interface AlertSurface {
    /** There's a tray on this desktop now. A panel can come and go, so it's asked each time. */
    val available: Boolean
    /** Heard when the alert or the tray's icon is clicked. */
    var clicked: () -> Unit
    fun show(title: String, text: String)
}

/**
 * AWT's tray. On Linux it's there only if something on the desktop holds the X11 tray's selection, and plain GNOME
 * has nothing that does (XSystemTrayPeer). Then there's no icon and [show] does nothing but log: whatever AWT throws
 * on a desktop it can't work on stays in here. [image] is only asked for once there's a tray to put it in.
 */
class AwtTray(private val log: Log, private val image: () -> Image) : AlertSurface {
    private var icon: TrayIcon? = null
    override var clicked: () -> Unit = {}

    override val available: Boolean get() = runCatching { SystemTray.isSupported() }.getOrDefault(false)

    /** The icon, once there's somewhere for it. Off the event thread it's sent there: AWT's peers want that. */
    fun start() {
        if (SwingUtilities.isEventDispatchThread()) install() else SwingUtilities.invokeLater { install() }
    }

    private fun install(): TrayIcon? {
        icon?.let { return it }
        if (!available) return null
        return try {
            TrayIcon(image(), APP_NAME).apply {
                isImageAutoSize = true
                // A click on the alert itself on Windows and on X11, a double click on the icon (AWT's own rule).
                addActionListener { clicked() }
                // A plain click on the icon: on a Mac it's the only one that reaches this, since CTrayIcon doesn't hear a click on its notification.
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (SwingUtilities.isLeftMouseButton(e)) clicked()
                    }
                })
                SystemTray.getSystemTray().add(this)
            }.also { icon = it }
        } catch (e: Throwable) {
            log.w(TAG, "no tray icon here (${e.javaClass.simpleName}): no alerts")
            null
        }
    }

    override fun show(title: String, text: String) {
        val tray = install()
        if (tray == null) {
            log.d(TAG, "no tray on this desktop: the window's title counts it")
            return
        }
        runCatching { tray.displayMessage(title, text, TrayIcon.MessageType.INFO) }
            .onFailure { log.w(TAG, "the tray didn't take an alert (${it.javaClass.simpleName})") }
    }

    private companion object {
        const val TAG = "Notify"
    }
}

/**
 * The data layer's [Notifier] on a desktop. [LiveHub][com.prudnikweb.hypernova.data.LiveHub] decides when to call it:
 * only with the window not in front or for a server not on screen, an agent's ask only once it has waited, and each
 * approval once ([Core.setVisible] follows the focus for this: Main.kt). Then this takes the owner's switches
 * ([NotificationPrefsStore], off nowhere yet on a desktop) and keeps to what needs input: approvals, questions, plans,
 * a session waiting in its terminal, and an account that needs its owner. The rest of a phone's notices stay in the log.
 *
 * It says who asks, never what. A system's alert can sit in a history or show over a locked screen, as a phone's can,
 * and here nothing can tell the two apart, so this keeps a phone's lock-screen rule. There's no Allow on it at all:
 * Allow stays beside the whole command, on the card (docs/DECISIONS.md, "The desktop app", 3).
 *
 * An alert can't be taken back on these systems, so what's answered elsewhere just fades. A click finds the list as it is.
 */
class TrayNotifier(
    private val log: Log,
    private val storage: Storage,
    val surface: AlertSurface,
    private val serverLabel: (String?) -> String? = Core::notifyLabel,
) : Notifier {
    /** Each request said, newest last, as a second line of defence behind the hub's own. */
    private val told = LinkedHashSet<String>()
    private var lastServer: String? = null

    /** What a click does, with the server the last alert was from. Main.kt brings the window forward. */
    var opened: (server: String?) -> Unit = {}

    init {
        surface.clicked = { opened(lastServer) }
    }

    override fun canPost(): Boolean = surface.available

    override fun approval(a: WApproval, s: Session?, failed: Boolean, server: String?) {
        if (!allowed(server, Notice.Kind.Approval) || !firstTime("approval:$server:${a.id}")) return
        val untitled = Ui.untitled_session.text()
        val who = (s?.name?.takeIf { it.isNotBlank() && it != untitled } ?: s?.project)?.takeIf { it.isNotBlank() }
        val title = when (a.kind) {
            "question" -> who?.let { Ui.notif_question.format(it) } ?: Ui.notif_question_plain.text()
            "plan" -> who?.let { Ui.notif_plan.format(it) } ?: Ui.notif_plan_plain.text()
            else -> who?.let { Ui.notif_tool.format(it) } ?: Ui.notif_tool_plain.text()
        }
        val agent = a.agentId != null
        say(title, lookInTheWindow(from(server, a.agentLabel.takeIf { agent }, agent)), server)
    }

    override fun notice(n: Notice, server: String?) {
        if (n.kind !in NEEDS_INPUT) {
            log.d(TAG, "a notice, not shown: ${n.kind}")
            return
        }
        if (!allowed(server, n.kind)) return
        // The hub says the account's once per episode, under one id, so only a session's are held to their id.
        if (n.sessionId != null && !firstTime("notice:$server:${n.id}")) return
        when (n.kind) {
            Notice.Kind.Account -> say(n.title, n.text, server)
            Notice.Kind.Waiting -> say(Ui.notice_title_waiting.format(n.title), lookInTheWindow(from(server, null, false)), server)
            else -> say(Ui.notice_title_needs_you.format(n.title), lookInTheWindow(from(server, null, false)), server)
        }
    }

    private fun allowed(server: String?, kind: Notice.Kind) = NotificationPrefsStore.get(storage).allows(server, kind)

    private fun firstTime(key: String): Boolean {
        if (!told.add(key)) return false
        if (told.size > MAX_TOLD) told.remove(told.first())
        return true
    }

    /** Where it's from, while that's worth saying: another computer's name, an agent the main agent started. The phone's words. */
    private fun from(server: String?, agent: String?, anAgent: Boolean): String? {
        val name = serverLabel(server)
        return when {
            name == null -> agent?.let { Ui.notif_from_agent.format(it) } ?: Ui.notif_from_an_agent.text().takeIf { anAgent }
            agent != null -> Ui.notif_server_from_agent.format(name, agent)
            anAgent -> Ui.notif_server_from_an_agent.format(name)
            else -> name
        }
    }

    private fun lookInTheWindow(from: String?): String = listOfNotNull(from, Ui.desk_alert_body.text()).joinToString("\n")

    private fun say(title: String, text: String, server: String?) {
        lastServer = server
        surface.show(title, text)
    }

    override fun answered(id: String) = Unit
    override fun replied(sessionId: String, server: String?) = Unit
    override fun cancelSilenced() = Unit
    override fun replyFailed(sessionId: String, title: String, why: String, server: String?) = Unit
    override fun cancelAccount(server: String?) = Unit
    override fun cancelServerUpdate(server: String?) = Unit
    override fun cancelApproval(id: String) = Unit
    override fun cancelAllApprovals(server: String?) = Unit

    private companion object {
        const val TAG = "Notify"
        const val MAX_TOLD = 500
        val NEEDS_INPUT = setOf(Notice.Kind.Approval, Notice.Kind.Question, Notice.Kind.Plan, Notice.Kind.Waiting, Notice.Kind.Account)
    }
}

/** The window's title: how many sessions need the owner, first. It's what's left on a desktop with no tray, and it shows in the window list on any. */
fun windowTitle(needsYou: Int): String = if (needsYou > 0) "($needsYou) $APP_NAME" else APP_NAME

/**
 * Whether the owner is looking, from the window's focus. It stays "looking" for [graceMs] after the focus goes (or
 * after the window opens without it), so a moment on something else, or a window still on its way up, doesn't
 * turn the next request into an alert.
 */
suspend fun followFocus(focused: Flow<Boolean>, graceMs: Long = FOCUS_GRACE_MS, looking: (Boolean) -> Unit) {
    focused.collectLatest {
        if (!it) delay(graceMs)
        looking(it)
    }
}

const val FOCUS_GRACE_MS = 2_000L
