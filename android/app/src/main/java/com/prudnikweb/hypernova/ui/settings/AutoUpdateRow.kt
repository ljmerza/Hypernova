package com.prudnikweb.hypernova.ui.settings

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.ServerUpdate
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.ToggleRow
import kotlinx.coroutines.launch

/**
 * The server section's **Update this server automatically** (docs/PROTOCOL.md, "The server's own
 * update"; owner, 2026-10-31): on unless turned off. Its line says what it does, or why this install
 * can't (a checkout, a container), what it is doing now or what failed, and the last update it installed.
 */
@Composable
internal fun AutoUpdateRow(hub: Hub, update: ServerUpdate) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    ToggleRow(context.getString(R.string.su_title), autoUpdateLine(context, update), update.auto, { want ->
        if (busy) return@ToggleRow
        busy = true
        scope.launch {
            hub.setAutoUpdate(want).onFailure { toast(it.message.orEmpty()) }
            busy = false
        }
    })
}

/** The row's line: what it does (or why not here), then what it's doing, then the last update. */
internal fun autoUpdateLine(context: android.content.Context, u: ServerUpdate): String {
    val now = when {
        u.state == ServerUpdate.INSTALLING && u.target != null -> context.getString(R.string.su_installing, u.target)
        u.state == ServerUpdate.WAITING && u.target != null -> context.getString(R.string.su_waiting, u.target)
        u.failure != null -> com.prudnikweb.hypernova.data.serverUpdateFailedText(context.resources, u.failure)
        !u.available && u.reason != null -> context.getString(R.string.su_unavailable, u.reason)
        else -> context.getString(R.string.su_desc)
    }
    val last = u.last?.let {
        context.getString(R.string.su_last, it.to, it.from, DateUtils.getRelativeTimeSpanString(it.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
    }
    return listOfNotNull(now, last).joinToString("\n")
}
