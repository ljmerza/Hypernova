package com.prudnikweb.hypernova.ui.session

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentStarting
import com.prudnikweb.hypernova.data.AgentState
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.WEvent
import com.prudnikweb.hypernova.data.agentState
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.clock
import com.prudnikweb.hypernova.ui.components.kindLabel
import com.prudnikweb.hypernova.ui.components.tokensFigure
import com.prudnikweb.hypernova.ui.components.FitRow
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.NoPart
import com.prudnikweb.hypernova.ui.components.Stamp
import com.prudnikweb.hypernova.ui.components.fitPart
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** How often an open agent's transcript is fetched again while it runs. */
private const val POLL_MS = 1500L

/**
 * One agent a session's main agent started, read-only: what it was asked (folded), everything it
 * did, and its report last, drawn as a session's chat is. Its transcript is the Mac's copy: while
 * it runs, what changed in it is asked for every 1.5 s (usually nothing, and the screen stays as it
 * is), and the whole of it once more when it ends. There's no composer: an agent answers the agent
 * that started it, not the owner; its approvals can be answered here.
 */
@Composable
fun AgentScreen(hub: Hub, sessionId: String, agentId: String, onBack: () -> Unit) {
    val session = hub.session(sessionId)
    // Opened straight here (the app restored this screen): the session's agents load with it.
    LaunchedEffect(sessionId) { hub.opened(sessionId) }
    val agent = hub.agents(sessionId).agents[agentId]
    if (session == null) {
        // The Mac not linked yet: say so, with Back, until it has the sessions (SessionScreen.kt).
        if (macAway(hub)) WaitingForMac(hub, onBack) else LaunchedEffect(sessionId) { onBack() }
        return
    }
    var events by remember(sessionId, agentId) { mutableStateOf<List<WEvent>?>(null) }
    var error by remember(sessionId, agentId) { mutableStateOf<String?>(null) }
    // Just started: the Mac has no transcript of it yet (AgentStarting).
    var starting by remember(sessionId, agentId) { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    val running by rememberUpdatedState(agent?.running == true)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(sessionId, agentId, attempt) {
        while (true) {
            // Only while the app is on screen: left open behind another app, this screen would
            // keep asking the Mac (or trying to reach one that's away) every 1.5 s.
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            hub.agentEvents(sessionId, agentId)
                .onSuccess { events = it; error = null; starting = false }
                .onFailure {
                    if (it is AgentStarting) {
                        starting = true
                        error = null
                    } else {
                        starting = false
                        if (events == null) error = it.message
                        // The Mac is away: ask again once the hub has it back, not every 1.5 s.
                        snapshotFlow { hub.link.state == LinkState.Connected }.first { it }
                    }
                }
            // Ended (or not known yet): rest until it runs again (a message woke it). One starting
            // is asked again in a moment, its file comes as it starts.
            if (!running && !starting) snapshotFlow { running }.first { it } else delay(POLL_MS)
        }
    }
    val list = events
    val items = if (list == null) emptyList() else hub.agentTranscript(sessionId, agentId, list)
    // Fork: its asks still waiting are one stack at the end, as in the session's chat (AskStack.kt).
    val (chat, asks) = Asks.split(items)
    val state = agent?.let { agentState(it.status) }
    val asking = items.any { it is Item.Approval && it.resolved == null }
    val res = LocalContext.current.resources
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listState = rememberLazyListState()
    // Its report, or what it does now, is at the end: start there, and keep up while it runs.
    var follow by rememberSaveable(agentId) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) follow = !listState.canScrollForward
        }
    }
    LaunchedEffect(items.size, items.lastOrNull()?.key) {
        // The list's last row: the stack, when there is one.
        if (follow && items.isNotEmpty()) listState.scrollToItem(chat.size - if (asks.isEmpty()) 1 else 0, Int.MAX_VALUE)
    }
    val ms by produceState(0L, agent?.startedAt, agent?.endedAt, running) {
        val start = agent?.startedAt ?: return@produceState
        value = ((agent?.endedAt ?: System.currentTimeMillis()) - start).coerceAtLeast(0)
        while (running) {
            delay(1000)
            value = (System.currentTimeMillis() - start).coerceAtLeast(0)
        }
    }

    ScreenScaffold(
        sourceKey = "agent:$sessionId:$agentId",
        title = agent?.label ?: stringResource(R.string.agent_label),
        // Its kind in tracked caps, then the session it works for in dim mono ("EXPLORE AGENT  agent
        // cards"), as a session's header has its project and id: no "agent · explore · …".
        subtitleContent = {
            val kind = stringResource(R.string.agent_kind, kindLabel(agent?.type ?: "agent"))
            val said = stringResource(R.string.agent_subtitle_said, kind, session.name)
            Row(Modifier.clearAndSetSemantics { contentDescription = said }.heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                HText(kind.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                HText(session.name, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        onBack = onBack,
        below = {
            Seam(
                line = false,
                label = when {
                    asking && running -> stringResource(R.string.state_needs_you)
                    state == AgentState.Running -> stringResource(R.string.state_working)
                    state == AgentState.Done -> stringResource(R.string.agent_state_done)
                    state == AgentState.Failed -> stringResource(R.string.agent_state_failed)
                    state == AgentState.Stopped -> stringResource(R.string.agent_state_stopped)
                    else -> stringResource(R.string.agent_label)
                },
                after = agent?.let { clock(res, ms) },
                // Its tokens after a token and its tool calls after a wrench, as on its card.
                trailContent = agent?.takeIf { (it.tokens ?: 0L) > 0L || (it.toolUses ?: 0) > 0 }?.let { a ->
                    {
                        val said = listOfNotNull(
                            a.tokens?.let { res.getString(R.string.stats_day_tokens, tokensFigure(it)) },
                            a.toolUses?.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.agent_tool_calls, it, it) },
                        ).joinToString(", ")
                        FitRow(
                            parts = listOf(
                                a.tokens?.let { t -> fitPart({ Stamp(Glyph.Token, tokensFigure(t)) }) } ?: NoPart,
                                a.toolUses?.takeIf { it > 0 }?.let { n -> fitPart({ Stamp(Glyph.Tool, n.toString()) }) } ?: NoPart,
                            ),
                            giveWay = intArrayOf(1),
                            gap = 10.dp,
                            modifier = Modifier.clearAndSetSemantics { contentDescription = said },
                        )
                    }
                },
                activity = when {
                    asking && running -> Activity.Alert
                    running -> Activity.Live
                    else -> Activity.Rest
                },
                color = when {
                    asking && running -> HN.amber
                    running -> HN.accentDim
                    state == AgentState.Failed -> HN.danger
                    else -> HN.inkMuted
                },
                modifier = Modifier.padding(start = 12.dp),
            )
        },
    ) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 8.dp, bottom = nav + 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (items.isEmpty()) {
                item(key = "empty") {
                    val err = error
                    when (agentEmpty(list != null, err, starting)) {
                        AgentEmpty.Loading -> LoadingLine(stringResource(R.string.loading_conversation), Modifier.padding(top = 8.dp))
                        AgentEmpty.Starting -> LoadingLine(stringResource(R.string.agent_starting), Modifier.padding(top = 8.dp))
                        AgentEmpty.Error -> Column(Modifier.padding(top = 8.dp)) {
                            HText(err.orEmpty(), style = Type.body, color = HN.danger)
                            Spacer(Modifier.height(12.dp))
                            Pill(stringResource(R.string.try_again), { error = null; attempt++ }, kind = PillKind.Outline, height = 44.dp)
                        }
                        AgentEmpty.Nothing -> HText(stringResource(R.string.agent_empty), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            itemsIndexed(chat, key = { _, it -> it.key }) { i, item ->
                val m = Modifier.fillMaxWidth().animateItem()
                // What it was asked comes first: folded, as the task it is rather than a message.
                if (i == 0 && item is Item.User) TaskBubble(item.key, item.text, m)
                else TranscriptItem(hub, sessionId, item, m)
            }
            if (asks.isNotEmpty()) item(key = "asks") {
                AskStack(asks, Modifier.fillMaxWidth().animateItem()) { ask, m -> TranscriptItem(hub, sessionId, ask, m.fillMaxWidth()) }
            }
        }
    }
}

/** What an agent's screen shows while it has no transcript rows. */
internal enum class AgentEmpty { Loading, Starting, Error, Nothing }

/**
 * [loaded]: an answer came. Starting (the Mac has it as just started, no file yet) wins: it's asked
 * again in a moment, not an error. Then an error, with Try again; then loading, or "Nothing yet."
 */
internal fun agentEmpty(loaded: Boolean, error: String?, starting: Boolean): AgentEmpty = when {
    starting && !loaded -> AgentEmpty.Starting
    error != null -> AgentEmpty.Error
    !loaded -> AgentEmpty.Loading
    else -> AgentEmpty.Nothing
}

/** The task an agent was given: TASK and its first lines; a tap unfolds the whole of it. */
@Composable
private fun TaskBubble(key: String, text: String, modifier: Modifier) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val long = remember(text) { text.length > 240 || text.lines().size > 4 }
    GlassCard(
        modifier,
        onClick = if (long) ({ open = !open }) else null,
        onClickLabel = stringResource(if (open) R.string.collapse else R.string.show_all),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).animateContentSize(tween(Motion.REVEAL, easing = Motion.Expo))) {
            HText(stringResource(R.string.agent_task).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            HText(text, style = Type.body, color = HN.inkMuted, maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
            if (long) {
                Spacer(Modifier.height(6.dp))
                HText(stringResource(if (open) R.string.collapse else R.string.show_all), style = Type.monoSmall, color = HN.inkDim)
            }
        }
    }
}
