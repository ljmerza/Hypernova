package com.prudnikweb.hypernova.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AboutUpdate
import com.prudnikweb.hypernova.data.AppUpdate
import com.prudnikweb.hypernova.data.DictationEngine
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.data.VoicePrefs
import com.prudnikweb.hypernova.ui.components.EnterKeyPref
import com.prudnikweb.hypernova.ui.home.CardSwipePref
import com.prudnikweb.hypernova.ui.home.CardSwipes
import com.prudnikweb.hypernova.ui.home.SwipeChoice
import com.prudnikweb.hypernova.ui.session.TopBarChoice
import com.prudnikweb.hypernova.ui.session.TopBarItem
import com.prudnikweb.hypernova.ui.session.TopBarPref
import com.prudnikweb.hypernova.ui.settings.CardSwipeSection
import com.prudnikweb.hypernova.ui.settings.TopBarSection
import com.prudnikweb.hypernova.ui.settings.TypingSection
import com.prudnikweb.hypernova.ui.settings.VoiceRecognitionSection
import com.prudnikweb.hypernova.ui.update.AboutUpdateRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings' parts kept on this phone (ui/settings): Enter key, voice engine, card swipes, the session
 * top bar, and About's update row. Each part on its own: the Settings tab itself takes the app's
 * HubViewModel (the paired computers), which a test has no fake of.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    @Before fun setUp() = resetPrefs()

    private fun parts() {
        rule.setContent {
            Harness(spy) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    VoiceRecognitionSection()
                    CardSwipeSection()
                    TopBarSection()
                    TypingSection()
                }
            }
        }
        rule.waitForIdle()
    }

    private fun isSwitch(name: String) = hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    @Config(qualifiers = "+h2300dp")
    @Test fun thePartsAsTheyStart() {
        parts()
        rule.snap("settings_parts")
    }

    @Test fun enterKeyNewLineAndBack() {
        parts()
        rule.onNodeWithText(str(R.string.st_enter_newline)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(EnterKey.NewLine, EnterKeyPref.state(context).value)
        rule.onNodeWithText(str(R.string.st_enter_send)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(EnterKey.Send, EnterKeyPref.state(context).value)
    }

    @Test fun voiceEngineOnDevice() {
        parts()
        rule.onNodeWithText(str(R.string.voice_engine_on_device)).performClick()
        rule.waitForIdle()
        assertEquals(DictationEngine.OnDevice, VoicePrefs.of(context).dictationEngine)
    }

    @Test fun cardSwipeRightPicksOpen() {
        parts()
        rule.onNodeWithText(str(R.string.st_swipe_right)).performScrollTo().performClick()
        rule.waitForIdle()
        rule.snap("settings_card_swipe_choices")
        rule.onNode(hasText(str(R.string.st_swipe_open)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        rule.waitForIdle()
        assertEquals(CardSwipes(left = SwipeChoice.Smart, right = SwipeChoice.Open), CardSwipePref.state(context).value)
        // The row says the pick now.
        rule.onNode(hasText(str(R.string.st_swipe_right)) and hasText(str(R.string.st_swipe_open))).assertExists()
    }

    @Test fun topBarToggleMoveAndReset() {
        parts()
        val model = str(R.string.st_top_bar_model)
        rule.onNode(isSwitch(model)).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(TopBarItem.MODEL in TopBarPref.state(context).value.shown)
        // Up one: before the 7-day window.
        rule.onNode(hasContentDescription(str(R.string.st_top_bar_up, model))).performScrollTo().performClick()
        rule.waitForIdle()
        val order = TopBarPref.state(context).value.order
        assertEquals(order.indexOf(TopBarItem.SEVEN_DAY) - 1, order.indexOf(TopBarItem.MODEL))
        // The first can't go up.
        rule.onNode(hasContentDescription(str(R.string.st_top_bar_up, str(R.string.st_top_bar_context)))).assertIsNotEnabled()
        rule.snap("settings_top_bar_changed")
        rule.onNodeWithText(str(R.string.st_top_bar_reset)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(TopBarChoice.DEFAULT, TopBarPref.state(context).value)
        rule.onNodeWithText(str(R.string.st_top_bar_reset)).assertDoesNotExist()
    }

    @Test fun aboutUpdateRowOffersTheUpdate() {
        val update = AppUpdate("0.6.0", 4242, "https://192.168.1.20:47323/app.apk")
        rule.setContent {
            Harness(spy) {
                Column(Modifier.padding(16.dp)) {
                    AboutUpdateRow(AboutUpdate(update, AboutUpdate.Step.Offered))
                    AboutUpdateRow(AboutUpdate(update, AboutUpdate.Step.Downloading, fraction = 0.4f))
                }
            }
        }
        rule.onNodeWithText(str(R.string.about_update_button)).assertExists()
        assertEquals(2, rule.onAllNodes(hasText(str(R.string.about_update_available, "0.6.0"))).fetchSemanticsNodes().size)
        rule.onNodeWithText(str(R.string.update_percent, 40)).assertExists()
        rule.snap("settings_about_update")
    }
}
