package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several paired computers (data/Servers.kt): the list kept, the old pairing carried over, which one gets what. */
class ServersTest {
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

    private val z2 = Pairing(
        baseUrl = "https://z2.tail.ts.net:47321", token = "tok-z2", macName = "z2", macId = "m-z2", deviceId = "d1",
        fingerprint = "a".repeat(64), lanAddresses = listOf("192.168.1.20", "10.0.0.5"), lanPort = 47323,
        tailnetName = "z2.tail.ts.net", tailnetIp = "100.64.0.2", tailnetUrl = null, tailnetAccount = "me@x", controlUrl = "https://hs.example",
        controlConfirmed = true,
    )
    private val mini = Pairing(baseUrl = "https://mini.tail.ts.net:47321", token = "tok-mini", macName = "mini", macId = "m-mini", deviceId = "d2")

    private fun session(id: String, state: SessionState, phase: Phase = Phase.of(state)) =
        Session(hex = id.take(2), id = id, name = id, project = "p", state = state, model = "opus", mode = "default", activity = "", updatedAt = 0L, phase = phase)

    // ─── Migration and persistence ─────────────────────────────────────────

    @Test fun theOnePairingBecomesTheFirstServerWithItsOldKeys() {
        val kv = Mem()
        val old = Old(z2)
        val (list, active) = ServerBook(kv, old).load()
        assertEquals(listOf(Server(Servers.LEGACY_ID, z2)), list)
        assertEquals(Servers.LEGACY_ID, active)
        // Its queue and certificate are where they always were: nothing to pair again.
        assertEquals("offlineQueue", Servers.scoped("offlineQueue", Servers.LEGACY_ID))
        assertEquals("hypernova.cert", Servers.scoped("hypernova.cert", Servers.LEGACY_ID))
        // Written once: a second load reads the list, the old keys still there for an older app.
        assertEquals(z2, old.p)
        assertEquals(list to active, ServerBook(kv, Old(null)).load())
    }

    @Test fun aFreshInstallHasNothingPaired() {
        val (list, active) = ServerBook(Mem(), Old(null)).load()
        assertTrue(list.isEmpty())
        assertNull(active)
    }

    @Test fun theListAndTheActiveOneComeBackAsKept() {
        val kv = Mem()
        val old = Old(z2)
        val book = ServerBook(kv, old)
        book.load()
        val list = listOf(Server(Servers.LEGACY_ID, z2), Server("abc123", mini))
        book.save(list, "abc123")
        assertEquals(list to "abc123", ServerBook(kv, old).load())
        // Every field of a pairing survives the trip.
        assertEquals(list, Servers.decode(Servers.encode(list)))
    }

    @Test fun anActiveIdNoLongerInTheListFallsBackToTheFirst() {
        val kv = Mem()
        val book = ServerBook(kv, Old(null))
        book.save(listOf(Server("a", mini)), "a")
        kv.put(ServerBook.ACTIVE, "gone")
        assertEquals("a", book.load().second)
    }

    @Test fun forgettingTheFirstServerClearsTheOldKeysToo() {
        val old = Old(z2)
        val book = ServerBook(Mem(), old)
        book.load()
        book.save(listOf(Server("abc", mini)), "abc")
        assertNull(old.p)
    }

    @Test fun aBrokenListIsReadAsEmptyAndBadEntriesAreDropped() {
        assertTrue(Servers.decode("not json").isEmpty())
        val raw = """[{"id":"a","baseUrl":"https://a","token":"t"},{"id":"","baseUrl":"https://b","token":"t"},{"id":"c","baseUrl":"https://c"},{"id":"a","baseUrl":"https://dup","token":"t"}]"""
        val list = Servers.decode(raw)
        assertEquals(listOf("a"), list.map { it.id })
        assertEquals("Mac", list[0].pairing.macName)
    }

    @Test fun anotherServersKeysAreItsOwn() {
        assertEquals("offlineQueue.abc", Servers.scoped("offlineQueue", "abc"))
        assertEquals("hypernova.cert.abc", Servers.scoped("hypernova.cert", "abc"))
    }

    // ─── Pairing and switching ─────────────────────────────────────────────

    @Test fun pairingAnotherComputerAddsIt() {
        val start = listOf(Server(Servers.LEGACY_ID, z2))
        val (list, id) = Servers.withPairing(start, mini) { "new1" }
        assertEquals("new1", id)
        assertEquals(listOf(Servers.LEGACY_ID, "new1"), list.map { it.id })
    }

    @Test fun pairingTheSameComputerAgainTakesItsPlace() {
        val start = listOf(Server(Servers.LEGACY_ID, z2), Server("m", mini))
        val again = z2.copy(token = "tok-new", deviceId = "d9")
        val (list, id) = Servers.withPairing(start, again) { error("no new id") }
        assertEquals(Servers.LEGACY_ID, id)
        assertEquals("tok-new", list[0].pairing.token)
        assertEquals(2, list.size)
    }

    @Test fun aNewIdNeverClashes() {
        val start = listOf(Server("x", mini))
        val ids = ArrayDeque(listOf("x", "y"))
        val (list, id) = Servers.withPairing(start, z2) { ids.removeFirst() }
        assertEquals("y", id)
        assertEquals(listOf("x", "y"), list.map { it.id })
    }

    @Test fun theSameComputerByIdOrByName() {
        assertTrue(Servers.sameComputer(z2, z2.copy(macName = "renamed")))
        assertFalse("another Mac id", Servers.sameComputer(z2, z2.copy(macId = "m-other")))
        // Without ids (an older daemon): the name, and the tailnet name when both know one.
        val a = mini.copy(macId = null, tailnetName = "mini")
        assertTrue(Servers.sameComputer(a, a.copy(macName = "MINI")))
        assertFalse(Servers.sameComputer(a, a.copy(tailnetName = "mini-2")))
        assertFalse(Servers.sameComputer(a, a.copy(macName = "studio")))
    }

    @Test fun forgettingTheActiveOneShowsTheNext() {
        val list = listOf(Server("a", z2), Server("b", mini))
        assertEquals("b", Servers.activeAfterRemoval(list, "a", "a"))
        assertEquals("b", Servers.activeAfterRemoval(list, "a", "b"))
        assertNull(Servers.activeAfterRemoval(listOf(Server("a", z2)), "a", "a"))
    }

    @Test fun theActiveOneComesFirst() {
        val list = listOf(Server("a", z2), Server("b", mini), Server("c", mini.copy(macId = "c")))
        assertEquals(listOf("b", "a", "c"), Servers.activeFirst(list, "b").map { it.id })
        assertEquals(listOf("a", "b", "c"), Servers.activeFirst(list, null).map { it.id })
    }

    // ─── Routing notifications and answers ─────────────────────────────────

    @Test fun aNotificationGoesToTheServerItNames() {
        val known = setOf("a", "b")
        assertEquals("b", Servers.target("b", "a", known))
        // One from before there were several: the active one.
        assertEquals("a", Servers.target(null, "a", known))
        // Its server forgotten since: nowhere, never the active one in its place.
        assertNull(Servers.target("gone", "a", known))
    }

    @Test fun anAnswerGoesWhereTheApprovalWaits() {
        val waiting = mapOf("a" to listOf("ap1"), "b" to listOf("ap2", "ap3"))
        // A notification names its server.
        assertEquals("b", Servers.approvalTarget("b", "ap2", "a", waiting))
        assertNull(Servers.approvalTarget("gone", "ap2", "a", waiting))
        // The widget names none: the server holding it, else the active one.
        assertEquals("b", Servers.approvalTarget(null, "ap3", "a", waiting))
        assertEquals("a", Servers.approvalTarget(null, "unknown", "a", waiting))
    }

    // ─── Needs-you counts ─────────────────────────────────────────────────

    @Test fun needsYouCountsLiveSessionsWaitingOnTheOwner() {
        val list = listOf(
            session("s1", SessionState.NeedsYou),
            session("s2", SessionState.NeedsYou),
            session("s3", SessionState.Working),
            session("s4", SessionState.NeedsYou, Phase.Ended),
        )
        assertEquals(2, Servers.needsYou(list))
    }

    @Test fun theChipCountsOnlyTheOtherServersThatAreLinked() {
        val statuses = listOf(
            ServerStatus("a", "z2", LinkState.Connected, needsYou = 3, active = true),
            ServerStatus("b", "mini", LinkState.Connected, needsYou = 1, active = false),
            ServerStatus("c", "studio", LinkState.Reconnecting, needsYou = 2, active = false),
            ServerStatus("d", "old", LinkState.Offline, needsYou = 5, active = false),
        )
        assertEquals(3, Servers.elsewhere(statuses))
    }

    // ─── The built-in node ─────────────────────────────────────────────────

    @Test fun theNodeRunsForTheActiveServersTailnetFirst() {
        val other = mini.copy(tailnetName = "mini", controlUrl = null)
        // Both signed in before: the active one's control server.
        assertEquals(Servers.NodePlan.Start("https://hs.example"), Servers.nodePlan(listOf(z2, other), { true }, null))
        assertEquals(Servers.NodePlan.Start(null), Servers.nodePlan(listOf(other, z2), { true }, null))
        // The active one's node never signed in here: the next one's.
        assertEquals(Servers.NodePlan.Start(null), Servers.nodePlan(listOf(z2, other), { it == null }, null))
    }

    @Test fun aControlServerNotAgreedToIsNeverStarted() {
        val unconfirmed = z2.copy(controlConfirmed = false)
        assertEquals(Servers.NodePlan.Leave, Servers.nodePlan(listOf(unconfirmed), { true }, "https://hs.example"))
        assertEquals(Servers.NodePlan.Stop, Servers.nodePlan(listOf(unconfirmed), { true }, "https://other"))
    }

    @Test fun withoutATailnetANodeForNoneOfThemStops() {
        // mini names no tailnet and is on tailscale.com: a tailscale.com node may be signing in, so it stays.
        assertEquals(Servers.NodePlan.Leave, Servers.nodePlan(listOf(mini), { false }, null))
        assertEquals(Servers.NodePlan.Stop, Servers.nodePlan(listOf(mini), { false }, "https://hs.example"))
        assertEquals(Servers.NodePlan.Stop, Servers.nodePlan(emptyList(), { true }, null))
    }

    @Test fun theNodeTunnelsToEveryServersDestination() {
        assertEquals(listOf("z2:47321", "mini:47321"), NodeDestinations.union(listOf("z2:47321", "mini:47321", "z2:47321")))
    }
}
