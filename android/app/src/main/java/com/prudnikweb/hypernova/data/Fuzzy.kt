package com.prudnikweb.hypernova.data

/**
 * Typing a few letters to find a name in a long list (projects, models): every word of the query
 * must be found in order, letter by letter; whole names, starts and word starts count most. Pure,
 * unit-tested.
 */
object Fuzzy {
    /**
     * How well [query] matches [text]: null when one of its words doesn't, higher is better. Case
     * and accents of ASCII letters aside, a word scores most as the whole text, then as its start,
     * a word's start inside it ("g2" in "even_realities/g2-local"), anywhere in it, and last as
     * letters in order ("hnv" in "hypernova"), the closer together the better.
     */
    fun score(query: String, text: String): Int? {
        val words = words(query)
        if (words.isEmpty()) return 0
        var total = 0
        for (w in words) total += word(w, text) ?: return null
        return total
    }

    /** A word found as itself (whole, a start, a word's start, inside), not only as letters in order. */
    const val STRONG = 300

    /**
     * Of [scored] matches (each with its weakest word's score), the ones worth showing: when some
     * matched every word as itself, only those; letters scattered in order only when nothing did.
     */
    fun <T> keepStrong(scored: List<Pair<T, Int>>): List<T> {
        val strong = scored.filter { it.second >= STRONG }
        return (strong.ifEmpty { scored }).map { it.first }
    }

    /** The query's words, lower case. */
    fun words(query: String): List<String> = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** One word of the query against [text]; null when its letters aren't all there in order. */
    fun word(w: String, text: String): Int? {
        val t = text.lowercase()
        if (t == w) return 1000
        if (t.startsWith(w)) return 800 - (t.length - w.length).coerceAtMost(100)
        var at = t.indexOf(w)
        while (at > 0) {
            if (boundary(text, at)) return 600 - at.coerceAtMost(100)
            at = t.indexOf(w, at + 1)
        }
        val inside = t.indexOf(w)
        if (inside >= 0) return 400 - inside.coerceAtMost(100)
        // Letters in order: each found as early as it can be, a word's start or a run scoring more.
        var score = 100
        var from = 0
        var last = -2
        for (c in w) {
            val i = t.indexOf(c, from)
            if (i < 0) return null
            if (i == last + 1) score += 8
            if (boundary(text, i)) score += 10
            score -= (i - from).coerceAtMost(12)
            last = i
            from = i + 1
        }
        return score.coerceAtLeast(1)
    }

    /** Where a word starts: the beginning, after a separator, or a capital after a small letter. */
    private fun boundary(text: String, i: Int): Boolean {
        if (i <= 0) return true
        val prev = text[i - 1]
        if (prev in SEPARATORS) return true
        return text[i].isUpperCase() && prev.isLowerCase()
    }

    private const val SEPARATORS = " -_./:~"
}
