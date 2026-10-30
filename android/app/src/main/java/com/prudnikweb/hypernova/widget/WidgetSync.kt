package com.prudnikweb.hypernova.widget

import android.app.AlarmManager
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import android.util.Log
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.ConnectionService
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.widget.WidgetModel.Answering
import com.prudnikweb.hypernova.widget.WidgetModel.Conn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.roundToInt

/**
 * Keeps the home screen widgets current. While any widget is placed, it watches the live hub
 * (the same state the screens read, through [Core]) and pushes a new picture:
 *
 * - at once when something you'd act on changes (who's working, who needs you, an approval, the
 *   connection), but not more often than every [URGENT_GAP];
 * - otherwise (what a session is doing, usage creeping up) at most every [GAP], with the state
 *   as it is by then;
 * - not while the screen is off: nobody sees a home screen then. The newest state waits and is
 *   drawn when the screen comes on;
 * - never on a timer of its own: when nothing changes, nothing is drawn. A picture that isn't
 *   live is redrawn only at the few moments it goes out of date by itself ([schedule]).
 *
 * The widget is "live" only while the app is on screen or the background connection runs; at
 * any other time it says when it last heard from the Mac, since nothing keeps it current.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object WidgetSync {
    private const val GAP = 12_000L
    private const val URGENT_GAP = 1_500L
    /** How often a live picture checks that its process is still there ([schedule]). */
    private const val LIVE_CHECK = 30 * 60_000L

    private lateinit var app: Application
    private val present = MutableStateFlow(false)
    /** Answers sent from the widget, still on their way (or the last try failed). */
    private val answering = mutableStateMapOf<String, Answering>()
    /** Approvals answered from the widget: hidden before the Mac's word arrives. */
    private var done by mutableStateOf<Set<String>>(emptySet())

    /** The last model drawn, kept on disk for a process that starts cold. */
    private var stored: WidgetModel? = null
    private var last: WidgetModel? = null
    /** [last] as it was drawn: with the resets and auto-denies that had passed by then. */
    private var shown: WidgetModel? = null
    private var lastAt = 0L
    private var pending: Job? = null
    private var pendingDue = 0L
    /** The pending draw has taken [latest] and is drawing it: anything newer needs a draw of its own. */
    private var pendingTook = false
    /** The newest model offered, which the pending draw (or the screen coming on) will show. */
    private var latest: WidgetModel? = null
    /**
     * When the alarm set by [schedule] goes off (0: none set). Unknown in a new process (an alarm
     * outlives the process that set it), so the first [schedule] sets or cancels it either way.
     */
    private var alarmAt = -1L
    /** Bitmaps and RemoteViews are built off the main thread, one update at a time. */
    private val drawing = Dispatchers.Default.limitedParallelism(1)
    private val lock = Mutex()

    /** Called once per process from [Core.init] (and by the widgets, if they get there first). */
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

    /** A widget was added or removed: watch the hub only while one is placed. */
    fun refreshPresence() {
        if (!::app.isInitialized) return
        val mgr = AppWidgetManager.getInstance(app) ?: return
        present.value = WidgetKind.entries.any { runCatching { mgr.getAppWidgetIds(component(app, it)).isNotEmpty() }.getOrDefault(false) }
    }

    /**
     * What the widgets should show right now. Read inside [snapshotFlow], so it runs again when
     * any state it reads changes. Times are left out (0) unless they come from disk, so a model
     * differs from the last only when what it shows does; [stamp] adds them.
     */
    private fun model(): WidgetModel {
        val live = Core.live
        if (Core.pairing == null || live == null || live.link.state == LinkState.Unpaired) return WidgetModel.unpaired()
        val fresh = Core.visible || ConnectionService.running
        // The background connection is on and not disconnected from its notification: it's meant
        // to keep the widgets current while the app is closed. Off, they wait for the app.
        val held = Core.wantsService()
        if (!live.synced) {
            // A process that just started: show what was kept (for this Mac), until the Mac answers.
            val conn = when {
                !fresh && !held -> Conn.AppOnly
                live.link.state == LinkState.Offline -> Conn.Offline
                else -> Conn.Stale
            }
            val kept = stored?.takeIf { it.conn != Conn.Unpaired && it.macName == live.mac.name }
                ?: return WidgetModel.unpaired().copy(conn = conn, macName = live.mac.name)
            return kept.copy(
                conn = conn,
                ask = kept.ask?.takeIf { it.id !in done }?.let { it.copy(answering = answering[it.id]) },
            )
        }
        return WidgetModel.of(live, fresh, held, answering.toMap(), done)
    }

    /**
     * A new model: drawn when its gap since the last drawing is up. A draw already due sooner
     * (an urgent change) stays due then and takes this newer model with it.
     */
    private fun offer(m: WidgetModel) {
        val prev = last
        if (m.sameContent(prev)) {
            // Back to what's on screen (a socket that dropped and came straight back): nothing to draw.
            latest = null
            return
        }
        latest = m
        // The screen is off: keep it for when the screen comes on ([wake]).
        if (!awake()) return
        val due = lastAt + if (urgent(prev, m)) URGENT_GAP else GAP
        // A draw that is due no later and hasn't started takes this model with it. One that has
        // started is drawing an older model: this one gets its own draw, after the gap.
        if (pending?.isActive == true && !pendingTook && pendingDue <= due) return
        pending?.cancel()
        pendingDue = due
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
     * Changes worth drawing at once: anything you'd act on, or that changes what's promised.
     * Which sessions are shown and in what state, not their order: the order follows the rows'
     * own sort, and isn't news by itself.
     */
    private fun urgent(a: WidgetModel?, b: WidgetModel): Boolean {
        if (a == null) return true
        if (a.conn != b.conn || a.working != b.working || a.needs != b.needs || a.account != b.account) return true
        // The agents: which leads, what stops Codex, the other's limit or trouble.
        if (a.leadAgent != b.leadAgent || a.codexTrouble != b.codexTrouble || (a.other == null) != (b.other == null)) return true
        if (a.other?.limited != b.other?.limited || a.other?.trouble != b.other?.trouble) return true
        if (a.ask?.id != b.ask?.id || a.ask?.answering != b.ask?.answering) return true
        if (a.rows.mapTo(HashSet()) { it.id to it.state } != b.rows.mapTo(HashSet()) { it.id to it.state }) return true
        val u = a.usage
        val v = b.usage
        return u.limited != v.limited || u.weekLimited != v.weekLimited || u.known != v.known
    }

    /**
     * When [m] was last true. Live: now. Not live: when the Mac was last heard from, even if the
     * socket still looks open (a cached or frozen process can't tell it isn't). From disk: as
     * kept. Not heard from yet: never (0, "Connecting…").
     */
    private fun stamp(m: WidgetModel, now: Long): WidgetModel {
        if (m.updatedAt > 0L) return m
        if (Core.live?.synced != true) return m
        return when (m.conn) {
            Conn.Live -> m.copy(updatedAt = now)
            Conn.Stale, Conn.AppOnly, Conn.Offline -> m.copy(updatedAt = heard())
            Conn.Unpaired -> m
        }
    }

    private fun heard(): Long = Core.live?.lastHeardAt?.takeIf { it > 0L } ?: last?.updatedAt ?: stored?.updatedAt ?: 0L

    /** Stamps [m], keeps it for a cold start, and redraws the widgets whose picture changed. One at a time. */
    private suspend fun push(m: WidgetModel, force: Boolean = false) = lock.withLock {
        val now = System.currentTimeMillis()
        val stamped = stamp(m, now)
        last = stamped
        lastAt = now
        stored = stamped
        val prev = shown
        val view = stamped.at(now)
        shown = view
        // Approvals answered here stay hidden only until the Mac's list catches up.
        val live = Core.live
        if (done.isNotEmpty() && live?.synced == true) {
            val waiting = live.pendingApprovals.mapTo(HashSet()) { it.id }
            if (done.any { it !in waiting }) done = done.filterTo(HashSet()) { it in waiting }
        }
        withContext(drawing) {
            runCatching { WidgetStore.save(app, stamped) }
            draw(view, if (force) null else prev)
        }
        schedule(stamped, now)
    }

    /** Redraws every placed widget, except a sun whose picture didn't change (it doesn't show what sessions are doing). */
    private fun draw(m: WidgetModel, prev: WidgetModel?) {
        val mgr = AppWidgetManager.getInstance(app) ?: return
        for (kind in WidgetKind.entries) {
            if (kind == WidgetKind.Sun && prev != null && sunPart(prev) == sunPart(m)) continue
            val ids = runCatching { mgr.getAppWidgetIds(component(app, kind)) }.getOrNull() ?: continue
            for (id in ids) runCatching { mgr.updateAppWidget(id, WidgetViews.build(app, mgr, id, kind, m)) }
        }
    }

    /** What the small widget shows: the sun, the counts and how fresh they are. */
    private fun sunPart(m: WidgetModel): List<Any?> = listOf(
        m.conn, m.working, m.needs, m.account, m.usingCredits,
        // The agents: which one the star follows, what stops Codex, the other's readout (in whole percents).
        m.leadAgent, m.codexTrouble, m.weekOnly, m.other,
        (m.usage.fiveHour * 100).toInt(), m.usage.fiveResetAt / 60_000L, m.usage.known, m.usage.limited, m.usage.weekLimited, m.usage.sevenResetAt / 60_000L,
        // Its meter: the pace's faint fill and when the limit comes, if first.
        m.usage.fivePace, m.usage.fiveLimitAt / 60_000L,
        if (m.conn == Conn.Live) 0L else m.updatedAt / 60_000L,
        if (m.conn == Conn.Live || m.updatedAt == 0L) "" else WidgetViews.stamp(app, m.updatedAt),
    )

    // ─── Moments a picture goes out of date by itself ───────────────────────

    /**
     * A picture that isn't live goes out of date by itself at a few known moments: a reset
     * passes, an approval reaches the Mac's 10-minute auto-deny, "Last updated 10:15" turns into
     * yesterday. A non-waking alarm redraws it at the next one, or when the phone next wakes up
     * after it: no wake-ups of its own and no polling. A live picture is redrawn by the hub (which
     * wakes itself for resets); its alarm only checks, every [LIVE_CHECK] while the phone is in
     * use, that the process behind it is still there: one the system killed without a word
     * would otherwise leave a live-looking picture behind for good ([tick]). This is why the
     * widgets have no updatePeriodMillis: that one wakes the phone every half hour to redraw
     * what's known.
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

    /** The next moment [m]'s picture changes with nothing new from the Mac (or, live, is checked), or null. */
    private fun nextChange(m: WidgetModel, now: Long): Long? {
        if (m.conn == Conn.Unpaired) return null
        // Live: the check already set stays while it's at least half the interval away, so a
        // busy hub doesn't move the alarm on every redraw.
        if (m.conn == Conn.Live) return alarmAt.takeIf { it - now in LIVE_CHECK / 2..LIVE_CHECK } ?: (now + LIVE_CHECK)
        val u = m.usage
        return listOfNotNull(
            m.ask?.expiresAt,
            // Without official numbers the reset times are placeholders, and aren't shown.
            u.fiveResetAt.takeIf { u.known || u.limited },
            u.sevenResetAt.takeIf { u.known || u.weekLimited },
            // The other agent's window resetting: its readout is fresh again.
            m.other?.resetAt?.takeIf { it > 0L },
            m.updatedAt.takeIf { it > 0L }?.let { WidgetViews.stampChanges(it, now) },
        ).filter { it > now }.minOrNull()
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
        val due = shown?.conn != Conn.Live && alarmAt in 1..System.currentTimeMillis()
        if (next == null && !due) return
        latest = null
        pending?.cancel()
        Core.scope.launch { withContext(NonCancellable) { push(next ?: model()) } }
    }

    // ─── Requests from the platform and from taps ───────────────────────────

    /**
     * The platform asked for a redraw (a widget was added, removed or resized, the device
     * started, the app was updated): draw what's known, in full, now, whether or not the screen
     * is on (a process started just for this may not live to see it come on). [finished] runs
     * once it's drawn.
     */
    fun redraw(ctx: Context, finished: () -> Unit = {}) {
        start(ctx.applicationContext as Application)
        refreshPresence()
        Core.scope.launch {
            try {
                latest = null
                pending?.cancel()
                push(model(), force = true)
            } finally {
                finished()
            }
        }
    }

    /**
     * The alarm set by [schedule]. A picture still live in this same process only gets its next
     * check; any other (one that went out of date by itself, or that a process the system killed
     * left behind) is drawn again, in full. [finished] runs once that's done.
     */
    fun tick(ctx: Context, finished: () -> Unit) {
        start(ctx.applicationContext as Application)
        refreshPresence()
        Core.scope.launch {
            try {
                val drawn = last
                val m = model()
                if (drawn != null && drawn.conn == Conn.Live && m.conn == Conn.Live) {
                    // Anything new is already on its way through [offer].
                    schedule(drawn, System.currentTimeMillis())
                    return@launch
                }
                latest = null
                pending?.cancel()
                push(m, force = true)
            } finally {
                finished()
            }
        }
    }

    /**
     * Allow or Deny from the widget: the same answer the session screen sends, through
     * [com.prudnikweb.hypernova.data.LiveHub.answerAsync] with this phone's token. The block says
     * "Allowing…" at once, then goes when the Mac has it, or says it didn't get through.
     * [finished] runs once the widget shows how it went, or after 8 seconds, whichever is first:
     * a broadcast can't hold the process for longer. [onResult]: whether the Mac took it (a
     * notification's Allow and Deny go this way too, data/NotifyActions.kt). It goes to the computer
     * [server] names (a notification's), else the one the approval waits on (data/Servers.kt).
     */
    fun answer(ctx: Context, approvalId: String, allow: Boolean, server: String? = null, onResult: (Boolean) -> Unit = {}, finished: () -> Unit) {
        start(ctx.applicationContext as Application)
        val live = Core.hubForApproval(server, approvalId)
        if (live == null) { onResult(false); finished(); return }
        Core.scope.launch {
            var released = false
            val release = { if (!released) { released = true; finished() } }
            try {
                answering[approvalId] = if (allow) Answering.Allowing else Answering.Denying
                pushNow()
                val sent = live.answerAsync(approvalId, if (allow) Answer.Allow else Answer.Deny)
                // Not confirmed within 8 s: the answer may still land, so it isn't a failure yet.
                // The block keeps saying "Allowing…" (no buttons) until the request settles,
                // which the HTTP client's own timeouts bound.
                val ok = withTimeoutOrNull(8_000L) { sent.await() } ?: run {
                    release()
                    runCatching { sent.await() }.getOrDefault(false)
                }
                onResult(ok)
                if (ok) {
                    done = done + approvalId
                    answering.remove(approvalId)
                } else {
                    answering[approvalId] = Answering.Failed
                    // The note stays a moment; then the buttons are simply there again.
                    Core.scope.launch {
                        delay(8_000L)
                        if (answering[approvalId] == Answering.Failed) answering.remove(approvalId)
                    }
                }
                pushNow()
            } finally {
                release()
            }
        }
    }

    /**
     * Debug builds: the widget as it is now, drawn at each of [sizes] ("340x56,150x150", in dp)
     * into the app's files as widget-render/<name>-<w>x<h>.png, for a size the home screen at hand
     * can't take (a strip one row tall, a landscape cell). Drawn by inflating the same RemoteViews
     * the launcher gets, outside any launcher:
     *
     *     adb shell run-as com.prudnikweb.hypernova am broadcast --user 0 \
     *       -n com.prudnikweb.hypernova/.widget.WidgetActions -a com.prudnikweb.hypernova.widget.RENDER \
     *       --es kind sun --es sizes 340x56 --es name strip
     *     adb exec-out run-as com.prudnikweb.hypernova cat files/widget-render/strip-340x56.png > strip.png
     *
     * With `--es sample limit` (or `pace`, `rest`) it draws [WidgetSamples] instead of the state.
     */
    fun render(ctx: Context, kind: String?, sizes: String?, name: String?, sample: String? = null, finished: () -> Unit) {
        start(ctx.applicationContext as Application)
        Core.scope.launch {
            try {
                val k = if (kind == "sessions") WidgetKind.Sessions else WidgetKind.Sun
                val now = System.currentTimeMillis()
                val m = if (sample != null) WidgetSamples.of(sample, now) else stamp(model(), now).at(now)
                val dir = File(app.filesDir, "widget-render").apply { mkdirs() }
                val density = app.resources.displayMetrics.density
                for (size in sizes.orEmpty().split(',')) {
                    val (w, h) = size.trim().split('x').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 2 } ?: continue
                    val views = WidgetViews.variant(app, k, m, w, h, WidgetViews.Art(app), listOf(SizeF(w, h)))
                    val view = views.apply(app, FrameLayout(app))
                    val pw = (w * density).roundToInt()
                    val ph = (h * density).roundToInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, pw, ph)
                    val bitmap = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val out = File(dir, "${name ?: k.name.lowercase()}-${size.trim()}.png")
                    out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    Log.i("HypernovaWidget", "rendered ${out.path}")
                }
            } catch (e: Exception) {
                Log.e("HypernovaWidget", "render failed", e)
            } finally {
                finished()
            }
        }
    }

    /**
     * Debug builds: [WidgetSamples] drawn into every widget on the home screen, for screenshots
     * without a Mac (see there). The next real redraw replaces it.
     */
    fun sample(ctx: Context, which: String?, finished: () -> Unit) {
        start(ctx.applicationContext as Application)
        Core.scope.launch {
            try {
                val m = WidgetSamples.of(which, System.currentTimeMillis())
                withContext(drawing) { draw(m, null) }
            } catch (e: Exception) {
                Log.e("HypernovaWidget", "sample failed", e)
            } finally {
                finished()
            }
        }
    }

    /** Draws the current state now, past the throttle: feedback for a tap on the widget. */
    private suspend fun pushNow() {
        latest = null
        pending?.cancel()
        push(model())
    }

    fun component(ctx: Context, kind: WidgetKind) = ComponentName(
        ctx, if (kind == WidgetKind.Sun) SunWidget::class.java else SessionsWidget::class.java,
    )
}
