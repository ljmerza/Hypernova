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
enum class Glyph {
    Check, Cross, Back, Send, Esc, Plus, Chevron, Folder, File, Globe, Up, Play, Reload, Terminal, Laptop, Keyboard, Clip, Image, Camera, Mic,
    /** A session's age (a clock face). */
    Clock,
    /** A session's cost (a banknote). */
    Note,
    /** A session's tokens (a hexagon, a token). */
    Token,
    /** How long something ran or took (a stopwatch), where [Clock] is when, or how long ago. */
    Timer,
    /** Tool calls (a wrench). */
    Tool,
    /** On mains power (a plug). */
    Plug,
    /** On the Mac's own network (a Wi-Fi fan); [Globe] is over the internet. */
    Lan,
    /** The permission modes ([modeGlyph]): a shield for asking first, with a check for auto, crossed out for bypass, with a bar for "allowed only"; a pencil for accepting edits, a list for plan. */
    Shield, ShieldCheck, ShieldOff, ShieldBar, Pencil, Plan,
    /** Finding in a list (a lens on a handle). */
    Search,
    /** A key (a provider's key was refused). */
    Key,
    /** A chain link, broken (the computer can't be reached). */
    Unlink,
    /** Someone's account (signed out, on hold). */
    Person,
    /** Narrowing a list (three lines, each shorter, a funnel's outline): Home's filter. */
    Filter,
}

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
            Glyph.Clock -> {
                drawCircle(color, radius = .38f * w, center = p(.5f, .5f), style = st)
                drawLine(color, p(.5f, .5f), p(.5f, .27f), st.width, StrokeCap.Round)
                drawLine(color, p(.5f, .5f), p(.66f, .58f), st.width, StrokeCap.Round)
            }
            Glyph.Note -> {
                drawRoundRect(color, topLeft = p(.1f, .26f), size = Size(.8f * w, .48f * h), cornerRadius = CornerRadius(.08f * w), style = st)
                drawCircle(color, radius = .11f * w, center = p(.5f, .5f), style = st)
            }
            Glyph.Token -> {
                // A hexagon, point up.
                val path = Path()
                for (i in 0 until 6) {
                    val a = Math.toRadians(-90.0 + 60.0 * i)
                    val x = (.5f + .4f * kotlin.math.cos(a).toFloat()) * w
                    val y = (.5f + .4f * kotlin.math.sin(a).toFloat()) * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color, style = st)
            }
            Glyph.Timer -> {
                // A stopwatch: its face, the crown on top and one hand.
                drawCircle(color, radius = .34f * w, center = p(.5f, .56f), style = st)
                drawLine(color, p(.5f, .22f), p(.5f, .1f), st.width, StrokeCap.Round)
                drawLine(color, p(.4f, .1f), p(.6f, .1f), st.width, StrokeCap.Round)
                drawLine(color, p(.79f, .27f), p(.86f, .2f), st.width, StrokeCap.Round)
                drawLine(color, p(.5f, .56f), p(.62f, .42f), st.width, StrokeCap.Round)
            }
            Glyph.Tool -> {
                // A wrench: an open jaw at the upper right, its handle down to the lower left.
                drawArc(color, startAngle = -15f, sweepAngle = 300f, useCenter = false, topLeft = p(.46f, .14f), size = Size(.4f * w, .4f * h), style = st)
                drawLine(color, p(.52f, .48f), p(.18f, .82f), st.width, StrokeCap.Round)
            }
            Glyph.Plug -> {
                drawRoundRect(color, topLeft = p(.3f, .36f), size = Size(.4f * w, .26f * h), cornerRadius = CornerRadius(.06f * w), style = st)
                drawLine(color, p(.42f, .36f), p(.42f, .14f), st.width, StrokeCap.Round)
                drawLine(color, p(.58f, .36f), p(.58f, .14f), st.width, StrokeCap.Round)
                drawPath(Path().apply { moveTo(.5f * w, .62f * h); lineTo(.5f * w, .74f * h); quadraticTo(.5f * w, .88f * h, .66f * w, .88f * h) }, color, style = st)
            }
            Glyph.Lan -> {
                // Three arcs fanning up from a point, as Wi-Fi is drawn.
                for (r in floatArrayOf(.18f, .32f, .46f)) {
                    drawArc(color, startAngle = -135f, sweepAngle = 90f, useCenter = false, topLeft = p(.5f - r, .8f - r), size = Size(2f * r * w, 2f * r * h), style = st)
                }
                drawCircle(color, radius = st.width * .8f, center = p(.5f, .8f))
            }
            Glyph.Shield, Glyph.ShieldCheck, Glyph.ShieldOff, Glyph.ShieldBar -> {
                drawPath(
                    Path().apply {
                        moveTo(.5f * w, .1f * h); lineTo(.84f * w, .22f * h); lineTo(.84f * w, .48f * h)
                        cubicTo(.84f * w, .7f * h, .68f * w, .84f * h, .5f * w, .92f * h)
                        cubicTo(.32f * w, .84f * h, .16f * w, .7f * h, .16f * w, .48f * h)
                        lineTo(.16f * w, .22f * h); close()
                    },
                    color, style = st,
                )
                when (glyph) {
                    Glyph.ShieldCheck -> drawPath(Path().apply { moveTo(.34f * w, .5f * h); lineTo(.46f * w, .62f * h); lineTo(.67f * w, .38f * h) }, color, style = st)
                    Glyph.ShieldOff -> drawLine(color, p(.1f, .9f), p(.9f, .1f), st.width, StrokeCap.Round)
                    Glyph.ShieldBar -> drawLine(color, p(.35f, .5f), p(.65f, .5f), st.width, StrokeCap.Round)
                    else -> Unit
                }
            }
            Glyph.Pencil -> {
                drawPath(
                    Path().apply { moveTo(.16f * w, .84f * h); lineTo(.22f * w, .62f * h); lineTo(.66f * w, .18f * h); lineTo(.82f * w, .34f * h); lineTo(.38f * w, .78f * h); close() },
                    color, style = st,
                )
                drawLine(color, p(.56f, .28f), p(.72f, .44f), st.width, StrokeCap.Round)
            }
            Glyph.Plan -> for (y in floatArrayOf(.28f, .5f, .72f)) {
                drawCircle(color, radius = st.width * .7f, center = p(.2f, y))
                drawLine(color, p(.38f, y), p(.86f, y), st.width, StrokeCap.Round)
            }
            Glyph.Search -> {
                drawCircle(color, radius = .27f * w, center = p(.43f, .43f), style = st)
                drawLine(color, p(.63f, .63f), p(.84f, .84f), st.width, StrokeCap.Round)
            }
            Glyph.Key -> {
                // A ring for the bow, a shaft, and two teeth.
                drawCircle(color, radius = .18f * w, center = p(.3f, .5f), style = st)
                drawLine(color, p(.48f, .5f), p(.88f, .5f), st.width, StrokeCap.Round)
                drawLine(color, p(.74f, .5f), p(.74f, .66f), st.width, StrokeCap.Round)
                drawLine(color, p(.86f, .5f), p(.86f, .62f), st.width, StrokeCap.Round)
            }
            Glyph.Unlink -> {
                // Two links of a chain, apart, with the break's sparks between them.
                rotate(-45f, p(.5f, .5f)) {
                    drawRoundRect(color, topLeft = p(.06f, .36f), size = Size(.34f * w, .28f * h), cornerRadius = CornerRadius(.14f * w), style = st)
                    drawRoundRect(color, topLeft = p(.6f, .36f), size = Size(.34f * w, .28f * h), cornerRadius = CornerRadius(.14f * w), style = st)
                }
                drawLine(color, p(.5f, .14f), p(.5f, .26f), st.width, StrokeCap.Round)
                drawLine(color, p(.5f, .74f), p(.5f, .86f), st.width, StrokeCap.Round)
            }
            Glyph.Person -> {
                drawCircle(color, radius = .17f * w, center = p(.5f, .34f), style = st)
                drawArc(color, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = p(.2f, .62f), size = Size(.6f * w, .5f * h), style = st)
            }
            Glyph.Filter -> {
                drawLine(color, p(.16f, .3f), p(.84f, .3f), st.width, StrokeCap.Round)
                drawLine(color, p(.3f, .5f), p(.7f, .5f), st.width, StrokeCap.Round)
                drawLine(color, p(.43f, .7f), p(.57f, .7f), st.width, StrokeCap.Round)
            }
            Glyph.Mic -> {
                // A capsule on a stand: the body, the cradle round its foot, the stem and the base.
                drawRoundRect(color, topLeft = p(.36f, .08f), size = Size(.28f * w, .5f * h), cornerRadius = CornerRadius(.14f * w), style = st)
                drawArc(color, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = p(.21f, .3f), size = Size(.58f * w, .46f * h), style = st)
                drawLine(color, p(.5f, .76f), p(.5f, .9f), st.width, StrokeCap.Round)
                drawLine(color, p(.34f, .9f), p(.66f, .9f), st.width, StrokeCap.Round)
            }
        }
    }
}

/** A permission mode's glyph (see [Glyph.Shield]); Codex's Read only is the shield, Can edit the pencil. */
fun modeGlyph(mode: String): Glyph = when (mode) {
    "acceptEdits", "workspace-write" -> Glyph.Pencil
    "plan" -> Glyph.Plan
    "auto" -> Glyph.ShieldCheck
    "bypassPermissions" -> Glyph.ShieldOff
    "dontAsk" -> Glyph.ShieldBar
    else -> Glyph.Shield
}
