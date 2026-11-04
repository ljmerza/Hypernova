package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.Str
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphButton
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * The session's terminal has text in its input box that isn't sent (typed on the Mac, or put
 * back there by an interrupt), so the prompt didn't go. Replace it, or go and look ([onOpen]: null where there's no terminal to go to).
 */
@Composable
internal fun TerminalTextCard(pending: String, onReplace: () -> Unit, onOpen: (() -> Unit)?, onDismiss: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), accent = HN.amber) {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 14.dp)) {
            // × lets it go once the text was dealt with in the terminal some other way.
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(stringResource(Str.busy_title), style = Type.heading, color = HN.ink, modifier = Modifier.weight(1f))
                GlyphButton(Glyph.Cross, stringResource(Str.busy_dismiss), onDismiss)
            }
            Column(Modifier.padding(end = 10.dp)) {
                HText(pending, style = Type.monoSmall, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                HText(stringResource(Str.busy_body), style = Type.small, color = HN.inkDim)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(stringResource(Str.busy_replace), onReplace, Modifier.weight(1f), kind = PillKind.Primary)
                    if (onOpen != null) Pill(stringResource(Str.open_terminal), onOpen, kind = PillKind.Outline, leading = Glyph.Terminal)
                }
            }
        }
    }
}

