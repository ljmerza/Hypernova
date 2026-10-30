package com.prudnikweb.hypernova.ui.session

import android.content.res.Resources
import com.prudnikweb.hypernova.ui.components.glassChoice
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.UsageProjection
import com.prudnikweb.hypernova.ui.components.ProjectionMeter
import com.prudnikweb.hypernova.data.ModelNames
import com.prudnikweb.hypernova.ui.components.FitRow
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.ModelChip
import com.prudnikweb.hypernova.ui.components.ProjectionMeterCompact
import com.prudnikweb.hypernova.ui.components.projectionSaid
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.InfoRow
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.fx.formatDuration
import com.prudnikweb.hypernova.ui.stats.compact
import com.prudnikweb.hypernova.ui.stats.grouped
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

/** Context this full or more: the strip offers Compact. */
const val COMPACT_AT = 85

/**
 * The session's usage, asked of the Mac when the screen opens and again a moment after the
 * transcript settles ([key] changes with every new line), so a reply streaming in costs one
 * request, not one per word. The last answer stays while a new one is on its way or fails.
 */
@Composable
internal fun rememberSessionUsage(hub: Hub, sessionId: String, key: String): SessionUsage? {
    val k by rememberUpdatedState(key)
    val usage by produceState<SessionUsage?>(null, sessionId) {
        var first = true
        snapshotFlow { k }.collectLatest {
            if (!first) delay(1500)
            first = false
            ChatRepo.usage(hub, sessionId).onSuccess { u -> value = u }
        }
    }
    return usage
}

/**
 * The slim glass strip at the top of the chat: how full the context is (a ring and its share),
 * the model, what the session has cost and used, and the plan's 5-hour window as a compact
 * projection meter (ui/components/ProjectionMeter.kt: what's used, where the pace leads, one tag).
 * "≈" marks what's worked out rather than Claude Code's own number. From [COMPACT_AT]% a Compact
 * chip offers /compact; a tap on the strip opens the session sheet ([onOpen], SessionSheet.kt).
 * TalkBack hears [more] after its own sentences: what the header's line used to say (the agents at
 * work, the queue, the mode).
 */
@Composable
internal fun UsageStrip(hub: Hub, session: Session, usage: SessionUsage, onCompact: () -> Unit, onOpen: () -> Unit, more: String, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val pct = usage.contextPct
    val builtIn = builtIn(hub, session)
    val u = hub.usage
    // Moves when "resets in" would read differently, in step with Home's countdowns (ui/fx/Countdown.kt).
    val now = com.prudnikweb.hypernova.ui.fx.rememberCountdownNow(u.fiveHourResetAt)
    val showFive = builtIn && u.known && !u.stale
    val five = if (showFive) UsageProjection.fiveHour(u, now) else null
    val said = stripDescription(res, usage, session, five) + more
    val shape = RoundedCornerShape(12.dp)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 40.dp)
                .clip(shape)
                .glassChoice(false, 12.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.session_sheet_open), onClick = onOpen)
                .clearAndSetSemantics { contentDescription = said }
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ContextRing(pct, size = 16.dp)
            Spacer(Modifier.width(7.dp))
            HText(
                pct?.let { (if (usage.contextExact) "" else "≈") + res.getString(R.string.percent, it) } ?: "—",
                style = Type.monoSmall, color = ringColor(pct), maxLines = 1,
            )
            Spacer(Modifier.width(9.dp))
            // The model as a chip, the cost and the tokens as figures after their glyphs (drawn,
            // not "Opus 5.5 · $0.64 · 7.4M tok"). Whole parts give way when the row is too narrow
            // (the Compact chip, a small phone), least useful first: the chip's version ("OPUS"),
            // then the tokens, then the chip; the cost last.
            FitRow(stripParts(res, usage, session), giveWay = intArrayOf(0, 2, 0, 1), modifier = Modifier.weight(1f), gap = 6.dp)
            // The plan's 5-hour window: drawn, not worded (its words are TalkBack's).
            if (five != null) {
                Spacer(Modifier.width(8.dp))
                ProjectionMeterCompact(stringResource(R.string.usage_five_short), five, barWidth = 32.dp)
            }
        }
        // /compact is Claude Code's: a Codex session has no Compact.
        if (pct != null && pct >= COMPACT_AT && !session.codex) {
            Spacer(Modifier.width(8.dp))
            CompactChip(onCompact)
        }
    }
}

/** The session runs on the subscription's own provider: the plan's windows are about it. */
internal fun builtIn(hub: Hub, session: Session): Boolean =
    hub.providers.firstOrNull { it.id == (session.providerId ?: "anthropic") }?.builtIn ?: (session.providerId == null || session.providerId == "anthropic")

/**
 * The strip's parts after the ring, for [FitRow]: the model's chip (whole, then just its family),
 * the cost after a banknote and the tokens after a token (the 5-hour window is the meter after
 * them). A session that hasn't spent anything yet (a rewind's new one) has no cost or tokens.
 */
private fun stripParts(res: Resources, usage: SessionUsage, session: Session): List<List<@Composable () -> Unit>> {
    val name = stripModel(usage, session.model, res.getString(R.string.model_default))
    val model = ModelNames.chip(name)
    val family = ModelNames.family(name)
    val chips = buildList<@Composable () -> Unit> {
        add { ModelChip(model) }
        if (family != null) add { ModelChip(family) }
    }
    val cost = usage.costUsd?.takeIf { usage.tokens > 0 }?.let { c -> (if (usage.costExact) "" else "≈") + money(c) }
    val costPart = if (cost != null) listOf<@Composable () -> Unit> { GlyphFigure(Glyph.Note, cost) } else emptyList()
    val tokens = compact(res, usage.tokens)
    val tokensPart = if (usage.tokens > 0) listOf<@Composable () -> Unit> { GlyphFigure(Glyph.Token, tokens) } else emptyList()
    return listOf(chips, costPart, tokensPart)
}

/**
 * The model the strip names: the usage's own name while it is about the model the session runs
 * now, else the session's, which follows a switch at once (/model in its terminal, the chat's
 * Model row) while the usage is asked for again. [unknown]: the session's word for a model nobody
 * has named yet.
 */
internal fun stripModel(usage: SessionUsage, sessionModel: String, unknown: String): String {
    val own = usage.modelName ?: usage.model ?: return sessionModel
    return if (sessionModel == unknown || usage.model == null || ModelNames.same(usage.model, sessionModel)) own else sessionModel
}

/** "Compact": sends /compact, so Claude Code folds the conversation into a summary and carries on. */
@Composable
private fun CompactChip(onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clip(CircleShape)
                .glassChoice(true, color = HN.amber)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HText(stringResource(R.string.chat_compact), style = Type.small.copy(fontWeight = FontWeight(600)), color = HN.amber, maxLines = 1)
        }
    }
}

/** Mint while there's room, amber from 70%, red when it's nearly full. */
private fun ringColor(pct: Int?): Color = when {
    pct == null -> HN.inkDim
    pct >= 95 -> HN.danger
    pct >= 70 -> HN.amber
    else -> HN.accent
}

/** How full the context window is: a hairline track and an arc that fills clockwise from the top. */
@Composable
internal fun ContextRing(pct: Int?, size: Dp, stroke: Dp = 2.dp) {
    val v by animateFloatAsState((pct ?: 0) / 100f, tween(Motion.SECTION, easing = Motion.Expo), label = "ring")
    val color = ringColor(pct)
    Canvas(Modifier.size(size)) {
        val w = stroke.toPx()
        val d = this.size.minDimension - w
        val tl = Offset(w / 2, w / 2)
        drawArc(HN.lineStrong, 0f, 360f, false, tl, Size(d, d), style = Stroke(w))
        if (pct != null && v > 0f) drawArc(color, -90f, 360f * v.coerceIn(0.02f, 1f), false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** "$0.64", "$7.86", "$142". */
internal fun money(usd: Double): String = when {
    usd < 0.01 && usd > 0 -> "<$0.01"
    usd < 100 -> String.format(Locale.ENGLISH, "$%.2f", usd)
    else -> String.format(Locale.ENGLISH, "$%,.0f", usd)
}

private fun stripDescription(res: Resources, u: SessionUsage, s: Session, five: UsageProjection?): String = buildString {
    append(res.getString(R.string.meta_model_said, stripModel(u, s.model, res.getString(R.string.model_default))))
    append(' ')
    val pct = u.contextPct
    append(
        when {
            pct == null -> res.getString(R.string.chat_context_unknown_said, com.prudnikweb.hypernova.ui.components.agentName(res, s.codex))
            u.contextExact -> res.getString(R.string.chat_context_said, pct)
            else -> res.getString(R.string.chat_context_about_said, pct)
        },
    )
    append(' ')
    u.costUsd?.let { append(res.getString(if (u.costExact) R.string.chat_cost_said else R.string.chat_cost_about_said, money(it))); append(' ') }
    append(res.getString(R.string.chat_tokens_said, grouped(u.tokens)))
    if (five != null) {
        append(' ')
        append(projectionSaid(res, res.getString(R.string.usage_name_five), five))
    }
}

/**
 * The model's full id when it says more than its chip (not "Opus 5.5 · claude-opus-5-5"): the
 * usage's while it is about the model the session runs, else the session's. Null when the chip is it.
 */
internal fun modelId(u: SessionUsage?, sessionModel: String, unknown: String): String? {
    val name = if (u == null) sessionModel else stripModel(u, sessionModel, unknown)
    return (if (name == sessionModel) sessionModel else u?.model ?: sessionModel).takeIf { ModelNames.chip(it) != it }
}

/**
 * The strip's breakdown, the session sheet's Usage part: the context in tokens, the tokens by
 * kind, what the cost is and isn't, and the plan's windows. Each estimate says so, in words. (The
 * model is the sheet's Model part.)
 */
@Composable
internal fun UsageDetails(hub: Hub, u: SessionUsage, s: Session, builtIn: Boolean, onCompact: () -> Unit) {
    val res = LocalContext.current.resources
    // The plan's per-model weeks below are read off /usage every 15 minutes: a fresh look now (data/UsageRefresh.kt).
    LaunchedEffect(builtIn) { if (builtIn) com.prudnikweb.hypernova.data.UsageRefresh.ask(hub) }
    // Context
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(contentAlignment = Alignment.Center) {
            ContextRing(u.contextPct, size = 56.dp, stroke = 3.dp)
            HText(u.contextPct?.let { res.getString(R.string.percent, it) } ?: "—", style = Type.mono, color = HN.ink)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            HText(stringResource(R.string.chat_context_label).uppercase(), style = Type.label, color = HN.inkDim)
            Spacer(Modifier.height(4.dp))
            val used = u.contextUsed
            val window = u.contextWindow
            HText(
                if (used != null && window != null) stringResource(if (u.contextExact) R.string.chat_context_of else R.string.chat_context_of_about, grouped(used), grouped(window))
                else stringResource(R.string.chat_context_unknown, com.prudnikweb.hypernova.ui.components.agentName(s.codex)),
                style = Type.small, color = HN.ink,
            )
            // Claude Code's status line makes it exact; Codex has none, so no promise of it there.
            if (used != null && !u.contextExact && !s.codex) {
                Spacer(Modifier.height(2.dp))
                HText(stringResource(R.string.chat_context_about_note), style = Type.small, color = HN.inkDim)
            }
        }
    }
    if ((u.contextPct ?: 0) >= 50 && !s.codex) {
        Spacer(Modifier.height(12.dp))
        Pill(stringResource(R.string.chat_compact_now), onCompact, Modifier.fillMaxWidth(), kind = if ((u.contextPct ?: 0) >= COMPACT_AT) PillKind.Primary else PillKind.Outline, height = 44.dp)
        Spacer(Modifier.height(4.dp))
        HText(stringResource(R.string.chat_compact_note), style = Type.small, color = HN.inkDim)
    }
    Spacer(Modifier.height(16.dp))
    Hairline(Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))

    // Tokens
    HText(stringResource(R.string.chat_tokens_label).uppercase(), style = Type.label, color = HN.inkDim)
    Spacer(Modifier.height(4.dp))
    InfoRow(stringResource(R.string.chat_tokens_input), grouped(u.input))
    InfoRow(stringResource(R.string.chat_tokens_output), grouped(u.output))
    InfoRow(stringResource(R.string.chat_tokens_cache_read), grouped(u.cacheRead))
    InfoRow(stringResource(R.string.chat_tokens_cache_write), grouped(u.cacheWrite))
    InfoRow(stringResource(R.string.chat_tokens_total), grouped(u.tokens), valueColor = HN.ink)
    if (u.agentTokens > 0) HText(stringResource(R.string.chat_tokens_agents, grouped(u.agentTokens)), style = Type.small, color = HN.inkDim)
    Spacer(Modifier.height(14.dp))
    Hairline(Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))

    // Cost
    HText(stringResource(R.string.chat_cost_label).uppercase(), style = Type.label, color = HN.inkDim)
    Spacer(Modifier.height(6.dp))
    val provider = hub.providers.firstOrNull { it.id == (s.providerId ?: "anthropic") }
    val c = u.costUsd
    if (c == null) {
        HText(stringResource(R.string.chat_cost_none, provider?.name ?: s.model), style = Type.small, color = HN.inkMuted)
    } else {
        HText((if (u.costExact) "" else stringResource(R.string.chat_about) + " ") + money(c), style = Type.title, color = HN.ink)
        Spacer(Modifier.height(4.dp))
        HText(stringResource(if (u.costExact) R.string.chat_cost_exact_note else R.string.chat_cost_about_note), style = Type.small, color = HN.inkDim)
        if (builtIn) {
            Spacer(Modifier.height(2.dp))
            HText(stringResource(R.string.chat_cost_plan_note), style = Type.small, color = HN.inkDim)
        }
    }

    // The plan's windows, as on Home.
    if (builtIn && hub.usage.known && !hub.usage.stale) {
        Spacer(Modifier.height(14.dp))
        Hairline(Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        val usage = hub.usage
        val now = com.prudnikweb.hypernova.ui.fx.rememberCountdownNow(usage.fiveHourResetAt, usage.sevenDayResetAt)
        val five = UsageProjection.fiveHour(usage, now)
        ProjectionMeter(stringResource(R.string.five_hours), five, projectionSaid(stringResource(R.string.usage_name_five), five))
        Spacer(Modifier.height(12.dp))
        val seven = UsageProjection.sevenDay(usage, now)
        ProjectionMeter(stringResource(R.string.seven_days), seven, projectionSaid(stringResource(R.string.usage_name_seven), seven))
        com.prudnikweb.hypernova.ui.home.ModelWeekMeters(usage.models, now, gap = 12.dp)
        Spacer(Modifier.height(6.dp))
        HText(stringResource(R.string.chat_plan_note), style = Type.small, color = HN.inkDim)
    }
}
