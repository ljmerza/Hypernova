package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * The server updating itself (docs/PROTOCOL.md, "The server's own update"; owner, 2026-10-31): the
 * snapshot's `mac.update`, `{t:"update"}` and `POST /v1/mac/update`'s answer. Settings shows it as
 * one switch in the server's section: **Update this server automatically**, off unless turned on.
 */
@Immutable
data class ServerUpdate(
    /** The setting (the server's settings.autoUpdate). */
    val auto: Boolean,
    /** This install can update itself; else [reason] says why not. */
    val available: Boolean,
    val reason: String? = null,
    /** [IDLE], [WAITING] (downloaded, for a quiet moment) or [INSTALLING], with the release's tag in [target]. */
    val state: String = IDLE,
    val target: String? = null,
    /** The last update the server installed by itself. */
    val last: Last? = null,
) {
    @Immutable
    data class Last(val from: String, val to: String, val at: Long)

    companion object {
        const val IDLE = "idle"
        const val WAITING = "waiting"
        const val INSTALLING = "installing"

        /** Null for a server too old to update itself (Settings shows no switch then). */
        fun parse(o: JSONObject?): ServerUpdate? {
            if (o == null || !o.has("auto")) return null
            val last = o.obj("last")?.let { l ->
                val from = l.str("from")
                val to = l.str("to")
                if (from.isNullOrBlank() || to.isNullOrBlank()) null else Last(from, to, l.long("at") ?: 0L)
            }
            val state = when (val s = o.str("state")) { WAITING, INSTALLING -> s; else -> IDLE }
            return ServerUpdate(
                auto = o.bool("auto"),
                available = o.bool("available"),
                reason = o.str("reason")?.takeIf { it.isNotBlank() },
                state = state,
                target = if (state == IDLE) null else o.str("target")?.takeIf { it.isNotBlank() },
                last = last,
            )
        }

        /**
         * The daemon's version changed between two snapshots: a line for the connection log ("the
         * server updated: 0.6.0 → 0.7.0"), null when it didn't or one side doesn't say.
         */
        fun versionChange(before: String?, after: String?): String? =
            if (before.isNullOrBlank() || after.isNullOrBlank() || before == after) null else "the server updated: $before → $after"
    }
}
