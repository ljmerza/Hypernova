package com.prudnikweb.hypernova.ui.screens

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.HubViewModel
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.Route
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.session
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.storage
import com.prudnikweb.hypernova.ui.App
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.settings.SettingsJump
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.MotionPrefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
 * Settings' sub pages through the whole app (ui/App.kt), not a stand-in for it: Home's Settings tab
 * opens them, the system's back gesture leaves them, the stack is the ViewModel's (so an Activity
 * recreated keeps them), and "Turn on in Settings" from another screen lands inside a server's page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+h900dp")
class SettingsNavigationTest {
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

    private fun pair(name: String, sessions: List<JSONObject> = emptyList()): FakeDaemon {
        val d = FakeDaemon(name).also(daemons::add)
        d.snapshot = snapshot(name, sessions = sessions) { getJSONObject("mac").put("files", JSONObject().put("browseOutsideProjects", false)) }
        Core.paired(Pairing(d.base, "tok-$name", name, "m-$name", "dev-$name"))
        val deadline = System.nanoTime() + 10_000_000_000
        while (!Core.allHubs.all { it.link.state == LinkState.Connected && it.synced }) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("linked (${Core.statuses})")
            Thread.sleep(3)
        }
        return d
    }

    private fun show(): HubViewModel {
        val vm = HubViewModel(AndroidCore.app)
        rule.setContent { CompositionLocalProvider(LocalMotion provides MotionPrefs(reduced = true)) { App(vm) {} } }
        rule.waitForIdle()
        return vm
    }

    private fun settingsTab() {
        rule.onNodeWithText(str(R.string.settings).uppercase()).performClick()
        rule.waitForIdle()
    }

    private fun systemBack() {
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
    }

    @Test fun aServersPageAndThePhonesOpenFromTheTabAndTheSystemBackLeavesThem() {
        pair("z2")
        val vm = show()
        settingsTab()
        rule.onNodeWithText("z2").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(Route.ServerSettings(Core.activeId!!), vm.nav.top)
        rule.onNodeWithText(str(R.string.mac_address)).assertIsDisplayed()
        systemBack()
        assertEquals(Route.Home, vm.nav.top)
        // Back on the Settings tab it came from, not on the sessions.
        rule.onNodeWithText(str(R.string.st_top_bar)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(Route.TopBarSettings, vm.nav.top)
        rule.onNodeWithText(str(R.string.st_top_bar_context)).assertIsDisplayed()
        systemBack()
        assertEquals(Route.Home, vm.nav.top)
        rule.onNodeWithText(str(R.string.st_all_phone)).assertExists()
    }

    @Test fun aSubPageIsStillThereWhenTheActivityIsRecreated() {
        pair("z2")
        val vm = HubViewModel(AndroidCore.app)
        val recreated = StateRestorationTester(rule)
        recreated.setContent { CompositionLocalProvider(LocalMotion provides MotionPrefs(reduced = true)) { App(vm) {} } }
        rule.waitForIdle()
        settingsTab()
        rule.onNodeWithText("z2").performClick()
        rule.waitForIdle()
        assertEquals(Route.ServerSettings(Core.activeId!!), vm.nav.top)
        // As a font size change does: the state saved and the screen composed anew over the same ViewModel, which keeps the stack.
        recreated.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(Route.ServerSettings(Core.activeId!!), vm.nav.top)
        rule.onNodeWithText(str(R.string.mac_address)).assertIsDisplayed()
        // Back is still the Settings tab.
        systemBack()
        assertEquals(Route.Home, vm.nav.top)
        rule.onNodeWithText(str(R.string.st_all_phone)).assertExists()
    }

    @Test fun turnOnInSettingsFromAnotherScreenLandsOnFilesInsideTheServersPage() {
        pair("z2")
        val vm = show()
        // Off another screen, as "Turn on in Settings" on a folder outside the projects: App's opener resets, then asks.
        vm.nav.push(Route.ConnectionLog)
        rule.waitForIdle()
        vm.nav.reset()
        SettingsJump.show(SettingsJump.FILES, Core.activeId)
        rule.waitForIdle()
        assertEquals(Route.ServerSettings(Core.activeId!!), vm.nav.top)
        rule.onNodeWithText(str(R.string.st_browse_title)).assertIsDisplayed()
        assertEquals(null, SettingsJump.asked)
        // The system's back: the Settings tab, not the sessions.
        systemBack()
        assertEquals(Route.Home, vm.nav.top)
        rule.onNodeWithText(str(R.string.st_all_phone)).assertExists()
    }

    // The tabs on Settings' pages (owner, 2026-11-03): "when in any setting some page I want the bottom
    // navigation bar [...] to always show", and "if somebody clicks settings again when you're in deeply nested
    // it just takes you back to the main settings page".

    private fun tab(name: Int) = rule.onNode(hasText(str(name).uppercase()) and isSelectable())

    private fun tap(name: Int) {
        tab(name).performClick()
        rule.waitForIdle()
    }

    private fun tabsOn() {
        tab(R.string.settings).assertIsDisplayed().assertIsSelected()
        tab(R.string.sessions).assertIsDisplayed().assertIsNotSelected()
    }

    private fun push(vm: HubViewModel, r: Route) {
        vm.nav.push(r)
        rule.waitForIdle()
    }

    /** Settings' main page, at its top: its first row is on screen. */
    private fun mainPageAtItsTop(vm: HubViewModel) {
        assertEquals(listOf<Route>(Route.Home), vm.nav.stack.toList())
        tabsOn()
        rule.onNodeWithText("z2").assertIsDisplayed()
    }

    /** Down the main page, its first row gone off the top (on a short screen), and in through Logs and the row inside. */
    private fun intoTheConnectionLogByTheRows(vm: HubViewModel) {
        settingsTab()
        rule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, with(rule.density) { 200.dp.toPx() }) }
        rule.waitForIdle()
        rule.onNodeWithText("z2").assertIsNotDisplayed()
        rule.onNodeWithText(str(R.string.st_logs)).assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.connlog_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.connlog_title)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(Route.Home, Route.LogSettings, Route.ConnectionLog), vm.nav.stack.toList())
    }

    @Test fun everySettingsPageHasTheTabsWithSettingsSelectedHoweverDeep() {
        pair("z2")
        val vm = show()
        settingsTab()
        val claude = "claude"
        for (r in listOf(
            Route.ServerSettings(Core.activeId!!), Route.Providers, Route.ProviderEdit(null, "custom"), Route.AgentSettings(claude),
            Route.McpServers(claude), Route.McpServerPage(claude, "m", "user", null), Route.McpEdit(claude, null, null, null),
            Route.Plugins(claude), Route.PluginPage(claude, "p@m", "user", null, true), Route.PluginBrowse(claude, null),
            Route.Skills(claude), Route.AgentEnv(claude), Route.AgentRaw(claude),
            Route.NotificationSettings, Route.TopBarSettings, Route.CardSwipeSettings, Route.TypingVoiceSettings, Route.LogSettings, Route.ConnectionLog,
            Route.Web("about:blank", "Checklist"),
        )) {
            push(vm, r)
            assertEquals(r, vm.nav.top)
            tabsOn()
        }
    }

    @Test fun aSessionsChatHasNoTabsNorDoesAPageOpenedOverIt() {
        pair("z2", sessions = listOf(session("s1")))
        val vm = show()
        push(vm, Route.Session("s1"))
        assertEquals(Route.Session("s1"), vm.nav.top)
        tab(R.string.settings).assertDoesNotExist()
        push(vm, Route.Providers)
        assertEquals(Route.Providers, vm.nav.top)
        tab(R.string.settings).assertDoesNotExist()
    }

    @Config(qualifiers = "+h480dp")
    @Test fun tappingSettingsFromDepthIsTheMainPagesTopInOneTap() {
        pair("z2")
        val vm = show()
        intoTheConnectionLogByTheRows(vm)
        tabsOn()
        tap(R.string.settings)
        mainPageAtItsTop(vm)
    }

    @Config(qualifiers = "+h480dp")
    @Test fun anotherTabGoesThereAndSettingsAfterItIsTheMainPage() {
        pair("z2")
        val vm = show()
        intoTheConnectionLogByTheRows(vm)
        tap(R.string.history)
        assertEquals(listOf<Route>(Route.Home), vm.nav.stack.toList())
        tab(R.string.history).assertIsSelected()
        tap(R.string.settings)
        mainPageAtItsTop(vm)
    }

    @Config(qualifiers = "+h480dp")
    @Test fun backStillGoesUpOneLevelAndFromTheMainPageToSessions() {
        pair("z2")
        val vm = show()
        intoTheConnectionLogByTheRows(vm)
        systemBack()
        assertEquals(listOf(Route.Home, Route.LogSettings), vm.nav.stack.toList())
        tabsOn()
        systemBack()
        assertEquals(listOf<Route>(Route.Home), vm.nav.stack.toList())
        // Where it was: down the main page, the row it opened still in view.
        rule.onNodeWithText(str(R.string.st_logs)).assertIsDisplayed()
        rule.onNodeWithText("z2").assertIsNotDisplayed()
        tab(R.string.settings).assertIsSelected()
        systemBack()
        tab(R.string.sessions).assertIsSelected()
    }

    @Test fun jumpsLandInsideTheServersPageWithTheTabs() {
        pair("z2")
        val vm = show()
        push(vm, Route.ConnectionLog)
        vm.nav.reset()
        SettingsJump.show(SettingsJump.FILES, Core.activeId)
        rule.waitForIdle()
        assertEquals(Route.ServerSettings(Core.activeId!!), vm.nav.top)
        rule.onNodeWithText(str(R.string.st_browse_title)).assertIsDisplayed()
        tabsOn()
        // A failed server update's banner: from one page of Settings to the server's own card.
        push(vm, Route.Providers)
        vm.nav.reset()
        SettingsJump.show(SettingsJump.SERVER, Core.activeId)
        rule.waitForIdle()
        assertEquals(listOf(Route.Home, Route.ServerSettings(Core.activeId!!)), vm.nav.stack.toList())
        rule.onNodeWithText(str(R.string.mac_address)).assertIsDisplayed()
        tabsOn()
    }

    @Config(qualifiers = "+h480dp")
    @Test fun aRecreatedPageKeepsTheTabsAndTheirTap() {
        pair("z2")
        val vm = HubViewModel(AndroidCore.app)
        val recreated = StateRestorationTester(rule)
        recreated.setContent { CompositionLocalProvider(LocalMotion provides MotionPrefs(reduced = true)) { App(vm) {} } }
        rule.waitForIdle()
        intoTheConnectionLogByTheRows(vm)
        recreated.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(Route.ConnectionLog, vm.nav.top)
        tabsOn()
        tap(R.string.settings)
        mainPageAtItsTop(vm)
    }

    private fun rawFile(d: FakeDaemon) = d.on("GET", "/v1/agents/raw") {
        FakeDaemon.json(
            200,
            JSONObject("""{"agent":"claude","scope":"user","folder":"home","folders":[{"id":"home","name":"~"}],"where":"~/.claude/settings.json","placeholder":"-","state":"ok","hash":"h1","hidden":0,"undo":null}""")
                .put("text", "{\n  \"theme\": \"dark\"\n}\n"),
        )
    }

    private fun waitForRawLines() = rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("line 1")).fetchSemanticsNodes().isNotEmpty() }

    @Test fun aTabAsksAsBackDoesBeforeRawJsonLosesWhatsTyped() {
        rawFile(pair("z2"))
        val vm = show()
        settingsTab()
        push(vm, Route.AgentRaw("claude"))
        waitForRawLines()
        // Nothing typed: straight there.
        tap(R.string.settings)
        assertEquals(listOf<Route>(Route.Home), vm.nav.stack.toList())

        push(vm, Route.AgentRaw("claude"))
        waitForRawLines()
        rule.onNode(hasContentDescription("line 1")).performTextReplacement("{ \"typed\": 1,")
        rule.waitForIdle()
        for (other in listOf(R.string.settings, R.string.history)) {
            tap(other)
            assertEquals(Route.AgentRaw("claude"), vm.nav.top)
            rule.onNodeWithText(str(R.string.raw_discard_title)).assertIsDisplayed()
            rule.onNodeWithText(str(R.string.cancel)).performClick()
            rule.waitForIdle()
            // Staying keeps what's typed.
            assertEquals(Route.AgentRaw("claude"), vm.nav.top)
            rule.onNodeWithText("typed", substring = true).assertExists()
        }
        tap(R.string.history)
        rule.onNodeWithText(str(R.string.raw_discard_action)).performClick()
        rule.waitForIdle()
        assertEquals(listOf<Route>(Route.Home), vm.nav.stack.toList())
        tab(R.string.history).assertIsSelected()
    }

    @Test fun aProvidersFormHoldsWhatsTypedThroughATabAsThroughBack() {
        pair("z2")
        val vm = show()
        settingsTab()
        val form = Route.ProviderEdit(null, "custom")
        push(vm, form)
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("typed name")
        rule.waitForIdle()
        systemBack()
        push(vm, form)
        rule.onNodeWithText("typed name").assertExists()
        tap(R.string.settings)
        push(vm, form)
        rule.onNodeWithText("typed name").assertExists()
    }

    @Test fun withAllServersAutomationsSaysWhyNotAndThePageStays() {
        pair("z2")
        pair("mini")
        val vm = show()
        Core.showAll()
        rule.waitForIdle()
        settingsTab()
        push(vm, Route.NotificationSettings)
        tap(R.string.au_tab)
        assertEquals(Route.NotificationSettings, vm.nav.top)
        assertEquals(str(R.string.all_servers_no_automations), vm.toast?.text)
        tabsOn()
        tap(R.string.sessions)
        assertEquals(listOf<Route>(Route.Home), vm.nav.stack.toList())
        tab(R.string.sessions).assertIsSelected()
    }

    @Config(qualifiers = "+h480dp")
    @Test fun aLongPagesLastRowClearsTheTabsWithoutRoomToSpare() {
        pair("z2")
        val vm = show()
        settingsTab()
        push(vm, Route.TopBarSettings)
        rule.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        rule.waitForIdle()
        val last = rule.onNodeWithText(str(R.string.st_top_bar_model_week)).assertIsDisplayed().getBoundsInRoot()
        val bar = tab(R.string.settings).getBoundsInRoot()
        val gap = bar.top - last.bottom
        assertTrue("the last row ends over the tabs, by ${-gap}", gap >= 0.dp)
        assertTrue("$gap between the last row and the tabs: the room counted twice", gap < 90.dp)
    }

    @Test fun theSystemBackAsksAsTheArrowDoesBeforeRawJsonLosesWhatsTyped() {
        rawFile(pair("z2"))
        val vm = show()
        settingsTab()
        push(vm, Route.AgentRaw("claude"))
        waitForRawLines()
        // Nothing typed: straight back.
        systemBack()
        assertEquals(listOf<Route>(Route.Home), vm.nav.stack.toList())

        push(vm, Route.AgentRaw("claude"))
        waitForRawLines()
        rule.onNode(hasContentDescription("line 1")).performTextReplacement("{ \"typed\": 1,")
        rule.waitForIdle()
        systemBack()
        assertEquals(Route.AgentRaw("claude"), vm.nav.top)
        rule.onNodeWithText(str(R.string.raw_discard_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        rule.waitForIdle()
        // Staying keeps what's typed.
        assertEquals(Route.AgentRaw("claude"), vm.nav.top)
        rule.onNodeWithText("typed", substring = true).assertExists()
        systemBack()
        rule.onNodeWithText(str(R.string.raw_discard_action)).performClick()
        rule.waitForIdle()
        assertEquals(listOf<Route>(Route.Home), vm.nav.stack.toList())
    }

    /** The keyboard, [height] tall, as the system tells the window (which doesn't resize: enableEdgeToEdge). */
    private fun keyboard(height: androidx.compose.ui.unit.Dp) {
        val px = with(rule.density) { height.roundToPx() }
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px))
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()
        rule.runOnIdle {
            val compose = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
            ViewCompat.dispatchApplyWindowInsets(compose.getChildAt(0), insets)
        }
        rule.waitForIdle()
    }

    @Config(qualifiers = "+h640dp")
    @Test fun aFieldLowOnAPageScrollsClearOfTheKeyboardAndTheTabsStayWhereTheyAre() {
        pair("z2")
        val vm = show()
        settingsTab()
        push(vm, Route.ProviderEdit(null, "custom"))
        val up = 280.dp
        val page = rule.onRoot().getBoundsInRoot().bottom
        val bar = tab(R.string.settings).getBoundsInRoot()
        // The lowest field on screen above the tabs: the keyboard will be over it.
        val fields = rule.onAllNodes(hasSetTextAction())
        val low = fields.fetchSemanticsNodes().withIndex()
            .filter { with(rule.density) { it.value.boundsInRoot.bottom.toDp() } < bar.top }
            .maxBy { it.value.boundsInRoot.bottom }.index
        assertTrue("the field is under where the keyboard goes", fields[low].getBoundsInRoot().bottom > page - up)
        fields[low].performClick()
        rule.waitForIdle()
        keyboard(up)
        assertTrue("${fields[low].getBoundsInRoot().bottom} is under the keyboard, from ${page - up}", fields[low].getBoundsInRoot().bottom <= page - up)
        assertTrue("and under the top bar's blur only if it has to", fields[low].getBoundsInRoot().top > 0.dp)
        assertEquals(bar, tab(R.string.settings).getBoundsInRoot())
    }
}
