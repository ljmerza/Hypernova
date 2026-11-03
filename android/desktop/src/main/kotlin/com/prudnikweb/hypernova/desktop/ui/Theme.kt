package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalWindowInfo
import com.prudnikweb.hypernova.ui.fx.AmbientClock
import com.prudnikweb.hypernova.ui.fx.FrameBudget
import com.prudnikweb.hypernova.ui.fx.Heat
import com.prudnikweb.hypernova.ui.fx.LocalAmbient
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.MotionPrefs
import kotlinx.coroutines.delay

/*
 * Atlas's tokens are the phone's own, from :sharedui. These names keep this app's screens reading as they did.
 */
typealias HN = com.prudnikweb.hypernova.ui.theme.HN
typealias Type = com.prudnikweb.hypernova.ui.theme.Type

/**
 * How far round the turning marks are, in degrees: one clock for them all, read only in their draw lambdas,
 * so a tick redraws them and recomposes nothing. Still (0) under [reduced] or while the window hasn't the
 * focus. There's no system-wide Reduce Motion a JVM can read: a test or a later setting says it here.
 */
val LocalTurn = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

private const val TURN_FRAME_MS = 33L
private const val TURN_MS = 3_000f

/** Mint selection, as the phone's. */
private val SelectionColors = TextSelectionColors(handleColor = HN.accent, backgroundColor = HN.accent.copy(alpha = 0.28f))

@Composable
fun AtlasTheme(reduced: Boolean, content: @Composable () -> Unit) {
    val turn = remember { mutableFloatStateOf(0f) }
    val focused = LocalWindowInfo.current.isWindowFocused
    // What the shared drawing's marks read: the phone's clock, on a computer's one pace of frames, and no grain to move.
    val clock = remember { AmbientClock() }
    LaunchedEffect(clock, reduced, focused) {
        if (reduced || !focused) return@LaunchedEffect
        clock.run({ FrameBudget.fps(it, Heat.COOL, powerSave = false) }, grainFps = 0)
    }
    LaunchedEffect(reduced, focused) {
        if (reduced || !focused) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) {
            delay(TURN_FRAME_MS)
            turn.floatValue = (System.currentTimeMillis() - start) % TURN_MS.toLong() / TURN_MS * 360f
        }
    }
    CompositionLocalProvider(
        LocalTurn provides turn,
        LocalMotion provides MotionPrefs(reduced = reduced),
        LocalAmbient provides clock,
        LocalTextSelectionColors provides SelectionColors,
        content = content,
    )
}
