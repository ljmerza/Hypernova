package com.prudnikweb.hypernova.ui.settings

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingsFile
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.ClaudeSettingsSummary
import com.prudnikweb.hypernova.data.CodexSettingsSummary
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.ShownSetting
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Agents & providers › an agent's Settings: the fixed list from [hub]'s server, in plain words, then
 * the summaries (docs/DECISIONS.md, "The agents' settings, viewed"). Read-only for now. Each server
 * answers for itself, so this is the hub whose card was tapped.
 */
@Composable
fun AgentSettingsScreen(hub: Hub, agent: String, onBack: () -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val codex = agent == AgentSettingsReply.CODEX
    val server = hub.mac.name
    var attempt by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<Result<AgentSettingsReply>?>(null) }
    LaunchedEffect(attempt) {
        reply = null
        reply = hub.agentSettings()
    }
    ScreenScaffold(
        sourceKey = "agent-settings:$agent",
        title = stringResource(if (codex) R.string.as_title_codex else R.string.as_title_claude),
        subtitle = server,
        onBack = onBack,
    ) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "intro") {
                HText(
                    stringResource(if (codex) R.string.as_intro_codex else R.string.as_intro_claude, server),
                    style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            val r = reply
            when {
                r == null -> item(key = "loading") { LoadingLine(stringResource(R.string.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                r.isFailure -> item(key = "failure") {
                    val e = r.exceptionOrNull()
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        HText(e?.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.as_err), style = Type.small, color = HN.amber)
                        // Trying again can't make an older server newer.
                        if (e !is AgentSettingsTooOld) {
                            Spacer(Modifier.height(10.dp))
                            Pill(stringResource(R.string.try_again), { attempt++ }, kind = PillKind.Outline, height = 40.dp)
                        }
                    }
                }
                else -> {
                    val file = r.getOrThrow().of(agent)
                    if (file.state != AgentSettingsFile.OK) {
                        item(key = "state") { FileState(file.state, codex, server) }
                    } else {
                        item(key = "values-seam") { Seam(stringResource(R.string.as_values), modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp, bottom = 2.dp)) }
                        item(key = "values") { ValuesCard(file.values) }
                        item(key = "summary-seam") { Seam(stringResource(R.string.as_summary), modifier = Modifier.padding(horizontal = 4.dp).padding(top = 14.dp, bottom = 2.dp)) }
                        file.claude?.let { s -> item(key = "summary") { ClaudeSummaryCard(s) } }
                        file.codex?.let { s -> item(key = "summary") { CodexSummaryCard(s) } }
                    }
                }
            }
        }
    }
}

/** The file isn't there (the agent's defaults), isn't valid, or can't be read. Never why in the file's own words: the server sends none. */
@Composable
private fun FileState(state: String, codex: Boolean, server: String) {
    val (text, trouble) = when (state) {
        AgentSettingsFile.MISSING -> (if (codex) R.string.as_missing_codex else R.string.as_missing_claude) to false
        AgentSettingsFile.MALFORMED -> (if (codex) R.string.as_malformed_codex else R.string.as_malformed_claude) to true
        else -> (if (codex) R.string.as_unreadable_codex else R.string.as_unreadable_claude) to true
    }
    GlassCard(Modifier.fillMaxWidth()) {
        HText(stringResource(text, server), style = Type.small, color = if (trouble) HN.amber else HN.inkMuted, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun ValuesCard(values: List<ShownSetting>) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            values.forEachIndexed { i, v ->
                if (i > 0) Hairline(Modifier.fillMaxWidth())
                val words = settingWords(v.key)
                ReadOnlyRow(
                    // A key without words of its own keeps its name, rather than a guess.
                    label = words?.let { stringResource(it.first) } ?: v.key,
                    value = shownValue(v),
                    valueColor = when (v.state) {
                        ShownSetting.UNRECOGNISED -> HN.amber
                        ShownSetting.UNSET -> HN.inkDim
                        else -> HN.ink
                    },
                    body = words?.let { stringResource(it.second) },
                )
            }
        }
    }
}

@Composable
private fun ClaudeSummaryCard(s: ClaudeSettingsSummary) {
    val unrecognised = stringResource(R.string.as_unrecognised)
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            val count = { n: Int? -> n?.toString() ?: "?" }
            ReadOnlyRow(stringResource(R.string.as_rules), stringResource(R.string.as_rules_value, count(s.allow), count(s.ask), count(s.deny)), HN.ink, stringResource(R.string.as_rules_body))
            Hairline(Modifier.fillMaxWidth())
            NamesRow(
                stringResource(R.string.as_plugins),
                s.plugins?.map { p ->
                    val state = when (p.on) {
                        true -> stringResource(R.string.as_on)
                        false -> stringResource(R.string.as_off)
                        null -> unrecognised
                    }
                    "${p.name}: $state"
                },
                null,
            )
            Hairline(Modifier.fillMaxWidth())
            NamesRow(stringResource(R.string.as_mcp), s.mcpServers, stringResource(R.string.as_mcp_claude_body))
            Hairline(Modifier.fillMaxWidth())
            NamesRow(stringResource(R.string.as_hooks), s.hookEvents, stringResource(R.string.as_hooks_body))
            Hidden(s.hidden)
        }
    }
}

@Composable
private fun CodexSummaryCard(s: CodexSettingsSummary) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            ReadOnlyRow(
                stringResource(R.string.as_projects),
                s.projects?.toString() ?: stringResource(R.string.as_unrecognised),
                if (s.projects == null) HN.amber else HN.ink,
                stringResource(R.string.as_projects_body),
            )
            Hairline(Modifier.fillMaxWidth())
            NamesRow(stringResource(R.string.as_mcp), s.mcpServers, null)
            Hidden(s.hidden)
        }
    }
}

@Composable
private fun Hidden(n: Int) {
    if (n <= 0) return
    Hairline(Modifier.fillMaxWidth())
    HText(pluralStringResource(R.plurals.as_hidden, n, n), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 10.dp))
}

/** A label with its value on the right and what it means below. Nothing to tap: it's only shown. */
@Composable
private fun ReadOnlyRow(label: String, value: String, valueColor: Color, body: String?) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HText(label, style = Type.body, color = HN.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            HText(value, style = Type.monoSmall, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
        }
        if (body != null) {
            Spacer(Modifier.height(2.dp))
            HText(body, style = Type.small, color = HN.inkDim)
        }
    }
}

/** A summary that's a list of names: each on its own line, "None", or that the file holds something else. */
@Composable
private fun NamesRow(label: String, names: List<String>?, body: String?) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HText(label, style = Type.body, color = HN.ink, modifier = Modifier.weight(1f))
            when {
                names == null -> HText(stringResource(R.string.as_unrecognised), style = Type.monoSmall, color = HN.amber, maxLines = 1)
                names.isEmpty() -> HText(stringResource(R.string.as_none), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                else -> HText(names.size.toString(), style = Type.monoSmall, color = HN.ink, maxLines = 1)
            }
        }
        if (body != null) {
            Spacer(Modifier.height(2.dp))
            HText(body, style = Type.small, color = HN.inkDim)
        }
        if (!names.isNullOrEmpty()) {
            Spacer(Modifier.height(6.dp))
            HText(names.joinToString("\n"), style = Type.monoSmall, color = HN.inkMuted)
        }
    }
}

@Composable
private fun shownValue(v: ShownSetting): String = when (v.state) {
    ShownSetting.UNSET -> stringResource(R.string.as_not_set)
    ShownSetting.UNRECOGNISED -> stringResource(R.string.as_unrecognised)
    else -> when {
        v.on != null -> stringResource(if (v.on) R.string.as_on else R.string.as_off)
        else -> valueWord(v.key, v.text.orEmpty())?.let { stringResource(it) } ?: v.text.orEmpty()
    }
}

/** The label and the line for a key. Each checked against the agents' own docs or binaries (docs/DECISIONS.md). */
internal fun settingWords(key: String): Pair<Int, Int>? = when (key) {
    "effortLevel" -> R.string.as_k_effort to R.string.as_k_effort_body
    "permissions.defaultMode" -> R.string.as_k_mode to R.string.as_k_mode_body
    "showThinkingSummaries" -> R.string.as_k_thinking to R.string.as_k_thinking_body
    "autoDreamEnabled" -> R.string.as_k_dream to R.string.as_k_dream_body
    "includeCoAuthoredBy" -> R.string.as_k_coauthor to R.string.as_k_coauthor_body
    "autoUpdatesChannel" -> R.string.as_k_channel to R.string.as_k_channel_body
    "inputNeededNotifEnabled" -> R.string.as_k_input to R.string.as_k_input_body
    "agentPushNotifEnabled" -> R.string.as_k_agent_push to R.string.as_k_agent_push_body
    "model" -> R.string.as_k_model to R.string.as_k_model_body
    "model_reasoning_effort" -> R.string.as_k_reasoning to R.string.as_k_reasoning_body
    "approval_policy" -> R.string.as_k_approval to R.string.as_k_approval_body
    "sandbox_mode" -> R.string.as_k_sandbox to R.string.as_k_sandbox_body
    "web_search" -> R.string.as_k_web to R.string.as_k_web_body
    "personality" -> R.string.as_k_personality to R.string.as_k_personality_body
    else -> null
}

/** A value in words; null: as it is in the file (a model's id, a word from a newer server). */
@StringRes
internal fun valueWord(key: String, value: String): Int? = when (key) {
    "effortLevel", "model_reasoning_effort" -> when (value) {
        "none" -> R.string.as_v_none
        "minimal" -> R.string.as_v_minimal
        "low" -> R.string.as_v_low
        "medium" -> R.string.as_v_medium
        "high" -> R.string.as_v_high
        "xhigh" -> R.string.as_v_xhigh
        "max" -> R.string.as_v_max
        "ultra" -> R.string.as_v_ultra
        else -> null
    }
    // The app's own names for the modes.
    "permissions.defaultMode" -> when (value) {
        "default", "manual" -> R.string.mode_title_default
        "acceptEdits" -> R.string.mode_title_accept
        "plan" -> R.string.mode_title_plan
        "auto" -> R.string.mode_title_auto
        "dontAsk" -> R.string.as_v_dont_ask
        "bypassPermissions" -> R.string.mode_title_bypass
        else -> null
    }
    "autoUpdatesChannel" -> when (value) {
        "latest" -> R.string.as_v_latest
        "stable" -> R.string.as_v_stable
        "rc" -> R.string.as_v_rc
        else -> null
    }
    "approval_policy" -> when (value) {
        "untrusted" -> R.string.as_v_untrusted
        "on-failure" -> R.string.as_v_on_failure
        "on-request" -> R.string.as_v_on_request
        "never" -> R.string.as_v_never
        else -> null
    }
    "sandbox_mode" -> when (value) {
        "read-only" -> R.string.as_v_read_only
        "workspace-write" -> R.string.as_v_workspace_write
        "danger-full-access" -> R.string.as_v_full_access
        else -> null
    }
    "web_search" -> when (value) {
        "disabled" -> R.string.as_v_disabled
        "cached" -> R.string.as_v_cached
        "indexed" -> R.string.as_v_indexed
        "live" -> R.string.as_v_live
        else -> null
    }
    "personality" -> when (value) {
        "none" -> R.string.as_v_none
        "friendly" -> R.string.as_v_friendly
        "pragmatic" -> R.string.as_v_pragmatic
        else -> null
    }
    else -> null
}
