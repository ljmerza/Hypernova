package com.prudnikweb.hypernova.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
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
 * The logo's stars, in the 108-unit box the launcher's vectors use, in drawing order: x, y and how far
 * to each point. Bottom left, top left, bottom right, top right, a little out of true, as a plotted
 * figure is. The res/drawable icons repeat these numbers.
 */
private val logoStars = floatArrayOf(
    36f, 81f, 6f,
    39f, 27f, 9.5f,
    69f, 80f, 7f,
    73f, 29f, 6f,
)
private const val LOGO_LINE = 4f
private const val LOGO_SPAN = 72f
private const val LOGO_X = 54.25f
private const val LOGO_Y = 52.25f

/** The logo, [extent] across, in the middle of this [DrawScope]. */
internal fun DrawScope.logo(extent: Float, color: Color) {
    val k = extent / LOGO_SPAN
    fun at(i: Int) = Offset(center.x + (logoStars[i * 3] - LOGO_X) * k, center.y + (logoStars[i * 3 + 1] - LOGO_Y) * k)
    val width = LOGO_LINE * k
    for (i in 0 until 3) drawLine(color, at(i), at(i + 1), width, StrokeCap.Round)
    for (i in 0 until 4) diamond(at(i), logoStars[i * 3 + 2] * k, color)
}
