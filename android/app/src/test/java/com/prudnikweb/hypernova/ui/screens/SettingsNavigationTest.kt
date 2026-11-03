package com.prudnikweb.hypernova.ui.screens

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.HubViewModel
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.Route
import com.prudnikweb.hypernova.data.live.FakeDaemon
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

    private fun pair(name: String): FakeDaemon {
        val d = FakeDaemon(name).also(daemons::add)
        d.snapshot = snapshot(name) { getJSONObject("mac").put("files", JSONObject().put("browseOutsideProjects", false)) }
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
}
