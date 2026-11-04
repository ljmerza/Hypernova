// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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

