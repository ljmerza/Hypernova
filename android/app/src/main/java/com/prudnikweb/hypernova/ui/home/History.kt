package com.prudnikweb.hypernova.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.HistorySearch
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.Z
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import dev.chrisbanes.haze.hazeSource
import java.time.ZoneId

/**
 * Ended sessions, most recent first, but for the ones a restart of the Mac ended lately (Home keeps
 * those), under the days they ended on (Today, Yesterday, Mon 13 Oct). A search finds one by its
 * title, project or last line; with several projects, chips narrow it to one (audit P9). An
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
    val found = remember(ended, query, narrowed) { HistorySearch.find(ended, query, narrowed) }
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
        if (rows.isEmpty()) {
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
                if (row.automation != null) {
                    com.prudnikweb.hypernova.ui.automations.AutomationHistoryRow(hub, row, now, onClick = { onRuns(row.automation.id) }, modifier = Modifier.padding(horizontal = 16.dp).animateItem())
                } else {
                    SessionCard(s, now = now, scramble = false, onClick = { onOpen(s.id) }, modifier = Modifier.padding(horizontal = 16.dp).animateItem(), codex = s.codex && com.prudnikweb.hypernova.data.AgentLead.both(hub.claude, hub.codex))
                }
            }
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
