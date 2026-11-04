@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.EnvReply
import com.prudnikweb.hypernova.data.EnvVar
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Mcp
import com.prudnikweb.hypernova.data.agentEnv
import com.prudnikweb.hypernova.data.envFlip
import com.prudnikweb.hypernova.data.envRemove
import com.prudnikweb.hypernova.data.envSet
import com.prudnikweb.hypernova.data.envUndo
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.mcp.mcpScopeWord
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.NavRow
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.screenBottom
import com.prudnikweb.hypernova.ui.components.Toggle
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/** A name that says its value is secret: the field hides what's typed. The server decides what leaves. */
private val SECRET_NAME = Regex("(^|_)(TOKEN|SECRET|PASSWORD|PASSWD|PASS|KEY|APIKEY|AUTH|CREDENTIALS?)(_|$)", RegexOption.IGNORE_CASE)

/**
 * Agents & providers › an agent's Settings › Environment variables: a list to tick and add to (docs/DECISIONS.md,
 * "Environment variables from the phone"). The server keeps the list of what needs a confirm: when it asks, its message
 * is the exact change, shown on a confirm, then the same request says so.
 */
@Composable
fun AgentEnvScreen(hub: Hub, agent: String, onBack: () -> Unit) {
    val bottom = screenBottom()
    val server = hub.mac.name
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    val codex = agent == AgentSettingsReply.CODEX
    var attempt by remember { mutableIntStateOf(0) }
    var folder by remember { mutableStateOf(Mcp.HOME) }
    var reply by remember { mutableStateOf<Result<EnvReply>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EnvVar?>(null) }
    var busy by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    var addName by remember { mutableStateOf("") }
    var addValue by remember { mutableStateOf("") }
    var addScope by remember { mutableStateOf(Mcp.USER) }
    LaunchedEffect(attempt, folder, hub.agentSettingsVersion) {
        if (reply?.isSuccess != true) reply = null
        reply = hub.agentEnv(agent, if (codex) null else folder)
    }
    val atFolder = if (codex) null else folder

    /** Sends [send]; if the server wants the check, the confirm with its words, and [send] again saying so. */
    fun act(done: () -> Unit = {}, send: suspend (confirmRun: Boolean) -> Result<*>) {
        busy = true
        trouble = null
        scope.launch {
            val first = send(false)
            first.onSuccess { done(); attempt++ }
            first.onFailure { e ->
                if (e is AgentSettingRefused && e.code == EnvReply.CONFIRM_RUN) {
                    val what = e.message.orEmpty()
                    confirm(
                        Confirm(context.getString(R.string.env_check_title, server), what, context.getString(R.string.env_check_action), danger = true) {
                            busy = true
                            scope.launch {
                                send(true).onSuccess { done() }.onFailure { x -> trouble = x.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err) }
                                attempt++
                                busy = false
                            }
                        },
                    )
                } else {
                    trouble = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err)
                    attempt++
                }
            }
            busy = false
        }
    }
    ScreenScaffold(sourceKey = "agent-env:$agent", title = stringResource(R.string.env_title), subtitle = server, onBack = onBack) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "when") { HText(stringResource(if (codex) R.string.env_when_codex else R.string.env_when_claude), style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
            val r = reply
            when {
                r == null -> item(key = "loading") { LoadingLine(stringResource(R.string.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                r.isFailure -> item(key = "failure") { McpFailure(r.exceptionOrNull(), R.string.env_err) { attempt++ } }
                else -> {
                    val data = r.getOrThrow()
                    if (!codex) item(key = "folder") { FolderCard(data.folders.firstOrNull { it.id == folder }?.let { mcpFolderName(it) } ?: stringResource(R.string.mcp_home)) { picking = true } }
                    data.undo?.let { u ->
                        item(key = "undo") {
                            GlassCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                                    NavRow(stringResource(R.string.as_undo_row), u.change, onClick = { if (!busy) act({ toast(context.getString(R.string.as_undone, server)) }) { hub.envUndo(agent, it) } })
                                }
                            }
                        }
                    }
                    for (f in data.files) {
                        if (!f.changeable) item(key = "trouble-${f.scope}") { HText(stringResource(R.string.env_file_trouble, f.where), style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp)) }
                    }
                    trouble?.let { t -> item(key = "trouble") { HText(t, style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp)) } }
                    item(key = "list") {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                if (data.vars.isEmpty()) HText(stringResource(R.string.env_none), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 12.dp))
                                data.vars.forEachIndexed { i, v ->
                                    if (i > 0) Hairline(Modifier.fillMaxWidth())
                                    val hash = data.fileOf(v.scope)?.hash
                                    val canChange = data.fileOf(v.scope)?.changeable == true && !busy
                                    EnvRow(
                                        v, codex, canChange,
                                        onFlip = { act { hub.envFlip(agent, v.scope, atFolder, v.name, hash, it) } },
                                        onEdit = { if (!v.refused) { trouble = null; editing = v } },
                                        onDelete = {
                                            val where = data.fileOf(v.scope)?.where.orEmpty()
                                            confirm(Confirm(context.getString(R.string.env_delete_title, v.name), context.getString(R.string.env_delete_body, where), context.getString(R.string.env_delete_action), danger = true) {
                                                act { hub.envRemove(agent, v.scope, atFolder, v.name, hash, it) }
                                            })
                                        },
                                    )
                                }
                                if (data.hidden > 0) {
                                    Hairline(Modifier.fillMaxWidth())
                                    HText(androidx.compose.ui.res.pluralStringResource(R.plurals.as_hidden, data.hidden, data.hidden), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 10.dp))
                                }
                                Hairline(Modifier.fillMaxWidth())
                                Column(Modifier.padding(vertical = 10.dp)) {
                                    Field(addName, { addName = it.trim() }, placeholder = stringResource(R.string.env_add_name), mono = true, label = stringResource(R.string.env_add_name))
                                    Spacer(Modifier.height(6.dp))
                                    Field(addValue, { addValue = it }, placeholder = stringResource(R.string.env_add_value), secret = SECRET_NAME.containsMatchIn(addName), label = stringResource(R.string.env_add_value))
                                    if (!codex) {
                                        Spacer(Modifier.height(6.dp))
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            data.files.filter { it.changeable }.forEach { f ->
                                                Pill(stringResource(mcpScopeWord(f.scope)), { addScope = f.scope }, kind = if (f.scope == addScope) PillKind.Primary else PillKind.Ghost, height = 36.dp)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    HText(stringResource(R.string.env_add_hint), style = Type.small, color = HN.inkDim)
                                    Spacer(Modifier.height(8.dp))
                                    Pill(stringResource(R.string.env_add), {
                                        val name = addName
                                        val value = addValue.ifEmpty { "1" }
                                        val target = if (codex) Mcp.USER else addScope
                                        val hash = data.fileOf(target)?.hash
                                        act({ addName = ""; addValue = "" }) { hub.envSet(agent, target, atFolder, name, value, hash, it) }
                                    }, kind = PillKind.Outline, height = 44.dp, leading = Glyph.Plus, enabled = addName.isNotBlank() && !busy)
                                }
                            }
                        }
                    }
                    if (busy) item(key = "busy") { LoadingLine(stringResource(R.string.as_saving), Modifier.padding(horizontal = 4.dp)) }
                }
            }
        }
    }
    if (picking) FolderSheet(reply?.getOrNull()?.folders.orEmpty(), folder, { picking = false }) { folder = it; picking = false }
    editing?.let { v ->
        val hash = reply?.getOrNull()?.fileOf(v.scope)?.hash
        EditSheet(v, onDismiss = { editing = null }) { value ->
            editing = null
            act { hub.envSet(agent, v.scope, atFolder, v.name, value, hash, it) }
        }
    }
}

@Composable
private fun EnvRow(v: EnvVar, codex: Boolean, canChange: Boolean, onFlip: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val switchWords = stringResource(R.string.env_switch, v.name)
    val deleteWords = stringResource(R.string.env_delete, v.name)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = canChange, role = Role.Button, onClick = onEdit)
                .padding(vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (v.guarded) {
                    Icon(Glyph.Shield, size = 12.dp, color = HN.amber)
                    Spacer(Modifier.width(4.dp))
                }
                HText(v.name, style = Type.monoSmall, color = if (v.effective) HN.ink else HN.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (v.flag == null) {
                Spacer(Modifier.height(2.dp))
                HText(v.value ?: stringResource(R.string.env_hidden_value), style = Type.monoSmall, color = if (v.masked) HN.inkDim else HN.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(2.dp))
            val where = buildList {
                if (!codex) add(stringResource(mcpScopeWord(v.scope)))
                v.overriddenBy?.let { add(stringResource(R.string.env_overridden, if (it == "system") stringResource(R.string.env_scope_system) else stringResource(mcpScopeWord(it)))) }
                if (v.guarded) add(stringResource(R.string.env_guarded))
                if (v.refused) add(stringResource(R.string.env_refused))
            }.joinToString(" · ")
            if (where.isNotEmpty()) HText(where, style = Type.small, color = HN.inkDim)
        }
        if (v.flag != null && !v.refused) {
            Box(
                Modifier
                    .size(56.dp, 48.dp)
                    .semantics { contentDescription = switchWords }
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = canChange, role = Role.Switch, onClick = onFlip),
                contentAlignment = Alignment.Center,
            ) { Toggle(v.flag == true) }
        }
        Box(
            Modifier
                .size(44.dp)
                .semantics { contentDescription = deleteWords }
                .clickable(remember { MutableInteractionSource() }, indication = null, enabled = canChange, role = Role.Button, onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) { Icon(Glyph.Cross, size = 14.dp, color = HN.inkDim) }
    }
}

/** A value to change: what's there if it may be seen, and a new one that goes one way. */
@Composable
private fun EditSheet(v: EnvVar, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(if (v.masked) "" else v.value.orEmpty()) }
    GlassSheet(v.name, onDismiss) {
        HText(v.name, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(6.dp))
        if (v.masked) {
            HText(v.value ?: stringResource(R.string.env_hidden_value), style = Type.monoSmall, color = HN.inkDim)
            Spacer(Modifier.height(2.dp))
            HText(stringResource(R.string.env_new_value_body), style = Type.small, color = HN.inkDim)
            Spacer(Modifier.height(8.dp))
        }
        Field(text, { text = it }, placeholder = stringResource(R.string.env_new_value), mono = true, secret = v.masked, label = stringResource(R.string.env_new_value))
        Spacer(Modifier.height(10.dp))
        Pill(stringResource(R.string.env_save), { onSave(text) }, kind = PillKind.Primary, height = 44.dp, enabled = !v.masked || text.isNotEmpty())
    }
}

