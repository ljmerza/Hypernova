package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.MAC_RESTART
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's three groups (homeGroups): needs input and working A to Z by name, completed newest first. */
class HomeGroupsTest {
    private fun s(id: String, name: String, state: SessionState, phase: Phase = Phase.of(state), agents: Int = 0, endedBy: String? = null, updatedAt: Long = 0L) =
        Session(hex = id, id = id, name = name, project = "p", state = state, model = "opus", mode = "default", activity = "", updatedAt = updatedAt, phase = phase, agentsRunning = agents, endedBy = endedBy)

    private fun names(groups: List<Pair<HomeGroup, List<Session>>>) = groups.map { (g, list) -> g to list.map { it.name } }

    @Test fun eachSessionSitsInItsGroupInOrder() {
        val groups = homeGroups(
            listOf(
                s("a", "idle", SessionState.Idle),
                s("b", "agents", SessionState.Idle, agents = 2),
                s("c", "asks", SessionState.NeedsYou, agents = 1),
                s("d", "works", SessionState.Working),
                s("e", "cut", SessionState.Idle, Phase.Ended, endedBy = MAC_RESTART),
            ),
        )
        assertEquals(
            listOf(
                HomeGroup.NeedsInput to listOf("asks"),
                // Its agents still run: working, though its main agent is idle.
                HomeGroup.Working to listOf("agents", "works"),
                // A restart's cut ones are with the rest, by name.
                HomeGroup.Completed to listOf("cut", "idle"),
            ),
            names(groups),
        )
    }

    @Test fun workingByNameNotActivitySoCardsHoldStill() {
        // The newest activity first in, as the hub has them: the list still reads A to Z, any case.
        val groups = homeGroups(
            listOf(
                s("1", "zeta", SessionState.Working, updatedAt = 30),
                s("2", "Alpha", SessionState.Working, updatedAt = 20),
                s("3", "beta", SessionState.NeedsYou, updatedAt = 10),
                s("4", "Gamma", SessionState.NeedsYou, updatedAt = 40),
            ),
        )
        assertEquals(listOf(HomeGroup.NeedsInput to listOf("beta", "Gamma"), HomeGroup.Working to listOf("Alpha", "zeta")), names(groups))
        // A card moves only when its group changes: beta starts working, the others keep their order.
        val after = homeGroups(
            listOf(
                s("3", "beta", SessionState.Working, updatedAt = 50),
                s("1", "zeta", SessionState.Working, updatedAt = 30),
                s("2", "Alpha", SessionState.Working, updatedAt = 60),
            ),
        )
        assertEquals(listOf(HomeGroup.Working to listOf("Alpha", "beta", "zeta")), names(after))
    }

    private val completed = listOf(
        s("1", "alpha", SessionState.Idle, updatedAt = 10),
        s("2", "zeta", SessionState.Idle, updatedAt = 30),
        s("3", "Beta", SessionState.Idle, updatedAt = 20),
        s("4", "cut", SessionState.Idle, Phase.Ended, endedBy = MAC_RESTART, updatedAt = 25),
    )

    @Test fun completedByNameByDefault() {
        // The owner, 2026-11-01: the sort button, Name by default, in every group (Completed was newest first).
        assertEquals(listOf(HomeGroup.Completed to listOf("alpha", "Beta", "cut", "zeta")), names(homeGroups(completed)))
    }

    @Test fun completedNewestFirstByRecent() {
        // The one that finished last at the top, by the time its card says (Completed's order before 2026-11-01).
        assertEquals(listOf(HomeGroup.Completed to listOf("zeta", "cut", "Beta", "alpha")), names(homeGroups(completed, HomeSort.Recent)))
    }

    @Test fun theSameNameFallsBackToTheId() {
        val list = listOf(s("b", "fix", SessionState.Working), s("a", "Fix", SessionState.Working), s("c", "fix", SessionState.Working))
        val ids = homeGroups(list).single().second.map { it.id }
        assertEquals(listOf("a", "b", "c"), ids)
        assertEquals(ids, homeGroups(list.reversed()).single().second.map { it.id })
    }

    @Test fun completedAtTheSameTimeByName() {
        val list = listOf(s("b", "fix", SessionState.Idle, updatedAt = 5), s("z", "Apply", SessionState.Idle, updatedAt = 5), s("a", "Fix", SessionState.Idle, updatedAt = 5))
        val ids = homeGroups(list, HomeSort.Recent).single().second.map { it.id }
        assertEquals(listOf("z", "a", "b"), ids)
        assertEquals(ids, homeGroups(list.reversed(), HomeSort.Recent).single().second.map { it.id })
    }

    @Test fun anEmptyGroupHasNoSeam() {
        assertTrue(homeGroups(emptyList()).isEmpty())
        assertEquals(listOf(HomeGroup.NeedsInput), homeGroups(listOf(s("a", "asks", SessionState.NeedsYou))).map { it.first })
    }

    @Test fun eachSeamHasItsOwnKey() {
        val keys = HomeGroup.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun theAccountCardFollowsWhatsOverIt() {
        assertEquals(0, homeHeadIndex(filter = false, offline = false))
        assertEquals(2, homeHeadIndex(filter = true, offline = true))
    }
}
