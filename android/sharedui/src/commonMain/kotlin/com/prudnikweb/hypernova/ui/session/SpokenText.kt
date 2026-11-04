package com.prudnikweb.hypernova.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * What's worth hearing of a reply: its words without the markdown. Code blocks are left out
 * (a voice spelling out code helps nobody), links read as their text, a table's cells as a list.
 */
internal fun spokenText(markdown: String): String {
    val out = StringBuilder()
    var fence = false
    for (raw in markdown.lines()) {
        val t = raw.trim()
        if (t.startsWith("```") || t.startsWith("~~~")) {
            fence = !fence
            continue
        }
        if (fence) continue
        // A table's rule line ("|---|:--|") says nothing.
        if (t.startsWith("|") && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }) continue
        var line = t
            .replace(Regex("^#{1,6}\\s+"), "")
            .replace(Regex("^>\\s?"), "")
            .replace(Regex("^([-*+]|\\d+[.)])\\s+"), "")
            .replace(Regex("^\\[[ xX]]\\s+"), "")
        if (line.startsWith("|")) line = line.trim('|').split('|').joinToString(", ") { it.trim() }
        line = line
            .replace(Regex("!?\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
            .replace(Regex("(?<![\\w*])[*_](\\S.*?)[*_](?![\\w*])"), "$1")
            .replace("`", "")
        if (line.isBlank()) {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
        } else {
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append(' ')
            out.append(line)
            // A heading or list item reads as its own sentence.
            if (!line.last().let { it == '.' || it == '!' || it == '?' || it == ':' || it == ',' } && (t.startsWith("#") || t.startsWith("-") || t.startsWith("*") || t.firstOrNull()?.isDigit() == true)) out.append('.')
        }
    }
    return out.toString().trim()
}
