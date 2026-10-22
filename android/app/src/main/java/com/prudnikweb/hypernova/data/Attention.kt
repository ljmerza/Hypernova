package com.prudnikweb.hypernova.data

/**
 * Attention cards the owner swiped away (ui/components/Attention.kt; owner, 2026-10-18): a card
 * swiped right leaves an Undo in its place for [UNDO_MS], then is gone for as long as what it says
 * stays the same. Each card names itself ([id]: "account", "provider:openrouter", "offline") and
 * what it says ([state]: the account's state and reason, the provider's trouble, the link's problem);
 * when that changes, the card comes back, and the old dismissal is forgotten. Pure, so the rules are
 * unit-tested; the screen keeps the map in a state holder for the life of the process.
 */
object Attention {
    /** How long a swiped card leaves its Undo. */
    const val UNDO_MS = 6_000L

    /** A dismissal: what the card said then, when, and whether its Undo has gone. */
    data class Gone(val state: String, val at: Long, val settled: Boolean = false)

    enum class Show { Card, Undo, Nothing }

    /** What [id] shows now, saying [state]. */
    fun show(gone: Map<String, Gone>, id: String, state: String, now: Long): Show {
        val g = gone[id] ?: return Show.Card
        if (g.state != state) return Show.Card
        return if (!g.settled && now - g.at < UNDO_MS) Show.Undo else Show.Nothing
    }

    fun dismiss(gone: Map<String, Gone>, id: String, state: String, now: Long): Map<String, Gone> = gone + (id to Gone(state, now))

    fun undo(gone: Map<String, Gone>, id: String): Map<String, Gone> = gone - id

    /** The Undo has had its time: from now on the card is simply gone. */
    fun settle(gone: Map<String, Gone>, id: String): Map<String, Gone> = gone[id]?.let { gone + (id to it.copy(settled = true)) } ?: gone

    /**
     * The card says something else now ([state]): its old dismissal no longer counts, and is
     * forgotten, so going back to what it said before shows it again too.
     */
    fun forgetChanged(gone: Map<String, Gone>, id: String, state: String): Map<String, Gone> =
        if (gone[id]?.state?.let { it != state } == true) gone - id else gone
}
