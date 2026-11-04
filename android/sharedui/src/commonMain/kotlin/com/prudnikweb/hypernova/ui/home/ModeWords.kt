package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.Str
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.ui.theme.HN

internal val MODES = listOf("default", "acceptEdits", "plan", "auto", "bypassPermissions")

/** A mode's name as its chip and picker say it: "Ask first", "Accept edits"…; Codex's "Read only", "Can edit". */
@Composable
internal fun modeTitle(mode: String): String = stringResource(
    when (mode) {
        "acceptEdits" -> Str.mode_title_accept
        "plan" -> Str.mode_title_plan
        "auto" -> Str.mode_title_auto
        "bypassPermissions" -> Str.mode_title_bypass
        AgentKind.READ_ONLY -> Str.cx_mode_read_only
        AgentKind.CAN_EDIT -> Str.cx_mode_can_edit
        else -> Str.mode_title_default
    },
)

/** What a mode does, in one line. */
@Composable
internal fun modeLine(mode: String): String = stringResource(
    when (mode) {
        "acceptEdits" -> Str.mode_accept_desc
        "plan" -> Str.mode_plan_desc
        "auto" -> Str.mode_auto_desc
        "bypassPermissions" -> Str.mode_bypass_desc
        AgentKind.READ_ONLY -> Str.cx_mode_read_only_desc
        AgentKind.CAN_EDIT -> Str.cx_mode_can_edit_desc
        else -> Str.mode_default_desc
    },
)

/** A mode's colour where it's chosen: Bypass red, Auto amber, the rest quiet. */
internal fun modeTone(mode: String): Color? = when (mode) {
    "bypassPermissions" -> HN.danger
    "auto" -> HN.amber
    else -> null
}

