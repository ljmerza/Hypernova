package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.WApproval
import com.prudnikweb.hypernova.data.Why
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.ConfirmDialog
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

// "Allow these N" (IDEAS PA6; docs/PROTOCOL.md, "Approvals"): when one session has several asks
// waiting (parallel agents), the session sheet's Permissions tab lists each command and allows them
// all with one confirmation. Only Allow: a deny stays one at a time, on its card in the chat.
// Nothing here is on the chat screen itself (the owner: nothing more on the chat screen).

/** One ask in the list: its command (or a tool's one line), the agent that asks if not the main one, and why it asked. */
internal data class SeveralAsk(val id: String, val tool: String, val text: String, val agent: String?, val why: Why? = null)

/** What the part shows: the tool asks it can allow, oldest first, and how many other asks wait (questions, plans). */
internal data class Several(val asks: List<SeveralAsk>, val others: Int)

/**
 * The session's waiting asks as the part shows them, or null when there aren't two tool asks to
 * allow together: a question or a plan needs its own answer, so it never counts.
 */
internal fun severalOf(waiting: List<WApproval>): Several? {
    val tools = waiting.filter { it.kind == "tool" }.sortedBy { it.createdAt }
    if (tools.size < 2) return null
    return Several(
        tools.map { a ->
            val text = a.input["command"]?.takeIf { it.isNotBlank() } ?: a.summary.removePrefix("${a.toolName}: ").ifBlank { a.toolName }
            SeveralAsk(a.id, a.toolName, text, a.agentLabel ?: a.agentId?.let { "" }, a.why)
        },
        waiting.size - tools.size,
    )
}

/** The Permissions tab's "Allow these N": nothing while fewer than two tool asks wait. */
@Composable
internal fun AllowSeveralPart(hub: Hub, s: Session) {
    val several = severalOf(hub.waitingAsks(s.id)) ?: return
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<List<String>?>(null) }
    val n = several.asks.size
    Column {
        Spacer(Modifier.height(14.dp))
        Hairline(Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HText(stringResource(R.string.several_seam).uppercase(), style = Type.label, color = HN.inkDim, modifier = Modifier.weight(1f))
            HText("$n", style = Type.monoSmall, color = HN.amber, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        for (a in several.asks) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                HText(if (a.tool == "Bash") "$" else "›", style = Type.mono, color = HN.amber)
                Spacer(Modifier.width(8.dp))
                HText(
                    if (a.tool == "Bash") a.text else "${a.tool}: ${a.text}", style = Type.mono, color = HN.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (a.agent != null) {
                    Spacer(Modifier.width(8.dp))
                    HText(
                        a.agent.ifBlank { stringResource(R.string.agent_label) }, style = Type.monoSmall, color = HN.inkDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 110.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(HN.ink.copy(alpha = 0.06f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            // Why it asked, under its command (WhyAsked.kt).
            if (a.why != null) WhyLine(a.why, Modifier.padding(start = 16.dp, bottom = 4.dp), label = false)
        }
        if (several.others > 0) {
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.several_more), style = Type.small, color = HN.inkDim)
        }
        Spacer(Modifier.height(10.dp))
        Pill(
            pluralStringResource(R.plurals.several_allow, n, n), { confirm = several.asks.map { it.id } },
            Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 44.dp,
        )
    }
    val ids = confirm
    ConfirmDialog(
        visible = ids != null,
        title = pluralStringResource(R.plurals.several_q, ids?.size ?: n, ids?.size ?: n),
        body = stringResource(R.string.several_body),
        confirm = pluralStringResource(R.plurals.several_allow, ids?.size ?: n, ids?.size ?: n),
        onConfirm = {
            confirm = null
            // The asks shown when Allow was tapped, not any that came in since.
            if (ids != null) appScope.launch { hub.allowSeveral(s.id, ids).onFailure { toast(it.message ?: context.getString(R.string.err_answer)) } }
        },
        onDismiss = { confirm = null },
    )
}
