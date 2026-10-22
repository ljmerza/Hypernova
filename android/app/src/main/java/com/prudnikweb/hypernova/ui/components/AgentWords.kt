package com.prudnikweb.hypernova.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.prudnikweb.hypernova.R

/**
 * "Claude" or "Codex", for the words that name a session's agent ("Claude is still at work",
 * "Codex didn't start"): one helper for every screen (data/AgentLead.kt says which agent leads).
 */
@Composable
fun agentName(codex: Boolean): String = stringResource(if (codex) R.string.cx_agent_codex else R.string.claude)

/** [agentName] outside composition (notifications, widgets, run words). */
fun agentName(res: Resources, codex: Boolean): String = res.getString(if (codex) R.string.cx_agent_codex else R.string.claude)

/**
 * One of three wordings, by the agents in use on the paired computer (data/AgentLead.kt): [claude]
 * where Claude Code is alone (the words from before Codex, unchanged), [codex] where Codex leads
 * (Claude Code isn't installed), [both] while both are in use.
 */
fun byAgents(claudeState: com.prudnikweb.hypernova.data.ClaudeState?, codexState: com.prudnikweb.hypernova.data.CodexState?, claude: Int, codex: Int, both: Int): Int = when {
    com.prudnikweb.hypernova.data.AgentLead.both(claudeState, codexState) -> both
    com.prudnikweb.hypernova.data.AgentLead.codexLeads(claudeState, codexState) -> codex
    else -> claude
}
