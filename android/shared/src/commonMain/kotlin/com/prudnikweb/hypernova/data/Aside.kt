package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * A side question (docs/PROTOCOL.md, "Side questions"): Claude Code's `/btw`, asked from the
 * session sheet's Commands tab while Claude goes on with its work. Its answer never reaches the
 * transcript; the Mac reads it off the terminal's screen. [state]: [ASKING] until it's read,
 * [ANSWERED] ([answer]; [clipped]: it went on past what the terminal showed), [FAILED] (Claude
 * Code's own words in [error]) or [UNSEEN] (the Mac couldn't read it: open the terminal).
 */
@Immutable
data class Aside(
    val id: String,
    val question: String,
    val state: String,
    val answer: String?,
    val error: String?,
    val clipped: Boolean,
    val askedAt: Long,
) {
    val asking: Boolean get() = state == ASKING

    companion object {
        const val ASKING = "asking"
        const val ANSWERED = "answered"
        const val FAILED = "failed"
        const val UNSEEN = "unseen"
    }
}

object AsideWire {
    /** `{aside: Aside | null}`. */
    fun of(o: JSONObject): Aside? = o.obj("aside")?.let(::aside)

    fun aside(o: JSONObject): Aside? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return Aside(
            id = id,
            question = o.str("question").orEmpty(),
            state = o.str("state") ?: Aside.UNSEEN,
            answer = o.str("answer")?.takeIf { it.isNotBlank() },
            error = o.str("error")?.takeIf { it.isNotBlank() },
            clipped = o.bool("clipped"),
            askedAt = o.long("askedAt") ?: 0L,
        )
    }
}
