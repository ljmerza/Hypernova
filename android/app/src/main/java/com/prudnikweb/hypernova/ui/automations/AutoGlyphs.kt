package com.prudnikweb.hypernova.ui.automations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.theme.HN
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The automation pickers' and templates' icons, drawn as hairlines in the set's stroke (neither
 * font carries them, and a system fallback would come in at the wrong weight).
 */
enum class AutoGlyph {
    Clock, Repeat, Reset, Limit, Finished, NeedsMe, Plug, Battery, Start, Skill, Continue, Bell, Glasses, Moon, Week, Switch,
    /** Workflows: a clock with its quarter ticks (cron), a hook catching a line (webhook), a page with a ripple (file watch), a branch (GitHub), a hook shape (Claude Code hooks), a speech bubble (ask), a prompt (shell), a page (a file output). */
    Cron, Webhook, FileWatch, Branch, Hook, Ask, Shell, Page,
}

@Composable
fun AutoIcon(glyph: AutoGlyph, modifier: Modifier = Modifier, size: Dp = 18.dp, color: Color = HN.ink, stroke: Dp = 1.4.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val st = Stroke(width = stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * w, y * h)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), st.width, StrokeCap.Round)
        fun path(vararg pts: Float, closed: Boolean = false) = drawPath(
            Path().apply {
                moveTo(pts[0] * w, pts[1] * h)
                var i = 2
                while (i < pts.size) { lineTo(pts[i] * w, pts[i + 1] * h); i += 2 }
                if (closed) close()
            },
            color, style = st,
        )
        when (glyph) {
            AutoGlyph.Clock -> {
                drawCircle(color, .38f * w, p(.5f, .5f), style = st)
                path(.5f, .28f, .5f, .5f, .66f, .6f)
            }
            AutoGlyph.Repeat -> {
                // An orbit nearly closed, with its arrow.
                drawArc(color, -40f, 290f, false, topLeft = p(.16f, .16f), size = Size(.68f * w, .68f * h), style = st)
                val a = (-40.0 * PI / 180).toFloat()
                val end = Offset(.5f * w + .34f * w * cos(a), .5f * h + .34f * h * sin(a))
                drawLine(color, end, end + Offset(-.02f * w, -.2f * h), st.width, StrokeCap.Round)
                drawLine(color, end, end + Offset(-.19f * w, .03f * h), st.width, StrokeCap.Round)
            }
            AutoGlyph.Reset -> {
                // A new star: four long points and four short ones (the disk igniting at the reset).
                val c = p(.5f, .5f)
                for (k in 0 until 8) {
                    val ang = (k * PI / 4).toFloat()
                    val len = if (k % 2 == 0) .4f else .2f
                    drawLine(color, c + Offset(cos(ang) * .08f * w, sin(ang) * .08f * h), c + Offset(cos(ang) * len * w, sin(ang) * len * h), st.width, StrokeCap.Round)
                }
            }
            AutoGlyph.Limit -> {
                // The supernova: a ring and its rays.
                drawCircle(color, .16f * w, p(.5f, .5f), style = st)
                val c = p(.5f, .5f)
                for (k in 0 until 8) {
                    val ang = (k * PI / 4 + PI / 8).toFloat()
                    drawLine(color, c + Offset(cos(ang) * .27f * w, sin(ang) * .27f * h), c + Offset(cos(ang) * .42f * w, sin(ang) * .42f * h), st.width, StrokeCap.Round)
                }
            }
            AutoGlyph.Finished -> {
                drawCircle(color, .38f * w, p(.5f, .5f), style = st)
                path(.33f, .52f, .45f, .64f, .68f, .38f)
            }
            AutoGlyph.NeedsMe -> {
                // The sky's focus bracket around a moon that waits.
                val k = .16f
                path(.14f, .14f + k, .14f, .14f, .14f + k, .14f)
                path(.86f - k, .14f, .86f, .14f, .86f, .14f + k)
                path(.86f, .86f - k, .86f, .86f, .86f - k, .86f)
                path(.14f + k, .86f, .14f, .86f, .14f, .86f - k)
                drawCircle(color, .09f * w, p(.5f, .5f))
            }
            AutoGlyph.Plug -> {
                drawRoundRect(color, p(.3f, .36f), Size(.4f * w, .26f * h), CornerRadius(.06f * w), style = st)
                line(.42f, .36f, .42f, .16f)
                line(.58f, .36f, .58f, .16f)
                path(.5f, .62f, .5f, .74f, .62f, .86f)
            }
            AutoGlyph.Battery -> {
                drawRoundRect(color, p(.12f, .32f), Size(.66f * w, .36f * h), CornerRadius(.07f * w), style = st)
                line(.86f, .44f, .86f, .56f)
                drawRoundRect(color, p(.2f, .41f), Size(.12f * w, .18f * h), CornerRadius(.02f * w))
            }
            AutoGlyph.Start -> path(.32f, .2f, .78f, .5f, .32f, .8f, closed = true)
            AutoGlyph.Skill -> {
                drawRoundRect(color, p(.14f, .14f), Size(.72f * w, .72f * h), CornerRadius(.16f * w), style = st)
                line(.58f, .3f, .42f, .7f)
            }
            AutoGlyph.Continue -> {
                path(.2f, .26f, .44f, .5f, .2f, .74f)
                path(.5f, .26f, .74f, .5f, .5f, .74f)
            }
            AutoGlyph.Bell -> {
                drawPath(
                    Path().apply {
                        moveTo(.22f * w, .7f * h)
                        lineTo(.78f * w, .7f * h)
                        lineTo(.7f * w, .58f * h)
                        lineTo(.7f * w, .42f * h)
                        arcTo(Rect(.3f * w, .2f * h, .7f * w, .62f * h), 0f, -180f, false)
                        lineTo(.3f * w, .58f * h)
                        close()
                    },
                    color, style = st,
                )
                drawCircle(color, .05f * w, p(.5f, .8f))
            }
            AutoGlyph.Glasses -> {
                drawCircle(color, .16f * w, p(.3f, .56f), style = st)
                drawCircle(color, .16f * w, p(.7f, .56f), style = st)
                drawArc(color, 200f, 140f, false, topLeft = p(.42f, .44f), size = Size(.16f * w, .16f * h), style = st)
                line(.14f, .52f, .08f, .38f)
                line(.86f, .52f, .92f, .38f)
            }
            AutoGlyph.Moon -> {
                val outer = Path().apply { addOval(Rect(.16f * w, .16f * h, .84f * w, .84f * h)) }
                val inner = Path().apply { addOval(Rect(.36f * w, .08f * h, .94f * w, .66f * h)) }
                drawPath(Path.combine(PathOperation.Difference, outer, inner), color, style = st)
            }
            AutoGlyph.Week -> {
                drawRoundRect(color, p(.14f, .22f), Size(.72f * w, .62f * h), CornerRadius(.08f * w), style = st)
                line(.14f, .4f, .86f, .4f)
                line(.34f, .14f, .34f, .28f)
                line(.66f, .14f, .66f, .28f)
                drawCircle(color, .045f * w, p(.36f, .58f))
                drawCircle(color, .045f * w, p(.5f, .58f))
                drawCircle(color, .045f * w, p(.64f, .58f))
            }
            AutoGlyph.Switch -> {
                path(.18f, .36f, .8f, .36f)
                path(.64f, .22f, .8f, .36f, .64f, .5f)
                path(.82f, .64f, .2f, .64f)
                path(.36f, .5f, .2f, .64f, .36f, .78f)
            }
            AutoGlyph.Cron -> {
                // A clock face with its four quarter ticks outside: a schedule.
                drawCircle(color, .3f * w, p(.5f, .5f), style = st)
                path(.5f, .34f, .5f, .5f, .62f, .56f)
                line(.5f, .06f, .5f, .13f)
                line(.5f, .87f, .5f, .94f)
                line(.06f, .5f, .13f, .5f)
                line(.87f, .5f, .94f, .5f)
            }
            AutoGlyph.Webhook -> {
                // A line arriving at a ring: something outside fires it.
                drawCircle(color, .14f * w, p(.66f, .34f), style = st)
                path(.12f, .86f, .5f, .48f)
                path(.3f, .48f, .5f, .48f, .5f, .68f)
            }
            AutoGlyph.FileWatch -> {
                path(.24f, .12f, .58f, .12f, .76f, .3f, .76f, .62f)
                path(.24f, .12f, .24f, .88f, .52f, .88f)
                path(.58f, .12f, .58f, .3f, .76f, .3f)
                drawArc(color, -60f, 120f, false, topLeft = p(.5f, .62f), size = Size(.3f * w, .3f * h), style = st)
                drawArc(color, -60f, 120f, false, topLeft = p(.42f, .56f), size = Size(.46f * w, .42f * h), style = st)
            }
            AutoGlyph.Branch -> {
                drawCircle(color, .09f * w, p(.3f, .2f), style = st)
                drawCircle(color, .09f * w, p(.3f, .8f), style = st)
                drawCircle(color, .09f * w, p(.72f, .32f), style = st)
                line(.3f, .29f, .3f, .71f)
                drawArc(color, 0f, 90f, false, topLeft = p(.3f - .42f, .41f - .3f), size = Size(.84f * w, .6f * h), style = st)
            }
            AutoGlyph.Hook -> {
                // A fishing hook: Claude Code's hooks.
                line(.56f, .12f, .56f, .62f)
                drawArc(color, 0f, 180f, false, topLeft = p(.28f, .46f), size = Size(.28f * w, .32f * h), style = st)
                path(.28f, .62f, .28f, .5f, .2f, .58f)
                drawCircle(color, .05f * w, p(.56f, .12f))
            }
            AutoGlyph.Ask -> {
                drawRoundRect(color, p(.12f, .16f), Size(.76f * w, .52f * h), CornerRadius(.14f * w), style = st)
                path(.3f, .68f, .26f, .86f, .46f, .68f)
                drawCircle(color, .045f * w, p(.36f, .42f))
                drawCircle(color, .045f * w, p(.5f, .42f))
                drawCircle(color, .045f * w, p(.64f, .42f))
            }
            AutoGlyph.Shell -> {
                drawRoundRect(color, p(.1f, .18f), Size(.8f * w, .64f * h), CornerRadius(.1f * w), style = st)
                path(.26f, .38f, .4f, .5f, .26f, .62f)
                line(.48f, .64f, .7f, .64f)
            }
            AutoGlyph.Page -> {
                path(.26f, .12f, .6f, .12f, .76f, .28f, .76f, .88f, .26f, .88f, closed = true)
                line(.36f, .46f, .66f, .46f)
                line(.36f, .6f, .66f, .6f)
                line(.36f, .74f, .56f, .74f)
            }
        }
    }
}
