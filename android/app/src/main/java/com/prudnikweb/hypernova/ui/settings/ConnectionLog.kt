package com.prudnikweb.hypernova.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ConnEntry
import com.prudnikweb.hypernova.data.ConnKind
import com.prudnikweb.hypernova.data.ConnLog
import com.prudnikweb.hypernova.data.ConnResult
import com.prudnikweb.hypernova.data.ConnText
import com.prudnikweb.hypernova.data.RouteKind
import com.prudnikweb.hypernova.data.RouteReport
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.StatusChip
import com.prudnikweb.hypernova.ui.components.Stamp
import com.prudnikweb.hypernova.ui.components.frostedGlass
import com.prudnikweb.hypernova.ui.onboarding.HomeNetIcon
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/*
 * Settings → Mac → Connection log (data/ConnLog.kt): what the app tried to reach the Mac, newest
 * first, drawn: each probe round in a glass card of its own (the network it saw at the bottom, the
 * routes' answers, the route chosen on top), each line with its age in mono, the route as a tag
 * (the home network's arcs, a globe for the base URL), the answer as a state
 * chip (mint, red, or quiet) and where and how long in dim mono. Copy puts the whole log on the
 * clipboard as plain text, oldest first, to send.
 */

@Composable
fun ConnectionLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val version = ConnLog.version
    val entries = remember(version) { ConnLog.entries() }
    val rounds = remember(entries) { roundsOf(entries) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { value = System.currentTimeMillis(); delay(5_000) }
    }
    val copied = stringResource(R.string.connlog_copied)
    val header = stringResource(R.string.connlog_header, BuildConfig.VERSION_NAME, DateFormat.getDateTimeInstance().format(Date()))
    ScreenScaffold(
        sourceKey = "connlog",
        title = stringResource(R.string.connlog_title),
        onBack = onBack,
        subtitle = stringResource(R.string.connlog_subtitle, entries.size),
        actions = {
            Pill(
                stringResource(R.string.connlog_copy),
                {
                    val text = ConnText.copy(ConnLog.entries(), header)
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Hypernova connection log", text))
                    toast(copied)
                },
                kind = PillKind.Outline, height = 36.dp,
            )
        },
    ) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        HText(stringResource(R.string.connlog_empty), style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(16.dp))
                    }
                }
            }
            items(rounds) { round ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        round.forEachIndexed { i, e ->
                            if (i > 0) Hairline(Modifier.fillMaxWidth(), color = HN.lineSoft)
                            ConnRow(e, now)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The entries, newest first, cut into rounds: each round starts (in time) with the network a probe
 * round saw, so a card holds that round and what followed it until the next.
 */
private fun roundsOf(newestFirst: List<ConnEntry>): List<List<ConnEntry>> {
    val out = mutableListOf<List<ConnEntry>>()
    var cur = mutableListOf<ConnEntry>()
    for (e in newestFirst) {
        cur += e
        if (e.kind == ConnKind.Network) {
            out += cur
            cur = mutableListOf()
        }
    }
    if (cur.isNotEmpty()) out += cur
    return out
}

/** One line: its age, what it's about (the route's tag or the kind's word), the answer as a chip, the phrase, and where and how long. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnRow(e: ConnEntry, now: Long) {
    val ago = ConnText.ago(now, e.at)
    val kind = kindWord(e.kind)
    val route = e.route?.let { routeWord(it) }
    val result = resultWord(e.result)
    val said = stringResource(R.string.connlog_row_said, ago, listOfNotNull(kind, route, result, e.text, e.target, e.ms?.let { "$it ms" }).joinToString(", "))
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 8.dp).clearAndSetSemantics { contentDescription = said },
        verticalAlignment = Alignment.Top,
    ) {
        HText(ago, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, modifier = Modifier.widthIn(min = 34.dp).padding(top = 3.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                HText(kind.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1, modifier = Modifier.padding(top = 5.dp, end = 2.dp))
                if (e.route != null) RouteTag(e.route)
                if (result != null) StatusChip(result, resultColor(e.result))
            }
            if (e.text != null) {
                Spacer(Modifier.height(4.dp))
                HText(e.text, style = Type.small, color = if (e.kind == ConnKind.Route) HN.ink else HN.inkMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (e.target != null || e.ms != null) {
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.target != null) HText(e.target, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                    if (e.target != null && e.ms != null) Spacer(Modifier.width(10.dp))
                    if (e.ms != null) Stamp(Glyph.Timer, msWords(e.ms))
                }
            }
        }
    }
}

/** A route as a small tag: its glyph and its name in tracked caps (the route chip's family, smaller). */
@Composable
fun RouteTag(route: RouteKind, modifier: Modifier = Modifier, trail: String? = null, trailColor: Color = HN.inkDim) {
    Row(
        modifier
            .frostedGlass(if (route == RouteKind.Remote) null else HN.accent, glowX = 12.dp)
            .heightIn(min = 22.dp)
            .padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (route) {
            RouteKind.Local -> HomeNetIcon(HN.accent)
            RouteKind.Remote -> Icon(Glyph.Globe, size = 12.dp, color = HN.inkDim, stroke = 1.2.dp)
        }
        Spacer(Modifier.width(5.dp))
        val caps = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.6f)
        HText(routeWord(route).uppercase(), style = caps, color = HN.inkMuted, maxLines = 1)
        if (trail != null) {
            Spacer(Modifier.width(6.dp))
            HText(trail.uppercase(), style = caps, color = trailColor, maxLines = 1)
        }
    }
}

/**
 * The offline card's row of what the last round tried when nothing answered: a tag per route with
 * its answer after it (`LOCAL  REFUSED`, `LOCAL  NOT TRIED`), and the base URL's when [showBase].
 * TalkBack hears one sentence.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteTriedRow(report: RouteReport, showBase: Boolean, modifier: Modifier = Modifier) {
    val shown = report.tries.filter { it.route != RouteKind.Remote || showBase }
        .groupBy { it.route }.mapNotNull { (_, list) -> list.lastOrNull() }
        .sortedBy { it.route.ordinal }
    if (shown.isEmpty()) return
    val parts = shown.map { "${routeWord(it.route)} ${(resultWord(it.result) ?: "").lowercase()}".trim() }
    val said = stringResource(R.string.route_tried_said, parts.joinToString(", "))
    FlowRow(
        modifier.clearAndSetSemantics { contentDescription = said },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (t in shown) RouteTag(t.route, trail = resultWord(t.result), trailColor = resultColor(t.result))
    }
}

@Composable
private fun kindWord(k: ConnKind): String = stringResource(
    when (k) {
        ConnKind.Network -> R.string.connlog_kind_network
        ConnKind.Probe -> R.string.connlog_kind_probe
        ConnKind.Route -> R.string.connlog_kind_route
        ConnKind.Link -> R.string.connlog_kind_link
        ConnKind.Socket -> R.string.connlog_kind_socket
        ConnKind.Wait -> R.string.connlog_kind_wait
        ConnKind.Mac -> R.string.connlog_kind_mac
    },
)

@Composable
private fun routeWord(r: RouteKind): String = stringResource(
    when (r) {
        RouteKind.Local -> R.string.connlog_route_local
        RouteKind.Remote -> R.string.connlog_route_base
    },
)

/** The answer in a word or two; null for news without a verdict. */
@Composable
private fun resultWord(r: ConnResult): String? = when (r) {
    ConnResult.Ok -> stringResource(R.string.connlog_result_ok)
    ConnResult.Refused -> stringResource(R.string.connlog_result_refused)
    ConnResult.Timeout -> stringResource(R.string.connlog_result_timeout)
    ConnResult.Unreachable -> stringResource(R.string.connlog_result_unreachable)
    ConnResult.NoName -> stringResource(R.string.connlog_result_no_name)
    ConnResult.PinWrong -> stringResource(R.string.connlog_result_pin_wrong)
    ConnResult.PinExpired -> stringResource(R.string.connlog_result_pin_expired)
    ConnResult.Tls -> stringResource(R.string.connlog_result_tls)
    ConnResult.Http -> stringResource(R.string.connlog_result_http)
    ConnResult.Closed -> stringResource(R.string.connlog_result_closed)
    ConnResult.Failed -> stringResource(R.string.connlog_result_failed)
    ConnResult.Skipped -> stringResource(R.string.connlog_result_skipped)
    ConnResult.Info -> null
}

/** Mint for an answer, red for a failure, amber for a socket the Mac closed, quiet for not tried. */
private fun resultColor(r: ConnResult): Color = when {
    r == ConnResult.Ok -> HN.accent
    r == ConnResult.Skipped || r == ConnResult.Info -> HN.inkDim
    r == ConnResult.Closed -> HN.amber
    else -> HN.danger
}

private fun msWords(ms: Long): String = if (ms < 1000) "$ms ms" else "%.1f s".format(ms / 1000.0)
