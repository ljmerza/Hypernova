package com.prudnikweb.hypernova.ui.stats

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.StatWords
import com.prudnikweb.hypernova.data.StatsAgents
import com.prudnikweb.hypernova.data.StatsBoard
import com.prudnikweb.hypernova.data.StatsRange
import com.prudnikweb.hypernova.data.StatsServer
import com.prudnikweb.hypernova.data.StatsShown
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.problemText
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.platform.SharedUi
import com.prudnikweb.hypernova.ui.platform.pillPointer
import com.prudnikweb.hypernova.ui.platform.pluralStringResource
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.ui.platform.wordsNow
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

/*
 * Statistics, the parts both apps show (docs/DESIGN.md, "Statistics"). Each app puts them in its own place: the phone a page
 * of Settings with its bar (ui/stats/StatsPage.kt), the desktop a page under its top bar (desktop/ui/StatsPane.kt).
 */

/** A line longer than this is hard to read and a chart this wide says nothing more: a huge window gets margins. */
val STATS_WIDEST = 1680.dp
private val TWO_ACROSS = 720.dp
private val THREE_ACROSS = 1120.dp
private val BETWEEN = 16.dp
private val SECTIONS = 28.dp

private const val ASK_AGAIN_MS = 60_000L

/** Under this a range's word would be cut: they wrap to a second line instead. */
private val CHIP_LEAST = 56.dp

/** The board for [hub], or with [servers] for all of them, asking for the chosen range: at once, each minute, and when a link changes. */
@Composable
fun rememberStatsBoard(hub: Hub, servers: List<StatsServer>?): StatsBoard {
    val board = remember(hub, servers?.map { it.id }) { StatsBoard(hub, servers) }
    LaunchedEffect(board, board.range, board.links) {
        while (true) {
            board.ask()
            delay(ASK_AGAIN_MS)
        }
    }
    return board
}

/** What the numbers cover, for under the title; with All servers it says so first. Null while there's nothing to say it of. */
@Composable
fun statsSubtitle(board: StatsBoard): String? {
    val res = wordsNow()
    val covers = (board.shown as? StatsShown.Numbers)?.let { StatWords.covers(res, it.stats) }
    val all = res.getString(Str.servers_all)
    return when {
        board.all && covers != null -> res.getString(Str.stats_on_server, all, covers)
        board.all -> all
        else -> covers
    }
}

/** A small mark while a question is on its way; nothing otherwise. */
@Composable
fun StatsAsking(board: StatsBoard, modifier: Modifier = Modifier) {
    if (board.asking) Planet(Activity.Live, modifier)
}

private fun rangeWord(r: StatsRange): Str = when (r) {
    StatsRange.Today -> Str.stats_range_today
    StatsRange.Week -> Str.stats_range_week
    StatsRange.Month -> Str.stats_range_month
    StatsRange.Ytd -> Str.stats_range_ytd
    StatsRange.All -> Str.stats_range_all
}

/** The five ranges, sharing the width; too narrow for all of them whole, they wrap instead of cutting a word. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsRangeSwitch(board: StatsBoard, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in StatsRange.entries) {
            val on = board.range == r
            val press = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .weight(1f)
                    .widthIn(min = CHIP_LEAST)
                    .heightIn(min = 44.dp)
                    .glassChoice(on)
                    .pillPointer(press, enabled = true)
                    .clickable(press, indication = null, role = Role.Tab) { board.pick(r) }
                    .semantics { selected = on }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                HText(stringResource(rangeWord(r)), style = Type.small, color = if (on) HN.accent else HN.inkMuted, maxLines = 1)
            }
        }
    }
}

/**
 * Everything under the switch, scrolling by itself: [modifier] goes on what scrolls (the phone's blur reads it), [top] and
 * [bottom] are what it leaves clear. One column on a phone or a narrow window, in the order the sections go; wider, the same
 * order read across, so a wide window holds at a glance what a phone scrolls through.
 */
@Composable
fun StatsBody(board: StatsBoard, modifier: Modifier = Modifier, top: Dp = 0.dp, bottom: Dp = 0.dp, scroll: ScrollState = rememberScrollState()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val across = when {
            maxWidth >= THREE_ACROSS -> 3
            maxWidth >= TWO_ACROSS -> 2
            else -> 1
        }
        val tight = maxWidth < NARROW
        Column(modifier.fillMaxSize().verticalScroll(scroll).padding(top = top, bottom = bottom), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = STATS_WIDEST).fillMaxWidth().padding(horizontal = BETWEEN)) {
                when (val shown = board.shown) {
                    StatsShown.Reading -> Reading(board)
                    is StatsShown.Failed -> StatsFailed(board, shown)
                    is StatsShown.NeedsUpdate -> OneCard(pluralStringResource(Plural.stats_needs_update, shown.names.size, shown.names.joinToString(", ")))
                    StatsShown.TooOld -> OneCard(stringResource(Str.stats_old_mac))
                    is StatsShown.Numbers -> Numbers(board, shown, across, tight)
                }
            }
        }
    }
}

/** Below this a tile's two across would cut its figures. */
internal val NARROW = 300.dp

@Composable
private fun Reading(board: StatsBoard) {
    val words = when {
        board.all -> stringResource(Str.stats_loading_all)
        board.expected == StatsAgents.Codex -> stringResource(Str.stats_loading_codex, board.serverName)
        board.expected == StatsAgents.Both -> stringResource(Str.stats_loading_agents, board.serverName)
        else -> stringResource(Str.stats_loading, board.serverName)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Planet(Activity.Live)
        Spacer(Modifier.width(10.dp))
        HText(words, style = Type.small, color = HN.inkMuted)
    }
}

@Composable
private fun StatsFailed(board: StatsBoard, shown: StatsShown.Failed) {
    val scope = rememberCoroutineScope()
    val res = wordsNow()
    GlassCard(Modifier.fillMaxWidth(), edge = HN.danger) {
        Column(Modifier.padding(16.dp)) {
            HText(stringResource(Str.stats_error), style = Type.heading)
            shown.problem?.let {
                Spacer(Modifier.height(4.dp))
                HText(problemText(res, it.problem), style = Type.small, color = HN.inkMuted)
            }
            Spacer(Modifier.height(12.dp))
            Pill(stringResource(Str.try_again), { scope.launch { board.ask(again = true) } }, kind = PillKind.Outline, height = 36.dp)
        }
    }
}

/** A word and nothing to do about it here. */
@Composable
private fun OneCard(words: String) {
    GlassCard(Modifier.fillMaxWidth(), edge = HN.amber) {
        HText(words, Modifier.padding(16.dp), style = Type.small)
    }
}

/** What these numbers leave out or how old they are, ahead of them: numbers must never look whole when they aren't. */
@Composable
private fun Notes(shown: StatsShown.Numbers) {
    val res = wordsNow()
    val lines = buildList {
        if (shown.missing.isNotEmpty()) add(pluralStringResource(Plural.stats_all_missing, shown.missing.size, shown.missing.joinToString(", ")) to HN.amber)
        if (shown.outdated.isNotEmpty()) add(pluralStringResource(Plural.stats_needs_update, shown.outdated.size, shown.outdated.joinToString(", ")) to HN.amber)
        shown.staleSince?.let { at ->
            val clock = SharedUi.platform.clock
            val style = clock.style()
            val pattern = clock.bestDatePattern(style.locale, if (style.is24Hour) "Hm" else "hm")
            add(stringResource(Str.stats_stale, SimpleDateFormat(pattern, style.locale).format(Date(at))) to HN.amber)
        }
        shown.stats.hoursSince?.let { add(stringResource(Str.stats_later, StatWords.dateWithYear(res, java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate())) to HN.inkMuted) }
    }
    if (lines.isEmpty()) return
    Column(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((words, color) in lines) HText(words, style = Type.small, color = color)
    }
}

/** One section and how wide it is among those beside it. */
private class Cell(val share: Float, val draw: @Composable (Modifier) -> Unit)

@Composable
private fun Numbers(board: StatsBoard, shown: StatsShown.Numbers, across: Int, tight: Boolean) {
    val s = shown.stats
    val agents = board.agents(s)
    Notes(shown)
    val hero = Cell(1f) { m -> Hero(s, agents, board.links.any { it }, m, stretch = across > 1) }
    val days = Cell(if (across == 3) 2f else 1f) { m -> DaysSection(s, m, stretch = across > 1) }
    val projects = if (s.projects.isEmpty()) null else Cell(1f) { m -> ProjectsSection(s, m, stretch = across > 1) }
    val records = if (!hasRecords(s)) null else Cell(1f) { m -> RecordsSection(s, agents, m, stretch = across > 1) }
    val work = Cell(1f) { m -> WorkSection(s, m, tiles = if (tight) 1 else if (across == 2) 4 else 2, stretch = across == 3) }
    val rows: List<List<Cell>> = when (across) {
        1 -> listOf(listOf(hero), listOf(days), listOfNotNull(projects), listOfNotNull(records), listOf(work))
        2 -> listOf(listOf(hero, days), listOfNotNull(projects, records), listOf(work))
        else -> listOf(listOf(hero, days), listOfNotNull(projects, records, work))
    }.filter { it.isNotEmpty() }
    rows.forEachIndexed { i, row ->
        if (i > 0) Spacer(Modifier.height(SECTIONS))
        if (row.size == 1) row.single().draw(Modifier.fillMaxWidth())
        else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(BETWEEN)) {
            for (c in row) c.draw(Modifier.weight(c.share).fillMaxHeight())
        }
    }
    Spacer(Modifier.height(SECTIONS))
    Footer(agents, shown.answered.joinToString(", "))
}

@Composable
private fun Footer(agents: StatsAgents, names: String) {
    val key = when (agents) {
        StatsAgents.Claude -> Str.stats_footer
        StatsAgents.Both -> Str.stats_footer_agents
        StatsAgents.Codex -> Str.stats_footer_codex
    }
    HText(stringResource(key, names), Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), style = Type.small.copy(textAlign = TextAlign.Center), color = HN.inkMuted)
}
