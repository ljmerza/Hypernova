package com.prudnikweb.hypernova.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
fun McpServersScreen(hub: Hub, agent: String, onBack: () -> Unit, onOpen: (name: String, scope: String, folder: String?) -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val server = hub.mac.name
    var attempt by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<Result<McpList>?>(null) }
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
                else -> mcpListItems(r.getOrThrow(), folder, live, { picking = true }, onOpen)
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
    item(key = "folder") {
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
fun McpServerScreen(hub: Hub, agent: String, name: String, scope: String, folder: String?, onBack: () -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
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
                    item(key = "status") { StatusCard(live, mine) }
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
                    if (f?.whyNot != null || facts.isFailure) HText(stringResource(whyNoTools(f?.whyNot)), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 8.dp))
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
