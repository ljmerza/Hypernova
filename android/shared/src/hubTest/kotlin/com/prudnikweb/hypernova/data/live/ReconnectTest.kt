package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Problem
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.msg
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reconnect policy (LiveHub's header, data/Retry.kt; docs/PROTOCOL.md: "Clients reconnect once at
 * once, then every 30 s"): a dropped link tries again at once, then every 30 s; a new network, the
 * screen coming on or the app returning try at once; nothing is tried without a network, or off
 * screen without the background service.
 */
@RunWith(RigRunner::class)
class ReconnectTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }

    @After fun tearDown() = rig.close()

    private fun probes() = daemon.count("GET", "/v1/health")

    /** Connected, then the server goes away and the socket drops: the at-once retry fails too. */
    private fun dropAndFailTwice() {
        rig.connect()
        daemon.down = true
        val before = probes()
        daemon.closeSocket()
        // The first try again goes at once; it fails (nothing answers); the next waits.
        rig.until("the at-once retry fails") { probes() > before && rig.hub.link.retryAt != null }
        // Nothing answered the probe: no snapshot was asked for, and the sessions are as they were.
        assertEquals(1, daemon.count("GET", "/v1/snapshot"))
    }

    @Test fun aDroppedSocketIsTriedAgainAtOnceThenAfterThirtySeconds() {
        dropAndFailTwice()
        val link = rig.hub.link
        // Two failures in a row after being linked: still "Reconnecting", the next try in 27-30 s.
        assertEquals(LinkState.Reconnecting, link.state)
        assertEquals(Problem.Unreachable, link.problem)
        val wait = link.retryAt!! - rig.now
        assertTrue("next try in $wait ms", wait in 25_000L..30_000L)
        val tried = probes()

        // Nothing before the wait is over...
        rig.advance(26_000)
        Thread.sleep(150)
        rig.pump()
        assertEquals(tried, probes())
        // ...then the next try.
        rig.advance(4_100)
        rig.until("the 30 s try") { probes() > tried }
    }

    @Test fun afterThreeFailuresInARowTheLinkSaysOfflineAndKeepsTryingEveryThirtySeconds() {
        dropAndFailTwice()
        var tried = probes()
        rig.advance(30_100)
        rig.until("the third try fails") { probes() > tried && rig.hub.link.state == LinkState.Offline && rig.hub.link.retryAt != null }
        tried = probes()
        rig.advance(30_100)
        rig.until("the fourth try") { probes() > tried && rig.hub.link.retryAt != null }
        assertEquals(LinkState.Offline, rig.hub.link.state)
    }

    @Test fun theServerBackReconnectsAtTheNextTryWithAFreshSnapshot() {
        dropAndFailTwice()
        val snaps = daemon.count("GET", "/v1/snapshot")
        daemon.down = false
        rig.advance(30_100)
        rig.until("linked again") { rig.hub.link.state == LinkState.Connected }
        assertEquals(snaps + 1, daemon.count("GET", "/v1/snapshot"))
        assertEquals(2, daemon.opened.get())
        assertNull(rig.hub.link.problem)
    }

    @Test fun theRetryHasAnAlarmTooWhichTriesWhenThePhoneSleptThroughTheDelay() {
        dropAndFailTwice()
        val alarm = rig.retryAlarm()
        assertNotNull("an alarm for the next try", alarm)
        val tried = probes()
        // The coroutine's delay doesn't count while the phone sleeps; the alarm does.
        rig.fireRetryAlarm()
        rig.until("the alarm's try") { probes() > tried }
    }

    @Test fun theScreenComingOnTriesAtOnceOnlyThirtySecondsAfterTheLastTry() {
        dropAndFailTwice()
        val tried = probes()
        rig.screenOn()
        rig.pump()
        Thread.sleep(150)
        rig.pump()
        assertEquals("too soon after the last try", tried, probes())
        // The phone slept: the wall clock moved on, the coroutine's delay did not.
        rig.slept(31_000)
        rig.screenOn()
        rig.until("the screen-on try") { probes() > tried }
    }

    @Test fun aNewNetworkTriesAtOnce() {
        dropAndFailTwice()
        val tried = probes()
        rig.networkChanged()
        rig.until("the network-change try") { probes() > tried }
    }

    @Test fun withoutANetworkNothingIsTriedUntilOneComes() {
        rig.connect()
        daemon.down = true
        rig.online(false)
        daemon.closeSocket()
        rig.until("offline") { rig.hub.link.state == LinkState.Offline && rig.hub.link.problem == Problem.NoNetwork }
        val tried = probes()
        assertNull(rig.hub.link.retryAt)
        rig.advance(120_000)
        assertEquals(tried, probes())

        // The network comes back (and the server with it).
        daemon.down = false
        rig.online(true)
        rig.networkChanged()
        rig.until("linked again") { rig.hub.link.state == LinkState.Connected }
    }

    @Test fun offScreenWithoutTheBackgroundServiceTheNextTryWaitsForTheApp() {
        rig.connect()
        rig.visible = false
        rig.heldOpen = false
        daemon.down = true
        daemon.closeSocket()
        rig.until("offline") { rig.hub.link.state == LinkState.Offline }
        assertNull(rig.hub.link.retryAt)
        val tried = probes()
        rig.advance(120_000)
        assertEquals(tried, probes())

        daemon.down = false
        rig.visible = true
        rig.hub.foreground(true)
        rig.until("linked again") { rig.hub.link.state == LinkState.Connected }
    }

    @Test fun retryNowTriesAtOnce() {
        dropAndFailTwice()
        val tried = probes()
        rig.hub.retry()
        rig.until("Retry now") { probes() > tried }
    }

    @Test fun aServerThatClosesTheSocketIsReconnectedAtOnce() {
        rig.connect()
        daemon.closeSocket()
        rig.until("the second socket") { daemon.opened.get() == 2 && rig.hub.link.state == LinkState.Connected }
    }

    @Test fun aSilentSocketIsDeclaredDeadAfterSeventySeconds() {
        rig.connect()
        // Pings keep it alive...
        rig.advance(40_000)
        daemon.push(msg("ping"))
        rig.until("the pong") { daemon.received.isNotEmpty() }
        rig.advance(40_000)
        assertEquals(LinkState.Connected, rig.hub.link.state)
        assertEquals(1, daemon.opened.get())
        // ...silence for over 70 s doesn't.
        rig.advance(40_000)
        rig.until("a new socket") { daemon.opened.get() == 2 }
    }

    @Test fun backOnScreenASocketSilentForThirtySecondsIsReplacedAtOnce() {
        rig.connect()
        rig.advance(20_000)
        rig.hub.foreground(true)
        rig.pump()
        assertEquals("heard from 20 s ago: kept", 1, daemon.opened.get())
        rig.advance(11_000)
        rig.hub.foreground(true)
        rig.until("a new socket") { daemon.opened.get() == 2 && rig.hub.link.state == LinkState.Connected }
    }

    @Test fun pauseClosesTheSocketUntilTheAppOpens() {
        rig.connect()
        rig.hub.pause()
        assertEquals(LinkState.Offline, rig.hub.link.state)
        rig.advance(120_000)
        assertEquals(1, daemon.opened.get())
        rig.hub.foreground(true)
        rig.until("linked again") { rig.hub.link.state == LinkState.Connected && daemon.opened.get() == 2 }
    }
}
