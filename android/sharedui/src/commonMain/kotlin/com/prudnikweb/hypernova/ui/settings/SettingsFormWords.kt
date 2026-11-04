package com.prudnikweb.hypernova.ui.settings

import com.prudnikweb.hypernova.data.FormProblem
import com.prudnikweb.hypernova.data.Str

/*
 * What the app already calls a setting and its values, so a row of the form says what the list screens say. Keys it has no words
 * for use what the server sends: the schema's own. New words aren't needed for a new setting; that's the point of these rows.
 */

/** The label and the line for a key the app words itself. */
fun formWords(key: String): Pair<Str, Str>? = when (key) {
    "effortLevel" -> Str.as_k_effort to Str.as_k_effort_body
    "permissions.defaultMode" -> Str.as_k_mode to Str.as_k_mode_body
    "showThinkingSummaries" -> Str.as_k_thinking to Str.as_k_thinking_body
    "autoDreamEnabled" -> Str.as_k_dream to Str.as_k_dream_body
    "includeCoAuthoredBy" -> Str.as_k_coauthor to Str.as_k_coauthor_body
    "autoUpdatesChannel" -> Str.as_k_channel to Str.as_k_channel_body
    "inputNeededNotifEnabled" -> Str.as_k_input to Str.as_k_input_body
    "agentPushNotifEnabled" -> Str.as_k_agent_push to Str.as_k_agent_push_body
    "model" -> Str.as_k_model to Str.as_k_model_body
    "model_reasoning_effort" -> Str.as_k_reasoning to Str.as_k_reasoning_body
    "approval_policy" -> Str.as_k_approval to Str.as_k_approval_body
    "sandbox_mode" -> Str.as_k_sandbox to Str.as_k_sandbox_body
    "web_search" -> Str.as_k_web to Str.as_k_web_body
    "personality" -> Str.as_k_personality to Str.as_k_personality_body
    else -> null
}

/**
 * A value in words; null: as it is in the file. Only the keys the app already words, and Claude Code's `model`, which is free text
 * in the form though Codex's isn't, has none.
 */
fun formValueWord(key: String, value: String): Str? = when (key) {
    "effortLevel", "model_reasoning_effort" -> when (value) {
        "none" -> Str.as_v_none
        "minimal" -> Str.as_v_minimal
        "low" -> Str.as_v_low
        "medium" -> Str.as_v_medium
        "high" -> Str.as_v_high
        "xhigh" -> Str.as_v_xhigh
        "max" -> Str.as_v_max
        "ultra" -> Str.as_v_ultra
        else -> null
    }
    "permissions.defaultMode" -> when (value) {
        "default", "manual" -> Str.mode_title_default
        "acceptEdits" -> Str.mode_title_accept
        "plan" -> Str.mode_title_plan
        "auto" -> Str.mode_title_auto
        "dontAsk" -> Str.as_v_dont_ask
        "bypassPermissions" -> Str.mode_title_bypass
        else -> null
    }
    "autoUpdatesChannel" -> when (value) {
        "latest" -> Str.as_v_latest
        "stable" -> Str.as_v_stable
        "rc" -> Str.as_v_rc
        else -> null
    }
    "approval_policy" -> when (value) {
        "untrusted" -> Str.as_v_untrusted
        "on-failure" -> Str.as_v_on_failure
        "on-request" -> Str.as_v_on_request
        "never" -> Str.as_v_never
        else -> null
    }
    "sandbox_mode" -> when (value) {
        "read-only" -> Str.as_v_read_only
        "workspace-write" -> Str.as_v_workspace_write
        "danger-full-access" -> Str.as_v_full_access
        else -> null
    }
    "web_search" -> when (value) {
        "disabled" -> Str.as_v_disabled
        "cached" -> Str.as_v_cached
        "indexed" -> Str.as_v_indexed
        "live" -> Str.as_v_live
        else -> null
    }
    "personality" -> when (value) {
        "none" -> Str.as_v_none
        "friendly" -> Str.as_v_friendly
        "pragmatic" -> Str.as_v_pragmatic
        else -> null
    }
    else -> null
}

/** What a value does, in a line, where the app has one. */
fun formValueLine(key: String, value: String): Str? = when (key) {
    "permissions.defaultMode" -> when (value) {
        "plan" -> Str.as_l_plan
        "default", "manual" -> Str.as_l_default
        "dontAsk" -> Str.as_l_dont_ask
        "acceptEdits" -> Str.as_l_accept
        "auto" -> Str.as_l_auto
        else -> null
    }
    "autoUpdatesChannel" -> when (value) {
        "latest" -> Str.as_l_latest
        "stable" -> Str.as_l_stable
        else -> null
    }
    "approval_policy" -> if (value == "on-request") Str.as_l_on_request else null
    "sandbox_mode" -> when (value) {
        "read-only" -> Str.as_l_read_only
        "workspace-write" -> Str.as_l_workspace_write
        else -> null
    }
    else -> null
}

/** When a change reaches the agent, as the server's table says; null: not known, and the row says so. */
fun formAppliesWords(applies: String?): Str? = when (applies) {
    "new_sessions" -> Str.as_when_new_sessions
    "maybe_new_session" -> Str.as_when_maybe_new_session
    "next_update" -> Str.as_when_next_update
    "new_threads" -> Str.as_when_new_threads
    "new_threads_unchosen" -> Str.as_when_new_threads_unchosen
    else -> null
}

fun formAskWords(asks: String?): Str? = when (asks) {
    "runs" -> Str.sf_asks_runs
    "loosens" -> Str.sf_asks_loosens
    "reach" -> Str.sf_asks_reach
    "unknown" -> Str.sf_asks_unknown
    else -> null
}

fun formReadOnlyWords(why: String?): Str? = when (why) {
    "managed" -> Str.sf_ro_managed
    "not_offered" -> Str.sf_ro_not_offered
    "shape" -> Str.sf_ro_shape
    "too_big" -> Str.sf_ro_too_big
    else -> null
}

/** What a row with its own screen goes to, by name. */
fun formScreenWords(screen: String?): Str? = when (screen) {
    "rules" -> Str.sf_own_screen_rules
    "mcp" -> Str.as_mcp
    "plugins" -> Str.as_plugins
    "env" -> Str.env_title
    "effort" -> Str.as_effort
    else -> null
}

/** A problem's words: the resource, and what goes in it. */
fun formProblemWords(p: FormProblem): Pair<Str, Array<Any>> = when (p) {
    FormProblem.Needed -> Str.sf_p_needed to emptyArray()
    is FormProblem.TooShort -> Str.sf_p_short to arrayOf(p.least)
    is FormProblem.TooLong -> Str.sf_p_long to arrayOf(p.most)
    FormProblem.WrongForm -> Str.sf_p_form to emptyArray()
    FormProblem.NotANumber -> Str.sf_p_nan to emptyArray()
    FormProblem.NotWhole -> Str.sf_p_whole to emptyArray()
    is FormProblem.Below -> Str.sf_p_below to arrayOf(p.least)
    is FormProblem.Above -> Str.sf_p_above to arrayOf(p.most)
    is FormProblem.NotOver -> Str.sf_p_not_over to arrayOf(p.floor)
    is FormProblem.TooMany -> Str.sf_p_many to arrayOf(p.most)
    is FormProblem.TooFew -> Str.sf_p_few to arrayOf(p.least)
    FormProblem.Twice -> Str.sf_p_twice to emptyArray()
    is FormProblem.BadJson -> Str.raw_bad_at to arrayOf(p.line, p.column)
}
