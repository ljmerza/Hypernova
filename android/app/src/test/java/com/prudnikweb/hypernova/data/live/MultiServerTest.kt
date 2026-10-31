package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.Servers
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.approval
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Two paired computers (data/Servers.kt, data/Core.kt): a hub each, the active one on screen and the
 * other watched in the background. An answer goes to the computer the approval waits on, as
 * [com.prudnikweb.hypernova.data.Core.hubForApproval] picks it with [Servers.approvalTarget].
 */
@RunWith(RobolectricTestRunner::class)
class MultiServerTest {
    private val dispatcher = StandardTestDispatcher()
    private val z2 = FakeDaemon("z2")
    private val gym = FakeDaemon("gym")
    private val active by lazy { HubRig(z2, serverId = Servers.LEGACY_ID, dispatcher = dispatcher) }
    // Watched in the background: not the computer on screen.
    private val watched by lazy { HubRig(gym, serverId = "gym", dispatcher = dispatcher).also { it.visible = false } }

    @After fun tearDown() {
        active.close()
        watched.close()
    }

    private fun connectBoth() {
        z2.snapshot = snapshot("z2", sessions = listOf(session("z-1", state = "working")))
        gym.snapshot = snapshot("gym", sessions = listOf(session("g-1", state = "working"), session("g-2")))
        active.connect()
        watched.connect()
    }

    /** What Core.hubForApproval does, over these two hubs. */
    private fun hubFor(server: String?, approvalId: String) =
        Servers.approvalTarget(server, approvalId, Servers.LEGACY_ID, mapOf(Servers.LEGACY_ID to active.hub.pendingApprovals.map { it.id }, "gym" to watched.hub.pendingApprovals.map { it.id }))
            ?.let { if (it == Servers.LEGACY_ID) active.hub else watched.hub }

    @Test fun eachHubHasItsOwnServersSessionsAndToken() {
        connectBoth()
        assertEquals(listOf("z-1"), active.hub.sessions.map { it.id })
        assertEquals(setOf("g-1", "g-2"), watched.hub.sessions.map { it.id }.toSet())
        assertEquals("z2", active.hub.mac.name)
        assertEquals("gym", watched.hub.mac.name)
        assertTrue(z2.requests.filter { it.bare != "/v1/health" }.all { it.auth == "Bearer tok-z2" })
        assertTrue(gym.requests.filter { it.bare != "/v1/health" }.all { it.auth == "Bearer tok-gym" })
    }

    @Test fun theWatchedHubsApprovalIsANotificationNotABanner() {
        connectBoth()
        watched.push(msg("approval") { put("approval", approval("g-a1", "g-1")) }) { watched.notified.isNotEmpty() }
        assertEquals("g-a1", watched.notified.single().first.id)
        assertTrue(watched.banners.isEmpty())
        assertTrue(active.hub.pendingApprovals.isEmpty())
    }

    @Test fun anAnswerFromTheWidgetGoesToTheServerTheApprovalWaitsOn() {
        connectBoth()
        watched.push(msg("approval") { put("approval", approval("g-a1", "g-1")) }) { watched.hub.pendingApprovals.isNotEmpty() }
        active.push(msg("approval") { put("approval", approval("z-a1", "z-1")) }) { active.hub.pendingApprovals.isNotEmpty() }
        gym.ok("POST", "/v1/approvals/g-a1")
        z2.ok("POST", "/v1/approvals/z-a1")

        // The widget names no computer: the one whose list has the approval.
        val h = hubFor(null, "g-a1")
        assertTrue(h === watched.hub)
        assertTrue(watched.call { h!!.answerApproval("g-a1", Answer.Allow) })
        assertEquals(1, gym.count("POST", "/v1/approvals/g-a1"))
        assertEquals(0, z2.requests.count { it.bare.startsWith("/v1/approvals/") })

        assertTrue(hubFor(null, "z-a1") === active.hub)
        // One nobody has: the active computer.
        assertTrue(hubFor(null, "nobody's") === active.hub)
    }

    @Test fun aNotificationNamesItsServerAndGoesThereEvenWithTheApprovalGone() {
        connectBoth()
        // The named computer, whatever the lists say; never another in its place.
        assertTrue(hubFor("gym", "z-a1") === watched.hub)
        assertEquals(null, Servers.approvalTarget("forgotten", "x", Servers.LEGACY_ID, mapOf(Servers.LEGACY_ID to emptyList(), "gym" to emptyList())))
    }

    @Test fun theChipCountsTheOtherServersNeedsYouWhileTheyAreFresh() {
        connectBoth()
        watched.push(msg("approval") { put("approval", approval("g-a1", "g-1")) }) { watched.hub.pendingApprovals.isNotEmpty() }
        fun statuses() = listOf(
            ServerStatus(Servers.LEGACY_ID, active.hub.mac.name, active.hub.link.state, Servers.needsYou(active.hub.sessions), active = true),
            ServerStatus("gym", watched.hub.mac.name, watched.hub.link.state, Servers.needsYou(watched.hub.sessions), active = false),
        )
        assertEquals(1, Servers.needsYou(watched.hub.sessions))
        assertEquals(1, Servers.elsewhere(statuses()))
        assertEquals(LinkState.Connected, Servers.worst(statuses().map { it.state }))

        // The watched server goes away for good: its count no longer counts.
        gym.down = true
        gym.closeSocket()
        watched.until("gym offline") { watched.hub.link.state == LinkState.Reconnecting && watched.hub.link.retryAt != null }
        watched.advance(30_100)
        watched.until("gym Offline") { watched.hub.link.state == LinkState.Offline }
        assertEquals(0, Servers.elsewhere(statuses()))
        assertEquals(LinkState.Offline, Servers.worst(statuses().map { it.state }))
        assertEquals(LinkState.Connected, active.hub.link.state)
    }

    @Test fun oneServerGoingAwayLeavesTheOtherLinked() {
        connectBoth()
        z2.down = true
        z2.closeSocket()
        active.until("z2 reconnecting") { active.hub.link.state != LinkState.Connected }
        assertEquals(LinkState.Connected, watched.hub.link.state)
        // Still answering: its socket carries on.
        watched.sync()
        assertEquals(1, gym.opened.get())
    }
}
