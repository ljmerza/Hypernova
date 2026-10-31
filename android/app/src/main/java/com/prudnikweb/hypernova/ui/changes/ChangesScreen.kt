package com.prudnikweb.hypernova.ui.changes

import androidx.compose.animation.AnimatedContent
import com.prudnikweb.hypernova.ui.components.glassChoice
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.BlurredBottom
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.fx.Grain
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

/** A turn is running (or waits on an answer): Commit would only queue behind it. */
private val Session.turnRunning: Boolean get() = phase == Phase.Working || phase == Phase.Starting || phase == Phase.NeedsYou

private val ADD = HN.accent
private val DEL = HN.danger

/**
 * The entry point in a session's header: a chip with the number of changed files and their
 * +/- once the session's folder has changes, a tap away from the list. Nothing shows for a folder
 * that is clean or isn't a git repository, so most sessions pay no space for it. The summary is
 * asked for when the session opens and each time it settles (a turn ends), never while it works.
 */
@Composable
fun ChangesEntry(hub: Hub, session: Session, modifier: Modifier = Modifier, fallback: @Composable () -> Unit = {}) {
    if (session.cwd == null) return fallback()
    var summary by remember(session.id) { mutableStateOf<WorkChanges?>(null) }
    var open by rememberSaveable(session.id) { mutableStateOf(false) }
    val running = session.turnRunning
    LaunchedEffect(session.id, running, open) {
        if (!running && !open) summary = ChangesRepo.load(session.id).getOrNull() ?: summary
    }
    val s = summary
    // [fallback] where there's no summary to show (not a git folder, nothing changed, not loaded yet).
    if (s == null || !s.git || s.files.isEmpty()) fallback()
    if (s != null && s.git && s.files.isNotEmpty()) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HText(stringResource(R.string.changes_entry).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .heightIn(min = 36.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { open = true },
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier
                        .clip(CircleShape)
                        .glassChoice(false)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Glyph.File, size = 11.dp, color = HN.accentDim)
                    Spacer(Modifier.width(6.dp))
                    HText(pluralStringResource(R.plurals.changes_files, s.files.size, s.files.size), style = Type.monoSmall, color = HN.ink, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Counts(s.totalAdd, s.totalDel)
                    Spacer(Modifier.width(4.dp))
                    Icon(Glyph.Chevron, size = 11.dp, color = HN.inkDim)
                }
            }
        }
    }
    if (open) ChangesDialog(hub, session, onDismiss = { open = false })
}

@Composable
private fun Counts(add: Int?, del: Int?) {
    if (add == null || del == null) return
    HText("+$add", style = Type.monoSmall, color = ADD, maxLines = 1)
    Spacer(Modifier.width(5.dp))
    HText("−$del", style = Type.monoSmall, color = DEL, maxLines = 1)
}

/**
 * The changes in their own window over the session: the files, and one file's diff in the same
 * window (Back goes from the diff to the list, then closes). A window of its own keeps this one
 * entry point in the session screen; it paints its own ground and blur. The session sheet's Files
 * touched opens it too (ui/session/SessionInfo.kt).
 */
@Composable
internal fun ChangesDialog(hub: Hub, session: Session, onDismiss: () -> Unit) {
    var file by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    Dialog(
        onDismissRequest = { if (file != null) file = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val haze = remember { HazeState() }
        CompositionLocalProvider(LocalHaze provides haze) {
            Box(Modifier.fillMaxSize().background(HN.bg)) {
                AnimatedContent(
                    targetState = file,
                    transitionSpec = { fadeIn(tween(Motion.DIALOG)) togetherWith fadeOut(tween(Motion.FAST)) },
                    label = "changes",
                ) { f ->
                    if (f == null) ChangesList(hub, session, onBack = onDismiss, onOpen = { file = it })
                    else DiffScreen(session, f, onBack = { file = null })
                }
                Grain(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ChangesList(hub: Hub, session: Session, onBack: () -> Unit, onOpen: (String) -> Unit) {
    var all by rememberSaveable(session.id) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var changes by remember { mutableStateOf<WorkChanges?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session.id, all, attempt, session.turnRunning) {
        ChangesRepo.load(session.id, all)
            .onSuccess { changes = it; error = null }
            .onFailure { if (changes == null) error = it.message }
    }
    val c = changes
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val sourceKey = "changes:${session.id}"
    val folder = session.cwd?.trimEnd('/')?.substringAfterLast('/') ?: session.project
    ScreenScaffold(
        sourceKey = sourceKey,
        title = stringResource(R.string.changes_title),
        subtitle = c?.branch?.let { stringResource(R.string.changes_subtitle_branch, it, folder) } ?: folder,
        onBack = onBack,
    ) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 8.dp, bottom = nav + 150.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                c == null && error == null -> item("loading") { LoadingLine(stringResource(R.string.changes_loading), Modifier.padding(top = 8.dp)) }
                c == null -> item("error") {
                    Column(Modifier.padding(top = 8.dp)) {
                        HText(error.orEmpty(), style = Type.body, color = HN.danger)
                        Spacer(Modifier.height(12.dp))
                        Pill(stringResource(R.string.try_again), { error = null; attempt++ }, kind = PillKind.Outline)
                    }
                }
                !c!!.git -> item("nogit") { Empty(stringResource(R.string.changes_no_git)) }
                c.files.isEmpty() -> item("clean") { Empty(stringResource(R.string.changes_clean)) }
                else -> {
                    item("summary") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HText(pluralStringResource(R.plurals.changes_files, c.files.size, c.files.size).uppercase(), style = Type.label, color = HN.inkDim)
                            Spacer(Modifier.width(10.dp))
                            Counts(c.totalAdd, c.totalDel)
                        }
                    }
                    item("files") {
                        GlassCard(Modifier.fillMaxWidth()) {
                            c.files.forEachIndexed { i, f ->
                                if (i > 0) Hairline(Modifier.padding(start = 56.dp))
                                FileRow(f, onClick = { onOpen(f.path) })
                            }
                        }
                    }
                    if (c.more) item("more") { Note(stringResource(R.string.changes_more)) }
                    if (c.scoped && c.others > 0) item("others") {
                        Toggle(pluralStringResource(R.plurals.changes_others, c.others, c.others), stringResource(R.string.changes_show_all)) { all = true }
                    } else if (!c.scoped && all) item("only") {
                        Toggle(null, stringResource(R.string.changes_only_session)) { all = false }
                    }
                    if (c.hidden > 0) item("hidden") { Note(pluralStringResource(R.plurals.changes_hidden, c.hidden, c.hidden)) }
                }
            }
        }
        if (c != null && c.git && c.files.isNotEmpty()) CommitBar(hub, session, c, onDone = onBack, sourceKey = sourceKey, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Empty(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Glyph.Check, size = 28.dp, color = HN.accentDim, stroke = 1.5.dp)
        Spacer(Modifier.height(14.dp))
        HText(text, style = Type.body, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 24.dp), maxLines = 4, overflow = TextOverflow.Clip)
    }
}

@Composable
private fun Note(text: String) = HText(text, style = Type.small, color = HN.inkDim)

@Composable
private fun Toggle(body: String?, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (body != null) HText(body, style = Type.small, color = HN.inkDim, modifier = Modifier.weight(1f), maxLines = 2)
        else Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        HText(action, style = Type.small.copy(fontWeight = FontWeight(600)), color = HN.accent, maxLines = 1)
    }
}

@Composable
private fun FileRow(f: ChangedFile, onClick: () -> Unit) {
    val color = when (f.status) { 'A' -> HN.accent; 'D' -> HN.danger; else -> HN.amber }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).glassChoice(true, 7.dp, color),
            contentAlignment = Alignment.Center,
        ) { HText(f.status.toString(), style = Type.monoSmall.copy(fontWeight = FontWeight(700)), color = color, maxLines = 1) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            HText(f.name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (f.dir.isNotEmpty()) HText(f.dir, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        Spacer(Modifier.width(12.dp))
        if (f.binary) HText("binary", style = Type.monoSmall, color = HN.inkDim, maxLines = 1) else Counts(f.add, f.del)
    }
}

/** The Commit button: a request to the session's agent (Claude, or Codex), so it follows the repository's own habits. */
@Composable
private fun CommitBar(hub: Hub, session: Session, c: WorkChanges, onDone: () -> Unit, sourceKey: String, modifier: Modifier) {
    val haze = LocalHaze.current
    val appScope = LocalAppScope.current
    val opener = LocalOpener.current
    val toast = LocalToast.current
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val busy = session.turnRunning
    val prompt = if (c.scoped && c.files.size <= 20) stringResource(R.string.changes_commit_prompt_files, c.files.joinToString(", ") { it.path })
    else stringResource(R.string.changes_commit_prompt)
    BlurredBottom(haze, sourceKey, modifier.fillMaxWidth()) {
        Column(Modifier.padding(top = 40.dp, bottom = nav + 12.dp, start = 20.dp, end = 20.dp)) {
            HText(
                stringResource(if (busy) R.string.changes_commit_busy else R.string.changes_commit_note, com.prudnikweb.hypernova.ui.components.agentName(session.codex)),
                style = Type.small, color = HN.inkDim,
            )
            Spacer(Modifier.height(10.dp))
            Pill(
                stringResource(R.string.changes_commit),
                onClick = {
                    if (!busy) {
                        appScope.launch {
                            hub.send(session.id, prompt)
                                .onSuccess { id -> if (id != session.id) opener.switchSession(id) }
                                .onFailure { toast(it.message.orEmpty()) }
                        }
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                kind = PillKind.Primary,
                enabled = !busy,
            )
        }
    }
}

// ─── One file's diff ───────────────────────────────────────────────────────

@Composable
private fun DiffScreen(session: Session, path: String, onBack: () -> Unit) {
    var diff by remember(path) { mutableStateOf<FileDiff?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(path, attempt) {
        ChangesRepo.diff(session.id, path).onSuccess { diff = it; error = null }.onFailure { error = it.message }
    }
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val ext = remember(path) { Highlight.extOf(path) }
    val d = diff
    ScreenScaffold(
        sourceKey = "diff:$path",
        title = path.substringAfterLast('/'),
        subtitle = path.substringBeforeLast('/', "").ifEmpty { null },
        onBack = onBack,
    ) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top + 4.dp, bottom = nav + 28.dp),
        ) {
            when {
                d == null && error == null -> item("loading") { LoadingLine(stringResource(R.string.changes_loading), Modifier.padding(start = 20.dp, top = 8.dp)) }
                d == null -> item("error") {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                        HText(error.orEmpty(), style = Type.body, color = HN.danger)
                        Spacer(Modifier.height(12.dp))
                        Pill(stringResource(R.string.try_again), { error = null; attempt++ }, kind = PillKind.Outline)
                    }
                }
                d!!.binary -> item("binary") { Note20(stringResource(R.string.changes_binary)) }
                d.lines.isEmpty() -> item("empty") { Note20(stringResource(R.string.changes_diff_empty)) }
                else -> {
                    items(d.lines.size, key = { it }) { i -> DiffRow(d.lines[i], ext) }
                    if (d.truncated) item("cut") { Note20(stringResource(R.string.changes_truncated)) }
                }
            }
        }
    }
}

@Composable
private fun Note20(text: String) = HText(text, style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))

private val GUTTER = 44.dp

/** One line: its number, the + or -, and the code (coloured, and wrapped when it is wider than the screen). */
@Composable
private fun DiffRow(line: DiffLine, ext: String) {
    val (tint, mark, markColor) = when (line.kind) {
        LineKind.Add -> Triple(ADD.copy(alpha = 0.10f), "+", ADD)
        LineKind.Del -> Triple(DEL.copy(alpha = 0.10f), "−", DEL)
        LineKind.Hunk -> Triple(HN.bg800, "", HN.inkDim)
        else -> Triple(Color.Transparent, "", HN.inkDim)
    }
    if (line.kind == LineKind.Hunk) {
        HText(line.text, style = Type.monoSmall, color = HN.inkMuted, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).background(tint).padding(horizontal = 12.dp, vertical = 5.dp))
        return
    }
    if (line.kind == LineKind.Note) {
        HText(line.text, style = Type.monoSmall, color = HN.inkDim, modifier = Modifier.padding(start = GUTTER + 22.dp, top = 2.dp, bottom = 2.dp))
        return
    }
    val text = remember(line, ext) { Highlight.line(line.text.ifEmpty { " " }, ext) }
    Row(Modifier.fillMaxWidth().background(tint)) {
        val no = if (line.kind == LineKind.Del) line.oldNo else line.newNo
        HText(no.toString(), style = Type.monoSmall.copy(textAlign = TextAlign.End), color = if (line.kind == LineKind.Ctx) HN.inkDim.copy(alpha = 0.7f) else markColor.copy(alpha = 0.6f), modifier = Modifier.width(GUTTER).padding(end = 8.dp), maxLines = 1)
        HText(mark, style = Type.monoSmall, color = markColor, modifier = Modifier.width(12.dp), maxLines = 1)
        HText(text, style = Type.monoSmall, color = HN.ink.copy(alpha = if (line.kind == LineKind.Ctx) 0.78f else 1f), modifier = Modifier.weight(1f).padding(end = 12.dp, top = 1.dp, bottom = 1.dp), overflow = TextOverflow.Clip)
    }
}
