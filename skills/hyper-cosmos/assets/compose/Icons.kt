package com.prudnikweb.hypernova.ui.components

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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.theme.HN

/**
 * A few drawn glyphs. Neither Manrope nor JetBrains Mono carries ✓ or ⎋, and a
 * system fallback font would show up at the wrong weight and size. The arrows
 * exist in both fonts but are drawn too, so the whole set shares one hairline
 * stroke that matches the Nova mark.
 */
enum class Glyph { Check, Cross, Back, Send, Esc, Plus, Chevron, Folder, File, Globe, Up, Play, Reload, Terminal, Laptop, Keyboard, Clip, Image, Camera }

@Composable
fun Icon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 14.dp, color: Color = HN.ink, stroke: Dp = 1.5.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val st = Stroke(width = stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * w, y * h)
        when (glyph) {
            Glyph.Check -> drawPath(Path().apply { moveTo(.18f * w, .54f * h); lineTo(.42f * w, .76f * h); lineTo(.84f * w, .28f * h) }, color, style = st)
            Glyph.Cross -> {
                drawLine(color, p(.24f, .24f), p(.76f, .76f), st.width, StrokeCap.Round)
                drawLine(color, p(.76f, .24f), p(.24f, .76f), st.width, StrokeCap.Round)
            }
            Glyph.Back -> {
                drawLine(color, p(.16f, .5f), p(.84f, .5f), st.width, StrokeCap.Round)
                drawPath(Path().apply { moveTo(.44f * w, .2f * h); lineTo(.16f * w, .5f * h); lineTo(.44f * w, .8f * h) }, color, style = st)
            }
            Glyph.Send -> {
                drawLine(color, p(.5f, .84f), p(.5f, .16f), st.width, StrokeCap.Round)
                drawPath(Path().apply { moveTo(.2f * w, .44f * h); lineTo(.5f * w, .16f * h); lineTo(.8f * w, .44f * h) }, color, style = st)
            }
            Glyph.Esc -> {
                // A small stop square inside a ring: "interrupt".
                drawCircle(color, radius = .38f * w, center = p(.5f, .5f), style = st)
                drawRect(color, topLeft = p(.38f, .38f), size = androidx.compose.ui.geometry.Size(.24f * w, .24f * h))
            }
            Glyph.Plus -> {
                drawLine(color, p(.5f, .2f), p(.5f, .8f), st.width, StrokeCap.Round)
                drawLine(color, p(.2f, .5f), p(.8f, .5f), st.width, StrokeCap.Round)
            }
            Glyph.Chevron -> drawPath(Path().apply { moveTo(.36f * w, .22f * h); lineTo(.66f * w, .5f * h); lineTo(.36f * w, .78f * h) }, color, style = st)
            Glyph.Folder -> drawPath(
                Path().apply {
                    moveTo(.12f * w, .26f * h); lineTo(.4f * w, .26f * h); lineTo(.5f * w, .36f * h); lineTo(.88f * w, .36f * h)
                    lineTo(.88f * w, .78f * h); lineTo(.12f * w, .78f * h); close()
                },
                color, style = st,
            )
            Glyph.File -> {
                drawPath(
                    Path().apply {
                        moveTo(.24f * w, .12f * h); lineTo(.58f * w, .12f * h); lineTo(.78f * w, .32f * h)
                        lineTo(.78f * w, .88f * h); lineTo(.24f * w, .88f * h); close()
                    },
                    color, style = st,
                )
                drawPath(Path().apply { moveTo(.58f * w, .12f * h); lineTo(.58f * w, .32f * h); lineTo(.78f * w, .32f * h) }, color, style = st)
            }
            Glyph.Globe -> {
                drawCircle(color, radius = .38f * w, center = p(.5f, .5f), style = st)
                drawOval(color, topLeft = p(.34f, .12f), size = androidx.compose.ui.geometry.Size(.32f * w, .76f * h), style = st)
                drawLine(color, p(.12f, .5f), p(.88f, .5f), st.width, StrokeCap.Round)
            }
            Glyph.Up -> {
                drawLine(color, p(.5f, .84f), p(.5f, .2f), st.width, StrokeCap.Round)
                drawPath(Path().apply { moveTo(.24f * w, .44f * h); lineTo(.5f * w, .18f * h); lineTo(.76f * w, .44f * h) }, color, style = st)
            }
            Glyph.Reload -> {
                drawArc(color, startAngle = -60f, sweepAngle = 300f, useCenter = false, topLeft = p(.18f, .18f), size = androidx.compose.ui.geometry.Size(.64f * w, .64f * h), style = st)
                drawPath(Path().apply { moveTo(.62f * w, .12f * h); lineTo(.70f * w, .25f * h); lineTo(.56f * w, .31f * h) }, color, style = st)
            }
            Glyph.Play -> drawPath(
                Path().apply { moveTo(.3f * w, .2f * h); lineTo(.8f * w, .5f * h); lineTo(.3f * w, .8f * h); close() },
                color, style = st,
            )
            Glyph.Terminal -> {
                // A window with a prompt: ">_".
                drawRoundRect(color, topLeft = p(.1f, .18f), size = androidx.compose.ui.geometry.Size(.8f * w, .64f * h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(.1f * w), style = st)
                drawPath(Path().apply { moveTo(.26f * w, .38f * h); lineTo(.4f * w, .5f * h); lineTo(.26f * w, .62f * h) }, color, style = st)
                drawLine(color, p(.48f, .64f), p(.7f, .64f), st.width, StrokeCap.Round)
            }
            Glyph.Laptop -> {
                drawRoundRect(color, topLeft = p(.2f, .22f), size = androidx.compose.ui.geometry.Size(.6f * w, .42f * h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(.06f * w), style = st)
                drawLine(color, p(.08f, .78f), p(.92f, .78f), st.width, StrokeCap.Round)
            }
            Glyph.Keyboard -> {
                drawRoundRect(color, topLeft = p(.1f, .26f), size = androidx.compose.ui.geometry.Size(.8f * w, .5f * h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(.08f * w), style = st)
                for (x in listOf(.28f, .44f, .6f, .74f)) drawCircle(color, radius = st.width * .55f, center = p(x, .42f))
                drawLine(color, p(.32f, .6f), p(.68f, .6f), st.width, StrokeCap.Round)
            }
            Glyph.Clip -> rotate(42f, p(.47f, .5f)) {
                // A paperclip: in at the inner end, round the bottom, over the top, round the outside.
                fun r(l: Float, t: Float, rr: Float, b: Float) = Rect(l * w, t * h, rr * w, b * h)
                drawPath(
                    Path().apply {
                        moveTo(.56f * w, .36f * h); lineTo(.56f * w, .66f * h)
                        arcTo(r(.38f, .57f, .56f, .75f), 0f, 180f, false)
                        lineTo(.38f * w, .26f * h)
                        arcTo(r(.38f, .12f, .66f, .40f), 180f, 180f, false)
                        lineTo(.66f * w, .70f * h)
                        arcTo(r(.28f, .51f, .66f, .89f), 0f, 180f, false)
                        lineTo(.28f * w, .40f * h)
                    },
                    color, style = st,
                )
            }
            Glyph.Image -> {
                drawRoundRect(color, topLeft = p(.12f, .2f), size = Size(.76f * w, .6f * h), cornerRadius = CornerRadius(.08f * w), style = st)
                drawCircle(color, radius = .06f * w, center = p(.35f, .4f), style = st)
                drawPath(Path().apply { moveTo(.14f * w, .72f * h); lineTo(.4f * w, .52f * h); lineTo(.56f * w, .64f * h); lineTo(.68f * w, .55f * h); lineTo(.86f * w, .7f * h) }, color, style = st)
            }
            Glyph.Camera -> {
                drawPath(
                    Path().apply {
                        moveTo(.1f * w, .34f * h); lineTo(.34f * w, .34f * h); lineTo(.41f * w, .22f * h); lineTo(.59f * w, .22f * h); lineTo(.66f * w, .34f * h)
                        lineTo(.9f * w, .34f * h); lineTo(.9f * w, .8f * h); lineTo(.1f * w, .8f * h); close()
                    },
                    color, style = st,
                )
                drawCircle(color, radius = .14f * w, center = p(.5f, .56f), style = st)
            }
        }
    }
}
