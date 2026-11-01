package com.prudnikweb.hypernova.ui.automations

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Automation
import com.prudnikweb.hypernova.data.AutomationError
import com.prudnikweb.hypernova.data.Budget
import com.prudnikweb.hypernova.data.BudgetUsed
import com.prudnikweb.hypernova.data.Features
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.data.RunStep
import com.prudnikweb.hypernova.data.Step
import com.prudnikweb.hypernova.data.WorkflowShape
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.AttentionCard
import com.prudnikweb.hypernova.ui.components.DialogButton
import com.prudnikweb.hypernova.ui.components.GlassDialog
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphFigure
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.usageBar
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

// ─── A run's steps ─────────────────────────────────────────────────────────

/**
 * A workflow run's steps as a compact vertical timeline: each step's mark on a hairline rail, its
 * name, what came of it, and how long it took; the step at work on a faint mint wash with its
 * planet turning. Then TOLD: what became of each output once the run ended. A step that worked in
 * a session opens it.
 */
@Composable
internal fun RunTimeline(hub: Hub, run: Run, a: Automation?, w: Words, now: Long, onOpenSession: (String) -> Unit, modifier: Modifier = Modifier) {
    val defs = a?.let { WorkflowShape.stepsOf(it) }.orEmpty()
    val outputs = a?.let { WorkflowShape.outputsOf(it) }.orEmpty()
    Column(modifier) {
        run.steps.forEachIndexed { i, s ->
            val def = defs.firstOrNull { it.id == s.id }
            val session = s.sessionIds.firstOrNull { hub.session(it) != null }
            TimelineRow(
                name = WorkflowWords.stepName(w, s, def),
                mono = s.kind == Step.SHELL && def != null,
                line = WorkflowWords.step(w, s, now, codex = (def?.action as? com.prudnikweb.hypernova.data.Action.Start)?.providerId == com.prudnikweb.hypernova.data.AgentKind.CODEX_PROVIDER),
                took = RunWords.ranStep(s, now)?.takeIf { s.state != RunStep.PENDING && s.state != Run.SKIPPED }?.let { RunWords.duration(w, it) },
                atWork = s.active,
                asking = s.state == Run.NEEDS_YOU,
                last = i == run.steps.lastIndex,
                onOpen = session?.let { { onOpenSession(it) } },
            )
        }
        if (run.outputs.isNotEmpty() && !run.active) {
            Spacer(Modifier.height(6.dp))
            HText(stringResource(R.string.wf_told_label).uppercase(), style = Type.label, color = HN.inkDim, modifier = Modifier.padding(start = 28.dp, bottom = 2.dp))
            for (o in run.outputs) {
                val line = WorkflowWords.output(w, o)
                val name = WorkflowWords.outputName(w, outputs.firstOrNull { it.id == o.id }, o.kind)
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).semantics(mergeDescendants = true) { contentDescription = "$name. ${line.text}" }, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { RunGlyph(line.tone, size = 11.dp) }
                    Spacer(Modifier.width(12.dp))
                    HText(name, style = Type.small, color = HN.inkMuted, maxLines = 1)
                    Spacer(Modifier.width(10.dp))
                    HText(line.text, style = Type.small, color = line.tone.color(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(name: String, mono: Boolean, line: RunLine, took: String?, atWork: Boolean, last: Boolean, onOpen: (() -> Unit)?, asking: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            // The step at work on a faint wash: mint while it works, amber while it waits for you.
            .then(if (atWork) Modifier.background((if (asking) HN.amber else HN.accent).copy(alpha = 0.07f)) else Modifier)
            .then(if (onOpen != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.au_open_run), onClick = onOpen) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(name, line.text, took, line.late).joinToString(". ") },
    ) {
        // The rail: the step's mark, and a hairline down to the next.
        Column(Modifier.width(16.dp).fillMaxHeight().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { RunGlyph(line.tone, size = 12.dp) }
            if (!last) Box(Modifier.padding(top = 3.dp).width(1.dp).weight(1f).background(HN.line))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            HText(name, style = if (mono) Type.monoSmall else Type.small, color = if (atWork) HN.ink else HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HText(line.text, style = Type.small, color = line.tone.color(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (line.late != null) HText(line.late, style = Type.small, color = HN.inkDim, maxLines = 1)
        }
        if (took != null) {
            Spacer(Modifier.width(8.dp))
            GlyphFigure(Glyph.Timer, took, Modifier.padding(top = 8.dp, end = 6.dp), color = if (asking) HN.amber else if (atWork) HN.accent else HN.inkDim)
        }
    }
}

// ─── Its budget ────────────────────────────────────────────────────────────

/**
 * A run's budget so far, in the usage meters' style (ui/components/UsageMeter.kt): a small
 * "BUDGET" label, what it used of what it may in mono ("12 of 30 min", "2.4 of 10 points"), and a
 * hairline bar with its used part: mint, amber from 80%, red once reached. One line each; [budget]
 * is its automation's (null: none now, so just what it used).
 */
@Composable
internal fun RunBudget(used: BudgetUsed, budget: Budget?, modifier: Modifier = Modifier) {
    val w = rememberWords()
    val lines = RunWords.budget(w, used, budget)
    if (lines.isEmpty()) return
    val said = stringResource(R.string.wf_budget_said, lines.joinToString(", ") { it.text })
    Column(modifier.clearAndSetSemantics { contentDescription = said }) {
        lines.forEachIndexed { k, l ->
            Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { if (k == 0) Icon(Glyph.Timer, size = 11.dp, color = HN.inkDim) }
                Spacer(Modifier.width(12.dp))
                HText(if (k == 0) stringResource(R.string.wf_budget_label).uppercase() else "", style = Type.label, color = HN.inkDim, maxLines = 1, modifier = Modifier.width(64.dp))
                HText(l.text, style = Type.monoSmall, color = HN.ink, maxLines = 1)
                if (l.used != null) {
                    Spacer(Modifier.width(10.dp))
                    val colour = when {
                        l.used >= 1f -> HN.danger
                        l.used >= 0.8f -> HN.amber
                        else -> HN.accent
                    }
                    Canvas(Modifier.weight(1f).height(9.dp)) { usageBar(l.used, colour) }
                }
            }
        }
    }
}

// ─── Retry from the step that failed ───────────────────────────────────────

/**
 * Retry, as the run rows and cards offer it: a run that failed at a later step asks first, "From
 * step 2" (the steps before it kept, with what they said) or "From the start"; any other runs again
 * at once. Returns what Retry calls.
 */
@Composable
internal fun rememberRetry(hub: Hub, w: Words, zone: ZoneId): (Run) -> Unit {
    val scope = LocalAppScope.current
    val toast = LocalToast.current
    var asking by remember { mutableStateOf<Pair<Run, Int>?>(null) }
    fun go(r: Run, from: String?) {
        scope.launch {
            hub.automations.retry(r.id, from)
                .onSuccess { toast(RunWords.started(w, it, System.currentTimeMillis(), zone, codex = startsOnCodex(hub.automations.automation(r.automationId)))) }
                .onFailure { e -> toast(runRefusal(w, e, zone)) }
        }
    }
    asking?.let { (r, k) ->
        GlassDialog(
            onDismiss = { asking = null },
            title = stringResource(R.string.wf_retry_q, k + 1),
            line = stringResource(R.string.wf_retry_body),
            secondary = DialogButton(stringResource(R.string.wf_retry_from_start), { asking = null; go(r, null) }, PillKind.Ghost),
            primary = DialogButton(stringResource(R.string.wf_retry_from_step, k + 1), { asking = null; go(r, r.steps[k].id) }, PillKind.Primary),
        )
    }
    val steps = hub.automations.state?.has(Features.STEPS) == true
    return { r ->
        val k = RunWords.failedStep(r)
        if (steps && k != null && k > 0) asking = r to k else go(r, null)
    }
}

// ─── A question that waits for you ─────────────────────────────────────────

/**
 * A run waiting at an ask step, as an attention card (Home and the Automations tab): the question as
 * its title, the automation's name under it, the default and its countdown as one quiet figure
 * ("Skip in 9m"), and the answers as pills. Swiped right it goes until another question comes; the
 * Mac still takes the default when the time runs out. Answered elsewhere first, it says so calmly.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskCard(hub: Hub, run: Run, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val ask = run.ask ?: return
    val context = LocalContext.current
    val scope = LocalAppScope.current
    val toast = LocalToast.current
    val w = rememberWords()
    val now by produceState(System.currentTimeMillis(), ask.until) {
        while (true) { value = System.currentTimeMillis(); delay(if (ask.until - value < 120_000) 1_000 else 15_000) }
    }
    // Minutes while there are many; seconds only in its last two minutes, when it ticks by the second.
    val ms = (ask.until - now).coerceAtLeast(0)
    val left = if (ms > 120_000) context.getString(R.string.wf_min, ((ms + 59_999) / 60_000).toInt()) else RunWords.duration(w, ms)
    var sending by remember(run.id, ask.stepId) { mutableStateOf<String?>(null) }
    val name = run.name.ifBlank { hub.automations.automation(run.automationId)?.name.orEmpty() }
    fun answer(a: String) {
        if (sending != null) return
        sending = a
        scope.launch {
            hub.automations.answer(run.id, ask.stepId, a)
                .onSuccess { toast(context.getString(R.string.wf_answered_toast, a)) }
                .onFailure { e ->
                    val err = e as? AutomationError
                    // Someone else answered first, or its time ran out: said calmly, with what was taken.
                    if (err?.code == "not_asking" || err?.code == "bad_step") {
                        val taken = err.run?.steps?.firstOrNull { it.id == ask.stepId }?.answer?.text
                        toast(taken?.let { context.getString(R.string.wf_not_asking_by, it) } ?: context.getString(R.string.wf_not_asking_plain))
                    } else toast(e.message.orEmpty())
                }
            sending = null
        }
    }
    AttentionCard(
        id = "ask:${run.id}:${ask.stepId}",
        state = ask.until.toString(),
        tone = HN.amber,
        title = ask.question,
        line = name,
        modifier = modifier.semantics { contentDescription = context.getString(R.string.wf_ask_said, name, ask.question, ask.default, left) },
        glyph = Glyph.Person,
        stamp = context.getString(R.string.wf_ask_default_in, ask.default, left),
        onClick = onOpen,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((k, a) in ask.answers.withIndex()) {
                Pill(a, { answer(a) }, kind = if (k == 0) PillKind.Primary else PillKind.Outline, height = 36.dp, enabled = sending == null)
            }
        }
    }
}

/** The runs asking now, as cards: what Home and the Automations tab put above everything else. */
@Composable
fun AskCards(hub: Hub, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val asks = hub.automations.state?.asks.orEmpty()
    if (asks.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (r in asks) AskCard(hub, r, onOpen = { onOpen(r.automationId) })
    }
}
