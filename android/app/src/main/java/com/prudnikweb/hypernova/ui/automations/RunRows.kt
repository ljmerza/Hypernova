package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.ui.components.GlyphFigure
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AutoMode
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.ui.components.frostedGlass
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import java.time.ZoneId

/** A run's drawn mark: a check, a dash, a cross, or the activity planet while it works or asks. */
@Composable
fun RunGlyph(tone: RunTone, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    when (tone) {
        RunTone.Active -> Planet(Activity.Live, modifier)
        RunTone.Alert -> Planet(Activity.Alert, modifier)
        RunTone.Ok -> Icon(Glyph.Check, modifier, size = size, color = HN.accentDim, stroke = 1.6.dp)
        // Not reached yet: a hollow ring, as the to-do panel's next items.
        RunTone.Pending -> Canvas(modifier.size(size)) { drawCircle(HN.inkGhost, this.size.minDimension * .3f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.3.dp.toPx())) }
        RunTone.Quiet -> Icon(Glyph.Check, modifier, size = size, color = HN.inkDim, stroke = 1.6.dp)
        RunTone.Failed -> Icon(Glyph.Cross, modifier, size = size, color = HN.danger, stroke = 1.6.dp)
        RunTone.Skipped, RunTone.Stopped -> {
            val c = if (tone == RunTone.Stopped) HN.amber else HN.inkDim
            Canvas(modifier.size(size)) {
                drawLine(c, Offset(size.toPx() * .25f, this.size.height / 2), Offset(size.toPx() * .75f, this.size.height / 2), 1.6.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/** The colour a run's words take. */
fun RunTone.color(): Color = when (this) {
    RunTone.Ok -> HN.inkMuted
    RunTone.Quiet, RunTone.Skipped, RunTone.Pending -> HN.inkDim
    RunTone.Failed -> HN.danger
    RunTone.Stopped, RunTone.Alert -> HN.amber
    RunTone.Active -> HN.accent
}

/**
 * A small capsule with no point of light: the AUTO chip on an automated session's card (accent-dim),
 * and a card's mode chip (amber for Auto, red for No asking).
 */
@Composable
fun OutlineChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .frostedGlass(null, glowX = 11.dp, tint = color)
            .heightIn(min = 22.dp)
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        HText(text.uppercase(), style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.75f), color = color, maxLines = 1)
    }
}

/** The AUTO chip on a session an automation started. */
@Composable
fun AutoChip(modifier: Modifier = Modifier) = OutlineChip(stringResource(R.string.au_session_chip), HN.accentDim, modifier)

/** A card's mode chip, for anything but the default "Allowed only"; nothing otherwise. */
@Composable
fun ModeChip(mode: String?, modifier: Modifier = Modifier) {
    when (mode) {
        AutoMode.ASK -> OutlineChip(stringResource(R.string.au_chip_asks), HN.inkMuted, modifier)
        // Codex's Can edit says so too (data/Codex.kt); its Read only, like Allowed only, says nothing.
        AutoMode.EDITS, com.prudnikweb.hypernova.data.AgentKind.CAN_EDIT -> OutlineChip(stringResource(R.string.au_chip_edits), HN.inkMuted, modifier)
        AutoMode.AUTO -> OutlineChip(stringResource(R.string.au_chip_auto), HN.amber, modifier)
        AutoMode.NO_ASKING -> OutlineChip(stringResource(R.string.au_chip_no_asking), HN.danger, modifier)
        else -> Unit
    }
}

/**
 * One run in the editor's RUNS: its mark, when it ran and for how long, then its result or why.
 * A tap opens its first session. A finished run that "Allowed only" held back offers each refused
 * rule ("Allow next time: Bash(npm test)"), a rule already allowed drawn as done ([allowed]); a run
 * still going has Stop, one that failed, stopped or was skipped Retry ([onRetry]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunRow(
    r: Run,
    w: Words,
    now: Long,
    zone: ZoneId,
    ask: String?,
    provider: String?,
    onOpen: (() -> Unit)?,
    onStop: () -> Unit,
    onAllow: (String) -> Unit,
    canAllow: Boolean = true,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    allowed: Set<String> = emptySet(),
    /** What a tap does, said (a workflow run's: "Its steps"); null: open its session. */
    openLabel: String? = null,
    /** What fired it, in a few words ("docs/plan.md and 2 more"). */
    detail: String? = null,
    /** Under the row: a workflow run's steps. */
    extra: (@Composable () -> Unit)? = null,
    /** Codex runs its session: its reasons say Codex. */
    codex: Boolean = false,
) {
    val line = RunWords.line(w, r, now, zone, ask, provider, codex)
    val ran = RunWords.ran(r, now)
    val at = RunWords.at(w, r.startedAt ?: r.firedAt, now, zone)
    val took = ran?.takeIf { r.state != Run.QUEUED }?.let { RunWords.duration(w, it) }
    val said = listOfNotNull(at, took?.let { stringResource(R.string.ran_for, it) }, line.text, line.late).joinToString(". ")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(
                    if (onOpen != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = openLabel ?: stringResource(R.string.au_open_run), onClick = onOpen)
                    else Modifier,
                )
                .semantics(mergeDescendants = true) { contentDescription = said }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 2.dp).size(16.dp), contentAlignment = Alignment.Center) { RunGlyph(line.tone) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                RunWhen(at, took)
                Spacer(Modifier.height(2.dp))
                HText(line.text, style = Type.small, color = line.tone.color(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (line.late != null) HText(line.late, style = Type.small, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail != null) HText(detail, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (r.active) {
                Spacer(Modifier.width(8.dp))
                Pill(stringResource(R.string.au_stop), onStop, kind = PillKind.Ghost, height = 32.dp)
            } else if (r.retriable && onRetry != null) {
                Spacer(Modifier.width(8.dp))
                Pill(stringResource(R.string.au_retry), onRetry, kind = PillKind.Outline, height = 32.dp, leading = Glyph.Reload)
            } else if (onOpen != null) {
                Spacer(Modifier.width(8.dp))
                Icon(Glyph.Chevron, Modifier.padding(top = 6.dp), size = 12.dp, color = HN.inkDim)
            }
        }
        if (extra != null) Box(Modifier.padding(start = 28.dp)) { extra() }
        if (canAllow && r.state == Run.DONE && r.skippedTools.isNotEmpty()) {
            FlowRow(
                Modifier.padding(start = 28.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                HText(stringResource(R.string.au_allow_next), style = Type.small, color = HN.inkDim)
                for (rule in r.skippedTools) {
                    if (rule in allowed) RuleChip(rule, stringResource(R.string.au_allowed_rule_said, rule), leading = Glyph.Check, onClick = null)
                    else RuleChip(rule, stringResource(R.string.au_allow_rule, rule), leading = Glyph.Plus, onClick = { onAllow(rule) })
                }
            }
        }
    }
}

/** When a run ran ("Today 9:00"), then how long after a stopwatch ("⏱ 2m 14s"), quiet: no "·" between. */
@Composable
fun RunWhen(at: String, took: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        HText(at, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        if (took != null) {
            Spacer(Modifier.width(12.dp))
            GlyphFigure(Glyph.Timer, took, color = HN.inkDim)
        }
    }
}

/** A Claude Code rule as a capsule in mono ("Bash(git log:*)"): tap to act on it ([onClick] null: done, said by [label]). */
@Composable
fun RuleChip(rule: String, label: String, leading: Glyph? = null, trailing: Glyph? = null, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .clip(CircleShape)
            .frostedGlass(null, glowX = 11.dp)
            .then(
                if (onClick != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
                else Modifier.semantics { contentDescription = label },
            )
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(leading, size = 10.dp, color = if (onClick == null) HN.accentDim else HN.accent)
            Spacer(Modifier.width(6.dp))
        }
        HText(rule, style = Type.monoSmall, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Icon(trailing, size = 10.dp, color = HN.inkDim)
        }
    }
}
