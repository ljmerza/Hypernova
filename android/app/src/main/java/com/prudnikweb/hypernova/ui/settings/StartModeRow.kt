package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.NewSessionMemory
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.ModePicker
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.home.modeTitle
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * An agent's **New sessions start in**, on its Settings for this server (docs/DECISIONS.md, "New sessions
 * start in, per server and agent"; owner, 2026-11-03): kept on that server's daemon, so its desktop client
 * follows too. Claude Code offers Ask, Edits, Plan and Auto, Auto through the think-twice card as it's
 * chosen; Codex its two. Bypass isn't offered: it's chosen each time in the sheet, behind its fingerprint.
 * An older server says it needs updating. A server without the agent doesn't show this: AgentSettingsScreen leaves it out.
 */
@Composable
internal fun StartModeSection(hub: Hub, agent: String) {
    val codex = agent == AgentKind.CODEX
    val toast = LocalToast.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val server = hub.mac.name
    val served = if (codex) hub.codex?.startMode else hub.claude?.startMode
    var asked by remember { mutableStateOf<String?>(null) }
    fun set(m: String) {
        if (asked != null) return
        asked = m
        scope.launch {
            hub.setStartMode(agent, m).onFailure { toast(it.message?.takeIf { w -> w.isNotBlank() } ?: context.getString(R.string.as_edit_err)) }
            asked = null
        }
    }
    Column {
        Seam(stringResource(R.string.st_new_sessions), modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp, bottom = 12.dp))
        GlassCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                HText(stringResource(R.string.st_start_mode), style = Type.body, color = HN.ink)
                Spacer(Modifier.height(4.dp))
                if (served == null) {
                    HText(stringResource(R.string.st_start_mode_too_old, server), style = Type.small, color = HN.amber)
                    return@Column
                }
                HText(stringResource(if (codex) R.string.st_start_mode_codex else R.string.st_start_mode_claude, server), style = Type.small, color = HN.inkMuted)
                Spacer(Modifier.height(12.dp))
                val now = asked ?: if (codex) NewSessionMemory.keptCodexMode(served) else NewSessionMemory.startMode(served)
                val look = Modifier.alpha(if (asked != null) 0.6f else 1f)
                if (codex) Segmented(AgentKind.CODEX_MODES.map { it to modeTitle(it) }, now, { if (it != now) set(it) }, look)
                else ModePicker(now, { if (it != now) set(it) }, look, bypass = false)
            }
        }
    }
}
