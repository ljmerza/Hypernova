package com.prudnikweb.hypernova.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Mcp
import com.prudnikweb.hypernova.data.SkillRow
import com.prudnikweb.hypernova.data.SkillsReply
import com.prudnikweb.hypernova.data.skillState
import com.prudnikweb.hypernova.data.skills
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.screenBottom
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

@StringRes
internal fun skillStateWord(state: String): Int = when (state) {
    SkillsReply.NAME_ONLY -> R.string.sk_name_only
    SkillsReply.USER_ONLY -> R.string.sk_user_only
    SkillsReply.OFF -> R.string.sk_off
    else -> R.string.sk_on
}

@StringRes
private fun skillStateLine(state: String): Int = when (state) {
    SkillsReply.NAME_ONLY -> R.string.sk_name_only_body
    SkillsReply.USER_ONLY -> R.string.sk_user_only_body
    SkillsReply.OFF -> R.string.sk_off_body
    else -> R.string.sk_on_body
}

@StringRes
private fun lockWords(by: String): Int = when (by) {
    "author" -> R.string.sk_lock_author
    "plugin" -> R.string.sk_lock_plugin
    "policy" -> R.string.sk_lock_policy
    "flag" -> R.string.sk_lock_flag
    else -> R.string.sk_lock_other
}

/** Agents & providers › an agent's Settings › Skills: each with where it's from and how it's listed. Claude Code's states are per folder, as in `/skills`. */
@Composable
fun SkillsScreen(hub: Hub, agent: String, onBack: () -> Unit) {
    val bottom = screenBottom()
    val server = hub.mac.name
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val codex = agent == AgentSettingsReply.CODEX
    var attempt by remember { mutableIntStateOf(0) }
    var folder by remember { mutableStateOf(Mcp.HOME) }
    var reply by remember { mutableStateOf<Result<SkillsReply>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<SkillRow?>(null) }
    var saving by remember { mutableStateOf<String?>(null) }
    var trouble by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt, folder, hub.agentSettingsVersion) {
        if (reply?.isSuccess != true) reply = null
        reply = hub.skills(agent, if (codex) null else folder)
    }
    ScreenScaffold(sourceKey = "skills:$agent", title = stringResource(if (codex) R.string.sk_title_codex else R.string.sk_title_claude), subtitle = server, onBack = onBack) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val r = reply
            when {
                r == null -> item(key = "loading") { LoadingLine(stringResource(R.string.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                r.isFailure -> item(key = "failure") { McpFailure(r.exceptionOrNull(), R.string.sk_err) { attempt++ } }
                else -> {
                    val data = r.getOrThrow()
                    item(key = "intro") { HText(stringResource(if (codex) R.string.sk_intro_codex else R.string.sk_intro_claude), style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
                    if (!codex) item(key = "folder") { FolderCard(data.folders.firstOrNull { it.id == folder }?.let { mcpFolderName(it) } ?: stringResource(R.string.mcp_home)) { picking = true } }
                    trouble?.let { t -> item(key = "trouble") { HText(t, style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp)) } }
                    if (data.skills.isEmpty()) item(key = "none") { HText(stringResource(R.string.sk_none), style = Type.body, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                    val groups = data.skills.groupBy { it.source ?: "" }
                    for ((from, rows) in groups) {
                        item(key = "seam-$from") { Seam(from.ifEmpty { stringResource(R.string.sk_elsewhere) }, modifier = Modifier.padding(horizontal = 4.dp).padding(top = 8.dp, bottom = 2.dp)) }
                        item(key = "card-$from") {
                            GlassCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                                    rows.forEachIndexed { i, s ->
                                        if (i > 0) Hairline(Modifier.fillMaxWidth())
                                        SkillRowView(s, saving == s.name) { trouble = null; open = s }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (picking) FolderSheet(reply?.getOrNull()?.folders.orEmpty(), folder, { picking = false }) { folder = it; picking = false }
    open?.let { s ->
        SkillSheet(s, reply?.getOrNull()?.states.orEmpty(), { open = null }) { state ->
            open = null
            saving = s.name
            scope.launch {
                hub.skillState(agent, s.name, if (codex) null else folder, state)
                    .onSuccess { toast(context.getString(R.string.as_saved, server)); attempt++ }
                    .onFailure { e -> trouble = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err) }
                saving = null
            }
        }
    }
}

@Composable
private fun SkillRowView(s: SkillRow, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            HText(s.displayName ?: s.name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            s.description?.let {
                Spacer(Modifier.height(2.dp))
                HText(it, style = Type.small, color = HN.inkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        HText(if (busy) stringResource(R.string.as_saving) else stringResource(skillStateWord(s.state)), style = Type.monoSmall, color = if (s.state == SkillsReply.ON) HN.accent else HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
    }
}

/** A skill in full, and how to list it. A locked one says who holds it. */
@Composable
private fun SkillSheet(s: SkillRow, states: List<String>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    GlassSheet(s.name, onDismiss) {
        HText(s.displayName ?: s.name, style = Type.title, color = HN.ink)
        if (s.displayName != null && s.displayName != s.name) HText(s.name, style = Type.monoSmall, color = HN.inkMuted)
        s.description?.let {
            Spacer(Modifier.height(6.dp))
            HText(it, style = Type.small, color = HN.inkMuted)
        }
        s.tokens?.let {
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.sk_tokens, it), style = Type.small, color = HN.inkDim)
        }
        Spacer(Modifier.height(10.dp))
        if (s.lockedBy != null) {
            HText(stringResource(skillStateWord(s.state)), style = Type.body, color = HN.ink)
            HText(stringResource(lockWords(s.lockedBy!!)), style = Type.small, color = HN.amber, modifier = Modifier.padding(top = 2.dp))
        } else states.forEach { st ->
            val now = st == s.state
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .selectable(now, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { if (now) onDismiss() else onPick(st) }
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    HText(stringResource(skillStateWord(st)), style = Type.body, color = if (now) HN.ink else HN.inkMuted)
                    HText(stringResource(skillStateLine(st)), style = Type.small, color = HN.inkDim)
                }
                Spacer(Modifier.width(12.dp))
                if (now) Icon(Glyph.Check, size = 14.dp, color = HN.accent)
            }
        }
    }
}
