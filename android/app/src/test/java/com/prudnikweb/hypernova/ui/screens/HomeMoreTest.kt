@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.prudnikweb.hypernova.ui.screens

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.TermLink
import com.prudnikweb.hypernova.data.ClaudeState
import com.prudnikweb.hypernova.data.CodexState
import com.prudnikweb.hypernova.data.CodexUsage
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.JobRestart
import com.prudnikweb.hypernova.data.MarkReadOnScreen
import com.prudnikweb.hypernova.data.ModelWeek
import com.prudnikweb.hypernova.data.OutdatedClaude
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.ProviderModels
import com.prudnikweb.hypernova.data.ProviderStatus
import com.prudnikweb.hypernova.data.ReadState
import com.prudnikweb.hypernova.data.SendStatus
import com.prudnikweb.hypernova.data.Window
import com.prudnikweb.hypernova.data.Servers
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.components.AttentionStore
import com.prudnikweb.hypernova.ui.fx.CosmosState
import com.prudnikweb.hypernova.ui.home.HomeScreen
import com.prudnikweb.hypernova.ui.home.ModelWeekMeters
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's Sessions tab beyond its cards (FEATURES section 3): the unread dot as the read marks keep it
 * (H4), the long-press menu with its rename sheet and confirms (H5), the notice for jobs on an older
 * Claude Code (H9) and the cards above the sessions (H10).
 */
@RunWith(RobolectricTestRunner::class)
class HomeMoreTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    /** A [FakeHub] with what these tests add: prompts whose session is gone, and the restarts. */
    private class HomeHub(val base: FakeHub) : Hub by base {
        var orphans by mutableStateOf<List<Item.User>>(emptyList())
        override fun orphanedPrompts() = orphans
        override suspend fun startFromWaiting(waitingId: String): Result<String> {
            base.calls += "startFromWaiting($waitingId)"
            orphans = orphans.filter { it.waitingId != waitingId }
            return Result.success("new-session")
        }
        override fun removeWaiting(waitingId: String) {
            base.calls += "removeWaiting($waitingId)"
            orphans = orphans.filter { it.waitingId != waitingId }
        }
        override suspend fun respawnSession(sessionId: String): Result<Unit> {
            base.calls += "respawnSession($sessionId)"
            return Result.success(Unit)
        }
        override suspend fun respawnOutdated(sessionIds: List<String>): Result<List<JobRestart>> {
            base.calls += "respawnOutdated($sessionIds)"
            return Result.success(sessionIds.map { JobRestart(it, ok = it != Samples.ALPHA, said = if (it == Samples.ALPHA) "Job is busy" else "Done") })
        }
    }

    @Before fun setUp() {
        resetPrefs()
        AttentionStore.undo("provider:or-1")
    }

    @After fun tearDown() {
        ReadState.serverOf = { (it as? com.prudnikweb.hypernova.data.LiveHub)?.serverId }
        AttentionStore.undo("provider:or-1")
    }

    private fun only(vararg ids: String): List<Session> = Samples.sessions().filter { it.id in ids }

    private fun home(hub: Hub, onScreen: () -> String? = { null }) {
        rule.setContent {
            Harness(spy) {
                HomeScreen(
                    hub, CosmosState(), mutableFloatStateOf(1f),
                    filter = null, onFilter = {}, quiet = emptyList(),
                    onOpen = { spy.opened += "session:$it" }, onNewSession = {}, settings = {},
                )
                // As App.kt does for the chat on screen.
                MarkReadOnScreen(hub, onScreen())
            }
        }
        rule.waitForIdle()
    }

    private fun dots() = rule.onAllNodesWithContentDescription(str(R.string.unread_said), useUnmergedTree = true).fetchSemanticsNodes().size

    // ─── H4: the unread dot, as the read marks have it ───

    @Test fun completedCardsAreUnreadTillOpenedAndAReplyAfterBringsTheDotBack() {
        val server = "test-z2"
        ReadState.serverOf = { server }
        // This phone has kept marks for its server since before any of these finished.
        context.getSharedPreferences(Servers.scoped("hypernova.read", server), Context.MODE_PRIVATE).edit().clear().putString("since", "0").commit()
        val hub = FakeHub(sessions = only(Samples.ZETA, Samples.MID, Samples.ALPHA, Samples.PARSER))
        var open by mutableStateOf<String?>(null)
        home(hub) { open }
        // Every Completed card (and only those) has its dot.
        assertEquals(3, dots())
        rule.snap("home_unread_dots")

        // The owner opens Mid sprint notes and comes back: its dot is gone, the others stay.
        open = Samples.MID
        rule.waitForIdle()
        open = null
        rule.waitForIdle()
        assertEquals(2, dots())
        assertTrue(ReadState.of(context, server).readUpTo(Samples.MID) != null)

        // A reply comes in while he's away from it: unread again.
        hub.sessionList = hub.sessionList.map { if (it.id == Samples.MID) it.copy(updatedAt = Samples.NOW) else it }
        rule.waitForIdle()
        assertEquals(3, dots())

        // A reply while its chat is on screen stays read.
        open = Samples.ZETA
        rule.waitForIdle()
        hub.sessionList = hub.sessionList.map { if (it.id == Samples.ZETA) it.copy(updatedAt = Samples.NOW + 1) else it }
        rule.waitForIdle()
        open = null
        rule.waitForIdle()
        assertEquals(2, dots())
    }

    @Test fun aHubWithoutAServerKeepsNoMarksAndShowsNoDots() {
        home(FakeHub(sessions = only(Samples.ZETA, Samples.MID)))
        assertEquals(0, dots())
    }

    // ─── H5: the long-press menu, its rename sheet and its confirms ───

    private fun longPress(name: String) {
        rule.onNode(hasText(name)).performSemanticsAction(SemanticsActions.OnLongClick)
        rule.waitForIdle()
    }

    @Config(qualifiers = "+h1600dp")
    @Test fun theMenuRenamesWithItsSheet() {
        val hub = FakeHub(sessions = only(Samples.ZETA))
        home(hub)
        longPress("Zeta cleanup")
        rule.onNodeWithText(str(R.string.session_rename)).assertExists()
        rule.onNodeWithText(str(R.string.session_stop)).assertExists()
        rule.snap("home_menu_sheet")
        rule.onNodeWithText(str(R.string.session_rename)).performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("Flags gone")
        rule.snap("home_menu_rename")
        rule.onNodeWithText(str(R.string.save)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("renameSession(${Samples.ZETA}, Flags gone)"), hub.calls)
    }

    @Test fun theMenusStopGoesAtOnce() {
        val hub = FakeHub(sessions = only(Samples.ZETA))
        home(hub)
        longPress("Zeta cleanup")
        rule.onNodeWithText(str(R.string.session_stop)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("stopSession(${Samples.ZETA})"), hub.calls)
    }

    @Config(qualifiers = "+h1600dp")
    @Test fun theMenusDeleteAsksFirst() {
        val hub = FakeHub(sessions = only(Samples.ALPHA))
        home(hub)
        longPress("Alpha release")
        rule.onNodeWithText(str(R.string.delete)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.session_delete_q, "Alpha release")).assertExists()
        assertTrue("nothing asked yet", hub.calls.isEmpty())
        rule.snap("home_menu_delete_confirm")
        rule.onAllNodesWithText(str(R.string.delete)).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        rule.onNode(hasText(str(R.string.delete)) and androidx.compose.ui.test.hasClickAction(), useUnmergedTree = false).performClick()
        rule.waitForIdle()
        assertEquals(listOf("deleteSession(${Samples.ALPHA})"), hub.calls)
    }

    /** Samples' Mid sprint notes (no job), as [how]. */
    private fun midAs(how: (com.prudnikweb.hypernova.data.Session) -> com.prudnikweb.hypernova.data.Session) =
        FakeHub(sessions = only(Samples.MID).map(how))

    private fun pressDelete() {
        rule.onNode(hasText(str(R.string.delete)) and androidx.compose.ui.test.hasClickAction(), useUnmergedTree = false).performClick()
        rule.waitForIdle()
    }

    @Config(qualifiers = "+h1600dp")
    @Test fun anIdleTerminalSessionHasDeleteAndSaysItsTerminalCloses() {
        val hub = midAs { it.copy(origin = "terminal", term = TermLink("t1", mac = 1, phone = 0)) }
        home(hub)
        longPress("Mid sprint notes")
        rule.onNodeWithText(str(R.string.session_rename)).assertExists()
        pressDelete()
        rule.onNodeWithText(str(R.string.session_delete_q, "Mid sprint notes")).assertExists()
        rule.onNodeWithText(str(R.string.session_delete_body_terminal)).assertExists()
        assertTrue("nothing asked yet", hub.calls.isEmpty())
        pressDelete()
        assertEquals(listOf("deleteSession(${Samples.MID})"), hub.calls)
    }

    @Config(qualifiers = "+h1600dp")
    @Test fun aRunningJobsDeleteSaysItStops() {
        home(FakeHub(sessions = only(Samples.ZETA)))
        longPress("Zeta cleanup")
        pressDelete()
        rule.onNodeWithText(str(R.string.session_delete_body_running)).assertExists()
    }

    @Config(qualifiers = "+h1600dp")
    @Test fun aSessionWithNothingRunningSaysWhereItGoes() {
        val hub = midAs { it.copy(origin = "hypernova") }
        home(hub)
        longPress("Mid sprint notes")
        pressDelete()
        rule.onNodeWithText(str(R.string.session_delete_body_plain)).assertExists()
        pressDelete()
        assertEquals(listOf("deleteSession(${Samples.MID})"), hub.calls)
    }

    @Config(qualifiers = "+h1600dp")
    @Test fun aTerminalItDoesntHoldSaysWhyItCantBeDeleted() {
        val hub = midAs { it.copy(origin = "terminal", term = null) }
        home(hub)
        longPress("Mid sprint notes")
        pressDelete()
        rule.onNodeWithText(str(R.string.session_delete_cant_q, "Mid sprint notes")).assertExists()
        rule.onNodeWithText(str(R.string.session_delete_cant_body)).assertExists()
        rule.onNodeWithText(str(R.string.fl_ok)).performClick()
        rule.waitForIdle()
        assertTrue("nothing went to the server", hub.calls.isEmpty())
    }

    @Test fun aSessionAtWorkThatIsNoJobHasNoDelete() {
        home(FakeHub(sessions = only(Samples.PARSER)))
        longPress("Refactor parser")
        rule.onNodeWithText(str(R.string.session_rename)).assertExists()
        rule.onNodeWithText(str(R.string.delete)).assertDoesNotExist()
    }

    @Test fun anOutdatedJobsMenuRestartsItAfterAsking() {
        val base = FakeHub(sessions = only(Samples.ALPHA).map { it.copy(agentOutdated = OutdatedClaude("2.1.312", "2.1.316")) })
        val hub = HomeHub(base)
        home(hub)
        longPress("Alpha release")
        // The menu's (Home's notice for the outdated job says Restart too).
        rule.onAllNodesWithText(str(R.string.session_restart)).onLast().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.session_restart_q, "Alpha release")).assertExists()
        assertTrue(base.calls.isEmpty())
        // The dialog's button (Home's notice for the outdated job says Restart too).
        rule.onAllNodes(hasText(str(R.string.session_restart)) and androidx.compose.ui.test.hasClickAction()).onLast().performClick()
        rule.waitForIdle()
        assertEquals(listOf("respawnSession(${Samples.ALPHA})"), base.calls)
    }

    // ─── H9: Restart all for jobs on an older Claude Code ───

    @Test fun theOutdatedJobsNoticeRestartsThemAllAfterAskingAndSaysWhichRefused() {
        val outdated = OutdatedClaude("2.1.312", "2.1.316")
        val base = FakeHub(sessions = only(Samples.ZETA, Samples.ALPHA, Samples.MID).map { if (it.agentJob != null) it.copy(agentOutdated = outdated) else it })
        val hub = HomeHub(base)
        home(hub)
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.jobs_outdated_title, 2, 2)).assertExists()
        rule.onNodeWithText(str(R.string.jobs_outdated_line, "2.1.316")).assertExists()
        rule.snap("home_outdated_jobs")
        rule.onNodeWithText(str(R.string.jobs_restart_all)).performClick()
        rule.waitForIdle()
        // Asked first: nothing restarted until he says so.
        val c = spy.confirms.single()
        assertEquals(context.resources.getQuantityString(R.plurals.jobs_restart_q, 2, 2), c.title)
        assertTrue(base.calls.isEmpty())
        c.onConfirm()
        rule.waitForIdle()
        assertEquals(1, base.calls.size)
        assertTrue(base.calls.single(), base.calls.single().startsWith("respawnOutdated(") && Samples.ZETA in base.calls.single() && Samples.ALPHA in base.calls.single())
        assertEquals(listOf(context.resources.getQuantityString(R.plurals.jobs_restart_refused, 1, 1, "Job is busy").trim()), spy.toasts)
    }

    // ─── H10: the cards above the sessions ───

    @Test fun aPromptWhoseSessionEndedStartsANewSessionOrGoes() {
        val base = FakeHub(sessions = only(Samples.MID))
        val hub = HomeHub(base)
        hub.orphans = listOf(
            Item.User("w1", "Check the deploy again", status = SendStatus.SessionGone, waitingId = "w1"),
            Item.User("w2", "And the logs", status = SendStatus.SessionGone, waitingId = "w2"),
        )
        home(hub)
        rule.onNodeWithText("Check the deploy again").assertExists()
        rule.snap("home_gone_prompts")
        rule.onAllNodesWithText(str(R.string.waiting_start_new)).onFirstNode().performClick()
        rule.waitForIdle()
        assertEquals(listOf("startFromWaiting(w1)"), base.calls)
        assertEquals(listOf("session:new-session"), spy.opened)
        rule.onNodeWithText(str(R.string.waiting_remove)).performClick()
        rule.waitForIdle()
        assertEquals("removeWaiting(w2)", base.calls.last())
        rule.onNodeWithText("And the logs").assertDoesNotExist()
    }

    @Test fun aProvidersTroubleIsACardThatGoesWithASwipeAndComesBackWithUndo() {
        val bad = Provider("or-1", "OpenRouter", "openrouter", "https://openrouter.ai/api", hasKey = true, models = ProviderModels(), builtIn = false, status = ProviderStatus(ProviderStatus.BAD_KEY, "Key refused", Samples.NOW))
        val hub = FakeHub(sessions = only(Samples.MID), providers = Samples.providers() + bad)
        home(hub)
        val title = str(R.string.provider_card_bad_key, "OpenRouter")
        rule.onNodeWithText(title).assertExists()
        rule.snap("home_provider_card")
        rule.onNode(hasText(title), useUnmergedTree = false).performCustomAccessibilityActionWithLabel(str(R.string.attention_dismiss))
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.attention_undo)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(title).assertExists()
    }

    // ─── H11: usage cards ───

    @Test fun whereOnlyCodexIsInUseHomesUsageCardIsCodexs() {
        val base = FakeHub(sessions = only(Samples.MID))
        val codexHub = object : Hub by base {
            override val codex = CodexState(
                available = true, enabled = true, connected = true,
                usage = CodexUsage(Window(40.0, Samples.NOW + 2 * 3_600_000L + 30_000), Window(65.0, Samples.NOW + 5 * 3_600_000L + 30_000), limited = false, observedAt = Samples.NOW),
            )
            override val claude = ClaudeState(available = false, version = null)
        }
        home(codexHub)
        rule.onAllNodes(hasText(str(R.string.five_hours), substring = true, ignoreCase = true) or androidx.compose.ui.test.hasContentDescription(str(R.string.five_hours), substring = true), useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue("Codex's five hours", it.isNotEmpty()) }
        rule.onAllNodes(hasText(str(R.string.seven_days), substring = true, ignoreCase = true) or androidx.compose.ui.test.hasContentDescription(str(R.string.seven_days), substring = true), useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue("Codex's week", it.isNotEmpty()) }
        rule.onNodeWithText(str(R.string.cx_usage_none)).assertDoesNotExist()
        rule.snap("home_codex_usage")
    }

    @Test fun eachModelsOwnWeekHasItsMeter() {
        rule.setContent {
            Harness(spy) {
                androidx.compose.foundation.layout.Column {
                    ModelWeekMeters(listOf(ModelWeek("Sonnet", 0.12f, Samples.NOW + 3 * 3_600_000L + 30_000), ModelWeek("Opus", 1f, Samples.NOW + 2 * 3_600_000L + 30_000)), Samples.NOW)
                }
            }
        }
        for (m in listOf("Sonnet", "Opus")) rule.onAllNodes(hasText(m, substring = true, ignoreCase = true) or androidx.compose.ui.test.hasContentDescription(m, substring = true), useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(m, it.isNotEmpty()) }
        rule.snap("home_model_weeks")
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirstNode() = this[0]
