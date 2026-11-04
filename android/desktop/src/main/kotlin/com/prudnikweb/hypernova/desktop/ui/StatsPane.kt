package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.StatsServer
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.ui.stats.STATS_WIDEST
import com.prudnikweb.hypernova.ui.stats.StatsAsking
import com.prudnikweb.hypernova.ui.stats.StatsBody
import com.prudnikweb.hypernova.ui.stats.StatsRangeSwitch
import com.prudnikweb.hypernova.ui.stats.rememberStatsBoard
import com.prudnikweb.hypernova.ui.stats.statsSubtitle
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/** The tag of Statistics' page, for the tests. */
const val STATS_TAG = "statistics"

/** How wide the switch gets: past this its five words sit too far apart to read as one thing. */
private val SWITCH_WIDEST = 520.dp
private val SWITCH_BESIDE = 720.dp

/**
 * Statistics under the top bar, across the whole window rather than in the pane beside the list: they're about the server, not
 * about a session, and the list has nothing to add. The top bar's server pills choose whose, with All servers beside them.
 */
@Composable
internal fun StatsPane(hub: Hub, shell: Shell, modifier: Modifier = Modifier) {
    val servers = if (shell.statsAll) Core.everyHub.map { (st, h) -> StatsServer(st.id, st.name, h) } else null
    val board = rememberStatsBoard(hub, servers)
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize().testTag(STATS_TAG)) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
                val title = @Composable { m: Modifier ->
                    Column(m) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HText(Ui.stats_title.text(), Modifier.semantics { heading() }, style = Type.title, maxLines = 1)
                            StatsAsking(board)
                        }
                        statsSubtitle(board)?.let { HText(it, style = Type.monoSmall, color = HN.inkDim, maxLines = 1) }
                    }
                }
                // In line with the numbers under it, however wide; a narrow window has the switch on a line of its own.
                val line = Modifier.widthIn(max = STATS_WIDEST).fillMaxWidth()
                if (maxWidth >= SWITCH_BESIDE) Row(line, verticalAlignment = Alignment.CenterVertically) {
                    title(Modifier.weight(1f))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { StatsRangeSwitch(board, Modifier.widthIn(max = SWITCH_WIDEST)) }
                } else Column(line) {
                    title(Modifier)
                    Spacer(Modifier.height(8.dp))
                    StatsRangeSwitch(board)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                StatsBody(board, top = 4.dp, bottom = 24.dp, scroll = scroll)
                VerticalScrollbar(
                    rememberScrollbarAdapter(scroll),
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
                    style = LocalScrollbarStyle.current.copy(unhoverColor = HN.ink.copy(alpha = 0.12f), hoverColor = HN.ink.copy(alpha = 0.3f)),
                )
            }
        }
    }
}
