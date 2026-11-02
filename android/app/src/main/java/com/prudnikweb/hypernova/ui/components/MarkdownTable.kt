package com.prudnikweb.hypernova.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Align
import com.prudnikweb.hypernova.data.MarkdownTable
import com.prudnikweb.hypernova.data.cellMarkdown
import com.prudnikweb.hypernova.data.cells
import com.prudnikweb.hypernova.data.isBlankCell
import com.prudnikweb.hypernova.data.isDivider
import com.prudnikweb.hypernova.data.isQuantity
import com.prudnikweb.hypernova.data.plain
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ─── Column widths ─────────────────────────────────────────────────────────

/**
 * Column widths for a table [viewport] wide, from each column's narrowest width ([min]: its
 * longest word) and widest ([max]: its longest cell on one line), padding included, in any one
 * unit. Numbers never wrap, but their header may, so a [numeric] column's [min] is its widest
 * number or its header's longest word, whichever is wider. Three cases:
 * - Everything fits on one line: each column as wide as it wants, and the room left goes to the
 *   text columns, so the card spans the reply and numbers sit at its right edge.
 * - It fits once text wraps: each text column starts from its least (its longest word, at least
 *   [minText]) and the room left is shared in proportion to how much more each would take, as a
 *   browser lays out a table: a column holding a long cell gets most of it, so its row stays
 *   short, and nothing scrolls.
 * - Even wrapped it doesn't fit: the table scrolls sideways, each column (a number column's
 *   header too) at a comfortable reading width ([comfy]) or its own if narrower.
 * A word wider than [comfy] (a path, a URL) breaks rather than make the table scroll. The sum is
 * [viewport] unless the table scrolls.
 */
internal fun columnWidths(
    min: FloatArray,
    max: FloatArray,
    numeric: BooleanArray,
    viewport: Float,
    minText: Float,
    comfy: Float,
): FloatArray {
    val cols = min.size
    val floor = FloatArray(cols) {
        if (numeric[it]) return@FloatArray min[it]
        val least = minOf(minText, max[it])
        min[it].coerceIn(least, maxOf(least, minOf(max[it], comfy)))
    }
    val sumN = max.sum()
    val sumF = floor.sum()
    return when {
        sumN <= viewport -> {
            val takers = (0 until cols).filter { !numeric[it] }.ifEmpty { (0 until cols).toList() }
            val base = takers.sumOf { max[it].toDouble() }.toFloat()
            val slack = viewport - sumN
            FloatArray(cols) { if (base > 0f && it in takers) max[it] + slack * max[it] / base else max[it] }
        }
        sumF <= viewport -> FloatArray(cols) { floor[it] + (viewport - sumF) * (max[it] - floor[it]) / (sumN - sumF) }
        else -> FloatArray(cols) { minOf(max[it], maxOf(floor[it], comfy)) }
    }
}

// ─── Drawing ───────────────────────────────────────────────────────────────

private val CellPadH = 12.dp
/** The outer cells' side padding: text 14dp from the card's edge, as in a diff or a terminal. */
private val EdgePadH = 14.dp
private val CellPadV = 8.dp
private val HeaderPadV = 9.dp
private val MinText = 96.dp
private val Comfy = 208.dp
private val EdgeFade = 28.dp

private val TableShape = RoundedCornerShape(14.dp)
private val TableFill = HN.bg950.copy(alpha = 0.78f)
private val TableRing = HN.ink.copy(alpha = 0.08f)
private val HeaderFill = HN.bg800.copy(alpha = 0.45f)

/**
 * A table in a reply or a plan: a hairline card like a diff's with the glass card's top highlight,
 * the header on a faint band in tracked caps, rows split by hairlines, numbers on the right in
 * mono. Columns take the width they need and share what's left; long text wraps; only when even
 * wrapped text can't fit does the table scroll sideways, its sides fading where there's more. The
 * cells in a row share a baseline, a few pixels apart at most at the top, so a selection across
 * rows copies them row by row, a cell per line. TalkBack reads each row as one stop ("System
 * prompt. Tokens: 3.2k. Percentage: 1.6%").
 */
@Composable
internal fun MdTable(t: MarkdownTable, color: Color, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(TableShape)
            .background(TableFill)
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                drawLine(TopHighlight, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), strokeWidth = stroke)
            }
            .border(1.dp, TableRing, TableShape),
    ) {
        // The scroll hands the visible width down as the least the grid may take, which is how
        // the grid knows whether it fits. The header band and hairlines run to the card's edges.
        Grid(
            t, color,
            Modifier
                .fillMaxWidth()
                .fadeSides(scroll)
                .horizontalScroll(scroll),
        )
    }
}

/** A `---` between parts of a reply: a hairline, as under a seam's label. */
@Composable
internal fun MdRule() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(HN.line))
}

/**
 * The cells, laid out in one pass: each column's widths come from its cells' own text, the
 * visible width from the scroll above. Each row is a backdrop (band, hairline, TalkBack's stop)
 * and its cells, which are hidden from TalkBack because the row speaks for them.
 */
@Composable
private fun Grid(table: MarkdownTable, color: Color, modifier: Modifier) {
    val t = rememberSteady(table)
    val cols = t.columns
    val context = LocalContext.current
    val glide = remember { Glide() }
    val scope = rememberCoroutineScope()
    val smooth = !LocalMotion.current.reduced
    // Short plain headers read as the app's tracked labels; long or code ones stay sentences. Code
    // is known as soon as it's typed; length only once the header line is whole, so a header still
    // arriving doesn't switch style mid-word.
    val tracked = remember(t.header, t.headerOpen) {
        t.header.none { '`' in it } && (t.headerOpen || t.header.all { plain(it).length <= 24 })
    }
    val texts = remember(t, tracked) {
        List(t.rows.size + 1) { r ->
            val row = if (r == 0) t.header else t.rows[r - 1]
            // The cells still being typed: the last row, or the header until its line ends.
            val typing = t.live && if (r == 0) t.headerOpen else r == t.rows.size
            row.map { if (r == 0 && tracked) AnnotatedString(plain(it).uppercase()) else markdownLite(cellMarkdown(it, typing)) }
        }
    }
    val count = t.rows.size + 1
    // Rows of only dashes are dividers: no text, no height and no TalkBack stop. The row under
    // one draws a firm line on its top in place of the soft one (none when the divider sits right
    // under the header, whose own line is there), so it reads as a break, like the header's.
    val divider = remember(t) { BooleanArray(count) { it > 0 && isDivider(t.rows[it - 1]) } }
    val rule = remember(divider) {
        Array(count) { r ->
            var k = r - 1
            while (k > 0 && divider[k]) k--
            when {
                r == 0 || divider[r] -> null
                k == r - 1 -> if (r > 1) HN.lineSoft else null
                k == 0 -> null
                else -> HN.line
            }
        }
    }
    val body = (1 until count).any { !divider[it] }
    val said = remember(t, context) {
        spokenRows(t) { name, value -> context.getString(R.string.table_cell, name, value) }
    }
    // A row with nothing in it (every cell empty, or a divider) isn't a stop for TalkBack; the
    // rest are counted without it. -1: hidden.
    val stop = remember(said) {
        var n = 0
        IntArray(said.size) { if (said[it].isEmpty()) -1 else n++ }
    }
    val stops = stop.count { it >= 0 }
    val copyLabel = stringResource(R.string.copy_table)
    val copy = remember(t.source, copyLabel, context) {
        listOf(
            CustomAccessibilityAction(copyLabel) {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(copyLabel, t.source))
                true
            },
        )
    }
    // Which body cells are drawn as numbers (mono, never broken): those in a number column that
    // read as one, or as nothing ("—"). While a table streams in, a column held as numbers (see
    // rememberSteady) may get a sentence, or be typing one: that cell wraps in the text style, and
    // the column takes a text column's widths while it holds one, so the table doesn't grow
    // sideways as it types and snap back when it's done.
    val asNumber = remember(t) { t.rows.mapIndexed { i, row -> BooleanArray(cols) { c -> t.numeric[c] && (divider[i + 1] || isBlankCell(row[c]) || isQuantity(row[c])) } } }
    val numeric = remember(t) { BooleanArray(cols) { c -> t.numeric[c] && asNumber.all { it[c] } } }
    val aligns = t.aligns
    val headStyle = if (tracked) Type.label.copy(color = HN.inkDim) else Type.small.copy(color = HN.ink, fontWeight = FontWeight(600))

    Layout(
        content = {
            for (r in 0 until count) {
                val header = r == 0
                val line = rule[r]
                Box(
                    Modifier
                        .then(
                            when {
                                header -> Modifier.background(HeaderFill).then(if (body) Modifier.drawBehind { hairline(HN.line, bottom = true) } else Modifier)
                                line != null -> Modifier.drawBehind { hairline(line, bottom = false) }
                                else -> Modifier
                            },
                        )
                        .then(
                            if (stop[r] < 0) Modifier
                            else Modifier.clearAndSetSemantics {
                                contentDescription = said[r]
                                collectionItemInfo = CollectionItemInfo(rowIndex = stop[r], rowSpan = 1, columnIndex = 0, columnSpan = cols)
                                if (header) heading()
                                customActions = copy
                            },
                        ),
                )
                // A divider's cells are empty boxes: no width asked, no height (the row under it
                // draws its line).
                if (divider[r]) {
                    repeat(cols) { Box(Modifier) }
                } else {
                    for (c in 0 until cols) {
                        val align = when (aligns[c]) {
                            Align.Left -> TextAlign.Start
                            Align.Center -> TextAlign.Center
                            Align.Right -> TextAlign.End
                        }
                        val style = when {
                            header -> headStyle
                            // Numbers in mono, as the app shows them everywhere else.
                            asNumber[r - 1][c] -> Type.monoSmall.copy(color = color)
                            // Row names (a first text column) in ink, like a list's labels.
                            c == 0 -> Type.small.copy(color = HN.ink)
                            else -> Type.small.copy(color = color)
                        }
                        BasicText(
                            texts[r][c],
                            Modifier
                                .padding(
                                    start = if (c == 0) EdgePadH else CellPadH,
                                    end = if (c == cols - 1) EdgePadH else CellPadH,
                                    top = if (header) HeaderPadV else CellPadV,
                                    bottom = if (header) HeaderPadV else CellPadV,
                                )
                                .clearAndSetSemantics { },
                            style = style.copy(textAlign = align),
                        )
                    }
                }
            }
        },
        modifier = modifier.semantics { collectionInfo = CollectionInfo(rowCount = stops, columnCount = cols) },
    ) { measurables, constraints ->
        val per = cols + 1
        val rows = measurables.size / per
        // Each column's narrowest (its longest word) and widest (its longest cell on one line).
        // A number is never broken, so in a number column only the header's words count as least.
        val lo = FloatArray(cols)
        val hi = FloatArray(cols)
        for (r in 0 until rows) for (c in 0 until cols) {
            val m = measurables[r * per + 1 + c]
            val most = m.maxIntrinsicWidth(Constraints.Infinity).toFloat()
            val least = if (r > 0 && asNumber[r - 1][c]) most else m.minIntrinsicWidth(Constraints.Infinity).toFloat()
            lo[c] = maxOf(lo[c], least)
            hi[c] = maxOf(hi[c], most)
        }
        val viewport = when {
            constraints.minWidth > 0 -> constraints.minWidth
            constraints.hasBoundedWidth -> constraints.maxWidth
            else -> hi.sum().roundToInt()
        }.toFloat()
        // Minimum and comfortable widths grow with the font size, or large text squeezes into slivers.
        val target = columnWidths(
            lo, hi, numeric, viewport,
            minText = MinText.toPx() * fontScale,
            comfy = Comfy.toPx() * fontScale,
        )
        val shown = glide.at(target, smooth)
        // Whole pixels that still add up: each column ends where the running total rounds to.
        val x = IntArray(cols + 1)
        var run = 0f
        for (c in 0 until cols) { run += shown[c]; x[c + 1] = run.roundToInt() }
        val total = x[cols]
        val cells = Array(rows) { r -> Array(cols) { c -> measurables[r * per + 1 + c].measure(Constraints.fixedWidth((x[c + 1] - x[c]).coerceAtLeast(0))) } }
        // The cells in a row share their first baseline: mono numbers sit on the same line as the
        // Manrope text beside them, a pixel or two lower than their own top would put them.
        val drop = Array(rows) { r ->
            val base = IntArray(cols) { c -> cells[r][c][FirstBaseline] }
            val line = base.filter { it != AlignmentLine.Unspecified }.maxOrNull() ?: 0
            IntArray(cols) { c -> if (base[c] == AlignmentLine.Unspecified) 0 else line - base[c] }
        }
        val heights = IntArray(rows) { r -> (0 until cols).maxOf { c -> drop[r][c] + cells[r][c].height } }
        val backs = Array(rows) { r -> measurables[r * per].measure(Constraints.fixed(total, heights[r])) }
        layout(total.coerceIn(constraints.minWidth, constraints.maxWidth), heights.sum()) {
            var y = 0
            for (r in 0 until rows) {
                backs[r].place(0, y)
                // Equal padding and a shared baseline keep a row's tops within a few pixels: selection
                // takes text that overlaps vertically as one row, left to right, and rows top to bottom.
                for (c in 0 until cols) cells[r][c].place(x[c], y + drop[r][c])
                y += heights[r]
            }
            glide.aim(target, shown, scope, smooth)
        }
    }
}

/**
 * While a table streams in, a column once seen holding numbers stays one (on the right, its
 * numbers in mono, unbroken) until the table is done, so it moves at most once while its rows
 * arrive rather than hopping left and back as a row with a word in it comes and goes. Words that
 * arrive in it meanwhile wrap as text (see Grid). The finished table decides for itself.
 */
@Composable
private fun rememberSteady(t: MarkdownTable): MarkdownTable {
    // Plain memory, not state: it only ever follows the table it's handed.
    val seen = remember { HashSet<Int>() }
    if (!t.live) {
        seen.clear()
        return t
    }
    t.numeric.forEachIndexed { c, n -> if (n) seen += c }
    val numeric = List(t.columns) { t.numeric[it] || it in seen }
    return if (numeric == t.numeric) t else t.copy(numeric = numeric)
}

private fun DrawScope.hairline(color: Color, bottom: Boolean) {
    val stroke = 1.dp.toPx()
    val y = if (bottom) size.height - stroke / 2 else stroke / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
}

/**
 * What TalkBack says for each row: the header's names, then "System prompt. Tokens: 3.2k.
 * Percentage: 1.6%". Nothing for a divider, which is no stop.
 */
private fun spokenRows(t: MarkdownTable, cell: (String, String) -> String): List<String> {
    val names = t.header.map(::plain)
    val head = names.filter { it.isNotEmpty() }.joinToString(", ")
    return listOf(head) + t.rows.map { row ->
        if (isDivider(row)) return@map ""
        row.mapIndexedNotNull { c, md ->
            val value = plain(md)
            when {
                value.isEmpty() -> null
                c == 0 || names[c].isEmpty() -> value
                else -> cell(names[c], value)
            }
        }.joinToString(". ")
    }
}

/**
 * Column widths that glide to new ones instead of jumping: a streaming table's columns widen as
 * its rows arrive. A table's first layout takes its widths as they are, so a finished reply never
 * animates, and under Reduce Motion they always snap. Measuring reads where the glide is; placing
 * aims it, so it only moves after a real layout.
 */
private class Glide {
    private var from: FloatArray? = null
    private var to: FloatArray? = null
    private var progress by mutableFloatStateOf(1f)
    private var job: Job? = null

    fun at(target: FloatArray, smooth: Boolean): FloatArray {
        val a = from
        val b = to
        if (!smooth || a == null || b == null || b.size != target.size) return target
        val p = progress
        return FloatArray(b.size) { a[it] + (b[it] - a[it]) * p }
    }

    fun aim(target: FloatArray, shown: FloatArray, scope: CoroutineScope, smooth: Boolean) {
        val b = to
        if (b != null && b.contentEquals(target)) return
        job?.cancel()
        if (!smooth || b == null || b.size != target.size) {
            from = target
            to = target
            return
        }
        from = shown
        to = target
        progress = 0f
        job = scope.launch { animate(0f, 1f, animationSpec = tween(Motion.REVEAL, easing = Motion.Expo)) { v, _ -> progress = v } }
    }
}

/**
 * Fades the content's sides where there's more to scroll: none at an end, full a fade's width
 * away from it, so it's never a hard edge and says "more this way" without any chrome. Read in
 * draw, so scrolling never recomposes; the offscreen layer exists only while it can scroll.
 */
private fun Modifier.fadeSides(scroll: ScrollState): Modifier = this
    .graphicsLayer {
        val max = scroll.maxValue
        compositingStrategy = if (max in 1 until Int.MAX_VALUE) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val max = scroll.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val edge = EdgeFade.toPx().coerceAtMost(size.width / 2)
        val left = (scroll.value / edge).coerceIn(0f, 1f)
        val right = ((max - scroll.value) / edge).coerceIn(0f, 1f)
        if (left > 0f) {
            drawRect(
                Brush.horizontalGradient(0f to Color.Black.copy(alpha = 1f - left), 1f to Color.Black, startX = 0f, endX = edge),
                size = Size(edge, size.height), blendMode = BlendMode.DstIn,
            )
        }
        if (right > 0f) {
            drawRect(
                Brush.horizontalGradient(0f to Color.Black, 1f to Color.Black.copy(alpha = 1f - right), startX = size.width - edge, endX = size.width),
                topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height), blendMode = BlendMode.DstIn,
            )
        }
    }
