package com.prudnikweb.hypernova.ui.components

import android.content.res.Resources
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.UsageProjection
import com.prudnikweb.hypernova.data.ratePerMin
import com.prudnikweb.hypernova.ui.fx.formatDuration
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * A usage window as a projection meter (owner, 2026-10-18: spending drawn, "not stupidly written as
 * text and separated with bullets"). One component wherever a window shows: Home's usage card, the
 * chat's usage breakdown, the per-model weeks, and (compact) the chat's strip.
 *
 * - The track is a hairline; the **used** part a solid 3dp fill in the window's colour (mint, amber
 *   from 80%, red when spent).
 * - While something spends, a **faint fill** of the same colour runs on from it to where the current
 *   pace leads by the reset: a long one reads as burning fast, by itself.
 * - When that's the limit, before the reset, the faint fill runs to the end and the end gets a **red
 *   notch**; the one tag beside the bar is then the time to the limit, in red ("47m").
 * - Otherwise the tag is the reset countdown, dim, after a drawn reset glyph ("↻ 2h 1m").
 * - The percentage is a number beside the label. One tag, never a sentence.
 *
 * Changes ease once (600ms, expo) when the numbers do; nothing moves otherwise, so an idle screen
 * draws no frames. TalkBack reads one full sentence ([projectionSaid]).
 *
 * [resetText] words the reset when a countdown isn't the right words (a week's "Tue 20:00").
 * [model]: a window of one model's own (Fable's week), its name as a model chip before the label
 * ("[FABLE] 7 DAYS"), never "Fable · week".
 */
@Composable
fun ProjectionMeter(label: String, p: UsageProjection, spoken: String, modifier: Modifier = Modifier, resetText: String? = null, model: String? = null) {
    val colour = meterColour(p)
    Column(modifier.clearAndSetSemantics { contentDescription = spoken }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The label and its number take the row's start; the tag keeps to its end.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (model != null) {
                    ModelChip(model, compact = true)
                    Spacer(Modifier.width(8.dp))
                }
                HText(label.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                HText(percent(p.used), style = Type.monoSmall, color = if (p.full) HN.danger else HN.ink, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            MeterTag(p, resetText)
        }
        Spacer(Modifier.height(4.dp))
        ProjectionBar(p, colour, Modifier.fillMaxWidth())
    }
}

/**
 * The compact meter for a single row (the chat's strip): its short label ("5H"), the percentage, a
 * thin projection bar [barWidth] wide and the one tag. Short of room, the strip asks for less:
 * [bar] false leaves the bar out, [reset] false the reset countdown (the time to the limit, in
 * red, still shows).
 */
@Composable
fun ProjectionMeterCompact(label: String, p: UsageProjection, modifier: Modifier = Modifier, barWidth: Dp = 40.dp, bar: Boolean = true, reset: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        HText(label.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        HText(percent(p.used), style = Type.monoSmall, color = if (p.full) HN.danger else if (p.used >= 0.8f) HN.amber else HN.inkMuted, maxLines = 1)
        if (bar) {
            Spacer(Modifier.width(6.dp))
            ProjectionBar(p, meterColour(p), Modifier.width(barWidth))
        }
        if (reset || p.tag is UsageProjection.Tag.Limit) {
            Spacer(Modifier.width(6.dp))
            MeterTag(p, null)
        }
    }
}

/** The bar: hairline track, the used fill, the pace's faint fill, and the red notch when the limit comes first. */
@Composable
fun ProjectionBar(p: UsageProjection, colour: Color, modifier: Modifier = Modifier) {
    val used by animateFloatAsState(p.used, tween(Motion.SECTION, easing = Motion.Expo), label = "used")
    // At rest the faint fill folds back into the used part.
    val lead by animateFloatAsState(p.projected ?: p.used, tween(Motion.SECTION, easing = Motion.Expo), label = "pace")
    val notch by animateFloatAsState(if (p.limitFirst) 1f else 0f, tween(Motion.SECTION, easing = Motion.Expo), label = "notch")
    Canvas(modifier.height(9.dp)) { projectionBar(used, lead, notch, colour) }
}

/** Draws the bar in this scope's width, centred on its height. */
fun DrawScope.projectionBar(used: Float, lead: Float, notch: Float, colour: Color) {
    val y = size.height / 2f
    val w = size.width
    val thick = 3.dp.toPx()
    drawLine(HN.line, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
    if (lead > used + 0.002f) {
        drawLine(colour, Offset(w * used, y), Offset(w * lead.coerceAtMost(1f), y), strokeWidth = thick, cap = StrokeCap.Round, alpha = 0.3f)
    }
    if (used > 0.002f) drawLine(colour, Offset(0f, y), Offset(w * used, y), strokeWidth = thick, cap = StrokeCap.Round)
    if (notch > 0.01f) {
        val x = w - 1.dp.toPx()
        drawLine(HN.danger, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx(), alpha = notch)
    }
}

/** The one tag: the time to the limit in red after a small notch, or the reset after a reset glyph, dim. */
@Composable
private fun MeterTag(p: UsageProjection, resetText: String?) {
    val res = LocalContext.current.resources
    when (val t = p.tag) {
        is UsageProjection.Tag.Limit -> Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 2.dp, height = 9.dp)) { drawRect(HN.danger) }
            Spacer(Modifier.width(5.dp))
            HText(formatDuration(res, t.ms), style = Type.monoSmall, color = HN.danger, maxLines = 1)
        }
        is UsageProjection.Tag.Reset -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyph.Reload, size = 10.dp, color = HN.inkDim, stroke = 1.2.dp)
            Spacer(Modifier.width(4.dp))
            HText(resetText ?: formatDuration(res, t.ms), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        }
        UsageProjection.Tag.None -> Unit
    }
}

/** The window's colour: red when spent, amber from 80%, mint otherwise. */
fun meterColour(p: UsageProjection): Color = when {
    p.full -> HN.danger
    p.used >= 0.8f -> HN.amber
    else -> HN.accent
}

private fun percent(v: Float): String = "${(v * 100).toInt().coerceIn(0, 100)}%"

/**
 * One full sentence for TalkBack about a window called [name] ("5-hour window"): "5-hour window 62%
 * used, spending 0.8% a minute: at this pace you reach the limit in about 47m, before the reset in
 * 2h 1m." [resetAt]: the reset as a day and time to say ("Tuesday at 3:56 PM"), for a window whose
 * meter shows a clock time rather than a countdown: "… resets on Tuesday at 3:56 PM." (never
 * "resets in Tue 3:56 PM").
 */
fun projectionSaid(res: Resources, name: String, p: UsageProjection, resetAt: String? = null): String {
    val pct = (p.used * 100).toInt().coerceIn(0, 100)
    val at = resetAt != null
    val reset = resetAt ?: p.resetIn.takeIf { it >= 0 }?.let { formatDuration(res, it) }
    val pace = ratePerMin(p.pace)
    return when {
        p.full && reset != null -> res.getString(if (at) R.string.usage_said_full_at else R.string.usage_said_full, name, reset)
        p.full -> res.getString(R.string.usage_said_full_plain, name)
        p.tag is UsageProjection.Tag.Limit && pace != null && reset != null ->
            res.getString(if (at) R.string.usage_said_limit_at else R.string.usage_said_limit, name, pct, pace, formatDuration(res, (p.tag as UsageProjection.Tag.Limit).ms), reset)
        pace != null && reset != null -> res.getString(if (at) R.string.usage_said_safe_at else R.string.usage_said_safe, name, pct, pace, reset)
        reset != null -> res.getString(if (at) R.string.usage_said_idle_at else R.string.usage_said_idle, name, pct, reset)
        else -> res.getString(R.string.usage_said_plain, name, pct)
    }
}

@Composable
fun projectionSaid(name: String, p: UsageProjection, resetAt: String? = null): String = projectionSaid(LocalContext.current.resources, name, p, resetAt)
