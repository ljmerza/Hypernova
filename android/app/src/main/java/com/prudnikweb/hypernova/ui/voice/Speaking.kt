package com.prudnikweb.hypernova.ui.voice

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.PulsarWave
import com.prudnikweb.hypernova.data.Speaker
import com.prudnikweb.hypernova.data.Speaking
import com.prudnikweb.hypernova.ui.components.frostedGlass
import com.prudnikweb.hypernova.ui.fx.LocalAmbient
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

/*
 * You can see when the phone speaks (owner: "some label or logo animations ... like audio wave ...
 * so everywhere"). The wave is a pulsar's beat: five mint bars, the middle one the loudness of what
 * the speaker plays this moment and each pair outward the same a beat earlier, so a syllable rises
 * in the middle and spreads out. Silent, it settles to five dots; stopped, it's gone. It moves
 * with the real audio (Speaker.level, the loudness at the track's play head), never a loop.
 * Reduce Motion: a still wave and the word "Speaking".
 */

/**
 * The wave. Reads the ambient clock while drawing, so it redraws with the app's one clock (30 fps,
 * 20 on battery saver) and costs nothing when nothing speaks (the screens remove it then).
 */
@Composable
fun SpeakingWave(modifier: Modifier = Modifier, color: Color = HN.accent, height: Dp = 14.dp, bar: Dp = 2.5.dp, gap: Dp = 2.5.dp, live: Boolean = true) {
    val clock = LocalAmbient.current
    val reduced = LocalMotion.current.reduced
    val wave = remember { PulsarWave() }
    val last = remember { floatArrayOf(-1f) }
    val width = bar * wave.bars + gap * (wave.bars - 1)
    Canvas(modifier.size(width, height)) {
        val heights = if (reduced) PulsarWave.STATIC else {
            val t = clock.seconds
            val dt = if (last[0] < 0f) 0f else (t - last[0]) * 1000f
            last[0] = t
            wave.step(if (live) Speaker.level() else 0f, dt)
            wave.heights
        }
        val w = bar.toPx()
        val g = gap.toPx()
        val h = size.height
        for (i in 0 until wave.bars) {
            val x = w / 2 + i * (w + g)
            // At rest a bar is a dot (its round caps alone); the loudest fills the height.
            val len = (h - w) * heights[i]
            drawLine(color, Offset(x, h / 2 - len / 2), Offset(x, h / 2 + len / 2), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

/**
 * "Speaking · Stop": a slim glass capsule with the wave, what speaks (this phone's Read aloud) and
 * Stop. [onStop] stops it.
 */
@Composable
fun SpeakingBar(speaking: Speaking, modifier: Modifier = Modifier, onStop: () -> Unit = { Speaker.stop("phone") }) {
    val view = LocalView.current
    val reduced = LocalMotion.current.reduced
    val what = stringResource(R.string.speaking_label)
    val stop = stringResource(R.string.speaking_stop)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .frostedGlass(HN.accent, glowX = 22.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = what
                liveRegion = LiveRegionMode.Polite
            }
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeakingWave(live = speaking.started, color = if (speaking.started) HN.accent else HN.accentDim)
        Spacer(Modifier.width(10.dp))
        HText(
            if (reduced || speaking.started) what.uppercase() else stringResource(R.string.speaking_starting).uppercase(),
            style = Type.label, color = HN.accent, maxLines = 1,
        )
        // A hairline divides the capsule: what it does, then the way to stop it (no "·").
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(1.dp).height(14.dp).background(HN.ink.copy(alpha = 0.14f)))
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stop) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onStop()
                }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            HText(stop.uppercase(), style = Type.label, color = HN.ink, maxLines = 1)
        }
    }
}

/**
 * The app's own bar while anything speaks and no screen shows one of its own: under the status
 * bar, centred, over whatever is there.
 */
@Composable
fun SpeakingBarHost(hidden: Boolean, modifier: Modifier = Modifier) {
    val speaking = Speaker.now
    // Keeps the last one on screen while it folds away.
    val last = remember { arrayOfNulls<Speaking>(1) }
    if (speaking != null) last[0] = speaking
    AnimatedVisibility(
        speaking != null && !hidden,
        modifier = modifier,
        enter = fadeIn(tween(Motion.FAST)) + slideInVertically(tween(Motion.DIALOG, easing = Motion.Expo)) { -it / 2 },
        exit = fadeOut(tween(Motion.FAST)) + slideOutVertically(tween(Motion.DIALOG)) { -it / 2 },
    ) {
        val s = last[0] ?: return@AnimatedVisibility
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
            SpeakingBar(s)
        }
    }
}

/**
 * Under a session's status line while it's being spoken (its reply on this phone): the wave and
 * "Speaking", in the status line's own type.
 */
@Composable
fun SpeakingStatus(sessionId: String, modifier: Modifier = Modifier) {
    val speaking = Speaker.now?.takeIf { it.play.sessionId == sessionId }
    AnimatedVisibility(
        speaking != null,
        modifier = modifier,
        enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
    ) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(12.dp), contentAlignment = Alignment.Center) {
                SpeakingWave(height = 12.dp, bar = 2.dp, gap = 0.5.dp, live = speaking?.started == true)
            }
            Spacer(Modifier.width(10.dp))
            HText(stringResource(R.string.speaking_status).uppercase(), style = Type.label, color = HN.accentDim, maxLines = 1)
        }
    }
}

/** On the message being read: a small wave at its corner, gone when the voice stops. */
@Composable
fun SpeakingMark(modifier: Modifier = Modifier) {
    Box(modifier.padding(6.dp).height(12.dp)) {
        SpeakingWave(height = 12.dp, bar = 2.dp, gap = 2.dp, live = Speaker.now?.started == true)
    }
}
