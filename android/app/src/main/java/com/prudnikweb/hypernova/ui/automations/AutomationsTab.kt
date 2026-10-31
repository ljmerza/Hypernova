package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.Glyph
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AutoMode
import com.prudnikweb.hypernova.data.Automation
import com.prudnikweb.hypernova.data.AutomationsState
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.data.WorkflowShape
import com.prudnikweb.hypernova.data.timed
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.Toggle
import com.prudnikweb.hypernova.ui.components.Z
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home's Automations tab (spec §5): the Mac's automations as cards, soonest and busiest first, a
 * Pause all, and templates to start from (the limit ones first). The sky hides here, as on History.
 * [sourceKey] is Home's blur source, so its bars blur these cards too.
 */
@Composable
fun AutomationsTab(
    hub: Hub,
    top: Dp,
    bottom: Dp,
    sourceKey: String,
    onEdit: (id: String?, template: String?) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val haze = LocalHaze.current
    val autos = hub.automations
    val st = autos.state
    val w = rememberWords()
    // Out of reach, the last snapshot holds still where the Mac was last heard: no stopwatch counts
    // on, no switch flips to bounce back (A15).
    val offline = hub.link.state != LinkState.Connected
    var asOf by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(offline) { asOf = if (offline) System.currentTimeMillis() else null }
    val anyActive = !offline && st?.list?.any { it.lastRun?.active == true } == true
    // A running card's clock moves by the second; otherwise relative times move once a minute.
    val ticking by produceState(System.currentTimeMillis(), anyActive) {
        while (true) { value = System.currentTimeMillis(); delay(if (anyActive) 1000 else 30_000) }
    }
    val now = asOf ?: ticking
    val ctx = summaryContext(hub, now)
    val scope = LocalAppScope.current
    val toast = LocalToast.current
    val context = LocalContext.current
    val opener = com.prudnikweb.hypernova.ui.LocalOpener.current
    // A switch flips at once; the Mac's answer settles it (or puts it back, with a toast).
    var flipping by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val retry = rememberRetry(hub, w, ctx.zone)

    LazyColumn(
        Modifier.fillMaxSize().hazeSource(haze, zIndex = Z.CONTENT, key = sourceKey),
        contentPadding = PaddingValues(top = top + 84.dp, bottom = bottom + 84.dp), // fork: the bottom bar is tabs only
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "seam") { Header(st, w, ctx, now, enabled = !offline, onPause = { p ->
            scope.launch {
                autos.setPaused(p)
                    .onSuccess { toast(context.getString(if (p) R.string.au_paused_toast else R.string.au_resumed_toast)) }
                    .onFailure { toast(it.message.orEmpty()) }
            }
        }) }
        if (st == null) {
            item(key = "none") {
                Box(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    when (hub.link.state) {
                        LinkState.Connecting, LinkState.Reconnecting -> LoadingLine(stringResource(R.string.connecting_to, hub.mac.name))
                        LinkState.Offline -> HText(stringResource(R.string.au_offline), style = Type.body, color = HN.inkMuted)
                        else -> HText(stringResource(R.string.au_old_daemon), style = Type.body, color = HN.inkMuted)
                    }
                }
            }
            return@LazyColumn
        }
        val at = asOf
        if (at != null) {
            item(key = "offline") {
                HText(stringResource(R.string.au_offline_as_of, RunWords.clockWords(w, at, at, ctx.zone)), style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 6.dp))
            }
        }
        val list = sorted(st.list)
        val used = st.list.mapNotNull { it.template }.toSet()
        val left = Templates.offered(used, st.features)
        // A question an automation waits on: above everything, with its answers.
        for (r in st.asks) {
            item(key = "ask:${r.id}") {
                AskCard(hub, r, Modifier.padding(horizontal = 16.dp).animateItem(), onOpen = { onEdit(r.automationId, null) })
            }
        }
        if (list.isEmpty()) {
            item(key = "intro") {
                HText(stringResource(com.prudnikweb.hypernova.ui.components.byAgents(hub.claude, hub.codex, R.string.au_intro, R.string.au_intro_codex, R.string.au_intro_both)), style = Type.body, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 6.dp))
            }
        }
        items(list, key = { it.id }) { a ->
            val on = flipping[a.id] ?: a.enabled
            AutomationCard(
                hub, a.copy(enabled = on), st, w, ctx, now,
                onEdit = { onEdit(a.id, null) },
                onOpenSession = onOpenSession,
                onToggle = { v ->
                    val once = a.trigger as? com.prudnikweb.hypernova.data.Trigger.Once
                    if (v && once != null && once.at <= System.currentTimeMillis()) {
                        // A Once whose time has passed: its editor opens on the next day at that time (A6).
                        OnceAgain.ask(a.id)
                        onEdit(a.id, null)
                    } else {
                        flipping = flipping + (a.id to v)
                        scope.launch {
                            autos.setEnabled(a.id, v).onFailure { toast(it.message.orEmpty()) }
                            flipping = flipping - a.id
                        }
                    }
                },
                onRetry = retry,
                editable = !offline,
                modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
            )
        }
        if (left.isNotEmpty()) {
            item(key = "from") {
                Seam(stringResource(R.string.au_start_from), Modifier.padding(horizontal = 20.dp).padding(top = if (list.isEmpty()) 4.dp else 18.dp, bottom = 6.dp))
            }
            items(left.chunked(2), key = { row -> "t:" + row.joinToString { it.id } }) { row ->
                Row(Modifier.padding(horizontal = 16.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (t in row) {
                        // Switch provider at the limit with nothing to switch to: Providers first (A4).
                        val needs = Templates.needsProvider(t, hub.providers)
                        TemplateTile(t, onClick = { if (needs) opener.providers() else onEdit(null, t.id) }, Modifier.weight(1f).fillMaxHeight(), needsProvider = needs)
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** "AUTOMATIONS ─── 3 on, next 9:00 (Pause all)", or Paused in amber with Resume. */
@Composable
private fun Header(st: AutomationsState?, w: Words, ctx: SummaryContext, now: Long, enabled: Boolean, onPause: (Boolean) -> Unit) {
    val list = st?.list.orEmpty()
    val on = list.count { it.enabled }
    val next = list.filter { it.enabled }.mapNotNull { it.nextAt }.filter { it > now - 60_000 }.minOrNull()
    // Nothing due on the clock: what the next one on waits for (A18).
    val waits = list.firstOrNull { it.enabled && !it.trigger.timed }?.trigger
    val trail = when {
        st == null -> null
        st.paused -> null
        on == 0 && list.isNotEmpty() -> stringResource(R.string.au_trail_off)
        on == 0 -> null
        next != null -> stringResource(R.string.au_trail_on_next, on, RunWords.clockWords(w, next, now, ctx.zone))
        waits is com.prudnikweb.hypernova.data.Trigger.Reset -> stringResource(R.string.au_trail_wait_reset, on)
        waits is com.prudnikweb.hypernova.data.Trigger.Limit -> stringResource(R.string.au_trail_wait_limit, on)
        waits is com.prudnikweb.hypernova.data.Trigger.SessionEvent -> stringResource(R.string.au_trail_wait_session, on)
        waits == com.prudnikweb.hypernova.data.Trigger.Plugged || waits is com.prudnikweb.hypernova.data.Trigger.BatteryLow -> stringResource(R.string.au_trail_wait_power, on)
        waits is com.prudnikweb.hypernova.data.Trigger.Webhook -> stringResource(R.string.wf_trail_wait_webhook, on)
        waits is com.prudnikweb.hypernova.data.Trigger.FileWatch -> stringResource(R.string.wf_trail_wait_files, on)
        waits is com.prudnikweb.hypernova.data.Trigger.Github -> stringResource(R.string.wf_trail_wait_github, on)
        waits is com.prudnikweb.hypernova.data.Trigger.Hook -> stringResource(R.string.wf_trail_wait_hook, on)
        else -> stringResource(R.string.au_trail_on, on)
    }
    val activity = when {
        list.any { it.lastRun?.state == Run.NEEDS_YOU } -> Activity.Alert
        list.any { it.lastRun?.working == true } -> Activity.Live
        else -> Activity.Rest
    }
    Row(Modifier.padding(start = 20.dp, end = 16.dp).padding(bottom = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Seam(stringResource(R.string.au_tab), Modifier.weight(1f), trail = trail, activity = activity)
        if (st != null && st.paused) {
            Spacer(Modifier.width(10.dp))
            HText(stringResource(R.string.au_paused).uppercase(), style = Type.label, color = HN.amber, maxLines = 1)
        }
        if (st != null && list.isNotEmpty()) {
            Spacer(Modifier.width(10.dp))
            Pill(stringResource(if (st.paused) R.string.au_resume else R.string.au_pause_all), { onPause(!st.paused) }, kind = if (st.paused) PillKind.Outline else PillKind.Ghost, height = 32.dp, enabled = enabled)
        }
    }
}

/**
 * Needs you, running, waiting, then by when they're due, then the ones events fire, then the ones
 * off. A run at work counts before the switch: an "only the next time" one is off while its run
 * still needs you (A1).
 */
fun sorted(list: List<Automation>): List<Automation> = list.sortedWith(
    compareBy<Automation> { a ->
        when {
            a.lastRun?.state == Run.NEEDS_YOU -> 0
            a.lastRun?.state == Run.RUNNING -> 1
            a.lastRun?.active == true -> 2
            !a.enabled -> 5
            a.nextAt != null -> 3
            else -> 4
        }
    }.thenBy { it.nextAt ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() },
)

/** Run now's or Retry's refusal in words: the limit with when it lifts, else as the Mac (or the app) said it. */
fun runRefusal(w: Words, e: Throwable, zone: java.time.ZoneId): String {
    val until = (e as? com.prudnikweb.hypernova.data.AutomationError)?.takeIf { it.code == "limited" }?.until
    return if (until != null) RunWords.limitedUntil(w, until, System.currentTimeMillis(), zone) else e.message.orEmpty()
}

/** A spent Once switched on again from its card: its editor opens moved to the next day at that time (A6). */
object OnceAgain {
    private var asked: String? = null
    fun ask(id: String) { asked = id }
    /** True once, for the automation that was asked about. */
    fun take(id: String?): Boolean = (id != null && asked == id).also { if (it) asked = null }
}

/**
 * One automation: its name and switch on the seam, the sentence it reads as, and one status line
 * (what its run does now, or how the last one went, and when it runs next). Off, the whole card
 * dims. A tap opens the editor; a tap on a working run's line opens its session.
 */
@Composable
fun AutomationCard(
    hub: Hub,
    a: Automation,
    st: AutomationsState,
    w: Words,
    ctx: SummaryContext,
    now: Long,
    onEdit: () -> Unit,
    onOpenSession: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: ((Run) -> Unit)? = null,
    /** False while the Mac can't be reached: the switch holds still. */
    editable: Boolean = true,
) {
    val context = LocalContext.current
    val run = a.lastRun
    val needs = run?.state == Run.NEEDS_YOU
    // A workflow reads as its trigger, its first step and how many follow; a simple one as today.
    val steps = WorkflowShape.stepsOf(a)
    val summary = if (a.steps != null) Summary.flow(w, a.trigger, steps, a.outputs, a.onlyOnce, ctx) else Summary.sentence(w, a.trigger, a.action, a.onlyOnce, ctx)
    val line = run?.let { RunWords.line(w, it, now, ctx.zone, askOf(hub, it), providerTrouble(context, hub, a), codex = startsOnCodex(a)) }
    // A problem the last run already failed on is said once, by that run.
    val problem = problemText(a)?.takeIf { run?.reason == null || run.reason != a.problem }
    val nextLine = when {
        a.problem != null || problem != null -> null
        !a.enabled -> null
        st.paused -> stringResource(R.string.au_paused)
        a.nextAt != null -> RunWords.next(w, a.nextAt, now, ctx.zone)
        else -> null
    }
    // Off dims the card, but not while its run still works or needs you (A1).
    val lit = a.enabled || run?.active == true
    GlassCard(
        modifier.fillMaxWidth().graphicsLayer { alpha = if (lit) 1f else 0.55f },
        accent = if (needs) HN.amber else null,
        // TalkBack reads the card as its name, the sentence and its status; the switch on its own.
        onClick = onEdit, onClickLabel = stringResource(R.string.au_edit),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 6.dp, top = 2.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Seam(
                    a.name,
                    Modifier.weight(1f),
                    activity = when {
                        needs -> Activity.Alert
                        run?.working == true -> Activity.Live
                        else -> Activity.Rest
                    },
                )
                Box(
                    Modifier
                        .size(56.dp, 48.dp)
                        .toggleable(a.enabled, remember { MutableInteractionSource() }, indication = null, enabled = editable, role = Role.Switch, onValueChange = onToggle)
                        .semantics { contentDescription = a.name },
                    contentAlignment = Alignment.Center,
                ) { Toggle(a.enabled) }
            }
            Column(Modifier.padding(end = 10.dp)) {
                HText(summary, style = Type.body, color = HN.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val mode = a.mode?.takeIf { it != AutoMode.ALLOWED }
                // A workflow's card is today's card and a small count of its steps.
                val chips = CardChips(mode, a.paid, steps.size.takeIf { it > 1 } ?: 0)
                val trailing = problem ?: nextLine
                // A run that failed, with nothing in the way that would fail it again: Retry on the card.
                val retry = onRetry?.takeIf { editable && run?.state == Run.FAILED && a.problem == null }
                if (run != null && line != null) {
                    Spacer(Modifier.height(10.dp))
                    // With nothing under it, the chips end the run's own line.
                    RunStatus(hub, run, line, w, ctx, now, onOpenSession, chips = if (trailing == null) chips else null, onRetry = retry?.let { { it(run) } })
                }
                if (trailing != null || (chips.any && (run == null || line == null))) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HText(trailing.orEmpty(), style = if (problem != null) Type.small else Type.monoSmall, color = if (problem != null) HN.amber else HN.inkDim, maxLines = 2, modifier = Modifier.weight(1f))
                        ChipsEnd(chips)
                    }
                }
            }
        }
    }
}

/** The card's run line: what it does now in its colour, or how the last one went and what it said. */
@Composable
private fun RunStatus(hub: Hub, run: Run, line: RunLine, w: Words, ctx: SummaryContext, now: Long, onOpenSession: (String) -> Unit, chips: CardChips? = null, onRetry: (() -> Unit)? = null) {
    val session = runSession(hub, run)
    val open = if (run.working && session != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.au_open_run)) { onOpenSession(session) } else Modifier
    when (run.state) {
        Run.RUNNING, Run.NEEDS_YOU -> {
            val alert = run.state == Run.NEEDS_YOU
            val color = if (alert) HN.amber else HN.accent
            // Drawn: RUNNING, then how long after a stopwatch (and its sessions, when several);
            // NEEDS YOU, then what it asks in mono. No "RUNNING · 1m 12s".
            val ask = if (alert) askOf(hub, run) else null
            val sessions = run.sessionIds.size.takeIf { !alert && it > 1 }?.let { pluralStringResource(R.plurals.au_sessions, it, it) }
            Row(open.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                HText(stringResource(if (alert) R.string.au_state_needs else R.string.au_state_running).uppercase(), style = Type.label, color = color, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                // What it does takes the room; the chip, if any, ends the line.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (alert) {
                        if (ask != null) HText(ask, style = Type.monoSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        GlyphFigure(Glyph.Timer, RunWords.duration(w, RunWords.ran(run, now) ?: 0L), color = color)
                        if (sessions != null) {
                            Spacer(Modifier.width(10.dp))
                            HText(sessions, style = Type.monoSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (chips != null) ChipsEnd(chips)
            }
        }
        Run.QUEUED, Run.WAITING -> Row(verticalAlignment = Alignment.CenterVertically) {
            HText(line.text, style = Type.small, color = HN.inkMuted, maxLines = 1, modifier = Modifier.weight(1f))
            if (chips != null) ChipsEnd(chips)
        }
        else -> Column {
            val ran = RunWords.ran(run, now)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { RunGlyph(line.tone, size = 12.dp) }
                Spacer(Modifier.width(8.dp))
                // When it ran, then how long after a stopwatch: no "Today 9:00 · 2m 14s".
                RunWhen(RunWords.at(w, run.startedAt ?: run.firedAt, now, ctx.zone), ran?.let { RunWords.duration(w, it) }, Modifier.weight(1f))
                if (chips != null) ChipsEnd(chips)
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                HText(line.text, style = Type.small, color = line.tone.color(), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (onRetry != null) {
                    Spacer(Modifier.width(8.dp))
                    Pill(stringResource(R.string.au_retry), onRetry, kind = PillKind.Outline, height = 32.dp, leading = Glyph.Reload)
                }
            }
        }
    }
}

/** A card's problem in amber, in place of "Next …": what keeps it from running as set. */
@Composable
fun problemText(a: Automation): String? {
    val ranIn = a.lastRun?.permissionMode
    if (a.mode == AutoMode.AUTO && ranIn != null && ranIn != AutoMode.AUTO) {
        return stringResource(R.string.au_problem_mode, modeTitle(ranIn))
    }
    return when (a.problem) {
        null -> null
        "outside_projects" -> stringResource(R.string.au_problem_outside)
        "no_model" -> stringResource(R.string.au_problem_no_model)
        "bad_definition" -> stringResource(R.string.au_problem_bad_definition)
        "no_battery" -> stringResource(R.string.au_problem_no_battery)
        "no_project" -> stringResource(R.string.au_problem_no_project)
        "no_session" -> stringResource(R.string.au_problem_no_session)
        "no_provider" -> stringResource(R.string.au_problem_no_provider)
        "mode_unavailable" -> stringResource(R.string.au_problem_mode, modeTitle(ranIn ?: AutoMode.ASK))
        "no_gh" -> stringResource(R.string.wf_problem_no_gh)
        "no_hooks" -> stringResource(R.string.wf_problem_no_hooks)
        "output_lost" -> stringResource(R.string.wf_problem_output_lost)
        else -> null
    }
}

/** A permission mode's name as the editor says it ("Allowed only", "Ask me"…). */
@Composable
fun modeTitle(mode: String): String = stringResource(
    when (mode) {
        AutoMode.ASK -> R.string.au_mode_ask
        AutoMode.EDITS -> R.string.au_mode_edits
        AutoMode.AUTO -> R.string.au_mode_auto
        AutoMode.NO_ASKING -> R.string.au_mode_no_asking
        // A start step Codex runs (data/Codex.kt).
        com.prudnikweb.hypernova.data.AgentKind.READ_ONLY -> R.string.cx_mode_read_only
        com.prudnikweb.hypernova.data.AgentKind.CAN_EDIT -> R.string.cx_mode_can_edit
        else -> R.string.au_mode_allowed
    },
)

/**
 * A template to start from: its drawn mark, its name and what it does; "Costs money" when it does,
 * or "Add a provider first" when it has nothing to work with yet ([needsProvider]: a tap opens Providers).
 */
@Composable
fun TemplateTile(t: Templates.Template, onClick: () -> Unit, modifier: Modifier = Modifier, needsProvider: Boolean = false) {
    val name = stringResource(t.name)
    GlassCard(modifier, onClick = onClick, onClickLabel = if (needsProvider) stringResource(R.string.au_add_provider) else name) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AutoIcon(t.glyph, size = 18.dp, color = HN.accentDim)
                Spacer(Modifier.weight(1f))
                if (needsProvider) OutlineChip(stringResource(R.string.au_t_switch_needs), HN.inkMuted)
                else if (t.costs) OutlineChip(stringResource(R.string.au_t_costs), HN.amber)
            }
            Spacer(Modifier.height(10.dp))
            HText(name, style = Type.small.copy(fontWeight = FontWeight(600)), color = HN.ink, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            HText(stringResource(t.blurb), style = Type.small, color = HN.inkMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The chips a card's last line ends with: its mode (anything but "Allowed only"), COSTS MONEY (A11), and a workflow's step count. */
class CardChips(val mode: String?, val paid: Boolean, val steps: Int = 0) {
    val any: Boolean get() = mode != null || paid || steps > 1
}

@Composable
private fun ChipsEnd(c: CardChips) {
    if (c.steps > 1) {
        Spacer(Modifier.width(6.dp))
        com.prudnikweb.hypernova.ui.components.TagChip(pluralStringResource(R.plurals.wf_steps_n, c.steps, c.steps))
    }
    if (c.paid) {
        Spacer(Modifier.width(6.dp))
        OutlineChip(stringResource(R.string.au_t_costs), HN.amber)
    }
    if (c.mode != null) {
        Spacer(Modifier.width(6.dp))
        ModeChip(c.mode)
    }
}
