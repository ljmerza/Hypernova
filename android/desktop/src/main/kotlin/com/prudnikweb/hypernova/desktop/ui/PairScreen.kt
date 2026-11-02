package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.desktop.PairState
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui

/** Paste a `hypernova pair` link: there's no camera to scan its code with. */
@Composable
fun PairScreen(shell: Shell, canCancel: Boolean) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        // Settings, for the debug log: a pairing that won't go is one of the things it's for.
        Pill(Ui.desk_settings.text(), { shell.settings = true }, Modifier.align(Alignment.BottomCenter), kind = PillKind.Ghost, height = 32.dp)
        GlassCard(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {
                Seam(Ui.pair_label.text(), MarkKind.Idle)
                Spacer(Modifier.height(18.dp))
                when (val state = shell.pair) {
                    PairState.Idle -> Asking(shell, canCancel)
                    is PairState.Working -> Working(state)
                    is PairState.Failed -> Failed(state, shell)
                }
            }
        }
    }
}

@Composable
private fun Asking(shell: Shell, canCancel: Boolean) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    HText(if (canCancel) Ui.onb_title_add.text() else Ui.onb_title.text(), style = Type.title)
    Spacer(Modifier.height(8.dp))
    HText(Ui.desk_pair_help.text(), style = Type.small, color = HN.inkMuted)
    Spacer(Modifier.height(18.dp))
    HText(Ui.desk_pair_field.text().uppercase(), style = Type.label, color = HN.inkDim)
    Spacer(Modifier.height(8.dp))
    val shape = RoundedCornerShape(10.dp)
    BasicTextField(
        value = shell.link,
        onValueChange = { shell.link = it },
        singleLine = true,
        textStyle = Type.mono.copy(color = HN.ink),
        cursorBrush = SolidColor(HN.accent),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = Ui.desk_pair_field.text() }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                    shell.submit()
                    true
                } else false
            },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(HN.bg900.copy(alpha = 0.9f), shape)
                    .border(1.dp, if (focused) HN.accentDim else HN.line, shape)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                if (shell.link.isEmpty()) HText(Ui.onb_paste_hint.text(), style = Type.mono, color = HN.inkDim, maxLines = 1)
                inner()
            }
        },
    )
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        if (canCancel) Pill(Ui.pair_back.text(), shell::cancelAdding, kind = PillKind.Ghost)
        Pill(Ui.onb_pair.text(), shell::submit, kind = PillKind.Primary, enabled = shell.link.isNotBlank())
    }
}

@Composable
private fun Working(state: PairState.Working) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mark(MarkKind.Working)
        Spacer(Modifier.padding(start = 10.dp))
        HText(Ui.pair_working.format(state.name), style = Type.title)
    }
}

@Composable
private fun Failed(state: PairState.Failed, shell: Shell) {
    HText(state.title, style = Type.title, color = HN.danger)
    Spacer(Modifier.height(8.dp))
    HText(state.body, style = Type.small, color = HN.inkMuted)
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        Pill(Ui.pair_back.text(), shell::closePair, kind = PillKind.Ghost)
        val again = state.link
        if (again != null) Pill(Ui.onb_pair.text(), { shell.pairWith(again) }, kind = PillKind.Primary)
    }
}
