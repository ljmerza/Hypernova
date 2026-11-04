package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.SessionUsage
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Session

/**
 * The usage strip, once the Mac has answered (nothing before, so the header never shows zeros).
 * [usage] is the screen's ([rememberSessionUsage]): the session sheet ([onOpen]) shows it too.
 * After it, while calls refused without the owner wait, the blocked mark ([onBlocked]), with or
 * without the strip. [agents]: the agents at work, for the strip's count ([onAgents]: its tap).
 */
@Composable
internal fun ChatUsage(
    hub: Hub, session: Session, usage: SessionUsage?, agents: Int, git: SheetGit?, onCompact: () -> Unit, onOpen: () -> Unit, onAgents: () -> Unit, onBlocked: () -> Unit, more: String,
    modifier: Modifier = Modifier,
) {
    val blocked = session.blockedCalls
    if (usage == null && blocked == 0) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (usage != null) UsageStrip(hub, session, usage, agents, git, onCompact, onOpen, onAgents, more, Modifier.weight(1f))
        if (blocked > 0) {
            if (usage != null) Spacer(Modifier.width(8.dp))
            BlockedMark(blocked, onBlocked)
        }
    }
}

