package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.HistorySearch
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.MAC_RESTART
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionAutomation
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TextHit
import com.prudnikweb.hypernova.data.TextHits
import com.prudnikweb.hypernova.ui.automations.foldRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** History with "All servers" (AllHistory.kt): every computer's ended sessions by time, each row going to its own. */
class AllHistoryTest {
    private fun s(
        id: String, name: String, updatedAt: Long = 0L, project: String = "p", activity: String = "",
        phase: Phase = Phase.Ended, endedBy: String? = null, automation: SessionAutomation? = null,
    ) = Session(
        hex = id.take(2), id = id, name = name, project = project, state = SessionState.Idle, model = "opus", mode = "default",
        activity = activity, updatedAt = updatedAt, phase = phase, endedBy = endedBy, automation = automation,
    )

    /** Each computer's hub is its name here: where a row's opening would go. */
    private fun part(id: String, name: String, sessions: List<Session>, state: LinkState = LinkState.Connected, hits: TextHits? = null) =
        ServerPart(id, name, state, hub = "hub-$name", sessions = sessions, hits = hits)

    private val brief = SessionAutomation("au1", "Morning brief", null)

    private val z2 = part(
        "0", "z2",
        listOf(
            s("a1", "fix the widget", updatedAt = 50),
            s("a2", "still working", updatedAt = 99, phase = Phase.Idle),
            s("a3", "old notes", updatedAt = 10),
            s("a4", "brief one", updatedAt = 40, automation = brief),
            s("a5", "brief two", updatedAt = 20, automation = brief),
        ),
    )
    private val gym = part(
        "b", "gym",
        listOf(
            s("b1", "backup check", updatedAt = 60),
            s("b2", "cut short", updatedAt = 70, endedBy = MAC_RESTART),
            s("b3", "brief", updatedAt = 30, automation = brief),
        ),
    )

    private fun shape(rows: List<HistoryItem<String>>) = rows.map { "${it.name}:${it.session.name}" }

    @Test fun everyComputersEndedSessionsMostRecentFirst() {
        assertEquals(
            // Live ones are Home's, and what a restart cut short stays there; runs fold per computer.
            listOf("gym:backup check", "z2:fix the widget", "z2:brief one", "gym:brief", "z2:old notes"),
            shape(AllHistory.rows(listOf(z2, gym))),
        )
    }

    @Test fun anAutomationsRunsFoldOnEachComputerOnItsOwn() {
        val rows = AllHistory.rows(listOf(z2, gym)).filter { it.entry.automation != null }
        // The same automation id on two computers is two automations: two rows, each with its own count.
        assertEquals(listOf("0" to 2, "b" to 1), rows.map { it.server to it.entry.count })
        assertEquals(2, rows.map { it.key }.toSet().size)
        assertEquals(listOf("hub-z2", "hub-gym"), rows.map { it.hub })
    }

    @Test fun eachRowGoesToItsOwnComputer() {
        val rows = AllHistory.rows(listOf(z2, gym))
        val b1 = rows.single { it.session.id == "b1" }
        assertEquals("b", b1.server)
        assertEquals("gym", b1.name)
        assertEquals("hub-gym", b1.hub)
        assertEquals(AllHome.key("b", "b1"), b1.key)
    }

    @Test fun theSameIdOnTwoComputersIsTwoRows() {
        val rows = AllHistory.rows(listOf(part("0", "z2", listOf(s("same", "x", 5))), part("b", "gym", listOf(s("same", "x", 5)))))
        assertEquals(2, rows.map { it.key }.toSet().size)
        // The same moment: in the order paired.
        assertEquals(listOf("z2", "gym"), rows.map { it.name })
    }

    @Test fun searchFindsOnEveryComputer() {
        val rows = AllHistory.rows(listOf(z2, gym), "widget backup")
        assertTrue(rows.isEmpty())
        assertEquals(listOf("a1"), AllHistory.rows(listOf(z2, gym), "widget").map { it.session.id })
        assertEquals(listOf("b1"), AllHistory.rows(listOf(z2, gym), "backup").map { it.session.id })
        assertEquals(5, AllHistory.rows(listOf(z2, gym), "  ").size)
    }

    @Test fun eachComputersFullTextHitsAreMergedIntoItsOwn() {
        fun hit(id: String) = TextHit(id, "…the VPN port…", listOf(5 until 8), fromClaude = true, matches = 1)
        // z2 says it found "vpn" in a3 (and b1, which isn't its own); gym found it in b1.
        val one = part("0", "z2", z2.sessions, hits = TextHits(listOf(hit("a3"), hit("b1")), partial = false))
        val two = part("b", "gym", gym.sessions, hits = TextHits(listOf(hit("b1")), partial = true))
        val rows = AllHistory.rows(listOf(one, two), "vpn")
        assertEquals(listOf("b:b1", "0:a3"), rows.map { it.key })
        // Each row's snippet is its own computer's.
        assertTrue(rows.all { it.hit != null })
        // A hit on a session Home keeps (a live one) stays off History.
        val live = part("0", "z2", z2.sessions, hits = TextHits(listOf(hit("a2")), partial = false))
        assertTrue(AllHistory.rows(listOf(live), "vpn").isEmpty())
    }

    @Test fun theProjectNarrowsEveryComputer() {
        val one = part("0", "z2", listOf(s("a1", "x", 3, project = "hypernova"), s("a2", "y", 2, project = "0stems")))
        val two = part("b", "gym", listOf(s("b1", "z", 1, project = "hypernova")))
        assertEquals(listOf("a1", "b1"), AllHistory.rows(listOf(one, two), project = "hypernova").map { it.session.id })
        // One chip for a project of the same name on two computers, the busiest first.
        assertEquals(listOf("hypernova", "0stems"), AllHistory.projects(listOf(one, two)))
        assertEquals(3, AllHistory.endedCount(listOf(one, two)))
    }

    @Test fun anOfflineComputersRowsStayDimmedAndItIsNamed() {
        val down = part("b", "gym", gym.sessions, state = LinkState.Offline)
        val rows = AllHistory.rows(listOf(z2, down))
        assertTrue(rows.filter { it.server == "b" }.let { it.isNotEmpty() && it.all { r -> r.offline } })
        assertTrue(rows.filter { it.server == "0" }.none { it.offline })
        assertEquals(listOf("gym"), AllHistory.offline(listOf(z2, down)))
        // Reconnecting is still current; connecting (nothing heard yet) isn't, even with nothing to list.
        assertTrue(AllHistory.offline(listOf(part("b", "gym", gym.sessions, LinkState.Reconnecting))).isEmpty())
        assertEquals(listOf("mini"), AllHistory.offline(listOf(part("d", "mini", emptyList(), LinkState.Connecting))))
    }

    @Test fun oneComputerIsHistoryAsItWas() {
        val hits = TextHits(listOf(TextHit("a3", "notes", listOf(0 until 5), fromClaude = false, matches = 1)), partial = false)
        val one = part("0", "z2", z2.sessions, state = LinkState.Offline, hits = hits)
        for ((query, project) in listOf("" to null, "notes" to null, "brief" to null, "" to "p")) {
            val rows = AllHistory.rows(listOf(one), query, project, tagged = false)
            val ended = AllHistory.ended(one.sessions)
            val before = foldRuns(HistorySearch.merge(ended, HistorySearch.find(ended, query, project), hits, project))
            assertEquals(before.map { it.key }, rows.map { it.key })
            // No tag, never dimmed, whatever its link says.
            assertTrue(rows.all { it.server == null && it.name == null && !it.offline })
        }
        assertNull(AllHistory.rows(listOf(one), tagged = false).single { it.session.id == "a1" }.hit)
        assertFalse(AllHistory.rows(listOf(one), "notes", tagged = false).single().hit == null)
    }
}
