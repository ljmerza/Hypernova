package com.prudnikweb.hypernova.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TurnNow
import com.prudnikweb.hypernova.ui.session.SessionScreen
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The folded bar's number is the turn's time, the server's, not how long this screen has shown it: the
 * owner's bar said 0:09 where his terminal said 8m 16s, having just opened the chat (2026-11-03).
 */
@RunWith(RobolectricTestRunner::class)
class StatusBarClockTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val spy = AppSpy()

    @Before fun setUp() = resetPrefs()

    private companion object {
        const val ID = "s-turn"
        const val AWAY = "away"
        const val EIGHT_16 = (8 * 60 + 16) * 1000L
    }

    private fun hub(turn: TurnNow? = null, started: Long = System.currentTimeMillis() - EIGHT_16, activity: String = "Bash: npm test") = FeatureHub(
        FakeHub(
            sessions = listOf(Samples.session(ID, "A long turn", SessionState.Working, 0, activity = activity).copy(turnStartedAt = started, turn = turn)),
            approvals = emptyMap(),
            transcripts = mapOf(ID to listOf(Item.User("u", "A question"))),
        ),
    )

    private var route by mutableStateOf(ID)

    private fun show(hub: FeatureHub = hub()) {
        rule.setContent {
            Harness(spy) {
                if (route == ID) SessionScreen(hub, ID, onBack = {}, usageOf = { _, _ -> null }, todosOf = { Result.success(emptyList()) })
            }
        }
        rule.waitForIdle()
    }

    private fun barSaysTheTurnsTime() {
        // A second or two may pass on the way here.
        val shown = rule.onAllNodes(hasText("8:16") or hasText("8:17") or hasText("8:18"), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the bar should say the turn's 8:16", shown.isNotEmpty())
    }

    @Test fun openingTheChatMidTurnShowsTheTurnsTimeNotZero() {
        show()
        barSaysTheTurnsTime()
    }

    @Test fun comingBackToTheChatDoesntRestartIt() {
        show()
        route = AWAY
        rule.waitForIdle()
        route = ID
        rule.waitForIdle()
        barSaysTheTurnsTime()
    }
}
