package com.prudnikweb.hypernova.ui.components

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
 * it needs you. [after] follows the label in mono (a timer).
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
) {
    // The label's colour eases with the planet's when the state changes.
    val labelColor by animateColorAsState(color, tween(900), label = "seam")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (bars) {
            Planet(activity)
            Spacer(Modifier.width(10.dp))
        }
        HText(label.uppercase(), style = Type.label, color = labelColor, maxLines = 1)
        if (after != null) {
            Spacer(Modifier.width(8.dp))
            HText(after, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        if (line) Box(Modifier.weight(1f).height(1.dp).background(HN.line)) else Spacer(Modifier.weight(1f))
        if (trail != null) {
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
        // Reads the clock only while it moves, so a still planet doesn't redraw.
        if (v > 0f || a > 0f) {
            val now = clock.seconds
            if (!turn[1].isNaN()) turn[0] += (now - turn[1]).coerceIn(0f, 0.2f) * v * 2f * PI.toFloat() / 3f
            turn[1] = now
        } else turn[1] = Float.NaN
        val pulse = if (a > 0f) 1f - a * 0.45f * (1f - clock.pulse(1.4f)) else 1f
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
private val TopHighlight = Brush.horizontalGradient(listOf(Color.Transparent, HN.ink.copy(alpha = 0.25f), Color.Transparent))
private val BottomHighlight = Brush.horizontalGradient(listOf(Color.Transparent, HN.ink.copy(alpha = 0.12f), Color.Transparent))

/**
 * 0stems Card: rounded 16, a dark translucent gradient, a 1dp inset ring at
 * ink 8%, and 1dp highlight lines along the top (ink 25%) and bottom (ink 12%).
 * No per-card blur: the cosmos behind is sparse, and a dozen live blurs would
 * cost more than they show.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    shape: Shape = RoundedCornerShape(16.dp),
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ring = accent?.copy(alpha = 0.35f) ?: HN.ink.copy(alpha = 0.08f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.985f else 1f, tween(Motion.FAST), label = "press")
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(CardFill)
            .then(if (accent != null) Modifier.background(accent.copy(alpha = 0.05f)) else Modifier)
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                drawLine(TopHighlight, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), strokeWidth = stroke)
                drawLine(BottomHighlight, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), strokeWidth = stroke)
            }
            .border(1.dp, ring, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(interaction, indication = null, onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
                } else Modifier,
            ),
        content = content,
    )
}

// ─── Buttons ───────────────────────────────────────────────────────────────

enum class PillKind { Primary, Outline, Ghost, Danger }

/** Pill button (Hyperscribe shape, 0stems colours). Light haptic tick on press. */
@Composable
fun Pill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PillKind = PillKind.Outline,
    height: Dp = 44.dp,
    leading: Glyph? = null,
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val (bg, fg, border) = when (kind) {
        PillKind.Primary -> Triple(HN.accent, HN.bg, HN.accentBright.copy(alpha = 0.5f))
        PillKind.Outline -> Triple(HN.bg800.copy(alpha = 0.3f), HN.ink, HN.ink.copy(alpha = 0.22f))
        PillKind.Ghost -> Triple(HN.bg800.copy(alpha = 0.2f), HN.inkMuted, HN.line)
        PillKind.Danger -> Triple(Color.Transparent, HN.danger, HN.danger.copy(alpha = 0.4f))
    }
    val bgAnim by animateColorAsState(if (pressed) bg.copy(alpha = (bg.alpha * 0.8f).coerceAtLeast(0.15f)) else bg, tween(Motion.FAST), label = "bg")
    val dy by animateDpAsState(if (pressed) 1.dp else 0.dp, tween(Motion.FAST), label = "dy")
    // The touch target is at least 48dp tall even when the pill is drawn smaller.
    val touchPad = ((48.dp - height) / 2).coerceAtLeast(0.dp)
    Box(
        modifier
            .clickable(interaction, indication = null, role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .padding(vertical = touchPad),
        propagateMinConstraints = true,
    ) {
    Box(
        Modifier
            .graphicsLayer { translationY = dy.toPx() }
            .height(height)
            .clip(CircleShape)
            .background(bgAnim)
            .border(1.dp, border, CircleShape)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Icon(leading, size = 14.dp, color = fg)
                Spacer(Modifier.width(8.dp))
            }
            HText(text, style = Type.small.copy(fontWeight = FontWeight(600)), color = fg, maxLines = 1)
        }
    }
    }
}

// ─── Status ────────────────────────────────────────────────────────────────

/**
 * Pill chip with a dot: 10sp tracked uppercase. When [live], the dot pulses on
 * the shared ambient clock; the read happens in the dot's layer, so the chip
 * itself never recomposes for it.
 */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, live: Boolean = false, filled: Boolean = false) {
    val pulse = live && !LocalMotion.current.reduced
    val clock = LocalAmbient.current
    Row(
        modifier
            .clip(CircleShape)
            .background(if (filled) color else color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = if (filled) 1f else 0.3f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .graphicsLayer { alpha = if (pulse) 1f - 0.65f * clock.pulse(2.4f) else 1f }
                .clip(CircleShape)
                .background(if (filled) HN.bg else color),
        )
        Spacer(Modifier.width(6.dp))
        HText(text.uppercase(), style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.75f), color = if (filled) HN.bg else color, maxLines = 1)
    }
}

/** Thin usage bar: 1dp track, 3dp fill; amber past 80%. */
@Composable
fun Meter(label: String, value: Float, trail: String, modifier: Modifier = Modifier, color: Color? = null) {
    val v by animateFloatAsState(value, tween(Motion.SECTION, easing = Motion.Expo), label = "meter")
    val fill = color ?: if (value >= 0.8f) HN.amber else HN.accent
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HText(label.uppercase(), style = Type.label, color = HN.inkDim)
            Spacer(Modifier.weight(1f))
            HText(stringResource(R.string.percent, (value * 100).toInt()), style = Type.monoSmall, color = HN.ink)
            Spacer(Modifier.width(6.dp))
            HText(trail, style = Type.monoSmall, color = HN.inkDim)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(3.dp)) {
            val y = size.height / 2
            drawLine(HN.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            drawLine(fill, Offset(0f, y), Offset(size.width * v, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

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
                .background(HN.bg800.copy(alpha = 0.4f))
                .border(1.dp, HN.ink.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(glyph, size = 16.dp, color = color)
        }
    }
}
