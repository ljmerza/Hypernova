package com.prudnikweb.hypernova.data

/**
 * Searching Home's sessions (idea HM8): History's search ([HistorySearch]) over the live ones, by
 * title, project, the folder's own name, last line and short hex id, any case; from 3 characters
 * merged with the Mac's full-text hits on them. Home then groups what's left as ever (empty groups
 * hide, the counts are the matches). Pure, unit-tested; ui/home/HomeScreen.kt draws it.
 */
object HomeSearch {
    /** What a Home session is searched by; the folder only when its name isn't the project's. */
    fun text(s: Session): String {
        val folder = s.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() && it != s.project }
        return listOfNotNull(s.name, s.project, folder, s.activity, s.hex).joinToString(" ")
    }

    /** [sessions] that [query] finds (all of them for a blank one), with [hits]' sessions among them added. */
    fun find(sessions: List<Session>, query: String, hits: TextHits? = null): List<Session> {
        val local = HistorySearch.find(sessions, query, by = ::text)
        if (query.isBlank()) return local
        return HistorySearch.merge(sessions, local, hits)
    }
}
