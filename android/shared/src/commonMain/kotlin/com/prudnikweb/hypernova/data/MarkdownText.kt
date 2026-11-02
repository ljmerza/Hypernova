package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable

// ─── Tables in replies ─────────────────────────────────────────────────────

/** Where a column's cells sit: as the separator's colons say, or on the right for numbers. */
enum class Align { Left, Center, Right }

/**
 * A GitHub-style table from a reply or a plan. [header] and [rows] keep the markdown inside each
 * cell, every row padded or cut to the header's width. [marks]: each column's alignment as the
 * separator's colons set it, null where they don't. [numeric]: a column of numbers ("3.2k",
 * "1.6%"), drawn in mono, which never wraps (its header may). [live]: a streaming reply ends in
 * it, so its last row may be cut mid-word; [headerOpen]: the header line itself is still arriving.
 * [source]: the markdown it came from, for "Copy table".
 */
@Immutable
data class MarkdownTable(
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
class TableAt(val table: MarkdownTable?, val end: Int)

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
fun tableAt(lines: List<String>, start: Int, streaming: Boolean = false): TableAt? {
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
            // The last row's last cell is still being typed until its closing pipe arrives, or
            // (a table without outer pipes) a line break after it.
            val open = end == lines.size && (!last.endsWith('|') || last.endsWith("\\|"))
            // A row just begun ("|") is nothing to draw yet, nor dashes still arriving
            // ("|---|--"): a divider shows as its line once whole, never as a row of dashes first.
            if (rows.last().all { it.isBlank() } || (open && PartialSeparator.matches(last))) rows = rows.dropLast(1)
            else if (open) typing = cells(last).size - 1
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
 * A row continues the table: it has a pipe and isn't a fence. As on GitHub, only a blank line or a
 * line without a pipe ends a table, never a row of dashes: a "|---|---|" divider over a totals row
 * stays in it (drawn as a line, see [isDivider]), "| - | - |" placeholders are rows (their dashes
 * don't decide a column), and a second table glued under the first, with no blank line between,
 * joins it: its header as a row, its separator as a divider.
 */
private fun isRow(lines: List<String>, i: Int): Boolean {
    val t = lines[i].trim()
    return t.isNotEmpty() && '|' in t && !t.startsWith("```")
}

/** A divider's cell: three or more dashes, colons allowed (a glued table's ":---:"). */
private val DividerCell = Regex("^:?-{3,}:?$")

/**
 * A body row of only dashes, empty cells aside ("|---|---|", or a glued table's separator): a
 * divider, drawn as a firmer hairline across the table rather than a row of "---", and no stop
 * for TalkBack. A dash or two ("| - | — |") is a placeholder for "nothing here", and stays a row.
 */
fun isDivider(row: List<String>): Boolean = row.any { it.isNotEmpty() } && row.all { it.isEmpty() || DividerCell.matches(it) }

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
    // holds numbers: "2h a" reads as one until "ago" arrives. Nor do dividers (":---:").
    val numeric = header.indices.map { c ->
        isNumberColumn(rows.indices.map { r -> if ((r == rows.lastIndex && c == typing) || isDivider(rows[r])) "" else rows[r][c] })
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
fun cells(line: String): List<String> {
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
fun isRule(line: String): Boolean = RuleLine.matches(line.trim())

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
fun isQuantity(cell: String): Boolean = '`' !in cell && Quantity.matches(plain(cell))

/** Says nothing about its column: empty, or a placeholder ("—", "n/a"). */
fun isBlankCell(cell: String): Boolean = plain(cell).let { it.isEmpty() || ('`' !in cell && Neutral.matches(it)) }

/** A column of numbers: at least one, and every other cell empty or a placeholder dash. */
private fun isNumberColumn(cells: List<String>): Boolean {
    var any = false
    for (c in cells) {
        when {
            isBlankCell(c) -> Unit
            isQuantity(c) -> any = true
            else -> return false
        }
    }
    return any
}

private val Br = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val Bold = Regex("\\*\\*")
private val MdLink = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
private val Spaces = Regex("\\s+")

/** Markdown's inline marks taken off, for TalkBack and one-line previews: **bold**, `code`, ~~strike~~, links as their text, <br> as a space. */
fun plain(md: String): String = md
    .replace(Br, " ")
    .replace(MdLink) { it.groupValues[1] }
    .replace("**", "").replace("~~", "").replace("`", "")
    .replace("\\|", "|")
    .replace(Spaces, " ")
    .trim()

/**
 * A cell as it's drawn: `<br>` (Claude's way to break a line in a cell) as a line break, except
 * inside code. While the cell is still being [typing], an unclosed ** or ` at its end is hidden
 * rather than shown raw for a moment.
 */
fun cellMarkdown(md: String, typing: Boolean = false): String {
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
                if (header == null) header = table.table?.header?.map(::plain)?.filter { it.isNotEmpty() }?.joinToString(", ")?.ifEmpty { null }
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

