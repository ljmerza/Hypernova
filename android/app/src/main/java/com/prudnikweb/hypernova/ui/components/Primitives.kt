package com.prudnikweb.hypernova.ui.components

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI
import android.content.ClipboardManager
import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.prudnikweb.hypernova.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.fx.LocalAmbient
import com.prudnikweb.hypernova.ui.fx.Pace
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import android.graphics.BlurMaskFilter
import kotlin.math.abs
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.data.MacAwake
import com.prudnikweb.hypernova.data.MacBattery
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

// ─── Seam ──────────────────────────────────────────────────────────────────

enum class Activity { Rest, Live, Alert }

/**
 * Hyperscribe's seam: the activity meter (a small spinning planet), a tracked
 * uppercase label, a hairline that fills the row, and an optional trail. The
 * planet is still at rest, spins while a session works, and holds amber when
 * it needs you. [after] follows the label in mono (a timer). [trailContent], when given, is drawn
 * in the trail's place (a session card's id and age, as quiet marks).
 */
@Composable
fun Seam(
    label: String,
    modifier: Modifier = Modifier,
    trail: String? = null,
    activity: Activity = Activity.Rest,
    color: Color = if (activity == Activity.Alert) HN.amber else HN.inkMuted,
    after: String? = null,
    bars: Boolean = true,
    line: Boolean = true,
    /** Claude is thinking: the label breathes between the accent's greens (ThinkingText). */
    thinking: Boolean = false,
    trailContent: (@Composable () -> Unit)? = null,
) {
    // The label's colour eases with the planet's when the state changes.
    val labelColor by animateColorAsState(color, tween(900), label = "seam")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (bars) {
            Planet(activity)
            Spacer(Modifier.width(10.dp))
        }
        if (thinking) ThinkingText(label.uppercase(), live = true, style = Type.label)
        else HText(label.uppercase(), style = Type.label, color = labelColor, maxLines = 1)
        if (after != null) {
            Spacer(Modifier.width(8.dp))
            HText(after, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        if (line) Box(Modifier.weight(1f).height(1.dp).background(HN.line)) else Spacer(Modifier.weight(1f))
        if (trailContent != null) {
            Spacer(Modifier.width(10.dp))
            trailContent()
        } else if (trail != null) {
            Spacer(Modifier.width(10.dp))
            HText(trail, style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.5f), color = HN.inkDim, maxLines = 1)
        }
    }
}

/**
 * The activity meter: a small planet spinning on its axis, lit from the upper left. Live:
 * faint meridians slide across its face (one turn in 3 s) and fade toward the shadowed side.
 * Rest: still and dim. Alert: amber and still, pulsing gently. Read only while drawing, on the
 * shared ambient clock, so a spinning meter redraws 12dp and nothing else.
 */
@Composable
fun Planet(activity: Activity, modifier: Modifier = Modifier) {
    val reduced = LocalMotion.current.reduced
    val clock = LocalAmbient.current
    // Between states it eases: finishing, it slows to a stop and greys out over a second or so;
    // starting, it brightens and spins up.
    val color by animateColorAsState(
        when (activity) {
            Activity.Rest -> HN.inkDim
            Activity.Live -> HN.accent
            Activity.Alert -> HN.amber
        },
        tween(1000), label = "planet",
    )
    val live = activity == Activity.Live && !reduced
    val speed = animateFloatAsState(if (live) 1f else 0f, tween(if (live) 700 else 1400, easing = Motion.Expo), label = "spin")
    val alert = animateFloatAsState(if (activity == Activity.Alert && !reduced) 1f else 0f, tween(700), label = "alert")
    // The turn so far and when it was last drawn: the angle advances by the current speed.
    val turn = remember { floatArrayOf(0.6f, Float.NaN) }
    Canvas(modifier.graphicsLayer().size(12.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 0.6.dp.toPx()
        val v = speed.value
        val a = alert.value
        // Reads the clock only while it moves, so a still planet doesn't redraw. On the calm pace:
        // a 12dp planet turning once in 3 s looks the same at 15 frames a second, and a chat with
        // agents at work shows several of these for hours.
        if (v > 0f || a > 0f) {
            val now = clock.seconds(Pace.Calm)
            if (!turn[1].isNaN()) turn[0] += (now - turn[1]).coerceIn(0f, 0.2f) * v * 2f * PI.toFloat() / 3f
            turn[1] = now
        } else turn[1] = Float.NaN
        val pulse = if (a > 0f) 1f - a * 0.45f * (1f - clock.pulse(1.4f, pace = Pace.Calm)) else 1f
        planetSphere(c, r, color, turn[0], alpha = pulse, stroke = 0.7.dp.toPx())
    }
}

/** Up and to the left: where the light comes from on a planet with no sun in the picture. */
val PlanetLight = Offset(-0.7071f, -0.7071f)

/**
 * A small planet (the activity meter, the logo, a project in the home sky): a sphere lit from
 * [light] (a unit direction toward the light), a rim, and meridians that slide across its face
 * as [spin] (radians) turns. Longitude λ projects to a half ellipse r·sin λ wide, seen while
 * cos λ > 0, fainter at the limb and on the shadowed side.
 */
fun DrawScope.planetSphere(center: Offset, r: Float, color: Color, spin: Float, light: Offset = PlanetLight, alpha: Float = 1f, stroke: Float = r * 0.13f, meridians: Boolean = true) {
    val lit = Offset(center.x + light.x * r * 0.64f, center.y + light.y * r * 0.64f)
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = 0.75f * alpha), color.copy(alpha = 0.22f * alpha), color.copy(alpha = 0.04f * alpha)), center = lit, radius = r * 1.7f),
        r, center,
    )
    if (meridians) {
        val st = Stroke(stroke)
        for (k in 0 until 4) {
            val lon = spin + k * PI.toFloat() / 2f
            val depth = cos(lon)
            if (depth <= 0.05f) continue
            val half = r * sin(lon)
            val w = kotlin.math.abs(half)
            val a = depth * (0.55f + 0.495f * (half / r) * light.x).coerceIn(0.1f, 0.9f) * alpha
            drawArc(
                color.copy(alpha = a), if (half >= 0f) -90f else 90f, 180f, false,
                topLeft = Offset(center.x - w, center.y - r), size = Size((2 * w).coerceAtLeast(0.5f), 2 * r), style = st,
            )
        }
    }
    drawCircle(color.copy(alpha = 0.45f * alpha), r, center, style = Stroke(stroke))
}

// ─── Surfaces ──────────────────────────────────────────────────────────────

// Denser than 0stems' 22% fills: there, a 40px backdrop blur hides the stars behind a card;
// here, opacity does that job so star labels never sit behind card text.
private val CardFill = Brush.verticalGradient(listOf(HN.bg800.copy(alpha = 0.84f), HN.bg900.copy(alpha = 0.78f), HN.bg900.copy(alpha = 0.86f)))
// Shared with a table's card (MarkdownTable.kt), so the two top edges catch the same light.
internal val TopHighlight = Brush.horizontalGradient(listOf(Color.Transparent, HN.ink.copy(alpha = 0.25f), Color.Transparent))
private val BottomHighlight = Brush.horizontalGradient(listOf(Color.Transparent, HN.ink.copy(alpha = 0.12f), Color.Transparent))

/**
 * 0stems Card: rounded 16, a dark translucent gradient, a 1dp inset ring at
 * ink 8%, and 1dp highlight lines along the top (ink 25%) and bottom (ink 12%).
 * No per-card blur: the cosmos behind is sparse, and a dozen live blurs would
 * cost more than they show.
 *
 * [accent] (or [edge]) marks a card that needs a look (owner, 2026-10-18: colour only as a thin
 * accent, never a tinted card): a thin edge of light down its left side, a soft glow beside it,
 * and the ring a little warmer. [accent] is kept for the cards that used to be tinted; the two
 * mean the same.
 *
 * [onLongClick] (with [onClick]): a long-press does it too, with a haptic tick; TalkBack offers it
 * as the card's long-press action, [onLongClickLabel].
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    shape: Shape = RoundedCornerShape(16.dp),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    edge: Color? = accent,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val view = LocalView.current
    val ring = edge?.copy(alpha = 0.12f) ?: HN.ink.copy(alpha = 0.08f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(Motion.FAST), label = "press")
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(CardFill)
            .then(if (edge != null) Modifier.accentEdge(edge) else Modifier)
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                drawLine(TopHighlight, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), strokeWidth = stroke)
                drawLine(BottomHighlight, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), strokeWidth = stroke)
            }
            .border(1.dp, ring, shape)
            .then(
                if (onClick != null && onLongClick != null) {
                    Modifier.combinedClickable(
                        interaction, indication = null, onClickLabel = onClickLabel, role = Role.Button,
                        onLongClickLabel = onLongClickLabel,
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onLongClick()
                        },
                        onClick = onClick,
                    )
                } else if (onClick != null) {
                    Modifier.clickable(interaction, indication = null, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                } else Modifier,
            ),
        content = content,
    )
}

/**
 * A card's thin accent: a 2dp line of [color] down its left (start) edge, brightest in the middle
 * and fading toward the corners, with a soft glow of the same colour beside it inside the card.
 */
internal fun Modifier.accentEdge(color: Color): Modifier = drawWithCache {
    val w = 2.dp.toPx()
    val glowW = 40.dp.toPx()
    val rtl = layoutDirection == LayoutDirection.Rtl
    val x = if (rtl) size.width - w / 2f else w / 2f
    val line = Brush.verticalGradient(0f to color.copy(alpha = 0.15f), 0.22f to color.copy(alpha = 0.85f), 0.78f to color.copy(alpha = 0.85f), 1f to color.copy(alpha = 0.15f))
    val glow = if (rtl) Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = 0.09f)), startX = size.width - glowW, endX = size.width)
    else Brush.horizontalGradient(listOf(color.copy(alpha = 0.09f), Color.Transparent), startX = 0f, endX = glowW)
    onDrawBehind {
        drawRect(glow, topLeft = Offset(if (rtl) size.width - glowW else 0f, 0f), size = Size(glowW, size.height))
        drawLine(line, Offset(x, 0f), Offset(x, size.height), strokeWidth = w)
    }
}

// ─── Buttons ───────────────────────────────────────────────────────────────

enum class PillKind { Primary, Outline, Ghost, Danger, Glass }

/**
 * Pill button (Hyperscribe shape, 0stems colours). Light haptic tick on press. [PillKind.Glass] is
 * the floating action over the sky (New session, New automation): frosted glass like the state
 * chips, its glyph a mint point of light, the label in ink; a solid mint pill there looked like a
 * stock Material button, not part of the sky (owner, 2026-10-16).
 *
 * [enabled] false: drawn faded, a tap does nothing, and TalkBack says it's disabled (so a second
 * try is never silently lost); say why next to it where the reason isn't plain.
 */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PillKind = PillKind.Outline,
    height: Dp = 44.dp,
    leading: Glyph? = null,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Every kind is frosted glass, as the state chips are; they differ in the light inside it.
    // Primary: a faint mint body, a mint edge light and a mint glow at the glyph, so it still
    // leads without being a solid slab. Danger the same in red. Outline and Ghost are neutral,
    // Ghost fainter (no dense body) and muted.
    val fg = when (kind) {
        PillKind.Primary -> HN.accent
        PillKind.Outline, PillKind.Glass -> HN.ink
        PillKind.Ghost -> HN.inkMuted
        PillKind.Danger -> HN.danger
    }
    val tint = when (kind) { PillKind.Primary -> HN.accent; PillKind.Danger -> HN.danger; else -> null }
    val glow = when (kind) { PillKind.Primary, PillKind.Glass -> HN.accent; PillKind.Danger -> HN.danger; else -> null }
    val glass = kind != PillKind.Ghost
    val dy by animateDpAsState(if (pressed) 1.dp else 0.dp, tween(Motion.FAST), label = "dy")
    // The touch target is at least 48dp tall even when the pill is drawn smaller.
    val touchPad = ((48.dp - height) / 2).coerceAtLeast(0.dp)
    Box(
        modifier
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .padding(vertical = touchPad),
        propagateMinConstraints = true,
    ) {
    Box(
        Modifier
            .graphicsLayer { translationY = dy.toPx(); alpha = if (!enabled) 0.45f else if (pressed) 0.8f else 1f }
            .height(height)
            .clip(CircleShape)
            // The glyph's centre: the padding plus half its 14dp.
            .frostedGlass(if (leading != null || kind == PillKind.Glass) glow else null, glowX = 27.dp, dense = glass, tint = tint)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Icon(leading, size = 14.dp, color = if (kind == PillKind.Glass) HN.accent else fg)
                Spacer(Modifier.width(8.dp))
            }
            HText(text, style = Type.small.copy(fontWeight = FontWeight(600)), color = fg, maxLines = 1)
        }
    }
    }
}

// ─── Status ────────────────────────────────────────────────────────────────

/**
 * A state chip (WORKING, NEEDS YOU, 2 AGENTS…): a capsule of frosted glass with no outline, its
 * top edge catching the light, and the state's colour carried by a point of light and the label
 * instead of a ring. The point is the session's moon as the sky draws it: live, it breathes and
 * sends out a slow ring; still, it holds, with a soft light around it inside the glass (none for
 * the greys: idle and ended have no light of their own). [filled] is the one solid element (needs
 * you): amber glass lit from above, a soft glow around it, and a dark target for its point.
 * [hollow] draws the point as a ring, for a state that's over (ended), so it reads apart from idle
 * in the same grey. Only a live point reads the shared clock, in its own small layer, so a still
 * chip never redraws. [glyph], when given, stands in the point's place (a Mac restart's resume
 * arrow), in the chip's colour.
 */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, live: Boolean = false, filled: Boolean = false, hollow: Boolean = false, glyph: Glyph? = null) {
    val lit = color != HN.inkDim && color != HN.inkMuted && color != HN.inkGhost
    Row(
        modifier
            .then(if (filled) Modifier.filledGlass(color) else Modifier.frostedGlass(if (lit) color else null, glowX = 11.dp))
            // Grows with the font size instead of clipping the label.
            .heightIn(min = 22.dp)
            .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) { Icon(glyph, size = 10.dp, color = color, stroke = 1.2.dp) }
        else StateLight(color, live = live && !filled, filled = filled, lit = lit, hollow = hollow && !filled)
        Spacer(Modifier.width(5.dp))
        HText(text.uppercase(), style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.75f), color = if (filled) HN.bg else color, maxLines = 1)
    }
}

/**
 * The chip's point of light, 10dp. Live: a core that breathes and a ring that grows from it and
 * fades every 2.4 s, as a working session's moon does in the sky; it eases in and out (600ms), so
 * a session that finishes doesn't snap its ring away. Filled: a dark dot in a dark ring. Hollow: a
 * ring the dot's size, with nothing inside.
 */
@Composable
private fun StateLight(color: Color, live: Boolean, filled: Boolean, lit: Boolean, hollow: Boolean = false) {
    val clock = LocalAmbient.current
    val on = animateFloatAsState(if (live && !LocalMotion.current.reduced) 1f else 0f, tween(600), label = "light")
    Spacer(
        Modifier
            .graphicsLayer()
            .size(10.dp)
            .drawWithCache {
                val u = 1.dp.toPx()
                val mid = Offset(size.width / 2f, size.height / 2f)
                val halo = Brush.radialGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0f)), mid, 5f * u)
                val ring = Stroke(0.8f * u)
                val target = Stroke(0.9f * u)
                onDrawBehind {
                    if (filled) {
                        drawCircle(HN.bg, 1.9f * u, center)
                        drawCircle(HN.bg.copy(alpha = 0.85f), 3.6f * u, center, style = target)
                        return@onDrawBehind
                    }
                    if (hollow) {
                        // As wide as the dot (2.1dp), its stroke inside that edge.
                        drawCircle(color, 1.65f * u, center, style = target)
                        return@onDrawBehind
                    }
                    val k = on.value
                    // Read only while live, so a still point never redraws; on the calm pace, as
                    // its ring grows a dp a second.
                    if (k > 0f) {
                        val t = clock.seconds(Pace.Calm)
                        val p = (t / 2.4f) % 1f
                        drawCircle(color, (2.4f + 2.8f * p) * u, center, alpha = 0.55f * (1f - p) * k, style = ring)
                        val breathe = 0.5f + 0.5f * cos(2f * PI.toFloat() * t / 2.4f)
                        drawCircle(halo, 5f * u, center, alpha = (if (lit) 0.65f else 0f) * (1f - k) + (0.35f + 0.55f * breathe) * k)
                    } else if (lit) {
                        drawCircle(halo, 5f * u, center, alpha = 0.65f)
                    }
                    drawCircle(color, 2.1f * u, center)
                }
            },
    )
}

/**
 * Frosted glass for a small capsule: a body a little lighter than what's under it, a light along
 * its top edge that fades out by the middle (no outline), and [glow], when given, as a soft light
 * inside the glass around [glowX], where the chip's glyph sits (from the start edge, so it follows
 * the glyph in a right-to-left layout). [dense] is for glass over the sky rather than on a card, so
 * the text keeps its contrast whatever drifts behind it. Shared with the transcript's chips.
 */
internal fun Modifier.frostedGlass(glow: Color?, glowX: Dp, dense: Boolean = false, tint: Color? = null, radius: Dp? = null): Modifier = frostedGlass({ glow }, glowX, dense, tint, radius)

/**
 * Glass for a choice (a mode chip, a day, a tile): neutral when off, washed and edge-lit in [color]
 * when on, with no outline either way. [radius] null is a capsule. The shared look for toggles and
 * pickers, so they match the buttons.
 */
internal fun Modifier.glassChoice(on: Boolean, radius: Dp? = null, color: Color = HN.accent): Modifier =
    frostedGlass(null, glowX = 0.dp, dense = true, tint = if (on) color else null, radius = radius)

/**
 * As above, with a glow that can ease: [glow] is read while the cache is built, so as it changes
 * only the cache is rebuilt, with no recomposition. Its alpha scales the light.
 */
private fun Modifier.frostedGlass(glow: () -> Color?, glowX: Dp, dense: Boolean = false, tint: Color? = null, radius: Dp? = null): Modifier = drawWithCache {
    val h = size.height
    val r = radius?.toPx() ?: (h / 2f)
    val corner = CornerRadius(r)
    val body = if (dense) {
        Brush.verticalGradient(listOf(lerp(HN.bg800, HN.ink, 0.07f).copy(alpha = 0.80f), HN.bg900.copy(alpha = 0.74f)))
    } else {
        Brush.verticalGradient(listOf(HN.ink.copy(alpha = 0.09f), HN.ink.copy(alpha = 0.04f)))
    }
    val gx = if (layoutDirection == LayoutDirection.Rtl) size.width - glowX.toPx() else glowX.toPx()
    val light = glow()?.takeIf { it.alpha > 0.01f }?.let {
        Brush.radialGradient(listOf(it.copy(alpha = (if (dense) 0.15f else 0.18f) * it.alpha), it.copy(alpha = 0.05f * it.alpha), Color.Transparent), center = Offset(gx, h / 2f), radius = h * 1.2f)
    }
    val sw = 1.dp.toPx()
    // A tint (a button's meaning) lays a faint colour over the body and lights the top edge in it.
    val wash = tint?.let { Brush.verticalGradient(listOf(it.copy(alpha = 0.16f), it.copy(alpha = 0.06f))) }
    val edgeC = tint ?: HN.ink
    val edge = Brush.verticalGradient(0f to edgeC.copy(alpha = if (tint != null) 0.55f else 0.22f), 0.5f to edgeC.copy(alpha = 0f), 1f to edgeC.copy(alpha = 0.05f))
    val edgeAt = Offset(sw / 2f, sw / 2f)
    val edgeSize = Size(size.width - sw, h - sw)
    val edgeCorner = CornerRadius(r - sw / 2f)
    val edgeStroke = Stroke(sw)
    onDrawBehind {
        drawRoundRect(body, cornerRadius = corner)
        if (wash != null) drawRoundRect(wash, cornerRadius = corner)
        if (light != null) drawRoundRect(light, cornerRadius = corner)
        drawRoundRect(edge, topLeft = edgeAt, size = edgeSize, cornerRadius = edgeCorner, style = edgeStroke)
    }
}

/**
 * The one filled element: [color] glass lit from above, darker at its foot, with a soft glow around
 * it. The glow is the capsule's own shape blurred, so it fades to nothing a few dp out and has no
 * edge of its own (a gradient cut to a larger pill read as a second capsule behind the chip).
 */
private fun Modifier.filledGlass(color: Color): Modifier = drawWithCache {
    val h = size.height
    val corner = CornerRadius(h / 2f)
    val body = Brush.verticalGradient(listOf(lerp(color, Color.White, 0.16f), color, lerp(color, Color.Black, 0.10f)))
    val sw = 1.dp.toPx()
    val edge = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.45f), 0.5f to Color.White.copy(alpha = 0f), 1f to Color.Black.copy(alpha = 0.12f))
    val halo = Paint().apply {
        this.color = color.copy(alpha = 0.4f)
        // Drawn by the GPU since Android 9; the app needs 10.
        asFrameworkPaint().maskFilter = BlurMaskFilter(5.dp.toPx(), BlurMaskFilter.Blur.OUTER)
    }
    onDrawBehind {
        drawIntoCanvas { it.drawRoundRect(0f, 0f, size.width, h, h / 2f, h / 2f, halo) }
        drawRoundRect(body, cornerRadius = corner)
        drawRoundRect(edge, topLeft = Offset(sw / 2f, sw / 2f), size = Size(size.width - sw, h - sw), cornerRadius = CornerRadius(h / 2f - sw / 2f), style = Stroke(sw))
    }
}

/**
 * The link to the Mac, as a quiet glass capsule: the Mac is a small planet and this phone its moon.
 * - Connected: the moon rests on its orbit, the planet lit mint, and the capsule names the Mac
 *   ([name]; "Connected" when there's none, as in Settings, where the name is the heading).
 * - Connecting, reconnecting: once the search has lasted 0.4 s, the moon circles, looking for the Mac
 *   (one turn in 1.8 s, spinning up over 0.6 s), so the blink of a reconnect when the app comes back
 *   leaves it still. Once the Mac is found, the moon comes to rest on the near side, never behind the
 *   planet: within 1.2 s, or, if it has to swing round to the front, in up to 1.7 s, never faster
 *   than it circles.
 * - Offline: a dark disc slides over the planet, leaving a thin red edge (eclipsed), and the moon goes.
 * - Unpaired: an empty outline. Demo: the planet and moon in grey.
 * - [battery]: a MacBook's charge after its name while linked, quietly, drawn: a small battery
 *   filled to its level and the percentage ("MacBook Air ▭ 62%", a small mint bolt while it
 *   charges); amber under 20% on battery, red under 10%. Never cut: a long name gives way first. A
 *   Mac without a battery (null) shows nothing about power.
 * - [via]: the route as a glyph at the end: Wi-Fi's fan on the Mac's own network, a globe over the
 *   internet (the paired base URL as it is). No "· Local".
 * - [awake]: a small crescent at the end while the Mac is kept awake with its lid closed (Stay awake,
 *   in a bag most likely), amber while it's about to be let sleep.
 * The planet's colour and the light in the glass ease together. The glyph reads the clock only while
 * the moon moves, so a steady link costs nothing; under Reduce Motion the moon stays at rest.
 * TalkBack hears one line: "Connected to Studio", "Reconnecting", "Connected to MacBook Air,
 * battery 62%, charging".
 */
@Composable
fun ConnectionChip(
    state: LinkState,
    modifier: Modifier = Modifier,
    name: String? = null,
    battery: MacBattery? = null,
    awake: MacAwake? = null,
    via: String? = null,
    /** Several computers are paired: the name shows in every state (the moon and its colour say the state). */
    named: Boolean = false,
    /** Sessions on the other paired computers that need the owner: a small amber count at the end. */
    elsewhere: Int = 0,
) {
    // Once linked, the Mac's name says it all; otherwise, the state in a word (with several paired,
    // the name still, so it's clear which one isn't linked).
    val mac = name?.takeIf { (state == LinkState.Connected || (named && state != LinkState.Demo)) && it.isNotBlank() }
    // The route, in a word, only while linked: "Local" on the Mac's own network, "Remote" otherwise.
    val route = via?.takeIf { state == LinkState.Connected }?.let { routeWord(it) }
    val word = when (state) {
        LinkState.Connected -> stringResource(R.string.link_connected)
        LinkState.Connecting -> stringResource(R.string.link_connecting)
        LinkState.Reconnecting -> stringResource(R.string.link_reconnecting)
        LinkState.Offline -> stringResource(R.string.link_offline)
        LinkState.Unpaired -> stringResource(R.string.link_unpaired)
        LinkState.Demo -> stringResource(R.string.link_demo)
    }
    // Only what's current: a Mac that isn't linked may have been plugged in or run down since.
    val power = battery?.takeIf { state == LinkState.Connected || state == LinkState.Demo }
    val base = when {
        mac == null -> word
        state == LinkState.Connected -> stringResource(R.string.link_connected_to, mac)
        // Named while not linked (several paired): "z2, Offline".
        else -> stringResource(R.string.link_said_named, mac, word)
    }
    val powerSaid = when {
        power == null -> base
        power.charging -> stringResource(R.string.link_said_charging, base, power.percent)
        else -> stringResource(R.string.link_said_battery, base, power.percent)
    }
    // Only while linked, as the battery: the crescent says how the Mac is now.
    val moon = awake?.takeIf { state == LinkState.Connected || state == LinkState.Demo }?.let {
        when {
            it.sleepAt != null -> HN.amber
            it.awakeLidClosed -> HN.accentDim
            else -> null
        }
    }
    val routeSaid = if (route != null) stringResource(R.string.link_said_via, powerSaid, route) else powerSaid
    val awakeSaid = when {
        moon == null -> routeSaid
        awake?.sleepAt != null -> stringResource(R.string.link_said_sleep_soon, routeSaid)
        else -> stringResource(R.string.link_said_awake_lid, routeSaid)
    }
    val said = if (elsewhere > 0) "$awakeSaid. " + pluralStringResource(R.plurals.servers_elsewhere, elsewhere, elsewhere) else awakeSaid
    val target = linkTone(state)
    // Read while drawing, so the 800ms ease redraws the chip without recomposing it. The greys have
    // no light of their own, so the glass's fades out to them.
    val tone = animateColorAsState(target, tween(800), label = "link")
    val glow = animateColorAsState(if (target == HN.inkMuted || target == HN.inkDim) target.copy(alpha = 0f) else target, tween(800), label = "linkGlow")
    val textColor = when {
        mac != null && state == LinkState.Connected -> HN.inkMuted
        // Named while not linked: in the state's own colour, as its word would be.
        mac != null -> if (state == LinkState.Connecting) HN.inkMuted else target
        state == LinkState.Connected -> HN.accent
        state == LinkState.Connecting || state == LinkState.Demo -> HN.inkMuted
        else -> target
    }
    Row(
        modifier
            .clearAndSetSemantics { contentDescription = said }
            .frostedGlass({ glow.value }, glowX = 15.dp, dense = true)
            .heightIn(min = 30.dp)
            .padding(start = 5.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MacMoon(state, tone)
        Spacer(Modifier.width(6.dp))
        // Measured after the battery (a weight), so a long name is cut rather than the percentage.
        HText(mac ?: word, style = Type.small, color = textColor, maxLines = 1, modifier = Modifier.weight(1f, fill = false).widthIn(max = 160.dp))
        if (power != null) {
            val ink = when {
                power.critical -> HN.danger
                power.low -> HN.amber
                else -> HN.inkDim
            }
            Spacer(Modifier.width(7.dp))
            BatteryMark(power.percent, ink)
            Spacer(Modifier.width(4.dp))
            HText(stringResource(R.string.link_battery, power.percent), style = Type.small, color = ink, maxLines = 1)
            if (power.charging) {
                Spacer(Modifier.width(3.dp))
                Bolt(HN.accent.copy(alpha = 0.85f))
            }
        }
        if (route != null && via != null) {
            Spacer(Modifier.width(7.dp))
            Icon(routeGlyph(via), size = 12.dp, color = HN.inkDim, stroke = 1.2.dp)
        }
        if (moon != null) {
            Spacer(Modifier.width(5.dp))
            Crescent(moon)
        }
        if (elsewhere > 0) {
            Spacer(Modifier.width(7.dp))
            ElsewhereCount(elsewhere)
        }
    }
}

/**
 * The connection chip's last mark while another paired computer needs the owner: the count in a
 * small amber disc (a dot's size for one digit), "9+" past nine.
 */
@Composable
private fun ElsewhereCount(n: Int) {
    Box(
        Modifier
            .heightIn(min = 16.dp)
            .widthIn(min = 16.dp)
            .clip(CircleShape)
            .background(HN.amber)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        HText(if (n > 9) "9+" else n.toString(), style = Type.label, color = HN.bg, maxLines = 1)
    }
}

/** [Mac.via] in a word: "Local" or "Remote" (anything else, such as the demo's, says nothing). */
@Composable
fun routeWord(via: String): String? = when (via) {
    Mac.VIA_LOCAL -> stringResource(R.string.route_local)
    // A plain remote address (a public one, or over the owner's VPN).
    Mac.VIA_REMOTE -> stringResource(R.string.route_remote)
    else -> null
}

/** The route's glyph: Wi-Fi's fan on the Mac's own network, a globe over the internet. */
fun routeGlyph(via: String): Glyph = if (via == Mac.VIA_LOCAL) Glyph.Lan else Glyph.Globe

/**
 * A small battery, 15 × 8dp, in [color]: a hairline body with its nub, filled to [percent] (at
 * least a sliver, so an empty one still reads as a battery).
 */
@Composable
fun BatteryMark(percent: Int, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 15.dp, height = 8.dp)) {
        val u = 1.dp.toPx()
        val body = size.width - 2f * u
        drawRoundRect(color, topLeft = Offset(0.5f * u, 0.5f * u), size = androidx.compose.ui.geometry.Size(body - u, size.height - u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.6f * u), style = Stroke(u))
        drawRoundRect(color, topLeft = Offset(body, size.height * 0.32f), size = androidx.compose.ui.geometry.Size(1.4f * u, size.height * 0.36f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.6f * u))
        val inner = body - 4f * u
        val fill = (inner * percent.coerceIn(0, 100) / 100f).coerceAtLeast(u)
        drawRoundRect(color, topLeft = Offset(2f * u, 2f * u), size = androidx.compose.ui.geometry.Size(fill, size.height - 4f * u), cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.6f * u))
    }
}

/** A small crescent, 8dp: the Mac is kept awake with its lid closed. */
@Composable
private fun Crescent(color: Color) {
    Canvas(Modifier.size(8.dp)) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        // A disc with a smaller one taken out of its upper right.
        val moon = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
        val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.45f, c.y - r * 0.35f), r * 0.82f)) }
        drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, moon, bite), color)
    }
}

/** A small filled lightning bolt, 6 × 9dp: the Mac is charging. */
@Composable
fun Bolt(color: Color) {
    Canvas(Modifier.size(width = 6.dp, height = 9.dp)) {
        val w = size.width
        val h = size.height
        val bolt = Path().apply {
            moveTo(0.66f * w, 0f)
            lineTo(0.04f * w, 0.58f * h)
            lineTo(0.46f * w, 0.58f * h)
            lineTo(0.32f * w, h)
            lineTo(0.96f * w, 0.40f * h)
            lineTo(0.54f * w, 0.40f * h)
            close()
        }
        drawPath(bolt, color)
    }
}

private fun linkTone(state: LinkState): Color = when (state) {
    LinkState.Connected -> HN.accent
    LinkState.Connecting -> HN.inkMuted
    LinkState.Reconnecting -> HN.amber
    LinkState.Offline, LinkState.Unpaired -> HN.danger
    LinkState.Demo -> HN.inkDim
}

private const val MOON_REST = 0.55f
private val MOON_TURN = 2f * PI.toFloat() / 1.8f
private val TWO_PI = 2f * PI.toFloat()

// The near side of the orbit, in front of the planet and clear of its tips: where the moon may rest.
private const val NEAR_FROM = 0.2f
private const val NEAR_TO = 2.95f

// However fast or slow it was going, an even brake brings the moon to rest in this many seconds. A
// firmer stop at the near side's end can take as little as 0.3 s, and a swing round to the front
// takes at least the longer one.
private const val GLIDE_MIN = 0.5f
private const val GLIDE_MAX = 1.2f

// The app reconnects each time it comes back after a quiet spell, and that's usually over in a blink.
// The moon starts circling only once a search has lasted this long, so those blinks leave it still
// (each one used to nudge it a little further on, until one sent it all the way round).
private const val SEEK_AFTER_MS = 400L

/**
 * Where the phone's moon is on its orbit and how fast it goes; it lives across frames. Once the Mac
 * is found, [glide] brings it to rest on the near side within 1.2 s (a swing round to the front, up
 * to 1.7 s), whatever its speed: braking evenly from v over a span T covers only v·T/2, so a moon
 * barely moving after a short search would otherwise crawl for seconds to reach the front.
 */
private class MoonOrbit {
    var angle = MOON_REST
    var speed = 0f
    var last = Float.NaN

    // The glide to rest: from [from] at [v], [d] further on over [span] seconds, [t] of them gone.
    private var gliding = false
    private var from = 0f
    private var v = 0f
    private var d = 0f
    private var span = 1f
    private var t = 0f

    /** Looking for the Mac: spins up to a turn in 1.8 s over 0.6 s. */
    fun seek(dt: Float) {
        gliding = false
        speed = minOf(MOON_TURN, speed + MOON_TURN * dt / 0.6f)
        angle = (angle + speed * dt) % TWO_PI
    }

    /**
     * Found: one step of the glide to rest, planned on its first. A Hermite curve from the speed it
     * had to a standstill [d] further on, so it never jerks; when span = 2d/v it's an even brake.
     */
    fun glide(dt: Float) {
        if (!gliding) plan()
        t += dt
        val s = (t / span).coerceAtMost(1f)
        angle = from + span * v * (s * s * s - 2f * s * s + s) + d * (3f * s * s - 2f * s * s * s)
        if (s >= 1f) {
            speed = 0f
            gliding = false
            angle %= TWO_PI
        } else {
            // Kept above zero until the end, so the frames keep coming.
            speed = maxOf(v * (3f * s * s - 4f * s + 1f) + d / span * 6f * s * (1f - s), 1e-4f)
        }
    }

    /** Where the moon sits when nothing moves it (and always, under Reduce Motion). */
    fun rest() {
        angle = MOON_REST
        speed = 0f
        last = Float.NaN
        gliding = false
    }

    private fun plan() {
        val a = (angle % TWO_PI + TWO_PI) % TWO_PI
        val ideal = speed * 0.4f
        // An even brake from this speed over 0.5..1.2 s stops lo..hi ahead. Of the points there on
        // the near side, take the one nearest an 0.8 s brake.
        val lo = speed * GLIDE_MIN / 2f
        val hi = speed * GLIDE_MAX / 2f
        var best = Float.NaN
        for (lap in 0..1) {
            val x0 = maxOf(NEAR_FROM + lap * TWO_PI, a + lo)
            val x1 = minOf(NEAR_TO + lap * TWO_PI, a + hi)
            if (x0 > x1) continue
            val c = (a + ideal).coerceIn(x0, x1) - a
            if (best.isNaN() || abs(c - ideal) < abs(best - ideal)) best = c
        }
        when {
            !best.isNaN() -> {
                d = best
                span = if (speed > 1e-3f) (2f * d / speed).coerceIn(GLIDE_MIN, GLIDE_MAX) else GLIDE_MIN
            }
            // It would brake past the near side: stop at its end, a little harder. With span = 3d/v
            // its speed falls as (1-s)², still gentle at the end.
            a in NEAR_FROM..NEAR_TO && NEAR_TO - a >= speed * 0.1f -> {
                d = NEAR_TO - a
                span = minOf(3f * d / speed, GLIDE_MIN)
            }
            // Already past it: swing round to the front, never faster than it circles. The curve
            // peaks at 1.5·d/span midway, so a long swing takes longer (up to 1.7 s) rather than
            // racing behind the planet while the chip already names the Mac. The span stays under
            // 3d/v, so the moon never overshoots and comes back.
            else -> {
                d = ((NEAR_FROM - a) % TWO_PI + TWO_PI) % TWO_PI + 0.15f
                span = maxOf(GLIDE_MAX, 1.5f * d / MOON_TURN)
            }
        }
        from = a
        v = speed
        t = 0f
        gliding = true
    }
}

/**
 * The connection's glyph, 20dp: the Mac's planet, its orbit and this phone's moon (see
 * [ConnectionChip]), in [tone], which eases with the chip's glass.
 */
@Composable
private fun MacMoon(state: LinkState, tone: State<Color>) {
    val clock = LocalAmbient.current
    val reduced = LocalMotion.current.reduced
    val looking = (state == LinkState.Connecting || state == LinkState.Reconnecting) && !reduced
    // Starts circling only once the search has lasted a moment, and stops as soon as it ends. The
    // wait is a timer, not the clock, so waiting costs no frames of its own.
    val seek = produceState(false, looking) {
        if (looking) delay(SEEK_AFTER_MS)
        value = looking
    }
    val ring = animateFloatAsState(if (state == LinkState.Offline || state == LinkState.Unpaired) 0f else 1f, tween(700), label = "orbit")
    val eclipse = animateFloatAsState(if (state == LinkState.Offline) 1f else 0f, tween(if (reduced) 0 else 900, easing = Motion.Expo), label = "eclipse")
    val hollow = animateFloatAsState(if (state == LinkState.Unpaired) 1f else 0f, tween(700), label = "hollow")
    val orbit = remember { MoonOrbit() }
    Spacer(
        Modifier
            .graphicsLayer()
            .size(20.dp)
            .drawWithCache {
                val u = 1.dp.toPx()
                val mid = Offset(size.width / 2f, size.height / 2f)
                val pr = 4.6f * u
                val rx = 8.2f * u
                val ry = 3.0f * u
                val orbitAt = Offset(mid.x - rx, mid.y - ry)
                val orbitSize = Size(2 * rx, 2 * ry)
                val orbitStroke = Stroke(0.75f * u)
                val outline = Stroke(1f * u)
                // The eclipse only ever darkens the planet, never the glass around it.
                val face = Path().apply { addOval(Rect(mid, pr + 0.5f * u)) }
                onDrawBehind {
                    val o = orbit
                    // Reduce Motion freezes the clock, so a moon caught mid-turn (even behind the
                    // planet) would stay there: put it back where it rests.
                    if (reduced) o.rest()
                    // The moon moves while it looks for the Mac, and after, until it has come to rest.
                    val seeking = looking && seek.value
                    if (seeking || o.speed > 0f) {
                        val now = clock.seconds(Pace.Calm)
                        val dt = if (o.last.isNaN()) 0f else (now - o.last).coerceIn(0f, 0.1f)
                        o.last = now
                        if (seeking) o.seek(dt) else o.glide(dt)
                        if (!seeking && o.speed == 0f) o.last = Float.NaN
                    }
                    val c = tone.value
                    val a = ring.value
                    val th = o.angle
                    val front = sin(th) >= 0f
                    val moon = Offset(center.x + rx * cos(th), center.y + ry * sin(th))
                    val moonColor = if (state == LinkState.Connected) HN.accentBright else c
                    rotate(-16f, center) {
                        if (a > 0f) {
                            drawArc(c, 180f, 180f, false, orbitAt, orbitSize, alpha = 0.32f * a, style = orbitStroke)
                            if (!front) drawCircle(moonColor, 1.55f * u, moon, alpha = a)
                        }
                    }
                    // A dark disc behind the planet, so the orbit's far side doesn't show through it.
                    drawCircle(HN.bg900, pr, center, alpha = 0.9f * (1f - hollow.value))
                    planetSphere(center, pr, c, PI.toFloat() / 4f, alpha = 1f - hollow.value, stroke = 0.75f * u, meridians = eclipse.value < 0.5f)
                    if (hollow.value > 0f) drawCircle(c, pr, center, alpha = hollow.value, style = outline)
                    val e = eclipse.value
                    if (e > 0f) {
                        // The eclipse slides in from the upper left and stops just short of covering it.
                        val shift = 1.5f * u + (1f - e) * 2.2f * pr
                        clipPath(face) { drawCircle(HN.bg950, pr * 0.98f, Offset(center.x - shift * 0.7071f, center.y - shift * 0.7071f)) }
                    }
                    rotate(-16f, center) {
                        if (a > 0f) {
                            drawArc(c, 0f, 180f, false, orbitAt, orbitSize, alpha = 0.32f * a, style = orbitStroke)
                            if (front) drawCircle(moonColor, 1.55f * u, moon, alpha = a)
                        }
                    }
                }
            },
    )
}

/** The clipboard's text, if it holds any (reading it is what Paste asks for). */
fun clipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

// ─── Brand ─────────────────────────────────────────────────────────────────

/**
 * The Nova mark: the small planet of the activity meter, still, lit from the upper left. The
 * app icon is the same planet.
 */
@Composable
fun NovaMark(modifier: Modifier = Modifier, size: Dp = 22.dp, color: Color = HN.accent) {
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f - 0.6.dp.toPx()
        planetSphere(center, r, color, PI.toFloat() / 4f, stroke = (r * 0.11f).coerceAtLeast(0.7.dp.toPx()))
    }
}

/** Lowercase light wordmark with the accent slash, like `0stems/` and `prudnikov/`. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    val appName = stringResource(R.string.app_name)
    HText(
        buildAnnotatedString {
            withStyle(SpanStyle(color = HN.inkMuted)) { append("hypernova") }
            withStyle(SpanStyle(color = HN.accent)) { append("/") }
        },
        modifier = modifier.semantics { contentDescription = appName },
        style = Type.wordmark,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = HN.line) {
    Box(modifier.height(1.dp).background(color))
}

/** Round icon button: 48dp touch target, 40dp visual, labelled for TalkBack. */
@Composable
fun GlyphButton(glyph: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = HN.ink) {
    Box(
        modifier
            .size(48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .frostedGlass(null, glowX = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, size = 16.dp, color = color)
        }
    }
}
