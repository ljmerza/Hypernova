package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext

/**
 * A session's chat on screen ([sessionId], null when none is) is read up to its last activity, and
 * stays read while replies come in: its Completed card on Home has no unread dot (App.kt).
 */
@Composable
fun MarkReadOnScreen(hub: Hub?, sessionId: String?) {
    val context = LocalContext.current
    val readAt = sessionId?.let { hub?.session(it)?.updatedAt }
    LaunchedEffect(hub, sessionId, readAt) {
        val server = hub?.let(ReadState::server)
        if (server != null && sessionId != null && readAt != null) ReadState.mark(context.storage, server, sessionId, readAt)
    }
}
