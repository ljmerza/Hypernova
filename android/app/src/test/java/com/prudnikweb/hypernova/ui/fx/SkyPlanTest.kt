package com.prudnikweb.hypernova.ui.fx

import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.fx.SkyPlanner.Doing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calm sky's plan (ui/fx/SkyPlan.kt): which projects get planets, the cap and its order, the
 * belt of quiet ones, places that don't jump, and the home list's filter.
 */
class SkyPlanTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    private fun s(project: String, state: SessionState = SessionState.Idle, ago: Long = 5 * min, id: String = "$project-${state.name}-$ago", agents: Int = 0) =
        Session(hex = "00", id = id, name = id, project = project, state = state, model = "opus", mode = "default", activity = "", updatedAt = now - ago, agentsRunning = agents)

    @Test fun theOwnersCaseOneLiveSessionAmongSevenProjects() {
        // His phone: seven projects, one session at work, the rest left for days.
        val sessions = listOf(
            s("hypernova", SessionState.Working, ago = 10_000),
            s("moon", ago = 3 * day), s("rocketeer", ago = 4 * day), s("application-allow-avoid", ago = 2 * day),
            s("site", ago = 6 * day), s("dotfiles", ago = 9 * day), s("notes", ago = 30 * day),
        )
        val plan = SkyPlanner.plan(sessions, now)
        assertEquals(listOf("hypernova"), plan.planetNames)
        assertEquals(6, plan.quiet.size)
        // The belt lists the most recently active first.
        assertEquals("application-allow-avoid", plan.quietNames.first())
        assertEquals("notes", plan.quietNames.last())
    }

    @Test fun aPlanetForWhatNeedsYouWhatWorksAndWhatWasActiveToday() {
        val plan = SkyPlanner.plan(
            listOf(
                s("waiting", SessionState.NeedsYou, ago = 3 * day),  // old, but it waits on you
                s("busy", SessionState.Working, ago = 2 * day),      // old, but at work
                s("today", ago = 23 * hour),                         // rested, within the day
                s("yesterday", ago = 25 * hour),                     // rested, over a day ago
            ),
            now,
        )
        assertEquals(listOf("waiting", "busy", "today"), plan.planetNames)
        assertEquals(listOf("yesterday"), plan.quietNames)
        assertEquals(Doing.NeedsYou, plan.planets[0].doing)
        assertEquals(Doing.Working, plan.planets[1].doing)
        assertEquals(Doing.Resting, plan.planets[2].doing)
    }

    @Test fun agentsAtWorkCountAsWork() {
        // Its main agent rests while agents it started work: the home list sorts it with the working ones.
        val plan = SkyPlanner.plan(listOf(s("agents", agents = 2, ago = 3 * day)), now)
        assertEquals(listOf("agents"), plan.planetNames)
        assertEquals(Doing.Working, plan.planets[0].doing)
        assertEquals(1, plan.planets[0].count) // sessions at work, not agents
        // An ended session's agents don't.
        val ended = s("agents", agents = 2, ago = 3 * day).copy(phase = Phase.Ended)
        assertFalse(SkyPlanner.working(ended))
    }

    @Test fun atMostFourByUrgencyThenRecency() {
        val sessions = listOf(
            s("rest-new", ago = 1 * min),
            s("rest-old", ago = 20 * hour),
            s("work-old", SessionState.Working, ago = 3 * hour),
            s("work-new", SessionState.Working, ago = 1 * min),
            s("needs-old", SessionState.NeedsYou, ago = 2 * hour),
            s("needs-new", SessionState.NeedsYou, ago = 30_000),
        )
        val plan = SkyPlanner.plan(sessions, now)
        assertEquals(SkyPlanner.MAX_PLANETS, plan.planets.size)
        assertEquals(listOf("needs-new", "needs-old", "work-new", "work-old"), plan.planetNames)
        // The ones past the cap join the belt, newest first, with the quiet ones.
        assertEquals(listOf("rest-new", "rest-old"), plan.quietNames)
    }

    @Test fun aProjectIsAsUrgentAsItsMostUrgentSession() {
        val p = SkyPlanner.project(
            "mixed",
            listOf(s("mixed", SessionState.Working, id = "a"), s("mixed", SessionState.NeedsYou, id = "b"), s("mixed", SessionState.NeedsYou, id = "c"), s("mixed", id = "d", ago = 2 * min)),
        )
        assertEquals(Doing.NeedsYou, p.doing)
        assertEquals(2, p.count)
        // Its last activity is its newest session's: the idle one, two minutes ago.
        assertEquals(now - 2 * min, p.lastActive)
    }

    @Test fun tiesAreBrokenByNameSoThePlanIsTheSameEveryTime() {
        val a = SkyPlanner.plan(listOf(s("b", ago = hour), s("a", ago = hour), s("c", ago = hour)), now)
        val b = SkyPlanner.plan(listOf(s("c", ago = hour), s("a", ago = hour), s("b", ago = hour)), now)
        assertEquals(listOf("a", "b", "c"), a.planetNames)
        assertEquals(a, b)
    }

    @Test fun noSessionsNoPlanets() {
        assertEquals(SkyPlanner.Plan.Empty, SkyPlanner.plan(emptyList(), now))
    }

    @Test fun placesDontJump() {
        // The first sky: most urgent first, into the places in order.
        var slots = SkyPlanner.assignSlots(emptyMap(), listOf("a", "b", "c"))
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2), slots)
        // "b" leaves: the others keep their places.
        slots = SkyPlanner.assignSlots(slots, listOf("a", "c"))
        assertEquals(mapOf("a" to 0, "c" to 2), slots)
        // "d" arrives, most urgent of all: it takes the free place, nobody moves.
        slots = SkyPlanner.assignSlots(slots, listOf("d", "a", "c"))
        assertEquals(mapOf("d" to 1, "a" to 0, "c" to 2), slots)
        // A change of urgency alone moves nothing.
        assertEquals(slots, SkyPlanner.assignSlots(slots, listOf("c", "a", "d")))
        // Full, and one replaced by another: the newcomer takes the place that was freed.
        slots = SkyPlanner.assignSlots(slots, listOf("d", "a", "c", "e"))
        assertEquals(3, slots["e"])
        slots = SkyPlanner.assignSlots(slots, listOf("f", "a", "c", "e"))
        assertEquals(1, slots["f"])
        assertEquals(setOf(0, 1, 2, 3), slots.values.toSet())
    }

    @Test fun placesAreDeterministic() {
        val prev = mapOf("x" to 3, "gone" to 0)
        val a = SkyPlanner.assignSlots(prev, listOf("y", "x", "z"))
        val b = SkyPlanner.assignSlots(prev, listOf("y", "x", "z"))
        assertEquals(a, b)
        assertEquals(mapOf("y" to 0, "x" to 3, "z" to 1), a)
        // More planets than places: the last ones get none.
        assertEquals(4, SkyPlanner.assignSlots(emptyMap(), listOf("1", "2", "3", "4", "5")).size)
    }

    @Test fun theFilterShowsOneProjectOrTheQuietOnes() {
        val list = listOf(s("a", id = "a1"), s("b", id = "b1"), s("a", id = "a2"), s("c", id = "c1"))
        assertEquals(list, SkyFilter.apply(list, null, listOf("c")))
        assertEquals(listOf("a1", "a2"), SkyFilter.apply(list, SkyFilter.Project("a"), listOf("c")).map { it.id })
        assertEquals(listOf("b1", "c1"), SkyFilter.apply(list, SkyFilter.Quiet, listOf("b", "c")).map { it.id })
        assertTrue(SkyFilter.apply(list, SkyFilter.Project("nope"), emptyList()).isEmpty())
    }

    @Test fun aFilterEndsWhenWhatItShowsIsGone() {
        val alive = listOf(s("a"))
        assertTrue(SkyFilter.stillThere(null, alive, emptyList()))
        assertTrue(SkyFilter.stillThere(SkyFilter.Project("a"), alive, emptyList()))
        assertFalse(SkyFilter.stillThere(SkyFilter.Project("b"), alive, emptyList()))
        assertTrue(SkyFilter.stillThere(SkyFilter.Quiet, alive, listOf("x")))
        assertFalse(SkyFilter.stillThere(SkyFilter.Quiet, alive, emptyList()))
    }
}
