package com.prudnikweb.hypernova.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.FileEntry
import com.prudnikweb.hypernova.data.FileKind
import com.prudnikweb.hypernova.data.FileProblem
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Listing
import com.prudnikweb.hypernova.data.Route
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.components.DialogButton
import com.prudnikweb.hypernova.ui.components.GlassDialog
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphButton
import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.home.ago
import com.prudnikweb.hypernova.ui.home.tilde
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import java.text.NumberFormat

/** Browse a project's (or a session's) folder on the Mac. Back walks up to where you started. */
@Composable
fun FilesScreen(hub: Hub, route: Route.Files, onBack: () -> Unit) {
    val opener = LocalOpener.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var dir by rememberSaveable(route.key) { mutableStateOf(route.start) }
    var listing by remember { mutableStateOf<Listing?>(null) }
    var problem by remember { mutableStateOf<FileProblem?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }
    var locked by remember { mutableStateOf<FileEntry?>(null) }
    LaunchedEffect(dir, reload) {
        loading = true
        problem = null
        hub.files(dir)
            .onSuccess { listing = it }
            .onFailure { problem = FileProblem.of(it); listing = null }
        loading = false
    }
    val root = route.root.trimEnd('/')
    val atRoot = dir.trimEnd('/') == root
    val up = { dir = dir.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" } }
    BackHandler(enabled = !atRoot) { up() }

    ScreenScaffold(
        sourceKey = route.key,
        title = route.title,
        subtitle = tilde(dir),
        onBack = onBack,
        actions = {
            if (!atRoot) GlyphButton(Glyph.Up, stringResource(R.string.up_one), { up() })
        },
    ) { top, source ->
        val entries = listing?.entries.orEmpty()
        // A folder being opened: the old rows stay but dimmed and dead, with the loading line, so a quick tap can't open a row of the folder just left.
        val stale = loading && listing != null
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 24.dp, start = 16.dp, end = 16.dp),
        ) {
            if (loading) item(key = "loading") { LoadingLine(stringResource(R.string.loading), Modifier.padding(8.dp)) }
            val p = problem
            when {
                p != null && !loading -> item(key = "error") {
                    FileProblemCard(
                        p, forFolder = true, Modifier.padding(vertical = 8.dp), projectsRoot = hub.projectsRoot,
                        onOpenProjects = { r -> opener.files(r, r.trimEnd('/').substringAfterLast('/').ifEmpty { r }) },
                        onRetry = { reload++ },
                        onUp = if (!atRoot) ({ up() }) else null,
                        onOpenOther = if (p.kind == FileKind.WrongKind) ({ opener.file(dir) }) else null,
                        onTurnOn = if (hub.mac.browseOutsideProjects == false) ({ opener.settings(com.prudnikweb.hypernova.ui.settings.SettingsJump.FILES) }) else null,
                    )
                }
                !loading && entries.isEmpty() && listing != null -> item(key = "empty") {
                    HText(stringResource(R.string.folder_empty), style = Type.body, color = HN.inkDim, modifier = Modifier.padding(8.dp))
                }
            }
            items(entries, key = { it.path }) { e ->
                Box(Modifier.alpha(if (stale) 0.35f else 1f)) {
                    Column {
                        EntryRow(e, enabled = !stale) {
                            when {
                                e.locked != null -> locked = e
                                e.isDir -> dir = e.path
                                else -> opener.file(e.path)
                            }
                        }
                        Hairline(Modifier.fillMaxWidth().padding(start = 40.dp))
                    }
                }
            }
            val l = listing
            if (l != null && l.truncated && !loading) item(key = "more") {
                val nf = NumberFormat.getIntegerInstance()
                HText(
                    stringResource(R.string.fl_truncated, nf.format(l.entries.size), nf.format(l.total)),
                    style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                )
            }
        }
        // Why a greyed row can't be opened: one line and OK.
        locked?.let { e ->
            GlassDialog(
                onDismiss = { locked = null },
                title = e.name,
                line = stringResource(
                    when (e.locked) {
                        FileEntry.LOCKED_OUTSIDE -> R.string.fl_locked_outside_body
                        FileEntry.LOCKED_FOLDER -> R.string.fp_locked_body
                        else -> R.string.fp_secret_body
                    },
                ),
                primary = DialogButton(stringResource(R.string.fl_ok), { locked = null }),
                tint = HN.amber,
            )
        }
    }
}

@Composable
private fun EntryRow(e: FileEntry, enabled: Boolean, onClick: () -> Unit) {
    val now = remember { System.currentTimeMillis() }
    val agoText = if (!e.isDir && e.mtime > 0) ago(e.mtime, now) else null
    val sizeLabel = if (!e.isDir) sizeText(e.size) else ""
    val description = when {
        e.isDir && e.locked != null -> stringResource(R.string.fl_locked_folder_row, e.name)
        e.isDir -> stringResource(R.string.fl_row_folder, e.name)
        e.locked != null -> stringResource(R.string.fl_row_locked, e.name)
        agoText != null -> stringResource(R.string.fl_row_file, e.name, sizeLabel, agoText)
        else -> stringResource(R.string.fl_row_file_nodate, e.name, sizeLabel)
    }
    val greyed = e.locked != null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clearAndSetSemantics { }) {
            when {
                greyed -> LockGlyph(color = HN.inkDim)
                else -> Icon(if (e.isDir) Glyph.Folder else Glyph.File, size = 18.dp, color = if (e.isDir) HN.accent else HN.inkMuted)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            HText(e.name, style = Type.body, color = if (greyed) HN.inkDim else HN.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (greyed) {
                HText(stringResource(if (e.locked == "outside") R.string.fl_locked_outside else R.string.fl_locked_secret), style = Type.small, color = HN.inkDim, maxLines = 1)
            } else if (!e.isDir) Row(verticalAlignment = Alignment.CenterVertically) {
                // Its size, then how long ago it changed after a clock, as a session's stamp has its
                // age: no "12.4 KB · 3m".
                HText(sizeLabel, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                if (agoText != null) {
                    Spacer(Modifier.width(12.dp))
                    GlyphFigure(Glyph.Clock, agoText, color = HN.inkDim)
                }
            }
        }
        if (e.isDir && !greyed) Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
    }
}
