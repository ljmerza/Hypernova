package com.prudnikweb.hypernova.ui.fx

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * A project's name in the sky for a slot [width] px wide (audit P6; owner, 2026-10-18:
 * "ZAZHIMAYU-POGODA-SANKT" read as another word): whole if it fits; else whole with its tracking
 * closed up, down to 60%; else cut after a word, at the last "-", "_", "." or space that fits, with
 * "…" ("ZAZHIMAYU-POGODA…"); only then cut mid-word. TalkBack's description of the sky keeps the
 * whole name.
 */
internal fun fitSkyName(measurer: TextMeasurer, name: String, style: TextStyle, width: Int): TextLayoutResult {
    val free = Constraints(maxWidth = Constraints.Infinity)
    val whole = measurer.measure(name, style, maxLines = 1, constraints = free)
    if (whole.size.width <= width) return whole
    for (k in floatArrayOf(0.8f, 0.6f)) {
        val t = measurer.measure(name, style.copy(letterSpacing = style.letterSpacing * k), maxLines = 1, constraints = free)
        if (t.size.width <= width) return t
    }
    val tight = style.copy(letterSpacing = style.letterSpacing * 0.6f)
    for (cut in nameCuts(name)) {
        val t = measurer.measure(cut, tight, maxLines = 1, constraints = free)
        if (t.size.width <= width) return t
    }
    return measurer.measure(name, tight, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = width))
}

/**
 * A long name cut after each of its words, longest first, each ending in "…": the separators are
 * "-", "_", "." and spaces ("ZAZHIMAYU-POGODA-SANKT-PETERBURG" → "ZAZHIMAYU-POGODA-SANKT…",
 * "ZAZHIMAYU-POGODA…", "ZAZHIMAYU…"). A name with no separator after its first letter has none.
 */
internal fun nameCuts(name: String): List<String> {
    val out = ArrayList<String>()
    for (i in name.length - 1 downTo 1) {
        if (name[i] !in "-_. ") continue
        val head = name.substring(0, i).trimEnd('-', '_', '.', ' ')
        if (head.isNotEmpty() && (out.isEmpty() || out.last() != "$head…")) out += "$head…"
    }
    return out
}
