package com.prudnikweb.hypernova.ui.settings

import com.prudnikweb.hypernova.data.LinkState

/**
 * What Settings shows and where (docs/DECISIONS.md, "Settings in sub pages"). The tab is the same
 * with one server and with "All servers": a row for each server listed, opening that server's own
 * page, then what this phone keeps, the longer parts behind rows of their own. A server's page holds
 * everything of that server, bound to it alone, whichever way it was opened. Pure, so it's tested
 * (SettingsPlanTest).
 */
object SettingsPlan {
    /** What Settings needs to know about a paired server. */
    data class ServerFacts(
        val id: String,
        val link: LinkState,
        /** It reads aloud with its own voices (`hub.speech?.settings`). */
        val readAloud: Boolean,
        /** It has the Browse outside projects setting (`hub.mac.browseOutsideProjects`). */
        val files: Boolean,
    )

    sealed interface Part {
        /** The list item's key: unique in its list, and what SettingsJump scrolls to on a server's page. */
        val key: String
    }

    // ─── The tab ───

    sealed interface TabPart : Part

    /** A row for each of [ids], in order; none: not paired. */
    data class Servers(val ids: List<String>) : TabPart { override val key = "servers" }
    /** "This phone": the heading, and the rows of [pages]. */
    data class Phone(val pages: List<PhonePage>) : TabPart { override val key = "phone" }
    data object NewSessions : TabPart { override val key = "newSessions" }
    data object Display : TabPart { override val key = "display" }
    /** Terminal engine: xterm.js or the Ghostty trial (ui/term/TermEngine.kt), this phone's; testing only. */
    data object TerminalEngine : TabPart { override val key = "termEngine" }
    data object About : TabPart { override val key = "about" }

    /** The phone's sub pages, in their order on the tab. */
    enum class PhonePage { Notifications, TopBar, CardSwipes, TypingAndVoice, Logs }

    // ─── A server's page ───

    /** A part that reads and writes one server's hub, [server]'s, and no other. */
    sealed interface ServerPart : Part {
        val server: String
    }

    /**
     * The server's card: its link, address, versions, battery and Stay awake, Retry, Forget. [waiting]
     * says it isn't connected: its other settings are left out until it is.
     */
    data class Server(override val server: String, val waiting: Boolean = false) : ServerPart { override val key = SettingsJump.SERVER }
    data class Providers(override val server: String) : ServerPart { override val key = "providers" }
    data class ReadAloud(override val server: String) : ServerPart { override val key = "readAloud" }
    data class Files(override val server: String) : ServerPart { override val key = SettingsJump.FILES }
    data class Checklist(override val server: String) : ServerPart { override val key = "qa" }

    /**
     * The tab's parts in order. [servers]: every paired one with All servers, in the order they were
     * paired, otherwise the one on screen (empty: none paired, so no Notifications either).
     * [testing]: the testing tools are unlocked (a debug build, or seven taps on the build): the
     * Terminal engine trial, just before About.
     */
    fun parts(servers: List<String>, testing: Boolean): List<TabPart> = buildList {
        add(Servers(servers))
        add(Phone(PhonePage.entries.filter { it != PhonePage.Notifications || servers.isNotEmpty() }))
        add(NewSessions)
        add(Display)
        if (testing) add(TerminalEngine)
        add(About)
    }

    /**
     * Server [s]'s page in order. With All servers ([all]), a server that isn't connected shows its
     * card alone, so no controls from its last snapshot; the one server on screen keeps its parts
     * while it can't be reached, as it always has. [testing]: its testing checklist too.
     */
    fun serverParts(s: ServerFacts, all: Boolean, testing: Boolean): List<ServerPart> = buildList {
        val waiting = all && s.link != LinkState.Connected
        add(Server(s.id, waiting))
        if (waiting) return@buildList
        add(Providers(s.id))
        if (s.readAloud) add(ReadAloud(s.id))
        if (s.files) add(Files(s.id))
        if (testing) add(Checklist(s.id))
    }
}
