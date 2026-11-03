package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * An agent's update, running or finished (docs/PROTOCOL.md, "AgentVersion"). [message] is the server's plain words for how it
 * ended, from the version read back after it, so it's what to believe. [output] is the updater's own last lines, scrubbed.
 */
@Immutable
data class AgentUpdateJob(
    val state: String,
    val from: String? = null,
    val to: String? = null,
    val message: String? = null,
    val output: List<String> = emptyList(),
) {
    val running get() = state == RUNNING
    companion object {
        const val RUNNING = "running"
        const val UPDATED = "updated"
        const val UNCHANGED = "unchanged"
        const val FAILED = "failed"
        fun wire(o: JSONObject) = AgentUpdateJob(
            state = o.str("state") ?: FAILED,
            from = o.str("from"),
            to = o.str("to"),
            message = o.str("message"),
            output = o.arr("output")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
        )
    }
}

/**
 * Claude Code or Codex on one server: what's installed and, after a check, what's newest and whether it can update from
 * here (docs/PROTOCOL.md, "Updating an agent"). The sentences are the server's, so the app holds no copy of what each install
 * kind can do. Each hub answers for its own server.
 */
@Immutable
data class AgentVersion(
    val installed: Boolean,
    val version: String? = null,
    /** Sessions on this server that aren't over, for the confirm. */
    val running: Int = 0,
    /** How it was installed, in words; null before a check. */
    val words: String? = null,
    val channel: String? = null,
    /** It also keeps itself up to date; null when that isn't known. */
    val autoUpdates: Boolean? = null,
    val latest: String? = null,
    val checkedAt: Long? = null,
    /** Why a check couldn't say what's newest. */
    val problem: String? = null,
    val newer: Boolean = false,
    val canUpdate: Boolean = false,
    /** Why it can't update from here. */
    val why: String? = null,
    /** What to run at a terminal instead. Only for a person: nothing here runs it. */
    val command: String? = null,
    /** What an update does to what's running; said on the confirm. */
    val effect: String? = null,
    /** What else the confirm has to say: a Codex past what this server was checked with, a network disk. */
    val warning: String? = null,
    val job: AgentUpdateJob? = null,
) {
    val updating get() = job?.running == true
    companion object {
        fun wire(o: JSONObject) = AgentVersion(
            installed = o.bool("installed"),
            version = o.str("version"),
            running = o.int("running") ?: 0,
            words = o.str("words"),
            channel = o.str("channel"),
            autoUpdates = if (o.has("autoUpdates") && !o.isNull("autoUpdates")) o.bool("autoUpdates") else null,
            latest = o.str("latest"),
            checkedAt = o.long("checkedAt"),
            problem = o.str("problem"),
            newer = o.bool("newer"),
            canUpdate = o.bool("canUpdate"),
            why = o.str("why"),
            command = o.str("command"),
            effect = o.str("effect"),
            warning = o.str("warning"),
            job = o.obj("job")?.let(AgentUpdateJob::wire),
        )
    }
}

/** What's installed and the last check. Asks nobody outside the server. An older server: [AgentSettingsTooOld]. */
suspend fun Hub.agentVersion(agent: String): Result<AgentVersion> =
    agentExt("GET", "/v1/agents/version" + McpWire.query("agent" to agent)).map(AgentVersion::wire)

/** Asks what's newest, now or from a look a few minutes old. Needs no fingerprint: it installs nothing. */
suspend fun Hub.checkAgentVersion(agent: String): Result<AgentVersion> =
    agentExt("POST", "/v1/agents/version/check", JSONObject().put("agent", agent), slow = true).map(AgentVersion::wire)

/** Starts the agent's own updater, and answers at once; [AgentVersion.job] follows. The caller has asked for the fingerprint first. */
suspend fun Hub.updateAgent(agent: String): Result<AgentVersion> =
    agentExt("POST", "/v1/agents/version/update", JSONObject().put("agent", agent).put("confirmRun", true), slow = true).map(AgentVersion::wire)
