package com.prudnikweb.hypernova.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.AgentSettingsFile
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.ClaudeSettingsSummary
import com.prudnikweb.hypernova.data.CodexSettingsSummary
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.EditableSetting
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.SettingChoice
import com.prudnikweb.hypernova.data.SettingUndo
import com.prudnikweb.hypernova.data.ShownSetting
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.NavRow
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * Agents & providers › an agent's Settings: the fixed list from [hub]'s server, in plain words, then
 * the summaries (docs/DECISIONS.md, "The agents' settings, viewed" and "…, edited"). A row the server
 * lists as editable opens a picker of its allowed values; the others are only shown. Each server
 * answers for itself, so this is the hub whose card was tapped, and nothing here changes several at once.
 */
@Composable
fun AgentSettingsScreen(hub: Hub, agent: String, onBack: () -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val codex = agent == AgentSettingsReply.CODEX
    val server = hub.mac.name
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var reply by remember { mutableStateOf<Result<AgentSettingsReply>?>(null) }
    /** The key whose picker is open. */
    var picking by remember { mutableStateOf<String?>(null) }
    /** The key being saved; the Undo row's too. */
    var saving by remember { mutableStateOf<String?>(null) }
    /** A failure, in the server's words, under the row it's about. */
    var trouble by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** A 404 on a change: this server shows its settings but is older than changing them. */
    var tooOld by remember { mutableStateOf(false) }
    LaunchedEffect(attempt, hub.agentSettingsVersion) {
        // A look again after a change keeps what's on screen until the new answer.
        if (reply?.isSuccess != true) reply = null
        reply = hub.agentSettings()
    }
    /** Runs [go], after a confirm saying what it allows and the owner's fingerprint if it [loosens] a permission. */
    fun gated(loosens: Boolean, what: String, line: String?, go: () -> Unit) {
        if (!loosens) return go()
        confirm(
            Confirm(
                title = context.getString(R.string.as_loosen_title, server),
                body = line?.let { context.getString(R.string.as_loosen_body, what, it) } ?: context.getString(R.string.as_loosen_generic),
                action = context.getString(R.string.as_loosen_action),
                danger = true,
            ) {
                OwnerCheck.ask(
                    context, context.getString(R.string.as_check_title, server), what, context.getString(R.string.check_no_lock),
                    onOk = go, onFail = { msg -> if (msg != null) toast(msg) },
                )
            },
        )
    }
    /** A failure stays under its row; a refusal may mean the file moved on, so it's read again. */
    fun failed(key: String, e: Throwable) {
        if (e is AgentSettingsTooOld) tooOld = true
        trouble = key to (e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.as_edit_err))
        if (e is AgentSettingRefused) attempt++
    }
    fun save(key: String, choice: SettingChoice, expected: String?) {
        saving = key
        trouble = null
        scope.launch {
            hub.changeAgentSetting(agent, key, choice.wire, expected)
                .onSuccess {
                    if (it.changed) toast(context.getString(R.string.as_saved, server))
                    attempt++
                }
                .onFailure { failed(key, it) }
            saving = null
        }
    }
    fun undo(key: String, expected: String) {
        saving = key
        trouble = null
        scope.launch {
            hub.undoAgentSetting(agent, expected)
                .onSuccess {
                    toast(context.getString(R.string.as_undone, server))
                    attempt++
                }
                .onFailure { failed(key, it) }
            saving = null
        }
    }
    val current = reply?.getOrNull()?.of(agent)
    val editable = if (tooOld) emptyMap() else current?.editable.orEmpty().associateBy { it.key }
    ScreenScaffold(
        sourceKey = "agent-settings:$agent",
        title = stringResource(if (codex) R.string.as_title_codex else R.string.as_title_claude),
        subtitle = server,
        onBack = onBack,
    ) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Whatever the file says: this comes with the server's live state, and the file may be missing.
            item(key = "account-seam") { Seam(stringResource(R.string.as_account), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 2.dp)) }
            item(key = "account") { if (codex) CodexAccountCard(hub, server) else ClaudeAccountCard(hub, server) }
            item(key = "intro") {
                // Not until the server's said otherwise is there anything to promise about changing them.
                val viewOnly = tooOld || (current != null && current.editable.isEmpty())
                HText(
                    stringResource(
                        when {
                            codex -> if (viewOnly) R.string.as_intro_codex_view else R.string.as_intro_codex
                            else -> if (viewOnly) R.string.as_intro_claude_view else R.string.as_intro_claude
                        },
                        server,
                    ),
                    style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
            val r = reply
            when {
                r == null -> item(key = "loading") { LoadingLine(stringResource(R.string.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                r.isFailure -> item(key = "failure") {
                    val e = r.exceptionOrNull()
                    Column(Modifier.padding(horizontal = 4.dp)) {
                        HText(e?.message?.takeIf { it.isNotBlank() } ?: stringResource(R.string.as_err), style = Type.small, color = HN.amber)
                        // Trying again can't make an older server newer.
                        if (e !is AgentSettingsTooOld) {
                            Spacer(Modifier.height(10.dp))
                            Pill(stringResource(R.string.try_again), { attempt++ }, kind = PillKind.Outline, height = 40.dp)
                        }
                    }
                }
                else -> {
                    val file = r.getOrThrow().of(agent)
                    file.whyNot?.let { why -> whyNotWords(why)?.let { words -> item(key = "why-not") { HText(stringResource(words, server), style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp)) } } }
                    if (file.state != AgentSettingsFile.OK) {
                        item(key = "state") { FileState(file.state, codex, server) }
                    }
                    // A file that isn't there yet: what can be set by making it, each not set.
                    val rows = when {
                        file.state == AgentSettingsFile.OK -> file.values
                        file.state == AgentSettingsFile.MISSING -> editable.keys.map { ShownSetting(it, ShownSetting.UNSET, null, null) }
                        else -> emptyList()
                    }
                    if (rows.isNotEmpty()) {
                        item(key = "values-seam") { Seam(stringResource(R.string.as_values), modifier = Modifier.padding(horizontal = 4.dp).padding(top = 4.dp, bottom = 2.dp)) }
                        item(key = "values") { ValuesCard(rows, editable, saving, trouble, enabled = saving == null) { trouble = null; picking = it } }
                    }
                    val offer = file.undo.takeIf { !tooOld }
                    val hash = file.hash
                    if (offer != null && hash != null) {
                        item(key = "undo") {
                            UndoCard(offer, saving == offer.key) { what, line -> gated(offer.loosens, what, line) { undo(offer.key, hash) } }
                        }
                    }
                    if (file.state == AgentSettingsFile.OK) {
                        item(key = "summary-seam") { Seam(stringResource(R.string.as_summary), modifier = Modifier.padding(horizontal = 4.dp).padding(top = 14.dp, bottom = 2.dp)) }
                        file.claude?.let { s -> item(key = "summary") { ClaudeSummaryCard(s) } }
                        file.codex?.let { s -> item(key = "summary") { CodexSummaryCard(s) } }
                    }
                }
            }
        }
    }
    val key = picking
    val edit = key?.let { editable[it] }
    if (key != null && edit != null) {
        val hash = current?.hash
        PickerSheet(edit, current?.values?.firstOrNull { it.key == key }, onDismiss = { picking = null }) { choice, what, line ->
            // The picker closes first: its own window would hide a confirm, or the line said on a phone with no lock.
            picking = null
            gated(choice.loosens, what, line) { save(key, choice, hash) }
        }
    }
}

/** Why a file reading fine has nothing to change. */
@StringRes
private fun whyNotWords(why: String): Int? = when (why) {
    AgentSettingsFile.LINK -> R.string.as_why_link
    AgentSettingsFile.READ_ONLY -> R.string.as_why_read_only
    AgentSettingsFile.NO_FOLDER -> R.string.as_why_no_folder
    else -> null
}

/**
 * Account details (owner, 2026-11-01): what the server already sends, and no more. The email and
 * organization stay on the server, as they always have.
 */
@Composable
private fun ClaudeAccountCard(hub: Hub, server: String) {
    val (line, trouble) = claudeAccountLine(hub.account, server)
    val u = hub.usage
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            HText(line, style = Type.small, color = if (trouble) HN.amber else HN.inkMuted, modifier = Modifier.padding(vertical = 4.dp))
            if (u.known) {
                if (u.fiveHourReported) UsedRow(R.string.as_account_five, (u.fiveHour * 100).toInt())
                UsedRow(R.string.as_account_week, (u.sevenDay * 100).toInt())
            }
        }
    }
}

@Composable
private fun CodexAccountCard(hub: Hub, server: String) {
    val c = hub.codex
    val account = c?.account
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            val line = when {
                c == null -> stringResource(R.string.ag_not_installed, server)
                account?.signedIn == true -> codexAccountLine(account)
                account != null -> stringResource(R.string.ag_codex_signed_out, server)
                else -> stringResource(R.string.as_account_unknown)
            }
            HText(line, style = Type.small, color = if (account?.signedIn == false) HN.amber else HN.inkMuted, modifier = Modifier.padding(vertical = 4.dp))
            c?.usage?.fiveHour?.let { UsedRow(R.string.as_account_five, it.pct.toInt()) }
            c?.usage?.sevenDay?.let { UsedRow(R.string.as_account_week, it.pct.toInt()) }
        }
    }
}

@Composable
private fun UsedRow(@StringRes label: Int, percent: Int) {
    Hairline(Modifier.fillMaxWidth())
    ReadOnlyRow(stringResource(label), stringResource(R.string.as_account_used, percent), HN.ink, null)
}

/** The file isn't there (the agent's defaults), isn't valid, or can't be read. Never why in the file's own words: the server sends none. */
@Composable
private fun FileState(state: String, codex: Boolean, server: String) {
    val (text, trouble) = when (state) {
        AgentSettingsFile.MISSING -> (if (codex) R.string.as_missing_codex else R.string.as_missing_claude) to false
        AgentSettingsFile.MALFORMED -> (if (codex) R.string.as_malformed_codex else R.string.as_malformed_claude) to true
        else -> (if (codex) R.string.as_unreadable_codex else R.string.as_unreadable_claude) to true
    }
    GlassCard(Modifier.fillMaxWidth()) {
        HText(stringResource(text, server), style = Type.small, color = if (trouble) HN.amber else HN.inkMuted, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun ValuesCard(values: List<ShownSetting>, editable: Map<String, EditableSetting>, saving: String?, trouble: Pair<String, String>?, enabled: Boolean, onPick: (String) -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            values.forEachIndexed { i, v ->
                if (i > 0) Hairline(Modifier.fillMaxWidth())
                val words = settingWords(v.key)
                // A key without words of its own keeps its name, rather than a guess.
                val label = words?.let { stringResource(it.first) } ?: v.key
                val body = words?.let { stringResource(it.second) }
                val color = when (v.state) {
                    ShownSetting.UNRECOGNISED -> HN.amber
                    ShownSetting.UNSET -> HN.inkDim
                    else -> HN.ink
                }
                if (v.key in editable) {
                    EditableRow(label, if (saving == v.key) stringResource(R.string.as_saving) else shownValue(v), color, body, whenWords(v.key)?.let { stringResource(it) }, enabled) { onPick(v.key) }
                } else {
                    ReadOnlyRow(label, shownValue(v), color, body)
                }
                if (trouble?.first == v.key) {
                    HText(trouble.second, style = Type.small, color = HN.amber, modifier = Modifier.padding(bottom = 10.dp))
                }
            }
        }
    }
}

/** As [ReadOnlyRow], with a chevron and when a change applies. A tap opens its picker. */
@Composable
private fun EditableRow(label: String, value: String, valueColor: Color, body: String?, applies: String?, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HText(label, style = Type.body, color = HN.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            HText(value, style = Type.monoSmall, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
            Spacer(Modifier.width(8.dp))
            Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
        }
        if (body != null) {
            Spacer(Modifier.height(2.dp))
            HText(body, style = Type.small, color = HN.inkDim)
        }
        if (applies != null) {
            Spacer(Modifier.height(2.dp))
            HText(applies, style = Type.small, color = HN.inkMuted)
        }
    }
}

/** Undo's row: what it would put back. [onUndo] gets the words for a confirm, should it ask. */
@Composable
private fun UndoCard(offer: SettingUndo, busy: Boolean, onUndo: (what: String, line: String?) -> Unit) {
    val label = settingWords(offer.key)?.let { stringResource(it.first) } ?: offer.key
    val shown = ShownSetting(offer.key, ShownSetting.SET, offer.toText, offer.toOn)
    val unset = offer.toText == null && offer.toOn == null
    val word = if (unset) "" else shownValue(shown)
    val line = offer.toText?.let { valueLine(offer.key, it) }?.let { stringResource(it) }
    val body = if (unset) stringResource(R.string.as_undo_to_unset, label) else stringResource(R.string.as_undo_to, label, word)
    val what = if (unset) label else word
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            NavRow(stringResource(R.string.as_undo_row), if (busy) stringResource(R.string.as_saving) else body, onClick = { if (!busy) onUndo(what, line) })
        }
    }
}

/** The server's allowed values for a key: the current one ticked, a line on each where it helps. */
@Composable
private fun PickerSheet(edit: EditableSetting, now: ShownSetting?, onDismiss: () -> Unit, onPick: (SettingChoice, what: String, line: String?) -> Unit) {
    val label = settingWords(edit.key)?.let { stringResource(it.first) } ?: edit.key
    GlassSheet(label, onDismiss) {
        HText(label, style = Type.title, color = HN.ink)
        whenWords(edit.key)?.let {
            Spacer(Modifier.height(4.dp))
            HText(stringResource(it), style = Type.small, color = HN.inkMuted)
        }
        // A permissive value in the file: shown for what it is, and not among the choices. A model's list only says what's on offer today.
        if (now?.state == ShownSetting.SET && edit.key != "model" && edit.choices.none { it.isCurrent(now) }) {
            Spacer(Modifier.height(6.dp))
            HText(stringResource(R.string.as_now_off_list, shownValue(now)), style = Type.small, color = HN.amber)
        }
        if (edit.key == "approval_policy") {
            Spacer(Modifier.height(6.dp))
            HText(stringResource(R.string.as_approval_note), style = Type.small, color = HN.inkDim)
        }
        Spacer(Modifier.height(8.dp))
        edit.choices.forEach { c ->
            val word = c.label ?: shownValue(ShownSetting(edit.key, ShownSetting.SET, c.text, c.on))
            val line = c.text?.let { valueLine(edit.key, it) }?.let { stringResource(it) }
            val current = c.isCurrent(now)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .selectable(current, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { if (current) onDismiss() else onPick(c, word, line) }
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    HText(word, style = Type.body, color = if (current) HN.ink else HN.inkMuted)
                    if (line != null) HText(line, style = Type.small, color = HN.inkDim)
                }
                Spacer(Modifier.width(12.dp))
                if (current) Icon(Glyph.Check, size = 14.dp, color = HN.accent)
            }
        }
    }
}

@Composable
private fun ClaudeSummaryCard(s: ClaudeSettingsSummary) {
    val unrecognised = stringResource(R.string.as_unrecognised)
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            val count = { n: Int? -> n?.toString() ?: "?" }
            ReadOnlyRow(stringResource(R.string.as_rules), stringResource(R.string.as_rules_value, count(s.allow), count(s.ask), count(s.deny)), HN.ink, stringResource(R.string.as_rules_body))
            Hairline(Modifier.fillMaxWidth())
            NamesRow(
                stringResource(R.string.as_plugins),
                s.plugins?.map { p ->
                    val state = when (p.on) {
                        true -> stringResource(R.string.as_on)
                        false -> stringResource(R.string.as_off)
                        null -> unrecognised
                    }
                    "${p.name}: $state"
                },
                null,
            )
            Hairline(Modifier.fillMaxWidth())
            NamesRow(stringResource(R.string.as_mcp), s.mcpServers, stringResource(R.string.as_mcp_claude_body))
            Hairline(Modifier.fillMaxWidth())
            NamesRow(stringResource(R.string.as_hooks), s.hookEvents, stringResource(R.string.as_hooks_body))
            Hidden(s.hidden)
        }
    }
}

@Composable
private fun CodexSummaryCard(s: CodexSettingsSummary) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            ReadOnlyRow(
                stringResource(R.string.as_projects),
                s.projects?.toString() ?: stringResource(R.string.as_unrecognised),
                if (s.projects == null) HN.amber else HN.ink,
                stringResource(R.string.as_projects_body),
            )
            Hairline(Modifier.fillMaxWidth())
            NamesRow(stringResource(R.string.as_mcp), s.mcpServers, null)
            Hidden(s.hidden)
        }
    }
}

@Composable
private fun Hidden(n: Int) {
    if (n <= 0) return
    Hairline(Modifier.fillMaxWidth())
    HText(pluralStringResource(R.plurals.as_hidden, n, n), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(vertical = 10.dp))
}

/** A label with its value on the right and what it means below. Nothing to tap: it's only shown. */
@Composable
private fun ReadOnlyRow(label: String, value: String, valueColor: Color, body: String?) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HText(label, style = Type.body, color = HN.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            HText(value, style = Type.monoSmall, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
        }
        if (body != null) {
            Spacer(Modifier.height(2.dp))
            HText(body, style = Type.small, color = HN.inkDim)
        }
    }
}

/** A summary that's a list of names: each on its own line, "None", or that the file holds something else. */
@Composable
private fun NamesRow(label: String, names: List<String>?, body: String?) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HText(label, style = Type.body, color = HN.ink, modifier = Modifier.weight(1f))
            when {
                names == null -> HText(stringResource(R.string.as_unrecognised), style = Type.monoSmall, color = HN.amber, maxLines = 1)
                names.isEmpty() -> HText(stringResource(R.string.as_none), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                else -> HText(names.size.toString(), style = Type.monoSmall, color = HN.ink, maxLines = 1)
            }
        }
        if (body != null) {
            Spacer(Modifier.height(2.dp))
            HText(body, style = Type.small, color = HN.inkDim)
        }
        if (!names.isNullOrEmpty()) {
            Spacer(Modifier.height(6.dp))
            HText(names.joinToString("\n"), style = Type.monoSmall, color = HN.inkMuted)
        }
    }
}

@Composable
private fun shownValue(v: ShownSetting): String = when (v.state) {
    ShownSetting.UNSET -> stringResource(R.string.as_not_set)
    ShownSetting.UNRECOGNISED -> stringResource(R.string.as_unrecognised)
    else -> when {
        v.on != null -> stringResource(if (v.on) R.string.as_on else R.string.as_off)
        else -> valueWord(v.key, v.text.orEmpty())?.let { stringResource(it) } ?: v.text.orEmpty()
    }
}

/** The label and the line for a key. Each checked against the agents' own docs or binaries (docs/DECISIONS.md). */
internal fun settingWords(key: String): Pair<Int, Int>? = when (key) {
    "effortLevel" -> R.string.as_k_effort to R.string.as_k_effort_body
    "permissions.defaultMode" -> R.string.as_k_mode to R.string.as_k_mode_body
    "showThinkingSummaries" -> R.string.as_k_thinking to R.string.as_k_thinking_body
    "autoDreamEnabled" -> R.string.as_k_dream to R.string.as_k_dream_body
    "includeCoAuthoredBy" -> R.string.as_k_coauthor to R.string.as_k_coauthor_body
    "autoUpdatesChannel" -> R.string.as_k_channel to R.string.as_k_channel_body
    "inputNeededNotifEnabled" -> R.string.as_k_input to R.string.as_k_input_body
    "agentPushNotifEnabled" -> R.string.as_k_agent_push to R.string.as_k_agent_push_body
    "model" -> R.string.as_k_model to R.string.as_k_model_body
    "model_reasoning_effort" -> R.string.as_k_reasoning to R.string.as_k_reasoning_body
    "approval_policy" -> R.string.as_k_approval to R.string.as_k_approval_body
    "sandbox_mode" -> R.string.as_k_sandbox to R.string.as_k_sandbox_body
    "web_search" -> R.string.as_k_web to R.string.as_k_web_body
    "personality" -> R.string.as_k_personality to R.string.as_k_personality_body
    else -> null
}

/** A value in words; null: as it is in the file (a model's id, a word from a newer server). */
@StringRes
internal fun valueWord(key: String, value: String): Int? = when (key) {
    "effortLevel", "model_reasoning_effort" -> when (value) {
        "none" -> R.string.as_v_none
        "minimal" -> R.string.as_v_minimal
        "low" -> R.string.as_v_low
        "medium" -> R.string.as_v_medium
        "high" -> R.string.as_v_high
        "xhigh" -> R.string.as_v_xhigh
        "max" -> R.string.as_v_max
        "ultra" -> R.string.as_v_ultra
        else -> null
    }
    // The app's own names for the modes.
    "permissions.defaultMode" -> when (value) {
        "default", "manual" -> R.string.mode_title_default
        "acceptEdits" -> R.string.mode_title_accept
        "plan" -> R.string.mode_title_plan
        "auto" -> R.string.mode_title_auto
        "dontAsk" -> R.string.as_v_dont_ask
        "bypassPermissions" -> R.string.mode_title_bypass
        else -> null
    }
    "autoUpdatesChannel" -> when (value) {
        "latest" -> R.string.as_v_latest
        "stable" -> R.string.as_v_stable
        "rc" -> R.string.as_v_rc
        else -> null
    }
    "approval_policy" -> when (value) {
        "untrusted" -> R.string.as_v_untrusted
        "on-failure" -> R.string.as_v_on_failure
        "on-request" -> R.string.as_v_on_request
        "never" -> R.string.as_v_never
        else -> null
    }
    "sandbox_mode" -> when (value) {
        "read-only" -> R.string.as_v_read_only
        "workspace-write" -> R.string.as_v_workspace_write
        "danger-full-access" -> R.string.as_v_full_access
        else -> null
    }
    "web_search" -> when (value) {
        "disabled" -> R.string.as_v_disabled
        "cached" -> R.string.as_v_cached
        "indexed" -> R.string.as_v_indexed
        "live" -> R.string.as_v_live
        else -> null
    }
    "personality" -> when (value) {
        "none" -> R.string.as_v_none
        "friendly" -> R.string.as_v_friendly
        "pragmatic" -> R.string.as_v_pragmatic
        else -> null
    }
    else -> null
}

/** When a change to [key] reaches the agent: only what's been checked, else that it may take a new session (docs/DECISIONS.md, "The agents' settings, edited"). */
@StringRes
internal fun whenWords(key: String): Int? = when (key) {
    "permissions.defaultMode" -> R.string.as_when_new_sessions
    "showThinkingSummaries" -> R.string.as_when_maybe_new_session
    "autoUpdatesChannel" -> R.string.as_when_next_update
    "model", "model_reasoning_effort", "web_search" -> R.string.as_when_new_threads
    // Hypernova's own thread/start names its sandbox and approvals (codex/sessions.ts).
    "approval_policy", "sandbox_mode" -> R.string.as_when_new_threads_unchosen
    else -> null
}

/** What a value does, in a line, where the key's own line doesn't already say. */
@StringRes
internal fun valueLine(key: String, value: String): Int? = when (key) {
    "permissions.defaultMode" -> when (value) {
        "plan" -> R.string.as_l_plan
        "default", "manual" -> R.string.as_l_default
        "dontAsk" -> R.string.as_l_dont_ask
        "acceptEdits" -> R.string.as_l_accept
        "auto" -> R.string.as_l_auto
        else -> null
    }
    "autoUpdatesChannel" -> when (value) {
        "latest" -> R.string.as_l_latest
        "stable" -> R.string.as_l_stable
        else -> null
    }
    "approval_policy" -> if (value == "on-request") R.string.as_l_on_request else null
    "sandbox_mode" -> when (value) {
        "read-only" -> R.string.as_l_read_only
        "workspace-write" -> R.string.as_l_workspace_write
        else -> null
    }
    else -> null
}
