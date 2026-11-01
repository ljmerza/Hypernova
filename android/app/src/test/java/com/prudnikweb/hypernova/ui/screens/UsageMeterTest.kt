package com.prudnikweb.hypernova.ui.screens

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Usage
import com.prudnikweb.hypernova.data.UsageRate
import com.prudnikweb.hypernova.data.UsageWindow
import com.prudnikweb.hypernova.ui.components.UsageMeter
import com.prudnikweb.hypernova.ui.components.UsageMeterCompact
import com.prudnikweb.hypernova.ui.components.usageSaid
import com.prudnikweb.hypernova.ui.fx.formatDuration
import com.prudnikweb.hypernova.ui.fx.CosmosState
import com.prudnikweb.hypernova.ui.home.HomeScreen
import com.prudnikweb.hypernova.ui.session.UsageDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A meter shows what's used and when the window resets, and no estimate of when it runs out, while
 * work is running too (owner, 2026-11-01: he saw a red "2d 2h" at 12% of a week just after it reset).
 */
@RunWith(RobolectricTestRunner::class)
class UsageMeterTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val hour = 3_600_000L
    private val res get() = context.resources

    @Before fun setUp() = resetPrefs()

    /** The owner's case: 12% of the week, six days left, at a rate that "runs out" in 2d 2h. */
    private fun working(): Usage = Usage(
        fiveHour = 0.12f, sevenDay = 0.12f,
        fiveHourResetAt = Samples.NOW + 2 * hour, sevenDayResetAt = Samples.NOW + 6 * 24 * hour,
        rate = UsageRate(0.8f, 0.0293f),
    )

    private fun said(needle: String): String = rule.onNode(hasContentDescription(needle, substring = true)).fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString(" ")

    private fun assertNoEstimate(s: String) {
        for (word in listOf("pace", "spending", "a minute", "before the reset", "reach the limit")) assertFalse(s, s.contains(word))
    }

    @Test fun theSentenceIsUsedAndResetAtRestAndAtWork() {
        val at = Samples.NOW
        val w = UsageWindow.sevenDay(working(), at)
        assertEquals(str(R.string.usage_said_reset, "Weekly window", 12, formatDuration(res, 6 * 24 * hour)), usageSaid(res, "Weekly window", w))
        assertEquals(usageSaid(res, "Weekly window", UsageWindow.sevenDay(working().copy(rate = null), at)), usageSaid(res, "Weekly window", w))
        assertNoEstimate(usageSaid(res, "Weekly window", w))
    }

    @Test fun aUsedUpWindowSaysSoUntilItsReset() {
        val at = Samples.NOW
        val w = UsageWindow.sevenDay(working().copy(sevenDay = 1f, weekLimitedAt = at), at)
        assertEquals(str(R.string.usage_said_full, "Weekly window", formatDuration(res, 6 * 24 * hour)), usageSaid(res, "Weekly window", w))
    }

    @Test fun theCompactMeterShowsTheNumberAndTheResetAndNothingElse() {
        val w = UsageWindow.sevenDay(working(), Samples.NOW)
        rule.setContent { Harness(spy) { UsageMeterCompact("7d", w) } }
        rule.onNodeWithText("12%").assertExists()
        rule.onNodeWithText(formatDuration(res, w.resetIn)).assertExists()
        rule.onNodeWithText("2d 2h").assertDoesNotExist()
    }

    @Test fun theFullMeterReadsItsSentence() {
        val w = UsageWindow.sevenDay(working(), Samples.NOW)
        rule.setContent { Harness(spy) { UsageMeter("7 days", w, usageSaid(res, "Weekly window", w)) } }
        assertNoEstimate(said("Weekly window"))
    }

    @Test fun homesUsageCardWhileWorkRuns() {
        rule.setContent {
            Harness(spy) {
                HomeScreen(
                    // One session, so the usage card is on screen in the lazy list.
                    FakeHub(sessions = Samples.sessions().filter { it.id == Samples.MID }, usage = working()), CosmosState(), mutableFloatStateOf(1f),
                    filter = null, onFilter = {}, quiet = emptyList(),
                    onOpen = {}, onNewSession = {}, settings = {},
                )
            }
        }
        rule.waitForIdle()
        for (name in listOf(R.string.usage_name_five, R.string.usage_name_seven)) {
            val s = said(str(name) + " 12%")
            org.junit.Assert.assertTrue(s, s.contains("resets in"))
            assertNoEstimate(s)
        }
    }

    @Test fun theSessionSheetsUsageWhileWorkRuns() {
        rule.setContent { Harness(spy) { androidx.compose.foundation.layout.Column { UsageDetails(FakeHub(usage = working())) } } }
        rule.waitForIdle()
        for (name in listOf(R.string.usage_name_five, R.string.usage_name_seven)) assertNoEstimate(said(str(name)))
    }
}
