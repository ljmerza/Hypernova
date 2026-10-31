package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AlwaysDraft
import com.prudnikweb.hypernova.data.AlwaysScope
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.FieldLabel
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

// The Always sheet (IDEAS PA2, PA3; docs/PROTOCOL.md, "Approvals"): opened by the approval card's
// own Always, so the card in the chat stays as it was. Claude Code's rule, editable, with quick ways
// to widen it, and where it goes: this session, this project (its local settings file) or
// everywhere. Claude Code saves it itself; the fingerprint comes after Always allow.

/** The scope's words on the sheet and in the fingerprint prompt. */
@Composable
internal fun alwaysScopeTitle(s: AlwaysScope): String = stringResource(
    when (s) {
        AlwaysScope.Session -> R.string.always_scope_session
        AlwaysScope.Project -> R.string.always_scope_project
        AlwaysScope.Everywhere -> R.string.always_scope_everywhere
    },
)

/** The fingerprint prompt's line: the rules and where (`Bash(npm test *), in this project`). */
internal fun alwaysCheckLine(res: android.content.res.Resources, a: Answer.AlwaysAs): String = res.getString(
    when (a.scope) {
        AlwaysScope.Session -> R.string.always_check_session
        AlwaysScope.Project -> R.string.always_check_project
        AlwaysScope.Everywhere -> R.string.always_check_everywhere
    },
    a.rules.joinToString(", "),
)

/**
 * Claude Code's rules for [item] ([Item.Approval.alwaysRules]), editable, and where they go. Always
 * allow hands [onSave] the answer; the caller asks for the fingerprint, then answers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlwaysSheet(item: Item.Approval, onDismiss: () -> Unit, onSave: (Answer.AlwaysAs) -> Unit) {
    var draft by remember(item.key) { mutableStateOf(AlwaysDraft.of(item.alwaysRules, item.alwaysScope)) }
    val title = stringResource(R.string.check_always_title)
    GlassSheet(title, onDismiss) {
        HText(title, style = Type.heading, color = HN.ink)
        Spacer(Modifier.height(6.dp))
        HText(
            (if (item.tool == "Bash") "$ " else "${item.tool}: ") + item.detail.lineSequence().firstOrNull().orEmpty(),
            style = Type.mono, color = HN.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        FieldLabel(stringResource(if (draft.texts.size > 1) R.string.always_rules else R.string.always_rule))
        val parsed = draft.parsed
        draft.texts.forEachIndexed { i, text ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            val rule = parsed[i]
            Field(
                text, { draft = draft.edit(i, it) },
                mono = true, error = rule == null,
                label = stringResource(R.string.always_rule),
                keyboard = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
            )
            if (rule == null) {
                Spacer(Modifier.height(6.dp))
                HText(stringResource(R.string.always_bad_rule), style = Type.small, color = HN.danger)
            } else if (rule.content == null) {
                Spacer(Modifier.height(6.dp))
                HText(stringResource(R.string.always_whole_tool, rule.tool), style = Type.small, color = HN.amber)
            }
            // Quick ways to widen it, and back to Claude Code's own once changed.
            val back = draft.suggestedAt(i)
            val wider = rule?.widenings().orEmpty()
            if (back != null || wider.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (w in wider) RuleChip(w.toString()) { draft = draft.set(i, w) }
                    if (back != null) RuleChip(stringResource(R.string.always_suggested), quiet = true) { draft = draft.edit(i, back) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        FieldLabel(stringResource(R.string.always_where))
        Segmented(AlwaysScope.entries.map { it to alwaysScopeTitle(it) }, draft.scope, { draft = draft.copy(scope = it) })
        Spacer(Modifier.height(8.dp))
        HText(
            stringResource(
                when (draft.scope) {
                    AlwaysScope.Session -> R.string.always_scope_session_body
                    AlwaysScope.Project -> R.string.always_scope_project_body
                    AlwaysScope.Everywhere -> R.string.always_scope_everywhere_body
                },
            ),
            style = Type.small, color = HN.inkDim,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Pill(stringResource(R.string.cancel), onDismiss, kind = PillKind.Ghost, height = 44.dp)
            Pill(stringResource(R.string.always_save), { onSave(Answer.AlwaysAs(draft.scope, draft.rules)) }, kind = PillKind.Primary, height = 44.dp, enabled = draft.valid)
        }
    }
}

/** A rule to widen to, in mono (or a quiet word), as a small capsule. */
@Composable
private fun RuleChip(text: String, quiet: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    HText(
        text, style = if (quiet) Type.small else Type.monoSmall, color = if (quiet) HN.inkMuted else HN.accent,
        maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(HN.ink.copy(alpha = 0.05f))
            .border(1.dp, HN.line, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}
