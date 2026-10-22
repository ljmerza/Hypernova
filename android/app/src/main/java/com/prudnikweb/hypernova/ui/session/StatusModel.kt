package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.SessionState

/*
 * What the status bar above the composer says, as plain data with nothing of Compose in it
 * (unit-tested): which state the turn is in, what Claude is doing in words (a [Say] the screen
 * turns into a string resource), how far the to-dos are, and how a time reads. The bar itself is
 * in StatusBar.kt.
 */

/** How the bar looks: mint while Claude works, amber when it needs you, red when it stopped on an error. */
internal enum class BarTone { Working, NeedsYou, Error }

/** What Claude is doing, without the wording (the wording is in strings_status.xml). */
internal enum class SayKind {
    Starting, Sending, Thinking, Writing, Retrying, Working,
    Reading, ReadingMany, Searching, Editing, Creating, Running, Fetching, SearchingWeb, Agents, Planning, Tool,
    WaitApprove, WaitAnswer, WaitPlan, WaitYou, Failed,
}

/** [arg]: the file, command or tool the sentence is about; [n]: a count (files read, agents working). */
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
    /** Stop can reach it: a shared terminal or a session the Mac runs (not one it only watches). */
    val stoppable: Boolean = true,
)

internal data class BarModel(
    val tone: BarTone,
    val say: Say,
    val done: Int,
    val total: Int,
    /** Stop works only while the main agent runs (not for agents alone, not when it needs you). */
    val canStop: Boolean,
) {
    val hasTodos: Boolean get() = total > 0

    /** The share of to-dos done, or null when there's no list: the line then has no end to fill toward. */
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
    val done = i.todos.count { it.status == TodoStatus.Done }
    val total = i.todos.size
    fun model(tone: BarTone, say: Say, stop: Boolean) = BarModel(tone, say, done, total, stop)
    return when {
        i.phase == Phase.Error -> model(BarTone.Error, Say(SayKind.Failed), false)
        i.pending != null || i.state == SessionState.NeedsYou || i.phase == Phase.NeedsYou ->
            model(BarTone.NeedsYou, waitSay(i.pending, i.activity), false)
        // The one rule for "working" (Session.busy): Claude works or is starting; its agents below.
        i.state == SessionState.Working || i.phase == Phase.Starting || i.sending ->
            model(BarTone.Working, workSay(i), i.stoppable)
        // The main agent is done for now but agents it started carry on: still a turn, as the user sees it.
        i.agentsRunning > 0 -> model(BarTone.Working, Say(SayKind.Agents, n = i.agentsRunning), false)
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
    val colon = a.indexOf(':')
    val name = if (colon in 1..40) a.substring(0, colon) else ""
    if (name.isBlank() || name.contains(' ')) return Say(SayKind.Working)
    val rest = a.substring(colon + 1).trim()
    return when (name) {
        "Read" -> if (i.trailingReads >= 2) Say(SayKind.ReadingMany, n = i.trailingReads) else Say(SayKind.Reading, baseName(rest))
        "Grep", "Glob", "LS", "ToolSearch" -> Say(SayKind.Searching, rest)
        "Edit", "MultiEdit", "NotebookEdit" -> Say(SayKind.Editing, baseName(rest))
        "Write" -> Say(SayKind.Creating, baseName(rest))
        "Bash", "BashOutput", "PowerShell" -> Say(SayKind.Running, rest)
        "WebFetch" -> Say(SayKind.Fetching, rest)
        "WebSearch" -> Say(SayKind.SearchingWeb, rest)
        "Task", "Agent", "Workflow" -> Say(SayKind.Agents, n = i.agentsRunning.coerceAtLeast(1))
        "TodoWrite", "TaskCreate", "TaskUpdate" -> Say(SayKind.Planning)
        else -> Say(SayKind.Tool, toolName(name))
    }
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
