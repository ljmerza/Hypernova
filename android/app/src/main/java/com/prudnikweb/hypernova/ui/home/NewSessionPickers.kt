package com.prudnikweb.hypernova.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.Folder
import com.prudnikweb.hypernova.data.FolderPick
import com.prudnikweb.hypernova.data.Folders
import com.prudnikweb.hypernova.data.ModelCatalog
import com.prudnikweb.hypernova.data.ModelList
import com.prudnikweb.hypernova.data.ModelOption
import com.prudnikweb.hypernova.data.ModelWeek
import com.prudnikweb.hypernova.data.ModelWeeks
import com.prudnikweb.hypernova.data.Project
import com.prudnikweb.hypernova.data.ProjectPick
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.ProviderWords
import com.prudnikweb.hypernova.data.UsageProjection
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphButton
import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Incidents
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ProjectionMeterCompact
import com.prudnikweb.hypernova.ui.components.Stamp
import com.prudnikweb.hypernova.ui.components.TagChip
import com.prudnikweb.hypernova.ui.components.ThinkTwiceCard
import com.prudnikweb.hypernova.ui.components.frostedGlass
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.components.modeGlyph
import com.prudnikweb.hypernova.ui.components.needsThinkTwice
import com.prudnikweb.hypernova.ui.session.ChatSheet
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay

// The new-session sheet's choices (owner, 2026-10-18: "not all models are there… and projects, just
// an endless list"): each a chip under the prompt, each opening its picker as a sheet over it. The
// project picker finds by a few letters and groups the rest; the model picker lists every model
// each provider offers (docs/PROTOCOL.md, "Models"); the mode picker explains each mode.

/** The five permission modes, in the order Claude Code's Shift+Tab cycles them. */
internal val MODES = listOf("default", "acceptEdits", "plan", "auto", "bypassPermissions")

/** A mode's name as its chip and picker say it: "Ask first", "Accept edits"…; Codex's "Read only", "Can edit". */
@Composable
internal fun modeTitle(mode: String): String = stringResource(
    when (mode) {
        "acceptEdits" -> R.string.mode_title_accept
        "plan" -> R.string.mode_title_plan
        "auto" -> R.string.mode_title_auto
        "bypassPermissions" -> R.string.mode_title_bypass
        AgentKind.READ_ONLY -> R.string.cx_mode_read_only
        AgentKind.CAN_EDIT -> R.string.cx_mode_can_edit
        else -> R.string.mode_title_default
    },
)

/** What a mode does, in one line. */
@Composable
internal fun modeLine(mode: String): String = stringResource(
    when (mode) {
        "acceptEdits" -> R.string.mode_accept_desc
        "plan" -> R.string.mode_plan_desc
        "auto" -> R.string.mode_auto_desc
        "bypassPermissions" -> R.string.mode_bypass_desc
        AgentKind.READ_ONLY -> R.string.cx_mode_read_only_desc
        AgentKind.CAN_EDIT -> R.string.cx_mode_can_edit_desc
        else -> R.string.mode_default_desc
    },
)

/** A mode's colour where it's chosen: Bypass red, Auto amber, the rest quiet. */
internal fun modeTone(mode: String): Color? = when (mode) {
    "bypassPermissions" -> HN.danger
    "auto" -> HN.amber
    else -> null
}

// ─── Chips ───

/**
 * A choice on the sheet, as a chip: a 36dp capsule of dense frosted glass (washed in [tint] when it
 * carries a warning), its content, and a small chevron pointing down to say it opens a list. 48dp
 * tall to touch. TalkBack hears [said] and "Change".
 */
@Composable
internal fun PickChip(said: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null, content: @Composable RowScope.() -> Unit) {
    val view = LocalView.current
    val change = stringResource(R.string.ns_change)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .clickable(interaction, indication = null, onClickLabel = change, role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            // One sentence for the whole chip; its glyph and words say nothing apart.
            .clearAndSetSemantics { contentDescription = said }
            .padding(vertical = 6.dp),
    ) {
        Row(
            Modifier
                .graphicsLayer { translationY = if (pressed) 1.dp.toPx() else 0f; alpha = if (pressed) 0.8f else 1f }
                .height(36.dp)
                .frostedGlass(null, glowX = 0.dp, dense = true, tint = tint)
                .padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
            Spacer(Modifier.width(6.dp))
            Icon(Glyph.Chevron, Modifier.rotate(90f), size = 10.dp, color = HN.inkDim, stroke = 1.3.dp)
        }
    }
}

/** A chip's words: small, a little bold, in [color], cut at the end when there's no room. */
@Composable
internal fun ChipText(text: String, color: Color = HN.ink, modifier: Modifier = Modifier) {
    HText(text, style = Type.small.copy(fontWeight = FontWeight(600)), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * The chips in one row: the project, the model, Claude Code's effort when [effort] has any, the
 * mode. The project's name comes first: the others give way whole, never cut, until it fits (or
 * has 60% of the row): the model drops its provider's name (the model's own says enough), the
 * effort its label, then the mode keeps only its glyph ([chipVariants]). Only then is the
 * project's name cut at its end. [model], [effort] and [mode]: their variants, widest first.
 */
@Composable
internal fun ChipRow(project: @Composable () -> Unit, model: List<@Composable () -> Unit>, mode: List<@Composable () -> Unit>, modifier: Modifier = Modifier, gap: Dp = 8.dp, effort: List<@Composable () -> Unit> = emptyList()) {
    Layout(content = { project(); model.forEach { it() }; effort.forEach { it() }; mode.forEach { it() } }, modifier = modifier) { ms, c ->
        val loose = Constraints(maxHeight = c.maxHeight)
        val modelP = model.indices.map { ms[1 + it].measure(loose) }
        val effortP = effort.indices.map { ms[1 + model.size + it].measure(loose) }
        val modeP = mode.indices.map { ms[1 + model.size + effort.size + it].measure(loose) }
        val g = gap.roundToPx()
        val gaps = (if (effortP.isEmpty()) 2 else 3) * g
        val width = if (c.hasBoundedWidth) c.maxWidth else Int.MAX_VALUE / 2
        // The project's whole name, as far as it can have it: the others give way first.
        val wanted = ms[0].maxIntrinsicWidth(c.maxHeight).coerceIn(112.dp.roundToPx(), (width * 0.6f).toInt())
        val (mi, ei, oi) = chipVariants(modelP.map { it.width }, effortP.map { it.width }, modeP.map { it.width }, width - wanted - gaps)
        val m = modelP[mi]
        val e = effortP.getOrNull(ei)
        val o = modeP[oi]
        val left = (c.maxWidth - m.width - (e?.width ?: 0) - o.width - gaps).coerceAtLeast(0)
        val p = ms[0].measure(Constraints(maxWidth = left, maxHeight = c.maxHeight))
        val h = maxOf(p.height, m.height, e?.height ?: 0, o.height)
        layout(c.maxWidth, h) {
            var x = 0
            p.placeRelative(x, (h - p.height) / 2)
            x += p.width + g
            m.placeRelative(x, (h - m.height) / 2)
            x += m.width + g
            if (e != null) {
                e.placeRelative(x, (h - e.height) / 2)
                x += e.width + g
            }
            o.placeRelative(x, (h - o.height) / 2)
        }
    }
}

/**
 * Which variant of the model, effort and mode chips [ChipRow] draws (indexes into their widths,
 * widest first) so they fit in [room]: the model gives way first, then the effort, then the mode;
 * the narrowest of all when nothing fits. No effort chip ([effort] empty): its index is 0.
 */
internal fun chipVariants(model: List<Int>, effort: List<Int>, mode: List<Int>, room: Int): Triple<Int, Int, Int> {
    var mi = 0
    var ei = 0
    var oi = 0
    while (model[mi] + (effort.getOrNull(ei) ?: 0) + mode[oi] > room) {
        if (mi < model.lastIndex) mi++ else if (ei < effort.lastIndex) ei++ else if (oi < mode.lastIndex) oi++ else break
    }
    return Triple(mi, ei, oi)
}

// ─── Shared parts of the pickers ───

/**
 * A picker's search box: the house field's well, a lens before the words, a cross to clear them.
 * [onClose]: the cross always shows and closes the search instead (Home's, [closeLabel] for TalkBack).
 */
@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null, closeLabel: String? = null) {
    val shape = RoundedCornerShape(14.dp)
    val clear = closeLabel ?: stringResource(R.string.close)
    BasicTextField(
        value, onChange,
        singleLine = true,
        textStyle = Type.body.copy(color = HN.ink),
        cursorBrush = SolidColor(HN.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(HN.bg800.copy(alpha = 0.6f))
                    .border(1.dp, HN.ink.copy(alpha = 0.10f), shape)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyph.Search, size = 16.dp, color = HN.inkDim, stroke = 1.4.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                    if (value.isEmpty()) HText(placeholder, style = Type.body, color = HN.inkGhost, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty() || onClose != null) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = clear) { if (onClose != null) onClose() else onChange("") }
                            .semantics { contentDescription = clear },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Glyph.Cross, size = 12.dp, color = HN.inkMuted) }
                } else Spacer(Modifier.width(8.dp))
            }
        },
    )
}

/** A part of a picker's list: its name (tracked caps, or a folder in mono), a hairline, a count. */
@Composable
internal fun PartHeader(label: String, mono: Boolean = false, trail: String? = null, trailColor: Color = HN.inkDim, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp).semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
        val most = Modifier.widthIn(max = 240.dp)
        if (mono) HText(label, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = most)
        else HText(label.uppercase(), style = Type.label, color = HN.inkMuted, maxLines = 1, modifier = most)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(HN.line))
        if (trail != null) {
            Spacer(Modifier.width(10.dp))
            HText(trail, style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.5f), color = trailColor, maxLines = 1)
        }
    }
}

/** A row's end when it's the one chosen: a mint check; else the same room, empty. */
@Composable
internal fun ChosenMark(selected: Boolean) {
    Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
        if (selected) Icon(Glyph.Check, size = 16.dp, color = HN.accent, stroke = 1.8.dp)
    }
}

/** A list row a picker chooses with: 56dp at least, the chosen one on a faint mint wash. */
@Composable
internal fun Modifier.pickRow(selected: Boolean, said: String, onClick: () -> Unit, min: Dp = 56.dp): Modifier {
    val view = LocalView.current
    return this
        .fillMaxWidth()
        .heightIn(min = min)
        .clip(RoundedCornerShape(12.dp))
        .then(if (selected) Modifier.glassChoice(true, 12.dp) else Modifier)
        .selectable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onClick()
        }
        .semantics { contentDescription = said }
        .padding(horizontal = 10.dp, vertical = 8.dp)
}

/** The nav bar's height, below a picker's list. */
@Composable
private fun listBottom(): PaddingValues = PaddingValues(bottom = 14.dp)

// ─── Projects ───

/**
 * The project picker: search (name or path, a few letters do), the most recent first, then the rest
 * by folder, each part opening and closing (only Recent and the chosen one's part open at first);
 * "New project" first of all, which names a new folder in the projects folder, then "Choose a folder",
 * the folder browser ([folders]) for any folder at all (owner, 2026-10-29: "a better way to choose a
 * folder"). [selected] is a path, or [NEW_PROJECT] with [newName].
 */
@Composable
internal fun ProjectPicker(
    projects: List<Project>,
    root: String?,
    selected: String?,
    newName: String,
    now: Long,
    onPick: (String) -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit,
    folders: suspend (path: String, hidden: Boolean) -> Result<Folders>,
) {
    val title = stringResource(R.string.project)
    var query by rememberSaveable { mutableStateOf("") }
    var naming by rememberSaveable { mutableStateOf(selected == NEW_PROJECT && newName.isEmpty()) }
    // The folder browser, while it's open: where it opened, as typed.
    var browsing by rememberSaveable { mutableStateOf<String?>(null) }
    val rootT = root?.let(ProjectPick::tilde) ?: "~"
    val groups = remember(projects, root) { ProjectPick.groups(projects, root) }
    val found = remember(projects, query) { ProjectPick.search(projects, query) }
    // Recent and the part holding the chosen one start open, the rest closed; [flipped] holds the
    // parts the owner opened or closed since (their keys, a line each).
    val startsOpen = { g: ProjectPick.Group -> g.kind == ProjectPick.Kind.Recent || g.projects.any { it.path == selected } }
    var flipped by rememberSaveable { mutableStateOf("") }
    val flips = flipped.split('\n').filter { it.isNotEmpty() }.toSet()
    // Opening starts at the one chosen (under its heading, after "New project" and "Choose a
    // folder"); a new search at the top of what it found.
    val chosenAt = remember(groups) {
        var i = 2
        var at = 0
        for (g in groups) {
            val k = g.projects.indexOfFirst { it.path == selected }
            if (k >= 0) { at = i + k; break }
            i += 1 + if (startsOpen(g)) g.projects.size else 0
        }
        at
    }
    // Recent fills the first screen (rows 3 to 7): only one further down is scrolled to, the row above it showing.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = if (chosenAt > 3 + ProjectPick.RECENT) chosenAt - 1 else 0)
    var searched by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (searched) list.scrollToItem(0)
        searched = true
    }
    ChatSheet(title, onDismiss, height = 640.dp, scroll = false) {
        browsing?.let { from ->
            FolderBrowser(from, selected, folders, onUse = onPick, onBack = { browsing = null })
            return@ChatSheet
        }
        HText(title, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(12.dp))
        if (!naming) {
            SearchField(query, { query = it }, stringResource(R.string.ns_search_projects))
            Spacer(Modifier.height(4.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = listBottom()) {
            if (naming) {
                item(key = "form") {
                    NewProjectForm(
                        initial = newName.ifEmpty { ProjectPick.asName(query) },
                        root = rootT,
                        onUse = { onNew(it) },
                        onCancel = { naming = false },
                    )
                }
                return@LazyColumn
            }
            val typed = ProjectPick.asName(query)
            val offer = typed.isNotEmpty() && ProjectPick.validName(typed) && projects.none { it.name.equals(typed, ignoreCase = true) }
            val newRow: @Composable () -> Unit = {
                NewProjectRow(
                    sub = if (offer) "$rootT/$typed" else stringResource(R.string.ns_new_project_in, rootT),
                    selected = selected == NEW_PROJECT,
                    onClick = { if (offer) onNew(typed) else naming = true },
                )
            }
            if (query.isBlank() || found.isEmpty()) item(key = "new") { newRow() }
            // The folder browser: at the chosen folder, so its own folders are a tap away; a typed path, there.
            val typedPath = query.trim().takeIf { it.startsWith("/") || it.startsWith("~") }
            if (query.isBlank() || typedPath != null) item(key = "browse") {
                BrowseRow(typedPath ?: stringResource(R.string.ns_browse_sub)) {
                    browsing = typedPath ?: selected?.takeIf { it != NEW_PROJECT }?.let { it.trimEnd('/') + "/" } ?: "~/"
                }
            }
            if (query.isBlank()) {
                for (g in groups) {
                    val key = "${g.kind}:${g.label}"
                    val open = startsOpen(g) != (key in flips)
                    item(key = "h:$key") {
                        val (label, mono) = when (g.kind) {
                            ProjectPick.Kind.Recent -> stringResource(R.string.ns_recent) to false
                            ProjectPick.Kind.Worktrees -> stringResource(R.string.ns_worktrees, g.label.orEmpty()) to false
                            else -> g.label.orEmpty() to true
                        }
                        FoldHeader(label, mono, g.projects.size, open) { flipped = (if (key in flips) flips - key else flips + key).joinToString("\n") }
                    }
                    if (!open) continue
                    val recent = g.kind == ProjectPick.Kind.Recent
                    items(g.projects, key = { "p:${g.kind}:${it.path}" }) { p ->
                        if (recent) ProjectRow(p, p.path == selected, now, showAge = true, onBrowse = { browsing = p.path.trimEnd('/') + "/" }) { onPick(p.path) }
                        else ProjectLine(p, p.path == selected) { onPick(p.path) }
                    }
                }
            } else {
                if (found.isEmpty()) {
                    item(key = "none") {
                        HText(stringResource(R.string.ns_no_match, query.trim()), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp))
                    }
                }
                items(found, key = { "f:${it.path}" }) { p -> ProjectRow(p, p.path == selected, now, showAge = true) { onPick(p.path) } }
                if (found.isNotEmpty()) item(key = "new") { newRow() }
            }
        }
    }
}

/**
 * A project: its name, and where it is in mono under it; the recent ones say when they were last at
 * work, and with [onBrowse] end in a chevron that opens the folder browser in it.
 */
@Composable
private fun ProjectRow(p: Project, selected: Boolean, now: Long, showAge: Boolean, onBrowse: (() -> Unit)? = null, onClick: () -> Unit) {
    val path = ProjectPick.tilde(p.path)
    val age = p.lastActivityAt?.takeIf { showAge }?.let { ago(it, now) }
    val said = listOfNotNull(p.name, path, age).joinToString(", ")
    Row(Modifier.pickRow(selected, said, onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            HText(p.name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HText(path, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        if (age != null) {
            Spacer(Modifier.width(10.dp))
            Stamp(Glyph.Clock, age, Modifier.clearAndSetSemantics { })
        }
        ChosenMark(selected)
        if (onBrowse != null) {
            val inside = stringResource(R.string.ns_browse_inside, p.name)
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = inside, onClick = onBrowse)
                    .semantics { contentDescription = inside },
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim) }
        }
    }
}

/**
 * A project under its folder's heading: one line, its name, and the folder's own name in mono only
 * when it isn't the project's ("Hyperscribe  g2-local-stt"); the heading already says where.
 */
@Composable
private fun ProjectLine(p: Project, selected: Boolean, onClick: () -> Unit) {
    val folder = p.path.trimEnd('/').substringAfterLast('/')
    val path = ProjectPick.tilde(p.path)
    Row(Modifier.pickRow(selected, "${p.name}, $path", onClick, min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            HText(p.name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (folder != p.name) {
                Spacer(Modifier.width(10.dp))
                HText(folder, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
        ChosenMark(selected)
    }
}

/** "New project": a plus, and where it goes (or the very folder, when what's typed names one). */
@Composable
private fun NewProjectRow(sub: String, selected: Boolean, onClick: () -> Unit) {
    val title = stringResource(R.string.new_project)
    Row(Modifier.pickRow(selected, "$title, $sub", onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).frostedGlass(HN.accent, glowX = 14.dp, dense = true), contentAlignment = Alignment.Center) {
            Icon(Glyph.Plus, size = 12.dp, color = HN.accent)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            HText(title, style = Type.body, color = HN.accent, maxLines = 1)
            HText(sub, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        ChosenMark(selected)
    }
}

/** Naming a new project: the folder's name, where it will be, and Use. */
@Composable
private fun NewProjectForm(initial: String, root: String, onUse: (String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val valid = ProjectPick.validName(name)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        HText(stringResource(R.string.ns_name_it), style = Type.small, color = HN.inkMuted)
        Spacer(Modifier.height(8.dp))
        Field(
            name, { name = it.replace(' ', '-') },
            modifier = Modifier.focusRequester(focus),
            placeholder = stringResource(R.string.new_project_hint), mono = true,
            error = name.isNotEmpty() && !valid,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            label = stringResource(R.string.new_project),
            onDone = { if (valid) onUse(name.trim()) },
        )
        Spacer(Modifier.height(8.dp))
        HText(
            if (name.isNotEmpty() && !valid) stringResource(R.string.new_project_invalid)
            else stringResource(R.string.new_project_creates, "$root/${name.trim().ifEmpty { stringResource(R.string.new_project_hint) }}"),
            style = Type.monoSmall, color = if (name.isNotEmpty() && !valid) HN.danger else HN.inkDim,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.ns_use_name), { if (valid) onUse(name.trim()) }, Modifier.weight(1f), kind = PillKind.Primary, height = 44.dp, enabled = valid)
            Pill(stringResource(R.string.cancel), onCancel, kind = PillKind.Ghost, height = 44.dp)
        }
    }
}

/** A part of the project list that opens and closes: its header, and a chevron that turns down while it's open. */
@Composable
private fun FoldHeader(label: String, mono: Boolean, count: Int, open: Boolean, onToggle: () -> Unit) {
    val view = LocalView.current
    val state = stringResource(if (open) R.string.ns_part_open else R.string.ns_part_closed)
    val action = stringResource(if (open) R.string.ns_part_hide else R.string.ns_part_show)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = action) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onToggle()
            }
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.Bottom,
    ) {
        PartHeader(label, mono = mono, trail = count.toString(), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Icon(Glyph.Chevron, Modifier.padding(bottom = 8.dp).rotate(if (open) 90f else 0f), size = 10.dp, color = HN.inkDim, stroke = 1.3.dp)
    }
}

/** "Choose a folder": a folder in mint glass, and where it starts (or the path typed). */
@Composable
private fun BrowseRow(sub: String, onClick: () -> Unit) {
    val title = stringResource(R.string.ns_browse)
    Row(Modifier.pickRow(false, "$title, $sub", onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).frostedGlass(HN.accent, glowX = 14.dp, dense = true), contentAlignment = Alignment.Center) {
            Icon(Glyph.Folder, size = 14.dp, color = HN.accent, stroke = 1.3.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            HText(title, style = Type.body, color = HN.accent, maxLines = 1)
            HText(sub, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
    }
}

/**
 * The folder browser (owner, 2026-10-29: "start from my home folder or manually type in a path, and
 * then start showing the folders from that path"): the path in a field to type or paste into (`~` is
 * the home folder; a name typed after the last slash finds it among the folders there), crumbs to
 * any folder above, the folders in it to go into, and Use for the one it's on. [from]: where it
 * opens, as typed. [selected]: the folder chosen now, washed where it shows.
 */
@Composable
private fun ColumnScope.FolderBrowser(from: String, selected: String?, load: suspend (String, Boolean) -> Result<Folders>, onUse: (String) -> Unit, onBack: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf(from) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    // The last folder listed, and the folder as typed it answers: an answer to an older one is never used.
    var listed by remember { mutableStateOf<Folders?>(null) }
    var listedFor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var jump by remember { mutableStateOf(true) }
    val (dir, start) = FolderPick.split(typed)
    LaunchedEffect(dir, hidden) {
        // Typing waits a moment, so not every letter asks the computer; a tap goes at once.
        if (!jump) delay(250)
        jump = false
        load(dir, hidden)
            .onSuccess { listed = it; listedFor = dir; error = null }
            .onFailure { listedFor = null; error = it.message }
    }
    val home = listed?.home
    val current = listed?.takeIf { listedFor == dir && error == null }
    val shown = current?.let { FolderPick.matching(it.folders, start) }.orEmpty()
    val chosen = current?.let { FolderPick.chosen(it, start) }
    val go = { path: String -> jump = true; typed = FolderPick.field(path, home) }
    val list = rememberLazyListState()
    LaunchedEffect(current?.path) { list.scrollToItem(0) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyph.Back, stringResource(R.string.back), onBack)
        Spacer(Modifier.width(4.dp))
        HText(stringResource(R.string.ns_folder_title), style = Type.title, color = HN.ink, modifier = Modifier.weight(1f))
        // Dot-folders, for when one is wanted.
        val said = stringResource(R.string.ns_folder_hidden_said)
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .toggleable(hidden, remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { jump = true; hidden = it }
                .semantics { contentDescription = said },
            contentAlignment = Alignment.Center,
        ) {
            HText(
                stringResource(R.string.ns_folder_hidden), style = Type.small, color = if (hidden) HN.accent else HN.inkMuted,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).glassChoice(hidden, 14.dp).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    PathField(typed, { typed = it }, onGo = { chosen?.let(onUse) })
    error?.let {
        Spacer(Modifier.height(6.dp))
        HText(it, style = Type.small, color = HN.danger, modifier = Modifier.padding(horizontal = 4.dp))
    }
    listed?.let { l -> FolderCrumbs(FolderPick.crumbs(l.path, l.home), go) }
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = listBottom()) {
        if (current == null) {
            if (error == null) item(key = "loading") { LoadingLine(stringResource(R.string.loading), Modifier.padding(horizontal = 10.dp, vertical = 14.dp)) }
            return@LazyColumn
        }
        val parent = current.parent
        if (parent != null && start.isEmpty()) item(key = "up") {
            FolderLine(stringResource(R.string.up_one), FolderPick.tilde(parent, home), Glyph.Up, selected = false) { go(parent) }
        }
        items(shown, key = { it.path }) { f -> FolderLine(f.name, null, Glyph.Folder, selected = f.path == selected) { go(f.path) } }
        if (shown.isEmpty()) item(key = "none") {
            HText(
                if (start.isEmpty()) stringResource(R.string.ns_folder_empty) else stringResource(R.string.ns_no_match, start),
                style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 10.dp, vertical = 14.dp),
            )
        }
        if (current.truncated) item(key = "cut") {
            HText(stringResource(R.string.ns_folder_truncated, current.folders.size, current.total), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
        }
    }
    // Use, for the folder it's on (or the one whose whole name is typed), its path above it.
    Spacer(Modifier.height(6.dp))
    HText(chosen?.let { FolderPick.tilde(it, home) } ?: " ", style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.padding(horizontal = 4.dp))
    Spacer(Modifier.height(6.dp))
    Pill(
        chosen?.let { stringResource(R.string.ns_use_folder, FolderPick.name(it)) } ?: stringResource(R.string.ns_use_this_folder),
        { chosen?.let(onUse) }, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 44.dp, enabled = chosen != null,
    )
    Spacer(Modifier.height(12.dp))
}

/** The browser's path: the house field's well in mono, a folder before it, a cross to clear it. */
@Composable
private fun PathField(value: String, onChange: (String) -> Unit, onGo: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val label = stringResource(R.string.ns_folder_path)
    val clear = stringResource(R.string.close)
    BasicTextField(
        value, onChange,
        singleLine = true,
        textStyle = Type.mono.copy(color = HN.ink),
        cursorBrush = SolidColor(HN.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(HN.bg800.copy(alpha = 0.6f))
                    .border(1.dp, HN.ink.copy(alpha = 0.10f), shape)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyph.Folder, size = 16.dp, color = HN.inkDim, stroke = 1.4.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                    if (value.isEmpty()) HText(label, style = Type.mono, color = HN.inkGhost, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = clear) { onChange("") }
                            .semantics { contentDescription = clear },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Glyph.Cross, size = 12.dp, color = HN.inkMuted) }
                } else Spacer(Modifier.width(8.dp))
            }
        },
    )
}

/** The folders above the one listed, from `~` or `/`, each a tap away; the last is the one listed. Scrolled to its end. */
@Composable
private fun FolderCrumbs(crumbs: List<Pair<String, String>>, onGo: (String) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { i, (name, path) ->
            val last = i == crumbs.lastIndex
            if (i > 0) Icon(Glyph.Chevron, size = 8.dp, color = HN.inkGhost)
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !last, role = Role.Button) { onGo(path) }
                    .semantics { contentDescription = path }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) { HText(name, style = Type.monoSmall, color = if (last) HN.ink else HN.inkMuted, maxLines = 1) }
        }
    }
}

/** A folder to go into (or Up): its glyph, its name (and [sub] in mono), a chevron; the chosen one on a faint mint wash. */
@Composable
private fun FolderLine(name: String, sub: String?, glyph: Glyph, selected: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val said = if (glyph == Glyph.Folder) stringResource(R.string.fl_row_folder, name) else listOfNotNull(name, sub).joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .then(if (selected) Modifier.glassChoice(true, 12.dp) else Modifier)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(glyph, Modifier.clearAndSetSemantics { }, size = 18.dp, color = if (glyph == Glyph.Folder) HN.accent else HN.inkMuted)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            HText(name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            if (sub != null) HText(sub, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        if (glyph == Glyph.Folder) Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
    }
}

// ─── Models ───

/**
 * The model picker: every provider's models (the Mac's lists, [lists]; what's known here until
 * they come, [loading]), each provider under its name with its trouble or what's left on it, the
 * subscription's earlier versions apart, another provider's own models before its catalogue. A
 * model with a week of its own ([weeks]) shows that week's meter. Search when the lists are long.
 */
@Composable
internal fun ModelPicker(
    providers: List<Provider>,
    lists: List<ModelList>?,
    loading: Boolean,
    providerId: String?,
    model: String?,
    weeks: List<ModelWeek>,
    now: Long,
    providerTrail: @Composable (Provider) -> Pair<String, Boolean>?,
    onPick: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(R.string.model)
    var query by rememberSaveable { mutableStateOf("") }
    val all = remember(providers, lists) { providers.map { it to ModelCatalog.of(it, lists) } }
    val total = all.sumOf { it.second.size }
    val searchable = total > 14
    // The list as it's drawn, each provider under its header: so the chosen row can be found.
    val entries = remember(all, query, lists, loading) { modelEntries(all, lists, loading, query) }
    val chosen = entries.indexOfFirst { it is ModelEntry.Choice && it.provider.id == providerId && it.option.value == model }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (chosen - 1).coerceAtLeast(0))
    // A new search starts at the top of what it found; opening starts at the one chosen.
    var searched by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (searched) list.scrollToItem(0)
        searched = true
    }
    ChatSheet(title, onDismiss, height = if (total > 8) 640.dp else null, scroll = false) {
        HText(title, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(if (searchable) 12.dp else 4.dp))
        if (searchable) SearchField(query, { query = it }, stringResource(R.string.ns_search_models))
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = list, contentPadding = listBottom()) {
            items(entries, key = { it.key }) { e ->
                when (e) {
                    is ModelEntry.Header -> {
                        val trail = providerTrail(e.provider)
                        PartHeader(e.provider.name, trail = trail?.first, trailColor = if (trail?.second == true) HN.amber else HN.inkDim)
                    }
                    is ModelEntry.Part -> Row(Modifier.padding(start = 10.dp, top = 10.dp, bottom = 2.dp).semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
                        val sub = when (e.part) {
                            ModelCatalog.Part.Older -> R.string.ns_earlier
                            ModelCatalog.Part.Own -> R.string.ns_own_models
                            else -> R.string.ns_all_models
                        }
                        HText(stringResource(sub).uppercase(), style = Type.label, color = HN.inkDim)
                        if (e.part == ModelCatalog.Part.Catalogue) {
                            Spacer(Modifier.width(8.dp))
                            HText(pluralStringResource(R.plurals.ns_models_count, e.count, e.count), style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.5f), color = HN.inkGhost)
                        }
                    }
                    is ModelEntry.Choice -> ModelRow(
                        e.option, e.all, selected = e.provider.id == providerId && e.option.value == model,
                        week = if (e.provider.builtIn) ModelCatalog.week(e.option, weeks) else null,
                        now = now,
                    ) { onPick(e.provider.id, e.option.value) }
                    is ModelEntry.Loading -> LoadingLine(stringResource(R.string.ns_loading_models), Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
                    is ModelEntry.Partial -> HText(stringResource(R.string.ns_models_partial), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** One line of the model picker's list. */
private sealed interface ModelEntry {
    val key: String
    data class Header(val provider: Provider) : ModelEntry { override val key get() = "prov:${provider.id}" }
    data class Part(val provider: Provider, val part: ModelCatalog.Part, val count: Int) : ModelEntry { override val key get() = "part:${provider.id}:$part" }
    data class Choice(val provider: Provider, val option: ModelOption, val all: List<ModelOption>) : ModelEntry {
        override val key get() = "m:${provider.id}:${option.value ?: "\u0000default"}"
    }
    data class Loading(val provider: Provider) : ModelEntry { override val key get() = "loading:${provider.id}" }
    data class Partial(val provider: Provider) : ModelEntry { override val key get() = "partial:${provider.id}" }
}

/**
 * Every provider's models as the picker lists them, [query] found: the provider's header, its parts
 * (the subscription's earlier versions, another's own models before its catalogue), and whether its
 * list is still coming or came incomplete.
 */
private fun modelEntries(all: List<Pair<Provider, List<ModelOption>>>, lists: List<ModelList>?, loading: Boolean, query: String): List<ModelEntry> {
    val out = ArrayList<ModelEntry>()
    for ((p, options) in all) {
        val shown = ModelCatalog.search(options, query)
        if (shown.isEmpty() && query.isNotBlank()) continue
        out += ModelEntry.Header(p)
        val parts = ModelCatalog.parts(shown, p.builtIn)
        for ((part, items) in parts) {
            if (part != ModelCatalog.Part.Current && (part != ModelCatalog.Part.Own || parts.size > 1)) out += ModelEntry.Part(p, part, items.size)
            for (o in items) out += ModelEntry.Choice(p, o, options)
        }
        val l = lists?.firstOrNull { it.providerId == p.id }
        if (!p.builtIn && loading && l == null && query.isBlank()) out += ModelEntry.Loading(p)
        else if (l?.error != null && query.isBlank()) out += ModelEntry.Partial(p)
    }
    return out
}

/**
 * One model: its name (the default with the model it runs as a chip), one line on what it's for or
 * its id, its context window and, on another provider, its price; a model with its own week, that
 * week's meter.
 */
@Composable
private fun ModelRow(o: ModelOption, all: List<ModelOption>, selected: Boolean, week: ModelWeek?, now: Long, onClick: () -> Unit) {
    val res = LocalContext.current.resources
    val runs = if (o.value == null) ModelCatalog.shown(o, all).takeIf { it != o.name } else null
    val sub = o.description ?: o.model?.takeIf { it != o.name }
    val price = o.price
    val priceText = price?.let { if (ModelCatalog.free(it)) stringResource(R.string.ns_free) else "${ModelCatalog.dollars(it.input)} / ${ModelCatalog.dollars(it.output)}" }
    val weekP = week?.let { w ->
        val trail = ModelWeeks.trail(w, now)
        UsageProjection.of(w.pct, if (trail == ModelWeeks.Trail.None) 0L else w.resetsAt ?: 0L, now, perMin = null, limited = w.full)
    }
    val said = buildString {
        append(res.getString(R.string.ns_row_said, o.name))
        if (runs != null) append(' ').append(res.getString(R.string.ns_runs_said, runs))
        o.description?.let { append(' ').append(it).append('.') }
        o.context?.let { append(' ').append(res.getString(R.string.ns_context_said, ModelCatalog.contextText(it))) }
        if (price != null) append(' ').append(if (ModelCatalog.free(price)) res.getString(R.string.ns_free_said) else res.getString(R.string.ns_price_said, ModelCatalog.dollars(price.input), ModelCatalog.dollars(price.output)))
        if (week != null) append(' ').append(res.getString(R.string.ns_week_said, (week.pct * 100).toInt().coerceIn(0, 100)))
    }
    Row(Modifier.pickRow(selected, said, onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(o.name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val tag = runs ?: o.slot?.uppercase()
                if (tag != null) {
                    Spacer(Modifier.width(8.dp))
                    TagChip(tag, compact = true)
                }
            }
            if (sub != null) {
                val mono = o.description == null
                HText(sub, style = if (mono) Type.monoSmall else Type.small, color = HN.inkDim, maxLines = 1, overflow = if (mono) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis)
            }
            if (weekP != null) {
                Spacer(Modifier.height(6.dp))
                ProjectionMeterCompact(stringResource(R.string.seven_days), weekP, barWidth = 48.dp)
            }
        }
        if (o.context != null || priceText != null) {
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.clearAndSetSemantics { }) {
                o.context?.let { GlyphFigure(Glyph.Token, ModelCatalog.contextText(it)) }
                if (priceText != null) {
                    Spacer(Modifier.height(4.dp))
                    GlyphFigure(Glyph.Note, priceText)
                }
            }
        }
        ChosenMark(selected)
    }
}

// ─── Modes ───

/**
 * The permission modes, each with its glyph (the session cards' shield family) and what it does.
 * Auto and Bypass open the think-twice card first; the mode changes only when it's confirmed.
 * [modes]: Claude Code's five, or Codex's two ([AgentKind.CODEX_MODES]).
 */
@Composable
internal fun ModeSheet(mode: String, onPick: (String) -> Unit, onDismiss: () -> Unit, modes: List<String> = MODES) {
    val context = LocalContext.current
    val title = stringResource(R.string.mode)
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    var incidentId by rememberSaveable { mutableStateOf<String?>(null) }
    ChatSheet(title, onDismiss) {
        HText(title, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(10.dp))
        for (m in modes) {
            ModeRow(m, m == mode) {
                if (m != mode && needsThinkTwice(m)) {
                    val pick = Incidents.next(context)
                    // No list to show (the file is damaged): go on rather than block the choice.
                    if (pick == null) onPick(m) else { incidentId = pick.id; asking = m }
                } else onPick(m)
            }
        }
        // Opened from inside the sheet, so it's the sheet that blurs behind the card.
        val m = asking
        val incident = Incidents.byId(context, incidentId)
        if (m != null && incident != null) {
            ThinkTwiceCard(m, incident, onKeep = { asking = null }, onGoOn = { asking = null; onPick(m) })
        }
    }
}

@Composable
private fun ModeRow(mode: String, selected: Boolean, onClick: () -> Unit) {
    val name = modeTitle(mode)
    val line = modeLine(mode)
    val tone = modeTone(mode)
    Row(Modifier.pickRow(selected, "$name. $line", onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).frostedGlass(tone ?: if (selected) HN.accent else null, glowX = 18.dp, dense = true).clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Icon(modeGlyph(mode), size = 18.dp, color = tone ?: if (selected) HN.accent else HN.inkMuted, stroke = 1.4.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            HText(name, style = Type.body, color = tone ?: HN.ink, maxLines = 1)
            HText(line, style = Type.small, color = HN.inkDim, maxLines = 2)
        }
        ChosenMark(selected)
    }
}
