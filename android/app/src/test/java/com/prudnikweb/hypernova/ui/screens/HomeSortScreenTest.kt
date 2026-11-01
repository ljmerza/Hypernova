@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.prudnikweb.hypernova.ui.screens

import android.content.Context
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.PrefsKeyValue
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.fx.CosmosState
import com.prudnikweb.hypernova.ui.home.Everywhere
import com.prudnikweb.hypernova.ui.home.HomeScreen
import com.prudnikweb.hypernova.ui.home.HomeSort
import com.prudnikweb.hypernova.ui.home.HomeSortPref
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
 * Home's sort (owner, 2026-11-01; ui/home/HomeSort.kt, HomeSortSheet.kt): the button beside the
 * filter, its sheet, the order within the groups, the lit button while it isn't by name, kept on the
 * phone, and by server with "All servers".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "+h1900dp")
class HomeSortScreenTest {
    @get:Rule val rule = createComposeRule()
    private val spy = AppSpy()

    @Before fun setUp() = resetPrefs()

    private fun home(hub: FakeHub, everywhere: Everywhere? = null, servers: ServerChoice? = null) {
        rule.setContent {
            Harness(spy) {
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
        rule.waitForIdle()
    }

    private fun openSheet() {
        rule.onNode(hasClickLabel(str(R.string.sort_title))).performClick()
        rule.waitForIdle()
    }

    private fun row(id: Int) = rule.onNode(hasText(str(id)) and isSelectable())
    private fun lit(id: Int) = str(R.string.sort_button_on, str(id))
    private val saved get() = HomeSort.load(PrefsKeyValue(context.getSharedPreferences("hypernova_ui", Context.MODE_PRIVATE)))

    @Test fun theButtonOpensTheSheetWithNameTicked() {
        home(FakeHub())
        // By name: the button isn't lit.
        rule.onNodeWithContentDescription(str(R.string.sort_title)).assertExists()
        openSheet()
        rule.onNodeWithText(str(R.string.sort_title).uppercase()).assertExists()
        row(R.string.sort_name).assertIsSelected()
        row(R.string.sort_recent).assertExists()
        row(R.string.sort_project).assertExists()
        // One computer: no Server.
        row(R.string.sort_server).assertDoesNotExist()
        rule.onNodeWithText(str(R.string.sort_within_groups)).assertExists()
        rule.snap("home_sort_sheet")
    }

    @Test fun byNameEveryGroupIsAToZ() {
        home(FakeHub())
        // Completed by name too (it was newest first): Alpha, Mid, Zeta.
        assertTrue(rule.topOf("Alpha release") < rule.topOf("Mid sprint notes"))
        assertTrue(rule.topOf("Mid sprint notes") < rule.topOf("Zeta cleanup"))
        // The groups keep their order.
        assertTrue(rule.topOf("Fix the build") < rule.topOf("Add widgets") && rule.topOf("Refactor parser") < rule.topOf("Alpha release"))
    }

    @Test fun choosingRecentSortsNewestFirstLightsTheButtonAndIsKept() {
        home(FakeHub())
        openSheet()
        row(R.string.sort_recent).performClick()
        rule.waitForIdle()
        // The sheet closes; Completed newest first (Zeta 2m, Mid 20m, Alpha 1h), within its group.
        row(R.string.sort_recent).assertDoesNotExist()
        assertTrue(rule.topOf("Zeta cleanup") < rule.topOf("Mid sprint notes"))
        assertTrue(rule.topOf("Mid sprint notes") < rule.topOf("Alpha release"))
        assertTrue(rule.topOf("Refactor parser") < rule.topOf("Zeta cleanup"))
        rule.onNodeWithContentDescription(lit(R.string.sort_recent)).assertExists()
        assertEquals(HomeSort.Recent, saved)
        rule.snap("home_sort_recent")
    }

    @Test fun projectSortsByProjectThenName() {
        HomeSortPref.set(context, HomeSort.Project)
        home(FakeHub())
        // Needs input: Fix the build (hypernova) before Deploy check (infra); Completed: hypernova's, then notes'.
        assertTrue(rule.topOf("Fix the build") < rule.topOf("Deploy check"))
        assertTrue(rule.topOf("Zeta cleanup") < rule.topOf("Mid sprint notes"))
        assertTrue(rule.topOf("Alpha release") < rule.topOf("Zeta cleanup"))
        rule.onNodeWithContentDescription(lit(R.string.sort_project)).assertExists()
    }

    @Test fun aKeptSortIsBackAndNameTurnsTheButtonOff() {
        HomeSortPref.set(context, HomeSort.Recent)
        home(FakeHub())
        rule.onNodeWithContentDescription(lit(R.string.sort_recent)).assertExists()
        openSheet()
        row(R.string.sort_recent).assertIsSelected()
        row(R.string.sort_name).performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription(str(R.string.sort_title)).assertExists()
        assertEquals(HomeSort.Name, saved)
    }

    @Test fun searchResultsAreSortedToo() {
        HomeSortPref.set(context, HomeSort.Recent)
        home(FakeHub())
        rule.onNodeWithContentDescription(str(R.string.home_search)).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("hypernova")
        rule.waitForIdle()
        assertTrue(rule.topOf("Zeta cleanup") < rule.topOf("Alpha release"))
    }

    // ─── All servers ───

    private fun everywhere(): Pair<FakeHub, Everywhere> {
        val z2 = FakeHub(sessions = Samples.sessions().filter { it.id in setOf(Samples.ZETA, Samples.ALPHA) })
        val mini = FakeHub(
            sessions = listOf(Samples.session("s-mini", "Mini backup", SessionState.Idle, 10, project = "backup")),
            approvals = emptyMap(),
        )
        val e = Everywhere(
            listOf(
                ServerStatus("z2", "z2", LinkState.Connected, needsYou = 0, active = true) to z2,
                ServerStatus("mini", "mini", LinkState.Connected, needsYou = 0, active = false) to mini,
            ),
            pickedName = "z2",
            onOpen = { server, id -> spy.opened += "$server:$id" },
            onNewSession = {},
            onRuns = { _, _ -> },
        )
        return z2 to e
    }

    @Test fun allServersOffersServerAndSortsEveryComputersCardsByIt() {
        val (z2, e) = everywhere()
        home(z2, e, ServerChoice(e.servers.map { it.first }, {}, {}, all = true))
        // By name across both computers: Alpha (z2), Mini (mini), Zeta (z2).
        assertTrue(rule.topOf("Alpha release") < rule.topOf("Mini backup") && rule.topOf("Mini backup") < rule.topOf("Zeta cleanup"))
        openSheet()
        row(R.string.sort_server).performClick()
        rule.waitForIdle()
        // mini's first, then z2's by name.
        assertTrue(rule.topOf("Mini backup") < rule.topOf("Alpha release") && rule.topOf("Alpha release") < rule.topOf("Zeta cleanup"))
        rule.onNodeWithContentDescription(lit(R.string.sort_server)).assertExists()
        assertEquals(HomeSort.Server, saved)
        rule.snap("home_sort_all_servers")
    }

    @Test fun serverKeptWaitsForAllServers() {
        HomeSortPref.set(context, HomeSort.Server)
        home(FakeHub())
        // One computer: by name, and the button isn't lit.
        rule.onNodeWithContentDescription(str(R.string.sort_title)).assertExists()
        assertTrue(rule.topOf("Alpha release") < rule.topOf("Zeta cleanup"))
        assertEquals(HomeSort.Server, saved)
    }
}
