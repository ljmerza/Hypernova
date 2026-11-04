package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.AgentSettingsFile
import com.prudnikweb.hypernova.data.AgentSettingsTooOld
import com.prudnikweb.hypernova.data.FormChoice
import com.prudnikweb.hypernova.data.FormProblem
import com.prudnikweb.hypernova.data.FormRow
import com.prudnikweb.hypernova.data.JsonFold
import com.prudnikweb.hypernova.data.JsonRows
import com.prudnikweb.hypernova.data.ProblemException
import com.prudnikweb.hypernova.data.SettingsFormLogic
import com.prudnikweb.hypernova.data.SettingsFormReply
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphButton
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Radio
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.SwitchMark
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.ui.platform.wordsNow
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * The rows of an agent's settings, generated from what the server sends: one control per kind, nothing per setting. [onScreen]: a
 * setting with a screen of its own, by the server's name for it. Each system lays this out in its own frame.
 */
@Composable
fun SettingsFormList(form: SettingsFormState, onScreen: (String) -> Unit, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val strings = wordsNow()
    val r = form.reply
    val data = r?.getOrNull()
    val server = form.hub.mac.name
    val groups = form.groups(strings)
    LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "search") {
            Field(form.query, { form.query = it }, placeholder = stringResource(Str.sf_search), label = stringResource(Str.sf_search))
        }
        when {
            r == null -> item(key = "loading") { LoadingLine(stringResource(Str.loading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
            r.isFailure -> item(key = "failure") {
                val e = r.exceptionOrNull()
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
                    HText(e?.message?.takeIf { it.isNotBlank() } ?: stringResource(Str.sf_err), style = Type.small, color = HN.amber)
                    // Trying again can't make an older server newer.
                    if (e !is AgentSettingsTooOld) {
                        Spacer(Modifier.height(10.dp))
                        Pill(stringResource(Str.try_again), { form.again() }, kind = PillKind.Outline, height = 40.dp)
                    }
                }
            }
            data != null -> {
                item(key = "about") { About(data, form.codex, server) }
                data.undo?.takeIf { data.hash != null }?.let { offer ->
                    item(key = "undo") {
                        GlassCard(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp)
                                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, enabled = form.saving == null) { form.undo() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    HText(stringResource(Str.as_undo_row), style = Type.body, color = HN.ink)
                                    HText(offer.key, style = Type.monoSmall, color = HN.inkDim)
                                }
                                Icon(Glyph.Reload, size = 14.dp, color = HN.inkMuted)
                            }
                        }
                    }
                }
                if (groups.isEmpty() && data.rows.isNotEmpty()) item(key = "none") { HText(stringResource(Str.sf_none), style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
                for (group in groups) {
                    item(key = "group:${group.id}") { Seam(group.title, modifier = Modifier.padding(horizontal = 4.dp).padding(top = 10.dp, bottom = 2.dp)) }
                    items(group.rows, key = { "row:${it.key}" }) { row ->
                        FormRowView(row, form, data.canChange, onScreen)
                        Hairline(Modifier.fillMaxWidth())
                    }
                }
                if (data.hiddenKeys > 0) item(key = "hidden-keys") { HText(stringResource(Str.sf_hidden_keys, data.hiddenKeys), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) }
            }
        }
    }
}

@Composable
private fun About(data: SettingsFormReply, codex: Boolean, server: String) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (data.whyNot) {
            AgentSettingsFile.LINK -> HText(stringResource(Str.as_why_link, server), style = Type.small, color = HN.amber)
            AgentSettingsFile.READ_ONLY -> HText(stringResource(Str.as_why_read_only, server), style = Type.small, color = HN.amber)
            AgentSettingsFile.NO_FOLDER -> HText(stringResource(Str.as_why_no_folder, server), style = Type.small, color = HN.amber)
        }
        when (data.state) {
            AgentSettingsFile.MALFORMED -> HText(stringResource(Str.sf_state_malformed), style = Type.small, color = HN.amber)
            AgentSettingsFile.UNREADABLE -> if (data.whyNot != AgentSettingsFile.LINK) HText(stringResource(Str.sf_state_unreadable), style = Type.small, color = HN.amber)
        }
        HText(stringResource(if (codex) Str.sf_scope_codex else Str.sf_scope_claude), style = Type.small, color = HN.inkMuted)
        HText(stringResource(Str.sf_source, data.from, data.checkedAgainst), style = Type.small, color = HN.inkDim)
    }
}

@Composable
private fun rowTitle(row: FormRow): String = formWords(row.key)?.let { stringResource(it.first) } ?: row.title

@Composable
private fun rowBody(row: FormRow): String = formWords(row.key)?.let { stringResource(it.second) } ?: row.description

@Composable
private fun valueWords(key: String, value: String): String = formValueWord(key, value)?.let { stringResource(it) } ?: value

/** What's there, in a line. Not set always reads as not set, with what that means if the schema says. */
@Composable
private fun statusLine(row: FormRow): String = when {
    row.state == FormRow.OPAQUE -> stringResource(Str.sf_in_file)
    !row.isSet -> when {
        row.defaultOn != null -> stringResource(Str.sf_not_set_default, stringResource(if (row.defaultOn == true) Str.sf_on else Str.sf_off))
        row.defaultWords != null -> stringResource(Str.sf_not_set_default, valueWords(row.key, row.defaultWords!!))
        else -> stringResource(Str.sf_not_set)
    }
    row.hidden > 0 && row.control != FormRow.JSON -> stringResource(Str.sf_set_hidden)
    row.on != null -> stringResource(if (row.on == true) Str.sf_on else Str.sf_off)
    row.string != null -> valueWords(row.key, row.string!!)
    row.number != null -> row.number!!
    row.items != null -> row.items!!.joinToString(", ").ifEmpty { stringResource(Str.sf_set_mark) }
    else -> stringResource(Str.sf_set_mark)
}

@Composable
private fun FormRowView(row: FormRow, form: SettingsFormState, canChange: Boolean, onScreen: (String) -> Unit) {
    val title = rowTitle(row)
    val isOpen = form.open == row.key
    val link = row.control == FormRow.LINK
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                    if (link) row.screen?.let(onScreen) else form.toggle(row.key)
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                HText(title, style = Type.body, color = HN.ink)
                HText(row.key, style = Type.monoSmall, color = HN.inkGhost)
                if (!link) HText(statusLine(row), style = Type.small, color = if (row.isSet) HN.accent else HN.inkDim, maxLines = 2)
                else formScreenWords(row.screen)?.let { HText(stringResource(it), style = Type.small, color = HN.inkDim) }
            }
            Spacer(Modifier.width(12.dp))
            when {
                link -> Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
                row.control == FormRow.SWITCH && canChange -> Box(
                    Modifier
                        .size(58.dp, 44.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch, enabled = form.saving == null) { form.save(row, !row.showsOn) }
                        .semantics {
                            contentDescription = title
                            toggleableState = ToggleableState(row.showsOn)
                        },
                    contentAlignment = Alignment.Center,
                ) { SwitchMark(row.showsOn) }
                else -> Icon(if (isOpen) Glyph.Up else Glyph.Chevron, size = 12.dp, color = HN.inkDim)
            }
        }
        if (isOpen && !link) FormRowDetails(row, form, canChange)
        form.trouble?.takeIf { it.first == row.key }?.let { (_, words) ->
            HText(words, style = Type.small, color = HN.amber, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (form.saving == row.key) LoadingLine(stringResource(Str.as_saving), Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun FormRowDetails(row: FormRow, form: SettingsFormState, canChange: Boolean) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val body = rowBody(row)
        if (body.isNotEmpty()) HText(body, style = Type.small, color = HN.inkMuted)
        HText(stringResource(formAppliesWords(row.applies) ?: Str.sf_when_unknown), style = Type.small, color = HN.inkDim)
        if (row.notRead) form.current?.let { HText(stringResource(Str.sf_not_read, it.checkedAgainst), style = Type.small, color = HN.amber) }
        if (row.notInSchema) HText(stringResource(Str.sf_not_in_schema), style = Type.small, color = HN.inkDim)
        else formAskWords(row.asks)?.let { HText(stringResource(it), style = Type.small, color = HN.amber) }
        when {
            row.readOnly != null -> {
                formReadOnlyWords(row.readOnly)?.let { HText(stringResource(it), style = Type.small, color = HN.inkDim) }
                row.json?.let { HText(it, style = Type.monoSmall, color = HN.inkMuted) }
            }
            !canChange -> row.json?.let { HText(it, style = Type.monoSmall, color = HN.inkMuted) }
            else -> when (row.control) {
                FormRow.PICK -> PickControl(row, form)
                FormRow.TEXT -> TextControl(row, form)
                FormRow.NUMBER -> NumberControl(row, form)
                FormRow.LIST -> ListControl(row, form)
                FormRow.JSON -> JsonControl(row, form)
                else -> {}
            }
        }
        if (row.removable && canChange) Pill(stringResource(Str.sf_remove), { form.remove(row) }, kind = PillKind.Ghost, height = 40.dp, enabled = form.saving == null)
    }
}

@Composable
private fun formProblemText(p: FormProblem): String {
    val (words, args) = formProblemWords(p)
    return if (args.isEmpty()) stringResource(words) else stringResource(words, *args)
}

/** Bypass and the like are never among the choices: a file holding one shows it in the row's line, and can move off it. */
@Composable
private fun PickControl(row: FormRow, form: SettingsFormState) {
    Column {
        row.choices.forEach { c -> key(c.text ?: c.on.toString()) { FormChoiceRow(row, c, form) } }
    }
}

@Composable
private fun FormChoiceRow(row: FormRow, c: FormChoice, form: SettingsFormState) {
    val value = c.text ?: c.on.toString()
    val selected = row.isSet && (c.text != null && (row.string == c.text || (row.key == "permissions.defaultMode" && row.string == "manual" && c.text == "default")) || c.on != null && row.on == c.on)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, enabled = form.saving == null) { if (!selected) form.save(row, c.wire) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            HText(c.label ?: valueWords(row.key, value), style = Type.body, color = HN.ink)
            formValueLine(row.key, value)?.let { HText(stringResource(it), style = Type.small, color = HN.inkDim) }
        }
    }
}

@Composable
private fun TextControl(row: FormRow, form: SettingsFormState) {
    val hidden = row.hidden > 0
    val shown = form.drafts[row.key] ?: if (hidden) "" else row.string.orEmpty()
    val problem = form.drafts[row.key]?.let { SettingsFormLogic.textProblem(row.rules, it) }
    Field(
        shown,
        { typed -> if (typed == (if (hidden) "" else row.string.orEmpty())) form.drafts.remove(row.key) else form.drafts[row.key] = typed },
        placeholder = if (hidden) stringResource(Str.sf_hidden) else "",
        mono = true,
        error = problem != null,
        label = rowTitleWords(row),
    )
    problem?.let { HText(formProblemText(it), style = Type.small, color = HN.amber) }
    Pill(stringResource(Str.raw_save), { form.drafts[row.key]?.let { form.save(row, it) } }, kind = PillKind.Primary, height = 40.dp, enabled = form.drafts[row.key] != null && problem == null && form.saving == null)
}

@Composable
private fun rowTitleWords(row: FormRow): String = rowTitle(row)

@Composable
private fun NumberControl(row: FormRow, form: SettingsFormState) {
    val shown = form.drafts[row.key] ?: row.number.orEmpty()
    val parsed = form.drafts[row.key]?.let { SettingsFormLogic.numberOf(row.rules, it) }
    val problem = (parsed?.exceptionOrNull() as? ProblemException)?.problem
    Field(
        shown,
        { typed -> if (typed == row.number.orEmpty()) form.drafts.remove(row.key) else form.drafts[row.key] = typed },
        placeholder = row.defaultWords.orEmpty(),
        mono = true,
        error = problem != null,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        label = rowTitleWords(row),
    )
    problem?.let { HText(formProblemText(it), style = Type.small, color = HN.amber) }
    Pill(stringResource(Str.raw_save), { parsed?.getOrNull()?.let { form.save(row, it) } }, kind = PillKind.Primary, height = 40.dp, enabled = parsed?.isSuccess == true && form.saving == null)
}

@Composable
private fun ListControl(row: FormRow, form: SettingsFormState) {
    val items = form.lists[row.key] ?: row.items.orEmpty()
    val adding = form.adding[row.key].orEmpty()
    val problem = SettingsFormLogic.listProblem(row.rules, items).takeIf { form.lists[row.key] != null }
    fun change(next: List<String>) {
        if (next == row.items.orEmpty()) form.lists.remove(row.key) else form.lists[row.key] = next
    }
    items.forEachIndexed { i, item ->
        key(i, item) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                HText(item, style = Type.monoSmall, color = HN.ink, modifier = Modifier.weight(1f))
                GlyphButton(Glyph.Cross, stringResource(Str.sf_item_remove, item), { change(items.filterIndexed { at, _ -> at != i }) }, color = HN.inkMuted)
            }
        }
    }
    Field(adding, { form.adding[row.key] = it }, placeholder = stringResource(Str.sf_item_hint), mono = true, label = stringResource(Str.sf_item_hint))
    problem?.let { HText(formProblemText(it), style = Type.small, color = HN.amber) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(
            stringResource(Str.sf_add),
            {
                change(items + adding)
                form.adding.remove(row.key)
            },
            kind = PillKind.Outline,
            height = 40.dp,
            enabled = adding.isNotEmpty() && SettingsFormLogic.itemProblem(row.rules, adding) == null,
        )
        Pill(stringResource(Str.raw_save), { form.lists[row.key]?.let { form.save(row, it) } }, kind = PillKind.Primary, height = 40.dp, enabled = form.lists[row.key] != null && problem == null && form.saving == null)
    }
}

/** The JSON tab's parser and folding, for one key's value: what's saved is the rows joined, whatever's folded. */
@Composable
private fun JsonControl(row: FormRow, form: SettingsFormState) {
    val fresh = remember(row.key, row.json) { form.freshDraft(row) }
    val draft = form.jsons[row.key] ?: fresh
    val text = draft.text
    val stops = remember(text) { JsonFold.parse(text) as? JsonFold.Parsed.Bad }
    if (row.hidden > 0) form.current?.let { HText(stringResource(Str.sf_hidden_json, it.placeholder), style = Type.small, color = HN.inkDim) }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            draft.rows.forEachIndexed { i, r ->
                key(r.region?.path ?: "text-$i") {
                    JsonRow(
                        r,
                        onEdit = { typed -> form.editJson(row.key, draft, i, typed) },
                        onFold = { path -> form.refold(row.key, draft, if (path in draft.folded) draft.folded - path else draft.folded + path) },
                    )
                }
            }
        }
    }
    draft.plainBecause?.let { HText(stringResource(Str.raw_no_fold, it.line, it.column), style = Type.small, color = HN.amber) }
        ?: stops?.let { HText(stringResource(Str.raw_bad_at, it.line, it.column), style = Type.small, color = HN.amber) }
    Pill(stringResource(Str.raw_save), { form.saveJson(row, JsonRows.join(draft.rows)) }, kind = PillKind.Primary, height = 40.dp, enabled = draft.changed && stops == null && form.saving == null)
}
