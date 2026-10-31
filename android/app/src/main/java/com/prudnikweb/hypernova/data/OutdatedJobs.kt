package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/** One job's answer to Restart all (docs/PROTOCOL.md, `POST /v1/jobs/respawn`); [said]: Claude Code's own words. */
@Immutable
data class JobRestart(val sessionId: String, val ok: Boolean, val said: String)

/**
 * The `claude agents` jobs on an older Claude Code than the one installed ([Session.agentOutdated]),
 * for Home's quiet notice and its Restart all (docs/DECISIONS.md, "Restarting jobs on an old Claude
 * Code"): each one restarted with `claude respawn <job>`, never `--all`, which restarts current ones too.
 */
object OutdatedJobs {
    /** The open jobs a process of an older Claude Code runs, the list's order. */
    fun of(sessions: List<Session>): List<Session> =
        sessions.filter { it.agentJob != null && it.agentOutdated != null && it.phase != Phase.Ended }

    /** What the notice says, for its dismissal (data/Attention.kt): another job behind, or a newer install, brings it back. */
    fun attention(jobs: List<Session>): String =
        jobs.map { it.id }.sorted().joinToString(",") + "@" + jobs.firstNotNullOfOrNull { it.agentOutdated?.installed }.orEmpty()

    /** The route's answer: each job it restarted or Claude Code refused. */
    fun results(o: JSONObject): List<JobRestart> {
        val a = o.arr("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val id = r.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            JobRestart(id, r.bool("ok"), r.str("said").orEmpty())
        }
    }
}
