package com.prudnikweb.hypernova.ui.session

import androidx.compose.ui.text.input.VisualTransformation
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.components.rememberFieldValue
import com.prudnikweb.hypernova.ui.components.CommandHighlight
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.data.TermMenu
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlin.coroutines.coroutineContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AccountWords
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.SessionSwitched
import com.prudnikweb.hypernova.data.Blocked
import com.prudnikweb.hypernova.data.healthyOther
import com.prudnikweb.hypernova.ui.home.SignInPanel
import com.prudnikweb.hypernova.ui.home.continueLabel
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.TerminalBusy
import com.prudnikweb.hypernova.data.knownCommands
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.AgentCard
import com.prudnikweb.hypernova.ui.components.WorkflowCard
import com.prudnikweb.hypernova.ui.components.ApprovalBlock
import com.prudnikweb.hypernova.ui.components.AssistantMessage
import com.prudnikweb.hypernova.ui.components.BlurredBottom
import com.prudnikweb.hypernova.ui.components.BlurredTop
import com.prudnikweb.hypernova.ui.components.CommandOutputRow
import com.prudnikweb.hypernova.ui.components.DiffCard
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphButton
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.MiniTerminal
import com.prudnikweb.hypernova.ui.components.NoticeRow
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ResultRow
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.ThinkingRow
import com.prudnikweb.hypernova.ui.components.ToolRow
import com.prudnikweb.hypernova.ui.components.UserMessage
import com.prudnikweb.hypernova.ui.components.Z
import com.prudnikweb.hypernova.ui.components.isWebPage
import com.prudnikweb.hypernova.ui.fx.FrameBudget
import com.prudnikweb.hypernova.ui.fx.Pace
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import com.prudnikweb.hypernova.ui.voice.DictationCapsuleHost
import com.prudnikweb.hypernova.ui.voice.MicButton
import com.prudnikweb.hypernova.ui.voice.VoiceHintHost
import com.prudnikweb.hypernova.ui.voice.VoiceInput
import com.prudnikweb.hypernova.ui.voice.askAgain
import com.prudnikweb.hypernova.ui.voice.openAppSettings
import com.prudnikweb.hypernova.ui.voice.rememberVoiceInput
import com.prudnikweb.hypernova.ui.voice.voiceTail
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

private val EDIT_TOOLS = setOf("Write", "Edit", "MultiEdit", "NotebookEdit")

/** [opening]: which opening of the session this is (a new one each time it's opened; see below). */
@Composable
fun SessionScreen(hub: Hub, sessionId: String, onBack: () -> Unit, opening: Int = 0) {
    val haze = LocalHaze.current
    val opener = LocalOpener.current
    val toast = LocalToast.current
    val appScope = com.prudnikweb.hypernova.ui.LocalAppScope.current
    // Before the early return: a photo the camera app hands back after the app was restarted
    // in the background arrives here, before the sessions do.
    val (attach, pickers) = rememberAttach(sessionId, hub)
    val session = hub.session(sessionId)
    if (session == null) {
        // Gone: don't leave an empty screen. The Mac not linked yet (after the app was restarted,
        // opened from a notification with the Mac away): say so, with Back, until it has the sessions.
        if (macAway(hub)) WaitingForMac(hub, onBack) else LaunchedEffect(sessionId) { onBack() }
        return
    }
    LaunchedEffect(sessionId) { hub.opened(sessionId) }
    val sourceKey = "session:$sessionId"
    val items = hub.transcript(sessionId)
    val loading = hub.loadingTranscript(sessionId)
    val loadError = hub.transcriptError(sessionId)
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (items.size - 1).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val cwd = session.cwd
    val openFile: ((String) -> Unit)? = if (hub.isDemo) null else opener.file
    // The "/" list if it has been fetched (typing "/" does): a command sent from here draws as
    // Claude Code's own or as the owner's before the Mac's copy arrives.
    val known = knownCommands(cwd)
    var tray by rememberSaveable { mutableStateOf(false) }
    // The session sheet (SessionSheet.kt): a tap on the usage strip or the status line.
    var sheetOpen by rememberSaveable(sessionId) { mutableStateOf(false) }
    // The model picker, opened from the session sheet (the Start sheet's, this provider's models).
    var modelOpen by rememberSaveable { mutableStateOf(false) }
    // The sheet opened from the strip's blocked mark: its blocked calls into view.
    var sheetAtBlocked by rememberSaveable(sessionId) { mutableStateOf(false) }
    // The switch to the other agent, open ([AgentKind.CLAUDE] or [AgentKind.CODEX]), from the same sheet.
    var switching by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    // The prompt being written, and text that's waiting unsent in the session's terminal (it
    // stopped the last send; the card above the composer shows it and offers to replace it).
    var draft by rememberSaveable(sessionId) { mutableStateOf("") }
    var busy by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    // A send that failed puts its text back, ahead of anything typed since.
    val back = AttachDrafts.givenBack(sessionId)
    LaunchedEffect(back) {
        val t = AttachDrafts.takeBack(sessionId) ?: return@LaunchedEffect
        draft = if (draft.isBlank()) t else "$t\n$draft"
    }
    // A send that waits for files still on their way (the voice path can send meanwhile): its
    // [replace], until they're all on the Mac.
    var sendWaiting by remember(sessionId) { mutableStateOf<Boolean?>(null) }
    val fixFirst = stringResource(R.string.attach_fix_first)
    /** Send the draft and the files ([replace]: clear unsent text in the terminal first). */
    val send: (Boolean) -> Unit = send@{ replace ->
        // Never without a file that was attached: one that failed is sent again or removed first,
        // and one still uploading is waited for.
        attach.failed?.let { f -> toast(String.format(fixFirst, f.name)); return@send }
        if (attach.uploading) { sendWaiting = replace; return@send }
        val text = draft
        val files = AttachDrafts.take(sessionId)
        if (text.isNotBlank() || files.isNotEmpty()) {
            draft = ""
            busy = null
            tray = false
            // The app's scope: leaving the screen doesn't cancel the send.
            appScope.launch {
                var sent = false
                // The conversation went on with the other agent: the words wait in that session's box.
                var movedTo: String? = null
                try {
                    hub.send(sessionId, text, files.mapNotNull { it.attachment }, replace)
                        .onSuccess { id -> sent = true; if (id != sessionId) opener.switchSession(id) }
                        .onFailure { e ->
                            when (e) {
                                is TerminalBusy -> busy = e.pending
                                is SessionSwitched -> movedTo = e.to
                                else -> toast(e.message.orEmpty())
                            }
                        }
                } finally {
                    // Failed or cancelled: the text and the files go back into the box
                    // (even if you've left the session meanwhile).
                    if (!sent) {
                        AttachDrafts.giveBack(movedTo ?: sessionId, text)
                        AttachDrafts.restore(movedTo ?: sessionId, files)
                    }
                    movedTo?.let { opener.switchSession(it) }
                }
            }
        }
    }
    LaunchedEffect(sendWaiting, attach.uploading, attach.failed) {
        val replace = sendWaiting ?: return@LaunchedEffect
        // A file failed meanwhile: its own message said so, and the text stays in the box.
        if (attach.failed != null) sendWaiting = null
        else if (!attach.uploading) { sendWaiting = null; send(replace) }
    }
    // Files written or edited in this session, newest last, for the strip under the header.
    val changed = remember(items) {
        val seen = LinkedHashSet<String>()
        for (i in items) {
            val p = when (i) {
                is Item.Diff -> i.path
                is Item.Tool -> i.path?.takeIf { i.verb in EDIT_TOOLS && !i.failed }
                else -> null
            }
            if (p != null) { seen.remove(p); seen.add(p) }
        }
        seen.toList().asReversed()
    }

    // The bars' own heights (the bottom grows with a multi-line draft), without the keyboard lift.
    var barPx by remember { mutableIntStateOf(0) }
    var topPx by remember { mutableIntStateOf(0) }
    // Read at measure time, so the keyboard animation relayouts the list without recomposing the screen.
    val contentPadding = remember(top, density, imeInsets, navInsets) {
        TranscriptPadding(
            // Just below the header (its blur fades over its own bottom padding), as in M1.
            top = { if (topPx == 0) top + 132.dp else with(density) { topPx.toDp() } + 8.dp },
            side = 20.dp,
        ) {
            val lift = (imeInsets.getBottom(density) - navInsets.getBottom(density)).coerceAtLeast(0)
            with(density) { (barPx + lift).toDp() } + 8.dp
        }
    }

    // Follow the tail only while you're at the end. Any scroll (drag, fling, TalkBack,
    // mouse wheel) that stops away from the end stops following; one that stops at the
    // end, or "Jump to latest", resumes it.
    var follow by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(list) {
        // The app itself only ever scrolls forward, so any scroll that went backward was
        // the user's (touch, TalkBack, mouse wheel, keyboard), wherever it came from.
        snapshotFlow { list.isScrollInProgress }.filter { !it }.collect {
            if (!list.canScrollForward) follow = true else if (list.lastScrolledBackward) follow = false
        }
    }
    // Voice (ui/voice): the mic where Send is while the box is empty, its words into the draft.
    val voice = rememberVoiceInput(hub, text = { draft }, setText = { draft = it }, onSend = { follow = true; send(false) })
    LaunchedEffect(voice.available) { if (voice.available) voice.firstSeen() }
    // Keep the tail pinned while the keyboard slides in. Only growth needs a scroll:
    // when the padding shrinks, the list's own clamping keeps the tail in place.
    LaunchedEffect(list, imeInsets, navInsets, density) {
        var prev = (imeInsets.getBottom(density) - navInsets.getBottom(density)).coerceAtLeast(0)
        snapshotFlow { (imeInsets.getBottom(density) - navInsets.getBottom(density)).coerceAtLeast(0) }.collect { lift ->
            val d = lift - prev
            prev = lift
            if (follow && d > 0) list.scrollBy(d.toFloat())
        }
    }
    fun tailOf(it: Item) = when (it) {
        is Item.Terminal -> "${it.key}:${it.lines.size}:${it.running}"
        is Item.Assistant -> "${it.key}:${it.text.length}"
        is Item.Approval -> "${it.key}:${it.resolved}:${it.partial}"
        // A command's output arrives under it, after it.
        is Item.User -> "${it.key}:${it.output.size}:${it.status}"
        // A running agent's card grows when it ends (its report) or starts one of its own.
        is Item.AgentCard -> "${it.key}:${it.agent.state}:${it.nested.size}"
        is Item.WorkflowCard -> "${it.key}:${it.workflow.state}:${it.workflow.phases.sumOf { p -> p.agents.size }}"
        else -> it.key
    }
    // Prompts waiting in the queue sit under Claude's live output, so what grows is the last
    // item above them.
    val liveAt = items.indexOfLast { !(it is Item.User && (it.queued || it.waiting || it.held)) }
    val tailKey = listOfNotNull(items.getOrNull(liveAt)?.let(::tailOf), items.lastOrNull()?.takeIf { items.lastIndex != liveAt }?.let(::tailOf)).joinToString("|")
    // Fork: the list's rows (ToolRuns.kt): runs of three or more tool calls fold into one line, and
    // only the first reply after each message carries Claude's name. A run opens on a tap; the run
    // Claude is working in stays open by itself until it's tapped shut.
    // Fork: a tool's permission card goes once it's answered, as in Claude Code's terminal; only a
    // denial stays (worth seeing later). Questions and plans stay: their answers are the content.
    // Fork: the asks still waiting are one stack, the last row of all, under prompts waiting in the
    // queue too (AskStack.kt).
    val (chat, asks) = Asks.split(items.filterNot { it is Item.Approval && it.kind == com.prudnikweb.hypernova.data.ApprovalKind.Tool && it.resolved != null && it.resolved != com.prudnikweb.hypernova.data.Resolution.Denied })
    val rows = ToolRuns.rows(chat).let { if (asks.isEmpty()) it else it + ChatRow.Asks(asks) }
    val labelled = ToolRuns.labelled(items)
    val runOpen = remember(sessionId) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val liveRowKey = items.getOrNull(liveAt)?.let { live -> rows.lastOrNull { it.has(live.key) } }?.key
    // Fork: each opening of the session (from Home, a notification, the banner) lands at the latest
    // and follows it. The app keeps a screen's saved state after it's closed, so the list used to
    // come back where it was left, not following if it had been scrolled up. Coming back from a
    // screen opened over it (an agent, a file) still keeps the place: that's the same opening.
    val openedAs = rememberSaveable { intArrayOf(opening) }
    // Fork: the ask whose start the list last scrolled to (AskStack.kt, Asks.toShow).
    var shownAsk by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    if (openedAs[0] != opening) {
        openedAs[0] = opening
        follow = true
        shownAsk = null
        // Rows, plus "Show earlier" above them (or the one "empty" row).
        val end = if (items.isEmpty()) 0 else (rows.size - 1 + if (hub.hasEarlier(sessionId)) 1 else 0).coerceAtLeast(0)
        SideEffect { list.requestScrollToItem(end, Int.MAX_VALUE) }
    }
    // Claude at work: the status line's string vibrates and says what it's doing, so a sent
    // prompt never meets silence. "Sending" lasts only while the prompt is on its way: a command
    // the transcript never logs settles after a few seconds instead of sending forever, and one
    // queued while Claude works says so in the transcript, not here.
    val sending = hub.sending(sessionId)
    // The Mac isn't linked (asleep, away, reconnecting): nothing here is live, so nothing says
    // "working" with a ticking clock; the status line and the bar say where the Mac is instead.
    val linked = hub.isDemo || hub.link.state == LinkState.Connected
    val working = linked && (sending || session.phase == Phase.Starting ||
        (session.state == SessionState.Working && items.none { it is Item.Approval && it.resolved == null }))
    // The status bar above the composer: while it shows, the header line below doesn't repeat it.
    val statusBar = rememberStatusBar(hub, session, items, sending, away = if (linked) null else linkWord(hub.link.state))
    val workingSince = remember(sessionId, working) { System.currentTimeMillis() }
    // The session's usage, for the strip and the sheet: asked again as the transcript settles, and
    // when the session's model changes (/model).
    val usage = rememberSessionUsage(hub, sessionId, "$tailKey|$working|${session.model}")
    // A limit, a sign-out or the provider stopped it: the card above the composer and its hint say so.
    val block = activeBlock(hub, session)
    // The chat's quick wins (ChatExtras.kt): which message's long-press menu is open, and a quick
    // prompt or Compact sent as it is, leaving the box alone.
    var menuKey by rememberSaveable(sessionId) { mutableStateOf<String?>(null) }
    // Continue after the away stop is on its way: a second tap doesn't send a second "continue".
    var continuing by remember(sessionId) { mutableStateOf(false) }
    // A double tap on a chip (or Compact) sends once.
    var lastQuick by remember(sessionId) { mutableStateOf<Pair<String, Long>?>(null) }
    val quickSend: (String) -> Unit = quick@{ text ->
        val at = android.os.SystemClock.elapsedRealtime()
        lastQuick?.let { (t, when_) -> if (t == text && at - when_ < QUICK_AGAIN_MS) return@quick }
        lastQuick = text to at
        follow = true
        appScope.launch {
            hub.send(sessionId, text)
                .onSuccess { id -> if (id != sessionId) opener.switchSession(id) }
                .onFailure { e ->
                    when (e) {
                        is TerminalBusy -> busy = e.pending
                        is SessionSwitched -> opener.switchSession(e.to)
                        else -> toast(e.message.orEmpty())
                    }
                }
        }
    }
    // "Show earlier": the item that was first stays where the eye is, with the last of the
    // earlier ones just above it, instead of the list jumping to the new start.
    var earlierAnchor by remember(sessionId) { mutableStateOf<String?>(null) }
    LaunchedEffect(items.size) {
        val k = earlierAnchor ?: return@LaunchedEffect
        // The anchor's row: its own, or the folded run it's in.
        val i = rows.indexOfFirst { it.has(k) }
        if (i > 0) {
            earlierAnchor = null
            // The "Show earlier" row (if still there) is the list's first item.
            list.scrollToItem(i - 1 + if (hub.hasEarlier(sessionId)) 1 else 0)
        }
    }
    // The tail's glide steps at the live pace's rate (30 a second on a cool phone, fewer as it
    // warms: ui/fx/Pace.kt), the rate the sky's live motion already uses.
    val motion = LocalMotion.current
    val glideFps by rememberUpdatedState(FrameBudget.fps(Pace.Live, motion.heat, motion.powerSave))
    LaunchedEffect(tailKey, barPx, items.size, working) {
        if (!follow || items.isEmpty()) return@LaunchedEffect
        val last = items.getOrNull(liveAt)
        // Fork: only once for each ask. Every run of this (the bar's height changing as "Jump to
        // latest" folds away, say) went back to the ask's start, so scrolling past the bottom of a
        // tall one jumped the list back up. After that it follows the end like anything else.
        val ask = Asks.toShow(last, shownAsk)
        if (last is Item.Approval && ask != null) {
            shownAsk = ask
            // A new question or plan can be taller than the screen: show where it starts.
            withFrameNanos { }
            // An approval is never folded: its own row in the list (the stack's, AskStack.kt), after
            // "Show earlier" when that's there.
            list.animateScrollToItem(rows.indexOfFirst { it.has(last.key) }.coerceAtLeast(0) + if (hub.hasEarlier(sessionId)) 1 else 0)
        } else {
            followTail(list, glideFps, motion.reduced)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().hazeSource(haze, zIndex = Z.CONTENT, key = sourceKey),
            state = list,
            contentPadding = contentPadding,
            // Fork: 10dp between rows (was 18), so more of a session fits on the screen.
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (items.isEmpty()) {
                item(key = "empty") {
                    when {
                        loading -> LoadingLine(stringResource(R.string.loading_conversation), Modifier.padding(top = 8.dp))
                        loadError != null -> Column(Modifier.padding(top = 8.dp)) {
                            HText(loadError, style = Type.body, color = HN.danger)
                            // Another try only helps once the Mac is linked (the status line says when it isn't).
                            if (linked) {
                                Spacer(Modifier.height(12.dp))
                                Pill(stringResource(R.string.try_again), { hub.reloadTranscript(sessionId) }, kind = PillKind.Outline, height = 44.dp)
                            }
                        }
                        else -> HText(stringResource(R.string.empty_conversation), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            // The start of a long session is further back: a way to it, at the top.
            if (items.isNotEmpty() && hub.hasEarlier(sessionId)) {
                item(key = "earlier") {
                    ShowEarlier(hub.loadingEarlier(sessionId)) {
                        earlierAnchor = items.first().key
                        hub.loadEarlier(sessionId)
                    }
                }
            }
            items(rows, key = { it.key }) { row ->
                // New items fade in, and nothing else moves by itself: with the list's default
                // placement spring, a card under one that grows (a command's output streaming in)
                // glided after it and was drawn over it, and an item that left (a run replaced,
                // a prompt delivered, an approval that moved to its own event) faded out where it
                // was while its successor slid over it: the same Bash card, "exit 0" and all,
                // drawn twice, a little apart, while the tail followed Claude's output.
                val rowModifier = Modifier.fillMaxWidth().animateItem(fadeInSpec = ItemFadeIn, placementSpec = null, fadeOutSpec = null)
                when (row) {
                    is ChatRow.One -> TranscriptItem(hub, sessionId, row.item, rowModifier, known = known, onMenu = { menuKey = it.key }, named = row.item.key in labelled)
                    is ChatRow.Run -> {
                        val open = runOpen[row.key] ?: (row.running || (working && row.key == liveRowKey))
                        ToolRunRow(row, open, onToggle = { runOpen[row.key] = !open }, modifier = rowModifier) { member ->
                            TranscriptItem(hub, sessionId, member, Modifier.fillMaxWidth(), known = known, onMenu = { menuKey = it.key })
                        }
                    }
                    is ChatRow.Asks -> AskStack(row.asks, rowModifier) { ask, m -> TranscriptItem(hub, sessionId, ask, m.fillMaxWidth()) }
                }
            }
        }

        // Fork: a shorter header (less padding and fade under it) leaves more of the chat in view.
        BlurredTop(haze, sourceKey, Modifier.fillMaxWidth().onSizeChanged { topPx = it.height }, fade = 16.dp) {
            Column(Modifier.fillMaxWidth().padding(top = top + 4.dp, start = 8.dp, end = 12.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphButton(Glyph.Back, stringResource(R.string.back), onBack)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        HText(session.name, style = Type.title, color = HN.ink, maxLines = 1)
                        // Its project, and its short id as a quiet mark after it (not "c7 · project").
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HText(session.project, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(8.dp))
                            HText(session.hex, style = Type.monoSmall.copy(fontSize = 10.sp), color = HN.inkDim.copy(alpha = 0.75f), maxLines = 1)
                        }
                    }
                    // A Codex session gets a terminal when it's asked for (`codex resume` on the Mac).
                    if ((session.term != null || session.codex) && !hub.isDemo) {
                        GlyphButton(Glyph.Terminal, stringResource(R.string.open_terminal), { opener.terminal(sessionId) }, color = if ((session.term?.mac ?: 0) > 0) HN.accent else HN.ink)
                    }
                    // Where the Mac lets the phone browse; quieter where only Browse outside projects would
                    // (its page says why, with Open projects and Turn on in Settings). Elsewhere, none.
                    if (cwd != null && !hub.isDemo && (session.browsable || session.outsideProjects)) {
                        GlyphButton(Glyph.Folder, stringResource(R.string.files), { opener.files(cwd, session.project) }, color = if (session.browsable) HN.ink else HN.inkDim)
                    }
                }
                Spacer(Modifier.height(6.dp))
                // While Claude works: what it's doing ("Bash: npm run build" → BASH), and for how long.
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(working) {
                    while (working) {
                        now = System.currentTimeMillis()
                        kotlinx.coroutines.delay(1000)
                    }
                }
                val doing = when {
                    // While Claude works the line keeps saying what it does: a prompt sent meanwhile
                    // says "Sending…" (then "Queued") in the transcript.
                    sending && session.phase != Phase.Working -> stringResource(R.string.state_sending)
                    session.phase == Phase.Starting -> stringResource(R.string.state_starting)
                    else -> session.activity.substringBefore(':').takeIf { it.isNotBlank() && it.length <= 18 } ?: stringResource(R.string.state_working)
                }
                // The main agent is done for now, but agents it started work on: say so, not "idle".
                val agentsOnly = linked && !working && session.agentsRunning > 0 && session.state == SessionState.Idle && session.phase != Phase.Ended
                // Started by an automation: the status line starts "AUTO · Morning brief", a way to it.
                session.automation?.let { auto -> com.prudnikweb.hypernova.ui.automations.AutoLine(auto.name, { opener.automation(auto.id) }, Modifier.padding(start = 12.dp)) }
                // It switched agents: "CONTINUED FROM CLAUDE ›", a way to the conversation before (or after) it.
                SwitchLine(hub, session, Modifier.padding(start = 12.dp))
                // Fork: no agents, queue or mode after the words (owner, 2026-10-29): those are the
                // session sheet's, and TalkBack hears them on the strip. The planet before them is
                // back (owner, 2026-10-30): it turns while Claude works, holds amber when it needs you.
                val more = stripMore(session, agentsAtWork(session, sheetAgents(hub.agents(sessionId))))
                val words = when {
                    statusBar.visible -> ""
                    !linked -> linkWord(hub.link.state)
                    working -> doing
                    agentsOnly -> stringResource(R.string.agents_label)
                    block != null -> blockLabel(block, hub.providers.firstOrNull { it.id == session.providerId }?.status)
                    else -> phaseLabel(session)
                }
                Seam(
                    line = false,
                    label = words,
                    activity = when {
                        !linked -> Activity.Rest
                        session.state == SessionState.Working -> Activity.Live
                        session.state == SessionState.NeedsYou -> Activity.Alert
                        agentsOnly -> Activity.Live
                        else -> Activity.Rest
                    },
                    after = if (working && !statusBar.visible) elapsedText(now - workingSince) else null,
                    // "Thinking" is the daemon's word for Claude between tools: the label breathes.
                    thinking = !statusBar.visible && working && sending.not() && session.phase == Phase.Working && session.activity.substringBefore(':') == "Thinking",
                    // The session's usage in the rest of the row: context, tokens, the 5-hour and weekly
                    // windows (UsageStrip.kt); a tap opens the session sheet. Then the blocked mark
                    // while calls wait, a tap to them in the sheet.
                    trailContent = {
                        ChatUsage(
                            hub, session, usage, onCompact = { quickSend("/compact") },
                            onOpen = { sheetAtBlocked = false; sheetOpen = true },
                            onBlocked = { sheetAtBlocked = true; sheetOpen = true },
                            // No words while the status bar shows: the planet's gap is enough.
                            more = more, Modifier.padding(start = if (words.isEmpty()) 0.dp else 10.dp),
                        )
                    },
                    color = when {
                        !linked -> HN.inkMuted
                        block?.needsYou == true -> HN.amber
                        session.phase == Phase.Error -> HN.danger
                        session.state == SessionState.NeedsYou -> HN.amber
                        working || agentsOnly -> HN.accentDim
                        else -> HN.inkMuted
                    },
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = stringResource(R.string.session_sheet_open), role = Role.Button) { sheetAtBlocked = false; sheetOpen = true },
                )
                // Its reply being read aloud here: the speaking wave.
                com.prudnikweb.hypernova.ui.voice.SpeakingStatus(session.id, Modifier.padding(start = 12.dp))
                // Fork: no "what changed" row (git summary / written files) in the header; the room
                // goes to the chat.
            }
        }

        BlurredBottom(
            haze, sourceKey,
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Only the part of the keyboard beyond the nav bar: the composer keeps its own nav padding.
                .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
                .onSizeChanged { barPx = it.height },
        ) {
            Column(Modifier.padding(top = 40.dp, bottom = nav + 12.dp, start = 16.dp, end = 16.dp)) {
                // A menu or dialog only the terminal shows: its options as buttons here, or (one
                // that doesn't read as a menu) a way to the terminal.
                val noCard = items.none { it is Item.Approval && it.resolved == null }
                val menu = session.menu?.takeIf { noCard && session.term != null && !session.codex }
                AnimatedVisibility(menu != null, enter = fadeIn(tween(Motion.DIALOG)) + expandVertically(tween(Motion.REVEAL, easing = Motion.Expo)), exit = fadeOut(tween(Motion.DIALOG)) + shrinkVertically(tween(Motion.DIALOG))) {
                    // Keeps the last menu on screen while it folds away.
                    val last = remember { arrayOfNulls<TermMenu>(1) }
                    if (menu != null) last[0] = menu
                    val m = last[0] ?: return@AnimatedVisibility
                    Column {
                        MenuCard(
                            m,
                            onPick = { choice, alt ->
                                appScope.launch { hub.answerMenu(sessionId, m.id, choice, alt).onFailure { toast(it.message.orEmpty()) } }
                            },
                            onOpen = { opener.terminal(sessionId) },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                // A question in words isn't a dialog: the reply goes in the composer, not the terminal.
                val waitingInTerminal = session.term != null && session.state == SessionState.NeedsYou && noCard && menu == null && session.question == null
                if (waitingInTerminal && !hub.isDemo) {
                    WaitingCard(codex = session.codex, onOpen = { opener.terminal(sessionId) })
                    Spacer(Modifier.height(12.dp))
                }
                val pending = busy
                if (pending != null) {
                    // Opening the terminal is where the text gets fixed: the card goes (a send that
                    // still meets text there brings it back).
                    TerminalTextCard(pending, onReplace = { follow = true; send(true) }, onOpen = { busy = null; opener.terminal(sessionId) }, onDismiss = { busy = null })
                    Spacer(Modifier.height(12.dp))
                }
                AnimatedVisibility(block != null, enter = fadeIn(tween(Motion.DIALOG)) + expandVertically(tween(Motion.REVEAL, easing = Motion.Expo)), exit = fadeOut(tween(Motion.DIALOG)) + shrinkVertically(tween(Motion.DIALOG))) {
                    // Keeps the last block on screen while the card folds away.
                    val last = remember { arrayOfNulls<Blocked>(1) }
                    if (block != null) last[0] = block
                    val b = last[0] ?: return@AnimatedVisibility
                    Column {
                        BlockedCard(hub, session, b, onMoved = { id -> follow = true; if (id != sessionId) opener.switchSession(id) })
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (session.stoppedForAway) {
                    PausedCard(busy = continuing, codex = session.codex, onContinue = {
                        if (!continuing) {
                            continuing = true
                            follow = true
                            appScope.launch {
                                try {
                                    hub.resume(sessionId)
                                        .onSuccess { id -> if (id != sessionId) opener.switchSession(id) }
                                        .onFailure { toast(it.message.orEmpty()) }
                                } finally {
                                    continuing = false
                                }
                            }
                        }
                    })
                    Spacer(Modifier.height(12.dp))
                }
                // The agent status bar and, opened, Claude's to-do list (StatusBar.kt), while a turn runs.
                ChatStatusBar(hub, session, statusBar)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ReadingPill(items)
                    Spacer(Modifier.weight(1f))
                    // Expands rather than popping in, so the bar never jumps a pill's height at once.
                    AnimatedVisibility(
                        !follow && list.canScrollForward,
                        enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
                        exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
                    ) {
                        Pill(
                            stringResource(R.string.jump_latest),
                            { follow = true; scope.launch { followTail(list, glideFps, motion.reduced) } },
                            kind = PillKind.Outline, height = 40.dp,
                        )
                    }
                }
                // A shared terminal needs no note (what you send is typed there); a terminal session
                // that isn't shared forks when you write, which is worth saying, once you start to
                // (owner, 2026-10-30: not while it only waits on you).
                val note = when {
                    session.term != null || draft.isBlank() -> null
                    session.origin == "terminal" && session.phase != Phase.Ended -> R.string.terminal_session_note
                    else -> null
                }
                if (note != null) {
                    Spacer(Modifier.height(8.dp))
                    HText(stringResource(note), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 6.dp))
                }
                Spacer(Modifier.height(10.dp))
                // "/" opens the commands and skills Claude Code has in this project.
                // (Claude Code's: a Codex session has none of them.)
                val cmdQuery = if (session.codex) null else commandQuery(draft)
                val commands = rememberCommands(hub, cwd, cmdQuery != null)
                AnimatedVisibility(
                    cmdQuery != null,
                    enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
                    exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
                ) {
                    CommandSuggestions(commands, cmdQuery.orEmpty(), onPick = { draft = "/${it.name} " }, Modifier.padding(bottom = 10.dp))
                }
                val picked = commands.picked(draft)
                if (picked != null && picked.argumentHint.isNotBlank()) CommandHint(picked, Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp))
                AnimatedVisibility(
                    tray && !hub.isDemo,
                    enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
                    exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
                ) {
                    AttachTray(pickers, Modifier.padding(start = 4.dp, bottom = 10.dp), onPicked = { tray = false })
                }
                // Fork: no quick-prompt chips over the box (fixed defaults, rarely the reply wanted).
                // Voice: a one-line note (why it stopped, a tip, the microphone), and the capsule
                // while listening, both added above the box so it never moves under the finger.
                VoiceHintHost(voice.hint, voice::dismissHint, onAllow = { askAgain(voice) }, onSettings = { openAppSettings(context) })
                DictationCapsuleHost(voice)
                Composer(
                    text = draft,
                    hint = when {
                        !linked -> stringResource(R.string.hint_mac_away_queue)
                        // Gone on with the other agent: a prompt here goes there (the send says so).
                        session.switchedTo != null -> stringResource(R.string.cx_hint_went_on, com.prudnikweb.hypernova.ui.components.agentName(hub.session(session.switchedTo)?.codex ?: !session.codex))
                        else -> block?.let { blockedHint(hub, session, it) } ?: stringResource(R.string.prompt_hint)
                    },
                    onText = { draft = it },
                    attach = attach,
                    trayOpen = tray,
                    onTray = if (hub.isDemo) null else ({ tray = !tray }),
                    voice = voice,
                )
            }
        }
        // A message's long-press menu (MessageMenu.kt); Quote puts the words at the head of the box.
        ChatMenuHost(hub, session, items, menuKey, onQuote = { q -> draft = q + draft }, onDismiss = { menuKey = null })
        // The session sheet (SessionSheet.kt); the model picker and the switch open over it.
        if (sheetOpen) {
            SessionSheet(
                hub, session, usage,
                onModel = { modelOpen = true },
                onSwitchAgent = { to -> sheetOpen = false; switching = to },
                // After Allow & retry, to the chat: Claude runs it again there.
                // The sheet stays while blocked calls are left to answer; it goes with the last.
                onRetried = { follow = true },
                onEmptied = { sheetOpen = false },
                onDismiss = { sheetOpen = false },
                atBlocked = sheetAtBlocked,
            )
        }
        if (modelOpen && canChange(hub, session)) {
            if (session.codex) CodexModelPicker(hub, session, onDismiss = { modelOpen = false })
            else SessionModelPicker(hub, session, onDismiss = { modelOpen = false })
        }
        ModelSwitchHost(hub, session)
        // Switching to the other agent (AgentSwitchSheet.kt): on success the chat goes on in the new session.
        switching?.let { to ->
            AgentSwitchSheet(hub, session, to, onSwitched = { id -> switching = null; opener.switchSession(id) }, onDismiss = { switching = null })
        }
    }
}

/**
 * One transcript item, drawn as what it is (a session's own, or an agent's on its screen). Files
 * and previews open in the app; an agent's card opens that agent.
 */
@Composable
internal fun TranscriptItem(hub: Hub, sessionId: String, item: Item, modifier: Modifier, known: List<com.prudnikweb.hypernova.data.SlashCommand>? = null, onMenu: ((Item) -> Unit)? = null, named: Boolean = true) {
    val opener = LocalOpener.current
    val preview: (Int) -> Unit = { port -> opener.preview(null, port, "localhost:$port") }
    // Read rows and diff cards open their file only where the Mac lets the phone browse the folder.
    val openFile: ((String) -> Unit)? = if (hub.isDemo || hub.session(sessionId)?.browsable == false) null else opener.file
    val openAgent: (String) -> Unit = { id -> opener.agent(sessionId, id) }
    when (item) {
        // [onMenu]: the session's chat gives its messages a long-press menu (ChatExtras.kt).
        is Item.User -> WithMessageMenu(item, onMenu) { menu ->
            val appScope = LocalAppScope.current
            val toast = LocalToast.current
            UserMessage(
                item, modifier.then(menu), known = known,
                codex = hub.session(sessionId)?.codex == true,
                onStartNew = item.waitingId?.let { w -> { appScope.launch { hub.startFromWaiting(w).onSuccess { opener.switchSession(it) }.onFailure { toast(it.message.orEmpty()) } } } },
                // Attachments are the Mac's own copies (its attachments folder), wherever the session is.
                files = { SentFiles(item.files, hub, if (hub.isDemo) null else opener.file) },
            )
        }
        is Item.Assistant -> WithMessageMenu(item, onMenu) { menu -> AssistantMessage(item, modifier.then(menu), onPreview = preview, codex = hub.session(sessionId)?.codex == true, named = named) }
        is Item.Tool -> ToolRow(item, modifier, onOpen = openFile, onPreview = preview)
        is Item.Terminal -> MiniTerminal(item, modifier, onPreview = preview)
        is Item.Diff -> DiffCard(item, modifier, onOpen = openFile, onPreviewFile = if (hub.isDemo) null else ({ path -> opener.preview(path, null, path.substringAfterLast('/')) }))
        is Item.Approval -> {
            // "Always" waits for the fingerprint or the screen lock (docs/DECISIONS.md).
            val context = LocalContext.current
            val toast = LocalToast.current
            val title = stringResource(R.string.check_always_title)
            val noLock = stringResource(R.string.check_no_lock)
            ApprovalBlock(item, onAnswer = { a ->
                if (a == Answer.Always) {
                    com.prudnikweb.hypernova.ui.components.OwnerCheck.ask(
                        context, title, item.detail.lineSequence().firstOrNull().orEmpty().take(80), noLock, hub.isDemo,
                        onOk = { hub.answer(sessionId, item, a) }, onFail = { msg -> if (msg != null) toast(msg) },
                    )
                } else hub.answer(sessionId, item, a)
            }, modifier = modifier)
        }
        is Item.Thinking -> ThinkingRow(item, modifier)
        is Item.Result -> ResultRow(item, modifier)
        is Item.Notice -> if (item.command != null) CommandOutputRow(item.key, item.text, item.tone, modifier) else NoticeRow(item, modifier)
        is Item.AgentCard -> AgentCard(item, openAgent, modifier)
        is Item.WorkflowCard -> WorkflowCard(item, openAgent, modifier)
    }
}

/** The Mac isn't linked yet or now (not the demo, not unpaired): what's on screen may be stale. */
internal fun macAway(hub: Hub): Boolean =
    !hub.isDemo && hub.link.state != LinkState.Connected && hub.link.state != LinkState.Unpaired

/** The link's state in a word, for the status line ("Reconnecting", "Offline"). */
@Composable
internal fun linkWord(state: LinkState): String = stringResource(
    when (state) {
        LinkState.Connecting -> R.string.link_connecting
        LinkState.Reconnecting -> R.string.link_reconnecting
        LinkState.Unpaired -> R.string.link_unpaired
        else -> R.string.link_offline
    },
)

/**
 * A session or agent screen opened before the Mac has said what it has (from a notification with
 * the Mac away, or the app restarted on this screen): a header with Back, where the link is, and
 * a way to try now. It turns into the screen as soon as the session arrives.
 */
@Composable
internal fun WaitingForMac(hub: Hub, onBack: () -> Unit) {
    val res = LocalContext.current.resources
    val link = hub.link
    com.prudnikweb.hypernova.ui.components.ScreenScaffold(sourceKey = "waiting", title = stringResource(R.string.waiting_mac_title), onBack = onBack) { top, source ->
        Column(Modifier.fillMaxSize().then(source).padding(top = top + 8.dp, start = 20.dp, end = 20.dp)) {
            if (link.state == LinkState.Connecting || (link.state == LinkState.Reconnecting && link.problem == null) || link.retrying) {
                LoadingLine(stringResource(R.string.waiting_mac_body))
            } else {
                HText(com.prudnikweb.hypernova.data.linkProblemText(res, link), style = Type.body, color = HN.inkMuted)
                Spacer(Modifier.height(14.dp))
                Pill(stringResource(R.string.waiting_mac_retry), { hub.retry() }, kind = PillKind.Outline, height = 44.dp, leading = Glyph.Reload)
            }
        }
    }
}

/**
 * The session's model and permission mode can be changed from here: not once it has ended, and
 * not for a terminal session the Mac only watches (its terminal isn't shared). A Codex session's
 * are Codex's own, changed over its app-server wherever it was started.
 */
internal fun canChange(hub: Hub, s: Session): Boolean =
    s.phase != Phase.Ended && (s.codex || s.term != null || s.origin == "hypernova" || hub.isDemo)

/**
 * The session sheet's Model part: the model the session runs, as its chip, and a way to another
 * one (the picker below). TalkBack hears "Model: Opus 5.5" and the action.
 */
@Composable
internal fun ModelLine(model: String, onClick: () -> Unit) {
    val res = LocalContext.current.resources
    val name = com.prudnikweb.hypernova.data.ModelNames.chip(model)
    val said = res.getString(R.string.meta_model_said, name)
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .glassChoice(false, 12.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.model_change), onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(stringResource(R.string.model).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        com.prudnikweb.hypernova.ui.components.ModelChip(name)
        Spacer(Modifier.width(10.dp))
        Icon(Glyph.Chevron, size = 11.dp, color = HN.inkDim)
    }
}

/**
 * The Start sheet's model picker for a running session: only its own provider's models (moving
 * to another provider is the limit card's "carry on elsewhere"), the one it runs marked. A pick
 * switches it ([Hub.setModel]); on a provider that bills it, after the owner's yes ("This costs
 * money", as automations ask).
 */
@Composable
private fun SessionModelPicker(hub: Hub, s: Session, onDismiss: () -> Unit) {
    val provider = hub.providers.firstOrNull { it.id == (s.providerId ?: "anthropic") } ?: hub.providers.firstOrNull { it.builtIn } ?: return
    var lists by remember { mutableStateOf<List<com.prudnikweb.hypernova.data.ModelList>?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(hub) {
        hub.models().onSuccess { lists = it }
        loading = false
    }
    val options = remember(provider, lists) { com.prudnikweb.hypernova.data.ModelCatalog.of(provider, lists) }
    val current = com.prudnikweb.hypernova.data.ModelCatalog.current(options, s.model)
    val opened = remember { System.currentTimeMillis() }
    com.prudnikweb.hypernova.ui.home.ModelPicker(
        listOf(provider), lists, loading, provider.id,
        // None of the list runs it (a model it was started on elsewhere): nothing is marked.
        model = if (current != null) current.value else NONE_MARKED,
        weeks = hub.usage.models, now = opened,
        providerTrail = { null },
        onPick = { _, value ->
            onDismiss()
            val o = com.prudnikweb.hypernova.data.ModelCatalog.find(options, value)
            if (o != null && o != current) {
                ModelSwitching.ask(s.id, o.value ?: o.model, o.name, paid = com.prudnikweb.hypernova.data.ModelCatalog.paid(provider, o))
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * A model switch on its way, and the "This costs money" question it may need first. Kept outside
 * the picker (which closes on the pick) so the question and the switch outlive it.
 */
private object ModelSwitching {
    /** The switch waiting for the owner's yes: session, model (null: the default), its name. */
    var asking by mutableStateOf<Triple<String, String?, String>?>(null)
    var pending by mutableStateOf<Pair<Triple<String, String?, String>, Boolean>?>(null)

    fun ask(sessionId: String, model: String?, name: String, paid: Boolean) {
        val t = Triple(sessionId, model, name)
        if (paid) asking = t else pending = t to false
    }
}

/**
 * Runs the switch [ModelSwitching] holds, and asks "This costs money" when the session's provider
 * bills it (or the Mac says so): the Mac switches only with that yes.
 */
@Composable
private fun ModelSwitchHost(hub: Hub, s: Session) {
    val appScope = com.prudnikweb.hypernova.ui.LocalAppScope.current
    val toast = LocalToast.current
    val res = LocalContext.current.resources
    val go = ModelSwitching.pending
    LaunchedEffect(go) {
        val (t, paid) = go ?: return@LaunchedEffect
        ModelSwitching.pending = null
        appScope.launch {
            hub.setModel(t.first, t.second, confirmPaid = paid)
                .onSuccess { sw -> if (sw.nextTurn) toast(res.getString(R.string.model_next_turn, t.third)) }
                .onFailure { e -> if (e is com.prudnikweb.hypernova.data.PaidModel && !paid) ModelSwitching.asking = t else toast(e.message.orEmpty()) }
        }
    }
    val q = ModelSwitching.asking?.takeIf { it.first == s.id } ?: return
    val provider = hub.providers.firstOrNull { it.id == (s.providerId ?: "anthropic") }?.name ?: s.providerId.orEmpty()
    val dismiss = { ModelSwitching.asking = null }
    com.prudnikweb.hypernova.ui.components.GlassDialog(
        onDismiss = dismiss,
        eyebrow = q.third,
        title = stringResource(R.string.model_paid_q),
        line = stringResource(R.string.model_paid_body, provider),
        primary = com.prudnikweb.hypernova.ui.components.DialogButton(stringResource(R.string.model_paid_ok), { ModelSwitching.pending = q to true; dismiss() }, PillKind.Primary),
        secondary = com.prudnikweb.hypernova.ui.components.DialogButton(stringResource(R.string.cancel), dismiss),
        tint = HN.amber,
    )
}

/** A second tap on the same quick prompt (or Compact) within this long is the same tap. */
private const val QUICK_AGAIN_MS = 1500L

/** A value no model has: the picker marks none. */
private const val NONE_MARKED = "\u0000none"

private fun elapsedText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

@Composable
private fun phaseLabel(s: Session): String = when (s.phase) {
    Phase.Starting -> stringResource(R.string.state_starting)
    Phase.Error -> stringResource(R.string.state_error)
    Phase.Ended -> stringResource(R.string.state_ended)
    else -> when (s.state) {
        SessionState.Working -> stringResource(R.string.state_working)
        SessionState.NeedsYou -> stringResource(R.string.state_needs_you)
        SessionState.Idle -> stringResource(R.string.state_idle)
    }
}

/**
 * What stops Claude replying in [s] now, or null. Looked at again when its limit resets (not on
 * a clock), and as the account and providers change.
 */
@Composable
private fun activeBlock(hub: Hub, s: Session): Blocked? {
    val b = s.blocked ?: return null
    val now by produceState(System.currentTimeMillis(), b) {
        val until = b.resetsAt?.let { it - System.currentTimeMillis() }
        if (until != null && until > 0) {
            kotlinx.coroutines.delay(until + 500)
            value = System.currentTimeMillis()
        }
    }
    return s.activeBlock(hub.account, hub.providers, now)
}

/**
 * The status line's word for a block: PAUSED at a limit, UNAVAILABLE for a model with nothing to
 * wait for, else what the owner has to fix.
 */
@Composable
private fun blockLabel(b: Blocked, provider: com.prudnikweb.hypernova.data.ProviderStatus? = null): String = stringResource(
    when (b.kind) {
        Blocked.LOGGED_OUT -> R.string.state_signed_out
        Blocked.NO_SUBSCRIPTION -> R.string.state_no_plan
        Blocked.ON_HOLD -> R.string.state_on_hold
        Blocked.NO_CREDIT -> R.string.state_no_credit
        // Another provider's own trouble ([provider]: its status).
        Blocked.PROVIDER -> when (provider?.state) {
            "no_credit" -> R.string.state_out_of_credits
            "bad_key" -> R.string.state_key_refused
            "limited" -> R.string.state_rate_limited
            "down" -> R.string.state_not_answering
            else -> R.string.state_paused
        }
        else -> if (b.unavailable()) R.string.state_unavailable else R.string.state_paused
    },
)

/** The model a one-model limit rests ("Opus"), from the account or Claude Code's line. */
@Composable
private fun restingModel(hub: Hub, b: Blocked): String =
    AccountWords.label(LocalContext.current, b, hub.account).replace(Regex("\\s+limit$", RegexOption.IGNORE_CASE), "")

/**
 * A model block with no reset: not a limit to wait out but a model this plan can't use, or one
 * that needs usage credits ("Claude Opus is not available with the Claude Pro plan"). Not private:
 * Home's chip for the session can say the same.
 */
internal fun Blocked.unavailable(): Boolean = kind == Blocked.MODEL && resetsAt == null && autoResumeAt == null

/** The other everyday model to switch to: Opus can't reply means Sonnet, and the other way round. */
@Composable
private fun otherModel(hub: Hub, b: Blocked): String {
    val about = if (b.unavailable()) b.text else restingModel(hub, b)
    return if (about.contains("sonnet", ignoreCase = true) && !about.contains("opus", ignoreCase = true)) "opus" else "sonnet"
}

/** The composer's hint while Claude can't reply: "Claude can't reply until 2:00". */
@Composable
private fun blockedHint(hub: Hub, s: Session, b: Blocked): String {
    val context = LocalContext.current
    return when (b.kind) {
        Blocked.LIMIT -> b.resetsAt?.let { stringResource(R.string.hint_blocked_until, AccountWords.clock(context, it)) } ?: stringResource(R.string.hint_blocked_limit)
        Blocked.MODEL -> b.resetsAt?.let { stringResource(R.string.hint_blocked_model, restingModel(hub, b), AccountWords.clock(context, it)) } ?: stringResource(R.string.hint_blocked_model_any)
        Blocked.LOGGED_OUT -> stringResource(R.string.hint_blocked_sign_in)
        Blocked.NO_SUBSCRIPTION -> stringResource(R.string.hint_blocked_plan)
        Blocked.ON_HOLD -> stringResource(R.string.hint_blocked_hold)
        Blocked.NO_CREDIT -> stringResource(R.string.hint_blocked_credits)
        else -> stringResource(R.string.hint_blocked_provider, hub.providers.firstOrNull { it.id == s.providerId }?.name ?: s.model)
    }
}

/**
 * Claude can't reply here: a limit, a sign-out or the session's provider stopped it. One line of
 * what happened, when it's back in mono, and the way on: the conversation on another provider,
 * the other model for a one-model limit, or signing in. Amber only when it needs the owner.
 * [onMoved]: the session that carries on (a new one when a live terminal session forks).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BlockedCard(hub: Hub, s: Session, b: Blocked, onMoved: (String) -> Unit) {
    val context = LocalContext.current
    val appScope = com.prudnikweb.hypernova.ui.LocalAppScope.current
    val toast = LocalToast.current
    val opener = LocalOpener.current
    val now = System.currentTimeMillis()
    var busy by remember { mutableStateOf(false) }
    fun act(block: suspend () -> Result<String>) {
        if (busy) return
        busy = true
        appScope.launch {
            block().onSuccess(onMoved).onFailure { toast(it.message.orEmpty()) }
            busy = false
        }
    }
    val provider = hub.providers.firstOrNull { it.id == s.providerId }
    val title = if (b.unavailable()) stringResource(R.string.blocked_model_unavailable) else when (b.kind) {
        Blocked.LIMIT, Blocked.MODEL -> stringResource(R.string.blocked_limit, AccountWords.label(context, b, hub.account))
        Blocked.LOGGED_OUT -> stringResource(R.string.blocked_signed_out)
        Blocked.NO_SUBSCRIPTION -> stringResource(R.string.blocked_no_plan)
        Blocked.ON_HOLD -> stringResource(R.string.blocked_on_hold)
        Blocked.NO_CREDIT -> stringResource(R.string.blocked_no_credit)
        else -> stringResource(R.string.blocked_provider, provider?.name ?: s.model)
    }
    val line = when {
        b.autoResumeAt != null && b.autoResumeAt > now -> stringResource(R.string.blocked_carries_on, AccountWords.atClock(context, b.autoResumeAt, now))
        b.resetsAt != null && b.resetsAt > now -> stringResource(R.string.blocked_back, AccountWords.atClock(context, b.resetsAt, now))
        // The provider's own words: out of credits, the key turned down.
        b.kind == Blocked.PROVIDER -> provider?.status?.text?.takeIf { it.isNotBlank() } ?: b.text.takeIf { it.isNotBlank() }
        // Nothing to wait for. Claude Code's own words are in the notice just above the card, so a
        // short line of its own instead of the same words twice. "Not available with the Pro plan"
        // may mention limits too, so it's asked about first.
        b.unavailable() -> stringResource(
            when {
                b.text.contains("not available", ignoreCase = true) -> R.string.blocked_model_not_on_plan
                b.text.contains("credit", ignoreCase = true) -> R.string.blocked_model_needs_credits
                b.text.contains("limit", ignoreCase = true) -> R.string.blocked_model_at_limit
                else -> R.string.blocked_model_not_on_plan
            },
        )
        else -> null
    }
    // Codex has no other provider to move to and doesn't carry on by itself (docs/PROTOCOL.md, "Codex").
    val other = if (s.codex) null else hub.providers.healthyOther(hub.account, s.providerId, now)
    // Another provider's trouble, or a limit that's over and waits for Continue (AutoContinue.kt).
    if (!s.codex && b.takenByLimitCard()) return LimitCard(hub, s, b, other, onMoved)
    GlassCard(Modifier.fillMaxWidth(), accent = if (b.needsYou) HN.amber else HN.accent) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            HText(title, style = Type.heading, color = HN.ink)
            // Whether it carries on by itself, with its switch (AutoContinue.kt); else when it's back.
            val resume = b.resume?.takeIf { !s.codex }
            if (resume != null && (resume.armed || resume.off)) {
                Spacer(Modifier.height(4.dp))
                ResumeLine(hub, s, resume, amber = b.needsYou, resetsAt = b.resetsAt, onMoved = onMoved)
            } else if (line != null) {
                Spacer(Modifier.height(4.dp))
                HText(line, style = Type.monoSmall, color = if (b.needsYou) HN.amber else HN.accentDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            if (b.kind == Blocked.LOGGED_OUT) {
                // Signing in may not help (a refused login can be an ended plan): this conversation
                // can go on elsewhere meanwhile.
                SignInPanel(hub, if (hub.isDemo) stringResource(R.string.your_mac) else hub.mac.name, more = {
                    if (other != null) Pill(continueLabel(other), { act { hub.switchProvider(s.id, other.id) } }, kind = PillKind.Outline, height = 40.dp)
                    else Pill(stringResource(R.string.account_add_provider), opener.providers, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Plus)
                })
                return@Column
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (b.kind == Blocked.MODEL && !s.codex) {
                    val to = otherModel(hub, b)
                    Pill(stringResource(R.string.account_switch_model, to.replaceFirstChar { it.uppercase() }), {
                        act { hub.setModel(s.id, to).map { s.id } }
                    }, kind = PillKind.Primary, height = 40.dp)
                }
                when {
                    other != null -> Pill(continueLabel(other), { act { hub.switchProvider(s.id, other.id) } }, kind = if (b.kind == Blocked.MODEL) PillKind.Outline else PillKind.Primary, height = 40.dp)
                    b.kind != Blocked.MODEL && !s.codex -> Pill(stringResource(R.string.account_add_provider), opener.providers, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Plus)
                }
                // A limit that will lift: the work can wait for it (the "reset" automation, in this folder).
                val cwd = s.cwd
                if (b.kind == Blocked.LIMIT && cwd != null && !hub.isDemo && !s.codex) {
                    Pill(stringResource(R.string.blocked_run_at_reset), { opener.runAtReset(cwd) }, kind = PillKind.Ghost, height = 40.dp, leading = Glyph.Play)
                }
            }
        }
    }
}

/** Stopped because nobody answered for 10 minutes: say why, and offer Continue ([busy]: on its way). */
@Composable
private fun PausedCard(busy: Boolean, codex: Boolean, onContinue: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), accent = HN.amber) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            HText(stringResource(R.string.paused_title), style = Type.heading, color = HN.ink)
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.paused_body, com.prudnikweb.hypernova.ui.components.agentName(codex)), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(12.dp))
            Pill(stringResource(R.string.continue_session), onContinue, Modifier.fillMaxWidth(), kind = PillKind.Primary, leading = Glyph.Play, enabled = !busy)
        }
    }
}

/**
 * A menu Claude Code shows in the session's terminal (the /model picker, a dialog with no card
 * here), as buttons: a tap picks that option there (arrow keys, then Enter). [onPick] gets the
 * option's index, or null to close it (Esc), and whether to use the footer's other key.
 */
@Composable
private fun MenuCard(menu: TermMenu, onPick: (Int?, Boolean) -> Unit, onOpen: () -> Unit) {
    var alt by remember(menu.id) { mutableStateOf(false) }
    // The option on its way to the terminal: the card holds still until the menu changes.
    var sent by remember(menu.id) { mutableStateOf<Int?>(null) }
    var expanded by remember(menu.id) { mutableStateOf(false) }
    LaunchedEffect(sent) {
        if (sent != null) {
            kotlinx.coroutines.delay(4000)
            sent = null
        }
    }
    GlassCard(Modifier.fillMaxWidth(), accent = HN.amber) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Seam(stringResource(R.string.menu_in_terminal), activity = Activity.Alert, line = false)
            if (menu.title != null) {
                Spacer(Modifier.height(10.dp))
                HText(menu.title, style = Type.heading, color = HN.ink)
            }
            if (menu.body.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                HText(
                    menu.body.joinToString("\n"), style = Type.small, color = HN.inkMuted,
                    maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .animateContentSize()
                        .clickable(remember { MutableInteractionSource() }, indication = null) { expanded = !expanded },
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                menu.options.forEachIndexed { i, o ->
                    MenuOption(i + 1, o, sending = sent == i, enabled = sent == null) {
                        sent = i
                        onPick(i, alt)
                    }
                }
            }
            if (menu.more) {
                Spacer(Modifier.height(8.dp))
                HText(stringResource(R.string.menu_more), style = Type.small, color = HN.inkDim)
            }
            val a = menu.alt
            if (a != null) {
                Spacer(Modifier.height(6.dp))
                ToggleRow(a.label.replaceFirstChar { it.uppercase() }, null, alt, { alt = it })
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (menu.cancel) Pill(stringResource(R.string.cancel), { if (sent == null) { sent = -1; onPick(null, false) } }, Modifier.weight(1f), kind = PillKind.Ghost, height = 40.dp)
                Pill(stringResource(if (menu.cancel) R.string.menu_open_terminal else R.string.open_terminal), onOpen, Modifier.weight(1f), kind = PillKind.Outline, height = 40.dp, leading = Glyph.Terminal)
            }
        }
    }
}

/** One option of a terminal menu: its number there, its label and what it means; ✓ on the current one. */
@Composable
private fun MenuOption(n: Int, o: TermMenu.Option, sending: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val current = stringResource(R.string.menu_current)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .graphicsLayer { alpha = if (enabled || sending) 1f else 0.45f }
            .clip(shape)
            .glassChoice(pressed || sending, 12.dp, HN.amber)
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            // TalkBack hears which option is the current one (the ✓).
            .semantics { if (o.current) { selected = true; stateDescription = current } }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(16.dp), contentAlignment = Alignment.CenterStart) {
            if (sending) Planet(Activity.Live) else HText("$n", style = Type.monoSmall, color = HN.amber)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            HText(o.label, style = Type.body, color = HN.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (o.detail != null) HText(o.detail, style = Type.small, color = HN.inkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (o.current) {
            Spacer(Modifier.width(8.dp))
            Icon(Glyph.Check, size = 13.dp, color = HN.accent)
        }
    }
}

/** Claude (or Codex, [codex]) waits for an answer only its terminal shows: take the owner there. */
@Composable
private fun WaitingCard(codex: Boolean, onOpen: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), accent = HN.amber) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            HText(stringResource(R.string.waiting_terminal_title, com.prudnikweb.hypernova.ui.components.agentName(codex)), style = Type.heading, color = HN.ink)
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.waiting_terminal_body), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(12.dp))
            Pill(stringResource(R.string.open_terminal), onOpen, Modifier.fillMaxWidth(), kind = PillKind.Primary, leading = Glyph.Terminal)
        }
    }
}

/** The top of a long session's transcript: "Show earlier", or a loading line while it comes. */
@Composable
private fun ShowEarlier(loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        if (loading) LoadingLine(stringResource(R.string.earlier_loading))
        else Pill(stringResource(R.string.earlier_show), onClick, kind = PillKind.Ghost, height = 40.dp)
    }
}

/** "FILES CHANGED" then file-name chips, scrolling sideways. Tap one to open it. */
@Composable
private fun FilesChanged(paths: List<String>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HText(stringResource(R.string.files_changed).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            items(paths, key = { it }) { p ->
                Box(
                    Modifier
                        .heightIn(min = 36.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onOpen(p) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .glassChoice(false)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Glyph.File, size = 11.dp, color = HN.accentDim)
                        Spacer(Modifier.width(5.dp))
                        HText(p.substringAfterLast('/'), style = Type.monoSmall, color = HN.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                    }
                }
            }
        }
    }
}

/** Transcript padding whose top and bottom are computed during measure (bars + keyboard). */
private class TranscriptPadding(private val top: () -> Dp, private val side: Dp, private val bottom: () -> Dp) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection) = side
    override fun calculateRightPadding(layoutDirection: LayoutDirection) = side
    override fun calculateTopPadding() = top()
    override fun calculateBottomPadding() = bottom()
}

/**
 * Scroll so the end of the last item sits at the bottom of the visible area, gliding at [fps]
 * steps a second. Why not animateScrollBy: while output streams, every new line glided the list
 * for a third of a second on every vsync (120 frames a second on the owner's phone), and each of
 * those frames blurred both edge bars again; a chat with a working session was the hottest screen
 * in the app. Between steps the list doesn't move, so no frame is drawn.
 */
private suspend fun followTail(list: LazyListState, fps: Int, reduced: Boolean) {
    withFrameNanos { } // let the new item be laid out first
    val info = list.layoutInfo
    if (info.totalItemsCount == 0) return
    val last = info.visibleItemsInfo.lastOrNull()
    if (last != null && last.index == info.totalItemsCount - 1) {
        val dist = (last.offset + last.size) - (info.viewportEndOffset - info.afterContentPadding)
        if (dist > 0) list.glideBy(dist.toFloat(), fps, reduced)
    } else {
        list.scrollToItem(info.totalItemsCount - 1, Int.MAX_VALUE)
    }
}

/** A transcript item's arrival: a short fade, and nothing that moves (see the list above). */
private val ItemFadeIn = androidx.compose.animation.core.tween<Float>(Motion.DIALOG)

/** How long the tail's glide takes (ms), as the dialog tween it used to be. */
private const val GLIDE_MS = Motion.DIALOG * 2f

/**
 * Scrolls by [dist] over [GLIDE_MS] with [Motion.Expo], moving the list only every 1/[fps] s. It
 * honours the coroutine's [MotionDurationScale] as animateScrollBy did (system animations off:
 * one jump) and the app's Reduce Motion ([reduced]: the same).
 */
private suspend fun LazyListState.glideBy(dist: Float, fps: Int, reduced: Boolean) {
    val scale = if (reduced) 0f else coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    scroll { glide(dist, fps, scale, { scrollBy(it) }, { withFrameNanos { it } }) }
}

/**
 * The glide itself, apart from the list so it can be tested: [scrollBy] moves it and answers how
 * far it did, [frame] waits for the next vsync and answers its time. A [scale] of 0 jumps at once,
 * otherwise [GLIDE_MS] is stretched by it. Between steps it sleeps rather than waking on every vsync.
 */
internal suspend fun glide(dist: Float, fps: Int, scale: Float, scrollBy: (Float) -> Float, frame: suspend () -> Long) {
    if (scale <= 0f) { scrollBy(dist); return }
    val stepNs = 1_000_000_000L / fps.coerceAtLeast(1)
    val totalMs = GLIDE_MS * scale
    val start = frame()
    var moved = 0f
    var lastStep = start
    while (true) {
        val now = frame()
        val k = ((now - start) / 1e6f / totalMs).coerceIn(0f, 1f)
        // A step is due; a vsync that comes early passes without moving anything (2 ms of slack,
        // so a step never slips a whole vsync late).
        if (k < 1f && now - lastStep < stepNs - 2_000_000L) continue
        lastStep = now
        moved += scrollBy(dist * Motion.Expo.transform(k) - moved)
        if (k >= 1f) break
        // Sleep to just before the next step instead of asking for every frame in between.
        val leftMs = (totalMs - (now - start) / 1e6f).toLong()
        delay(minOf(stepNs / 1_000_000L - 3L, leftMs).coerceAtLeast(0L))
    }
}

/**
 * The prompt box: a paperclip ([onTray], null in the demo), the files waiting to go, the text
 * and Send, the buttons on the last line as the text grows. Send waits until every file is on
 * the Mac. While the box is empty and the Mac offers voice, Send is the mic ([voice]); words
 * still settling after a dictation are drawn dim.
 */
@Composable
private fun Composer(
    text: String,
    hint: String,
    onText: (String) -> Unit,
    attach: AttachState,
    trayOpen: Boolean,
    onTray: (() -> Unit)?,
    voice: VoiceInput,
    modifier: Modifier = Modifier,
) {
    val files = attach.files
    val field = rememberFieldValue(text)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .glassChoice(false, 24.dp)
            .animateContentSize(tween(Motion.DIALOG, easing = Motion.Expo)),
    ) {
        if (files.isNotEmpty()) DraftStrip(files, attach::remove, Modifier.padding(top = 10.dp), onRetry = attach::retry)
        Row(
            Modifier.fillMaxWidth().padding(start = if (onTray != null) 3.dp else 18.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (onTray != null) AttachButton(trayOpen, onTray)
            // As tall as the buttons for one line, so it lines up with them; taller lines grow upward.
            Box(Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) HText(if (voice.listening) stringResource(R.string.voice_waiting) else hint, style = Type.body, color = HN.inkDim, maxLines = 1)
                BasicTextField(
                    value = field.value,
                    onValueChange = { field.value = it; if (it.text != text) onText(it.text) },
                    textStyle = Type.body.copy(color = HN.ink),
                    cursorBrush = SolidColor(HN.accent),
                    // The "/command" (or "!") it starts with, marked as Claude Code will read it; with
                    // files attached it goes as a plain prompt, so nothing is marked.
                    visualTransformation = voiceTail(voice.tail, if (files.isEmpty()) CommandHighlight else VisualTransformation.None),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            val uploading = attach.uploading
            val hasText = text.isNotBlank() || files.isNotEmpty()
            // Send as before (a tap sends; held, it dictates more), or the mic while the box is empty.
            // With a file that failed, a tap says what to do about it rather than nothing.
            MicButton(voice, send = hasText, canSend = hasText && !uploading, uploading = uploading)
        }
    }
}

/**
 * The session's terminal has text in its input box that isn't sent (typed on the Mac, or put
 * back there by an interrupt), so the prompt didn't go. Replace it, or go and look.
 */
@Composable
private fun TerminalTextCard(pending: String, onReplace: () -> Unit, onOpen: () -> Unit, onDismiss: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), accent = HN.amber) {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 14.dp)) {
            // × lets it go once the text was dealt with in the terminal some other way.
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(stringResource(R.string.busy_title), style = Type.heading, color = HN.ink, modifier = Modifier.weight(1f))
                GlyphButton(Glyph.Cross, stringResource(R.string.busy_dismiss), onDismiss)
            }
            Column(Modifier.padding(end = 10.dp)) {
                HText(pending, style = Type.monoSmall, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                HText(stringResource(R.string.busy_body), style = Type.small, color = HN.inkDim)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(stringResource(R.string.busy_replace), onReplace, Modifier.weight(1f), kind = PillKind.Primary)
                    Pill(stringResource(R.string.open_terminal), onOpen, kind = PillKind.Outline, leading = Glyph.Terminal)
                }
            }
        }
    }
}

