package com.prudnikweb.hypernova.data.live

import com.prudnikweb.hypernova.data.AwakeWords
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.data.MacAwake
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.strings
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Mac-only features (docs/PROTOCOL.md, "Mac-only features", "Stay awake") as the hub has them:
 * a Mac's battery and Stay awake from their messages, its sleep and Backpack warnings as banners and
 * notifications, the routes that set Stay awake and open a terminal window, and a Linux server's
 * refusals with their sentences. Every message and answer is its contract fixture
 * (protocol/fixtures/), which the daemon's own code made.
 */
@RunWith(RobolectricTestRunner::class)
class MacOnlyTest {
    private val daemon = FakeDaemon()
    private val rig by lazy { HubRig(daemon) }

    @After fun tearDown() = rig.close()

    private fun fx(name: String) = JSONObject(javaClass.classLoader!!.getResource("fixtures/$name.json")!!.readText())

    private fun mac(platform: String = Mac.DARWIN, unavailable: JSONObject? = null) {
        daemon.snapshot = snapshot(platform = platform, sessions = listOf(session("s1"))) {
            if (unavailable != null) getJSONObject("mac").put("unavailable", unavailable)
        }
        rig.connect()
    }

    private val res get() = rig.context.resources

    @Test fun aMacsBatteryAndStayAwakeComeFromTheirMessages() {
        mac()
        assertNull(rig.hub.mac.battery)
        rig.push(fx("ws-battery")) { rig.hub.mac.battery != null }
        val b = rig.hub.mac.battery!!
        assertEquals(84, b.percent)
        assertFalse(b.charging)
        assertFalse(b.onAC)
        assertEquals(312, b.minutesLeft)

        rig.push(fx("ws-awake")) { rig.hub.mac.awake != null }
        val a = rig.hub.mac.awake!!
        assertEquals(MacAwake.BACKPACK, a.mode)
        assertTrue(a.holding)
        assertTrue(a.lidClosedOk)
        assertTrue(a.backpackReady)
        assertEquals(1_800_000_000_000L, a.until)
        assertTrue(a.extendable)
        assertEquals(false, a.lidClosed)
        assertEquals("backpack", a.sleepDisabledBy)
        assertFalse(a.keptByOther)
    }

    @Test fun theMacSleepingSoonIsABannerOnScreenAndANotificationAway() {
        mac()
        rig.sync()
        rig.push(fx("ws-mac-sleep-soon")) { rig.banners.isNotEmpty() }
        val n = rig.banners.single()
        // About the battery: it takes the battery notice's place.
        assertEquals("battery", n.id)
        assertEquals(Notice.Kind.Power, n.kind)
        assertEquals("z2", n.title)
        assertEquals(AwakeWords.sleepSoon(res.strings, 120, MacAwake.BATTERY, 15), n.text)
        assertTrue(n.text, n.text.contains("15"))
        assertTrue(rig.notices.isEmpty())

        rig.visible = false
        rig.push(fx("ws-mac-sleep-soon").put("reason", "hot")) { rig.notices.isNotEmpty() }
        assertEquals("mac_sleep", rig.notices.single().id)
        assertEquals(AwakeWords.sleepSoon(res.strings, 120, MacAwake.HOT, 15), rig.notices.single().text)
    }

    @Test fun backpackEndingOffersTwoHoursMore() {
        mac()
        rig.sync()
        rig.push(fx("ws-mac-backpack-ending")) { rig.banners.isNotEmpty() }
        val n = rig.banners.single()
        assertEquals("backpack-ending", n.id)
        assertEquals(Notice.Kind.Backpack, n.kind)
        assertEquals(Notice.ACTION_EXTEND, n.action)
        // 540 s: "in 9 minutes".
        assertEquals(res.getQuantityString(com.prudnikweb.hypernova.R.plurals.notice_backpack_ends, 9, 9), n.text)

        rig.visible = false
        rig.push(fx("ws-mac-backpack-ending")) { rig.notices.isNotEmpty() }
        assertEquals(Notice.ACTION_EXTEND, rig.notices.single().action)
    }

    @Test fun stayAwakeIsSetAndExtendedThroughTheMacsRoute() {
        mac()
        daemon.ok("POST", "/v1/mac/awake", fx("rest-mac-awake"))
        assertTrue(rig.call { rig.hub.setAwake(MacAwake.WORKING) }.isSuccess)
        assertEquals(MacAwake.WORKING, daemon.calls("POST", "/v1/mac/awake").single().json.getString("mode"))
        // The Mac's answer is what the phone shows (Off, here), not what it asked for.
        assertEquals(MacAwake.OFF, rig.hub.mac.awake!!.mode)

        daemon.ok("POST", "/v1/mac/awake", JSONObject().put("awake", fx("ws-awake").getJSONObject("awake")))
        assertTrue(rig.call { rig.hub.extendAwake() }.isSuccess)
        val body = daemon.calls("POST", "/v1/mac/awake").last().json
        assertEquals(true, body.getBoolean("extend"))
        assertFalse(body.has("mode"))
        assertEquals(MacAwake.BACKPACK, rig.hub.mac.awake!!.mode)
    }

    @Test fun openOnTheMacSaysWhichAppAndWhetherItWasOpenAlready() {
        mac()
        daemon.ok("POST", "/v1/sessions/s1/open", JSONObject().put("app", "iTerm").put("already", true))
        assertEquals("iTerm" to true, rig.call { rig.hub.openOnMac("s1") }.getOrThrow())
        daemon.ok("POST", "/v1/sessions/s1/open", JSONObject())
        assertEquals("Terminal" to false, rig.call { rig.hub.openOnMac("s1") }.getOrThrow())
        assertEquals(2, daemon.count("POST", "/v1/sessions/s1/open"))
    }

    @Test fun aLinuxServerLacksThemAndSaysWhyWhenAskedAnyway() {
        val lacks = JSONObject()
            .put(Mac.FEATURE_AWAKE, fx("rest-mac-awake-unavailable").getJSONObject("error").getString("message"))
            .put(Mac.FEATURE_OPEN_WINDOW, fx("rest-session-open-unavailable").getJSONObject("error").getString("message"))
        mac(Mac.LINUX, lacks)
        val m = rig.hub.mac
        assertFalse(m.isMac)
        assertFalse(m.has(Mac.FEATURE_AWAKE))
        assertFalse(m.has(Mac.FEATURE_OPEN_WINDOW))
        assertTrue("nothing said about the voice: it has it", m.has(Mac.FEATURE_MAC_VOICE))
        assertEquals(lacks.getString(Mac.FEATURE_AWAKE), m.unavailable[Mac.FEATURE_AWAKE])

        // Asked anyway (an older app's way): the server's own sentence, nothing unpaired.
        daemon.on("POST", "/v1/mac/awake") { json(501, fx("rest-mac-awake-unavailable")) }
        val awake = rig.call { rig.hub.setAwake(MacAwake.WORKING) }
        assertEquals(lacks.getString(Mac.FEATURE_AWAKE), awake.exceptionOrNull()?.message)
        daemon.on("POST", "/v1/sessions/s1/open") { json(501, fx("rest-session-open-unavailable")) }
        val open = rig.call { rig.hub.openOnMac("s1") }
        assertEquals(lacks.getString(Mac.FEATURE_OPEN_WINDOW), open.exceptionOrNull()?.message)
        assertEquals(com.prudnikweb.hypernova.data.LinkState.Connected, rig.hub.link.state)
    }

    @Test fun aMacHasEveryFeature() {
        mac(Mac.DARWIN, JSONObject())
        assertTrue(rig.hub.mac.isMac)
        for (f in listOf(Mac.FEATURE_AWAKE, Mac.FEATURE_OPEN_WINDOW, Mac.FEATURE_MAC_VOICE)) assertTrue(f, rig.hub.mac.has(f))
    }
}
