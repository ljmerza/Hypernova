package com.prudnikweb.hypernova.widget

import android.app.AlarmManager
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.ConnectionService
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.widget.WidgetModel.Answering
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the home screen widget current. While one is placed, it watches every paired computer's
 * hub (the one on screen and the ones watched in the background, through [Core]) and pushes a new
 * picture:
 *
 * - when what needs you changes on any of them (a session starts or stops waiting, an approval comes
 *   or goes, a computer's link drops or comes back), but not more often than every [GAP]: a burst of
 *   changes is drawn once, with the state as it is by then;
 * - not while the screen is off: nobody sees a home screen then. The newest state waits and is
 *   drawn when the screen comes on;
 * - never on a timer of its own: when nothing changes, nothing is drawn. A picture that isn't
 *   live is redrawn only at the few moments it goes out of date by itself ([schedule]).
 *
 * The widget is "live" only while the app is on screen or the background connection runs; at
 * any other time it says when it last heard from the computers, since nothing keeps it current.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object WidgetSync {
    /** The least time between two pictures: everything the widget shows is something you'd act on. */
    private const val GAP = 1_500L
    /** How often a live picture checks that its process is still there ([schedule]). */
    private const val LIVE_CHECK = 30 * 60_000L

    private lateinit var app: Application
    private val present = MutableStateFlow(false)
    /** Answers sent from the widget, still on their way (or the last try failed), by approval or menu id. */
    private val answering = mutableStateMapOf<String, Answering>()
    /** Approvals and terminal dialogs answered from the widget: hidden before the computer's word arrives. */
    private var done by mutableStateOf<Set<String>>(emptySet())

    /** The last model drawn, kept on disk for a process that starts cold. */
    private var stored: WidgetModel? = null
    private var last: WidgetModel? = null
    /**
     * [last] as it was drawn (without the approvals that had left the phone by then): what the
     * list's rows are made from ([WidgetListService], on a binder thread).
     */
    @Volatile private var shown: WidgetModel? = null
    private var lastAt = 0L
    private var pending: Job? = null
    /** The pending draw has taken [latest] and is drawing it: anything newer needs a draw of its own. */
    private var pendingTook = false
    /** The newest model offered, which the pending draw (or the screen coming on) will show. */
    private var latest: WidgetModel? = null
    /**
     * When the alarm set by [schedule] goes off (0: none set). Unknown in a new process (an alarm
     * outlives the process that set it), so the first [schedule] sets or cancels it either way.
     */
    private var alarmAt = -1L
    /** RemoteViews are built off the main thread, one update at a time. */
    private val drawing = Dispatchers.Default.limitedParallelism(1)
    private val lock = Mutex()

    /** Called once per process from [Core.init] (and by the widget, if it gets there first). */
    fun start(a: Application) {
        if (::app.isInitialized) return
        app = a
        stored = WidgetStore.load(a)
        refreshPresence()
        checkSecrets(a)
        Core.scope.launch {
            present.collectLatest { placed ->
                listenToScreen(placed)
                if (!placed) {
                    unschedule()
                    return@collectLatest
                }
                snapshotFlow { model() }.collect { offer(it) }
            }
        }
    }

    /**
     * A debug build checks its masking once per install ([SecretsCheck]: the app has no JVM tests),
     * off the main thread, and logs how it went: `adb logcat -s HypernovaSecrets`.
     */
    private fun checkSecrets(a: Application) {
        if (!BuildConfig.DEBUG) return
        val prefs = a.getSharedPreferences("widget", Context.MODE_PRIVATE)
        if (prefs.getInt("secretsChecked", 0) == BuildConfig.VERSION_CODE) return
        Core.scope.launch(Dispatchers.Default) {
            val r = SecretsCheck.run()
            if (r.failures.isEmpty()) Log.i("HypernovaSecrets", "all ${r.cases} cases pass, slowest shape ${r.slowestMs} ms")
            else r.failures.forEach { Log.e("HypernovaSecrets", it) }
            prefs.edit().putInt("secretsChecked", BuildConfig.VERSION_CODE).apply()
        }
    }

    /** A widget was added or removed: watch the hubs only while one is placed. */
    fun refreshPresence() {
        if (!::app.isInitialized) return
        val mgr = AppWidgetManager.getInstance(app) ?: return
        present.value = runCatching { mgr.getAppWidgetIds(component(app)).isNotEmpty() }.getOrDefault(false)
    }

    /** What the list shows now, for its rows ([WidgetListService]): the last picture drawn, else the one kept. */
    fun current(): WidgetModel = shown ?: stored?.at(System.currentTimeMillis()) ?: WidgetModel.unpaired()

    /**
     * What the widget should show right now. Read inside [snapshotFlow], so it runs again when any
     * state it reads changes: any computer's sessions, approvals or link, which computers are paired,
     * the app coming on screen. Times are left out (0), so a model differs from the last only when
     * what it shows does; [stamp] adds them.
     */
    private fun model(): WidgetModel {
        if (Core.servers.isEmpty()) return WidgetModel.unpaired()
        val sources = Core.allHubs.map { h ->
            WidgetModel.Source(
                id = h.serverId,
                name = Core.nameOf(h.serverId).orEmpty(),
                link = h.link.state,
                synced = h.synced,
                sessions = h.sessions,
                approvals = h.pendingApprovals.toList(),
            )
        }
        return WidgetModel.of(sources, live = Core.visible || ConnectionService.running, answering = answering.toMap(), done = done, kept = stored)
    }

    /**
     * A new model: drawn when [GAP] since the last drawing is up. A draw already waiting takes this
     * newer model with it.
     */
    private fun offer(m: WidgetModel) {
        if (m.sameContent(last)) {
            // Back to what's on screen (a socket that dropped and came straight back): nothing to draw.
            latest = null
            return
        }
        latest = m
        // The screen is off: keep it for when the screen comes on ([wake]).
        if (!awake()) return
        // One that has started is drawing an older model: this one gets its own draw, after the gap.
        if (pending?.isActive == true && !pendingTook) return
        val due = lastAt + GAP
        pending?.cancel()
        pendingTook = false
        pending = Core.scope.launch {
            val wait = due - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            // It went off in the meantime: [latest] stays for the screen coming on.
            if (!awake()) return@launch
            val next = latest ?: return@launch
            latest = null
            pendingTook = true
            // Once it starts drawing, it finishes: half a redraw would leave widgets behind.
            withContext(NonCancellable) { push(next) }
        }
    }

    /**
     * When [m] was last true. Live: now. Not live: when a computer was last heard from, even if the
     * socket still looks open (a cached or frozen process can't tell it isn't); else as kept.
     */
    private fun stamp(m: WidgetModel, now: Long): WidgetModel {
        if (!m.paired || m.updatedAt > 0L) return m
        if (m.live) return m.copy(updatedAt = now)
        val heard = Core.allHubs.maxOfOrNull { it.lastHeardAt }?.takeIf { it > 0L }
        return m.copy(updatedAt = heard ?: last?.updatedAt ?: stored?.updatedAt ?: 0L)
    }

    /** Stamps [m], keeps it for a cold start, and redraws the widgets. One at a time. */
    private suspend fun push(m: WidgetModel) = lock.withLock {
        val now = System.currentTimeMillis()
        val stamped = stamp(m, now)
        last = stamped
        lastAt = now
        stored = stamped
        val view = stamped.at(now)
        shown = view
        forgetDone()
        withContext(drawing) {
            runCatching { WidgetStore.save(app, stamped) }
            draw(view)
        }
        schedule(stamped, now)
    }

    /** Answers given here stay hidden only until every computer's list has caught up. */
    private fun forgetDone() {
        if (done.isEmpty()) return
        val hubs = Core.allHubs
        if (hubs.any { !it.synced }) return
        val waiting = HashSet<String>()
        for (h in hubs) {
            h.pendingApprovals.forEach { waiting += it.id }
            h.sessions.forEach { s -> s.menu?.id?.let(waiting::add) }
        }
        if (done.any { it !in waiting }) done = done.filterTo(HashSet()) { it in waiting }
    }

    /** Redraws every placed widget; the list's rows are fetched again from [WidgetListService]. */
    @Suppress("DEPRECATION")
    private fun draw(m: WidgetModel) {
        val mgr = AppWidgetManager.getInstance(app) ?: return
        val ids = runCatching { mgr.getAppWidgetIds(component(app)) }.getOrNull() ?: return
        if (ids.isEmpty()) return
        for (id in ids) runCatching { mgr.updateAppWidget(id, WidgetViews.build(app, id, m)) }
        runCatching { mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list) }
    }

    // ─── Moments a picture goes out of date by itself ───────────────────────

    /**
     * A picture that isn't live goes out of date by itself at a few known moments: an approval
     * leaves the phone for nobody answering, "Last updated 10:15" turns into yesterday. A
     * non-waking alarm redraws it at the next one, or when the phone next wakes up after it: no
     * wake-ups of its own and no polling. A live picture is redrawn by the hubs; its alarm only
     * checks, every [LIVE_CHECK] while the phone is in use, that the process behind it is still
     * there: one the system killed without a word would otherwise leave a live-looking picture
     * behind for good ([tick]). This is why the widget has no updatePeriodMillis: that one wakes
     * the phone every half hour to redraw what's known.
     */
    private fun schedule(m: WidgetModel, now: Long) {
        val at = if (present.value) nextChange(m, now) ?: 0L else 0L
        if (at == alarmAt) return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            if (at == 0L) Taps.tick(app, create = false)?.let(am::cancel)
            // A window rather than an exact time: inexact alarms need no special permission,
            // and Android lets them through within ten minutes or so of the moment.
            else Taps.tick(app, create = true)?.let { am.setWindow(AlarmManager.RTC, at + 1_000L, 10 * 60_000L, it) }
        }
        alarmAt = at
    }

    private fun unschedule() {
        if (alarmAt == 0L) return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching { Taps.tick(app, create = false)?.let(am::cancel) }
        alarmAt = 0L
    }

    /** The next moment [m]'s picture changes with nothing new from the computers (or, live, is checked), or null. */
    private fun nextChange(m: WidgetModel, now: Long): Long? {
        if (!m.paired) return null
        // Live: the check already set stays while it's at least half the interval away, so a
        // busy hub doesn't move the alarm on every redraw.
        if (m.live) return alarmAt.takeIf { it - now in LIVE_CHECK / 2..LIVE_CHECK } ?: (now + LIVE_CHECK)
        val expiries = m.groups.flatMap { g -> g.rows.map { it.expiresAt } }
        val stampAt = m.updatedAt.takeIf { it > 0L }?.let { WidgetViews.stampChanges(it, now) }
        return (expiries + listOfNotNull(stampAt)).filter { it > now }.minOrNull()
    }

    // ─── The screen ─────────────────────────────────────────────────────────

    private fun awake(): Boolean = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = wake()
    }
    private var listening = false

    /** Hears the screen come on, while a widget is placed (and only then). */
    private fun listenToScreen(on: Boolean) {
        if (on == listening) return
        listening = on
        runCatching {
            if (on) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                ContextCompat.registerReceiver(app, screen, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            } else {
                app.unregisterReceiver(screen)
            }
        }
    }

    /**
     * The screen came on (or was unlocked): draw what changed while it was off, and a picture
     * that went out of date by itself in the meantime, at once.
     */
    private fun wake() {
        val next = latest
        val due = shown?.live != true && alarmAt in 1..System.currentTimeMillis()
        if (next == null && !due) return
        latest = null
        pending?.cancel()
        Core.scope.launch { withContext(NonCancellable) { push(next ?: model()) } }
    }

    // ─── Requests from the platform and from taps ───────────────────────────

    /**
     * The platform asked for a redraw (a widget was added, removed or resized, the device
     * started, the app was updated): draw what's known, now, whether or not the screen is on (a
     * process started just for this may not live to see it come on). [finished] runs once it's drawn.
     */
    fun redraw(ctx: Context, finished: () -> Unit = {}) {
        start(ctx.applicationContext as Application)
        refreshPresence()
        Core.scope.launch {
            try {
                latest = null
                pending?.cancel()
                push(model())
            } finally {
                finished()
            }
        }
    }

    /**
     * The alarm set by [schedule]. A picture still live in this same process only gets its next
     * check; any other (one that went out of date by itself, or that a process the system killed
     * left behind) is drawn again. [finished] runs once that's done.
     */
    fun tick(ctx: Context, finished: () -> Unit) {
        start(ctx.applicationContext as Application)
        refreshPresence()
        Core.scope.launch {
            try {
                val drawn = last
                val m = model()
                if (drawn != null && drawn.live && m.live) {
                    // Anything new is already on its way through [offer].
                    schedule(drawn, System.currentTimeMillis())
                    return@launch
                }
                latest = null
                pending?.cancel()
                push(m)
            } finally {
                finished()
            }
        }
    }

    /**
     * Allow or Deny on an approval, from the widget or a notification: the same answer the session
     * screen sends, through [com.prudnikweb.hypernova.data.LiveHub.answerAsync] with this phone's
     * token. The row says "Allowing…" at once, then goes when the computer has it, or says it didn't
     * get through. [finished] runs once the widget shows how it went, or after 8 seconds, whichever
     * is first: a broadcast can't hold the process for longer. [onResult]: whether the computer took
     * it (a notification's Allow and Deny go this way too, data/NotifyActions.kt). It goes to the
     * computer [server] names (the row's, a notification's), else the one the approval waits on
     * (data/Servers.kt).
     */
    fun answer(ctx: Context, approvalId: String, allow: Boolean, server: String? = null, onResult: (Boolean) -> Unit = {}, finished: () -> Unit) {
        start(ctx.applicationContext as Application)
        val live = Core.hubForApproval(server, approvalId)
        if (live == null) { onResult(false); finished(); return }
        send(approvalId, allow, { live.answerAsync(approvalId, if (allow) Answer.Allow else Answer.Deny) }, onResult, finished)
    }

    /**
     * Allow or Deny on Claude Code's own permission dialog in a session's terminal, from the widget:
     * its Yes ([choice] its Allow option) or its last No pressed there, through the menu route, as a
     * Home card answers it. On the computer [server] names.
     */
    fun answerMenu(ctx: Context, server: String?, sessionId: String, menuId: String, choice: Int, allow: Boolean, finished: () -> Unit) {
        start(ctx.applicationContext as Application)
        val hub = Core.hub(server)
        if (hub == null || choice < 0) { finished(); return }
        send(menuId, allow, { Core.scope.async { hub.answerMenu(sessionId, menuId, choice).isSuccess } }, {}, finished)
    }

    /** Sends one answer ([call]) for the row [id], saying so on the widget while it goes. */
    private fun send(id: String, allow: Boolean, call: () -> Deferred<Boolean>, onResult: (Boolean) -> Unit, finished: () -> Unit) {
        Core.scope.launch {
            var released = false
            val release = { if (!released) { released = true; finished() } }
            try {
                answering[id] = if (allow) Answering.Allowing else Answering.Denying
                pushNow()
                val sent = call()
                // Not confirmed within 8 s: the answer may still land, so it isn't a failure yet.
                // The row keeps saying "Allowing…" (no buttons) until the request settles, which
                // the HTTP client's own timeouts bound.
                val ok = withTimeoutOrNull(8_000L) { sent.await() } ?: run {
                    release()
                    runCatching { sent.await() }.getOrDefault(false)
                }
                onResult(ok)
                if (ok) {
                    done = done + id
                    answering.remove(id)
                } else {
                    answering[id] = Answering.Failed
                    // The note stays a moment; then the buttons are simply there again.
                    Core.scope.launch {
                        delay(8_000L)
                        if (answering[id] == Answering.Failed) answering.remove(id)
                    }
                }
                pushNow()
            } finally {
                release()
            }
        }
    }

    /** Draws the current state now, past the gap: feedback for a tap on the widget. */
    private suspend fun pushNow() {
        latest = null
        pending?.cancel()
        push(model())
    }

    fun component(ctx: Context) = ComponentName(ctx, SessionsWidget::class.java)
}
