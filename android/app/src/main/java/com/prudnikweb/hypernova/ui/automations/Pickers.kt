package com.prudnikweb.hypernova.ui.automations

import android.view.HapticFeedbackConstants
import com.prudnikweb.hypernova.ui.components.glassChoice
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Radio
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.components.frostedGlass
import com.prudnikweb.hypernova.ui.session.ChatSheet
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlinx.coroutines.launch

/** One tile in a WHEN or DO sheet; [reason] ghosts it and says why it can't apply. */
class Tile<T>(val value: T, val glyph: AutoGlyph, val label: String, val reason: String? = null)

/**
 * "Change ›": a sheet of tiles, two to a row, 72dp tall, a drawn hairline icon over a short label.
 * Tiles that can't apply are ghosted with the reason under them.
 */
@Composable
fun <T> TilesSheet(title: String, tiles: List<Tile<T>>, selected: T?, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    ChatSheet(title, onDismiss) {
        HText(title, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(16.dp))
        for (row in tiles.chunked(2)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (t in row) TileView(t, t.value == selected, Modifier.weight(1f).fillMaxHeight()) { onPick(t.value) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun <T> TileView(t: Tile<T>, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val on = t.reason == null
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .heightIn(min = 72.dp)
            .clip(shape)
            .glassChoice(selected, 14.dp)
            .selectable(selected, enabled = on, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick)
            .graphicsLayer { alpha = if (on) 1f else 0.4f }
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        AutoIcon(t.glyph, size = 18.dp, color = if (selected) HN.accent else HN.inkMuted)
        Spacer(Modifier.height(8.dp))
        HText(t.label, style = Type.small, color = if (selected) HN.ink else HN.inkMuted, maxLines = 2)
        if (t.reason != null) HText(t.reason, style = Type.monoSmall, color = HN.inkDim, maxLines = 2)
    }
}

/** A list to pick one from (a project, a session), with optional section titles. */
class Choice<T>(val value: T, val title: String, val sub: String? = null, val section: String? = null, val leading: AutoGlyph? = null)

/**
 * A list to pick one from, in the house sheet. A long one (a Mac with many projects) gets a search
 * field: a few letters of the name or the path do (A10).
 */
@Composable
fun <T> ChoiceSheet(title: String, choices: List<Choice<T>>, selected: T?, onPick: (T) -> Unit, onDismiss: () -> Unit, empty: String? = null, searchHint: String? = null) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val searchable = choices.size > SEARCH_FROM
    val shown = if (!searchable || query.isBlank()) choices else choices.filter { c -> matches(c, query) }
    ChatSheet(title, onDismiss) {
        HText(title, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(10.dp))
        if (searchable) {
            com.prudnikweb.hypernova.ui.home.SearchField(query, { query = it }, searchHint ?: stringResource(R.string.ns_search_projects))
            Spacer(Modifier.height(6.dp))
            if (shown.isEmpty()) HText(stringResource(R.string.ns_no_match, query.trim()), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 10.dp))
        }
        if (choices.isEmpty() && empty != null) HText(empty, style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 10.dp))
        var section: String? = null
        for (c in shown) {
            if (c.section != null && c.section != section) {
                section = c.section
                Spacer(Modifier.height(12.dp))
                HText(c.section.uppercase(), style = Type.label, color = HN.inkDim)
                Spacer(Modifier.height(4.dp))
            }
            ChoiceRow(c.title, c.sub, c.value == selected, c.leading) { onPick(c.value) }
            Hairline(Modifier.fillMaxWidth())
        }
    }
}

/** A list longer than this gets a search field. */
private const val SEARCH_FROM = 8

/** Every word typed is in the choice's title or its line (a path, a project). */
internal fun matches(c: Choice<*>, query: String): Boolean {
    val hay = (c.title + " " + c.sub.orEmpty()).lowercase()
    return query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { it in hay }
}

@Composable
fun ChoiceRow(title: String, sub: String?, selected: Boolean, leading: AutoGlyph? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .selectable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        Spacer(Modifier.width(14.dp))
        if (leading != null) {
            AutoIcon(leading, size = 14.dp, color = if (selected) HN.accent else HN.inkMuted)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            HText(title, style = Type.body, color = if (selected) HN.ink else HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) HText(sub, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
    }
}

/**
 * A glass block of the editor: the chosen kind's icon and title with "Change ›" (a tap anywhere on
 * the row opens the sheet), then its parameters.
 */
@Composable
fun KindBlock(glyph: AutoGlyph, title: String, onChange: (() -> Unit)?, error: String?, content: @Composable ColumnScope.() -> Unit) {
    com.prudnikweb.hypernova.ui.components.GlassCard(Modifier.fillMaxWidth(), accent = if (error != null) HN.amber else null) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(if (onChange != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.au_change), onClick = onChange) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoIcon(glyph, size = 18.dp, color = HN.accent)
                Spacer(Modifier.width(12.dp))
                HText(title, style = Type.heading, color = HN.ink, maxLines = 1, modifier = Modifier.weight(1f))
                if (onChange != null) {
                    HText(stringResource(R.string.au_change), style = Type.small, color = HN.inkMuted)
                    Spacer(Modifier.width(6.dp))
                    Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
                }
            }
            content()
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                HText(error, style = Type.small, color = HN.amber)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** A label over a parameter, tracked like the seams, a little quieter. */
@Composable
fun ParamLabel(text: String, modifier: Modifier = Modifier) {
    HText(text.uppercase(), style = Type.label, color = HN.inkDim, modifier = modifier.padding(top = 14.dp, bottom = 8.dp))
}

/** A value to tap and change: "9:00", "Fri 17 Oct", "hypernova ›". */
@Composable
fun ValuePill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null, mono: Boolean = true) {
    val view = LocalView.current
    Box(
        modifier
            .clip(CircleShape)
            .frostedGlass(HN.accent, glowX = 14.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = label) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .heightIn(min = 44.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        HText(text, style = if (mono) Type.mono.copy(fontSize = Type.body.fontSize) else Type.body, color = HN.ink, maxLines = 1)
    }
}

/** A row that opens a list ("PROJECT   hypernova ›"). */
@Composable
fun PickRow(label: String, value: String, onClick: () -> Unit, sub: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(label.uppercase(), style = Type.label, color = HN.inkDim, modifier = Modifier.width(96.dp), maxLines = 1)
        Column(Modifier.weight(1f)) {
            HText(value, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) HText(sub, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
    }
}

/**
 * The seven day chips (36dp, narrow letters, Monday first); at least one stays on, so tapping the
 * last one lit does nothing. TalkBack reads the day's whole name and whether it's on.
 */
@Composable
fun DayChips(days: List<Int>, onChange: (List<Int>) -> Unit, modifier: Modifier = Modifier) {
    val letters = rememberWords().arr(R.array.au_day_narrow)
    val names = rememberWords().arr(R.array.au_day_long)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (d in 1..7) {
            val on = d in days
            Box(
                Modifier
                    .size(44.dp)
                    .toggleable(on, remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox) { v ->
                        val next = if (v) (days + d).distinct().sorted() else days - d
                        if (next.isNotEmpty()) onChange(next)
                    }
                    .semantics { contentDescription = names[d - 1] },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).glassChoice(on),
                    contentAlignment = Alignment.Center,
                ) {
                    HText(letters[d - 1], style = Type.small, color = if (on) HN.accent else HN.inkMuted)
                }
            }
        }
    }
}

/** A small capsule to toggle ("Once"). */
@Composable
fun ToggleChip(text: String, on: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .toggleable(on, remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox, onValueChange = onChange),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.clip(CircleShape).glassChoice(on).padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            HText(text, style = Type.small, color = if (on) HN.accent else HN.inkMuted)
        }
    }
}

/**
 * The next two weeks as chips ("Today", "Tomorrow", "Sat 18"…), for a run that happens once. The
 * chosen day scrolls into view.
 */
@Composable
fun DateStrip(selected: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val w = rememberWords()
    val days = remember(today) { (0 until 21).map { today.plusDays(it.toLong()) } }
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { days.indexOf(selected).takeIf { it > 1 }?.let { state.scrollToItem(it - 1) } }
    LazyRow(modifier.fillMaxWidth(), state = state, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(days, key = { it.toEpochDay() }) { d ->
            val on = d == selected
            val label = when (d) {
                today -> w.s(R.string.au_when_today, "").trim()
                else -> DateTimeFormatter.ofPattern("EEE d", w.locale).format(d)
            }
            val full = DateTimeFormatter.ofPattern("EEEE d MMMM", w.locale).format(d)
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .selectable(on, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { onPick(d) }
                    .semantics { contentDescription = full },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.clip(CircleShape).glassChoice(on).padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    HText(label, style = Type.small, color = if (on) HN.accent else HN.inkMuted, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The time picker, in a glass sheet: an hour wheel and a minute wheel that snap to the line in the
 * middle (and AM/PM on a 12-hour phone). A tap on a number picks it too, so TalkBack can.
 */
@Composable
fun TimeSheet(initial: String, onPick: (String) -> Unit, onDismiss: () -> Unit, title: String = stringResource(R.string.au_pick_time)) {
    val w = rememberWords()
    val t = runCatching { LocalTime.parse(initial) }.getOrDefault(LocalTime.of(9, 0))
    var hour by remember { mutableIntStateOf(t.hour) }
    var minute by remember { mutableIntStateOf(t.minute) }
    ChatSheet(title, onDismiss) {
        HText(title, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(4.dp))
        HText(Summary.clock(w, LocalTime.of(hour, minute)), style = Type.mono.copy(fontSize = Type.title.fontSize), color = HN.accent)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // The line the wheels snap to.
            Box(Modifier.fillMaxWidth().height(WHEEL_ROW).clip(RoundedCornerShape(12.dp)).background(HN.ink.copy(alpha = 0.06f)).border(1.dp, HN.ink.copy(alpha = 0.10f), RoundedCornerShape(12.dp)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (w.h24) {
                    Wheel((0..23).map { it.toString() }, hour, { hour = it }, Modifier.width(72.dp))
                } else {
                    val h12 = if (hour % 12 == 0) 11 else hour % 12 - 1
                    Wheel((1..12).map { it.toString() }, h12, { i -> hour = ((i + 1) % 12) + if (hour >= 12) 12 else 0 }, Modifier.width(72.dp))
                }
                HText(":", style = Type.title, color = HN.inkMuted)
                Wheel((0..59).map { String.format(java.util.Locale.ROOT, "%02d", it) }, minute, { minute = it }, Modifier.width(72.dp))
                if (!w.h24) {
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Segmented(
                            listOf(false to w.s(R.string.au_am), true to w.s(R.string.au_pm)), hour >= 12,
                            { pm -> hour = hour % 12 + if (pm) 12 else 0 },
                            Modifier.width(120.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Pill(stringResource(R.string.au_done), { onPick(String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute)) }, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 48.dp)
    }
}

private val WHEEL_ROW = 44.dp

/** A wheel of [items] that snaps one to the middle; [index] follows it. */
@Composable
private fun Wheel(items: List<String>, index: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = index.coerceIn(0, items.lastIndex))
    val fling = rememberSnapFlingBehavior(state)
    val pick by rememberUpdatedState(onIndex)
    // The item nearest the middle line is the one picked, as it scrolls.
    LaunchedEffect(state) {
        snapshotFlow {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index
        }.collect { i -> if (i != null) pick(i.coerceIn(0, items.lastIndex)) }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LazyColumn(
        modifier.height(WHEEL_ROW * 5),
        state = state,
        flingBehavior = fling,
        contentPadding = PaddingValues(vertical = WHEEL_ROW * 2),
    ) {
        itemsIndexed(items) { i, label ->
            val on = i == index
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(WHEEL_ROW)
                    .selectable(on, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) {
                        scope.launch { state.animateScrollToItem(i) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                HText(label, style = Type.mono.copy(fontSize = Type.title.fontSize), color = if (on) HN.ink else HN.inkGhost)
            }
        }
    }
}

/**
 * A slider in steps (the battery's 5–50%): a hairline track, a mint fill and knob; drag or tap
 * anywhere on it, and TalkBack adjusts it as a slider.
 */
@Composable
fun StepSlider(value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit, label: String, modifier: Modifier = Modifier) {
    val steps = (range.last - range.first) / step
    val f = (value - range.first).toFloat() / (range.last - range.first)
    val change by rememberUpdatedState(onChange)
    fun at(x: Float, width: Float): Int {
        val k = (x / width).coerceIn(0f, 1f)
        return range.first + (k * steps).roundToInt() * step
    }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(), steps - 1)
                setProgress { v -> change(((v / step).roundToInt() * step).coerceIn(range.first, range.last)); true }
            }
            .pointerInput(Unit) { detectTapGestures { change(at(it.x, size.width.toFloat())) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> c.consume(); change(at(c.position.x, size.width.toFloat())) } },
        contentAlignment = Alignment.CenterStart,
    ) {
        val knob = 22.dp
        val travel = maxWidth - knob
        Box(Modifier.fillMaxWidth().height(2.dp).clip(CircleShape).background(HN.lineStrong))
        Box(Modifier.width(knob / 2 + travel * f).height(2.dp).clip(CircleShape).background(HN.accent))
        Box(
            Modifier
                .offset { IntOffset((travel * f).roundToPx(), 0) }
                .size(knob)
                .clip(CircleShape)
                .background(HN.accent)
                .border(1.dp, HN.accentBright.copy(alpha = 0.6f), CircleShape),
        )
    }
}

/** A warning box (8% fill, 35% ring, as notices are): amber for Auto, red for No asking. */
@Composable
fun WarningBox(text: String, color: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(12.dp),
    ) {
        WarnMark(color, Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(10.dp))
        HText(text, style = Type.small, color = HN.ink)
    }
}

/** The warning's drawn triangle. */
@Composable
private fun WarnMark(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val st = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w / 2, h * .1f); lineTo(w * .94f, h * .9f); lineTo(w * .06f, h * .9f); close() }, color, style = st)
        drawLine(color, androidx.compose.ui.geometry.Offset(w / 2, h * .4f), androidx.compose.ui.geometry.Offset(w / 2, h * .6f), st.width, androidx.compose.ui.graphics.StrokeCap.Round)
        drawCircle(color, 0.9.dp.toPx(), androidx.compose.ui.geometry.Offset(w / 2, h * .76f))
    }
}

/** Placeholders a text can carry, offered as chips that insert at the cursor. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceholderChips(list: List<String>, onInsert: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in list) RuleChip(p, stringResource(R.string.au_insert, p), leading = Glyph.Plus, onClick = { onInsert(p) })
    }
}
