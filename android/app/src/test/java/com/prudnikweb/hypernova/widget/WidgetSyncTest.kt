package com.prudnikweb.hypernova.widget

import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.storage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The home screen widget kept current (widget/WidgetSync.kt) against a scripted computer, through
 * [Core] as the app runs it: what it redraws and when (at most every 1.5 s, a burst drawn once,
 * nothing while the screen is off and what changed drawn as it comes on, never on a timer), its list's
 * rows (WidgetListService), and its taps (WidgetTap, WidgetActions): Allow and Deny to the row's
 * computer, Open, and nothing answered on a locked phone. Time is Robolectric's: the 1.5 s gap and
 * the delays pass only when a test lets them ([pass]).
 */
@RunWith(RobolectricTestRunner::class)
class WidgetSyncTest {
    private val z2 = FakeDaemon("z2")
    private val draws = CopyOnWriteArrayList<WidgetModel>()
    private val app get() = AndroidCore.app
    private val widget = 77

    @Before fun setUp() {
        AndroidCore.init(RuntimeEnvironment.getApplication())
        ComputerWords.clear(app.storage)
        // The widget's wall clock moves with the main thread's delays: the gap passes only as a test lets it.
        val base = System.currentTimeMillis() - SystemClock.uptimeMillis()
        WidgetSync.clock = { base + SystemClock.uptimeMillis() }
        screen(on = true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
    }

    @After fun tearDown() {
        WidgetSync.drawn = null
        WidgetSync.clock = System::currentTimeMillis
        for (s in Core.servers.toList()) Core.forget(s.id)
        // A picture still waiting for its gap (or a failed answer's note) finishes now: Robolectric
        // drops what's left on the main thread at the end of a test, and WidgetSync outlives it.
        pass(10_000)
        // The widget taken off again, so WidgetSync stops watching: Core's Application (and its
        // widget manager) is the first test's and outlives this one, and the next test's screen
        // receiver must be its own. The shadow has no way to remove one but its map.
        val mgr = shadowOf(AppWidgetManager.getInstance(app))
        (mgr.javaClass.getDeclaredField("widgetInfos").apply { isAccessible = true }.get(mgr) as MutableMap<*, *>).clear()
        WidgetSync.refreshPresence()
        settle()
        z2.close()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Waits in real time (requests and the socket are real; drawing is off the main thread) until [cond]. */
    private fun until(what: String, timeoutMs: Long = 8_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            idle()
            if (cond()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (draws ${draws.size}, ${Core.statuses})")
            Thread.sleep(3)
        }
    }

    /** Settles in real time without letting Robolectric's clock move: whatever is due now runs. */
    private fun settle(ms: Long = 250) {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) { idle(); Thread.sleep(5) }
    }

    /** Lets [ms] pass on the app's clock (its delays), then settles. */
    private fun pass(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        settle()
    }

    private fun screen(on: Boolean) = shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(on)

    /** z2 paired and linked with [sessions] and [approvals], one widget placed, and its first picture drawn. */
    private fun placed(sessions: List<JSONObject>, approvals: List<JSONObject> = emptyList()) {
        z2.snapshot = snapshot("z2", sessions = sessions, approvals = approvals)
        Core.paired(Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-1"))
        until("linked") { Core.live?.link?.state == LinkState.Connected && Core.live?.synced == true }
        shadowOf(AppWidgetManager.getInstance(app)).bindAppWidgetId(widget, WidgetSync.component(app))
        WidgetSync.drawn = { draws += it }
        // As the launcher's first onUpdate: drawn at once.
        var done = false
        WidgetSync.redraw(app) { done = true }
        until("the first picture") { done && draws.isNotEmpty() }
        settle()
        draws.clear()
    }

    private val shown: WidgetModel get() = WidgetSync.current()

    /** The header of the picture [m], as the launcher shows it. */
    private fun header(m: WidgetModel): View = WidgetViews.build(app, widget, m).apply(app, FrameLayout(app))

    private fun newApproval(id: String, sid: String) = msg("approval") { put("approval", approval(id, sid)) }

    private fun waiting(sid: String) = msg("session") { put("session", session(sid, state = "needs_you")) }

    // ─── When it redraws (WG3) ───

    @Test fun aChangeIsDrawnOnceTheGapIsUpAndABurstIsDrawnOnce() {
        placed(listOf(session("s1"), session("s2"), session("s3")))
        assertEquals(0, shown.count)
        // A change right after a picture waits out the 1.5 s gap.
        z2.push(waiting("s1"))
        z2.push(newApproval("a1", "s1"))
        settle()
        assertTrue("nothing before the gap is up", draws.isEmpty())
        // Two more changes meanwhile: the same picture takes them.
        z2.push(waiting("s2"))
        z2.push(newApproval("a2", "s2"))
        settle()
        pass(1_000)
        assertTrue(draws.isEmpty())
        pass(600)
        until("the picture after the gap") { draws.isNotEmpty() }
        settle()
        assertEquals("one picture for the burst", 1, draws.size)
        assertEquals(2, draws.single().count)
        assertEquals("2", header(draws.single()).findViewById<TextView>(R.id.w_count).text.toString())
        // Nothing changes: nothing is drawn, however long (no timer of its own).
        pass(10 * 60_000)
        assertEquals(1, draws.size)
    }

    @Test fun backToWhatsShownIsNoPicture() {
        placed(listOf(session("s1")))
        z2.push(waiting("s1"))
        z2.push(newApproval("a1", "s1"))
        settle()
        // Answered elsewhere before the gap is up: what's on screen is right again.
        z2.push(msg("approval.resolved") { put("id", "a1"); put("decision", "allow") })
        z2.push(msg("session") { put("session", session("s1")) })
        settle()
        pass(2_000)
        assertTrue("nothing new to draw: $draws", draws.isEmpty())
    }

    @Test fun nothingIsDrawnWithTheScreenOffAndWhatChangedIsDrawnAsItComesOn() {
        placed(listOf(session("s1")))
        pass(2_000)
        screen(on = false)
        z2.push(waiting("s1"))
        z2.push(newApproval("a1", "s1"))
        settle()
        pass(30_000)
        assertTrue("not with the screen off", draws.isEmpty())
        screen(on = true)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
        until("drawn as the screen comes on") { draws.isNotEmpty() }
        settle()
        assertEquals(1, draws.size)
        assertEquals(1, draws.single().count)
        // At once: no gap waited out.
        pass(0)
        assertEquals(1, draws.size)
    }

    // ─── Its rows (WidgetListService) ───

    @Test fun theListsRowsAreTheComputerThenWhatEachSessionWaitsOn() {
        placed(listOf(session("s1", state = "needs_you", title = "Deploy")), listOf(approval("a1", "s1", summary = "Bash: npm test")))
        val service = Robolectric.setupService(WidgetListService::class.java)
        val rows = service.onGetViewFactory(Taps.list(app, widget))
        rows.onCreate()
        rows.onDataSetChanged()
        assertEquals(2, rows.count)
        assertEquals(2, rows.viewTypeCount)
        assertTrue(rows.hasStableIds())
        val parent = FrameLayout(app)
        val server = rows.getViewAt(0).apply(app, parent)
        assertEquals("z2", server.findViewById<TextView>(R.id.s_name).text.toString())
        assertEquals("1", server.findViewById<TextView>(R.id.s_count).text.toString())
        assertEquals(View.GONE, server.findViewById<View>(R.id.s_state).visibility)
        val need = rows.getViewAt(1).apply(app, parent)
        assertEquals("Deploy", need.findViewById<TextView>(R.id.n_title).text.toString())
        assertEquals("Bash", need.findViewById<TextView>(R.id.n_kind).text.toString())
        assertEquals("$ ls", need.findViewById<TextView>(R.id.n_command).text.toString())
        // A whole command: Deny and Allow, no Open.
        assertEquals(View.VISIBLE, need.findViewById<View>(R.id.n_deny).visibility)
        assertEquals(View.VISIBLE, need.findViewById<View>(R.id.n_allow).visibility)
        assertEquals(View.GONE, need.findViewById<View>(R.id.n_open).visibility)
        // Its key holds still across pictures.
        assertEquals(rows.getItemId(1), rows.getItemId(1))
        assertTrue(rows.getItemId(0) != rows.getItemId(1))
    }

    // ─── Its taps (WG2) ───

    private fun row(): WidgetModel.Row = shown.groups.single().rows.single()

    /** A tap on the list, as the launcher fills the template in with the row's [fillIn]. */
    private fun tap(fillIn: Intent) {
        Robolectric.buildActivity(WidgetTap::class.java, Intent(app, WidgetTap::class.java).putExtras(fillIn)).create()
        settle()
    }

    @Test fun allowFromTheWidgetGoesToTheRowsComputerAndTheRowGoes() {
        placed(listOf(session("s1", state = "needs_you")), listOf(approval("a1", "s1")))
        val r = row()
        assertEquals("a1", r.askId)
        z2.ok("POST", "/v1/approvals/a1")
        tap(Taps.answer(r, allow = true))
        until("the answer") { z2.count("POST", "/v1/approvals/a1") == 1 }
        assertEquals("allow", z2.calls("POST", "/v1/approvals/a1").single().json.getString("decision"))
        // Its Deny and Allow go once the computer has it, before its word arrives (drawn at once, past
        // the gap); the session still waits on the owner until the computer says otherwise.
        until("the approval gone") { shown.groups.single().rows.none { it.askId == "a1" } }
        assertEquals(WidgetModel.Kind.Waiting, row().kind)
        assertTrue(draws.size >= 2)
        assertEquals(WidgetModel.Answering.Allowing, draws.first().groups.single().rows.single().answering)
    }

    @Test fun denyThatDoesntGetThroughSaysSoThenTheButtonsComeBack() {
        placed(listOf(session("s1", state = "needs_you")), listOf(approval("a1", "s1")))
        z2.on("POST", "/v1/approvals/a1") { refusal(500, "oops", "Something went wrong.") }
        tap(Taps.answer(row(), allow = false))
        until("the answer") { z2.count("POST", "/v1/approvals/a1") == 1 }
        assertEquals("deny", z2.calls("POST", "/v1/approvals/a1").single().json.getString("decision"))
        until("it failed") { row().answering == WidgetModel.Answering.Failed }
        assertEquals(1, shown.count)
        pass(8_500)
        until("the buttons back") { row().answering == null }
    }

    @Test fun claudeCodesOwnDialogIsAnsweredThroughItsMenuOnTheRowsComputer() {
        // The contract's session waiting on Claude Code's permission dialog in its terminal.
        val snap = JSONObject(javaClass.classLoader!!.getResource("fixtures/snapshot.json")!!.readText())
        val dialog = (0 until snap.getJSONArray("sessions").length()).map { snap.getJSONArray("sessions").getJSONObject(it) }.single { it.getString("id") == "sess-dialog" }
        // Open in a shared terminal (the contract's has none), so the widget can answer it there.
        dialog.put("term", JSONObject().put("id", "term-1").put("mac", 0).put("phone", 0))
        placed(listOf(dialog))
        val r = row()
        assertEquals(WidgetModel.Kind.Terminal, r.kind)
        val menuId = dialog.getJSONObject("menu").getString("id")
        assertEquals(menuId, r.askId)
        z2.ok("POST", "/v1/sessions/sess-dialog/menu")
        tap(Taps.answer(r, allow = false))
        until("the answer") { z2.count("POST", "/v1/sessions/sess-dialog/menu") == 1 }
        val b = z2.calls("POST", "/v1/sessions/sess-dialog/menu").single().json
        assertEquals(menuId, b.getString("menuId"))
        assertEquals(r.denyChoice, b.getInt("choice"))
        assertTrue(r.denyChoice >= 0)
        assertEquals(0, z2.count("POST", "/v1/approvals/$menuId"))
    }

    @Test fun onALockedPhoneATapOpensTheSessionAndAnswersNothing() {
        placed(listOf(session("s1", state = "needs_you")), listOf(approval("a1", "s1")))
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(true)
        tap(Taps.answer(row(), allow = true))
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity::class.java.name, opened.component?.className)
        assertEquals(MainActivity.ACTION_OPEN_SESSION, opened.action)
        assertEquals("s1", opened.getStringExtra(MainActivity.EXTRA_SESSION))
        assertEquals(Core.activeId, opened.getStringExtra(MainActivity.EXTRA_SERVER))
        settle(400)
        assertEquals(0, z2.count("POST", "/v1/approvals/a1"))
        assertEquals(1, shown.count)
    }

    @Test fun openAndTheComputersNameOpenTheApp() {
        placed(listOf(session("s1", state = "needs_you")), listOf(approval("a1", "s1")))
        val server = Core.activeId!!
        tap(Taps.open(server, "s1"))
        val session = shadowOf(app).nextStartedActivity
        assertEquals(MainActivity.ACTION_OPEN_SESSION, session.action)
        assertEquals("s1", session.getStringExtra(MainActivity.EXTRA_SESSION))
        assertEquals(server, session.getStringExtra(MainActivity.EXTRA_SERVER))
        // A computer's name: the app on that computer, no session.
        tap(Taps.open(server, null))
        val home = shadowOf(app).nextStartedActivity
        assertNull(home.action)
        assertFalse(home.hasExtra(MainActivity.EXTRA_SESSION))
        assertEquals(server, home.getStringExtra(MainActivity.EXTRA_SERVER))
        assertEquals(0, z2.count("POST", "/v1/approvals/a1"))
    }
}
