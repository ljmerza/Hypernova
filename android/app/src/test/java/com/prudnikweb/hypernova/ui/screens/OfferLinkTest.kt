package com.prudnikweb.hypernova.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.HubViewModel
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.PairFlow
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.Route
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.storage
import com.prudnikweb.hypernova.ui.App
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.MotionPrefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A pairing link from outside the app (MainActivity's intent; HubViewModel.offerLink), the hostile
 * one above all: it does nothing until the tap, Cancel leaves nothing, and even after the tap it
 * neither replaces nor disconnects a server paired already. Through the real [Core], hubs and scripted
 * daemons; the screen itself: PairingScreensTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+h900dp")
class OfferLinkTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val daemons = mutableListOf<FakeDaemon>()

    @Before fun setUp() {
        resetPrefs()
        AndroidCore.init(RuntimeEnvironment.getApplication())
        ComputerWords.clear(AndroidCore.app.storage)
    }

    @After fun tearDown() {
        for (s in Core.servers.toList()) Core.forget(s.id)
        shadowOf(Looper.getMainLooper()).idle()
        daemons.forEach { it.close() }
    }

    private fun until(what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (${Core.statuses})")
            Thread.sleep(3)
        }
    }

    private fun z2(): FakeDaemon {
        val d = FakeDaemon("z2").also(daemons::add)
        d.snapshot = snapshot("z2")
        Core.paired(Pairing(d.base, "tok-z2", "z2", "m-z2", "dev-z2", fingerprint = "a".repeat(64)))
        linked("z2")
        return d
    }

    private fun linked(name: String) = until("$name linked") { Core.allHubs.isNotEmpty() && Core.everyHub.first { it.first.name == name }.second.link.state == LinkState.Connected }

    /** A listener of its own, its own certificate, and an answer that says it is z2, its id included. */
    private fun impostor(): FakeDaemon {
        val d = FakeDaemon("z2", lan = true).also(daemons::add)
        d.snapshot = snapshot("z2")
        d.ok("POST", "/v1/pair", JSONObject().put("token", "tok-evil").put("deviceId", "dev-evil").put("mac", JSONObject().put("id", "m-z2").put("name", "z2")))
        return d
    }

    private fun hostileLink(d: FakeDaemon) =
        "hypernova://pair?v=1&u=https%3A%2F%2F127.0.0.1%3A${d.lanPort}&c=stolen-code&n=z2&fp=${d.fingerprint}&lan=127.0.0.1&lp=${d.lanPort}"

    private fun FakeDaemon.pairCalls() = calls("POST", "/v1/pair").size

    @Test fun aHostileLinkDoesNothingUntilTheTapAndCancelLeavesNothing() {
        z2()
        val evil = impostor()
        val vm = HubViewModel(AndroidCore.app)
        val id = Core.activeId
        val before = Core.servers.toList()
        vm.nav.push(Route.Providers)
        vm.addServer(true)

        vm.offerLink(hostileLink(evil))
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals("the screen", "z2", vm.offeredLink!!.macName)
        assertEquals("nothing sent", 0, evil.pairCalls())
        assertNull(vm.pairFlow)
        assertEquals(before, Core.servers)
        assertEquals(id, Core.activeId)
        assertEquals(Route.Providers, vm.nav.top)
        assertTrue(vm.addingServer)

        vm.declineOffer()
        assertNull(vm.offeredLink)
        assertEquals(0, evil.pairCalls())
        assertEquals(before, Core.servers)
        assertEquals(Route.Providers, vm.nav.top)
        assertTrue("where it was", vm.addingServer)
        assertEquals(LinkState.Connected, Core.live!!.link.state)
    }

    /** Cancel spent nothing: the very same code still pairs when the owner means it. */
    @Test fun aCodeCancelledIsntSpent() {
        val d = impostor()
        val vm = HubViewModel(AndroidCore.app)
        vm.offerLink(hostileLink(d))
        vm.declineOffer()
        assertEquals(0, d.pairCalls())
        vm.offerLink(hostileLink(d))
        vm.acceptOffer()
        until("paired") { vm.pairFlow is PairFlow.Done }
        assertEquals(1, d.pairCalls())
        assertEquals("stolen-code", d.calls("POST", "/v1/pair").single().json.getString("code"))
    }

    @Test fun afterTheTapItNeitherReplacesNorDisconnectsAServerPaired() {
        val real = z2()
        val evil = impostor()
        val vm = HubViewModel(AndroidCore.app)
        val realId = Core.activeId!!
        val realHub = Core.live!!

        vm.offerLink(hostileLink(evil))
        vm.acceptOffer()
        until("paired") { vm.pairFlow is PairFlow.Done }

        // Beside it, under an id of its own, whatever its answer claimed.
        assertEquals(2, Core.servers.size)
        val kept = Core.servers.first { it.id == realId }.pairing
        assertEquals("tok-z2", kept.token)
        assertEquals(real.base, kept.baseUrl)
        assertEquals("a".repeat(64), kept.fingerprint)
        val added = Core.servers.first { it.id != realId }.pairing
        assertEquals("tok-evil", added.token)
        assertTrue(added.baseUrl != kept.baseUrl)
        // The result names what was added, and the real one is still linked, on the same hub.
        assertEquals("z2", (vm.pairFlow as PairFlow.Done).macName)
        assertEquals(LinkState.Connected, realHub.link.state)
        assertTrue(realHub === Core.everyHub.first { it.first.id == realId }.second)
        assertEquals(0, real.calls("POST", "/v1/pair").size)
    }

    @Test fun anIntentAsksFirstAndItsExtrasChooseNothing() {
        val a = z2()
        val aId = Core.activeId!!
        val b = FakeDaemon("mini").also(daemons::add)
        b.snapshot = snapshot("mini")
        Core.paired(Pairing(b.base, "tok-mini", "mini", "m-mini", "dev-mini"))
        val bId = Core.activeId!!
        assertTrue(aId != bId)
        val evil = impostor()
        // As a web page can word it: intent://pair?…#Intent;scheme=hypernova;S.server=<id>;end
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(hostileLink(evil)), AndroidCore.app, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SERVER, aId)
        val made = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        try {
            val vm = ViewModelProvider(made.get())[HubViewModel::class.java]
            assertEquals("z2", vm.offeredLink!!.macName)
            assertEquals("the server on screen stays", bId, Core.activeId)
            assertEquals(0, evil.pairCalls())
            assertEquals(LinkState.Connected, Core.everyHub.first { it.first.id == aId }.second.link.state)
            assertNull("handled once", made.get().intent.data)
            assertEquals("z2", a.name)
        } finally {
            made.destroy()
        }
        // A notification's own tap still chooses its server.
        val note = MainActivity.command(AndroidCore.app).setAction(MainActivity.ACTION_OPEN_SESSION)
            .putExtra(MainActivity.EXTRA_SERVER, aId).putExtra(MainActivity.EXTRA_SESSION, "s")
        Robolectric.buildActivity(MainActivity::class.java, note).create().destroy()
        assertEquals(aId, Core.activeId)
    }

    @Test fun aLinkOfOursThatCantBeReadSaysSoButNeverOverAPairingUnderWay() {
        val vm = HubViewModel(AndroidCore.app)
        vm.offerLink("hypernova://pair?v=1&c=no-address")
        assertTrue(vm.pairFlow is PairFlow.Failed)
        vm.closePairFlow()

        val slow = FakeDaemon("box").also(daemons::add)
        val gate = java.util.concurrent.CountDownLatch(1)
        slow.on("POST", "/v1/pair") {
            gate.await()
            FakeDaemon.json(200, JSONObject().put("token", "t").put("deviceId", "d").put("mac", JSONObject().put("id", "m-box").put("name", "box")))
        }
        slow.snapshot = snapshot("box")
        vm.handleLink("hypernova://pair?v=1&u=${slow.base}&c=c&n=box")
        val working = vm.pairFlow
        assertTrue(working is PairFlow.Working)
        vm.offerLink("hypernova://pair?v=1&c=no-address")
        assertEquals(working, vm.pairFlow)
        // A readable one asks, and Cancel leaves the pairing to finish.
        vm.offerLink("hypernova://pair?v=1&u=https%3A%2F%2F192.0.2.1%3A47323&c=other&n=evil")
        assertEquals(working, vm.pairFlow)
        vm.declineOffer()
        gate.countDown()
        until("paired") { vm.pairFlow is PairFlow.Done }
        assertEquals("box", (vm.pairFlow as PairFlow.Done).macName)
    }

    @Test fun inTheAppItCoversTheScreenAndCancelLeavesItAsItWas() {
        z2()
        val vm = HubViewModel(AndroidCore.app)
        rule.setContent { CompositionLocalProvider(LocalMotion provides MotionPrefs(reduced = true)) { App(vm) {} } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.pair_offer_title)).assertDoesNotExist()
        val evil = impostor()
        rule.runOnIdle { vm.offerLink(hostileLink(evil)) }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.pair_offer_title)).assertExists()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        assertNull("Back is Cancel", vm.offeredLink)
        assertEquals(Route.Home, vm.nav.top)
        assertEquals(0, evil.pairCalls())

        rule.runOnIdle { vm.offerLink(hostileLink(evil)) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText(str(R.string.pair_offer_add)).performClick()
        rule.waitForIdle()
        until("paired") { vm.pairFlow is PairFlow.Done }
        assertEquals(1, evil.pairCalls())
        assertNull(vm.offeredLink)
    }

    /** With no server yet the steps aren't behind the question, so the camera can't be running under it. */
    @Test fun nothingPairedTheStepsGiveWayToTheQuestion() {
        val vm = HubViewModel(AndroidCore.app)
        rule.setContent { CompositionLocalProvider(LocalMotion provides MotionPrefs(reduced = true)) { App(vm) {} } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.onb_title)).assertExists()
        rule.runOnIdle { vm.offerLink("hypernova://pair?v=1&u=https%3A%2F%2F192.0.2.1%3A47323&c=c&n=box") }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.onb_title)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.pair_offer_title)).assertExists()
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.onb_title)).assertExists()
    }
}
