package com.prudnikweb.hypernova.ui.session

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.TerminalAsk
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Fork: Claude Code's own permission dialog in the session's terminal ([TerminalAsk]), drawn as the
 * chat's approval card is: the amber band, the dialog's words, the command whole (scrolling when
 * tall), then Deny and Allow, and below them any other option the dialog has. [onPick] gets the
 * menu option to press there; the card holds still while it goes, until the dialog changes.
 */
@Composable
internal fun TerminalAskCard(ask: TerminalAsk, onPick: (Int) -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    // The option on its way to the terminal (by its place): the buttons wait, 4 s at most.
    var sent by remember(ask.menuId) { mutableStateOf<Int?>(null) }
    LaunchedEffect(sent) {
        if (sent != null) {
            kotlinx.coroutines.delay(4000)
            sent = null
        }
    }
    fun pick(i: Int) {
        if (sent != null) return
        sent = i
        onPick(i)
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HN.bg950.copy(alpha = 0.9f))
            .background(HN.amber.copy(alpha = 0.05f))
            .border(1.dp, HN.amber.copy(alpha = 0.45f), shape),
    ) {
        // The band: NEEDS YOU, and in the terminal, the tool.
        Row(
            Modifier.fillMaxWidth().background(HN.amber).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HText(stringResource(R.string.state_needs_you).uppercase(), style = Type.label.copy(fontWeight = FontWeight(700)), color = HN.bg, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            HText(
                (ask.tool ?: stringResource(R.string.menu_in_terminal)).uppercase(),
                style = Type.label.copy(fontWeight = FontWeight(700), textAlign = TextAlign.End), color = HN.bg.copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Column(Modifier.padding(14.dp)) {
            if (ask.lines.isNotEmpty()) {
                HText(ask.lines.joinToString("\n"), style = Type.small, color = HN.inkMuted)
                Spacer(Modifier.height(10.dp))
            }
            val command = ask.command
            if (command != null) {
                // Never cut: what Allow lets run, all of it, scrolling past a tall one.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(HN.bg.copy(alpha = 0.9f))
                        .border(1.dp, HN.line, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState()),
                ) {
                    SelectionContainer {
                        HText(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = HN.amber)) { append(if (ask.isBash) "$ " else "› ") }
                                append(command)
                            },
                            style = Type.monoSmall, color = HN.ink,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.deny), { pick(ask.deny) }, Modifier.weight(1f), kind = PillKind.Danger, enabled = sent == null || sent == ask.deny)
                Pill(
                    stringResource(R.string.allow),
                    {
                        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                        pick(ask.allow)
                    },
                    Modifier.weight(1.2f), kind = PillKind.Primary, enabled = sent == null || sent == ask.allow,
                )
            }
            // The dialog's other answers ("Yes, and don't ask again for …"), as it words them.
            for (e in ask.extras) {
                Spacer(Modifier.height(8.dp))
                Pill(
                    listOfNotNull(e.label, e.detail).joinToString(" · "), { pick(e.choice) }, Modifier.fillMaxWidth(),
                    kind = PillKind.Ghost, height = 40.dp, enabled = sent == null || sent == e.choice,
                )
            }
            Spacer(Modifier.height(8.dp))
            Pill(stringResource(R.string.menu_open_terminal), onOpen, Modifier.fillMaxWidth(), kind = PillKind.Outline, height = 40.dp, leading = Glyph.Terminal)
        }
    }
}
