package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * The fixed list of Claude Code's and Codex's user-level settings on a server (docs/PROTOCOL.md,
 * "Agent settings"). Read-only. The server decides what may leave, so a key the app has no words
 * for is shown with its raw name.
 */
@Immutable
data class AgentSettingsReply(val claude: AgentSettingsFile, val codex: AgentSettingsFile) {
    fun of(agent: String): AgentSettingsFile = if (agent == CODEX) codex else claude

    companion object {
        const val CLAUDE = "claude"
        const val CODEX = "codex"

        fun wire(o: JSONObject) = AgentSettingsReply(file(o.obj(CLAUDE)), file(o.obj(CODEX)))

        private fun file(o: JSONObject?): AgentSettingsFile {
            if (o == null) return AgentSettingsFile(AgentSettingsFile.UNREADABLE, emptyList(), null, null)
            val summary = o.obj("summary")
            return AgentSettingsFile(
                state = o.str("state") ?: AgentSettingsFile.UNREADABLE,
                values = o.arr("values").mapObjects(::shown),
                claude = summary?.takeIf { it.has("rules") }?.let(::claudeSummary),
                codex = summary?.takeIf { it.has("projects") }?.let(::codexSummary),
                mcp = o.obj("mcp")?.let(::mcp),
            )
        }

        private fun shown(o: JSONObject): ShownSetting? {
            val key = o.str("key") ?: return null
            val value = o.opt("value")
            return when {
                o.str("state") == ShownSetting.UNSET -> ShownSetting(key, ShownSetting.UNSET, null, null)
                o.str("state") == ShownSetting.SET && value is Boolean -> ShownSetting(key, ShownSetting.SET, null, value)
                o.str("state") == ShownSetting.SET && value is String -> ShownSetting(key, ShownSetting.SET, value, null)
                else -> ShownSetting(key, ShownSetting.UNRECOGNISED, null, null)
            }
        }

        private fun mcp(o: JSONObject) = ClaudeMcp(
            state = o.str("state") ?: AgentSettingsFile.UNREADABLE,
            servers = o.arr("servers").mapObjects { s ->
                val name = s.str("name") ?: return@mapObjects null
                McpName(name, s.str("scope") ?: ClaudeMcp.USER, s.str("project"))
            },
            hidden = o.int("hidden") ?: 0,
            foldersNotRead = o.int("foldersNotRead") ?: 0,
        )

        private fun claudeSummary(o: JSONObject): ClaudeSettingsSummary {
            val rules = o.obj("rules")
            return ClaudeSettingsSummary(
                allow = rules?.int("allow"),
                ask = rules?.int("ask"),
                deny = rules?.int("deny"),
                plugins = o.arr("plugins")?.let { list ->
                    list.mapObjects { p -> p.str("name")?.let { PluginSetting(it, if (p.isNull("on")) null else p.bool("on")) } }
                },
                mcpServers = names(o.arr("mcpServers")),
                hookEvents = names(o.arr("hookEvents")),
                hidden = o.int("hidden") ?: 0,
            )
        }

        private fun codexSummary(o: JSONObject) = CodexSettingsSummary(o.int("projects"), names(o.arr("mcpServers")), o.int("hidden") ?: 0)

        private fun names(a: JSONArray?): List<String>? = a?.let { list -> (0 until list.length()).mapNotNull { i -> list.optString(i).takeIf { it.isNotEmpty() } } }
    }
}

/** One agent's file: its [state], the shown [values], and one of the summaries when it was read. */
@Immutable
data class AgentSettingsFile(
    val state: String,
    val values: List<ShownSetting>,
    val claude: ClaudeSettingsSummary?,
    val codex: CodexSettingsSummary?,
    /** Claude Code's MCP servers: beside the file, which needn't be there for them. Null from an older server. */
    val mcp: ClaudeMcp? = null,
) {
    companion object {
        const val OK = "ok"
        const val MISSING = "missing"
        const val MALFORMED = "malformed"
        const val UNREADABLE = "unreadable"
    }
}

/** A shown key: [SET] with its [text] or [on], [UNSET], or [UNRECOGNISED], whose value stayed on the server. */
@Immutable
data class ShownSetting(val key: String, val state: String, val text: String?, val on: Boolean?) {
    companion object {
        const val SET = "set"
        const val UNSET = "unset"
        const val UNRECOGNISED = "unrecognised"
    }
}

/** [on] null: neither on nor off in the file. */
@Immutable
data class PluginSetting(val name: String, val on: Boolean?)

/** A null is something other than the expected list in the file. [hidden]: names the server held back. */
@Immutable
data class ClaudeSettingsSummary(
    val allow: Int?,
    val ask: Int?,
    val deny: Int?,
    val plugins: List<PluginSetting>?,
    val mcpServers: List<String>?,
    val hookEvents: List<String>?,
    val hidden: Int,
)

@Immutable
data class CodexSettingsSummary(val projects: Int?, val mcpServers: List<String>?, val hidden: Int)

/** One MCP server's name and where it's set: [project] is a folder's base name, for local and project scope. */
@Immutable
data class McpName(val name: String, val scope: String, val project: String?)

/** [names] to show of a group, and how many more it has. [project] null: every project on the server. */
@Immutable
data class McpGroup(val project: String?, val names: List<String>, val more: Int)

/** Claude Code's MCP servers on a server (docs/PROTOCOL.md, "Agent settings"). [state] is `~/.claude.json`'s. */
@Immutable
data class ClaudeMcp(val state: String, val servers: List<McpName>, val hidden: Int, val foldersNotRead: Int) {
    /** The servers to show, grouped: the server's own first, then each project's by name, each with a cap. */
    fun groups(): Pair<List<McpGroup>, Int> {
        val own = servers.filter { it.scope == USER || it.project == null }.map { it.name }.distinct()
        val projects = servers.filter { it.scope != USER && it.project != null }
            .groupBy({ it.project!! }, { it.name })
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .map { (project, names) -> group(project, names.distinct()) }
        val shown = projects.take(GROUPS_SHOWN)
        return (if (own.isEmpty()) shown else listOf(group(null, own)) + shown) to projects.size - shown.size
    }

    private fun group(project: String?, names: List<String>) = McpGroup(project, names.take(NAMES_SHOWN), (names.size - NAMES_SHOWN).coerceAtLeast(0))

    companion object {
        const val USER = "user"
        const val GROUPS_SHOWN = 12
        const val NAMES_SHOWN = 12
    }
}

/** The server has no `GET /v1/agents/settings`: it's older than this app. */
class AgentSettingsTooOld(message: String) : HubException(message)
