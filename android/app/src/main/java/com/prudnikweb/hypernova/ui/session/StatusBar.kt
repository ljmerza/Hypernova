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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
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
) {
    val visible: Boolean get() = model != null
}

/**
 * The bar's state for a session. The to-do list is asked of the Mac here. The red error bar
 * folds by itself after [ERROR_SHOW_MS] so a stopped session doesn't wear it forever; a tap on it
 * keeps it until tapped again.
 */
@Composable
internal fun rememberStatusBar(hub: Hub, session: Session, items: List<Item>, sending: Boolean, away: String? = null): StatusBarState {
    val todos = rememberTodos(hub, session.id, items)
    val pending = remember(items) { items.lastOrNull { it is Item.Approval && it.resolved == null } as? Item.Approval }
    val reads = remember(items) { trailingReads(items) }
    // Stop sends Esc to a shared terminal or interrupts a session the Mac runs; a terminal session
    // the Mac only watches can't be stopped from here (the Mac answers 409).
    val stoppable = session.term != null || session.origin == "hypernova" || hub.isDemo
    val raw = statusBar(
        BarInput(session.phase, session.state, session.activity, sending, session.agentsRunning, pending, todos, reads, stoppable = stoppable),
    )
    val isError = raw?.tone == BarTone.Error
    // Kept out here so the list stays open (or folded) across short gaps between two steps.
    var open by rememberSaveable(session.id) { mutableStateOf(false) }
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
        open = !open
        if (isError) kept = !kept
    }, away = away)
}

/**
 * The agent status bar above the prompt box, only while a turn runs (owner, 2026-10-17): one line
 * of what Claude is doing, the to-dos as 3/7, the turn's time and Stop, with a thin progress line
 * along the bottom. It is the to-do panel folded: tap it to open the checklist, tap again to fold
 * it. When the turn ends it folds away; it keeps its last look while it does.
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
                StatusCard(shown, bar.todos, bar.open, bar.onToggle, since, bar.away, stopping, codex = session.codex) {
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
private fun StatusCard(model: BarModel, todos: List<Todo>, open: Boolean, onToggle: () -> Unit, since: Long, away: String?, stopping: Boolean, codex: Boolean, onStop: () -> Unit) {
    // Ticks once a second while the card is on screen and the Mac is linked; gone with it.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(away == null) {
        while (away == null) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val tone by animateColorAsState(if (away != null) HN.inkDim else toneColor(model.tone), tween(300), label = "statusTone")
    val said = away ?: sayText(model.say)
    val time = clockText(now - since)
    val expandable = model.hasTodos
    val spoken = when {
        model.hasTodos -> stringResource(R.string.status_desc_todos, said, model.done, model.total, time)
        else -> stringResource(R.string.status_desc_plain, said, time)
    }
    val stateWord = stringResource(if (open) R.string.status_open else R.string.status_folded)
    val label = stringResource(if (open) R.string.status_fold else R.string.status_expand)
    GlassCard(Modifier.fillMaxWidth().animateContentSize(tween(Motion.DIALOG, easing = Motion.Expo))) {
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
        if (open && expandable) TodoChecklist(todos)
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
 * With one: the share done fills it and the item Claude is on breathes. Still when Claude waits
 * for you or stopped on an error, and under Reduce Motion or battery saver. The clock is read only
 * inside the draw lambda and only when something moves, so a still line asks for no frames.
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
        // The track.
        drawRoundRect(HN.ink.copy(alpha = 0.09f), Offset.Zero, size, r)
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
            else -> {
                val done = w * progress
                if (done > 0f) drawRoundRect(tone, Offset.Zero, Size(done, h), r)
                // The item Claude is on: the stretch after the done part, breathing.
                if (progress < 1f && model.total > 0) {
                    val seg = w / model.total
                    val a = if (moves) 0.25f + 0.6f * clock.pulse(BREATH_S, pace = Pace.Calm) else 0.5f
                    val gap = 1.5.dp.toPx()
                    drawRoundRect(tone.copy(alpha = a), Offset(done + gap, 0f), Size((seg - gap).coerceAtLeast(h), h), r)
                }
            }
        }
    }
}

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
    SayKind.WaitApprove -> stringResource(R.string.status_wait_approve, s.arg)
    SayKind.WaitAnswer -> stringResource(R.string.status_wait_answer)
    SayKind.WaitPlan -> stringResource(R.string.status_wait_plan)
    SayKind.WaitYou -> stringResource(R.string.status_wait_you)
    SayKind.Failed -> stringResource(R.string.status_failed)
}
