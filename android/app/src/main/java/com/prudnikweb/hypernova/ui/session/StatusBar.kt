package com.prudnikweb.hypernova.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentState
import com.prudnikweb.hypernova.data.AgentView
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.WorkflowView
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.ui.fx.LocalAmbient
import com.prudnikweb.hypernova.ui.fx.Pace
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

/** One pass of the moving highlight across the line (seconds), and its width as a share of the line. */
private const val SWEEP_S = 1.7f
private const val SWEEP_WIDTH = 0.30f
/** One breath of the to-do the agent is on (seconds). */
private const val BREATH_S = 1.6f

/** What the screen needs to know about the bar: the session header hides its own words while it shows. */
@androidx.compose.runtime.Stable
internal class StatusBarState(
    /** What the bar says now, or null when it isn't showing (no turn, or an error bar that timed out). */
    val model: BarModel?,
    val todos: List<Todo>,
    val open: Boolean,
    val onToggle: () -> Unit,
    /** The Mac isn't linked: the link's word, said instead of what Claude was last doing; no Stop, no clock. */
    val away: String? = null,
    /** The session's running workflow: opened, the bar lists its agents (and the to-dos under them). */
    val workflow: WorkflowView? = null,
    /** The agents at work outside a running workflow: opened, the bar lists the main agent and them. */
    val agents: List<BarAgent> = emptyList(),
) {
    val visible: Boolean get() = model != null
}

/**
 * The sessions whose bar is opened, while their chat is on screen: leaving it closes the bar (owner,
 * 2026-11-01). Not saved state, which the screens' state holder (App.kt) would hand back on the way
 * back, and a restart would too. In memory, as a configuration change recreates the activity and
 * that's not leaving; a process restart starts with none.
 */
private val openBars = mutableStateSetOf<String>()

/**
 * The bar's state for a session. The to-do list is asked of the Mac here. The red error bar
 * folds by itself after [ERROR_SHOW_MS] so a stopped session doesn't wear it forever; a tap on it
 * keeps it until tapped again.
 */
@Composable
internal fun rememberStatusBar(hub: Hub, session: Session, items: List<Item>, sending: Boolean, away: String? = null, todosOf: suspend (String) -> Result<List<Todo>> = ChatRepo::todos): StatusBarState {
    val todos = rememberTodos(session.id, items, todosOf)
    val pending = remember(items) { items.lastOrNull { it is Item.Approval && it.resolved == null } as? Item.Approval }
    val reads = remember(items) { trailingReads(items) }
    val workflow = remember(items) { runningWorkflow(items) }
    // The agents at work beside the main one, from the board (a new board as they change).
    val board = hub.agents(session.id)
    val asking = remember(items) { askingAgents(items) }
    val agents = remember(board, asking) { barAgents(board, asking) }
    // Stop sends Esc to a shared terminal or interrupts a session the Mac runs; a terminal session
    // the Mac only watches can't be stopped from here (the Mac answers 409).
    val stoppable = session.term != null || session.origin == "hypernova"
    val raw = statusBar(
        BarInput(session.phase, session.state, session.activity, sending, session.agentsRunning, pending, todos, reads, stoppable = stoppable, workflow = workflow, agents = agents),
    )
    val isError = raw?.tone == BarTone.Error
    // Kept out here so the list stays open (or folded) across short gaps between two steps.
    val open = session.id in openBars
    val activity = LocalActivity.current
    DisposableEffect(session.id) {
        val id = session.id
        onDispose { if (activity?.isChangingConfigurations != true) openBars.remove(id) }
    }
    var kept by remember(session.id) { mutableStateOf(false) }
    var expired by remember(session.id) { mutableStateOf(false) }
    LaunchedEffect(isError, kept) {
        if (!isError) { expired = false; kept = false; return@LaunchedEffect }
        if (kept) { expired = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(ERROR_SHOW_MS)
        expired = true
    }
    // While an approval waits the bar stays, amber, saying what it waits on (owner, 2026-10-18).
    val model = if (raw != null && isError && errorFolded(expired, kept)) null else raw
    return StatusBarState(model, todos, open, {
        if (!openBars.remove(session.id)) openBars.add(session.id)
        if (isError) kept = !kept
    }, away = away, workflow = workflow, agents = agents)
}

/**
 * The agent status bar above the prompt box, only while a turn runs (owner, 2026-10-17): one line
 * of what Claude is doing, the to-dos as 3/7, the turn's time and Stop, with a thin progress line
 * along the bottom. It is the to-do panel folded: tap it to open the checklist, tap again to fold
 * it. When the turn ends it folds away; it keeps its last look while it does. While a workflow
 * runs it says the workflow's name and counts its agents instead, with its own time and tokens
 * as Claude Code's footer writes them ("review-changes 1/7 · 19m08s · ↓ 181.1k"), and opens to
 * them, the to-dos under them. With no workflow, while the main agent's agents work, it says
 * "main + 3 agents" and opens to the main agent and a row for each, as Claude Code's footer lists
 * them (owner, 2026-10-30). The to-dos keep a labelled tally of their own, never read as agents.
 */
@Composable
internal fun ChatStatusBar(hub: Hub, session: Session, bar: StatusBarState, modifier: Modifier = Modifier) {
    val model = bar.model
    val last = remember { arrayOfNulls<BarModel>(1) }
    if (model != null) last[0] = model
    // The turn's clock: from the moment the bar appears, through a stop for your answer.
    var since by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val alive = model != null
    LaunchedEffect(alive) { if (alive) since = System.currentTimeMillis() }
    // Stop pressed: "Stopping…" and no second Esc (a second one opens Claude Code's rewind menu
    // in a shared terminal) until the turn ends, or 3 seconds pass.
    var stopping by remember(session.id) { mutableStateOf(false) }
    val turnOn = model != null && model.tone == BarTone.Working
    LaunchedEffect(stopping, turnOn) {
        if (!stopping) return@LaunchedEffect
        if (!turnOn) { stopping = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(STOPPING_MS)
        stopping = false
    }
    AnimatedVisibility(
        model != null,
        modifier,
        enter = fadeIn(tween(Motion.DIALOG)) + expandVertically(tween(Motion.REVEAL, easing = Motion.Expo)),
        exit = fadeOut(tween(Motion.DIALOG)) + shrinkVertically(tween(Motion.DIALOG, easing = Motion.Expo)),
    ) {
        val shown = model ?: last[0]
        if (shown != null) {
            Column {
                val opener = com.prudnikweb.hypernova.ui.LocalOpener.current
                StatusCard(shown, bar.todos, bar.workflow, bar.agents, bar.open, bar.onToggle, since, bar.away, stopping, codex = session.codex, onOpenAgent = { id -> opener.agent(session.id, id) }, modifier = Modifier.weight(1f, fill = false)) {
                    if (!stopping) {
                        stopping = true
                        hub.interrupt(session.id)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun StatusCard(model: BarModel, todos: List<Todo>, workflow: WorkflowView?, barAgents: List<BarAgent>, open: Boolean, onToggle: () -> Unit, since: Long, away: String?, stopping: Boolean, codex: Boolean, onOpenAgent: (String) -> Unit, modifier: Modifier = Modifier, onStop: () -> Unit) {
    // Ticks once a second while the card is on screen and the Mac is linked; gone with it.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(away == null) {
        while (away == null) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val tone by animateColorAsState(if (away != null) HN.inkDim else toneColor(model.tone), tween(300), label = "statusTone")
    val res = LocalContext.current.resources
    val said = away ?: sayText(model.say)
    val turnMs = now - since
    // A running workflow's own time and tokens, as Claude Code's footer writes them (19m08s,
    // ↓ 181.1k); otherwise the turn's clock.
    val run = workflow?.takeIf { model.hasWorkflow }
    val runMs = run?.let { (now - it.startedAt).coerceAtLeast(0) }
    val runTokens = run?.let { workflowTokens(it) }
    val time = if (runMs != null) {
        listOfNotNull(ccDuration(runMs), runTokens?.let { stringResource(R.string.status_tokens_short, ccTokens(it)) }).joinToString(" · ")
    } else clockText(turnMs)
    val timeSaid = if (runMs != null) {
        listOfNotNull(stringResource(R.string.status_run_said, durationSaid(res, runMs)), runTokens?.let { tokensSaid(res, it) }).joinToString(", ")
    } else clockText(turnMs)
    // The workflow's agents, phase by phase as its card has them, then any of no phase.
    val agents = remember(workflow) { workflow?.let { w -> w.phases.flatMap { it.agents } + w.loose }.orEmpty() }
    val listsAgents = model.hasWorkflow && agents.isNotEmpty()
    // No workflow: the main agent and the agents it runs, a row each.
    val listsMain = model.hasAgents
    val expandable = model.hasTodos || listsAgents || listsMain
    val saidAloud = if (away == null && model.say.kind == SayKind.MainAndAgents) pluralStringResource(R.plurals.status_main_agents_said, model.say.n, model.say.n) else said
    val spoken = when {
        model.hasWorkflow && away == null && model.say.kind == SayKind.Workflow ->
            stringResource(R.string.status_desc_workflow_only, model.workflow!!, model.done, model.total, timeSaid)
        model.hasWorkflow -> stringResource(R.string.status_desc_workflow, said, model.workflow!!, model.done, model.total, timeSaid)
        model.hasTodos -> stringResource(R.string.status_desc_todos, saidAloud, model.done, model.total, timeSaid)
        else -> stringResource(R.string.status_desc_plain, saidAloud, timeSaid)
    }
    val stateWord = stringResource(if (open) R.string.status_open else R.string.status_folded)
    val label = stringResource(
        when {
            listsAgents -> if (open) R.string.status_fold_agents else R.string.status_expand_agents
            listsMain -> if (open) R.string.status_fold_main else R.string.status_expand_main
            else -> if (open) R.string.status_fold else R.string.status_expand
        },
    )
    GlassCard(modifier.fillMaxWidth().animateContentSize(tween(Motion.DIALOG, easing = Motion.Expo))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    // 48dp, the touch target for opening the list.
                    .heightIn(min = 48.dp)
                    .then(
                        if (expandable || model.tone == BarTone.Error) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = label, onClick = onToggle)
                        else Modifier,
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = spoken
                        if (expandable) stateDescription = stateWord
                        // Said aloud when it needs you or fails, never for the tool-by-tool chatter.
                        if (model.tone != BarTone.Working) liveRegion = LiveRegionMode.Polite
                    }
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(tone, RoundedCornerShape(50)))
                Spacer(Modifier.width(10.dp))
                HText(said, style = Type.small, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (model.hasTodos) {
                    // The to-dos' tally, labelled so it never reads as a count of agents.
                    Spacer(Modifier.width(10.dp))
                    TodoTally(model.done, model.total)
                } else if (model.counted) {
                    Spacer(Modifier.width(10.dp))
                    HText("${model.done}/${model.total}", style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
                }
                Spacer(Modifier.width(10.dp))
                HText(time, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                if (expandable) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Glyph.Chevron, Modifier.rotate(if (open) 90f else -90f), size = 12.dp, color = HN.inkDim)
                }
            }
            if (model.canStop && away == null) StopButton(stopping, codex, onStop)
            else Spacer(Modifier.width(8.dp))
        }
        // The body takes what the row above and the line below leave (weight: measured after them),
        // so the card's own cap can never squeeze either of those.
        if (open && listsAgents) {
            // The running agent stays in view.
            val at = agents.indexOfFirst { it.running }
            PanelList(todos, follow = if (at > 1) at - 1 else 0, Modifier.weight(1f, fill = false).panelBody()) {
                items(agents, key = { it.id }) { WorkflowAgentRow(it) }
            }
        } else if (open && listsMain) {
            // The newest agent stays in view, with the one before it.
            PanelList(todos, follow = if (barAgents.size > 3) barAgents.size - 1 else 0, Modifier.weight(1f, fill = false).panelBody()) {
                item(key = "main") { MainRow(model, turnMs) }
                items(barAgents, key = { it.id }) { a -> BarAgentRow(a, now, onOpen = { onOpenAgent(a.id) }) }
            }
        } else if (open && expandable) TodoChecklist(todos, Modifier.weight(1f, fill = false).panelBody())
        StatusLine(model, tone, Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp), frozen = away != null)
    }
}

/** Stop, or "Stopping…" (dim, not a button) once pressed until the turn ends. TalkBack: "Stop Claude", or Codex ([codex]). */
@Composable
private fun StopButton(stopping: Boolean, codex: Boolean, onClick: () -> Unit) {
    val desc = if (stopping) stringResource(R.string.status_stopping) else stringResource(R.string.status_stop_desc, com.prudnikweb.hypernova.ui.components.agentName(codex))
    Box(
        Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = !stopping, role = Role.Button, onClickLabel = desc, onClick = onClick)
            .semantics { contentDescription = desc }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(30.dp)
                .background(HN.ink.copy(alpha = if (stopping) 0.03f else 0.06f), RoundedCornerShape(50))
                .border(1.dp, HN.ink.copy(alpha = if (stopping) 0.06f else 0.12f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HText(stringResource(if (stopping) R.string.status_stopping else R.string.status_stop), style = Type.small, color = if (stopping) HN.inkDim else HN.inkMuted, maxLines = 1)
        }
    }
}

/** How long "Stopping…" holds at most when the turn doesn't end (the Esc didn't take). */
private const val STOPPING_MS = 3000L

/**
 * The thin line. Without a to-do list: a soft highlight sweeps along it while Claude works.
 * With one (or a workflow's agents): the share done fills it and the item Claude is on breathes.
 * Still when Claude waits for you or stopped on an error, and under Reduce Motion or battery saver.
 * The clock is read only inside the draw lambda and only when something moves, so a still line
 * asks for no frames.
 */
@Composable
private fun StatusLine(model: BarModel, tone: Color, modifier: Modifier = Modifier, frozen: Boolean = false) {
    val motion = LocalMotion.current
    val clock = LocalAmbient.current
    val moves = model.tone == BarTone.Working && !frozen && !motion.reduced && !motion.powerSave
    val progress = model.progress
    val still = frozen || model.tone != BarTone.Working
    Canvas(modifier.height(3.dp).clearAndSetSemantics { }) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(h / 2, h / 2)
        val gap = 1.5.dp.toPx()
        // A divider for each step (owner, 2026-11-01), while there's room for them to read as steps.
        val steps = model.total.takeIf { progress != null && !still && stepsFit(it, w, gap) } ?: 0
        // The track.
        if (steps == 0) drawRoundRect(HN.ink.copy(alpha = 0.09f), Offset.Zero, size, r)
        when {
            // Stopped for you or by an error: the whole line in that colour.
            still -> drawRoundRect(tone.copy(alpha = 0.75f), Offset.Zero, size, r)
            progress == null -> {
                if (!moves) {
                    // Held: a soft bar across the middle instead of a travelling one.
                    drawRoundRect(tone.copy(alpha = 0.55f), Offset(w * 0.2f, 0f), Size(w * 0.6f, h), r)
                } else {
                    val seg = w * SWEEP_WIDTH
                    val t = (clock.seconds(Pace.Live) / SWEEP_S) % 1f
                    // From fully off the left edge to fully off the right.
                    val x = -seg + (w + seg) * t
                    val brush = Brush.horizontalGradient(listOf(Color.Transparent, tone, Color.Transparent), startX = 0f, endX = seg)
                    translate(x) { drawRect(brush, Offset.Zero, Size(seg, h)) }
                }
            }
            steps > 0 -> {
                val doneSteps = kotlin.math.round(progress!! * steps).toInt()
                val a = if (moves) 0.25f + 0.6f * clock.pulse(BREATH_S, pace = Pace.Calm) else 0.5f
                val seg = w / steps
                for (i in 0 until steps) {
                    val color = when {
                        i < doneSteps -> tone
                        // The item Claude is on, breathing.
                        i == doneSteps -> tone.copy(alpha = a)
                        else -> HN.ink.copy(alpha = 0.09f)
                    }
                    drawRoundRect(color, Offset(seg * i, 0f), Size(if (i < steps - 1) seg - gap else seg, h), r)
                }
            }
            else -> {
                val done = w * progress!!
                if (done > 0f) drawRoundRect(tone, Offset.Zero, Size(done, h), r)
                // The item Claude is on: the stretch after the done part, breathing.
                if (progress < 1f && model.total > 0) {
                    val seg = w / model.total
                    val a = if (moves) 0.25f + 0.6f * clock.pulse(BREATH_S, pace = Pace.Calm) else 0.5f
                    drawRoundRect(tone.copy(alpha = a), Offset(done + gap, 0f), Size((seg - gap).coerceAtLeast(h), h), r)
                }
            }
        }
    }
}

/** Whether [total] steps can each show as their own piece of a line [width] wide: two or more, each at least 3 gaps long. */
internal fun stepsFit(total: Int, width: Float, gap: Float): Boolean = total >= 2 && width / total >= gap * 3

private fun toneColor(t: BarTone): Color = when (t) {
    BarTone.Working -> HN.accent
    BarTone.NeedsYou -> HN.amber
    BarTone.Error -> HN.danger
}

@Composable
private fun sayText(s: Say): String = when (s.kind) {
    SayKind.Starting -> stringResource(R.string.status_starting)
    SayKind.Sending -> stringResource(R.string.status_sending)
    SayKind.Thinking -> stringResource(R.string.status_thinking)
    SayKind.Writing -> stringResource(R.string.status_writing)
    SayKind.Working -> stringResource(R.string.status_working)
    SayKind.Retrying -> stringResource(R.string.status_retrying, s.arg)
    SayKind.Reading -> stringResource(R.string.status_reading, s.arg)
    SayKind.ReadingMany -> stringResource(R.string.status_reading_many, s.n)
    SayKind.Searching -> stringResource(R.string.status_searching, s.arg)
    SayKind.Editing -> stringResource(R.string.status_editing, s.arg)
    SayKind.Creating -> stringResource(R.string.status_creating, s.arg)
    SayKind.Running -> stringResource(R.string.status_running, s.arg)
    SayKind.Fetching -> stringResource(R.string.status_fetching, s.arg)
    SayKind.SearchingWeb -> stringResource(R.string.status_searching_web, s.arg)
    SayKind.Agents -> pluralStringResource(R.plurals.status_agents, s.n, s.n)
    SayKind.Planning -> stringResource(R.string.status_planning)
    SayKind.Tool -> stringResource(R.string.status_tool, s.arg)
    SayKind.Workflow -> stringResource(R.string.status_workflow, s.arg)
    SayKind.MainAndAgents -> pluralStringResource(R.plurals.status_main_agents, s.n, s.n)
    SayKind.MainWaiting -> stringResource(R.string.status_main_waiting)
    SayKind.Raw -> s.arg
    SayKind.WaitApprove -> stringResource(R.string.status_wait_approve, s.arg)
    SayKind.WaitAnswer -> stringResource(R.string.status_wait_answer)
    SayKind.WaitPlan -> stringResource(R.string.status_wait_plan)
    SayKind.WaitYou -> stringResource(R.string.status_wait_you)
    SayKind.Failed -> stringResource(R.string.status_failed)
}

/**
 * The opened bar's agents ([rows]) and, under their heading, the to-dos: one list that scrolls as
 * one, since a list each couldn't share a small panel (the first took all of it, leaving the to-dos
 * out of reach). It follows the agents ([follow], a row to bring into view; 0 for none), not the
 * to-dos, whose tally stays in the row above: two things to follow in one list would pull it about.
 */
@Composable
private fun PanelList(todos: List<Todo>, follow: Int, modifier: Modifier = Modifier, rows: LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(follow) { if (follow > 0) list.animateScrollToItem(follow) }
    LazyColumn(
        modifier.fillMaxWidth().panelViewport(list),
        state = list,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp),
    ) {
        rows()
        if (todos.isNotEmpty()) {
            item(key = "todo-heading") { TodoHeading(todos) }
            todoRows(todos)
        }
    }
}

/**
 * The opened bar's list gets no sliver: with less room than a row and its gap it isn't drawn, and
 * the bar reads as folded until there's room (the chevron still folds and opens it).
 */
private fun Modifier.panelBody(): Modifier = layout { measurable, constraints ->
    if (constraints.maxHeight < StatusPanel.ONE_ROW.roundToPx()) layout(0, 0) {}
    else {
        val list = measurable.measure(constraints)
        layout(list.width, list.height) { list.place(0, 0) }
    }
}

/**
 * Keeps the bar to its share of the room above the prompt box ([statusPanelMaxPx]). For a child of
 * weight in the box's column: all that column's other children are measured first, so what this
 * sees is what they left, keyboard and a grown box already taken off. [columnTop]: the padding at
 * the top of that column; [headerPx]: how tall the session's header is.
 */
internal fun Modifier.statusPanelRoom(columnTop: Dp, headerPx: () -> Int): Modifier = layout { measurable, constraints ->
    val max = statusPanelMaxPx(constraints.maxHeight, headerPx(), columnTop.roundToPx(), StatusPanel.FOLDED.roundToPx(), StatusPanel.ONE_ROW.roundToPx())
    if (max == 0) layout(0, 0) {}
    else {
        val bar = measurable.measure(constraints.copy(minHeight = 0, maxHeight = max))
        layout(bar.width, bar.height) { bar.place(0, 0) }
    }
}

/** The heights the bar's room is reckoned in. */
private object StatusPanel {
    /** The folded bar: its 48dp row, the 3dp line and the 10dp under it, and the 10dp between the card and what's below. */
    val FOLDED = 71.dp
    /** One row of the list whichever it is (an agent's is 44dp, a to-do's 30dp), and the 8dp under it. */
    val ONE_ROW = 52.dp
}

/**
 * One of a running workflow's agents inside the opened bar: a turning planet while it runs (amber
 * while it asks), a tick once done, a cross when it failed, a ring when stopped; its label, and its
 * state in a word, or what it does now while it runs. No tap through to an agent yet (LATER L95);
 * its card in the chat opens it.
 */
@Composable
private fun WorkflowAgentRow(a: AgentView) {
    val state = when (a.state) {
        AgentState.Running -> a.activity?.takeIf { it.isNotBlank() } ?: stringResource(R.string.status_agent_running)
        AgentState.Done -> stringResource(R.string.status_agent_done)
        AgentState.Failed -> stringResource(R.string.agent_failed_short)
        AgentState.Stopped -> stringResource(R.string.tool_stopped)
    }
    val said = if (a.running && a.activity != null) stringResource(R.string.status_agent_said_doing, a.label, a.activity)
    else stringResource(R.string.status_agent_said, a.label, state)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (a.running) HN.accent.copy(alpha = 0.07f) else HN.bg.copy(alpha = 0f))
            .clearAndSetSemantics { contentDescription = said }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when (a.state) {
                AgentState.Running -> Planet(if (a.asking) Activity.Alert else Activity.Live)
                AgentState.Done -> Icon(Glyph.Check, size = 13.dp, color = HN.accentDim, stroke = 1.6.dp)
                AgentState.Failed -> Icon(Glyph.Cross, size = 9.dp, color = HN.danger)
                AgentState.Stopped -> Canvas(Modifier.size(9.dp)) { drawCircle(HN.inkGhost, radius = size.minDimension / 2 - 0.5.dp.toPx(), style = Stroke(1.dp.toPx())) }
            }
        }
        Spacer(Modifier.width(10.dp))
        HText(a.label, style = Type.monoSmall, color = if (a.running) HN.ink else HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        HText(
            state, style = Type.monoSmall,
            color = when {
                a.asking -> HN.amber
                a.state == AgentState.Done -> HN.accentDim
                a.state == AgentState.Failed -> HN.danger.copy(alpha = 0.85f)
                else -> HN.inkDim
            },
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** The folded bar's to-do tally, `TO-DO 7/10`: labelled, so it never reads as a count of agents. */
@Composable
private fun TodoTally(done: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HText(stringResource(R.string.status_todo_label).uppercase(), style = Type.label, color = HN.accentDim, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        HText("$done/$total", style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
    }
}

/** The to-dos' heading in the opened bar, under the agents: `TO-DO 7/10`. TalkBack hears a heading. */
@Composable
private fun TodoHeading(todos: List<Todo>) {
    val done = todos.count { it.status == TodoStatus.Done }
    val said = stringResource(R.string.status_todo_heading_said, done, todos.size)
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = said; heading() }
            // In the list, which gives 14dp at the sides and 2dp between rows.
            .padding(start = 6.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TodoTally(done, todos.size)
    }
}

/**
 * The main agent, first of the rows the opened bar lists as Claude Code's footer does (owner,
 * 2026-10-30), with what it does and the turn's time: turning while it works, amber when it needs
 * you, still while it waits on its agents. A row per agent follows, oldest first.
 */
@Composable
private fun MainRow(model: BarModel, turnMs: Long) {
    val res = LocalContext.current.resources
    val doing = sayText(model.main)
    val name = stringResource(R.string.status_main)
    val said = listOf(name, doing, durationSaid(res, turnMs)).joinToString(", ")
    val activity = when {
        model.tone == BarTone.NeedsYou -> Activity.Alert
        model.tone == BarTone.Working && model.main.kind != SayKind.MainWaiting -> Activity.Live
        else -> Activity.Rest
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clearAndSetSemantics { contentDescription = said }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { Planet(activity) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(name, style = Type.monoSmall, color = HN.ink, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                HText(ccDuration(turnMs), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            }
            HText(
                doing, style = Type.small, color = if (model.tone == BarTone.NeedsYou) HN.amber else HN.inkMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // As wide as the agents' chevron, so the times line up.
        Spacer(Modifier.width(16.dp))
    }
}

/**
 * One agent at work: its kind in mono, and on the right how long it has run and its tokens as
 * Claude Code writes them (`4m12s · ↓ 81.2k tokens`); under it what it does now in words, or what
 * it's for before its first step. Amber while it asks. A tap opens its screen.
 */
@Composable
private fun BarAgentRow(a: BarAgent, now: Long, onOpen: () -> Unit) {
    val res = LocalContext.current.resources
    val ms = (now - a.startedAt).coerceAtLeast(0)
    val doing = when {
        a.asking -> stringResource(R.string.status_wait_you)
        else -> a.doing?.let { sayText(it) } ?: a.task
    }
    val figures = listOfNotNull(ccDuration(ms), a.tokens?.let { stringResource(R.string.status_tokens_figure, ccTokens(it)) }).joinToString(" · ")
    val said = listOfNotNull(a.kind, a.task, a.doing?.let { sayText(it) }?.takeIf { it != a.task }, if (a.asking) doing else null, durationSaid(res, ms), a.tokens?.let { tokensSaid(res, it) })
        .joinToString(", ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (a.asking) HN.amber.copy(alpha = 0.07f) else HN.accent.copy(alpha = 0.05f))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.session_sheet_open_agent), onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { Planet(if (a.asking) Activity.Alert else Activity.Live) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(a.kind, style = Type.monoSmall, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                HText(figures, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            }
            if (doing != null) {
                HText(doing, style = Type.small, color = if (a.asking) HN.amber else HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(Glyph.Chevron, size = 10.dp, color = HN.inkDim)
    }
}

/** How long, for TalkBack: "42 seconds", "4 minutes", "1 hour 12 minutes". */
internal fun durationSaid(res: android.content.res.Resources, ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0).toInt()
    return if (s < 60) res.getQuantityString(R.plurals.status_seconds_said, s, s)
    else com.prudnikweb.hypernova.ui.stats.hoursSaid(res, ms)
}

/** Tokens, for TalkBack: "980 tokens", "81 thousand tokens", "1.2 million tokens". */
internal fun tokensSaid(res: android.content.res.Resources, n: Long): String {
    val t = spokenTokens(n)
    return when (t.scale) {
        TokenScale.Units -> res.getQuantityString(R.plurals.status_tokens_said_units, t.count, t.count)
        TokenScale.Thousands -> res.getString(R.string.status_tokens_said_thousands, t.figure)
        TokenScale.Millions -> res.getString(R.string.status_tokens_said_millions, t.figure)
    }
}
