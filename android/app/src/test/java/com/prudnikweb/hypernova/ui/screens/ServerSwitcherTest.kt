@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.prudnikweb.hypernova.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.ui.home.ServerSwitcher
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The switcher's rows (owner, 2026-10-31, a phone photo): with "4 NEED YOU" and "Reconnecting" on a
 * row there was no room left for the name, so "All servers" and k1 showed as "…". The count is the
 * number alone now, and every name shows in full, at the phone's usual and at a large font.
 */
@RunWith(RobolectricTestRunner::class)
class ServerSwitcherTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    private val servers = listOf(
        ServerStatus("k1", "k1", LinkState.Reconnecting, needsYou = 1, active = false),
        ServerStatus("z2", "z2", LinkState.Connected, needsYou = 3, active = true),
        ServerStatus("gym", "gym", LinkState.Reconnecting, needsYou = 0, active = false),
    )

    private fun show(fontScale: Float) {
        rule.setContent {
            Harness(spy) {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    ServerSwitcher(servers, onSwitch = {}, onPair = {}, onDismiss = {}, all = true, onAll = {})
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertWhole(text: String) {
        val node = rule.onNodeWithText(text).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse("\"$text\" is cut short", layouts.single().hasVisualOverflow)
    }

    @Test fun everyNameShowsWholeAndTheCountIsTheNumber() {
        show(1f)
        for (name in listOf("All servers", "k1", "z2", "gym")) assertWhole(name)
        rule.onNodeWithText("4").assertExists()
        rule.onNodeWithContentDescription("4 need you").assertExists()
        rule.onNodeWithContentDescription("1 needs you").assertExists()
        rule.snap("server_switcher")
    }

    @Test fun namesStillShowAtALargeFont() {
        show(1.3f)
        for (name in listOf("All servers", "k1", "z2", "gym")) assertWhole(name)
        rule.snap("server_switcher_large_font")
    }
}
