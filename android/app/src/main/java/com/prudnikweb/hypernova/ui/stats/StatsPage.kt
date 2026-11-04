package com.prudnikweb.hypernova.ui.stats

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.StatsServer
import com.prudnikweb.hypernova.ui.components.HomeMark
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.screenBottom
import androidx.compose.ui.res.stringResource

/**
 * Statistics on the phone: a page of Settings with the bar every page has, and in it the logo and the range switch, which stay
 * in view as the numbers scroll. [onHome]: the logo, to the Sessions list.
 */
@Composable
fun StatsScreen(hub: Hub, onBack: () -> Unit, servers: List<StatsServer>? = null, onHome: () -> Unit = {}) {
    val board = rememberStatsBoard(hub, servers)
    ScreenScaffold(
        sourceKey = "statistics",
        title = stringResource(R.string.stats_title),
        onBack = onBack,
        subtitle = statsSubtitle(board),
        lead = {
            Spacer(Modifier.width(4.dp))
            HomeMark(onHome, target = 30.dp)
        },
        actions = { StatsAsking(board) },
        // In line with the cards under it: the bar has its own smaller margins.
        below = { StatsRangeSwitch(board, Modifier.padding(start = 8.dp, end = 4.dp)) },
    ) { top, source ->
        StatsBody(board, source, top = top + 4.dp, bottom = screenBottom() + 32.dp)
    }
}
