package com.prudnikweb.hypernova.desktop.ui

import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.knownCommands
import com.prudnikweb.hypernova.data.sendPrompt
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.prudnikweb.hypernova.desktop.DeskAttach
import com.prudnikweb.hypernova.ui.session.AttachButton
import com.prudnikweb.hypernova.ui.session.AttachState
import com.prudnikweb.hypernova.ui.session.ChatMenuHost
import com.prudnikweb.hypernova.ui.session.CommandHint
import com.prudnikweb.hypernova.ui.session.CommandSuggestions
import com.prudnikweb.hypernova.ui.session.DraftStrip
import com.prudnikweb.hypernova.ui.session.FilesFirstEffect
import com.prudnikweb.hypernova.ui.session.commandQuery
import com.prudnikweb.hypernova.ui.session.matching
import com.prudnikweb.hypernova.ui.session.picked
import com.prudnikweb.hypernova.ui.session.rememberCommands
import com.prudnikweb.hypernova.ui.session.rememberFilesFirst
import com.prudnikweb.hypernova.ui.session.FollowChatTail
import com.prudnikweb.hypernova.ui.session.FollowWithTheList
import com.prudnikweb.hypernova.ui.session.KeepPlaceOverEarlier
import com.prudnikweb.hypernova.ui.session.TerminalTextCard
import com.prudnikweb.hypernova.ui.session.activeBlock
import com.prudnikweb.hypernova.ui.session.chatBoxHint
import com.prudnikweb.hypernova.ui.session.chatListRows
import com.prudnikweb.hypernova.ui.session.chatRows
import com.prudnikweb.hypernova.ui.session.chatWorking
import com.prudnikweb.hypernova.ui.session.rememberGlideFps
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionCarry
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.chat.ChatTimeLabel
import com.prudnikweb.hypernova.ui.chat.TIME_LABEL_GAP
import com.prudnikweb.hypernova.ui.chat.followTail
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import kotlinx.coroutines.launch

/*
 * The chat in the pane beside the list. What it draws is the phone's own (sharedui: Transcript.kt and the rest); what's here is
 * the part that's this window's: the list around them and the box. What the list does is shared too (ChatList.kt). docs/DECISIONS.md, "The chat on the desktop".
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
    /** Escape: true if the session on screen had something to put away. */
    var escape: (() -> Boolean)? = null
    /** Ctrl+I: the session sheet, open or away. */
    var sheet: (() -> Unit)? = null
    /** Where the session on screen's sheet is open, or null: the window's panes make room for it. */
    val sheetAt = mutableStateOf<SheetAt?>(null)
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
    FollowWithTheList(list) { following.on = it }
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
    // A tool's ask waiting isn't drawn here: Allow goes only beside the whole command, in the card above (PaneAsk, CardOffer).
    val showsAsk = { ask: Item.Approval -> ask.kind != ApprovalKind.Tool }
    val model = chatListRows(items, showsAsk = showsAsk)
    val runOpen = remember(sessionId) { mutableStateMapOf<String, Boolean>() }
    // Which message's menu is open: the other mouse button on it (UiInput.messageMenu).
    var menuKey by remember(sessionId) { mutableStateOf<String?>(null) }
    val working = chatWorking(hub, s, items)
    val shownAsk = remember(sessionId) { mutableStateOf<String?>(null) }
    val earlierAnchor = remember(sessionId) { mutableStateOf<String?>(null) }
    /** The list's first rows before the conversation's own: [head], and "Show earlier" when it's there. */
    val above = { 1 + if (hub.hasEarlier(sessionId)) 1 else 0 }
    KeepPlaceOverEarlier(list, items, model.rows, earlierAnchor, above, settled = true)
    val glideFps by rememberGlideFps()
    FollowChatTail(list, items, model, working, following = { following.on }, shownAsk = shownAsk, above = above, showsAsk = showsAsk)
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
            chatRows(hub, sessionId, items, model, working, runOpen, earlierAnchor, known = knownCommands(s.cwd), onMenu = { menuKey = it.key })
        }
        ChatMenuHost(hub, s, items, menuKey, onQuote = { q -> ChatDrafts.set(sessionId, q + ChatDrafts.of(sessionId)) }, onDismiss = { menuKey = null })
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

/**
 * The message box: what Hub's send takes, a prompt at a time. It goes by Settings' Enter key as the phone's does ([Field]), and a
 * send that can't go puts its words back, as the phone's does. The words wait per session in [ChatDrafts]. A word to the session
 * goes through the same send as the phone's, so while the server is away it waits in the same queue, and shows as queued in the chat.
 */
@Composable
internal fun ChatBox(hub: Hub, s: Session, shell: Shell, box: FocusRequester, modifier: Modifier = Modifier) {
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
    val attach = remember(sessionId) { AttachState(sessionId, hub, DeskAttach, Core.platform.strings, Core.scope, shell::say) }
    val filesFirst = rememberFilesFirst(sessionId, attach)
    fun send(replace: Boolean) {
        val text = ChatDrafts.of(sessionId)
        if (!filesFirst.mayGo(replace)) return
        val files = AttachDrafts.take(sessionId)
        if (text.isBlank() && files.isEmpty()) return
        ChatDrafts.set(sessionId, "")
        busy = null
        // The chat stays where it is, scrolled back or not: docs/DECISIONS.md, "Sending never moves the chat".
        // Core's scope: leaving the session doesn't cancel the send.
        Core.scope.launch {
            sendPrompt(hub, sessionId, text, files, replace, onSwitch = { shell.choose(Core.activeId.orEmpty(), it) }, onBusy = { busy = it }, onFail = shell::say)
        }
    }
    FilesFirstEffect(filesFirst, attach) { send(it) }
    val focus = LocalFocusManager.current
    // "/" opens the commands and skills Claude Code has in this project, as on the phone. (Claude Code's: a Codex session has none of them.)
    val query = if (s.codex) null else commandQuery(draft)
    val commands = rememberCommands(hub, s.cwd, query != null)
    val pickFiles = {
        val title = Ui.attach.text()
        // The dialog holds the window until it's put away, as a system's file dialog does.
        attach.add(shell.desk.pickFiles(title))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        busy?.let { pending -> TerminalTextCard(pending, onReplace = { send(true) }, onOpen = null, onDismiss = { busy = null }) }
        if (query != null) CommandSuggestions(commands, query, onPick = { ChatDrafts.set(sessionId, "/${it.name} ") })
        commands.picked(draft)?.takeIf { it.argumentHint.isNotBlank() }?.let { CommandHint(it, Modifier.padding(horizontal = 18.dp)) }
        if (attach.files.isNotEmpty()) DraftStrip(attach.files, attach::remove, side = 0.dp, onRetry = attach::retry)
        Row(verticalAlignment = Alignment.Bottom) {
            AttachButton(false, pickFiles)
            Field(
                draft, { ChatDrafts.set(sessionId, it) },
                // The arrows and Escape stop here: going through to the screen, they'd choose another session under the words being typed.
                Modifier.weight(1f).testTag(BOX_TAG).focusRequester(box).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when {
                        // A picture on the clipboard attaches, as on the phone; anything else is the box's own paste.
                        e.isCtrlPressed && e.key == Key.V -> shell.desk.clipboardImage()?.let { attach.add(listOf(it), temporary = true) } != null
                        // The best match of the "/" list goes in.
                        e.key == Key.Tab && query != null -> commands.list?.let { matching(it, query).firstOrNull() }?.let { ChatDrafts.set(sessionId, "/${it.name} ") } != null
                        else -> false
                    }
                }.onKeyEvent { e ->
                    when (e.key) {
                        Key.DirectionUp, Key.DirectionDown -> true
                        Key.Escape -> {
                            if (e.type == KeyEventType.KeyDown) focus.clearFocus()
                            true
                        }
                        else -> false
                    }
                },
                placeholder = chatBoxHint(hub, s, activeBlock(hub, s)),
                singleLine = false, maxLines = BOX_LINES,
                commands = !s.codex,
                label = Ui.prompt_hint.text(),
                onSend = { send(false) },
                canSend = (draft.isNotBlank() || attach.files.isNotEmpty()) && !attach.uploading,
            )
            Spacer(Modifier.width(8.dp))
            Pill(Ui.send.text(), { send(false) }, kind = PillKind.Primary, height = 48.dp, enabled = (draft.isNotBlank() || attach.files.isNotEmpty()) && !attach.uploading)
        }
    }
}

/** How tall the box grows before it scrolls. */
private const val BOX_LINES = 6
