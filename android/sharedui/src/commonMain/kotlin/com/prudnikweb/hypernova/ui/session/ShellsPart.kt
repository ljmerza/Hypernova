package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.platform.wordsNow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.fx.formatDuration
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * The session's background commands (owner, 2026-11-03: "I can only see what agents are running but
 * not background commands"), under the agents: what each runs, masked as a tool row is, and how it
 * stands. Read-only. Where the Mac can't tell any more, the row says so rather than "running".
 */
@Composable
internal fun ShellsPart(shells: List<SheetShell>) {
    // How long each has run, a second at a time while the sheet is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    for (s in shells) ShellRow(s, now)
}

@Composable
private fun ShellRow(s: SheetShell, now: Long) {
    val res = wordsNow()
    val text = s.text ?: stringResource(Str.session_sheet_shell_no_command)
    val going = s.say == ShellSay.Running || s.say == ShellSay.MonitorRunning
    val time = if (s.say == ShellSay.Unknown) null else formatDuration(res, ((s.endedAt ?: now) - s.startedAt).coerceAtLeast(0L))
    val words = when (s.say) {
        ShellSay.Running -> stringResource(Str.session_sheet_shell_running)
        ShellSay.MonitorRunning -> stringResource(Str.session_sheet_shell_monitor_running)
        ShellSay.Finished -> stringResource(Str.session_sheet_shell_finished)
        ShellSay.Failed -> stringResource(Str.session_sheet_shell_failed)
        ShellSay.FailedCode -> stringResource(Str.session_sheet_shell_failed_code, s.exitCode ?: 0)
        ShellSay.Stopped -> stringResource(Str.session_sheet_shell_stopped)
        ShellSay.Unknown -> stringResource(Str.session_sheet_shell_unknown, formatDuration(res, (now - s.startedAt).coerceAtLeast(0L)))
    }
    val said = stringResource(Str.session_sheet_shell_said, text, words, time.orEmpty()).trimEnd(' ', ',')
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clearAndSetSemantics { contentDescription = said }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A mint dot as an agent's while it runs; none claimed otherwise.
        Box(Modifier.size(7.dp).background(if (going) HN.accent else HN.inkDim.copy(alpha = 0.4f), RoundedCornerShape(50)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            HText(text, style = Type.monoSmall, color = if (going) HN.ink else HN.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            HText(words, style = Type.small, color = HN.inkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (time != null) {
            Spacer(Modifier.width(10.dp))
            HText(time, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        }
    }
}
