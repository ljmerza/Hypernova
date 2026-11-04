package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.Str
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.prudnikweb.hypernova.ui.platform.stringResource

/** A reasoning effort as the pickers say it ("Low", "Extra high"); one this build doesn't know, as Codex calls it. */
@Composable
internal fun effortTitle(effort: String): String = when (effort) {
    "none" -> stringResource(Str.cx_effort_none)
    "minimal" -> stringResource(Str.cx_effort_minimal)
    "low" -> stringResource(Str.cx_effort_low)
    "medium" -> stringResource(Str.cx_effort_medium)
    "high" -> stringResource(Str.cx_effort_high)
    "xhigh" -> stringResource(Str.cx_effort_xhigh)
    else -> effort.replaceFirstChar { it.uppercase() }
}

