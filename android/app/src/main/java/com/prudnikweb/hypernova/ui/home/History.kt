package com.prudnikweb.hypernova.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.HistorySearch
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.TextHit
import com.prudnikweb.hypernova.data.TextHits
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.SessionStamp
import com.prudnikweb.hypernova.ui.components.sessionSaid
import com.prudnikweb.hypernova.ui.components.Z
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import dev.chrisbanes.haze.hazeSource
import java.time.ZoneId
import kotlinx.coroutines.delay

/**
 * Ended sessions, most recent first, but for the ones a restart of the Mac ended lately (Home keeps
 * those), under the days they ended on (Today, Yesterday, Mon 13 Oct). A search finds one by its
 * title, project or last line, and from 3 characters by the words inside it, which the current
 * Mac looks for ([Hub.searchHistory]; a hit shows its snippet, [HitCard]); with several projects,
 * chips narrow it to one (audit P9). An
 * automation's sessions fold into one row where its newest is; [onRuns] opens its runs.
 */
@Composable
internal fun HistoryTab(hub: Hub, top: Dp, bottom: Dp, now: Long, onOpen: (String) -> Unit, onRuns: (String) -> Unit) {
    val haze = LocalHaze.current
    val ended = remember(hub.sessions) { hub.sessions.filter { it.phase == Phase.Ended && !it.cutByRestart } }
    var query by rememberSaveable { mutableStateOf("") }
    var project by rememberSaveable { mutableStateOf<String?>(null) }
    val projects = remember(ended) { HistorySearch.projects(ended) }
    // A project chip that has gone (its sessions aged out) no longer narrows anything.
    val narrowed = project?.takeIf { it in projects }
    val local = remember(ended, query, narrowed) { HistorySearch.find(ended, query, narrowed) }
    // From 3 characters, once typing pauses, the Mac looks inside the conversations too (idea HI1).
    // An older Mac (null) or none reachable leaves the phone's own filter alone.
    val asked = query.trim()
    val canAsk = hub.link.state == LinkState.Connected
    var unsupported by remember(hub) { mutableStateOf(false) }
    var hits by remember(hub) { mutableStateOf<Pair<String, TextHits>?>(null) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(hub, asked, canAsk, unsupported) {
        if (!HistorySearch.wantsFullText(asked) || !canAsk || unsupported) {
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(HistorySearch.FULL_TEXT_DEBOUNCE_MS)
        hub.searchHistory(asked)
            .onSuccess { if (it == null) unsupported = true else hits = asked to it }
            .onFailure { hits = null }
        searching = false
    }
    val text = hits?.takeIf { it.first == asked && HistorySearch.wantsFullText(asked) && canAsk }?.second
    val found = remember(ended, local, text, narrowed) { HistorySearch.merge(ended, local, text, narrowed) }
    val rows = remember(found) { com.prudnikweb.hypernova.ui.automations.foldRuns(found) }
    val zone = remember { ZoneId.systemDefault() }
    val locale = LocalConfiguration.current.locales[0]
    val today = HistorySearch.day(now, zone)
    val todayWord = stringResource(R.string.history_today)
    val yesterdayWord = stringResource(R.string.history_yesterday)
    LazyColumn(
        Modifier.fillMaxSize().hazeSource(haze, zIndex = Z.CONTENT, key = HOME_SOURCE),
        contentPadding = PaddingValues(top = top + 84.dp, bottom = bottom + 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "seam") {
            Seam(
                stringResource(R.string.history),
                trail = stringResource(R.string.history_trail, ended.size),
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp),
            )
        }
        if (ended.isEmpty()) {
            item(key = "empty") {
                HText(
                    stringResource(R.string.history_empty), style = Type.title, color = HN.inkMuted,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            return@LazyColumn
        }
        item(key = "search") {
            SearchField(query, { query = it }, stringResource(R.string.history_search), Modifier.padding(horizontal = 16.dp))
        }
        if (projects.isNotEmpty()) {
            item(key = "projects") { ProjectChips(projects, narrowed) { project = if (narrowed == it) null else it } }
        }
        // A quiet line while the Mac looks, and when it couldn't look everywhere in time.
        val note = when {
            searching -> R.string.history_searching
            text?.partial == true -> R.string.history_partial
            else -> null
        }
        if (note != null) {
            item(key = "searching") {
                HText(
                    stringResource(note), style = Type.small, color = HN.inkDim,
                    modifier = Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (rows.isEmpty() && !searching) {
            item(key = "none") {
                HText(
                    if (query.isBlank()) stringResource(R.string.history_none_in, narrowed.orEmpty()) else stringResource(R.string.history_no_match, query.trim()),
                    style = Type.small, color = HN.inkDim,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
        }
        var lastDay: java.time.LocalDate? = null
        for (row in rows) {
            val day = HistorySearch.day(row.session.updatedAt, zone)
            if (day != lastDay) {
                lastDay = day
                item(key = "day:$day") {
                    HText(
                        HistorySearch.label(day, today, todayWord, yesterdayWord, locale).uppercase(),
                        style = Type.label, color = HN.inkDim, maxLines = 1,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp).semantics { heading() }.animateItem(),
                    )
                }
            }
            item(key = row.key) {
                val s = row.session
                val hit = text?.of(s.id)
                if (row.automation != null) {
                    com.prudnikweb.hypernova.ui.automations.AutomationHistoryRow(hub, row, now, onClick = { onRuns(row.automation.id) }, modifier = Modifier.padding(horizontal = 16.dp).animateItem())
                } else if (hit != null) {
                    HitCard(s, hit, now, onClick = { onOpen(s.id) }, modifier = Modifier.padding(horizontal = 16.dp).animateItem())
                } else {
                    SessionCard(s, now = now, scramble = false, onClick = { onOpen(s.id) }, modifier = Modifier.padding(horizontal = 16.dp).animateItem(), codex = s.codex && com.prudnikweb.hypernova.data.AgentLead.both(hub.claude, hub.codex))
                }
            }
        }
    }
}

/**
 * A session the Mac found by the words inside it: its project, id and age, its name, and the
 * snippet around the first hit with the words in the accent, in place of the last line.
 */
@Composable
private fun HitCard(s: Session, hit: TextHit, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, onClickLabel = stringResource(R.string.open_session)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Seam(
                label = s.project,
                trailContent = { SessionStamp(s.hex, ago(s.updatedAt, now), sessionSaid(res, s.hex, s.updatedAt, now)) },
            )
            Spacer(Modifier.height(12.dp))
            HText(s.name, style = Type.heading, color = HN.ink, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            val accent = HN.accent
            val snippet = remember(hit, accent) {
                buildAnnotatedString {
                    append(hit.snippet)
                    for (m in hit.marks) addStyle(SpanStyle(color = accent), m.first, m.last + 1)
                }
            }
            HText(snippet, style = Type.monoSmall, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** History's projects as glass chips in one sideways row; the chosen one is lit, and a tap on it again shows all. */
@Composable
private fun ProjectChips(projects: List<String>, chosen: String?, onPick: (String) -> Unit) {
    val view = LocalView.current
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (p in projects) {
            val on = p == chosen
            HText(
                p.uppercase(), style = Type.label, color = if (on) HN.accent else HN.inkMuted, maxLines = 1,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .clip(CircleShape)
                    .glassChoice(on)
                    .semantics { selected = on }
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onPick(p)
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}
