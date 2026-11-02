@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.prudnikweb.hypernova.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.McpForm
import com.prudnikweb.hypernova.data.McpLogin
import com.prudnikweb.hypernova.data.McpPair
import com.prudnikweb.hypernova.data.mcpAdd
import com.prudnikweb.hypernova.data.mcpApproval
import com.prudnikweb.hypernova.data.mcpEdit
import com.prudnikweb.hypernova.data.mcpLogin
import com.prudnikweb.hypernova.data.mcpLoginCancel
import com.prudnikweb.hypernova.data.mcpLoginFinish
import com.prudnikweb.hypernova.data.mcpLogout
import com.prudnikweb.hypernova.data.mcpReconnect
import com.prudnikweb.hypernova.data.mcpRemove
import com.prudnikweb.hypernova.data.mcpResetChoices
import com.prudnikweb.hypernova.data.mcpToggle
import com.prudnikweb.hypernova.data.mcpUndo
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.FieldLabel
import com.prudnikweb.hypernova.ui.components.NavRow
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingsFile
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Mcp
import com.prudnikweb.hypernova.data.McpFacts
import com.prudnikweb.hypernova.data.McpFolder
import com.prudnikweb.hypernova.data.McpList
import com.prudnikweb.hypernova.data.McpLive
import com.prudnikweb.hypernova.data.McpLiveReply
import com.prudnikweb.hypernova.data.McpLiveTool
import com.prudnikweb.hypernova.data.McpRow
import com.prudnikweb.hypernova.data.McpServer
import com.prudnikweb.hypernova.data.McpSetup
import com.prudnikweb.hypernova.data.McpThing
import com.prudnikweb.hypernova.data.McpTool
import com.prudnikweb.hypernova.data.mcpList
import com.prudnikweb.hypernova.data.mcpLive
import com.prudnikweb.hypernova.data.mcpServer
import com.prudnikweb.hypernova.data.mcpTools
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

@Composable
private fun seamMod() = Modifier.padding(horizontal = 4.dp).padding(top = 8.dp, bottom = 2.dp)

/** The status's word and colour. Null: not asked yet, or it isn't in the answer. */
@StringRes
internal fun mcpStatusWord(status: String?): Int = when (status) {
    Mcp.CONNECTED -> R.string.mcp_s_connected
    Mcp.FAILED -> R.string.mcp_s_failed
    Mcp.NEEDS_AUTH -> R.string.mcp_s_needs_auth
    Mcp.PENDING -> R.string.mcp_s_pending
    Mcp.DISABLED -> R.string.mcp_s_disabled
    Mcp.WAITING -> R.string.mcp_s_waiting
    Mcp.REJECTED -> R.string.mcp_s_rejected
    else -> R.string.mcp_s_unknown
}

@Composable
private fun statusColor(status: String?): Color = when (status) {
    Mcp.CONNECTED -> HN.accent
    Mcp.FAILED, Mcp.NEEDS_AUTH, Mcp.WAITING -> HN.amber
    else -> HN.inkDim
}

@StringRes
internal fun mcpScopeWord(scope: String): Int = when (scope) {
    Mcp.USER -> R.string.mcp_scope_user
    Mcp.LOCAL -> R.string.mcp_scope_local
    Mcp.PROJECT -> R.string.mcp_scope_project
    Mcp.SETTINGS -> R.string.mcp_scope_settings
    Mcp.PLUGIN -> R.string.mcp_scope_plugin
    "managed", "enterprise" -> R.string.mcp_scope_managed
    "claudeai" -> R.string.mcp_scope_claudeai
    else -> R.string.mcp_scope_plugin
}

/** Agents & providers › an agent's settings › MCP servers: every scope, each to tap, with its status from the folder chosen. */
@Composable
fun McpServersScreen(hub: Hub, agent: String, onBack: () -> Unit, onOpen: (name: String, scope: String, folder: String?) -> Unit, onAdd: () -> Unit = {}) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val server = hub.mac.name
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<Result<McpList>?>(null) }
    var undoing by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf(Mcp.HOME) }
    var live by remember { mutableStateOf<Result<McpLiveReply>?>(null) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(attempt, hub.agentSettingsVersion) {
        if (reply?.isSuccess != true) reply = null
        reply = hub.mcpList(agent)
    }
    LaunchedEffect(attempt, folder, hub.agentSettingsVersion) {
        live = null
        live = hub.mcpLive(agent, folder)
    }
    ScreenScaffold(
        sourceKey = "mcp:$agent",
        title = stringResource(if (agent == AgentSettingsReply.CODEX) R.string.mcp_title_codex else R.string.mcp_title_claude),
        subtitle = server,
        onBack = onBack,
    ) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "intro") { HText(stringResource(R.string.mcp_intro, server), style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
            val r = reply
            when {
                r == null -> item(key = "loading") { LoadingLine(stringResource(R.string.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                r.isFailure -> item(key = "failure") { McpFailure(r.exceptionOrNull(), R.string.mcp_err) { attempt++ } }
                else -> {
                    item(key = "add") { Pill(stringResource(R.string.mcp_add), onAdd, kind = PillKind.Outline, height = 44.dp, leading = Glyph.Plus, modifier = Modifier.padding(horizontal = 4.dp)) }
                    r.getOrThrow().undo?.let { u ->
                        item(key = "undo") {
                            val words = stringResource(when (u.op) { "add" -> R.string.mcp_undo_add; "remove" -> R.string.mcp_undo_remove; else -> R.string.mcp_undo_edit }, u.name)
                            GlassCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                                    NavRow(stringResource(R.string.as_undo_row), if (undoing) stringResource(R.string.as_saving) else words, onClick = {
                                        if (undoing) return@NavRow
                                        val go = { check: Boolean ->
                                            undoing = true
                                            scope.launch {
                                                hub.mcpUndo(agent, check)
                                                    .onSuccess { toast(context.getString(R.string.as_undone, server)); attempt++ }
                                                    .onFailure { e -> toast(e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err)); attempt++ }
                                                undoing = false
                                            }
                                            Unit
                                        }
                                        if (u.needsCheck) mcpRunCheck(context, confirm, toast, server, u.name, u.command) { go(true) } else go(false)
                                    })
                                }
                            }
                        }
                    }
                    mcpListItems(r.getOrThrow(), folder, live, { picking = true }, onOpen)
                }
            }
        }
    }
    val folders = reply?.getOrNull()?.folders.orEmpty()
    if (picking) {
        GlassSheet(stringResource(R.string.mcp_folder_pick), { picking = false }) {
            HText(stringResource(R.string.mcp_folder_pick), style = Type.title, color = HN.ink)
            Spacer(Modifier.height(8.dp))
            folders.forEach { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { folder = f.id; picking = false }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HText(mcpFolderName(f), style = Type.body, color = if (f.id == folder) HN.ink else HN.inkMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (f.id == folder) Icon(Glyph.Check, size = 14.dp, color = HN.accent)
                }
            }
        }
    }
}

@Composable
internal fun mcpFolderName(f: McpFolder): String = if (f.id == Mcp.HOME) stringResource(R.string.mcp_home) else f.name

private fun LazyListScope.mcpListItems(
    list: McpList,
    folder: String,
    live: Result<McpLiveReply>?,
    onPickFolder: () -> Unit,
    onOpen: (String, String, String?) -> Unit,
) {
    val states = live?.getOrNull()?.servers.orEmpty()
    fun liveOf(row: McpRow): McpLive? = states.firstOrNull { it.name == row.name && it.source == row.scope }.takeIf { row.folder == null || row.folder == folder } ?: if (row.folder == null) states.firstOrNull { it.name == row.name && it.source == Mcp.USER } else null
    val fromFiles = setOf(Mcp.USER, Mcp.LOCAL, Mcp.PROJECT)
    val others = states.filter { it.source !in fromFiles }
    val common = list.servers.filter { it.folder == null }
    val byFolder = list.servers.filter { it.folder != null }.groupBy { it.project ?: it.folder!! }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    // Codex has one list, for every folder: only whether it's checking is worth a line.
    if (list.agent == AgentSettingsReply.CODEX) item(key = "checking") {
        val line = when {
            live == null -> stringResource(R.string.mcp_status_checking)
            live.isFailure -> live.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mcp_status_err)
            else -> null
        }
        if (line != null) HText(line, style = Type.small, color = if (live?.isFailure == true) HN.amber else HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp))
    } else item(key = "folder") {
        GlassCard(Modifier.fillMaxWidth(), onClick = onPickFolder) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HText(stringResource(R.string.mcp_folder), style = Type.body, color = HN.ink, modifier = Modifier.weight(1f))
                    HText(list.folders.firstOrNull { it.id == folder }?.let { mcpFolderName(it) } ?: stringResource(R.string.mcp_home), style = Type.monoSmall, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
                    Spacer(Modifier.width(8.dp))
                    Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
                }
                Spacer(Modifier.height(2.dp))
                HText(
                    when {
                        live == null -> stringResource(R.string.mcp_status_checking)
                        live.isFailure -> live.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mcp_status_err)
                        else -> stringResource(R.string.mcp_folder_body)
                    },
                    style = Type.small, color = if (live?.isFailure == true) HN.amber else HN.inkDim,
                )
            }
        }
    }
    if (list.state == AgentSettingsFile.MALFORMED || list.state == AgentSettingsFile.UNREADABLE) {
        item(key = "trouble") { HText(stringResource(R.string.as_mcp_trouble), style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
    if (list.servers.isEmpty() && others.isEmpty() && live != null) {
        item(key = "none") { HText(stringResource(R.string.mcp_none), style = Type.body, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
    }
    fun group(key: String, title: @Composable () -> String, rows: List<McpRow>) {
        if (rows.isEmpty()) return
        item(key = "$key-seam") { Seam(title(), modifier = seamMod()) }
        item(key = "$key-card") {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    rows.forEachIndexed { i, row ->
                        if (i > 0) Hairline(Modifier.fillMaxWidth())
                        McpServerRow(row.name, row.scope, listOfNotNull(row.transport, row.summary).joinToString(" · "), liveOf(row)?.status ?: row.approval?.takeIf { it != "approved" }?.let { if (it == "pending") Mcp.WAITING else Mcp.REJECTED }) { onOpen(row.name, row.scope, row.folder ?: folder) }
                    }
                }
            }
        }
    }
    group("common", { stringResource(R.string.mcp_seam_user) }, common)
    byFolder.forEach { (name, rows) -> group("folder-$name", { name }, rows) }
    if (others.isNotEmpty()) {
        item(key = "others-seam") { Seam(stringResource(R.string.mcp_seam_plugin), modifier = seamMod()) }
        item(key = "others") {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    others.forEachIndexed { i, s ->
                        if (i > 0) Hairline(Modifier.fillMaxWidth())
                        McpServerRow(s.name, s.source, s.setup?.let { listOfNotNull(it.transport, it.command ?: it.url).joinToString(" · ") }.orEmpty(), s.status) { onOpen(s.name, s.source, folder) }
                    }
                }
            }
        }
    }
    if (list.hidden > 0) item(key = "hidden") { HText(pluralStringResource(R.plurals.as_hidden, list.hidden, list.hidden), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp)) }
    if (list.foldersNotRead > 0) item(key = "not-read") { HText(pluralStringResource(R.plurals.as_mcp_not_read, list.foldersNotRead, list.foldersNotRead), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp)) }
}

@Composable
private fun McpServerRow(name: String, scope: String, how: String, status: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            HText(name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val under = listOf(stringResource(mcpScopeWord(scope)), how).filter { it.isNotBlank() }.joinToString(" · ")
            HText(under, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        HText(stringResource(mcpStatusWord(status)), style = Type.monoSmall, color = statusColor(status), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
    }
}

@Composable
internal fun McpFailure(e: Throwable?, @StringRes fallback: Int, onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        HText(e?.message?.takeIf { it.isNotBlank() } ?: stringResource(fallback), style = Type.small, color = HN.amber)
        if (e !is AgentSettingsTooOld) {
            Spacer(Modifier.height(10.dp))
            Pill(stringResource(R.string.try_again), onRetry, kind = PillKind.Outline, height = 40.dp)
        }
    }
}

/** One server: where it's defined, how it's set up, and how it's doing from [folder]. */
@Composable
fun McpServerScreen(hub: Hub, agent: String, name: String, scope: String, folder: String?, onBack: () -> Unit, onEdit: () -> Unit = {}) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val coroutines = rememberCoroutineScope()
    val serverName = hub.mac.name
    /** A change under way: the buttons wait for it. */
    var busy by remember { mutableStateOf(false) }
    /** What the last change said, under the buttons. */
    var trouble by remember { mutableStateOf<String?>(null) }
    var login by remember { mutableStateOf<McpLogin?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<Result<McpServer>?>(null) }
    var live by remember { mutableStateOf<Result<McpLiveReply>?>(null) }
    LaunchedEffect(attempt, hub.agentSettingsVersion) {
        if (reply?.isSuccess != true) reply = null
        reply = hub.mcpServer(agent, name, scope, folder)
    }
    LaunchedEffect(attempt, hub.agentSettingsVersion) {
        live = null
        live = hub.mcpLive(agent, folder)
    }
    var facts by remember { mutableStateOf<Result<McpFacts>?>(null) }
    var tool by remember { mutableStateOf<McpTool?>(null) }
    var liveTool by remember { mutableStateOf<McpLiveTool?>(null) }
    LaunchedEffect(attempt, hub.agentSettingsVersion) {
        facts = null
        facts = hub.mcpTools(agent, name, scope, folder)
    }
    ScreenScaffold(sourceKey = "mcp:$agent:$scope:$folder:$name", title = name, subtitle = hub.mac.name, onBack = onBack) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val r = reply
            when {
                r == null -> item(key = "loading") { LoadingLine(stringResource(R.string.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                r.isFailure -> item(key = "failure") { McpFailure(r.exceptionOrNull(), R.string.mcp_err) { attempt++ } }
                else -> {
                    val s = r.getOrThrow()
                    val mine = live?.getOrNull()?.servers?.firstOrNull { it.name == name }
                    item(key = "where-seam") { Seam(stringResource(R.string.mcp_seam_where), modifier = seamMod()) }
                    item(key = "where") { WhereCard(s) }
                    item(key = "status-seam") { Seam(stringResource(R.string.mcp_seam_status), modifier = seamMod()) }
                    item(key = "status") {
                        StatusCard(live, mine)
                    }
                    item(key = "actions") {
                        fun run(work: suspend () -> Result<*>, ok: String?, then: () -> Unit = {}) {
                            busy = true
                            trouble = null
                            coroutines.launch {
                                work().onSuccess { ok?.let(toast); attempt++; then() }.onFailure { e -> trouble = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err); attempt++ }
                                busy = false
                            }
                        }
                        ActionsCard(
                            s, mine, busy, trouble,
                            onToggle = { on -> run({ hub.mcpToggle(agent, name, folder, on) }, context.getString(if (on) R.string.mcp_turned_on else R.string.mcp_turned_off)) },
                            onReconnect = { run({ hub.mcpReconnect(agent, name, folder) }, context.getString(R.string.mcp_reconnected)) },
                            onApprove = {
                                mcpRunCheck(context, confirm, toast, serverName, name, s.commandLine()) { run({ hub.mcpApproval(agent, name, folder, true) }, context.getString(R.string.mcp_approved)) }
                            },
                            onReject = { run({ hub.mcpApproval(agent, name, folder, false) }, context.getString(R.string.mcp_rejected)) },
                            onResetChoices = {
                                confirm(Confirm(context.getString(R.string.mcp_reset_title), context.getString(R.string.mcp_reset_body), context.getString(R.string.mcp_reset_action), danger = false) {
                                    run({ hub.mcpResetChoices(agent, folder) }, null)
                                })
                            },
                            onSignIn = {
                                busy = true
                                trouble = null
                                coroutines.launch {
                                    hub.mcpLogin(agent, name, folder)
                                        .onSuccess { l -> if (l.done) { l.words?.let(toast); attempt++ } else login = l }
                                        .onFailure { e -> trouble = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err) }
                                    busy = false
                                }
                            },
                            onSignOut = { run({ hub.mcpLogout(agent, name, folder) }, context.getString(R.string.mcp_signed_out)) },
                            onEdit = onEdit,
                            onRemove = {
                                confirm(Confirm(context.getString(R.string.mcp_remove_title, name), context.getString(R.string.mcp_remove_body), context.getString(R.string.mcp_remove_action), danger = true) {
                                    run({ hub.mcpRemove(s) }, context.getString(R.string.mcp_removed, serverName), onBack)
                                })
                            },
                        )
                    }
                    item(key = "setup-seam") { Seam(stringResource(R.string.mcp_seam_setup), modifier = seamMod()) }
                    item(key = "setup") { SetupCard(s.setup) }
                    item(key = "tools-seam") { Seam(stringResource(R.string.mcp_seam_tools), modifier = seamMod()) }
                    item(key = "tools") { ToolsCard(facts, mine, { tool = it }, { liveTool = it }) }
                    facts?.getOrNull()?.let { f ->
                        f.instructions?.let { words ->
                            item(key = "instructions-seam") { Seam(stringResource(R.string.mcp_seam_instructions), modifier = seamMod()) }
                            item(key = "instructions") { TextCard(words) }
                        }
                        if (f.prompts.isNotEmpty()) {
                            item(key = "prompts-seam") { Seam(stringResource(R.string.mcp_seam_prompts), modifier = seamMod()) }
                            item(key = "prompts") { ThingsCard(f.prompts) }
                        }
                        if (f.resources.isNotEmpty()) {
                            item(key = "resources-seam") { Seam(stringResource(R.string.mcp_seam_resources), modifier = seamMod()) }
                            item(key = "resources") { ThingsCard(f.resources) }
                        }
                    }
                }
            }
        }
    }
    login?.let { l ->
        LoginSheet(
            l,
            onCancel = {
                login = null
                l.login?.let { id -> coroutines.launch { hub.mcpLoginCancel(agent, id) } }
            },
            onFinish = { address, done ->
                coroutines.launch {
                    hub.mcpLoginFinish(agent, l.login.orEmpty(), address)
                        .onSuccess { login = null; toast(context.getString(R.string.mcp_signed_in)); attempt++; done(null) }
                        .onFailure { e -> done(e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err)) }
                }
            },
        )
    }
    tool?.let { t -> ToolSheet(t) { tool = null } }
    liveTool?.let { t -> LiveToolSheet(t) { liveTool = null } }
}

@Composable
private fun Fact(label: String, value: String, mono: Boolean = true, color: Color = HN.ink) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp)) {
        HText(label, style = Type.small, color = HN.inkDim)
        Spacer(Modifier.height(2.dp))
        HText(value, style = if (mono) Type.monoSmall else Type.body, color = color)
    }
}

@Composable
private fun WhereCard(s: McpServer) {
    val place = s.project ?: stringResource(R.string.mcp_home)
    val body = when (s.scope) {
        Mcp.USER -> stringResource(R.string.mcp_scope_user_body)
        Mcp.LOCAL -> stringResource(R.string.mcp_scope_local_body, place)
        Mcp.PROJECT -> stringResource(R.string.mcp_scope_project_body, place)
        Mcp.SETTINGS -> stringResource(R.string.mcp_scope_settings_body)
        "managed", "enterprise" -> stringResource(R.string.mcp_scope_managed_body)
        "claudeai" -> stringResource(R.string.mcp_scope_claudeai_body)
        else -> stringResource(R.string.mcp_scope_plugin_body)
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Fact(stringResource(R.string.mcp_scope), body, mono = false)
            s.where?.let {
                Hairline(Modifier.fillMaxWidth())
                Fact(stringResource(R.string.mcp_file), it)
            }
        }
    }
}

@Composable
private fun StatusCard(live: Result<McpLiveReply>?, mine: McpLive?) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            when {
                live == null -> LoadingLine(stringResource(R.string.mcp_status_checking))
                live.isFailure -> HText(live.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mcp_status_err), style = Type.small, color = HN.amber)
                else -> {
                    HText(stringResource(mcpStatusWord(mine?.status)), style = Type.body, color = statusColor(mine?.status))
                    mine?.serverName?.let { n -> HText(listOfNotNull(n, mine.version).joinToString(" "), style = Type.monoSmall, color = HN.inkMuted, modifier = Modifier.padding(top = 2.dp)) }
                    mine?.error?.let { HText(it, style = Type.small, color = HN.amber, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
}

/** What may be said of how it's set up. Values of env vars and headers aren't known here at all. */
@Composable
internal fun SetupCard(setup: McpSetup) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            var first = true
            @Composable fun row(label: String, value: String?) {
                if (value.isNullOrBlank()) return
                if (!first) Hairline(Modifier.fillMaxWidth())
                first = false
                Fact(label, value)
            }
            row(stringResource(R.string.mcp_type), setup.transport ?: stringResource(R.string.sm_other))
            row(stringResource(R.string.mcp_command), setup.command)
            row(stringResource(R.string.mcp_args), setup.args.joinToString("\n").ifBlank { null })
            row(stringResource(R.string.mcp_url), setup.url)
            row(stringResource(R.string.mcp_env), setup.envNames.joinToString("\n").ifBlank { null })
            row(stringResource(R.string.mcp_headers), setup.headerNames.joinToString("\n").ifBlank { null })
            row(stringResource(R.string.mcp_helper), setup.headersHelper)
            row(stringResource(R.string.mcp_oauth_client), setup.clientId)
            row(stringResource(R.string.mcp_oauth_port), setup.callbackPort?.toString())
            row(stringResource(R.string.mcp_timeout), setup.timeout?.let { stringResource(R.string.mcp_timeout_ms, it) })
            row(stringResource(R.string.mcp_bearer), setup.bearerEnv)
            row(stringResource(R.string.mcp_cwd), setup.cwd)
            row(stringResource(R.string.mcp_tools_on), setup.toolsOn.joinToString("\n").ifBlank { null })
            row(stringResource(R.string.mcp_tools_off), setup.toolsOff.joinToString("\n").ifBlank { null })
            row(stringResource(R.string.mcp_startup), setup.startupTimeout?.let { stringResource(R.string.mcp_startup_s, it) })
            if (setup.envNames.isNotEmpty() || setup.headerNames.isNotEmpty()) {
                HText(stringResource(R.string.mcp_values_stay), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (setup.hidden > 0) HText(pluralStringResource(R.plurals.as_hidden, setup.hidden, setup.hidden), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@StringRes
private fun whyNoTools(why: String?): Int = when (why) {
    "approval" -> R.string.mcp_why_approval
    "disabled" -> R.string.mcp_why_disabled
    "needs_auth" -> R.string.mcp_why_auth
    "too_slow" -> R.string.mcp_why_slow
    "failed" -> R.string.mcp_why_failed
    else -> R.string.mcp_why_names
}

/** The tools in full if the server told us; else the names the agent's process knows, with why that's all. */
@Composable
private fun ToolsCard(facts: Result<McpFacts>?, mine: McpLive?, onTool: (McpTool) -> Unit, onLive: (McpLiveTool) -> Unit) {
    val f = facts?.getOrNull()
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            when {
                facts == null -> LoadingLine(stringResource(R.string.mcp_tools_asking), Modifier.padding(vertical = 8.dp))
                f != null && f.tools.isNotEmpty() -> {
                    f.tools.forEachIndexed { i, t ->
                        if (i > 0) Hairline(Modifier.fillMaxWidth())
                        ToolRow(t.name, t.description, toolMarks(t.readOnly, t.destructive)) { onTool(t) }
                    }
                    if (f.moreTools > 0) HText(pluralStringResource(R.plurals.mcp_more_tools, f.moreTools, f.moreTools), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 8.dp))
                }
                else -> {
                    val names = mine?.tools.orEmpty()
                    names.forEachIndexed { i, t ->
                        if (i > 0) Hairline(Modifier.fillMaxWidth())
                        ToolRow(t.name, null, toolMarks(t.readOnly.takeIf { it }, t.destructive.takeIf { it })) { onLive(t) }
                    }
                    // Claude Code's sign-in is its own, so a connected server can still say no to the daemon.
                    val why = if (f?.whyNot == "needs_auth" && mine?.status == Mcp.CONNECTED) "no_direct" else f?.whyNot
                    if (why != null || facts.isFailure) HText(stringResource(whyNoTools(why)), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 8.dp))
                    else if (names.isEmpty()) HText(stringResource(R.string.mcp_no_tools), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun toolMarks(readOnly: Boolean?, destructive: Boolean?): String? = when {
    destructive == true -> stringResource(R.string.mcp_a_destructive)
    readOnly == true -> stringResource(R.string.mcp_a_read_only)
    else -> null
}

@Composable
private fun ToolRow(name: String, description: String?, mark: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            HText(name, style = Type.monoSmall, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            description?.let {
                Spacer(Modifier.height(2.dp))
                HText(it, style = Type.small, color = HN.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        mark?.let {
            Spacer(Modifier.width(10.dp))
            HText(it, style = Type.small, color = HN.inkDim, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
    }
}

@Composable
private fun TextCard(words: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        HText(words, style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun ThingsCard(things: List<McpThing>) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            things.forEachIndexed { i, t ->
                if (i > 0) Hairline(Modifier.fillMaxWidth())
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp)) {
                    HText(listOf(t.name, t.args.joinToString(", ").takeIf { it.isNotEmpty() }?.let { "($it)" }.orEmpty()).joinToString(" ").trim(), style = Type.monoSmall, color = HN.ink)
                    t.description?.let { HText(it, style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(top = 2.dp)) }
                    t.uri?.let { HText(it, style = Type.monoSmall, color = HN.inkDim, modifier = Modifier.padding(top = 2.dp)) }
                }
            }
        }
    }
}

/** What each hint says, for a flag the server gave. Its own claim: nothing checks it. */
@Composable
private fun AnnotationLines(readOnly: Boolean?, destructive: Boolean?, idempotent: Boolean?, openWorld: Boolean?) {
    val lines = listOfNotNull(
        readOnly?.let { stringResource(if (it) R.string.mcp_h_read_only else R.string.mcp_h_writes) },
        destructive?.let { stringResource(if (it) R.string.mcp_h_destructive else R.string.mcp_h_not_destructive) },
        idempotent?.let { stringResource(if (it) R.string.mcp_h_idempotent else R.string.mcp_h_not_idempotent) },
        openWorld?.let { stringResource(if (it) R.string.mcp_h_open else R.string.mcp_h_closed) },
    )
    if (lines.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    HText(stringResource(R.string.mcp_tool_says).uppercase(), style = Type.label, color = HN.inkDim)
    Spacer(Modifier.height(4.dp))
    lines.forEach { HText(it, style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(vertical = 1.dp)) }
}

/** A tool: what it's for, what it says of itself, its parameters. A tool can't be run from here. */
@Composable
private fun ToolSheet(t: McpTool, onDismiss: () -> Unit) {
    var schema by remember { mutableStateOf(false) }
    GlassSheet(t.name, onDismiss) {
        HText(t.name, style = Type.title, color = HN.ink)
        t.title?.takeIf { it != t.name }?.let { HText(it, style = Type.small, color = HN.inkMuted) }
        t.description?.let {
            Spacer(Modifier.height(8.dp))
            HText(it, style = Type.body, color = HN.inkMuted)
        }
        AnnotationLines(t.readOnly, t.destructive, t.idempotent, t.openWorld)
        Spacer(Modifier.height(12.dp))
        HText(stringResource(R.string.mcp_params).uppercase(), style = Type.label, color = HN.inkDim)
        if (t.params.isEmpty()) HText(stringResource(R.string.mcp_params_none), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 4.dp))
        t.params.forEachIndexed { i, p ->
            if (i > 0) Hairline(Modifier.fillMaxWidth())
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HText(p.name, style = Type.monoSmall, color = HN.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(10.dp))
                    HText(listOf(p.type, if (p.required) stringResource(R.string.mcp_param_required) else stringResource(R.string.mcp_param_optional)).joinToString(" · "), style = Type.small, color = if (p.required) HN.ink else HN.inkDim, maxLines = 1)
                }
                p.description?.let { HText(it, style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(top = 2.dp)) }
                p.default?.let { HText(stringResource(R.string.mcp_param_default, it), style = Type.monoSmall, color = HN.inkDim, modifier = Modifier.padding(top = 2.dp)) }
                if (p.choices.isNotEmpty()) HText(p.choices.joinToString(" · "), style = Type.monoSmall, color = HN.inkDim, modifier = Modifier.padding(top = 2.dp))
            }
        }
        t.schema?.let { text ->
            Spacer(Modifier.height(8.dp))
            Pill(stringResource(if (schema) R.string.mcp_schema_hide else R.string.mcp_schema_show), { schema = !schema }, kind = PillKind.Ghost, height = 40.dp)
            if (schema) HText(text, style = Type.monoSmall, color = HN.inkMuted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** All that's known of a tool the server itself couldn't be asked about. */
@Composable
private fun LiveToolSheet(t: McpLiveTool, onDismiss: () -> Unit) {
    GlassSheet(t.name, onDismiss) {
        HText(t.name, style = Type.title, color = HN.ink)
        AnnotationLines(t.readOnly.takeIf { it }, t.destructive.takeIf { it }, null, t.openWorld.takeIf { it })
        Spacer(Modifier.height(8.dp))
        HText(stringResource(R.string.mcp_why_names), style = Type.small, color = HN.inkDim)
    }
}

/** The command as it would run, for a confirm: what looks secret is already masked by the server. */
private fun McpServer.commandLine(): String? = setup.command?.let { c -> (listOf(c) + setup.args).joinToString(" ") } ?: setup.url

/**
 * Before anything that runs a program on the server: the exact command on a confirm, then the owner's
 * fingerprint or screen lock (docs/DECISIONS.md, "Managing MCP servers from the phone").
 */
internal fun mcpRunCheck(context: Context, confirm: (Confirm) -> Unit, toast: (String) -> Unit, server: String, name: String, command: String?, go: () -> Unit) {
    confirm(
        Confirm(
            title = context.getString(R.string.mcp_run_title, server),
            body = context.getString(R.string.mcp_run_body, name, command ?: context.getString(R.string.mcp_run_unknown)),
            action = context.getString(R.string.mcp_run_action),
            danger = true,
        ) {
            OwnerCheck.ask(context, context.getString(R.string.mcp_run_title, server), name, context.getString(R.string.check_no_lock), onOk = go, onFail = { msg -> if (msg != null) toast(msg) })
        },
    )
}

@Composable
private fun ActionsCard(
    s: McpServer,
    mine: McpLive?,
    busy: Boolean,
    trouble: String?,
    onToggle: (Boolean) -> Unit,
    onReconnect: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onResetChoices: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val status = mine?.status
    val waiting = status == Mcp.WAITING || status == Mcp.REJECTED || s.approval == "pending" || s.approval == "rejected"
    val hasAddress = s.setup.transport == Mcp.HTTP || s.setup.transport == Mcp.SSE
    val codex = s.agent == AgentSettingsReply.CODEX
    val canSignIn = hasAddress && if (codex) s.authStatus == Mcp.CODEX_OAUTH || s.authStatus == Mcp.CODEX_SIGNED_OUT || status == Mcp.NEEDS_AUTH else s.setup.headerNames.none { it.equals("Authorization", ignoreCase = true) }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.scope == Mcp.PROJECT && waiting) {
            Pill(stringResource(R.string.mcp_approve), onApprove, kind = PillKind.Primary, height = 40.dp, enabled = !busy)
            if (s.approval != "rejected") Pill(stringResource(R.string.mcp_reject), onReject, kind = PillKind.Outline, height = 40.dp, enabled = !busy)
        }
        if (!waiting && status != null) {
            if (status == Mcp.DISABLED) Pill(stringResource(R.string.mcp_enable), { onToggle(true) }, kind = PillKind.Primary, height = 40.dp, enabled = !busy)
            else Pill(stringResource(R.string.mcp_disable), { onToggle(false) }, kind = PillKind.Outline, height = 40.dp, enabled = !busy)
            if (status != Mcp.DISABLED) Pill(stringResource(R.string.mcp_reconnect), onReconnect, kind = PillKind.Outline, height = 40.dp, enabled = !busy)
        }
        if (canSignIn) {
            Pill(stringResource(if (status == Mcp.NEEDS_AUTH) R.string.mcp_sign_in else R.string.mcp_sign_in_again), onSignIn, kind = if (status == Mcp.NEEDS_AUTH) PillKind.Primary else PillKind.Outline, height = 40.dp, enabled = !busy)
            if (status != Mcp.NEEDS_AUTH) Pill(stringResource(R.string.mcp_sign_out), onSignOut, kind = PillKind.Ghost, height = 40.dp, enabled = !busy)
        }
        if (s.scope == Mcp.PROJECT) Pill(stringResource(R.string.mcp_reset), onResetChoices, kind = PillKind.Ghost, height = 40.dp, enabled = !busy)
        if (s.editable) {
            Pill(stringResource(R.string.mcp_edit), onEdit, kind = PillKind.Outline, height = 40.dp, enabled = !busy, leading = Glyph.Pencil)
            Pill(stringResource(R.string.mcp_remove), onRemove, kind = PillKind.Danger, height = 40.dp, enabled = !busy)
        }
    }
    Column(Modifier.padding(horizontal = 4.dp)) {
        if (busy) LoadingLine(stringResource(R.string.as_saving), Modifier.padding(top = 8.dp))
        trouble?.let { HText(it, style = Type.small, color = HN.amber, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(6.dp))
        HText(stringResource(if (codex) R.string.mcp_actions_note_codex else R.string.mcp_actions_note), style = Type.small, color = HN.inkDim)
    }
}

/**
 * A sign-in from the phone: the page opens in the browser, which at the end goes to an address on localhost that
 * a phone can't open. That address, pasted here, finishes it on the server.
 */
@Composable
private fun LoginSheet(l: McpLogin, onCancel: () -> Unit, onFinish: (String, (String?) -> Unit) -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    GlassSheet(stringResource(R.string.mcp_sign_in), onCancel) {
        HText(stringResource(R.string.mcp_sign_in), style = Type.title, color = HN.ink)
        Spacer(Modifier.height(6.dp))
        HText(stringResource(R.string.mcp_login_body), style = Type.small, color = HN.inkMuted)
        Spacer(Modifier.height(10.dp))
        Pill(stringResource(R.string.mcp_login_open), {
            // The browser itself: it holds any sign-in the owner already has there.
            l.url?.let { u -> runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) }.onFailure { toast(context.getString(R.string.about_github_no_browser)) } }
        }, kind = PillKind.Primary, height = 44.dp)
        Spacer(Modifier.height(12.dp))
        Field(address, { address = it }, placeholder = "http://localhost:…", mono = true, singleLine = false, label = stringResource(R.string.mcp_login_address))
        trouble?.let { HText(it, style = Type.small, color = HN.amber, modifier = Modifier.padding(top = 6.dp)) }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.mcp_login_finish), {
                busy = true
                trouble = null
                onFinish(address) { err -> trouble = err; busy = false }
            }, kind = PillKind.Outline, height = 44.dp, enabled = address.isNotBlank() && !busy)
            Pill(stringResource(R.string.cancel), onCancel, kind = PillKind.Ghost, height = 44.dp)
        }
    }
}

private class PairRow(val name: String, val value: String, val kept: Boolean)

/** Env vars or headers on a form: a value goes in and never comes back. One already on the server shows that it stays, until it's replaced. */
@Composable
private fun PairsEditor(title: String, rows: List<PairRow>, onChange: (List<PairRow>) -> Unit) {
    FieldLabel(title, Modifier.padding(top = 10.dp))
    rows.forEachIndexed { i, r ->
        val set = { x: PairRow -> onChange(rows.toMutableList().also { it[i] = x }) }
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (r.kept) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HText(r.name, style = Type.monoSmall, color = HN.ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    HText(stringResource(R.string.mcp_value_kept), style = Type.small, color = HN.inkDim, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Pill(stringResource(R.string.mcp_value_replace), { set(PairRow(r.name, "", false)) }, kind = PillKind.Ghost, height = 36.dp)
                    Pill(stringResource(R.string.mcp_value_remove), { onChange(rows.filterIndexed { x, _ -> x != i }) }, kind = PillKind.Ghost, height = 36.dp)
                }
            } else {
                Field(r.name, { set(PairRow(it, r.value, false)) }, placeholder = stringResource(R.string.mcp_pair_name), mono = true, label = stringResource(R.string.mcp_pair_name))
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(r.value, { set(PairRow(r.name, it, false)) }, modifier = Modifier.weight(1f), placeholder = stringResource(R.string.mcp_pair_value), secret = true, label = stringResource(R.string.mcp_pair_value))
                    Spacer(Modifier.width(8.dp))
                    Pill(stringResource(R.string.mcp_value_remove), { onChange(rows.filterIndexed { x, _ -> x != i }) }, kind = PillKind.Ghost, height = 36.dp)
                }
            }
        }
    }
    Pill(stringResource(R.string.mcp_pair_add), { onChange(rows + PairRow("", "", false)) }, kind = PillKind.Ghost, height = 36.dp, modifier = Modifier.padding(top = 6.dp), leading = Glyph.Plus)
}

@Composable
private fun ChoiceRow(options: List<Pair<String, String>>, chosen: String, enabled: Boolean, onChoose: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (value, word) ->
            Pill(word, { onChoose(value) }, kind = if (value == chosen) PillKind.Primary else PillKind.Ghost, height = 36.dp, enabled = enabled)
        }
    }
}

/** Add a server, or change one: the form, then the confirm and the fingerprint where it runs something. Values are checked again on the server. */
@Composable
fun McpEditScreen(hub: Hub, agent: String, name: String?, scope: String?, folder: String?, onBack: () -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val coroutines = rememberCoroutineScope()
    val server = hub.mac.name
    val editing = name != null
    val codex = agent == AgentSettingsReply.CODEX
    var loaded by remember { mutableStateOf<Result<McpServer>?>(null) }
    var folders by remember { mutableStateOf<List<McpFolder>>(emptyList()) }
    var formName by remember { mutableStateOf(name.orEmpty()) }
    var where by remember { mutableStateOf(scope ?: Mcp.USER) }
    var inFolder by remember { mutableStateOf(folder) }
    var transport by remember { mutableStateOf(Mcp.STDIO) }
    var command by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var env by remember { mutableStateOf(emptyList<PairRow>()) }
    var headers by remember { mutableStateOf(emptyList<PairRow>()) }
    var clientId by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var bearerEnv by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        hub.mcpList(agent).onSuccess { folders = it.folders }
        if (name != null) {
            val r = hub.mcpServer(agent, name, scope ?: Mcp.USER, folder)
            r.onSuccess { s ->
                transport = s.setup.transport ?: Mcp.STDIO
                command = s.setup.command.orEmpty()
                argsText = s.setup.args.joinToString("\n")
                url = s.setup.url.orEmpty()
                env = s.setup.envNames.map { PairRow(it, "", true) }
                headers = s.setup.headerNames.map { PairRow(it, "", true) }
                clientId = s.setup.clientId.orEmpty()
                port = s.setup.callbackPort?.toString().orEmpty()
                bearerEnv = s.setup.bearerEnv.orEmpty()
            }
            loaded = r
        }
    }
    ScreenScaffold(sourceKey = "mcp-edit:$agent:$scope:$folder:$name", title = stringResource(if (editing) R.string.mcp_edit_title else R.string.mcp_add_title), subtitle = server, onBack = onBack) { top, source ->
        Column(
            Modifier.fillMaxSize().then(source).verticalScroll(rememberScrollState()).padding(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
        ) {
            val failure = loaded?.exceptionOrNull()
            if (failure != null) {
                McpFailure(failure, R.string.mcp_err) { onBack() }
                return@Column
            }
            FieldLabel(stringResource(R.string.mcp_f_name))
            if (editing) HText(formName, style = Type.mono, color = HN.ink, modifier = Modifier.padding(vertical = 6.dp))
            else Field(formName, { formName = it }, placeholder = "my-server", mono = true, label = stringResource(R.string.mcp_f_name))
            if (!codex && !editing) {
                FieldLabel(stringResource(R.string.mcp_f_where), Modifier.padding(top = 10.dp))
                ChoiceRow(listOf(Mcp.USER to stringResource(R.string.mcp_f_global), Mcp.PROJECT to stringResource(R.string.mcp_f_project), Mcp.LOCAL to stringResource(R.string.mcp_f_local)), where, true) { where = it }
                HText(stringResource(when (where) { Mcp.USER -> R.string.mcp_f_global_body; Mcp.PROJECT -> R.string.mcp_f_project_body; else -> R.string.mcp_f_local_body }), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 4.dp))
                if (where != Mcp.USER) {
                    FieldLabel(stringResource(R.string.mcp_f_folder), Modifier.padding(top = 10.dp))
                    val choices = folders.filter { it.id != Mcp.HOME }
                    if (choices.isEmpty()) HText(stringResource(R.string.mcp_f_no_folders), style = Type.small, color = HN.amber)
                    ChoiceRow(choices.map { it.id to it.name }, inFolder.orEmpty(), true) { inFolder = it }
                }
            }
            FieldLabel(stringResource(R.string.mcp_f_type), Modifier.padding(top = 10.dp))
            ChoiceRow(if (codex) listOf(Mcp.STDIO to "stdio", Mcp.HTTP to "HTTP") else listOf(Mcp.STDIO to "stdio", Mcp.HTTP to "HTTP", Mcp.SSE to "SSE"), transport, true) { transport = it }
            if (transport == Mcp.STDIO) {
                FieldLabel(stringResource(R.string.mcp_command), Modifier.padding(top = 10.dp))
                Field(command, { command = it }, placeholder = "npx", mono = true, label = stringResource(R.string.mcp_command))
                FieldLabel(stringResource(R.string.mcp_f_args), Modifier.padding(top = 10.dp))
                Field(argsText, { argsText = it }, placeholder = "-y\n@acme/mcp", mono = true, singleLine = false, minLines = 2, label = stringResource(R.string.mcp_args))
                PairsEditor(stringResource(R.string.mcp_env), env) { env = it }
            } else {
                FieldLabel(stringResource(R.string.mcp_url), Modifier.padding(top = 10.dp))
                Field(url, { url = it }, placeholder = "https://mcp.example.com/mcp", mono = true, label = stringResource(R.string.mcp_url))
                PairsEditor(stringResource(R.string.mcp_headers), headers) { headers = it }
                if (codex) {
                    FieldLabel(stringResource(R.string.mcp_bearer), Modifier.padding(top = 10.dp))
                    Field(bearerEnv, { bearerEnv = it }, placeholder = "MY_API_TOKEN", mono = true, label = stringResource(R.string.mcp_bearer))
                    HText(stringResource(R.string.mcp_f_codex_note), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 4.dp))
                } else {
                    FieldLabel(stringResource(R.string.mcp_f_oauth), Modifier.padding(top = 10.dp))
                    Field(clientId, { clientId = it }, placeholder = stringResource(R.string.mcp_oauth_client), mono = true, label = stringResource(R.string.mcp_oauth_client))
                    Spacer(Modifier.height(4.dp))
                    Field(port, { port = it }, placeholder = stringResource(R.string.mcp_oauth_port), mono = true, label = stringResource(R.string.mcp_oauth_port))
                    Spacer(Modifier.height(4.dp))
                    Field(clientSecret, { clientSecret = it }, placeholder = stringResource(R.string.mcp_f_client_secret), secret = true, label = stringResource(R.string.mcp_f_client_secret))
                }
            }
            HText(stringResource(R.string.mcp_f_secrets_note, server), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 10.dp))
            trouble?.let { HText(it, style = Type.small, color = HN.amber, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(14.dp))
            val before = loaded?.getOrNull()
            val args = argsText.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val form = { check: Boolean ->
                McpForm(
                    agent = agent,
                    scope = where,
                    folder = if (where == Mcp.USER) null else inFolder,
                    name = formName.trim(),
                    transport = transport,
                    command = command.trim(),
                    args = args,
                    url = url.trim(),
                    env = env.map { McpPair(it.name.trim(), if (it.kept) null else it.value) },
                    headers = headers.map { McpPair(it.name.trim(), if (it.kept) null else it.value) },
                    clientId = clientId.trim().ifEmpty { null },
                    callbackPort = port.trim().toIntOrNull(),
                    clientSecret = clientSecret.ifEmpty { null },
                    confirmRun = check,
                    expected = before?.hash,
                    bearerEnv = bearerEnv.trim().ifEmpty { null },
                )
            }
            val runs = transport == Mcp.STDIO && (before == null || before.setup.transport != Mcp.STDIO || before.setup.command != command.trim() || before.setup.args != args || env.any { !it.kept } || env.map { it.name } != before.setup.envNames)
            val ready = formName.isNotBlank() && (if (transport == Mcp.STDIO) command.isNotBlank() else url.isNotBlank()) && (editing || where == Mcp.USER || inFolder != null) && !saving
            Pill(stringResource(R.string.mcp_f_save), {
                val send = { check: Boolean ->
                    saving = true
                    trouble = null
                    coroutines.launch {
                        val f = form(check)
                        (if (editing) hub.mcpEdit(f) else hub.mcpAdd(f))
                            .onSuccess { toast(context.getString(R.string.as_saved, server)); onBack() }
                            .onFailure { e -> trouble = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err) }
                        saving = false
                    }
                    Unit
                }
                if (runs) mcpRunCheck(context, confirm, toast, server, formName.trim(), (listOf(command.trim()) + args).joinToString(" ")) { send(true) } else send(false)
            }, kind = PillKind.Primary, height = 48.dp, enabled = ready)
        }
    }
}
