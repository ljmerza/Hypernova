@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.prudnikweb.hypernova.ui.screens

import android.app.KeyguardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.BlockedCall
import com.prudnikweb.hypernova.data.AlwaysScope
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.ModelSwitch
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import com.prudnikweb.hypernova.ui.session.SessionScreen
import com.prudnikweb.hypernova.ui.session.SessionSheet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The session sheet's pickers and the owner's check (FEATURES S2, S4, A2): the model picker driven
 * from the sheet, Auto and Bypass asked about twice, and the fingerprint or screen lock before
 * Bypass and before an Always rule is saved (OwnerCheck, its system prompt stood in for).
 */
@RunWith(RobolectricTestRunner::class)
class SheetMoreTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    private class SheetHub(val base: FakeHub) : Hub by base {
        var calls = listOf(
            BlockedCall("b1", Samples.ZETA, "Bash", "git push origin main", BlockedCall.AUTO_MODE, "Pushes to the default branch", Samples.NOW - 60_000),
            BlockedCall("b2", Samples.ZETA, "WebFetch", "https://example.com/spec", BlockedCall.AUTO_MODE, null, Samples.NOW - 120_000),
        )
        override suspend fun blocked(sessionId: String): Result<List<BlockedCall>> = Result.success(calls)
        override suspend fun retryBlocked(sessionId: String, id: String): Result<List<BlockedCall>> {
            base.calls += "retryBlocked($sessionId, $id)"
            calls = calls.filter { it.id != id }
            return Result.success(calls)
        }
        override suspend fun dismissBlocked(sessionId: String, id: String): Result<List<BlockedCall>> {
            base.calls += "dismissBlocked($sessionId, $id)"
            calls = calls.filter { it.id != id }
            return Result.success(calls)
        }
        override suspend fun setModel(sessionId: String, model: String?, confirmPaid: Boolean): Result<ModelSwitch> {
            base.calls += "setModel($sessionId, $model)"
            return Result.success(ModelSwitch(model, nextTurn = false))
        }
    }

    @Before fun setUp() = resetPrefs()

    @After fun tearDown() {
        OwnerCheck.prompt = null
    }

    /** The phone has a screen lock (or not); a check that [passes] or is cancelled. */
    private fun lock(secure: Boolean, passes: Boolean = true) {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(secure)
        OwnerCheck.prompt = { ok, fail -> if (passes) ok() else fail(null) }
    }

    private fun sheet(hub: Hub, id: String) {
        val s = hub.session(id)!!
        rule.setContent { Harness(spy) { SessionSheet(hub, s, usage = null, onModel = {}, onSwitchAgent = {}, onRetried = {}, onDismiss = {}) } }
        rule.waitForIdle()
        rule.onNode(hasText(str(R.string.session_sheet_permissions)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun mode(label: Int) {
        rule.onNodeWithText(str(label), useUnmergedTree = true).performClick()
        rule.waitForIdle()
    }

    // ─── S4: Auto and Bypass, asked about twice ───

    @Test fun autoAsksToThinkTwiceFirst() {
        val hub = FakeHub()
        sheet(hub, Samples.ZETA)
        mode(R.string.mode_auto)
        rule.onNodeWithText(str(R.string.think_use_auto)).assertExists()
        // No picture: the card tells a story picked at random (Incidents.next).
        // Keep asking: nothing changes.
        rule.onNodeWithText(str(R.string.think_keep)).performClick()
        rule.waitForIdle()
        assertTrue(hub.calls.isEmpty())
        mode(R.string.mode_auto)
        rule.onNodeWithText(str(R.string.think_use_auto)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("setMode(${Samples.ZETA}, auto)"), hub.calls)
    }

    @Test fun bypassAsksTwiceThenForTheOwner() {
        val hub = FakeHub()
        sheet(hub, Samples.ZETA)
        // No screen lock: there's nothing to check, so Bypass doesn't go.
        lock(secure = false)
        mode(R.string.mode_bypass)
        rule.onNodeWithText(str(R.string.think_use_bypass)).performClick()
        rule.waitForIdle()
        assertTrue(hub.calls.isEmpty())
        assertEquals(1, spy.toasts.size)
        // The check cancelled: nothing, and nothing said.
        lock(secure = true, passes = false)
        mode(R.string.mode_bypass)
        rule.onNodeWithText(str(R.string.think_use_bypass)).performClick()
        rule.waitForIdle()
        assertTrue(hub.calls.isEmpty())
        assertEquals(1, spy.toasts.size)
        // The owner's fingerprint: Bypass.
        lock(secure = true)
        mode(R.string.mode_bypass)
        rule.onNodeWithText(str(R.string.think_use_bypass)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("setMode(${Samples.ZETA}, bypassPermissions)"), hub.calls)
    }

    @Test fun planNeedsNoSecondThought() {
        val hub = FakeHub()
        sheet(hub, Samples.ZETA)
        mode(R.string.mode_accept)
        rule.onNodeWithText(str(R.string.think_keep)).assertDoesNotExist()
        assertEquals(listOf("setMode(${Samples.ZETA}, acceptEdits)"), hub.calls)
    }

    // ─── A2: an Always rule is saved only after the owner's check ───

    @Test fun anAlwaysRuleIsSavedOnlyAfterTheOwnersCheck() {
        val hub = FakeHub()
        rule.setContent { Harness(spy) { SessionScreen(hub, Samples.BUILD, onBack = {}, usageOf = { _, _ -> null }) } }
        rule.waitForIdle()
        fun save() {
            rule.onNodeWithText(str(R.string.always)).performClick()
            rule.waitForIdle()
            rule.onNodeWithText(str(R.string.always_save)).performClick()
            rule.waitForIdle()
        }
        lock(secure = true, passes = false)
        save()
        assertTrue("cancelled: not answered", hub.calls.isEmpty())
        rule.onNodeWithText(str(R.string.cancel)).performClick()
        rule.waitForIdle()
        lock(secure = true)
        save()
        assertEquals(listOf("answer(${Samples.BUILD}, ap-test, ${Answer.AlwaysAs(AlwaysScope.Project, listOf("Bash(npm test *)"))})"), hub.calls)
    }

    // ─── S2: the model picker, from the sheet ───

    @Config(qualifiers = "+h1600dp")
    @Test fun theSheetsModelOpensThePickerAndSwitchesTheModel() {
        val base = FakeHub()
        val hub = SheetHub(base)
        val usage = com.prudnikweb.hypernova.ui.session.SessionUsage("claude-opus-5-5", "Opus 5.5", 84_000, 200_000, 42, true, 12_000, 3_400, 60_000, 8_000, 0, null, false)
        rule.setContent { Harness(spy) { SessionScreen(hub, Samples.ZETA, onBack = {}, usageOf = { _, _ -> usage }) } }
        rule.waitForIdle()
        rule.onNode(hasContentDescription(str(R.string.usage_name_seven), substring = true)).performClick()
        rule.waitForIdle()
        rule.onNode(hasClickLabel(str(R.string.model_change))).performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasText("Sonnet", substring = true) or hasContentDescription("Sonnet", substring = true)).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(listOf("setModel(${Samples.ZETA}, sonnet)"), base.calls.filter { it.startsWith("setModel") })
    }

    // ─── S5: blocked calls in the sheet ───

    @Config(qualifiers = "+h1600dp")
    @Test fun blockedCallsAreAllowedAndRetriedOrDismissedFromTheSheet() {
        val base = FakeHub(sessions = Samples.sessions().map { if (it.id == Samples.ZETA) it.copy(blockedCalls = 2) else it })
        val hub = SheetHub(base)
        var retried = 0
        var emptied = 0
        val s = hub.session(Samples.ZETA)!!
        rule.setContent { Harness(spy) { SessionSheet(hub, s, usage = null, onModel = {}, onSwitchAgent = {}, onRetried = { retried++ }, onEmptied = { emptied++ }, onDismiss = {}, atBlocked = true) } }
        rule.waitForIdle()
        rule.onNodeWithText("git push origin main", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Pushes to the default branch", substring = true, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText(str(R.string.blocked_retry)).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(listOf("retryBlocked(${Samples.ZETA}, b1)"), base.calls)
        assertEquals(1, retried)
        rule.onNodeWithText("git push origin main", substring = true, useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.blocked_dismiss)).performClick()
        rule.waitForIdle()
        assertEquals("dismissBlocked(${Samples.ZETA}, b2)", base.calls.last())
        assertEquals(1, emptied)
    }
}
