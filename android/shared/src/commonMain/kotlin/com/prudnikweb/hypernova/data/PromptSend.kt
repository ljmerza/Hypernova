package com.prudnikweb.hypernova.data

/**
 * One prompt on its way, on both systems: [text] and [files] to the session. Went: [onSwitch] if it went on in another session. Stopped by text waiting in its
 * terminal: [onBusy]. Otherwise [onFail] with why. Whatever didn't go, cancelled too, goes back into the box it came from, or the box of the session the
 * conversation went on in, even if another session is on screen by now.
 */
suspend fun sendPrompt(
    hub: Hub,
    sessionId: String,
    text: String,
    files: List<DraftFile>,
    replace: Boolean,
    onSwitch: (String) -> Unit,
    onBusy: (String) -> Unit,
    onFail: (String) -> Unit,
) {
    var sent = false
    // The conversation went on with the other agent: the words wait in that session's box.
    var movedTo: String? = null
    try {
        hub.send(sessionId, text, files.mapNotNull { it.attachment }, replace)
            .onSuccess { id ->
                sent = true
                if (id != sessionId) onSwitch(id)
            }
            .onFailure { e ->
                when (e) {
                    is TerminalBusy -> onBusy(e.pending)
                    is SessionSwitched -> movedTo = e.to
                    else -> onFail(e.message.orEmpty())
                }
            }
    } finally {
        if (!sent) {
            AttachDrafts.giveBack(movedTo ?: sessionId, text)
            AttachDrafts.restore(movedTo ?: sessionId, files)
        }
        movedTo?.let(onSwitch)
    }
}
