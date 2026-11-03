package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Blocked
import com.prudnikweb.hypernova.data.ConnResult
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.HistorySearch
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Link
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.RouteKind
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.onHome
import com.prudnikweb.hypernova.data.linkProblemText
import com.prudnikweb.hypernova.data.rememberFullText
import com.prudnikweb.hypernova.desktop.APP_NAME
import com.prudnikweb.hypernova.desktop.CardOffer
import com.prudnikweb.hypernova.desktop.Group
import com.prudnikweb.hypernova.desktop.Page
import com.prudnikweb.hypernova.desktop.RowLine
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.desktop.durationWords
import com.prudnikweb.hypernova.desktop.groupsOf
import com.prudnikweb.hypernova.desktop.historyModel
import com.prudnikweb.hypernova.desktop.itemIndexOf
import com.prudnikweb.hypernova.desktop.neighbour
import com.prudnikweb.hypernova.desktop.orderOf
import com.prudnikweb.hypernova.desktop.rowHasChip
import com.prudnikweb.hypernova.desktop.rowLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/** How wide the list gets, however wide the window: past this a line of a command is too long to read. */
private val COLUMN = 720.dp
/** Under this, in dp, one pane at a time: the list, or the chosen session with a way back. */
internal val TWO_PANES = 760.dp
private val LIST_MIN = 340.dp
private val LIST_MAX = 460.dp
/** Off-link cards stay, as last heard, and step back. */
internal const val STALE_ALPHA = 0.55f
/** The tag of the list, for the tests. */
const val LIST_TAG = "session-list"

/**
 * The sessions on one server, with its link's state at the top. The list on the left, the chosen session on the right,
 * or in a narrow window one of them at a time. docs/DECISIONS.md, "The desktop client's two panes".
 */
@Composable
fun SessionsScreen(hub: Hub, statuses: List<ServerStatus>, shell: Shell) {
    val server = Core.activeId.orEmpty()
    val link = hub.link
    // As the phone's Home: what's ended is in History (Session.onHome).
    val groups = groupsOf(hub.sessions.filter { it.onHome })
    val onSessions = shell.page == Page.Sessions
    val history = shell.historyOf(server)
    val full = rememberFullText(hub, if (onSessions) "" else history.query)
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    val zone = remember { ZoneId.systemDefault() }
    val past = remember(hub.sessions, history.query, history.project, full.hits, HistorySearch.day(now, zone)) {
        historyModel(hub.sessions, history.query, history.project, full.hits, now, zone)
    }
    val order = if (onSessions) orderOf(groups) else past.order
    // A rewind's copy goes on as the session it replaced.
    val chosenId = (if (onSessions) shell.chosen(server) else history.chosen)?.let { hub.replacedBy(it) ?: it }
    val chosen = chosenId?.let(hub::session)
    val list = if (onSessions) shell.listOf(server) else history.list
    val scope = rememberCoroutineScope()
    val keys = remember { FocusRequester() }
    var hasKeys by remember { mutableStateOf(false) }
    // In one pane the arrows can't choose as they go, or the list would be gone at the first press: they move this, and Enter chooses.
    var cursor by remember(server, shell.page) { mutableStateOf<String?>(null) }
    fun choose(id: String?) {
        if (id != null) cursor = id
        if (onSessions) shell.choose(server, id) else history.chosen = id
    }
    /** Only for the keys: a row the mouse reached is where it is. */
    fun showRow(id: String) {
        val at = (if (onSessions) itemIndexOf(groups, id) else past.indexOf(id)) ?: return
        val seen = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == at }
        val fits = seen != null && seen.offset >= list.layoutInfo.viewportStartOffset && seen.offset + seen.size <= list.layoutInfo.viewportEndOffset
        if (!fits) scope.launch { list.scrollToItem(at) }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPanes = maxWidth >= TWO_PANES
        val listWidth = (maxWidth * 0.42f).coerceIn(LIST_MIN, LIST_MAX)
        /** An arrow key: the next row chosen, or marked in one pane. False if there's nowhere to go. */
        fun walk(step: Int): Boolean {
            if (!twoPanes && chosenId != null) return false
            val next = neighbour(order, if (twoPanes) chosenId else cursor, step) ?: return false
            if (twoPanes) choose(next) else cursor = next
            showRow(next)
            return true
        }
        Column(
            Modifier.fillMaxSize()
                .focusRequester(keys)
                .onFocusChanged { hasKeys = it.hasFocus }
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) return@onKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> walk(1)
                        Key.DirectionUp -> walk(-1)
                        Key.Enter, Key.NumPadEnter -> {
                            if (twoPanes || chosenId != null) return@onKeyEvent false
                            val at = cursor?.takeIf { it in order } ?: return@onKeyEvent false
                            choose(at)
                            true
                        }
                        Key.Escape -> {
                            if (chosenId == null) return@onKeyEvent false
                            choose(null)
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
        ) {
            TopBar(hub, statuses, shell)
            // Over the list, not in it: scrolled down, a card that came in above would never be seen.
            if (link.state == LinkState.Unpaired || link.state == LinkState.Offline) {
                Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), contentAlignment = Alignment.TopCenter) {
                    val m = Modifier.widthIn(max = COLUMN).fillMaxWidth()
                    if (link.state == LinkState.Unpaired) UnpairedCard(hub, shell, m) else OfflineCard(hub, hasSessions = order.isNotEmpty(), m)
                }
            }
            val stale = link.state != LinkState.Connected && link.state != LinkState.Connecting
            val onRow = { id: String ->
                choose(id)
                runCatching { keys.requestFocus() }
                Unit
            }
            val sessionList = @Composable { m: Modifier ->
                if (onSessions) SessionList(hub, groups, list, stale, chosenId.takeIf { twoPanes }, cursor.takeIf { !twoPanes }, m, onRow)
                else HistoryList(hub, history, past, full, stale, chosenId.takeIf { twoPanes }, cursor.takeIf { !twoPanes }, now, m, ::walk, onRow)
            }
            // Nothing to choose from, nothing to say beside it.
            val nothing = (if (onSessions) groups.isEmpty() else past.endedCount == 0) && chosenId == null
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                when {
                    !twoPanes && chosenId != null -> SessionPane(hub, chosenId, chosen, shell, stale, onBack = { choose(null); runCatching { keys.requestFocus() } }, Modifier.fillMaxSize().widthIn(max = PANE_MAX))
                    !twoPanes || nothing -> sessionList(Modifier.fillMaxSize())
                    else -> Row(Modifier.widthIn(max = LIST_MAX + PANE_MAX).fillMaxHeight()) {
                        sessionList(Modifier.width(listWidth).fillMaxHeight())
                        SessionPane(hub, chosenId, chosen, shell, stale, onBack = null, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
        // Keys from the first moment, with nothing to click first. And whatever had the focus can go with what's on screen, a pill that was just pressed, say: then they come back here.
        LaunchedEffect(hasKeys) { if (!hasKeys) runCatching { keys.requestFocus() } }
    }
}

/** The groups and their rows. It scrolls by itself: [state] is kept for this server, and nothing here moves it. */
@Composable
private fun SessionList(hub: Hub, groups: List<Pair<Group, List<Session>>>, state: LazyListState, stale: Boolean, chosen: String?, cursor: String?, modifier: Modifier = Modifier, onChoose: (String) -> Unit) {
    Box(modifier.testTag(LIST_TAG)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (groups.isEmpty() && hub.link.state == LinkState.Connected) {
                item(key = "none") { NoSessions(Modifier.widthIn(max = COLUMN).fillMaxWidth()) }
            }
            for ((group, members) in groups) {
                item(key = group.name) { GroupSeam(group, members.size, Modifier.widthIn(max = COLUMN).fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) }
                items(members, key = { it.id }) { s ->
                    SessionRow(
                        hub, s, chosen = s.id == chosen, marked = s.id == cursor, onChoose = { onChoose(s.id) },
                        Modifier.widthIn(max = COLUMN).fillMaxWidth().graphicsLayer { alpha = if (stale) STALE_ALPHA else 1f },
                    )
                }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(state),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
            style = LocalScrollbarStyle.current.copy(unhoverColor = HN.ink.copy(alpha = 0.12f), hoverColor = HN.ink.copy(alpha = 0.3f)),
        )
    }
}

/** The logo, the name, then where the link stands in a chip that's always there. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBar(hub: Hub, statuses: List<ServerStatus>, shell: Shell) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            NovaMark()
            HText(APP_NAME, style = Type.wordmark, maxLines = 1)
            ConnectionChip(hub)
            Spacer(Modifier.weight(1f))
            for (page in Page.entries) {
                val on = shell.page == page
                Pill(
                    (if (page == Page.Sessions) Ui.sessions else Ui.history).text(), { shell.page = page },
                    Modifier.semantics { selected = on }, kind = if (on) PillKind.Primary else PillKind.Ghost, height = 32.dp,
                )
            }
            Pill(Ui.desk_settings.text(), { shell.settings = true }, kind = PillKind.Ghost, height = 32.dp)
            Pill(Ui.desk_pair_another.text(), { shell.adding = true }, kind = PillKind.Ghost, height = 32.dp)
        }
        if (statuses.size > 1) {
            Spacer(Modifier.height(8.dp))
            Switcher(statuses)
        }
    }
}

/** One pill per paired server: a tap puts it on screen. [Core] already watches each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Switcher(statuses: List<ServerStatus>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // In the order they were paired, so a pill doesn't jump when it's picked.
        for (s in statuses.sortedBy { it.name.lowercase() }) {
            val word = if (s.needsYou > 0) "${s.name}  ${s.needsYou}" else s.name
            Pill(
                word, { Core.switchTo(s.id) },
                kind = if (s.active) PillKind.Primary else if (s.needsYou > 0) PillKind.Outline else PillKind.Ghost,
                height = 32.dp,
            )
        }
    }
}

/** The word, the mark and the colour for a link. */
private data class LinkWords(val word: String, val mark: LinkMark, val color: Color)

private fun linkWords(link: Link): LinkWords = when (link.state) {
    LinkState.Connected -> LinkWords(Ui.desk_link_linked.text(), LinkMark.Linked, HN.accent)
    LinkState.Connecting -> LinkWords(Ui.desk_link_connecting.text(), LinkMark.Looking, HN.inkMuted)
    LinkState.Reconnecting -> LinkWords(Ui.desk_link_reconnecting.text(), LinkMark.Looking, HN.amber)
    LinkState.Offline -> LinkWords(Ui.desk_link_offline.text(), LinkMark.Offline, HN.danger)
    LinkState.Unpaired -> LinkWords(Ui.desk_link_unpaired.text(), LinkMark.Unpaired, HN.danger)
}

/** The server's name and its link: `◆ z2  LINKED  LOCAL`. */
@Composable
private fun ConnectionChip(hub: Hub) {
    val link = hub.link
    val words = linkWords(link)
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinkGlyph(words.mark, words.color)
        Spacer(Modifier.width(6.dp))
        HText(hub.mac.name, style = Type.small, color = HN.inkMuted, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        HText(words.word.uppercase(), style = Type.label, color = words.color, maxLines = 1)
        if (link.state == LinkState.Connected) {
            Spacer(Modifier.width(8.dp))
            val route = if (hub.mac.via == Mac.VIA_REMOTE) Ui.connlog_route_base else Ui.connlog_route_local
            HText(route.text().uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        }
    }
}

/** Offline, and why: the server's silence as the link says it, when it tries again, what it tried. */
@Composable
private fun OfflineCard(hub: Hub, hasSessions: Boolean, modifier: Modifier = Modifier) {
    val link = hub.link
    val now by produceState(System.currentTimeMillis(), link) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    GlassCard(modifier, edge = HN.danger) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            HText(Ui.cant_reach.format(hub.mac.name), style = Type.heading)
            Spacer(Modifier.height(4.dp))
            HText(linkProblemText(Core.platform.strings, link), style = Type.small, color = HN.inkMuted)
            link.tried?.let { tried ->
                val shown = tried.tries.groupBy { it.route }.mapNotNull { (_, list) -> list.lastOrNull() }.sortedBy { it.route.ordinal }
                if (shown.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        for (t in shown) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HText((if (t.route == RouteKind.Local) Ui.connlog_route_local else Ui.connlog_route_base).text().uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                                resultWord(t.result)?.let {
                                    Spacer(Modifier.width(6.dp))
                                    HText(it.uppercase(), style = Type.label, color = if (t.result == ConnResult.Skipped) HN.inkDim else HN.danger, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val retry = when {
                    link.retrying -> Ui.retrying_now.text()
                    link.retryAt != null && (link.retryAt ?: 0L) - now >= 1_000 -> Ui.retrying_in.format(durationWords((link.retryAt ?: 0L) - now))
                    link.retryAt != null -> Ui.retrying_now.text()
                    else -> null
                }
                HText(retry.orEmpty(), Modifier.weight(1f), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                Pill(Ui.retry_now.text(), hub::retry, kind = PillKind.Outline, height = 36.dp)
            }
            if (hasSessions) {
                Spacer(Modifier.height(8.dp))
                HText(Ui.desk_stale.text(), style = Type.small, color = HN.inkDim)
            }
        }
    }
}

private fun resultWord(r: ConnResult): String? = when (r) {
    ConnResult.Ok -> Ui.connlog_result_ok.text()
    ConnResult.Refused -> Ui.connlog_result_refused.text()
    ConnResult.Timeout -> Ui.connlog_result_timeout.text()
    ConnResult.Unreachable -> Ui.connlog_result_unreachable.text()
    ConnResult.NoName -> Ui.connlog_result_no_name.text()
    ConnResult.PinWrong -> Ui.connlog_result_pin_wrong.text()
    ConnResult.PinExpired -> Ui.connlog_result_pin_expired.text()
    ConnResult.Tls -> Ui.connlog_result_tls.text()
    ConnResult.Http -> Ui.connlog_result_http.text()
    ConnResult.Closed -> Ui.connlog_result_closed.text()
    ConnResult.Failed -> Ui.connlog_result_failed.text()
    ConnResult.Skipped -> Ui.connlog_result_skipped.text()
    ConnResult.Info -> null
}

@Composable
private fun UnpairedCard(hub: Hub, shell: Shell, modifier: Modifier = Modifier) {
    GlassCard(modifier, edge = HN.danger) {
        Column(Modifier.padding(16.dp)) {
            HText(Ui.desk_unpaired_title.format(hub.mac.name), style = Type.heading)
            Spacer(Modifier.height(4.dp))
            HText(Ui.desk_unpaired_body.text(), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(10.dp))
            Pill(Ui.desk_pair_again.text(), shell::pairAgain, kind = PillKind.Primary, height = 36.dp)
        }
    }
}

@Composable
private fun NoSessions(modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            HText(Ui.desk_no_sessions.text(), style = Type.heading)
            Spacer(Modifier.height(4.dp))
            HText(Ui.desk_no_sessions_body.text(), style = Type.small, color = HN.inkMuted)
        }
    }
}

@Composable
private fun GroupSeam(group: Group, count: Int, modifier: Modifier = Modifier) {
    val (label, color, mark) = when (group) {
        Group.NeedsInput -> Triple(Ui.group_needs.text(), HN.amber, MarkKind.NeedsYou)
        Group.Working -> Triple(Ui.group_working.text(), HN.accent, MarkKind.Working)
        Group.Completed -> Triple(Ui.group_done.text(), HN.inkDim, MarkKind.Idle)
    }
    Seam(label, mark, modifier.semantics { heading() }, color = if (group == Group.Completed) HN.inkMuted else color) {
        CountMark(count.toString(), color)
    }
}

/**
 * A row of the list. The same height chosen or not, so choosing one moves nothing. Tab doesn't stop at each: the
 * arrow keys walk them (SessionsScreen), and Tab goes on to what's in the pane.
 */
@Composable
private fun SessionRow(hub: Hub, s: Session, chosen: Boolean, marked: Boolean, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    val ask = hub.approval(s.id)
    val offer = remember(ask, s) { CardOffer.of(ask, s) }
    val line = rowLine(offer, s)
    val block = s.activeBlock(hub.account, hub.providers, System.currentTimeMillis())
    val needs = s.state == SessionState.NeedsYou
    RowCard(chosen, marked, onChoose, modifier, edge = if (needs) HN.amber else null) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Seam(
                s.project, if (needs) MarkKind.NeedsYou else if (s.busy) MarkKind.Working else MarkKind.Idle,
                color = if (needs) HN.amber else HN.inkMuted,
            ) { HText(s.hex, style = Type.monoSmall, color = HN.inkDim, maxLines = 1) }
            Spacer(Modifier.height(6.dp))
            HText(s.name, style = Type.heading, maxLines = 2)
            if (line.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                val color = when (line.tone) {
                    RowLine.Tone.Ask -> HN.amber
                    RowLine.Tone.Danger -> HN.danger
                    RowLine.Tone.Quiet -> HN.inkMuted
                }
                HText(line.text, style = Type.monoSmall, color = color, maxLines = 1)
            }
            if (rowHasChip(s, block)) {
                Spacer(Modifier.height(8.dp))
                StateChip(s, block)
            }
        }
    }
}

/** A row of a list, this one or History's: its ring and tint draw over what's there, so choosing one moves nothing. */
@Composable
internal fun RowCard(chosen: Boolean, marked: Boolean, onChoose: () -> Unit, modifier: Modifier = Modifier, edge: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val ring = when {
        marked -> HN.ink
        chosen -> HN.accent
        hovered -> HN.ink.copy(alpha = 0.3f)
        else -> Color.Transparent
    }
    GlassCard(
        modifier
            .border(1.5.dp, ring, shape)
            .then(if (chosen) Modifier.background(HN.accent.copy(alpha = 0.08f), shape) else Modifier)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(hover)
            .focusProperties { canFocus = false }
            .clickable(hover, indication = null, role = Role.Button, onClick = onChoose)
            .semantics { selected = chosen },
        edge = edge,
        content = content,
    )
}

/** The state chip, by the phone's rules (HomeScreen's PhaseChip). */
@Composable
internal fun StateChip(s: Session, block: Blocked?) {
    if (block != null) {
        when (block.kind) {
            Blocked.LIMIT, Blocked.MODEL -> StatusChip(Ui.state_paused.text(), HN.inkMuted)
            Blocked.LOGGED_OUT -> StatusChip(Ui.state_signed_out.text(), HN.amber)
            Blocked.NO_SUBSCRIPTION -> StatusChip(Ui.state_no_plan.text(), HN.amber)
            Blocked.ON_HOLD -> StatusChip(Ui.state_on_hold.text(), HN.amber)
            Blocked.NO_CREDIT -> StatusChip(Ui.state_no_credit.text(), HN.amber)
            else -> StatusChip(Ui.state_paused.text(), if (block.resume?.armed == true) HN.inkMuted else HN.amber)
        }
        return
    }
    when (s.phase) {
        Phase.Starting -> StatusChip(Ui.state_starting.text(), HN.accent, live = true)
        Phase.Error -> StatusChip(Ui.state_error.text(), HN.danger)
        Phase.Ended -> if (s.cutByRestart) StatusChip(Ui.state_mac_restarted.text(), HN.inkMuted) else StatusChip(Ui.state_ended.text(), HN.inkDim, hollow = true)
        else -> when (s.state) {
            SessionState.Working -> StatusChip(Ui.state_working.text(), HN.accent, live = true)
            SessionState.NeedsYou -> StatusChip(Ui.state_needs_you.text(), HN.amber, filled = true)
            SessionState.Idle -> if (s.busy) StatusChip(Ui.state_working.text(), HN.accent, live = true) else StatusChip(Ui.state_idle.text(), HN.inkDim)
        }
    }
}

