@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.prudnikweb.hypernova.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.AvailablePlugin
import com.prudnikweb.hypernova.data.AvailableReply
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Marketplace
import com.prudnikweb.hypernova.data.Mcp
import com.prudnikweb.hypernova.data.PluginChange
import com.prudnikweb.hypernova.data.PluginDetail
import com.prudnikweb.hypernova.data.PluginRow
import com.prudnikweb.hypernova.data.PluginsReply
import com.prudnikweb.hypernova.data.marketplaceAdd
import com.prudnikweb.hypernova.data.marketplaceRemove
import com.prudnikweb.hypernova.data.marketplaceUpdate
import com.prudnikweb.hypernova.data.pluginConfigure
import com.prudnikweb.hypernova.data.pluginDetail
import com.prudnikweb.hypernova.data.pluginInstall
import com.prudnikweb.hypernova.data.pluginToggle
import com.prudnikweb.hypernova.data.pluginUninstall
import com.prudnikweb.hypernova.data.pluginUpdate
import com.prudnikweb.hypernova.data.plugins
import com.prudnikweb.hypernova.data.pluginsAvailable
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.FieldLabel
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

private val seam = Modifier.padding(horizontal = 4.dp).padding(top = 8.dp, bottom = 2.dp)

/** Installing a plugin, or adding a marketplace, is someone else's code on the server: the whole source on a confirm, then the fingerprint. */
internal fun codeCheck(context: Context, confirm: (Confirm) -> Unit, toast: (String) -> Unit, server: String, what: String, body: String, go: () -> Unit) {
    confirm(
        Confirm(
            title = context.getString(R.string.pl_run_title, server),
            body = body,
            action = context.getString(R.string.pl_run_action),
            danger = true,
        ) {
            OwnerCheck.ask(context, context.getString(R.string.pl_run_title, server), what, context.getString(R.string.check_no_lock), onOk = go, onFail = { msg -> if (msg != null) toast(msg) })
        },
    )
}

/** Agents & providers › an agent's Settings › Plugins: what's installed and from where. */
@Composable
fun PluginsScreen(hub: Hub, agent: String, onBack: () -> Unit, onOpen: (id: String, scope: String, folder: String?, enabled: Boolean) -> Unit, onBrowse: (folder: String?) -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val server = hub.mac.name
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    val codex = agent == AgentSettingsReply.CODEX
    var attempt by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<Result<PluginsReply>?>(null) }
    var folder by remember { mutableStateOf(Mcp.HOME) }
    var picking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var source by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt, folder, hub.agentSettingsVersion) {
        if (reply?.isSuccess != true) reply = null
        reply = hub.plugins(agent, if (codex) null else folder)
    }
    fun act(work: suspend () -> Result<PluginChange>) {
        busy = true
        trouble = null
        scope.launch {
            work().onSuccess { c -> toast(c.message ?: context.getString(R.string.pl_done)); attempt++ }.onFailure { e -> trouble = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err) }
            busy = false
        }
    }
    ScreenScaffold(sourceKey = "plugins:$agent", title = stringResource(if (codex) R.string.pl_title_codex else R.string.pl_title_claude), subtitle = server, onBack = onBack) { top, sourceMod ->
        LazyColumn(
            Modifier.fillMaxSize().then(sourceMod),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val r = reply
            when {
                r == null -> item(key = "loading") { LoadingLine(stringResource(R.string.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                r.isFailure -> item(key = "failure") { McpFailure(r.exceptionOrNull(), R.string.pl_err) { attempt++ } }
                else -> {
                    val data = r.getOrThrow()
                    if (!codex) item(key = "folder") { FolderCard(data.folders.firstOrNull { it.id == folder }?.let { mcpFolderName(it) } ?: stringResource(R.string.mcp_home)) { picking = true } }
                    item(key = "actions") {
                        FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill(stringResource(R.string.pl_browse), { onBrowse(if (codex) null else folder) }, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Plus)
                            Pill(stringResource(R.string.pl_market_add), { adding = true }, kind = PillKind.Ghost, height = 40.dp)
                        }
                    }
                    trouble?.let { t -> item(key = "trouble") { HText(t, style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp)) } }
                    if (busy) item(key = "busy") { LoadingLine(stringResource(R.string.as_saving), Modifier.padding(horizontal = 4.dp)) }
                    item(key = "installed-seam") { Seam(stringResource(R.string.pl_seam_installed), modifier = seam) }
                    item(key = "installed") {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                                if (data.plugins.isEmpty()) HText(stringResource(R.string.pl_none), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 10.dp))
                                data.plugins.forEachIndexed { i, p ->
                                    if (i > 0) Hairline(Modifier.fillMaxWidth())
                                    PluginRowView(p) { onOpen(p.id, p.scope, if (codex) null else folder, p.enabled) }
                                }
                            }
                        }
                    }
                    item(key = "markets-seam") { Seam(stringResource(R.string.pl_seam_markets), modifier = seam) }
                    item(key = "markets") {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                                if (data.marketplaces.isEmpty()) HText(stringResource(R.string.pl_no_markets), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 10.dp))
                                data.marketplaces.forEachIndexed { i, m ->
                                    if (i > 0) Hairline(Modifier.fillMaxWidth())
                                    MarketRow(
                                        m,
                                        onUpdate = { act { hub.marketplaceUpdate(agent, m.name, if (codex) null else folder) } },
                                        onRemove = {
                                            confirm(Confirm(context.getString(R.string.pl_market_remove_title, m.name), context.getString(R.string.pl_market_remove_body), context.getString(R.string.pl_market_remove), danger = true) {
                                                act { hub.marketplaceRemove(agent, m.name, if (codex) null else folder) }
                                            })
                                        },
                                    )
                                }
                                if (data.marketplaces.size > 1) Pill(stringResource(R.string.pl_market_update_all), { act { hub.marketplaceUpdate(agent, null, if (codex) null else folder) } }, kind = PillKind.Ghost, height = 36.dp, modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }
                    }
                    item(key = "note") { HText(stringResource(R.string.pl_note), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp)) }
                }
            }
        }
    }
    if (picking) FolderSheet(reply?.getOrNull()?.folders.orEmpty(), folder, { picking = false }) { folder = it; picking = false }
    if (adding) {
        GlassSheet(stringResource(R.string.pl_market_add), { adding = false }) {
            HText(stringResource(R.string.pl_market_add), style = Type.title, color = HN.ink)
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.pl_market_add_body), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(10.dp))
            Field(source, { source = it }, placeholder = "owner/repo", mono = true, label = stringResource(R.string.pl_market_source))
            Spacer(Modifier.height(10.dp))
            Pill(stringResource(R.string.pl_market_add), {
                val what = source.trim()
                adding = false
                codeCheck(context, confirm, toast, server, what, context.getString(R.string.pl_run_market, what)) {
                    source = ""
                    act { hub.marketplaceAdd(agent, what, Mcp.USER, null) }
                }
            }, kind = PillKind.Primary, height = 44.dp, enabled = source.isNotBlank())
        }
    }
}

@Composable
internal fun FolderCard(name: String, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            HText(stringResource(R.string.mcp_folder), style = Type.body, color = HN.ink, modifier = Modifier.weight(1f))
            HText(name, style = Type.monoSmall, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
        }
    }
}

@Composable
internal fun FolderSheet(folders: List<com.prudnikweb.hypernova.data.McpFolder>, chosen: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    GlassSheet(stringResource(R.string.mcp_folder_pick), onDismiss) {
        HText(stringResource(R.string.mcp_folder_pick), style = Type.title, color = HN.ink)
        Spacer(Modifier.height(8.dp))
        folders.forEach { f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { onPick(f.id) }
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HText(mcpFolderName(f), style = Type.body, color = if (f.id == chosen) HN.ink else HN.inkMuted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (f.id == chosen) Icon(Glyph.Check, size = 14.dp, color = HN.accent)
            }
        }
    }
}

@Composable
private fun PluginRowView(p: PluginRow, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            HText(p.name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            HText(listOfNotNull(stringResource(mcpScopeWord(p.scope)), p.marketplace, p.version).joinToString(" · "), style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            p.problem?.let { HText(it, style = Type.small, color = HN.amber) }
        }
        Spacer(Modifier.width(12.dp))
        HText(stringResource(if (p.enabled) R.string.pl_on else R.string.pl_off), style = Type.monoSmall, color = if (p.enabled) HN.accent else HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
    }
}

@Composable
private fun MarketRow(m: Marketplace, onUpdate: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HText(m.name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        m.where?.let { HText(it, style = Type.monoSmall, color = HN.inkMuted) }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.pl_market_update), onUpdate, kind = PillKind.Ghost, height = 36.dp)
            Pill(stringResource(R.string.pl_market_remove), onRemove, kind = PillKind.Ghost, height = 36.dp)
        }
    }
}

/** A plugin: what it brings, its options, and what can be done with it. */
@Composable
fun PluginScreen(hub: Hub, agent: String, id: String, scopeWord: String, folder: String?, enabled: Boolean, onBack: () -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val server = hub.mac.name
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    val codex = agent == AgentSettingsReply.CODEX
    var attempt by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<Result<PluginDetail>?>(null) }
    var on by remember { mutableStateOf(enabled) }
    var busy by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    val typed = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(attempt) {
        if (reply?.isSuccess != true) reply = null
        reply = hub.pluginDetail(agent, id, folder)
    }
    fun act(then: () -> Unit = {}, work: suspend () -> Result<PluginChange>) {
        busy = true
        trouble = null
        scope.launch {
            work().onSuccess { c ->
                if (c.acceptCommand != null) {
                    codeCheck(context, confirm, toast, server, id, context.getString(R.string.pl_run_command, c.command.orEmpty())) {
                        act(then) { hub.pluginUpdate(agent, id, scopeWord, folder, c.acceptCommand) }
                    }
                } else {
                    toast(c.message ?: context.getString(R.string.pl_done))
                    attempt++
                    then()
                }
            }.onFailure { e -> trouble = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err) }
            busy = false
        }
    }
    ScreenScaffold(sourceKey = "plugin:$agent:$scopeWord:$folder:$id", title = id.substringBefore('@'), subtitle = server, onBack = onBack) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "where") {
                HText(listOfNotNull(id.substringAfter('@', "").ifEmpty { null }, stringResource(mcpScopeWord(scopeWord))).joinToString(" · "), style = Type.monoSmall, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
            }
            item(key = "actions") {
                FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (on) Pill(stringResource(R.string.pl_disable), { act({ on = false }) { hub.pluginToggle(agent, id, scopeWord, folder, false) } }, kind = PillKind.Outline, height = 40.dp, enabled = !busy)
                    else Pill(stringResource(R.string.pl_enable), { act({ on = true }) { hub.pluginToggle(agent, id, scopeWord, folder, true) } }, kind = PillKind.Primary, height = 40.dp, enabled = !busy)
                    if (!codex) Pill(stringResource(R.string.pl_update), { act { hub.pluginUpdate(agent, id, scopeWord, folder) } }, kind = PillKind.Outline, height = 40.dp, enabled = !busy)
                    Pill(stringResource(R.string.pl_uninstall), {
                        confirm(Confirm(context.getString(R.string.pl_uninstall_title, id.substringBefore('@')), context.getString(R.string.pl_uninstall_body), context.getString(R.string.pl_uninstall), danger = true) {
                            act(onBack) { hub.pluginUninstall(agent, id, scopeWord, folder) }
                        })
                    }, kind = PillKind.Danger, height = 40.dp, enabled = !busy)
                }
            }
            if (busy) item(key = "busy") { LoadingLine(stringResource(R.string.as_saving), Modifier.padding(horizontal = 4.dp)) }
            trouble?.let { t -> item(key = "trouble") { HText(t, style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp)) } }
            val r = reply
            when {
                r == null -> item(key = "loading") { LoadingLine(stringResource(R.string.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                r.isFailure -> item(key = "failure") { McpFailure(r.exceptionOrNull(), R.string.pl_err) { attempt++ } }
                else -> {
                    val d = r.getOrThrow()
                    d.description?.let { words -> item(key = "description") { HText(words, style = Type.body, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp)) } }
                    item(key = "brings-seam") { Seam(stringResource(R.string.pl_seam_brings), modifier = seam) }
                    item(key = "brings") { BringsCard(d) }
                    if (d.options.isNotEmpty()) {
                        item(key = "options-seam") { Seam(stringResource(R.string.pl_seam_options), modifier = seam) }
                        item(key = "options") {
                            GlassCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    d.options.forEachIndexed { i, o ->
                                        if (i > 0) Hairline(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                                        HText(o.title ?: o.key, style = Type.body, color = HN.ink)
                                        o.description?.let { HText(it, style = Type.small, color = HN.inkMuted) }
                                        HText(if (o.sensitive && o.set) stringResource(R.string.pl_option_set) else o.value ?: if (o.set) stringResource(R.string.pl_option_set) else stringResource(R.string.pl_option_unset), style = Type.monoSmall, color = HN.inkDim, modifier = Modifier.padding(top = 2.dp))
                                        Spacer(Modifier.height(4.dp))
                                        Field(typed[o.key].orEmpty(), { typed[o.key] = it }, placeholder = stringResource(R.string.pl_option_new), secret = o.sensitive, label = o.key)
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    val values = typed.filterValues { it.isNotEmpty() }.toMap()
                                    Pill(stringResource(R.string.pl_options_save), { act({ typed.clear() }) { hub.pluginConfigure(agent, id, folder, values) } }, kind = PillKind.Outline, height = 40.dp, enabled = values.isNotEmpty() && !busy)
                                }
                            }
                        }
                    }
                    item(key = "note") { HText(stringResource(R.string.pl_note), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun BringsCard(d: PluginDetail) {
    val groups = listOf(
        R.string.pl_skills to d.skills,
        R.string.pl_agents to d.agents,
        R.string.pl_hooks to d.hooks,
        R.string.as_mcp to d.mcpServers,
        R.string.pl_commands to d.commands,
        R.string.pl_lsp to d.lspServers,
        R.string.pl_apps to d.apps,
    ).filter { it.second.isNotEmpty() }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (groups.isEmpty()) HText(stringResource(R.string.pl_brings_nothing), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 6.dp))
            groups.forEachIndexed { i, (label, names) ->
                if (i > 0) Hairline(Modifier.fillMaxWidth())
                Column(Modifier.padding(vertical = 8.dp)) {
                    HText(stringResource(label), style = Type.small, color = HN.inkDim)
                    Spacer(Modifier.height(2.dp))
                    HText(names.joinToString("\n"), style = Type.monoSmall, color = HN.ink)
                }
            }
            d.alwaysOnTokens?.let {
                Hairline(Modifier.fillMaxWidth())
                HText(stringResource(R.string.pl_tokens, it), style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

/** What can be installed, to search, and a tap to install with its source on the confirm. */
@Composable
fun PluginBrowseScreen(hub: Hub, agent: String, folder: String?, onBack: () -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val server = hub.mac.name
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    val codex = agent == AgentSettingsReply.CODEX
    var attempt by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(Mcp.USER) }
    var reply by remember { mutableStateOf<Result<AvailableReply>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt, query) {
        kotlinx.coroutines.delay(if (query.isEmpty()) 0 else 300)
        reply = hub.pluginsAvailable(agent, folder, query)
    }
    fun install(p: AvailablePlugin, accept: String? = null) {
        busy = true
        trouble = null
        scope.launch {
            hub.pluginInstall(agent, p.id, target, if (target == Mcp.USER) null else folder, accept)
                .onSuccess { c ->
                    if (c.acceptCommand != null) codeCheck(context, confirm, toast, server, p.id, context.getString(R.string.pl_run_command, c.command.orEmpty())) { install(p, c.acceptCommand) }
                    else {
                        toast(c.message ?: context.getString(R.string.pl_installed, server))
                        onBack()
                    }
                }
                .onFailure { e -> trouble = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err) }
            busy = false
        }
    }
    ScreenScaffold(sourceKey = "plugin-browse:$agent:$folder", title = stringResource(R.string.pl_browse_title), subtitle = server, onBack = onBack) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "search") { Field(query, { query = it }, placeholder = stringResource(R.string.pl_search), label = stringResource(R.string.pl_search)) }
            if (!codex) item(key = "target") {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    FieldLabel(stringResource(R.string.pl_install_for))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Mcp.USER to R.string.mcp_f_global, Mcp.PROJECT to R.string.mcp_f_project, Mcp.LOCAL to R.string.mcp_f_local).forEach { (v, word) ->
                            // Project and local go in the folder this screen was opened from.
                            Pill(stringResource(word), { target = v }, kind = if (v == target) PillKind.Primary else PillKind.Ghost, height = 36.dp, enabled = v == Mcp.USER || (folder != null && folder != Mcp.HOME))
                        }
                    }
                }
            }
            trouble?.let { t -> item(key = "trouble") { HText(t, style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp)) } }
            if (busy) item(key = "busy") { LoadingLine(stringResource(R.string.as_saving), Modifier.padding(horizontal = 4.dp)) }
            val r = reply
            when {
                r == null -> item(key = "loading") { LoadingLine(stringResource(R.string.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                r.isFailure -> item(key = "failure") { McpFailure(r.exceptionOrNull(), R.string.pl_err) { attempt++ } }
                else -> {
                    val data = r.getOrThrow()
                    if (data.plugins.isEmpty()) item(key = "none") { HText(stringResource(R.string.pl_no_found), style = Type.body, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp)) }
                    data.plugins.forEach { p ->
                        item(key = "plugin-${p.id}") {
                            GlassCard(Modifier.fillMaxWidth(), onClick = {
                                if (busy) return@GlassCard
                                val from = listOfNotNull(p.marketplace, p.marketplaceSource, p.source).joinToString(" · ").ifBlank { context.getString(R.string.pl_run_unknown) }
                                codeCheck(context, confirm, toast, server, p.id, context.getString(R.string.pl_run_install, p.id, from)) { install(p) }
                            }) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    HText(p.id.substringBefore('@'), style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    HText(listOfNotNull(p.marketplace, p.version).joinToString(" · "), style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    p.description?.let { HText(it, style = Type.small, color = HN.inkDim, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                                }
                            }
                        }
                    }
                    if (data.total > data.plugins.size) item(key = "more") { HText(stringResource(R.string.pl_more, data.total - data.plugins.size), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp)) }
                }
            }
        }
    }
}
