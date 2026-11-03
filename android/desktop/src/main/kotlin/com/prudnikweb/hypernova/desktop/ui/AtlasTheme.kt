package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalWindowInfo
import com.prudnikweb.hypernova.ui.fx.AmbientClock
import com.prudnikweb.hypernova.ui.fx.FrameBudget
import com.prudnikweb.hypernova.ui.fx.Heat
import com.prudnikweb.hypernova.ui.fx.LocalAmbient
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.MotionPrefs

/** Mint selection, as the phone's. */
private val SelectionColors = TextSelectionColors(handleColor = HN.accent, backgroundColor = HN.accent.copy(alpha = 0.28f))

/**
 * What the phone's HypernovaTheme is to it: the motion the marks may use and the clock they read. There's no system-wide Reduce Motion a JVM
 * can read: a test or a later setting says it in [reduced]. Still while the window hasn't the focus.
 */
@Composable
fun AtlasTheme(reduced: Boolean, content: @Composable () -> Unit) {
    val focused = LocalWindowInfo.current.isWindowFocused
    val clock = remember { AmbientClock() }
    LaunchedEffect(clock, reduced, focused) {
        if (reduced || !focused) return@LaunchedEffect
        // A computer's one pace of frames, and no grain to move.
        clock.run({ FrameBudget.fps(it, Heat.COOL, powerSave = false) }, grainFps = 0)
    }
    CompositionLocalProvider(
        LocalMotion provides MotionPrefs(reduced = reduced),
        LocalAmbient provides clock,
        LocalTextSelectionColors provides SelectionColors,
        content = content,
    )
}
