package com.prudnikweb.hypernova.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.DraftFile
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.Speaker
import com.prudnikweb.hypernova.data.Thumbs
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.LocalSelectableText
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.voice.SpeakingBar
import com.prudnikweb.hypernova.ui.voice.SpeakingMark
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The chat's quick wins (docs/DECISIONS.md, "Everyday quick wins"), as the session screen places
// them: the usage strip under the status line, Claude's to-do list and the quick prompts over the
// prompt box, and a message's long-press menu. Each piece lives in its own file beside this one.

/**
 * The usage strip, once the Mac has answered (nothing before, so the header never shows zeros).
 * [usage] is the screen's ([rememberSessionUsage]): the session sheet ([onOpen]) shows it too.
 * After it, while calls refused without the owner wait, the blocked mark ([onBlocked]), with or
 * without the strip. [agents]: the agents at work, for the strip's count ([onAgents]: its tap).
 */
@Composable
internal fun ChatUsage(
    hub: Hub, session: Session, usage: SessionUsage?, agents: Int, onCompact: () -> Unit, onOpen: () -> Unit, onAgents: () -> Unit, onBlocked: () -> Unit, more: String,
    modifier: Modifier = Modifier,
) {
    val blocked = session.blockedCalls
    if (usage == null && blocked == 0) return
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (usage != null) UsageStrip(hub, session, usage, agents, onCompact, onOpen, onAgents, more, Modifier.weight(1f))
        if (blocked > 0) {
            if (usage != null) Spacer(Modifier.width(8.dp))
            BlockedMark(blocked, onBlocked)
        }
    }
}

/** The quick prompts, while there's nothing in the box and Claude isn't at work. */
@Composable
internal fun ChatQuickPrompts(visible: Boolean, onSend: (String) -> Unit) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
    ) {
        QuickPromptChips(onSend, Modifier.padding(bottom = 6.dp))
    }
}

/**
 * Beside Interrupt while anything speaks: "Speaking · Stop" with the wave (a message of this chat),
 * else "Stop reading" while the older way reads one of this chat's messages. Leaving the chat stops
 * its own reading.
 */
@Composable
internal fun ReadingPill(items: List<Item>) {
    DisposableEffect(Unit) { onDispose { ReadAloud.stop() } }
    val speaking = Speaker.now
    if (speaking != null) return SpeakingBar(speaking, onStop = { if (speaking.key != null) ReadAloud.stop() else Speaker.stop("phone") })
    val key = ReadAloud.reading ?: return
    if (items.none { it.key == key }) return
    Pill(stringResource(R.string.chat_read_stop), ReadAloud::stop, kind = PillKind.Ghost, height = 40.dp)
}

/**
 * A message as the session's chat draws it: with its long-press menu ([onMenu]) instead of text
 * selection, for the owner's messages and Claude's replies.
 */
@Composable
internal fun WithMessageMenu(item: Item, onMenu: ((Item) -> Unit)?, content: @Composable (Modifier) -> Unit) {
    if (onMenu == null || item.menuText() == null) return content(Modifier)
    val view = LocalView.current
    val label = stringResource(R.string.chat_message_actions)
    // One gesture detector per message, kept while a reply streams in (a new one would drop a
    // long-press under way).
    val open by rememberUpdatedState(onMenu)
    val latest by rememberUpdatedState(item)
    val menu = remember(item.key, label) { Modifier.messageMenu(view, label) { open?.invoke(latest) } }
    CompositionLocalProvider(LocalSelectableText provides false) {
        // The message being read aloud wears the speaking wave at its corner. Always in a box, so
        // the message isn't rebuilt when reading starts or stops.
        Box {
            content(menu)
            if (ReadAloud.reading == item.key) SpeakingMark(Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * The menu of the message [menuKey] names (null: none open). Quote puts the words into the box
 * through [onQuote]; a rewind opens the session that replaces this one, with the message back in its box.
 */
@Composable
internal fun ChatMenuHost(hub: Hub, session: Session, items: List<Item>, menuKey: String?, onQuote: (String) -> Unit, onDismiss: () -> Unit) {
    val item = menuKey?.let { k -> items.firstOrNull { it.key == k } } ?: return
    val context = LocalContext.current
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
                f.thumb = Thumbs.get(a.path)
                AttachDrafts.add(r.sessionId, f)
            }
            appScope.launch {
                // The new session reaches this phone over the live link, usually first; a screen
                // opened before it would take it for gone and close.
                repeat(40) { if (hub.session(r.sessionId) == null) delay(100) }
                opener.switchSession(r.sessionId)
                toast(r.originalStays ?: context.getString(R.string.chat_rewound))
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
