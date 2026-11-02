package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable

/**
 * Folding JSON text on a phone (docs/DECISIONS.md, "JSON mode"). Folding only decides what's drawn: [JsonRows.join] of any
 * rows is the text they were built from, byte for byte, with what's folded kept whole in [FoldRow.hidden].
 */
object JsonFold {
    /** A multi-line object or array below the top level: [path] names it across rebuilds, [count] is its keys or items. */
    @Immutable
    data class Region(val startLine: Int, val endLine: Int, val isObject: Boolean, val count: Int, val depth: Int, val path: String)

    @Immutable
    sealed interface Parsed {
        @Immutable
        data class Ok(val regions: List<Region>) : Parsed

        /** Where it stops being JSON, counted from 1. */
        @Immutable
        data class Bad(val line: Int, val column: Int) : Parsed
    }

    private class Stop(val at: Int) : Exception()

    /** Strict JSON, with where each object and array starts and ends. */
    fun parse(text: String): Parsed {
        val regions = ArrayList<Region>()
        var i = 0
        var line = 0
        fun fail(): Nothing = throw Stop(i)
        fun space() {
            while (i < text.length && text[i] in " \t\r\n") {
                if (text[i] == '\n') line++
                i++
            }
        }
        fun string(): String {
            val from = ++i
            while (i < text.length && text[i] != '"') {
                val c = text[i]
                if (c < ' ') fail()
                if (c == '\\') {
                    i++
                    val e = text.getOrNull(i) ?: fail()
                    if (e == 'u') {
                        if (i + 4 >= text.length || (1..4).any { !text[i + it].isHexDigit() }) fail()
                        i += 4
                    } else if (e !in "\"\\/bfnrt") fail()
                }
                i++
            }
            if (i >= text.length) fail()
            return text.substring(from, i++)
        }
        fun value(depth: Int, path: String) {
            if (depth > 200) fail()
            space()
            val c = text.getOrNull(i) ?: fail()
            when {
                c == '{' || c == '[' -> {
                    val isObject = c == '{'
                    val close = if (isObject) '}' else ']'
                    val start = line
                    var count = 0
                    i++
                    space()
                    if (text.getOrNull(i) == close) i++
                    else {
                        while (true) {
                            if (isObject) {
                                space()
                                if (text.getOrNull(i) != '"') fail()
                                val key = string()
                                space()
                                if (text.getOrNull(i) != ':') fail()
                                i++
                                value(depth + 1, if (path.isEmpty()) key else "$path.$key")
                            } else value(depth + 1, "$path[$count]")
                            count++
                            space()
                            val next = text.getOrNull(i) ?: fail()
                            if (next == ',') {
                                i++
                                continue
                            }
                            if (next != close) fail()
                            i++
                            break
                        }
                    }
                    if (depth >= 1 && line > start) regions += Region(start, line, isObject, count, depth, path)
                }
                c == '"' -> string()
                else -> {
                    val m = LITERAL.matchAt(text, i) ?: fail()
                    i += m.value.length
                }
            }
        }
        return try {
            value(0, "")
            space()
            if (i < text.length) fail()
            Parsed.Ok(regions.sortedBy { it.startLine })
        } catch (s: Stop) {
            val before = text.substring(0, s.at.coerceAtMost(text.length))
            Parsed.Bad(before.count { it == '\n' } + 1, before.length - before.lastIndexOf('\n'))
        }
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
    private val LITERAL = Regex("true|false|null|-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    /** A file longer than this opens with its top level showing and what's below it folded. */
    const val LONG_FILE = 40

    /** What a file opens with folded. */
    fun opening(text: String, regions: List<Region>): Set<String> =
        if (text.count { it == '\n' } + 1 > LONG_FILE) regions.mapTo(HashSet()) { it.path } else emptySet()
}

/** One row of the editor: text to edit, with [hidden] after it when it's folded. [region]: it can fold. [firstLine]: its number, from 1. */
@Immutable
data class FoldRow(val text: String, val hidden: String?, val region: JsonFold.Region?, val firstLine: Int) {
    /** How many lines it draws. */
    val lines: Int get() = text.count { it == '\n' } + 1
}

object JsonRows {
    /** [text]'s rows with [folded] folded, or, if it isn't valid JSON, one row of plain text and where it stopped. */
    fun build(text: String, folded: Set<String>): Pair<List<FoldRow>, JsonFold.Parsed.Bad?> {
        val parsed = JsonFold.parse(text)
        if (parsed is JsonFold.Parsed.Bad) return listOf(FoldRow(text, null, null, 1)) to parsed
        val regions = (parsed as JsonFold.Parsed.Ok).regions
        // The first to start on a line is the outermost, and a line has one control.
        val byLine = HashMap<Int, JsonFold.Region>()
        for (r in regions.sortedBy { it.depth }) byLine.putIfAbsent(r.startLine, r)
        val lines = text.split('\n')
        val rows = ArrayList<FoldRow>()
        var i = 0
        var run = -1
        fun flush(to: Int) {
            if (run >= 0) rows += FoldRow(lines.subList(run, to).joinToString("\n"), null, null, run + 1)
            run = -1
        }
        while (i < lines.size) {
            val r = byLine[i]
            if (r == null) {
                if (run < 0) run = i
                i++
                continue
            }
            flush(i)
            if (r.path in folded) {
                rows += FoldRow(lines[i], lines.subList(i + 1, r.endLine + 1).joinToString("\n"), r, i + 1)
                i = r.endLine + 1
            } else {
                rows += FoldRow(lines[i], null, r, i + 1)
                i++
            }
        }
        flush(lines.size)
        return rows to null
    }

    /** The whole text: what's saved, folded or not. */
    fun join(rows: List<FoldRow>): String = rows.joinToString("\n") { if (it.hidden != null) "${it.text}\n${it.hidden}" else it.text }

    /** The rows' line numbers again after an edit changed how many lines one has. */
    fun renumber(rows: List<FoldRow>): List<FoldRow> {
        var line = 1
        return rows.map { r ->
            val out = r.copy(firstLine = line)
            line += r.lines + (r.hidden?.count { it == '\n' }?.plus(1) ?: 0)
            out
        }
    }

    /** What a folded row's last line closes with, comma and all: `}`, `],`. */
    fun closing(row: FoldRow): String = row.hidden?.substringAfterLast('\n')?.trim().orEmpty()
}
