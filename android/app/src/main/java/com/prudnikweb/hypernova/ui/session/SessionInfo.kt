package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Finished
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.automations.rememberWords
import com.prudnikweb.hypernova.ui.changes.ChangesDialog
import com.prudnikweb.hypernova.ui.changes.ChangesRepo
import com.prudnikweb.hypernova.ui.changes.WorkChanges
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.InfoRow
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.fx.formatDuration
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay

/*
 * The session sheet's Session tab, before WHERE IT RUNS (owner, 2026-10-31; ideas SS7 to SS9):
 * TIME, when it started and how it's going, and GIT, its branch line and the files it touched with
 * the way to What changed. The rows are SessionInfoModel.kt's.
 */

/**
 * When it started, how long it has run, this turn while it's in one, and its last activity, as
 * InfoRows, a second at a time while the sheet is open.
 */
@Composable
internal fun TimePart(s: Session) {
    val res = LocalContext.current.resources
    val words = rememberWords()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    for (row in sheetTimeRows(s, now)) when (row) {
        is TimeRow.Started -> InfoRow(stringResource(R.string.session_sheet_started), startedWords(words, row.at, now))
        is TimeRow.Running -> InfoRow(stringResource(if (row.ended) R.string.session_sheet_ran_for else R.string.session_sheet_running_for), formatDuration(res, row.ms))
        is TimeRow.Turn -> InfoRow(stringResource(R.string.session_sheet_this_turn), formatDuration(res, row.ms), valueColor = HN.accent)
        is TimeRow.Last -> InfoRow(stringResource(R.string.session_sheet_last_activity), agoWords(row.ago))
    }
}

/** "just now", "2m ago", "yesterday", as Completed cards say when they finished. */
@Composable
private fun agoWords(f: Finished): String = when (f) {
    Finished.Now -> stringResource(R.string.done_ago_now)
    is Finished.Minutes -> stringResource(R.string.done_ago_minutes, f.n)
    is Finished.Hours -> stringResource(R.string.done_ago_hours, f.n)
    Finished.Yesterday -> stringResource(R.string.done_ago_yesterday)
    is Finished.Days -> stringResource(R.string.done_ago_days, f.n)
}

/**
 * The branch line, the worktree, the uncommitted files and the line to What changed, under
 * [seam], from the changes route (asked when the tab opens, again as each turn ends and after What
 * changed closes). Nothing at all for a folder that isn't a repository, nor until the Mac answers.
 */
@Composable
internal fun GitPart(hub: Hub, s: Session, seam: @Composable () -> Unit) {
    if (!hub.isDemo && s.cwd == null) return
    var changes by remember(s.id) { mutableStateOf<WorkChanges?>(null) }
    var open by remember(s.id) { mutableStateOf(false) }
    val inTurn = s.phase == Phase.Working || s.phase == Phase.Starting || s.phase == Phase.NeedsYou
    LaunchedEffect(s.id, inTurn, open) {
        if (!open && (changes == null || !inTurn)) changes = ChangesRepo.load(hub, s.id).getOrNull() ?: changes
    }
    if (open) ChangesDialog(hub, s, onDismiss = { open = false })
    val g = changes?.let(::sheetGit) ?: return
    seam()
    BranchRow(g)
    g.worktree?.let { InfoRow(stringResource(R.string.session_sheet_worktree), it) }
    if (g.showsDirty) {
        val dirty = g.dirty ?: 0
        InfoRow(
            stringResource(R.string.session_sheet_uncommitted),
            if (dirty == 0) stringResource(R.string.session_sheet_clean) else pluralStringResource(R.plurals.changes_files, dirty, dirty),
        )
    }
    if (g.showsFiles) {
        Spacer(Modifier.height(4.dp))
        FilesLine(g, onClick = { open = true })
    }
}

/**
 * The branch as an InfoRow has it, its name in mono ink, then how far from its upstream, `↑2 ↓1`
 * in mint, or `no upstream` dim. TalkBack: "Branch main. 2 ahead and 1 behind origin/main."
 */
@Composable
private fun BranchRow(g: SheetGit) {
    val res = LocalContext.current.resources
    val name = g.branch ?: stringResource(R.string.session_sheet_detached)
    val said = buildString {
        append(if (g.branch != null) res.getString(R.string.session_sheet_branch_said, g.branch) else res.getString(R.string.session_sheet_detached_said))
        if (g.branch != null) {
            append(' ')
            append(
                when {
                    g.upstream == null -> res.getString(R.string.session_sheet_no_upstream_said)
                    g.ahead == null || g.behind == null -> res.getString(R.string.session_sheet_upstream_unknown_said, g.upstream)
                    g.ahead == 0 && g.behind == 0 -> res.getString(R.string.session_sheet_even_said, g.upstream)
                    else -> res.getString(R.string.session_sheet_ahead_behind_said, g.ahead, g.behind, g.upstream)
                },
            )
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp).clearAndSetSemantics { contentDescription = said },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(stringResource(R.string.session_sheet_branch), style = Type.small, color = HN.inkDim)
        Spacer(Modifier.width(16.dp))
        Spacer(Modifier.weight(1f))
        HText(name, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
        val marks = g.marks
        when {
            g.branch == null -> Unit
            g.upstream == null -> {
                Spacer(Modifier.width(8.dp))
                HText(stringResource(R.string.session_sheet_no_upstream), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            }
            marks != null -> {
                Spacer(Modifier.width(8.dp))
                HText(marks, style = Type.monoSmall, color = HN.accent, maxLines = 1)
            }
        }
    }
}

/**
 * The way to What changed, as the sheet's other links are drawn: "7 files touched" (or, when none
 * of the changes is known to be this session's, "7 files changed"), then `+120 −30` in What
 * changed's colours, and a chevron.
 */
@Composable
private fun FilesLine(g: SheetGit, onClick: () -> Unit) {
    val text = pluralStringResource(if (g.scoped) R.plurals.session_sheet_files_touched else R.plurals.session_sheet_files_changed, g.files, g.files)
    val (add, del) = lineCounts(g.add, g.del)
    val said = stringResource(R.string.session_sheet_lines_said, text, g.add, g.del)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .glassChoice(false, 12.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.changes_entry), onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(text, style = Type.small, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        HText(add, style = Type.monoSmall, color = HN.accent, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        HText(del, style = Type.monoSmall, color = HN.danger, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Icon(Glyph.Chevron, size = 11.dp, color = HN.inkDim)
    }
}
