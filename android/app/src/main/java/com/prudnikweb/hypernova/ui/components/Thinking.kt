package com.prudnikweb.hypernova.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.prudnikweb.hypernova.ui.fx.LocalAmbient
import com.prudnikweb.hypernova.ui.fx.Pace
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion

/** One breath of the thinking label, start to start (seconds). */
private const val BREATH_S = 1.8f

/**
 * A label that shows Claude is thinking (owner, 2026-10-16): while [live] it breathes between the
 * accent's dim and bright greens, a slow pulse of about 1.8 s; once the thinking is over it is one
 * constant colour, [settled]. With Reduce Motion (or battery saver) it is a steady accent colour
 * instead of the animation. One animation per label on screen, and the colour is read while
 * drawing, so a pulse redraws this text and nothing else (no recomposition, nothing allocated per frame).
 */
@Composable
fun ThinkingText(
    text: String,
    live: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle,
    settled: Color = HN.accentDim,
    maxLines: Int = 1,
) {
    val motion = LocalMotion.current
    when {
        !live -> HText(text, modifier, style, settled, maxLines)
        motion.reduced || motion.powerSave -> HText(text, modifier, style, HN.accent, maxLines)
        else -> {
            // On the shared clock, not an infinite transition: that asked for a frame on every
            // vsync (120 a second on the owner's phone) for as long as Claude thought.
            val clock = LocalAmbient.current
            BasicText(
                text = text,
                modifier = modifier,
                style = style,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                // A slow breath of colour: the calm pace is plenty.
                color = { lerp(HN.accentDim, HN.accentBright, ease(clock.tri(BREATH_S, pace = Pace.Calm))) },
            )
        }
    }
}

/** A smoothstep, so the pulse lingers at both ends instead of turning sharply. */
private fun ease(t: Float) = t * t * (3f - 2f * t)
