package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * A call refused without the owner (docs/PROTOCOL.md, "Blocked calls"): auto mode's classifier said
 * no ([AUTO_MODE]), or nobody answered its approval for 10 minutes ([AWAY]). The session sheet
 * lists them (ui/session/BlockedTab.kt), with Allow & retry and Dismiss.
 */
@Immutable
data class BlockedCall(
    val id: String,
    val sessionId: String,
    val tool: String,
    /** What it would touch: the command, the file, the address (keys masked on the Mac). */
    val target: String,
    val reason: String,
    /** The classifier's reason, when it gave one. */
    val why: String?,
    val at: Long,
) {
    companion object {
        const val AUTO_MODE = "auto_mode"
        const val AWAY = "away"
    }
}

object BlockedWire {
    /** `{blocked: BlockedCall[]}`, newest first; an older Mac sends none. */
    fun list(o: JSONObject): List<BlockedCall> = o.arr("blocked").mapObjects(::call).sortedByDescending { it.at }

    fun call(o: JSONObject): BlockedCall? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return BlockedCall(
            id = id,
            sessionId = o.str("sessionId").orEmpty(),
            tool = o.str("tool")?.takeIf { it.isNotBlank() } ?: "Tool",
            target = o.str("target").orEmpty(),
            reason = o.str("reason") ?: BlockedCall.AUTO_MODE,
            why = o.str("why")?.takeIf { it.isNotBlank() },
            at = o.long("at") ?: 0L,
        )
    }
}
