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
    data object Providers : Route { override val key = "providers" }
    /** An agent's user-level settings on this server, to view: [agent] is [AgentSettingsReply.CLAUDE] or [AgentSettingsReply.CODEX]. */
    data class AgentSettings(val agent: String) : Route { override val key = "agent-settings:$agent" }
    /** Settings › Mac › Connection log (data/ConnLog.kt). */
    data object ConnectionLog : Route { override val key = "connection-log" }
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

