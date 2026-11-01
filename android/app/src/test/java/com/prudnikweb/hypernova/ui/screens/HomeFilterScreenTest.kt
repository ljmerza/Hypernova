@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.prudnikweb.hypernova.ui.screens

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.PrefsKeyValue
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.fx.CosmosState
import com.prudnikweb.hypernova.ui.home.Everywhere
import com.prudnikweb.hypernova.ui.home.HomeFilter
import com.prudnikweb.hypernova.ui.home.HomeFilterPref
import com.prudnikweb.hypernova.ui.home.HomeGroup
import com.prudnikweb.hypernova.ui.home.HomeScreen
import com.prudnikweb.hypernova.ui.home.ServerChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home's filter (owner, 2026-11-01; ui/home/HomeFilter.kt, HomeFilterSheet.kt): the button beside
 * the search, its sheet, the chip over the list that says what's shown and how many that need you
 * it hides, Clear, the empty result, with one computer and with "All servers".
 */
@RunWith(RobolectricTestRunner::class)
class HomeFilterScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    @Before fun setUp() = resetPrefs()

    private fun only(vararg ids: String): List<Session> = Samples.sessions().filter { it.id in ids }

    private fun home(hub: FakeHub, everywhere: Everywhere? = null, servers: ServerChoice? = null, fontScale: Float? = null) {
        rule.setContent {
            Harness(spy) {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale ?: d.fontScale)) {
                    HomeScreen(
                        hub, CosmosState(), mutableFloatStateOf(1f),
                        filter = null, onFilter = {}, quiet = emptyList(),
                        onOpen = { spy.opened += "session:$it" },
                        onNewSession = {},
                        settings = {},
                        servers = servers, everywhere = everywhere,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun openSheet() {
        rule.onNode(hasClickLabel(str(R.string.filter_title))).performClick()
        rule.waitForIdle()
    }

    /** A choice in the sheet: a state or the preset (a switch), or one of a row (All, a project, a server). */
    private fun toggle(text: String) = rule.onNode(hasText(text) and isToggleable())
    private fun choice(text: String) = rule.onNode(hasText(text) and isSelectable())

    private fun done() {
        rule.onNodeWithText(str(R.string.filter_done)).performClick()
        rule.waitForIdle()
    }

    private fun chip(said: String) = rule.onNodeWithContentDescription(said)
    private fun showing(what: String) = str(R.string.filter_showing, what)
    private fun hidden(n: Int) = context.resources.getQuantityString(R.plurals.filter_waiting_hidden, n, n)
    private val saved get() = HomeFilter.load(PrefsKeyValue(context.getSharedPreferences("hypernova_ui", Context.MODE_PRIVATE)))

    @Test fun theButtonOpensTheSheet() {
        home(FakeHub())
        // Off: nothing over the list, and the button isn't lit.
        rule.onNodeWithContentDescription(str(R.string.filter_button_on)).assertDoesNotExist()
        openSheet()
        rule.onNodeWithText(str(R.string.filter_title).uppercase()).assertExists()
        toggle(str(R.string.filter_active)).assertExists()
        for (g in listOf(R.string.filter_needs_you, R.string.filter_working, R.string.filter_completed)) toggle(str(g)).assertIsOn()
        // Three projects on Home (hypernova, infra, notes): their row, All chosen. One computer: no server row.
        choice("infra").assertExists()
        rule.onNodeWithText(str(R.string.filter_project).uppercase()).assertExists()
        rule.onNodeWithText(str(R.string.filter_server).uppercase()).assertDoesNotExist()
        // Claude Code alone here: no agent row.
        rule.onNodeWithText(str(R.string.filter_agent).uppercase()).assertDoesNotExist()
        rule.snap("home_filter_sheet")
    }

    @Test fun activeShowsNeedsYouAndWorkingAndTheChipSaysSo() {
        home(FakeHub())
        openSheet()
        toggle(str(R.string.filter_active)).performClick()
        rule.waitForIdle()
        toggle(str(R.string.filter_active)).assertIsOn()
        done()
        rule.onNodeWithText("Fix the build").assertExists()
        rule.onNodeWithText("Refactor parser").assertExists()
        rule.onNodeWithText("Zeta cleanup").assertDoesNotExist()
        rule.onNodeWithText("Mid sprint notes").assertDoesNotExist()
        chip(showing("Needs you + Working")).assertExists()
        rule.onNodeWithText(showing("Needs you + Working").uppercase(), useUnmergedTree = true).assertExists()
        // Nothing that needs you is hidden by it: no amber count.
        rule.onNodeWithText(hidden(2).uppercase(), substring = true, ignoreCase = true).assertDoesNotExist()
        // The button is lit, and the choice is kept on the phone.
        rule.onNodeWithContentDescription(str(R.string.filter_button_on)).assertExists()
        assertEquals(HomeFilter.ACTIVE, saved.groups)
        rule.snap("home_filter_active")
    }

    @Test fun theChipCountsWhatNeedsYouOutsideIt() {
        HomeFilterPref.set(context, HomeFilter(groups = setOf(HomeGroup.Working)))
        home(FakeHub())
        chip(showing("Working")).assertExists()
        rule.onNodeWithText("Refactor parser").assertExists()
        rule.onNodeWithText("Fix the build").assertDoesNotExist()
        // Fix the build and Deploy check wait on the owner, outside the filter.
        rule.onNodeWithText(hidden(2)).assertExists()
    }

    @Config(qualifiers = "+h1900dp")
    @Test fun clearShowsEverythingAgain() {
        HomeFilterPref.set(context, HomeFilter(groups = setOf(HomeGroup.Completed), project = "hypernova"))
        home(FakeHub())
        chip(showing("Completed · hypernova")).assertExists()
        rule.onNodeWithText("Mid sprint notes").assertDoesNotExist()
        chip(showing("Completed · hypernova")).performClick()
        rule.waitForIdle()
        chip(showing("Completed · hypernova")).assertDoesNotExist()
        for (name in listOf("Fix the build", "Refactor parser", "Mid sprint notes")) rule.onNodeWithText(name).assertExists()
        assertEquals(HomeFilter.NONE, saved)
    }

    @Test fun theSheetsClearToo() {
        HomeFilterPref.set(context, HomeFilter(groups = HomeFilter.ACTIVE))
        home(FakeHub())
        openSheet()
        rule.onNodeWithText(str(R.string.filter_clear)).performClick()
        rule.waitForIdle()
        toggle(str(R.string.filter_completed)).assertIsOn()
        assertEquals(HomeFilter.NONE, saved)
    }

    @Test fun nothingLeftSaysSoWithClear() {
        HomeFilterPref.set(context, HomeFilter(groups = setOf(HomeGroup.Completed)))
        home(FakeHub(sessions = only(Samples.BUILD, Samples.PARSER)))
        rule.onNodeWithText(str(R.string.filter_none)).assertExists()
        rule.onNodeWithText("Fix the build").assertDoesNotExist()
        rule.snap("home_filter_empty")
        rule.onNodeWithText(str(R.string.filter_clear)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(R.string.filter_none)).assertDoesNotExist()
        rule.onNodeWithText("Fix the build").assertExists()
    }

    @Test fun aSearchLooksOnlyWithinTheFilter() {
        HomeFilterPref.set(context, HomeFilter(groups = HomeFilter.ACTIVE))
        home(FakeHub())
        rule.onNodeWithContentDescription(str(R.string.home_search)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("zeta")
        rule.waitForIdle()
        // Zeta cleanup is Completed: the filter leaves it out, so the search finds nothing.
        rule.onNodeWithText("Zeta cleanup").assertDoesNotExist()
        rule.onNodeWithText(str(R.string.home_no_match)).assertExists()
        chip(showing("Needs you + Working")).assertExists()
    }

    // ─── All servers ───

    private fun everywhere(): Pair<FakeHub, Everywhere> {
        val z2 = FakeHub(sessions = only(Samples.BUILD, Samples.ZETA))
        val mini = FakeHub(
            sessions = listOf(Samples.session("s-mini", "Mini backup", SessionState.Working, 10, project = "backup")),
            approvals = emptyMap(),
        )
        val e = Everywhere(
            listOf(
                ServerStatus("z2", "z2", LinkState.Connected, needsYou = 1, active = true) to z2,
                ServerStatus("mini", "mini", LinkState.Connected, needsYou = 0, active = false) to mini,
            ),
            pickedName = "z2",
            onOpen = { server, id -> spy.opened += "$server:$id" },
            onNewSession = {},
            onRuns = { _, _ -> },
        )
        return z2 to e
    }

    @Test fun allServersOffersEachServerAndShowsOnlyIts() {
        val (z2, e) = everywhere()
        home(z2, e, ServerChoice(e.servers.map { it.first }, {}, {}, all = true))
        openSheet()
        rule.onNodeWithText(str(R.string.filter_server).uppercase()).assertExists()
        // Both computers' projects: backup (mini's) and hypernova (z2's).
        choice("backup").assertExists()
        choice("mini").performClick()
        rule.waitForIdle()
        choice("mini").assertIsSelected()
        rule.snap("home_filter_sheet_all_servers")
        done()
        rule.onNodeWithText("Mini backup").assertExists()
        rule.onNodeWithText("Fix the build").assertDoesNotExist()
        rule.onNodeWithText("Zeta cleanup").assertDoesNotExist()
        chip(showing("mini")).assertExists()
        // z2's Fix the build needs the owner, outside the filter.
        rule.onNodeWithText(hidden(1)).assertExists()
        assertEquals("mini", saved.server)
        rule.snap("home_filter_all_servers")
    }

    @Test fun aServerKeptForAllServersWaitsWithOneComputer() {
        HomeFilterPref.set(context, HomeFilter(server = "mini"))
        home(FakeHub())
        // One computer: no server filter applies, nothing is narrowed, no chip.
        rule.onNodeWithText("Fix the build").assertExists()
        chip(showing("mini")).assertDoesNotExist()
        rule.onNodeWithContentDescription(str(R.string.filter_button_on)).assertDoesNotExist()
        // A change here keeps it for All servers.
        openSheet()
        toggle(str(R.string.filter_active)).performClick()
        rule.waitForIdle()
        assertEquals(HomeFilter(groups = HomeFilter.ACTIVE, server = "mini"), saved)
    }

    // ─── The top bar still fits ───

    /**
     * [text] is drawn whole: as wide as its words and not cut in height (a part of a FitRow is
     * measured with no width limit, where hasVisualOverflow says "overflow" for any text).
     */
    private fun assertWhole(text: String) {
        val node = rule.onNode(hasText(text) and isPlaced() and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val l = layouts.single()
        assertTrue("\"$text\" is cut short", l.size.width >= kotlin.math.ceil(l.multiParagraph.intrinsics.maxIntrinsicWidth) - 0.5f && !l.didOverflowHeight)
    }

    private fun placed(matcher: SemanticsMatcher) = rule.onNode(matcher and isPlaced(), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** At the usual font, on the tests' 392dp phone, New session keeps its words beside the filter and the lens. */
    @Test fun theTopBarKeepsNewSessionsWordsAtTheUsualFont() {
        home(FakeHub())
        assertWhole(str(R.string.new_session))
        assertWhole("z2")
        rule.onNode(hasContentDescription(str(R.string.new_session)) and isPlaced()).assertDoesNotExist()
    }

    @Test fun theTopBarKeepsNewSessionsWordsWithAllServers() {
        val (z2, e) = everywhere()
        home(z2, e, ServerChoice(e.servers.map { it.first }, {}, {}, all = true))
        assertWhole(str(R.string.new_session))
        assertWhole(str(R.string.servers_all_chip))
    }

    /**
     * The owner's rule (the server menu had names squeezed out before): at a large font, with All,
     * the filter, the lens and New session, the chip still says "All" whole, on screen. New session
     * gives way to its plus alone, which still starts one.
     */
    @Test fun theTopBarFitsAtALargeFontWithAllServers() {
        val (z2, e) = everywhere()
        home(z2, e, ServerChoice(e.servers.map { it.first }, {}, {}, all = true), fontScale = 1.3f)
        assertWhole(str(R.string.servers_all_chip))
        val all = placed(hasText(str(R.string.servers_all_chip)))
        val plus = placed(hasContentDescription(str(R.string.new_session)))
        val lens = placed(hasContentDescription(str(R.string.home_search)))
        val filter = placed(hasClickLabel(str(R.string.filter_title)))
        val width = rule.onNode(isRoot()).fetchSemanticsNode().boundsInRoot.right
        assertTrue("in order: filter, lens, New session, All", filter.right <= lens.left && lens.right <= plus.left && plus.right <= all.left)
        assertTrue("All is on screen", all.right <= width && all.width > 0f)
        rule.onNode(hasText(str(R.string.new_session)) and isPlaced(), useUnmergedTree = true).assertDoesNotExist()
        rule.snap("home_filter_top_bar_large_font")
    }
}
