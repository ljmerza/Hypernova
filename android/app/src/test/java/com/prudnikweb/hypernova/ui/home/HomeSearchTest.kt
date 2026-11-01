package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.HistorySearch
import com.prudnikweb.hypernova.data.HomeSearch
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TextHit
import com.prudnikweb.hypernova.data.TextHits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Searching Home's sessions (data/HomeSearch.kt, idea HM8), and the groups it leaves (homeGroups). */
class HomeSearchTest {
    private fun s(
        hex: String, name: String, project: String, state: SessionState = SessionState.Idle,
        activity: String = "", cwd: String? = null, updatedAt: Long = 0L, phase: Phase = Phase.of(state),
    ) = Session(hex = hex, id = "id-$hex", name = name, project = project, state = state, model = "opus", mode = "default", activity = activity, updatedAt = updatedAt, cwd = cwd, phase = phase)

    private val home = listOf(
        s("c7", "Fix the widget crash", "hypernova", SessionState.Working, "Running the tests", cwd = "/home/u/projects/hypernova"),
        s("a3", "Weather in Lisbon", "pogoda", SessionState.NeedsYou, "Allow Bash: curl wttr.in?", cwd = "/home/u/projects/zazhimayu-pogoda"),
        s("4f", "release landing copy", "0stems", SessionState.Idle, "Pushed to main.", updatedAt = 20L),
        s("9b", "Agent cards", "hypernova", SessionState.Idle, "Done: the cards are in.", updatedAt = 10L),
    )

    private fun names(list: List<Session>) = list.map { it.name }.sorted()

    private fun hit(id: String, snippet: String = "…the VPN port…") = TextHit(id, snippet, listOf(5 until 8), fromClaude = true, matches = 1)

    @Test fun blankFindsEverything() {
        assertEquals(names(home), names(HomeSearch.find(home, "  ")))
    }

    @Test fun findsByTitle() {
        assertEquals(listOf("Fix the widget crash"), names(HomeSearch.find(home, "widget")))
    }

    @Test fun findsByProject() {
        assertEquals(listOf("Agent cards", "Fix the widget crash"), names(HomeSearch.find(home, "hypernova")))
    }

    @Test fun findsByFolderName() {
        // The folder's own name, though the project is called something else.
        assertEquals(listOf("Weather in Lisbon"), names(HomeSearch.find(home, "zazhimayu")))
    }

    @Test fun findsByLastLine() {
        assertEquals(listOf("release landing copy"), names(HomeSearch.find(home, "pushed")))
    }

    @Test fun findsByHexId() {
        assertEquals(listOf("Fix the widget crash"), names(HomeSearch.find(home, "c7")))
        assertEquals(listOf("Agent cards"), names(HomeSearch.find(home, "9b")))
    }

    @Test fun anyCase() {
        assertEquals(listOf("Weather in Lisbon"), names(HomeSearch.find(home, "LISBON")))
        assertEquals(listOf("Fix the widget crash"), names(HomeSearch.find(home, "C7")))
        assertEquals(listOf("Agent cards"), names(HomeSearch.find(home, "aGeNt CaRdS")))
    }

    @Test fun everyWordHasToBeThere() {
        assertEquals(listOf("Agent cards"), names(HomeSearch.find(home, "hypernova cards")))
        assertTrue(HomeSearch.find(home, "hypernova lisbon").isEmpty())
    }

    @Test fun nothingMatches() {
        assertTrue(HomeSearch.find(home, "qqqq").isEmpty())
    }

    @Test fun theFolderIsSearchedOnlyWhenItIsntTheProject() {
        assertEquals("Fix the widget crash hypernova Running the tests c7", HomeSearch.text(home[0]))
        assertEquals("Weather in Lisbon pogoda zazhimayu-pogoda Allow Bash: curl wttr.in? a3", HomeSearch.text(home[1]))
    }

    @Test fun groupsWithNoMatchHideAndCountTheMatches() {
        val groups = homeGroups(HomeSearch.find(home, "hypernova"))
        assertEquals(listOf(HomeGroup.Working to 1, HomeGroup.Completed to 1), groups.map { (g, list) -> g to list.size })
        assertEquals(listOf(HomeGroup.NeedsInput), homeGroups(HomeSearch.find(home, "lisbon")).map { it.first })
        assertTrue(homeGroups(HomeSearch.find(home, "qqqq")).isEmpty())
    }

    @Test fun groupsKeepTheirOrderWhileSearching() {
        val done = listOf(
            s("01", "beta notes", "docs", updatedAt = 5L),
            s("02", "alpha notes", "docs", updatedAt = 50L),
            s("03", "zeta notes", "docs", SessionState.Working),
            s("04", "eta notes", "docs", SessionState.Working),
        )
        val groups = homeGroups(HomeSearch.find(done, "notes")).map { (g, list) -> g to list.map { it.name } }
        // Working and Completed A to Z (the default sort), as without a search.
        assertEquals(listOf(HomeGroup.Working to listOf("eta notes", "zeta notes"), HomeGroup.Completed to listOf("alpha notes", "beta notes")), groups)
    }

    @Test fun fullTextHitsOnHomeSessionsAreMergedIn() {
        // "vpn" is nowhere in a title, project, folder, last line or id, not even as scattered letters:
        // only the Mac found it inside.
        assertTrue(HomeSearch.find(home, "vpn").isEmpty())
        val hits = TextHits(listOf(hit("id-9b"), hit("id-4f")), partial = false)
        assertEquals(listOf("Agent cards", "release landing copy"), names(HomeSearch.find(home, "vpn", hits)))
        // A local match and a hit on the same session: once.
        val both = HomeSearch.find(home, "widget", TextHits(listOf(hit("id-c7"), hit("id-9b")), partial = false))
        assertEquals(listOf("Agent cards", "Fix the widget crash"), names(both))
    }

    @Test fun hitsOnSessionsNotOnHomeAreLeftOut() {
        // The Mac answers for History's sessions too; Home shows only its own.
        val hits = TextHits(listOf(hit("id-ended"), hit("id-a3")), partial = false)
        assertEquals(listOf("Weather in Lisbon"), names(HomeSearch.find(home, "vpn", hits)))
    }

    @Test fun hitsDontCountWithoutWords() {
        val hits = TextHits(listOf(hit("id-9b")), partial = false)
        assertEquals(names(home), names(HomeSearch.find(home, "", hits)))
    }

    @Test fun historyStillLeavesHomeSessionsOut() {
        // The same Mac answer, merged for History's ended sessions, drops the ones on Home.
        val ended = listOf(s("e1", "Old one", "p", phase = Phase.Ended))
        val hits = TextHits(listOf(hit("id-e1"), hit("id-9b")), partial = false)
        assertEquals(listOf("Old one"), HistorySearch.merge(ended, emptyList(), hits).map { it.name })
    }
}
