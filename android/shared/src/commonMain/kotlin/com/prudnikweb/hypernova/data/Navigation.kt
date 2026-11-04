package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue


/** Where you are. The stack lives in the ViewModel, so it survives rotation and recreation. */
sealed interface Route {
    val key: String

    data object Home : Route { override val key = "home" }
    data class Session(val id: String) : Route { override val key = "session:$id" }
    /** Browse files under [root], starting at [start]. */
    data class Files(val root: String, val title: String, val start: String = root) : Route { override val key = "files:$root" }
    data class FileView(val path: String) : Route { override val key = "file:$path" }
    /** A web page; [preview]: one the owner opened as a preview ([title] is what he tapped), [port] its dev server's. */
    data class Web(val url: String, val title: String, val preview: Boolean = false, val port: Int? = null) : Route { override val key = "web:$url" }
    /** A session's live terminal. */
    data class Terminal(val sessionId: String) : Route { override val key = "terminal:$sessionId" }
    /** One of a session's agents: its own transcript, read-only. */
    data class Agent(val sessionId: String, val agentId: String) : Route { override val key = "agent:$sessionId:$agentId" }
    /** How much the agents worked: on the server on screen, or on every one with All servers. From Settings. */
    data object Statistics : Route { override val key = "statistics" }
    data object Providers : Route { override val key = "providers" }
    /** An agent's user-level settings on this server, to view: [agent] is [AgentSettingsReply.CLAUDE] or [AgentSettingsReply.CODEX]. */
    data class AgentSettings(val agent: String) : Route { override val key = "agent-settings:$agent" }
    /** An agent's MCP servers on this server: every scope, each to tap (docs/PROTOCOL.md, "An agent's MCP servers"). */
    data class McpServers(val agent: String) : Route { override val key = "mcp:$agent" }
    /** One MCP server: where it's defined, how it's set up, its status and tools. [folder]: the folder it's looked at from. */
    data class McpServerPage(val agent: String, val name: String, val scope: String, val folder: String?) : Route { override val key = "mcp:$agent:$scope:$folder:$name" }
    /** Add an MCP server, or, with [name], change that one. */
    data class McpEdit(val agent: String, val name: String?, val scope: String?, val folder: String?) : Route { override val key = "mcp-edit:$agent:$scope:$folder:$name" }
    /** An agent's plugins and marketplaces on this server. */
    data class Plugins(val agent: String) : Route { override val key = "plugins:$agent" }
    /** One plugin: what it brings, its options, on, off, update, uninstall. */
    data class PluginPage(val agent: String, val id: String, val scope: String, val folder: String?, val enabled: Boolean) : Route { override val key = "plugin:$agent:$scope:$folder:$id" }
    /** The plugins that can be installed, to search. */
    data class PluginBrowse(val agent: String, val folder: String?) : Route { override val key = "plugin-browse:$agent:$folder" }
    /** An agent's skills on this server. */
    data class Skills(val agent: String) : Route { override val key = "skills:$agent" }
    /** An agent's environment variables on this server, as a list to tick and add to. */
    data class AgentEnv(val agent: String) : Route { override val key = "agent-env:$agent" }
    /** An agent's permission rules on this server: Allow, Ask and Deny, to search and change. */
    data class AgentRules(val agent: String) : Route { override val key = "agent-rules:$agent" }
    /** One of Claude Code's settings files as raw JSON, folding as it goes. */
    data class AgentRaw(val agent: String) : Route { override val key = "agent-raw:$agent" }
    /** Settings › Logs › Connection log (data/ConnLog.kt). */
    data object ConnectionLog : Route { override val key = "connection-log" }
    /** Settings › a server: everything of that one server, with one server on screen or with "All servers". */
    data class ServerSettings(val server: String) : Route { override val key = "settings-server:$server" }
    /** Settings' sub pages for what this phone keeps. */
    data object NotificationSettings : Route { override val key = "settings-notifications" }
    data object TopBarSettings : Route { override val key = "settings-top-bar" }
    data object CardSwipeSettings : Route { override val key = "settings-card-swipes" }
    data object TypingVoiceSettings : Route { override val key = "settings-typing-voice" }
    data object LogSettings : Route { override val key = "settings-logs" }
    data class ProviderEdit(val id: String?, val kind: String) : Route { override val key = "provider:${id ?: "new-$kind"}" }
    /**
     * An automation's editor: [id] null for a new one, filled from [template] if given (in [project]
     * when the template starts a session there, e.g. from a limited session); [runs]: opened at its runs.
     */
    data class AutomationEdit(val id: String?, val template: String? = null, val runs: Boolean = false, val project: String? = null) : Route {
        override val key = "automation:${id ?: "new:${template.orEmpty()}:${project.orEmpty()}"}"
    }
}

class Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    val top: Route get() = stack.last()
    val depth: Int get() = stack.size

    fun push(r: Route) {
        if (stack.last() != r) stack.add(r)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Replace the top (e.g. the new-session sheet opening its session). */
    fun replaceTop(r: Route) {
        if (stack.size <= 1) push(r) else stack[stack.lastIndex] = r
    }

    fun reset() {
        stack.clear()
        stack.add(Route.Home)
    }

    /** Open session [id] by the one rule, [sessionStack]: every way into a session comes here. */
    fun openSession(id: String, fresh: Boolean = false) {
        val now = stack.toList()
        val next = sessionStack(now, id, fresh)
        if (next == now) return
        stack.clear()
        stack.addAll(next)
    }
}

/** A session's own screens: its chat, its agents and its live terminal. */
private val Route.ofSession: Boolean get() = this is Route.Session || this is Route.Agent || this is Route.Terminal

/**
 * The stack after opening session [id] on [stack]. Never a session over another session (owner,
 * 2026-10-30: Back always goes to the list of sessions): over a session's screens (its chat, agents,
 * terminal, and files or pages opened from them), or from outside the app ([fresh]: a notification,
 * a widget), it is Home and then the session. Already on it: unchanged. Otherwise (Home, an
 * automation's runs) it goes on top.
 */
fun sessionStack(stack: List<Route>, id: String, fresh: Boolean = false): List<Route> {
    val r = Route.Session(id)
    return when {
        stack.lastOrNull() == r -> stack
        fresh || stack.any { it.ofSession } -> listOf(Route.Home, r)
        else -> stack + r
    }
}

/**
 * A page of Settings, at any depth: what the Settings tab's rows lead to, and the same pages opened
 * from elsewhere (a provider card, the offline card's connection log). Exhaustive, so a new route is
 * decided on.
 */
val Route.isSettingsPage: Boolean get() = when (this) {
    Route.Statistics, Route.Providers, is Route.ProviderEdit, is Route.AgentSettings, is Route.McpServers, is Route.McpServerPage, is Route.McpEdit,
    is Route.Plugins, is Route.PluginPage, is Route.PluginBrowse, is Route.Skills, is Route.AgentEnv, is Route.AgentRaw, is Route.AgentRules,
    Route.ConnectionLog, is Route.ServerSettings, Route.NotificationSettings, Route.TopBarSettings, Route.CardSwipeSettings,
    Route.TypingVoiceSettings, Route.LogSettings -> true
    Route.Home, is Route.Session, is Route.Files, is Route.FileView, is Route.Web, is Route.Terminal, is Route.Agent, is Route.AutomationEdit -> false
}

/**
 * Whether [stack] is inside Settings, so the bottom tabs stay on screen (owner, 2026-11-03): only
 * Settings' pages over Home, and a web page opened from one (the testing checklist, a link on a page).
 * A page opened over a session or an automation's editor isn't: a tab would throw away what it's a
 * detour from, an editor's unsaved words among them.
 */
fun inSettings(stack: List<Route>): Boolean {
    val pages = stack.drop(1)
    return pages.firstOrNull()?.isSettingsPage == true && pages.all { it.isSettingsPage || it is Route.Web }
}

/** The pairing screen's states. */
sealed interface PairFlow {
    @Immutable data class Working(val link: PairLink) : PairFlow
    @Immutable data class Done(val macName: String) : PairFlow
    /**
     * [link] is null when the link itself was unreadable. [code]: the Mac's own reason when it gave
     * one (`pairing_closed`, `too_many_tries` with [retryAfterSeconds] from its local listener).
     */
    @Immutable data class Failed(val link: PairLink?, val problem: Problem?, val message: String?, val code: String? = null, val retryAfterSeconds: Int = 0) : PairFlow
}

@Immutable
data class Toast(val id: Long, val text: String)

/** A question that needs a yes before something irreversible (forget a Mac, delete a provider). */
class Confirm(val title: String, val body: String, val action: String, val danger: Boolean, val onConfirm: () -> Unit)

/**
 * Whether pairing with [link] would replace the computer this phone is paired with ([current]) by
 * another one, rather than pair the same one again (after it unpaired this phone, say): another name.
 */
fun replacesAnother(current: Pairing, link: PairLink): Boolean =
    !current.macName.equals(link.macName, ignoreCase = true)

