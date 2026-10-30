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
import kotlinx.coroutines.withTimeoutOrNull

/** Where you are. The stack lives in the ViewModel, so it survives rotation and recreation. */
sealed interface Route {
    val key: String

    data object Home : Route { override val key = "home" }
    data class Session(val id: String) : Route { override val key = "session:$id" }
    /** Browse files under [root], starting at [start]. */
    data class Files(val root: String, val title: String, val start: String = root) : Route { override val key = "files:$root" }
    data class FileView(val path: String) : Route { override val key = "file:$path" }
    data class Web(val url: String, val title: String) : Route { override val key = "web:$url" }
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
 * another one, rather than pair the same one again (after it unpaired this phone, say): another name,
 * or another name on the tailnet.
 */
fun replacesAnother(current: Pairing, link: PairLink): Boolean =
    !current.macName.equals(link.macName, ignoreCase = true) ||
        (current.tailnetName != null && link.tailnetName != null && !current.tailnetName.equals(link.tailnetName, ignoreCase = true))

/**
 * The app's root state: which hub is active (live, demo, or none → onboarding), the
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

    /** The paired Mac, if any (process-wide, see [Core]). */
    val pairing: Pairing? get() = Core.pairing
    val live: LiveHub? get() = Core.live
    var demo by mutableStateOf(Core.store.demo)
        private set
    private var fakeHub: FakeHub? = null
    private fun fake(): FakeHub = fakeHub ?: FakeHub(viewModelScope, app.resources).also { fakeHub = it }

    /** The hub the screens use; null means "not paired, not in demo": show onboarding. */
    val hub: Hub? get() = if (demo) fake() else live

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
        // Another computer's code while this phone is paired (audit P12): asked first, as it ends
        // the first pairing (one computer at a time until there's a switcher).
        val current = Core.pairing
        if (current != null && replacesAnother(current, link)) {
            confirm = Confirm(
                title = app.getString(R.string.pair_replace_q, current.macName, link.macName),
                body = app.getString(R.string.pair_replace_body, current.macName),
                action = app.getString(R.string.pair_replace),
                danger = true,
            ) { pair(link) }
            return
        }
        pair(link)
    }

    /** The control server (a link's `tc`) the user agreed to on the pairing screen, if any. */
    var confirmedControl by mutableStateOf<String?>(null)
        private set

    /** "Use this server": the link's own control server may be used; the node starts against it. */
    fun confirmControl(link: PairLink) {
        confirmedControl = link.controlUrl
        link.controlUrl?.let { Tsnet.start(app, it) }
    }

    fun pair(link: PairLink) {
        pairJob?.cancel()
        pairFlow = PairFlow.Working(link)
        pairJob = viewModelScope.launch {
            try {
                val p = Pairer.pair(app, link).let { it.copy(controlConfirmed = it.controlUrl == null || it.controlUrl == confirmedControl) }
                // Replacing another computer: it lets this phone go first (as Forget does), so it
                // doesn't keep a phone that no longer listens in its list. Best effort: it may be away.
                val before = Core.pairing
                val old = Core.live
                if (before != null && old != null && replacesAnother(before, link)) withTimeoutOrNull(5_000L) { old.forget() }
                Core.paired(p)
                setDemoInternal(false)
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

    /** Forget this Mac here and (if it can be reached) on the Mac too. */
    fun forget(onDone: (reachedMac: Boolean) -> Unit = {}) {
        val l = live
        viewModelScope.launch {
            val ok = l?.forget()?.isSuccess ?: true
            confirmedControl = null
            clearPairing()
            onDone(ok)
        }
    }

    /** After "This phone was unpaired": drop the dead token and start over. */
    fun clearPairing() {
        confirmedControl = null
        Core.clear()
        nav.reset()
    }

    fun switchDemo(on: Boolean) {
        setDemoInternal(on)
        nav.reset()
    }

    private fun setDemoInternal(on: Boolean) {
        demo = on
        store.demo = on
    }

    var askedNotifications: Boolean
        get() = store.askedNotifications
        set(v) { store.askedNotifications = v }

    override fun onCleared() {
        // The connection belongs to the process (Core), not to this screen: keep it.
        if (Core.toastSink == ::showToast) Core.toastSink = null
    }
}
