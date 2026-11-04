package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Resolution
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.SlashCommand
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.TerminalAsk
import com.prudnikweb.hypernova.ui.chat.AskStack
import com.prudnikweb.hypernova.ui.chat.Asks
import com.prudnikweb.hypernova.ui.chat.ChatRow
import com.prudnikweb.hypernova.ui.chat.ItemFadeIn
import com.prudnikweb.hypernova.ui.chat.ShowEarlier
import com.prudnikweb.hypernova.ui.chat.ToolRunRow
import com.prudnikweb.hypernova.ui.chat.ToolRuns
import com.prudnikweb.hypernova.ui.chat.chatTail
import com.prudnikweb.hypernova.ui.chat.followTail
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.fx.FrameBudget
import com.prudnikweb.hypernova.ui.fx.Pace
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.flow.filter

/*
 * What a session's chat list does, on both systems: what it shows, following its tail, and "Show earlier". How the list sits on the screen, with its bars or in
 * a pane, is each system's own (SessionScreen.kt, ChatPane.kt).
 */

/** What the list draws of a session's items now. */
@Immutable
internal class ChatListRows(
    val rows: List<ChatRow>,
    val labelled: Set<String>,
    /** The last item above any prompts waiting in the queue: what grows while Claude works. */
    val liveAt: Int,
    /** What changes as the tail grows: while it does, a list following it scrolls on. */
    val tailKey: String,
    /** The row Claude is working in. */
    val liveRowKey: String?,
    /** Claude Code's own permission dialog, in the asks' place: none while an ask waits. */
    val termAsk: TerminalAsk?,
)

/**
 * Runs of three or more tool calls fold into one line, and only the first reply after each message carries Claude's name (ToolRuns.kt). A tool's permission card
 * goes once it's answered, as in Claude Code's terminal; only a denial stays (worth seeing later). Questions and plans stay: their answers are the content.
 * The asks still waiting are one stack, the last row of all, under prompts waiting in the queue too (AskStack.kt). [showsAsk]: which of those this system draws here.
 */
internal fun chatListRows(items: List<Item>, termAsk: TerminalAsk? = null, showsAsk: (Item.Approval) -> Boolean = { true }): ChatListRows {
    // Prompts waiting in the queue sit under Claude's live output, so what grows is the last item above them.
    val liveAt = items.indexOfLast { !(it is Item.User && (it.queued || it.waiting || it.held)) }
    val tailKey = listOfNotNull(items.getOrNull(liveAt)?.let(::chatTail), items.lastOrNull()?.takeIf { items.lastIndex != liveAt }?.let(::chatTail)).joinToString("|")
    val (chat, waiting) = Asks.split(items.filterNot { it is Item.Approval && it.kind == ApprovalKind.Tool && it.resolved != null && it.resolved != Resolution.Denied })
    val asks = waiting.filter(showsAsk)
    val term = termAsk.takeIf { asks.isEmpty() }
    val rows = ToolRuns.rows(chat).let { if (asks.isEmpty()) it else it + ChatRow.Asks(asks) }.let { if (term == null) it else it + ChatRow.TermAsk(term) }
    return ChatListRows(rows, ToolRuns.labelled(items), liveAt, tailKey, items.getOrNull(liveAt)?.let { live -> rows.lastOrNull { it.has(live.key) } }?.key, term)
}

/** Whether Claude is at work here, as far as anyone can tell: with the server away nothing is live, so nothing says "working" with a ticking clock. */
internal fun chatWorking(hub: Hub, s: Session, items: List<Item>): Boolean =
    hub.link.state == LinkState.Connected && (hub.sending(s.id) || s.phase == Phase.Starting || (s.state == SessionState.Working && items.none { it is Item.Approval && it.resolved == null }))

/**
 * Follow the tail only while at the end. Any scroll (drag, fling, TalkBack, mouse wheel) that stops away from the end stops following; one that stops at the end
 * resumes it, as "Jump to latest" does.
 */
@Composable
internal fun FollowWithTheList(list: LazyListState, setFollow: (Boolean) -> Unit) {
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) setFollow(false) }
    }
    LaunchedEffect(list) {
        // The app itself only ever scrolls forward, so any scroll that went backward was the owner's (touch, TalkBack, mouse wheel, keyboard), wherever it came from.
        snapshotFlow { list.isScrollInProgress }.filter { !it }.collect {
            if (!list.canScrollForward) setFollow(true) else if (list.lastScrolledBackward) setFollow(false)
        }
    }
}

/**
 * "Show earlier": the item that was first ([anchor]) stays where the eye is, with the last of the earlier ones just above it, instead of the list jumping to the new
 * start. [above]: the list's rows before the conversation's own. [settled]: a frame goes by first, for a list with a head of its own to lay out.
 */
@Composable
internal fun KeepPlaceOverEarlier(list: LazyListState, items: List<Item>, rows: List<ChatRow>, anchor: MutableState<String?>, above: () -> Int, settled: Boolean = false) {
    LaunchedEffect(items.size) {
        val k = anchor.value ?: return@LaunchedEffect
        // The anchor's row: its own, or the folded run it's in.
        val i = rows.indexOfFirst { it.has(k) }
        if (i > 0) {
            anchor.value = null
            if (settled) withFrameNanos { }
            list.scrollToItem(i - 1 + above())
        }
    }
}

/** The tail's glide steps at the live pace's rate (30 a second on a cool phone, fewer as it warms: ui/fx/Pace.kt). */
@Composable
internal fun rememberGlideFps(): State<Int> {
    val motion = LocalMotion.current
    return rememberUpdatedState(FrameBudget.fps(Pace.Live, motion.heat, motion.powerSave))
}

/**
 * While following, the list stays at the tail as it grows. A new question or plan can be taller than the screen, so the list shows where it starts, only once for
 * each ask ([shownAsk]): every run of this went back to the ask's start, so scrolling past the bottom of a tall one jumped the list back up. After that it follows
 * the end like anything else. [also]: anything else that moves the end of the list. [showsAsk]: as [chatListRows].
 */
@Composable
internal fun FollowChatTail(
    list: LazyListState, items: List<Item>, model: ChatListRows, working: Boolean, following: () -> Boolean, shownAsk: MutableState<String?>, above: () -> Int, also: Any? = null,
    showsAsk: (Item.Approval) -> Boolean = { true },
) {
    val motion = LocalMotion.current
    val glideFps by rememberGlideFps()
    LaunchedEffect(model.tailKey, also, items.size, working) {
        if (!following() || items.isEmpty()) return@LaunchedEffect
        val last = items.getOrNull(model.liveAt)
        val ask = Asks.toShow(last?.takeUnless { it is Item.Approval && !showsAsk(it) }, shownAsk.value)
        if (last is Item.Approval && ask != null) {
            shownAsk.value = ask
            // A new question or plan can be taller than the screen: show where it starts.
            withFrameNanos { }
            // An approval is never folded: its own row in the list (the stack's, AskStack.kt), after what's above the rows.
            list.animateScrollToItem(model.rows.indexOfFirst { it.has(last.key) }.coerceAtLeast(0) + above())
        } else {
            followTail(list, glideFps, motion.reduced)
        }
    }
}

/** Nothing to read yet: it's coming, it couldn't come, or there's nothing. */
@Composable
internal fun ChatEmptyRow(hub: Hub, sessionId: String) {
    val error = hub.transcriptError(sessionId)
    when {
        hub.loadingTranscript(sessionId) -> LoadingLine(stringResource(Str.loading_conversation), Modifier.padding(top = 8.dp))
        error != null -> Column(Modifier.padding(top = 8.dp)) {
            HText(error, style = Type.body, color = HN.danger)
            // Another try only helps once the server is linked (the status line says when it isn't).
            if (hub.link.state == LinkState.Connected) {
                Spacer(Modifier.height(12.dp))
                Pill(stringResource(Str.try_again), { hub.reloadTranscript(sessionId) }, kind = PillKind.Outline, height = 44.dp)
            }
        }
        else -> HText(stringResource(Str.empty_conversation), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * The list's own rows: the empty one, "Show earlier" and the conversation. [anchor]: as [KeepPlaceOverEarlier]. [known]: the "/" list, so a command sent from here draws
 * as what it is before the server's copy arrives. [onMenu]: its messages have a menu. [termAsk]: draws Claude Code's own dialog, where this system has it.
 */
internal fun LazyListScope.chatRows(
    hub: Hub, sessionId: String, items: List<Item>, model: ChatListRows, working: Boolean, runOpen: SnapshotStateMap<String, Boolean>, anchor: MutableState<String?>,
    known: List<SlashCommand>? = null, onMenu: ((Item) -> Unit)? = null, termAsk: @Composable (ChatRow.TermAsk, Modifier) -> Unit = { _, _ -> },
) {
    if (items.isEmpty()) {
        item(key = "empty") { ChatEmptyRow(hub, sessionId) }
    }
    // The start of a long session is further back: a way to it, at the top.
    if (items.isNotEmpty() && hub.hasEarlier(sessionId)) {
        item(key = "earlier") {
            ShowEarlier(hub.loadingEarlier(sessionId)) {
                anchor.value = items.first().key
                hub.loadEarlier(sessionId)
            }
        }
    }
    items(model.rows, key = { it.key }) { row ->
        // New items fade in, and nothing else moves by itself: with the list's default placement spring, a card under one that grows (a command's output streaming
        // in) glided after it and was drawn over it, and an item that left (a run replaced, a prompt delivered, an approval that moved to its own event) faded out
        // where it was while its successor slid over it: the same Bash card, "exit 0" and all, drawn twice, a little apart, while the tail followed Claude's output.
        val rowModifier = Modifier.fillMaxWidth().animateItem(fadeInSpec = ItemFadeIn, placementSpec = null, fadeOutSpec = null)
        when (row) {
            is ChatRow.One -> TranscriptItem(hub, sessionId, row.item, rowModifier, known = known, onMenu = onMenu, named = row.item.key in model.labelled)
            is ChatRow.Run -> {
                val open = runOpen[row.key] ?: false
                val live = row.running || (working && row.key == model.liveRowKey)
                ToolRunRow(row, open, live, onToggle = { runOpen[row.key] = !open }, modifier = rowModifier) { member ->
                    TranscriptItem(hub, sessionId, member, Modifier.fillMaxWidth(), known = known, onMenu = onMenu)
                }
            }
            is ChatRow.Asks -> AskStack(row.asks, rowModifier) { ask, m -> TranscriptItem(hub, sessionId, ask, m.fillMaxWidth()) }
            // Keyed by its menu: the next dialog is a new card, never the last one's buttons.
            is ChatRow.TermAsk -> key(row.ask.menuId) { termAsk(row, rowModifier) }
        }
    }
}
