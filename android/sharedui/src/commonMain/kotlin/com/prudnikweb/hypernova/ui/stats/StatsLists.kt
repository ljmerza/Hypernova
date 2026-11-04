package com.prudnikweb.hypernova.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.StatProject
import com.prudnikweb.hypernova.data.StatWords
import com.prudnikweb.hypernova.data.Stats
import com.prudnikweb.hypernova.data.StatsAgents
import com.prudnikweb.hypernova.data.StatsRange
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.platform.pluralStringResource
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.ui.platform.wordsNow
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlin.math.absoluteValue
import kotlin.math.sqrt

/** The most tools a tile lists: a tile is half a phone. The rest are in its total. */
private const val TOOLS_LISTED = 4

// ─── Projects ───

@Composable
internal fun ProjectsSection(s: Stats, modifier: Modifier = Modifier, stretch: Boolean = false) {
    val most = s.projects.maxOfOrNull { it.claudeMs } ?: 0L
    StatsSection(stringResource(Str.stats_projects), stringResource(Str.stats_by_hours), modifier, stretch) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            s.projects.forEachIndexed { i, p ->
                if (i > 0) Hairline(Modifier.fillMaxWidth(), color = HN.lineSoft)
                StatProjectRow(p, most)
            }
        }
    }
}

@Composable
private fun StatProjectRow(p: StatProject, most: Long) {
    val res = wordsNow()
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        ProjectMark(p, most)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            HText(p.name, style = Type.body, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HText(pluralStringResource(Plural.stats_sessions_count, p.sessions, p.sessions), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                val tokens = StatWords.count(res, p.tokens)
                GlyphFigure(Glyph.Token, tokens, Modifier.semantics { contentDescription = res.getString(Str.stats_day_tokens, tokens) }, color = HN.inkDim)
            }
        }
        Spacer(Modifier.width(8.dp))
        HText(StatWords.time(res, p.claudeMs), style = Type.mono.copy(fontSize = 15.sp), maxLines = 1)
    }
}

/** A solid diamond, its shade from the project's name and its size from its hours (by area, as eyes compare them). */
@Composable
private fun ProjectMark(p: StatProject, most: Long) {
    val share = if (most > 0L) sqrt(p.claudeMs.toFloat() / most) else 0f
    val hash = (p.name.hashCode().absoluteValue % 100) / 100f
    val shade: Color = lerp(HN.accentDim, HN.accentBright, 0.35f + hash * 0.5f)
    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(36.dp)) {
            val r = (size.minDimension / 2f) * (0.42f + 0.58f * share.coerceIn(0f, 1f))
            val c = Offset(size.width / 2f, size.height / 2f)
            drawPath(
                Path().apply {
                    moveTo(c.x, c.y - r)
                    lineTo(c.x + r, c.y)
                    lineTo(c.x, c.y + r)
                    lineTo(c.x - r, c.y)
                    close()
                },
                shade,
            )
        }
    }
}

// ─── Records ───

/** The range's own busiest day is the hero again when the range is one day. */
private fun showsBusiest(s: Stats) = s.busiestDay != null && s.range != StatsRange.Today

private fun limitsReached(s: Stats) = s.limitsFiveHour + s.limitsWeek + s.limitsModel

internal fun hasRecords(s: Stats) = s.longestSession != null || showsBusiest(s) || s.streak > 0 || limitsReached(s) > 0

/** A record's name and its figure on one line, then what there is to say of it. */
@Composable
private fun Record(name: String, figure: String, details: (@Composable () -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp).semantics(mergeDescendants = true) { }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HText(name.uppercase(), Modifier.weight(1f), style = Type.label, color = HN.inkMuted, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            HText(figure, style = Type.mono.copy(fontSize = 15.sp), maxLines = 1)
        }
        if (details != null) {
            Spacer(Modifier.height(6.dp))
            details()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordsSection(s: Stats, agents: StatsAgents, modifier: Modifier = Modifier, stretch: Boolean = false) {
    val res = wordsNow()
    val records = buildList<@Composable () -> Unit> {
        s.longestSession?.let { longest ->
            add {
                Record(stringResource(Str.stats_longest), StatWords.time(res, longest.claudeMs)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                        HText(longest.title, style = Type.small, maxLines = 2)
                        if (longest.project.isNotBlank()) HText(longest.project.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                    }
                }
            }
        }
        val busiest = s.busiestDay
        if (busiest != null && showsBusiest(s)) add {
            Record(stringResource(Str.stats_busiest), StatWords.weekday(res, busiest.day)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GlyphFigure(Glyph.Timer, StatWords.time(res, busiest.claudeMs))
                    GlyphFigure(Glyph.Token, StatWords.count(res, busiest.tokens))
                }
            }
        }
        if (s.streak > 0) add { StreakRecord(s, agents) }
        if (limitsReached(s) > 0) add { LimitsRecord(s) }
    }
    StatsSection(stringResource(Str.stats_records), null, modifier, stretch) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            records.forEachIndexed { i, r ->
                if (i > 0) Hairline(Modifier.fillMaxWidth(), color = HN.lineSoft)
                r()
            }
        }
    }
}

@Composable
private fun StreakRecord(s: Stats, agents: StatsAgents) {
    val res = wordsNow()
    val days = pluralStringResource(Plural.stats_streak_days, s.streak, s.streak)
    // One day isn't a run of anything to say a start of.
    val from = StatWords.streakStart(s.days, s.streak)?.takeIf { s.streak > 1 }
    val words = when (agents) {
        StatsAgents.Claude -> Str.stats_streak_detail
        StatsAgents.Both -> Str.stats_streak_detail_agents
        StatsAgents.Codex -> Str.stats_streak_detail_codex
    }
    Record(stringResource(Str.stats_streak), days, from?.let { { HText(stringResource(words, StatWords.weekday(res, it)), style = Type.small, color = HN.inkMuted) } })
}

@Composable
private fun LimitsRecord(s: Stats) {
    val kinds = listOf(
        Triple(s.limitsFiveHour, Str.stats_limit_five_only, Str.stats_limit_five),
        Triple(s.limitsWeek, Str.stats_limit_week_only, Str.stats_limit_week),
        Triple(s.limitsModel, Str.stats_limit_model_only, Str.stats_limit_model),
    ).filter { it.first > 0 }
    Record(stringResource(Str.stats_limits), limitsReached(s).toString()) {
        if (kinds.size == 1) HText(stringResource(kinds.single().second), style = Type.small, color = HN.inkMuted)
        else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((n, _, word) in kinds) Breakdown(stringResource(word), n.toString())
        }
    }
}

// ─── Work ───

private class WorkFigures(val name: Str, val total: Int, val parts: List<Pair<String, Int>>)

/** The work under the hours, in [tiles] across, then the tokens by kind. */
@Composable
internal fun WorkSection(s: Stats, modifier: Modifier = Modifier, tiles: Int = 2, stretch: Boolean = false) {
    val res = wordsNow()
    val all = listOf(
        WorkFigures(Str.stats_prompts, s.prompts, listOf(stringResource(Str.stats_from_phone) to s.fromPhone, stringResource(Str.stats_by_voice) to s.byVoice)),
        WorkFigures(Str.stats_tool_calls, s.toolCalls, s.tools.take(TOOLS_LISTED).map { it.name to it.count }),
        WorkFigures(Str.stats_files, s.filesEdited, emptyList()),
        WorkFigures(Str.stats_approvals, s.approvals, listOf(stringResource(Str.stats_ap_phone) to s.approvalsPhone, stringResource(Str.stats_ap_mac) to s.approvalsMac)),
    )
    StatsSection(stringResource(Str.stats_work), null, modifier, stretch) {
        Column(Modifier.padding(16.dp)) {
            all.chunked(tiles).forEachIndexed { i, row ->
                if (i > 0) Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    for (t in row) WorkFigureTile(t, Modifier.weight(1f))
                    repeat(tiles - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            // Only what the server itself sees has a start of its own.
            if (s.countingSince > s.from) {
                val since = StatWords.date(res, java.time.Instant.ofEpochMilli(s.countingSince).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
                Spacer(Modifier.height(14.dp))
                HText(stringResource(Str.stats_counted_what, since), style = Type.small, color = HN.inkDim)
            }
            if (s.tokens.total > 0L) {
                Spacer(Modifier.height(16.dp))
                Hairline(Modifier.fillMaxWidth(), color = HN.lineSoft)
                Spacer(Modifier.height(16.dp))
                TokenKinds(s)
            }
        }
    }
}

@Composable
private fun WorkFigureTile(t: WorkFigures, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { }) {
        HText(stringResource(t.name).uppercase(), style = Type.label, color = HN.inkMuted, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        HText(StatWords.whole(wordsNow(), t.total.toLong()), style = Type.mono.copy(fontSize = 20.sp, lineHeight = 24.sp), maxLines = 1)
        if (t.parts.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((name, n) in t.parts) Breakdown(name, StatWords.whole(wordsNow(), n.toLong()))
            }
        }
    }
}

/** What the token total is made of: the split first, since the point is how much of it is cache. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenKinds(s: Stats) {
    val res = wordsNow()
    val t = s.tokens
    val kinds = listOf(
        Triple(Str.stats_tok_out, t.output, HN.accent),
        Triple(Str.stats_tok_in, t.input, HN.accentDim),
        Triple(Str.stats_tok_cache_read, t.cacheRead, HN.inkMuted),
        Triple(Str.stats_tok_cache_write, t.cacheWrite, HN.lineStrong),
    )
    SplitTrack(kinds.map { (_, n, color) -> n to color })
    Spacer(Modifier.height(10.dp))
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((word, n, color) in kinds) {
            Column(Modifier.semantics(mergeDescendants = true) { }) {
                HText(StatWords.count(res, n), style = Type.monoSmall, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendPoint(color)
                    Spacer(Modifier.width(5.dp))
                    HText(stringResource(word).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                }
            }
        }
    }
}
