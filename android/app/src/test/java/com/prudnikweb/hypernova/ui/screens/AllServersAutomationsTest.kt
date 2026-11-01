package com.prudnikweb.hypernova.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.ui.fx.CosmosState
import com.prudnikweb.hypernova.ui.home.Everywhere
import com.prudnikweb.hypernova.ui.home.HomeScreen
import com.prudnikweb.hypernova.ui.home.ServerChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Automations stay one server's under "All servers" (owner, 2026-10-31): the tab isn't opened
 * there, a toast says to pick a server, and choosing All servers while on Automations brings
 * Sessions back. With one server picked the tab opens as ever.
 */
@RunWith(RobolectricTestRunner::class)
class AllServersAutomationsTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    @Before fun setUp() = resetPrefs()

    private val z2 = FakeHub(sessions = listOf(Samples.session(Samples.BUILD, "Fix the build", com.prudnikweb.hypernova.data.SessionState.Working, 1)))
    private val everywhere = Everywhere(
        listOf(
            ServerStatus("z2", "z2", LinkState.Connected, needsYou = 0, active = true) to z2,
            ServerStatus("gym", "gym", LinkState.Connected, needsYou = 0, active = false) to FakeHub(sessions = emptyList(), approvals = emptyMap()),
        ),
        pickedName = "z2", onOpen = { _, _ -> }, onNewSession = {}, onRuns = { _, _ -> },
    )
    private var all by mutableStateOf(false)

    private fun home() {
        rule.setContent {
            Harness(spy) {
                HomeScreen(
                    z2, CosmosState(), mutableFloatStateOf(1f), filter = null, onFilter = {}, quiet = emptyList(),
                    onOpen = {}, onNewSession = {}, settings = {},
                    servers = ServerChoice(everywhere.servers.map { it.first }, {}, {}, all = all),
                    everywhere = everywhere.takeIf { all },
                )
            }
        }
        rule.waitForIdle()
    }

    private fun tab(label: Int) = rule.onAllNodes(hasText(str(label), ignoreCase = true), useUnmergedTree = true).onFirst()

    /** The Sessions tab's own content is on screen. */
    private fun onSessions() = rule.onAllNodesWithText("Fix the build").fetchSemanticsNodes().isNotEmpty()

    @Test fun underAllServersAutomationsAsksForOneServer() {
        all = true
        home()
        tab(R.string.au_tab).performClick()
        rule.waitForIdle()
        assertEquals(listOf(str(R.string.all_servers_no_automations)), spy.toasts)
        assertTrue("still on Sessions", onSessions())
    }

    @Test fun withOneServerPickedAutomationsOpens() {
        home()
        tab(R.string.au_tab).performClick()
        rule.waitForIdle()
        assertTrue(spy.toasts.isEmpty())
        assertTrue("Sessions gave way", !onSessions())
    }

    @Test fun choosingAllServersOnAutomationsBringsSessions() {
        home()
        tab(R.string.au_tab).performClick()
        rule.waitForIdle()
        assertTrue(!onSessions())
        all = true
        rule.waitForIdle()
        assertTrue("Sessions again", onSessions())
    }
}
