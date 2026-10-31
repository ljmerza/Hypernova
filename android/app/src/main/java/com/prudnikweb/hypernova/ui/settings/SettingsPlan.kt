package com.prudnikweb.hypernova.ui.settings

import com.prudnikweb.hypernova.data.LinkState

/**
 * Which parts of the Settings tab show, in their order, with one server on screen or with "All
 * servers" (docs/DECISIONS.md, "All servers in Settings"). With one server, the parts are as they
 * always were. With All servers, nothing acts on the server picked last: each server's own parts
 * (its card, Agents & providers, Read aloud, Files, the testing checklist) show once per server, one
 * after the other under its name, each bound to that server alone; a server that isn't connected
 * shows its card only, saying its settings come back once it is. Then what this phone keeps, under
 * "This phone". Pure, so it's tested (SettingsPlanTest).
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
        /** The list item's key: unique in the list, and what SettingsJump scrolls to. */
        val key: String
    }

    /** A part that reads and writes one server's hub, [server]'s, and no other. */
    sealed interface ServerPart : Part {
        val server: String
    }

    /**
     * The server's card: its name and link, address, battery and Stay awake, Retry, Forget. With All
     * servers its name is the block's heading and [waiting] says it isn't connected: its other
     * settings are left out until it is.
     */
    data class Server(override val server: String, override val key: String, val waiting: Boolean = false) : ServerPart
    data class Providers(override val server: String, override val key: String) : ServerPart
    data class ReadAloud(override val server: String, override val key: String) : ServerPart
    data class Files(override val server: String, override val key: String) : ServerPart
    data class Checklist(override val server: String, override val key: String) : ServerPart

    /** Voice recognition, this phone's; with one server, its Read aloud follows in the same item ([readAloud]). */
    data class Voice(val readAloud: String? = null) : Part { override val key = "voice" }
    /** "This phone": the heading over what the phone keeps, with All servers only. */
    data object PhoneHeading : Part { override val key = "phone" }
    /** The connection log, once (it's the phone's, every server's lines in it), with All servers only. */
    data object ConnectionLog : Part { override val key = "connectionLog" }
    data object NewSessions : Part { override val key = "newSessions" }
    data object Display : Part { override val key = "display" }
    data object CardSwipes : Part { override val key = "cardSwipes" }
    data object TopBar : Part { override val key = "topBar" }
    data object Typing : Part { override val key = "typing" }
    data object Notifications : Part { override val key = "background" }
    data object About : Part { override val key = "about" }

    /** A server part's key: the part's own with one server, the part's and the server's with All servers. */
    fun keyOf(part: String, server: String, all: Boolean): String = if (all) "$part:$server" else part

    /**
     * The parts in order. [all]: "All servers" is on, [servers] every paired one in the order they
     * were paired; otherwise [servers] is the one on screen (empty: none paired). [testing]: the
     * testing checklist is unlocked (a debug build, or seven taps on the build).
     */
    fun parts(all: Boolean, servers: List<ServerFacts>, testing: Boolean): List<Part> =
        if (all) allServers(servers, testing) else oneServer(servers.firstOrNull(), testing)

    private fun oneServer(s: ServerFacts?, testing: Boolean): List<Part> = buildList {
        add(Server(s?.id.orEmpty(), "mac"))
        if (s != null) {
            add(Providers(s.id, "providers"))
            add(Voice(readAloud = s.id.takeIf { s.readAloud }))
            if (s.files) add(Files(s.id, SettingsJump.FILES))
        }
        addAll(phoneKept())
        if (s != null) {
            add(Notifications)
            if (testing) add(Checklist(s.id, "qa"))
        }
        add(About)
    }

    private fun allServers(servers: List<ServerFacts>, testing: Boolean): List<Part> = buildList {
        for (s in servers) {
            val linked = s.link == LinkState.Connected
            add(Server(s.id, keyOf("server", s.id, true), waiting = !linked))
            if (!linked) continue
            add(Providers(s.id, keyOf("providers", s.id, true)))
            if (s.readAloud) add(ReadAloud(s.id, keyOf("readAloud", s.id, true)))
            if (s.files) add(Files(s.id, keyOf(SettingsJump.FILES, s.id, true)))
            if (testing) add(Checklist(s.id, keyOf("qa", s.id, true)))
        }
        add(PhoneHeading)
        add(Voice())
        addAll(phoneKept())
        add(Notifications)
        add(ConnectionLog)
        add(About)
    }

    /** Kept on this phone, whatever server: the same with one server and with All servers. */
    private fun phoneKept(): List<Part> = listOf(NewSessions, Display, CardSwipes, TopBar, Typing)
}
