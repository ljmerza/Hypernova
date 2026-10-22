package com.prudnikweb.hypernova.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.CodexModel
import com.prudnikweb.hypernova.data.ModelNames
import com.prudnikweb.hypernova.data.pick
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.components.TagChip
import com.prudnikweb.hypernova.ui.session.ChatSheet
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

// Codex's choices (docs/PROTOCOL.md, "Codex (a second agent)"; data/Codex.kt): which agent, and
// Codex's own models with their reasoning efforts. The new-session sheet, a session's panel, the
// switch sheet and an automation's start step share them.

/** Which agent: "Claude Code | Codex", the house segmented choice. */
@Composable
internal fun AgentChoice(agent: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Segmented(
        listOf(AgentKind.CLAUDE to stringResource(R.string.cx_agent_claude), AgentKind.CODEX to stringResource(R.string.cx_agent_codex)),
        if (agent == AgentKind.CODEX) AgentKind.CODEX else AgentKind.CLAUDE,
        onChange,
        modifier,
    )
}

/** A reasoning effort as the pickers say it ("Low", "Extra high"); one this build doesn't know, as Codex calls it. */
@Composable
internal fun effortTitle(effort: String): String = when (effort) {
    "none" -> stringResource(R.string.cx_effort_none)
    "minimal" -> stringResource(R.string.cx_effort_minimal)
    "low" -> stringResource(R.string.cx_effort_low)
    "medium" -> stringResource(R.string.cx_effort_medium)
    "high" -> stringResource(R.string.cx_effort_high)
    "xhigh" -> stringResource(R.string.cx_effort_xhigh)
    else -> effort.replaceFirstChar { it.uppercase() }
}

/** Codex's model [value] (null: its default) by its name, as a chip says it; [fallback] while the list isn't here. */
internal fun codexModelName(models: List<CodexModel>?, value: String?, fallback: String): String =
    models?.pick(value)?.name ?: value?.let(ModelNames::chip) ?: fallback

/**
 * Codex's models as rows (the chosen one marked, its default with a DEFAULT chip, what each is for
 * under its name). [withEffort]: the chosen model's reasoning efforts under them as a segmented
 * choice, and Done takes both ([onDone] gets the model and the effort; null for the defaults);
 * without (an automation's start step has no effort), a tap on a row is the choice. [models] null:
 * still coming; empty: only Codex's default, with [note] saying why.
 */
@Composable
internal fun CodexModelSheet(
    models: List<CodexModel>?,
    note: String?,
    model: String?,
    effort: String?,
    withEffort: Boolean,
    onDone: (model: String?, effort: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(R.string.model)
    var m by rememberSaveable { mutableStateOf(model) }
    var e by rememberSaveable { mutableStateOf(effort) }
    val list = models.orEmpty()
    val chosen = list.pick(m)
    ChatSheet(title, onDismiss) {
        HText(title, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(4.dp))
        HText(stringResource(R.string.cx_models_line), style = Type.small, color = HN.inkDim)
        Spacer(Modifier.height(10.dp))
        when {
            models == null -> LoadingLine(stringResource(R.string.cx_loading_models), Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
            list.isEmpty() -> {
                // Nothing listed (Codex is off until its first session, or the Mac couldn't ask): its default.
                CodexModelRow(stringResource(R.string.model_label_default), note, isDefault = false, selected = true) { if (!withEffort) onDone(null, null) }
            }
            else -> for (o in list) {
                CodexModelRow(o.name, o.description, o.isDefault, selected = o == chosen) {
                    if (!withEffort) onDone(o.value, null)
                    else {
                        m = o.value
                        // An effort the new model doesn't offer goes back to its own default.
                        if (e != null && e !in o.efforts) e = null
                    }
                }
            }
        }
        if (withEffort) {
            val efforts = chosen?.efforts.orEmpty()
            if (efforts.size > 1) {
                PartHeader(stringResource(R.string.cx_effort))
                Segmented(efforts.map { it to effortTitle(it) }, e ?: chosen?.defaultEffort ?: efforts.first(), { e = it })
            }
            Spacer(Modifier.height(16.dp))
            Pill(stringResource(R.string.cx_done), { onDone(m, e) }, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 44.dp)
        }
    }
}

/** One of Codex's models: its name (its default with a chip), and what it's for under it. */
@Composable
private fun CodexModelRow(name: String, sub: String?, isDefault: Boolean, selected: Boolean, onClick: () -> Unit) {
    val res = LocalContext.current.resources
    val said = listOfNotNull(res.getString(R.string.ns_row_said, name), sub).joinToString(" ")
    Row(Modifier.pickRow(selected, said, onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clearAndSetSemantics { }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (isDefault) {
                    Spacer(Modifier.width(8.dp))
                    TagChip(stringResource(R.string.model_label_default), compact = true)
                }
            }
            if (sub != null) HText(sub, style = Type.small, color = HN.inkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        ChosenMark(selected)
    }
}
