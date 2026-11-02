package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * The server's debug log (docs/PROTOCOL.md, "The server's debug log"; owner, 2026-11-02): the snapshot's
 * `mac.debug`, `{t:"debug"}` and `POST /v1/mac/debug`'s answer. Settings shows it as one switch in the
 * server's section, with when it ends. The lines stay on the server: no route brings them here.
 */
@Immutable
data class ServerDebug(
    val on: Boolean,
    /** When it turns itself off, epoch ms; null while off. */
    val until: Long? = null,
) {
    companion object {
        /** Null for a server too old to have one (Settings shows no switch then). */
        fun parse(o: JSONObject?): ServerDebug? {
            if (o == null || !o.has("on")) return null
            val on = o.bool("on")
            return ServerDebug(on, if (on) o.long("until") else null)
        }
    }
}
