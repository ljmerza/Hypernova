package com.prudnikweb.hypernova.data

import kotlinx.coroutines.launch

/**
 * Backpack's "+2 h" from a banner or a notification button (its end moves 2 hours on). A refusal
 * (too early, Backpack already over) is the Mac's own words in a toast, via [onFailure].
 */
fun HubViewModel.extendBackpack(onFailure: (String) -> Unit = {}) {
    val h = hub ?: return
    appScope.launch { h.extendAwake().onFailure { onFailure(it.message.orEmpty()) } }
}
