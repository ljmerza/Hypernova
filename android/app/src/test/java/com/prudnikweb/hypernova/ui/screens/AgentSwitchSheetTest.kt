package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.SwitchPreview
import com.prudnikweb.hypernova.data.SwitchRefused
import com.prudnikweb.hypernova.ui.session.AgentSwitchSheet
import com.prudnikweb.hypernova.ui.session.CodexModelPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Switching agents (ui/session/AgentSwitchSheet.kt): the preview asked for as the sheet opens and
 * again for Entire and None, the Switch with how the other agent starts, a session at work stopped
 * first, and the Mac's refusals; and a Codex session's model picker.
 */
@RunWith(RobolectricTestRunner::class)
class AgentSwitchSheetTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val switched = mutableListOf<String>()
    private var dismissed = 0

    @Before fun setUp() = resetPrefs()

    private fun sheet(hub: FeatureHub, sessionId: String = Samples.MID, to: String = AgentKind.CODEX) {
        rule.setContent {
            Harness(spy) {
                val s = hub.session(sessionId)!!
                AgentSwitchSheet(hub, s, to, onSwitched = { switched += it }, onDismiss = { dismissed++ })
            }
        }
        rule.waitForIdle()
    }

    private fun switchPill() = rule.onAllNodesWithText(str(R.string.cx_switch)).onFirst()

    @Test fun theRecentPartIsReadAsItOpensAndSwitchSendsItWithCodexsDefaults() {
        val hub = FeatureHub()
        sheet(hub)
        assertEquals(listOf("switchPreview(${Samples.MID}, codex, recent)"), hub.calls)
        rule.onNode(hasText("User: fix the parser\nClaude: Trimmed its input.")).assertExists()
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.cx_messages, 2, 2)).assertExists()
        rule.snap("switch_to_codex")
        switchPill().performClick()
        rule.waitForIdle()
        assertEquals("switchAgent(${Samples.MID}, codex, 47, null, null, read-only)", hub.calls.last())
        assertEquals(listOf("s-new"), switched)
    }

    @Test fun entireAndNoneAreAskedForAgain() {
        val hub = FeatureHub()
        sheet(hub)
        rule.onNodeWithText(str(R.string.cx_carry_all)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.cx_carry_none)).performClick()
        rule.waitForIdle()
        assertEquals(
            listOf("switchPreview(${Samples.MID}, codex, recent)", "switchPreview(${Samples.MID}, codex, all)", "switchPreview(${Samples.MID}, codex, none)"),
            hub.calls,
        )
    }

    @Test fun moreThanTheMacTakesIsSaidAndSwitchWaits() {
        val hub = FeatureHub()
        hub.previews = { Result.success(SwitchPreview("x".repeat(2_048), 40, 2_048, 1_024)) }
        sheet(hub)
        rule.onNodeWithText(str(R.string.cx_too_long, "1.0 KB")).assertExists()
        switchPill().assertIsNotEnabled()
    }

    @Test fun aRefusalIsSaidUnderTheFieldAndOneThatWentOnOpensThatSession() {
        val hub = FeatureHub()
        hub.switchAnswer = Result.failure(SwitchRefused("That’s more than 96 KB.", "context_too_large", maxBytes = 98_304))
        sheet(hub)
        switchPill().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.cx_too_long, "96 KB")).assertExists()
        assertTrue(switched.isEmpty())

        hub.switchAnswer = Result.failure(SwitchRefused("It went on already.", "switched", to = "s-there"))
        switchPill().performClick()
        rule.waitForIdle()
        assertEquals(listOf("s-there"), switched)
    }

    @Test fun aSessionAtWorkIsStoppedFirstThenSwitched() {
        val hub = FeatureHub()
        sheet(hub, sessionId = Samples.PARSER)
        rule.onNodeWithText(str(R.string.cx_stop_first, str(R.string.claude)), substring = true).assertExists()
        rule.onAllNodesWithText(str(R.string.cx_stop_and_switch)).onFirst().performClick()
        rule.waitForIdle()
        assertEquals("interrupt(${Samples.PARSER})", hub.calls.last())
        assertTrue("not before it has stopped", hub.calls.none { it.startsWith("switchAgent") })
        // It rests (the live link says so): the switch goes.
        hub.base.sessionList = hub.base.sessionList.map { if (it.id == Samples.PARSER) it.copy(state = SessionState.Idle, turnStartedAt = 0L) else it }
        rule.waitForIdle()
        rule.waitUntil(3_000) { switched.isNotEmpty() }
        assertTrue(hub.calls.last().startsWith("switchAgent(${Samples.PARSER}, codex"))
    }

    @Test fun aCodexSessionGoesToClaudeWithClaudesMode() {
        val hub = FeatureHub()
        hub.base.sessionList = hub.base.sessionList.map { if (it.id == Samples.MID) it.copy(agent = AgentKind.CODEX, mode = AgentKind.READ_ONLY, model = "gpt-test") else it }
        sheet(hub, to = AgentKind.CLAUDE)
        assertEquals("switchPreview(${Samples.MID}, claude, recent)", hub.calls.single())
        switchPill().performClick()
        rule.waitForIdle()
        assertEquals("switchAgent(${Samples.MID}, claude, 47, null, null, default)", hub.calls.last())
    }

    @Test fun aCodexSessionsModelPickerSwitchesItsModelAndEffort() {
        val hub = FeatureHub()
        hub.base.sessionList = hub.base.sessionList.map { if (it.id == Samples.MID) it.copy(agent = AgentKind.CODEX, mode = AgentKind.READ_ONLY, model = "gpt-test") else it }
        rule.setContent { Harness(spy) { CodexModelPicker(hub, hub.session(Samples.MID)!!, onDismiss = { dismissed++ }) } }
        rule.waitForIdle()
        rule.onNode(saidStartsWith(str(R.string.ns_row_said, "GPT-Other"))).performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText(str(R.string.cx_done)).onFirst().performClick()
        rule.waitForIdle()
        // Its one effort, so none is chosen: the model's own.
        assertEquals("setCodexModel(${Samples.MID}, gpt-other, null)", hub.calls.last())
        assertEquals(1, dismissed)
    }
}
