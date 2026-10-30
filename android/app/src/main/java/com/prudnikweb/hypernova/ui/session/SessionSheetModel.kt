package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.AgentBoard
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.WAgent

/*
 * What the session sheet shows (SessionSheet.kt), as plain data with nothing of Compose in it
 * (unit-tested): the agents at work and the workflows they run in, how many that is, and where
 * the session runs.
 */

/**
 * A running workflow as the sheet lists it: its name, its agents done of those it has started so
 * far (the total grows as it starts more), and the ones at work now. None at work: between phases.
 */
internal data class SheetWorkflow(val id: String, val name: String, val done: Int, val started: Int, val agents: List<WAgent>)

/** The agents at work: the running workflows with theirs, then every other running agent, oldest first. */
internal data class SheetAgents(val workflows: List<SheetWorkflow>, val loose: List<WAgent>) {
    val running: Int get() = loose.size + workflows.sumOf { it.agents.size }
    val isEmpty: Boolean get() = workflows.isEmpty() && loose.isEmpty()
}

/**
 * The board's agents at work, by workflow. A workflow's tally is the daemon's `done` and
 * `agentCount`, or what this phone has on its board if more (as its card counts them); a retried
 * step's earlier attempts are in neither (the daemon leaves them out, the phone takes them off).
 */
internal fun sheetAgents(board: AgentBoard): SheetAgents {
    val live = board.workflows.values.filter { it.running }.sortedBy { it.startedAt }
    val ids = live.mapTo(HashSet()) { it.id }
    val workflows = live.map { w ->
        val mine = board.inWorkflow[w.id].orEmpty()
        SheetWorkflow(
            w.id, w.name,
            done = maxOf(w.done, mine.count { it.status == "done" }),
            started = maxOf(w.agentCount, mine.size),
            agents = mine.filter { it.running },
        )
    }
    val loose = board.agents.values.filter { a -> a.running && (a.workflowId == null || a.workflowId !in ids) }.sortedBy { it.startedAt }
    return SheetAgents(workflows, loose)
}

/**
 * How many agents are at work in [s]: the session's own count, or the board's when it knows more
 * (agents an agent started). None once it has ended.
 */
internal fun agentsAtWork(s: Session, agents: SheetAgents): Int =
    if (s.phase == Phase.Ended) 0 else maxOf(s.agentsRunning, agents.running)

/** Where a session runs, as the sheet says it. */
internal enum class RunsIn { Terminal, Mac, Watched }

/**
 * A shared terminal Hypernova holds, a session the Mac runs itself, or a terminal session it only
 * watches through the hooks (its terminal isn't shared: nothing can be typed into it from here).
 */
internal fun runsIn(s: Session): RunsIn = when {
    s.term != null -> RunsIn.Terminal
    s.origin == "hypernova" -> RunsIn.Mac
    else -> RunsIn.Watched
}
