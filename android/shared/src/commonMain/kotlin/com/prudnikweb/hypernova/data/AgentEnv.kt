package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * An agent's environment variables on a server (docs/PROTOCOL.md, "An agent's environment variables"). [value] is null when
 * it's masked; [flag] is on or off for what reads as one, whatever form it's written in. [overriddenBy]: the scope that wins this name.
 */
@Immutable
data class EnvVar(val name: String, val scope: String, val masked: Boolean, val value: String?, val flag: Boolean?, val effective: Boolean, val overriddenBy: String?, val guarded: Boolean, val refused: Boolean)

/** A file variables come from. [hash]: its version, to send back with a change. */
@Immutable
data class EnvFile(val scope: String, val where: String, val state: String, val hash: String?) {
    val changeable: Boolean get() = state == OK || state == MISSING

    companion object {
        const val OK = "ok"
        const val MISSING = "missing"
    }
}

/** What Undo would do: [change] says it in words, with secrets masked by the server. */
@Immutable
data class EnvUndo(val name: String, val scope: String, val folder: String?, val needsCheck: Boolean, val change: String)

@Immutable
data class EnvReply(val agent: String, val folder: String, val folders: List<McpFolder>, val files: List<EnvFile>, val vars: List<EnvVar>, val hidden: Int, val undo: EnvUndo?) {
    fun fileOf(scope: String) = files.firstOrNull { it.scope == scope }

    companion object {
        /** The server's way of saying a change needs the owner's fingerprint: its message says exactly what would change. */
        const val CONFIRM_RUN = "confirm_run"

        fun undo(o: JSONObject): EnvUndo? {
            val name = o.str("name") ?: return null
            return EnvUndo(name, o.str("scope") ?: Mcp.USER, o.str("folder"), o.bool("needsCheck"), o.str("change") ?: name)
        }

        fun wire(o: JSONObject) = EnvReply(
            agent = o.str("agent") ?: AgentSettingsReply.CLAUDE,
            folder = o.str("folder") ?: Mcp.HOME,
            folders = PluginsReply.folders(o),
            files = o.arr("files").mapObjects { f ->
                val scope = f.str("scope") ?: return@mapObjects null
                EnvFile(scope, f.str("where").orEmpty(), f.str("state") ?: "unreadable", f.str("hash"))
            },
            vars = o.arr("vars").mapObjects { v ->
                val name = v.str("name") ?: return@mapObjects null
                EnvVar(name, v.str("scope") ?: Mcp.USER, v.bool("masked"), v.str("value"), if (v.has("flag")) v.bool("flag") else null, !v.has("effective") || v.bool("effective"), v.str("overriddenBy"), v.bool("guarded"), v.bool("refused"))
            },
            hidden = o.int("hidden") ?: 0,
            undo = o.obj("undo")?.let(::undo),
        )
    }
}

suspend fun Hub.agentEnv(agent: String, folder: String?): Result<EnvReply> =
    agentExt("GET", "/v1/agents/env" + McpWire.query("agent" to agent, "folder" to folder), slow = true).map(EnvReply::wire)

private fun envBody(agent: String, scope: String, folder: String?, name: String, expected: String?, confirmRun: Boolean) =
    JSONObject().put("agent", agent).put("scope", scope).put("name", name).put("expected", expected ?: JSONObject.NULL).also { o ->
        folder?.let { o.put("folder", it) }
        if (confirmRun) o.put("confirmRun", true)
    }

suspend fun Hub.envSet(agent: String, scope: String, folder: String?, name: String, value: String, expected: String?, confirmRun: Boolean = false): Result<JSONObject> =
    agentExt("POST", "/v1/agents/env", envBody(agent, scope, folder, name, expected, confirmRun).put("value", value), slow = true)
suspend fun Hub.envFlip(agent: String, scope: String, folder: String?, name: String, expected: String?, confirmRun: Boolean = false): Result<JSONObject> =
    agentExt("POST", "/v1/agents/env", envBody(agent, scope, folder, name, expected, confirmRun).put("flip", true), slow = true)
suspend fun Hub.envRemove(agent: String, scope: String, folder: String?, name: String, expected: String?, confirmRun: Boolean = false): Result<JSONObject> =
    agentExt("POST", "/v1/agents/env/remove", envBody(agent, scope, folder, name, expected, confirmRun), slow = true)
suspend fun Hub.envUndo(agent: String, confirmRun: Boolean = false): Result<JSONObject> =
    agentExt("POST", "/v1/agents/env/undo", JSONObject().put("agent", agent).also { if (confirmRun) it.put("confirmRun", true) }, slow = true)
