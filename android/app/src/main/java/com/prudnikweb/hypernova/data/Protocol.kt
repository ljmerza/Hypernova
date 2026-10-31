package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/*
 * The daemon's wire types (docs/PROTOCOL.md, v1), parsed with the platform's org.json.
 * Unknown fields are ignored and unknown enum values are kept as strings, so a newer
 * daemon never crashes an older app.
 */

@Immutable
data class MacInfo(
    val id: String,
    val name: String,
    val daemonVersion: String?,
    val claudeVersion: String?,
    val projectsRoot: String?,
    /** The Android app the Mac hands out (its build number), to offer an update. */
    val appVersionCode: Int? = null,
    val appVersionName: String? = null,
    /** Null for a Mac without a battery, or a daemon too old to say. */
    val battery: MacBattery? = null,
    /** Stay awake (data/Awake.kt); null from a daemon too old to say. */
    val awake: MacAwake? = null,
    /** The computer's system: [Mac.DARWIN] (also an older daemon, which doesn't say) or [Mac.LINUX]. */
    val platform: String = Mac.DARWIN,
    /** Settings → Browse outside projects (docs/PROTOCOL.md, "Files"); null from a daemon too old to have it. */
    val browseOutsideProjects: Boolean? = null,
    /** The Mac features this computer lacks, each with its sentence (docs/PROTOCOL.md, "Mac-only features"). */
    val unavailable: Map<String, String> = emptyMap(),
)

@Immutable
data class WSession(
    val id: String,
    val title: String,
    val cwd: String,
    val projectName: String,
    val origin: String,
    val state: String,
    val providerId: String?,
    val model: String?,
    val permissionMode: String,
    val createdAt: Long,
    val lastActivityAt: Long,
    val lastText: String?,
    val activity: String?,
    val pendingApprovalIds: List<String>,
    val stoppedForAway: Boolean,
    val contextPct: Double?,
    val costUsd: Double?,
    val queued: Int,
    /** The shared terminal it runs in right now, if any. */
    val term: TermLink? = null,
    /** A menu or dialog its terminal shows instead of the input box, to answer with buttons. */
    val menu: TermMenu? = null,
    /** Prompts waiting until Claude can take them, oldest first ([queued] of them). */
    val queue: List<WQueued> = emptyList(),
    /** Agents its main agent started that are running now. */
    val agentsRunning: Int = 0,
    /** Stopped by an account or provider problem (a limit, signed out…), until a real reply clears it. */
    val blocked: Blocked? = null,
    /** Can switch to Bypass (an older Mac doesn't say: then yes, and the switch reports if not). */
    val bypassAvailable: Boolean = true,
    /** Why it ended when nobody ended it: "mac_restart" (the Mac restarted with it open); null otherwise. */
    val endedBy: String? = null,
    /** The automation that started it (data/Automations.kt); null for the owner's own. */
    val automation: SessionAutomation? = null,
    /** Its folder can be browsed from the phone (an older Mac doesn't say: then yes). */
    val browsable: Boolean = true,
    /** Its folder is in the home folder, outside the projects folder: Browse outside projects would open it. */
    val outsideProjects: Boolean = false,
    /** Which agent runs it: [AgentKind.CLAUDE] (an older Mac doesn't say), or [AgentKind.CODEX] (data/Codex.kt). */
    val agent: String = AgentKind.CLAUDE,
    /** It switched agents: the session it came from, and the one it went on in (docs/PROTOCOL.md, "Switching agents"). */
    val switchedFrom: String? = null,
    val switchedTo: String? = null,
    /** Its `claude agents` job id, when it is one (docs/PROTOCOL.md, "Session menu"): it can be stopped or deleted. */
    val agentJob: String? = null,
    /** Its `claude agents` job's process runs (docs/PROTOCOL.md, "Session menu"); null when the Mac doesn't say. */
    val agentRunning: Boolean? = null,
    /** Its job runs an older Claude Code than the one installed: both versions; an older Mac doesn't say. */
    val agentOutdated: OutdatedClaude? = null,
    /** What a `claude agents` job asked in words, when it waits on a reply rather than a dialog. */
    val question: String? = null,
    /** Calls refused without the owner (docs/PROTOCOL.md, "Blocked calls"); an older Mac doesn't say: none. */
    val blockedCalls: Int = 0,
    /** Fork: prompts the Mac holds until Claude Code's input box is back, oldest first (docs/PROTOCOL.md, "Held prompts"). */
    val held: List<WQueued> = emptyList(),
    /** Claude Code's reasoning effort ("low"…"max"; docs/PROTOCOL.md, "A session's effort"); null: not known (its default), Codex, or an older Mac. */
    val effort: String? = null,
    /** When its turn started (docs/PROTOCOL.md, Session.turnStartedAt); null out of a turn, or from an older Mac. */
    val turnStartedAt: Long? = null,
    /** Claude's to-do list in short (docs/PROTOCOL.md, Session.todo); null: none, or an older Mac. */
    val todo: TodoNow? = null,
    /** When the turn it works on started (Session.workingSince); null: not working, or an older Mac. */
    val workingSince: Long? = null,
)

/**
 * An agent a session's main agent started (or an agent started, [parentAgentId]), alone or in a
 * workflow run ([workflowId], in [phase]). [toolUseId] is the Agent call that started it. [status]
 * is "running", "done", "error" or "stopped" (an unknown one draws as stopped); [activity] says
 * what it does while it runs ("Bash: ./gradlew build"); [result] is its report, trimmed.
 */
@Immutable
data class WAgent(
    val id: String,
    val sessionId: String,
    val toolUseId: String?,
    val parentAgentId: String?,
    val workflowId: String?,
    val phase: String?,
    val label: String,
    val type: String,
    val model: String?,
    val background: Boolean,
    val status: String,
    val activity: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val lastActivityAt: Long,
    val tokens: Long?,
    val toolUses: Int?,
    val result: String?,
) {
    val running: Boolean get() = status == "running"
}

/** A workflow run: agents in phases, started by one Workflow call ([toolUseId]). Its [id] is the run id. */
@Immutable
data class WWorkflow(
    val id: String,
    val sessionId: String,
    val toolUseId: String?,
    val name: String,
    val summary: String?,
    val status: String,
    val phases: List<WPhase>,
    val agentCount: Int,
    val done: Int,
    val failed: Int,
    val startedAt: Long,
    val endedAt: Long?,
    val tokens: Long?,
    val toolUses: Int?,
) {
    val running: Boolean get() = status == "running"
}

@Immutable
data class WPhase(val title: String, val detail: String?)

/**
 * A prompt waiting until Claude can take it: in Claude Code's queue (typed while Claude works),
 * or behind the running prompt of an SDK session. [text] is cut at 300 characters (ending in
 * "…"); [clientId] names the phone send it came from, null when it was typed on the Mac.
 */
@Immutable
data class WQueued(val id: String, val text: String, val files: List<Attachment>, val at: Long, val clientId: String?)

/**
 * Queued prompts that left the queue without reaching Claude: [returned] to the terminal's input
 * box (the Up arrow on the Mac), or else dropped (cleared, or a Stop in an SDK session).
 */
@Immutable
data class Unsent(val returned: Boolean, val prompts: List<Prompt>) {
    @Immutable data class Prompt(val text: String, val clientId: String?)
}

/**
 * A slash command or skill Claude Code offers in a project (the "/" list): Claude Code's own
 * ([builtin]), or the owner's, the project's, a plugin's. [terminal]: one of Claude Code's own that
 * opens a screen of its own in the terminal, which the phone can't work (an older Mac never says).
 */
@Immutable
data class SlashCommand(val name: String, val description: String, val argumentHint: String, val aliases: List<String>, val builtin: Boolean, val terminal: Boolean = false)

/** A session's shared terminal on the Mac, and who has it open ([mac] terminal windows, [phone] screens). */
@Immutable
data class TermLink(val id: String, val mac: Int, val phone: Int)

/**
 * A menu Claude Code shows in a shared terminal (the /model picker, a dialog with no card here),
 * read off its screen. [cursor] is the option Enter picks there; [more] means it scrolls past
 * what the screen shows; [cancel] means Esc closes it; [alt] is the footer's second way to pick
 * ("s to use this session only"). [permission]: it's Claude Code's own permission dialog (an ask
 * rule over auto mode, or an ask its hook handed back), drawn as an approval card ([TerminalAsk]).
 */
@Immutable
data class TermMenu(
    val id: String,
    val title: String?,
    val body: List<String>,
    val options: List<Option>,
    val cursor: Int,
    val more: Boolean,
    val cancel: Boolean,
    val alt: Alt?,
    val permission: Permission? = null,
) {
    @Immutable data class Option(val label: String, val detail: String?, val current: Boolean)
    @Immutable data class Alt(val enter: String, val key: String, val label: String)
    /** [tool]: the dialog's title ("Bash command"); [command]: what it would run, as the screen shows it. */
    @Immutable data class Permission(val tool: String?, val command: String?, val question: String)
}

@Immutable
data class WHunk(val oldStart: Int, val oldLines: Int, val newStart: Int, val newLines: Int, val lines: List<String>)

@Immutable
data class WDiff(val path: String, val added: Int, val removed: Int, val hunks: List<WHunk>)

/**
 * A command the owner ran in Claude Code: [name] with its "/" ("/effort"), or "!" for a shell line
 * typed in bash mode; [args] is the rest ("ultracode", or the shell command). [kind] is "local"
 * (Claude Code's own), "skill", "prompt" (the owner's other commands: a prompt or a plugin's
 * command) or "bash"; a kind this build doesn't know draws as "prompt". [guessed]: read from text
 * typed on this phone, so the kind is a guess until the Mac's copy arrives.
 */
@Immutable
data class CommandRun(val name: String, val args: String, val kind: String, val guessed: Boolean = false) {
    val bash: Boolean get() = kind == "bash" || name == "!"

    /** How the transcript draws it. */
    val type: CommandKind get() = when {
        bash -> CommandKind.Bash
        kind == "local" -> CommandKind.Local
        kind == "skill" -> CommandKind.Skill
        else -> CommandKind.Prompt
    }

    /** The name as the "/" list shows it: "effort", "telegram:access". */
    val bare: String get() = name.removePrefix("/")

    /**
     * A guess made with the project's "/" list ([known], null when it hasn't been fetched): Claude
     * Code's own command is local, anything else on the list is one of the owner's. Whether that
     * is a skill or a command, only the Mac's copy says.
     */
    fun refined(known: List<SlashCommand>?): CommandRun {
        if (!guessed || bash || known == null) return this
        val c = known.find(name) ?: return this
        // Claude Code marks its own prompt-type commands and bundled skills (/init, /code-review) as
        // built-in too; those are recorded like skills, so only the plain settings commands are local.
        val k = if (c.builtin && (c.name in BUILTIN || c.aliases.any { it in BUILTIN })) "local" else "prompt"
        return if (k == kind) this else copy(kind = k)
    }

    companion object {
        // One word after the slash: "/Users/me/shot.png what's this" is a path, not a command.
        private val NAME = Regex("^/[A-Za-z0-9][\\w.:-]*$")
        private val SPACE = Regex("\\s")

        /**
         * Claude Code's own commands, for the guess made before the "/" list is known: anything
         * else is taken for one of the owner's (a skill or a command, drawn as a prompt).
         */
        private val BUILTIN = setOf(
            "effort", "model", "compact", "clear", "reset", "new", "context", "usage", "cost", "stats",
            "config", "settings", "fast", "rename", "color", "theme", "login", "logout", "resume",
            "export", "rewind", "help", "status", "permissions", "allowed-tools", "memory", "mcp",
            "agents", "hooks", "doctor", "add-dir", "exit", "quit", "plugin", "todos", "ide", "upgrade",
            "output-style", "privacy-settings", "reload-plugins", "reload-skills", "skills", "autocompact",
            "rate-limit-options", "usage-credits", "advisor",
            // Claude Code's own that it runs at once, even while Claude works (owner, 2026-10-30: "/btw
            // I'm testing the btw" read as a skill's chip and its words): drawn as typed, a COMMAND row.
            "btw", "version", "stop", "tasks", "workflows", "loops", "focus", "bug", "feedback",
        )

        /**
         * How Claude Code will read a prompt typed on the phone, so its bubble looks like a command
         * before the Mac's copy arrives: "/name args", "!command", or null for plain text. Whether
         * a slash command is Claude Code's own is a guess until then ([refined] sharpens it).
         */
        fun typed(text: String): CommandRun? {
            val t = text.trim()
            if (t.startsWith("!")) return t.drop(1).trim().takeIf { it.isNotEmpty() }?.let { CommandRun("!", it, "bash", guessed = true) }
            if (!t.startsWith("/")) return null
            val parts = t.split(SPACE, limit = 2)
            if (!NAME.matches(parts[0])) return null
            val kind = if (parts[0].drop(1).lowercase() in BUILTIN) "local" else "prompt"
            return CommandRun(parts[0], parts.getOrNull(1)?.trim().orEmpty(), kind, guessed = true)
        }

        /**
         * Where, in text being typed, the command it starts with sits: "/name" (or a lone "/", the
         * list is open), or bash mode's "!". Null for a prompt (or a path like "/Users/…").
         */
        fun lead(text: String): IntRange? {
            val start = text.indexOfFirst { !it.isWhitespace() }
            if (start < 0) return null
            return when (text[start]) {
                '!' -> start..start
                '/' -> {
                    var end = start + 1
                    while (end < text.length && !text[end].isWhitespace()) end++
                    val token = text.substring(start, end)
                    if ((token == "/" && end == text.length) || NAME.matches(token)) start until end else null
                }
                else -> null
            }
        }
    }
}

/**
 * The command on a project's "/" list called [name] ("cost" or "/cost"): by its name, or else one
 * of its aliases, since Claude Code logs a command under its own name ("/cost" as "/usage").
 */
fun List<SlashCommand>.find(name: String): SlashCommand? {
    val n = name.removePrefix("/")
    return firstOrNull { it.name == n } ?: firstOrNull { n in it.aliases }
}

/**
 * Whether two command names ("/cost", "/usage") are one command on the project's "/" list
 * ([known]), by its name or an alias. Without the list, only the same name is.
 */
fun sameCommand(a: String, b: String, known: List<SlashCommand>?): Boolean {
    if (a == b) return true
    val c = known?.find(a) ?: return false
    return c == known.find(b)
}

/**
 * The last "/" list fetched for each project (by its folder). State, so what reads it redraws
 * once a list arrives: a command sent from the phone draws as Claude Code's own or as the
 * owner's before the Mac's copy says which, and the hub matches "/cost" to its "/usage" copy.
 */
private val fetchedCommands = mutableStateMapOf<String, List<SlashCommand>>()

/** The project's "/" list if this app has fetched it, else null; it never asks the Mac itself. */
fun knownCommands(cwd: String?): List<SlashCommand>? = fetchedCommands[cwd.orEmpty()]

/** A "/" list just fetched for the project in [cwd], for [knownCommands]. */
fun noteCommands(cwd: String?, list: List<SlashCommand>) {
    fetchedCommands[cwd.orEmpty()] = list
}

sealed interface WEvent {
    val id: String
    val ts: Long

    /**
     * [files]: attached on a phone (on the Mac now), shown apart from the text. [command]: it was a
     * command. [clientId]: the phone send it came from, when the Mac knows it.
     */
    @Immutable data class User(
        override val id: String, override val ts: Long, val text: String,
        val files: List<Attachment> = emptyList(),
        val command: CommandRun? = null,
        val clientId: String? = null,
    ) : WEvent
    @Immutable data class Assistant(override val id: String, override val ts: Long, val text: String, val streaming: Boolean) : WEvent
    @Immutable data class Thinking(override val id: String, override val ts: Long, val chars: Int) : WEvent
    @Immutable data class Tool(
        override val id: String, override val ts: Long,
        val toolUseId: String?, val name: String, val summary: String?,
        /** A few useful input fields, flattened (command, file_path, url, pattern…). */
        val input: Map<String, String>,
        val status: String, val output: String?, val exitCode: Int?, val durationMs: Long?,
        val diff: WDiff?, val path: String?,
    ) : WEvent
    @Immutable data class Approval(override val id: String, override val ts: Long, val approvalId: String, val summary: String?, val resolution: String?) : WEvent
    @Immutable data class Result(
        override val id: String, override val ts: Long,
        val ok: Boolean, val durationMs: Long?, val costUsd: Double?, val turns: Int?, val error: String?,
    ) : WEvent
    /**
     * [command]: what a command printed, with the id of the command's own user event. [unsent]:
     * queued prompts this notice says didn't reach Claude.
     */
    /**
     * [blocker]: Claude Code's own API-error line (a limit, signed out…), with the kind of block it
     * put on the session ([Blocked.kind]); drawn as a slim row, never as Claude's reply.
     */
    @Immutable data class Notice(override val id: String, override val ts: Long, val text: String, val tone: String, val command: String? = null, val unsent: Unsent? = null, val blocker: String? = null) : WEvent
    /** A kind this build doesn't know: shown generically as a notice. */
    @Immutable data class Unknown(override val id: String, override val ts: Long, val kind: String, val text: String?) : WEvent
}

@Immutable
data class WApproval(
    val id: String,
    val sessionId: String,
    val createdAt: Long,
    /** When it leaves the phone for nobody answering; null when it never does (it waits for the phone). */
    val expiresAt: Long?,
    val kind: String,
    val toolName: String,
    val summary: String,
    val input: Map<String, String>,
    val reason: String?,
    val canAlways: Boolean,
    /** Can be allowed for the rest of the session at once (web and read tools). */
    val canSession: Boolean = false,
    val questions: List<Question>,
    val plan: String?,
    /** An agent the main agent started asks (not the main agent), and what it's called. */
    val agentId: String? = null,
    val agentLabel: String? = null,
    /** A MultiEdit's edits, old text to new ([input] has only their flattened text). */
    val edits: List<Pair<String, String>> = emptyList(),
    /**
     * At [expiresAt] the Mac says no for nobody answering (Codex, an automation's run). Never for the
     * owner's Claude Code sessions (docs/DECISIONS.md, "Never auto-deny"): their asks go back to the
     * terminal's dialog or wait for the phone, so they have no countdown.
     */
    val autoDeny: Boolean = false,
)

@Immutable
data class Window(val pct: Double, val resetsAt: Long)

/**
 * [source]: "official" (the status line's numbers), "none", or "stale": the account is signed out
 * or stopped, so these are the last numbers known and no longer move. [models]: the weeks some
 * models have of their own (data/ModelWeeks.kt), none from an older Mac.
 */
@Immutable
data class WUsage(val fiveHour: Window?, val sevenDay: Window?, val source: String, val observedAt: Long?, val models: List<WModelWeek> = emptyList(), val rate: UsageRate? = null)

@Immutable
data class Project(val path: String, val name: String, val lastActivityAt: Long?, val sessionCount: Int)

@Immutable
data class FileEntry(
    val name: String, val path: String, val isDir: Boolean, val size: Long, val mtime: Long, val mime: String?,
    /**
     * Listed but not openable: [LOCKED_SECRET] (keys and passwords stay on the Mac), [LOCKED_OUTSIDE] (a link
     * out of the allowed folders) or [LOCKED_FOLDER] (a key place such as ~/.ssh or ~/Library/Keychains).
     */
    val locked: String? = null,
) {
    companion object {
        const val LOCKED_SECRET = "secret"
        const val LOCKED_OUTSIDE = "outside"
        const val LOCKED_FOLDER = "folder"
    }
}

/** A folder's entries; [truncated] says there are [total] and only the first (2,000) are here. */
@Immutable
data class Listing(val path: String, val entries: List<FileEntry>, val truncated: Boolean = false, val total: Int = entries.size)

class FileBytes(val bytes: ByteArray, val mime: String?)

@Immutable
data class ProviderModels(val default: String? = null, val opus: String? = null, val sonnet: String? = null, val haiku: String? = null) {
    fun toJson(): JSONObject = JSONObject().apply {
        default?.takeIf { it.isNotBlank() }?.let { put("default", it.trim()) }
        opus?.takeIf { it.isNotBlank() }?.let { put("opus", it.trim()) }
        sonnet?.takeIf { it.isNotBlank() }?.let { put("sonnet", it.trim()) }
        haiku?.takeIf { it.isNotBlank() }?.let { put("haiku", it.trim()) }
    }
}

@Immutable
data class Provider(
    val id: String,
    val name: String,
    val kind: String,
    val baseUrl: String?,
    val hasKey: Boolean,
    val models: ProviderModels,
    val builtIn: Boolean,
    /** What went wrong with it lately (out of credits, key rejected), or null when nothing did. */
    val status: ProviderStatus? = null,
    /** What's left to spend on it, where its API says (OpenRouter); null when unknown. */
    val balance: Balance? = null,
    /** Running on it costs money (automations ask first); false only when the owner said so, and for the built-in one. */
    val paid: Boolean = !builtIn,
)

/**
 * A provider's trouble: [state] is "no_credit", "bad_key", "limited" or "down" (unknown ones show
 * generically); [text] is its own words. [resetsAt]: when a limit lifts, only when the provider
 * said. [budget]: out of credit under a cap someone set (the key's limit), not the account's balance.
 */
@Immutable
data class ProviderStatus(val state: String, val text: String, val since: Long?, val resetsAt: Long? = null, val budget: Boolean = false) {
    companion object {
        const val NO_CREDIT = "no_credit"
        const val BAD_KEY = "bad_key"
        const val LIMITED = "limited"
        const val DOWN = "down"
    }
}

/** What the app sends to create or edit a provider. A null [apiKey] keeps the saved one; a null [paid] keeps what was said (new: it costs money). */
data class ProviderInput(val name: String, val kind: String, val baseUrl: String?, val apiKey: String?, val models: ProviderModels, val paid: Boolean? = null) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name.trim())
        put("kind", kind)
        baseUrl?.takeIf { it.isNotBlank() }?.let { put("baseUrl", it.trim()) }
        apiKey?.takeIf { it.isNotBlank() }?.let { put("apiKey", it.trim()) }
        put("models", models.toJson())
        paid?.let { put("paid", it) }
    }
}

@Immutable
data class Snapshot(
    val mac: MacInfo,
    val usage: WUsage?,
    val sessions: List<WSession>,
    val approvals: List<WApproval>,
    val projects: List<Project>,
    val providers: List<Provider>,
    /** Claude Code's account on the Mac; null from a daemon that doesn't report it. */
    val account: Account? = null,
    /** Automations (data/Automations.kt); null from a daemon without them. */
    val automations: AutomationsState? = null,
    /** The Mac's local-network listener (data/LanRoute.kt); null when off, or from a daemon without one. */
    val lan: LanInfo? = null,
    /** Codex as a second agent (data/Codex.kt); null where it isn't installed, and from an older daemon. */
    val codex: CodexState? = null,
    /** Claude Code on the computer (data/AgentLead.kt); null from an older daemon, where it always was. */
    val claude: ClaudeState? = null,
)

// ─── Parsing ────────────────────────────────────────────────────────────────

internal fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
internal fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() }?.toLong() else null
internal fun JSONObject.int(key: String): Int? = long(key)?.toInt()
internal fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null
internal fun JSONObject.bool(key: String): Boolean = optBoolean(key, false)
internal fun JSONObject.obj(key: String): JSONObject? = if (has(key) && !isNull(key)) optJSONObject(key) else null
internal fun JSONObject.arr(key: String): JSONArray? = if (has(key) && !isNull(key)) optJSONArray(key) else null

internal inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T?): List<T> {
    if (this == null) return emptyList()
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        // One malformed entry shouldn't hide the rest.
        runCatching { f(o) }.getOrNull()?.let(out::add)
    }
    return out
}

internal fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> if (isNull(i)) null else optString(i) }
}

/** Tool input, trimmed by the daemon: keep the scalar fields as strings. */
private fun flatInput(o: JSONObject?): Map<String, String> {
    if (o == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (k in o.keys()) {
        if (o.isNull(k)) continue
        when (val v = o.opt(k)) {
            is String -> out[k] = v
            is Number, is Boolean -> out[k] = v.toString()
            is JSONArray -> if (v.length() <= 8) out[k] = (0 until v.length()).joinToString(", ") { i -> v.opt(i)?.toString().orEmpty() }
        }
    }
    return out
}

object Wire {
    fun mac(o: JSONObject) = MacInfo(
        id = o.str("id").orEmpty(),
        name = o.str("name") ?: "Mac",
        daemonVersion = o.str("daemonVersion"),
        claudeVersion = o.str("claudeVersion"),
        projectsRoot = o.str("projectsRoot"),
        appVersionCode = o.obj("app")?.int("versionCode"),
        appVersionName = o.obj("app")?.str("versionName"),
        battery = battery(o.obj("battery")),
        awake = MacAwake.parse(o.obj("awake")),
        platform = if (o.str("platform") == Mac.LINUX) Mac.LINUX else Mac.DARWIN,
        browseOutsideProjects = o.obj("files")?.bool("browseOutsideProjects"),
        unavailable = o.obj("unavailable")?.let { u -> u.keys().asSequence().mapNotNull { k -> u.str(k)?.let { k to it } }.toMap() }.orEmpty(),
    )

    /**
     * `{percent, charging, onAC, minutesLeft}`, or null: no battery (`null` or absent, as a Mac mini
     * reports it), or no percentage to show.
     */
    fun battery(o: JSONObject?): MacBattery? {
        val pct = o?.dbl("percent") ?: return null
        return MacBattery(
            percent = pct.roundToInt().coerceIn(0, 100),
            charging = o.bool("charging"),
            onAC = o.bool("onAC"),
            minutesLeft = o.int("minutesLeft")?.takeIf { it >= 0 },
        )
    }

    fun session(o: JSONObject): WSession {
        val cwd = o.str("cwd").orEmpty()
        return WSession(
            id = o.getString("id"),
            title = o.str("title").orEmpty(),
            cwd = cwd,
            projectName = o.str("projectName")?.takeIf { it.isNotBlank() } ?: cwd.trimEnd('/').substringAfterLast('/').ifBlank { "~" },
            origin = o.str("origin") ?: "hypernova",
            state = o.str("state") ?: "idle",
            providerId = o.str("providerId"),
            model = o.str("model"),
            permissionMode = o.str("permissionMode") ?: "default",
            bypassAvailable = o.optBoolean("bypassAvailable", true),
            createdAt = o.long("createdAt") ?: 0L,
            lastActivityAt = o.long("lastActivityAt") ?: o.long("createdAt") ?: 0L,
            lastText = o.str("lastText"),
            activity = o.str("activity"),
            pendingApprovalIds = o.arr("pendingApprovalIds").strings(),
            stoppedForAway = o.bool("stoppedForAway"),
            contextPct = o.dbl("contextPct"),
            costUsd = o.dbl("costUsd"),
            queued = o.int("queued") ?: 0,
            term = o.obj("term")?.let { t -> t.str("id")?.let { TermLink(it, t.int("mac") ?: 0, t.int("phone") ?: 0) } },
            menu = o.obj("menu")?.let(::menu),
            queue = o.arr("queue").mapObjects(::queued),
            agentsRunning = o.int("agentsRunning") ?: 0,
            blocked = runCatching { blocked(o.obj("blocked")) }.getOrNull(),
            endedBy = o.str("endedBy")?.takeIf { it.isNotBlank() },
            automation = AutomationWire.sessionAutomation(o.obj("automation")),
            browsable = o.optBoolean("browsable", true),
            outsideProjects = o.bool("outsideProjects"),
            agent = o.str("agent")?.takeIf { it.isNotBlank() } ?: AgentKind.CLAUDE,
            switchedFrom = o.str("switchedFrom")?.takeIf { it.isNotBlank() },
            switchedTo = o.str("switchedTo")?.takeIf { it.isNotBlank() },
            agentJob = o.str("agentJob")?.takeIf { it.isNotBlank() },
            agentRunning = if (o.has("agentRunning")) o.bool("agentRunning") else null,
            agentOutdated = o.obj("agentOutdated")?.let { v ->
                val runs = v.str("runs")?.takeIf { it.isNotBlank() }
                val installed = v.str("installed")?.takeIf { it.isNotBlank() }
                if (runs != null && installed != null) OutdatedClaude(runs, installed) else null
            },
            question = o.str("question")?.takeIf { it.isNotBlank() },
            blockedCalls = o.int("blockedCalls") ?: 0,
            held = o.arr("held").mapObjects(::queued),
            effort = o.str("effort")?.takeIf { it.isNotBlank() },
            turnStartedAt = o.long("turnStartedAt")?.takeIf { it > 0 },
            todo = o.obj("todo")?.let { t ->
                val total = t.int("total") ?: 0
                if (total > 0) TodoNow(t.str("active")?.takeIf { it.isNotBlank() }, (t.int("done") ?: 0).coerceIn(0, total), total) else null
            },
            workingSince = o.long("workingSince")?.takeIf { it > 0 },
        )
    }

    /** `session.blocked`; an older daemon has none. */
    fun blocked(o: JSONObject?): Blocked? {
        if (o == null) return null
        val kind = o.str("kind")?.takeIf { it.isNotBlank() } ?: return null
        return Blocked(kind, o.str("text").orEmpty(), o.long("resetsAt")?.takeIf { it > 0 }, o.long("autoResumeAt")?.takeIf { it > 0 }, ProviderWire.resume(o.obj("resume")))
    }

    /**
     * `Snapshot.account` and the `account` message. Anything missing reads as fine: a malformed
     * account never stops the app from showing the rest.
     */
    fun account(o: JSONObject): Account {
        val limit = o.obj("limit")?.let { l ->
            Account.Limit(
                window = l.str("window") ?: "five_hour",
                label = l.str("label")?.takeIf { it.isNotBlank() },
                resetsAt = l.long("resetsAt")?.takeIf { it > 0 },
                autoResumeAt = l.long("autoResumeAt")?.takeIf { it > 0 },
                usingCredits = l.bool("usingCredits"),
            )
        }
        val login = o.obj("login")?.let { l ->
            Account.Login(
                state = l.str("state") ?: "starting",
                url = l.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") },
                onMac = l.str("where") == "mac",
                error = l.str("error")?.takeIf { it.isNotBlank() },
            )
        }
        return Account(
            state = o.str("state") ?: "ok",
            providerId = o.str("providerId") ?: "anthropic",
            plan = o.str("plan"),
            limit = limit,
            reason = o.str("reason"),
            text = o.str("text")?.takeIf { it.isNotBlank() },
            since = o.long("since"),
            sessionId = o.str("sessionId")?.takeIf { it.isNotBlank() },
            checkedAt = o.long("checkedAt"),
            login = login,
        )
    }

    fun agent(o: JSONObject): WAgent? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val started = o.long("startedAt") ?: 0L
        return WAgent(
            id = id,
            sessionId = o.str("sessionId").orEmpty(),
            toolUseId = o.str("toolUseId"),
            parentAgentId = o.str("parentAgentId"),
            workflowId = o.str("workflowId"),
            phase = o.str("phase")?.takeIf { it.isNotBlank() },
            label = o.str("label")?.takeIf { it.isNotBlank() } ?: o.str("type") ?: "agent",
            type = o.str("type")?.takeIf { it.isNotBlank() } ?: "agent",
            model = o.str("model"),
            background = o.bool("background"),
            status = o.str("status") ?: "running",
            activity = o.str("activity")?.takeIf { it.isNotBlank() },
            startedAt = started,
            endedAt = o.long("endedAt"),
            lastActivityAt = o.long("lastActivityAt") ?: started,
            tokens = o.long("tokens"),
            toolUses = o.int("toolUses"),
            result = o.str("result")?.takeIf { it.isNotBlank() },
        )
    }

    fun workflow(o: JSONObject): WWorkflow? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return WWorkflow(
            id = id,
            sessionId = o.str("sessionId").orEmpty(),
            toolUseId = o.str("toolUseId"),
            name = o.str("name")?.takeIf { it.isNotBlank() } ?: "workflow",
            summary = o.str("summary")?.takeIf { it.isNotBlank() },
            status = o.str("status") ?: "running",
            phases = o.arr("phases").mapObjects { p -> p.str("title")?.takeIf { it.isNotBlank() }?.let { WPhase(it, p.str("detail")?.takeIf { d -> d.isNotBlank() }) } },
            agentCount = o.int("agentCount") ?: 0,
            done = o.int("done") ?: 0,
            failed = o.int("failed") ?: 0,
            startedAt = o.long("startedAt") ?: 0L,
            endedAt = o.long("endedAt"),
            tokens = o.long("tokens"),
            toolUses = o.int("toolUses"),
        )
    }

    private fun queued(o: JSONObject): WQueued? {
        val id = o.str("id") ?: return null
        return WQueued(id, o.str("text").orEmpty(), o.arr("files").mapObjects(::attachment), o.long("at") ?: 0L, o.str("clientId")?.takeIf { it.isNotBlank() })
    }

    /** `notice.unsent`; an older daemon has none. */
    private fun unsent(o: JSONObject?): Unsent? {
        if (o == null) return null
        val prompts = o.arr("prompts").mapObjects { p -> Unsent.Prompt(p.str("text").orEmpty(), p.str("clientId")?.takeIf { it.isNotBlank() }) }
        return Unsent(o.str("reason") == "returned", prompts)
    }

    private fun menu(o: JSONObject): TermMenu? {
        val id = o.str("id") ?: return null
        val opts = o.arr("options") ?: return null
        val options = (0 until opts.length()).mapNotNull { i ->
            opts.optJSONObject(i)?.let { x -> x.str("label")?.let { TermMenu.Option(it, x.str("detail"), x.bool("current")) } }
        }
        if (options.size < 2) return null
        return TermMenu(
            id = id,
            title = o.str("title"),
            body = o.arr("body").strings(),
            options = options,
            cursor = o.int("cursor") ?: 0,
            more = o.bool("more"),
            cancel = o.bool("cancel"),
            alt = o.obj("alt")?.let { a ->
                val enter = a.str("enter"); val key = a.str("key"); val label = a.str("label")
                if (enter != null && key != null && label != null) TermMenu.Alt(enter, key, label) else null
            },
            // An older daemon sends none: the menu card as before.
            permission = o.obj("permission")?.let { p ->
                p.str("question")?.let { q -> TermMenu.Permission(p.str("tool"), p.str("command")?.takeIf { it.isNotBlank() }, q) }
            },
        )
    }

    private fun diff(o: JSONObject?): WDiff? {
        if (o == null) return null
        return WDiff(
            path = o.str("path").orEmpty(),
            added = o.int("added") ?: 0,
            removed = o.int("removed") ?: 0,
            hunks = o.arr("hunks").mapObjects { h ->
                WHunk(h.int("oldStart") ?: 0, h.int("oldLines") ?: 0, h.int("newStart") ?: 0, h.int("newLines") ?: 0, h.arr("lines").strings())
            },
        )
    }

    /**
     * `user.command`; an older daemon has none, and a malformed one only loses the command look.
     * Its kind is kept as sent: one this build doesn't know draws as a prompt ([CommandRun.type]).
     */
    private fun commandRun(o: JSONObject?): CommandRun? {
        val name = o?.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return CommandRun(name, o.str("args")?.trim().orEmpty(), o.str("kind") ?: "local")
    }

    fun event(o: JSONObject): WEvent {
        val id = o.getString("id")
        val ts = o.long("ts") ?: System.currentTimeMillis()
        return when (val kind = o.str("kind")) {
            "user" -> WEvent.User(
                id, ts, o.str("text").orEmpty(), o.arr("files").mapObjects(::attachment),
                command = runCatching { commandRun(o.obj("command")) }.getOrNull(),
                clientId = o.str("clientId")?.takeIf { it.isNotBlank() },
            )
            "assistant" -> WEvent.Assistant(id, ts, o.str("text").orEmpty(), o.bool("streaming"))
            "thinking" -> WEvent.Thinking(id, ts, o.int("chars") ?: 0)
            "tool" -> WEvent.Tool(
                id, ts,
                toolUseId = o.str("toolUseId"),
                name = o.str("name") ?: "Tool",
                summary = o.str("summary"),
                input = flatInput(o.obj("input")),
                status = o.str("status") ?: "done",
                output = o.str("output"),
                exitCode = o.int("exitCode"),
                durationMs = o.long("durationMs"),
                diff = diff(o.obj("diff")),
                path = o.str("path"),
            )
            "approval" -> WEvent.Approval(id, ts, o.str("approvalId").orEmpty(), o.str("summary"), o.str("resolution"))
            "result" -> WEvent.Result(
                id, ts,
                ok = o.optBoolean("ok", true),
                durationMs = o.long("durationMs"),
                costUsd = o.dbl("costUsd"),
                turns = o.int("turns"),
                error = o.str("error"),
            )
            "notice" -> WEvent.Notice(
                id, ts, o.str("text").orEmpty(), o.str("tone") ?: "info",
                command = (o.opt("command") as? String)?.takeIf { it.isNotBlank() },
                unsent = runCatching { unsent(o.obj("unsent")) }.getOrNull(),
                blocker = o.str("blocker")?.takeIf { it.isNotBlank() },
            )
            else -> WEvent.Unknown(id, ts, kind ?: "?", o.str("text") ?: o.str("summary"))
        }
    }

    private fun question(o: JSONObject) = Question(
        question = o.str("question").orEmpty(),
        header = o.str("header").orEmpty(),
        multiSelect = o.bool("multiSelect"),
        options = o.arr("options").mapObjects { op -> Question.Option(op.str("label").orEmpty(), op.str("description")?.takeIf { it.isNotBlank() }) },
    )

    fun approval(o: JSONObject) = WApproval(
        id = o.getString("id"),
        sessionId = o.str("sessionId").orEmpty(),
        createdAt = o.long("createdAt") ?: System.currentTimeMillis(),
        expiresAt = o.long("expiresAt"),
        kind = o.str("kind") ?: "tool",
        toolName = o.str("toolName") ?: "Tool",
        summary = o.str("summary").orEmpty(),
        input = flatInput(o.obj("input")),
        reason = o.str("reason")?.takeIf { it.isNotBlank() },
        canAlways = o.bool("canAlways"),
        canSession = o.bool("canSession"),
        questions = o.arr("questions").mapObjects(::question),
        plan = o.str("plan"),
        agentId = o.str("agentId")?.takeIf { it.isNotBlank() },
        agentLabel = o.str("agentLabel")?.takeIf { it.isNotBlank() },
        edits = o.obj("input")?.optJSONArray("edits")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { e -> e.str("old_string").orEmpty() to e.str("new_string").orEmpty() } }
        }.orEmpty(),
        autoDeny = o.bool("autoDeny"),
    )

    private fun window(o: JSONObject?): Window? {
        if (o == null) return null
        val pct = o.dbl("pct") ?: return null
        return Window(pct, o.long("resetsAt") ?: 0L)
    }

    fun usage(o: JSONObject?): WUsage? {
        if (o == null) return null
        return WUsage(window(o.obj("fiveHour")), window(o.obj("sevenDay")), o.str("source") ?: "none", o.long("observedAt"), ModelWeeks.wire(o.arr("models")), rate(o.obj("rate")))
    }

    /** The spend pace while something works; null idle and from an older Mac. */
    private fun rate(o: JSONObject?): UsageRate? {
        if (o == null) return null
        return UsageRate(o.dbl("fiveHour")?.toFloat(), o.dbl("sevenDay")?.toFloat(), o.long("fiveHourEtaMs"), o.long("sevenDayEtaMs"))
    }

    fun project(o: JSONObject) = Project(
        path = o.getString("path"),
        name = o.str("name") ?: o.getString("path").trimEnd('/').substringAfterLast('/'),
        lastActivityAt = o.long("lastActivityAt"),
        sessionCount = o.int("sessionCount") ?: 0,
    )

    fun provider(o: JSONObject): Provider {
        val m = o.obj("models")
        return Provider(
            id = o.getString("id"),
            name = o.str("name") ?: o.getString("id"),
            kind = o.str("kind") ?: "custom",
            baseUrl = o.str("baseUrl"),
            hasKey = o.bool("hasKey"),
            models = ProviderModels(m?.str("default"), m?.str("opus"), m?.str("sonnet"), m?.str("haiku")),
            builtIn = o.bool("builtIn"),
            status = ProviderWire.status(o.obj("status")),
            balance = ProviderWire.balance(o.obj("balance")),
            // An older Mac doesn't say: every provider but the built-in one costs money.
            paid = if (o.has("paid")) o.bool("paid") else !o.bool("builtIn"),
        )
    }

    fun commands(o: JSONObject): List<SlashCommand> {
        val a = o.arr("commands") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { c ->
                c.str("name")?.takeIf { it.isNotBlank() }?.let {
                    SlashCommand(it, c.str("description").orEmpty(), c.str("argumentHint").orEmpty(), c.arr("aliases").strings(), c.bool("builtin"), c.bool("terminal"))
                }
            }
        }
    }

    fun attachment(o: JSONObject): Attachment? {
        val path = o.str("path") ?: return null
        return Attachment(path, o.str("name") ?: path.substringAfterLast('/'), o.str("mime"), o.long("size") ?: 0)
    }

    fun entry(o: JSONObject) = FileEntry(
        name = o.str("name") ?: o.getString("path").substringAfterLast('/'),
        path = o.getString("path"),
        isDir = o.str("kind") == "dir",
        size = o.long("size") ?: 0L,
        mtime = o.long("mtime") ?: 0L,
        mime = o.str("mime"),
        locked = o.str("locked"),
    )

    fun snapshot(o: JSONObject) = Snapshot(
        mac = mac(o.obj("mac") ?: JSONObject()),
        usage = usage(o.obj("usage")),
        sessions = o.arr("sessions").mapObjects(::session),
        approvals = o.arr("approvals").mapObjects(::approval),
        projects = o.arr("projects").mapObjects(::project),
        providers = o.arr("providers").mapObjects(::provider),
        account = o.obj("account")?.let { runCatching { account(it) }.getOrNull() },
        automations = runCatching { AutomationWire.fromSnapshot(o) }.getOrNull(),
        lan = LanInfo.parse(o.obj("lan")),
        codex = runCatching { CodexWire.state(o.obj("codex")) }.getOrNull(),
        claude = runCatching { ClaudeState.parse(o.obj("claude")) }.getOrNull(),
    )
}
