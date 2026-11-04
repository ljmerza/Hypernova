package com.prudnikweb.hypernova.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.DayChart
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.StatDay
import com.prudnikweb.hypernova.data.StatWords
import com.prudnikweb.hypernova.data.Stats
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.platform.pluralStringResource
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.ui.platform.wordsNow
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

private val CHART_HEIGHT = 132.dp
private const val OUT_OF_RANGE = 0.35f
private const val GROW_MS = 900

/** A section's seam and its card; in a row of sections, [stretch] lets the card take the row's height. */
@Composable
internal fun StatsSection(label: String, trail: String?, modifier: Modifier, stretch: Boolean, content: @Composable () -> Unit) {
    Column(modifier) {
        Seam(label, Modifier.padding(horizontal = 4.dp), trail = trail)
        Spacer(Modifier.height(10.dp))
        GlassCard(Modifier.fillMaxWidth().then(if (stretch) Modifier.weight(1f) else Modifier)) { content() }
    }
}

/**
 * The last 30 days: a bar a day, the hours of work, whatever the range, which only lights its own days. A tap, a drag or a
 * pointer over the bars reads a day in the line under them; today until then.
 */
@Composable
internal fun DaysSection(s: Stats, modifier: Modifier = Modifier, stretch: Boolean = false) {
    val res = wordsNow()
    val chart = remember(s.days, s.range) { DayChart(s.days, s.range) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var hover by remember { mutableStateOf<Int?>(null) }
    val last = chart.days.lastIndex
    val shown = (hover ?: picked ?: last).coerceAtMost(last)
    StatsSection(stringResource(Str.stats_last_days), pluralStringResource(Plural.stats_active_days, chart.daysWithWork, chart.daysWithWork), modifier, stretch) {
        Column(Modifier.padding(16.dp).then(if (stretch) Modifier.fillMaxHeight() else Modifier)) {
            Bars(
                chart, shown,
                onPick = { picked = it },
                onHover = { hover = it },
                said = chartSaid(res, chart, shown),
                step = { by ->
                    val to = (shown + by).coerceIn(0, last)
                    val moved = to != shown
                    picked = to
                    moved
                },
                modifier = Modifier.fillMaxWidth().then(if (stretch) Modifier.weight(1f).heightIn(min = CHART_HEIGHT) else Modifier.height(CHART_HEIGHT)),
            )
            Spacer(Modifier.height(8.dp))
            AxisLabels(chart, res)
            Spacer(Modifier.height(12.dp))
            Hairline(Modifier.fillMaxWidth(), color = HN.lineSoft)
            Spacer(Modifier.height(12.dp))
            if (shown >= 0) DayLine(chart.days[shown], res)
        }
    }
}

private fun daySaid(res: Strings, d: StatDay): String {
    val name = StatWords.weekday(res, d.day)
    val figures = when {
        d.claudeMs > 0L -> {
            val time = StatWords.time(res, d.claudeMs)
            val both = res.getString(Str.stats_day_said, time, StatWords.count(res, d.tokens))
            if (d.codexMs > 0L) res.getString(Str.stats_day_codex_said, both, StatWords.time(res, d.codexMs)) else both
        }
        d.sessions > 0 -> res.getString(Str.stats_day_no_hours)
        else -> res.getString(Str.stats_day_none)
    }
    return "$name, $figures"
}

private fun chartSaid(res: Strings, chart: DayChart, shown: Int): String {
    val busiest = chart.busiest ?: return res.getString(Str.a11y_stats_days_none)
    val about = res.getString(Str.a11y_stats_days, StatWords.timeSaid(res, busiest.claudeMs), StatWords.weekday(res, busiest.day))
    return if (shown >= 0) about + " " + daySaid(res, chart.days[shown]) else about
}

@Composable
private fun Bars(chart: DayChart, shown: Int, onPick: (Int) -> Unit, onHover: (Int?) -> Unit, said: String, step: (Int) -> Boolean, modifier: Modifier) {
    val reduced = LocalMotion.current.reduced
    val grow = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) grow.animateTo(1f, tween(GROW_MS, easing = Motion.Expo)) }
    // Each bar eases as the range changes. Read only while drawing, so that redraws and nothing else.
    val light = chart.days.indices.map { i -> animateFloatAsState(if (chart.inRange(i)) 1f else OUT_OF_RANGE, tween(Motion.REVEAL), label = "lit") }
    val measurer = rememberTextMeasurer()
    val res = wordsNow()
    val top = res.getString(Str.stats_h, chart.topHours)
    val topText = remember(top) { measurer.measure(top, Type.monoSmall.copy(color = HN.inkDim)) }
    val before = stringResource(Str.a11y_stats_prev_day)
    val after = stringResource(Str.a11y_stats_next_day)
    Canvas(
        modifier
            .semantics {
                contentDescription = said
                customActions = listOf(CustomAccessibilityAction(before) { step(-1) }, CustomAccessibilityAction(after) { step(1) })
            }
            .pointerInput(chart) { detectTapGestures { onPick(chart.dayAt(it.x / size.width)) } }
            // Sideways only: an up-and-down drag stays the screen's scroll.
            .pointerInput(chart) {
                detectHorizontalDragGestures(onDragStart = { onPick(chart.dayAt(it.x / size.width)) }) { change, _ ->
                    change.consume()
                    onPick(chart.dayAt(change.position.x / size.width))
                }
            }
            // A mouse with no button down. A finger's move is a drag: it has no leaving, and would hold a day for good.
            .pointerInput(chart) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val mouse = e.changes.firstOrNull()?.takeIf { it.type == PointerType.Mouse } ?: continue
                        when (e.type) {
                            PointerEventType.Enter, PointerEventType.Move -> if (!mouse.pressed) onHover(chart.dayAt(mouse.position.x / size.width))
                            PointerEventType.Exit -> onHover(null)
                            else -> Unit
                        }
                    }
                }
            },
    ) {
        val labelH = topText.size.height.toFloat()
        val lineY = labelH / 2f
        val base = size.height
        val room = base - lineY
        val slot = size.width / chart.days.size.coerceAtLeast(1)
        val barW = slot * chart.barShare
        val corner = CornerRadius(2.dp.toPx().coerceAtMost(barW / 2f))
        val least = 3.dp.toPx()
        val tick = 1.5.dp.toPx()
        drawText(topText, topLeft = Offset(0f, 0f))
        drawLine(
            HN.line, Offset(topText.size.width + 6.dp.toPx(), lineY), Offset(size.width, lineY), strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
        )
        for (i in chart.days.indices) {
            val x = chart.centre(i) * size.width - barW / 2f
            val alpha = light[i].value
            if (i == shown) drawRect(HN.ink.copy(alpha = 0.06f), Offset(chart.centre(i) * size.width - slot / 2f, lineY), Size(slot, room))
            val share = chart.height(i)
            if (share <= 0f) {
                drawRect(HN.inkDim.copy(alpha = alpha), Offset(x, base - tick), Size(barW, tick))
                continue
            }
            val color = when (i) {
                shown -> HN.accentBright
                chart.days.lastIndex -> HN.accent
                else -> HN.accentDim
            }
            val h = (room * share).coerceAtLeast(least) * grow.value
            drawRoundRect(color.copy(alpha = alpha), Offset(x, base - h), Size(barW, h), corner)
        }
    }
}

/** First day, middle day and TODAY in tracked caps. The middle one drops out rather than crowd an end. */
@Composable
private fun AxisLabels(chart: DayChart, res: Strings) {
    if (chart.days.isEmpty()) return
    val days = chart.days
    val first = StatWords.date(res, days.first().day).uppercase()
    val middle = StatWords.date(res, days[chart.middleDay].day).uppercase()
    val today = stringResource(Str.stats_today_tick).uppercase()
    Layout(
        content = {
            HText(first, style = Type.label, color = HN.inkDim, maxLines = 1)
            HText(middle, style = Type.label, color = HN.inkDim, maxLines = 1)
            HText(today, style = Type.label, color = HN.inkMuted, maxLines = 1)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { parts, c ->
        val width = c.maxWidth
        val (a, b, t) = parts.map { it.measure(Constraints()) }
        val w = width.toFloat().coerceAtLeast(1f)
        val axis = chart.axis(a.width / w, b.width / w, t.width / w, 12.dp.toPx() / w)
        layout(width, maxOf(a.height, t.height)) {
            axis.first?.let { a.place((it * w).toInt(), 0) }
            axis.middle?.let { b.place((it * w).toInt(), 0) }
            t.place((axis.today * w).toInt(), 0)
        }
    }
}

/** The day being read: its name, then its figures, or why there are none. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayLine(d: StatDay, res: Strings) {
    FlowRow(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        HText(StatWords.weekday(res, d.day).uppercase(), style = Type.label, color = HN.ink, maxLines = 1)
        when {
            d.claudeMs > 0L -> {
                GlyphFigure(Glyph.Timer, StatWords.time(res, d.claudeMs))
                GlyphFigure(Glyph.Token, StatWords.count(res, d.tokens))
                if (d.codexMs > 0L) HText(stringResource(Str.stats_agent_codex).uppercase() + " " + StatWords.time(res, d.codexMs), style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
            }
            d.sessions > 0 -> HText(stringResource(Str.stats_day_no_hours), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            else -> HText(stringResource(Str.stats_day_none), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        }
    }
}
