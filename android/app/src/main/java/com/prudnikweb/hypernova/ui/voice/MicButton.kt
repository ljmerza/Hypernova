package com.prudnikweb.hypernova.ui.voice

import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.VoiceTiming
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.frostedGlass
import com.prudnikweb.hypernova.ui.components.planetSphere
import com.prudnikweb.hypernova.ui.fx.LocalAmbient
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI

/** What the button shows (spec 3.1). */
private enum class Face { Send, SendOff, Uploading, Mic, MicDim, Listening, HandsFree, Settling }

/**
 * Send and the mic in one 44 dp circle (in a 48 dp target), where Send has always been.
 *
 * - Empty box, voice on the Mac: the mic, frosted glass with a mint light (dim and dark while the
 *   Mac can't be reached). Text or files in the box: Send, as before; holding it 400 ms dictates
 *   more. No voice: Send, disabled, as before.
 * - Listening: mint, 12% bigger, a halo that swells with the voice; hands-free shows a check (a tap
 *   is Done). Let go with nothing heard yet: a small planet turns until the words come.
 * - [send]: the box has text or files. [canSend]: Send works now. [uploading]: a file is still on
 *   its way (Send waits; holding still dictates).
 *
 * TalkBack: "Dictate" or "Send"; a double tap starts hands-free (or sends), and while it listens
 * the state reads "Listening" with Stop and Cancel as actions; Send has "Dictate more". A double
 * tap and hold is push-to-talk, as a long press is.
 */
@Composable
fun MicButton(voice: VoiceInput, send: Boolean, canSend: Boolean, modifier: Modifier = Modifier, uploading: Boolean = false) {
    val scope = rememberCoroutineScope()
    val sendNow by rememberUpdatedState(send)
    val canSendNow by rememberUpdatedState(canSend)
    val face = when {
        voice.handsFree -> Face.HandsFree
        voice.listening -> Face.Listening
        send && uploading -> Face.Uploading
        send -> if (canSend) Face.Send else Face.SendOff
        voice.settlingEmpty -> Face.Settling
        voice.available -> if (voice.linked) Face.Mic else Face.MicDim
        else -> Face.SendOff
    }
    val micLabel = stringResource(R.string.voice_mic)
    val sendLabel = stringResource(R.string.send)
    val listeningLabel = stringResource(R.string.voice_a11y_listening)
    val stopLabel = stringResource(R.string.voice_a11y_stop)
    val cancelLabel = stringResource(R.string.voice_a11y_cancel)
    val moreLabel = stringResource(R.string.voice_a11y_more)
    val voiceOffered = voice.available
    Box(
        modifier
            .size(48.dp)
            .pointerInput(voice) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    voice.on(
                        MicEvent.Down(
                            at = SystemClock.elapsedRealtime(), send = sendNow, canSend = canSendNow,
                            voice = voice.available, linked = voice.linked, permitted = voice.permitted(),
                        ),
                    )
                    // Send with text: a tap sends, 400 ms held dictates more.
                    var held: Job? = null
                    if ((voice.gesture as? MicState.SendPressed)?.canDictate == true) {
                        held = scope.launch {
                            delay(VoiceTiming.SEND_HOLD_MS)
                            voice.on(MicEvent.SendHeld(SystemClock.elapsedRealtime()))
                        }
                    }
                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (c.changedToUpIgnoreConsumed()) {
                                // The system took the touch: Compose hands us a release already consumed.
                                voice.on(if (c.isConsumed) MicEvent.PointerCancel else MicEvent.Up(SystemClock.elapsedRealtime()))
                                c.consume()
                                break
                            }
                            val away = (c.position - down.position).getDistance() / density
                            voice.on(MicEvent.Move(away))
                            c.consume()
                        }
                    } catch (e: CancellationException) {
                        voice.on(MicEvent.PointerCancel)
                        throw e
                    } finally {
                        held?.cancel()
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = if (send && !voice.listening) sendLabel else micLabel
                if (voice.listening) stateDescription = listeningLabel
                onClick {
                    when {
                        voice.listening -> voice.on(MicEvent.Done)
                        send -> if (canSend) voice.sendNow()
                        else -> voice.on(MicEvent.A11yStart(append = false, linked = voice.linked, permitted = voice.permitted()))
                    }
                    true
                }
                customActions = when {
                    voice.listening -> listOf(
                        CustomAccessibilityAction(stopLabel) { voice.leave(); true },
                        CustomAccessibilityAction(cancelLabel) { voice.on(MicEvent.Cancel); true },
                    )
                    send && voiceOffered -> listOf(
                        CustomAccessibilityAction(moreLabel) { voice.on(MicEvent.A11yStart(append = true, linked = voice.linked, permitted = voice.permitted())); true },
                    )
                    else -> emptyList()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(face, animationSpec = tween(Motion.FAST), label = "mic") { f -> Face(f, voice) }
    }
}

@Composable
private fun Face(f: Face, voice: VoiceInput) {
    val clock = LocalAmbient.current
    val reduced = LocalMotion.current.reduced
    when (f) {
        // Glass like every button (owner, 2026-10-16: "Send button doesn't look like glass"): Send is
        // mint-tinted glass with a lit arrow, as a Primary pill; off or waiting, plain glass.
        Face.Send, Face.SendOff, Face.Uploading -> Box(
            Modifier.size(44.dp).frostedGlass(if (f == Face.Send) HN.accent else null, glowX = 22.dp, dense = true, tint = if (f == Face.Send) HN.accent else null),
            contentAlignment = Alignment.Center,
        ) {
            if (f == Face.Uploading) com.prudnikweb.hypernova.ui.session.Spinner(Modifier.size(18.dp))
            else Icon(Glyph.Send, size = 16.dp, color = if (f == Face.Send) HN.accent else HN.inkDim, stroke = 1.8.dp)
        }
        Face.Mic, Face.MicDim -> Box(
            Modifier.size(44.dp).frostedGlass(if (f == Face.Mic) HN.accent else null, glowX = 22.dp, dense = true),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyph.Mic, size = 20.dp, color = if (f == Face.Mic) HN.accent else HN.inkDim, stroke = 1.5.dp)
        }
        Face.Listening, Face.HandsFree -> {
            // 12% bigger while held; a halo outside it swells with the voice (read only while drawing).
            val grow by animateFloatAsState(if (f == Face.Listening) 1.12f else 1f, tween(Motion.DIALOG, easing = Motion.Expo), label = "grow")
            Box(
                Modifier
                    .size(44.dp)
                    .drawBehind {
                        val d = voice.current ?: return@drawBehind
                        if (!reduced) clock.seconds
                        val l = d.level()
                        val r = size.minDimension / 2f * grow
                        val tone = if (voice.armed) HN.danger else HN.accent
                        drawCircle(tone, r + (3f + 9f * l) * density, alpha = 0.10f + 0.22f * l)
                        drawCircle(tone, r + (2f + 7f * l) * density, alpha = 0.25f + 0.35f * l, style = Stroke(1.dp.toPx()))
                    }
                    .graphicsLayer { scaleX = grow; scaleY = grow }
                    // Lit glass, red while armed to cancel: the halo outside says it's listening.
                    .frostedGlass(if (voice.armed) HN.danger else HN.accent, glowX = 22.dp, dense = true, tint = if (voice.armed) HN.danger else HN.accent),
                contentAlignment = Alignment.Center,
            ) {
                val tone = if (voice.armed) HN.danger else HN.accent
                if (f == Face.HandsFree) Icon(Glyph.Check, size = 18.dp, color = tone, stroke = 2.dp)
                else Icon(Glyph.Mic, size = 20.dp, color = tone, stroke = 1.7.dp)
            }
        }
        Face.Settling -> Box(Modifier.size(44.dp).frostedGlass(HN.accent, glowX = 22.dp, dense = true), contentAlignment = Alignment.Center) {
            // A small planet turning while the words are on their way (3 s at most).
            val turn = remember { floatArrayOf(0f) }
            Canvas(Modifier.size(16.dp)) {
                val t = if (reduced) 0f else clock.seconds
                turn[0] = t * 2f * PI.toFloat() / 1.2f
                planetSphere(Offset(size.width / 2f, size.height / 2f), 6.5.dp.toPx(), HN.accent, turn[0], stroke = 0.8.dp.toPx())
            }
        }
    }
}
