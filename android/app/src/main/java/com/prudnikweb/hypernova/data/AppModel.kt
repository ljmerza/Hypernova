package com.prudnikweb.hypernova.data

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.fx.AmbientClock
import com.prudnikweb.hypernova.ui.fx.CosmosState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where you are. The stack lives in the ViewModel, so it survives rotation and recreation. */
sealed interface Route {
    val key: String

    data object Home : Route { override val key = "home" }
    data class Session(val id: String) : Route { override val key = "session:$id" }
    /** Browse files under [root], starting at [start]. */
    data class Files(val root: String, val title: String, val start: String = root) : Route { override val key = "files:$root" }
    data class FileView(val path: String) : Route { override val key = "file:$path" }
    /** A web page; [preview]: one the owner opened as a preview ([title] is what he tapped), [port] its dev server's. */
    data class Web(val url: String, val title: String, val preview: Boolean = false, val port: Int? = null) : Route { override val key = "web:$url" }
    /** A session's live terminal. */
    data class Terminal(val sessionId: String) : Route { override val key = "terminal:$sessionId" }
    /** One of a session's agents: its own transcript, read-only. */
    data class Agent(val sessionId: String, val agentId: String) : Route { override val key = "agent:$sessionId:$agentId" }
    data object Providers : Route { override val key = "providers" }
    /** Settings › Mac › Connection log (data/ConnLog.kt). */
    data object ConnectionLog : Route { override val key = "connection-log" }
    data class ProviderEdit(val id: String?, val kind: String) : Route { override val key = "provider:${id ?: "new-$kind"}" }
    /**
     * An automation's editor: [id] null for a new one, filled from [template] if given (in [project]
     * when the template starts a session there, e.g. from a limited session); [runs]: opened at its runs.
     */
    data class AutomationEdit(val id: String?, val template: String? = null, val runs: Boolean = false, val project: String? = null) : Route {
        override val key = "automation:${id ?: "new:${template.orEmpty()}:${project.orEmpty()}"}"
    }
}

class Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    val top: Route get() = stack.last()
    val depth: Int get() = stack.size

    fun push(r: Route) {
        if (stack.last() != r) stack.add(r)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Replace the top (e.g. the new-session sheet opening its session). */
    fun replaceTop(r: Route) {
        if (stack.size <= 1) push(r) else stack[stack.lastIndex] = r
    }

    fun reset() {
        stack.clear()
        stack.add(Route.Home)
    }

    /** Open session [id] by the one rule, [sessionStack]: every way into a session comes here. */
    fun openSession(id: String, fresh: Boolean = false) {
        val now = stack.toList()
        val next = sessionStack(now, id, fresh)
        if (next == now) return
        stack.clear()
        stack.addAll(next)
    }
}

/** A session's own screens: its chat, its agents and its live terminal. */
private val Route.ofSession: Boolean get() = this is Route.Session || this is Route.Agent || this is Route.Terminal

/**
 * The stack after opening session [id] on [stack]. Never a session over another session (owner,
 * 2026-10-30: Back always goes to the list of sessions): over a session's screens (its chat, agents,
 * terminal, and files or pages opened from them), or from outside the app ([fresh]: a notification,
 * a widget), it is Home and then the session. Already on it: unchanged. Otherwise (Home, an
 * automation's runs) it goes on top.
 */
fun sessionStack(stack: List<Route>, id: String, fresh: Boolean = false): List<Route> {
    val r = Route.Session(id)
    return when {
        stack.lastOrNull() == r -> stack
        fresh || stack.any { it.ofSession } -> listOf(Route.Home, r)
        else -> stack + r
    }
}

/** The pairing screen's states. */
sealed interface PairFlow {
    @Immutable data class Working(val link: PairLink) : PairFlow
    @Immutable data class Done(val macName: String) : PairFlow
    /**
     * [link] is null when the link itself was unreadable. [code]: the Mac's own reason when it gave
     * one (`pairing_closed`, `too_many_tries` with [retryAfterSeconds] from its local listener).
     */
    @Immutable data class Failed(val link: PairLink?, val problem: Problem?, val message: String?, val code: String? = null, val retryAfterSeconds: Int = 0) : PairFlow
}

@Immutable
data class Toast(val id: Long, val text: String)

/** A question that needs a yes before something irreversible (forget a Mac, delete a provider). */
class Confirm(val title: String, val body: String, val action: String, val danger: Boolean, val onConfirm: () -> Unit)

/**
 * Whether pairing with [link] would replace the computer this phone is paired with ([current]) by
 * another one, rather than pair the same one again (after it unpaired this phone, say): another name.
 */
fun replacesAnother(current: Pairing, link: PairLink): Boolean =
    !current.macName.equals(link.macName, ignoreCase = true)

/**
 * The app's root state: which hub is active (a paired computer's, or none → onboarding), the
 * pairing flow, navigation and toasts. It outlives the Activity.
 */
class HubViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    val cosmos = CosmosState()
    val clock = AmbientClock()
    val nav = Nav()

    init {
        Core.init(app)
        Core.toastSink = ::showToast
    }

    private val store get() = Core.store

    /** The active paired Mac, if any (process-wide, see [Core]). */
    val pairing: Pairing? get() = Core.pairing
    val live: LiveHub? get() = Core.live

    /** Every paired computer for the switcher, the active one first (data/Servers.kt). */
    val servers: List<ServerStatus> get() = Core.statuses

    /** The switcher: another paired computer on screen (from "All servers" too: that one alone). */
    fun switchServer(id: String) {
        if (id == Core.activeId && !Core.all) return
        Core.switchTo(id)
        nav.reset()
        notice = null
    }

    /** Home shows every paired computer together (the switcher's "All servers", two or more paired). */
    val allServers: Boolean get() = Core.all

    /** The switcher's "All servers". */
    fun showAll() {
        if (Core.all) return
        Core.showAll()
        nav.reset()
        notice = null
    }

    /** A session of [server] opens from "All servers" (a card, a new session, a notification): its screens are that computer's. */
    fun visit(server: String) = Core.visit(server)

    /** Home is back in "All servers": History, Automations, Settings and Statistics are the one picked's again. */
    fun backToPicked() = Core.backToPicked()

    /** Every paired computer with its hub, for Home's "All servers". */
    val everyHub: List<Pair<ServerStatus, LiveHub>> get() = Core.everyHub

    /** A paired computer's hub (null: not paired any more). */
    fun hubOf(server: String): Hub? = Core.hub(server)

    /** "Add a server" (the switcher): the pairing steps over the app, the computers paired kept. */
    var addingServer by mutableStateOf(false)
        private set
    fun addServer(on: Boolean) { addingServer = on }

    /** The hub the screens use; null means "not paired": show onboarding. */
    val hub: Hub? get() = live

    var pairFlow by mutableStateOf<PairFlow?>(null)
        private set
    private var pairJob: Job? = null

    var toast by mutableStateOf<Toast?>(null)
        private set
    private var toastSeq = 0L

    /** The confirm dialog on screen, if any (drawn above every screen and bar). */
    var confirm by mutableStateOf<Confirm?>(null)

    /** Set by a notification tap: open this session once the hub has it. */
    var pendingOpen by mutableStateOf<String?>(null)

    /**
     * The computer [pendingOpen] is on, while Home shows "All servers": it is visited as the session
     * opens ([visit]), and "All servers" stays. Null: the computer on screen.
     */
    var pendingServer by mutableStateOf<String?>(null)

    /** Set by an automation's notification: open its editor ("" the Automations tab). */
    var pendingAutomation by mutableStateOf<String?>(null)

    /** "Stay connected in the background". */
    val background: Boolean get() = Core.background
    fun stayConnected(on: Boolean) = Core.stayConnected(on)

    /** Work that must finish even if the screen that started it goes away (sending a prompt). */
    val appScope get() = Core.scope

    /** The banner on screen (a session needs you, finished, failed; an app update). */
    var notice by mutableStateOf<Notice?>(null)
        private set

    private val putAway = PutAway()

    /** False when it isn't shown: one like it was put away a moment ago ([PutAway]). */
    fun showNotice(n: Notice): Boolean {
        if (putAway.quiet(n, android.os.SystemClock.elapsedRealtime())) return false
        notice = n
        viewModelScope.launch {
            // Something to answer stays a little longer.
            delay(if (n.kind == Notice.Kind.Finished || n.kind == Notice.Kind.Update) 6000 else 9000)
            if (notice == n) notice = null
        }
        return true
    }

    fun dismissNotice() {
        notice = null
    }

    /** Fork: the owner put the banner away (swiped it or tapped ×), not opened it. */
    fun putAwayNotice() {
        notice?.let { putAway.put(it, android.os.SystemClock.elapsedRealtime()) }
        notice = null
    }

    fun showToast(text: String) {
        val t = Toast(toastSeq++, text)
        toast = t
        viewModelScope.launch {
            delay(3600)
            if (toast == t) toast = null
        }
    }

    fun dismissToast() { toast = null }

    fun setVisible(v: Boolean) = Core.setVisible(v)

    // ─── Pairing ───────────────────────────────────────────────────────────

    /** A link from the camera, a paste, or an intent. */
    fun handleLink(text: String?) {
        val link = PairLinks.parse(text)
        if (link == null) {
            pairFlow = PairFlow.Failed(null, null, null)
            return
        }
        // Another computer's code while this phone is paired: it joins the ones paired (data/Servers.kt),
        // nothing is replaced, so nothing is asked first (audit P12 asked while one was all there was).
        addingServer = false
        pair(link)
    }

    fun pair(link: PairLink) {
        pairJob?.cancel()
        pairFlow = PairFlow.Working(link)
        pairJob = viewModelScope.launch {
            try {
                val p = Pairer.pair(app, link)
                // Added next to the computers paired already (or in the place of the same one paired
                // before), and on screen.
                Core.paired(p)
                nav.reset()
                pairFlow = PairFlow.Done(p.macName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val ex = Net.classify(e)
                val problem = if (!hasNetwork() && ex.problem != Problem.CodeExpired) Problem.NoNetwork else ex.problem
                pairFlow = PairFlow.Failed(link, problem, ex.serverMessage, ex.code, ex.error?.optInt("retryAfterSeconds") ?: 0)
            }
        }
    }

    fun closePairFlow() {
        pairJob?.cancel()
        pairFlow = null
    }

    private fun hasNetwork(): Boolean {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Forget this Mac (the one on screen, or [server] when given: Settings' block for it with "All
     * servers") here and (if it can be reached) on the Mac too; only this one, even if the switcher
     * shows another meanwhile. The next one paired comes on screen.
     */
    fun forget(server: String? = null, onDone: (reachedMac: Boolean) -> Unit = {}) {
        val id = server?.takeIf { s -> Core.servers.any { it.id == s } } ?: if (server == null) Core.activeId else return
        val l = if (server == null) live else Core.everyHub.firstOrNull { it.first.id == id }?.second
        viewModelScope.launch {
            val ok = l?.forget()?.isSuccess ?: true
            if (id != null) Core.forget(id)
            nav.reset()
            onDone(ok)
        }
    }

    /**
     * Forget the computer on screen here (Forget, or after "This phone was unpaired": the dead token).
     * Another computer still paired comes on screen in its place.
     */
    fun clearPairing() {
        Core.clear()
        nav.reset()
    }

    /**
     * "Pair again" after "This phone was unpaired": the dead pairing goes, and the pairing steps show
     * (over the next computer while another is paired; the onboarding otherwise).
     */
    fun pairAgain() {
        clearPairing()
        if (Core.servers.isNotEmpty()) addingServer = true
    }

    var askedNotifications: Boolean
        get() = store.askedNotifications
        set(v) { store.askedNotifications = v }

    override fun onCleared() {
        // The connection belongs to the process (Core), not to this screen: keep it.
        if (Core.toastSink == ::showToast) Core.toastSink = null
    }
}
