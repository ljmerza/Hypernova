package com.prudnikweb.hypernova.data

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.prudnikweb.hypernova.ui.fx.AmbientClock
import com.prudnikweb.hypernova.ui.fx.CosmosState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    /** Settings, at [server]'s own section (a notification's tap; null: the one on screen). */
    fun openServerSettings(server: String?) {
        nav.reset()
        com.prudnikweb.hypernova.ui.settings.SettingsJump.show(com.prudnikweb.hypernova.ui.settings.SettingsJump.SERVER, server ?: Core.activeId)
    }

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
                val p = Pairer.pair(AndroidPlatform(app), link)
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

    private fun hasNetwork(): Boolean = AndroidNetwork(app).hasNetwork()


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
