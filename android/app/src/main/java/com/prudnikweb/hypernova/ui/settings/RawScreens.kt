@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.prudnikweb.hypernova.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.EnvReply
import com.prudnikweb.hypernova.data.FoldRow
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.JsonFold
import com.prudnikweb.hypernova.data.JsonRows
import com.prudnikweb.hypernova.data.Mcp
import com.prudnikweb.hypernova.data.RawFile
import com.prudnikweb.hypernova.data.rawFile
import com.prudnikweb.hypernova.data.rawSave
import com.prudnikweb.hypernova.data.rawUndo
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.mcp.mcpScopeWord
import com.prudnikweb.hypernova.ui.components.AskBeforeTabs
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.NavRow
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.screenBottom
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/** Between the settings as rows and the file as text. Each is a screen; this says which. */
@Composable
internal fun SettingsModeSwitch(json: Boolean, onSettings: () -> Unit, onJson: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.raw_mode_settings), { if (json) onSettings() }, kind = if (json) PillKind.Ghost else PillKind.Primary, height = 40.dp)
        Pill(stringResource(R.string.raw_mode_json), { if (!json) onJson() }, kind = if (json) PillKind.Primary else PillKind.Ghost, height = 40.dp)
    }
}

/**
 * Agents & providers › Claude Code's Settings › JSON: one settings file as text (docs/DECISIONS.md, "JSON mode"). The server
 * keeps the secrets, and refuses or asks for what it must. Here the text is [rows], whatever's folded: what's saved is [JsonRows.join].
 */
@Composable
fun AgentRawScreen(hub: Hub, agent: String, onBack: () -> Unit) {
    val bottom = screenBottom()
    val server = hub.mac.name
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var where by remember { mutableStateOf(Mcp.USER) }
    var folder by remember { mutableStateOf(Mcp.HOME) }
    var picking by remember { mutableStateOf(false) }
    var file by remember { mutableStateOf<Result<RawFile>?>(null) }
    var rows by remember { mutableStateOf<List<FoldRow>>(emptyList()) }
    var folded by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** Set while folding is off because the text isn't JSON: it's one plain row until it is. */
    var plainBecause by remember { mutableStateOf<JsonFold.Parsed.Bad?>(null) }
    var opened by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(attempt, where, folder) {
        file = null
        trouble = null
        val got = hub.rawFile(agent, where, if (where == Mcp.USER) null else folder)
        file = got
        got.getOrNull()?.text?.let { text ->
            opened = text
            val parsed = JsonFold.parse(text)
            folded = if (parsed is JsonFold.Parsed.Ok) JsonFold.opening(text, parsed.regions) else emptySet()
            val (built, bad) = JsonRows.build(text, folded)
            rows = built
            plainBecause = bad
        }
    }
    val doc = JsonRows.join(rows)
    val stops = remember(doc) { JsonFold.parse(doc) as? JsonFold.Parsed.Bad }
    val dirty = file?.getOrNull()?.text != null && doc != opened

    /** Folds again from the whole text, or, if that isn't JSON now, falls back to plain text rather than lose anything. */
    fun refold(next: Set<String>, text: String = doc) {
        val (built, bad) = JsonRows.build(text, next)
        rows = built
        plainBecause = bad
        if (bad == null) folded = next
    }
    fun allRegions(): Set<String> = (JsonFold.parse(doc) as? JsonFold.Parsed.Ok)?.regions?.mapTo(HashSet()) { it.path }.orEmpty()
    fun afterSave() {
        toast(context.getString(R.string.as_saved, server))
        attempt++
    }
    fun failed(e: Throwable) {
        trouble = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err)
    }
    fun checked(what: String, resend: () -> Unit) {
        confirm(
            Confirm(context.getString(R.string.raw_check_title, server), what, context.getString(R.string.raw_check_action), danger = true) {
                OwnerCheck.ask(context, context.getString(R.string.raw_check_title, server), context.getString(R.string.raw_check_sub), context.getString(R.string.check_no_lock), onOk = resend, onFail = { msg -> if (msg != null) toast(msg) })
            },
        )
    }
    fun save(hash: String?) {
        trouble = null
        // Checked here for the moment's answer; the server checks again.
        stops?.let {
            trouble = context.getString(R.string.raw_bad_at, it.line, it.column)
            return
        }
        saving = true
        val target = if (where == Mcp.USER) null else folder
        scope.launch {
            hub.rawSave(agent, where, target, doc, hash)
                .onSuccess { afterSave() }
                .onFailure { e ->
                    if (e is AgentSettingRefused && e.code == EnvReply.CONFIRM_RUN) {
                        checked(e.message.orEmpty()) {
                            saving = true
                            scope.launch {
                                hub.rawSave(agent, where, target, doc, hash, confirmRun = true).onSuccess { afterSave() }.onFailure(::failed)
                                saving = false
                            }
                        }
                    } else failed(e)
                }
            saving = false
        }
    }
    fun undo() {
        saving = true
        trouble = null
        scope.launch {
            hub.rawUndo(agent)
                .onSuccess { toast(context.getString(R.string.as_undone, server)); attempt++ }
                .onFailure { e ->
                    if (e is AgentSettingRefused && e.code == EnvReply.CONFIRM_RUN) {
                        checked(e.message.orEmpty()) {
                            scope.launch { hub.rawUndo(agent, confirmRun = true).onSuccess { toast(context.getString(R.string.as_undone, server)); attempt++ }.onFailure(::failed) }
                        }
                    } else failed(e)
                }
            saving = false
        }
    }
    /** A change of file loses what's typed, so it asks first. */
    fun leaving(go: () -> Unit) {
        if (!dirty) return go()
        confirm(Confirm(context.getString(R.string.raw_discard_title), context.getString(R.string.raw_discard_body), context.getString(R.string.raw_discard_action), danger = true) { go() })
    }
    AskBeforeTabs { go -> leaving(go) }
    // The system's back gesture too: App's own handler would pop the page and lose what's typed.
    BackHandler(enabled = dirty) { leaving(onBack) }
    ScreenScaffold(sourceKey = "agent-raw:$agent", title = stringResource(R.string.raw_title), subtitle = server, onBack = { leaving(onBack) }) { top, source ->
        Column(
            Modifier.fillMaxSize().then(source).verticalScroll(rememberScrollState()).padding(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsModeSwitch(json = true, onSettings = { leaving(onBack) }, onJson = {}, modifier = Modifier.padding(horizontal = 4.dp))
            FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Mcp.USER, Mcp.PROJECT, Mcp.LOCAL).forEach { s ->
                    Pill(stringResource(mcpScopeWord(s)), { if (s != where) leaving { where = s } }, kind = if (s == where) PillKind.Primary else PillKind.Ghost, height = 36.dp)
                }
            }
            val r = file
            val data = r?.getOrNull()
            if (where != Mcp.USER) FolderCard(data?.folders?.firstOrNull { it.id == folder }?.let { mcpFolderName(it) } ?: stringResource(R.string.mcp_home)) { leaving { picking = true } }
            when {
                r == null -> LoadingLine(stringResource(R.string.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                r.isFailure -> McpFailure(r.exceptionOrNull(), R.string.raw_err) { attempt++ }
                data?.text == null -> HText(rawStateWords(data), style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp))
                else -> {
                    data.undo?.let {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                                NavRow(stringResource(R.string.as_undo_row), stringResource(R.string.raw_undo_body), onClick = { if (!saving) undo() })
                            }
                        }
                    }
                    HText(data.where, style = Type.monoSmall, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
                    if (data.hidden > 0) HText(pluralStringResource(R.plurals.raw_hidden, data.hidden, data.hidden, data.placeholder), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp))
                    FlowRow(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(stringResource(R.string.raw_collapse_all), { refold(allRegions()) }, kind = PillKind.Ghost, height = 44.dp)
                        Pill(stringResource(R.string.raw_expand_all), { refold(emptySet()) }, kind = PillKind.Ghost, height = 44.dp)
                    }
                    // Why folding is off, or, while typing, where it stops being JSON.
                    plainBecause?.let { HText(stringResource(R.string.raw_no_fold, it.line, it.column), style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp)) }
                        ?: stops?.let { HText(stringResource(R.string.raw_bad_at, it.line, it.column), style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp)) }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            rows.forEachIndexed { i, row ->
                                key(row.region?.path ?: "text-$i") {
                                    JsonRow(
                                        row,
                                        onEdit = { text ->
                                            val next = JsonRows.renumber(rows.toMutableList().also { it[i] = row.copy(text = text) })
                                            rows = next
                                            // Valid again: folding comes back as it was.
                                            val whole = JsonRows.join(next)
                                            if (plainBecause != null && JsonFold.parse(whole) is JsonFold.Parsed.Ok) refold(folded, whole)
                                        },
                                        onFold = { path -> refold(if (path in folded) folded - path else folded + path) },
                                    )
                                }
                            }
                        }
                    }
                    trouble?.let { t -> HText(t, style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp)) }
                    if (saving) LoadingLine(stringResource(R.string.as_saving), Modifier.padding(horizontal = 4.dp))
                    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(stringResource(R.string.raw_save), { save(data.hash) }, kind = PillKind.Primary, height = 48.dp, enabled = dirty && !saving)
                        Pill(stringResource(R.string.raw_reopen), { leaving { attempt++ } }, kind = PillKind.Ghost, height = 48.dp)
                    }
                    HText(stringResource(R.string.raw_note), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }
    if (picking) FolderSheet(file?.getOrNull()?.folders.orEmpty(), folder, { picking = false }) { folder = it; picking = false }
}

@Composable
private fun rawStateWords(f: RawFile?): String = when {
    f == null -> stringResource(R.string.raw_err)
    f.state == RawFile.MALFORMED -> stringResource(R.string.raw_malformed, f.line ?: 1, f.column ?: 1)
    f.state == "link" -> stringResource(R.string.raw_link)
    f.state == "too_big" -> stringResource(R.string.raw_too_big)
    else -> stringResource(R.string.raw_err)
}

/** A row: its number, a fold control big enough for a thumb where it has one, its text, and what's folded in words. */
@Composable
private fun JsonRow(row: FoldRow, onEdit: (String) -> Unit, onFold: (String) -> Unit) {
    val region = row.region
    val isFolded = row.hidden != null
    val mono = Type.monoSmall.copy(color = HN.ink)
    val foldWords = if (region == null) "" else stringResource(if (isFolded) R.string.raw_unfold else R.string.raw_fold, region.path)
    Row(Modifier.fillMaxWidth(), verticalAlignment = if (region != null) Alignment.CenterVertically else Alignment.Top) {
        Box(
            Modifier
                .size(44.dp, if (region != null) 44.dp else 4.dp)
                .then(
                    if (region != null) Modifier
                        .semantics { contentDescription = foldWords }
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onFold(region.path) }
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // A chevron pointing on when folded, up when it can close.
            if (region != null) Icon(if (isFolded) Glyph.Chevron else Glyph.Up, size = 12.dp, color = HN.inkDim)
        }
        HText((row.firstLine until row.firstLine + row.lines).joinToString("\n"), style = Type.monoSmall, color = HN.inkGhost, modifier = Modifier.width(30.dp))
        Spacer(Modifier.width(6.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = row.text,
                onValueChange = onEdit,
                textStyle = mono,
                cursorBrush = SolidColor(HN.accent),
                modifier = Modifier.semantics { contentDescription = "line ${row.firstLine}" },
            )
            if (region != null && isFolded) {
                val words = if (region.isObject) pluralStringResource(R.plurals.raw_keys, region.count, region.count) else pluralStringResource(R.plurals.raw_items, region.count, region.count)
                HText(" … $words ${JsonRows.closing(row)}", style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
            }
        }
    }
}
