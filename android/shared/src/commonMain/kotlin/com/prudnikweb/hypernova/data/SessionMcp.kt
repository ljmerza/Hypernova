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

/**
 * One server as this session has it (docs/PROTOCOL.md, "A session's MCP connections"). [actions] are the [SessionMcp] words the server offers
 * for it, which differ by the session's kind. [toolCount] null: it didn't say. [tools] empty with a count: ask [sessionMcpTools]. [cut]: its whole name
 * wasn't on the terminal, so only the terminal can act on it. [offForNew]: on in this session, turned off for new ones.
 */
@Immutable
data class SessionMcpRow(
    val name: String,
    val status: String,
    val source: String,
    val toolCount: Int?,
    val tools: List<McpLiveTool>,
    val error: String?,
    val cut: Boolean,
    val offForNew: Boolean,
    val transport: String?,
    val command: String?,
    val host: String?,
    val actions: List<String>,
    val signIn: Boolean,
) {
    companion object {
        fun wire(o: JSONObject): SessionMcpRow? {
            val name = o.str("name") ?: return null
            return SessionMcpRow(
                name = name,
                status = o.str("status") ?: Mcp.FAILED,
                source = o.str("source") ?: "unknown",
                toolCount = o.int("toolCount"),
                tools = o.arr("tools").mapObjects(SessionMcp::tool),
                error = o.str("error"),
                cut = o.bool("cut"),
                offForNew = o.bool("offForNew"),
                transport = o.str("transport"),
                command = o.str("command"),
                host = o.str("host"),
                actions = McpWire.strings(o.arr("actions")),
                signIn = o.bool("signIn"),
            )
        }
    }
}

/**
 * A session's servers as it has them, however that session can be asked. [live]: they're its own connections; else what a new session would get, or its next
 * turn. [unreadCode] and [unreadWords]: why they couldn't be read this time, and there are no rows.
 */
@Immutable
data class SessionMcpLive(val agent: String, val kind: String, val live: Boolean, val folder: String, val servers: List<SessionMcpRow>, val unreadCode: String?, val unreadWords: String?) {
    companion object {
        fun wire(o: JSONObject) = SessionMcpLive(
            agent = o.str("agent") ?: AgentSettingsReply.CLAUDE,
            kind = o.str("kind") ?: SessionMcp.WATCHED,
            live = o.bool("live"),
            folder = o.str("folder") ?: Mcp.HOME,
            servers = o.arr("servers").mapObjects(SessionMcpRow::wire),
            unreadCode = o.obj("unread")?.str("code"),
            unreadWords = o.obj("unread")?.str("words"),
        )
    }
}

/** What an action really did, by the state read back: [outcome] is one of [SessionMcp]'s. [said]: the agent's own words, masked. */
@Immutable
data class SessionMcpActed(val action: String, val name: String, val outcome: String, val said: String?, val server: SessionMcpRow?, val reply: SessionMcpLive) {
    companion object {
        fun wire(o: JSONObject): SessionMcpActed {
            val reply = o.obj("reply")?.let(SessionMcpLive::wire) ?: SessionMcpLive.wire(JSONObject())
            return SessionMcpActed(o.str("action").orEmpty(), o.str("name").orEmpty(), o.str("outcome") ?: SessionMcp.FAILED, o.str("said"), o.obj("server")?.let(SessionMcpRow::wire), reply)
        }
    }
}

object SessionMcp {
    const val TERMINAL = "terminal"
    const val JOB = "job"
    const val TURN = "turn"
    const val REST = "rest"
    const val CODEX = "codex"
    const val WATCHED = "watched"
    const val ENDED = "ended"
    const val RECONNECT = "reconnect"
    const val ENABLE = "enable"
    const val DISABLE = "disable"
    const val DONE = "done"
    const val FAILED = "failed"
    const val NO_CHANGE = "no_change"
    const val UNCONFIRMED = "unconfirmed"
    const val NEXT_TURN = "next_turn"
    const val NEW_SESSIONS = "new_sessions"

    internal fun tool(t: JSONObject): McpLiveTool? {
        val n = t.str("name") ?: return null
        return McpLiveTool(n, t.bool("readOnly"), t.bool("destructive"), t.bool("openWorld"))
    }
}

suspend fun Hub.sessionMcpLive(id: String, fresh: Boolean = false): Result<SessionMcpLive> =
    agentExt("GET", "/v1/sessions/$id/mcp/live" + McpWire.query("fresh" to if (fresh) "1" else null).takeIf { fresh }.orEmpty(), slow = true).map(SessionMcpLive::wire)

suspend fun Hub.sessionMcpTools(id: String, name: String): Result<List<McpLiveTool>> =
    agentExt("GET", "/v1/sessions/$id/mcp/live/tools" + McpWire.query("name" to name), slow = true).map { o -> o.arr("tools").mapObjects(SessionMcp::tool) }

suspend fun Hub.sessionMcpAct(id: String, action: String, name: String): Result<SessionMcpActed> =
    agentExt("POST", "/v1/sessions/$id/mcp/live", JSONObject().put("action", action).put("name", name), slow = true).map(SessionMcpActed::wire)
