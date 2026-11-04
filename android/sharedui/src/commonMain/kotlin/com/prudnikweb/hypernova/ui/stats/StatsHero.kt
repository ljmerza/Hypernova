package com.prudnikweb.hypernova.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.data.StatWords
import com.prudnikweb.hypernova.data.Stats
import com.prudnikweb.hypernova.data.StatsAgents
import com.prudnikweb.hypernova.data.StatsRange
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.ui.platform.wordsNow
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

/** The hero's digits, with their units small and quiet beside them. */
private val BIG = Type.mono.copy(fontSize = 46.sp, lineHeight = 52.sp, letterSpacing = (-1).sp)

/** The hero's little table doesn't stretch across a wide card: its numbers would leave their names behind. */
private val NOTES_WIDEST = 260.dp

/** [value], gliding from the last value to a new one while the server is there to have sent it, and only then. */
@Composable
private fun glide(value: Long, linked: Boolean): Long {
    val reduced = LocalMotion.current.reduced
    val shown by animateFloatAsState(value.toFloat(), if (linked && !reduced) tween(Motion.REVEAL, easing = Motion.Expo) else snap(), label = "stat")
    return if (shown == value.toFloat()) value else shown.toLong()
}

/**
 * The big number: how long the agents worked, whose it was with both at work, then the two other ways to read time as a small
 * table, and the three figures of scale. TalkBack hears it as one sentence.
 */
@Composable
internal fun Hero(s: Stats, agents: StatsAgents, linked: Boolean, modifier: Modifier = Modifier, stretch: Boolean = false) {
    val res = wordsNow()
    val (word, sentenceWords) = when (agents) {
        StatsAgents.Claude -> Str.stats_claude_worked to Str.a11y_stats_hero
        StatsAgents.Both -> Str.stats_agents_worked to Str.a11y_stats_hero_agents
        StatsAgents.Codex -> Str.stats_codex_worked to Str.a11y_stats_hero_codex
    }
    val tokens = s.tokens.total
    val said = buildList {
        add(res.getString(sentenceWords, StatWords.timeSaid(res, s.claudeMs), StatWords.count(res, tokens), s.sessions, s.agents))
        add(res.getString(Str.stats_by_clock, StatWords.timeSaid(res, s.wallMs)) + ".")
        add(res.getString(Str.stats_agent_work, StatWords.timeSaid(res, s.agentMs)) + ".")
        if (agents == StatsAgents.Both) add(res.getString(Str.a11y_stats_split, StatWords.timeSaid(res, s.claudeOwnMs), StatWords.timeSaid(res, s.codexOwnMs)))
    }.joinToString(" ")
    val heading = stringResource(word)
    val inside: @Composable (Modifier) -> Unit = { m ->
        Column(m.padding(20.dp)) {
            if (!stretch) {
                HText(heading.uppercase(), style = Type.label, color = HN.inkMuted)
                Spacer(Modifier.height(10.dp))
            }
            BigTime(glide(s.claudeMs, linked))
            if (s.claudeMs == 0L) {
                Spacer(Modifier.height(4.dp))
                HText(stringResource(if (s.range == StatsRange.Today) Str.stats_quiet_today else Str.stats_quiet), style = Type.small, color = HN.inkMuted)
            } else {
                if (agents == StatsAgents.Both) {
                    Spacer(Modifier.height(12.dp))
                    AgentSplit(s)
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.widthIn(max = NOTES_WIDEST), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Breakdown(stringResource(Str.stats_by_clock_row), StatWords.time(res, s.wallMs))
                    Breakdown(stringResource(Str.stats_agent_work_row), StatWords.time(res, s.agentMs))
                }
            }
            if (stretch) Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(16.dp))
            Hairline(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ScaleFigure(StatWords.count(res, glide(tokens, linked)), stringResource(Str.stats_tokens), Modifier.weight(1f))
                ScaleFigure(StatWords.whole(res, glide(s.sessions.toLong(), linked)), stringResource(Str.stats_sessions), Modifier.weight(1f))
                ScaleFigure(StatWords.whole(res, glide(s.agents.toLong(), linked)), stringResource(Str.stats_agents), Modifier.weight(1f))
            }
        }
    }
    val sentence = Modifier.clearAndSetSemantics { contentDescription = said }
    // On its own it says what it is inside the card; beside other sections, on a seam as they do, so their cards line up.
    if (stretch) StatsSection(heading, null, modifier.then(sentence), stretch = true) { inside(Modifier.fillMaxHeight()) }
    else GlassCard(modifier.then(sentence)) { inside(Modifier) }
}

/** `12h 3m`, the digits big. Digits and units come apart by what they are, so it works in any language's letters. */
@Composable
private fun BigTime(ms: Long) {
    val words = StatWords.time(wordsNow(), ms)
    val text = buildAnnotatedString {
        for (c in words) {
            if (c.isDigit() || c == ' ') append(c)
            else withStyle(SpanStyle(fontSize = 26.sp, color = HN.inkDim)) { append(c) }
        }
    }
    HText(text, style = BIG, color = HN.ink, maxLines = 1)
}

@Composable
private fun ScaleFigure(figure: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        HText(figure, style = Type.mono.copy(fontSize = 22.sp, lineHeight = 26.sp), maxLines = 1)
        Spacer(Modifier.height(4.dp))
        HText(label.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
    }
}

/** A name on the left and its figure on the right, small: the hero's notes, a tile's parts, limits of several kinds. */
@Composable
internal fun Breakdown(name: String, figure: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HText(name, Modifier.weight(1f), style = Type.small, color = HN.inkMuted, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        HText(figure, style = Type.monoSmall, color = HN.ink, maxLines = 1)
    }
}

/** Parts of a whole on a hairline track: 3dp, a small gap between. Parts with nothing aren't drawn. */
@Composable
internal fun SplitTrack(parts: List<Pair<Long, Color>>, modifier: Modifier = Modifier) {
    val whole = parts.sumOf { it.first }.toFloat()
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(HN.line, Offset(0f, size.height / 2f - 0.5f), Size(size.width, 1f))
        if (whole <= 0f) return@Canvas
        val seen = parts.filter { it.first > 0L }
        val gap = 4.dp.toPx()
        val room = size.width - gap * (seen.size - 1)
        var x = 0f
        for ((n, color) in seen) {
            val w = room * (n / whole)
            drawRoundRect(color, Offset(x, 0f), Size(w, size.height), r)
            x += w + gap
        }
    }
}

/** A still point of light for a legend. */
@Composable
internal fun LegendPoint(color: Color) {
    Box(Modifier.size(5.dp).background(color, CircleShape))
}

@Composable
private fun AgentSplit(s: Stats) {
    val res = wordsNow()
    SplitTrack(listOf(s.claudeOwnMs to HN.accent, s.codexOwnMs to HN.inkMuted))
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        for ((word, ms, color) in listOf(Triple(Str.claude, s.claudeOwnMs, HN.accent), Triple(Str.stats_agent_codex, s.codexOwnMs, HN.inkMuted))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendPoint(color)
                Spacer(Modifier.width(6.dp))
                HText(stringResource(word).uppercase(), style = Type.label, color = HN.inkMuted, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                HText(StatWords.time(res, ms), style = Type.monoSmall, maxLines = 1)
            }
        }
    }
}
