package com.prudnikweb.hypernova.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingsFile
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionMcpReply
import com.prudnikweb.hypernova.data.SessionMcpServer
import com.prudnikweb.hypernova.data.SessionMcpTooOld
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * The Home card's menu › MCP servers (owner, 2026-11-01; docs/DECISIONS.md, "A session's MCP servers"):
 * what's set up for this session's folder, not the ones for all projects (a count of those, as a line).
 * Each with what the server lets leave. Whether one is connected isn't known here, and the note says so.
 */
@Composable
internal fun SessionMcpSheet(hub: Hub, s: Session, onDismiss: () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<Result<SessionMcpReply>?>(null) }
    LaunchedEffect(s.id, attempt) {
        reply = null
        reply = hub.sessionMcp(s.id)
    }
    GlassSheet(s.name, onDismiss) {
        HText(stringResource(R.string.sm_label, s.project).uppercase(), style = Type.label, color = HN.inkDim)
        Spacer(Modifier.height(6.dp))
        HText(s.name, style = Type.small, color = HN.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        val r = reply
        when {
            r == null -> LoadingLine(stringResource(R.string.loading), Modifier.padding(vertical = 8.dp))
            r.isFailure -> {
                val e = r.exceptionOrNull()
                HText(e?.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.sm_err), style = Type.small, color = HN.amber)
                // Trying again can't make an older server newer.
                if (e !is SessionMcpTooOld) {
                    Spacer(Modifier.height(10.dp))
                    Pill(stringResource(R.string.try_again), { attempt++ }, kind = PillKind.Outline, height = 40.dp)
                }
            }
            else -> McpList(r.getOrThrow())
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Pill(stringResource(R.string.chat_close), onDismiss, kind = PillKind.Ghost, height = 44.dp)
        }
    }
}

@Composable
private fun McpList(r: SessionMcpReply) {
    if (r.servers.isEmpty()) HText(stringResource(R.string.sm_empty), style = Type.body, color = HN.inkMuted)
    r.servers.forEachIndexed { i, server ->
        if (i > 0) Hairline(Modifier.fillMaxWidth())
        ServerRow(server)
    }
    if (r.state == AgentSettingsFile.MALFORMED || r.state == AgentSettingsFile.UNREADABLE) Line(stringResource(R.string.as_mcp_trouble), amber = true)
    if (r.hidden > 0) Line(pluralStringResource(R.plurals.as_hidden, r.hidden, r.hidden))
    if (r.foldersNotRead > 0) Line(pluralStringResource(R.plurals.as_mcp_not_read, r.foldersNotRead, r.foldersNotRead))
    if (r.forAllProjects > 0) Line(pluralStringResource(R.plurals.sm_for_all, r.forAllProjects, r.forAllProjects))
    if (r.servers.isNotEmpty()) Line(stringResource(R.string.sm_note))
}

@Composable
private fun Line(text: String, amber: Boolean = false) {
    Spacer(Modifier.height(10.dp))
    HText(text, style = Type.small, color = if (amber) HN.amber else HN.inkDim)
}

/** Its name and scope, then how it runs: "stdio · npx", "http · mcp.example.com". */
@Composable
private fun ServerRow(server: SessionMcpServer) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HText(server.name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            HText(stringResource(if (server.scope == SessionMcpReply.LOCAL) R.string.sm_local else R.string.sm_project), style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
        }
        Spacer(Modifier.height(2.dp))
        val how = listOfNotNull(server.transport ?: stringResource(R.string.sm_other), server.command, server.host).joinToString(" · ")
        HText(how, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        server.from?.let { HText(stringResource(R.string.sm_from, it), style = Type.small, color = HN.inkDim) }
    }
}
