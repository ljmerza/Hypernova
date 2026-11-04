package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.AgentUpdateJob
import com.prudnikweb.hypernova.data.AgentVersion
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.agentVersion
import com.prudnikweb.hypernova.data.checkAgentVersion
import com.prudnikweb.hypernova.data.updateAgent
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** While an update runs, how often this page asks again. Coming back to the page asks at once. */
internal const val UPDATE_POLL_MS = 2_500L

/**
 * An agent's Settings › Version (docs/DECISIONS.md, "Updating Claude Code and Codex"; owner: "also add ability to check and update
 * Claude code and codex"). What [hub]'s server has, a check when asked, and an update behind a confirm. The
 * update runs on the server whether or not this page is open, so its result is here on return.
 */
@Composable
internal fun AgentVersionSection(hub: Hub, agent: String) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    val server = hub.mac.name
    val name = stringResource(if (agent == AgentSettingsReply.CODEX) R.string.cx_agent_codex else R.string.cx_agent_claude)
    var view by remember(hub, agent) { mutableStateOf<AgentVersion?>(null) }
    var tooOld by remember(hub, agent) { mutableStateOf(false) }
    var trouble by remember(hub, agent) { mutableStateOf<String?>(null) }
    var checking by remember(hub, agent) { mutableStateOf(false) }
    var showOutput by remember(hub, agent) { mutableStateOf(false) }
    fun took(r: Result<AgentVersion>) {
        r.onSuccess {
            view = it
            trouble = null
        }.onFailure { e ->
            if (e is AgentSettingsTooOld) tooOld = true
            trouble = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.av_err)
        }
    }
    LaunchedEffect(hub, agent, hub.agentSettingsVersion) { took(hub.agentVersion(agent)) }
    val current = view
    val updating = current?.updating == true
    LaunchedEffect(hub, agent, updating) {
        while (updating) {
            delay(UPDATE_POLL_MS)
            hub.agentVersion(agent).onSuccess { view = it }
        }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (tooOld) {
                // Trying again can't make an older server newer.
                HText(stringResource(R.string.av_too_old, server, name), style = Type.small, color = HN.amber, modifier = Modifier.padding(vertical = 10.dp))
                return@Column
            }
            if (current != null && !current.installed) {
                HText(stringResource(R.string.ag_not_installed, server), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 10.dp))
                return@Column
            }
            if (current != null) {
                ReadOnlyRow(stringResource(R.string.av_installed), current.version ?: stringResource(R.string.av_unknown), HN.ink, current.words?.let { stringResource(R.string.av_installed_with, it) })
                current.latest?.let { latest ->
                    Hairline(Modifier.fillMaxWidth())
                    ReadOnlyRow(
                        stringResource(R.string.av_newest), latest, if (current.newer) HN.amber else HN.inkMuted,
                        if (current.newer) current.channel?.takeIf { it == STABLE }?.let { stringResource(R.string.av_on_stable) } else stringResource(R.string.av_nothing_newer),
                    )
                }
                Words(current.problem, amber = true)
                if (!updating) Words(current.why, amber = true)
                if (!updating) current.command?.let { ShellLine(stringResource(R.string.av_run), it) }
                if (current.autoUpdates == true && current.canUpdate) Words(stringResource(R.string.av_also_itself, name), amber = false)
                current.job?.let { job -> JobPart(job, showOutput) { showOutput = !showOutput } }
            }
            Words(trouble, amber = true)
            if (checking) LoadingLine(stringResource(R.string.av_checking), Modifier.padding(vertical = 6.dp))
            if (!updating) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Pill(stringResource(R.string.av_check), {
                        checking = true
                        trouble = null
                        scope.launch {
                            took(hub.checkAgentVersion(agent))
                            checking = false
                        }
                    }, kind = PillKind.Outline, height = 40.dp, enabled = !checking)
                    if (current?.canUpdate == true) {
                        val latest = current.latest.orEmpty()
                        Pill(stringResource(R.string.av_update, latest), {
                            val parts = listOfNotNull(
                                context.getString(R.string.av_confirm_what, name, current.version ?: context.getString(R.string.av_unknown), latest, current.words.orEmpty()),
                                current.running.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.av_confirm_running, it, it) },
                                current.effect,
                                current.warning,
                            )
                            codeCheck(context, confirm, toast, server, context.getString(R.string.av_check_what, name, latest), parts.joinToString("\n\n")) {
                                scope.launch {
                                    trouble = null
                                    took(hub.updateAgent(agent))
                                }
                            }
                        }, kind = PillKind.Primary, height = 40.dp, enabled = !checking)
                    }
                }
            }
        }
    }
}

/** How the update is doing, or how it ended, and what the updater itself said on request. */
@Composable
private fun JobPart(job: AgentUpdateJob, showOutput: Boolean, onToggle: () -> Unit) {
    Hairline(Modifier.fillMaxWidth())
    if (job.running) {
        LoadingLine(stringResource(R.string.av_updating), Modifier.padding(vertical = 8.dp))
        Output(job.output.takeLast(RUNNING_LINES))
        Spacer(Modifier.height(8.dp))
        return
    }
    Words(job.message, amber = job.state != AgentUpdateJob.UPDATED)
    if (job.output.isNotEmpty()) {
        Pill(stringResource(if (showOutput) R.string.av_output_hide else R.string.av_output_show), onToggle, kind = PillKind.Outline, height = 32.dp)
        if (showOutput) {
            Spacer(Modifier.height(6.dp))
            Output(job.output)
        }
    }
}

@Composable
private fun Output(lines: List<String>) {
    if (lines.isEmpty()) return
    HText(lines.joinToString("\n"), style = Type.monoSmall, color = HN.inkDim)
}

@Composable
private fun Words(text: String?, amber: Boolean) {
    if (text.isNullOrBlank()) return
    HText(text, style = Type.small, color = if (amber) HN.amber else HN.inkDim, modifier = Modifier.padding(vertical = 6.dp))
}

private const val STABLE = "stable"
private const val RUNNING_LINES = 6
