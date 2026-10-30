package com.prudnikweb.hypernova.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ClaudeEffort
import com.prudnikweb.hypernova.ui.session.ChatSheet
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

// Claude Code's reasoning effort (data/Effort.kt): the new-session sheet's Effort chip opens this
// picker, and so does the session sheet's Overview. Codex's efforts stay in its model picker
// (CodexPickers.kt).

/** A Claude Code effort as a chip and the picker say it: "Default" for none, else as Codex's are said ("Extra high", "Max"). */
@Composable
internal fun claudeEffortTitle(level: String?): String = if (level == null) stringResource(R.string.effort_default) else effortTitle(level)

/** What an effort does, in one line. */
@Composable
internal fun claudeEffortLine(level: String?): String = stringResource(
    when (level) {
        "low" -> R.string.effort_low_desc
        "medium" -> R.string.effort_medium_desc
        "high" -> R.string.effort_high_desc
        "xhigh" -> R.string.effort_xhigh_desc
        "max" -> R.string.effort_max_desc
        else -> R.string.effort_default_desc
    },
)

/**
 * Claude Code's efforts as rows, lowest first, each with what it does, the chosen one marked; a tap
 * is the choice. [withDefault]: "Default" first (a new session: nothing passed); a running session
 * has none to go back to, so it lists the levels alone.
 */
@Composable
internal fun EffortSheet(effort: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit, withDefault: Boolean = true) {
    val title = stringResource(R.string.effort)
    ChatSheet(title, onDismiss) {
        HText(title, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(10.dp))
        val levels: List<String?> = if (withDefault) listOf(null) + ClaudeEffort.LEVELS else ClaudeEffort.LEVELS
        for (level in levels) EffortRow(level, level == effort) { onPick(level) }
    }
}

@Composable
private fun EffortRow(level: String?, selected: Boolean, onClick: () -> Unit) {
    val name = claudeEffortTitle(level)
    val line = claudeEffortLine(level)
    Row(Modifier.pickRow(selected, "$name. $line", onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            HText(name, style = Type.body, color = HN.ink, maxLines = 1)
            HText(line, style = Type.small, color = HN.inkDim, maxLines = 2)
        }
        ChosenMark(selected)
    }
}
