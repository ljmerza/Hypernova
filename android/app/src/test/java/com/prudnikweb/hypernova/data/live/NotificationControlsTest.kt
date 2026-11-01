package com.prudnikweb.hypernova.data.live

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.ConnectionService
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.NotificationPrefs
import com.prudnikweb.hypernova.data.NotificationPrefsStore
import com.prudnikweb.hypernova.data.Notifier
import com.prudnikweb.hypernova.data.NotifyActions
import com.prudnikweb.hypernova.data.NotifyGroup
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.ServerNotify
import com.prudnikweb.hypernova.data.Servers
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.updateNoticed
import com.prudnikweb.hypernova.widget.WidgetSync
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which notifications reach the shade (data/NotificationPrefs.kt), through [Core] and the real
 * [Notifier] against [FakeDaemon]s: only alerts change, so what a server says still arrives, and
 * what's turned off leaves the shade. Banners and the once-only memories with [HubRig].
 */
@RunWith(RobolectricTestRunner::class)
class NotificationControlsTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val z2 = FakeDaemon("z2")
    private val gym = FakeDaemon("gym")
    private lateinit var z2Id: String
    private lateinit var gymId: String
    private val shade get() = app.getSystemService(NotificationManager::class.java)
    private val nm get() = shadowOf(shade)

    private fun until(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what")
            Thread.sleep(3)
        }
    }

    @Before fun pair() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Core.init(app)
        ComputerWords.clear(app)
        NotificationPrefsStore.set(app, NotificationPrefs())
        Core.setVisible(false)
        z2.snapshot = snapshot("z2", sessions = listOf(session("z1", state = "working", title = "Fix the parser")))
        gym.snapshot = snapshot("gym", sessions = listOf(session("g1", state = "working", title = "Nightly build")))
        Core.paired(Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-1"))
        z2Id = Core.activeId!!
        Core.paired(Pairing(gym.base, "tok-gym", "gym", "m-gym", "dev-1"))
        gymId = Core.activeId!!
        Core.switchTo(z2Id)
        until("both linked") { Core.allHubs.size == 2 && Core.allHubs.all { it.link.state == LinkState.Connected && it.synced } }
        // A first look's approvals and the like are done with.
        shade.cancelAll()
    }

    @After fun forget() {
        Core.setNotifications(NotificationPrefs())
        for (s in Core.servers.toList()) Core.forget(s.id)
        Core.stayConnected(true)
        shadowOf(Looper.getMainLooper()).idle()
        shade.cancelAll()
        z2.close()
        gym.close()
    }

    private fun prefs() = NotificationPrefsStore.get(app)

    /** The id of a session's notice on [server] (Notifier.noticeId). */
    private fun noticeId(server: String, key: String) = ("session:" + (if (server == Servers.LEGACY_ID) key else "$server:$key")).hashCode()

    private fun approvalArrives(d: FakeDaemon, id: String, session: String, server: String) {
        d.push(msg("approval") { put("approval", approval(id, session)) })
        until("$id arrives") { Core.hub(server)!!.pendingApprovals.any { it.id == id } }
    }

    private fun errorArrives(d: FakeDaemon, session: String, server: String) {
        d.push(msg("session") { put("session", session(session, state = "error") { put("lastText", "It crashed") }) })
        until("the error arrives") { Core.hub(server)!!.session(session)?.phase == Phase.Error }
    }

    @Test fun aWatchedServerTurnedOffPostsNothingWhileItsStateStillArrives() {
        Core.setNotifications(prefs().withMode(gymId, ServerNotify.Off))
        approvalArrives(gym, "a1", "g1", gymId)
        errorArrives(gym, "g1", gymId)
        assertNull(nm.getNotification("a1".hashCode()))
        assertNull(nm.getNotification(noticeId(gymId, "g1")))
        // The app and the widget read this: the approval is there to answer.
        assertEquals(listOf("a1"), Core.hub(gymId)!!.pendingApprovals.map { it.id })
        // The other server is another matter.
        approvalArrives(z2, "a2", "z1", z2Id)
        errorArrives(z2, "z1", z2Id)
        assertNotNull(nm.getNotification("a2".hashCode()))
        assertNotNull(nm.getNotification(noticeId(z2Id, "z1")))
    }

    @Test fun aServersCustomSetGatesByKind() {
        Core.setNotifications(prefs().withCustom(gymId, NotifyGroup.Errors, on = false))
        approvalArrives(gym, "a1", "g1", gymId)
        errorArrives(gym, "g1", gymId)
        assertNotNull("approvals stay on", nm.getNotification("a1".hashCode()))
        assertNull("errors are off", nm.getNotification(noticeId(gymId, "g1")))
    }

    @Test fun aServerOnSameAsAllFollowsTheDefaults() {
        Core.setNotifications(prefs().withDefault(NotifyGroup.Errors, on = false))
        errorArrives(gym, "g1", gymId)
        assertNull(nm.getNotification(noticeId(gymId, "g1")))
        Core.setNotifications(prefs().withDefault(NotifyGroup.Errors, on = true))
        errorArrives(z2, "z1", z2Id)
        assertNotNull(nm.getNotification(noticeId(z2Id, "z1")))
    }

    @Test fun aJobThatDiedIsAnErrorAndFollowsItsSwitch() {
        fun died(at: Long) = msg("job.died") { put("sessionId", "g1").put("job", "j1").put("reason", "API error 529").put("at", at) }
        Core.setNotifications(prefs().withDefault(NotifyGroup.Errors, on = false))
        gym.push(died(7_000L))
        // The next approval's arrival says everything before it has been handled.
        approvalArrives(gym, "a1", "g1", gymId)
        assertNull(nm.getNotification(noticeId(gymId, "g1")))
        Core.setNotifications(prefs().withDefault(NotifyGroup.Errors, on = true))
        gym.push(died(8_000L))
        until("the notice") { nm.getNotification(noticeId(gymId, "g1")) != null }
    }

    @Test fun theUpdatedNoticeOfTheAppItselfFollowsTheDefaults() {
        val kept = app.getSharedPreferences("hypernova", android.content.Context.MODE_PRIVATE)
        fun replaced() {
            kept.edit().putInt("selfUpdateFrom", BuildConfig.VERSION_CODE - 1).apply()
            app.sendBroadcast(Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(app.packageName))
            shadowOf(Looper.getMainLooper()).idle()
        }
        Core.setNotifications(prefs().withDefault(NotifyGroup.Updates, on = false))
        replaced()
        assertNull(nm.getNotification(4733))
        Core.setNotifications(prefs().withDefault(NotifyGroup.Updates, on = true))
        replaced()
        assertNotNull(nm.getNotification(4733))
        // And it goes with the switch, as the rest do.
        Core.setNotifications(prefs().withDefault(NotifyGroup.Updates, on = false))
        assertNull(nm.getNotification(4733))
    }

    private fun showing(server: String, kind: Notice.Kind, session: String) =
        Core.notifier.notice(Notice("$kind:$session", kind, session, "A session", "Words"), server = server)

    @Test fun aGroupTurnedOffCancelsWhatsShowingFromThatGroupAlone() {
        showing(z2Id, Notice.Kind.Finished, "z1")
        showing(gymId, Notice.Kind.Finished, "g1")
        showing(z2Id, Notice.Kind.Error, "z2")
        approvalArrives(z2, "a1", "z1", z2Id)
        // A server with Custom keeps its own: it isn't following the defaults.
        Core.setNotifications(prefs().withMode(gymId, ServerNotify.Custom).withDefault(NotifyGroup.Finished, on = false))
        assertNull(nm.getNotification(noticeId(z2Id, "z1")))
        assertNotNull(nm.getNotification(noticeId(gymId, "g1")))
        assertNotNull(nm.getNotification(noticeId(z2Id, "z2")))
        assertNotNull(nm.getNotification("a1".hashCode()))
    }

    /**
     * Allow on approval [id]'s notification, as Android delivers it, to its very end. An answer goes
     * the widget's way, which draws before and after it holding WidgetSync's lock, and it outlives a
     * test: dropped halfway by a test that ends early, the lock stays held for the rest of the
     * process, and the next test's answer or picture never comes.
     */
    private fun allowFromTheNotification(id: String, server: String) {
        val pictures = AtomicInteger()
        WidgetSync.drawn = { pictures.incrementAndGet() }
        app.sendBroadcast(Intent(app, NotifyActions::class.java).setAction(NotifyActions.ACTION_ALLOW).putExtra(NotifyActions.EXTRA_SERVER, server).putExtra(NotifyActions.EXTRA_APPROVAL, id))
        until("the answer's two pictures") { pictures.get() >= 2 }
        WidgetSync.drawn = null
        // The lock let go, and a failed answer's note, 8 s on the app's clock, gone.
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
    }

    @Test fun aServerTurnedOffCancelsItsOwnAndItsApprovalsGoOnWorking() {
        approvalArrives(gym, "m1", "g1", gymId)
        approvalArrives(z2, "m2", "z1", z2Id)
        assertNotNull(nm.getNotification("m1".hashCode()))
        Core.setNotifications(prefs().withMode(gymId, ServerNotify.Off))
        assertNull(nm.getNotification("m1".hashCode()))
        assertNotNull(nm.getNotification("m2".hashCode()))
        // An Allow on the notification, tapped just before: it still reaches the server.
        gym.ok("POST", "/v1/approvals/m1")
        allowFromTheNotification("m1", gymId)
        assertEquals("allow", gym.calls("POST", "/v1/approvals/m1").single().json.getString("decision"))
    }

    @Test fun anAnswerThatFailedSaysNothingFromAServerTurnedOffSinceItsCardHasIt() {
        approvalArrives(gym, "m3", "g1", gymId)
        Core.setNotifications(prefs().withMode(gymId, ServerNotify.Off))
        gym.on("POST", "/v1/approvals/m3") { FakeDaemon.refusal(500, "oops", "No") }
        allowFromTheNotification("m3", gymId)
        assertEquals(1, gym.count("POST", "/v1/approvals/m3"))
        assertNull(nm.getNotification("m3".hashCode()))
        assertEquals(listOf("m3"), Core.hub(gymId)!!.pendingApprovals.map { it.id })
    }

    @Test fun theMasterOffCancelsEverythingButTheConnectionsOwn() {
        val c = Robolectric.buildService(ConnectionService::class.java).create()
        until("the permanent notification") { nm.getNotification(Notifier.CONNECTION_ID) != null }
        showing(z2Id, Notice.Kind.Finished, "z1")
        approvalArrives(gym, "a1", "g1", gymId)
        Core.setNotifications(prefs().copy(master = false))
        assertNull(nm.getNotification(noticeId(z2Id, "z1")))
        assertNull(nm.getNotification("a1".hashCode()))
        assertNotNull("Android requires it", nm.getNotification(Notifier.CONNECTION_ID))
        // Nothing new either, from either server.
        approvalArrives(z2, "a2", "z1", z2Id)
        assertNull(nm.getNotification("a2".hashCode()))
        c.destroy()
    }

    @Test fun aReplyThatFailedIsSaidWhateverTheSwitchesSayAndStays() {
        Core.setNotifications(prefs().copy(master = false))
        Core.notifier.replyFailed("z1", "Fix the parser", "Didn't send", server = z2Id)
        assertNotNull(nm.getNotification(noticeId(z2Id, "z1")))
        Core.notifier.cancelSilenced()
        assertNotNull(nm.getNotification(noticeId(z2Id, "z1")))
    }

    @Test fun aSilencedNoticeStillTakesTheLastOnesPlace() {
        showing(z2Id, Notice.Kind.Waiting, "z1")
        assertNotNull(nm.getNotification(noticeId(z2Id, "z1")))
        Core.setNotifications(prefs().withDefault(NotifyGroup.Finished, on = false))
        assertNotNull("Waiting is still on", nm.getNotification(noticeId(z2Id, "z1")))
        // The session moved on: it isn't waiting any more, and Finished is off.
        showing(z2Id, Notice.Kind.Finished, "z1")
        assertNull(nm.getNotification(noticeId(z2Id, "z1")))
    }

    @Test fun anAutomationsNotesAndQuestionsFollowTheirSwitchAndServer() {
        val note = { id: String, more: JSONObject.() -> Unit ->
            JSONObject().put("id", id).put("automationId", "auto-$id").put("runId", "run-$id").put("name", "Nightly tests").put("text", "All pass.").put("to", "phone").put("at", System.currentTimeMillis()).apply(more)
        }
        val ask = JSONObject().put("stepId", "step-1").put("question", "Deploy now?").put("answers", JSONArray(listOf("Yes", "No"))).put("default", "No").put("until", System.currentTimeMillis() + 600_000)
        fun automations() = nm.allNotifications.filter { it.channelId == "automations" }
        // Off for gym: its note says nothing, and it's in the list.
        Core.setNotifications(prefs().withCustom(gymId, NotifyGroup.Automations, on = false))
        gym.push(msg("note") { put("note", note("n1") {}) })
        gym.push(msg("note") { put("note", note("q1") { put("ask", ask) }) })
        approvalArrives(gym, "a1", "g1", gymId)
        assertTrue(automations().isEmpty())
        // On for z2: a note and a question, and both go when its switch does.
        z2.push(msg("note") { put("note", note("n2") {}) })
        z2.push(msg("note") { put("note", note("q2") { put("ask", ask) }) })
        until("the note and the question") { automations().size == 2 }
        Core.setNotifications(prefs().withDefault(NotifyGroup.Automations, on = false))
        assertTrue(automations().isEmpty())
    }

    @Test fun forgettingAServerRemovesItsOverride() {
        Core.setNotifications(prefs().withMode(gymId, ServerNotify.Off).withMode(z2Id, ServerNotify.Custom))
        Core.forget(gymId)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf(z2Id), prefs().servers.keys)
    }

    // ─── With the hub's own view: banners, and what's remembered once. ───

    private fun rig(block: (HubRig, FakeDaemon) -> Unit) {
        val d = FakeDaemon("shed")
        d.snapshot = snapshot("shed", sessions = listOf(session("s1", state = "working")))
        val rig = HubRig(d, serverId = "shed")
        try {
            shadowOf(rig.context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            rig.connect()
            block(rig, d)
        } finally {
            rig.close()
        }
    }

    @Test fun aBannerStillShowsWithTheServerOffAndTheApprovalStaysInTheHub() = rig { rig, d ->
        NotificationPrefsStore.set(app, prefs().withMode("shed", ServerNotify.Off).copy(master = false))
        rig.push(msg("approval") { put("approval", approval("a1", "s1")) }) { rig.banners.any { it.id == "a:a1" } }
        assertEquals(listOf("a1"), rig.hub.pendingApprovals.map { it.id })
        // An error on screen too.
        d.push(msg("session") { put("session", session("s1", state = "error") { put("lastText", "It crashed") }) })
        rig.until("its banner") { rig.banners.any { it.kind == Notice.Kind.Error } }
    }

    @Test fun anUpdateSilencedOffScreenIsntUsedUpForTheBannerOrAnotherServer() = rig { rig, _ ->
        NotificationPrefsStore.set(app, prefs().withCustom("shed", NotifyGroup.Updates, on = false))
        rig.visible = false
        val code = 2_000_000_101
        val offer = JSONObject().put("versionCode", code).put("versionName", "9.9.11")
        rig.push(msg("app") { put("app", offer) }) { rig.hub.appUpdate != null }
        assertTrue(rig.notices.none { it.kind == Notice.Kind.Update })
        assertTrue("the app's banner still has it", updateNoticed(rig.context, code))
    }
}
