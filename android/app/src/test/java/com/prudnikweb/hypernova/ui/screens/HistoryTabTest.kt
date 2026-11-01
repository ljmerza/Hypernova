package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionAutomation
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.fx.formatDuration
import com.prudnikweb.hypernova.ui.home.HistoryTab
import com.prudnikweb.hypernova.ui.session.TimePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * History (FEATURES Y1): ended sessions under their day, an automation's runs folded into one row,
 * each opening what it is; and the session sheet's TIME part (S11), checked by its words, never
 * pictured (its Started is a wall-clock time).
 */
@RunWith(RobolectricTestRunner::class)
class HistoryTabTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    @Before fun setUp() = resetPrefs()

    private fun ended(id: String, name: String, agoMin: Long, automation: SessionAutomation? = null): Session =
        Samples.session(id, name, SessionState.Idle, agoMin).copy(phase = Phase.Ended, automation = automation)

    @Test fun endedSessionsByDayAndAnAutomationsRunsFolded() {
        val brief = SessionAutomation("au_1", "Morning brief", null)
        val hub = FakeHub(
            sessions = Samples.sessions() + listOf(
                ended("h-today", "Tidy the README", 1),
                ended("h-yday", "Port the plugin", 24 * 60),
                ended("h-run1", "Morning brief", 30, brief),
                ended("h-run2", "Morning brief", 24 * 60 + 5, brief),
            ),
        )
        val opened = mutableListOf<String>()
        val runs = mutableListOf<String>()
        rule.setContent { Harness(spy) { HistoryTab(hub, top = 0.dp, bottom = 0.dp, now = Samples.NOW, onOpen = { opened += it }, onRuns = { runs += it }) } }
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.history_today).uppercase()).assertExists()
        rule.onNodeWithText(str(R.string.history_yesterday).uppercase()).assertExists()
        // Only ended sessions: none of Home's live ones.
        rule.onNodeWithText("Fix the build").assertDoesNotExist()
        rule.onNodeWithText("Tidy the README").performClick()
        assertEquals(listOf("h-today"), opened)
        // The automation's two runs are one row, which opens its runs.
        rule.onNode(hasText(context.resources.getQuantityString(R.plurals.au_history_runs, 2, 2), substring = true)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("au_1"), runs)
        assertTrue(opened.size == 1)
    }

    @Test fun nothingEndedYetSaysSo() {
        rule.setContent { Harness(spy) { HistoryTab(FakeHub(), top = 0.dp, bottom = 0.dp, now = Samples.NOW, onOpen = {}, onRuns = {}) } }
        rule.onNodeWithText(str(R.string.history_empty)).assertExists()
    }

    @Test fun theSessionTabsTimeSaysHowLongItHasRunAndWhenItLastDidSomething() {
        val s = Samples.sessions().first { it.id == Samples.MID }
        rule.setContent { Harness(spy) { TimePart(s) } }
        rule.onNodeWithText(str(R.string.session_sheet_started)).assertExists()
        rule.onNodeWithText(str(R.string.session_sheet_running_for)).assertExists()
        rule.onNodeWithText(formatDuration(context.resources, 90 * 60_000L)).assertExists()
        rule.onNodeWithText(str(R.string.session_sheet_last_activity)).assertExists()
        rule.onNodeWithText(str(R.string.done_ago_minutes, 20)).assertExists()
    }
}
