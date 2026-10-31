package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.TerminalAsk
import com.prudnikweb.hypernova.data.Why
import com.prudnikweb.hypernova.data.whyAsksOf
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

// Why it asked (IDEAS PA7; docs/DECISIONS.md, "Why it asked"): one line, the rule in mono, on the
// Always sheet and in the session sheet's Permissions tab. Never on the chat's approval card nor
// on Home (the owner: nothing more on the chat screen).

/**
 * "Why it asked: Ask rule `Bash(python:*)` overrides auto mode for this command." [label]: lead with
 * "Why it asked:" (off under a seam that already says it). The daemon's own reading ends
 * "· Hypernova's reading".
 */
@Composable
internal fun WhyLine(why: Why, modifier: Modifier = Modifier, label: Boolean = true) {
    val lead = stringResource(R.string.why_label)
    val reading = stringResource(R.string.why_hypernova)
    val mono = Type.mono.let { SpanStyle(fontFamily = it.fontFamily, fontWeight = it.fontWeight, fontSize = it.fontSize, color = HN.accent) }
    val text = buildAnnotatedString {
        if (label) withStyle(SpanStyle(color = HN.inkDim)) { append("$lead ") }
        for (s in why.spans()) if (s.mono) withStyle(mono) { append(s.text) } else append(s.text)
        if (why.hypernovas) withStyle(SpanStyle(color = HN.inkDim)) { append(" · $reading") }
    }
    HText(text, modifier, style = Type.small, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
}

/**
 * The Permissions tab's "Why it asked", while fewer than two tool asks wait (with two or more,
 * "Asks waiting" says why under each): the waiting ask's command and why, and Claude Code's own
 * dialog in the terminal's. Nothing when none says why.
 */
@Composable
internal fun WhyAskedPart(hub: Hub, s: Session) {
    val dialog = s.menu?.permission?.takeIf { TerminalAsk.of(s) != null }
    val asks = whyAsksOf(hub.waitingAsks(s.id), dialog)
    if (asks.isEmpty()) return
    Column {
        Spacer(Modifier.height(14.dp))
        Hairline(Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        HText(stringResource(R.string.why_seam).uppercase(), style = Type.label, color = HN.inkDim)
        Spacer(Modifier.height(8.dp))
        for (a in asks) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                HText(if (a.tool == "Bash") "$" else "›", style = Type.mono, color = HN.amber)
                Spacer(Modifier.width(8.dp))
                HText(
                    if (a.tool == "Bash" || a.tool.isBlank()) a.text else "${a.tool}: ${a.text}", style = Type.mono, color = HN.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            WhyLine(a.why, Modifier.padding(start = 16.dp, bottom = 4.dp), label = false)
        }
    }
}
