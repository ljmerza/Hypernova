package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/*
 * Codex as a second agent (docs/PROTOCOL.md, "Codex (a second agent)" and "Switching agents"):
 * OpenAI's Codex next to Claude Code on the Mac. A Mac without it sends `codex: null` (an older one
 * nothing at all) and the app looks exactly as before; while the owner doesn't use it
 * ([CodexState.enabled] false) only the new-session sheet offers it, as its first session turns it on.
 */

/** The agents a session runs on ([Session.agent]), and Codex's sandboxes (a Codex session's permission mode). */
object AgentKind {
    const val CLAUDE = "claude"
    const val CODEX = "codex"
    /** A Codex session's providerId, and an automation's start step that Codex runs. */
    const val CODEX_PROVIDER = "codex"
    /** Codex reads, and asks before it changes anything. The default. */
    const val READ_ONLY = "read-only"
    /** Codex edits files and runs commands in its folder, and asks before it goes further. */
    const val CAN_EDIT = "workspace-write"
    val CODEX_MODES = listOf(READ_ONLY, CAN_EDIT)

    fun codexMode(mode: String?): Boolean = mode == READ_ONLY || mode == CAN_EDIT
}

/**
 * Codex on the Mac (the snapshot's `codex`, and `{t:"codex"}`): [available] it's installed and new
 * enough; [enabled] the owner uses it; [connected] the Mac reaches it now; [problem] what's wrong,
 * in words. [account] and [usage] only while it's on.
 */
@Immutable
data class CodexState(
    val available: Boolean,
    val enabled: Boolean,
    val connected: Boolean,
    val version: String? = null,
    val problem: String? = null,
    val account: CodexAccount? = null,
    val usage: CodexUsage? = null,
    /** The version Hypernova's routes were checked against ("0.163.0"): an older one can't be used. */
    val min: String? = null,
    /** Where the codex binary is on the Mac. */
    val path: String? = null,
) {
    /** It can take a switch from a Claude session: installed and in use. */
    val canSwitchTo: Boolean get() = available && enabled
}

/** How Codex is signed in: [type] "chatgpt" or "apiKey" (null when it isn't), its plan. */
@Immutable
data class CodexAccount(val signedIn: Boolean, val type: String?, val planType: String?) {
    /** On an API key every run costs money (automations ask first). */
    val paid: Boolean get() = type == "apiKey"
}

/** Codex's own limits: its 5-hour and weekly windows (a plan may have only the week); [limited]: one is used up. */
@Immutable
data class CodexUsage(val fiveHour: Window?, val sevenDay: Window?, val limited: Boolean, val observedAt: Long?)

/** One of Codex's models for the pickers (`GET /v1/codex/models`), with the reasoning efforts it offers. */
@Immutable
data class CodexModel(
    val value: String,
    val name: String,
    val description: String?,
    val efforts: List<String>,
    val defaultEffort: String?,
    val isDefault: Boolean,
)

/** What a switch to the other agent would carry over: the text to read and edit, its messages, its size and the most it may be. */
@Immutable
data class SwitchPreview(val text: String, val messages: Int, val bytes: Int, val maxBytes: Int)

/**
 * A switch the Mac turned down ([code]): "busy" (stop the session first), "switched" (it went on in
 * [to] already), "context_too_large" (more than [maxBytes]), "terminal_session", "same_agent", "no_codex".
 */
class SwitchRefused(message: String, val code: String?, val to: String? = null, val maxBytes: Int? = null) : HubException(message)

/** A prompt to a conversation that went on with the other agent: the chat goes on in [to]. */
class SessionSwitched(message: String, val to: String) : HubException(message)

/** The wire shapes above, parsed as forgivingly as the rest (data/Protocol.kt). */
object CodexWire {
    /** `Snapshot.codex` and `{t:"codex"}`; null when Codex isn't installed, or from an older Mac. */
    fun state(o: JSONObject?): CodexState? {
        if (o == null) return null
        return CodexState(
            available = o.bool("available"),
            enabled = o.bool("enabled"),
            connected = o.bool("connected"),
            version = o.str("version"),
            problem = o.str("problem")?.takeIf { it.isNotBlank() },
            account = o.obj("account")?.let { a -> CodexAccount(a.bool("signedIn"), a.str("type")?.takeIf { it.isNotBlank() }, a.str("planType")?.takeIf { it.isNotBlank() }) },
            usage = o.obj("usage")?.let { u -> CodexUsage(window(u.obj("fiveHour")), window(u.obj("sevenDay")), u.bool("limited"), u.long("observedAt")) },
            min = o.str("min")?.takeIf { it.isNotBlank() },
            path = o.str("path")?.takeIf { it.isNotBlank() },
        )
    }

    private fun window(o: JSONObject?): Window? {
        val pct = o?.dbl("pct") ?: return null
        return Window(pct, o.long("resetsAt") ?: 0L)
    }

    /** `GET /v1/codex/models`: one without a value is left out. */
    fun models(o: JSONObject): List<CodexModel> = o.arr("models").mapObjects { m ->
        val value = m.str("value")?.takeIf { it.isNotBlank() } ?: return@mapObjects null
        CodexModel(
            value = value,
            name = m.str("name")?.takeIf { it.isNotBlank() } ?: value,
            description = m.str("description")?.takeIf { it.isNotBlank() },
            efforts = m.arr("efforts").strings().filter { it.isNotBlank() },
            defaultEffort = m.str("defaultEffort")?.takeIf { it.isNotBlank() },
            isDefault = m.bool("isDefault"),
        )
    }

    /** `POST /v1/sessions/{id}/switch/preview`. */
    fun preview(o: JSONObject) = SwitchPreview(
        text = o.str("text").orEmpty(),
        messages = o.int("messages") ?: 0,
        bytes = o.int("bytes") ?: 0,
        maxBytes = o.int("maxBytes")?.takeIf { it > 0 } ?: SWITCH_MAX_BYTES,
    )

    /** The most a switch carries (96 KB, UTF-8), when the Mac doesn't say. */
    const val SWITCH_MAX_BYTES = 96 * 1024
}

/** [models]' entry for [value] (null: Codex's default). */
fun List<CodexModel>.pick(value: String?): CodexModel? = if (value == null) firstOrNull { it.isDefault } ?: firstOrNull() else firstOrNull { it.value == value }
