package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable

/**
 * The agents a session's main agent started and the workflows of them, as the Mac reports them
 * (`GET /v1/sessions/{id}/agents`, then `agent` and `workflow` messages; docs/PROTOCOL.md, "Agents
 * and workflows"). A change makes a new board, so a transcript is rebuilt only when it changes.
 */
@Immutable
class AgentBoard(val agents: Map<String, WAgent>, val workflows: Map<String, WWorkflow>) {
    /** Agents by the Agent call that started them (in the session's transcript, or in an agent's own). */
    val byCall: Map<String, WAgent> by lazy { agents.values.filter { it.toolUseId != null }.associateBy { it.toolUseId!! } }
    val workflowByCall: Map<String, WWorkflow> by lazy { workflows.values.filter { it.toolUseId != null }.associateBy { it.toolUseId!! } }
    /** What each agent started itself, oldest first. */
    val children: Map<String, List<WAgent>> by lazy { agents.values.filter { it.parentAgentId != null }.groupBy { it.parentAgentId!! }.mapValues { (_, v) -> v.sortedBy { it.startedAt } } }
    val inWorkflow: Map<String, List<WAgent>> by lazy { agents.values.filter { it.workflowId != null }.groupBy { it.workflowId!! }.mapValues { (_, v) -> v.sortedBy { it.startedAt } } }

    fun isEmpty() = agents.isEmpty() && workflows.isEmpty()
    fun with(a: WAgent) = AgentBoard(agents + (a.id to a), workflows)
    fun without(agentId: String) = if (agentId in agents) AgentBoard(agents - agentId, workflows) else this
    fun with(w: WWorkflow) = AgentBoard(agents, workflows + (w.id to w))

    companion object {
        val Empty = AgentBoard(emptyMap(), emptyMap())
    }
}

/** The Mac's word for an agent's status as the app draws it; one this build doesn't know is a stop. */
fun agentState(status: String): AgentState = when (status) {
    "running" -> AgentState.Running
    "done" -> AgentState.Done
    "error" -> AgentState.Failed
    else -> AgentState.Stopped
}

fun WAgent.view(asking: Boolean = false, depth: Int = 0) = AgentView(
    id = id, label = label, type = type, state = agentState(status), activity = activity, startedAt = startedAt, endedAt = endedAt,
    tokens = tokens, toolUses = toolUses, result = result, asking = asking, depth = depth, phase = phase,
)

/** The agents [agentId] started, and theirs, depth first, under it (a few levels, a dozen at most). */
fun AgentBoard.nested(agentId: String, asking: Set<String>): List<AgentView> {
    if (children.isEmpty()) return emptyList()
    val out = ArrayList<AgentView>()
    fun walk(id: String, depth: Int) {
        for (c in children[id].orEmpty()) {
            if (out.size >= 12 || depth > 4) return
            out += c.view(c.id in asking, depth)
            walk(c.id, depth + 1)
        }
    }
    walk(agentId, 1)
    return out
}

/**
 * A workflow as its card draws it: each phase of its script with its agents and how far it is, and
 * agents in a phase the script didn't name.
 */
fun AgentBoard.workflowView(w: WWorkflow, asking: Set<String>): WorkflowView {
    val mine = inWorkflow[w.id].orEmpty()
    val titles = w.phases.mapTo(HashSet()) { it.title }
    val phases = w.phases.map { p ->
        val agents = mine.filter { it.phase == p.title }
        val state = when {
            agents.any { it.running } -> PhaseState.Live
            agents.isEmpty() -> PhaseState.Waiting
            agents.any { it.status == "error" } -> PhaseState.Failed
            else -> PhaseState.Done
        }
        PhaseView(p.title, p.detail, agents.map { it.view(it.id in asking) }, agents.count { it.status == "done" }, state)
    }
    return WorkflowView(
        id = w.id,
        name = w.name,
        summary = w.summary,
        state = agentState(w.status),
        startedAt = w.startedAt,
        endedAt = w.endedAt,
        tokens = w.tokens,
        agentCount = maxOf(w.agentCount, mine.size),
        running = mine.count { it.running },
        phases = phases,
        done = maxOf(w.done, mine.count { it.status == "done" }),
        loose = mine.filter { it.phase == null || it.phase !in titles }.map { it.view(it.id in asking) },
    )
}
