package com.prudnikweb.hypernova.ui.screens

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentBoard
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.WAgent
import com.prudnikweb.hypernova.ui.session.SessionScreen
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The opened status bar closes when you leave its session (owner, 2026-11-01), however you come back,
 * and stays open while you haven't left. The screens sit in a state holder as they do in App.kt, which
 * is what used to bring it back open.
 */
@RunWith(RobolectricTestRunner::class)
class StatusPanelResetTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val spy = AppSpy()

    @Before fun setUp() = resetPrefs()

    private companion object {
        const val FIRST = "s-first"
        const val SECOND = "s-second"
        const val AWAY = "away"
    }

    private fun agent(session: String, n: Int) = WAgent(
        "a$n", session, null, null, null, null, "Survey part $n", "Explore", null, true, "running", "Read: src/part$n.kt",
        Samples.NOW, null, Samples.NOW, null, null, null,
    )

    private val hub = FeatureHub(
        FakeHub(
            sessions = listOf(FIRST, SECOND).map { Samples.session(it, "Turn $it", SessionState.Working, 0, activity = "Bash: npm test") },
            approvals = emptyMap(),
            transcripts = listOf(FIRST, SECOND).associateWith { listOf(Item.User("u", "A question")) },
        ),
    ).apply { board = AgentBoard((1..2).map { agent(FIRST, it) }.associateBy { it.id }, emptyMap()) }

    /** The route shown: a session's id, or [AWAY] for another screen (an agent's, Home). */
    private var route by mutableStateOf(FIRST)

    private val screen: @androidx.compose.runtime.Composable () -> Unit = {
        Harness(spy) {
            val screens = rememberSaveableStateHolder()
            val at = route
            screens.SaveableStateProvider(at) {
                if (at != AWAY) SessionScreen(hub, at, onBack = {}, usageOf = { _, _ -> null }, todosOf = { Result.success(emptyList()) })
            }
        }
    }

    private fun expand() = rule.onNode(hasClickLabel(str(R.string.status_expand_main)))
    private fun fold() = rule.onNode(hasClickLabel(str(R.string.status_fold_main)))
    private fun go(to: String) {
        route = to
        rule.waitForIdle()
    }

    private fun open() {
        expand().performClick()
        rule.waitForIdle()
        fold().assertExists()
    }

    @Test fun leavingForAnotherScreenAndComingBackFindsItClosed() {
        rule.setContent(screen)
        rule.waitForIdle()
        open()
        go(AWAY)
        go(FIRST)
        expand().assertExists()
        fold().assertDoesNotExist()
        // And it opens again as ever.
        open()
    }

    @Test fun goingToAnotherSessionAndBackFindsBothClosed() {
        rule.setContent(screen)
        rule.waitForIdle()
        open()
        go(SECOND)
        expand().assertExists()
        fold().assertDoesNotExist()
        go(FIRST)
        expand().assertExists()
        fold().assertDoesNotExist()
    }

    @Test fun whatTheSystemSavesDoesntBringItBackOpen() {
        // As after the process was killed: all that's left is what was saved.
        val restoration = StateRestorationTester(rule)
        restoration.setContent(screen)
        rule.waitForIdle()
        open()
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        expand().assertExists()
        fold().assertDoesNotExist()
    }

    @Test fun recreatingTheActivityForAConfigurationChangeKeepsItOpen() {
        rule.runOnUiThread { rule.activity.setContent(content = screen) }
        rule.waitForIdle()
        open()
        // A font size or a split screen: the app is held to portrait, so not a rotation (the manifest).
        rule.activityRule.scenario.recreate()
        rule.runOnUiThread { rule.activity.setContent(content = screen) }
        rule.waitForIdle()
        fold().assertExists()
    }
}
