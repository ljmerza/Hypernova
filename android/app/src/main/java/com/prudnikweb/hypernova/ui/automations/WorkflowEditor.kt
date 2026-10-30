package com.prudnikweb.hypernova.ui.automations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AutoMode
import com.prudnikweb.hypernova.data.AutomationInput
import com.prudnikweb.hypernova.data.Budget
import com.prudnikweb.hypernova.data.Features
import com.prudnikweb.hypernova.data.Guard
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Output
import com.prudnikweb.hypernova.data.Step
import com.prudnikweb.hypernova.data.Then
import com.prudnikweb.hypernova.data.Trigger
import com.prudnikweb.hypernova.data.WorkflowShape
import com.prudnikweb.hypernova.ui.components.CommandHighlight
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.components.frostedGlass
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.home.tilde
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

/** A problem shown in the workflow editor: its words, and the field inside its block to mark ("command"). */
data class FieldError(val text: String, val sub: String?)

// ─── Then: the steps ───────────────────────────────────────────────────────

/**
 * THEN: the steps as a short list of glass cards joined by a hairline, each one line (its number,
 * its kind, what it does, and its condition as a small "only if …" line) that opens to its form.
 * Steps are added (up to 5), moved and removed here; a lone step is always open.
 */
@Composable
internal fun StepsSection(
    hub: Hub,
    d: AutomationInput,
    features: Set<String>,
    errors: Map<String, FieldError>,
    template: String?,
    editable: Boolean,
    open: String?,
    onOpen: (String?) -> Unit,
    onSteps: (List<Step>) -> Unit,
    project: String?,
    builtIn: String,
) {
    val steps = d.steps.orEmpty()
    val canSteps = Features.STEPS in features
    var adding by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf<String?>(null) }
    // The first step never has a condition: one moved there loses it.
    fun settle(list: List<Step>): List<Step> = list.mapIndexed { i, s -> if (i == 0 && s.guard != null) s.copy(guard = null) else s }
    Column {
        BlockSeam(stringResource(R.string.wf_then), trail = if (steps.size > 1) pluralStringResource(R.plurals.wf_steps_n, steps.size, steps.size) else null)
        errors["steps"]?.let {
            HText(it.text, style = Type.small, color = HN.amber, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
        steps.forEachIndexed { i, s ->
            if (i > 0) Rail()
            StepCard(
                hub, d, i, s, features, errors[FieldPath.step(i)], template, editable,
                open = steps.size == 1 || open == s.id,
                lone = steps.size == 1,
                onToggle = { onOpen(if (open == s.id) null else s.id) },
                onChange = { ns -> onSteps(steps.toMutableList().also { it[i] = ns }) },
                onMove = { by ->
                    val j = i + by
                    if (j in steps.indices) onSteps(settle(steps.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }))
                },
                onRemove = { if (steps.size > 1) onSteps(settle(steps.filterIndexed { k, _ -> k != i })) },
                onKind = { changing = s.id },
            )
        }
        if (editable && canSteps && steps.size < WorkflowShape.MAX_STEPS) {
            Spacer(Modifier.height(12.dp))
            Pill(stringResource(R.string.wf_add_step), { adding = true }, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Plus)
        }
    }
    if (adding || changing != null) {
        val current = changing?.let { id -> steps.firstOrNull { it.id == id } }
        TilesSheet(
            stringResource(R.string.wf_pick_step),
            DoKind.entries.filter { it.feature == null || it.feature in features }.map { k -> Tile(k, doGlyph.getValue(k), doTitle(k)) },
            current?.action?.kind,
            onPick = { k ->
                val target = current
                adding = false
                changing = null
                if (target != null) {
                    if (k != target.action.kind) {
                        val at = steps.indexOf(target)
                        onSteps(steps.toMutableList().also { it[at] = target.copy(action = newAction(k, target.action, d.trigger, steps.take(at), project, builtIn)) })
                    }
                } else {
                    val id = WorkflowShape.newId("st_", steps.map { it.id })
                    // After a question, the next step acts on its first answer ("only if the answer is Approve").
                    val ask = steps.lastOrNull()?.action as? Action.Ask
                    val guard = ask?.answers?.firstOrNull()?.let { Guard(Guard.ANSWER, it) }
                    onSteps(steps + Step(id, guard, newAction(k, Action.Notify(""), d.trigger, steps, project, builtIn)))
                    onOpen(id)
                }
            },
            onDismiss = { adding = false; changing = null },
        )
    }
}

/** A step's action of kind [k]: what carries over from [from], a continue aimed at the session an earlier step worked in. */
private fun newAction(k: DoKind, from: Action, trigger: Trigger, before: List<Step>, project: String?, builtIn: String): Action {
    val a = defaultAction(k, from, trigger, project, builtIn)
    return when (a) {
        is Action.Start -> a.copy(then = Then.NONE)
        is Action.Continue -> {
            val earlier = before.lastOrNull { it.action is Action.Start || it.action is Action.Continue }
            a.copy(then = Then.NONE, target = earlier?.let { Action.Continue.STEP + it.id } ?: a.target)
        }
        else -> a
    }
}

/** The hairline that joins one step's card to the next, under their numbers. */
@Composable
private fun Rail() {
    Box(Modifier.padding(start = 27.dp).width(1.dp).height(12.dp).background(HN.lineStrong))
}

/** A step's number in a small glass circle (mint while it's open). */
@Composable
private fun NumberBadge(n: Int, lit: Boolean) {
    Box(Modifier.size(24.dp).clip(CircleShape).frostedGlass(if (lit) HN.accent else null, glowX = 12.dp), contentAlignment = Alignment.Center) {
        HText(n.toString(), style = Type.monoSmall, color = if (lit) HN.accent else HN.inkMuted)
    }
}

@Composable
private fun StepCard(
    hub: Hub,
    d: AutomationInput,
    i: Int,
    s: Step,
    features: Set<String>,
    error: FieldError?,
    template: String?,
    editable: Boolean,
    open: Boolean,
    lone: Boolean,
    onToggle: () -> Unit,
    onChange: (Step) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onKind: () -> Unit,
) {
    val w = rememberWords()
    val steps = d.steps.orEmpty()
    val k = s.action.kind
    val title = WorkflowWords.kindTitle(w, s.action)
    val detail = WorkflowWords.detail(w, s.action) { hub.session(it)?.name }
    val guardLine = if (i > 0 && s.guard != null) WorkflowWords.guard(w, s.guard) else null
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(Motion.DIALOG), label = "step")
    GlassCard(Modifier.fillMaxWidth(), accent = if (error != null) HN.amber else null) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = if (open) 12.dp else 4.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .then(if (!lone) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.wf_expand), onClick = onToggle) else Modifier)
                    .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(w.s(R.string.wf_step_said, i + 1, title), detail, guardLine).joinToString(". ") }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NumberBadge(i + 1, open)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AutoIcon(k?.let { doGlyph.getValue(it) } ?: AutoGlyph.Start, size = 14.dp, color = HN.accent)
                        Spacer(Modifier.width(8.dp))
                        HText(title, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!open && detail != null) {
                        HText(detail, style = if (s.action is Action.Shell) Type.monoSmall else Type.small, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Its condition, a small line of its own: "only if the step before failed".
                    if (guardLine != null) GuardLine(guardLine)
                }
                if (!lone) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Glyph.Chevron, Modifier.rotate(turn), size = 12.dp, color = HN.inkDim)
                }
            }
            AnimatedVisibility(open, enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)), exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG))) {
                Column {
                    if (editable) {
                        if (i > 0 && Features.STEPS in features) GuardRow(features, steps, i, s, error?.sub == "if") { g -> onChange(s.copy(guard = g)) }
                        StepForm(hub, d, i, s, template, error?.sub, features) { a -> onChange(s.copy(action = a)) }
                    }
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        HText(error.text, style = Type.small, color = HN.amber)
                    }
                    if (editable) StepFoot(i, steps.size, lone, onKind, onMove, onRemove)
                }
            }
        }
    }
}

/** "↳ only if the step before failed", in the accent's quiet green, under a step's title. */
@Composable
private fun GuardLine(text: String) {
    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
            val c = HN.accentDim
            val st = 1.2.dp.toPx()
            drawLine(c, androidx.compose.ui.geometry.Offset(size.width * .2f, 0f), androidx.compose.ui.geometry.Offset(size.width * .2f, size.height * .6f), st)
            drawLine(c, androidx.compose.ui.geometry.Offset(size.width * .2f, size.height * .6f), androidx.compose.ui.geometry.Offset(size.width, size.height * .6f), st)
        }
        Spacer(Modifier.width(6.dp))
        HText(text, style = Type.small, color = HN.accentDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Change, move up and down, remove: a quiet row at the foot of an open step. */
@Composable
private fun StepFoot(i: Int, count: Int, lone: Boolean, onKind: () -> Unit, onMove: (Int) -> Unit, onRemove: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Hairline(Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Pill(stringResource(R.string.au_change), onKind, kind = PillKind.Ghost, height = 32.dp)
        Spacer(Modifier.weight(1f))
        if (!lone) {
            if (i > 0) MoveButton(up = true, stringResource(R.string.wf_move_up)) { onMove(-1) }
            if (i < count - 1) MoveButton(up = false, stringResource(R.string.wf_move_down)) { onMove(1) }
            Spacer(Modifier.width(4.dp))
            Pill(stringResource(R.string.wf_remove), onRemove, kind = PillKind.Ghost, height = 32.dp)
        }
    }
}

@Composable
private fun MoveButton(up: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Glyph.Up, Modifier.rotate(if (up) 0f else 180f), size = 14.dp, color = HN.inkMuted)
    }
}

/** A step's condition: the row, a sheet of the conditions it can have, and the words to look for. */
@Composable
private fun GuardRow(features: Set<String>, steps: List<Step>, i: Int, s: Step, error: Boolean, onChange: (Guard?) -> Unit) {
    val w = rememberWords()
    var pick by remember { mutableStateOf(false) }
    val now = s.guard
    PickRow(stringResource(R.string.wf_condition), WorkflowWords.guard(w, now).replaceFirstChar { it.uppercaseChar() }, { pick = true })
    if (now?.on == Guard.CONTAINS) {
        Field(
            now.text.orEmpty(), { onChange(now.copy(text = it.take(WorkflowShape.GUARD_TEXT_MAX))) },
            placeholder = stringResource(R.string.wf_g_words), label = stringResource(R.string.wf_g_words), error = error,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
        )
        HText(stringResource(R.string.wf_g_words_note), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
    }
    Hairline(Modifier.fillMaxWidth().padding(vertical = 4.dp))
    if (pick) {
        val options = GuardOptions.offered(features, steps, i)
        val selected = options.firstOrNull { o -> o?.on == now?.on && (o?.on != Guard.ANSWER || o.text.equals(now?.text, true)) }
        ChoiceSheet(
            stringResource(R.string.wf_pick_condition),
            options.map { g -> Choice(g, WorkflowWords.guard(w, g).replaceFirstChar { it.uppercaseChar() }) },
            selected,
            onPick = { g ->
                pick = false
                // "Said certain words" keeps the words typed before.
                onChange(if (g?.on == Guard.CONTAINS) Guard(Guard.CONTAINS, now?.text?.takeIf { now.on == Guard.CONTAINS }.orEmpty()) else g)
            },
            onDismiss = { pick = false },
        )
    }
}

/** A step's own compact form, by its kind. */
@Composable
private fun StepForm(hub: Hub, d: AutomationInput, i: Int, s: Step, template: String?, sub: String?, features: Set<String>, onChange: (Action) -> Unit) {
    val steps = d.steps.orEmpty()
    when (val a = s.action) {
        is Action.Start -> {
            val chips = Placeholders.offered(d.trigger, steps, i, Placeholders.Where.Prompt, bypass = a.permissionMode == AutoMode.NO_ASKING, features = features)
            StartParams(hub, a, template.takeIf { i == 0 }, onChange, withThen = false, chips = chips, errorField = sub)
            StepPermissions(a, sub, onChange)
        }
        is Action.Continue -> {
            val earlier = steps.take(i).mapIndexedNotNull { k, b -> if (b.action is Action.Start || b.action is Action.Continue) b.id to (k + 1) else null }
            ContinueParams(hub, a, d.trigger, onChange, withThen = false, earlier = earlier, chips = Placeholders.offered(d.trigger, steps, i, Placeholders.Where.Prompt, features = features), errorField = sub)
        }
        is Action.Ask -> AskParams(a, Placeholders.offered(d.trigger, steps, i, Placeholders.Where.Question, features = features), sub, onChange)
        is Action.Shell -> ShellParams(hub, a, Placeholders.offered(d.trigger, steps, i, Placeholders.Where.Shell), Placeholders.env(steps, i, features), sub, onChange)
        is Action.Notify -> {
            TextParams(a.text, d.trigger, offered = Placeholders.offered(d.trigger, steps, i, Placeholders.Where.Text, features = features), error = sub == "text") { onChange(a.copy(text = it)) }
            NotifyBlocked()
        }
        is Action.Unknown -> Unit
    }
}

/**
 * A start step's permissions, folded to one row ("Permissions   Allowed only, 3 rules ›") that
 * opens the five modes in place, so a list of steps stays short.
 */
@Composable
private fun StepPermissions(a: Action.Start, sub: String?, onChange: (Action) -> Unit) {
    val marked = sub == "permissionMode" || sub == "allowedTools"
    var shown by remember { mutableStateOf(marked) }
    val rules = a.allowedTools.size.takeIf { it > 0 && Rules.kept(a.permissionMode) }
    val value = modeTitle(a.permissionMode) + (rules?.let { ", " + pluralStringResource(R.plurals.wf_rules_n, it, it) } ?: "")
    Spacer(Modifier.height(4.dp))
    PickRow(stringResource(R.string.au_permissions), value, { shown = !shown })
    AnimatedVisibility(shown || marked) {
        PermissionsBlock(a, null, editable = true, framed = false, onChange = onChange)
    }
}

// ─── Ask me ────────────────────────────────────────────────────────────────

/** The question, its 2 or 3 answers, the one taken when nobody answers, and how long it waits. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskParams(a: Action.Ask, chips: List<String>, sub: String?, onChange: (Action) -> Unit) {
    var more by remember { mutableStateOf("") }
    Spacer(Modifier.height(6.dp))
    ChipsText(
        a.question, { onChange(a.copy(question = it.take(WorkflowShape.ASK_QUESTION_MAX))) }, chips,
        placeholder = stringResource(R.string.wf_question_hint), label = stringResource(R.string.wf_question), minLines = 1, maxLines = 3, error = sub == "question",
    )
    ParamLabel(stringResource(R.string.wf_answers))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        for (answer in a.answers) {
            val removable = a.answers.size > 2
            RuleChip(answer, stringResource(R.string.wf_remove_answer, answer), trailing = if (removable) Glyph.Cross else null, onClick = if (removable) ({
                val rest = a.answers - answer
                onChange(a.copy(answers = rest, default = a.default.takeIf { it in rest } ?: rest.last()))
            }) else null)
        }
    }
    if (a.answers.size < 3) {
        Spacer(Modifier.height(8.dp))
        fun add() {
            val v = more.trim().take(WorkflowShape.ASK_ANSWER_MAX)
            if (v.isEmpty() || a.answers.any { it.equals(v, ignoreCase = true) }) return
            onChange(a.copy(answers = a.answers + v))
            more = ""
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                Field(more, { more = it.take(WorkflowShape.ASK_ANSWER_MAX) }, placeholder = stringResource(R.string.wf_answer_hint), label = stringResource(R.string.wf_answer_hint), error = sub == "answers", onDone = ::add,
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done))
            }
            Spacer(Modifier.width(8.dp))
            Pill(stringResource(R.string.wf_add_answer), ::add, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Plus)
        }
    }
    ParamLabel(stringResource(R.string.wf_default))
    Segmented(a.answers.map { it to it }, a.default, { onChange(a.copy(default = it)) })
    ParamLabel(stringResource(R.string.wf_waits))
    val waits = (listOf(5, 10, 30, 60) + a.timeoutMin).distinct().sorted()
    Segmented(waits.map { it to stringResource(R.string.wf_minutes, it) }, a.timeoutMin, { onChange(a.copy(timeoutMin = it)) })
    Spacer(Modifier.height(10.dp))
    HText(stringResource(R.string.wf_ask_note), style = Type.small, color = HN.inkDim)
}

// ─── Run a shell command ───────────────────────────────────────────────────

/** Its folder, the command (only the safe placeholders, the outside words as its environment), and its time. */
@Composable
private fun ShellParams(hub: Hub, a: Action.Shell, chips: List<String>, env: List<String>, sub: String?, onChange: (Action) -> Unit) {
    var pick by remember { mutableStateOf(false) }
    val p = hub.projects.firstOrNull { it.path == a.projectPath }
    PickRow(stringResource(R.string.au_project), p?.name ?: a.projectPath.substringAfterLast('/').ifEmpty { stringResource(R.string.au_err_project_needed) }, { pick = true }, sub = a.projectPath.takeIf { it.isNotEmpty() }?.let(::tilde))
    Spacer(Modifier.height(6.dp))
    ChipsText(
        a.command, { onChange(a.copy(command = it.take(WorkflowShape.SHELL_COMMAND_MAX))) }, chips,
        placeholder = stringResource(R.string.wf_command_hint), label = stringResource(R.string.wf_command), minLines = 2, maxLines = 6,
        mono = true, env = env, error = sub == "command",
    )
    ParamLabel(stringResource(R.string.wf_timeout))
    val times = (listOf(30, 60, 300, 600) + a.timeoutSec).distinct().sorted()
    Segmented(times.map { it to if (it % 60 == 0) stringResource(R.string.wf_minutes, it / 60) else stringResource(R.string.wf_seconds, it) }, a.timeoutSec, { onChange(a.copy(timeoutSec = it)) })
    Spacer(Modifier.height(10.dp))
    HText(stringResource(R.string.wf_shell_note), style = Type.small, color = HN.inkDim)
    Spacer(Modifier.height(6.dp))
    HText(stringResource(R.string.wf_shell_fingerprint), style = Type.small, color = HN.amber)
    if (pick) {
        ChoiceSheet(
            stringResource(R.string.au_project),
            hub.projects.map { Choice(it.path, it.name, tilde(it.path)) },
            a.projectPath,
            onPick = { path -> pick = false; onChange(a.copy(projectPath = path)) },
            onDismiss = { pick = false },
            empty = stringResource(R.string.au_no_projects),
        )
    }
}

// ─── Placeholders as chips ─────────────────────────────────────────────────

/**
 * The workflow's steps by number, for its chips: `{step:<id>}` and `"$HYPERNOVA_STEP_<id>"` read
 * "Step 2's result" (the editor provides it over the whole draft).
 */
internal val LocalStepNumbers = staticCompositionLocalOf<Map<String, Int>> { emptyMap() }

/** Each step's two chips (its `{step:<id>}` and its variable) to its number, from 1. */
internal fun stepNumbers(steps: List<Step>): Map<String, Int> = buildMap {
    steps.forEachIndexed { k, s ->
        put(WorkflowShape.stepRef(s.id), k + 1)
        put(WorkflowShape.stepEnv(s.id), k + 1)
    }
}

/**
 * A text box that keeps its cursor, with the placeholders it can hold as chips under it: a tap puts
 * one at the cursor, and one line says what the last one touched fills in with. [env]: a shell
 * command's environment variables, as chips of their own.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipsText(
    text: String,
    onChange: (String) -> Unit,
    chips: List<String>,
    placeholder: String,
    label: String,
    modifier: Modifier = Modifier,
    minLines: Int = 2,
    maxLines: Int = 6,
    mono: Boolean = false,
    commands: Boolean = false,
    error: Boolean = false,
    env: List<String> = emptyList(),
) {
    val w = rememberWords()
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    var said by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(14.dp)
    val style = (if (mono) Type.mono.copy(fontSize = Type.body.fontSize, lineHeight = Type.body.lineHeight) else Type.body).copy(color = HN.ink)
    BasicTextField(
        value, { v -> value = v; if (v.text != text) onChange(v.text) },
        textStyle = style,
        cursorBrush = SolidColor(HN.accent),
        minLines = minLines, maxLines = maxLines,
        visualTransformation = if (commands) CommandHighlight else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (mono) KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false) else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(HN.bg800.copy(alpha = 0.6f))
                    .border(1.dp, if (error) HN.danger.copy(alpha = 0.6f) else HN.ink.copy(alpha = 0.10f), shape).padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.text.isEmpty()) HText(placeholder, style = style, color = HN.inkGhost, maxLines = maxLines)
                inner()
            }
        },
    )
    fun insert(token: String) {
        val (next, at) = Placeholders.insert(value.text, value.selection.min, value.selection.max, token)
        value = TextFieldValue(next, TextRange(at))
        said = token
        onChange(next)
    }
    if (chips.isEmpty() && env.isEmpty()) return
    // An earlier step's chips read "Step 2's result", whatever its id.
    val numbers = LocalStepNumbers.current
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in chips + env) {
            val name = WorkflowWords.chip(w, p, numbers[p])
            RuleChip(name, stringResource(R.string.au_insert, name), leading = Glyph.Plus, onClick = { insert(p) })
        }
    }
    // One line on what the last chip touched fills in with (the first one's, to begin with).
    val shown = said ?: chips.firstOrNull { it == "{prev}" } ?: chips.firstOrNull() ?: env.first()
    HText(WorkflowWords.placeholder(w, shown, numbers[shown]), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 6.dp, start = 2.dp), maxLines = 2)
}

// ─── Tell: the outputs ─────────────────────────────────────────────────────

/** The output kinds TELL offers: the phone always; a webhook and a file as the Mac lists them. */
private enum class OutKind(val feature: String? = null) { Notify, Webhook(Features.O_WEBHOOK), File(Features.O_FILE) }

private fun Output.outKind(): OutKind? = when (this) {
    is Output.Notify -> OutKind.Notify
    is Output.Webhook -> OutKind.Webhook
    is Output.File -> OutKind.File
    is Output.Unknown -> null
}

private val outGlyph = mapOf(OutKind.Notify to AutoGlyph.Bell, OutKind.Webhook to AutoGlyph.Webhook, OutKind.File to AutoGlyph.Page)

/**
 * TELL: where the result goes when a run ends done (0 to 2): notify, a webhook (ntfy,
 * Slack, Discord, Telegram or a plain POST; its secret typed once, then only its host and last
 * four characters), or a line in a file inside the project.
 */
@Composable
internal fun OutputsSection(
    hub: Hub,
    d: AutomationInput,
    features: Set<String>,
    errors: Map<String, FieldError>,
    editable: Boolean,
    open: String?,
    onOpen: (String?) -> Unit,
    onOutputs: (List<Output>) -> Unit,
    project: String?,
) {
    val w = rememberWords()
    val outputs = d.outputs
    var adding by remember { mutableStateOf(false) }
    Column {
        BlockSeam(stringResource(R.string.wf_tell))
        errors["outputs"]?.let { HText(it.text, style = Type.small, color = HN.amber, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)) }
        if (outputs.isEmpty()) HText(stringResource(R.string.wf_tell_none), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp))
        outputs.forEachIndexed { i, o ->
            if (i > 0) Spacer(Modifier.height(10.dp))
            OutputCard(
                hub, d, o, errors[FieldPath.output(i)], editable, open == o.id, w, features,
                onToggle = { onOpen(if (open == o.id) null else o.id) },
                onChange = { n -> onOutputs(outputs.toMutableList().also { it[i] = n }) },
                onRemove = { onOutputs(outputs.filterIndexed { k, _ -> k != i }) },
            )
        }
        if (editable && outputs.size < WorkflowShape.MAX_OUTPUTS) {
            Spacer(Modifier.height(12.dp))
            Pill(stringResource(R.string.wf_add_output), { adding = true }, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Plus)
        }
    }
    if (adding) {
        val titles = mapOf(
            OutKind.Notify to stringResource(R.string.wf_ot_notify),
            OutKind.Webhook to stringResource(R.string.wf_ot_webhook), OutKind.File to stringResource(R.string.wf_ot_file),
        )
        TilesSheet(
            stringResource(R.string.wf_pick_output),
            OutKind.entries.filter { it.feature == null || it.feature in features }.map { k -> Tile(k, outGlyph.getValue(k), titles.getValue(k)) },
            null,
            onPick = { k ->
                adding = false
                val id = WorkflowShape.newId("ou_", outputs.map { it.id })
                val o = when (k) {
                    OutKind.Notify -> Output.Notify(id)
                    OutKind.Webhook -> Output.Webhook(id, preset = Output.SLACK)
                    OutKind.File -> Output.File(id, path = project?.let { "${it.trimEnd('/')}/${w.s(R.string.wf_t_digest_file)}" }.orEmpty())
                }
                onOutputs(outputs + o)
                onOpen(id)
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun OutputCard(
    hub: Hub,
    d: AutomationInput,
    o: Output,
    error: FieldError?,
    editable: Boolean,
    open: Boolean,
    w: Words,
    features: Set<String>,
    onToggle: () -> Unit,
    onChange: (Output) -> Unit,
    onRemove: () -> Unit,
) {
    val k = o.outKind()
    val name = WorkflowWords.outputName(w, o, "")
    val line = when (o) {
        is Output.Webhook -> null
        is Output.File -> tilde(o.path)
        else -> o.text ?: stringResource(R.string.wf_o_result)
    }
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(Motion.DIALOG), label = "out")
    GlassCard(Modifier.fillMaxWidth(), accent = if (error != null) HN.amber else null) {
        Column(Modifier.padding(start = 16.dp, end = 14.dp, top = 4.dp, bottom = if (open) 12.dp else 4.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.wf_expand), onClick = onToggle)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoIcon(k?.let { outGlyph.getValue(it) } ?: AutoGlyph.Bell, size = 16.dp, color = HN.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    HText(name, style = Type.body, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (o is Output.Webhook) SecretLine(o)
                    else if (line != null && !open) HText(line, style = if (o is Output.File) Type.monoSmall else Type.small, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Glyph.Chevron, Modifier.rotate(turn), size = 12.dp, color = HN.inkDim)
            }
            AnimatedVisibility(open, enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)), exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG))) {
                Column {
                    if (editable) OutputForm(hub, d, o, error?.sub, features, onChange)
                    if (error != null) {
                        Spacer(Modifier.height(8.dp))
                        HText(error.text, style = Type.small, color = HN.amber)
                    }
                    if (editable) {
                        Spacer(Modifier.height(12.dp))
                        Hairline(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                            Pill(stringResource(R.string.wf_remove), onRemove, kind = PillKind.Ghost, height = 32.dp)
                        }
                    }
                }
            }
        }
    }
}

/** A webhook's kept secret, drawn: its host in mono and a chip with its last four ("hooks.slack.com  ••••Xq7Z"). */
@Composable
private fun SecretLine(o: Output.Webhook) {
    val w = rememberWords()
    if (!o.kept && !o.typed) return
    val said = if (o.kept && !o.typed) stringResource(R.string.wf_secret_said, o.host, o.last4) else null
    Row(Modifier.padding(top = 2.dp).then(if (said != null) Modifier.semantics(mergeDescendants = true) { contentDescription = said } else Modifier), verticalAlignment = Alignment.CenterVertically) {
        val host = if (o.typed) o.url?.trim()?.substringAfter("://")?.substringBefore('/')?.ifEmpty { null } ?: Summary.presetName(w, o.preset) else o.host
        HText(host, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (o.kept && !o.typed) {
            Spacer(Modifier.width(8.dp))
            KeyChip(o.last4)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutputForm(hub: Hub, d: AutomationInput, o: Output, sub: String?, features: Set<String>, onChange: (Output) -> Unit) {
    val steps = d.steps.orEmpty()
    val chips = Placeholders.offered(d.trigger, steps, steps.size, Placeholders.Where.Output, features = features)
    fun text(t: String): String? = t.takeIf { it.isNotBlank() }?.take(WorkflowShape.OUTPUT_TEXT_MAX)
    when (o) {
        is Output.Notify -> {
            ChipsText(o.text.orEmpty(), { onChange(o.copy(text = text(it))) }, chips, placeholder = stringResource(R.string.wf_o_text_hint), label = stringResource(R.string.au_text), error = sub == "text")
            NotifyBlocked()
        }
        is Output.Webhook -> {
            ParamLabel(stringResource(R.string.wf_service))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (p in Output.PRESETS) {
                    val label = if (p == Output.POST) stringResource(R.string.wf_p_post) else Summary.presetName(rememberWords(), p)
                    // Another service needs its own address: what was kept for the last one won't do.
                    ToggleChip(label, o.preset == p, { if (it && p != o.preset) onChange(o.copy(preset = p, url = null, botToken = null, chatId = null, last4 = "", host = "")) })
                }
            }
            WebhookSecret(o, sub, onChange)
            ParamLabel(stringResource(R.string.au_text))
            ChipsText(o.text.orEmpty(), { onChange(o.copy(text = text(it))) }, chips, placeholder = stringResource(R.string.wf_o_text_hint), label = stringResource(R.string.au_text), error = sub == "text")
        }
        is Output.File -> FileOutputParams(hub, o, chips, sub, onChange)
        is Output.Unknown -> Unit
    }
}

/**
 * The address (or Telegram's bot token and chat), typed once: kept on the Mac, it shows as its host
 * and last four with Replace; never shown back.
 */
@Composable
private fun WebhookSecret(o: Output.Webhook, sub: String?, onChange: (Output) -> Unit) {
    var replacing by remember(o.id) { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    if (o.kept && !replacing && !o.typed) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SecretLine(o) }
            Pill(stringResource(R.string.wf_replace), { replacing = true }, kind = PillKind.Outline, height = 36.dp)
        }
        HText(stringResource(R.string.wf_secret_note), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 6.dp))
        return
    }
    if (o.preset == Output.TELEGRAM) {
        Field(o.botToken.orEmpty(), { onChange(o.copy(botToken = it.trim())) }, placeholder = stringResource(R.string.wf_bot_token_hint), label = stringResource(R.string.wf_bot_token), mono = true, secret = true, error = sub == "botToken",
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
        Spacer(Modifier.height(8.dp))
        Field(o.chatId.orEmpty(), { onChange(o.copy(chatId = it.trim())) }, placeholder = stringResource(R.string.wf_chat_hint), label = stringResource(R.string.wf_chat), mono = true, error = sub == "chatId",
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
        HText(stringResource(R.string.wf_tg_note), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 6.dp))
    } else {
        val hint = when (o.preset) {
            Output.NTFY -> R.string.wf_url_hint_ntfy
            Output.SLACK -> R.string.wf_url_hint_slack
            Output.DISCORD -> R.string.wf_url_hint_discord
            else -> R.string.wf_url_hint_post
        }
        val note = when (o.preset) {
            Output.NTFY -> R.string.wf_url_note_ntfy
            Output.SLACK -> R.string.wf_url_note_slack
            Output.DISCORD -> R.string.wf_url_note_discord
            else -> R.string.wf_url_note_post
        }
        Field(o.url.orEmpty(), { onChange(o.copy(url = it.trim())) }, placeholder = stringResource(hint), label = stringResource(R.string.wf_url), mono = true, error = sub == "url",
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
        HText(stringResource(note), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 6.dp))
    }
    HText(stringResource(R.string.wf_secret_note), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 2.dp))
}

/** A file inside a project: the project, then the file's path in it; each run adds one line. */
@Composable
private fun FileOutputParams(hub: Hub, o: Output.File, chips: List<String>, sub: String?, onChange: (Output) -> Unit) {
    var pick by remember { mutableStateOf(false) }
    val proj = hub.projects.filter { o.path.startsWith(it.path.trimEnd('/') + "/") }.maxByOrNull { it.path.length }
    val base = proj?.path?.trimEnd('/') ?: o.path.substringBeforeLast('/', "")
    val rel = if (base.isEmpty()) o.path else o.path.removePrefix("$base/")
    PickRow(stringResource(R.string.au_project), proj?.name ?: base.substringAfterLast('/').ifEmpty { stringResource(R.string.au_err_project_needed) }, { pick = true }, sub = base.takeIf { it.isNotEmpty() }?.let(::tilde))
    Spacer(Modifier.height(6.dp))
    Field(rel, { v -> onChange(o.copy(path = if (base.isEmpty()) v.trim() else "$base/${v.trim().trimStart('/')}")) }, placeholder = stringResource(R.string.wf_file_hint), label = stringResource(R.string.wf_file), mono = true, error = sub == "path",
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
    HText(stringResource(R.string.wf_file_note), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 6.dp))
    ParamLabel(stringResource(R.string.au_text))
    ChipsText(o.text.orEmpty(), { onChange(o.copy(text = it.takeIf { t -> t.isNotBlank() }?.take(WorkflowShape.OUTPUT_TEXT_MAX))) }, chips, placeholder = stringResource(R.string.wf_o_text_hint), label = stringResource(R.string.au_text), error = sub == "text")
    if (pick) {
        ChoiceSheet(
            stringResource(R.string.au_project),
            hub.projects.map { Choice(it.path, it.name, tilde(it.path)) },
            proj?.path,
            onPick = { path -> pick = false; onChange(o.copy(path = "${path.trimEnd('/')}/${rel.trimStart('/').ifEmpty { "notes.md" }}")) },
            onDismiss = { pick = false },
            empty = stringResource(R.string.au_no_projects),
        )
    }
}

/**
 * A secret's last four characters after four dots ("••••9f3c"), in mono and in its own case (a tag
 * chip's caps would change it): what the Mac keeps, never the secret.
 */
@Composable
internal fun KeyChip(last4: String, modifier: Modifier = Modifier) {
    Box(modifier.frostedGlass(null, glowX = 0.dp).heightIn(min = 22.dp).padding(horizontal = 8.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        HText("••••$last4", style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
    }
}

// ─── Budget ────────────────────────────────────────────────────────────────

/** BUDGET: a switch, then how long a run may work and how much of the 5-hour window it may spend. */
@Composable
internal fun BudgetSection(budget: Budget?, error: String?, editable: Boolean, onChange: (Budget?) -> Unit) {
    Column {
        BlockSeam(stringResource(R.string.wf_budget))
        GlassCard(Modifier.fillMaxWidth(), accent = if (error != null) HN.amber else null) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                ToggleRow(stringResource(R.string.wf_budget_on), stringResource(R.string.wf_budget_body), budget != null, { on -> if (editable) onChange(if (on) Budget(30, null) else null) })
                if (budget != null) {
                    val off = stringResource(R.string.wf_off)
                    ParamLabel(stringResource(R.string.wf_budget_minutes), Modifier.padding(top = 0.dp))
                    val minutes = (listOf(15, 30, 60, 120) + listOfNotNull(budget.minutes)).distinct().sorted()
                    Segmented(
                        listOf<Pair<Int?, String>>(null to off) + minutes.map { it to if (it % 60 == 0) stringResource(R.string.wf_hours, it / 60) else stringResource(R.string.wf_min, it) },
                        budget.minutes, { m -> onChange(Budget(m, budget.windowPct).takeIf { it.any }) },
                    )
                    ParamLabel(stringResource(R.string.wf_budget_pct))
                    val pcts = (listOf(10, 25, 50) + listOfNotNull(budget.windowPct)).distinct().sorted()
                    Segmented(
                        listOf<Pair<Int?, String>>(null to off) + pcts.map { it to stringResource(R.string.wf_pct, it) },
                        budget.windowPct, { p -> onChange(Budget(budget.minutes, p).takeIf { it.any }) },
                    )
                    Spacer(Modifier.height(10.dp))
                }
                if (error != null) {
                    HText(error, style = Type.small, color = HN.amber)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
