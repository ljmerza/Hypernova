package com.prudnikweb.hypernova.desktop

import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.NewSessionMemory
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.PairingStore
import com.prudnikweb.hypernova.data.Server
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * The old phone-wide "New sessions start in", through the real [Core.init], which only this test's own JVM runs first
 * (the desktop's tests fork per class): docs/DECISIONS.md, "One starting mode, the agent's own". It's dropped at the
 * first start, nothing's sent anywhere, and the form opens on what the server says from the agent's own file.
 */
class StartModeFirstStartTest {
    private fun until(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!cond()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (${Core.statuses})")
            Thread.sleep(5)
        }
    }

    @Test fun theOldValueIsDroppedAndTheFormFollowsTheFile() {
        val z2 = FakeDaemon("z2").apply { snapshot = snapshot(name) { put("claude", JSONObject().put("available", true).put("startMode", "plan")) } }
        val storage = DesktopRig.platform.storage
        val prefs = storage.prefs("hypernova")
        try {
            PairingStore(storage).servers.save(listOf(Server("z2id", Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-z2"))), "z2id")
            // As v0.27.0 left things: Auto on this client, and this server still owed it.
            prefs.edit().putString("newSession.startMode", "auto").putString("newSession.startModeOwed", "z2id").apply()

            DesktopRig.main { Core.init(DesktopRig.platform) }

            assertFalse("gone at the start", prefs.contains("newSession.startMode"))
            assertFalse(prefs.contains("newSession.startModeOwed"))
            until("linked") { Core.hub("z2id")?.claude != null }
            Thread.sleep(300)
            assertEquals("nothing's moved or written", 0, z2.count("POST", "/v1/claude"))
            assertEquals("plan", NewSessionMemory.startsIn(AgentKind.CLAUDE, Core.hub("z2id")!!, NewSessionMemory.load(storage)))
        } finally {
            DesktopRig.main { for (s in Core.servers.map { it.id }) Core.forget(s) }
            z2.close()
        }
    }
}
