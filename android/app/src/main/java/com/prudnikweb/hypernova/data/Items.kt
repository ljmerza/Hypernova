package com.prudnikweb.hypernova.data

/** How an approval ended, as the daemon reported it (`approval.resolved`). */
data class Resolved(val decision: String, val by: String?)

/**
 * Turns the daemon's normalized events into the transcript the session screen draws:
 * Bash calls become mini terminals, edits become diff cards, approvals pull their details
 * from the pending [approvals], and localhost URLs become "Open preview" pills.
 */
object Items {
    private val OWN_PORTS = 47321..47323
    private val PORT = Regex("""(?:https?://)?(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1])(?::(\d{2,5}))""")
    private const val MAX_DIFF_LINES = 120
    private const val MAX_TERMINAL_LINES = 400

    /** Ports of local dev servers mentioned in [text] (e.g. `http://localhost:5173/`). */
    fun ports(text: String?): List<Int> {
        if (text.isNullOrEmpty() || (!text.contains("localhost") && !text.contains("127.0.0.1") && !text.contains("0.0.0.0") && !text.contains("[::1]"))) return emptyList()
        // Not Hypernova's own ports (the API, the local API, the local route): the Mac refuses to preview those.
        return PORT.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it in 1..65535 && it !in OWN_PORTS }.distinct().take(3).toList()
    }

    /** A short path for display: relative to the session folder, or `~/…`. */
    fun shortPath(path: String, cwd: String?): String {
        if (cwd != null && cwd.length > 1 && path.startsWith(cwd.trimEnd('/') + "/")) return path.substring(cwd.trimEnd('/').length + 1)
        val home = Regex("^/Users/[^/]+/").find(path)
        return if (home != null) "~/" + path.substring(home.value.length) else path
    }

    /** Converted items by event id; reused while the event (an immutable instance) is unchanged. */
    class Cache {
        internal val byId = HashMap<String, Pair<WEvent, Item?>>()
        internal var cwd: String? = null
        internal var active = true
    }

    fun build(
        events: List<WEvent>,
        approvals: Map<String, WApproval>,
        resolved: Map<String, Resolved>,
        answered: Map<String, Resolution>,
        sessionId: String,
        cwd: String?,
        now: Long,
        /** True once a snapshot has loaded: a pending-looking approval that isn't pending is closed. */
        settled: Boolean = false,
        cache: Cache? = null,
        /** False when the session isn't running: a tool still marked "running" was cut off. */
        active: Boolean = true,
        /** The session's agents and workflows: an Agent or Workflow call becomes their card. */
        board: AgentBoard = AgentBoard.Empty,
        /** The session's own transcript: running agents whose call isn't loaded go last (not in an agent's). */
        orphans: Boolean = true,
    ): List<Item> {
        if (cache != null && (cache.cwd != cwd || cache.active != active)) { cache.byId.clear(); cache.cwd = cwd; cache.active = active }
        val out = ArrayList<Item>(events.size + 2)
        val seenApprovals = HashSet<String>()
        // Fork: approvals, questions and plans still waiting for an answer, kept for the end.
        val asks = ArrayList<Item>(1)
        // Agents with an approval waiting: their planet turns amber.
        val asking = if (board.isEmpty()) emptySet() else approvals.values.mapNotNullTo(HashSet()) { a ->
            a.agentId?.takeIf { a.sessionId == sessionId && a.id !in resolved && a.id !in answered }
        }
        // Agents and workflows drawn under their call; running ones whose call isn't loaded go last.
        val placed = HashSet<String>()
        // A tool that's waiting for its approval shows up once, in the approval block, not
        // also as a "running" terminal above it. An agent's approval is for its own tool, not one
        // of the main agent's.
        val waiting = HashSet<String>()
        for (a in approvals.values) {
            if (a.sessionId != sessionId || a.id in resolved || a.id in answered || a.agentId != null) continue
            val cmd = a.input["command"]
            val t = events.lastOrNull { it is WEvent.Tool && it.status == "running" && it.name == a.toolName && (cmd == null || it.input["command"] == null || it.input["command"] == cmd) }
            if (t != null) waiting += t.id
        }
        // What a command printed goes under the command, as in the terminal, not on a row of its
        // own; output whose command isn't here (history cut off before it) stays a row.
        val commands = if (events.any { it is WEvent.Notice && it.command != null }) events.mapNotNullTo(HashSet()) { (it as? WEvent.User)?.id } else emptySet()
        val outputs = HashMap<String, MutableList<CommandOutput>>()
        for (e in events) {
            if (e is WEvent.Notice && e.command != null && e.command in commands) {
                outputs.getOrPut(e.command) { ArrayList(1) } += CommandOutput(e.id, e.text, tone(e.tone))
            }
        }
        // Thinking still going (the newest event of a running session), and how long a finished one took
        // (from the prompt it answers, the only start the transcript knows).
        val thinking = HashMap<String, Pair<Boolean, Long?>>()
        for ((i, e) in events.withIndex()) {
            if (e !is WEvent.Thinking) continue
            val took = (events.getOrNull(i - 1) as? WEvent.User)?.let { e.ts - it.ts }?.takeIf { it in 1_000..1_800_000 }
            thinking[e.id] = (active && i == events.lastIndex) to took
        }
        for (e in events) {
            if (e.id in waiting) continue
            if (e is WEvent.Notice && e.command != null && e.command in commands) continue
            if (e is WEvent.Approval) {
                // Approvals depend on live state (pending, resolved, answered): always rebuilt.
                seenApprovals += e.approvalId
                val pendingLooking = e.resolution == null && approvals[e.approvalId] == null && resolved[e.approvalId] == null && answered[e.approvalId] == null
                // The daemon sends the event a moment before the approval itself, so only an
                // old one that isn't pending any more is closed; a fresh one is still arriving.
                val closed = pendingLooking && settled && now - e.ts > 4000
                val item = approval(e.id, e.approvalId, e.summary, if (closed) "closed" else e.resolution, approvals[e.approvalId], resolved[e.approvalId], answered[e.approvalId])
                // Fork: one still waiting goes last, under what came after it (another tool's
                // output, an agent's card, more text), so it's in view without scrolling up.
                // Answered, it's back in its place.
                if (item.resolved == null) asks += item else out += item
                continue
            }
            // An agent's or a workflow's call: its card, rebuilt as the agent moves on (not cached).
            if (e is WEvent.Tool && !board.isEmpty()) {
                val card = launcherCard(e, board, asking)
                if (card != null) {
                    placed += if (card is Item.WorkflowCard) card.workflow.id else (card as Item.AgentCard).agent.id
                    out += card
                    continue
                }
            }
            val hit = cache?.byId?.get(e.id)
            val item = if (hit != null && hit.first === e) hit.second else convert(e, cwd, now, active).also { cache?.byId?.put(e.id, e to it) }
            val printed = outputs[e.id]
            if (item is Item.User && printed != null) out += item.copy(output = printed)
            else if (item is Item.Thinking) thinking[e.id]?.let { (ongoing, took) -> out += item.copy(ongoing = ongoing, tookMs = took) } ?: run { out += item }
            else if (item != null) out += item
        }
        if (cache != null && cache.byId.size > events.size + 64) {
            val ids = events.mapTo(HashSet()) { it.id }
            cache.byId.keys.retainAll(ids)
        }
        if (orphans && !board.isEmpty()) out += unplaced(board, placed, asking, now)
        out += asks
        // A pending approval whose event hasn't arrived (yet): still answerable, at the end.
        for (a in approvals.values) {
            if (a.sessionId == sessionId && a.id !in seenApprovals) {
                out += approval("approval-${a.id}", a.id, a.summary, null, a, resolved[a.id], answered[a.id])
            }
        }
        return out
    }

    private val AGENT_CALLS = setOf("Agent", "Task")

    /** The card for an Agent or Workflow call whose agent or run the board knows; null otherwise (it stays a tool row). */
    private fun launcherCard(e: WEvent.Tool, board: AgentBoard, asking: Set<String>): Item? {
        val call = e.toolUseId ?: e.id
        if (e.name in AGENT_CALLS) {
            val a = board.byCall[call] ?: return null
            return Item.AgentCard(e.id, a.view(a.id in asking), board.nested(a.id, asking))
        }
        if (e.name == "Workflow") {
            val w = board.workflowByCall[call] ?: return null
            return Item.WorkflowCard(e.id, board.workflowView(w, asking))
        }
        return null
    }

    /**
     * Running agents and workflows whose call isn't among the loaded events (it's further back than
     * the history the phone loaded), so they aren't lost: last, under what Claude does now. One the
     * Mac has only just seen, with no call named yet, waits a moment for its call's row to take it.
     */
    private fun unplaced(board: AgentBoard, placed: Set<String>, asking: Set<String>, now: Long): List<Item> {
        val out = ArrayList<Item>()
        for (a in board.agents.values.sortedBy { it.startedAt }) {
            if (!a.running || a.id in placed || a.parentAgentId != null || a.workflowId != null) continue
            if (a.toolUseId == null && now - a.startedAt < 10_000) continue
            out += Item.AgentCard("agent:${a.id}", a.view(a.id in asking), board.nested(a.id, asking))
        }
        for (w in board.workflows.values.sortedBy { it.startedAt }) {
            if (w.running && w.id !in placed) out += Item.WorkflowCard("workflow:${w.id}", board.workflowView(w, asking))
        }
        return out
    }

    private fun convert(e: WEvent, cwd: String?, now: Long, active: Boolean): Item? = when (e) {
        is WEvent.User -> Item.User(e.id, e.text, files = e.files, command = e.command)
        is WEvent.Assistant -> if (e.text.isNotBlank() || e.streaming) Item.Assistant(e.id, e.text, e.streaming, ports(e.text)) else null
        is WEvent.Thinking -> Item.Thinking(e.id, e.chars)
        is WEvent.Tool -> tool(e, cwd, now, active)
        is WEvent.Result -> Item.Result(e.id, e.ok, e.durationMs, e.costUsd, e.turns, e.error)
        is WEvent.Notice -> Item.Notice(e.id, e.text, tone(e.tone), command = e.command, blocker = e.blocker)
        is WEvent.Unknown -> e.text?.takeIf { it.isNotBlank() }?.let { Item.Notice(e.id, it, Tone.Info) }
        is WEvent.Approval -> null
    }

    private fun tone(t: String) = when (t) { "warn" -> Tone.Warn; "error" -> Tone.Error; else -> Tone.Info }

    private fun tool(e: WEvent.Tool, cwd: String?, now: Long, active: Boolean): Item {
        // "Running" in a session that has stopped means it was cut off (interrupt, restart).
        val stopped = e.status == "running" && !active
        val running = e.status == "running" && active
        val failed = e.status == "error"
        val verb = e.name.substringAfterLast("__")
        if (e.name == "Bash") {
            var lines = e.output?.trimEnd()?.lines().orEmpty()
            if (lines.size > MAX_TERMINAL_LINES) lines = lines.takeLast(MAX_TERMINAL_LINES)
            return Item.Terminal(
                key = e.id,
                command = e.input["command"] ?: e.summary?.removePrefix("Bash: ") ?: "",
                lines = lines,
                running = running,
                exitCode = when {
                    running || stopped -> null
                    e.exitCode != null -> e.exitCode
                    failed -> 1
                    else -> 0
                },
                elapsedMs = e.durationMs ?: if (running) (now - e.ts).coerceAtLeast(0) else 0L,
                stopped = stopped,
                startedAt = if (running) e.ts else null,
                ports = ports(e.output),
            )
        }
        val d = e.diff
        if (d != null && !failed) {
            val lines = ArrayList<DiffLine>()
            d.hunks.forEachIndexed { i, h ->
                if (i > 0) lines += DiffLine('~', "")
                for (l in h.lines) {
                    if (l.isEmpty()) { lines += DiffLine(' ', ""); continue }
                    val k = l[0]
                    lines += if (k == '+' || k == '-' || k == ' ') DiffLine(k, l.substring(1)) else DiffLine(' ', l)
                }
            }
            val shown = if (lines.size > MAX_DIFF_LINES) lines.take(MAX_DIFF_LINES) + DiffLine('…', (lines.size - MAX_DIFF_LINES).toString()) else lines
            val path = d.path.ifBlank { e.path.orEmpty() }
            return Item.Diff(e.id, shortPath(path, cwd), d.added, d.removed, shown, path = path.ifBlank { null }, verb = verb)
        }
        val target = when {
            e.path != null -> shortPath(e.path, cwd)
            !e.summary.isNullOrBlank() -> e.summary.removePrefix("${e.name}: ").removePrefix("$verb: ")
            else -> e.input["file_path"] ?: e.input["pattern"] ?: e.input["url"] ?: e.input["query"] ?: e.input["description"] ?: ""
        }
        // A history loaded with outputs cut to their end starts them with "…".
        val error = if (failed) e.output?.removePrefix("…\n")?.trim()?.lines()?.filter { it.isNotBlank() }?.take(3)?.joinToString("\n") else null
        return Item.Tool(e.id, verb, target, path = e.path, running = running, failed = failed, error = error, ports = ports(e.output))
    }

    private fun approval(
        key: String, approvalId: String, summary: String?, eventResolution: String?,
        a: WApproval?, r: Resolved?, mine: Resolution?,
    ): Item.Approval {
        val kind = when (a?.kind) { "question" -> ApprovalKind.Question; "plan" -> ApprovalKind.Plan; else -> ApprovalKind.Tool }
        val decision = r?.decision ?: eventResolution
        val resolution: Resolution? = when {
            mine != null -> mine
            decision == null -> null
            decision == "closed" -> Resolution.Closed
            decision == "elsewhere" -> Resolution.AnsweredElsewhere
            decision == "terminal" || decision == "unknown" || r?.by == "terminal" -> Resolution.AnsweredOnMac
            decision == "timeout" || r?.by == "timeout" -> Resolution.TimedOut
            decision == "allow" || decision == "always" -> when (kind) {
                ApprovalKind.Question -> Resolution.Answered
                ApprovalKind.Plan -> Resolution.PlanApproved
                ApprovalKind.Tool -> if (decision == "always") Resolution.AllowedAlways else Resolution.Allowed
            }
            else -> if (kind == ApprovalKind.Plan) Resolution.KeptPlanning else Resolution.Denied
        }
        val sum = a?.summary?.takeIf { it.isNotBlank() } ?: summary.orEmpty()
        val tool = a?.toolName ?: sum.substringBefore(":", "").ifBlank { "Tool" }
        val detail = when {
            a?.input?.get("command") != null -> a.input.getValue("command")
            sum.startsWith("$tool: ") -> sum.removePrefix("$tool: ")
            else -> sum
        }
        return Item.Approval(
            key = key,
            tool = tool,
            detail = detail,
            reason = a?.reason.orEmpty(),
            resolved = resolution,
            approvalId = approvalId,
            kind = kind,
            canAlways = a?.canAlways ?: false,
            canSession = a?.canSession ?: false,
            questions = a?.questions.orEmpty(),
            plan = a?.plan,
            expiresAt = a?.expiresAt,
            by = r?.by,
            partial = a == null,
            // Empty: an agent asks whose name the Mac doesn't know yet.
            agent = a?.agentLabel ?: a?.agentId?.let { "" },
            change = a?.let { change(it.toolName, it.input, it.edits) },
            autoDeny = a?.autoDeny ?: false,
            alwaysRules = a?.alwaysRules.orEmpty(),
            alwaysScope = a?.alwaysScope,
        )
    }

    /** Lines of context kept around a change on an approval card. */
    private const val CONTEXT = 2

    /**
     * What an edit asks to change, as diff lines for its approval card (the Mac sends the text, up
     * to 4000 characters of each part): an Edit's old and new text, each of a MultiEdit's, the
     * whole of what a Write or a notebook cell puts in. Null for other tools, or when the text
     * didn't come.
     */
    fun change(tool: String, input: Map<String, String>, edits: List<Pair<String, String>>): List<DiffLine>? = when (tool) {
        "Edit" -> input["new_string"]?.let { replaced(input["old_string"].orEmpty(), it) }
        "MultiEdit" -> edits.takeIf { it.isNotEmpty() }?.flatMapIndexed { i, (old, new) ->
            (if (i > 0) listOf(DiffLine('~', "")) else emptyList()) + replaced(old, new)
        }
        "Write" -> input["content"]?.let(::added)
        "NotebookEdit" -> input["new_source"]?.let(::added)
        else -> null
    }?.takeIf { it.isNotEmpty() }

    private fun added(text: String): List<DiffLine> = text.trimEnd('\n').lines().map { DiffLine('+', it) }

    /**
     * [old] replaced by [new], line by line: the lines both start and end with stay as context (at
     * most [CONTEXT] of each), the ones between go out (−) and come in (+).
     */
    fun replaced(old: String, new: String): List<DiffLine> {
        val a = if (old.isEmpty()) emptyList() else old.lines()
        val b = if (new.isEmpty()) emptyList() else new.lines()
        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++
        var tail = 0
        while (tail < a.size - head && tail < b.size - head && a[a.size - 1 - tail] == b[b.size - 1 - tail]) tail++
        val out = ArrayList<DiffLine>()
        for (i in maxOf(0, head - CONTEXT) until head) out += DiffLine(' ', a[i])
        for (i in head until a.size - tail) out += DiffLine('-', a[i])
        for (i in head until b.size - tail) out += DiffLine('+', b[i])
        for (i in a.size - tail until minOf(a.size, a.size - tail + CONTEXT)) out += DiffLine(' ', a[i])
        return out
    }
}
