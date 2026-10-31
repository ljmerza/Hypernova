package com.prudnikweb.hypernova.ui.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ApiException
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Problem
import com.prudnikweb.hypernova.data.StatDay
import com.prudnikweb.hypernova.data.StatProject
import com.prudnikweb.hypernova.data.Stats
import com.prudnikweb.hypernova.data.StatsAgents
import com.prudnikweb.hypernova.data.StatsRange
import com.prudnikweb.hypernova.data.StatsSource
import com.prudnikweb.hypernova.data.bothAgents
import com.prudnikweb.hypernova.data.codexLeads
import com.prudnikweb.hypernova.data.problemText
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.components.planetSphere
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.JetBrainsMono
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/** The hero's number: big mono digits with a faint mint halo, as a bright star has. */
private val HeroNumber = TextStyle(
    fontFamily = JetBrainsMono, fontWeight = FontWeight(400), fontSize = 46.sp, lineHeight = 50.sp,
    letterSpacing = (-0.04).em, shadow = Shadow(HN.accent.copy(alpha = 0.22f), Offset.Zero, 28f),
)
private val StatNumber = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight(400), fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.02).em)
private val RowNumber = Type.mono.copy(fontSize = 15.sp, lineHeight = 20.sp)

/** The screen's blur source, for its top bar. */
private const val STATS_SOURCE = "stats"

/**
 * Statistics, opened by tapping the sun: how much the agents worked on the Mac, in the range picked
 * (Today, Week, Month, All). The hours and the work behind them, the last 30 days as a bar a day,
 * the projects as planets, and a few records. The Mac adds it up from Claude Code's
 * transcripts and the Codex turns it followed; this asks for it when the screen opens or the range
 * changes (showing the last answer at once), then every minute while open. Back (the arrow or the
 * gesture) is [onBack]. Every total covers both agents; with Codex at work in the range the hero
 * splits its hours between them ([StatsAgents]); Claude's alone reads exactly as before Codex.
 */
@Composable
fun StatsScreen(
    hub: Hub,
    onBack: () -> Unit,
    /** Home shows "All servers": whose statistics these are (the one picked last), before the days they cover. */
    server: String? = null,
) {
    val res = LocalContext.current.resources
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var range by rememberSaveable { mutableStateOf(StatsRange.Week) }
    var stats by remember(hub) { mutableStateOf(StatsSource.cached(hub, range)) }
    var loading by remember(hub) { mutableStateOf(false) }
    var problem by remember(hub) { mutableStateOf<ApiException?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    // The day the last 30 days' readout shows (epoch day); none picked: today.
    var picked by rememberSaveable { mutableStateOf<Long?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lastRange = remember(hub) { arrayOf<StatsRange?>(null) }
    LaunchedEffect(hub, range, retry) {
        StatsSource.cached(hub, range)?.let { stats = it }
        // A new range starts clean: the last one's failure isn't its own. Trying again keeps it,
        // so the error stays up while the Mac is asked again.
        if (lastRange[0] != range) {
            lastRange[0] = range
            problem = null
        }
        var asked = retry
        // Only while the app is on screen: the background connection keeps the process alive, and
        // nobody reads these there.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                // An answer from the last half minute is shown as it is; after that, today goes on.
                if (asked > 0 || !StatsSource.fresh(hub, range)) {
                    loading = true
                    try {
                        StatsSource.load(hub, range)
                            .onSuccess { stats = it; problem = null }
                            .onFailure { problem = it as? ApiException ?: ApiException(Problem.Unreachable) }
                    } finally {
                        // Also when the app leaves the screen mid-ask, so the planet doesn't spin on.
                        loading = false
                    }
                }
                asked = 0
                delay(60_000)
            }
        }
    }

    // Another range's numbers stay while the Mac answers, and glide to this one's. They never
    // stand in for this range otherwise: not once asking has failed, and not while there's no
    // link to the Mac, when the answer may be seconds away or not come at all.
    val linked = hub.link.state == LinkState.Connected
    val s = stats?.takeIf { it.range == range || (linked && problem == null) }
    // Whose work it is: Claude's alone (as ever), both agents', or Codex's where it leads.
    val agents = s?.agents(hub.codexLeads) ?: StatsAgents.Claude
    // The days the range covers, from any answer: the Mac's calendar, and its first day with work.
    // Before the first answer the line holds its place, so the bar doesn't change height.
    val span = stats?.let { rangeSpan(res, range, it.days.lastOrNull()?.day ?: localDate(it.to), it.since) } ?: " "
    ScreenScaffold(
        sourceKey = STATS_SOURCE,
        title = stringResource(R.string.stats_title),
        subtitle = when {
            server == null -> span
            span.isBlank() -> server
            else -> stringResource(R.string.stats_on_server, server, span)
        },
        onBack = onBack,
        // The activity meter: it turns while an answer loads.
        actions = { Planet(if (loading) Activity.Live else Activity.Rest, Modifier.padding(end = 10.dp)) },
        below = {
            Segmented(
                listOf(
                    StatsRange.Today to stringResource(R.string.stats_range_today),
                    StatsRange.Week to stringResource(R.string.stats_range_week),
                    StatsRange.Month to stringResource(R.string.stats_range_month),
                    StatsRange.All to stringResource(R.string.stats_range_all),
                ),
                range, { range = it },
                Modifier.padding(start = 8.dp),
            )
        },
    ) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top + 6.dp, bottom = bottom + 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                // No answer for this range and the Mac can't give one: say so, rather than leave
                // another range's numbers under this one's name.
                s == null && problem != null -> item(key = "error") { ErrorCard(problem, onRetry = { retry++ }) }
                s == null -> item(key = "loading") {
                    GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                        val reading = when {
                            hub.bothAgents -> R.string.stats_loading_agents
                            hub.codexLeads -> R.string.stats_loading_codex
                            else -> R.string.stats_loading
                        }
                        LoadingLine(stringResource(reading, hub.mac.name), Modifier.padding(16.dp))
                    }
                }
                else -> {
                    if (problem != null) item(key = "stale") {
                        val at = remember(s.to) { DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).format(java.time.Instant.ofEpochMilli(s.to).atZone(java.time.ZoneId.systemDefault())) }
                        HText(stringResource(R.string.stats_stale, at), style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    item(key = "hero") { Hero(s, agents, Modifier.padding(horizontal = 16.dp)) }
                    item(key = "days") {
                        val today = s.days.lastOrNull()?.day
                        val day = picked?.let { p -> s.days.firstOrNull { it.day.toEpochDay() == p } } ?: s.days.lastOrNull()
                        DaysSection(s, day?.day ?: today, onSelect = { picked = it.toEpochDay() }, agents = agents)
                    }
                    if (s.projects.isNotEmpty()) item(key = "projects") { Projects(s) }
                    item(key = "records") { Records(s, agents) }
                    if (s.prompts > 0 || s.toolCalls > 0 || s.approvals > 0) item(key = "work") { Work(s) }
                    item(key = "footer") {
                        HText(
                            stringResource(
                                when (agents) {
                                    StatsAgents.Claude -> R.string.stats_footer
                                    StatsAgents.Both -> R.string.stats_footer_agents
                                    StatsAgents.Codex -> R.string.stats_footer_codex
                                },
                                hub.mac.name,
                            ),
                            // A quiet note under everything.
                            style = Type.small.copy(textAlign = TextAlign.Center), color = HN.inkDim,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(problem: ApiException?, onRetry: () -> Unit) {
    val res = LocalContext.current.resources
    GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            HText(stringResource(R.string.stats_error), style = Type.heading, color = HN.ink)
            Spacer(Modifier.height(4.dp))
            // A Mac from before statistics answers "Unknown endpoint".
            val text = when {
                problem?.status == 404 && problem.code == "not_found" -> stringResource(R.string.stats_old_mac)
                problem != null && problem.problem == Problem.Server -> problem.serverMessage ?: stringResource(R.string.stats_error)
                else -> problemText(res, problem?.problem)
            }
            HText(text, style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(14.dp))
            Pill(stringResource(R.string.try_again), onRetry, kind = PillKind.Outline, height = 40.dp)
        }
    }
}

// ─── The hero ──────────────────────────────────────────────────────────────

/**
 * The hero: the range's hours, its notes, and its tokens, sessions and agents. [who]: Claude's
 * work (as before Codex), both agents' (a split under the number: [AgentSplit]), or Codex's.
 */
@Composable
private fun Hero(s: Stats, who: StatsAgents, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val reduced = LocalMotion.current.reduced
    // Numbers glide to their new values when the range changes (a count, never a jump).
    val spec = if (reduced) snap<Float>() else tween(900, easing = Motion.Expo)
    val claude by animateFloatAsState(s.claudeMs.toFloat(), spec, label = "claude")
    val tokens by animateFloatAsState(s.tokens.total.toFloat(), spec, label = "tokens")
    val sessions by animateFloatAsState(s.sessions.toFloat(), spec, label = "sessions")
    val agents by animateFloatAsState(s.agents.toFloat(), spec, label = "agents")
    val sub = buildList {
        if (s.claudeMs <= 0L) add(stringResource(if (s.range == StatsRange.Today) R.string.stats_quiet_today else R.string.stats_quiet))
        // Sessions side by side add up above; by the clock they count once.
        if (s.wallMs > 0 && s.claudeMs - s.wallMs >= 60_000L) add(stringResource(R.string.stats_by_clock, hours(res, s.wallMs)))
        if (s.agentMs >= 60_000L) add(stringResource(R.string.stats_agent_work, hours(res, s.agentMs)))
    }
    val a11y = stringResource(
        when (who) {
            StatsAgents.Claude -> R.string.a11y_stats_hero
            StatsAgents.Both -> R.string.a11y_stats_hero_agents
            StatsAgents.Codex -> R.string.a11y_stats_hero_codex
        },
        hours(res, s.claudeMs), compact(res, s.tokens.total), s.sessions, s.agents,
    )
    // Both agents: how the hours split, said after the hero's own sentence.
    val split = if (who == StatsAgents.Both) stringResource(R.string.a11y_stats_split, hoursSaid(res, s.claudeOwnMs), hoursSaid(res, s.codexOwnMs)) else null
    GlassCard(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = (listOf(a11y) + sub + listOfNotNull(split)).joinToString(". ") }) {
        Column(Modifier.padding(horizontal = 18.dp).padding(top = 18.dp, bottom = 16.dp)) {
            val title = when (who) {
                StatsAgents.Claude -> R.string.stats_claude_worked
                StatsAgents.Both -> R.string.stats_agents_worked
                StatsAgents.Codex -> R.string.stats_codex_worked
            }
            HText(stringResource(title).uppercase(), style = Type.label, color = HN.inkDim)
            Spacer(Modifier.height(10.dp))
            HText(hoursStyled(res, claude.toLong(), 24.sp, HN.inkDim), style = HeroNumber, color = HN.ink, maxLines = 1)
            // Each on a line of its own, never "·"-joined.
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                for (line in sub) HText(line, style = Type.small, color = HN.inkMuted, maxLines = 1)
            }
            if (who == StatsAgents.Both) {
                Spacer(Modifier.height(14.dp))
                AgentSplit(s.claudeOwnMs, s.codexOwnMs)
            }
            Spacer(Modifier.height(18.dp))
            Hairline(Modifier.fillMaxWidth(), HN.lineSoft)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                MiniStat(compact(res, tokens.toLong()), stringResource(R.string.stats_tokens), Modifier.weight(1f))
                MiniStat(grouped(sessions.toLong()), stringResource(R.string.stats_sessions), Modifier.weight(1f))
                MiniStat(grouped(agents.toLong()), stringResource(R.string.stats_agents), Modifier.weight(1f))
            }
        }
    }
}

/**
 * How the hours split between the agents, drawn (never "Claude 5h 10m · Codex 1h 20m" in words):
 * a bar as the meters draw one, hairline track and 3dp parts, Claude's in mint and Codex's in muted
 * ink with a gap between them, and under it each agent's name in tracked caps after a dot of its
 * colour, with its hours in mono (Claude's on the left, Codex's on the right). The hero's sentence
 * says it for TalkBack.
 */
@Composable
private fun AgentSplit(claudeMs: Long, codexMs: Long) {
    val res = LocalContext.current.resources
    val reduced = LocalMotion.current.reduced
    val total = (claudeMs + codexMs).coerceAtLeast(1L)
    val share by animateFloatAsState(claudeMs.toFloat() / total, if (reduced) snap() else tween(900, easing = Motion.Expo), label = "split")
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(9.dp)) {
            val y = size.height / 2f
            val w = size.width
            val thick = 3.dp.toPx()
            val half = thick / 2f
            val gap = 4.dp.toPx()
            drawLine(HN.line, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
            val cut = w * share
            // Round caps reach half the stroke past each end: the parts are inset by it, so they keep the gap.
            if (claudeMs > 0L) drawLine(HN.accent, Offset(half, y), Offset((cut - gap / 2f - half).coerceIn(half, w - half), y), strokeWidth = thick, cap = StrokeCap.Round)
            if (codexMs > 0L) drawLine(HN.inkMuted, Offset((cut + gap / 2f + half).coerceIn(half, w - half), y), Offset(w - half, y), strokeWidth = thick, cap = StrokeCap.Round)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SplitPart(stringResource(R.string.claude), hours(res, claudeMs), HN.accent)
            Spacer(Modifier.weight(1f))
            SplitPart(stringResource(R.string.cx_agent_codex), hours(res, codexMs), HN.inkMuted)
        }
    }
}

/** One agent's part of the split: a dot of its colour, its name in tracked caps, its hours in mono. */
@Composable
private fun SplitPart(name: String, value: String, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(5.dp)) { drawCircle(colour) }
        Spacer(Modifier.width(6.dp))
        HText(name.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        HText(value, style = Type.monoSmall, color = HN.ink, maxLines = 1)
    }
}

@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        HText(value, style = StatNumber, color = HN.ink, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        HText(label.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
    }
}

// ─── The last 30 days ──────────────────────────────────────────────────────

/**
 * The last 30 days, a card like the others: a bar a day ([DayBarsChart]) and under it the picked
 * day's line (`SAT 31 OCT  ⏱ 9h 17m  ⬡ 435M`), today until another is picked. The seam's trail
 * counts the days with work.
 */
@Composable
private fun DaysSection(s: Stats, selected: LocalDate?, onSelect: (LocalDate) -> Unit, agents: StatsAgents = StatsAgents.Claude) {
    val res = LocalContext.current.resources
    val active = s.days.count { it.claudeMs > 0 }
    val day = s.days.firstOrNull { it.day == selected }
    // With both agents at work, a day's Codex share follows its hours and tokens (a bar is both agents' hours).
    val codexPart = day?.takeIf { d -> agents == StatsAgents.Both && d.codexMs >= 60_000L && d.claudeMs > 0 }?.codexMs
    val readout = day?.let {
        if (it.claudeMs <= 0) res.getString(R.string.stats_day_none)
        else res.getString(R.string.stats_day_said, hours(res, it.claudeMs), compact(res, it.tokens))
            .let { said -> codexPart?.let { c -> res.getString(R.string.stats_day_codex_said, said, hours(res, c)) } ?: said }
    }
    // TalkBack: the chart in a sentence (its busiest day), then the picked day.
    val busiest = DayBars.busiest(s.days.map { it.claudeMs })
    val summary = if (busiest < 0) stringResource(R.string.a11y_stats_days_none)
    else stringResource(R.string.a11y_stats_days, hoursSaid(res, s.days[busiest].claudeMs), dayName(s.days[busiest].day))
    val picked = listOfNotNull(day?.let { dayName(it.day) }, readout).joinToString(", ")
    Column(Modifier.fillMaxWidth()) {
        Seam(
            stringResource(R.string.stats_last_days),
            trail = pluralStringResource(R.plurals.stats_active_days, active, active),
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 12.dp),
        )
        GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 12.dp)) {
                DayBarsChart(
                    days = s.days,
                    lit = if (s.range == StatsRange.All) 30 else s.range.days,
                    selected = day?.day,
                    onSelect = onSelect,
                    description = listOf(summary, picked).filter { it.isNotEmpty() }.joinToString(" "),
                    modifier = Modifier.fillMaxWidth().height(132.dp),
                )
                Spacer(Modifier.height(12.dp))
                Hairline(Modifier.fillMaxWidth(), HN.lineSoft)
                Spacer(Modifier.height(10.dp))
                DayLine(day, codexPart, readout)
            }
        }
    }
}

/** The picked day's line, holding its place under the chart: its name, its hours after a stopwatch, its tokens after a token. */
@Composable
private fun DayLine(day: StatDay?, codexPart: Long?, readout: String?) {
    val res = LocalContext.current.resources
    Row(
        Modifier.fillMaxWidth().heightIn(min = 20.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (day != null) {
            // The name gives way first when the figures need the room (large fonts, Codex's share too).
            HText(dayName(day.day).uppercase(), style = Type.label, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            // Its hours after a stopwatch and its tokens after a token; a day without work in words.
            if (day.claudeMs > 0) {
                GlyphFigure(Glyph.Timer, hours(res, day.claudeMs))
                Spacer(Modifier.width(12.dp))
                GlyphFigure(Glyph.Token, compact(res, day.tokens))
                if (codexPart != null) {
                    Spacer(Modifier.width(12.dp))
                    HText(stringResource(R.string.cx_agent_codex).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                    Spacer(Modifier.width(6.dp))
                    HText(hours(res, codexPart), style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
                }
            } else HText(readout.orEmpty(), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        }
    }
}

/**
 * A bar a day, oldest on the left: its height the day's hours of work against the scale's top (a
 * round figure of hours, [DayBars.scaleHours], marked by a dashed hairline with its hours), today's
 * in the accent, the others accent-dim, a day without work a faint tick on the baseline. Under the
 * bars the first day, the middle one and TODAY. Days outside the chosen range ([lit]: how many of
 * the latest are in it) stay, fainter. A tap or a drag across the bars picks a day ([onSelect]):
 * a faint column behind it and its bar brighter. TalkBack hears [description], and the day before
 * and after as actions. The bars grow in once; under Reduce Motion they're simply there.
 */
@Composable
private fun DayBarsChart(
    days: List<StatDay>,
    lit: Int,
    selected: LocalDate?,
    onSelect: (LocalDate) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val res = LocalContext.current.resources
    val reduced = LocalMotion.current.reduced
    val litAnim by animateFloatAsState(lit.toFloat(), if (reduced) snap() else tween(Motion.SECTION, easing = Motion.Expo), label = "lit")
    val grow = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, tween(900, easing = Motion.Expo)) }
    val ms = remember(days) { days.map { it.claudeMs } }
    val heights = remember(ms) { DayBars.heights(ms) }
    val measurer = rememberTextMeasurer()
    val scale = res.getString(R.string.stats_h, DayBars.scaleHours(ms.maxOrNull() ?: 0L))
    val scaleLayout = remember(measurer, scale) { measurer.measure(scale, Type.monoSmall.copy(color = HN.inkDim)) }
    val today = stringResource(R.string.stats_today_tick).uppercase()
    val labels = remember(measurer, days, today) {
        DayBars.labelDays(days.size).map { i ->
            if (i == days.lastIndex) measurer.measure(today, Type.label.copy(color = HN.inkMuted))
            else measurer.measure(dateName(days[i].day).uppercase(), Type.label.copy(color = HN.inkDim))
        }
    }
    val labelWidths = remember(labels) { FloatArray(labels.size) { labels[it].size.width.toFloat() } }
    val sel = days.indexOfFirst { it.day == selected }
    val now = rememberUpdatedState(days)
    val select = rememberUpdatedState(onSelect)
    fun pick(x: Float, width: Int) {
        val d = now.value
        DayBars.indexAt(x, width.toFloat(), d.size).takeIf { it >= 0 }?.let { select.value(d[it].day) }
    }
    val prev = stringResource(R.string.a11y_stats_prev_day)
    val next = stringResource(R.string.a11y_stats_next_day)
    Canvas(
        modifier
            .semantics {
                contentDescription = description
                customActions = listOfNotNull(
                    days.getOrNull(sel - 1)?.let { d -> CustomAccessibilityAction(prev) { onSelect(d.day); true } },
                    days.getOrNull(sel + 1)?.let { d -> CustomAccessibilityAction(next) { onSelect(d.day); true } },
                )
            }
            .pointerInput(Unit) { detectTapGestures { p -> pick(p.x, size.width) } }
            .pointerInput(Unit) {
                // Across only: an up-and-down drag stays the screen's scroll.
                detectHorizontalDragGestures(onDragStart = { p -> pick(p.x, size.width) }) { change, _ ->
                    change.consume()
                    pick(change.position.x, size.width)
                }
            },
    ) {
        val n = days.size
        if (n == 0) return@Canvas
        val w = size.width
        val u = 1.dp.toPx()
        // The scale's hours sit over its hairline; the labels under the baseline.
        val top = scaleLayout.size.height + 4 * u
        val labelGap = 8 * u
        val base = size.height - (labels.maxOfOrNull { it.size.height } ?: 0) - labelGap
        val chart = (base - top).coerceAtLeast(0f)

        drawText(scaleLayout, topLeft = Offset(0f, 0f))
        drawLine(
            HN.line, Offset(scaleLayout.size.width + 8 * u, top), Offset(w, top), strokeWidth = u,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * u, 3 * u)),
        )

        if (sel in 0 until n) {
            val slot = w / n
            drawRoundRect(HN.ink.copy(alpha = 0.06f), Offset(slot * sel, top), Size(slot, base - top + 3 * u), CornerRadius(3 * u))
        }

        val bw = DayBars.barWidth(w, n)
        val corner = CornerRadius(minOf(bw / 2f, 2 * u))
        for (i in 0 until n) {
            val x = DayBars.barLeft(i, w, n)
            val shown = 0.35f + 0.65f * DayBars.lit(i, n, litAnim)
            if (days[i].claudeMs <= 0L) {
                drawRect(HN.inkDim.copy(alpha = 0.55f * shown), Offset(x, base - 1.5f * u), Size(bw, 1.5f * u))
                continue
            }
            // Even a few minutes stand clear of a tick.
            val h = max(3 * u, chart * heights[i] * grow.value)
            val colour = when (i) {
                n - 1 -> HN.accent
                sel -> HN.accentBright
                else -> HN.accentDim
            }
            drawRoundRect(colour.copy(alpha = shown), Offset(x, base - h), Size(bw, h), corner)
        }

        val lefts = DayBars.labelLefts(n, w, labelWidths, 8 * u)
        for (k in labels.indices) if (!lefts[k].isNaN()) drawText(labels[k], topLeft = Offset(lefts[k], base + labelGap))
    }
}

// ─── Projects as planets ───────────────────────────────────────────────────

/** A project's planet shade, seeded from its name exactly as the home sky seeds it, so it's the same planet. */
private fun planetColor(name: String): Color {
    val rnd = Random(name.hashCode())
    rnd.nextFloat() // the sky's first draw is its orbit's inclination
    return lerp(HN.accentDim, HN.accentBright, 0.35f + 0.5f * rnd.nextFloat())
}

@Composable
private fun Projects(s: Stats) {
    val shown = s.projects.take(6)
    val max = shown.maxOf { it.claudeMs }.coerceAtLeast(1L)
    Column(Modifier.fillMaxWidth()) {
        Seam(
            stringResource(R.string.stats_projects), trail = stringResource(R.string.stats_by_hours),
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 12.dp),
        )
        GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                shown.forEachIndexed { i, p ->
                    if (i > 0) Hairline(Modifier.fillMaxWidth().padding(start = 68.dp, end = 16.dp), HN.lineSoft)
                    ProjectRow(p, max)
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(p: StatProject, max: Long) {
    val res = LocalContext.current.resources
    val color = remember(p.name) { planetColor(p.name) }
    // Sized by hours (by area, so a project with twice the hours isn't twice as wide).
    val share = sqrt(p.claudeMs.toFloat() / max).coerceIn(0f, 1f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(44.dp)) {
            val r = (4.5f + 13f * share).dp.toPx()
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.18f), Color.Transparent), center, r * 2f), r * 2f, center)
            planetSphere(center, r, color, (PI / 4).toFloat(), stroke = (r * 0.09f).coerceAtLeast(0.7.dp.toPx()))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            HText(p.name, style = Type.heading, color = HN.ink, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            // Its sessions in words, its tokens after a token: no "12 sessions · 1.3B tokens".
            if (p.sessions > 0 || p.tokens > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.sessions > 0) HText(pluralStringResource(R.plurals.stats_sessions_count, p.sessions, p.sessions), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                if (p.sessions > 0 && p.tokens > 0) Spacer(Modifier.width(12.dp))
                if (p.tokens > 0) GlyphFigure(Glyph.Token, compact(res, p.tokens), color = HN.inkDim)
            }
        }
        Spacer(Modifier.width(12.dp))
        HText(hours(res, p.claudeMs), style = RowNumber, color = HN.ink, maxLines = 1)
    }
}

// ─── Records ───────────────────────────────────────────────────────────────

/** A record: its label and value, and under them [detail] in words or [drawn] ([said] for TalkBack). */
private class Record(val label: String, val value: String, val detail: String?, val drawn: (@Composable () -> Unit)? = null, val said: String? = null)

@Composable
private fun Records(s: Stats, agents: StatsAgents = StatsAgents.Claude) {
    val res = LocalContext.current.resources
    val rows = buildList {
        s.longestSession?.takeIf { it.claudeMs > 0 }?.let { l ->
            // Its title, then its project in tracked caps, as a session card names them.
            val title = l.title.takeIf { it.isNotBlank() }
            val project = l.project.takeIf { it.isNotBlank() && it != l.title }
            add(
                Record(
                    stringResource(R.string.stats_longest), hours(res, l.claudeMs), null,
                    drawn = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (title != null) HText(title, style = Type.small, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (title != null && project != null) Spacer(Modifier.width(10.dp))
                            if (project != null) HText(project.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                        }
                    },
                    said = listOfNotNull(title, project?.let { res.getString(R.string.stats_in_project, it) }).joinToString(", "),
                ),
            )
        }
        if (s.range != StatsRange.Today) s.busiestDay?.takeIf { it.claudeMs > 0 }?.let { b ->
            add(
                Record(
                    stringResource(R.string.stats_busiest), dayName(b.day), null,
                    drawn = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GlyphFigure(Glyph.Timer, hours(res, b.claudeMs))
                            Spacer(Modifier.width(12.dp))
                            GlyphFigure(Glyph.Token, compact(res, b.tokens))
                        }
                    },
                    said = res.getString(R.string.stats_day_said, hours(res, b.claudeMs), compact(res, b.tokens)),
                ),
            )
        }
        if (s.streak >= 2) {
            // The streak runs to today, or to yesterday while today hasn't started.
            val today = s.days.lastOrNull()
            val end = if (today != null && today.claudeMs >= 60_000L) today.day else (today?.day ?: localDate(s.to)).minusDays(1)
            val streakWords = when (agents) {
                StatsAgents.Claude -> R.string.stats_streak_detail
                StatsAgents.Both -> R.string.stats_streak_detail_agents
                StatsAgents.Codex -> R.string.stats_streak_detail_codex
            }
            add(Record(stringResource(R.string.stats_streak), pluralStringResource(R.plurals.stats_streak_days, s.streak, s.streak), stringResource(streakWords, dayName(end.minusDays(s.streak - 1L)))))
        }
        val limits = s.limitsFiveHour + s.limitsWeek + s.limitsModel
        if (limits > 0) {
            val kinds = listOf(
                Triple(s.limitsFiveHour, R.string.stats_limit_five_only, R.string.stats_limit_five),
                Triple(s.limitsWeek, R.string.stats_limit_week_only, R.string.stats_limit_week),
                Triple(s.limitsModel, R.string.stats_limit_model_only, R.string.stats_limit_model),
            ).filter { it.first > 0 }
            // One kind: which one. Several: how many of each, as a small breakdown.
            val one = kinds.singleOrNull()?.let { stringResource(it.second) }
            val rows = kinds.map { stringResource(it.third) to grouped(it.first.toLong()) }
            add(
                Record(
                    stringResource(R.string.stats_limits), grouped(limits.toLong()), one,
                    drawn = if (one == null) ({ Breakdown(rows) }) else null,
                    said = if (one == null) rows.joinToString(", ") { (k, n) -> "$k $n" } else null,
                ),
            )
        }
    }
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Seam(stringResource(R.string.stats_records), modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 12.dp))
        GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            rows.forEachIndexed { i, r ->
                if (i > 0) Hairline(Modifier.fillMaxWidth().padding(horizontal = 16.dp), HN.lineSoft)
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HText(r.label.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        HText(r.value, style = RowNumber, color = HN.ink, maxLines = 1)
                    }
                    if (r.drawn != null) {
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.clearAndSetSemantics { r.said?.let { contentDescription = it } }) { r.drawn.invoke() }
                    } else if (!r.detail.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        HText(r.detail, style = Type.small, color = HN.inkMuted, maxLines = 2)
                    }
                }
            }
        }
    }
}

// ─── The work behind it ────────────────────────────────────────────────────

@Composable
private fun Work(s: Stats) {
    val res = LocalContext.current.resources
    val counting = s.countingSince > s.from
    val since = dateName(localDate(s.countingSince))
    Column(Modifier.fillMaxWidth()) {
        Seam(stringResource(R.string.stats_work), modifier = Modifier.padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 12.dp))
        GlassCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    val from = listOfNotNull(
                        s.fromPhone.takeIf { it > 0 }?.let { stringResource(R.string.stats_from_phone) to grouped(it.toLong()) },
                        s.byVoice.takeIf { it > 0 }?.let { stringResource(R.string.stats_by_voice) to grouped(it.toLong()) },
                    )
                    Tile(stringResource(R.string.stats_prompts), grouped(s.prompts.toLong()), null, Modifier.weight(1f), from)
                    Spacer(Modifier.width(14.dp))
                    val top = s.tools.filter { it.name != "Other" }.take(3).map { it.name to compact(res, it.count.toLong()) }
                    Tile(stringResource(R.string.stats_tool_calls), grouped(s.toolCalls.toLong()), null, Modifier.weight(1f), top)
                }
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth()) {
                    Tile(stringResource(R.string.stats_files), grouped(s.filesEdited.toLong()), null, Modifier.weight(1f))
                    Spacer(Modifier.width(14.dp))
                    val ap = listOfNotNull(
                        s.approvalsPhone.takeIf { it > 0 }?.let { stringResource(R.string.stats_ap_phone) to grouped(it.toLong()) },
                        s.approvalsMac.takeIf { it > 0 }?.let { stringResource(R.string.stats_ap_mac) to grouped(it.toLong()) },
                    )
                    // Only the Mac knows these, and only since it started counting.
                    val detail = if (ap.isEmpty() && counting) stringResource(R.string.stats_counting_since, since) else null
                    Tile(stringResource(R.string.stats_approvals), grouped(s.approvals.toLong()), detail, Modifier.weight(1f), ap)
                }
                if (s.tokens.total > 0) {
                    Spacer(Modifier.height(16.dp))
                    Hairline(Modifier.fillMaxWidth(), HN.lineSoft)
                    Spacer(Modifier.height(12.dp))
                    // The tokens by kind: mostly cache reads, which is why the total is so big.
                    Row(Modifier.fillMaxWidth()) {
                        TokenPart(compact(res, s.tokens.output), stringResource(R.string.stats_tok_out), Modifier.weight(0.8f))
                        TokenPart(compact(res, s.tokens.input), stringResource(R.string.stats_tok_in), Modifier.weight(0.8f))
                        TokenPart(compact(res, s.tokens.cacheRead), stringResource(R.string.stats_tok_cache_read), Modifier.weight(1.2f))
                        TokenPart(compact(res, s.tokens.cacheWrite), stringResource(R.string.stats_tok_cache_write), Modifier.weight(1.2f))
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenPart(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        HText(value, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        HText(label.uppercase(), style = Type.label.copy(fontSize = 9.sp), color = HN.inkDim, maxLines = 1)
    }
}

/**
 * What a number is made of, as a small table: each part's name on the left and its count on the
 * right in mono ("phone ··· 31"), a row each, never "phone 31 · spoken 12".
 */
@Composable
private fun Breakdown(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((name, n) in rows) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HText(name, style = BreakdownName, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            HText(n, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
        }
    }
}

private val BreakdownName = Type.small.copy(fontSize = 12.sp, lineHeight = 16.sp)

/** A tile: its label, its number, and under them a line of words ([detail]) or its [parts] as a [Breakdown]. */
@Composable
private fun Tile(label: String, value: String, detail: String?, modifier: Modifier = Modifier, parts: List<Pair<String, String>> = emptyList()) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        HText(label.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        HText(value, style = StatNumber, color = HN.ink, maxLines = 1)
        if (parts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Breakdown(parts, Modifier.padding(end = 4.dp))
        } else if (detail != null) {
            Spacer(Modifier.height(4.dp))
            HText(detail, style = BreakdownName, color = HN.inkMuted, maxLines = 3)
        }
    }
}
