package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.AgentBoard
import com.prudnikweb.hypernova.data.AgentState
import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.WorkflowView

/*
 * What the status bar above the composer says, as plain data with nothing of Compose in it
 * (unit-tested): which state the turn is in, what Claude is doing in words (a [Say] the screen
 * turns into a string resource), how far the to-dos or a running workflow are, the main agent and
 * the agents it runs as Claude Code's own footer lists them, and how a time or a token count reads.
 * The bar itself is in StatusBar.kt.
 */

/** How the bar looks: mint while Claude works, amber when it needs you, red when it stopped on an error. */
internal enum class BarTone { Working, NeedsYou, Error }

/** What Claude is doing, without the wording (the wording is in strings_status.xml). */
internal enum class SayKind {
    Starting, Sending, Thinking, Writing, Retrying, Working,
    Reading, ReadingMany, Searching, Editing, Creating, Running, Fetching, SearchingWeb, Agents, Planning, Tool,
    Workflow,
    /** The folded bar while the main agent's agents work, no workflow: "main + 3 agents". */
    MainAndAgents,
    /** The main agent idle, or in a foreground Agent call, while its agents work. */
    MainWaiting,
    /** An agent's activity this build can't put in words, as the Mac said it. */
    Raw,
    WaitApprove, WaitAnswer, WaitPlan, WaitYou, Failed,
}

/** [arg]: the file, command, tool or workflow the sentence is about; [n]: a count (files read, agents working). */
internal data class Say(val kind: SayKind, val arg: String = "", val n: Int = 0)

/** What the model needs from a session; [trailingReads] comes from [trailingReads]. */
internal data class BarInput(
    val phase: Phase,
    val state: SessionState,
    /** The daemon's one line: "Edit: src/App.kt", "$ npm test", "Thinking". */
    val activity: String,
    /** A prompt from this phone is on its way. */
    val sending: Boolean = false,
    val agentsRunning: Int = 0,
    /** The approval waiting for an answer, if any. */
    val pending: Item.Approval? = null,
    val todos: List<Todo> = emptyList(),
    val trailingReads: Int = 0,
    /** The session's running workflow ([runningWorkflow]): while it runs, the bar counts its agents instead of the to-dos. */
    val workflow: WorkflowView? = null,
    /** Stop can reach it: a shared terminal or a session the Mac runs (not one it only watches). */
    val stoppable: Boolean = true,
    /** The agents at work outside a running workflow, from the board ([barAgents]). */
    val agents: List<BarAgent> = emptyList(),
)

internal data class BarModel(
    val tone: BarTone,
    val say: Say,
    val done: Int,
    val total: Int,
    /** Stop works only while the main agent runs (not for agents alone, not when it needs you). */
    val canStop: Boolean,
    /** The running workflow whose agents [done] (finished, however they ended) and [total] count, by name; null for the to-dos. */
    val workflow: String? = null,
    /**
     * The agents at work beside the main agent, no workflow running (owner, 2026-10-30, as Claude
     * Code's footer lists them): the folded bar says "main + 3 agents", the opened one lists them.
     * Never the to-dos, which [done] and [total] count.
     */
    val agents: Int = 0,
    /** What the main agent itself does, for its row in the opened bar ([say] is the folded line's). */
    val main: Say = say,
) {
    val hasTodos: Boolean get() = total > 0 && workflow == null
    val hasWorkflow: Boolean get() = workflow != null
    val hasAgents: Boolean get() = agents > 0 && workflow == null

    /** There's a tally to show ("3/7"): to-dos, or the agents a workflow has started so far. */
    val counted: Boolean get() = total > 0

    /** The share done, or null when there's nothing to count: the line then has no end to fill toward. */
    val progress: Float? get() = progress(done, total)
}

/** How long a red error bar stays before it folds by itself (a tap keeps it). */
internal const val ERROR_SHOW_MS = 30_000L

/** Whether a red bar has folded: its time is up and nobody tapped to keep it. */
internal fun errorFolded(expired: Boolean, kept: Boolean): Boolean = expired && !kept

internal fun progress(done: Int, total: Int): Float? =
    if (total <= 0) null else (done.toFloat() / total).coerceIn(0f, 1f)

/** The bar for a session, or null when no turn runs (it folds away). */
internal fun statusBar(i: BarInput): BarModel? {
    if (i.phase == Phase.Ended) return null
    // A running workflow wins over the to-dos (owner, 2026-10-29): its agents finished (done, failed
    // or stopped, so a run with a failure still reaches 7/7) over those it has started so far, a
    // total that grows while it starts more.
    val wf = i.workflow?.takeIf { it.state == AgentState.Running }
    val done = wf?.finished ?: i.todos.count { it.status == TodoStatus.Done }
    val total = wf?.agentCount ?: i.todos.size
    // The agents beside the main one, with no workflow running (a workflow's own UI wins, owner,
    // 2026-10-30): the session's count, or the board's when it knows more.
    val n = if (wf != null) 0 else maxOf(i.agentsRunning, i.agents.size)
    fun model(tone: BarTone, say: Say, stop: Boolean, main: Say = say) = BarModel(tone, say, done, total, stop, wf?.name, n, main)
    // "main + 3 agents" while it works; what it waits on or why it stopped still leads when it does.
    fun working(main: Say, stop: Boolean) = when {
        wf != null -> model(BarTone.Working, Say(SayKind.Workflow, wf.name), stop, main)
        n > 0 -> model(BarTone.Working, Say(SayKind.MainAndAgents, n = n), stop, if (main.kind == SayKind.Agents) Say(SayKind.MainWaiting) else main)
        else -> model(BarTone.Working, main, stop)
    }
    return when {
        i.phase == Phase.Error -> model(BarTone.Error, Say(SayKind.Failed), false)
        i.pending != null || i.state == SessionState.NeedsYou || i.phase == Phase.NeedsYou ->
            model(BarTone.NeedsYou, waitSay(i.pending, i.activity), false)
        // The one rule for "working" (Session.busy): Claude works or is starting; its agents below.
        i.state == SessionState.Working || i.phase == Phase.Starting || i.sending -> working(workSay(i), i.stoppable)
        // The main agent is done for now but agents it started carry on: still a turn, as the user sees it.
        // A workflow keeps it too between its phases, when none of its agents is at work.
        n > 0 || wf != null -> working(Say(SayKind.MainWaiting), false)
        else -> null
    }
}

private fun waitSay(a: Item.Approval?, activity: String): Say {
    if (a == null) {
        // "wants to run git push origin main": the daemon's own words for what it waits on.
        val what = activity.removePrefix("wants to ").trim()
        return if (what.isBlank() || what == activity.trim()) Say(SayKind.WaitYou) else Say(SayKind.WaitApprove, what)
    }
    return when (a.kind) {
        ApprovalKind.Question -> Say(SayKind.WaitAnswer)
        ApprovalKind.Plan -> Say(SayKind.WaitPlan)
        ApprovalKind.Tool -> Say(SayKind.WaitApprove, approvalTarget(a.tool, a.detail))
    }
}

/** "git push" for a shell command, "Edit Model.kt" for a file tool. */
internal fun approvalTarget(tool: String, detail: String): String {
    val d = detail.trim().lines().firstOrNull().orEmpty()
    if (tool == "Bash" || d.isBlank()) return d.ifBlank { tool }
    return "$tool ${baseName(d)}"
}

private fun workSay(i: BarInput): Say {
    val a = i.activity.trim()
    if (i.sending && i.phase != Phase.Working) return Say(SayKind.Sending)
    if (i.phase == Phase.Starting) return Say(SayKind.Starting)
    if (a.startsWith("$ ")) return Say(SayKind.Running, a.removePrefix("$ ").trim())
    if (a.equals("Thinking", true)) return Say(SayKind.Thinking)
    if (a.equals("Writing", true)) return Say(SayKind.Writing)
    if (a.startsWith("Retrying", true)) return Say(SayKind.Retrying, a)
    return toolSay(a, i.trailingReads, i.agentsRunning)
}

/** "Edit: src/App.kt" in words, or [SayKind.Working] when it isn't a tool's line. */
private fun toolSay(a: String, trailingReads: Int, agentsRunning: Int): Say {
    val colon = a.indexOf(':')
    val name = if (colon in 1..40) a.substring(0, colon) else ""
    if (name.isBlank() || name.contains(' ')) return Say(SayKind.Working)
    val rest = a.substring(colon + 1).trim()
    return when (name) {
        "Read" -> if (trailingReads >= 2) Say(SayKind.ReadingMany, n = trailingReads) else Say(SayKind.Reading, baseName(rest))
        "Grep", "Glob", "LS", "ToolSearch" -> Say(SayKind.Searching, rest)
        "Edit", "MultiEdit", "NotebookEdit" -> Say(SayKind.Editing, baseName(rest))
        "Write" -> Say(SayKind.Creating, baseName(rest))
        "Bash", "BashOutput", "PowerShell" -> Say(SayKind.Running, rest)
        "WebFetch" -> Say(SayKind.Fetching, rest)
        "WebSearch" -> Say(SayKind.SearchingWeb, rest)
        "Task", "Agent", "Workflow" -> Say(SayKind.Agents, n = agentsRunning.coerceAtLeast(1))
        "TodoWrite", "TaskCreate", "TaskUpdate" -> Say(SayKind.Planning)
        else -> Say(SayKind.Tool, toolName(name))
    }
}

/** The session's newest workflow that still runs, from its cards (a run not yet under its call is a card too, last). */
internal fun runningWorkflow(items: List<Item>): WorkflowView? {
    for (k in items.indices.reversed()) {
        val w = (items[k] as? Item.WorkflowCard)?.workflow ?: continue
        if (w.state == AgentState.Running) return w
    }
    return null
}

/** "mcp__github__create_issue" → "create issue". */
internal fun toolName(raw: String): String =
    raw.substringAfterLast("__").replace('_', ' ').ifBlank { raw }

internal fun baseName(path: String): String {
    // Anything after the file ("src/App.kt (lines 1-40)") isn't part of the name.
    val p = path.trim().substringBefore(" (")
    return p.trimEnd('/').substringAfterLast('/').ifBlank { p }
}

/**
 * How many files Claude has read in a row at the end of the transcript, for "Reading 3 files".
 * Thinking rows between them don't break the run.
 */
internal fun trailingReads(items: List<Item>): Int {
    var n = 0
    for (k in items.indices.reversed()) {
        val it = items[k]
        when {
            it is Item.Thinking -> continue
            it is Item.Tool && it.verb == "Read" -> n++
            else -> break
        }
    }
    return n
}

/** 0:07, 12:34, 1:02:03. */
internal fun clockText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * One agent at work as the opened bar lists it, the way Claude Code's footer does (owner,
 * 2026-10-30): its kind, what it does now, and on the right how long it has run and its tokens.
 */
internal data class BarAgent(
    val id: String,
    /** Its kind ("general-purpose", "Explore"), or what it's for when the Mac knows no kind. */
    val kind: String,
    /** What it's for ("Add routing helpers"), when that isn't its kind. */
    val task: String?,
    /** What it does now, in words ("Reading Servers.kt"); null before its first step. */
    val doing: Say?,
    /** When it started, the Mac's clock (epoch ms). */
    val startedAt: Long,
    /** Its tokens as Claude Code's footer counts them (docs/PROTOCOL.md, Agent.tokens); null before its first reply. */
    val tokens: Long?,
    /** An approval of its own waits for the owner. */
    val asking: Boolean = false,
)

/**
 * The agents at work outside a running workflow, oldest first ([sheetAgents]' others, as the
 * session sheet lists them), [asking] those with an approval waiting.
 */
internal fun barAgents(board: AgentBoard, asking: Set<String> = emptySet()): List<BarAgent> =
    sheetAgents(board).loose.map { a ->
        val kind = a.type.takeIf { it.isNotBlank() && it != "agent" } ?: a.label
        BarAgent(
            id = a.id,
            kind = kind,
            task = a.label.takeIf { it.isNotBlank() && it != kind },
            doing = a.activity?.trim()?.takeIf { it.isNotEmpty() }?.let { agentSay(it) },
            startedAt = a.startedAt,
            tokens = a.tokens,
            asking = a.id in asking,
        )
    }

/** An agent's activity line ("Read: src/Servers.kt") in the bar's words ("Reading Servers.kt"), or as it is. */
internal fun agentSay(activity: String): Say {
    val a = activity.trim()
    if (a.startsWith("$ ")) return Say(SayKind.Running, a.removePrefix("$ ").trim())
    if (a.equals("Thinking", true)) return Say(SayKind.Thinking)
    val s = toolSay(a, 0, 1)
    return when (s.kind) {
        SayKind.Working -> Say(SayKind.Raw, a)
        // An agent starting agents of its own.
        SayKind.Agents -> Say(SayKind.Raw, a.substringBefore(':'))
        else -> s
    }
}

/** The agents with an approval waiting, from the chat's agent cards (theirs turn amber). */
internal fun askingAgents(items: List<Item>): Set<String> {
    val out = HashSet<String>()
    for (it in items) {
        val c = it as? Item.AgentCard ?: continue
        if (c.agent.asking) out += c.agent.id
        for (n in c.nested) if (n.asking) out += n.id
    }
    return out
}

/**
 * A running workflow's tokens: the sum over its agents (the Mac sends its own sum only when an
 * agent's status changes, so the agents' newer counts lead while it runs), or the Mac's total if more.
 */
internal fun workflowTokens(w: WorkflowView): Long? {
    val agents = w.phases.flatMap { it.agents } + w.loose
    val sum = if (agents.any { it.tokens != null }) agents.sumOf { it.tokens ?: 0L } else null
    return listOfNotNull(sum, w.tokens).maxOrNull()
}

/** How long, as Claude Code's footer writes it: 42s, 4m12s, 19m08s, 1h02m, 2d03h. */
internal fun ccDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    if (s < 60) return "${s}s"
    val m = s / 60
    if (m < 60) return "%dm%02ds".format(m, s % 60)
    val h = m / 60
    if (h < 24) return "%dh%02dm".format(h, m % 60)
    return "%dd%02dh".format(h / 24, h % 24)
}

/**
 * Tokens as Claude Code's footer writes them (Intl's compact notation, lower case): 980, 8.4k,
 * 81.0k, 181.1k, 1.2m; from a thousand up always one decimal.
 */
internal fun ccTokens(n: Long): String {
    if (n < 1000) return n.coerceAtLeast(0).toString()
    val units = arrayOf("k", "m", "b", "t")
    var v = n / 1000.0
    var u = 0
    // 999,950 rounds to "1000.0k": Intl moves it up to "1.0m".
    while (u < units.lastIndex && Math.round(v * 10) >= 10_000) {
        v /= 1000.0
        u++
    }
    return String.format(java.util.Locale.ROOT, "%.1f%s", Math.round(v * 10) / 10.0, units[u])
}

/** A token count for TalkBack, without its unit's words: 980 tokens, 81 thousand, 1.2 million. */
internal enum class TokenScale { Units, Thousands, Millions }

internal data class SpokenTokens(val scale: TokenScale, val figure: String, val count: Int)

internal fun spokenTokens(n: Long): SpokenTokens = when {
    n < 1000 -> SpokenTokens(TokenScale.Units, n.coerceAtLeast(0).toString(), n.coerceAtLeast(0).toInt())
    Math.round(n / 1000.0) < 1000 -> Math.round(n / 1000.0).let { SpokenTokens(TokenScale.Thousands, it.toString(), it.toInt()) }
    else -> {
        val m = Math.round(n / 100_000.0) / 10.0
        SpokenTokens(TokenScale.Millions, if (m % 1.0 == 0.0) m.toLong().toString() else m.toString(), m.toInt())
    }
}
