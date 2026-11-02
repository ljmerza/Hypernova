package com.prudnikweb.hypernova.data.live

import android.os.Looper
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.UsageRefresh
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.refusal
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.storage
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Refresh usage now (data/UsageRefresh.kt, `POST /v1/usage/refresh`): opening the usage asks the
 * computer on screen for a fresh look, at most once in five minutes from this phone, and a computer
 * without the route (404) changes nothing. Through [Core], as the screens call it.
 */
@RunWith(RobolectricTestRunner::class)
class UsageRefreshCallTest {
    private val z2 = FakeDaemon("z2")

    private fun until(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (${Core.statuses})")
            Thread.sleep(3)
        }
    }

    /** Lets whatever the app's scope has under way settle (a request that should not go, say). */
    private fun settle(ms: Long = 300) {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    @Before fun setUp() {
        Core.init(RuntimeEnvironment.getApplication())
        ComputerWords.clear(Core.app.storage)
        UsageRefresh.reset()
    }

    @After fun tearDown() {
        for (s in Core.servers.toList()) Core.forget(s.id)
        shadowOf(Looper.getMainLooper()).idle()
        z2.close()
        UsageRefresh.reset()
    }

    @Test fun nothingIsAskedWithNoComputerPaired() {
        UsageRefresh.ask()
        settle()
        // Nothing paired is no look used up either: the first computer's ask goes.
        z2.snapshot = snapshot("z2")
        z2.on("POST", "/v1/usage/refresh") { json(200, JSONObject().put("usage", JSONObject())) }
        Core.paired(Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-1"))
        until("linked") { Core.live?.link?.state == LinkState.Connected }
        UsageRefresh.ask()
        until("the look") { z2.count("POST", "/v1/usage/refresh") == 1 }
    }

    @Test fun oneLookThenNoneForFiveMinutesAndA404ChangesNothing() {
        z2.snapshot = snapshot("z2")
        z2.on("POST", "/v1/usage/refresh") { refusal(404, "not_found", "Unknown endpoint.") }
        Core.paired(Pairing(z2.base, "tok-z2", "z2", "m-z2", "dev-1"))
        until("linked") { Core.live?.link?.state == LinkState.Connected }
        UsageRefresh.ask()
        until("the look") { z2.count("POST", "/v1/usage/refresh") == 1 }
        assertEquals("Bearer tok-z2", z2.calls("POST", "/v1/usage/refresh").single().auth)
        // An older server's 404: the hub carries on linked, nothing said.
        settle()
        assertEquals(LinkState.Connected, Core.live?.link?.state)
        // Asked again at once (the sheet opened twice): no second look.
        UsageRefresh.ask()
        UsageRefresh.ask()
        settle()
        assertEquals(1, z2.count("POST", "/v1/usage/refresh"))
    }
}
