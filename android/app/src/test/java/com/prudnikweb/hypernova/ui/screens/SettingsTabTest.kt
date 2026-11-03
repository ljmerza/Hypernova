package com.prudnikweb.hypernova.ui.screens

import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.HubViewModel
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.NewSessionMemory
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.Route
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.json
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.storage
import com.prudnikweb.hypernova.ui.settings.AboutBuild
import com.prudnikweb.hypernova.ui.settings.SettingsJump
import com.prudnikweb.hypernova.ui.theme.TextSize
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The whole of Settings (ui/settings/Settings.kt), as App.kt shows it: the app's own [HubViewModel]
 * over [Core], with real hubs linked to scripted daemons (data/live/FakeDaemon). The tab with one
 * server, All servers and nothing paired, every sub page, jumps; each page's clicks reach its own
 * server. The daemons listen on fixed ports, since the server's card shows its address; About shows a
 * pinned build ([AboutBuild]), since each build has its own number.
 */
@RunWith(RobolectricTestRunner::class)
// Tall enough for every part of a page at once: the pages are lazy lists, and a part off screen isn't there to click.
@Config(qualifiers = "+h2200dp")
class SettingsTabTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val daemons = mutableListOf<FakeDaemon>()
    private val build = AboutBuild.name to AboutBuild.code
    /** The screen's own context: its words, as the tab draws them. */
    private var ui: android.content.Context? = null

    private fun str(id: Int, vararg args: Any): String = (ui ?: context).getString(id, *args)

    @Before fun setUp() {
        resetPrefs()
        AndroidCore.init(RuntimeEnvironment.getApplication())
        ComputerWords.clear(AndroidCore.app.storage)
        AboutBuild.name = "1.2.3"
        AboutBuild.code = 4242
    }

    @After fun tearDown() {
        for (s in Core.servers.toList()) Core.forget(s.id)
        Core.stayConnected(true)
        shadowOf(Looper.getMainLooper()).idle()
        daemons.forEach { it.close() }
        TextSize.set(context, TextSize.SMALL)
        NewSessionMemory.setStartMode(context.storage, "default")
        AboutBuild.name = build.first
        AboutBuild.code = build.second
    }

    private fun until(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what (${Core.statuses})")
            Thread.sleep(3)
        }
    }

    /** A server on [port] (shown on its card) whose snapshot is [more]'s, answering the checklist. */
    private fun server(name: String, port: Int, more: JSONObject.() -> Unit = {}): FakeDaemon {
        val d = FakeDaemon(name, port = port).also(daemons::add)
        d.snapshot = snapshot(name, more = more)
        d.on("GET", "/v1/qa") { json(200, JSONObject().put("url", "http://127.0.0.1:47321/qa?k=key-$name")) }
        return d
    }

    /** Pairs [d] (it becomes the one on screen) and returns its id. */
    private fun pair(d: FakeDaemon): String {
        Core.paired(Pairing(d.base, "tok-${d.name}", d.name, "m-${d.name}", "dev-${d.name}"))
        return Core.activeId!!
    }

    private fun linked() = until("every server linked") { Core.allHubs.isNotEmpty() && Core.allHubs.all { it.link.state == LinkState.Connected && it.synced } }

    private fun JSONObject.files(on: Boolean) = apply { getJSONObject("mac").put("files", JSONObject().put("browseOutsideProjects", on)) }

    private fun show(): HubViewModel {
        val vm = HubViewModel(AndroidCore.app)
        rule.setContent {
            ui = androidx.compose.ui.platform.LocalContext.current
            Harness(spy) { SettingsHost(vm) }
        }
        rule.waitForIdle()
        return vm
    }

    private fun isSwitch(name: String) = hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    /** Clicks the [index]th node (top down) with [text], scrolled to first. */
    private fun clickText(text: String, index: Int = 0) {
        rule.onAllNodesWithText(text)[index].performScrollTo().performClick()
        rule.waitForIdle()
    }

    /** Back, the page's own button. */
    private fun back() {
        rule.onNode(hasContentDescription(str(R.string.back))).performClick()
        rule.waitForIdle()
    }

    // ─── One server ───

    @Test fun aboutLinksToTheProjectsPage() {
        pair(server("z2", 47391) { files(false) })
        linked()
        show()
        rule.onNodeWithText(str(R.string.about_github_repo)).performScrollTo().performClick()
        rule.waitForIdle()
        val page = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(android.content.Intent.ACTION_VIEW, page.action)
        assertEquals("https://github.com/ljmerza/Hypernova", page.dataString)
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun oneServerIsARowThenThePhonesPartsInOrder() {
        pair(server("z2", 47391) { files(false) })
        linked()
        val vm = show()
        val order = listOf(
            str(R.string.mac), "z2", str(R.string.st_all_phone), str(R.string.notifications), str(R.string.st_top_bar), str(R.string.st_card_swipes),
            str(R.string.st_typing_voice), str(R.string.st_logs), str(R.string.st_new_sessions).uppercase(), str(R.string.st_display).uppercase(), str(R.string.about).uppercase(),
        )
        val tops = order.map { rule.topOf(it) }
        assertEquals("Settings' parts top down", tops.sorted(), tops)
        // A server's settings aren't on the tab; About's the phone's.
        rule.onNodeWithText(str(R.string.forget_mac)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.providers)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.about_app_build, "1.2.3", 4242)).assertExists()
        // Nothing of All servers.
        rule.onNodeWithText(str(R.string.st_servers)).assertDoesNotExist()
        rule.snap("settings_tab_one_server")
        // Its page: its card, its versions, its providers, its files.
        clickText("z2")
        assertEquals(Route.ServerSettings(Core.activeId!!), vm.nav.top)
        rule.onNodeWithText(str(R.string.providers_desc, Core.live!!.providers.size)).assertExists()
        rule.onNodeWithText("1.0.0").assertExists()
        rule.onNodeWithText(str(R.string.forget_mac)).assertExists()
        rule.onNodeWithText(str(R.string.st_browse_title)).assertExists()
        rule.onNodeWithText(str(R.string.st_all_waiting)).assertDoesNotExist()
        rule.snap("settings_server_page")
    }

    /**
     * The inventory: every section the old long page had, on the page it lives on now. A section that
     * goes missing or moves fails here by name.
     */
    @Test fun everySectionIsReachable() {
        pair(server("z2", 47391) { files(false) })
        linked()
        val vm = show()
        val id = Core.activeId!!
        // A read aloud setting comes with the server's own voices; it's in SettingsPlanTest, and its section on its own in ReadAloudLiveTest.
        for ((text, section) in listOf(R.string.st_new_sessions to "New sessions", R.string.st_display to "Display", R.string.about to "About", R.string.st_term_engine to "Terminal engine")) {
            rule.onNode(hasText(str(text).uppercase())).assertExists("the tab: $section")
        }
        val pages = listOf(
            "z2" to (Route.ServerSettings(id) to listOf(R.string.mac_address, R.string.forget_mac, R.string.providers, R.string.st_browse_title, R.string.testing_checklist, R.string.nc_same, R.string.about_daemon)),
            str(R.string.notifications) to (Route.NotificationSettings to listOf(R.string.bg_title, R.string.nc_which)),
            str(R.string.st_top_bar) to (Route.TopBarSettings to listOf(R.string.st_top_bar_context, R.string.st_top_bar_desc, R.string.st_top_bar_model_week)),
            str(R.string.st_card_swipes) to (Route.CardSwipeSettings to listOf(R.string.st_swipe_left, R.string.st_swipe_right)),
            str(R.string.st_typing_voice) to (Route.TypingVoiceSettings to listOf(R.string.st_enter_key, R.string.voice_engine)),
            str(R.string.st_logs) to (Route.LogSettings to listOf(R.string.connlog_title, R.string.debuglog_switch)),
        )
        for ((row, target) in pages) {
            val (route, inside) = target
            clickText(row)
            assertEquals(row, route, vm.nav.top)
            for (text in inside) assertTrue("$row holds ${str(text)}", rule.onAllNodesWithText(str(text)).fetchSemanticsNodes().isNotEmpty())
            // Back, to the tab.
            back()
            assertEquals(row, Route.Home, vm.nav.top)
            rule.onNodeWithText(str(R.string.st_all_phone)).assertExists()
        }
    }

    @Test fun whatItKeepsSurvivesAwayAndBackOnAPage() {
        pair(server("z2", 47391))
        linked()
        val vm = show()
        clickText(str(R.string.st_typing_voice))
        clickText(str(R.string.st_enter_newline))
        assertEquals(com.prudnikweb.hypernova.data.EnterKey.NewLine, com.prudnikweb.hypernova.ui.components.EnterKeyPref.state(context).value)
        back()
        clickText(str(R.string.st_typing_voice))
        assertEquals(Route.TypingVoiceSettings, vm.nav.top)
        clickText(str(R.string.st_enter_send))
        assertEquals(com.prudnikweb.hypernova.data.EnterKey.Send, com.prudnikweb.hypernova.ui.components.EnterKeyPref.state(context).value)
    }

    @Test fun oneServerRowsOpenTheirScreensAndTheChecklistItsPage() {
        val z2 = server("z2", 47391)
        pair(z2)
        linked()
        val vm = show()
        clickText("z2")
        clickText(str(R.string.providers))
        assertEquals(Route.Providers, vm.nav.top)
        vm.nav.reset()
        rule.waitForIdle()
        clickText(str(R.string.st_logs))
        clickText(str(R.string.connlog_title))
        assertEquals(Route.ConnectionLog, vm.nav.top)
        vm.nav.reset()
        rule.waitForIdle()
        // The checklist: its page on this server, opened in the app.
        clickText("z2")
        clickText(str(R.string.testing_checklist))
        rule.waitUntil(5_000) { spy.opened.isNotEmpty() }
        assertEquals(listOf("web:${z2.base}/qa?k=key-z2"), spy.opened)
        // A server that can't give it says why.
        z2.on("GET", "/v1/qa") { FakeDaemon.refusal(500, "oops", "The checklist isn't there.") }
        clickText(str(R.string.testing_checklist))
        rule.waitUntil(5_000) { spy.toasts.isNotEmpty() }
        assertEquals(listOf("The checklist isn't there."), spy.toasts)
    }

    @Test fun browseOutsideProjectsOffAtOnceOnOnlyAfterTheScreenLock() {
        val z2 = server("z2", 47391) { files(true) }
        z2.ok("POST", "/v1/mac/files", JSONObject().put("files", JSONObject().put("browseOutsideProjects", false)))
        pair(z2)
        linked()
        show()
        clickText("z2")
        val browse = str(R.string.st_browse_title)
        rule.onNode(isSwitch(browse)).performScrollTo().performClick()
        rule.waitUntil(5_000) { z2.count("POST", "/v1/mac/files") == 1 }
        assertFalse(z2.calls("POST", "/v1/mac/files").single().json.getBoolean("browseOutsideProjects"))
        rule.waitUntil(5_000) { Core.live?.mac?.browseOutsideProjects == false }
        // On asks for the fingerprint or screen lock first; a phone with none can't, and says so.
        rule.onNode(isSwitch(browse)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf(str(R.string.st_browse_no_lock)), spy.toasts)
        assertEquals(1, z2.count("POST", "/v1/mac/files"))
    }

    @Test fun thePhonesOwnPartsKeepWhatsPicked() {
        pair(server("z2", 47391))
        linked()
        val vm = show()
        // Display: the text size.
        clickText(str(R.string.st_text_large))
        assertEquals(TextSize.LARGE, TextSize.state(context).value)
        // New sessions start in Plan.
        clickText(str(R.string.mode_plan))
        assertEquals("plan", NewSessionMemory.startMode(context.storage))
        // Stay connected in the background: off, and back on.
        clickText(str(R.string.notifications))
        assertTrue(vm.background)
        rule.onNode(isSwitch(str(R.string.bg_title))).performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(vm.background)
        assertFalse(Core.background)
        rule.onNode(isSwitch(str(R.string.bg_title))).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(Core.background)
    }

    @Test fun forgetAsksFirstThenTheServerForgetsThisPhone() {
        val z2 = server("z2", 47391)
        z2.ok("DELETE", "/v1/devices/me", JSONObject().put("ok", true))
        pair(z2)
        linked()
        val vm = show()
        clickText("z2")
        clickText(str(R.string.forget_mac))
        val ask = spy.confirms.single()
        assertEquals(str(R.string.forget_q, "z2"), ask.title)
        assertTrue(ask.danger)
        assertEquals(0, z2.count("DELETE", "/v1/devices/me"))
        ask.onConfirm()
        until("forgotten") { Core.servers.isEmpty() }
        rule.waitUntil(5_000) { z2.count("DELETE", "/v1/devices/me") == 1 }
        // Its page is gone with it: back on the tab.
        until("back on the tab") { vm.nav.top == Route.Home }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.not_paired)).performScrollTo().assertExists()
        assertTrue(spy.toasts.isEmpty())
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun notPairedIsTheCardAndThePhonesParts() {
        show()
        rule.onNodeWithText(str(R.string.not_paired)).assertExists()
        rule.onNodeWithText(str(R.string.providers)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.testing_checklist)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.notifications)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.st_top_bar)).assertExists()
        rule.onNodeWithText(str(R.string.st_display).uppercase()).assertExists()
        rule.snap("settings_tab_not_paired")
    }

    // ─── All servers ───

    /** z2 then shed paired, z2 picked, then All servers: shed has Browse outside projects. */
    private fun twoServers(): Pair<FakeDaemon, FakeDaemon> {
        val z2 = server("z2", 47391)
        val shed = server("shed", 47392) { files(false) }
        val z2Id = pair(z2)
        pair(shed)
        linked()
        Core.switchTo(z2Id)
        Core.showAll()
        return z2 to shed
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun allServersIsARowPerServerThenThisPhone() {
        twoServers()
        val vm = show()
        // The servers, one row each in the order they were paired, then This phone.
        val order = listOf(str(R.string.st_servers), "z2", "shed", str(R.string.st_all_phone), str(R.string.st_display).uppercase(), str(R.string.about).uppercase())
        val tops = order.map { rule.topOf(it) }
        assertEquals(tops.sorted(), tops)
        assertEquals(2, rule.onAllNodesWithText(str(R.string.st_server_row_desc)).fetchSemanticsNodes().size)
        // Nothing of a server's own on the tab, and one way to the connection log, for all.
        rule.onNodeWithText(str(R.string.providers)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.forget_mac)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.st_browse_title)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.connlog_desc)).assertDoesNotExist()
        rule.snap("settings_tab_all_servers")
        // Each server's row opens its own page, with its versions, and shed alone has Files.
        val ids = Core.everyHub.map { it.first.id }
        clickText("z2")
        assertEquals(Route.ServerSettings(ids[0]), vm.nav.top)
        rule.onNodeWithText("1.0.0").assertExists()
        rule.onNodeWithText(str(R.string.st_browse_title)).assertDoesNotExist()
        back()
        clickText("shed")
        assertEquals(Route.ServerSettings(ids[1]), vm.nav.top)
        rule.onNodeWithText("1.0.0").assertExists()
        rule.onNodeWithText(str(R.string.st_browse_title)).assertExists()
        back()
        assertEquals(Route.Home, vm.nav.top)
    }

    @Test fun allServersEachPageActsOnItsOwnServer() {
        val (z2, shed) = twoServers()
        val shedId = Core.servers.single { it.pairing.macName == "shed" }.id
        val vm = show()
        clickText("shed")
        // shed's Browse outside projects: on asks for the screen lock (none here), so nothing reaches either.
        rule.onNode(isSwitch(str(R.string.st_browse_title))).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(0, z2.count("POST", "/v1/mac/files") + shed.count("POST", "/v1/mac/files"))
        // shed's checklist: shed's page.
        clickText(str(R.string.testing_checklist))
        rule.waitUntil(5_000) { spy.opened.isNotEmpty() }
        assertEquals(listOf("web:${shed.base}/qa?k=key-shed"), spy.opened)
        assertEquals(0, z2.count("GET", "/v1/qa"))
        // shed's providers: its screens are shed's (a visit, as a session's from Home).
        clickText(str(R.string.providers))
        assertEquals(Route.Providers, vm.nav.top)
        assertEquals(shedId, Core.activeId)
        assertTrue(Core.all)
        // So its agents' settings are shed's to answer, and z2 isn't asked.
        shed.ok("GET", "/v1/agents/settings", JSONObject("""{"scope":"user","claude":{"state":"missing","values":[],"summary":null},"codex":{"state":"missing","values":[],"summary":null}}"""))
        val fromShed = kotlinx.coroutines.runBlocking { vm.hubOf(shedId)!!.agentSettings() }
        assertEquals(com.prudnikweb.hypernova.data.AgentSettingsFile.MISSING, fromShed.getOrThrow().claude.state)
        assertEquals(1, shed.count("GET", "/v1/agents/settings"))
        assertEquals(0, z2.count("GET", "/v1/agents/settings"))
        Core.backToPicked()
        vm.nav.reset()
        rule.waitForIdle()
        // Forget on shed's page names shed and forgets it alone.
        shed.ok("DELETE", "/v1/devices/me", JSONObject().put("ok", true))
        clickText("shed")
        clickText(str(R.string.forget_mac))
        val ask = spy.confirms.single()
        assertEquals(str(R.string.forget_q, "shed"), ask.title)
        ask.onConfirm()
        until("shed forgotten") { Core.servers.size == 1 }
        until("shed told") { shed.count("DELETE", "/v1/devices/me") == 1 }
        assertEquals(0, z2.count("DELETE", "/v1/devices/me"))
        assertEquals(listOf("z2"), Core.servers.map { it.pairing.macName })
    }

    @Test fun aServerThatCantBeReachedKeepsItsCardAloneAndRetry() {
        val (_, shed) = twoServers()
        show()
        clickText("shed")
        shed.down = true
        shed.closeSocket()
        until("shed away") { Core.statuses.single { it.name == "shed" }.state != LinkState.Connected }
        rule.waitForIdle()
        // Its card says the rest comes back; its providers, files and checklist are left out.
        rule.onNodeWithText(str(R.string.st_all_waiting)).performScrollTo().assertExists()
        rule.onNodeWithText(str(R.string.providers)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.st_browse_title)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.forget_mac)).assertExists()
        // Retry now tries shed again at once.
        val before = shed.count("GET", "/v1/snapshot") + shed.count("GET", "/v1/health")
        shed.down = false
        if (rule.onAllNodesWithText(str(R.string.retry_now)).fetchSemanticsNodes().isNotEmpty()) {
            clickText(str(R.string.retry_now))
            until("shed tried again") { shed.count("GET", "/v1/snapshot") + shed.count("GET", "/v1/health") > before }
        }
        until("shed back") { Core.statuses.single { it.name == "shed" }.state == LinkState.Connected }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.st_all_waiting)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.providers)).assertExists()
    }

    // ─── Jumps ───

    /** Short, so a part further down the page is off screen until it's scrolled to. */
    @Config(qualifiers = "+h640dp")
    @Test fun aJumpToFilesOpensTheServersPageAtFiles() {
        pair(server("z2", 47391) { files(false) })
        linked()
        val vm = show()
        SettingsJump.show(SettingsJump.FILES, Core.activeId)
        rule.waitForIdle()
        assertEquals(Route.ServerSettings(Core.activeId!!), vm.nav.top)
        rule.onNodeWithText(str(R.string.st_browse_title)).assertIsDisplayed()
        assertEquals("once", null, SettingsJump.asked)
        // Back is the tab, with nothing asked again.
        back()
        assertEquals(Route.Home, vm.nav.top)
        rule.onNodeWithText(str(R.string.st_all_phone)).assertIsDisplayed()
    }

    @Config(qualifiers = "+h640dp")
    @Test fun withAllServersAJumpLandsOnTheServerItWasAskedFor() {
        twoServers()
        val shedId = Core.servers.single { it.pairing.macName == "shed" }.id
        val vm = show()
        // Picked is z2; "Turn on in Settings" from a session of shed's.
        SettingsJump.show(SettingsJump.FILES, shedId)
        rule.waitForIdle()
        assertEquals(Route.ServerSettings(shedId), vm.nav.top)
        rule.onNodeWithText(str(R.string.st_browse_title)).assertIsDisplayed()
        assertEquals(null, SettingsJump.asked)
    }

    @Test fun aServersUpdateNoticeOpensItsCard() {
        twoServers()
        val shedId = Core.servers.single { it.pairing.macName == "shed" }.id
        val vm = show()
        vm.openServerSettings(shedId)
        rule.waitForIdle()
        assertEquals(Route.ServerSettings(shedId), vm.nav.top)
        rule.onNodeWithText(str(R.string.mac_address)).assertIsDisplayed()
        assertEquals(null, SettingsJump.asked)
    }

    @Test fun aJumpToWhatAServerWaitingHasntGotStaysAtTheTop() {
        val (_, shed) = twoServers()
        val shedId = Core.servers.single { it.pairing.macName == "shed" }.id
        val vm = show()
        shed.down = true
        shed.closeSocket()
        until("shed away") { Core.statuses.single { it.name == "shed" }.state != LinkState.Connected }
        SettingsJump.show(SettingsJump.FILES, shedId)
        rule.waitForIdle()
        assertEquals(Route.ServerSettings(shedId), vm.nav.top)
        rule.onNodeWithText(str(R.string.st_all_waiting)).assertIsDisplayed()
        assertEquals(null, SettingsJump.asked)
    }

    @Test fun aJumpNamingAServerNoLongerPairedFallsBackToTheOneOnScreen() {
        pair(server("z2", 47391))
        linked()
        val vm = show()
        SettingsJump.show(SettingsJump.SERVER, "gone")
        rule.waitForIdle()
        assertEquals(Route.ServerSettings(Core.activeId!!), vm.nav.top)
        assertEquals(null, SettingsJump.asked)
    }
}
