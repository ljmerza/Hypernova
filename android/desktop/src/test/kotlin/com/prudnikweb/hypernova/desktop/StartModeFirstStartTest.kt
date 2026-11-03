package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.PairingStore
import com.prudnikweb.hypernova.data.Server
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * The old phone-wide "New sessions start in", through the real [Core.init], which only this test's own JVM
 * runs first (the desktop's tests fork per class): docs/DECISIONS.md, "New sessions start in, per server and
 * agent", 6. A server paired at the first start gets it; one paired after that gets nothing, and stays on Ask.
 */
class StartModeFirstStartTest {
    private fun FakeDaemon.keeps(mode: String?) {
        snapshot = snapshot(name) { put("claude", JSONObject().put("available", true).apply { if (mode != null) put("startMode", mode) }) }
    }

    private fun server(name: String, mode: String?) = FakeDaemon(name).apply {
        keeps(mode)
        ok("POST", "/v1/claude", JSONObject().put("claude", JSONObject().put("available", true).put("startMode", "auto")))
    }

    private fun until(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!cond()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (${Core.statuses})")
            Thread.sleep(5)
        }
    }

    @Test fun aServerPairedAfterTheFirstStartGetsNoMove() {
        val current = server("z2", "default")
        val older = server("gym", null)
        val later = server("shed", "default")
        val storage = DesktopRig.platform.storage
        val prefs = storage.prefs("hypernova")
        try {
            // As the phone left things: two servers paired, one of them older, the old setting on Auto.
            PairingStore(storage).servers.save(
                listOf(
                    Server("z2id", Pairing(current.base, "tok-z2", "z2", "m-z2", "dev-z2")),
                    Server("gymid", Pairing(older.base, "tok-gym", "gym", "m-gym", "dev-gym")),
                ),
                "z2id",
            )
            prefs.edit().putString("newSession.startMode", "auto").apply()

            DesktopRig.main { Core.init(DesktopRig.platform) }

            until("the current server took it") { current.count("POST", "/v1/claude") == 1 }
            assertEquals("auto", current.calls("POST", "/v1/claude").single().json.getString("moveStartMode"))
            until("the older server linked") { Core.hub("gymid")?.claude != null }
            assertEquals("older: nothing yet", 0, older.count("POST", "/v1/claude"))
            assertEquals("kept while the older one's owed it", "auto", prefs.getString("newSession.startMode", null))

            // Paired now, with the old value still here: it's not for this one.
            DesktopRig.main { Core.paired(Pairing(later.base, "tok-shed", "shed", "m-shed", "dev-shed")) }
            until("the later server linked") { Core.live?.pairing?.macName == "shed" && Core.live?.link?.state == LinkState.Connected && Core.live?.claude != null }
            Thread.sleep(300)
            assertEquals("paired after the first start: no move", 0, later.count("POST", "/v1/claude"))
            assertEquals("default", Core.live?.claude?.startMode)
            assertNull(Core.live?.startModeOwed)

            // The older one updates: it takes it, and with nobody left the old value and the list go.
            older.keeps("default")
            older.closeSocket()
            until("the older server took it after its update") { older.count("POST", "/v1/claude") == 1 }
            assertEquals("auto", older.calls("POST", "/v1/claude").single().json.getString("moveStartMode"))
            until("nothing left to move") { !prefs.contains("newSession.startMode") }
            assertFalse(prefs.contains("newSession.startModeOwed"))
            assertEquals(0, later.count("POST", "/v1/claude"))
        } finally {
            DesktopRig.main { for (s in Core.servers.map { it.id }) Core.forget(s) }
            current.close()
            older.close()
            later.close()
        }
    }
}
