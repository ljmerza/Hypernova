package com.prudnikweb.hypernova.ui

import androidx.compose.runtime.staticCompositionLocalOf

/*
 * What a screen asks of the app round it, on either system. The phone's App.kt and the desktop's provide them. What a window has no screen
 * for yet says so through its Opener, rather than doing nothing (docs/PLATFORMS.md, "The shared drawing").
 */

/** Show a short message over everything (errors, confirmations). */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Ask before doing something irreversible. */
val LocalConfirm = staticCompositionLocalOf<(com.prudnikweb.hypernova.data.Confirm) -> Unit> { {} }

/** What screens can ask the app to do: open things, which may need the Mac first. */
class Opener(
    val session: (String) -> Unit,
    /** Swap the current session screen for another (a terminal session that forked); the same rule as [session]. */
    val switchSession: (String) -> Unit,
    val files: (root: String, title: String) -> Unit,
    val file: (String) -> Unit,
    /** Ask the Mac for a signed preview URL ([path] of an HTML file, or a dev server [port]), then open it. */
    val preview: (path: String?, port: Int?, title: String) -> Unit,
    val web: (url: String, title: String) -> Unit,
    /** A session's live terminal (sessions running in a shared terminal). */
    val terminal: (sessionId: String) -> Unit = {},
    /** One of a session's agents, read-only. */
    val agent: (sessionId: String, agentId: String) -> Unit = { _, _ -> },
    /** Settings › Providers, over whatever is open (the new-session sheet closes first). */
    val providers: () -> Unit = {},
    /** One provider's settings (its key, its models): the way to fix its trouble. */
    val provider: (id: String, kind: String) -> Unit = { _, _ -> },
    /** The editor on the "reset" template, in this folder: from a limited session. */
    val runAtReset: (project: String) -> Unit = {},
    /** An automation's editor (an automated session's status line opens the one that started it). */
    val automation: (String) -> Unit = {},
    /** Settings › Logs › Connection log: what the app tried to reach the Mac (the offline card links to it). */
    val connectionLog: () -> Unit = {},
    /** The Settings tab, scrolled to one part ([com.prudnikweb.hypernova.ui.settings.SettingsJump]): "Turn on in Settings". */
    val settings: (key: String) -> Unit = {},
    /** Signing Claude Code in, over whatever is open (ui/home/SignInSheet.kt): a /login, or Sign in here. */
    val signIn: () -> Unit = {},
    /** An agent's MCP servers in Settings, over whatever is open: where they're added and removed. */
    val mcpServers: (agent: String) -> Unit = {},
    /** Whether [file] opens anything on this system: where it doesn't, a read row or a diff has nothing to open. */
    val opensFiles: Boolean = true,
)

val LocalOpener = staticCompositionLocalOf<Opener> { error("No Opener") }

/** A scope that outlives screens: a prompt being sent finishes even if you leave the session. */
val LocalAppScope = staticCompositionLocalOf<kotlinx.coroutines.CoroutineScope> { error("No app scope") }
