package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * The fixed list of Claude Code's and Codex's user-level settings on a server (docs/PROTOCOL.md,
 * "Agent settings"). The server decides what may leave, so a key the app has no words for is shown
 * with its raw name, and it decides what may be changed: only [AgentSettingsFile.editable] is, with
 * only its choices.
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
                hash = o.str("hash"),
                editable = o.arr("editable").mapObjects(::editable),
                undo = o.obj("undo")?.let(::undo),
                whyNot = o.str("whyNot"),
            )
        }

        private fun editable(o: JSONObject): EditableSetting? {
            val key = o.str("key") ?: return null
            return EditableSetting(key, o.arr("choices").mapObjects(::choice)).takeIf { it.choices.isNotEmpty() }
        }

        private fun choice(o: JSONObject): SettingChoice? = when (val v = o.opt("value")) {
            is String -> SettingChoice(v, null, o.bool("loosens"), o.str("label"))
            is Boolean -> SettingChoice(null, v, o.bool("loosens"), null)
            else -> null
        }

        private fun undo(o: JSONObject): SettingUndo? {
            val key = o.str("key") ?: return null
            val to = o.opt("to")
            return SettingUndo(key, to as? String, to as? Boolean, o.bool("loosens"))
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
    /** The file as read, sent back with a change. Null: it isn't there, or an older server. */
    val hash: String? = null,
    /** What may be changed. Empty: nothing, also from an older server. */
    val editable: List<EditableSetting> = emptyList(),
    /** The last change, if it can still be undone. */
    val undo: SettingUndo? = null,
    /** Why nothing here can be changed though the file reads fine: [LINK], [READ_ONLY], [NO_FOLDER]. */
    val whyNot: String? = null,
) {
    companion object {
        const val OK = "ok"
        const val MISSING = "missing"
        const val MALFORMED = "malformed"
        const val UNREADABLE = "unreadable"
        const val LINK = "link"
        const val READ_ONLY = "read_only"
        const val NO_FOLDER = "no_folder"
    }
}

/** A key that can be changed, and the only values the server takes for it. */
@Immutable
data class EditableSetting(val key: String, val choices: List<SettingChoice>)

/** A value to pick: its [text] or [on], whether picking it now [loosens] a permission (the server's order), and Codex's name for a model. */
@Immutable
data class SettingChoice(val text: String?, val on: Boolean?, val loosens: Boolean, val label: String?) {
    /** The value as it goes on the wire. */
    val wire: Any get() = on ?: text.orEmpty()

    /** Whether this is what [v] holds. `manual` is `default`'s other name, and only `default` is offered. */
    fun isCurrent(v: ShownSetting?): Boolean = when {
        v == null || v.state != ShownSetting.SET -> false
        on != null -> v.on == on
        else -> v.text == text || (v.key == "permissions.defaultMode" && v.text == "manual" && text == "default")
    }
}

/** What Undo would do: [key] back to [toText] or [toOn]; both null, to not being set. */
@Immutable
data class SettingUndo(val key: String, val toText: String?, val toOn: Boolean?, val loosens: Boolean)

/** What came of a change. [changed] false: it already had that value. */
@Immutable
data class SettingChange(val changed: Boolean, val loosened: Boolean, val hash: String?) {
    companion object {
        fun wire(o: JSONObject) = SettingChange(o.bool("changed"), o.bool("loosened"), o.str("hash"))
    }
}

/** The server refused a change or an undo: its [code], and in [message] its own words to show. */
class AgentSettingRefused(val code: String?, message: String) : HubException(message) {
    companion object {
        const val FILE_CHANGED = "file_changed"
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

/** The server has no `GET /v1/agents/settings`: it's older than this app. */
class AgentSettingsTooOld(message: String) : HubException(message)
