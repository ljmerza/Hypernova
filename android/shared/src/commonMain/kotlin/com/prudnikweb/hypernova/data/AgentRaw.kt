package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * A settings file as raw JSON (docs/PROTOCOL.md, "JSON mode"). [text] holds what's safe to send, with [placeholder] where a
 * secret stays on the server. Null: [state] says why there's nothing to edit.
 */
@Immutable
data class RawFile(val scope: String, val folder: String?, val folders: List<McpFolder>, val where: String, val state: String, val hash: String?, val text: String?, val placeholder: String, val hidden: Int, val line: Int?, val column: Int?, val undo: RawUndo?) {
    companion object {
        const val OK = "ok"
        const val MISSING = "missing"
        const val MALFORMED = "malformed"

        fun wire(o: JSONObject) = RawFile(
            scope = o.str("scope") ?: Mcp.USER,
            folder = o.str("folder"),
            folders = PluginsReply.folders(o),
            where = o.str("where").orEmpty(),
            state = o.str("state") ?: "unreadable",
            hash = o.str("hash"),
            text = o.str("text"),
            placeholder = o.str("placeholder").orEmpty(),
            hidden = o.int("hidden") ?: 0,
            line = o.int("line"),
            column = o.int("column"),
            undo = o.obj("undo")?.let { u -> RawUndo(u.str("scope") ?: Mcp.USER, u.str("folder"), u.bool("needsCheck")) },
        )
    }
}

/** What Undo would do, and whether it asks. */
@Immutable
data class RawUndo(val scope: String, val folder: String?, val needsCheck: Boolean)

/** What came of a save. [changed] false: it was already that. */
@Immutable
data class RawSaved(val changed: Boolean, val hash: String?) {
    companion object {
        fun wire(o: JSONObject) = RawSaved(o.bool("changed"), o.str("hash"))
    }
}

suspend fun Hub.rawFile(agent: String, scope: String, folder: String?): Result<RawFile> =
    agentExt("GET", "/v1/agents/raw" + McpWire.query("agent" to agent, "scope" to scope, "folder" to folder), slow = true).map(RawFile::wire)
suspend fun Hub.rawSave(agent: String, scope: String, folder: String?, text: String, expected: String?, confirmRun: Boolean = false): Result<RawSaved> =
    agentExt("POST", "/v1/agents/raw", JSONObject().put("agent", agent).put("scope", scope).put("text", text).put("expected", expected ?: JSONObject.NULL).also { o ->
        folder?.let { o.put("folder", it) }
        if (confirmRun) o.put("confirmRun", true)
    }, slow = true).map(RawSaved::wire)
suspend fun Hub.rawUndo(agent: String, confirmRun: Boolean = false): Result<JSONObject> =
    agentExt("POST", "/v1/agents/raw/undo", JSONObject().put("agent", agent).also { if (confirmRun) it.put("confirmRun", true) }, slow = true)
