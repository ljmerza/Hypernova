package com.prudnikweb.hypernova.data

/**
 * Fork: held prompts (docs/PROTOCOL.md, "Held prompts"). A prompt sent while Claude waits for the
 * owner's answer is held on the Mac (`session.held`) and typed in once Claude Code's input box is
 * back. The chat draws each as the owner's own bubble, "Held", just above the asks waiting at the
 * bottom. Pure, unit-tested.
 */
object Held {
    /** How far back the chat looks for a held prompt's own event. */
    private const val LOOKBACK = 20

    /**
     * [held] as the chat draws them, oldest first. One the Mac has typed in already (an event or a
     * queue entry names the same send) is left out: the session's own update is a moment behind.
     */
    fun items(held: List<WQueued>, events: List<WEvent>, queue: List<WQueued>): List<Item.User> {
        if (held.isEmpty()) return emptyList()
        val shown = HashSet<String>()
        for (e in events.asReversed().take(LOOKBACK)) (e as? WEvent.User)?.clientId?.let(shown::add)
        for (q in queue) q.clientId?.let(shown::add)
        return held.filter { it.clientId == null || it.clientId !in shown }.map(::item)
    }

    fun item(h: WQueued) = Item.User(
        key = "held:${h.id}",
        text = h.text,
        status = SendStatus.Held,
        files = h.files,
        command = if (h.files.isEmpty()) CommandRun.typed(h.text) else null,
        heldId = h.id,
    )

    /**
     * Held prompts in [before] that [after] no longer has: the Mac typed them in (or let them go,
     * and its notice says so), except the ones this phone took back itself ([removed]).
     */
    fun left(before: List<WQueued>, after: List<WQueued>, removed: Set<String>): List<WQueued> {
        if (before.isEmpty()) return emptyList()
        val still = after.mapTo(HashSet()) { it.id }
        return before.filter { it.id !in still && it.id !in removed }
    }
}
