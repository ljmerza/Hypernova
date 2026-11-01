package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.KeyValue
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's filter (HomeFilter.kt; owner, 2026-11-01): what it keeps, its taps, its words and what the phone keeps of it. */
class HomeFilterTest {
    private fun s(
        id: String, state: SessionState = SessionState.Idle, phase: Phase = Phase.of(state), project: String = "p",
        agent: String = AgentKind.CLAUDE, agentsRunning: Int = 0,
    ) = Session(hex = id.take(2), id = id, name = id, project = project, state = state, model = "opus", mode = "default", activity = "", updatedAt = 0L, phase = phase, agent = agent, agentsRunning = agentsRunning)

    private val needs = s("needs", SessionState.NeedsYou, project = "infra")
    private val working = s("working", SessionState.Working)
    private val agents = s("agents", SessionState.Idle, agentsRunning = 2) // its main agent rests, its agents run: Working
    private val starting = s("starting", SessionState.Working, phase = Phase.Starting)
    private val errored = s("errored", SessionState.Idle, phase = Phase.Error)
    private val idle = s("idle", project = "notes")
    private val codex = s("codex", SessionState.Working, agent = AgentKind.CODEX, project = "infra")
    private val list = listOf(needs, working, agents, starting, errored, idle, codex)

    private fun ids(f: HomeFilter) = f.apply(list).map { it.id }

    @Test fun offByDefaultAndShowsEverything() {
        val f = HomeFilter()
        assertFalse(f.on)
        assertEquals(HomeFilter.ALL_GROUPS, f.shownGroups)
        assertSame("nothing narrowed: the list as it was", list, f.apply(list))
    }

    @Test fun statesFollowHomesGroups() {
        // Starting and agents-running sit in Working, an error in Completed, as Home groups them.
        assertEquals(listOf("needs"), ids(HomeFilter(groups = setOf(HomeGroup.NeedsInput))))
        assertEquals(listOf("working", "agents", "starting", "codex"), ids(HomeFilter(groups = setOf(HomeGroup.Working))))
        assertEquals(listOf("errored", "idle"), ids(HomeFilter(groups = setOf(HomeGroup.Completed))))
        for (x in list) assertEquals(homeGroup(x) in setOf(HomeGroup.NeedsInput), HomeFilter(groups = setOf(HomeGroup.NeedsInput)).keeps(x))
    }

    @Test fun activeIsNeedsYouAndWorkingInOneTap() {
        val f = HomeFilter(project = "p").toggleActive()
        assertTrue(f.active && f.on)
        assertEquals(HomeFilter.ACTIVE, f.groups)
        assertEquals("the rest stays as it was", "p", f.project)
        assertEquals(listOf("working", "agents", "starting"), ids(f))
        val again = f.toggleActive()
        assertFalse(again.active)
        assertEquals(HomeFilter.ALL_GROUPS, again.shownGroups)
    }

    @Test fun toggleTurnsAStateOffAndOnButNeverTheLastOne() {
        val noDone = HomeFilter().toggle(HomeGroup.Completed)
        assertEquals(HomeFilter.ACTIVE, noDone.groups)
        assertTrue(noDone.active)
        val workingOnly = noDone.toggle(HomeGroup.NeedsInput)
        assertEquals(setOf(HomeGroup.Working), workingOnly.groups)
        assertSame("the last state shown stays", workingOnly, workingOnly.toggle(HomeGroup.Working))
        // All three on again is no group filter at all.
        val back = workingOnly.toggle(HomeGroup.NeedsInput).toggle(HomeGroup.Completed)
        assertEquals(emptySet<HomeGroup>(), back.groups)
        assertFalse(back.on)
    }

    @Test fun agentAndProjectNarrowToo() {
        assertEquals(listOf("codex"), ids(HomeFilter(agent = FilterAgent.Codex)))
        assertEquals(listOf("needs", "working", "agents", "starting", "errored", "idle"), ids(HomeFilter(agent = FilterAgent.Claude)))
        assertEquals(listOf("needs", "codex"), ids(HomeFilter(project = "infra")))
        assertEquals(listOf("codex"), ids(HomeFilter(project = "infra", groups = setOf(HomeGroup.Working))))
        assertEquals("a project with no session left: nothing, said on Home", emptyList<String>(), ids(HomeFilter(project = "gone")))
    }

    @Test fun theServerAppliesOnlyWithAllServersAndWhileItIsPaired() {
        val f = HomeFilter(server = "gym")
        assertNull("one computer: no server filter", f.forServers(null).server)
        assertFalse(f.forServers(null).on)
        assertNull("a computer that's gone: none", f.forServers(listOf("z2")).server)
        assertEquals("gym", f.forServers(listOf("z2", "gym")).server)
        assertTrue(f.forServers(listOf("z2", "gym")).on)
        // All three groups kept is no group filter.
        assertEquals(emptySet<HomeGroup>(), HomeFilter(groups = HomeFilter.ALL_GROUPS).forServers(null).groups)
    }

    @Test fun partsKeepTheChosenComputer() {
        val parts = listOf(ServerPart("z2", "z2", LinkState.Connected, "hub-z2", list), ServerPart("gym", "gym", LinkState.Connected, "hub-gym", list))
        assertEquals(listOf("z2", "gym"), HomeFilter().parts(parts).map { it.id })
        assertEquals(listOf("gym"), HomeFilter(server = "gym").parts(parts).map { it.id })
        // With the rest of the filter: AllHome.cards narrows each computer's on its own.
        val f = HomeFilter(server = "gym", groups = setOf(HomeGroup.NeedsInput))
        val cards = AllHome.cards(f.parts(parts), narrow = f::apply)
        assertEquals(listOf("gym:needs"), cards.map { it.key })
    }

    @Test fun piecesSayWhatIsShownInTheSheetsOrder() {
        val words = mapOf(HomeGroup.NeedsInput to "Needs you", HomeGroup.Working to "Working", HomeGroup.Completed to "Completed")
        fun pieces(f: HomeFilter) = f.pieces({ words.getValue(it) }, { it.name }, { if (it == "gym") "gym" else null })
        assertEquals(listOf("Needs you + Working"), pieces(HomeFilter(groups = setOf(HomeGroup.Working, HomeGroup.NeedsInput))))
        assertEquals(listOf("Working", "Codex", "infra", "gym"), pieces(HomeFilter(setOf(HomeGroup.Working), FilterAgent.Codex, "infra", "gym")))
        assertEquals("every state: no state piece", listOf("infra"), pieces(HomeFilter(project = "infra")))
        assertEquals("a computer that's gone has no name", emptyList<String>(), pieces(HomeFilter(server = "old")))
    }

    @Test fun theProjectsAreHomesAToZWithTheChosenOne() {
        assertEquals(listOf("infra", "notes", "p"), HomeFilter.projects(list, null))
        assertEquals(listOf("gone", "infra", "notes", "p"), HomeFilter.projects(list, "gone"))
        assertEquals(listOf("Alpha", "beta"), HomeFilter.projects(listOf(s("a", project = "beta"), s("b", project = "Alpha"), s("c", project = "")), null))
    }

    private class Mem : KeyValue {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @Test fun keptOnThePhoneAndBackAfterARestart() {
        val kv = Mem()
        assertEquals(HomeFilter(), HomeFilter.load(kv))
        val f = HomeFilter(HomeFilter.ACTIVE, FilterAgent.Codex, "infra", "gym")
        f.save(kv)
        assertEquals(f, HomeFilter.load(kv))
        HomeFilter.NONE.save(kv)
        assertTrue("cleared, nothing is left behind", kv.map.isEmpty())
        assertEquals(HomeFilter(), HomeFilter.load(kv))
    }

    @Test fun aNameFromALaterVersionIsLeftOut() {
        val kv = Mem()
        kv.put("home_filter_groups", "Working,Paused")
        kv.put("home_filter_agent", "Gemini")
        val f = HomeFilter.load(kv)
        assertEquals(setOf(HomeGroup.Working), f.groups)
        assertNull(f.agent)
    }
}
