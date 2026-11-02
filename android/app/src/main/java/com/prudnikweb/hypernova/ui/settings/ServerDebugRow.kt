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
import com.prudnikweb.hypernova.data.ServerDebug
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.ToggleRow
import kotlinx.coroutines.launch

/**
 * The server section's **Server debug log** (docs/PROTOCOL.md, "The server's debug log"; owner,
 * 2026-11-02): the switch and when it ends. Not the app's own logs, which have their own place: what
 * this turns on is written on the server and stays there.
 */
@Composable
internal fun ServerDebugRow(hub: Hub, debug: ServerDebug) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    ToggleRow(context.getString(R.string.sd_title), serverDebugLine(context, debug), debug.on, { want ->
        if (busy) return@ToggleRow
        busy = true
        scope.launch {
            hub.setServerDebug(want).onFailure { toast(it.message.orEmpty()) }
            busy = false
        }
    })
}

/** The row's line: when it ends, or what it does. */
internal fun serverDebugLine(context: android.content.Context, d: ServerDebug): String =
    if (d.on && d.until != null) {
        context.getString(R.string.sd_on, DateUtils.formatDateTime(context, d.until, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_TIME))
    } else context.getString(R.string.sd_off)
