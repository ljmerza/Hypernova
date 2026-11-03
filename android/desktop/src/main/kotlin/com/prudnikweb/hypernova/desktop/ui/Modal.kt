package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.prudnikweb.hypernova.desktop.Ui

/** The whole window, for a [Modal]'s scrim. */
private val WholeWindow = object : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

/**
 * A card over a dimmed window, as the phone's GlassDialog: a click outside or Escape puts it away. It has the keys and
 * the pointer while it's up, so nothing behind it can be pressed.
 */
@Composable
internal fun Modal(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Popup(
        popupPositionProvider = WholeWindow,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        onKeyEvent = { e ->
            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                onDismiss()
                true
            } else false
        },
    ) {
        Box(
            Modifier.fillMaxSize().background(HN.bg.copy(alpha = 0.6f))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            // Swallows a click, so one on the card isn't one outside it.
            GlassCard(
                Modifier.padding(24.dp).widthIn(max = 480.dp).fillMaxWidth()
                    // A card's glass is for over the chart: what's under this one would show through its words.
                    .background(HN.bg900, RoundedCornerShape(16.dp))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
            ) {
                Column(Modifier.padding(20.dp), content = content)
            }
        }
    }
}

/** Asks first: the phone's ConfirmDialog. The focus starts on the way out, so a stray Enter does nothing. [cancel] null: only [confirm], for what's only said. */
@Composable
internal fun ConfirmModal(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false, cancel: String? = Ui.cancel.text()) {
    val focus = remember { FocusRequester() }
    Modal(onDismiss) {
        // In here: the popup's own content, not yet there when this composes.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        HText(title, Modifier.semantics { heading() }, style = Type.title)
        Spacer(Modifier.height(10.dp))
        HText(body, style = Type.small, color = HN.inkMuted)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            if (cancel != null) Pill(cancel, onDismiss, Modifier.focusRequester(focus), kind = PillKind.Ghost)
            Pill(
                confirm, onConfirm, if (cancel == null) Modifier.focusRequester(focus) else Modifier,
                kind = if (danger) PillKind.Danger else PillKind.Primary,
            )
        }
    }
}
