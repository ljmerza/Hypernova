package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * An agent's MCP servers on a server, to see and to manage (docs/PROTOCOL.md, "An agent's MCP servers"). The server decides
 * what may leave: env and header values never do, and arguments and URLs come masked. Calls go through
 * [Hub.agentExt], so the words of a refusal are the server's.
 */
@Immutable
data class McpFolder(val id: String, val name: String)

/** One server in the list. [folder] and [project]: for local and project scope. [approval]: a project server's. */
@Immutable
data class McpRow(val name: String, val scope: String, val folder: String?, val project: String?, val transport: String?, val summary: String?, val approval: String?)

@Immutable
data class McpList(val agent: String, val state: String, val servers: List<McpRow>, val folders: List<McpFolder>, val hidden: Int, val foldersNotRead: Int, val scopes: List<String>, val undo: McpUndo? = null) {
    companion object {
        fun wire(o: JSONObject) = McpList(
            agent = o.str("agent") ?: AgentSettingsReply.CLAUDE,
            state = o.str("state") ?: AgentSettingsFile.UNREADABLE,
            servers = o.arr("servers").mapObjects { s ->
                val name = s.str("name") ?: return@mapObjects null
                McpRow(name, s.str("scope") ?: Mcp.USER, s.str("folder"), s.str("project"), s.str("transport"), s.str("summary"), s.str("approval"))
            },
            folders = o.arr("folders").mapObjects { f ->
                val id = f.str("id") ?: return@mapObjects null
                McpFolder(id, f.str("name") ?: id)
            },
            hidden = o.int("hidden") ?: 0,
            foldersNotRead = o.int("foldersNotRead") ?: 0,
            scopes = McpWire.strings(o.arr("scopes")),
            undo = o.obj("undo")?.let { u ->
                val name = u.str("name") ?: return@let null
                McpUndo(u.str("op") ?: "add", name, u.str("scope") ?: Mcp.USER, u.str("folder"), u.bool("needsCheck"), u.str("command"))
            },
        )
    }
}

/** What Undo would do. [needsCheck]: it would run something, so it confirms first, showing [command]. */
@Immutable
data class McpUndo(val op: String, val name: String, val scope: String, val folder: String?, val needsCheck: Boolean, val command: String?)

/** How a server is set up, in what may leave. */
@Immutable
data class McpSetup(
    val transport: String?,
    val command: String?,
    val args: List<String>,
    val url: String?,
    val envNames: List<String>,
    val headerNames: List<String>,
    val headersHelper: String?,
    val clientId: String?,
    val callbackPort: Int?,
    val timeout: Int?,
    val hidden: Int,
    /** Codex: the variable its bearer token comes from, its working folder, the tools it lets through or holds back, how long it may take to start. */
    val bearerEnv: String? = null,
    val cwd: String? = null,
    val toolsOn: List<String> = emptyList(),
    val toolsOff: List<String> = emptyList(),
    val startupTimeout: Int? = null,
) {
    companion object {
        fun wire(o: JSONObject): McpSetup {
            val oauth = o.obj("oauth")
            return McpSetup(
                transport = o.str("transport"),
                command = o.str("command"),
                args = McpWire.strings(o.arr("args")),
                url = o.str("url"),
                envNames = McpWire.strings(o.arr("envNames")),
                headerNames = McpWire.strings(o.arr("headerNames")),
                headersHelper = o.str("headersHelper"),
                clientId = oauth?.str("clientId"),
                callbackPort = oauth?.int("callbackPort"),
                timeout = o.int("timeout"),
                hidden = o.int("hidden") ?: 0,
                bearerEnv = o.str("bearerEnv"),
                cwd = o.str("cwd"),
                toolsOn = McpWire.strings(o.arr("toolsOn")),
                toolsOff = McpWire.strings(o.arr("toolsOff")),
                startupTimeout = o.int("startupTimeout"),
            )
        }
    }
}

/** A server's page. [where]: its file, in words. [hash]: the version to send back with a change. */
@Immutable
data class McpServer(
    val agent: String,
    val name: String,
    val scope: String,
    val folder: String?,
    val project: String?,
    val where: String?,
    val setup: McpSetup,
    val approval: String?,
    val hash: String?,
    val editable: Boolean,
    val enabled: Boolean?,
    val authStatus: String?,
) {
    companion object {
        fun wire(o: JSONObject) = McpServer(
            agent = o.str("agent") ?: AgentSettingsReply.CLAUDE,
            name = o.str("name").orEmpty(),
            scope = o.str("scope") ?: Mcp.USER,
            folder = o.str("folder"),
            project = o.str("project"),
            where = o.str("where"),
            setup = McpSetup.wire(o),
            approval = o.str("approval"),
            hash = o.str("hash"),
            editable = o.bool("editable"),
            enabled = if (o.has("enabled")) o.bool("enabled") else null,
            authStatus = o.str("authStatus"),
        )
    }
}

@Immutable
data class McpLiveTool(val name: String, val readOnly: Boolean, val destructive: Boolean, val openWorld: Boolean)

/** One server as a process in that folder sees it. [status] is one of [Mcp]'s; [source]: where it's from, the agent's own word. */
@Immutable
data class McpLive(val name: String, val status: String, val source: String, val serverName: String?, val version: String?, val error: String?, val tools: List<McpLiveTool>, val setup: McpSetup?)

@Immutable
data class McpLiveReply(val folder: String, val servers: List<McpLive>) {
    companion object {
        fun one(o: JSONObject): McpLive? {
            val name = o.str("name") ?: return null
            return McpLive(
                name = name,
                status = o.str("status") ?: Mcp.FAILED,
                source = o.str("source") ?: "unknown",
                serverName = o.str("serverName"),
                version = o.str("version"),
                error = o.str("error"),
                tools = o.arr("tools").mapObjects { t ->
                    val n = t.str("name") ?: return@mapObjects null
                    McpLiveTool(n, t.bool("readOnly"), t.bool("destructive"), t.bool("openWorld"))
                },
                setup = o.obj("setup")?.let(McpSetup::wire),
            )
        }

        fun wire(o: JSONObject) = McpLiveReply(o.str("folder") ?: Mcp.HOME, o.arr("servers").mapObjects(::one))
    }
}

/** One parameter of a tool, in words. */
@Immutable
data class McpParam(val name: String, val type: String, val required: Boolean, val description: String?, val default: String?, val choices: List<String>)

/** A tool in full, from the server itself. Its words are the server's own. */
@Immutable
data class McpTool(
    val name: String,
    val title: String?,
    val description: String?,
    val readOnly: Boolean?,
    val destructive: Boolean?,
    val idempotent: Boolean?,
    val openWorld: Boolean?,
    val params: List<McpParam>,
    val schema: String?,
)

/** A prompt or a resource a server offers. */
@Immutable
data class McpThing(val name: String, val description: String?, val uri: String?, val args: List<String>)

/** [whyNot] says why there's nothing: [Mcp]'s WHY_ words. */
@Immutable
data class McpFacts(val tools: List<McpTool>, val prompts: List<McpThing>, val resources: List<McpThing>, val instructions: String?, val moreTools: Int, val whyNot: String?) {
    companion object {
        private fun flag(o: JSONObject?, k: String): Boolean? = if (o != null && o.has(k)) o.bool(k) else null
        private fun thing(o: JSONObject): McpThing? {
            val name = o.str("name") ?: return null
            return McpThing(name, o.str("description"), o.str("uri"), McpWire.strings(o.arr("args")))
        }

        fun wire(o: JSONObject) = McpFacts(
            tools = o.arr("tools").mapObjects { t ->
                val name = t.str("name") ?: return@mapObjects null
                val a = t.obj("annotations")
                McpTool(
                    name = name,
                    title = a?.str("title"),
                    description = t.str("description"),
                    readOnly = flag(a, "readOnly"),
                    destructive = flag(a, "destructive"),
                    idempotent = flag(a, "idempotent"),
                    openWorld = flag(a, "openWorld"),
                    params = t.arr("params").mapObjects { p ->
                        val n = p.str("name") ?: return@mapObjects null
                        McpParam(n, p.str("type") ?: "any", p.bool("required"), p.str("description"), p.str("default"), McpWire.strings(p.arr("choices")))
                    },
                    schema = t.str("schema"),
                )
            },
            prompts = o.arr("prompts").mapObjects(::thing),
            resources = o.arr("resources").mapObjects(::thing),
            instructions = o.str("instructions"),
            moreTools = o.int("moreTools") ?: 0,
            whyNot = o.str("whyNot"),
        )
    }
}

object Mcp {
    const val HOME = "home"
    const val USER = "user"
    const val LOCAL = "local"
    const val PROJECT = "project"
    const val SETTINGS = "settings"
    const val PLUGIN = "plugin"
    const val CONNECTED = "connected"
    const val FAILED = "failed"
    const val NEEDS_AUTH = "needs-auth"
    const val PENDING = "pending"
    const val DISABLED = "disabled"
    const val WAITING = "waiting"
    const val REJECTED = "rejected"
    const val STDIO = "stdio"
    const val HTTP = "http"
    const val SSE = "sse"
    /** Codex's words for how a server signs in: only these two have an OAuth sign-in to start or end. */
    const val CODEX_OAUTH = "o_auth"
    const val CODEX_SIGNED_OUT = "not_logged_in"
}

internal object McpWire {
    fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotEmpty() } }
    fun query(vararg parts: Pair<String, String?>): String = parts.filter { it.second != null }.joinToString("&", prefix = "?") { "${it.first}=${Api.q(it.second!!)}" }
}

suspend fun Hub.mcpList(agent: String): Result<McpList> =
    agentExt("GET", "/v1/agents/mcp" + McpWire.query("agent" to agent)).map(McpList::wire)

suspend fun Hub.mcpLive(agent: String, folder: String?, fresh: Boolean = false): Result<McpLiveReply> =
    agentExt("GET", "/v1/agents/mcp/status" + McpWire.query("agent" to agent, "folder" to folder, "fresh" to if (fresh) "1" else null), slow = true).map(McpLiveReply::wire)

suspend fun Hub.mcpServer(agent: String, name: String, scope: String, folder: String?): Result<McpServer> =
    agentExt("GET", "/v1/agents/mcp/server" + McpWire.query("agent" to agent, "name" to name, "scope" to scope, "folder" to folder), slow = true).map(McpServer::wire)

suspend fun Hub.mcpTools(agent: String, name: String, scope: String, folder: String?): Result<McpFacts> =
    agentExt("GET", "/v1/agents/mcp/tools" + McpWire.query("agent" to agent, "name" to name, "scope" to scope, "folder" to folder), slow = true).map(McpFacts::wire)

/** A sign-in started: the page to open, then the address it ends at goes to [mcpLoginFinish]. [done]: nothing to sign in to, and [words] says why. */
@Immutable
data class McpLogin(val done: Boolean, val login: String?, val url: String?, val words: String?)

/** A row of a form's env vars or headers: a new [value], or null to keep what's on the server, which the phone doesn't know. */
@Immutable
data class McpPair(val name: String, val value: String?)

/** What a form sends. Secrets go one way: values aren't read back, and [McpPair.value] null keeps the server's. */
@Immutable
data class McpForm(
    val agent: String,
    val scope: String,
    val folder: String?,
    val name: String,
    val transport: String,
    val command: String,
    val args: List<String>,
    val url: String,
    val env: List<McpPair>,
    val headers: List<McpPair>,
    val clientId: String?,
    val callbackPort: Int?,
    val clientSecret: String?,
    val confirmRun: Boolean,
    val expected: String?,
    /** Codex: the variable a bearer token comes from. */
    val bearerEnv: String? = null,
) {
    fun wire(): JSONObject {
        val pairs = { list: List<McpPair> ->
            JSONArray().also { a ->
                list.forEach { p -> a.put(JSONObject().put("name", p.name).also { if (p.value == null) it.put("keep", true) else it.put("value", p.value) }) }
            }
        }
        val o = JSONObject().put("agent", agent).put("scope", scope).put("name", name).put("transport", transport)
        folder?.let { o.put("folder", it) }
        expected?.let { o.put("expected", it) }
        if (confirmRun) o.put("confirmRun", true)
        if (transport == Mcp.STDIO) {
            o.put("command", command).put("args", JSONArray().also { a -> args.forEach { x -> a.put(x) } }).put("env", pairs(env))
        } else {
            o.put("url", url).put("headers", pairs(headers))
            if (agent == AgentSettingsReply.CODEX) {
                o.put("bearerEnv", bearerEnv ?: JSONObject.NULL)
                return o
            }
            val oauth = JSONObject().put("clientId", clientId ?: JSONObject.NULL).put("callbackPort", callbackPort ?: JSONObject.NULL)
            clientSecret?.takeIf { it.isNotEmpty() }?.let { oauth.put("clientSecret", it) }
            o.put("oauth", oauth)
        }
        return o
    }
}

suspend fun Hub.mcpAdd(form: McpForm): Result<JSONObject> = agentExt("POST", "/v1/agents/mcp/servers", form.wire(), slow = true)
suspend fun Hub.mcpEdit(form: McpForm): Result<JSONObject> = agentExt("PUT", "/v1/agents/mcp/servers", form.wire(), slow = true)
suspend fun Hub.mcpRemove(s: McpServer): Result<JSONObject> = agentExt("POST", "/v1/agents/mcp/servers/remove", JSONObject().put("agent", s.agent).put("scope", s.scope).put("name", s.name).put("expected", s.hash ?: "").also { o -> s.folder?.let { o.put("folder", it) } }, slow = true)
suspend fun Hub.mcpToggle(agent: String, name: String, folder: String?, enabled: Boolean): Result<JSONObject> = agentExt("POST", "/v1/agents/mcp/toggle", JSONObject().put("agent", agent).put("name", name).put("folder", folder ?: Mcp.HOME).put("enabled", enabled), slow = true)
suspend fun Hub.mcpReconnect(agent: String, name: String, folder: String?): Result<McpLive?> = agentExt("POST", "/v1/agents/mcp/reconnect", JSONObject().put("agent", agent).put("name", name).put("folder", folder ?: Mcp.HOME), slow = true).map { o -> o.obj("server")?.let(McpLiveReply::one) }
suspend fun Hub.mcpApproval(agent: String, name: String, folder: String?, approved: Boolean): Result<JSONObject> = agentExt("POST", "/v1/agents/mcp/approval", JSONObject().put("agent", agent).put("name", name).put("folder", folder ?: Mcp.HOME).put("approved", approved).also { if (approved) it.put("confirmRun", true) }, slow = true)
suspend fun Hub.mcpResetChoices(agent: String, folder: String?): Result<JSONObject> = agentExt("POST", "/v1/agents/mcp/reset-choices", JSONObject().put("agent", agent).put("folder", folder ?: Mcp.HOME), slow = true)
suspend fun Hub.mcpLogin(agent: String, name: String, folder: String?): Result<McpLogin> = agentExt("POST", "/v1/agents/mcp/login", JSONObject().put("agent", agent).put("name", name).put("folder", folder ?: Mcp.HOME), slow = true).map { o -> McpLogin(o.bool("done"), o.str("login"), o.str("url"), o.str("words")) }
suspend fun Hub.mcpLoginFinish(agent: String, login: String, address: String): Result<JSONObject> = agentExt("POST", "/v1/agents/mcp/login/finish", JSONObject().put("agent", agent).put("login", login).put("address", address), slow = true)
suspend fun Hub.mcpLoginCancel(agent: String, login: String): Result<JSONObject> = agentExt("POST", "/v1/agents/mcp/login/cancel", JSONObject().put("agent", agent).put("login", login))
suspend fun Hub.mcpLogout(agent: String, name: String, folder: String?): Result<JSONObject> = agentExt("POST", "/v1/agents/mcp/logout", JSONObject().put("agent", agent).put("name", name).put("folder", folder ?: Mcp.HOME), slow = true)
suspend fun Hub.mcpUndo(agent: String, confirmRun: Boolean): Result<JSONObject> = agentExt("POST", "/v1/agents/mcp/undo", JSONObject().put("agent", agent).also { if (confirmRun) it.put("confirmRun", true) }, slow = true)
