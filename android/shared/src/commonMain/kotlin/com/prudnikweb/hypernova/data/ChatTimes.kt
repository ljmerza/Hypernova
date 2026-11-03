package com.prudnikweb.hypernova.data

/**
 * When the things in a chat happened, by [Item.key], for the time over the chat and on a message's
 * menu (docs/DECISIONS.md, "The time over the chat"). Only real times are here: an event with a [WEvent.ts]
 * of 0, or none from the Mac ([WEvent.tsGuessed]), isn't; nor is what has no event yet (a prompt on
 * its way or queued). A side question's line has the time it was asked.
 */
class ChatTimes private constructor(
    private val byKey: Map<String, Long>,
    /** The newest of the owner's messages and Claude's replies. */
    val lastMessage: Long?,
) {
    fun at(key: String): Long? = byKey[key]

    companion object {
        val Empty = ChatTimes(emptyMap(), null)

        fun of(events: List<WEvent>): ChatTimes {
            val byKey = HashMap<String, Long>(events.size * 2)
            var last: Long? = null
            for (e in events) {
                val at = e.ts.takeIf { it > 0 && !e.tsGuessed } ?: continue
                byKey[e.id] = at
                if ((e is WEvent.User || e is WEvent.Assistant) && at > (last ?: 0L)) last = at
            }
            return ChatTimes(byKey, last)
        }
    }
}
