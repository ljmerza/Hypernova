package com.prudnikweb.hypernova.ui.screens

import android.os.Looper
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
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
import com.prudnikweb.hypernova.ui.settings.AboutBuild
import com.prudnikweb.hypernova.ui.settings.SettingsTab
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
 * The whole Settings tab (ui/settings/Settings.kt), as App.kt shows it: the app's own [HubViewModel]
 * over [Core], with real hubs linked to scripted daemons (data/live/FakeDaemon). One server, All
 * servers (a block per server, then This phone) and nothing paired; each part's clicks reach the
 * server of the block they're in. The daemons listen on fixed ports, since the server's card shows
 * its address; About shows a pinned build ([AboutBuild]), since each build has its own number.
 */
@RunWith(RobolectricTestRunner::class)
// Tall enough for every part at once: the tab is a lazy list, and a part off screen isn't there to click.
@Config(qualifiers = "+h5200dp")
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
        Core.init(RuntimeEnvironment.getApplication())
        ComputerWords.clear(Core.app)
        AboutBuild.name = "1.2.3"
        AboutBuild.code = 4242
    }

    @After fun tearDown() {
        for (s in Core.servers.toList()) Core.forget(s.id)
        Core.stayConnected(true)
        shadowOf(Looper.getMainLooper()).idle()
        daemons.forEach { it.close() }
        TextSize.set(context, TextSize.SMALL)
        NewSessionMemory.setStartMode(context, "default")
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
        val vm = HubViewModel(Core.app)
        rule.setContent {
            ui = androidx.compose.ui.platform.LocalContext.current
            Harness(spy) { SettingsTab(vm, top = 0.dp) }
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

    @Config(qualifiers = "+h3500dp")
    @Test fun oneServerShowsEveryPartInOrder() {
        pair(server("z2", 47391) { files(false) })
        linked()
        show()
        val headings = listOf(
            R.string.mac, R.string.providers, R.string.voice_settings, R.string.st_files, R.string.st_new_sessions, R.string.st_display,
            R.string.st_card_swipes, R.string.st_top_bar, R.string.st_typing, R.string.notifications, R.string.testing_checklist, R.string.about,
        ).map { str(it).uppercase() }
        val tops = headings.map { h -> rule.topOf(h) }
        assertEquals("Settings' parts top down", tops.sorted(), tops)
        // The server's card: its name, its versions in About, the providers it has.
        rule.onNodeWithText(str(R.string.providers_desc, Core.live!!.providers.size)).assertExists()
        rule.onNodeWithText(str(R.string.about_app_build, "1.2.3", 4242)).assertExists()
        rule.onNodeWithText("1.0.0").assertExists()
        rule.onNodeWithText(str(R.string.forget_mac)).assertExists()
        // Nothing of All servers.
        rule.onNodeWithText(str(R.string.st_all_intro)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.st_all_phone)).assertDoesNotExist()
        rule.snap("settings_tab_one_server")
    }

    @Test fun oneServerRowsOpenTheirScreensAndTheChecklistItsPage() {
        val z2 = server("z2", 47391)
        pair(z2)
        linked()
        val vm = show()
        clickText(str(R.string.providers))
        assertEquals(Route.Providers, vm.nav.top)
        vm.nav.reset()
        clickText(str(R.string.connlog_title))
        assertEquals(Route.ConnectionLog, vm.nav.top)
        vm.nav.reset()
        // The checklist: its page on this server, opened in the app.
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
        assertEquals("plan", NewSessionMemory.startMode(context))
        // Stay connected in the background: off, and back on.
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
        show()
        clickText(str(R.string.forget_mac))
        val ask = spy.confirms.single()
        assertEquals(str(R.string.forget_q, "z2"), ask.title)
        assertTrue(ask.danger)
        assertEquals(0, z2.count("DELETE", "/v1/devices/me"))
        ask.onConfirm()
        until("forgotten") { Core.servers.isEmpty() }
        rule.waitUntil(5_000) { z2.count("DELETE", "/v1/devices/me") == 1 }
        rule.onNodeWithText(str(R.string.not_paired)).performScrollTo().assertExists()
        assertTrue(spy.toasts.isEmpty())
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun notPairedIsTheCardAndThePhonesParts() {
        show()
        rule.onNodeWithText(str(R.string.not_paired)).assertExists()
        rule.onNodeWithText(str(R.string.providers)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.testing_checklist)).assertDoesNotExist()
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

    @Config(qualifiers = "+h5200dp")
    @Test fun allServersSaysSoThenABlockPerServerThenThisPhone() {
        twoServers()
        show()
        rule.onNodeWithText(str(R.string.st_all_intro)).assertExists()
        // The intro, z2's block, shed's block, then This phone.
        val order = listOf(str(R.string.st_all_intro), "z2", "shed", str(R.string.st_all_phone), str(R.string.st_display).uppercase(), str(R.string.about).uppercase())
        val tops = order.map { rule.topOf(it) }
        assertEquals(tops.sorted(), tops)
        // Each server's own parts: two providers rows, two checklists, Files in shed's only; one connection log, for all.
        assertEquals(2, rule.onAllNodesWithText(str(R.string.providers)).fetchSemanticsNodes().size)
        assertEquals(2, rule.onAllNodesWithText(str(R.string.forget_mac)).fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText(str(R.string.st_browse_title)).fetchSemanticsNodes().size)
        assertTrue(rule.topOf(str(R.string.st_browse_title)) > rule.topOf("shed"))
        assertEquals(1, rule.onAllNodesWithText(str(R.string.connlog_desc)).fetchSemanticsNodes().size)
        // The phone's About has no server's versions (each card has its own).
        assertEquals(2, rule.onAllNodesWithText("1.0.0").fetchSemanticsNodes().size)
        rule.snap("settings_tab_all_servers")
    }

    @Test fun allServersEachBlockActsOnItsOwnServer() {
        val (z2, shed) = twoServers()
        val shedId = Core.servers.single { it.pairing.macName == "shed" }.id
        val vm = show()
        // shed's Browse outside projects: on asks for the screen lock (none here), so nothing reaches either.
        rule.onNode(isSwitch(str(R.string.st_browse_title))).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(0, z2.count("POST", "/v1/mac/files") + shed.count("POST", "/v1/mac/files"))
        // shed's checklist (the second server's): shed's page.
        clickText(str(R.string.testing_checklist), index = 1)
        rule.waitUntil(5_000) { spy.opened.isNotEmpty() }
        assertEquals(listOf("web:${shed.base}/qa?k=key-shed"), spy.opened)
        assertEquals(0, z2.count("GET", "/v1/qa"))
        // shed's providers: its screens are shed's (a visit, as a session's from Home).
        clickText(str(R.string.providers), index = 1)
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
        // Forget in shed's block names shed and forgets it alone.
        shed.ok("DELETE", "/v1/devices/me", JSONObject().put("ok", true))
        clickText(str(R.string.forget_mac), index = 1)
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
        shed.down = true
        shed.closeSocket()
        until("shed away") { Core.statuses.single { it.name == "shed" }.state != LinkState.Connected }
        rule.waitForIdle()
        // Its card says the rest comes back; its providers, files and checklist are left out.
        rule.onNodeWithText(str(R.string.st_all_waiting)).performScrollTo().assertExists()
        assertEquals(1, rule.onAllNodesWithText(str(R.string.providers)).fetchSemanticsNodes().size)
        rule.onNodeWithText(str(R.string.st_browse_title)).assertDoesNotExist()
        assertEquals(2, rule.onAllNodesWithText(str(R.string.forget_mac)).fetchSemanticsNodes().size)
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
        assertEquals(2, rule.onAllNodesWithText(str(R.string.providers)).fetchSemanticsNodes().size)
    }
}
