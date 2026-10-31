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
import androidx.compose.ui.res.pluralStringResource
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
import com.prudnikweb.hypernova.ui.components.StatusChip
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
internal fun rememberSessionUsage(sessionId: String, key: String): SessionUsage? {
    val k by rememberUpdatedState(key)
    val usage by produceState<SessionUsage?>(null, sessionId) {
        var first = true
        snapshotFlow { k }.collectLatest {
            if (!first) delay(1500)
            first = false
            ChatRepo.usage(sessionId).onSuccess { u -> value = u }
        }
    }
    return usage
}

/**
 * The slim glass strip at the top of the chat: how full the context is (a ring and its share),
 * the model, the tokens the session has used, and the plan's 5-hour and weekly windows as compact
 * projection meters (ui/components/ProjectionMeter.kt: what's used, where the pace leads, one tag).
 * No cost (owner, 2026-10-30). "≈" marks what's worked out rather than Claude Code's own number.
 * From [COMPACT_AT]% a Compact chip offers /compact; a tap on the strip opens the session sheet
 * ([onOpen], SessionSheet.kt). TalkBack hears [more] after its own sentences: what the header's
 * line used to say (the agents at work, the queue, the mode).
 */
@Composable
internal fun UsageStrip(hub: Hub, session: Session, usage: SessionUsage, onCompact: () -> Unit, onOpen: () -> Unit, more: String, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val pct = usage.contextPct
    val u = hub.usage
    // Moves when "resets in" would read differently, in step with Home's countdowns (ui/fx/Countdown.kt).
    val now = com.prudnikweb.hypernova.ui.fx.rememberCountdownNow(u.fiveHourResetAt, u.sevenDayResetAt)
    val showPlan = planShown(hub, session)
    val five = if (showPlan) UsageProjection.fiveHour(u, now) else null
    val seven = if (showPlan) UsageProjection.sevenDay(u, now) else null
    val said = stripDescription(res, usage, session, five, seven) + more
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
            // The model as a chip, the tokens as a figure after its glyph (drawn, not "Opus 5.5 ·
            // 7.4M tok"). Whole parts give way when the row is too narrow (the Compact chip, the
            // blocked mark, a small phone), least useful first: the chip's version ("OPUS"), then
            // the tokens, then the chip. They have what the windows leave.
            FitRow(stripParts(res, usage, session), giveWay = intArrayOf(0, 1, 0), modifier = Modifier.weight(1f), gap = 6.dp)
            // The plan's windows, 5 hours then the week: drawn, not worded (their words are
            // TalkBack's). They come before the chip and the tokens; short of room even so, they
            // give way too ([WINDOWS_GIVE_WAY]).
            if (five != null && seven != null) {
                Spacer(Modifier.width(8.dp))
                FitRow(windowParts(five, seven), giveWay = WINDOWS_GIVE_WAY, gap = 10.dp)
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
 * The strip's parts after the ring, for [FitRow]: the model's chip (whole, then just its family)
 * and the tokens after a token (the plan's windows are the meters after them). A session that
 * hasn't spent anything yet (a rewind's new one) has no tokens.
 */
private fun stripParts(res: Resources, usage: SessionUsage, session: Session): List<List<@Composable () -> Unit>> {
    val name = stripModel(usage, session.model, res.getString(R.string.model_default))
    val model = ModelNames.chip(name)
    val family = ModelNames.family(name)
    val chips = buildList<@Composable () -> Unit> {
        add { ModelChip(model) }
        if (family != null) add { ModelChip(family) }
    }
    val tokens = compact(res, usage.tokens)
    val tokensPart = if (usage.tokens > 0) listOf<@Composable () -> Unit> { GlyphFigure(Glyph.Token, tokens) } else emptyList()
    return listOf(chips, tokensPart)
}

/**
 * How the plan's windows give way, for [FitRow] over [windowParts] (0: the 5-hour window, 1: the
 * week): first the week's bar, then the 5-hour reset countdown, then the week. The 5-hour meter
 * stays.
 */
internal val WINDOWS_GIVE_WAY = intArrayOf(1, 0, 1)

/**
 * The strip's windows, for [FitRow]: the 5-hour meter (whole, then without its reset countdown),
 * then the week's ("7D", whole, then without its bar). The week's reset is days away, so it never
 * shows here; the time to either limit, in red, always does.
 */
@Composable
private fun windowParts(five: UsageProjection, seven: UsageProjection): List<List<@Composable () -> Unit>> {
    val fiveLabel = stringResource(R.string.usage_five_short)
    val sevenLabel = stringResource(R.string.usage_seven_short)
    return listOf(
        listOf(
            { ProjectionMeterCompact(fiveLabel, five, barWidth = 32.dp) },
            { ProjectionMeterCompact(fiveLabel, five, barWidth = 32.dp, reset = false) },
        ),
        listOf(
            { ProjectionMeterCompact(sevenLabel, seven, barWidth = 32.dp, reset = false) },
            { ProjectionMeterCompact(sevenLabel, seven, bar = false, reset = false) },
        ),
    )
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

/**
 * "2 BLOCKED" after the strip while calls refused without the owner wait (as the Home card's chip),
 * now that they live in the session sheet's Permissions part, not over the chat; a tap opens them
 * there ([onClick]).
 */
@Composable
internal fun BlockedMark(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val said = pluralStringResource(R.plurals.blocked_mark_said, count, count)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.blocked_mark_open), onClick = onClick)
            .clearAndSetSemantics { contentDescription = said },
        contentAlignment = Alignment.Center,
    ) {
        StatusChip(pluralStringResource(R.plurals.blocked_chip, count, count), HN.amber)
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

private fun stripDescription(res: Resources, u: SessionUsage, s: Session, five: UsageProjection?, seven: UsageProjection?): String = buildString {
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
    append(res.getString(R.string.chat_tokens_said, grouped(u.tokens)))
    if (five != null) {
        append(' ')
        append(projectionSaid(res, res.getString(R.string.usage_name_five), five))
    }
    if (seven != null) {
        append(' ')
        append(projectionSaid(res, res.getString(R.string.usage_name_seven), seven))
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
 * The session sheet's Usage part: the plan's windows, as on Home (owner, 2026-10-30: not the
 * context, the tokens or the cost; the strip has the context, and a cost is no use on a plan).
 * Only for a session on the subscription's own provider while the windows are known
 * ([planShown]).
 */
@Composable
internal fun UsageDetails(hub: Hub) {
    // The plan's per-model weeks below are read off /usage every 15 minutes: a fresh look now (data/UsageRefresh.kt).
    LaunchedEffect(Unit) { com.prudnikweb.hypernova.data.UsageRefresh.ask() }
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

/** The plan's windows are about [session] and known: the strip's meters and the sheet's Usage part show. */
internal fun planShown(hub: Hub, session: Session): Boolean = builtIn(hub, session) && hub.usage.known && !hub.usage.stale
