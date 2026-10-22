package com.prudnikweb.hypernova.ui.fx

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.prudnikweb.hypernova.ui.theme.LocalMotion

private const val GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#@$%&*"

/**
 * 0stems ScrambleHeading: each character resolves at its own threshold.
 * [k] runs 0→1; [salt] changes the noise between ticks.
 */
fun scramble(text: String, k: Float, salt: Int): String {
    if (k >= 1f) return text
    val sb = StringBuilder(text.length)
    for (i in text.indices) {
        val c = text[i]
        // Deterministic per-char threshold in [0, 1).
        val threshold = ((i * 7919 + text.length * 104729) % 1000) / 1000f
        sb.append(
            if (c == ' ' || k > threshold) c
            else GLYPHS[((i * 31 + salt * 17) and 0x7fffffff) % GLYPHS.length],
        )
    }
    return sb.toString()
}

/** Returns [text] scrambling in over 700ms the first time it's composed, if [enabled]. */
@Composable
fun rememberScrambled(text: String, enabled: Boolean): String {
    val reduced = LocalMotion.current.reduced
    if (!enabled || reduced) return text
    val anim = remember(text) { Animatable(0f) }
    LaunchedEffect(text) { anim.animateTo(1f, tween(700, easing = LinearEasing)) }
    val k = anim.value
    // Tick the noise at ~20 steps per run, like the 50ms interval on the web.
    return scramble(text, k, (k * 14).toInt())
}
