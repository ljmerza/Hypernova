package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.ui.platform.SharedUi
import com.prudnikweb.hypernova.ui.platform.wordsNow

import com.prudnikweb.hypernova.data.Str
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.DraftFile
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The menu of the message [menuKey] names (null: none open). Quote puts the words into the box
 * through [onQuote]; a rewind opens the session that replaces this one, with the message back in its box.
 */
@Composable
internal fun ChatMenuHost(hub: Hub, session: Session, items: List<Item>, menuKey: String?, onQuote: (String) -> Unit, onDismiss: () -> Unit) {
    val item = menuKey?.let { k -> items.firstOrNull { it.key == k } } ?: return
    val res = wordsNow()
    val toast = LocalToast.current
    val opener = LocalOpener.current
    val appScope = LocalAppScope.current
    // Codex has no rewind (docs/PROTOCOL.md, "Codex"): its messages' menus don't offer it.
    val ask = remember(items, item, session) { if (session.codex) null else rewindAsk(session, items, item) }
    MessageMenu(
        hub, item, ask,
        sessionId = session.id,
        onQuote = onQuote,
        onRewound = { r ->
            AttachDrafts.giveBack(r.sessionId, r.text)
            for (a in r.files) {
                val f = DraftFile(AttachDrafts.nextId(), a.name, a.isImage)
                f.attachment = a
                f.thumb = SharedUi.platform.images.cached(a.path)
                AttachDrafts.add(r.sessionId, f)
            }
            appScope.launch {
                // The new session reaches this phone over the live link, usually first; a screen
                // opened before it would take it for gone and close.
                repeat(40) { if (hub.session(r.sessionId) == null) delay(100) }
                opener.switchSession(r.sessionId)
                toast(r.originalStays ?: res.getString(Str.chat_rewound))
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * Whether the session can go back to before [item], and what would go: the owner's messages from
 * it on and Claude's replies. Not for the first message (there's nothing before it), one not on
 * the Mac yet, or Claude Code's own commands and shell lines (they aren't turns of the conversation).
 */
private fun rewindAsk(session: Session, items: List<Item>, item: Item): RewindAsk? {
    if (item !is Item.User || item.status != null) return null
    if (item.command?.kind == "local" || item.command?.kind == "bash") return null
    val at = items.indexOf(item)
    if (at < 0 || items.subList(0, at).none { it is Item.User && it.status == null }) return null
    val after = items.subList(at, items.size)
    // A side question's line isn't in the conversation (data/Asides.kt): nothing of it goes.
    val gone = session.phase == com.prudnikweb.hypernova.data.Phase.Ended
    return RewindAsk(
        session.id, after.count { it is Item.User && it.status == null && !it.key.startsWith(com.prudnikweb.hypernova.data.Asides.KEY) }, after.count { it is Item.Assistant },
        running = when {
            gone -> RewindRunning.No
            session.state == com.prudnikweb.hypernova.data.SessionState.NeedsYou -> RewindRunning.Waiting
            session.state == com.prudnikweb.hypernova.data.SessionState.Working || session.agentsRunning > 0 || session.queued > 0 -> RewindRunning.Working
            else -> RewindRunning.No
        },
        terminalCloses = !gone && (session.term != null || session.agentJob != null),
        // Open in a terminal no holder has: nothing here can close it, and the server leaves it.
        stays = !gone && session.origin == "terminal" && session.term == null && session.agentJob == null,
    )
}
