package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * One MCP server set up for a session's folder (docs/PROTOCOL.md, "A session's MCP servers"). The
 * server decides what may leave: [command] is a base name and [host] only a host, each null when it
 * didn't pass. [transport] null: a kind it doesn't name. [from]: the folder's base name, when it's not
 * the session's own.
 */
@Immutable
data class SessionMcpServer(val name: String, val scope: String, val transport: String?, val command: String?, val host: String?, val from: String?)

/** What a session's folder has. [state] is `~/.claude.json`'s; [forAllProjects] is a count of the user's, never their names. */
@Immutable
data class SessionMcpReply(val state: String, val servers: List<SessionMcpServer>, val forAllProjects: Int, val hidden: Int, val foldersNotRead: Int) {
    companion object {
        const val LOCAL = "local"

        fun wire(o: JSONObject) = SessionMcpReply(
            state = o.str("state") ?: AgentSettingsFile.UNREADABLE,
            servers = o.arr("servers").mapObjects { s ->
                val name = s.str("name") ?: return@mapObjects null
                SessionMcpServer(name, s.str("scope") ?: "project", s.str("transport"), s.str("command"), s.str("host"), s.str("from"))
            },
            forAllProjects = o.int("forAllProjects") ?: 0,
            hidden = o.int("hidden") ?: 0,
            foldersNotRead = o.int("foldersNotRead") ?: 0,
        )
    }
}

/** The server has no `GET /v1/sessions/{id}/mcp`: it's older than this app. */
class SessionMcpTooOld(message: String) : HubException(message)
