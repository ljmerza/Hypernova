package com.prudnikweb.hypernova.ui.fx

import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState

/**
 * Which projects the calm sky shows as planets (docs/DESIGN.md, "The calm sky"). Pure, unit-tested.
 *
 * The owner (2026-10-18), on 7 planets for 1 live session: "it looks very overwhelming". A planet is
 * for what matters now: a project with a session that needs him, one at work, or one active in the
 * last 24 hours, at most [MAX_PLANETS] of them, the most urgent first. Every other project is quiet:
 * it joins the faint belt at the edge of the system ("+5 quiet").
 */
object SkyPlanner {
    /** Planets at most: beyond this, by urgency, then by how recently each was active. */
    const val MAX_PLANETS = 4
    /** A project at rest still gets a planet this long after its last activity. */
    const val RECENT_MS = 24 * 60 * 60_000L

    /** What a project is doing, most urgent first. */
    enum class Doing { NeedsYou, Working, Resting }

    /**
     * One project as the sky sees it: what it's doing, how many of its sessions are doing that
     * (all of them while it rests), and when any of them last did something.
     */
    data class Project(val name: String, val doing: Doing, val count: Int, val lastActive: Long)

    /** The planets, most urgent first, and the quiet rest (most recently active first). */
    data class Plan(val planets: List<Project>, val quiet: List<Project>) {
        val quietNames: List<String> get() = quiet.map { it.name }
        val planetNames: List<String> get() = planets.map { it.name }
        companion object { val Empty = Plan(emptyList(), emptyList()) }
    }

    /** At work: [Session.busy], the one rule (Claude works, or agents it started still do). */
    fun working(s: Session): Boolean = s.busy

    fun project(name: String, sessions: List<Session>): Project {
        val needs = sessions.count { it.state == SessionState.NeedsYou }
        val work = sessions.count { it.state != SessionState.NeedsYou && working(it) }
        val last = sessions.maxOf { it.updatedAt }
        return when {
            needs > 0 -> Project(name, Doing.NeedsYou, needs, last)
            work > 0 -> Project(name, Doing.Working, work, last)
            else -> Project(name, Doing.Resting, sessions.size, last)
        }
    }

    private val urgency = compareBy<Project> { it.doing.ordinal }.thenByDescending { it.lastActive }.thenBy { it.name }

    /** The sky for these (alive) [sessions] at [now]. */
    fun plan(sessions: List<Session>, now: Long, max: Int = MAX_PLANETS): Plan {
        if (sessions.isEmpty()) return Plan.Empty
        val all = sessions.groupBy { it.project }.map { (name, list) -> project(name, list) }.sortedWith(urgency)
        val (eligible, rest) = all.partition { it.doing != Doing.Resting || now - it.lastActive < RECENT_MS }
        val planets = eligible.take(max)
        val quiet = (eligible.drop(max) + rest).sortedWith(compareByDescending<Project> { it.lastActive }.thenBy { it.name })
        return Plan(planets, quiet)
    }

    /**
     * Each planet's place ([0, slots)), so that nothing jumps: a planet that stays keeps its place,
     * and one that arrives takes the first free one, in the order given (most urgent first).
     * [previous] may name projects that have left; their places are free again. Planets beyond
     * [slots] get none. The same inputs always give the same places.
     */
    fun assignSlots(previous: Map<String, Int>, planets: List<String>, slots: Int = MAX_PLANETS): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        val taken = BooleanArray(slots)
        for (p in planets) {
            val s = previous[p] ?: continue
            if (s in 0 until slots && !taken[s]) { out[p] = s; taken[s] = true }
        }
        for (p in planets) {
            if (p in out) continue
            val free = taken.indexOfFirst { !it }
            if (free < 0) break
            out[p] = free
            taken[free] = true
        }
        return out
    }
}

/** What the home list shows: one project's sessions, or the quiet projects' (the belt's). */
sealed interface SkyFilter {
    data class Project(val name: String) : SkyFilter
    data object Quiet : SkyFilter

    companion object {
        /** The sessions [filter] keeps, in their order ([quiet]: the projects in the belt). */
        fun apply(sessions: List<Session>, filter: SkyFilter?, quiet: Collection<String>): List<Session> = when (filter) {
            null -> sessions
            is Project -> sessions.filter { it.project == filter.name }
            Quiet -> quiet.toHashSet().let { q -> sessions.filter { it.project in q } }
        }

        /** Whether [filter] still has something to show; a project or a belt that's gone ends it. */
        fun stillThere(filter: SkyFilter?, alive: List<Session>, quiet: Collection<String>): Boolean = when (filter) {
            null -> true
            is Project -> alive.any { it.project == filter.name }
            Quiet -> quiet.isNotEmpty()
        }
    }
}
