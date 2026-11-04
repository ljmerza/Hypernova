package com.prudnikweb.hypernova.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TurnNow
import com.prudnikweb.hypernova.data.TurnStep
import com.prudnikweb.hypernova.ui.session.SessionScreen
import org.junit.Assert.assertEquals
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

    // ─── The panel ───

    private val key = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz0123456789"
    private val longCommand = "cd ~/projects/_worktrees/status-details/daemon/test/fixtures/spinner-screens && ANTHROPIC_API_KEY=$key gh run watch 123456789 --exit-status --interval 5 --repo owner/repository"

    private fun rich(): TurnNow = TurnNow(tokens = 2900, step = TurnStep("Bash", longCommand, System.currentTimeMillis() - 133_000, 6))

    private fun expand() = rule.onNode(hasClickLabel(str(R.string.status_expand_details)))
    private fun fold() = rule.onNode(hasClickLabel(str(R.string.status_fold_details)))
    private fun text(s: String) = rule.onAllNodes(hasText(s, substring = true), useUnmergedTree = true).fetchSemanticsNodes()

    @Test fun foldedItsTheSameHeightAndSaysNothingNew() {
        show(hub(rich()))
        val folded = expand().fetchSemanticsNode().size.height
        // Its 48dp row, whatever the turn knows: nothing of the details on the way.
        assertEquals(with(rule.density) { 48.dp.roundToPx() }, folded)
        for (word in listOf(str(R.string.status_detail_turn), str(R.string.status_detail_tokens), str(R.string.status_detail_step), str(R.string.status_detail_at_once))) {
            assertTrue("$word shows folded", text(word).isEmpty())
        }
        assertTrue("the whole command shows folded", text("gh run watch 123456789").isEmpty())
    }

    @Test fun itOpensToTheDetailsAndFolds() {
        show(hub(rich()))
        expand().performClick()
        rule.waitForIdle()
        fold().assertExists()
        for (word in listOf(str(R.string.status_detail_turn), str(R.string.status_detail_tokens), str(R.string.status_detail_step), str(R.string.status_detail_at_once))) {
            assertTrue("$word shows opened", text(word).isNotEmpty())
        }
        assertTrue("the turn's time as the terminal writes it", text("8m 1").isNotEmpty())
        assertTrue("the tokens as the terminal writes them", text("↓ 2.9k tokens").isNotEmpty())
        assertTrue("the step's time", text("2m 1").isNotEmpty())
        assertTrue("how many at once", text("6 commands").isNotEmpty())
        fold().performClick()
        rule.waitForIdle()
        expand().assertExists()
        assertTrue(text(str(R.string.status_detail_turn)).isEmpty())
        assertEquals(with(rule.density) { 48.dp.roundToPx() }, expand().fetchSemanticsNode().size.height)
    }

    @Test fun theWholeCommandWrapsAndIsMasked() {
        show(hub(rich()))
        expand().performClick()
        rule.waitForIdle()
        val shown = text("gh run watch 123456789 --exit-status --interval 5 --repo owner/repository")
        assertEquals("the command's own row, all of it", 1, shown.size)
        val line = shown.single()
        assertTrue("it wraps: taller than one line", line.size.height > with(rule.density) { 30.dp.roundToPx() })
        assertTrue("its key is masked", text(key).isEmpty())
        assertTrue(text("•••").isNotEmpty())
    }

    @Test fun rowsItDoesntKnowArentThere() {
        // An older Mac: its activity line and the turn's start.
        show(hub(null))
        expand().performClick()
        rule.waitForIdle()
        assertTrue(text("$ npm test").isNotEmpty())
        assertTrue(text(str(R.string.status_detail_turn)).isNotEmpty())
        for (word in listOf(str(R.string.status_detail_tokens), str(R.string.status_detail_step), str(R.string.status_detail_at_once))) {
            assertTrue("$word with nothing to say", text(word).isEmpty())
        }
    }

    @Test fun withNothingToSayItDoesntOpen() {
        show(hub(null, started = 0L, activity = "Thinking"))
        expand().assertDoesNotExist()
    }
}
