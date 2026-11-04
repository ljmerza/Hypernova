package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.AgentRulesReply
import com.prudnikweb.hypernova.data.AgentSettingRefused
import com.prudnikweb.hypernova.data.AgentSettingsFile
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.RuleChange
import com.prudnikweb.hypernova.data.RuleDraft
import com.prudnikweb.hypernova.data.RuleKind
import com.prudnikweb.hypernova.data.RuleRow
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings
import com.prudnikweb.hypernova.data.agentRules
import com.prudnikweb.hypernova.data.changeRule
import com.prudnikweb.hypernova.data.rulesMatching
import com.prudnikweb.hypernova.data.undoRule
import com.prudnikweb.hypernova.ui.components.CheckBox
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.platform.pluralStringResource
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.ui.platform.wordsNow
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * Agents & providers › an agent's settings › Permission rules, drawn once for the phone and the desktop (docs/DECISIONS.md,
 * "Permission rules, edited"). Each shell puts [agentRulesItems] in its own scrolling list and says how to ask and how to tell
 * ([RulesHost]). The server decides what's valid, what's hidden and what loosens; here is only what it said.
 */

/** What this asks of the app it's in. */
interface RulesHost {
    /** A plain question with one way on, in the words the server gave. */
    fun confirm(title: String, body: String, action: String, go: () -> Unit)
    fun toast(text: String)
}

internal data class RuleForm(val list: String, val index: Int?, val masked: Boolean, val kind: RuleKind, val main: String, val extra: String = "", val prefix: Boolean = true)

/** The screen's state: what the server said, what's open, and what's being typed. */
@Stable
class AgentRulesState internal constructor(private val hub: Hub, private val agent: String, private val host: RulesHost, private val scope: CoroutineScope, private val words: Strings, private val server: String) {
    internal var attempt by mutableIntStateOf(0)
    internal var reply by mutableStateOf<Result<AgentRulesReply>?>(null)
    internal var query by mutableStateOf("")
    internal var tab by mutableStateOf(AgentRulesReply.ALLOW)
    internal var open by mutableStateOf<String?>(null)
    internal var form by mutableStateOf<RuleForm?>(null)
    internal var busy by mutableStateOf(false)
    internal var trouble by mutableStateOf<String?>(null)
    /** The last Save was refused as a rule Claude Code would skip, so Save as typed is on offer. */
    internal var canKeep by mutableStateOf(false)

    internal suspend fun load() {
        // A look again after a change keeps what's on screen until the new answer.
        if (reply?.isSuccess != true) reply = null
        reply = hub.agentRules(agent)
    }

    private fun refused(e: Throwable, again: (confirmed: Boolean) -> Unit) {
        val message = e.message?.takeIf { it.isNotBlank() } ?: words.getString(Str.as_edit_err)
        if (e is AgentSettingRefused && e.code == AgentRulesReply.CONFIRM_NEEDED) {
            host.confirm(words.getString(Str.pr_confirm_title, server), message, words.getString(Str.pr_confirm_action)) { again(true) }
            return
        }
        trouble = message
        canKeep = e is AgentSettingRefused && e.code == AgentRulesReply.BAD_RULE
        if (e is AgentSettingRefused && e.code == AgentSettingRefused.FILE_CHANGED) attempt++
    }

    internal fun send(change: RuleChange, asTyped: Boolean = false, confirmed: Boolean = false) {
        val hash = reply?.getOrNull()?.hash
        busy = true
        trouble = null
        scope.launch {
            hub.changeRule(agent, change, hash, confirmed, asTyped)
                .onSuccess {
                    if (it.changed) host.toast(words.getString(Str.as_saved, server))
                    form = null
                    open = null
                    canKeep = false
                    attempt++
                }
                .onFailure { e -> refused(e) { send(change, asTyped, confirmed = it) } }
            busy = false
        }
    }

    internal fun undo(confirmed: Boolean = false) {
        val hash = reply?.getOrNull()?.hash ?: return
        busy = true
        trouble = null
        scope.launch {
            hub.undoRule(agent, hash, confirmed)
                .onSuccess {
                    host.toast(words.getString(Str.as_undone, server))
                    attempt++
                }
                .onFailure { e -> refused(e) { undo(confirmed = it) } }
            busy = false
        }
    }

    internal fun save(asTyped: Boolean = false) {
        val f = form ?: return
        val text = RuleDraft.text(f.kind, f.main, f.extra, f.prefix)
        send(if (f.index != null) RuleChange.Edit(f.list, f.index, text) else RuleChange.Add(f.list, text), asTyped)
    }

    internal fun startAdd(list: String) {
        trouble = null
        canKeep = false
        open = null
        form = RuleForm(list, null, false, RuleKind.Command, "")
    }

    internal fun cancelForm() {
        trouble = null
        canKeep = false
        form = null
    }

    /** To another list. What's being added goes along with it, since which list is part of what's being decided. */
    internal fun showList(list: String) {
        tab = list
        open = null
        trouble = null
        canKeep = false
        form = form?.takeIf { it.index == null }?.copy(list = list)
    }

    internal fun startEdit(list: String, row: RuleRow) {
        trouble = null
        canKeep = false
        form = RuleForm(list, row.index, row.masked, RuleKind.Text, row.text)
    }
}

/** [server]: the name it's said to be on. */
@Composable
fun rememberAgentRulesState(hub: Hub, agent: String, server: String, host: RulesHost): AgentRulesState {
    val scope = rememberCoroutineScope()
    val words = wordsNow()
    val state = remember(hub, agent) { AgentRulesState(hub, agent, host, scope, words, server) }
    LaunchedEffect(state.attempt, hub.agentSettingsVersion) { state.load() }
    return state
}

private val PAD = Modifier.padding(horizontal = 4.dp)

@Composable
private fun listName(list: String) = stringResource(
    when (list) {
        AgentRulesReply.ALLOW -> Str.pr_list_allow
        AgentRulesReply.ASK -> Str.pr_list_ask
        else -> Str.pr_list_deny
    },
)

@Composable
private fun listBody(list: String) = stringResource(
    when (list) {
        AgentRulesReply.ALLOW -> Str.pr_allow_body
        AgentRulesReply.ASK -> Str.pr_ask_body
        else -> Str.pr_deny_body
    },
)

/** The whole screen, in a list the shell has made. */
fun LazyListScope.agentRulesItems(s: AgentRulesState, server: String) {
    val r = s.reply
    when {
        r == null -> item(key = "loading") { LoadingLine(stringResource(Str.loading), PAD.padding(vertical = 8.dp)) }
        r.isFailure -> item(key = "failure") {
            val e = r.exceptionOrNull()
            Column(PAD) {
                HText(e?.message?.takeIf { it.isNotBlank() } ?: stringResource(Str.as_edit_err), style = Type.small, color = HN.amber)
                if (e !is AgentSettingsTooOld) {
                    Spacer(Modifier.height(10.dp))
                    Pill(stringResource(Str.try_again), { s.attempt++ }, kind = PillKind.Outline, height = 40.dp)
                }
            }
        }
        else -> {
            val data = r.getOrThrow()
            if (!data.supported) item(key = "codex") { CodexWords(data, server) } else claudeItems(s, data, server)
        }
    }
}

@Composable
private fun CodexWords(data: AgentRulesReply, server: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HText(stringResource(Str.pr_codex), style = Type.small, color = HN.inkMuted)
            data.rulesFiles?.let { n -> HText(pluralStringResource(Plural.pr_codex_files, n, n, server), style = Type.small, color = HN.inkDim) }
        }
    }
}

private fun LazyListScope.claudeItems(s: AgentRulesState, data: AgentRulesReply, server: String) {
    item(key = "intro") {
        Column(PAD, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HText(stringResource(Str.pr_intro, server, data.where), style = Type.small, color = HN.inkMuted)
            HText(stringResource(Str.pr_when), style = Type.small, color = HN.inkDim)
            HText(stringResource(Str.pr_scope), style = Type.small, color = HN.inkDim)
        }
    }
    data.whyNot?.let { why ->
        val words = when (why) {
            AgentSettingsFile.LINK -> Str.as_why_link
            AgentSettingsFile.READ_ONLY -> Str.as_why_read_only
            AgentSettingsFile.NO_FOLDER -> Str.as_why_no_folder
            else -> null
        }
        if (words != null) item(key = "why-not") { HText(stringResource(words, server), PAD, style = Type.small, color = HN.amber) }
    }
    when (data.state) {
        AgentSettingsFile.MISSING -> if (data.whyNot == null) item(key = "missing") { HText(stringResource(Str.pr_missing, server), PAD, style = Type.small, color = HN.inkDim) }
        AgentSettingsFile.OK -> Unit
        AgentSettingsFile.MALFORMED -> item(key = "malformed") { HText(stringResource(Str.pr_malformed), PAD, style = Type.small, color = HN.amber) }
        else -> item(key = "unreadable") { HText(stringResource(Str.pr_unreadable), PAD, style = Type.small, color = HN.amber) }
    }
    if (data.lists == null) return
    val undo = data.undo
    if (undo != null && data.editable) item(key = "undo") {
        GlassCard(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                HText(stringResource(Str.as_undo_row), Modifier.weight(1f), style = Type.body)
                Spacer(Modifier.width(12.dp))
                Pill(stringResource(Str.pr_undo), { if (!s.busy) s.undo() }, kind = PillKind.Ghost, height = 36.dp, enabled = !s.busy)
            }
        }
    }
    item(key = "search") { Field(s.query, { s.query = it }, Modifier.fillMaxWidth(), placeholder = stringResource(Str.pr_search), label = stringResource(Str.pr_search)) }
    val searching = s.query.isNotBlank()
    if (!searching) item(key = "tabs") { Tabs(s, data) }
    val shown = if (searching) AgentRulesReply.LISTS else listOf(s.tab)
    var found = 0
    for (list in shown) {
        val view = data.list(list)
        val rows = view.rules
        if (rows == null) {
            item(key = "not-list:$list") { HText(stringResource(Str.pr_not_a_list, list), PAD, style = Type.small, color = HN.amber) }
            continue
        }
        val matches = rulesMatching(rows, s.query)
        found += matches.size
        if (searching && matches.isNotEmpty()) item(key = "seam:$list") { HText("${listName(list)} ${matches.size}", PAD.padding(top = 6.dp), style = Type.small, color = HN.inkMuted) }
        if (!searching) {
            item(key = "list-head:$list") {
                Column(PAD, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HText(listBody(list), style = Type.small, color = HN.inkDim)
                    if (data.editable && s.form?.takeIf { it.index == null } == null) Pill(stringResource(Str.pr_add), { s.startAdd(list) }, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Plus)
                }
            }
            s.form?.takeIf { it.index == null && it.list == list }?.let { item(key = "add-form") { FormCard(s, it) } }
            if (rows.isEmpty()) item(key = "none:$list") { HText(stringResource(Str.pr_none), PAD, style = Type.small, color = HN.inkDim) }
        }
        items(matches.size, key = { "rule:$list:${matches[it].index}" }) { i -> RuleCard(s, data, list, matches[i]) }
        if (!searching && view.others > 0) item(key = "others:$list") { HText(pluralStringResource(Plural.pr_others, view.others, view.others), PAD, style = Type.small, color = HN.amber) }
    }
    if (searching && found == 0) item(key = "none-found") { HText(stringResource(Str.pr_none_found), PAD, style = Type.small, color = HN.inkDim) }
    // A failure with nowhere else to go: a tap on Undo, say.
    s.trouble?.takeIf { s.form == null && s.open == null }?.let { t -> item(key = "trouble") { HText(t, PAD, style = Type.small, color = HN.amber) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tabs(s: AgentRulesState, data: AgentRulesReply) {
    FlowRow(PAD, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (list in AgentRulesReply.LISTS) {
            val count = data.list(list).rules?.size?.toString() ?: "?"
            Pill(stringResource(Str.pr_list_with, listName(list), count), { s.showList(list) }, kind = if (s.tab == list) PillKind.Primary else PillKind.Ghost, height = 40.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(s: AgentRulesState, data: AgentRulesReply, list: String, row: RuleRow) {
    val key = "$list:${row.index}"
    val opened = s.open == key
    val editing = s.form?.takeIf { it.list == list && it.index == row.index }
    if (editing != null) {
        FormCard(s, editing)
        return
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, enabled = data.editable) {
                    s.trouble = null
                    s.open = if (opened) null else key
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HText(row.text, style = Type.monoSmall, color = if (row.problem != null) HN.amber else HN.ink)
            row.problem?.let { HText(stringResource(Str.pr_skipped, it), style = Type.small, color = HN.amber) }
            row.broad?.let { HText(it, style = Type.small, color = HN.amber) }
            row.overriddenBy?.let { over -> HText(stringResource(Str.pr_wins, listName(over), row.overrides.orEmpty()), style = Type.small, color = HN.inkMuted) }
            row.warning?.let { HText(it, style = Type.small, color = HN.inkDim) }
            if (opened) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(stringResource(Str.pr_edit), { s.startEdit(list, row) }, kind = PillKind.Outline, height = 36.dp, enabled = !s.busy)
                    for (to in AgentRulesReply.LISTS.filter { it != list }) {
                        Pill(stringResource(Str.pr_move_to, listName(to)), { s.send(RuleChange.Move(list, row.index, to)) }, kind = PillKind.Ghost, height = 36.dp, enabled = !s.busy)
                    }
                    Pill(stringResource(Str.pr_delete), { s.send(RuleChange.Delete(list, row.index)) }, kind = PillKind.Danger, height = 36.dp, enabled = !s.busy)
                }
                s.trouble?.let { HText(it, Modifier.padding(top = 4.dp), style = Type.small, color = HN.amber) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormCard(s: AgentRulesState, f: RuleForm) {
    val set = { next: RuleForm ->
        s.form = next
        s.trouble = null
        s.canKeep = false
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HText(listName(f.list), style = Type.small, color = HN.inkMuted)
            // An edit is the rule as text, placeholders and all: the helper's fields couldn't hold them.
            if (f.index == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((kind, words) in listOf(RuleKind.Command to Str.pr_kind_command, RuleKind.ReadFiles to Str.pr_kind_read, RuleKind.EditFiles to Str.pr_kind_edit, RuleKind.Site to Str.pr_kind_site, RuleKind.Mcp to Str.pr_kind_mcp, RuleKind.Text to Str.pr_kind_text)) {
                        Pill(stringResource(words), { set(f.copy(kind = kind, main = "", extra = "")) }, kind = if (f.kind == kind) PillKind.Primary else PillKind.Ghost, height = 34.dp)
                    }
                }
            }
            val mainHint = stringResource(
                when (f.kind) {
                    RuleKind.Command -> Str.pr_field_command
                    RuleKind.ReadFiles, RuleKind.EditFiles -> Str.pr_field_path
                    RuleKind.Site -> Str.pr_field_site
                    RuleKind.Mcp -> Str.pr_field_server
                    RuleKind.Text -> Str.pr_field_text
                },
            )
            Field(f.main, { set(f.copy(main = it)) }, Modifier.fillMaxWidth(), placeholder = mainHint, singleLine = f.kind != RuleKind.Text, mono = true, label = mainHint)
            if (f.kind == RuleKind.Mcp) {
                val hint = stringResource(Str.pr_field_tool)
                Field(f.extra, { set(f.copy(extra = it)) }, Modifier.fillMaxWidth(), placeholder = hint, mono = true, label = hint)
            }
            if (f.kind == RuleKind.Command) {
                Row(
                    Modifier
                        .heightIn(min = 40.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox) { set(f.copy(prefix = !f.prefix)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckBox(f.prefix)
                    Spacer(Modifier.width(8.dp))
                    HText(stringResource(Str.pr_anything_after), style = Type.small, color = HN.inkMuted)
                }
            }
            if (f.kind == RuleKind.Text) HText(stringResource(Str.pr_text_note), style = Type.small, color = HN.inkDim)
            if (f.masked) HText(stringResource(Str.pr_hidden), style = Type.small, color = HN.inkDim)
            val text = RuleDraft.text(f.kind, f.main, f.extra, f.prefix)
            if (f.kind != RuleKind.Text && text.isNotEmpty()) HText(stringResource(Str.pr_will_be, text), style = Type.monoSmall, color = HN.inkMuted)
            s.trouble?.let { HText(it, style = Type.small, color = HN.amber) }
            if (s.canKeep) HText(stringResource(Str.pr_save_anyway_body), style = Type.small, color = HN.inkDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(stringResource(Str.pr_save), { s.save() }, kind = PillKind.Primary, height = 40.dp, enabled = text.isNotEmpty() && !s.busy)
                if (s.canKeep) Pill(stringResource(Str.pr_save_anyway), { s.save(asTyped = true) }, kind = PillKind.Outline, height = 40.dp, enabled = !s.busy)
                Pill(stringResource(Str.cancel), { s.cancelForm() }, kind = PillKind.Ghost, height = 40.dp)
            }
            if (s.busy) LoadingLine(stringResource(Str.as_saving))
        }
    }
}
