package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.KeyValue
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's sort (HomeSort.kt; owner, 2026-11-01): each order within the groups, the default, and what the phone keeps. */
class HomeSortTest {
    private fun s(id: String, name: String, state: SessionState = SessionState.Idle, updatedAt: Long = 0L, project: String = "p") =
        Session(hex = id.take(2), id = id, name = name, project = project, state = state, model = "opus", mode = "default", activity = "", updatedAt = updatedAt)

    private val list = listOf(
        s("1", "zeta", SessionState.Working, updatedAt = 10, project = "beta"),
        s("2", "Alpha", SessionState.Working, updatedAt = 30, project = "beta"),
        s("3", "mid", SessionState.Working, updatedAt = 20, project = "Alpha"),
        s("4", "older", updatedAt = 5, project = "beta"),
        s("5", "newer", updatedAt = 50, project = "alpha"),
        s("6", "asks", SessionState.NeedsYou, updatedAt = 1),
    )

    private fun names(sort: HomeSort) = homeGroups(list, sort).map { (g, l) -> g to l.map { it.name } }

    @Test fun nameIsTheDefaultInEveryGroup() {
        assertEquals(HomeSort.Name, HomeSort.DEFAULT)
        assertFalse(HomeSort.Name.changed)
        assertEquals(
            listOf(
                HomeGroup.NeedsInput to listOf("asks"),
                HomeGroup.Working to listOf("Alpha", "mid", "zeta"),
                HomeGroup.Completed to listOf("newer", "older"),
            ),
            names(HomeSort.Name),
        )
        assertEquals(names(HomeSort.Name), homeGroups(list).map { (g, l) -> g to l.map { it.name } })
    }

    @Test fun recentIsNewestFirstWithinEachGroup() {
        assertTrue(HomeSort.Recent.changed)
        assertEquals(
            listOf(
                HomeGroup.NeedsInput to listOf("asks"),
                HomeGroup.Working to listOf("Alpha", "mid", "zeta"),
                HomeGroup.Completed to listOf("newer", "older"),
            ).map { it.first },
            names(HomeSort.Recent).map { it.first },
        )
        assertEquals(listOf("Alpha", "mid", "zeta"), names(HomeSort.Recent).toMap().getValue(HomeGroup.Working))
        // 30, 20, 10: the same as by name here by chance; reversed times reverse it.
        val flipped = list.map { if (it.state == SessionState.Working) it.copy(updatedAt = 100 - it.updatedAt) else it }
        assertEquals(listOf("zeta", "mid", "Alpha"), homeGroups(flipped, HomeSort.Recent).toMap().getValue(HomeGroup.Working).map { it.name })
    }

    @Test fun projectAToZAnyCaseThenName() {
        // Alpha (mid) before beta (Alpha, zeta); in Completed alpha (newer) before beta (older).
        assertEquals(listOf("mid", "Alpha", "zeta"), names(HomeSort.Project).toMap().getValue(HomeGroup.Working))
        assertEquals(listOf("newer", "older"), names(HomeSort.Project).toMap().getValue(HomeGroup.Completed))
    }

    @Test fun theGroupsKeepTheirOrderWhateverTheSort() {
        for (sort in HomeSort.entries) {
            assertEquals(listOf(HomeGroup.NeedsInput, HomeGroup.Working, HomeGroup.Completed), names(sort).map { it.first })
        }
    }

    @Test fun equalKeysFallBackToTheNameThenTheId() {
        val same = listOf(s("b", "fix", updatedAt = 5), s("a", "Fix", updatedAt = 5), s("z", "apply", updatedAt = 5))
        for (sort in HomeSort.entries) {
            val ids = homeGroups(same, sort).single().second.map { it.id }
            assertEquals(listOf("z", "a", "b"), ids)
            assertEquals(ids, homeGroups(same.reversed(), sort).single().second.map { it.id })
        }
    }

    @Test fun serverOnlyWithAllServers() {
        assertEquals(HomeSort.Name, HomeSort.Server.forServers(all = false))
        assertEquals(HomeSort.Server, HomeSort.Server.forServers(all = true))
        assertEquals(HomeSort.Recent, HomeSort.Recent.forServers(all = false))
        assertEquals(listOf(HomeSort.Name, HomeSort.Recent, HomeSort.Project), HomeSort.offered(all = false))
        assertEquals(HomeSort.entries.toList(), HomeSort.offered(all = true))
    }

    @Test fun byServerAcrossEveryComputersCardsThenName() {
        val z2 = ServerPart("z2", "z2", LinkState.Connected, "hub-z2", listOf(s("a", "zeta", SessionState.Working), s("b", "Alpha", SessionState.Working)))
        val gym = ServerPart("gym", "gym", LinkState.Connected, "hub-gym", listOf(s("c", "mid", SessionState.Working)))
        val cards = AllHome.cards(listOf(z2, gym))
        fun order(sort: HomeSort) = AllHome.groups(cards, sort).single().second.map { "${it.name}:${it.session.name}" }
        assertEquals(listOf("gym:mid", "z2:Alpha", "z2:zeta"), order(HomeSort.Server))
        assertEquals(listOf("z2:Alpha", "gym:mid", "z2:zeta"), order(HomeSort.Name))
    }

    private class Mem : KeyValue {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @Test fun keptOnThePhone() {
        val kv = Mem()
        assertEquals(HomeSort.Name, HomeSort.load(kv))
        HomeSort.save(kv, HomeSort.Recent)
        assertEquals(HomeSort.Recent, HomeSort.load(kv))
        HomeSort.save(kv, HomeSort.Name)
        assertNull("the default leaves nothing behind", kv.map["home_sort"])
        kv.put("home_sort", "Oldest")
        assertEquals("a name from a later version is the default", HomeSort.Name, HomeSort.load(kv))
    }
}
