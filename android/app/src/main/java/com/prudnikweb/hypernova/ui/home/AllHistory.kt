package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.HistorySearch
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Servers
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.TextHit
import com.prudnikweb.hypernova.ui.automations.HistoryEntry
import com.prudnikweb.hypernova.ui.automations.foldRuns

/*
 * History with "All servers" (docs/DECISIONS.md, "All servers on Home"): every paired computer's
 * ended sessions in one list, most recent first under the days they ended on, each row naming its
 * computer, and opening one (or an automation's runs) going to that computer. Merged by time rather
 * than grouped by computer: History's own structure is the days, and a computer's group would split
 * each day in two. One computer is the same list untagged, so History draws both the same way.
 * Pure, generic in the hub ([H]: a Hub in the app, anything in tests); ui/home/History.kt draws it.
 */

/**
 * A History row: its [entry] (a session, or an automation's folded runs) and the computer it's on
 * ([server] null: History shows one computer, no tag). [live]: that computer's list is current
 * ([Servers.fresh]); otherwise the row is dimmed and tagged offline. [hit]: what that computer's
 * full-text search found inside it.
 */
class HistoryItem<H>(
    val server: String?,
    val name: String?,
    val hub: H,
    val entry: HistoryEntry,
    val live: Boolean = true,
    val hit: TextHit? = null,
) {
    val session: Session get() = entry.session
    /** The list's key: the row's own with one computer, its computer's and the row's with several (ids may repeat across computers). */
    val key: String get() = AllHome.key(server, entry.key)
    /** Its computer isn't linked: tagged offline and dimmed. */
    val offline: Boolean get() = server != null && !live
}

object AllHistory {
    /** What History lists of a computer's sessions: the ended ones, but for what a restart ended lately (Home keeps those). */
    fun ended(sessions: List<Session>): List<Session> = sessions.filter { it.phase == Phase.Ended && !it.cutByRestart }

    /** How many ended sessions all of [parts] hold (the seam's count). */
    fun <H> endedCount(parts: List<ServerPart<H>>): Int = parts.sumOf { ended(it.sessions).size }

    /** The project chips over every computer's ended sessions: a project of the same name on two is one chip. */
    fun <H> projects(parts: List<ServerPart<H>>): List<String> = HistorySearch.projects(parts.flatMap { ended(it.sessions) })

    /**
     * History's rows over [parts]: each computer's ended sessions that [query] finds, in [project]
     * only when given, with that computer's own full-text hits merged in ([HistorySearch.merge]) and
     * its automations' runs folded ([foldRuns], each computer's on its own), then all of them most
     * recent first (the same moment on two computers in the order paired). [tagged] false: one
     * computer, its rows untagged, keyed and live as History always had them.
     */
    fun <H> rows(parts: List<ServerPart<H>>, query: String = "", project: String? = null, tagged: Boolean = true): List<HistoryItem<H>> =
        parts.flatMap { p ->
            val ended = ended(p.sessions)
            val local = HistorySearch.find(ended, query, project)
            val found = HistorySearch.merge(ended, local, p.hits, project)
            val live = !tagged || Servers.fresh(p.state)
            foldRuns(found).map { e ->
                HistoryItem(if (tagged) p.id else null, if (tagged) p.name else null, p.hub, e, live, p.hits?.of(e.session.id))
            }
        }.sortedByDescending { it.session.updatedAt }

    /** The names of [parts] whose computer isn't linked, in the order paired: History says their rows are as last seen. */
    fun <H> offline(parts: List<ServerPart<H>>): List<String> = parts.filter { !Servers.fresh(it.state) }.map { it.name }
}
