package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TermLink
import com.prudnikweb.hypernova.ui.session.SessionScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Rewind to here (FEATURES C9; docs/PROTOCOL.md, "Rewind"), beyond ChatMoreTest's own: what the
 * confirm sheet says of what a session is at, and a screen on a session a rewind replaced.
 */
@RunWith(RobolectricTestRunner::class)
class RewindSheetTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    @Before fun setUp() {
        resetPrefs()
        // ChatRepo (Rewind) asks Core for the computer: none is paired here.
        Core.init(RuntimeEnvironment.getApplication())
    }

    private val conversation = listOf(
        Item.User("u1", "Sketch the lexer"),
        Item.Assistant("a1", "Here is a **sketch**."),
        Item.User("u2", "Now make it faster"),
        Item.Assistant("a2", "Done."),
    )

    private fun chat(hub: Hub, id: String) {
        rule.setContent { Harness(spy) { SessionScreen(hub, id, onBack = {}, usageOf = { _, _ -> null }) } }
        rule.waitForIdle()
    }

    private fun longPress(text: String) {
        rule.onAllNodes(hasAnyDescendant(hasText(text, substring = true)) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick), useUnmergedTree = true)
            .onLast().performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
    }

    private fun rewindSheetOf(s: Session) {
        chat(FakeHub(sessions = listOf(s), approvals = emptyMap(), transcripts = mapOf(s.id to conversation)), s.id)
        longPress("Now make it faster")
        rule.onNodeWithText(str(R.string.chat_rewind)).performClick()
        rule.waitForIdle()
    }

    @Test fun aRewindOfAWorkingSessionSaysItStopsIt() {
        rewindSheetOf(Samples.session("s-work", "At work", SessionState.Working, 0).copy(term = TermLink("t-work", mac = 0, phone = 0)))
        rule.onNodeWithText(str(R.string.chat_rewind_working)).assertExists()
        rule.onNodeWithText(str(R.string.chat_rewind_closes)).assertExists()
        rule.onNodeWithText(str(R.string.chat_rewind_files)).assertExists()
    }

    @Test fun aRewindOfASessionWaitingOnYouOrWithAgentsAtWorkSaysItStopsIt() {
        rewindSheetOf(Samples.session("s-ask", "Asking", SessionState.NeedsYou, 0).copy(phase = Phase.NeedsYou))
        rule.onNodeWithText(str(R.string.chat_rewind_waiting)).assertExists()
    }

    @Test fun aRewindWithItsAgentsStillAtWorkSaysItStopsThem() {
        rewindSheetOf(Samples.session("s-agents", "Agents", SessionState.Idle, 0).copy(agentsRunning = 2))
        rule.onNodeWithText(str(R.string.chat_rewind_working)).assertExists()
    }

    @Test fun aRewindOfASessionInATerminalNothingHoldsSaysItStays() {
        rewindSheetOf(Samples.session("s-out", "Out there", SessionState.Working, 0).copy(origin = "terminal", term = null, agentJob = null))
        rule.onNodeWithText(str(R.string.chat_rewind_stays)).assertExists()
        // "Rewinding stops it" would be untrue here.
        rule.onNodeWithText(str(R.string.chat_rewind_working)).assertDoesNotExist()
    }

    @Test fun anEndedSessionHasNothingToStop() {
        rewindSheetOf(Samples.session("s-over", "Over", SessionState.Idle, 0).copy(phase = Phase.Ended))
        rule.onNodeWithText(str(R.string.chat_rewind_working)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.chat_rewind_closes)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.chat_rewind_stays)).assertDoesNotExist()
    }

    @Test fun aScreenOnASessionARewindReplacedLandsOnTheNewOne() {
        val base = FakeHub(sessions = listOf(Samples.session("s-new", "Same title", SessionState.Idle, 0)), approvals = emptyMap())
        val hub = object : Hub by base {
            override fun replacedBy(id: String) = if (id == "s-old") "s-new" else null
        }
        chat(hub, "s-old")
        assertEquals(listOf("switch:s-new"), spy.opened)
    }

    @Test fun aScreenOnASessionThatIsSimplyGoneGoesBackWithoutAWord() {
        var back = 0
        rule.setContent { Harness(spy) { SessionScreen(FakeHub(sessions = emptyList(), approvals = emptyMap()), "s-gone", onBack = { back++ }, usageOf = { _, _ -> null }) } }
        rule.waitForIdle()
        assertEquals(1, back)
        assertTrue(spy.toasts.isEmpty())
        assertTrue(spy.opened.isEmpty())
    }
}
