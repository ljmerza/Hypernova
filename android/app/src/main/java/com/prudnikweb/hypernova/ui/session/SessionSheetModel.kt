package com.prudnikweb.hypernova.ui.session

import androidx.annotation.StringRes
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentBoard
import com.prudnikweb.hypernova.data.ModelNames
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.WAgent
import com.prudnikweb.hypernova.data.WShell

/*
 * What the session sheet shows (SessionSheet.kt), as plain data with nothing of Compose in it
 * (unit-tested): its tabs and the one it opens on, the summary line under its header, the agents
 * at work and the workflows they run in, how many that is, and where the session runs.
 */

/**
 * The sheet's tabs, in the row's order, each with its name (owner, 2026-10-30). A new tab is an
 * entry here and its branch in the sheet's `when` (the compiler asks for it).
 */
internal enum class SheetTab(@StringRes val label: Int) {
    /** The model and the plan's windows. */
    Overview(R.string.session_sheet_tab_overview),
    /** The mode, the blocked calls and Claude Code's rules in effect (RulesPart.kt). */
    Permissions(R.string.session_sheet_permissions),
    /** The agents at work, by workflow, and the background commands (ShellsPart.kt). */
    Agents(R.string.session_sheet_agents),
    /** This session's MCP servers, and what can be done to them (McpTab.kt). */
    Mcp(R.string.session_sheet_tab_mcp),
    /** A side question (/btw), and Claude Code's commands and the owner's skills (CommandsTab.kt). */
    Commands(R.string.session_sheet_tab_commands),
    /** Its time and git line (SessionInfo.kt), where it runs, and the Home card's menu. */
    Session(R.string.session_sheet_title),
}

/** The tab the sheet opens on: Permissions from the strip's blocked mark, Agents from its agents count, else Overview. */
internal fun openingTab(atBlocked: Boolean, atAgents: Boolean = false): SheetTab = when {
    atBlocked -> SheetTab.Permissions
    atAgents -> SheetTab.Agents
    else -> SheetTab.Overview
}

/**
 * The tab row's widths, in px, from each tab's own [widths]: all as wide as the widest when that
 * fits in [room] (as the segmented control's), else each its own with what's left shared out;
 * each its own when even that doesn't fit (the row then scrolls sideways).
 */
internal fun tabWidths(widths: IntArray, room: Int): IntArray {
    val n = widths.size
    if (n == 0) return widths.copyOf()
    val widest = widths.max()
    // What's left of the room, a px more to the first ones until it's all used.
    fun share(base: IntArray, left: Int) = IntArray(n) { base[it] + left / n + if (it < left % n) 1 else 0 }
    return when {
        widest * n <= room -> share(IntArray(n) { widest }, room - widest * n)
        widths.sum() <= room -> share(widths, room - widths.sum())
        else -> widths.copyOf()
    }
}

/**
 * The line under the sheet's header, always in view whichever tab is open: the permission mode,
 * the model as its chip names it (null when it has none), how long it has run from its start to
 * its last activity (null while its start is unknown), and the agents at work and the calls
 * blocked, each shown only when there are any.
 */
internal data class SheetSummary(val mode: String, val model: String?, val ranMs: Long?, val atWork: Int, val blocked: Int) {
    val showsAgents: Boolean get() = atWork > 0
    val showsBlocked: Boolean get() = blocked > 0
}

/**
 * [s]'s summary line, with [atWork] as [agentsAtWork] counts them and [model] as the usage strip
 * names it ([stripModel]: the Mac's own name once it has answered).
 */
internal fun sheetSummary(s: Session, atWork: Int, model: String = s.model): SheetSummary = SheetSummary(
    mode = s.mode,
    model = model.takeIf { it.isNotBlank() }?.let(ModelNames::chip),
    ranMs = if (s.createdAt > 0L) (s.updatedAt - s.createdAt).coerceAtLeast(0L) else null,
    atWork = atWork.coerceAtLeast(0),
    blocked = s.blockedCalls.coerceAtLeast(0),
)

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

/** How a background command stands, as the sheet words it. */
internal enum class ShellSay { Running, MonitorRunning, Finished, Failed, FailedCode, Stopped, Unknown }

/** A background command as the sheet lists it: what it runs, and how long it ran or has been going (null: not known). */
internal data class SheetShell(val id: String, val text: String?, val say: ShellSay, val exitCode: Int?, val startedAt: Long, val endedAt: Long?)

/** The most finished ones listed: the sheet is about what's going. */
private const val SHELLS_FINISHED_SHOWN = 5

/**
 * The session's background commands: running, oldest first; then those nothing says anything more
 * about; then the last few that ended, newest first. Those whose status this build doesn't know read
 * as unknown ([WShell.status]). None from an older Mac, or from Codex.
 */
internal fun sheetShells(board: AgentBoard): List<SheetShell> {
    val all = board.shells.values
    val running = all.filter { it.running }.sortedBy { it.startedAt }
    val ended = all.filter { !it.running && say(it) != ShellSay.Unknown }.sortedByDescending { it.endedAt ?: it.startedAt }.take(SHELLS_FINISHED_SHOWN)
    val unknown = all.filter { say(it) == ShellSay.Unknown }.sortedByDescending { it.startedAt }
    return (running + unknown + ended).map { SheetShell(it.id, it.command ?: it.description, say(it), it.exitCode, it.startedAt, it.endedAt) }
}

private fun say(s: WShell): ShellSay = when (s.status) {
    "running" -> if (s.monitor) ShellSay.MonitorRunning else ShellSay.Running
    "done" -> ShellSay.Finished
    "error" -> if (s.exitCode != null) ShellSay.FailedCode else ShellSay.Failed
    "stopped" -> ShellSay.Stopped
    else -> ShellSay.Unknown
}

/** How many background commands run in [s]: the session's own count, or the list's when it knows more. None once it has ended. */
internal fun shellsAtWork(s: Session, shells: List<SheetShell>): Int =
    if (s.phase == Phase.Ended) 0 else maxOf(s.shellsRunning, shells.count { it.say == ShellSay.Running || it.say == ShellSay.MonitorRunning })

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
