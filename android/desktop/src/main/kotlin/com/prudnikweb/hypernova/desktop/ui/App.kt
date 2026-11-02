package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DebugLog
import com.prudnikweb.hypernova.desktop.Shell

/**
 * The window's content. Nothing paired, or "Pair another": the pairing field. Otherwise the server on
 * screen's sessions. [reduced]: nothing turns (a test's screen, or a later setting).
 */
@Composable
fun App(shell: Shell, reduced: Boolean = false) {
    AtlasTheme(reduced) {
        Box(Modifier.fillMaxSize().background(HN.bg)) {
            val hub = Core.live
            val pairing = hub == null || shell.adding
            AtlasBackground(week = hub?.usage?.sevenDay ?: 0f, strength = if (pairing) 0.45f else 1f)
            // The debug log's line for each screen, as on a phone.
            val screen = if (shell.settings) "settings" else if (pairing) "pairing" else "sessions"
            LaunchedEffect(screen) { DebugLog.i("screen") { screen } }
            if (shell.settings) SettingsScreen(shell)
            else if (hub == null || shell.adding) PairScreen(shell, canCancel = hub != null)
            else SessionsScreen(hub, Core.statuses, shell)
            shell.toast?.let { Toast(it, shell, Modifier.align(Alignment.BottomCenter)) }
            shell.zoomNote?.let { ZoomNote(it, Modifier.align(Alignment.TopCenter)) }
        }
    }
}

/**
 * Everything in [content] at [scale] pixels per dp. Only what dp and sp come to changes: layout, drawing and the
 * pointer are all in pixels, so a click lands where it's aimed and text is drawn at its size, not stretched.
 */
@Composable
fun Scaled(scale: Float, content: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    CompositionLocalProvider(LocalDensity provides Density(scale, fontScale), content = content)
}

/** The zoom level, for a moment, out of the way of a toast. */
@Composable
private fun ZoomNote(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .padding(12.dp)
            .background(HN.bg900.copy(alpha = 0.97f), shape)
            .border(1.dp, HN.lineStrong, shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        HText(text, style = Type.small, maxLines = 1)
    }
}

/** A hub's refusal or a lost link, in the glass of the app's toast. A click puts it away. */
@Composable
private fun Toast(text: String, shell: Shell, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .padding(16.dp)
            .widthIn(max = 560.dp)
            .background(HN.bg900.copy(alpha = 0.97f), shape)
            .border(1.dp, HN.danger.copy(alpha = 0.35f), shape)
            .clickable(onClick = shell::dismissToast)
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        HText(text, style = Type.small)
    }
}
