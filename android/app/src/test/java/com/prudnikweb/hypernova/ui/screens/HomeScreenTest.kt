@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.prudnikweb.hypernova.ui.screens

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTextInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.fx.CosmosState
import com.prudnikweb.hypernova.ui.home.CardSwipePref
import com.prudnikweb.hypernova.ui.home.CardSwipes
import com.prudnikweb.hypernova.ui.home.Everywhere
import com.prudnikweb.hypernova.ui.home.HomeScreen
import com.prudnikweb.hypernova.ui.home.ServerChoice
import com.prudnikweb.hypernova.ui.home.SessionCard
import com.prudnikweb.hypernova.ui.home.SwipeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's Sessions tab (ui/home/HomeScreen.kt) with a fake hub: its groups, cards, search, swipes and "All servers". */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()
    private val newSessions = mutableListOf<String>()

    @Before fun setUp() = resetPrefs()

    private fun only(vararg ids: String): List<Session> = Samples.sessions().filter { it.id in ids }

    private fun home(hub: FakeHub, everywhere: Everywhere? = null, servers: ServerChoice? = null) {
        rule.setContent {
            Harness(spy) {
                HomeScreen(
                    hub, CosmosState(), mutableFloatStateOf(1f),
                    filter = null, onFilter = {}, quiet = emptyList(),
                    onOpen = { spy.opened += "session:$it" },
                    onNewSession = { newSessions += "here" },
                    settings = {},
                    servers = servers, everywhere = everywhere,
                )
            }
        }
        rule.waitForIdle()
    }

    // ─── The three groups and their sort ───

    @Config(qualifiers = "+h1900dp")
    @Test fun groupsInOrderEachSorted() {
        home(FakeHub())
        fun seam(label: Int, n: Int) = rule.topOfSaid(str(R.string.group_said, str(label), context.resources.getQuantityString(R.plurals.sessions_count, n, n)))
        val needs = seam(R.string.group_needs, 2)
        val working = seam(R.string.group_working, 2)
        val done = seam(R.string.group_done, 3)
        assertTrue("Needs input, then Working, then Completed", needs < working && working < done)
        // Needs input and Working: A to Z.
        assertTrue(rule.topOf("Deploy check") < rule.topOf("Fix the build"))
        assertTrue(rule.topOf("Add widgets") < rule.topOf("Refactor parser"))
        assertTrue(rule.topOf("Fix the build") < working && rule.topOf("Refactor parser") < done)
        // Completed: newest first, not A to Z (Zeta 2m, Mid 20m, Alpha 1h).
        assertTrue(rule.topOf("Zeta cleanup") < rule.topOf("Mid sprint notes"))
        assertTrue(rule.topOf("Mid sprint notes") < rule.topOf("Alpha release"))
        rule.snap("home_groups")
    }

    @Test fun workingCardSaysItsTodoAndForHowLong() {
        home(FakeHub(sessions = only(Samples.PARSER)))
        rule.onNodeWithText("Running the tests", substring = true).assertExists()
        rule.onNodeWithText("3/7", substring = true).assertExists()
        rule.onNodeWithContentDescription(str(R.string.working_for_said, context.resources.getQuantityString(R.plurals.working_minutes, 12, 12)), useUnmergedTree = true).assertExists()
        rule.snap("home_working_card")
    }

    @Test fun tappingACardOpensItsSession() {
        home(FakeHub(sessions = only(Samples.MID)))
        rule.onNodeWithText("Mid sprint notes").performClick()
        assertEquals(listOf("session:${Samples.MID}"), spy.opened)
    }

    // ─── Allow and Deny on a Needs input card ───

    @Test fun needsYouCardAllowAnswersItsApproval() {
        val hub = FakeHub(sessions = only(Samples.BUILD))
        home(hub)
        rule.onNodeWithText("npm test", substring = true).assertExists()
        rule.onNodeWithText(str(R.string.deny)).assertExists()
        rule.onNodeWithText(str(R.string.allow)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("answerApproval(ap-test, Allow)"), hub.calls)
        rule.onNodeWithText(str(R.string.widget_allowing)).assertExists()
        rule.snap("home_card_allowing")
    }

    @Test fun needsYouCardDenyAnswersItsApproval() {
        val hub = FakeHub(sessions = only(Samples.BUILD))
        home(hub)
        rule.onNodeWithText(str(R.string.deny)).performClick()
        rule.waitForIdle()
        assertEquals(listOf("answerApproval(ap-test, Deny)"), hub.calls)
    }

    @Test fun anAnswerThatDidNotGetThroughBringsThePillsBack() {
        val hub = FakeHub(sessions = only(Samples.BUILD)).apply { answerOk = false }
        home(hub)
        rule.onNodeWithText(str(R.string.allow)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.allow)).assertExists()
    }

    @Test fun terminalDialogCardDeniesThroughTheMenu() {
        val hub = FakeHub(sessions = only(Samples.DEPLOY))
        home(hub)
        rule.onNodeWithText("kubectl get pods -n prod", substring = true).assertExists()
        rule.onNodeWithText(str(R.string.deny)).performClick()
        rule.waitForIdle()
        // "No, and tell Claude…" is the menu's third option.
        assertEquals(listOf("answerMenu(${Samples.DEPLOY}, m-deploy, 2, false)"), hub.calls)
    }

    // ─── The unread dot ───

    @Test fun unreadCompletedCardHasTheDot() {
        val s = Samples.sessions().first { it.id == Samples.MID }
        rule.setContent {
            Harness(spy) {
                androidx.compose.foundation.layout.Column {
                    SessionCard(s, Samples.NOW, scramble = false, onClick = {}, completed = true, unread = true)
                    SessionCard(s.copy(id = "read", name = "Read one"), Samples.NOW, scramble = false, onClick = {}, completed = true, unread = false)
                }
            }
        }
        assertEquals(1, rule.onAllNodesWithContentDescription(str(R.string.unread_said), useUnmergedTree = true).fetchSemanticsNodes().size)
        rule.snap("home_card_unread")
    }

    // ─── Search ───

    @Test fun searchOpensFiltersAndCloses() {
        home(FakeHub())
        rule.onNodeWithContentDescription(str(R.string.home_search)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("zeta")
        rule.waitForIdle()
        rule.onNodeWithText("Zeta cleanup").assertExists()
        rule.onNodeWithText("Fix the build").assertDoesNotExist()
        rule.onNodeWithText("Refactor parser").assertDoesNotExist()
        rule.snap("home_search")
        rule.onNode(hasSetTextAction()).performTextInput("zzz")
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.home_no_match)).assertExists()
        // The lens (lit) closes it, words and all.
        rule.onAllNodesWithContentDescription(str(R.string.home_search_close)).onFirst().performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
        rule.onNodeWithText("Fix the build").assertExists()
    }

    // ─── Swipes, as Settings → Card swipes has them ───

    @Test fun smartSwipeOpensANeedsInputCard() {
        home(FakeHub(sessions = only(Samples.BUILD)))
        rule.onNode(hasText("Fix the build")).performCustomAccessibilityActionWithLabel(str(R.string.open_session))
        assertEquals(listOf("session:${Samples.BUILD}"), spy.opened)
    }

    @Test fun smartSwipeStopsACompletedJobThatRuns() {
        val hub = FakeHub(sessions = only(Samples.ZETA))
        home(hub)
        rule.onNode(hasText("Zeta cleanup")).performCustomAccessibilityActionWithLabel(str(R.string.session_stop))
        rule.waitForIdle()
        assertEquals(listOf("stopSession(${Samples.ZETA})"), hub.calls)
    }

    @Test fun smartSwipeDeletesAStoppedJobAfterAsking() {
        val hub = FakeHub(sessions = only(Samples.ALPHA))
        home(hub)
        rule.onNode(hasText("Alpha release")).performCustomAccessibilityActionWithLabel(str(R.string.delete))
        rule.waitForIdle()
        // Delete always asks first: nothing has gone to the Mac yet.
        rule.onNodeWithText(str(R.string.session_delete_q, "Alpha release")).assertExists()
        assertTrue(hub.calls.isEmpty())
    }

    @Test fun workingCardHasNoSmartSwipe() {
        home(FakeHub(sessions = only(Samples.PARSER)))
        assertTrue(rule.onNode(hasText("Refactor parser")).customActions().isEmpty())
    }

    @Test fun rightSwipeFollowsTheSetting() {
        CardSwipePref.set(context, CardSwipes(left = SwipeChoice.Nothing, right = SwipeChoice.Rename))
        val hub = FakeHub(sessions = only(Samples.PARSER))
        home(hub)
        rule.onNode(hasText("Refactor parser")).performCustomAccessibilityActionWithLabel(str(R.string.session_rename))
        rule.waitForIdle()
        // Rename opens the menu's rename sheet; nothing goes until it's saved.
        rule.onNode(hasSetTextAction()).assertExists()
        assertTrue(hub.calls.isEmpty())
    }

    @Test fun aPickThatDoesNotFitLeavesThatWayWithoutASwipe() {
        // Stop doesn't fit a session that isn't a job: no swipe at all, never something else.
        CardSwipePref.set(context, CardSwipes(left = SwipeChoice.Stop, right = SwipeChoice.Nothing))
        home(FakeHub(sessions = only(Samples.MID)))
        assertTrue(rule.onNode(hasText("Mid sprint notes")).customActions().isEmpty())
    }

    // ─── All servers ───

    private fun everywhere(): Pair<FakeHub, Everywhere> {
        val z2 = FakeHub(sessions = only(Samples.BUILD, Samples.ZETA))
        val mini = FakeHub(
            sessions = listOf(Samples.session("s-mini", "Mini backup", SessionState.Idle, 10, project = "backup")),
            link = com.prudnikweb.hypernova.data.Link(LinkState.Offline),
            approvals = emptyMap(),
        )
        val e = Everywhere(
            listOf(
                ServerStatus("z2", "z2", LinkState.Connected, needsYou = 1, active = true) to z2,
                ServerStatus("mini", "mini", LinkState.Offline, needsYou = 0, active = false) to mini,
            ),
            pickedName = "z2",
            onOpen = { server, id -> spy.opened += "$server:$id" },
            onNewSession = { server -> newSessions += server },
            onRuns = { _, _ -> },
        )
        return z2 to e
    }

    @Config(qualifiers = "+h1200dp")
    @Test fun allServersTagsEachCardAndSaysAll() {
        val (z2, e) = everywhere()
        home(z2, e, ServerChoice(e.servers.map { it.first }, {}, {}, all = true))
        rule.onNodeWithText(str(R.string.servers_all_chip), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Fix the build")).assert(hasText("z2", substring = false))
        rule.onNode(hasText("Mini backup")).assert(hasText(str(R.string.servers_tag_offline, "mini")))
        // A card opens on its own computer.
        rule.onNodeWithText("Mini backup").performClick()
        assertEquals(listOf("mini:s-mini"), spy.opened)
        rule.snap("home_all_servers")
    }

    @Test fun allServersNewSessionAsksWhichComputer() {
        val (z2, e) = everywhere()
        home(z2, e, ServerChoice(e.servers.map { it.first }, {}, {}, all = true))
        rule.onAllNodesWithText(str(R.string.new_session)).onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.servers_new_on).uppercase()).assertExists()
        // Only a linked computer can be picked.
        rule.onNode(hasClickLabel(str(R.string.servers_new_on_label, "mini"))).assertIsNotEnabled()
        rule.snap("home_server_picker")
        rule.onNode(hasClickLabel(str(R.string.servers_new_on_label, "z2"))).performClick()
        rule.waitForIdle()
        assertEquals(listOf("z2"), newSessions)
    }

    @Test fun oneComputerNewSessionGoesStraightToTheSheet() {
        home(FakeHub(sessions = only(Samples.MID)))
        rule.onAllNodesWithText(str(R.string.new_session)).onFirst().performClick()
        assertEquals(listOf("here"), newSessions)
    }
}
