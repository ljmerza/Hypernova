package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.usage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Connecting (docs/PROTOCOL.md, "WebSocket"): the snapshot fills the hub, then the socket opens and answers pings. */
@RunWith(RigRunner::class)
class ConnectTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }

    @After fun tearDown() = rig.close()

    @Test fun theSnapshotFillsSessionsApprovalsAndUsageThenTheSocketOpens() {
        val in5h = rig.now + 3 * 3_600_000L
        val in7d = rig.now + 5 * 86_400_000L
        daemon.snapshot = snapshot(
            sessions = listOf(session("s1", state = "working", lastActivityAt = 2_000), session("s2", state = "idle", lastActivityAt = 3_000)),
            approvals = listOf(approval("a1", "s1")),
            usage = usage(40.0, 10.0, in5h, in7d),
        )
        assertEquals(LinkState.Connecting, rig.hub.link.state)
        assertFalse(rig.hub.synced)
        rig.connect()

        val hub = rig.hub
        assertTrue(hub.synced)
        // Newest activity first.
        assertEquals(listOf("s2", "s1"), hub.sessions.map { it.id })
        // s1 waits on a1: it needs the owner, whatever its state says.
        assertEquals(SessionState.NeedsYou, hub.session("s1")!!.state)
        assertEquals(SessionState.Idle, hub.session("s2")!!.state)
        assertEquals(listOf("a1"), hub.pendingApprovals.map { it.id })
        assertEquals("a1", hub.approval("s1")?.id)
        assertEquals(0.4f, hub.usage.fiveHour, 0.001f)
        assertEquals(0.1f, hub.usage.sevenDay, 0.001f)
        assertEquals(in5h, hub.usage.fiveHourResetAt)
        assertTrue(hub.usage.known)
        assertEquals("z2", hub.mac.name)
        assertEquals(Mac.LINUX, hub.mac.platform)
        assertTrue(hub.mac.online)
        assertEquals(Mac.VIA_REMOTE, hub.mac.via)
    }

    @Test fun everyRequestCarriesTheToken() {
        rig.connect()
        val asked = daemon.requests.filter { it.bare == "/v1/snapshot" || it.bare == "/v1/ws" }
        assertEquals(listOf("/v1/snapshot", "/v1/ws"), asked.map { it.bare })
        assertTrue(asked.all { it.auth == "Bearer tok-z2" })
    }

    @Test fun theRouterProbesTheBaseUrlFirst() {
        rig.connect()
        val first = daemon.requests.indexOfFirst { it.bare == "/v1/health" }
        val snap = daemon.requests.indexOfFirst { it.bare == "/v1/snapshot" }
        assertTrue("health probe before the snapshot: ${daemon.requests.map { it.bare }}", first in 0 until snap)
    }

    @Test fun aPingIsAnsweredWithAPong() {
        rig.connect()
        daemon.push(msg("ping"))
        rig.until("the pong") { daemon.received.any { JSONObject(it).optString("t") == "pong" } }
    }

    @Test fun anApprovalInTheSnapshotIsAnnouncedOnceAsABannerOnScreen() {
        daemon.snapshot = snapshot(sessions = listOf(session("s1", state = "needs_you")), approvals = listOf(approval("a1", "s1", summary = "Bash: rm -rf build")))
        rig.connect()
        rig.until("the banner") { rig.banners.any { it.id == "a:a1" } }
        assertTrue(rig.notified.isEmpty())
        // A resync with the same approval says nothing again.
        daemon.push(msg("snapshot") { put("snapshot", daemon.snapshot) })
        rig.sync()
        assertEquals(1, rig.banners.count { it.id == "a:a1" })
    }

    @Test fun offScreenAnApprovalInTheSnapshotIsANotification() {
        daemon.snapshot = snapshot(sessions = listOf(session("s1", state = "needs_you")), approvals = listOf(approval("a1", "s1")))
        rig.visible = false
        rig.connect()
        assertEquals(listOf("a1"), rig.notified.map { it.first.id })
        assertEquals("s1", rig.notified.single().second?.id)
        assertTrue(rig.banners.none { it.id == "a:a1" })
    }

    @Test fun aSnapshotWithoutAnApprovalLetsItsNotificationGo() {
        daemon.snapshot = snapshot(sessions = listOf(session("s1")), approvals = listOf(approval("a1", "s1")))
        rig.connect()
        daemon.snapshot = snapshot(sessions = listOf(session("s1")))
        daemon.push(msg("snapshot") { put("snapshot", daemon.snapshot) })
        rig.until("a1 gone") { rig.hub.pendingApprovals.isEmpty() }
        assertEquals(listOf("a1"), rig.gone)
    }
}
