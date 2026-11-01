package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.SERVER_UPDATE_NOTICE
import com.prudnikweb.hypernova.data.ServerUpdate
import com.prudnikweb.hypernova.data.Servers
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A server's own update as the hub tells of it (docs/PROTOCOL.md, "The server's own update"): one
 * that failed is a banner or a notification, once per release and once more when the server gives
 * up, each server's its own; nothing while an update goes well. And an approval a daemon restart
 * took away, its session still asking in its own dialog, is said again.
 */
@RunWith(RobolectricTestRunner::class)
class ServerUpdateNoticeTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }

    @After fun tearDown() = rig.close()

    private fun fx(name: String) = JSONObject(javaClass.classLoader!!.getResource("fixtures/$name.json")!!.readText())

    private fun update(state: String = "idle", target: String? = null, failure: JSONObject? = null) = JSONObject()
        .put("auto", true).put("available", true).put("reason", JSONObject.NULL)
        .put("state", state).put("target", target ?: JSONObject.NULL).put("last", JSONObject.NULL)
        .put("failure", failure ?: JSONObject.NULL)

    private fun failure(tag: String = "v0.14.0", next: Long? = 1_800_003_600_000L) = JSONObject()
        .put("tag", tag).put("why", "the new daemon didn’t answer, so the old one is back; still 0.13.1")
        .put("at", 1_800_000_000_000L).put("next", next ?: JSONObject.NULL)

    private fun message(u: JSONObject) = JSONObject().put("t", "update").put("update", u)

    private fun connect(r: HubRig = rig, u: JSONObject? = update()) {
        r.daemon.snapshot = snapshot(name = r.daemon.name) { if (u != null) getJSONObject("mac").put("update", u) }
        r.connect()
    }

    @Test fun onScreen_aFailureIsABannerOncePerRelease_andOnceMoreWhenItGivesUp() {
        connect()
        rig.push(fx("ws-update-failed")) { rig.banners.isNotEmpty() }
        val n = rig.banners.single()
        assertEquals(SERVER_UPDATE_NOTICE, n.id)
        assertEquals(Notice.Kind.ServerUpdateFailed, n.kind)
        assertEquals("z2", n.title)
        assertNull("a banner opens Settings, it has no button", n.action)
        assertTrue(n.text, n.text.startsWith(rig.context.getString(R.string.su_failed_why, "0.9.0", "the new daemon didn’t answer, so the old one is back; still 0.5.0")))
        assertTrue(n.text, n.text.endsWith(rig.context.getString(R.string.su_failed_again)))
        assertTrue("no notification while on screen", rig.notices.isEmpty())
        assertEquals(ServerUpdate.Failure("v0.9.0", "the new daemon didn’t answer, so the old one is back; still 0.5.0", 1_800_000_000_000L, 1_800_003_600_000L), rig.hub.mac.update?.failure)

        // The same failure again (the next try's, a reconnect's): not said again.
        rig.push(fx("ws-update-failed"))  { true }
        rig.sync()
        assertEquals(1, rig.banners.size)

        // It gives up on that release.
        rig.push(message(update(failure = failure("v0.9.0", next = null)))) { rig.banners.size == 2 }
        assertTrue(rig.banners[1].text, rig.banners[1].text.endsWith(rig.context.getString(R.string.su_failed_final)))

        // A newer release fails: news again.
        rig.push(message(update(failure = failure("v0.10.0")))) { rig.banners.size == 3 }
    }

    @Test fun offScreen_aFailureIsANotification_fromASnapshotToo_andOnceAcrossRestartsOfTheApp() {
        rig.visible = false
        connect(u = update(failure = failure()))
        rig.until("the notice") { rig.notices.isNotEmpty() }
        val n = rig.notices.single()
        assertEquals(Notice.Kind.ServerUpdateFailed, n.kind)
        assertTrue(rig.banners.isEmpty())
        // The app started again, the same failure in the snapshot: remembered on the phone.
        val again = HubRig(FakeDaemon("z2"), dispatcher = StandardTestDispatcher())
        try {
            again.visible = false
            connect(again, update(failure = failure()))
            again.sync()
            assertTrue(again.notices.isEmpty())
        } finally {
            again.close()
        }
    }

    @Test fun eachWatchedServerSaysItsOwn() {
        val dispatcher = StandardTestDispatcher()
        val z2 = HubRig(FakeDaemon("z2"), serverId = Servers.LEGACY_ID, dispatcher = dispatcher)
        val gym = HubRig(FakeDaemon("gym"), serverId = "gym", dispatcher = dispatcher).also { it.visible = false }
        try {
            connect(z2, update(failure = failure()))
            connect(gym, update(failure = failure()))
            z2.until("z2's banner") { z2.banners.isNotEmpty() }
            gym.until("gym's notification") { gym.notices.isNotEmpty() }
            assertEquals("z2", z2.banners.single().title)
            assertEquals("gym", gym.notices.single().title)
            assertTrue(gym.banners.isEmpty())
        } finally {
            z2.close()
            gym.close()
        }
    }

    @Test fun anUpdateGoingWell_orAnOlderDaemon_saysNothing() {
        connect(u = update())
        rig.push(message(update(state = "installing", target = "v0.14.0"))) { rig.hub.mac.update?.state == ServerUpdate.INSTALLING }
        rig.sync()
        assertTrue(rig.banners.isEmpty())
        assertTrue(rig.notices.isEmpty())
        // A daemon from before: "waiting" for a quiet moment, no failure field.
        rig.push(message(JSONObject().put("auto", true).put("available", true).put("state", "waiting").put("target", "v0.14.0"))) { rig.hub.mac.update?.state == ServerUpdate.WAITING }
        rig.sync()
        assertNull(rig.hub.mac.update?.failure)
        assertTrue(rig.banners.isEmpty())
        // A state this app doesn't know: idle.
        rig.push(message(update(state = "something-new", target = "v0.14.0"))) { rig.hub.mac.update?.state == ServerUpdate.IDLE }
        assertNull(rig.hub.mac.update?.target)
    }

    @Test fun anApprovalARestartTookAway_itsSessionStillAsking_isSaidAgain() {
        rig.visible = false
        daemon.snapshot = snapshot(sessions = listOf(session("s1")), approvals = listOf(approval("a1", "s1")))
        rig.connect()
        rig.until("its notification") { rig.notified.isNotEmpty() }
        // The daemon restarted: the hook's gone, and Claude Code asks in its own dialog.
        daemon.snapshot = snapshot(sessions = listOf(session("s1") { put("state", "needs_you") }))
        val opened = daemon.opened.get()
        daemon.closeSocket()
        rig.until("back") { daemon.opened.get() > opened }
        rig.until("the approval's notification goes") { rig.gone.contains("a1") }
        rig.until("said again") { rig.notices.any { it.kind == Notice.Kind.Waiting && it.sessionId == "s1" } }
        assertNotNull(rig.notices.single { it.kind == Notice.Kind.Waiting })
    }

    @Test fun anApprovalAnsweredWhileAway_isNotSaidAgain() {
        rig.visible = false
        daemon.snapshot = snapshot(sessions = listOf(session("s1")), approvals = listOf(approval("a1", "s1")))
        rig.connect()
        rig.until("its notification") { rig.notified.isNotEmpty() }
        daemon.snapshot = snapshot(sessions = listOf(session("s1") { put("state", "working") }))
        val opened = daemon.opened.get()
        daemon.closeSocket()
        rig.until("back") { daemon.opened.get() > opened }
        rig.until("the approval's notification goes") { rig.gone.contains("a1") }
        rig.sync()
        assertTrue(rig.notices.none { it.kind == Notice.Kind.Waiting })
    }
}
