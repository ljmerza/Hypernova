package com.prudnikweb.hypernova.ui.voice

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Dictation
import com.prudnikweb.hypernova.ui.fx.LocalAmbient
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import kotlinx.coroutines.delay

/**
 * The voice as a radio telescope hears it (the owner: "kind of space audioform changing when
 * someone speaks louder or silent"): the seam of the dictation capsule becomes a light curve of the
 * last 2.4 s. Silence is a row of faint star-dots on the hairline; speech lifts them into mirrored
 * strokes of light, the newest by the timer, older ones dimming as they drift away, with a glint
 * on the loudest. It reads [Dictation.level] in draw on the shared clock, so it never recomposes.
 * Reduce Motion: nothing drifts; the dots stand still and their height steps with the voice four
 * times a second.
 */
@Composable
internal fun VoiceTrace(d: Dictation?, tone: Color, modifier: Modifier) {
    val clock = LocalAmbient.current
    val reduced = LocalMotion.current.reduced
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val step by produceState(0f, d, reduced) {
        if (!reduced || d == null) return@produceState
        while (true) {
            value = ((d.level() * 4f).toInt().coerceIn(0, 3) + 0.5f) / 4f
            delay(250)
        }
    }
    val st = remember { TraceState() }
    Canvas(modifier) {
        val u = 1.dp.toPx()
        val mid = size.height / 2f
        val gap = 4f * u
        val n = (size.width / gap).toInt().coerceIn(1, TraceState.N)
        // The seam's hairline stays under it, so the row still reads as one line in silence.
        drawLine(HN.line, Offset(0f, mid), Offset(size.width, mid), strokeWidth = u)
        if (reduced) {
            // Still: every dot at the one stepped height, no drift.
            val h = step * (mid - u)
            for (i in 0 until n) {
                val x = size.width - (i + 0.5f) * gap
                val at = if (rtl) size.width - x else x
                val a = 0.25f + 0.55f * step
                if (h < 1.2f * u) drawCircle(tone, 0.8f * u, Offset(at, mid), alpha = a)
                else drawLine(tone, Offset(at, mid - h), Offset(at, mid + h), strokeWidth = 1.4f * u, cap = StrokeCap.Round, alpha = a)
            }
            return@Canvas
        }
        st.feed(clock.seconds, d?.level() ?: 0f)
        for (i in 0 until n) {
            val l = st.at(i)
            // Newest at the timer's end; older ones drift toward the label and fade like a tail.
            val x = size.width - (i + 0.5f) * gap
            val at = if (rtl) size.width - x else x
            val age = i.toFloat() / n
            val fade = 1f - 0.75f * age
            val h = l * (mid - u)
            if (h < 1.2f * u) {
                drawCircle(tone, 0.8f * u, Offset(at, mid), alpha = 0.22f * fade)
                continue
            }
            drawLine(tone, Offset(at, mid - h), Offset(at, mid + h), strokeWidth = 1.4f * u, cap = StrokeCap.Round, alpha = (0.35f + 0.6f * l) * fade)
            // A glint on a loud one: a small four-pointed star at its tip.
            if (l > 0.6f) {
                val r = (1.5f + 2f * (l - 0.6f)) * u
                val tip = Offset(at, mid - h)
                val a = 0.8f * fade * (l - 0.6f) / 0.4f
                drawLine(Color.White, Offset(tip.x - r, tip.y), Offset(tip.x + r, tip.y), strokeWidth = 0.6f * u, alpha = a)
                drawLine(Color.White, Offset(tip.x, tip.y - r), Offset(tip.x, tip.y + r), strokeWidth = 0.6f * u, alpha = a)
            }
        }
    }
}

/** The last [N] loudness readings, one every 50 ms, smoothed like a meter (quick up, slow down). */
private class TraceState {
    companion object { const val N = 96 }
    private val ring = FloatArray(N)
    private var head = 0
    private var last = Float.NaN
    private var held = 0f
    private var meter = 0f

    fun feed(t: Float, level: Float) {
        if (last.isNaN() || t < last) { last = t; return }
        val dt = (t - last).coerceAtMost(0.2f)
        // Rise in ~40 ms, fall in ~220 ms, as the speaking wave does.
        val k = if (level > meter) (dt / 0.04f) else (dt / 0.22f)
        meter += (level - meter) * k.coerceIn(0f, 1f)
        held += t - last
        last = t
        // A long gap (the app was away) shifts at most the whole window.
        var steps = (held / 0.05f).toInt().coerceAtMost(N)
        held -= steps * 0.05f
        if (held > 0.05f) held = 0f
        while (steps-- > 0) {
            head = (head + 1) % N
            ring[head] = meter.coerceIn(0f, 1f)
        }
    }

    /** The reading [i] steps ago (0 = newest). */
    fun at(i: Int): Float = ring[((head - i) % N + N) % N]
}
