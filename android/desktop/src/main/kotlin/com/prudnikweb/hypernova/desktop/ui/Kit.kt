package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.components.diamond
import com.prudnikweb.hypernova.ui.components.idleMark
import com.prudnikweb.hypernova.ui.components.logo
import com.prudnikweb.hypernova.ui.components.needsYouMark
import com.prudnikweb.hypernova.ui.components.struckDiamond
import com.prudnikweb.hypernova.ui.components.workingArcs
import com.prudnikweb.hypernova.ui.components.workingMark

/*
 * The few parts of the app's kit these screens need (ui/components/Primitives.kt, docs/DESIGN.md,
 * "Components"), in the same sizes and colours. A pill has a focus ring and a hand here too: a desktop has
 * a keyboard and a pointer.
 */

@Composable
fun HText(text: String, modifier: Modifier = Modifier, style: TextStyle = Type.body, color: Color = HN.ink, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Ellipsis) {
    com.prudnikweb.hypernova.ui.theme.HText(text, modifier, style, color, maxLines, overflow)
}

@Composable
fun HText(text: AnnotatedString, modifier: Modifier = Modifier, style: TextStyle = Type.body, color: Color = HN.ink, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Ellipsis) {
    com.prudnikweb.hypernova.ui.theme.HText(text, modifier, style, color, maxLines, overflow)
}

private val CardFill = Brush.verticalGradient(listOf(HN.bg800.copy(alpha = 0.84f), HN.bg900.copy(alpha = 0.78f), HN.bg900.copy(alpha = 0.86f)))
private val TopHighlight = Brush.horizontalGradient(listOf(Color.Transparent, HN.ink.copy(alpha = 0.25f), Color.Transparent))
private val BottomHighlight = Brush.horizontalGradient(listOf(Color.Transparent, HN.ink.copy(alpha = 0.12f), Color.Transparent))

/** Radius 16, a dark gradient, a 1dp ring at ink 8%, a highlight along the top and the bottom; [edge]: a card that needs a look, as a thin line of its colour down the side. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, edge: Color? = null, shape: Shape = RoundedCornerShape(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val ring = edge?.copy(alpha = 0.12f) ?: HN.ink.copy(alpha = 0.08f)
    Column(
        modifier
            .clip(shape)
            .background(CardFill)
            .then(if (edge != null) Modifier.accentEdge(edge) else Modifier)
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                drawLine(TopHighlight, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), strokeWidth = stroke)
                drawLine(BottomHighlight, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), strokeWidth = stroke)
            }
            .border(1.dp, ring, shape),
        content = content,
    )
}

private fun Modifier.accentEdge(color: Color): Modifier = drawWithCache {
    val w = 2.dp.toPx()
    val glowW = 40.dp.toPx()
    val line = Brush.verticalGradient(0f to color.copy(alpha = 0.15f), 0.22f to color.copy(alpha = 0.85f), 0.78f to color.copy(alpha = 0.85f), 1f to color.copy(alpha = 0.15f))
    val glow = Brush.horizontalGradient(listOf(color.copy(alpha = 0.09f), Color.Transparent), startX = 0f, endX = glowW)
    onDrawBehind {
        drawRect(glow, topLeft = Offset.Zero, size = Size(glowW, size.height))
        drawLine(line, Offset(w / 2f, 0f), Offset(w / 2f, size.height), strokeWidth = w)
    }
}

private fun Modifier.frostedGlass(glow: Color?, glowX: Dp, dense: Boolean = false, tint: Color? = null): Modifier = drawWithCache {
    val h = size.height
    val r = h / 2f
    val corner = CornerRadius(r)
    val body = if (dense) {
        Brush.verticalGradient(listOf(lerp(HN.bg800, HN.ink, 0.07f).copy(alpha = 0.80f), HN.bg900.copy(alpha = 0.74f)))
    } else {
        Brush.verticalGradient(listOf(HN.ink.copy(alpha = 0.09f), HN.ink.copy(alpha = 0.04f)))
    }
    val light = glow?.let {
        Brush.radialGradient(listOf(it.copy(alpha = if (dense) 0.15f else 0.18f), it.copy(alpha = 0.05f), Color.Transparent), center = Offset(glowX.toPx(), h / 2f), radius = h * 1.5f)
    }
    val sw = 1.dp.toPx()
    val wash = tint?.let { Brush.verticalGradient(listOf(it.copy(alpha = 0.16f), it.copy(alpha = 0.06f))) }
    val edgeC = tint ?: HN.ink
    val edge = Brush.verticalGradient(0f to edgeC.copy(alpha = if (tint != null) 0.55f else 0.22f), 0.5f to edgeC.copy(alpha = 0f), 1f to edgeC.copy(alpha = 0.05f))
    onDrawBehind {
        drawRoundRect(body, cornerRadius = corner)
        if (wash != null) drawRoundRect(wash, cornerRadius = corner)
        if (light != null) drawRoundRect(light, cornerRadius = corner)
        drawRoundRect(edge, topLeft = Offset(sw / 2f, sw / 2f), size = Size(size.width - sw, h - sw), cornerRadius = CornerRadius(r - sw / 2f), style = Stroke(sw))
    }
}

private fun Modifier.filledGlass(color: Color): Modifier = drawWithCache {
    val h = size.height
    val corner = CornerRadius(h / 2f)
    val body = Brush.verticalGradient(listOf(lerp(color, Color.White, 0.16f), color, lerp(color, Color.Black, 0.10f)))
    val sw = 1.dp.toPx()
    val edge = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.45f), 0.5f to Color.White.copy(alpha = 0f), 1f to Color.Black.copy(alpha = 0.12f))
    onDrawBehind {
        drawRoundRect(body, cornerRadius = corner)
        drawRoundRect(edge, topLeft = Offset(sw / 2f, sw / 2f), size = Size(size.width - sw, h - sw), cornerRadius = CornerRadius(h / 2f - sw / 2f), style = Stroke(sw))
    }
}

enum class PillKind { Primary, Outline, Ghost, Danger }

/** The app's pill. Tab reaches it, Enter or Space presses it, and the focus shows as a ring of ink. */
@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: PillKind = PillKind.Outline, height: Dp = 40.dp, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val fg = when (kind) {
        PillKind.Primary -> HN.accent
        PillKind.Outline -> HN.ink
        PillKind.Ghost -> HN.inkMuted
        PillKind.Danger -> HN.danger
    }
    val tint = when (kind) { PillKind.Primary -> HN.accent; PillKind.Danger -> HN.danger; else -> null }
    Box(
        modifier
            .graphicsLayer { translationY = if (pressed) 1.dp.toPx() else 0f; alpha = if (!enabled) 0.45f else if (pressed) 0.8f else 1f }
            .height(height)
            .clip(CircleShape)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .frostedGlass(if (hovered) fg else null, glowX = 27.dp, dense = kind != PillKind.Ghost, tint = tint)
            .then(if (focused) Modifier.border(1.5.dp, HN.ink, CircleShape) else Modifier)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        HText(text, style = Type.small.copy(fontWeight = FontWeight(600)), color = fg, maxLines = 1)
    }
}

/** What state a mark says (docs/DESIGN.md, "Marks"): each its own shape. */
enum class MarkKind { Working, NeedsYou, Idle }

/** A state's mark, 12dp. A working one reads the shared clock, in its draw only. */
@Composable
fun Mark(kind: MarkKind, modifier: Modifier = Modifier, size: Dp = 12.dp, color: Color = when (kind) { MarkKind.Working -> HN.accent; MarkKind.NeedsYou -> HN.amber; MarkKind.Idle -> HN.idle }) {
    val turn = LocalTurn.current
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 14f
        when (kind) {
            MarkKind.Working -> workingMark(center, u, color, turn.value)
            MarkKind.NeedsYou -> needsYouMark(center, u, color)
            MarkKind.Idle -> idleMark(center, u, color)
        }
    }
}

/** The logo, in ink with its tips in the accent, as on the phone. */
@Composable
fun NovaMark(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Canvas(modifier.size(size)) { logo(this.size.minDimension, HN.ink, HN.accent) }
}

/** A seam: the mark, the label in tracked caps, a hairline, and a trail. */
@Composable
fun Seam(label: String, mark: MarkKind, modifier: Modifier = Modifier, color: Color = HN.inkMuted, trail: @Composable (() -> Unit)? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Mark(mark)
        Spacer(Modifier.width(10.dp))
        HText(label.uppercase(), style = Type.label, color = color, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(HN.line))
        if (trail != null) {
            Spacer(Modifier.width(10.dp))
            trail()
        }
    }
}

/** A group's count: a still point of light and the number. */
@Composable
fun CountMark(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) {
            val u = 1.dp.toPx()
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.4f), color.copy(alpha = 0f)), center, 4f * u), 4f * u, center)
            drawCircle(color, 1.9f * u, center)
        }
        Spacer(Modifier.width(4.dp))
        HText(text, style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.5f), color = color, maxLines = 1)
    }
}

/** A state chip: frosted glass, the mark, the word in tracked caps; [filled] (needs you) is the one solid thing. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, live: Boolean = false, filled: Boolean = false, hollow: Boolean = false) {
    val lit = color != HN.inkDim && color != HN.inkMuted
    val turn = LocalTurn.current
    Row(
        modifier
            .then(if (filled) Modifier.filledGlass(color) else Modifier.frostedGlass(if (lit) color else null, glowX = 11.dp))
            .heightIn(min = 22.dp)
            .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            Modifier.size(10.dp).drawBehind {
                val u = size.minDimension / 14f
                when {
                    filled -> needsYouMark(center, u, HN.bg)
                    hollow -> drawLine(color, Offset(center.x - 3.4f * u, center.y), Offset(center.x + 3.4f * u, center.y), 1.3f * u, StrokeCap.Round)
                    else -> {
                        if (live) workingArcs(center, 5.5f * u, color, 1.15f * u, turn.value)
                        if (lit) diamond(center, 2.6f * u, color) else idleMark(center, u, color)
                    }
                }
            },
        )
        Spacer(Modifier.width(5.dp))
        HText(text.uppercase(), style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.75f), color = if (filled) HN.bg else color, maxLines = 1)
    }
}

/** The connection chip's mark, 16dp (`MacMoon`): a solid diamond linked, arcs round it while looking, struck through offline, plain hollow unpaired. */
enum class LinkMark { Linked, Looking, Offline, Unpaired }

@Composable
fun LinkGlyph(kind: LinkMark, color: Color, modifier: Modifier = Modifier) {
    val turn = LocalTurn.current
    Canvas(modifier.size(16.dp)) {
        val u = size.minDimension / 14f
        when (kind) {
            LinkMark.Linked -> diamond(center, 3.4f * u, color)
            LinkMark.Looking -> {
                diamond(center, 2.6f * u, color)
                workingArcs(center, 5.5f * u, color, 1.15f * u, turn.value)
            }
            LinkMark.Offline -> struckDiamond(center, 4.2f * u, color, 1.1f * u)
            LinkMark.Unpaired -> idleMark(center, u, color)
        }
    }
}
