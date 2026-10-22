package com.prudnikweb.hypernova.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bridge's connections (BridgeConnections.kt): slow senders can't crowd Lens out, a revoke ends
 * everything a grant had going, and the settings go to the feeds from one thread.
 */
class BridgeConnectionsTest {
    /**
     * The finding: sixteen connections that never finish their head (or trickle it) took every slot,
     * and Lens's own were refused. Now the oldest one still reading goes for the newcomer, and
     * Lens's calls and feeds, once signed, are counted in pools of their own.
     */
    @Test fun aFullOpenPoolClosesItsOldestStillReading() {
        val slots = ConnectionSlots(maxOpen = 3, maxCalls = 2, maxFeeds = 1)
        val closed = ArrayList<String>()
        val a = slots.admit { closed += "a" }!!
        val b = slots.admit { closed += "b" }!!
        val c = slots.admit { closed += "c" }!!
        // a has its request in (a ping answering): not one to close.
        slots.read(a)
        val d = slots.admit { closed += "d" }
        assertNotNull(d)
        assertEquals(listOf("b"), closed)
        assertEquals(3, slots.counts()[ConnectionSlots.Pool.Open])
        // b's thread ends later: releasing it again changes nothing.
        slots.release(b)
        assertEquals(3, slots.counts()[ConnectionSlots.Pool.Open])
        // Every one there has its request in: a newcomer is refused rather than one cut off mid-answer.
        slots.read(c)
        slots.read(d!!)
        assertNull(slots.admit { closed += "e" })
        assertEquals(listOf("b"), closed)
        slots.release(a)
        assertNotNull(slots.admit { })
    }

    @Test fun signedCallsAndFeedsHavePoolsOfTheirOwn() {
        val slots = ConnectionSlots(maxOpen = 2, maxCalls = 2, maxFeeds = 1)
        val call1 = slots.admit { }!!
        val call2 = slots.admit { }!!
        assertTrue(slots.grant(call1, feed = false))
        assertTrue(slots.grant(call2, feed = false))
        assertEquals(ConnectionSlots.Pool.Call, call1.pool)
        // Signed calls left the open pool: five knocking now close only each other.
        val closed = AtomicInteger()
        repeat(5) { slots.admit { closed.incrementAndGet() } }
        assertEquals(3, closed.get())
        assertEquals(2, slots.counts()[ConnectionSlots.Pool.Call])
        // A third call waits for room among the calls (503), not among the open ones.
        val call3 = slots.admit { }!!
        assertFalse(slots.grant(call3, feed = false))
        val feed = slots.admit { }!!
        assertTrue(slots.grant(feed, feed = true))
        assertEquals(1, slots.counts()[ConnectionSlots.Pool.Feed])
        val feed2 = slots.admit { }!!
        assertFalse(slots.grant(feed2, feed = true))
        // Granting twice is still one.
        assertTrue(slots.grant(call1, feed = false))
        assertEquals(2, slots.counts()[ConnectionSlots.Pool.Call])
        slots.release(call1)
        slots.release(feed)
        assertEquals(1, slots.counts()[ConnectionSlots.Pool.Call])
        assertEquals(0, slots.counts()[ConnectionSlots.Pool.Feed])
        assertTrue(slots.grant(feed2, feed = true))
    }

    /**
     * The second review: pictures and preview files fetched without a signature held their open
     * slots while a slow Mac answered, and could fill them all. They're counted apart, and few.
     */
    @Test fun unsignedFetchesHaveAFewSlotsOfTheirOwn() {
        val slots = ConnectionSlots(maxOpen = 2, maxCalls = 2, maxFeeds = 1, maxCarried = 2)
        val p1 = slots.admit { }!!
        val p2 = slots.admit { }!!
        assertTrue(slots.carry(p1))
        assertTrue(slots.carry(p2))
        assertTrue("carrying twice is still one", slots.carry(p2))
        assertEquals(2, slots.counts()[ConnectionSlots.Pool.Carried])
        // The open pool is free again for Lens's calls, and a third picture waits (503).
        val call = slots.admit { }!!
        val p3 = slots.admit { }!!
        assertFalse(slots.carry(p3))
        assertTrue(slots.grant(call, feed = false))
        slots.release(p1)
        assertEquals(1, slots.counts()[ConnectionSlots.Pool.Carried])
        assertTrue(slots.carry(p3))
        // A signed call isn't carried unsigned.
        assertFalse(slots.carry(call))
    }

    /** The finding: Disconnect left calls and preview streams under way running to their end. */
    @Test fun aRevokeEndsWhatItsGrantHadUnderWay() {
        val f = InFlight()
        val ended = ArrayList<String>()
        val one = f.add("g1") { ended += "call 1" }
        f.add("g1") { ended += "preview 1" }
        val other = f.add("g2") { ended += "call 2" }
        assertEquals(2, f.count("g1"))
        // A call that finished by itself is no longer there to end.
        f.remove(one)
        f.cancel("g1")
        assertEquals(listOf("preview 1"), ended)
        assertEquals(0, f.count("g1"))
        assertEquals(1, f.count("g2"))
        // Every grant at once (the phone paired again, the Mac forgot the glasses).
        f.add("g3") { error("a cancel that fails doesn't stop the others") }
        f.add("g3") { ended += "call 3" }
        f.cancelAll()
        assertEquals(listOf("preview 1", "call 2", "call 3"), ended)
        f.remove(other)
        assertEquals(0, f.count("g2"))
    }

    /** The finding: each settings change started a thread per feed, and one stuck feed kept them all waiting. */
    @Test fun settingsGoFromOneThreadWithOnlyTheLatest() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
        val threads = java.util.Collections.synchronizedSet(HashSet<String>())
        val latest = Latest<Int>("test-push") { v ->
            threads += Thread.currentThread().name
            seen += v
            if (v == 1) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
        }
        latest.offer(1)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // While the first is stuck, a burst of changes waits as one: the last.
        for (v in 2..50) latest.offer(v)
        release.countDown()
        val done = CountDownLatch(1)
        latest.offer(51)
        Thread { while (seen.lastOrNull() != 51) Thread.sleep(5); done.countDown() }.start()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("test-push"), threads.toList())
        assertTrue("$seen", seen.size <= 3)
        assertEquals(1, seen.first())
    }

    /**
     * The finding: a failed bind was never tried again, so an app that took the port first (after a
     * reboot, say) kept it, and the glasses with it, until the app restarted. Now the port is tried
     * again every few seconds, the owner is told once while another app holds it and again when
     * it's back, and each opening has a listener id of its own.
     */
    @Test fun thePortIsTriedAgainUntilItsOurs() {
        var busyFor = 3
        val events = ArrayList<String>()
        val sleeps = ArrayList<Long>()
        val ids = ArrayList<String>()
        var served = 0
        var n = 0
        PortKeeper(
            open = {
                if (busyFor-- > 0) throw java.net.BindException("Address already in use")
                java.net.ServerSocket()
            },
            serve = { server, id ->
                ids += id
                served++
                events += "serving $id"
                // The first opening fails after a while (its socket closed); the port is held again,
                // by another app for a moment.
                if (served == 1) busyFor = 1
                assertFalse(server.isClosed)
            },
            onBusy = { events += "busy: ${it.message}" },
            onFree = { events += "free" },
            newId = { "listener-${++n}" },
            sleep = { sleeps += it },
        ).run(stop = { served >= 2 })
        assertEquals(
            listOf("busy: Address already in use", "free", "serving listener-1", "busy: Address already in use", "free", "serving listener-2"),
            events,
        )
        assertEquals(listOf(PortKeeper.RETRY_MS, PortKeeper.RETRY_MS, PortKeeper.RETRY_MS, PortKeeper.RETRY_MS), sleeps)
        assertEquals(2, ids.toSet().size)
        assertEquals(PortKeeper.RETRY_LATER_MS, PortKeeper.delayMs(PortKeeper.RETRIES_SOON))
    }

    /** Free at once: nothing to tell the owner. */
    @Test fun aPortFreeAtOnceSaysNothing() {
        val events = ArrayList<String>()
        var served = 0
        PortKeeper(
            open = { java.net.ServerSocket() },
            serve = { _, id -> served++; events += id },
            onBusy = { events += "busy" },
            onFree = { events += "free" },
            newId = { "id-$served" },
            sleep = { error("no waiting") },
        ).run(stop = { served >= 1 })
        assertEquals(listOf("id-0"), events)
    }
}
