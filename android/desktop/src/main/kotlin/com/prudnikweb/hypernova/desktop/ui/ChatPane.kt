package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Links
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Resolution
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionCarry
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.SessionSwitched
import com.prudnikweb.hypernova.data.TerminalBusy
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.ui.chat.AgentCard
import com.prudnikweb.hypernova.ui.chat.ApprovalBlock
import com.prudnikweb.hypernova.ui.chat.AssistantMessage
import com.prudnikweb.hypernova.ui.chat.CommandOutputRow
import com.prudnikweb.hypernova.ui.chat.DiffCard
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.chat.MiniTerminal
import com.prudnikweb.hypernova.ui.chat.NoticeRow
import com.prudnikweb.hypernova.ui.chat.ResultRow
import com.prudnikweb.hypernova.ui.chat.ThinkingRow
import com.prudnikweb.hypernova.ui.chat.ToolRow
import com.prudnikweb.hypernova.ui.chat.UserMessage
import com.prudnikweb.hypernova.ui.chat.WorkflowCard
import com.prudnikweb.hypernova.ui.chat.linkShown
import com.prudnikweb.hypernova.ui.fx.FrameBudget
import com.prudnikweb.hypernova.ui.fx.Heat
import com.prudnikweb.hypernova.ui.fx.Pace
import com.prudnikweb.hypernova.ui.chat.AskStack
import com.prudnikweb.hypernova.ui.chat.Asks
import com.prudnikweb.hypernova.ui.chat.ChatRow
import com.prudnikweb.hypernova.ui.chat.ChatTimeLabel
import com.prudnikweb.hypernova.ui.chat.ItemFadeIn
import com.prudnikweb.hypernova.ui.chat.ShowEarlier
import com.prudnikweb.hypernova.ui.chat.TIME_LABEL_GAP
import com.prudnikweb.hypernova.ui.chat.ToolRunRow
import com.prudnikweb.hypernova.ui.chat.ToolRuns
import com.prudnikweb.hypernova.ui.chat.chatTail
import com.prudnikweb.hypernova.ui.chat.followTail
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The chat in the pane beside the list. What it draws is the phone's own (sharedui: Transcript.kt and the rest); what's here is
 * the part that's this window's: the list around them, the box, and where a link goes. docs/DECISIONS.md, "The chat on the desktop".
 */

/** The tag of the conversation, for the tests. */
const val CHAT_TAG = "session-chat"
/** The tag of the message box. */
const val BOX_TAG = "session-box"

/** Whether the chat follows its tail: the list and the box both move it. */
@Stable
internal class ChatFollowing {
    var on by mutableStateOf(true)
}

/** What's typed in each session's box: it stays while another session is looked at, and for as long as the window is open. */
internal object ChatDrafts {
    private val texts = mutableStateMapOf<String, String>()

    fun of(session: String): String = texts[session].orEmpty()

    fun set(session: String, text: String) {
        if (text.isEmpty()) texts.remove(session) else texts[session] = text
    }
}

/**
 * Page Up and Page Down, from wherever the window's keys are: the focus usually sits on the screen itself (SessionsScreen), which
 * isn't inside the chat. Only the chat on screen listens.
 */
internal object ChatKeys {
    var page: ((down: Boolean) -> Unit)? = null
}

/** The share of the list one Page Up or Page Down moves, so the last lines stay in view. */
private const val PAGE = 0.85f

/**
 * The conversation: [head], then the session's history as the phone draws it, following its tail as the phone's does, with the
 * time over it and a way back to the end.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ChatList(hub: Hub, s: Session, shell: Shell, following: ChatFollowing, box: FocusRequester, modifier: Modifier = Modifier, head: @Composable () -> Unit) {
    val sessionId = s.id
    LaunchedEffect(sessionId) { hub.opened(sessionId) }
    val items = hub.transcript(sessionId)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (items.size - 1).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val motion = LocalMotion.current
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) following.on = false }
    }
    LaunchedEffect(list) {
        // The app itself only ever scrolls forward, so a scroll that went backward was the owner's.
        snapshotFlow { list.isScrollInProgress }.filter { !it }.collect {
            if (!list.canScrollForward) following.on = true else if (list.lastScrolledBackward) following.on = false
        }
    }
    DisposableEffect(list) {
        val page = { down: Boolean ->
            if (!down) following.on = false
            scope.launch {
                val by = list.layoutInfo.viewportSize.height * PAGE * if (down) 1f else -1f
                if (motion.reduced) list.scrollBy(by) else list.animateScrollBy(by)
            }
            Unit
        }
        ChatKeys.page = page
        onDispose { if (ChatKeys.page === page) ChatKeys.page = null }
    }
    // Prompts waiting in the queue sit under Claude's live output, so what grows is the last item above them.
    val liveAt = items.indexOfLast { !(it is Item.User && (it.queued || it.waiting || it.held)) }
    val tailKey = listOfNotNull(items.getOrNull(liveAt)?.let(::chatTail), items.lastOrNull()?.takeIf { items.lastIndex != liveAt }?.let(::chatTail)).joinToString("|")
    // As on the phone, a tool's permission card goes once it's answered and only a denial stays; a question or a plan stays.
    val (chat, waiting) = Asks.split(items.filterNot { it is Item.Approval && it.kind == ApprovalKind.Tool && it.resolved != null && it.resolved != Resolution.Denied })
    // A tool's ask waiting isn't drawn here: Allow goes only beside the whole command, in the card above (AskCard, CardOffer).
    val asks = waiting.filter { it.kind != ApprovalKind.Tool }
    val rows = ToolRuns.rows(chat).let { if (asks.isEmpty()) it else it + ChatRow.Asks(asks) }
    val labelled = ToolRuns.labelled(items)
    val runOpen = remember(sessionId) { mutableStateMapOf<String, Boolean>() }
    val liveRowKey = items.getOrNull(liveAt)?.let { live -> rows.lastOrNull { it.has(live.key) } }?.key
    val linked = hub.link.state == LinkState.Connected
    val working = linked && (hub.sending(sessionId) || s.phase == Phase.Starting || (s.state == SessionState.Working && items.none { it is Item.Approval && it.resolved == null }))
    var shownAsk by remember(sessionId) { mutableStateOf<String?>(null) }
    var earlierAnchor by remember(sessionId) { mutableStateOf<String?>(null) }
    /** The list's first rows before the conversation's own: [head], and "Show earlier" when it's there. */
    fun above() = 1 + if (hub.hasEarlier(sessionId)) 1 else 0
    LaunchedEffect(items.size) {
        val k = earlierAnchor ?: return@LaunchedEffect
        val i = rows.indexOfFirst { it.has(k) }
        if (i > 0) {
            earlierAnchor = null
            withFrameNanos { }
            list.scrollToItem(i - 1 + above())
        }
    }
    val glideFps by rememberUpdatedState(FrameBudget.fps(Pace.Live, Heat.COOL, powerSave = false))
    LaunchedEffect(tailKey, items.size, working) {
        if (!following.on || items.isEmpty()) return@LaunchedEffect
        val last = items.getOrNull(liveAt)
        val ask = (last as? Item.Approval)?.takeIf { it.kind != ApprovalKind.Tool }?.let { Asks.toShow(it, shownAsk) }
        if (last is Item.Approval && ask != null) {
            shownAsk = ask
            // A new question or plan can be taller than the pane: show where it starts.
            withFrameNanos { }
            list.animateScrollToItem(rows.indexOfFirst { it.has(last.key) }.coerceAtLeast(0) + above())
        } else {
            followTail(list, glideFps, motion.reduced)
        }
    }
    Box(
        modifier
            .testTag(CHAT_TAG)
            // Upward at once: the phone's rule only knows when a scroll has ended, and meanwhile a reply coming in would pull this one back.
            .onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { e ->
                if ((e.changes.firstOrNull()?.scrollDelta?.y ?: 0f) < 0f) following.on = false
            },
    ) {
        // Selecting with a mouse is in each message and each terminal, as the phone's long-press selection is (Selectable, SelectableOutput). Not
        // one selection over the whole list: a text under a selection takes the mouse's press, so the rows' and pills' words wouldn't click.
        LazyColumn(
            // Tab from the pills goes to the box, not through every message on screen; Shift+Tab from the box comes back through them.
            Modifier.fillMaxSize().focusProperties {
                onEnter = {
                    if (requestedFocusDirection == FocusDirection.Next) box.requestFocus()
                }
            }.focusGroup(),
            state = list,
            contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "head") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { head() } }
            if (items.isEmpty()) {
                item(key = "empty") { ChatEmpty(hub, sessionId, linked) }
            }
            if (items.isNotEmpty() && hub.hasEarlier(sessionId)) {
                item(key = "earlier") {
                    ShowEarlier(hub.loadingEarlier(sessionId)) {
                        earlierAnchor = items.first().key
                        hub.loadEarlier(sessionId)
                    }
                }
            }
            items(rows, key = { it.key }) { row ->
                val rowModifier = Modifier.fillMaxWidth().animateItem(fadeInSpec = ItemFadeIn, placementSpec = null, fadeOutSpec = null)
                when (row) {
                    is ChatRow.One -> ChatItem(hub, s, row.item, rowModifier, shell, named = row.item.key in labelled)
                    is ChatRow.Run -> {
                        val open = runOpen[row.key] ?: false
                        val live = row.running || (working && row.key == liveRowKey)
                        ToolRunRow(row, open, live, onToggle = { runOpen[row.key] = !open }, modifier = rowModifier) { member ->
                            ChatItem(hub, s, member, Modifier.fillMaxWidth(), shell)
                        }
                    }
                    is ChatRow.Asks -> AskStack(row.asks, rowModifier) { ask, m -> ChatItem(hub, s, ask, m.fillMaxWidth(), shell) }
                    is ChatRow.TermAsk -> Unit
                }
            }
        }
        // Over the list and beside it, so nothing in the chat moves for it, and no pointer input, so a click goes through.
        val gap = with(androidx.compose.ui.platform.LocalDensity.current) { TIME_LABEL_GAP.roundToPx() }
        ChatTimeLabel(hub, sessionId, opening = 0, list, following = { following.on }, top = { gap }, modifier = Modifier.align(Alignment.TopCenter))
        AnimatedVisibility(
            !following.on && list.canScrollForward,
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp),
            enter = fadeIn(tween(Motion.FAST)),
            exit = fadeOut(tween(Motion.FAST)),
        ) {
            Pill(
                Ui.jump_latest.text(),
                {
                    following.on = true
                    scope.launch { followTail(list, glideFps, motion.reduced) }
                },
                kind = PillKind.Outline, height = 32.dp,
            )
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(list),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
            style = LocalScrollbarStyle.current.copy(unhoverColor = HN.ink.copy(alpha = 0.12f), hoverColor = HN.ink.copy(alpha = 0.3f)),
        )
    }
}

/**
 * [content] a mouse can select and Ctrl+C can copy, without a selection's silent stop for the Tab key: it can have the focus only once a
 * press has landed in it, so Tab still goes straight from the top bar to Deny and Allow (docs/DECISIONS.md, "The desktop client's two panes").
 */
@Composable
internal fun MouseSelectable(content: @Composable () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                }
            }
            .onFocusChanged { if (!it.hasFocus) pressed = false }
            .focusProperties { canFocus = pressed },
    ) {
        SelectionContainer(content = content)
    }
}

/** Nothing to read yet: it's coming, it couldn't come, or there's nothing. */
@Composable
private fun ChatEmpty(hub: Hub, sessionId: String, linked: Boolean) {
    val error = hub.transcriptError(sessionId)
    when {
        hub.loadingTranscript(sessionId) -> LoadingLine(Ui.loading_conversation.text(), Modifier.padding(top = 8.dp))
        error != null -> Column(Modifier.padding(top = 8.dp)) {
            HText(error, style = Type.body, color = HN.danger)
            if (linked) {
                Spacer(Modifier.height(12.dp))
                Pill(Ui.try_again.text(), { hub.reloadTranscript(sessionId) }, kind = PillKind.Outline)
            }
        }
        else -> HText(Ui.empty_conversation.text(), Modifier.padding(top = 8.dp), style = Type.small, color = HN.inkDim)
    }
}

/**
 * One item, as the phone's TranscriptItem draws it. What opens something the phone has and this window doesn't (a file, a preview,
 * an agent's own screen) says so rather than doing nothing. A tool's ask never comes here while it waits (see [ChatList]).
 */
@Composable
private fun ChatItem(hub: Hub, s: Session, item: Item, modifier: Modifier, shell: Shell, named: Boolean = true) {
    val previewOnPhone = { _: Int -> shell.say(Ui.desk_preview_on_phone.text()) }
    val agentOnPhone = { _: String -> shell.say(Ui.desk_agent_on_phone.text()) }
    when (item) {
        is Item.User -> UserMessage(
            item, modifier, codex = s.codex,
            files = { AttachedNames(item) },
            onStartNew = item.waitingId?.let { w -> { Core.scope.launch { hub.startFromWaiting(w).onFailure { shell.say(it.message.orEmpty()) } } } },
        )
        is Item.Assistant -> AssistantMessage(item, modifier, onPreview = previewOnPhone, codex = s.codex, named = named)
        is Item.Tool -> ToolRow(item, modifier, onOpen = null, onPreview = previewOnPhone)
        is Item.Terminal -> MiniTerminal(item, modifier, onPreview = previewOnPhone)
        is Item.Diff -> DiffCard(item, modifier, onOpen = null, onPreviewFile = null)
        is Item.Approval -> ApprovalBlock(item, onAnswer = { a -> hub.answer(s.id, item, a) }, modifier = modifier)
        is Item.Thinking -> ThinkingRow(item, modifier)
        is Item.Result -> ResultRow(item, modifier)
        is Item.Notice -> if (item.command != null) CommandOutputRow(item.key, item.text, item.tone, modifier) else NoticeRow(item, modifier)
        is Item.AgentCard -> AgentCard(item, agentOnPhone, modifier)
        is Item.WorkflowCard -> WorkflowCard(item, agentOnPhone, modifier)
    }
}

/** Files sent with a message, by name. They were sent from a phone: nothing attaches here, and their pictures aren't fetched. */
@Composable
private fun AttachedNames(item: Item.User) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (f in item.files) GlyphFigure(Glyph.Clip, f.name, color = HN.inkMuted)
    }
}

/**
 * The message box: what Hub's send takes, a prompt at a time. It goes by Settings' Enter key as the phone's does ([Field]), and a
 * send that can't go puts its words back, as the phone's does. The words wait per session in [ChatDrafts]. A word to the session
 * goes through the same send as the phone's, so while the server is away it waits in the same queue, and shows as queued in the chat.
 */
@Composable
internal fun ChatBox(hub: Hub, s: Session, shell: Shell, following: ChatFollowing, box: FocusRequester, modifier: Modifier = Modifier) {
    val sessionId = s.id
    val draft = ChatDrafts.of(sessionId)
    // Goes on with the session if a rewind replaces it (data/Attachments.kt, SessionCarry).
    SideEffect { SessionCarry.typing(sessionId, draft) }
    // Text waiting unsent in the session's terminal: the last send stopped for it.
    var busy by remember(sessionId) { mutableStateOf<String?>(null) }
    val back = AttachDrafts.givenBack(sessionId)
    LaunchedEffect(back) {
        val t = AttachDrafts.takeBack(sessionId) ?: return@LaunchedEffect
        ChatDrafts.set(sessionId, if (ChatDrafts.of(sessionId).isBlank()) t else "$t\n${ChatDrafts.of(sessionId)}")
    }
    fun send(replace: Boolean) {
        val text = ChatDrafts.of(sessionId)
        if (text.isBlank()) return
        ChatDrafts.set(sessionId, "")
        busy = null
        following.on = true
        // Core's scope: leaving the session doesn't cancel the send.
        Core.scope.launch {
            var sent = false
            var movedTo: String? = null
            try {
                hub.send(sessionId, text, emptyList(), replace)
                    .onSuccess { id ->
                        sent = true
                        if (id != sessionId) shell.choose(Core.activeId.orEmpty(), id)
                    }
                    .onFailure { e ->
                        when (e) {
                            is TerminalBusy -> busy = e.pending
                            is SessionSwitched -> movedTo = e.to
                            else -> shell.say(e.message.orEmpty())
                        }
                    }
            } finally {
                // Failed or cancelled: the words go back in the box, even if another session is on screen by now.
                if (!sent) AttachDrafts.giveBack(movedTo ?: sessionId, text)
                movedTo?.let { shell.choose(Core.activeId.orEmpty(), it) }
            }
        }
    }
    val focus = LocalFocusManager.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        busy?.let { pending -> BusyCard(pending, onReplace = { send(true) }, onDismiss = { busy = null }) }
        Row(verticalAlignment = Alignment.Bottom) {
            Field(
                draft, { ChatDrafts.set(sessionId, it) },
                // The arrows and Escape stop here: going through to the screen, they'd choose another session under the words being typed.
                Modifier.weight(1f).testTag(BOX_TAG).focusRequester(box).onKeyEvent { e ->
                    when (e.key) {
                        Key.DirectionUp, Key.DirectionDown -> true
                        Key.Escape -> {
                            if (e.type == KeyEventType.KeyDown) focus.clearFocus()
                            true
                        }
                        else -> false
                    }
                },
                placeholder = chatHint(hub, s),
                singleLine = false, maxLines = BOX_LINES,
                commands = !s.codex,
                label = Ui.prompt_hint.text(),
                onSend = { send(false) },
                canSend = draft.isNotBlank(),
            )
            Spacer(Modifier.width(8.dp))
            Pill(Ui.send.text(), { send(false) }, kind = PillKind.Primary, height = 48.dp, enabled = draft.isNotBlank())
        }
    }
}

/** How tall the box grows before it scrolls. */
private const val BOX_LINES = 6

/** What the empty box says, in the phone's words: why a prompt won't go at once, where it goes, or just what it's for. */
private fun chatHint(hub: Hub, s: Session): String = when {
    hub.link.state != LinkState.Connected -> Ui.hint_mac_away_queue.text()
    s.switchedTo != null -> Ui.cx_hint_went_on.format(if (hub.session(s.switchedTo.orEmpty())?.codex ?: !s.codex) Ui.cx_agent_codex.text() else Ui.claude.text())
    s.phase == Phase.Ended -> Ui.prompt_hint_ended.text()
    else -> Ui.prompt_hint.text()
}

/** The last send stopped for text waiting in the session's terminal. There's no terminal here to fix it in. */
@Composable
private fun BusyCard(pending: String, onReplace: () -> Unit, onDismiss: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), edge = HN.amber) {
        Column(Modifier.padding(16.dp)) {
            HText(Ui.busy_title.text(), style = Type.heading)
            Spacer(Modifier.height(4.dp))
            HText(pending, style = Type.monoSmall, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            HText(Ui.busy_body.text(), style = Type.small, color = HN.inkDim)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(Ui.busy_dismiss.text(), onDismiss, kind = PillKind.Ghost)
                Pill(Ui.busy_replace.text(), onReplace, kind = PillKind.Primary)
            }
        }
    }
}

/**
 * Where a link in the chat goes, with the phone's rules (its LinkHandler): the web asks first, with the whole address; anything but
 * the web never opens. A dev server on the server is a phone's preview, and on this computer its address would be some other program.
 */
@Stable
internal class DeskLinks(private val shell: Shell) : UriHandler {
    var asking by mutableStateOf<String?>(null)

    override fun openUri(uri: String) {
        when (val t = Links.target(uri)) {
            is Links.Target.Web -> asking = t.url
            is Links.Target.Local -> shell.say(Ui.desk_preview_on_phone.text())
            Links.Target.Never -> Unit
        }
    }
}

/** "Open this link?" with the whole address, its scheme and host bright so a lookalike shows. Open hands it to the system's browser; Copy; Cancel. */
@Composable
internal fun LinkAsk(links: DeskLinks, shell: Shell) {
    val url = links.asking ?: return
    val dismiss = { links.asking = null }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(url) { runCatching { first.requestFocus() } }
    Box(
        Modifier.fillMaxSize()
            .background(HN.bg.copy(alpha = 0.72f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = dismiss)
            .onKeyEvent { e ->
                if (e.key == Key.Escape) {
                    if (e.type == KeyEventType.KeyDown) dismiss()
                    true
                } else {
                    false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        GlassCard(Modifier.widthIn(max = 520.dp).padding(24.dp)) {
            Column(Modifier.padding(16.dp)) {
                HText(Ui.link_open_title.text(), style = Type.heading.copy(fontWeight = FontWeight(600)))
                Spacer(Modifier.height(10.dp))
                SelectionContainer { HText(linkShown(url), style = Type.mono, color = HN.inkMuted) }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(Ui.cancel.text(), dismiss, Modifier.focusRequester(first), kind = PillKind.Ghost)
                    Pill(Ui.link_copy.text(), {
                        shell.desk.copy(url)
                        dismiss()
                    })
                    Pill(Ui.link_open.text(), {
                        dismiss()
                        scope.launch {
                            val opened = withContext(Dispatchers.IO) { shell.desk.browse(url) }
                            if (!opened) shell.say(Ui.link_no_browser.text())
                        }
                    }, kind = PillKind.Primary)
                }
            }
        }
    }
}

