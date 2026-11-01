package com.prudnikweb.hypernova.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/** A state's word in the filter sheet and on the chip: the owner's words, "Needs you", "Working", "Completed". */
@Composable
internal fun filterGroupWord(g: HomeGroup): String = stringResource(
    when (g) {
        HomeGroup.NeedsInput -> R.string.filter_needs_you
        HomeGroup.Working -> R.string.filter_working
        HomeGroup.Completed -> R.string.filter_completed
    },
)

@Composable
internal fun filterAgentWord(a: FilterAgent): String = stringResource(
    when (a) {
        FilterAgent.Claude -> R.string.filter_agent_claude
        FilterAgent.Codex -> R.string.filter_agent_codex
    },
)

/**
 * Home's filter sheet (owner, 2026-11-01; docs/DECISIONS.md, "Home filters"), opened by the filter
 * button in Home's top bar, in the switcher's glass (ServerSwitcher.kt). "Active: Needs you +
 * Working" in one tap, then STATE (each of Home's three groups on or off; never all off), AGENT
 * ([agents]: only while both agents are in use, or one is chosen), PROJECT ([projects]: only with two
 * or more, or one chosen) and SERVER ([servers], id and name: only with "All servers"), each All or
 * one. Every tap applies at once; Clear shows everything again ([onClear]: the sky's filter too).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeFilterSheet(
    filter: HomeFilter,
    onChange: (HomeFilter) -> Unit,
    onClear: () -> Unit,
    projects: List<String>,
    agents: Boolean,
    servers: List<Pair<String, String>>?,
    onDismiss: () -> Unit,
) {
    val title = stringResource(R.string.filter_title)
    GlassSheet(title, onDismiss) {
        HText(title.uppercase(), style = Type.label, color = HN.inkDim)
        Spacer(Modifier.height(10.dp))
        FilterChoice(stringResource(R.string.filter_active), filter.active, check = true) { onChange(filter.toggleActive()) }
        Section(stringResource(R.string.filter_state)) {
            for (g in HomeGroup.entries) {
                FilterChoice(filterGroupWord(g), g in filter.shownGroups, check = true) { onChange(filter.toggle(g)) }
            }
        }
        if (agents || filter.agent != null) {
            Section(stringResource(R.string.filter_agent)) {
                FilterChoice(stringResource(R.string.filter_all), filter.agent == null) { onChange(filter.copy(agent = null)) }
                for (a in FilterAgent.entries) {
                    FilterChoice(filterAgentWord(a), filter.agent == a) { onChange(filter.copy(agent = a)) }
                }
            }
        }
        if (projects.size > 1 || filter.project != null) {
            Section(stringResource(R.string.filter_project)) {
                FilterChoice(stringResource(R.string.filter_all), filter.project == null) { onChange(filter.copy(project = null)) }
                for (p in projects) {
                    FilterChoice(p, filter.project == p) { onChange(filter.copy(project = p)) }
                }
            }
        }
        if (servers != null) {
            Section(stringResource(R.string.filter_server)) {
                FilterChoice(stringResource(R.string.filter_all), filter.server == null) { onChange(filter.copy(server = null)) }
                for ((id, name) in servers) {
                    FilterChoice(name, filter.server == id) { onChange(filter.copy(server = id)) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Pill(stringResource(R.string.filter_clear), onClear, kind = PillKind.Outline, height = 44.dp, enabled = filter.on)
            Spacer(Modifier.weight(1f))
            Pill(stringResource(R.string.filter_done), onDismiss, kind = PillKind.Primary, height = 44.dp)
        }
    }
}

/** A heading in the sheet's label style, and its choices wrapping under it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.Section(label: String, choices: @Composable () -> Unit) {
    Spacer(Modifier.height(14.dp))
    HText(label.uppercase(), style = Type.label, color = HN.inkDim)
    Spacer(Modifier.height(2.dp))
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { choices() }
}

/**
 * One choice: a glass capsule (glassChoice, lit when on) in a 48dp target. [check]: a switch of its
 * own (a state, the preset), with a tick while on; else one of a row, All or a single pick.
 */
@Composable
private fun FilterChoice(text: String, on: Boolean, check: Boolean = false, onClick: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val tap = {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onClick()
    }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .then(
                if (check) Modifier.toggleable(on, interaction, indication = null, role = Role.Checkbox) { tap() }
                else Modifier.selectable(on, interaction, indication = null, role = Role.RadioButton) { tap() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.clip(CircleShape).glassChoice(on).heightIn(min = 36.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (check && on) {
                Icon(Glyph.Check, size = 12.dp, color = HN.accent, stroke = 1.6.dp)
                Spacer(Modifier.width(6.dp))
            }
            HText(text, style = Type.small, color = if (on) HN.accent else HN.inkMuted, maxLines = 1)
        }
    }
}
