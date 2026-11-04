@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.ui.platform.wordsNow

import com.prudnikweb.hypernova.data.Str
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Mcp
import com.prudnikweb.hypernova.data.McpLiveTool
import com.prudnikweb.hypernova.data.McpLogin
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionMcp
import com.prudnikweb.hypernova.data.SessionMcpActed
import com.prudnikweb.hypernova.data.SessionMcpLive
import com.prudnikweb.hypernova.data.SessionMcpRow
import com.prudnikweb.hypernova.data.mcpLogin
import com.prudnikweb.hypernova.data.mcpLoginCancel
import com.prudnikweb.hypernova.data.mcpLoginFinish
import com.prudnikweb.hypernova.data.sessionMcpAct
import com.prudnikweb.hypernova.data.sessionMcpLive
import com.prudnikweb.hypernova.data.sessionMcpTools
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.mcp.McpLiveToolSheet
import com.prudnikweb.hypernova.ui.mcp.McpLoginSheet
import com.prudnikweb.hypernova.ui.mcp.McpToolRow
import com.prudnikweb.hypernova.ui.mcp.mcpScopeWord
import com.prudnikweb.hypernova.ui.mcp.mcpStatusColor
import com.prudnikweb.hypernova.ui.mcp.mcpStatusWord
import com.prudnikweb.hypernova.ui.mcp.mcpToolMark
import com.prudnikweb.hypernova.ui.mcp.AgentSettingsFailure
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * The session sheet's MCP tab (owner, 2026-11-03: "a new tab for MCPs for us to manage the MCPs, like reload them or disconnect, whatever you can do in the
 * terminal"; docs/DECISIONS.md, "The session sheet's MCP tab"). Its servers as this session has them, and what its kind can do to them. The server says
 * which actions a row has and, after one, what the state read back shows: this says that, and never that something happened to the session unless it did.
 * Adding and removing are Settings', and it's linked. It asks once as it opens, and when asked to, since in a terminal each ask opens /mcp there for a moment.
 */
@Composable
internal fun McpTab(hub: Hub, s: Session, onDismiss: () -> Unit) {
    val opener = LocalOpener.current
    val scope = rememberCoroutineScope()
    val res = wordsNow()
    var attempt by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<Result<SessionMcpLive>?>(null) }
    /** The server an action is under way on: the others wait for it, one terminal being one screen. */
    var busy by remember { mutableStateOf<String?>(null) }
    /** What the last action came to, under its row. */
    var result by remember { mutableStateOf<Pair<String, McpResultLine>?>(null) }
    var login by remember { mutableStateOf<Pair<String, McpLogin>?>(null) }
    LaunchedEffect(s.id, attempt) {
        reply = null
        reply = hub.sessionMcpLive(s.id, fresh = attempt > 0)
    }
    val fallback = stringResource(Str.smt_act_err)
    fun act(action: String, name: String) {
        busy = name
        result = null
        scope.launch {
            hub.sessionMcpAct(s.id, action, name)
                .onSuccess { a ->
                    reply = Result.success(a.reply)
                    result = name to mcpResultLine(a) { res.getString(it) }
                }
                .onFailure { e -> result = name to McpResultLine(e.message?.takeIf { it.isNotBlank() } ?: fallback, trouble = true) }
            busy = null
        }
    }
    val r = reply
    val live = r?.getOrNull()
    SheetSeam(stringResource(Str.smt_seam))
    when {
        r == null -> LoadingLine(stringResource(Str.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        r.isFailure -> AgentSettingsFailure(r.exceptionOrNull(), stringResource(Str.smt_err)) { attempt++ }
        live != null -> {
            HText(stringResource(mcpNote(live)), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            live.unreadWords?.let { HText(it, style = Type.small, color = HN.amber, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) }
            if (live.servers.isEmpty() && live.unreadWords == null) HText(stringResource(Str.smt_empty), style = Type.body, color = HN.inkMuted, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            live.servers.forEachIndexed { i, row ->
                if (i > 0) Hairline(Modifier.fillMaxWidth())
                val line = result?.takeIf { it.first == row.name }?.second
                McpRowPart(
                    hub, s, live, row, busy, line,
                    onAct = { action -> act(action, row.name) },
                    onSignIn = {
                        busy = row.name
                        result = null
                        scope.launch {
                            hub.mcpLogin(live.agent, row.name, live.folder)
                                .onSuccess { l -> if (l.done) l.words?.let { w -> result = row.name to McpResultLine(w, trouble = false) } else login = row.name to l }
                                .onFailure { e -> result = row.name to McpResultLine(e.message?.takeIf { it.isNotBlank() } ?: fallback, trouble = true) }
                            busy = null
                        }
                    },
                )
            }
            // What the owner said belongs elsewhere, and what can't be done from here.
            Spacer(Modifier.height(10.dp))
            Pill(stringResource(Str.smt_check), { result = null; attempt++ }, kind = PillKind.Outline, height = 40.dp, enabled = busy == null, modifier = Modifier.padding(start = 4.dp))
            if (live.kind == SessionMcp.TERMINAL || live.kind == SessionMcp.JOB || live.kind == SessionMcp.CODEX || s.term != null) {
                Spacer(Modifier.height(10.dp))
                SheetLink(stringResource(Str.open_terminal), null, { onDismiss(); opener.terminal(s.id) })
            }
            Spacer(Modifier.height(8.dp))
            SheetLink(stringResource(Str.smt_settings), null, { onDismiss(); opener.mcpServers(live.agent) })
            HText(stringResource(Str.smt_settings_body), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        }
    }
    login?.let { (name, l) ->
        val agent = live?.agent ?: AgentSettingsReply.CLAUDE
        McpLoginSheet(
            l,
            onCancel = {
                login = null
                l.login?.let { id -> scope.launch { hub.mcpLoginCancel(agent, id) } }
            },
            onFinish = { address, done ->
                scope.launch {
                    hub.mcpLoginFinish(agent, l.login.orEmpty(), address)
                        .onSuccess {
                            login = null
                            done(null)
                            // Signed in, for Claude Code. This session's own connection takes it only when it reconnects (measured), so it's asked to, and what's read back is what's said.
                            if (live?.live == true) act(SessionMcp.RECONNECT, name)
                            else {
                                result = name to McpResultLine(res.getString(Str.smt_signed_in_rest), trouble = false)
                                attempt++
                            }
                        }
                        .onFailure { e -> done(e.message?.takeIf { it.isNotBlank() } ?: fallback) }
                }
            },
        )
    }
}

/** What's said under a row after an action. */
internal data class McpResultLine(val words: String, val trouble: Boolean)

/** What the state read back says happened, in this kind's words. The agent's own words after them, where it failed. */
internal fun mcpResultLine(a: SessionMcpActed, words: (Str) -> String): McpResultLine {
    val codex = a.reply.agent == AgentSettingsReply.CODEX
    val id: Str? = when (a.outcome) {
        SessionMcp.DONE -> when (a.action) {
            SessionMcp.RECONNECT -> if (codex) Str.smt_did_reconnect_codex else Str.smt_did_reconnect
            SessionMcp.DISABLE -> if (codex) Str.smt_did_disable_codex else Str.smt_did_disable
            else -> if (codex) Str.smt_did_enable_codex else Str.smt_did_enable
        }
        SessionMcp.NEXT_TURN -> when (a.action) {
            SessionMcp.DISABLE -> if (codex) Str.smt_next_turn_disable_codex else Str.smt_next_turn_disable
            else -> if (codex) Str.smt_next_turn_enable_codex else Str.smt_next_turn_enable
        }
        SessionMcp.NEW_SESSIONS -> Str.smt_new_sessions
        SessionMcp.UNCONFIRMED -> Str.smt_unconfirmed
        // Its own words say it best, and they say what to do.
        SessionMcp.NO_CHANGE -> if (a.said == null) Str.smt_unconfirmed else null
        else -> when (a.action) {
            SessionMcp.RECONNECT -> Str.smt_failed_reconnect
            SessionMcp.DISABLE -> Str.smt_failed_disable
            else -> Str.smt_failed_enable
        }
    }
    val failed = a.outcome == SessionMcp.FAILED
    val line = when {
        id == null -> a.said.orEmpty()
        // "Failed to reconnect to x: why" adds the why, where it had one.
        failed && a.said != null -> "${words(id)} ${a.said}"
        else -> words(id)
    }
    return McpResultLine(line, trouble = failed || a.outcome == SessionMcp.UNCONFIRMED || a.outcome == SessionMcp.NO_CHANGE)
}

private fun mcpNote(live: SessionMcpLive): Str = when (live.kind) {
    SessionMcp.TERMINAL, SessionMcp.JOB -> Str.smt_note_terminal
    SessionMcp.TURN -> Str.smt_note_turn
    SessionMcp.REST -> Str.smt_note_rest
    SessionMcp.CODEX -> if (live.live) Str.smt_note_codex else Str.smt_note_codex_idle
    SessionMcp.ENDED -> Str.smt_note_ended
    else -> Str.smt_note_watched
}

/** One server: its name and status, how it's set up, its tools to open, what can be done, and what came of the last thing done. */
@Composable
private fun McpRowPart(hub: Hub, s: Session, live: SessionMcpLive, row: SessionMcpRow, busy: String?, line: McpResultLine?, onAct: (String) -> Unit, onSignIn: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            HText(row.name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            HText(stringResource(mcpStatusWord(row.status)), style = Type.monoSmall, color = mcpStatusColor(row.status), maxLines = 1)
        }
        val how = listOfNotNull(row.source.takeIf { it != "unknown" }?.let { stringResource(mcpScopeWord(it)) }, row.transport, row.command, row.host).joinToString(" · ")
        if (how.isNotBlank()) HText(how, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        row.error?.let { HText(it, style = Type.small, color = HN.amber, modifier = Modifier.padding(top = 4.dp)) }
        if (row.offForNew) HText(stringResource(Str.smt_off_for_new), style = Type.small, color = HN.amber, modifier = Modifier.padding(top = 4.dp))
        if (row.cut) HText(stringResource(Str.smt_cut), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 4.dp))
        if (row.status == Mcp.WAITING || row.status == Mcp.REJECTED) {
            HText(stringResource(if (live.kind == SessionMcp.REST && row.status == Mcp.WAITING) Str.smt_held_rest else Str.smt_held), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 4.dp))
        }
        McpToolsPart(hub, s, row)
        if (row.signIn || row.actions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val free = busy == null
                if (row.signIn) Pill(stringResource(Str.mcp_sign_in), onSignIn, kind = PillKind.Primary, height = 40.dp, enabled = free)
                row.actions.forEach { action ->
                    when (action) {
                        SessionMcp.RECONNECT -> Pill(stringResource(Str.mcp_reconnect), { onAct(action) }, kind = PillKind.Outline, height = 40.dp, enabled = free)
                        SessionMcp.DISABLE -> Pill(stringResource(Str.mcp_disable), { onAct(action) }, kind = PillKind.Outline, height = 40.dp, enabled = free)
                        SessionMcp.ENABLE -> Pill(stringResource(if (row.offForNew) Str.smt_keep_on else Str.mcp_enable), { onAct(action) }, kind = PillKind.Primary, height = 40.dp, enabled = free)
                    }
                }
            }
        }
        if (busy == row.name) LoadingLine(stringResource(Str.smt_working), Modifier.padding(top = 8.dp))
        line?.let { HText(it.words, style = Type.small, color = if (it.trouble) HN.amber else HN.inkMuted, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/**
 * Its tools, folded under a count: as a terminal's screen shows them they're read off it only when asked for. What a session's /mcp says of a tool is its name
 * and hints, so a tool's sheet says that and points at Settings' page.
 */
@Composable
private fun McpToolsPart(hub: Hub, s: Session, row: SessionMcpRow) {
    val count = row.toolCount ?: return
    if (row.status != Mcp.CONNECTED) return
    val scope = rememberCoroutineScope()
    var open by remember(row.name) { mutableStateOf(false) }
    var tools by remember(row.name, row.tools) { mutableStateOf<Result<List<McpLiveTool>>?>(if (row.tools.isNotEmpty()) Result.success(row.tools) else null) }
    var tool by remember { mutableStateOf<McpLiveTool?>(null) }
    val label = when {
        open -> stringResource(Str.smt_tools_hide)
        count == 0 -> stringResource(Str.smt_no_tools)
        count == 1 -> stringResource(Str.smt_one_tool)
        else -> stringResource(Str.smt_tools_count, count)
    }
    if (count == 0) {
        HText(label, style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 4.dp))
        return
    }
    Pill(label, {
        open = !open
        if (open && tools?.isSuccess != true) {
            tools = null
            scope.launch { tools = hub.sessionMcpTools(s.id, row.name) }
        }
    }, kind = PillKind.Ghost, height = 36.dp, modifier = Modifier.padding(top = 2.dp))
    if (!open) return
    val t = tools
    when {
        t == null -> LoadingLine(stringResource(Str.loading), Modifier.padding(vertical = 6.dp))
        t.isFailure -> HText(t.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: stringResource(Str.smt_tools_err), style = Type.small, color = HN.amber, modifier = Modifier.padding(top = 4.dp))
        else -> t.getOrThrow().forEachIndexed { i, x ->
            if (i > 0) Hairline(Modifier.fillMaxWidth())
            McpToolRow(x.name, null, mcpToolMark(x.readOnly.takeIf { it }, x.destructive.takeIf { it })) { tool = x }
        }
    }
    tool?.let { x -> McpLiveToolSheet(x, stringResource(Str.smt_tool_names)) { tool = null } }
}
