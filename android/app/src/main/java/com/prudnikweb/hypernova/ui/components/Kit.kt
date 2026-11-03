// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Toast
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import dev.chrisbanes.haze.hazeSource

/**
 * A pushed screen: content scrolls under a blurred top bar with Back, a title and a mono
 * subtitle ([subtitleContent] draws one instead, when it's more than words). [content] gets the
 * top padding (status bar + bar) and a modifier that makes it the bar's blur source; apply it to
 * the scrolling container.
 */
@Composable
fun ScreenScaffold(
    sourceKey: String,
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    below: (@Composable ColumnScope.() -> Unit)? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.(top: Dp, source: Modifier) -> Unit,
) {
    val haze = LocalHaze.current
    val density = LocalDensity.current
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var barPx by remember { mutableIntStateOf(0) }
    val barH = with(density) { barPx.toDp() }
    Box(Modifier.fillMaxSize()) {
        // Content starts where the bar's blur has faded to nearly sharp, so its first line is readable.
        content(if (barPx == 0) status + 104.dp else barH - 6.dp, Modifier.hazeSource(haze, zIndex = Z.CONTENT, key = sourceKey))
        BlurredTop(haze, sourceKey, Modifier.fillMaxWidth().onSizeChanged { barPx = it.height }) {
            Column(Modifier.fillMaxWidth().padding(top = status + 4.dp, start = 8.dp, end = 12.dp, bottom = 26.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphButton(Glyph.Back, stringResource(R.string.back), onBack)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        HText(title, style = Type.title, color = HN.ink, maxLines = 1)
                        if (subtitleContent != null) subtitleContent()
                        else if (subtitle != null) {
                            HText(subtitle, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                    }
                    actions()
                }
                if (below != null) {
                    Spacer(Modifier.height(10.dp))
                    below()
                }
            }
        }
    }
}

/** How tall the tabs are from the screen's bottom edge, on a Settings page that has them (ui/App.kt); else nothing. */
val LocalTabsRoom = compositionLocalOf { 0.dp }

/**
 * What a pushed screen's content leaves free at the bottom: the tabs on a Settings page, which
 * already stand on the navigation bar, else the navigation bar.
 */
@Composable
fun screenBottom(): Dp = maxOf(LocalTabsRoom.current, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())

/** What the page on screen asks before a tab takes it away: given the going, it goes or doesn't. */
val LocalTabsAsk = staticCompositionLocalOf<MutableState<((go: () -> Unit) -> Unit)?>?> { null }

/** For a page that asks before it loses what's typed: a tab asks as its Back does, with [ask]. */
@Composable
fun AskBeforeTabs(ask: (go: () -> Unit) -> Unit) {
    val holder = LocalTabsAsk.current ?: return
    val now by rememberUpdatedState(ask)
    DisposableEffect(holder) {
        val mine: (go: () -> Unit) -> Unit = { go -> now(go) }
        holder.value = mine
        onDispose { if (holder.value === mine) holder.value = null }
    }
}

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
        secondary = DialogButton(dismiss ?: stringResource(R.string.cancel), onDismiss, PillKind.Ghost),
        primary = DialogButton(confirm, onConfirm, if (danger) PillKind.Danger else PillKind.Primary),
    )
}

/** A short message that floats above the bottom chrome, then fades. Tap to dismiss. */
@Composable
fun ToastHost(toast: Toast?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // With the keyboard open, at the top: at the bottom it would cover the text you're writing.
    val keyboard = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Box(modifier.fillMaxSize(), contentAlignment = if (keyboard) Alignment.TopCenter else Alignment.BottomCenter) {
        AnimatedVisibility(
            toast != null,
            enter = fadeIn(tween(Motion.DIALOG)) + slideInVertically(tween(Motion.REVEAL, easing = Motion.Expo)) { it / 2 },
            exit = fadeOut(tween(Motion.DIALOG)) + slideOutVertically(tween(Motion.DIALOG)) { it / 3 },
        ) {
            val last = remember { mutableListOf<Toast?>(null) }
            if (toast != null) last[0] = toast
            val t = last[0]
            if (t != null) {
                Box(
                    Modifier
                        .padding(start = 20.dp, end = 20.dp, top = if (keyboard) top + 12.dp else 0.dp, bottom = if (keyboard) 0.dp else nav + 150.dp)
                        // The pop-ups' glass (ui/components/Glass.kt), at a toast's size.
                        .popupGlass(null, radius = 18.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    HText(t.text, style = Type.small, color = HN.ink)
                }
            }
        }
    }
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
