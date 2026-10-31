package com.prudnikweb.hypernova.ui.screens

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.NewSessionMemory
import com.prudnikweb.hypernova.ui.home.NewSessionSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The New session sheet (ui/home/NewSessionSheet.kt): its chips (folder, model, effort, mode), each
 * opening its picker, and Start sending what they say. Its server picker in "All servers" is Home's
 * (HomeScreenTest.allServersNewSessionAsksWhichComputer).
 */
@RunWith(RobolectricTestRunner::class)
class NewSessionSheetTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val started = mutableListOf<String>()

    @Before fun setUp() {
        resetPrefs()
        NewSessionMemory.draft = ""
    }

    private fun sheet(hub: FakeHub, project: String? = null) {
        rule.setContent { Harness(spy) { NewSessionSheet(hub, initialProject = project, onStarted = { started += it }) } }
        rule.waitForIdle()
    }

    /** The chip on screen whose sentence has [said] (ChipRow keeps the variants it didn't place in the tree). */
    private fun chip(said: String) = rule.onNode(hasContentDescription(said, substring = true) and isPlaced())

    @Test fun chipsSayTheirChoices() {
        sheet(FakeHub(), project = "/home/owner/projects/hypernova")
        chip(str(R.string.ns_project_said, "hypernova", "~/projects")).assertExists()
        chip(str(R.string.ns_mode_said, "").trimEnd('.')).assertExists()
        chip(str(R.string.ns_effort_said, str(R.string.effort_default))).assertExists()
        chip("Model:").assertExists()
        // Start waits for words.
        rule.onNodeWithText(str(R.string.start)).performClick()
        rule.waitForIdle()
        assertTrue(started.isEmpty())
        rule.snap("new_session_sheet")
    }

    @Test fun eachChipOpensItsPickerAndStartSendsThePicks() {
        val hub = FakeHub()
        sheet(hub, project = "/home/owner/projects/hypernova")
        // The folder: another project.
        chip("Project:").performClick()
        rule.waitForIdle()
        rule.snap("new_session_folder_picker")
        rule.onAllNodes(hasText("infra") or hasContentDescription("infra", substring = true)).onFirst().performClick()
        rule.waitForIdle()
        chip(str(R.string.ns_project_said, "infra", "~/projects")).assertExists()
        // The mode: Plan.
        chip(str(R.string.ns_mode_said, "").trimEnd('.')).performClick()
        rule.waitForIdle()
        rule.snap("new_session_mode_picker")
        rule.onNode(saidStartsWith(str(R.string.mode_title_plan) + ".")).performClick()
        rule.waitForIdle()
        // The effort: High.
        chip(str(R.string.ns_effort_said, str(R.string.effort_default))).performClick()
        rule.waitForIdle()
        rule.onNode(saidStartsWith(str(R.string.cx_effort_high) + ".")).performClick()
        rule.waitForIdle()
        chip(str(R.string.ns_effort_said, str(R.string.cx_effort_high))).assertExists()
        // The model picker opens on the subscription's models.
        chip("Model:").performClick()
        rule.waitForIdle()
        rule.snap("new_session_model_picker")
        rule.onAllNodes(hasText("Sonnet", substring = true) or hasContentDescription("Sonnet", substring = true)).onFirst().performClick()
        rule.waitForIdle()
        chip("Sonnet").assertExists()

        rule.onNode(hasSetTextAction()).performTextInput("Check the pods")
        rule.onNodeWithText(str(R.string.start)).performClick()
        rule.waitForIdle()
        assertEquals(1, hub.calls.size)
        val call = hub.calls.single()
        assertTrue(call, call.startsWith("start(/home/owner/projects/infra, Check the pods, "))
        assertTrue(call, call.contains("sonnet", ignoreCase = true))
        assertTrue(call, call.endsWith(", plan, high)"))
        assertEquals(listOf("new-session"), started)
    }
}
