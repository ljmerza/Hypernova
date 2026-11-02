package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/** An installed plugin: [scope] is where it's on. [problem]: what the agent says is wrong with it. */
@Immutable
data class PluginRow(val id: String, val name: String, val marketplace: String?, val version: String?, val scope: String, val enabled: Boolean, val hasConfig: Boolean, val mcpServers: List<String>, val source: String?, val problem: String?)

/** A marketplace: [type] is the agent's word (`github`, `directory`, `local`…), [where] its source, masked. */
@Immutable
data class Marketplace(val name: String, val type: String?, val where: String?)

@Immutable
data class PluginsReply(val agent: String, val plugins: List<PluginRow>, val marketplaces: List<Marketplace>, val scopes: List<String>, val folders: List<McpFolder>) {
    companion object {
        fun folders(o: JSONObject) = o.arr("folders").mapObjects { f ->
            val id = f.str("id") ?: return@mapObjects null
            McpFolder(id, f.str("name") ?: id)
        }

        fun wire(o: JSONObject) = PluginsReply(
            agent = o.str("agent") ?: AgentSettingsReply.CLAUDE,
            plugins = o.arr("plugins").mapObjects { p ->
                val id = p.str("id") ?: return@mapObjects null
                PluginRow(id, p.str("name") ?: id, p.str("marketplace"), p.str("version"), p.str("scope") ?: Mcp.USER, p.bool("enabled"), p.bool("hasConfig"), McpWire.strings(p.arr("mcpServers")), p.str("source"), p.str("problem"))
            },
            marketplaces = o.arr("marketplaces").mapObjects { m ->
                val name = m.str("name") ?: return@mapObjects null
                Marketplace(name, m.str("type"), m.str("where"))
            },
            scopes = McpWire.strings(o.arr("scopes")),
            folders = folders(o),
        )
    }
}

/** A plugin that can be installed, with what the confirm has to say of where it comes from. */
@Immutable
data class AvailablePlugin(val id: String, val description: String?, val marketplace: String?, val version: String?, val source: String?, val marketplaceSource: String?)

@Immutable
data class AvailableReply(val total: Int, val plugins: List<AvailablePlugin>) {
    companion object {
        fun wire(o: JSONObject) = AvailableReply(
            o.int("total") ?: 0,
            o.arr("plugins").mapObjects { p ->
                val id = p.str("id") ?: return@mapObjects null
                AvailablePlugin(id, p.str("description"), p.str("marketplace"), p.str("version"), p.str("source"), p.str("marketplaceSource"))
            },
        )
    }
}

/** One of a plugin's options. A [sensitive] one is write-only: [value] is never sent, [set] says it has one. */
@Immutable
data class PluginOption(val key: String, val title: String?, val description: String?, val sensitive: Boolean, val set: Boolean, val value: String?)

/** What a plugin brings. */
@Immutable
data class PluginDetail(
    val id: String,
    val description: String?,
    val skills: List<String>,
    val agents: List<String>,
    val hooks: List<String>,
    val mcpServers: List<String>,
    val lspServers: List<String>,
    val commands: List<String>,
    val apps: List<String>,
    val alwaysOnTokens: Int?,
    val options: List<PluginOption>,
) {
    companion object {
        fun wire(o: JSONObject) = PluginDetail(
            id = o.str("id").orEmpty(),
            description = o.str("description"),
            skills = McpWire.strings(o.arr("skills")),
            agents = McpWire.strings(o.arr("agents")),
            hooks = McpWire.strings(o.arr("hooks")),
            mcpServers = McpWire.strings(o.arr("mcpServers")),
            lspServers = McpWire.strings(o.arr("lspServers")),
            commands = McpWire.strings(o.arr("commands")),
            apps = McpWire.strings(o.arr("apps")),
            alwaysOnTokens = o.int("alwaysOnTokens"),
            options = o.arr("options").mapObjects { p ->
                val key = p.str("key") ?: return@mapObjects null
                PluginOption(key, p.str("title"), p.str("description"), p.bool("sensitive"), p.bool("set"), p.str("value"))
            },
        )
    }
}

/** What came of an install or an update: [acceptCommand] and [command] when it wants a shown command accepted first. */
@Immutable
data class PluginChange(val ok: Boolean, val message: String?, val command: String?, val acceptCommand: String?) {
    companion object {
        fun wire(o: JSONObject): PluginChange {
            val a = o.obj("needsAccept")
            return PluginChange(o.bool("ok"), o.str("message"), a?.str("command"), a?.str("sha256"))
        }
    }
}

private fun JSONObject.at(scope: String?, folder: String?) = also { o ->
    scope?.let { o.put("scope", it) }
    folder?.let { o.put("folder", it) }
}

suspend fun Hub.plugins(agent: String, folder: String?): Result<PluginsReply> =
    agentExt("GET", "/v1/agents/plugins" + McpWire.query("agent" to agent, "folder" to folder), slow = true).map(PluginsReply::wire)
suspend fun Hub.pluginsAvailable(agent: String, folder: String?, q: String): Result<AvailableReply> =
    agentExt("GET", "/v1/agents/plugins/available" + McpWire.query("agent" to agent, "folder" to folder, "q" to q.ifBlank { null }), slow = true).map(AvailableReply::wire)
suspend fun Hub.pluginDetail(agent: String, id: String, folder: String?): Result<PluginDetail> =
    agentExt("GET", "/v1/agents/plugins/detail" + McpWire.query("agent" to agent, "plugin" to id, "folder" to folder), slow = true).map(PluginDetail::wire)
suspend fun Hub.pluginInstall(agent: String, id: String, scope: String, folder: String?, accept: String? = null): Result<PluginChange> =
    agentExt("POST", "/v1/agents/plugins/install", JSONObject().put("agent", agent).put("plugin", id).put("confirmRun", true).at(scope, folder).also { o -> accept?.let { o.put("acceptCommand", it) } }, slow = true).map(PluginChange::wire)
suspend fun Hub.pluginUninstall(agent: String, id: String, scope: String, folder: String?): Result<PluginChange> =
    agentExt("POST", "/v1/agents/plugins/uninstall", JSONObject().put("agent", agent).put("plugin", id).at(scope, folder), slow = true).map(PluginChange::wire)
suspend fun Hub.pluginToggle(agent: String, id: String, scope: String, folder: String?, on: Boolean): Result<PluginChange> =
    agentExt("POST", "/v1/agents/plugins/toggle", JSONObject().put("agent", agent).put("plugin", id).put("enabled", on).at(scope, folder), slow = true).map(PluginChange::wire)
suspend fun Hub.pluginUpdate(agent: String, id: String, scope: String, folder: String?, accept: String? = null): Result<PluginChange> =
    agentExt("POST", "/v1/agents/plugins/update", JSONObject().put("agent", agent).put("plugin", id).at(scope, folder).also { o -> accept?.let { o.put("acceptCommand", it).put("confirmRun", true) } }, slow = true).map(PluginChange::wire)
suspend fun Hub.pluginConfigure(agent: String, id: String, folder: String?, values: Map<String, String>): Result<PluginChange> =
    agentExt("POST", "/v1/agents/plugins/configure", JSONObject().put("agent", agent).put("plugin", id).put("values", JSONObject().also { v -> values.forEach { (k, x) -> v.put(k, x) } }).at(null, folder), slow = true).map(PluginChange::wire)
suspend fun Hub.marketplaceAdd(agent: String, source: String, scope: String, folder: String?): Result<PluginChange> =
    agentExt("POST", "/v1/agents/plugins/marketplaces/add", JSONObject().put("agent", agent).put("source", source).put("confirmRun", true).at(scope, folder), slow = true).map(PluginChange::wire)
suspend fun Hub.marketplaceRemove(agent: String, name: String, folder: String?): Result<PluginChange> =
    agentExt("POST", "/v1/agents/plugins/marketplaces/remove", JSONObject().put("agent", agent).put("name", name).at(null, folder), slow = true).map(PluginChange::wire)
suspend fun Hub.marketplaceUpdate(agent: String, name: String?, folder: String?): Result<PluginChange> =
    agentExt("POST", "/v1/agents/plugins/marketplaces/update", JSONObject().put("agent", agent).also { o -> name?.let { o.put("name", it) } }.at(null, folder), slow = true).map(PluginChange::wire)
