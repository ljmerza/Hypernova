package com.prudnikweb.hypernova.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AwakeWords
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.HubException
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.data.MacAwake
import com.prudnikweb.hypernova.data.MacBattery
import com.prudnikweb.hypernova.data.ServerUpdate
import com.prudnikweb.hypernova.ui.settings.AutoUpdateRow
import com.prudnikweb.hypernova.ui.settings.StayAwakeBlock
import com.prudnikweb.hypernova.ui.settings.autoUpdateLine
import com.prudnikweb.hypernova.ui.term.TerminalScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [FakeHub] with the server's own settings answered and recorded: Stay awake, the auto-update switch, Open on the Mac. */
class ServerHub(val base: FakeHub = FakeHub()) : Hub by base {
    val calls = mutableListOf<String>()
    var refusal: String? = null
    private fun <T> answer(call: String, ok: T): Result<T> {
        calls += call
        return refusal?.let { Result.failure(HubException(it)) } ?: Result.success(ok)
    }
    override suspend fun setAwake(mode: String): Result<Unit> = answer("setAwake($mode)", Unit)
    override suspend fun extendAwake(): Result<Unit> = answer("extendAwake()", Unit)
    override suspend fun setAutoUpdate(on: Boolean): Result<Unit> = answer("setAutoUpdate($on)", Unit)
    override suspend fun openOnMac(sessionId: String): Result<Pair<String, Boolean>> = answer("openOnMac($sessionId)", "iTerm" to false)
}

/**
 * The Mac-only features on the phone's screens (docs/PROTOCOL.md, "Mac-only features"): Stay awake's
 * picker and what it says, the terminal's Open on the Mac (a Mac only: a Linux server has no screen
 * for it), and the server section's Update this server automatically.
 */
@RunWith(RobolectricTestRunner::class)
class MacOnlyScreensTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val res get() = context.resources

    @Before fun setUp() = resetPrefs()

    private fun awake(hub: Hub, a: MacAwake, battery: MacBattery? = null) {
        rule.setContent { Harness(spy) { Column(Modifier.padding(16.dp)) { StayAwakeBlock(hub, a, battery) } } }
        rule.waitForIdle()
    }

    private fun mode(m: String) = rule.onNode(hasText(AwakeWords.modeLabel(res, m)), useUnmergedTree = true)

    @Test fun stayAwakeIsPickedOnTheMac() {
        val hub = ServerHub()
        awake(hub, MacAwake(mode = MacAwake.OFF, holding = false, backpackReady = true), MacBattery(62, charging = false, onAC = false))
        rule.onNodeWithText(str(R.string.awake_title), substring = true, ignoreCase = true).assertExists()
        rule.snap("settings_stay_awake")
        mode(MacAwake.WORKING).performClick()
        rule.waitForIdle()
        assertEquals(listOf("setAwake(working)"), hub.calls)
        // Backpack again starts its hours over; the mode on show (Off, the Mac's word) isn't asked twice.
        mode(MacAwake.OFF).performClick()
        rule.waitForIdle()
        assertEquals(listOf("setAwake(working)"), hub.calls)
    }

    @Test fun backpackBeforeItsSetupSaysToSetItUpOnTheMac() {
        val hub = ServerHub()
        awake(hub, MacAwake(mode = MacAwake.OFF, holding = false, backpackReady = false))
        mode(MacAwake.BACKPACK).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.awake_needs_setup)).assertExists()
        assertTrue("nothing asked of the Mac", hub.calls.isEmpty())
    }

    @Test fun aMacWithoutALidHasNoBackpack() {
        awake(ServerHub(), MacAwake(mode = MacAwake.WORKING, holding = true, laptop = false))
        mode(MacAwake.WORKING).assertExists()
        mode(MacAwake.BACKPACK).assertDoesNotExist()
    }

    @Test fun aRefusalIsSaid() {
        val hub = ServerHub().apply { refusal = "Stay awake is for a Mac." }
        awake(hub, MacAwake(mode = MacAwake.OFF, holding = false, backpackReady = true))
        mode(MacAwake.WORKING).performClick()
        rule.waitForIdle()
        assertEquals(listOf("Stay awake is for a Mac."), spy.toasts)
    }

    // ─── Open on the Mac, in the terminal's top bar ───

    private fun terminal(platform: String, unavailable: Map<String, String>): ServerHub {
        val hub = ServerHub(FakeHub(mac = Samples.mac().copy(platform = platform, unavailable = unavailable)))
        rule.setContent { Harness(spy) { TerminalScreen(hub, Samples.DEPLOY, onBack = {}) } }
        rule.waitForIdle()
        return hub
    }

    @Test fun aMacsTerminalOpensInAWindowThere() {
        val hub = terminal(Mac.DARWIN, emptyMap())
        rule.onNode(hasContentDescription(str(R.string.open_on_mac))).performClick()
        rule.waitForIdle()
        assertEquals(listOf("openOnMac(${Samples.DEPLOY})"), hub.calls)
        assertTrue(spy.toasts.toString(), str(R.string.opened_on_mac, "iTerm") in spy.toasts)
    }

    @Test fun aLinuxServerHasNoWindowToOpen() {
        terminal(Mac.LINUX, mapOf(Mac.FEATURE_OPEN_WINDOW to "This server has no screen to open a window on."))
        rule.onNode(hasContentDescription(str(R.string.open_on_mac))).assertDoesNotExist()
    }

    // ─── Update this server automatically ───

    private fun isSwitch() = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    private fun autoUpdate(hub: Hub, u: ServerUpdate) {
        rule.setContent { Harness(spy) { Column(Modifier.padding(16.dp)) { AutoUpdateRow(hub, u) } } }
        rule.waitForIdle()
    }

    @Test fun theServersOwnUpdateIsTurnedOnFromThePhone() {
        val hub = ServerHub()
        autoUpdate(hub, ServerUpdate(auto = false, available = true))
        rule.onNodeWithText(str(R.string.su_desc), substring = true).assertExists()
        rule.snap("settings_auto_update")
        rule.onNode(isSwitch()).performClick()
        rule.waitForIdle()
        assertEquals(listOf("setAutoUpdate(true)"), hub.calls)
    }

    @Test fun theServersOwnUpdateSaysWhyNotAndWhatItIsDoing() {
        assertEquals(str(R.string.su_unavailable, "a git checkout"), autoUpdateLine(context, ServerUpdate(auto = false, available = false, reason = "a git checkout")))
        assertEquals(str(R.string.su_installing, "v0.43.0"), autoUpdateLine(context, ServerUpdate(auto = true, available = true, state = ServerUpdate.INSTALLING, target = "v0.43.0")))
        assertEquals(str(R.string.su_waiting, "v0.43.0"), autoUpdateLine(context, ServerUpdate(auto = true, available = true, state = ServerUpdate.WAITING, target = "v0.43.0")))
        val last = autoUpdateLine(context, ServerUpdate(auto = true, available = true, last = ServerUpdate.Last("v0.42.0", "v0.43.0", System.currentTimeMillis() - 3_600_000)))
        assertTrue(last, last.startsWith(str(R.string.su_desc) + "\n") && "v0.43.0" in last && "v0.42.0" in last)
    }

    @Test fun aRefusedSwitchIsSaid() {
        val hub = ServerHub().apply { refusal = "This install can't update itself." }
        autoUpdate(hub, ServerUpdate(auto = false, available = true))
        rule.onNode(isSwitch()).performClick()
        rule.waitForIdle()
        assertEquals(listOf("This install can't update itself."), spy.toasts)
    }
}
