package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** The Mac's full-text answer for the query typed now ([hits], null while there's none) and whether it's being asked ([searching]). */
class FullText(val hits: TextHits?, val searching: Boolean)

/**
 * The words inside the conversations, for History's search and Home's: from 3 characters, once
 * typing pauses, the current Mac is asked (idea HI1, [Hub.searchHistory]). An older Mac (null)
 * turns it off until this leaves the screen; none reachable, or a failure, leaves the phone's own
 * filter alone. Hits only ever answer the query they were asked for.
 */
@Composable
fun rememberFullText(hub: Hub, query: String): FullText {
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
    return FullText(hits?.takeIf { it.first == asked && HistorySearch.wantsFullText(asked) && canAsk }?.second, searching)
}
