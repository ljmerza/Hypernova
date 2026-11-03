package com.prudnikweb.hypernova.ui.screens

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSpeechRecognizer

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

    /** Android 35, as PhoneDictationTest: the phone has a recognizer, and the mic may be used. */
    private fun micHere() {
        val app = context as Application
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app.packageManager).addResolveInfoForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            ResolveInfo().apply { serviceInfo = ServiceInfo().apply { packageName = "recognizer"; name = "Recognizer" } },
        )
    }

    private fun said(words: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(words)) }

    /**
     * A skill picked, the rest dictated, and Start while the mic still listens (owner, 2026-11-02: "it only
     * picked up the skill"): the box counted alone, so the session started on "/code-review" and the
     * words went with the closing sheet. An empty box keeps Start off, which is why only a skill shows it.
     */
    @Config(sdk = [35])
    @Test fun startWhileDictatingTakesTheWordsWithTheSkill() {
        micHere()
        val hub = FakeHub()
        sheet(hub, project = "/home/owner/projects/hypernova")
        rule.onNode(hasSetTextAction()).performTextInput("/code-review ")
        rule.onNode(hasContentDescription(str(R.string.voice_mic))).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val rec = Shadow.extract<ShadowSpeechRecognizer>(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        rec.triggerOnReadyForSpeech(Bundle())
        rec.triggerOnPartialResults(said("look at the auth changes"))
        rule.waitForIdle()

        rule.onNodeWithText(str(R.string.start)).performClick()
        rule.waitForIdle()
        assertTrue("it waits for the last words: ${hub.calls}", hub.calls.isEmpty())
        rec.triggerOnResults(said("look at the auth changes and ignore the tests. Also check the migrations."))
        rule.waitForIdle()

        val call = hub.calls.single()
        assertTrue(call, call.startsWith("start(/home/owner/projects/hypernova, /code-review look at the auth changes and ignore the tests. Also check the migrations., "))
        assertEquals(listOf("new-session"), started)
    }

    /** Typed words go as they are, every line, with Start as ever. */
    @Test fun startSendsASkillWithEveryLineAfterIt() {
        val hub = FakeHub()
        sheet(hub, project = "/home/owner/projects/hypernova")
        rule.onNode(hasSetTextAction()).performTextInput("/code-review look at the auth changes.\n\nAlso check the migrations.")
        rule.onNodeWithText(str(R.string.start)).performClick()
        rule.waitForIdle()
        val call = hub.calls.single()
        assertTrue(call, call.startsWith("start(/home/owner/projects/hypernova, /code-review look at the auth changes.\n\nAlso check the migrations., "))
    }
}
