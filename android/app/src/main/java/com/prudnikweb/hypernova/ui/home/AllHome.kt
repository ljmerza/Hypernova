package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.HomeSearch
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.Servers
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TextHits

/*
 * Home's "All servers" (docs/DECISIONS.md, "All servers on Home"): every paired computer's sessions
 * in Home's three groups and sorts, each card with the computer it's on, and everything a card does
 * (Allow and Deny, its menu, its swipes, opening it) going to that computer's hub. Pure, generic in
 * the hub ([H]: a Hub in the app, anything in tests); ui/home/HomeScreen.kt draws it.
 */

/** One paired computer's part of Home: its id and name, its link, its hub, its sessions and its full-text hits. */
class ServerPart<H>(
    val id: String,
    val name: String,
    val state: LinkState,
    val hub: H,
    val sessions: List<Session>,
    val hits: TextHits? = null,
)

/**
 * A card on Home: its [session] and the computer it's on ([server] null: Home shows one computer,
 * no tag). [live]: that computer's list is current ([Servers.fresh]); otherwise the card is dimmed,
 * tagged offline, and only opens.
 */
class HomeCard<H>(
    val server: String?,
    val name: String?,
    val hub: H,
    val session: Session,
    val live: Boolean = true,
) {
    /** The list's key: the session's id on one computer, the computer's and the session's with several (ids may repeat across computers). */
    val key: String get() = AllHome.key(server, session.id)
    /** Its computer isn't linked: the tag says offline, the card is dimmed and only opens. */
    val offline: Boolean get() = server != null && !live
}

/** A row of the New session server picker: [enabled] only while it's linked (folders and models come from it). */
data class ServerPick(val id: String, val name: String, val state: LinkState, val enabled: Boolean)

object AllHome {
    /**
     * What Home lists of a computer's sessions: what's alive, and what a restart of the Mac ended
     * lately; a conversation that switched agents as its newest session.
     */
    fun homeSessions(sessions: List<Session>): List<Session> =
        sessions.filter { (it.phase != Phase.Ended || it.cutByRestart) && it.switchedTo == null }

    fun key(server: String?, id: String): String = if (server == null) id else "$server:$id"

    /**
     * Every computer's cards: its Home sessions, narrowed by [narrow] (the project filter), then what
     * [query] finds among them with its own full-text hits ([HomeSearch], each computer's on its
     * own). In the order paired; [homeGroupsOf] sorts them.
     */
    fun <H> cards(parts: List<ServerPart<H>>, query: String = "", narrow: (List<Session>) -> List<Session> = { it }): List<HomeCard<H>> =
        parts.flatMap { p ->
            val mine = narrow(homeSessions(p.sessions))
            val found = if (query.isBlank()) mine else HomeSearch.find(mine, query, p.hits)
            val live = Servers.fresh(p.state)
            found.map { HomeCard(p.id, p.name, p.hub, it, live) }
        }

    /** Home's groups of [cards], as [homeGroups] has them; the same name and id on two computers by the computer's name. */
    internal fun <H> groups(cards: List<HomeCard<H>>): List<Pair<HomeGroup, List<HomeCard<H>>>> =
        homeGroupsOf(cards, { it.session }, compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })

    /** The card [key] names, so what it does goes to its own computer's hub (null: it's gone). */
    fun <H> cardOf(cards: List<HomeCard<H>>, key: String): HomeCard<H>? = cards.firstOrNull { it.key == key }

    /** How many of [cards] need the owner. */
    fun <H> needsYou(cards: List<HomeCard<H>>): Int = cards.count { it.session.state == SessionState.NeedsYou }

    /** The New session picker's rows: every paired computer in the switcher's order, only a linked one to pick. */
    fun picks(servers: List<ServerStatus>): List<ServerPick> =
        servers.map { ServerPick(it.id, it.name, it.state, enabled = it.state == LinkState.Connected) }
}
