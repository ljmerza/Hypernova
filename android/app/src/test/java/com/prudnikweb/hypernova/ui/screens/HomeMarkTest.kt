package com.prudnikweb.hypernova.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.home.HomeFilter
import com.prudnikweb.hypernova.ui.home.HomeFilterPref
import com.prudnikweb.hypernova.ui.home.HomeGroup
import com.prudnikweb.hypernova.ui.home.HomeScreen
import com.prudnikweb.hypernova.ui.home.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The logo in Home's top bar (owner, 2026-11-03): "it should always just go straight to the sessions list screen no
 * matter where you are so that you can always just reset back to the [...] home screen".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+h700dp")
class HomeMarkTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val tab = mutableStateOf(Tab.Sessions)
    private val settingsSaid = "the Settings tab"

    @Before fun setUp() = resetPrefs()

    private fun home(hub: FakeHub = FakeHub(), toTop: androidx.compose.runtime.MutableState<Boolean>? = null) {
        rule.setContent {
            Harness(spy) {
                val top = toTop ?: androidx.compose.runtime.remember { mutableStateOf(false) }
                HomeScreen(
                    hub, project = null, onClearProject = {}, onOpen = {}, onNewSession = {},
                    settings = { LazyColumn { item { androidx.compose.foundation.text.BasicText(settingsSaid) } } },
                    tabState = tab, toTop = top,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun mark() {
        rule.onNodeWithContentDescription(str(R.string.home_mark_label)).performClick()
        rule.waitForIdle()
    }

    private fun tap(label: Int) {
        rule.onNodeWithText(str(label).uppercase()).performClick()
        rule.waitForIdle()
    }

    private val atTheTop get() = rule.topOf("Fix the build")
    private val firstCard get() = "Fix the build"

    @Test fun fromEveryTabItGoesToSessions() {
        home()
        for ((label, on) in listOf(R.string.settings to Tab.Settings, R.string.history to Tab.History, R.string.au_tab to Tab.Automations)) {
            tap(label)
            assertEquals(on, tab.value)
            mark()
            assertEquals(Tab.Sessions, tab.value)
            rule.onNodeWithText(firstCard).assertExists()
        }
    }

    @Test fun onSessionsItGoesToTheTopOfTheList() {
        home()
        val top = atTheTop
        rule.onNode(hasScrollAction()).performScrollToIndex(6)
        rule.waitForIdle()
        rule.onNodeWithText(firstCard).assertDoesNotExist()
        mark()
        assertEquals(top, atTheTop, 0.5f)
    }

    @Test fun fromAnotherTabItGoesToTheTopTooNotWhereTheListWasLeft() {
        home()
        val top = atTheTop
        rule.onNode(hasScrollAction()).performScrollToIndex(6)
        rule.waitForIdle()
        tap(R.string.settings)
        mark()
        assertEquals(Tab.Sessions, tab.value)
        assertEquals(top, atTheTop, 0.5f)
    }

    @Test fun aScreenOverHomeAsksTheSameThing() {
        // Statistics' logo (App.kt): Home comes back with this set.
        val toTop = mutableStateOf(true)
        tab.value = Tab.History
        home(toTop = toTop)
        // Only Sessions clears it: nothing has asked to change the tab here, App.kt does that with it.
        assertTrue(toTop.value)
        rule.runOnIdle { tab.value = Tab.Sessions }
        rule.waitForIdle()
        assertFalse(toTop.value)
    }

    @Test fun aSearchIsClosedTheFilterStays() {
        val filter = HomeFilter(groups = setOf(HomeGroup.Working))
        HomeFilterPref.set(context, filter)
        home()
        rule.onNode(hasClickLabel(str(R.string.home_search))).performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("build")
        rule.waitForIdle()
        mark()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals(filter, HomeFilterPref.state(context).value)
    }

    @Test fun talkBackHearsWhereItGoesNotStatistics() {
        home()
        rule.onNode(hasClickLabel(str(R.string.home_mark_label))).assertExists()
    }
}
