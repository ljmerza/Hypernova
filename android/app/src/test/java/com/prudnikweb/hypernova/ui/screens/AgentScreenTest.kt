package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentBoard
import com.prudnikweb.hypernova.data.AgentStarting
import com.prudnikweb.hypernova.data.HubException
import com.prudnikweb.hypernova.data.WAgent
import com.prudnikweb.hypernova.data.WEvent
import com.prudnikweb.hypernova.ui.session.AgentScreen
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * One agent's own screen (ui/session/AgentScreen.kt): its transcript as the hub has it, asked for
 * again every 1.5 s while it runs (on the compose clock), and once more as it ends; "Starting…"
 * while the Mac has no transcript yet; an error with Try again.
 */
@RunWith(RobolectricTestRunner::class)
class AgentScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private var backs = 0

    private companion object {
        const val AGENT = "a0123456789abcdef"
    }

    @Before fun setUp() = resetPrefs()

    private fun agent(status: String, endedAfterMin: Long? = null) = WAgent(
        id = AGENT, sessionId = Samples.BUILD, toolUseId = "toolu_agent", parentAgentId = null, workflowId = null, phase = null,
        label = "Review the parser", type = "Explore", model = "claude-sonnet-5-5", background = true, status = status,
        activity = "Read: parser.ts", startedAt = Samples.NOW - 3 * 60_000L, endedAt = endedAfterMin?.let { Samples.NOW - 3 * 60_000L + it * 60_000L },
        lastActivityAt = Samples.NOW, tokens = 20_100L, toolUses = 3, result = null,
    )

    private fun hub(a: WAgent) = FeatureHub().also { it.board = AgentBoard(mapOf(AGENT to a), emptyMap()) }

    private fun screen(hub: FeatureHub) {
        rule.setContent { Harness(spy) { AgentScreen(hub, Samples.BUILD, AGENT, onBack = { backs++ }) } }
        rule.waitForIdle()
    }

    private val events: List<WEvent> get() = ScreenFixtures.events("rest-agent-events")
    private fun asks(hub: FeatureHub) = hub.calls.count { it.startsWith("agentEvents") }

    @Test fun anEndedAgentsTranscriptIsReadOnceAndDrawn() {
        val hub = hub(agent("done", endedAfterMin = 2))
        hub.agentAnswers += Result.success(events)
        screen(hub)
        rule.onNodeWithText("Review the parser").assertExists()
        rule.onNodeWithText(str(R.string.agent_task).uppercase()).assertExists()
        rule.mainClock.advanceTimeBy(5_000)
        rule.waitForIdle()
        assertEquals("ended: nothing more to ask", 1, asks(hub))
        rule.snap("agent_done")
    }

    @Test fun whileItRunsItIsAskedAgainEveryOneAndAHalfSecondsAndOnceMoreWhenItEnds() {
        val hub = hub(agent("running"))
        hub.agentAnswers += Result.success(events)
        rule.mainClock.autoAdvance = false
        screen(hub)
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val first = asks(hub)
        assertEquals(1, first)
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        assertEquals(2, asks(hub))
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        assertEquals(3, asks(hub))
        // It ends (the socket says): one more ask, then it rests.
        hub.board = AgentBoard(mapOf(AGENT to agent("done", endedAfterMin = 2)), emptyMap())
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        val atEnd = asks(hub)
        rule.mainClock.advanceTimeBy(6_000)
        rule.waitForIdle()
        assertEquals("resting once it has ended", atEnd, asks(hub))
    }

    @Test fun justStartedSaysStartingAndAsksAgainInAMoment() {
        val hub = hub(agent("running"))
        hub.agentAnswers += Result.failure(AgentStarting())
        hub.agentAnswers += Result.success(events)
        rule.mainClock.autoAdvance = false
        screen(hub)
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.agent_starting), substring = true).assertExists()
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.agent_starting), substring = true).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.agent_task).uppercase()).assertExists()
    }

    @Test fun anErrorWithNothingShownHasTryAgain() {
        val hub = hub(agent("done", endedAfterMin = 2))
        hub.agentAnswers += Result.failure(HubException("That agent isn’t in this session any more."))
        hub.agentAnswers += Result.success(events)
        screen(hub)
        rule.onNodeWithText("That agent isn’t in this session any more.").assertExists()
        rule.onNodeWithText(str(R.string.try_again)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.agent_task).uppercase()).assertExists()
        assertEquals(2, asks(hub))
    }

    @Test fun backLeaves() {
        val hub = hub(agent("done", endedAfterMin = 2))
        screen(hub)
        rule.onNode(androidx.compose.ui.test.hasContentDescription(str(R.string.back))).performClick()
        assertEquals(1, backs)
    }
}
