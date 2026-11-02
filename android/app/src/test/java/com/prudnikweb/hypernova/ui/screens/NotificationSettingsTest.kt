package com.prudnikweb.hypernova.ui.screens

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AndroidCore
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.HubViewModel
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.data.NotificationPrefs
import com.prudnikweb.hypernova.data.NotificationPrefsStore
import com.prudnikweb.hypernova.data.NotifyGroup
import com.prudnikweb.hypernova.data.Pairing
import com.prudnikweb.hypernova.data.ServerNotify
import com.prudnikweb.hypernova.data.live.FakeDaemon
import com.prudnikweb.hypernova.data.live.FakeDaemon.Companion.snapshot
import com.prudnikweb.hypernova.data.storage
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.components.ConfirmDialog
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.settings.NotificationDefaults
import com.prudnikweb.hypernova.ui.settings.ServerNotifyRow
import com.prudnikweb.hypernova.ui.settings.SettingsTab
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
 * Settings' notification controls (ui/settings/NotificationControls.kt): the defaults in the
 * Notifications card, a row in each server's card, and the confirm that says a prompt would wait
 * silently. The tab itself in SettingsTabTest; here its rows, on their own so the pictures show them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+h1000dp")
class NotificationSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val daemons = mutableListOf<FakeDaemon>()

    @Before fun setUp() {
        resetPrefs()
        AndroidCore.init(RuntimeEnvironment.getApplication())
        ComputerWords.clear(AndroidCore.app.storage)
    }

    @After fun tearDown() {
        Core.setNotifications(NotificationPrefs())
        for (s in Core.servers.toList()) Core.forget(s.id)
        shadowOf(Looper.getMainLooper()).idle()
        daemons.forEach { it.close() }
    }

    private fun prefs() = NotificationPrefsStore.get(AndroidCore.app.storage)

    private fun pair(name: String): String {
        val d = FakeDaemon(name).also(daemons::add)
        d.snapshot = snapshot(name)
        Core.paired(Pairing(d.base, "tok-$name", name, "m-$name", "dev-$name"))
        return Core.activeId!!
    }

    private fun linked() {
        val deadline = System.nanoTime() + 10_000_000_000
        while (Core.allHubs.isEmpty() || !Core.allHubs.all { it.link.state == LinkState.Connected && it.synced }) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("every server linked (${Core.statuses})")
            Thread.sleep(3)
        }
    }

    private fun isSwitch(name: String) = hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    private fun click(text: String, index: Int = 0) {
        rule.onAllNodesWithText(text)[index].performClick()
        rule.waitForIdle()
    }

    private fun flip(name: String) {
        rule.onNode(isSwitch(name)).performClick()
        rule.waitForIdle()
    }

    private fun defaults() {
        rule.setContent {
            Harness(spy) {
                GlassCard(Modifier.fillMaxWidth().padding(16.dp)) { Column(Modifier.padding(horizontal = 16.dp)) { NotificationDefaults() } }
            }
        }
        rule.waitForIdle()
    }

    private fun serverRow(id: String, name: String) {
        rule.setContent {
            Harness(spy) {
                GlassCard(Modifier.fillMaxWidth().padding(16.dp)) { Column(Modifier.padding(horizontal = 16.dp)) { ServerNotifyRow(id, name) } }
            }
        }
        rule.waitForIdle()
    }

    // ─── The defaults ───

    @Test fun theGroupsFoldUnderTheMasterAndAllSevenAreThere() {
        pair("z2")
        linked()
        defaults()
        rule.onNode(isSwitch(str(R.string.notifications))).assertIsOn()
        rule.onNodeWithText(str(R.string.nc_which_all)).assertExists()
        rule.onNodeWithText(str(R.string.nc_answers)).assertDoesNotExist()
        rule.snap("settings_notifications_folded")
        click(str(R.string.nc_which))
        val rows = listOf(R.string.nc_answers, R.string.nc_finished, R.string.nc_waiting, R.string.nc_errors, R.string.nc_limits, R.string.nc_automations, R.string.nc_updates).map(::str)
        for (name in rows) rule.onNode(isSwitch(name)).assertIsOn()
        rule.snap("settings_notifications_unfolded")
    }

    @Test fun aGroupOtherThanAnswersTurnsOffAtOnceAndTheFoldCountsIt() {
        pair("z2")
        linked()
        defaults()
        click(str(R.string.nc_which))
        flip(str(R.string.nc_finished))
        flip(str(R.string.nc_updates))
        assertTrue(spy.confirms.isEmpty())
        assertEquals(setOf(NotifyGroup.Finished, NotifyGroup.Updates), prefs().off)
        rule.onNode(isSwitch(str(R.string.nc_finished))).assertIsOff()
        rule.onNodeWithText(str(R.string.nc_which_some, 5, 7)).assertExists()
    }

    @Test fun needsYourAnswerAsksFirstAndNothingChangesUntilYes() {
        pair("z2")
        linked()
        defaults()
        click(str(R.string.nc_which))
        flip(str(R.string.nc_answers))
        val ask = spy.confirms.single()
        assertEquals(str(R.string.nc_ask_answers), ask.title)
        assertEquals(str(R.string.nc_ask_body), ask.body)
        assertTrue(ask.body, ask.body.contains("wait silently"))
        assertTrue(ask.danger)
        assertTrue("not yet", prefs().allows(Core.activeId, Notice.Kind.Approval))
        rule.onNode(isSwitch(str(R.string.nc_answers))).assertIsOn()
        ask.onConfirm()
        rule.waitForIdle()
        assertFalse(prefs().allows(Core.activeId, Notice.Kind.Question))
        rule.onNode(isSwitch(str(R.string.nc_answers))).assertIsOff()
        // Back on needs no asking.
        flip(str(R.string.nc_answers))
        assertEquals(1, spy.confirms.size)
        assertTrue(prefs().allows(Core.activeId, Notice.Kind.Plan))
    }

    @Test fun theMasterAsksFirstThenSaysWhatStillShowsAndHidesTheGroups() {
        pair("z2")
        linked()
        defaults()
        flip(str(R.string.notifications))
        val ask = spy.confirms.single()
        assertEquals(str(R.string.nc_ask_master), ask.title)
        assertTrue(prefs().master)
        ask.onConfirm()
        rule.waitForIdle()
        assertFalse(prefs().master)
        rule.onNode(isSwitch(str(R.string.notifications))).assertIsOff()
        rule.onNodeWithText(str(R.string.nc_master_off_desc), substring = true).assertExists()
        rule.onNodeWithText(str(R.string.nc_which)).assertDoesNotExist()
        rule.snap("settings_notifications_master_off")
        // Back on: no asking.
        flip(str(R.string.notifications))
        assertEquals(1, spy.confirms.size)
        assertTrue(prefs().master)
    }

    /** The confirm as App.kt shows it. */
    @Test fun theConfirmSaysItPlainly() {
        pair("z2")
        linked()
        rule.setContent {
            var shown by remember { mutableStateOf<Confirm?>(null) }
            Harness(spy) {
                CompositionLocalProvider(LocalConfirm provides { shown = it }) {
                    GlassCard(Modifier.fillMaxWidth().padding(16.dp)) { Column(Modifier.padding(horizontal = 16.dp)) { NotificationDefaults() } }
                }
                shown?.let { c -> ConfirmDialog(true, c.title, c.body, c.action, c.onConfirm, { shown = null }, danger = c.danger) }
            }
        }
        rule.waitForIdle()
        flip(str(R.string.notifications))
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText(str(R.string.nc_ask_body)).assertExists()
        rule.onNodeWithText(str(R.string.nc_ask_action)).assertExists()
        rule.onNode(isDialog()).snap("settings_notifications_confirm")
    }

    // ─── A server's own ───

    @Test fun aServerStartsSameAsAllAndCustomShowsItsOwnSwitches() {
        val id = pair("z2")
        linked()
        serverRow(id, "z2")
        assertEquals(ServerNotify.Same, prefs().modeOf(id))
        rule.onNodeWithText(str(R.string.nc_server_same_desc)).assertExists()
        rule.onNodeWithText(str(R.string.nc_answers)).assertDoesNotExist()
        rule.snap("settings_server_notifications_same")
        click(str(R.string.nc_custom))
        assertTrue("Custom alone silences nothing", spy.confirms.isEmpty())
        assertEquals(ServerNotify.Custom, prefs().modeOf(id))
        rule.onNode(isSwitch(str(R.string.nc_answers))).assertIsOn()
        flip(str(R.string.nc_finished))
        assertTrue(spy.confirms.isEmpty())
        assertFalse(prefs().allows(id, Notice.Kind.Finished))
        assertTrue("the defaults aren't touched", prefs().off.isEmpty())
        rule.onNode(isSwitch(str(R.string.nc_finished))).assertIsOff()
        rule.snap("settings_server_notifications_custom")
    }

    @Test fun aServerCustomSetAsksBeforeNeedsYourAnswerGoesNamingTheServer() {
        val id = pair("z2")
        linked()
        serverRow(id, "z2")
        click(str(R.string.nc_custom))
        flip(str(R.string.nc_answers))
        val ask = spy.confirms.single()
        assertEquals(str(R.string.nc_ask_answers_server, "z2"), ask.title)
        assertTrue(prefs().allows(id, Notice.Kind.Approval))
        ask.onConfirm()
        rule.waitForIdle()
        assertFalse(prefs().allows(id, Notice.Kind.Approval))
    }

    @Test fun aServerOffAsksFirstNamingTheServer() {
        val id = pair("z2")
        linked()
        serverRow(id, "z2")
        click(str(R.string.nc_off))
        val ask = spy.confirms.single()
        assertEquals(str(R.string.nc_ask_server, "z2"), ask.title)
        assertEquals(str(R.string.nc_ask_body), ask.body)
        assertEquals("not yet", ServerNotify.Same, prefs().modeOf(id))
        ask.onConfirm()
        rule.waitForIdle()
        assertEquals(ServerNotify.Off, prefs().modeOf(id))
        assertFalse(prefs().allows(id, Notice.Kind.Finished))
        rule.onNodeWithText(str(R.string.nc_server_off_desc)).assertExists()
        rule.snap("settings_server_notifications_off")
        // Same as all again: nothing to ask.
        click(str(R.string.nc_same))
        assertEquals(1, spy.confirms.size)
        assertTrue(prefs().allows(id, Notice.Kind.Finished))
    }

    @Test fun withTheMasterOffAServerSaysSo() {
        val id = pair("z2")
        linked()
        NotificationPrefsStore.set(AndroidCore.app.storage, prefs().copy(master = false))
        serverRow(id, "z2")
        rule.onNodeWithText(str(R.string.nc_server_master_off)).assertExists()
    }

    // ─── In the tab ───

    @Config(qualifiers = "+h5200dp")
    @Test fun inAllServersEachBlocksRowIsThatServersAndThePermissionsAskStays() {
        val z2 = pair("z2")
        val shed = pair("shed")
        linked()
        Core.switchTo(z2)
        Core.showAll()
        val vm = HubViewModel(AndroidCore.app)
        rule.setContent { Harness(spy) { SettingsTab(vm, top = 0.dp) } }
        rule.waitForIdle()
        // A row per server, and one master in Notifications.
        assertEquals(2, rule.onAllNodesWithText(str(R.string.nc_same)).fetchSemanticsNodes().size)
        rule.onNode(isSwitch(str(R.string.notifications))).assertExists()
        // Robolectric's phone hasn't granted the permission: the existing ask is there.
        rule.onNodeWithText(str(R.string.notif_allow)).performScrollTo().assertExists()
        rule.onAllNodesWithText(str(R.string.nc_custom))[1].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(ServerNotify.Custom, prefs().modeOf(shed))
        assertEquals(ServerNotify.Same, prefs().modeOf(z2))
        rule.onNode(isSwitch(str(R.string.nc_finished))).performScrollTo().performClick()
        rule.waitForIdle()
        assertFalse(prefs().allows(shed, Notice.Kind.Finished))
        assertTrue(prefs().allows(z2, Notice.Kind.Finished))
        // Off in z2's block names z2.
        rule.onAllNodesWithText(str(R.string.nc_off))[0].performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(str(R.string.nc_ask_server, "z2"), spy.confirms.single().title)
    }

    @Config(qualifiers = "+h3300dp")
    @Test fun aServerThatCantBeReachedKeepsItsRow() {
        val id = pair("z2")
        linked()
        daemons.single().down = true
        daemons.single().closeSocket()
        val deadline = System.nanoTime() + 10_000_000_000
        while (Core.statuses.single().state == LinkState.Connected) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("z2 away")
            Thread.sleep(3)
        }
        val vm = HubViewModel(AndroidCore.app)
        rule.setContent { Harness(spy) { SettingsTab(vm, top = 0.dp) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.nc_off)).performScrollTo().performClick()
        rule.waitForIdle()
        spy.confirms.single().onConfirm()
        rule.waitForIdle()
        assertEquals(ServerNotify.Off, prefs().modeOf(id))
    }
}
