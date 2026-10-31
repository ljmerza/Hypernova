package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.ui.components.GlyphFigure
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.Role
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionAutomation
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.home.ago
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/** A History row: one ended session, or ([automation] set) all of one automation's, by its newest [session]. */
class HistoryEntry(val key: String, val session: Session, val automation: SessionAutomation?, val count: Int)

/**
 * History's rows: an automation's ended sessions fold into one, placed by its newest run, so a
 * daily brief doesn't bury the owner's own sessions. Their own grouping; the sky shows them as
 * ordinary moons of their project.
 */
fun foldRuns(ended: List<Session>): List<HistoryEntry> {
    val counts = ended.mapNotNull { it.automation?.id }.groupingBy { it }.eachCount()
    val seen = HashSet<String>()
    return ended.mapNotNull { s ->
        val a = s.automation
        when {
            a == null -> HistoryEntry(s.id, s, null, 1)
            seen.add(a.id) -> HistoryEntry("auto:${a.id}", s, a, counts[a.id] ?: 1)
            else -> null
        }
    }
}

/**
 * "◉ MORNING BRIEF ─── 2h" over "12 runs  ◷ Today 9:00" and the AUTO chip; a tap opens its runs.
 * [server]: History with "All servers" names its computer after the name.
 */
@Composable
fun AutomationHistoryRow(hub: Hub, e: HistoryEntry, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier, server: String? = null) {
    val a = e.automation ?: return
    val w = rememberWords()
    val live = hub.automations.automation(a.id)
    // Its automation is gone (the list has come and doesn't have it): a tap still lists its sessions (A2).
    val gone = live == null && hub.automations.state != null
    val runs = live?.runCount?.takeIf { it > 0 } ?: e.count
    val zone = zoneOf(hub.automations.state?.tz)
    val last = live?.lastRun?.let { it.startedAt ?: it.firedAt } ?: e.session.createdAt.takeIf { it > 0 } ?: e.session.updatedAt
    GlassCard(modifier.fillMaxWidth(), onClick = onClick, onClickLabel = stringResource(R.string.au_runs)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Seam(live?.name ?: a.name, trail = ago(e.session.updatedAt, now), activity = Activity.Rest, after = server)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // How many runs, then the last one's time after a clock, as a session stamp has its age.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    HText(pluralStringResource(R.plurals.au_history_runs, runs, runs), style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
                    Spacer(Modifier.width(12.dp))
                    GlyphFigure(Glyph.Clock, RunWords.at(w, last, now, zone), color = HN.inkDim)
                    if (gone) {
                        Spacer(Modifier.width(12.dp))
                        HText(stringResource(R.string.au_history_deleted), style = Type.small, color = HN.inkDim, maxLines = 1)
                    }
                }
                AutoChip()
            }
        }
    }
}

/** The session screen's AUTO chip and "MORNING BRIEF ›" over its status line: a tap opens the automation. */
@Composable
fun AutoLine(name: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val said = stringResource(R.string.au_session_line_said, name)
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.au_open_automation), onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutoChip(Modifier.clearAndSetSemantics { })
        Spacer(Modifier.width(8.dp))
        HText(name.uppercase(), style = Type.label, color = HN.accentDim, maxLines = 1, modifier = Modifier.semantics { contentDescription = said })
        Spacer(Modifier.width(6.dp))
        Icon(Glyph.Chevron, size = 10.dp, color = HN.accentDim)
    }
}
