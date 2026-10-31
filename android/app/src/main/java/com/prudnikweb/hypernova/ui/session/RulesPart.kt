package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.PermissionRules
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.home.modeLabel
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * The Permissions tab's rules in effect (IDEAS SS5; docs/DECISIONS.md, "Rules in effect"):
 * Claude Code's allow, ask and deny lists for the session, read-only, so the owner can see why a
 * call asked or didn't. One seam with the counts, folded; open, a line on how they weigh, then
 * each source in Claude Code's precedence order (managed, this session, project local, project,
 * user) as a row with its counts, which opens to its rules in mono. A file that couldn't be read
 * says so on its row. Read from the Mac each time the tab opens. Not for Codex (its sandbox decides).
 */
@Composable
internal fun RulesPart(hub: Hub, s: Session) {
    var rules by remember(s.id) { mutableStateOf<PermissionRules?>(null) }
    var error by remember(s.id) { mutableStateOf<String?>(null) }
    var reload by remember(s.id) { mutableIntStateOf(0) }
    var fold by remember(s.id) { mutableStateOf(RulesFold()) }
    LaunchedEffect(s.id, reload) {
        hub.permissionRules(s.id)
            .onSuccess { rules = it; error = null }
            .onFailure { error = it.message.orEmpty() }
    }
    val view = rules?.let { rulesView(it, s.cwd) }
    val title = stringResource(R.string.rules_seam)
    val said = view?.let { stringResource(R.string.rules_said, title, it.counts.deny, it.counts.ask, it.counts.allow) } ?: title
    Seam(
        title,
        Modifier
            .padding(top = 18.dp, bottom = 10.dp)
            .heightIn(min = 32.dp)
            .clickable(
                remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                onClickLabel = stringResource(if (fold.open) R.string.rules_close else R.string.rules_open),
            ) { fold = fold.toggled() }
            .semantics(mergeDescendants = true) { contentDescription = said },
        trailContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (view != null) {
                    if (view.none) HText(stringResource(R.string.rules_none).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                    else Counts(view.counts)
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Glyph.Chevron, Modifier.rotate(if (fold.open) 90f else 0f), size = 10.dp, color = HN.inkDim)
            }
        },
    )
    if (!fold.open) return
    when {
        view == null && error != null -> Column(Modifier.padding(start = 4.dp)) {
            HText(error.orEmpty(), style = Type.small, color = HN.danger)
            Spacer(Modifier.height(10.dp))
            Pill(stringResource(R.string.try_again), { error = null; reload++ }, kind = PillKind.Outline, height = 44.dp)
        }
        view == null -> LoadingLine(stringResource(R.string.rules_loading), Modifier.padding(start = 4.dp))
        else -> {
            if (view.none) HText(stringResource(R.string.rules_none), style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            else HText(stringResource(R.string.rules_precedence), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            if (view.managedOnly) HText(stringResource(R.string.rules_managed_only), style = Type.small, color = HN.amber, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            for (g in view.groups) key(g.key) {
                SourceRow(g, open = fold.shows(g.key), onToggle = { fold = fold.toggled(g.key) })
            }
            if (view.missing.isNotEmpty()) {
                val names = view.missing.map { stringResource(sourceLabel(it.source)) }.distinct().joinToString(", ")
                HText(stringResource(R.string.rules_missing, names), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            }
        }
    }
}

/** Deny, ask and allow counts, each in its colour; a kind with none isn't shown. */
@Composable
private fun Counts(c: RuleCounts) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        var first = true
        for ((n, label, color) in listOf(Triple(c.deny, R.string.rules_kind_deny, HN.danger), Triple(c.ask, R.string.rules_kind_ask, HN.amber), Triple(c.allow, R.string.rules_kind_allow, HN.accent))) {
            if (n == 0) continue
            if (!first) Spacer(Modifier.width(8.dp))
            first = false
            HText("$n ${stringResource(label).uppercase()}", style = Type.monoSmall, color = color, maxLines = 1)
        }
    }
}

/** A source: its name and file (or why it has nothing), its counts, then its rules once opened. */
@Composable
private fun SourceRow(g: RuleGroup, open: Boolean, onToggle: () -> Unit) {
    val name = stringResource(sourceLabel(g.source.source))
    val problem = stateLabel(g.source.state)?.let { stringResource(it) }
    val path = g.path ?: stringResource(R.string.rules_session_path)
    val said = stringResource(R.string.rules_source_said, name, problem ?: stringResource(R.string.rules_said, path, g.counts.deny, g.counts.ask, g.counts.allow))
    Column(Modifier.fillMaxWidth().alpha(if (g.inEffect) 1f else 0.55f)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (g.expandable) Modifier.clickable(
                        remember { MutableInteractionSource() }, indication = null, role = Role.Button,
                        onClickLabel = stringResource(if (open) R.string.collapse else R.string.show_all), onClick = onToggle,
                    ) else Modifier,
                )
                .clearAndSetSemantics { contentDescription = said }
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (g.expandable) Icon(Glyph.Chevron, Modifier.rotate(if (open) 90f else 0f), size = 10.dp, color = HN.accentDim)
            else Spacer(Modifier.width(10.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                HText(name, style = Type.small, color = HN.ink, maxLines = 1)
                HText(path, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
            Spacer(Modifier.width(10.dp))
            if (problem != null) HText(problem, style = Type.monoSmall, color = HN.amber, maxLines = 1)
            else Counts(g.counts)
        }
        if (open) {
            Column(Modifier.padding(start = 22.dp, end = 4.dp, bottom = 8.dp)) {
                if (!g.inEffect) Note(stringResource(R.string.rules_not_in_effect), HN.amber)
                Rules(R.string.rules_kind_deny, g.source.deny, HN.danger)
                Rules(R.string.rules_kind_ask, g.source.ask, HN.amber)
                Rules(R.string.rules_kind_allow, g.source.allow, HN.accent)
                g.source.defaultMode?.let { Note(stringResource(R.string.rules_default_mode, modeLabel(it)), HN.inkMuted) }
                if (g.source.additionalDirectories.isNotEmpty()) Rules(R.string.rules_dirs, g.source.additionalDirectories, HN.inkMuted, caps = false)
                if (g.source.clipped) Note(stringResource(R.string.rules_clipped), HN.inkDim)
            }
        }
    }
}

/** One kind's rules under its label, each on its own line in mono, selectable. */
@Composable
private fun Rules(label: Int, rules: List<String>, color: Color, caps: Boolean = true) {
    if (rules.isEmpty()) return
    val words = stringResource(label)
    HText(if (caps) words.uppercase() else words, style = Type.label, color = color, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
    SelectionContainer {
        Column {
            for (r in rules) HText(r, style = Type.monoSmall, color = HN.ink, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun Note(text: String, color: Color) {
    HText(text, style = Type.small, color = color, modifier = Modifier.padding(top = 6.dp))
}
