package com.prudnikweb.hypernova.ui.components

import com.prudnikweb.hypernova.data.Str
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

/* The kit's rows and controls for a form: labels, switches, segments. */

/** A label above a field, tracked like the seam labels. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, trail: String? = null) {
    Row(modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HText(text.uppercase(), style = Type.label, color = HN.inkDim)
        if (trail != null) {
            Spacer(Modifier.width(8.dp))
            HText(trail, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        }
    }
}

/** A switch in the house style: a glass track that washes mint, a mint knob. */
@Composable
fun Toggle(checked: Boolean, modifier: Modifier = Modifier) {
        val x by animateDpAsState(if (checked) 18.dp else 0.dp, tween(Motion.DIALOG, easing = Motion.Expo), label = "knob")
    Box(
        modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(CircleShape)
            .glassChoice(checked)
            .padding(3.dp),
    ) {
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 0) }.size(18.dp).clip(CircleShape).background(if (checked) HN.accent else HN.inkMuted))
    }
}

/** A settings row with a title, a description and a switch. The whole row toggles (48dp+). */
@Composable
fun ToggleRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onChange(!checked) }
            .semantics { toggleableState = ToggleableState(checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            HText(title, style = Type.body, color = HN.ink)
            if (body != null) {
                Spacer(Modifier.height(2.dp))
                HText(body, style = Type.small, color = HN.inkDim)
            }
        }
        Spacer(Modifier.width(16.dp))
        Toggle(checked)
    }
}

/** A tappable settings row: title, description, and a chevron (or a trailing value). */
@Composable
fun NavRow(title: String, body: String?, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = HN.ink, trailing: String? = null, end: (@Composable () -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            HText(title, style = Type.body, color = color)
            if (body != null) {
                Spacer(Modifier.height(2.dp))
                HText(body, style = Type.small, color = HN.inkDim)
            }
        }
        Spacer(Modifier.width(12.dp))
        if (trailing != null) {
            HText(trailing, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
        if (end != null) {
            end()
            Spacer(Modifier.width(8.dp))
        }
        Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
    }
}

/** A key and a value on one line (mono value). */
@Composable
fun InfoRow(key: String, value: String, modifier: Modifier = Modifier, valueColor: Color = HN.inkMuted) {
    Row(modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        HText(key, style = Type.small, color = HN.inkDim)
        Spacer(Modifier.width(16.dp))
        Spacer(Modifier.weight(1f))
        HText(value, style = Type.monoSmall, color = valueColor, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

/**
 * A confirmation, as the house pop-up ([GlassDialog]): the question as its title, one line on what
 * it means, Cancel on the left and the action on the right ([danger] makes it red, and the card's
 * thin accent with it). Tap outside or Back to cancel.
 */
@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false,
    /** The quiet pill's words: Cancel unless said ("Keep editing"). */
    dismiss: String? = null,
) {
    if (!visible) return
    GlassDialog(
        onDismiss = onDismiss,
        title = title,
        line = body,
        tint = if (danger) HN.danger else null,
        secondary = DialogButton(dismiss ?: stringResource(Str.cancel), onDismiss, PillKind.Ghost),
        primary = DialogButton(confirm, onConfirm, if (danger) PillKind.Danger else PillKind.Primary),
    )
}

/** Hyperscribe segmented control: glass track, the active segment washed mint. 48dp tall for touch. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit, modifier: Modifier = Modifier, enabled: (T) -> Boolean = { true }) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .frostedGlass(null, glowX = 0.dp)
            .padding(4.dp),
    ) {
        for ((o, label) in options) {
            val selected = o == value
            // A segment that can't be chosen now (Bypass in a session that can't switch to it) is dimmed.
            val on = enabled(o)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .glassChoice(selected)
                    .graphicsLayer { alpha = if (on) 1f else 0.35f }
                    .selectable(selected, enabled = on, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { onChange(o) }
                    .padding(vertical = 9.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                HText(label, style = Type.small, color = if (selected) HN.accent else HN.inkMuted, maxLines = 1)
            }
        }
    }
}
