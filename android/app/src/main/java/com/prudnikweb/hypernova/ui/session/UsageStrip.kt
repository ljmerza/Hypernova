package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.SessionUsage
import com.prudnikweb.hypernova.data.ChatRepo
import android.content.res.Resources
import com.prudnikweb.hypernova.ui.components.glassChoice
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import com.prudnikweb.hypernova.ui.components.ModeTag
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
import com.prudnikweb.hypernova.data.UsageWindow
import com.prudnikweb.hypernova.ui.components.UsageMeter
import com.prudnikweb.hypernova.data.ModelNames
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.ModelChip
import com.prudnikweb.hypernova.ui.components.UsageMeterCompact
import com.prudnikweb.hypernova.ui.components.usageSaid
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
 * The slim glass strip at the top of the chat, the session's top bar: what the owner chose in
 * Settings → Session top bar, in his order ([TopBarChoice], TopBar.kt; owner, 2026-10-31). By
 * default the context ring and its share, the mode and the effort ("auto · high"), the plan's
 * 5-hour window (only while Claude Code reports one) and the week, as compact meters
 * with their resets (ui/components/UsageMeter.kt); the model, the tokens, the agents at work
 * ([agents]) and this turn's time can be turned on. No cost (owner, 2026-10-30). "≈" marks what's
 * worked out rather than Claude Code's own number. One line: nothing drops for room, the row
 * scrolls sideways under a finger, its edges fading where there's more. A tap (not a drag) opens
 * the session sheet ([onOpen], SessionSheet.kt); a tap on the agents count opens it on its Agents
 * tab ([onAgents]). From [COMPACT_AT]% a Compact chip after it offers /compact. TalkBack hears one
 * sentence of what it shows, then [more] (the queue).
 */
@Composable
internal fun UsageStrip(
    hub: Hub, session: Session, usage: SessionUsage, agents: Int, onCompact: () -> Unit, onOpen: () -> Unit, onAgents: () -> Unit, more: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val res = context.resources
    val choice by remember { TopBarPref.state(context) }
    val pct = usage.contextPct
    val u = hub.usage
    // Moves when "resets in" would read differently, in step with Home's countdowns (ui/fx/Countdown.kt).
    val now = com.prudnikweb.hypernova.ui.fx.rememberCountdownNow(u.fiveHourResetAt, u.sevenDayResetAt)
    val facts = TopBarFacts(
        codex = session.codex, effort = session.effort, tokens = usage.tokens, agents = agents,
        turnStartedAt = session.turnStartedAt, ended = session.phase == com.prudnikweb.hypernova.data.Phase.Ended,
        plan = planShown(hub, session), fiveHour = u.fiveHourReported,
    )
    // This turn's time, to the minute, while it's shown.
    val turnOn = TopBarItem.TURN in choice.items && facts.inTurn(System.currentTimeMillis())
    val clock by produceState(System.currentTimeMillis(), turnOn) {
        while (turnOn) {
            value = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val parts = choice.visible(facts, maxOf(clock, now))
    val five = UsageWindow.fiveHour(u, now)
    val seven = UsageWindow.sevenDay(u, now)
    val mode = com.prudnikweb.hypernova.ui.home.modeLabel(session.mode)
    val effort = session.effort?.takeIf { facts.effortShown }?.let { com.prudnikweb.hypernova.ui.home.claudeEffortTitle(it) }
    val model = stripModel(usage, session.model, res.getString(R.string.model_default))
    val turnMs = (maxOf(clock, now) - session.turnStartedAt).coerceAtLeast(0L)
    val said = stripDescription(res, parts, usage, session, model, mode, effort, five, seven, agents, turnMs) + more
    val agentsLabel = stringResource(R.string.top_bar_agents_open)
    val shape = RoundedCornerShape(12.dp)
    val scroll = rememberScrollState()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = 40.dp)
                .clip(shape)
                .glassChoice(false, 12.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.session_sheet_open), onClick = onOpen)
                .clearAndSetSemantics {
                    contentDescription = said
                    if (TopBarItem.AGENTS in parts) customActions = listOf(CustomAccessibilityAction(agentsLabel) { onAgents(); true })
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                Modifier
                    .fadedEdges(scroll)
                    .horizontalScroll(scroll)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                for (part in parts) when (part) {
                    TopBarItem.CONTEXT -> Row(verticalAlignment = Alignment.CenterVertically) {
                        ContextRing(pct, size = 16.dp)
                        Spacer(Modifier.width(7.dp))
                        HText(
                            pct?.let { (if (usage.contextExact) "" else "≈") + res.getString(R.string.percent, it) } ?: "—",
                            style = Type.monoSmall, color = ringColor(pct), maxLines = 1,
                        )
                    }
                    // The mode's glyph and word, the effort after it: "auto · high"; Codex its mode alone.
                    TopBarItem.MODE -> ModeTag(session.mode, if (effort != null) "$mode · ${effort.lowercase()}" else mode, color = HN.inkMuted)
                    TopBarItem.FIVE_HOUR -> UsageMeterCompact(stringResource(R.string.usage_five_short), five, barWidth = 32.dp)
                    TopBarItem.SEVEN_DAY -> UsageMeterCompact(stringResource(R.string.usage_seven_short), seven, barWidth = 32.dp)
                    TopBarItem.MODEL -> ModelChip(ModelNames.chip(model))
                    TopBarItem.TOKENS -> GlyphFigure(Glyph.Token, compact(res, usage.tokens))
                    TopBarItem.AGENTS -> Box(
                        Modifier
                            .heightIn(min = 26.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onAgents),
                        contentAlignment = Alignment.Center,
                    ) {
                        HText(pluralStringResource(R.plurals.top_bar_agents, agents, agents), style = Type.monoSmall, color = HN.accentDim, maxLines = 1)
                    }
                    TopBarItem.TURN -> GlyphFigure(Glyph.Timer, turnShort(turnMs))
                }
            }
        }
        // /compact is Claude Code's: a Codex session has no Compact.
        if (pct != null && pct >= COMPACT_AT && !session.codex) {
            Spacer(Modifier.width(8.dp))
            CompactChip(onCompact)
        }
    }
}

/**
 * The row's content fades out at an edge it can scroll past ([scroll]), so a bar wider than the
 * phone says there's more; the glass under it stays as it is.
 */
private fun Modifier.fadedEdges(scroll: ScrollState): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fade = 18.dp.toPx().coerceAtMost(size.width / 3f)
        if (scroll.value > 0) drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fade),
            size = Size(fade, size.height), blendMode = BlendMode.DstIn,
        )
        if (scroll.value < scroll.maxValue) drawRect(
            Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - fade, endX = size.width),
            topLeft = Offset(size.width - fade, 0f), size = Size(fade, size.height), blendMode = BlendMode.DstIn,
        )
    }

/** The session runs on the subscription's own provider: the plan's windows are about it. */
internal fun builtIn(hub: Hub, session: Session): Boolean =
    hub.providers.firstOrNull { it.id == (session.providerId ?: "anthropic") }?.builtIn ?: (session.providerId == null || session.providerId == "anthropic")

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

/** TalkBack's one sentence for the bar: each part it shows ([parts]), in its order. */
private fun stripDescription(
    res: Resources, parts: List<TopBarItem>, u: SessionUsage, s: Session, model: String, mode: String, effort: String?,
    five: UsageWindow, seven: UsageWindow, agents: Int, turnMs: Long,
): String = parts.joinToString(" ") { part ->
    when (part) {
        TopBarItem.CONTEXT -> {
            val pct = u.contextPct
            when {
                pct == null -> res.getString(R.string.chat_context_unknown_said, com.prudnikweb.hypernova.ui.components.agentName(res, s.codex))
                u.contextExact -> res.getString(R.string.chat_context_said, pct)
                else -> res.getString(R.string.chat_context_about_said, pct)
            }
        }
        TopBarItem.MODE -> res.getString(R.string.meta_mode_said, mode) + (effort?.let { " " + res.getString(R.string.ns_effort_said, it) } ?: "")
        TopBarItem.FIVE_HOUR -> usageSaid(res, res.getString(R.string.usage_name_five), five)
        TopBarItem.SEVEN_DAY -> usageSaid(res, res.getString(R.string.usage_name_seven), seven)
        TopBarItem.MODEL -> res.getString(R.string.meta_model_said, model)
        TopBarItem.TOKENS -> res.getString(R.string.chat_tokens_said, grouped(u.tokens))
        TopBarItem.AGENTS -> res.getQuantityString(R.plurals.session_sheet_agents_said, agents, agents)
        TopBarItem.TURN -> res.getString(R.string.top_bar_turn_said, formatDuration(res, turnMs))
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
    // The 5-hour window only while Claude Code reports one (owner, 2026-10-31: Max has none).
    if (usage.fiveHourReported) {
        val five = UsageWindow.fiveHour(usage, now)
        UsageMeter(stringResource(R.string.five_hours), five, usageSaid(stringResource(R.string.usage_name_five), five))
        Spacer(Modifier.height(12.dp))
    }
    val seven = UsageWindow.sevenDay(usage, now)
    UsageMeter(stringResource(R.string.seven_days), seven, usageSaid(stringResource(R.string.usage_name_seven), seven))
    com.prudnikweb.hypernova.ui.home.ModelWeekMeters(usage.models, now, gap = 12.dp)
    Spacer(Modifier.height(6.dp))
    HText(stringResource(R.string.chat_plan_note), style = Type.small, color = HN.inkDim)
}

/** The plan's windows are about [session] and known: the strip's meters and the sheet's Usage part show. */
internal fun planShown(hub: Hub, session: Session): Boolean = builtIn(hub, session) && hub.usage.known && !hub.usage.stale
