package com.prudnikweb.hypernova.ui.voice

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Dictation
import com.prudnikweb.hypernova.data.DictationState
import com.prudnikweb.hypernova.data.VoiceTiming
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.planetSphere
import com.prudnikweb.hypernova.ui.fx.AmbientClock
import com.prudnikweb.hypernova.ui.fx.LocalAmbient
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlin.math.PI

/**
 * The capsule above the box while listening (spec 3.4), with its way in and out: from the button's
 * corner (scale 0.6 → 1, 220 ms) when the mic opens; on release it sinks into the box (180 ms) as
 * the words land there; cancelled, it shrinks back into the button (160 ms). It's added above the
 * box in the same bottom-anchored column, so the box and the button under the finger never move.
 * Under Reduce Motion it only fades.
 */
@Composable
fun DictationCapsuleHost(voice: VoiceInput, modifier: Modifier = Modifier, maxLines: Int = 5) {
    val reduced = LocalMotion.current.reduced
    val corner = TransformOrigin(1f, 1f)
    AnimatedVisibility(
        voice.listening,
        modifier,
        enter = if (reduced) fadeIn(tween(220)) + expandVertically(tween(220), expandFrom = Alignment.Bottom)
        else fadeIn(tween(220, easing = Motion.Expo)) + scaleIn(tween(220, easing = Motion.Expo), initialScale = 0.6f, transformOrigin = corner) +
            expandVertically(tween(220, easing = Motion.Expo), expandFrom = Alignment.Bottom),
        exit = when {
            reduced -> fadeOut(tween(180)) + shrinkVertically(tween(180), shrinkTowards = Alignment.Bottom)
            voice.cancelled -> fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.6f, transformOrigin = corner) +
                shrinkVertically(tween(160), shrinkTowards = Alignment.Bottom)
            else -> fadeOut(tween(180)) + slideOutVertically(tween(180)) { it / 3 } + shrinkVertically(tween(180), shrinkTowards = Alignment.Bottom)
        },
    ) {
        Column {
            DictationCapsule(voice, maxLines = maxLines)
            Spacer(Modifier.height(10.dp))
        }
    }
}

private val CapsuleShape = RoundedCornerShape(24.dp)
// The glass card's fill: dense enough that nothing behind reads through the words.
private val CapsuleFill = Brush.verticalGradient(listOf(HN.bg800.copy(alpha = 0.86f), HN.bg900.copy(alpha = 0.80f), HN.bg900.copy(alpha = 0.86f)))
private val TopEdge = Brush.horizontalGradient(listOf(Color.Transparent, HN.ink.copy(alpha = 0.25f), Color.Transparent))
private val CaretId = "caret"

/**
 * What's being heard: the ringed planet on the left (it hears you), then a seam (LISTENING, a
 * hairline, the time), the words (settled in ink, still changing dim, a mint caret), and the way
 * out (slide away to cancel; hands-free has Cancel and Done). Armed to cancel, the light and the
 * planet go red and the words dim. TalkBack hears none of it while the mic is open (it would be
 * transcribed); the button says it's listening. [compact]: it fits a given space (the sheet's
 * field), without the hint line. [opaque]: it lies over other controls, so none read through.
 */
@Composable
fun DictationCapsule(voice: VoiceInput, modifier: Modifier = Modifier, maxLines: Int = 5, compact: Boolean = false, opaque: Boolean = false) {
    // Kept while the capsule folds away after the dictation has gone on to settle.
    val keep = remember { arrayOfNulls<Dictation>(1) }
    voice.current?.let { keep[0] = it }
    val d = keep[0]
    val state = voice.state
    val armed = voice.armed
    val handsFree = voice.handsFree
    val tone by animateColorAsState(if (armed) HN.danger else HN.accent, tween(120), label = "tone")
    // Words: the last seen while listening; once let go, they stay as they were.
    val words = remember { arrayOf("", "") }
    when (state) {
        is DictationState.Live -> { words[0] = state.final; words[1] = state.interim }
        else -> {}
    }
    val clock = LocalAmbient.current

    Row(
        modifier
            .fillMaxWidth()
            .clip(CapsuleShape)
            .drawBehind {
                // Laid over other controls (the sheet's attachments), nothing of them may read through.
                if (opaque) drawRect(HN.bg900)
                drawRect(CapsuleFill)
                // A soft light inside the glass around the planet, red while armed.
                val c = Offset(10.dp.toPx() + 24.dp.toPx(), 12.dp.toPx() + 24.dp.toPx())
                drawCircle(Brush.radialGradient(listOf(tone.copy(alpha = 0.14f), tone.copy(alpha = 0.04f), Color.Transparent), c, 90.dp.toPx()), 90.dp.toPx(), c)
            }
            .drawWithContent {
                drawContent()
                val sw = 1.dp.toPx()
                drawLine(TopEdge, Offset(0f, sw / 2f), Offset(size.width, sw / 2f), strokeWidth = sw)
            }
            .border(1.dp, HN.ink.copy(alpha = 0.08f), CapsuleShape)
            // Its surface takes taps, so none fall through to what's behind (a hidden field, the transcript).
            .pointerInput(Unit) { detectTapGestures { } }
            .clearAndSetSemantics { }
            .padding(start = 10.dp, end = 16.dp, top = if (compact) 8.dp else 12.dp, bottom = if (compact) 8.dp else 12.dp),
    ) {
        RingedPlanet(d, voice, Modifier.size(48.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(top = 4.dp)) {
            // ─── The seam: what it's doing, a hairline, the time ───
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(
                    stringResource(
                        when {
                            armed -> R.string.voice_release_cancel
                            handsFree -> R.string.voice_hands_free
                            else -> R.string.voice_listening
                        },
                    ).uppercase(),
                    style = Type.label, color = if (armed) HN.danger else HN.accentDim, maxLines = 1,
                )
                Spacer(Modifier.width(10.dp))
                // The seam is the voice's light curve: flat dots in silence, strokes of light as it speaks.
                VoiceTrace(d, tone, Modifier.weight(1f).height(16.dp))
                Spacer(Modifier.width(10.dp))
                Elapsed(d)
            }
            Spacer(Modifier.height(6.dp))
            // In a fixed space (compact) the words take what's left and keep to the bottom. Only
            // there: in a scrolling sheet the height is unbounded and a weight would leave them none.
            Captions(words[0], words[1], dim = armed, maxLines = maxLines, clock = clock, modifier = if (compact) Modifier.weight(1f, fill = false) else Modifier)
            // ─── The way out ───
            if (handsFree) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Pill(stringResource(R.string.voice_cancel), { voice.on(MicEvent.Cancel) }, kind = PillKind.Ghost, height = 36.dp)
                    // Submit sends what was said (MicEvent.DoneSend), the keyboard kept shut; a box that doesn't send (New session) says Done.
                    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
                    Pill(stringResource(if (voice.submits) R.string.voice_submit else R.string.voice_done), { if (voice.submits) keyboard?.hide(); voice.on(MicEvent.DoneSend) }, kind = PillKind.Primary, height = 36.dp)
                }
            } else if (!armed && !compact) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    HText(stringResource(R.string.voice_slide_cancel).uppercase(), style = Type.label, color = HN.inkGhost, maxLines = 1)
                }
            }
        }
    }
}

/** `0:07`, once a second; from 1:45 the time left, `0:15 LEFT`, in amber. */
@Composable
private fun Elapsed(d: Dictation?) {
    val since = d?.startedAt
    val ms by produceState(0L, since) {
        if (since == null) return@produceState
        while (true) {
            value = SystemClock.elapsedRealtime() - since
            delay(1_000 - value % 1_000)
        }
    }
    val late = ms >= VoiceTiming.COUNTDOWN_MS
    val shown = if (late) (VoiceTiming.MAX_MS - ms).coerceAtLeast(0) else ms
    val clock = "%d:%02d".format(shown / 60_000, (shown / 1_000) % 60)
    HText(
        if (late) stringResource(R.string.voice_left, clock).uppercase() else clock,
        style = Type.monoSmall, color = if (late) HN.amber else HN.inkDim, maxLines = 1,
    )
}

/**
 * The words, body size: settled in ink, still changing dim, a 2 dp mint caret blinking on the shared
 * clock (read in draw, so it never recomposes). Up to [maxLines]; beyond that the text keeps to the
 * bottom and the oldest line fades under a 24 dp mask.
 */
@Composable
private fun Captions(final: String, interim: String, dim: Boolean, maxLines: Int, clock: AmbientClock, modifier: Modifier = Modifier) {
    val waiting = stringResource(R.string.voice_waiting)
    val text: AnnotatedString = remember(final, interim, dim, waiting) {
        buildAnnotatedString {
            if (final.isEmpty() && interim.isEmpty()) {
                withStyle(SpanStyle(color = HN.inkMuted)) { append(waiting) }
                return@buildAnnotatedString
            }
            withStyle(SpanStyle(color = if (dim) HN.inkDim else HN.ink)) { append(final) }
            if (final.isNotEmpty() && interim.isNotEmpty()) append(' ')
            withStyle(SpanStyle(color = if (dim) HN.inkGhost else HN.inkMuted)) { append(interim) }
            if (!dim) appendInlineContent(CaretId, "▍")
        }
    }
    val caret = remember(clock) {
        mapOf(
            CaretId to InlineTextContent(Placeholder(0.4.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
                Canvas(Modifier.fillMaxSize()) {
                    if (!clock.blink(1.06f)) return@Canvas
                    val w = 2.dp.toPx()
                    drawRect(HN.accent, topLeft = Offset(w * 0.6f, 0f), size = Size(w, size.height))
                }
            },
        )
    }
    // The text's height, read in draw: taller than its box, the oldest line fades.
    val textHeight = remember { mutableFloatStateOf(0f) }
    val lineDp = 22.dp
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = lineDp * maxLines)
            .clipToBounds()
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                // The oldest line fades away at the top as more come.
                if (textHeight.floatValue > size.height + 1f) drawRect(Brush.verticalGradient(0f to Color.Transparent, 24.dp.toPx() / size.height to Color.Black), blendMode = BlendMode.DstIn)
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        BasicText(
            text,
            Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true),
            style = Type.body,
            inlineContent = caret,
            onTextLayout = { textHeight.floatValue = it.size.height.toFloat() },
        )
    }
}

/**
 * The planet that hears you: a small mint sphere (meridians turning once in 3 s, slower while the
 * link opens, still while armed) inside two tilted rings that swell and brighten with the voice
 * [Dictation.level], the back half of each drawn behind it and the front half in front. A syllable
 * sends an echo ring out from the outer one. Red while armed; the rings draw in as it settles.
 * Every number is read in draw on the shared clock, so a tick never recomposes. Reduce Motion:
 * no spin and no echoes, fixed rings whose light steps with the voice four times a second.
 */
@Composable
private fun RingedPlanet(d: Dictation?, voice: VoiceInput, modifier: Modifier) {
    val clock = LocalAmbient.current
    val reduced = LocalMotion.current.reduced
    val tone by animateColorAsState(if (voice.armed) HN.danger else HN.accent, tween(120), label = "planet")
    val gather by animateFloatAsState(if (voice.listening) 1f else 0f, tween(180, easing = Motion.Expo), label = "gather")
    // Under Reduce Motion the clock stands still: the level is sampled in four steps instead.
    val step by produceState(0, d, reduced) {
        if (!reduced || d == null) return@produceState
        while (true) {
            value = (d.level() * 4f).toInt().coerceIn(0, 3)
            delay(250)
        }
    }
    val st = remember { PlanetMotion() }
    Canvas(modifier) {
        val t = if (reduced) 0f else clock.seconds
        val l = when {
            d == null -> 0f
            reduced -> (step + 0.5f) / 4f
            else -> d.level()
        }
        val c = Offset(size.width / 2f, size.height / 2f)
        val u = 1.dp.toPx()
        val opening = voice.state == DictationState.Opening
        // The turn so far: its rate changes (opening, armed), so it's integrated, never computed from absolute time.
        if (!reduced) {
            if (!st.last.isNaN()) {
                val rate = if (voice.armed) 0f else if (opening) 0.4f else 1f
                st.turn += (t - st.last).coerceIn(0f, 0.2f) * rate * 2f * PI.toFloat() / 3f
            }
            st.last = t
        }
        // Echoes: a syllable's onset (the level up 0.22 within 80 ms, above 0.3).
        if (!reduced && d != null) st.hear(t, l)
        // The glow behind (its gradient made again only while the colour eases).
        drawCircle(st.glow(tone, c, 18f * u), 18f * u, c, alpha = 0.10f + 0.18f * l)
        val fixed = reduced
        val r1 = 11f * u * (if (fixed) 1f else 1f + 0.18f * l) * gather
        val r2 = 15f * u * (if (fixed) 1f else 1f + 0.32f * l) * gather
        val a1 = 0.35f + 0.45f * l
        val a2 = 0.18f + 0.40f * l
        val ring = Stroke(1.1f * u)
        // Back halves, the planet, then the front halves: the rings pass in front of it.
        rings(c, r1, r2, a1, a2, tone, ring, back = true)
        if (!reduced) for (k in 0 until 3) {
            val age = t - st.echoes[k]
            if (age < 0f || age > 0.7f) continue
            val p = age / 0.7f
            val r = (15f + 7f * p) * u * gather
            rotate(-18f, c) { drawOval(tone, Offset(c.x - r, c.y - r * 0.34f), Size(2f * r, 2f * r * 0.34f), alpha = 0.45f * (1f - p), style = ring) }
        }
        planetSphere(c, 6.5f * u, tone, st.turn, stroke = 0.8f * u)
        rings(c, r1, r2, a1, a2, tone, ring, back = false)
    }
}

private fun DrawScope.rings(c: Offset, r1: Float, r2: Float, a1: Float, a2: Float, tone: Color, stroke: Stroke, back: Boolean) {
    rotate(-18f, c) {
        halfRing(c, r1, a1, tone, stroke, back)
        halfRing(c, r2, a2, tone, stroke, back)
    }
}

/** The far half is the top of the ellipse (seen from a little above), the near half its bottom. */
private fun DrawScope.halfRing(c: Offset, r: Float, a: Float, tone: Color, stroke: Stroke, back: Boolean) {
    if (r <= 0.5f) return
    drawArc(tone, if (back) 180f else 0f, 180f, false, Offset(c.x - r, c.y - r * 0.34f), Size(2f * r, 2f * r * 0.34f), alpha = a.coerceIn(0f, 1f), style = stroke)
}

/** The planet's turn and its three echo slots; plain fields, so a frame allocates nothing. */
private class PlanetMotion {
    var turn = 0.6f
    var last = Float.NaN
    val echoes = floatArrayOf(-9f, -9f, -9f)
    private val times = FloatArray(6) { -9f }
    private val levels = FloatArray(6)
    private var at = 0
    private var lastEcho = -9f
    private var glowBrush: Brush? = null
    private var glowKey = 0L

    fun glow(tone: Color, c: Offset, r: Float): Brush {
        val key = tone.value.toLong() * 31 + r.toBits()
        val b = glowBrush
        if (b != null && key == glowKey) return b
        glowKey = key
        return Brush.radialGradient(listOf(tone, Color.Transparent), c, r).also { glowBrush = it }
    }

    fun hear(t: Float, l: Float) {
        times[at] = t
        levels[at] = l
        at = (at + 1) % times.size
        var low = l
        for (i in times.indices) if (t - times[i] <= 0.08f && levels[i] < low) low = levels[i]
        if (l > 0.3f && l - low > 0.22f && t - lastEcho > 0.18f) {
            lastEcho = t
            // The oldest slot goes.
            var k = 0
            for (i in 1 until 3) if (echoes[i] < echoes[k]) k = i
            echoes[k] = t
        }
    }
}
