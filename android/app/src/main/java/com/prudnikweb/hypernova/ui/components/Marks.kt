package com.prudnikweb.hypernova.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Atlas's small marks (docs/DESIGN.md, "Marks"): every state is a shape as well as a colour, so it
 * still reads for someone who can't tell the colours apart. All of them are [unit] × 14 wide: 1 dp
 * in a 14 dp mark. No paths: a working mark redraws up to 30 times a second, and these allocate
 * next to nothing.
 *
 * - Working: a solid diamond inside two arcs that turn.
 * - Needs you: a larger solid diamond in four corner brackets.
 * - Idle: a hollow diamond.
 */

/** A solid diamond, [r] from its middle to each point. */
internal fun DrawScope.diamond(center: Offset, r: Float, color: Color, alpha: Float = 1f) {
    val side = r * SQRT2
    rotate(45f, center) { drawRect(color, Offset(center.x - side / 2f, center.y - side / 2f), Size(side, side), alpha = alpha) }
}

/** A hollow diamond, [r] from its middle to each point of its line's middle. */
internal fun DrawScope.hollowDiamond(center: Offset, r: Float, color: Color, width: Float, alpha: Float = 1f) {
    val side = r * SQRT2
    rotate(45f, center) { drawRect(color, Offset(center.x - side / 2f, center.y - side / 2f), Size(side, side), alpha = alpha, style = Stroke(width)) }
}

/** A session at work's mark: the diamond and its arcs, [turn] degrees round. */
internal fun DrawScope.workingMark(center: Offset, unit: Float, color: Color, turn: Float, alpha: Float = 1f) {
    diamond(center, 2.6f * unit, color, alpha)
    workingArcs(center, 5.5f * unit, color, 1.15f * unit, turn, alpha)
}

/** The working mark's two arcs alone, at radius [r]. */
internal fun DrawScope.workingArcs(center: Offset, r: Float, color: Color, width: Float, turn: Float, alpha: Float = 1f) {
    val stroke = Stroke(width, cap = StrokeCap.Round)
    val at = Offset(center.x - r, center.y - r)
    val size = Size(2f * r, 2f * r)
    drawArc(color, turn, ARC, false, at, size, alpha = alpha, style = stroke)
    drawArc(color, turn + 180f, ARC, false, at, size, alpha = alpha, style = stroke)
}

/** A session that needs you's mark: the diamond in its brackets. */
internal fun DrawScope.needsYouMark(center: Offset, unit: Float, color: Color, alpha: Float = 1f) {
    diamond(center, 3.3f * unit, color, alpha)
    brackets(center, 6.3f * unit, 2.1f * unit, color, 1.05f * unit, alpha)
}

/** Four corner brackets, [reach] from the middle, each arm [arm] long. */
internal fun DrawScope.brackets(center: Offset, reach: Float, arm: Float, color: Color, width: Float, alpha: Float = 1f) {
    var sx = -1f
    while (sx <= 1f) {
        var sy = -1f
        while (sy <= 1f) {
            val corner = Offset(center.x + sx * reach, center.y + sy * reach)
            // Square caps meet at the corner without a notch, and stay inside reach + width / 2.
            drawLine(color, corner, Offset(corner.x, corner.y - sy * arm), width, StrokeCap.Square, alpha = alpha)
            drawLine(color, corner, Offset(corner.x - sx * arm, corner.y), width, StrokeCap.Square, alpha = alpha)
            sy += 2f
        }
        sx += 2f
    }
}

/** A session at rest's mark: the hollow diamond. */
internal fun DrawScope.idleMark(center: Offset, unit: Float, color: Color, alpha: Float = 1f) {
    hollowDiamond(center, 3.2f * unit, color, unit, alpha)
}

/** Blocked or cut off: a hollow diamond with a stroke through it. */
internal fun DrawScope.struckDiamond(center: Offset, r: Float, color: Color, width: Float, alpha: Float = 1f) {
    hollowDiamond(center, r, color, width, alpha)
    strike(center, r, color, width, alpha)
}

private val SQRT2 = sqrt(2f)
/** Degrees each of the working mark's arcs covers: the concept's 2.1 radians. */
private const val ARC = 120f

/** The stroke through a [struckDiamond]. */
internal fun DrawScope.strike(center: Offset, r: Float, color: Color, width: Float, alpha: Float = 1f) {
    val reach = r * 0.9f
    drawLine(color, Offset(center.x - reach, center.y + reach), Offset(center.x + reach, center.y - reach), width, StrokeCap.Round, alpha = alpha)
}

/** A small mark at work without a state: the diamond and its arcs at radius [r], [spin] radians round. */
internal fun DrawScope.turningStar(center: Offset, r: Float, color: Color, spin: Float, width: Float) {
    diamond(center, r * 0.47f, color)
    workingArcs(center, r, color, width, spin * 180f / PI.toFloat())
}

/**
 * The logo's two arms, in the launcher's units round its middle: the front layer of
 * res/drawable/ic_launcher_foreground.xml, thick at the core and tapering to a point. Built once:
 * unlike the marks above it's a path, but it never animates, so it's only drawn when its screen is.
 */
private val logoArms: Path by lazy {
    Path().apply {
        addLogoArm(LOGO_ROT)
        addLogoArm(LOGO_ROT + 180f)
    }
}
private const val LOGO_ROT = 200f
private const val LOGO_SWEEP = 235f
private const val LOGO_R0 = 4f
private const val LOGO_REACH = 33f
private const val LOGO_ARM = 10f
private const val LOGO_POWER = 0.85f
private const val LOGO_STEPS = 44
private const val LOGO_CORE = 7.5f
/** How far out, of [LOGO_REACH], the arms keep the core's colour before they turn towards their tips'. */
private const val LOGO_SOLID = 0.42f

private fun Path.addLogoArm(rot: Float) {
    fun halfWidth(t: Float) = LOGO_ARM * (1f - t).pow(0.75f) * min(1f, t / 0.1f + 0.25f)
    fun radius(t: Float) = LOGO_R0 + (LOGO_REACH - LOGO_R0) * t.pow(LOGO_POWER)
    fun angle(t: Float) = (rot + LOGO_SWEEP * t) * PI.toFloat() / 180f
    for (i in 0..LOGO_STEPS) {
        val t = i / LOGO_STEPS.toFloat()
        val r = radius(t) + halfWidth(t)
        val x = r * cos(angle(t))
        val y = r * sin(angle(t))
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    for (i in LOGO_STEPS downTo 0) {
        val t = i / LOGO_STEPS.toFloat()
        val r = max(0f, radius(t) - halfWidth(t))
        lineTo(r * cos(angle(t)), r * sin(angle(t)))
    }
    close()
}

/** The Nebula, its arms' tips [extent] across, in the middle of this [DrawScope]: [color] at the core, its arms turning to [tip]. */
internal fun DrawScope.logo(extent: Float, color: Color, tip: Color) {
    val k = extent / (2f * LOGO_REACH)
    translate(center.x, center.y) {
        scale(k, k, Offset.Zero) {
            drawPath(logoArms, Brush.radialGradient(0f to color, LOGO_SOLID to color, 1f to tip, center = Offset.Zero, radius = LOGO_REACH))
            drawCircle(color, LOGO_CORE, Offset.Zero)
        }
    }
}
