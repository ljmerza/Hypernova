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
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ─── Tables in replies ─────────────────────────────────────────────────────

/** Where a column's cells sit: as the separator's colons say, or on the right for numbers. */
internal enum class Align { Left, Center, Right }

/**
 * A GitHub-style table from a reply or a plan. [header] and [rows] keep the markdown inside each
 * cell, every row padded or cut to the header's width. [marks]: each column's alignment as the
 * separator's colons set it, null where they don't. [numeric]: a column of numbers ("3.2k",
 * "1.6%"), drawn in mono, which never wraps (its header may). [live]: a streaming reply ends in
 * it, so its last row may be cut mid-word; [headerOpen]: the header line itself is still arriving.
 * [source]: the markdown it came from, for "Copy table".
 */
@Immutable
internal data class MarkdownTable(
    val header: List<String>,
    val marks: List<Align?>,
    val numeric: List<Boolean>,
    val rows: List<List<String>>,
    val source: String,
    val live: Boolean = false,
    val headerOpen: Boolean = false,
) {
    val columns: Int get() = header.size

    /** Where each column's cells sit: as the colons say, or on the right for numbers. */
    val aligns: List<Align> get() = List(columns) { marks.getOrNull(it) ?: if (numeric[it]) Align.Right else Align.Left }
}

/**
 * A table found among a reply's lines, and the index of the first line after it. [table] is null
 * for a table only just begun while streaming ("|"): those lines draw nothing yet.
 */
internal class TableAt(val table: MarkdownTable?, val end: Int)

/**
 * The table starting at [lines]`[start]`, or null. GitHub's rules, a little more forgiving: a
 * header row, then a separator row (`|---|:--:|`, colons for alignment) with as many cells, outer
 * pipes optional, indented or not (a table inside a list item). Rows follow up to a blank line or
 * a line without a pipe, so a sentence written right under a table stays a sentence (GitHub would
 * make it a row). While [streaming], a pipe-led line at the very end, or followed only by a
 * separator still being typed, is already a table with just its header: a table arriving word by
 * word never shows as a paragraph of pipes first and then jumps. Until one of its header cells has
 * some text ("|", "|  |") it draws nothing, so an empty card doesn't flash up for a moment.
 */
internal fun tableAt(lines: List<String>, start: Int, streaming: Boolean = false): TableAt? {
    val head = lines[start].trim()
    if ('|' !in head) return null
    val header = cells(head)
    val sep = lines.getOrNull(start + 1)?.trim()
    if (sep != null && separates(sep, header.size)) {
        var end = start + 2
        while (end < lines.size && isRow(lines, end)) end++
        val live = streaming && blankFrom(lines, end)
        var rows = lines.subList(start + 2, end).map { fit(cells(it), header.size) }
        var typing = -1
        if (live && rows.isNotEmpty()) {
            val last = lines[end - 1].trim()
            // A row just begun ("|") is nothing to draw yet. Otherwise its last cell is still
            // being typed until its closing pipe arrives.
            if (rows.last().all { it.isBlank() }) rows = rows.dropLast(1)
            else if (!last.endsWith('|') || last.endsWith("\\|")) typing = cells(last).size - 1
        }
        return TableAt(table(header, cells(sep), rows, lines.subList(start, end), live, typing = typing), end)
    }
    if (streaming && head.startsWith('|')) {
        val open = blankFrom(lines, start + 1)
        if (open || (sep != null && PartialSeparator.matches(sep) && blankFrom(lines, start + 2))) {
            val t = if (header.all { it.isBlank() }) null else table(header, emptyList(), emptyList(), lines.subList(start, lines.size), live = true, headerOpen = open)
            return TableAt(t, lines.size)
        }
    }
    return null
}

/** [sep] is the separator under a header of [n] cells. A lone "| text" over "---" is a sentence and a rule, not a one-column table. */
private fun separates(sep: String, n: Int): Boolean = Separator.matches(sep) && ('|' in sep || n > 1) && cells(sep).size == n

/**
 * A row continues the table: it has a pipe, isn't a fence, and doesn't head a new table of its own.
 * It heads one only over a separator [tableAt] itself would take, with three dashes or more in each
 * cell as Claude writes them: a one-column table's last row stays a row over a "---" rule, and a row
 * of "-" placeholders ("| - | - |") is a row, not a separator that splits the table.
 */
private fun isRow(lines: List<String>, i: Int): Boolean {
    val t = lines[i].trim()
    if (t.isEmpty() || '|' !in t || t.startsWith("```")) return false
    val next = lines.getOrNull(i + 1)?.trim() ?: return true
    return !(separates(next, cells(t).size) && cells(next).all { cell -> cell.count { it == '-' } >= 3 })
}

private fun blankFrom(lines: List<String>, from: Int) = (from until lines.size).all { lines[it].isBlank() }

private fun fit(row: List<String>, n: Int): List<String> = List(n) { row.getOrElse(it) { "" } }

private fun table(
    header: List<String>,
    marks: List<String>,
    rows: List<List<String>>,
    source: List<String>,
    live: Boolean,
    headerOpen: Boolean = false,
    typing: Int = -1,
): MarkdownTable {
    // The cell still being typed ([typing], in the last row) doesn't decide whether its column
    // holds numbers: "2h a" reads as one until "ago" arrives.
    val numeric = header.indices.map { c ->
        isNumberColumn(rows.indices.map { r -> if (r == rows.lastIndex && c == typing) "" else rows[r][c] })
    }
    val set = header.indices.map { c ->
        val m = marks.getOrElse(c) { "" }
        when {
            m.startsWith(':') && m.endsWith(':') && m.length > 1 -> Align.Center
            m.endsWith(':') -> Align.Right
            m.startsWith(':') -> Align.Left
            else -> null
        }
    }
    return MarkdownTable(header, set, numeric, rows, source.joinToString("\n") { it.trim() }, live, headerOpen)
}

/**
 * A row's cells: the outer pipes dropped, split on the pipes between cells. `\|` is a pipe inside
 * a cell, and a pipe inside `code` doesn't split either: Claude doesn't always escape those.
 */
internal fun cells(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith('|')) s = s.substring(1)
    if (s.endsWith('|') && !s.endsWith("\\|")) s = s.dropLast(1)
    val out = ArrayList<String>()
    val cell = StringBuilder()
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        when {
            ch == '\\' && s.getOrNull(i + 1) == '|' -> { cell.append('|'); i += 2 }
            ch == '`' -> {
                val run = ticks(s, i)
                val close = closingTicks(s, i + run, run)
                if (close < 0) { cell.append(s, i, i + run); i += run }
                else { cell.append(s.substring(i, close + run).replace("\\|", "|")); i = close + run }
            }
            ch == '|' -> { out += cell.toString().trim(); cell.clear(); i++ }
            else -> { cell.append(ch); i++ }
        }
    }
    out += cell.toString().trim()
    return out
}

private fun ticks(s: String, at: Int): Int {
    var k = at
    while (k < s.length && s[k] == '`') k++
    return k - at
}

/** Where a code span opened by [n] backticks closes (a run of exactly [n]), or -1 if it never does. */
private fun closingTicks(s: String, from: Int, n: Int): Int {
    var j = from
    while (j < s.length) {
        if (s[j] != '`') { j++; continue }
        val run = ticks(s, j)
        if (run == n) return j
        j += run
    }
    return -1
}

private val Separator = Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?$")
/** A separator row still being typed: "|----------|-----". */
private val PartialSeparator = Regex("^\\|?(\\s*:?-*:?\\s*\\|)*\\s*:?-*:?\\s*$")
private val RuleLine = Regex("^(?:(?:-[ \\t]*){3,}|(?:\\*[ \\t]*){3,}|(?:_[ \\t]*){3,})$")

/** A thematic break ("---", "***", "___", "- - -") between parts of a reply. */
internal fun isRule(line: String): Boolean = RuleLine.matches(line.trim())

// A quantity: "3.2k", "1.6%", "$0.42", "~11 px", "1 234", "12:30", "647.8k tokens (64.8%)", "68.7k / 200k".
// "2h ago" and "0–4.8 s" read as text. A unit is a few letters right after the number, or one word.
// The bracket after it is short ("(64.8%)", "(3 skipped)"): "41 (3 skipped because they need a
// device)" is a sentence, and a number column would never wrap it.
private const val NUM = "[~≈<>≤≥±+\\-−]?\\s?[\$€£¥₽]?\\d(?:[\\d,.:]|[ \\u00A0\\u202F](?=\\d{3}(?!\\d)))*"
private const val UNIT = "(?:\\s?(?:%|[\\p{L}×]{1,3}(?!\\p{L})))?(?:\\s?[\$€£¥₽])?"
private val Quantity = Regex("^$NUM$UNIT(?:\\s(?!ago\\b)\\p{L}{1,12})?(?:\\s?/\\s?$NUM$UNIT)?(?:\\s?\\(\\s?[~≈±+\\-−]?\\d[^()]{0,16}\\))?\$")
/** Cells that say "nothing here" and don't decide a column: "—", "-", "n/a", "?". */
private val Neutral = Regex("^(?:[-–—?…·]+|n/?a)$", RegexOption.IGNORE_CASE)

/** True when [cell] reads as a number ("3.2k", "1.6%", "**2**"). Code is never a number: `99472fe` is a hash. */
internal fun isQuantity(cell: String): Boolean = '`' !in cell && Quantity.matches(plain(cell))

/** A column of numbers: at least one, and every other cell empty or a placeholder dash. */
private fun isNumberColumn(cells: List<String>): Boolean {
    var any = false
    for (c in cells) {
        val p = plain(c)
        when {
            p.isEmpty() || ('`' !in c && Neutral.matches(p)) -> Unit
            isQuantity(c) -> any = true
            else -> return false
        }
    }
    return any
}

private val Br = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val Bold = Regex("\\*\\*")
private val Link = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
private val Spaces = Regex("\\s+")

/** Markdown's inline marks taken off, for TalkBack and one-line previews: **bold**, `code`, ~~strike~~, links as their text, <br> as a space. */
internal fun plain(md: String): String = md
    .replace(Br, " ")
    .replace(Link) { it.groupValues[1] }
    .replace("**", "").replace("~~", "").replace("`", "")
    .replace("\\|", "|")
    .replace(Spaces, " ")
    .trim()

/**
 * A cell as it's drawn: `<br>` (Claude's way to break a line in a cell) as a line break, except
 * inside code. While the cell is still being [typing], an unclosed ** or ` at its end is hidden
 * rather than shown raw for a moment.
 */
internal fun cellMarkdown(md: String, typing: Boolean = false): String {
    val out = StringBuilder()
    var i = 0
    while (i < md.length) {
        if (md[i] == '`') {
            val run = ticks(md, i)
            val close = closingTicks(md, i + run, run)
            val end = if (close < 0) i + run else close + run
            out.append(md, i, end)
            i = end
            continue
        }
        val br = Br.matchAt(md, i)
        if (br != null) { out.append('\n'); i = br.range.last + 1 } else { out.append(md[i]); i++ }
    }
    var s = out.toString()
    if (typing) {
        if (Bold.findAll(s).count() % 2 == 1) s.lastIndexOf("**").let { s = s.removeRange(it, it + 2) }
        if (s.count { it == '`' } % 2 == 1) s.lastIndexOf('`').let { s = s.removeRange(it, it + 1) }
    }
    return s
}

private val LinePrefix = Regex("^(?:#{1,6}\\s+|>\\s?|[-*+]\\s+(?:\\[[ xX]]\\s+)?|\\d+[.)]\\s+)")

/**
 * One readable line from a reply, for a session card or a notification: its first line of prose,
 * without markdown marks ("## Done" → "Done", "**Fixed** the `parser`" → "Fixed the parser").
 * Code (everything inside a fence), rules, separators and table rows are skipped. A reply that is
 * only a table, or was cut short inside one, reads as the table's header: "Category · Tokens ·
 * Percentage", but only for a real table (a header over a separator) outside code. Null when
 * there's nothing to read.
 */
fun readableLine(text: String): String? {
    val lines = text.lines()
    var header: String? = null
    var fenced = false
    var i = 0
    while (i < lines.size) {
        val t = lines[i].trim()
        i++
        if (t.startsWith("```")) { fenced = !fenced; continue }
        if (fenced || t.isEmpty() || isRule(t) || Separator.matches(t)) continue
        // A header over a separator, read as if still streaming: a preview is often cut off
        // inside a table, even inside its separator.
        val next = lines.getOrNull(i)?.trim().orEmpty()
        if (next.isNotEmpty() && (Separator.matches(next) || PartialSeparator.matches(next))) {
            val table = tableAt(lines, i - 1, streaming = true)
            if (table != null) {
                if (header == null) header = table.table?.header?.map(::plain)?.filter { it.isNotEmpty() }?.joinToString(" · ")?.ifEmpty { null }
                i = maxOf(table.end, i)
                continue
            }
        }
        // A row of a table, or pipes that never became one.
        if (t.startsWith('|')) continue
        var s = t
        while (true) {
            val m = LinePrefix.find(s) ?: break
            s = s.substring(m.range.last + 1)
        }
        val line = plain(s)
        if (line.isNotEmpty()) return line
    }
    return header
}

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
    val said = remember(t, context) {
        spokenRows(t) { name, value -> context.getString(R.string.table_cell, name, value) }
    }
    // A row with nothing in it (every cell empty) isn't a stop for TalkBack; the rest are counted
    // without it. -1: hidden.
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
    val numeric = remember(t.numeric) { t.numeric.toBooleanArray() }
    val aligns = t.aligns
    val headStyle = if (tracked) Type.label.copy(color = HN.inkDim) else Type.small.copy(color = HN.ink, fontWeight = FontWeight(600))
    val count = t.rows.size + 1

    Layout(
        content = {
            for (r in 0 until count) {
                val header = r == 0
                Box(
                    Modifier
                        .then(
                            when {
                                header -> Modifier.background(HeaderFill).then(if (count > 1) Modifier.drawBehind { hairline(HN.line, bottom = true) } else Modifier)
                                r > 1 -> Modifier.drawBehind { hairline(HN.lineSoft, bottom = false) }
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
                for (c in 0 until cols) {
                    val align = when (aligns[c]) {
                        Align.Left -> TextAlign.Start
                        Align.Center -> TextAlign.Center
                        Align.Right -> TextAlign.End
                    }
                    val style = when {
                        header -> headStyle
                        // Numbers in mono, as the app shows them everywhere else.
                        t.numeric[c] -> Type.monoSmall.copy(color = color)
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
            val least = if (numeric[c] && r > 0) most else m.minIntrinsicWidth(Constraints.Infinity).toFloat()
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
 * While a table streams in, a column once seen holding numbers stays one (on the right, in mono,
 * unbroken) until the table is done, so it moves at most once while its rows arrive rather than
 * hopping left and back as a row with a word in it comes and goes. The finished table decides for
 * itself.
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

/** What TalkBack says for each row: the header's names, then "System prompt. Tokens: 3.2k. Percentage: 1.6%". */
private fun spokenRows(t: MarkdownTable, cell: (String, String) -> String): List<String> {
    val names = t.header.map(::plain)
    val head = names.filter { it.isNotEmpty() }.joinToString(", ")
    return listOf(head) + t.rows.map { row ->
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
