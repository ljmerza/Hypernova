package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "All servers" on Home (data/Servers.kt): the choice kept on the phone, when it shows, and the "All" chip's link. */
class AllServersTest {
    private class Mem : KeyValue {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    private class Old(var p: Pairing?) : LegacyPairing {
        override fun load() = p
        override fun save(p: Pairing) { this.p = p }
        override fun clear() { p = null }
    }

    private val z2 = Pairing(baseUrl = "http://10.0.2.2:47321", token = "tok-z2", macName = "z2", macId = "m-z2", deviceId = "d1")
    private val gym = Pairing(baseUrl = "http://10.0.2.3:47321", token = "tok-gym", macName = "gym", macId = "m-gym", deviceId = "d2")

    @Test fun offUntilChosen() {
        assertFalse(ServerBook(Mem(), Old(null)).all)
    }

    @Test fun theChoiceSurvivesARestart() {
        val kv = Mem()
        ServerBook(kv, Old(null)).all = true
        // A new process reads the same preferences.
        assertTrue(ServerBook(kv, Old(null)).all)
        ServerBook(kv, Old(null)).all = false
        assertFalse(ServerBook(kv, Old(null)).all)
        // Off leaves nothing behind.
        assertFalse(kv.map.containsKey(ServerBook.ALL))
    }

    @Test fun theChoiceLeavesTheListAndThePickAlone() {
        val kv = Mem()
        val book = ServerBook(kv, Old(null))
        book.save(listOf(Server("0", z2), Server("b", gym)), "b")
        book.all = true
        val (list, active) = ServerBook(kv, Old(null)).load()
        assertEquals(listOf("0", "b"), list.map { it.id })
        // The one picked last is what the other tabs show in All mode.
        assertEquals("b", active)
        assertTrue(ServerBook(kv, Old(null)).all)
    }

    @Test fun showsOnlyWithTwoOrMorePaired() {
        assertFalse(Servers.allShown(chosen = true, paired = 0))
        assertFalse(Servers.allShown(chosen = true, paired = 1))
        assertTrue(Servers.allShown(chosen = true, paired = 2))
        assertFalse(Servers.allShown(chosen = false, paired = 3))
    }

    @Test fun theChipIsTheWorstLink() {
        assertEquals(LinkState.Connected, Servers.worst(listOf(LinkState.Connected, LinkState.Connected)))
        assertEquals(LinkState.Reconnecting, Servers.worst(listOf(LinkState.Connected, LinkState.Reconnecting)))
        assertEquals(LinkState.Offline, Servers.worst(listOf(LinkState.Reconnecting, LinkState.Offline, LinkState.Connected)))
        assertEquals(LinkState.Unpaired, Servers.worst(listOf(LinkState.Offline, LinkState.Unpaired)))
        assertEquals(LinkState.Connecting, Servers.worst(listOf(LinkState.Connected, LinkState.Connecting)))
        assertEquals(LinkState.Connecting, Servers.worst(emptyList()))
    }

    @Test fun countsTheOnesNotLinked() {
        assertEquals(0, Servers.notLinked(listOf(LinkState.Connected, LinkState.Connected)))
        assertEquals(2, Servers.notLinked(listOf(LinkState.Connected, LinkState.Offline, LinkState.Connecting)))
    }

    @Test fun aListIsCurrentWhileLinkedOrReconnecting() {
        assertTrue(Servers.fresh(LinkState.Connected))
        assertTrue(Servers.fresh(LinkState.Reconnecting))
        assertFalse(Servers.fresh(LinkState.Connecting))
        assertFalse(Servers.fresh(LinkState.Offline))
        assertFalse(Servers.fresh(LinkState.Unpaired))
    }
}
