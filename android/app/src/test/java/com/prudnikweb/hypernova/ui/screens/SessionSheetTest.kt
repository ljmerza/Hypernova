package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentBoard
import com.prudnikweb.hypernova.data.WAgent
import com.prudnikweb.hypernova.ui.session.SessionSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The session sheet (ui/session/SessionSheet.kt): its tabs, and the Permissions tab's parts. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+h1400dp")
class SessionSheetTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private var dismissed = 0

    @Before fun setUp() = resetPrefs()

    private fun sheet(hub: FakeHub, id: String) {
        val s = hub.session(id)!!
        rule.setContent {
            Harness(spy) {
                SessionSheet(hub, s, usage = null, onModel = {}, onSwitchAgent = {}, onRetried = {}, onDismiss = { dismissed++ })
            }
        }
        rule.waitForIdle()
    }

    private fun tab(label: Int) {
        // The tabs scroll sideways on a narrow phone: the last ones come into view first.
        rule.onNode(hasText(str(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test fun opensOnOverviewAndEachTabSwitches() {
        sheet(FakeHub(), Samples.ZETA)
        // Overview: the model part, and the plan's windows (it runs on the subscription).
        rule.onNodeWithText(str(R.string.session_sheet_usage).uppercase(), useUnmergedTree = true).assertExists()
        rule.snap("sheet_overview")
        tab(R.string.session_sheet_permissions)
        rule.onNodeWithText(str(R.string.rules_seam).uppercase(), useUnmergedTree = true).assertExists()
        tab(R.string.session_sheet_agents)
        rule.onNodeWithText(str(R.string.session_sheet_no_agents)).assertExists()
        tab(R.string.session_sheet_tab_commands)
        rule.onNodeWithText("/compact", substring = true, useUnmergedTree = true).assertExists()
        rule.snap("sheet_commands")
        tab(R.string.session_sheet_title)
        rule.onNodeWithText(str(R.string.session_sheet_where).uppercase(), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("j-zeta", useUnmergedTree = true).assertExists()
        // No picture of this tab: its "Started" is a wall-clock time.
    }

    @Test fun anAgentWaitingOnItsBackgroundCommandIsAtWorkAndSaysSo() {
        val line = "Waiting for its background command"
        val hub = FeatureHub()
        hub.board = AgentBoard(
            mapOf(
                "a1" to WAgent(
                    id = "a1", sessionId = Samples.ZETA, toolUseId = null, parentAgentId = null, workflowId = null, phase = null, label = "Integrate the branches",
                    type = "general-purpose", model = null, background = true, status = "running", activity = line,
                    startedAt = Samples.NOW - 60_000L, endedAt = null, lastActivityAt = Samples.NOW - 60_000L, tokens = null, toolUses = null, result = null,
                ),
            ),
            emptyMap(),
        )
        val s = hub.base.session(Samples.ZETA)!!
        rule.setContent {
            Harness(spy) {
                SessionSheet(hub, s, usage = null, onModel = {}, onSwitchAgent = {}, onRetried = {}, onDismiss = { dismissed++ })
            }
        }
        rule.waitForIdle()
        tab(R.string.session_sheet_agents)
        rule.onNodeWithText(line, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Integrate the branches", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(str(R.string.session_sheet_no_agents)).assertDoesNotExist()
    }

    @Test fun closeDismisses() {
        sheet(FakeHub(), Samples.ZETA)
        rule.onNodeWithText(str(R.string.chat_close)).performClick()
        assertEquals(1, dismissed)
    }

    @Test fun rulesInEffectFoldOpen() {
        sheet(FakeHub(), Samples.ZETA)
        tab(R.string.session_sheet_permissions)
        val title = str(R.string.rules_seam)
        // Folded, the seam says how many of each (1 deny, 1 ask, 3 allow across project and user).
        rule.onNode(hasContentDescription(str(R.string.rules_said, title, 1, 1, 3))).assertExists()
        rule.onNode(hasClickLabel(str(R.string.rules_open))).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.rules_precedence)).assertExists()
        rule.snap("sheet_rules_open")
    }

    @Test fun allowTheseNAllowsEveryWaitingAskAfterAsking() {
        val hub = FakeHub()
        sheet(hub, Samples.BUILD)
        tab(R.string.session_sheet_permissions)
        rule.onNodeWithText(str(R.string.several_seam).uppercase()).assertExists()
        rule.onNodeWithText("npm run lint", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Lint fixer", useUnmergedTree = true).assertExists()
        // Why each asked, under its command (two waiting: no separate part).
        rule.onNodeWithText("overrides auto mode", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("No allow rule matches", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText(str(R.string.why_seam).uppercase()).assertDoesNotExist()
        rule.snap("sheet_allow_these")
        val allow = context.resources.getQuantityString(R.plurals.several_allow, 2, 2)
        rule.onNodeWithText(allow).performClick()
        rule.waitForIdle()
        assertTrue("asks first", hub.calls.isEmpty())
        // The dialog's own button is the last one so named.
        rule.onAllNodesWithText(allow).onLast().performClick()
        rule.waitForIdle()
        assertEquals(listOf("allowSeveral(${Samples.BUILD}, [ap-test, ap-lint])"), hub.calls)
    }

    @Test fun whyItAskedForTheTerminalDialog() {
        sheet(FakeHub(), Samples.DEPLOY)
        tab(R.string.session_sheet_permissions)
        rule.onNodeWithText(str(R.string.why_seam).uppercase()).assertExists()
        rule.onNodeWithText("kubectl get pods -n prod", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Ask rule Bash(kubectl:*) overrides auto mode", substring = true, useUnmergedTree = true).assertExists()
        // One ask: no "Allow these".
        rule.onNodeWithText(str(R.string.several_seam).uppercase()).assertDoesNotExist()
        rule.snap("sheet_why_asked")
    }

    @Test fun modePickerChangesTheMode() {
        val hub = FakeHub()
        sheet(hub, Samples.ZETA)
        tab(R.string.session_sheet_permissions)
        rule.onNodeWithText(str(R.string.mode_plan), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("setMode(${Samples.ZETA}, plan)"), hub.calls)
    }
}
