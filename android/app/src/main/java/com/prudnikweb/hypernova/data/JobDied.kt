package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * A `claude agents` job that died: a crash, an error exit, its process gone mid-turn, an API error
 * that ended it (docs/PROTOCOL.md, "Session menu", `{t:"job.died"}`). The Mac says it once per death,
 * never for one that finished or that the owner stopped or deleted; the phone says it as it says a
 * session stopping on an error: a banner, or a notification in "Session updates" that opens it.
 * [reason]: Claude Code's words ("exit 1 — …"), null when it gave none.
 */
@Immutable
data class JobDied(val sessionId: String, val reason: String?, val at: Long) {
    /** The notice: "<name> stopped on an error", then why ([fallback] when Claude Code didn't say). One per death. */
    fun notice(name: String, fallback: String) = Notice("died:$sessionId:$at", Notice.Kind.Error, sessionId, name, reason ?: fallback)

    companion object {
        fun parse(o: JSONObject): JobDied? {
            val sid = o.str("sessionId")?.takeIf { it.isNotBlank() } ?: return null
            return JobDied(sid, o.str("reason")?.trim()?.takeIf { it.isNotEmpty() }, o.long("at") ?: 0L)
        }
    }
}
