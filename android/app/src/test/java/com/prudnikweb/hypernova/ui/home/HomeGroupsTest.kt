package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.MAC_RESTART
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's three groups (homeGroups): needs input, working, completed, each A to Z by name. */
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

    @Test fun byNameNotActivitySoCardsHoldStill() {
        // The newest activity first in, as the hub has them: the list still reads A to Z, any case.
        val groups = homeGroups(
            listOf(
                s("1", "zeta", SessionState.Idle, updatedAt = 30),
                s("2", "Alpha", SessionState.Idle, updatedAt = 20),
                s("3", "beta", SessionState.Idle, updatedAt = 10),
            ),
        )
        assertEquals(listOf(HomeGroup.Completed to listOf("Alpha", "beta", "zeta")), names(groups))
        // A card moves only when its group changes: beta starts working, the others keep their order.
        val after = homeGroups(
            listOf(
                s("3", "beta", SessionState.Working, updatedAt = 40),
                s("1", "zeta", SessionState.Idle, updatedAt = 30),
                s("2", "Alpha", SessionState.Idle, updatedAt = 20),
            ),
        )
        assertEquals(listOf(HomeGroup.Working to listOf("beta"), HomeGroup.Completed to listOf("Alpha", "zeta")), names(after))
    }

    @Test fun theSameNameFallsBackToTheId() {
        val list = listOf(s("b", "fix", SessionState.Idle), s("a", "Fix", SessionState.Idle), s("c", "fix", SessionState.Idle))
        val ids = homeGroups(list).single().second.map { it.id }
        assertEquals(listOf("a", "b", "c"), ids)
        assertEquals(ids, homeGroups(list.reversed()).single().second.map { it.id })
    }

    @Test fun anEmptyGroupHasNoSeam() {
        assertTrue(homeGroups(emptyList()).isEmpty())
        assertEquals(listOf(HomeGroup.NeedsInput), homeGroups(listOf(s("a", "asks", SessionState.NeedsYou))).map { it.first })
    }

    @Test fun eachSeamHasItsOwnKey() {
        val keys = HomeGroup.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun theAccountCardFollowsTheSkyAndWhatsOverIt() {
        assertEquals(1, homeHeadIndex(filter = false, offline = false))
        assertEquals(3, homeHeadIndex(filter = true, offline = true))
    }
}
