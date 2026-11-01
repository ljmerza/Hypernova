package com.prudnikweb.hypernova.ui.screens

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AutomationError
import com.prudnikweb.hypernova.data.Link
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.automations.AutomationEditor
import com.prudnikweb.hypernova.ui.automations.AutomationsTab
import com.prudnikweb.hypernova.ui.automations.Templates
import com.prudnikweb.hypernova.ui.fx.CosmosState
import com.prudnikweb.hypernova.ui.home.Everywhere
import com.prudnikweb.hypernova.ui.home.HomeScreen
import com.prudnikweb.hypernova.ui.home.ServerChoice
import com.prudnikweb.hypernova.data.ServerStatus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Automations tab and its editor (ui/automations/AutomationsTab.kt, AutomationEditor.kt) over a
 * fake hub and [FakeAutomations]: the cards, a switch, Pause all, an ask answered from its card,
 * the templates; the editor's Run now, Save, Delete and a refusal under its block; and All servers,
 * where Automations stay one server's (Home's tab bar says so).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+h1600dp")
class AutomationsScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val edits = mutableListOf<String>()
    private val sessions = mutableListOf<String>()
    private var backs = 0
    private val saved = mutableListOf<String>()

    @Before fun setUp() = resetPrefs()

    private fun hub(autos: FakeAutomations = FakeAutomations(AutoSamples.state()), link: Link = Link(LinkState.Connected)) =
        AutomationsHub(FakeHub(link = link), autos)

    private fun tab(hub: AutomationsHub) {
        rule.setContent {
            Harness(spy) {
                AutomationsTab(hub, 0.dp, 0.dp, "home", onEdit = { id, t -> edits += "${id ?: "new"}/${t ?: "-"}" }, onOpenSession = { sessions += it })
            }
        }
        rule.waitForIdle()
    }

    private fun switchOf(name: String) = hasContentDescription(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    @Test fun theCardsTheAskAboveThemAndTheTemplates() {
        val autos = FakeAutomations(AutoSamples.state())
        tab(hub(autos))
        // A card's name is its seam's (in capitals), its switch says it.
        rule.onNode(switchOf("Nightly digest")).assertExists()
        rule.onNode(switchOf("Weekly cleanup")).assertExists()
        // The ask a run waits on, above the cards, with its answers.
        rule.onAllNodesWithText("Deploy now?").onFirst().assertExists()
        rule.onNodeWithText("Deploy").assertExists()
        rule.onNodeWithText("Not now").assertExists()
        // The one that needs you first, the one that's off last.
        assertTrue(rule.topOfSaid("Deploy gate") < rule.topOfSaid("Nightly digest"))
        assertTrue(rule.topOfSaid("Nightly digest") < rule.topOfSaid("Weekly cleanup"))
        rule.onNodeWithText(str(R.string.au_pause_all)).assertExists()
        rule.snap("automations_tab")
    }

    @Test fun aSwitchFlipsAtOnceAndAsksTheMac() {
        val autos = FakeAutomations(AutoSamples.state())
        tab(hub(autos))
        rule.onNode(switchOf("Weekly cleanup")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("setEnabled(au-off, true)"), autos.calls)
        assertTrue(autos.state!!.byId("au-off")!!.enabled)
    }

    @Test fun aSwitchTheMacRefusesGoesBackWithItsWords() {
        val autos = FakeAutomations(AutoSamples.state())
        autos.refuse = AutomationError("no_project", "That project folder is gone.")
        tab(hub(autos))
        rule.onNode(switchOf("Nightly digest")).performClick()
        rule.waitForIdle()
        assertEquals(listOf("That project folder is gone."), spy.toasts)
        assertTrue(autos.state!!.byId("au-brief")!!.enabled)
    }

    @Test fun pauseAllThenResume() {
        val autos = FakeAutomations(AutoSamples.state())
        tab(hub(autos))
        rule.onNodeWithText(str(R.string.au_pause_all)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("setPaused(true)"), autos.calls)
        assertEquals(listOf(str(R.string.au_paused_toast)), spy.toasts)
        rule.onNodeWithText(str(R.string.au_resume)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("setPaused(true)", "setPaused(false)"), autos.calls)
        rule.snap("automations_tab_resumed")
    }

    @Test fun anAskIsAnsweredFromItsCard() {
        val autos = FakeAutomations(AutoSamples.state())
        tab(hub(autos))
        rule.onNodeWithText("Deploy").performClick()
        rule.waitForIdle()
        assertEquals(listOf("answer(ru-ask, st_q, Deploy)"), autos.calls)
        assertEquals(listOf(str(R.string.wf_answered_toast, "Deploy")), spy.toasts)
        rule.onNodeWithText("Not now").assertDoesNotExist()
    }

    @Test fun anAskSomeoneElseAnsweredSaysWhatWasTaken() {
        val autos = FakeAutomations(AutoSamples.state())
        val taken = Samples.run("ru-ask", Samples.DEPLOY_GATE, com.prudnikweb.hypernova.data.Run.DONE).copy(
            steps = listOf(com.prudnikweb.hypernova.data.RunStep("st_q", "ask", "done", answer = com.prudnikweb.hypernova.data.StepAnswer("Not now", "mac", Samples.NOW))),
        )
        autos.refuse = AutomationError("not_asking", "It isn’t waiting for that answer any more.", run = taken)
        tab(hub(autos))
        rule.onNodeWithText("Deploy").performClick()
        rule.waitForIdle()
        assertEquals(listOf(str(R.string.wf_not_asking_by, "Not now")), spy.toasts)
    }

    @Test fun aCardOpensItsEditorAndATemplateANewOne() {
        tab(hub())
        // The cards in their order (the ask's run first): the second is Nightly digest.
        rule.onAllNodes(hasClickLabel(str(R.string.au_edit)))[1].performClick()
        val first = Templates.offered(emptySet(), setOf("steps", "ask")).first()
        rule.onNode(hasText(str(first.name))).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(listOf("au-brief/-", "new/${first.id}"), edits)
    }

    @Test fun anOlderMacSaysToUpdateAndOfflineSaysSo() {
        tab(hub(FakeAutomations(null)))
        rule.onNodeWithText(str(R.string.au_old_daemon)).assertExists()
    }

    @Test fun outOfReachTheSwitchesHoldStill() {
        val autos = FakeAutomations(AutoSamples.state())
        tab(hub(autos, link = Link(LinkState.Offline)))
        rule.onNode(switchOf("Nightly digest")).assertIsNotEnabled()
        rule.onNodeWithText(str(R.string.au_pause_all)).assertIsNotEnabled()
    }

    // ─── The editor ───

    private fun editor(hub: AutomationsHub, id: String?, template: String? = null) {
        rule.setContent {
            Harness(spy) {
                AutomationEditor(hub, id, template, toRuns = false, templateProject = null, onBack = { backs++ }, onSaved = { saved += it }, onOpenSession = { sessions += it })
            }
        }
        rule.waitForIdle()
    }

    @Test fun aSavedWorkflowShowsItsStepsRunsNowAndSavesOnlyWhenChanged() {
        // No run yet: a run's row says when it ran, a wall-clock time no picture may show.
        val autos = FakeAutomations(AutoSamples.state(list = AutoSamples.list().map { it.copy(lastRun = null) }))
        editor(hub(autos), Samples.DEPLOY_GATE)
        rule.onAllNodesWithText("Deploy now?", substring = true).onFirst().assertExists()
        rule.onNodeWithText(str(R.string.au_save)).assertIsNotEnabled()
        rule.snap("automation_editor_workflow")

        rule.onNodeWithText(str(R.string.au_run_now)).performClick()
        rule.waitForIdle()
        assertTrue(autos.calls.toString(), "runNow(au-gate)" in autos.calls)
        assertEquals(1, spy.toasts.size)

        rule.onNode(hasText("Deploy gate") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performScrollTo().performTextReplacement("Deploy gate (prod)")
        rule.waitForIdle()
        // Changed: Save, and Run now waits until it's saved.
        rule.onNodeWithText(str(R.string.au_run_now)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.au_save)).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertTrue(autos.calls.toString(), "save(au-gate, Deploy gate (prod))" in autos.calls)
        assertEquals(str(R.string.au_saved), spy.toasts.last())
        assertEquals("Deploy gate (prod)", autos.state!!.byId(Samples.DEPLOY_GATE)!!.name)
        assertTrue("an edit isn't a new one", saved.isEmpty())
    }

    @Test fun aRefusedNameIsSaidUnderTheName() {
        val autos = FakeAutomations(AutoSamples.state())
        editor(hub(autos), "au-brief")
        rule.onNode(hasText("Nightly digest") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performScrollTo().performTextReplacement("Brief")
        autos.refuse = AutomationError("bad_name", str(R.string.au_err_name))
        rule.onNodeWithText(str(R.string.au_save)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.au_err_name)).assertExists()
        assertTrue("said where it belongs, not in a toast", spy.toasts.isEmpty())
    }

    @Test fun deleteAsksFirstThenGoesBack() {
        val autos = FakeAutomations(AutoSamples.state())
        editor(hub(autos), "au-brief")
        rule.onNodeWithText(str(R.string.au_delete)).performScrollTo().performClick()
        val c = spy.confirms.single()
        assertEquals(str(R.string.au_delete_q, "Nightly digest"), c.title)
        assertTrue(c.danger)
        assertTrue("nothing deleted before the yes", autos.calls.none { it.startsWith("delete") })
        c.onConfirm()
        rule.waitForIdle()
        assertTrue(autos.calls.toString(), "delete(au-brief)" in autos.calls)
        assertEquals(1, backs)
    }

    @Test fun aNewOneFromATemplateSavesAsNewAndOpensWhatWasSaved() {
        val autos = FakeAutomations(AutoSamples.state(list = emptyList()))
        val t = Templates.offered(emptySet(), setOf("steps", "ask")).first { !it.costs }
        editor(hub(autos), null, t.id)
        rule.onNodeWithText(str(R.string.au_run_now)).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.au_save)).assertIsEnabled().performClick()
        rule.waitForIdle()
        assertTrue(autos.calls.toString(), autos.calls.any { it.startsWith("save(null, ") })
        assertEquals(listOf("au-new"), saved)
    }

    @Test fun aSavedOneThatIsGoneSaysSoInsteadOfABlankForm() {
        editor(hub(), "au-deleted")
        rule.onNodeWithText(str(R.string.au_save)).assertDoesNotExist()
    }

    // ─── All servers (M8) ───

    private fun everywhere(): Pair<FakeHub, Everywhere> {
        val z2 = FakeHub(sessions = Samples.sessions().filter { it.id == Samples.BUILD })
        val mini = FakeHub(sessions = listOf(Samples.session("s-mini", "Mini backup", SessionState.Idle, 10, project = "backup")), approvals = emptyMap())
        return z2 to Everywhere(
            listOf(ServerStatus("z2", "z2", LinkState.Connected, needsYou = 1, active = true) to z2, ServerStatus("mini", "mini", LinkState.Connected, needsYou = 0, active = false) to mini),
            pickedName = "z2", onOpen = { _, _ -> }, onNewSession = {}, onRuns = { _, _ -> },
        )
    }

    @Test fun inAllServersTheAutomationsTabSaysToPickAServerAndStaysOnSessions() {
        val (z2, e) = everywhere()
        val all = mutableStateOf<Everywhere?>(e)
        rule.setContent {
            Harness(spy) {
                HomeScreen(
                    z2, CosmosState(), mutableFloatStateOf(1f), filter = null, onFilter = {}, quiet = emptyList(),
                    onOpen = {}, onNewSession = {}, settings = {},
                    servers = ServerChoice(e.servers.map { it.first }, {}, {}, all = all.value != null), everywhere = all.value,
                )
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText(str(R.string.au_tab), ignoreCase = true, useUnmergedTree = true).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(listOf(str(R.string.all_servers_no_automations)), spy.toasts)
        // Still Sessions: its cards, not the tab's.
        rule.onNodeWithText("Fix the build").assertExists()
        rule.onNodeWithText(str(R.string.au_pause_all)).assertDoesNotExist()

        // One server again: the tab opens.
        all.value = null
        rule.waitForIdle()
        rule.onAllNodesWithText(str(R.string.au_tab), ignoreCase = true, useUnmergedTree = true).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(1, spy.toasts.size)
        rule.onNodeWithText("Fix the build").assertDoesNotExist()
    }
}
