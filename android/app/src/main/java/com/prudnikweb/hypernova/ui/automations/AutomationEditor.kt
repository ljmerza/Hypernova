package com.prudnikweb.hypernova.ui.automations

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.AutoMode
import com.prudnikweb.hypernova.data.Automation
import com.prudnikweb.hypernova.data.AutomationError
import com.prudnikweb.hypernova.data.AutomationErrors
import com.prudnikweb.hypernova.data.AutomationInput
import com.prudnikweb.hypernova.data.AutomationWire
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Features
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.data.Then
import com.prudnikweb.hypernova.data.Trigger
import com.prudnikweb.hypernova.data.input
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Incidents
import com.prudnikweb.hypernova.ui.components.OwnerCheck
import com.prudnikweb.hypernova.data.flow
import com.prudnikweb.hypernova.data.flowSteps
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Radio
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.components.ThinkTwiceCard
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.home.tilde
import com.prudnikweb.hypernova.ui.session.CommandHint
import com.prudnikweb.hypernova.ui.session.CommandSuggestions
import com.prudnikweb.hypernova.ui.session.commandQuery
import com.prudnikweb.hypernova.ui.session.picked
import com.prudnikweb.hypernova.ui.session.rememberCommands
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What fires it. The friendly ones first; the developers' (cron, webhook, files, GitHub, hooks)
 * after them, offered only when the Mac lists them ([features]).
 */
internal enum class WhenKind(val feature: String? = null) {
    Time, Every, Reset, Limit, Finished, Needs, Plugged, Battery,
    Cron(Features.T_CRON), Webhook(Features.T_WEBHOOK), File(Features.T_FILE), Github(Features.T_GITHUB), Hook(Features.T_HOOK),
}

/** What a step (or today's one action) does; Ask and Shell only as a workflow's steps the Mac lists. */
internal enum class DoKind(val feature: String? = null) { Start, Skill, Continue, Ask(Features.ASK), Shell(Features.SHELL), Notify }

internal val Trigger.kind: WhenKind?
    get() = when (this) {
        is Trigger.Time, is Trigger.Once -> WhenKind.Time
        is Trigger.Every -> WhenKind.Every
        is Trigger.Reset -> WhenKind.Reset
        is Trigger.Limit -> WhenKind.Limit
        is Trigger.SessionEvent -> if (event == Trigger.NEEDS_YOU) WhenKind.Needs else WhenKind.Finished
        Trigger.Plugged -> WhenKind.Plugged
        is Trigger.BatteryLow -> WhenKind.Battery
        is Trigger.Cron -> WhenKind.Cron
        is Trigger.Webhook -> WhenKind.Webhook
        is Trigger.FileWatch -> WhenKind.File
        is Trigger.Github -> WhenKind.Github
        is Trigger.Hook -> WhenKind.Hook
        is Trigger.Unknown -> null
    }

internal val Action.kind: DoKind?
    get() = when (this) {
        is Action.Start -> if (skill) DoKind.Skill else DoKind.Start
        is Action.Continue -> DoKind.Continue
        is Action.Ask -> DoKind.Ask
        is Action.Shell -> DoKind.Shell
        is Action.Notify -> DoKind.Notify
        is Action.Unknown -> null
    }

internal val whenGlyph = mapOf(
    WhenKind.Time to AutoGlyph.Clock, WhenKind.Every to AutoGlyph.Repeat, WhenKind.Reset to AutoGlyph.Reset, WhenKind.Limit to AutoGlyph.Limit,
    WhenKind.Finished to AutoGlyph.Finished, WhenKind.Needs to AutoGlyph.NeedsMe, WhenKind.Plugged to AutoGlyph.Plug, WhenKind.Battery to AutoGlyph.Battery,
    WhenKind.Cron to AutoGlyph.Cron, WhenKind.Webhook to AutoGlyph.Webhook, WhenKind.File to AutoGlyph.FileWatch, WhenKind.Github to AutoGlyph.Branch, WhenKind.Hook to AutoGlyph.Hook,
)
internal val doGlyph = mapOf(
    DoKind.Start to AutoGlyph.Start, DoKind.Skill to AutoGlyph.Skill, DoKind.Continue to AutoGlyph.Continue, DoKind.Ask to AutoGlyph.Ask, DoKind.Shell to AutoGlyph.Shell,
    DoKind.Notify to AutoGlyph.Bell,
)

@Composable
internal fun whenTitle(k: WhenKind): String = stringResource(
    when (k) {
        WhenKind.Time -> R.string.au_w_time
        WhenKind.Every -> R.string.au_w_every
        WhenKind.Reset -> R.string.au_w_reset
        WhenKind.Limit -> R.string.au_w_limit
        WhenKind.Finished -> R.string.au_w_finished
        WhenKind.Needs -> R.string.au_w_needs
        WhenKind.Plugged -> R.string.au_w_plugged
        WhenKind.Battery -> R.string.au_w_battery
        WhenKind.Cron -> R.string.wf_w_cron
        WhenKind.Webhook -> R.string.wf_w_webhook
        WhenKind.File -> R.string.wf_w_file
        WhenKind.Github -> R.string.wf_w_github
        WhenKind.Hook -> R.string.wf_w_hook
    },
)

@Composable
internal fun doTitle(k: DoKind): String = stringResource(
    when (k) {
        DoKind.Start -> R.string.au_d_start
        DoKind.Skill -> R.string.au_d_skill
        DoKind.Continue -> R.string.au_d_continue
        DoKind.Ask -> R.string.wf_d_ask
        DoKind.Shell -> R.string.wf_d_shell
        DoKind.Notify -> R.string.au_d_notify
    },
)

/** The draft survives a rotation as the JSON the Mac would get (a webhook's typed secret left out). */
private val DraftSaver = Saver<AutomationInput, String>(
    save = { AutomationWire.draft(it).toString() },
    restore = { runCatching { AutomationWire.input(JSONObject(it)) }.getOrNull() },
)

/**
 * An automation, new ([id] null, maybe from a [template]) or saved: the sentence it reads as, WHEN,
 * DO, PERMISSIONS (for a session it starts), its name, and for a saved one its runs and Delete.
 * Run now only for a saved one with nothing unsaved; Save is the one primary action. [toRuns]
 * opens it scrolled to RUNS (from History).
 *
 * On a Mac with workflows (it lists `features`, even none), DO and PERMISSIONS give way to a short
 * list, not a canvas: THEN (1 to 5 steps, each with its condition), TELL (0 to 2 outputs) and,
 * when offered, BUDGET (ui/automations/WorkflowEditor.kt). What the Mac doesn't list isn't offered.
 */
@Composable
fun AutomationEditor(
    hub: Hub,
    id: String?,
    template: String?,
    toRuns: Boolean,
    templateProject: String?,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val res = context.resources
    val autos = hub.automations
    val st = autos.state
    val saved = id?.let { autos.automation(it) }
    val scope = LocalAppScope.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val w = rememberWords()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis(), saved?.lastRun?.active) {
        while (true) { value = System.currentTimeMillis(); delay(if (saved?.lastRun?.active == true) 1000 else 30_000) }
    }
    val ctx = summaryContext(hub, now)
    val project = templateProject ?: hub.projects.firstOrNull()?.path
    val builtIn = hub.providers.firstOrNull { it.builtIn }?.id ?: "anthropic"
    // Where a new start step goes: Claude Code's built-in provider, or Codex where Claude Code isn't
    // installed (data/AgentLead.kt).
    val codexLeads = com.prudnikweb.hypernova.data.AgentLead.codexLeads(hub.claude, hub.codex)
    val startOn = if (codexLeads) AgentKind.CODEX_PROVIDER else builtIn
    // What the Mac can do of workflows; null: today's one-action editor, exactly as before.
    val features = st?.features
    val flow = features != null
    // A saved one that isn't here: the list is still coming, or it was deleted (from History or a
    // session's AUTO line). Never a blank form whose Save fails (A2).
    if (id != null && saved == null) {
        GoneAutomation(hub, id, loading = st == null, onBack = onBack, onOpenSession = onOpenSession)
        return
    }
    // Out of reach: what's here can be read and changed, but Save and Run now wait (A15).
    val offline = hub.link.state != com.prudnikweb.hypernova.data.LinkState.Connected
    // A spent Once switched on from its card: moved to the next day at that time, to save (A6).
    val again = remember(id) { OnceAgain.take(id) }

    val baseInput = remember(id, template) {
        val i = saved?.input()
            // The limit templates go by the 5-hour window only while Claude Code reports one (2026-10-31).
            ?: template?.let { Templates.input(it, res, project, hub.providers, if (hub.usage.fiveHourReported) Trigger.FIVE_HOUR else Trigger.SEVEN_DAY)?.let { t -> if (codexLeads) movedToCodex(t) else t } }
            ?: AutomationInput("", true, false, Trigger.Time("09:00", Trigger.WEEKDAYS), newStart(project.orEmpty(), "", startOn, Then.NOTIFY), null)
        shaped(i, flow)
    }
    val initial = remember(id, template) {
        val once = baseInput.trigger as? Trigger.Once
        if (again && once != null) baseInput.copy(trigger = nextOnce(once, System.currentTimeMillis(), ctx.zone), enabled = true) else baseInput
    }
    var baseline by rememberSaveable(id, template, stateSaver = DraftSaver) { mutableStateOf(baseInput) }
    var draft by rememberSaveable(id, template, stateSaver = DraftSaver) { mutableStateOf(initial) }
    if (again) LaunchedEffect(Unit) {
        (draft.trigger as? Trigger.Once)?.let { toast(res.getString(R.string.au_once_moved, Summary.once(w, it.at, ctx))) }
    }
    // A name filled from the prompt follows it until it's typed over (a template's stays).
    var nameEdited by rememberSaveable(id, template) { mutableStateOf(saved != null || template != null) }
    val dirty = draft != baseline
    var busy by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    var serverErrors by remember { mutableStateOf<Map<AutomationErrors.Block, String>>(emptyMap()) }
    // A workflow's refusals, where the Mac's `field` puts them ("steps[2]" and its "command").
    var flowServer by remember { mutableStateOf<Map<String, FieldError>>(emptyMap()) }
    // The step and the output open for editing (one of each; a lone step is always open).
    var openStep by rememberSaveable(id, template) { mutableStateOf(initial.steps?.firstOrNull()?.id?.takeIf { saved == null }) }
    var openOutput by rememberSaveable(id, template) { mutableStateOf<String?>(null) }
    var discardOpen by remember { mutableStateOf(false) }
    val editable = flowEditable(draft)
    val zone = ctx.zone

    fun update(f: (AutomationInput) -> AutomationInput) {
        var next = f(draft)
        if (!nameEdited) next = next.copy(name = autoName(next.flowSteps.first().action))
        draft = shaped(next, flow)
        serverErrors = emptyMap()
        flowServer = emptyMap()
    }

    val problems = if (flow) emptyMap() else validate(draft, baseline, now, zone, res)
    val flowProblems = if (!flow) emptyMap() else flowValidate(draft, baseline, features, now, w, res)
    val errors = (if (tried) problems else emptyMap()) + serverErrors
    val flowErrors = (if (tried) flowProblems else emptyMap()) + flowServer
    val blocked = if (flow) flowProblems.isNotEmpty() else problems.isNotEmpty()

    fun leave() { if (dirty && editable) discardOpen = true else onBack() }
    BackHandler(enabled = dirty && editable && !discardOpen) { discardOpen = true }

    val list = rememberLazyListState()
    /** Opens the step or output a problem is about, and brings its block into view. */
    fun reveal(key: String) {
        FieldPath.index(key)?.let { i ->
            if (key.startsWith("steps")) draft.steps?.getOrNull(i)?.let { openStep = it.id }
            else draft.outputs.getOrNull(i)?.let { openOutput = it.id }
        }
        val at = flowItemIndex(key, features)
        if (at != null) scope.launch { delay(120); runCatching { list.animateScrollToItem(at) } }
    }

    /**
     * What every save asks first, as the Mac does: another provider costs money (a plain yes), and
     * a start in No asking or a shell step needs the fingerprint or the screen lock (one check for
     * both, every time). Then [go] with the confirmations set.
     */
    fun confirmed(input: AutomationInput, go: (AutomationInput) -> Unit) {
        val paidBy = input.paidProvider(builtIn, providerOf(hub), costs(hub))
        fun check() {
            val ready = input.copy(confirmPaid = paidBy != null, confirmBypass = input.bypass, confirmShell = input.shell)
            if (!input.bypass && !input.shell) { go(ready); return }
            val shell = input.shell
            OwnerCheck.ask(
                context,
                title = res.getString(if (shell) R.string.wf_shell_title else R.string.au_bypass_title),
                subtitle = input.name.ifBlank { res.getString(R.string.au_edit_new) },
                noLock = res.getString(if (shell) R.string.wf_lock_needed else R.string.au_bypass_needed),
                onOk = { go(ready) },
                onFail = { msg -> if (msg != null) toast(msg) },
            )
        }
        if (paidBy != null) {
            val provider = if (paidBy == AgentKind.CODEX_PROVIDER) res.getString(R.string.cx_agent_codex) else hub.providers.firstOrNull { it.id == paidBy }?.name ?: paidBy
            confirm(Confirm(res.getString(R.string.au_paid_q), res.getString(R.string.au_paid_body, provider), res.getString(R.string.au_paid_ok), danger = false) { check() })
        } else check()
    }

    fun save() {
        tried = true
        if (flow && flowProblems.isNotEmpty()) reveal(flowProblems.keys.first())
        if (busy || blocked || !editable || offline) return
        confirmed(draft) { ready ->
            busy = true
            scope.launch {
                autos.save(id, ready)
                    .onSuccess { a ->
                        val back = shaped(a.input(), flow)
                        baseline = back
                        draft = back
                        tried = false
                        toast(res.getString(R.string.au_saved))
                        if (id == null) onSaved(a.id)
                    }
                    .onFailure { e ->
                        val err = e as? AutomationError
                        val code = err?.code
                        // Outside the projects folder: which folder that is, so the owner knows where to pick from (A3).
                        val text = if (code == "outside_projects" && hub.projectsRoot != null) res.getString(R.string.au_err_outside_root, tilde(hub.projectsRoot!!)) else e.message.orEmpty()
                        if (flow) {
                            val key = FieldPath.key(err?.field, code)
                            flowServer = mapOf(key to FieldError(text, FieldPath.sub(err?.field)))
                            if (key == FieldPath.OTHER) toast(text) else reveal(key)
                        } else {
                            val block = AutomationErrors.block(code)
                            serverErrors = mapOf(block to text)
                            if (block == AutomationErrors.Block.Other) toast(text)
                        }
                    }
                busy = false
            }
        }
    }

    var whenSheet by remember { mutableStateOf(false) }
    var doSheet by remember { mutableStateOf(false) }

    ScreenScaffold(
        sourceKey = "automation:${id ?: template ?: "new"}",
        title = draft.name.ifBlank { stringResource(R.string.au_edit_new) },
        onBack = ::leave,
        actions = {
            if (saved != null && !dirty && editable) {
                Pill(stringResource(R.string.au_run_now), {
                    scope.launch {
                        // What the run does ("Started", "In line…", "Waiting out your limit until 14:00"), or why it didn't (A8).
                        autos.runNow(saved.id)
                            .onSuccess { toast(RunWords.started(w, it, System.currentTimeMillis(), zone, codex = startsOnCodex(saved))) }
                            .onFailure { toast(runRefusal(w, it, zone)) }
                    }
                }, kind = PillKind.Ghost, height = 36.dp, leading = Glyph.Play, enabled = !offline)
                Spacer(Modifier.width(8.dp))
            }
            if (editable) {
                // Nothing to save (unchanged), or Save tried with something missing (the reason is
                // under its block): faded and untappable, so an unchanged paid one isn't re-sent (P14).
                Pill(
                    if (busy) stringResource(R.string.au_saving) else stringResource(R.string.au_save), ::save,
                    kind = PillKind.Primary, height = 36.dp,
                    enabled = !busy && !offline && (dirty || saved == null) && (!blocked || !tried),
                )
            }
        },
    ) { top, source ->
        // Opened from History: straight to the runs.
        LaunchedEffect(toRuns, saved != null) {
            if (toRuns && saved != null) {
                delay(120)
                list.animateScrollToItem(if (flow) flowItemIndex("runs", features) ?: 5 else if (draft.action is Action.Start) 5 else 4)
            }
        }
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            state = list,
            contentPadding = PaddingValues(top = top, bottom = bottom + 40.dp, start = 16.dp, end = 16.dp),
        ) {
            item(key = "summary") {
                Column(Modifier.padding(horizontal = 4.dp)) {
                    HText(
                        if (flow) Summary.flow(w, draft.trigger, draft.flowSteps, draft.outputs, draft.onlyOnce, ctx)
                        else Summary.sentence(w, draft.trigger, draft.action, draft.onlyOnce, ctx),
                        style = Type.heading, color = HN.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (!editable) {
                        Spacer(Modifier.height(10.dp))
                        HText(stringResource(R.string.au_unknown_note), style = Type.small, color = HN.amber)
                    } else if (offline) {
                        Spacer(Modifier.height(10.dp))
                        HText(stringResource(R.string.au_offline_edit), style = Type.small, color = HN.amber)
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
            item(key = "when") {
                Column {
                    BlockSeam(stringResource(R.string.au_when))
                    WhenBlock(
                        hub, draft, zone, now, w,
                        if (flow) flowErrors[FieldPath.WHEN]?.text else errors[AutomationErrors.Block.When],
                        onChange = { t -> update { it.copy(trigger = t) } }, onOnly = { v -> update { it.copy(onlyOnce = v) } }, onKind = { whenSheet = true }, editable = editable,
                        errorField = flowErrors[FieldPath.WHEN]?.sub, savedId = saved?.id, features = features,
                    )
                }
            }
            if (flow) {
                // An earlier step's chips read "Step 2's result".
                val numbers = stepNumbers(draft.steps.orEmpty())
                item(key = "then") {
                    CompositionLocalProvider(LocalStepNumbers provides numbers) {
                        StepsSection(
                            hub, draft, features.orEmpty(), flowErrors, template, editable, openStep,
                            onOpen = { openStep = it },
                            onSteps = { steps -> update { it.copy(steps = steps) } },
                            project = project, builtIn = startOn,
                        )
                    }
                }
                item(key = "tell") {
                    CompositionLocalProvider(LocalStepNumbers provides numbers) {
                        OutputsSection(
                            hub, draft, features.orEmpty(), flowErrors, editable, openOutput,
                            onOpen = { openOutput = it },
                            onOutputs = { outs -> update { it.copy(outputs = outs) } },
                            project = project,
                        )
                    }
                }
                if (features?.contains(Features.BUDGET) == true || draft.budget != null) {
                    item(key = "budget") {
                        BudgetSection(draft.budget, flowErrors[FieldPath.BUDGET]?.text, editable, fiveHour = hub.usage.fiveHourReported) { b -> update { it.copy(budget = b) } }
                    }
                }
            } else {
                item(key = "do") {
                    Column {
                        BlockSeam(stringResource(R.string.au_do))
                        DoBlock(hub, draft, template, errors[AutomationErrors.Block.Do], onChange = { a -> update { it.copy(action = a) } }, onKind = { doSheet = true }, editable = editable)
                    }
                }
                val start = draft.action as? Action.Start
                if (start != null) {
                    item(key = "permissions") {
                        Column {
                            BlockSeam(stringResource(R.string.au_permissions))
                            PermissionsBlock(start, errors[AutomationErrors.Block.Permissions], editable) { a -> update { it.copy(action = a) } }
                        }
                    }
                }
            }
            item(key = "name") {
                val nameError = if (flow) flowErrors[FieldPath.NAME]?.text else errors[AutomationErrors.Block.Name]
                val otherError = if (flow) flowErrors[FieldPath.OTHER]?.text else errors[AutomationErrors.Block.Other]
                Column {
                    BlockSeam(stringResource(R.string.au_name))
                    Field(
                        draft.name, { v -> nameEdited = true; draft = draft.copy(name = v.take(60)); serverErrors = serverErrors - AutomationErrors.Block.Name; flowServer = flowServer - FieldPath.NAME },
                        placeholder = stringResource(R.string.au_name_hint), label = stringResource(R.string.au_name),
                        error = nameError != null,
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    )
                    nameError?.let {
                        Spacer(Modifier.height(8.dp))
                        HText(it, style = Type.small, color = HN.amber)
                    }
                    otherError?.let {
                        Spacer(Modifier.height(14.dp))
                        HText(it, style = Type.small, color = HN.amber)
                    }
                }
            }
            if (saved != null) {
                item(key = "runs") { Runs(hub, saved, w, ctx, now, offline, onOpenSession, onAllowed = { rule ->
                    // What was saved is now the stored rule list: the draft and baseline take it too, so the
                    // next Save of another edit doesn't drop it (and edits in progress stay).
                    fun with(i: AutomationInput): AutomationInput = shaped(allowRule(i, rule), flow)
                    baseline = with(baseline)
                    draft = with(draft)
                }) }
                item(key = "delete") {
                    Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        // Duplicate: an off copy of it as saved, opened to change (A5).
                        if (editable) {
                            Pill(stringResource(R.string.au_duplicate), {
                                val copy = shaped(saved.input(), flow).copy(name = res.getString(R.string.au_copy_name, saved.name).take(60), enabled = false)
                                confirmed(copy) { ready ->
                                    scope.launch {
                                        autos.save(null, ready)
                                            .onSuccess { toast(res.getString(R.string.au_duplicated)); onSaved(it.id) }
                                            .onFailure { toast(it.message.orEmpty()) }
                                    }
                                }
                            }, kind = PillKind.Outline, height = 40.dp, enabled = !offline)
                            Spacer(Modifier.width(10.dp))
                        }
                        Pill(stringResource(R.string.au_delete), {
                            // A run at work now is stopped first: said so (A19).
                            val body = if (saved.lastRun?.working == true) R.string.au_delete_body_busy else R.string.au_delete_body
                            confirm(
                                Confirm(res.getString(R.string.au_delete_q, saved.name), res.getString(body), res.getString(R.string.au_delete), danger = true) {
                                    scope.launch { autos.delete(saved.id).onSuccess { onBack() }.onFailure { toast(it.message.orEmpty()) } }
                                },
                            )
                        }, kind = PillKind.Danger, height = 40.dp, enabled = !offline)
                    }
                }
            }
        }
    }

    if (whenSheet) {
        // Ghosted only when the Mac says it has no battery, not while it doesn't know yet (A15).
        val battery = st?.hasBattery != false
        val noBattery = stringResource(R.string.au_w_no_battery)
        TilesSheet(
            stringResource(R.string.au_pick_when),
            // The friendly ones first; the developers' only as the Mac lists them.
            WhenKind.entries.filter { it.feature == null || features?.contains(it.feature) == true }.map { k ->
                Tile(k, whenGlyph.getValue(k), whenTitle(k), reason = if ((k == WhenKind.Battery || k == WhenKind.Plugged) && !battery) noBattery else null)
            },
            draft.trigger.kind,
            onPick = { k ->
                whenSheet = false
                if (k != draft.trigger.kind) update {
                    val t = defaultTrigger(k, it.trigger, now, zone, project)
                    // "The session that fired it" needs a session's trigger: every working one otherwise (A7).
                    fun fit(a: Action): Action = if (a is Action.Continue && a.target == Action.Continue.TRIGGER && t !is Trigger.SessionEvent && t !is Trigger.Hook) a.copy(target = Action.Continue.WORKING) else a
                    it.copy(
                        trigger = t, action = fit(it.action),
                        steps = it.steps?.map { s -> s.copy(action = fit(s.action)) },
                        onlyOnce = if (k == WhenKind.Time || k == WhenKind.Every || k == WhenKind.Cron || k == WhenKind.Webhook) false else it.onlyOnce,
                    )
                }
            },
            onDismiss = { whenSheet = false },
        )
    }
    if (doSheet) {
        TilesSheet(
            stringResource(R.string.au_pick_do),
            // Skills are Claude Code's: not offered where a new step goes to Codex.
            DoKind.entries.filter { it.feature == null && !(it == DoKind.Skill && startOn == AgentKind.CODEX_PROVIDER) }.map { k -> Tile(k, doGlyph.getValue(k), doTitle(k)) },
            draft.action.kind,
            onPick = { k ->
                doSheet = false
                if (k != draft.action.kind) update { it.copy(action = defaultAction(k, it.action, it.trigger, project, startOn)) }
            },
            onDismiss = { doSheet = false },
        )
    }
    // A webhook trigger's token, just made: shown once, then put away.
    val token = saved?.id?.let { autos.freshToken(it) }
    val hook = saved?.trigger as? Trigger.Webhook
    if (token != null && hook != null) TokenCard(hook, token, onDone = { autos.forgetToken(saved.id) })
    DiscardDialog(discardOpen, onDiscard = { discardOpen = false; onBack() }, onKeep = { discardOpen = false })
}

/**
 * The draft in the shape the Mac takes: with workflows, its steps (today's action as one step and
 * its `then` as outputs), the action kept as step 1's so a rotation and the summary agree; without,
 * today's action alone (nothing of steps goes to a Mac that doesn't know them).
 */
private fun shaped(i: AutomationInput, flow: Boolean): AutomationInput =
    if (!flow) i.copy(steps = null, outputs = emptyList(), budget = null)
    else i.flow().let { f -> f.copy(action = f.steps!!.first().action) }

/** It can be edited here: nothing in it that this app doesn't know (a newer Mac's kinds are kept, not edited). */
private fun flowEditable(d: AutomationInput): Boolean =
    d.trigger !is Trigger.Unknown && d.flowSteps.none { it.action is Action.Unknown || (it.guard != null && it.guard.on !in com.prudnikweb.hypernova.data.Guard.ALL) } &&
        d.outputs.none { it is com.prudnikweb.hypernova.data.Output.Unknown }

/** "Allow next time": [rule] into the one start step that runs in a mode that keeps rules. */
private fun allowRule(i: AutomationInput, rule: String): AutomationInput {
    fun add(a: Action): Action = (a as? Action.Start)?.takeIf { rule !in it.allowedTools }?.let { it.copy(allowedTools = (it.allowedTools + rule).take(20)) } ?: a
    val steps = i.steps ?: return i.copy(action = add(i.action))
    val at = steps.indexOfFirst { it.action is Action.Start }.takeIf { k -> k >= 0 && steps.count { it.action is Action.Start } == 1 } ?: return i
    return i.copy(steps = steps.toMutableList().also { it[at] = it[at].copy(action = add(it[at].action)) })
}

/** Where each block sits in the workflow editor's list, to scroll a problem into view. */
private fun flowItemIndex(key: String, features: Set<String>?): Int? {
    val budget = if (features?.contains(Features.BUDGET) == true) 1 else 0
    return when {
        key == FieldPath.WHEN -> 1
        key.startsWith("steps") -> 2
        key.startsWith("outputs") -> 3
        key == FieldPath.BUDGET -> 4
        key == FieldPath.NAME || key == FieldPath.OTHER -> 4 + budget
        key == "runs" -> 5 + budget
        else -> null
    }
}

/** A workflow's problems by block, in words (shown once Save was tapped), with today's checks of its trigger. */
private fun flowValidate(d: AutomationInput, baseline: AutomationInput, features: Set<String>?, now: Long, w: Words, res: android.content.res.Resources): Map<String, FieldError> {
    val out = LinkedHashMap<String, FieldError>()
    val t = d.trigger
    if (EditorRules.pastOnce(d, baseline, now)) out[FieldPath.WHEN] = FieldError(res.getString(R.string.au_err_past), null)
    if (t is Trigger.Reset && t.from != null && t.to != null && t.to == t.from) out[FieldPath.WHEN] = FieldError(res.getString(R.string.au_err_time), null)
    for ((key, issue) in WorkflowRules.byKey(WorkflowRules.check(d, features))) {
        if (key !in out) out[key] = FieldError(WorkflowWords.issue(w, issue.code), issue.sub)
    }
    return out
}

@Composable
internal fun BlockSeam(label: String, trail: String? = null) {
    Seam(label, Modifier.padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 10.dp), trail = trail)
}

/** A name from the prompt's first four words ("Summarise what changed in"), when none was typed. */
internal fun autoName(a: Action): String {
    val text = when (a) {
        is Action.Start -> a.prompt
        is Action.Continue -> a.prompt
        is Action.Notify -> a.text
        is Action.Ask -> a.question
        is Action.Shell -> a.command
        is Action.Unknown -> ""
    }
    val words = text.trim().removePrefix("/").split(Regex("\\s+")).filter { it.isNotBlank() }.take(4)
    return words.joinToString(" ").trimEnd(',', '.', ':', ';').replaceFirstChar { it.uppercaseChar() }.take(60)
}

/**
 * What keeps it from saving, by block (shown once Save was tapped). A spent Once that stays off
 * with its time unchanged can still be renamed (A6); an every-few-hours window may run past
 * midnight (A13); "the session that fired it" needs a session's trigger (A7).
 */
private fun validate(d: AutomationInput, baseline: AutomationInput, now: Long, zone: ZoneId, res: android.content.res.Resources): Map<AutomationErrors.Block, String> {
    val out = mutableMapOf<AutomationErrors.Block, String>()
    if (d.name.isBlank()) out[AutomationErrors.Block.Name] = res.getString(R.string.au_err_name)
    val t = d.trigger
    if (EditorRules.pastOnce(d, baseline, now)) out[AutomationErrors.Block.When] = res.getString(R.string.au_err_past)
    if (t is Trigger.Reset && t.from != null && t.to != null && t.to == t.from) out[AutomationErrors.Block.When] = res.getString(R.string.au_err_time)
    if (EditorRules.badTarget(d)) out[AutomationErrors.Block.Do] = res.getString(R.string.au_err_target)
    when (val a = d.action) {
        is Action.Start -> {
            if (a.projectPath.isBlank()) out[AutomationErrors.Block.Do] = res.getString(R.string.au_err_project_needed)
            else if (a.prompt.isBlank() || a.prompt.trim() == "/") out[AutomationErrors.Block.Do] = res.getString(if (a.providerId == AgentKind.CODEX_PROVIDER) R.string.au_err_prompt_codex else R.string.au_err_prompt)
            val bad = a.allowedTools.firstOrNull { Rules.check(it) != null }
            if (bad != null) out[AutomationErrors.Block.Permissions] = res.getString(if (Rules.check(bad) == Rules.BROAD) R.string.au_err_rule_broad else R.string.au_err_rule)
        }
        is Action.Continue -> when {
            a.providerId != null && a.providerId.isBlank() -> out[AutomationErrors.Block.Do] = res.getString(R.string.au_err_provider_needed)
            a.prompt.isBlank() && a.target != Action.Continue.LIMITED && a.providerId == null -> out[AutomationErrors.Block.Do] = res.getString(R.string.au_err_prompt)
        }
        is Action.Notify -> if (a.text.isBlank()) out[AutomationErrors.Block.Do] = res.getString(R.string.au_err_text)
        is Action.Ask, is Action.Shell, is Action.Unknown -> Unit
    }
    return out
}

/** A trigger of [k], keeping what carries over (the time, the days, the project). */
internal fun defaultTrigger(k: WhenKind, from: Trigger, now: Long, zone: ZoneId, project: String? = null): Trigger {
    val at = (from as? Trigger.Time)?.at ?: (from as? Trigger.Every)?.from ?: "09:00"
    val days = (from as? Trigger.Time)?.days ?: (from as? Trigger.Every)?.days ?: Trigger.WEEKDAYS
    // The folder a trigger already named carries over (a session's, a hook's, a file watch's, a repository).
    val named = (from as? Trigger.SessionEvent)?.projectPath ?: (from as? Trigger.Hook)?.projectPath
        ?: (from as? Trigger.FileWatch)?.path?.ifEmpty { null } ?: (from as? Trigger.Github)?.repoPath?.ifEmpty { null }
    val folder = named ?: project
    return when (k) {
        WhenKind.Time -> Trigger.Time(at, days)
        WhenKind.Every -> Trigger.Every(2, at, "19:00".takeIf { at < "19:00" }, days)
        WhenKind.Reset -> Trigger.Reset(Trigger.FIVE_HOUR, afterLimit = true)
        WhenKind.Limit -> Trigger.Limit(Trigger.FIVE_HOUR)
        WhenKind.Finished -> Trigger.SessionEvent(Trigger.FINISHED, named, Trigger.MINE)
        WhenKind.Needs -> Trigger.SessionEvent(Trigger.NEEDS_YOU, named, Trigger.MINE)
        WhenKind.Plugged -> Trigger.Plugged
        WhenKind.Battery -> Trigger.BatteryLow(20)
        // A time of day already picked reads on as cron ("0 9 * * 1-5").
        WhenKind.Cron -> Trigger.Cron(TriggerWords.cronOf(from) ?: "*/30 9-18 * * 1-5")
        WhenKind.Webhook -> Trigger.Webhook()
        WhenKind.File -> Trigger.FileWatch(folder.orEmpty(), null, Trigger.CHANGED)
        WhenKind.Github -> Trigger.Github(folder.orEmpty(), listOf(Trigger.PR_OPENED), 10)
        WhenKind.Hook -> Trigger.Hook(Trigger.H_TOOL_FAILURE, null, named, Trigger.MINE)
    }
}

/**
 * A new start step on [agentProvider]: Claude Code's built-in provider, Allowed only; or Codex
 * ([AgentKind.CODEX_PROVIDER], where Claude Code isn't installed), Read only, with no allow rules
 * (it asks nobody).
 */
internal fun newStart(path: String, prompt: String, agentProvider: String, then: String): Action.Start =
    if (agentProvider == AgentKind.CODEX_PROVIDER) Action.Start(path, prompt, AgentKind.CODEX_PROVIDER, null, AgentKind.READ_ONLY, emptyList(), then)
    else Action.Start(path, prompt, agentProvider, null, AutoMode.ALLOWED, emptyList(), then)

/** [i] with each start step moved to Codex, Read only (a template, on a Mac where Codex leads). */
internal fun movedToCodex(i: AutomationInput): AutomationInput {
    // A skill ("/name") is Claude Code's: on Codex the step waits for words instead.
    fun move(a: Action): Action = if (a is Action.Start && a.providerId != AgentKind.CODEX_PROVIDER)
        a.copy(prompt = if (a.skill) "" else a.prompt, providerId = AgentKind.CODEX_PROVIDER, model = null, permissionMode = AgentKind.READ_ONLY, allowedTools = emptyList()) else a
    return i.copy(action = move(i.action), steps = i.steps?.map { it.copy(action = move(it.action)) })
}

/** An action of [k], keeping the words typed so far and the project. [builtIn]: where a new start step goes ([newStart]). */
internal fun defaultAction(k: DoKind, from: Action, trigger: Trigger, project: String?, builtIn: String): Action {
    val text = when (from) {
        is Action.Start -> from.prompt.takeIf { !from.skill }.orEmpty()
        is Action.Continue -> from.prompt
        is Action.Notify -> from.text
        is Action.Ask -> from.question
        is Action.Shell -> ""
        is Action.Unknown -> ""
    }
    val path = (from as? Action.Start)?.projectPath ?: (from as? Action.Shell)?.projectPath ?: project.orEmpty()
    val then = (from as? Action.Start)?.then ?: (from as? Action.Continue)?.then ?: Then.NOTIFY
    return when (k) {
        DoKind.Start -> (from as? Action.Start)?.copy(prompt = text) ?: newStart(path, text, builtIn, then)
        // A skill starts with "/" typed, so the list of the project's commands opens.
        DoKind.Skill -> (from as? Action.Start)?.copy(prompt = "/") ?: newStart(path, "/", builtIn, then)
        DoKind.Continue -> Action.Continue(if (trigger is Trigger.SessionEvent) Action.Continue.TRIGGER else Action.Continue.WORKING, text, then)
        DoKind.Ask -> Action.Ask(text.take(200))
        DoKind.Shell -> Action.Shell(path, "")
        DoKind.Notify -> Action.Notify(text)
    }
}

// ─── When ──────────────────────────────────────────────────────────────────

@Composable
internal fun WhenBlock(
    hub: Hub,
    d: AutomationInput,
    zone: ZoneId,
    now: Long,
    w: Words,
    error: String?,
    onChange: (Trigger) -> Unit,
    onOnly: (Boolean) -> Unit,
    onKind: () -> Unit,
    editable: Boolean,
    /** The field the Mac's refusal named ("expr", "path"): marked. */
    errorField: String? = null,
    /** A saved automation's id (a webhook's token and address belong to it). */
    savedId: String? = null,
    /** What the Mac can do of workflows: "only if it still needs me after …" needs [Features.T_AFTER]. */
    features: Set<String>? = null,
) {
    val t = d.trigger
    val k = t.kind
    var timeSheet by remember { mutableStateOf<String?>(null) }
    var onPickTime by remember { mutableStateOf<(String) -> Unit>({}) }
    fun pickTime(current: String, then: (String) -> Unit) { onPickTime = then; timeSheet = current }
    KindBlock(k?.let { whenGlyph.getValue(it) } ?: AutoGlyph.Clock, k?.let { whenTitle(it) } ?: stringResource(R.string.au_sum_unknown_trigger), onChange = onKind.takeIf { editable }, error = error) {
        if (!editable) return@KindBlock
        when (t) {
            is Trigger.Time, is Trigger.Once -> TimeParams(t, zone, now, w, ::pickTime, onChange)
            is Trigger.Every -> {
                ParamLabel(stringResource(R.string.au_every_label))
                Segmented(Trigger.HOURS.map { it to it.toString() }, t.hours, { onChange(t.copy(hours = it)) })
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (t.to != null) {
                        ValuePill(stringResource(R.string.au_from, Summary.clock(w, t.from)), { pickTime(t.from) { onChange(t.copy(from = it)) } })
                        // A window may run past midnight (22:00 to 6:00), as the Mac runs it (A13).
                        ValuePill(stringResource(R.string.au_to, Summary.clock(w, t.to)), { pickTime(t.to) { onChange(t.copy(to = it)) } })
                    } else {
                        // Around the clock it runs on one grid every day: its first run of the day, the real time.
                        val first = EditorRules.firstOfDay(t.from, t.hours)
                        ValuePill(stringResource(R.string.au_first_at, Summary.clock(w, first)), { pickTime(first) { onChange(t.copy(from = EditorRules.firstOfDay(it, t.hours))) } })
                    }
                }
                ToggleRow(stringResource(R.string.au_around), null, t.to == null, { v -> onChange(t.copy(to = if (v) null else maxOf("19:00", t.from))) })
                DayChips(t.days, { onChange(t.copy(days = it)) })
                MacZoneNote(zone, t.from, w)
            }
            is Trigger.Reset -> {
                Spacer(Modifier.height(4.dp))
                Segmented(listOf(Trigger.FIVE_HOUR to stringResource(R.string.au_window_five), Trigger.SEVEN_DAY to stringResource(R.string.au_window_week)), t.window, { onChange(t.copy(window = it)) })
                ToggleRow(stringResource(R.string.au_reset_after), stringResource(R.string.au_reset_after_body), t.afterLimit, { onChange(t.copy(afterLimit = it)) })
                val night = t.from != null && t.to != null
                ToggleRow(stringResource(R.string.au_reset_night), null, night, { v -> onChange(if (v) t.copy(from = "00:00", to = "07:00") else t.copy(from = null, to = null)) })
                if (night) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        ValuePill(stringResource(R.string.au_from, Summary.clock(w, t.from!!)), { pickTime(t.from) { onChange(t.copy(from = it)) } })
                        ValuePill(stringResource(R.string.au_to, Summary.clock(w, t.to!!)), { pickTime(t.to) { onChange(t.copy(to = it)) } })
                    }
                }
                OnlyNext(d.onlyOnce, onOnly)
            }
            is Trigger.Limit -> {
                Spacer(Modifier.height(4.dp))
                Segmented(listOf(Trigger.FIVE_HOUR to stringResource(R.string.au_window_five), Trigger.SEVEN_DAY to stringResource(R.string.au_window_week)), t.window, { onChange(t.copy(window = it)) })
                Spacer(Modifier.height(8.dp))
                HText(stringResource(R.string.au_limit_note), style = Type.small, color = HN.inkDim)
                OnlyNext(d.onlyOnce, onOnly)
            }
            is Trigger.SessionEvent -> {
                var pick by remember { mutableStateOf(false) }
                PickRow(stringResource(R.string.au_project), t.projectPath?.let { p -> hub.projects.firstOrNull { it.path == p }?.name ?: p.substringAfterLast('/') } ?: stringResource(R.string.au_any_project), { pick = true })
                ParamLabel(stringResource(R.string.au_sessions_from))
                Segmented(
                    listOf(Trigger.MINE to stringResource(R.string.au_from_mine), Trigger.AUTOMATED to stringResource(R.string.au_from_auto), Trigger.ANY to stringResource(R.string.au_from_any)),
                    t.from, { onChange(t.copy(from = it)) },
                )
                // "Still needs me after 10 minutes": only when the Mac can wait (or one it already waits).
                if (t.event == Trigger.NEEDS_YOU && (features?.contains(Features.T_AFTER) == true || t.after != null)) {
                    ParamLabel(stringResource(R.string.au_after_label))
                    val waits = (listOf(5, 10, 30) + listOfNotNull(t.after)).distinct().sorted()
                    Segmented(
                        listOf<Pair<Int?, String>>(null to stringResource(R.string.au_after_now)) + waits.map { it to stringResource(R.string.wf_minutes, it) },
                        t.after, { onChange(t.copy(after = it)) },
                    )
                    Spacer(Modifier.height(8.dp))
                    HText(stringResource(if (t.after == null) R.string.au_after_now_note else R.string.au_after_note), style = Type.small, color = HN.inkDim)
                }
                OnlyNext(d.onlyOnce, onOnly)
                if (pick) {
                    val any = stringResource(R.string.au_any_project)
                    ChoiceSheet(
                        stringResource(R.string.au_project),
                        listOf(Choice<String?>(null, any)) + hub.projects.map { Choice<String?>(it.path, it.name, tilde(it.path)) },
                        t.projectPath,
                        onPick = { p -> pick = false; onChange(t.copy(projectPath = p)) },
                        onDismiss = { pick = false },
                    )
                }
            }
            Trigger.Plugged -> OnlyNext(d.onlyOnce, onOnly)
            is Trigger.BatteryLow -> {
                val at = stringResource(R.string.au_battery_at, t.below)
                ParamLabel(at)
                StepSlider(t.below, 5..50, 5, { onChange(t.copy(below = it)) }, at)
                OnlyNext(d.onlyOnce, onOnly)
            }
            is Trigger.Cron -> CronParams(hub, t, zone, now, w, errorField, onChange)
            is Trigger.Webhook -> WebhookParams(hub, t, savedId, w, zone, now)
            is Trigger.FileWatch -> { FileWatchParams(hub, t, errorField, onChange); OnlyNext(d.onlyOnce, onOnly) }
            is Trigger.Github -> { GithubParams(hub, t, errorField, onChange); OnlyNext(d.onlyOnce, onOnly) }
            is Trigger.Hook -> { HookParams(hub, t, onChange); OnlyNext(d.onlyOnce, onOnly) }
            is Trigger.Unknown -> Unit
        }
    }
    val current = timeSheet
    if (current != null) TimeSheet(current, onPick = { v -> timeSheet = null; onPickTime(v) }, onDismiss = { timeSheet = null })
}

@Composable
internal fun OnlyNext(on: Boolean, onChange: (Boolean) -> Unit) {
    ToggleRow(stringResource(R.string.au_only_next), stringResource(R.string.au_only_next_body), on, onChange)
}

/** At a time: the time, the days (or with Once, the date), and what happens if the Mac sleeps. */
@Composable
private fun TimeParams(t: Trigger, zone: ZoneId, now: Long, w: Words, pickTime: (String, (String) -> Unit) -> Unit, onChange: (Trigger) -> Unit) {
    // The days a repeating time had, kept while Once is on, so turning it off brings them back.
    var lastDays by rememberSaveable { mutableStateOf((t as? Trigger.Time)?.days ?: Trigger.WEEKDAYS) }
    val once = t as? Trigger.Once
    val at = once?.let { Instant.ofEpochMilli(it.at).atZone(zone) }
    val hhmm = (t as? Trigger.Time)?.at ?: at?.toLocalTime()?.let { String.format(java.util.Locale.ROOT, "%02d:%02d", it.hour, it.minute) } ?: "09:00"
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun onceAt(day: LocalDate, time: String): Trigger.Once = Trigger.Once(ZonedDateTime.of(day, LocalTime.parse(time), zone).toInstant().toEpochMilli())
    Spacer(Modifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        ValuePill(Summary.clock(w, hhmm), {
            pickTime(hhmm) { v -> onChange(if (once != null) onceAt(at!!.toLocalDate(), v) else (t as Trigger.Time).copy(at = v)) }
        }, label = stringResource(R.string.au_time))
        Spacer(Modifier.weight(1f))
        ToggleChip(stringResource(R.string.au_once), once != null, { v ->
            if (v) {
                lastDays = (t as? Trigger.Time)?.days ?: lastDays
                val time = LocalTime.parse(hhmm)
                val nowTime = Instant.ofEpochMilli(now).atZone(zone).toLocalTime()
                onChange(onceAt(if (time.isAfter(nowTime)) today else today.plusDays(1), hhmm))
            } else onChange(Trigger.Time(hhmm, lastDays))
        })
    }
    Spacer(Modifier.height(10.dp))
    if (once != null) DateStrip(at!!.toLocalDate(), today, { day -> onChange(onceAt(day, hhmm)) })
    else DayChips((t as Trigger.Time).days, { onChange(t.copy(days = it)) })
    MacZoneNote(zone, hhmm, w)
    Spacer(Modifier.height(10.dp))
    HText(stringResource(R.string.au_time_note, Summary.clock(w, hhmm)), style = Type.small, color = HN.inkDim)
}

/** "9:00 on the Mac (Europe/Moscow)", when the phone is in another zone than the Mac. */
@Composable
private fun MacZoneNote(zone: ZoneId, hhmm: String, w: Words) {
    if (zone.rules.getOffset(Instant.now()) == ZoneId.systemDefault().rules.getOffset(Instant.now())) return
    Spacer(Modifier.height(8.dp))
    HText(stringResource(R.string.au_mac_time, Summary.clock(w, hhmm), zone.id), style = Type.small, color = HN.amber)
}

// ─── Do ────────────────────────────────────────────────────────────────────

@Composable
private fun DoBlock(
    hub: Hub,
    d: AutomationInput,
    template: String?,
    error: String?,
    onChange: (Action) -> Unit,
    onKind: () -> Unit,
    editable: Boolean,
) {
    val a = d.action
    val k = a.kind
    KindBlock(k?.let { doGlyph.getValue(it) } ?: AutoGlyph.Start, k?.let { doTitle(it) } ?: stringResource(R.string.au_sum_unknown_action), onChange = onKind.takeIf { editable }, error = error) {
        if (!editable) return@KindBlock
        when (a) {
            is Action.Start -> StartParams(hub, a, template, onChange)
            is Action.Continue -> ContinueParams(hub, a, d.trigger, onChange)
            is Action.Notify -> {
                TextParams(a.text, d.trigger) { onChange(a.copy(text = it)) }
                NotifyBlocked()
            }
            is Action.Ask, is Action.Shell, is Action.Unknown -> Unit
        }
    }
}

/**
 * A start's project, prompt and model. [withThen]: today's "When it's done" (a workflow's outputs
 * say that instead); [chips]: the placeholders its prompt can hold, as chips under it.
 */
@Composable
internal fun StartParams(hub: Hub, a: Action.Start, template: String?, onChange: (Action) -> Unit, withThen: Boolean = true, chips: List<String>? = null, errorField: String? = null) {
    var pickProject by remember { mutableStateOf(false) }
    val p = hub.projects.firstOrNull { it.path == a.projectPath }
    PickRow(stringResource(R.string.au_project), p?.name ?: a.projectPath.substringAfterLast('/').ifEmpty { stringResource(R.string.au_err_project_needed) }, { pickProject = true }, sub = a.projectPath.takeIf { it.isNotEmpty() }?.let(::tilde))
    Spacer(Modifier.height(6.dp))
    // Which agent runs it (data/Codex.kt): offered while both are in use here (Claude Code installed,
    // Codex on); on a step already on Codex while Claude Code is here; and on a Claude step where
    // Codex leads (Claude Code isn't installed), to move it. Codex starts read only, with no allow
    // rules (it asks nobody). A Mac with one agent has nothing to choose.
    val onCodex = a.providerId == AgentKind.CODEX_PROVIDER
    val claudeHere = com.prudnikweb.hypernova.data.AgentLead.claudeAvailable(hub.claude)
    if (com.prudnikweb.hypernova.data.AgentLead.both(hub.claude, hub.codex) || (onCodex && claudeHere) || (!onCodex && com.prudnikweb.hypernova.data.AgentLead.codexLeads(hub.claude, hub.codex))) {
        val builtIn = hub.providers.firstOrNull { it.builtIn }?.id ?: "anthropic"
        com.prudnikweb.hypernova.ui.home.AgentChoice(
            if (onCodex) AgentKind.CODEX else AgentKind.CLAUDE,
            { agent ->
                when {
                    agent == AgentKind.CODEX && !onCodex -> onChange(a.copy(providerId = AgentKind.CODEX_PROVIDER, model = null, permissionMode = AgentKind.READ_ONLY, allowedTools = emptyList()))
                    agent == AgentKind.CLAUDE && onCodex -> onChange(a.copy(providerId = builtIn, model = null, permissionMode = AutoMode.ALLOWED))
                }
            },
        )
        Spacer(Modifier.height(10.dp))
    }
    val hint = Templates.byId(template)?.hint
    val focus = remember { FocusRequester() }
    // A template that leaves the prompt to you opens with the cursor in it.
    if (hint != null && a.prompt.isEmpty()) LaunchedEffect(Unit) { delay(350); runCatching { focus.requestFocus() } }
    if (chips == null) Field(
        a.prompt, { onChange(a.copy(prompt = it.take(8000))) },
        modifier = Modifier.focusRequester(focus),
        placeholder = stringResource(hint ?: if (a.skill) R.string.au_skill_hint else if (onCodex) R.string.cx_prompt_new_hint else R.string.au_prompt_hint),
        singleLine = false, minLines = 3, maxLines = 8, label = stringResource(R.string.au_prompt), commands = true,
    ) else ChipsText(
        a.prompt, { onChange(a.copy(prompt = it.take(8000))) }, chips,
        placeholder = stringResource(hint ?: if (a.skill) R.string.au_skill_hint else if (onCodex) R.string.cx_prompt_new_hint else R.string.au_prompt_hint),
        label = stringResource(R.string.au_prompt), minLines = 3, maxLines = 8, commands = true, error = errorField == "prompt",
        modifier = Modifier.focusRequester(focus),
    )
    val query = commandQuery(a.prompt)
    val commands = rememberCommands(hub, a.projectPath.takeIf { it.isNotEmpty() }, query != null)
    if (query != null) {
        Spacer(Modifier.height(10.dp))
        CommandSuggestions(commands, query, onPick = { onChange(a.copy(prompt = "/${it.name} ")) })
    }
    commands.picked(a.prompt)?.takeIf { it.argumentHint.isNotBlank() }?.let { CommandHint(it, Modifier.padding(start = 4.dp, top = 8.dp)) }
    // The model and its provider, as the Start sheet picks them: every model each provider offers,
    // each provider with its trouble or what's left on it (A10).
    val provider = hub.providers.firstOrNull { it.id == a.providerId } ?: hub.providers.firstOrNull()
    var lists by remember { mutableStateOf<List<com.prudnikweb.hypernova.data.ModelList>?>(null) }
    var loadingModels by remember { mutableStateOf(true) }
    var pickModel by remember { mutableStateOf(false) }
    LaunchedEffect(hub) {
        hub.models().onSuccess { lists = it }
        loadingModels = false
    }
    val options = remember(provider, lists) { provider?.let { com.prudnikweb.hypernova.data.ModelCatalog.of(it, lists) } ?: com.prudnikweb.hypernova.data.ModelCatalog.CLAUDE }
    val chosen = com.prudnikweb.hypernova.data.ModelCatalog.find(options, a.model)
    val modelName = chosen?.let { com.prudnikweb.hypernova.data.ModelCatalog.shown(it, options) } ?: a.model ?: stringResource(R.string.model_label_default)
    // Codex's own models (an automation has no reasoning effort: Codex's default).
    var codexModels by remember { mutableStateOf<List<com.prudnikweb.hypernova.data.CodexModel>?>(null) }
    var codexNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hub, onCodex) {
        if (onCodex) hub.codexModels().onSuccess { codexModels = it }.onFailure { codexModels = emptyList(); codexNote = it.message }
    }
    Spacer(Modifier.height(6.dp))
    if (onCodex) {
        PickRow(stringResource(R.string.au_model), com.prudnikweb.hypernova.ui.home.codexModelName(codexModels, a.model, stringResource(R.string.model_label_default)), { pickModel = true }, sub = stringResource(R.string.cx_agent_codex))
        // On an API key every run costs money; on a ChatGPT plan it's the plan's.
        if (hub.codex?.account?.paid == true) {
            Spacer(Modifier.height(6.dp))
            WarningBox(stringResource(R.string.cx_paid_body), HN.amber)
        }
    } else {
        PickRow(stringResource(R.string.au_model), modelName, { pickModel = true }, sub = provider?.takeIf { !it.builtIn }?.name)
        // Another provider bills it, unless the owner said it costs nothing.
        if (provider != null && !provider.builtIn && provider.paid) {
            Spacer(Modifier.height(6.dp))
            WarningBox(stringResource(R.string.au_paid_body, provider.name), HN.amber)
        }
    }
    if (withThen) ThenParams(a.then) { onChange(a.copy(then = it)) }
    if (pickModel && onCodex) {
        com.prudnikweb.hypernova.ui.home.CodexModelSheet(
            codexModels, codexNote, a.model, null, withEffort = false,
            onDone = { m, _ -> pickModel = false; onChange(a.copy(model = m)) },
            onDismiss = { pickModel = false },
        )
    } else if (pickModel) {
        val context = LocalContext.current
        com.prudnikweb.hypernova.ui.home.ModelPicker(
            hub.providers, lists, loadingModels, provider?.id, a.model, hub.usage.models, System.currentTimeMillis(),
            providerTrail = { p -> if (p.builtIn) null else com.prudnikweb.hypernova.data.ProviderWords.trail(context, p) },
            onPick = { id, value -> pickModel = false; onChange(a.copy(providerId = id, model = value)) },
            onDismiss = { pickModel = false },
        )
    }
    if (pickProject) {
        ChoiceSheet(
            stringResource(R.string.au_project),
            hub.projects.map { Choice(it.path, it.name, tilde(it.path)) },
            a.projectPath,
            onPick = { path -> pickProject = false; onChange(a.copy(projectPath = path)) },
            onDismiss = { pickProject = false },
            empty = stringResource(R.string.au_no_projects),
        )
    }
}

@Composable
private fun ThenParams(then: String, onChange: (String) -> Unit) {
    ParamLabel(stringResource(R.string.au_when_done))
    Segmented(
        listOf(Then.NOTIFY to stringResource(R.string.au_then_notify), Then.NONE to stringResource(R.string.au_then_none)),
        then, onChange,
    )
    // A note to a phone that blocks them goes nowhere (A16).
    if (then == Then.NOTIFY) NotifyBlocked()
}

/**
 * "Notifications from Hypernova are off on this phone" with **Turn on**, while the app's
 * notifications or the Automations channel are off: a note would be dropped (A16).
 */
@Composable
internal fun NotifyBlocked() {
    val context = LocalContext.current
    val ask = com.prudnikweb.hypernova.ui.LocalAskNotifications.current
    val blocked by androidx.compose.runtime.produceState(com.prudnikweb.hypernova.data.AutomationNotes.blocked(context)) {
        while (true) { value = com.prudnikweb.hypernova.data.AutomationNotes.blocked(context); delay(2000) }
    }
    if (!blocked) return
    Spacer(Modifier.height(10.dp))
    HText(stringResource(R.string.au_notify_blocked), style = Type.small, color = HN.amber)
    Spacer(Modifier.height(8.dp))
    Pill(stringResource(R.string.au_turn_on), {
        // The app's own switch first (the system asks, or opens its page); the channel's page when only it is off.
        if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) com.prudnikweb.hypernova.data.AutomationNotes.openChannel(context) else ask()
    }, kind = PillKind.Outline, height = 40.dp)
}

/**
 * A continue's session, prompt and provider. [withThen]: today's "When it's done"; [earlier]: a
 * workflow's earlier start and continue steps (id to its number), offered as "the session from
 * step 1"; [chips]: the prompt's placeholders.
 */
@Composable
internal fun ContinueParams(
    hub: Hub,
    a: Action.Continue,
    trigger: Trigger,
    onChange: (Action) -> Unit,
    withThen: Boolean = true,
    earlier: List<Pair<String, Int>> = emptyList(),
    chips: List<String>? = null,
    errorField: String? = null,
) {
    var pick by remember { mutableStateOf(false) }
    val stepNumber = earlier.firstOrNull { Action.Continue.STEP + it.first == a.target }?.second
    val target = when {
        a.target == Action.Continue.TRIGGER -> stringResource(R.string.au_t_trigger)
        a.target == Action.Continue.WORKING -> stringResource(R.string.au_t_working)
        a.target == Action.Continue.LIMITED -> stringResource(R.string.au_t_limited)
        stepNumber != null -> stringResource(R.string.wf_t_step, stepNumber)
        a.target.startsWith(Action.Continue.STEP) -> stringResource(R.string.wf_target_step_short)
        else -> hub.session(a.target)?.name ?: stringResource(R.string.au_sum_target_gone)
    }
    PickRow(stringResource(R.string.au_session), target, { pick = true })
    if (errorField == "target") HText(stringResource(R.string.au_err_target), style = Type.small, color = HN.amber)
    Spacer(Modifier.height(6.dp))
    if (chips == null) Field(
        a.prompt, { onChange(a.copy(prompt = it.take(8000))) },
        placeholder = stringResource(R.string.au_continue_hint),
        singleLine = false, minLines = 2, maxLines = 6, label = stringResource(R.string.au_prompt),
    ) else ChipsText(
        a.prompt, { onChange(a.copy(prompt = it.take(8000))) }, chips,
        placeholder = stringResource(R.string.au_continue_hint), label = stringResource(R.string.au_prompt), minLines = 2, maxLines = 6, error = errorField == "prompt",
    )
    Spacer(Modifier.height(8.dp))
    HText(stringResource(R.string.au_own_mode), style = Type.small, color = HN.inkDim)
    val others = hub.providers.filter { !it.builtIn }
    if (others.isNotEmpty() || a.providerId != null) {
        ParamLabel(stringResource(R.string.au_on_provider))
        ChoiceRow(stringResource(R.string.au_own_provider), null, a.providerId == null) { onChange(a.copy(providerId = null)) }
        for (p in others) ChoiceRow(p.name, p.baseUrl?.removePrefix("https://"), a.providerId == p.id) { onChange(a.copy(providerId = p.id)) }
        val chosen = a.providerId?.let { id -> hub.providers.firstOrNull { it.id == id } }
        if (chosen != null && chosen.paid) {
            Spacer(Modifier.height(8.dp))
            WarningBox(stringResource(R.string.au_costs_money, chosen.name), HN.amber)
        }
        // Switch provider at the limit with nothing to switch to: the way to add one, here (A4).
        if (others.isEmpty()) {
            val opener = com.prudnikweb.hypernova.ui.LocalOpener.current
            Spacer(Modifier.height(8.dp))
            HText(stringResource(R.string.au_needs_provider), style = Type.small, color = HN.amber)
            Spacer(Modifier.height(8.dp))
            Pill(stringResource(R.string.au_add_provider), { opener.providers() }, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Plus)
        }
    }
    if (withThen) ThenParams(a.then) { onChange(a.copy(then = it)) }
    if (pick) {
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val live = stringResource(R.string.au_live_sessions)
        val past = stringResource(R.string.au_past_sessions)
        val stepsSection = stringResource(R.string.wf_steps_section)
        val stepTitle = earlier.map { (id, n) -> id to stringResource(R.string.wf_t_step, n) }
        val choices = buildList {
            for ((id, title) in stepTitle) add(Choice(Action.Continue.STEP + id, title, leading = AutoGlyph.Continue, section = stepsSection))
            if (trigger is Trigger.SessionEvent || trigger is Trigger.Hook) add(Choice(Action.Continue.TRIGGER, stringResource(R.string.au_t_trigger), leading = AutoGlyph.NeedsMe))
            add(Choice(Action.Continue.WORKING, stringResource(R.string.au_t_working), leading = AutoGlyph.Repeat))
            add(Choice(Action.Continue.LIMITED, stringResource(R.string.au_t_limited), leading = AutoGlyph.Limit))
            for (s in hub.sessions.filter { it.phase != Phase.Ended }) add(Choice(s.id, s.name, s.project, section = live))
            for (s in hub.sessions.filter { it.phase == Phase.Ended && it.updatedAt > weekAgo }) add(Choice(s.id, s.name, s.project, section = past))
        }
        ChoiceSheet(stringResource(R.string.au_pick_session), choices, a.target, onPick = { v -> pick = false; onChange(a.copy(target = v)) }, onDismiss = { pick = false }, searchHint = stringResource(R.string.au_search_sessions))
    }
}

/** Notify me / Read aloud: the text, with the placeholders this trigger can fill as chips ([offered]: a workflow's own list). */
@Composable
internal fun TextParams(text: String, trigger: Trigger, offered: List<String>? = null, error: Boolean = false, onChange: (String) -> Unit) {
    if (offered != null) {
        ChipsText(text, { onChange(it.take(500)) }, offered, placeholder = stringResource(R.string.au_text_hint), label = stringResource(R.string.au_text), minLines = 2, maxLines = 6, error = error)
        return
    }
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    TextBox(value, { v -> value = v; if (v.text != text) onChange(v.text.take(500)) }, stringResource(R.string.au_text_hint), stringResource(R.string.au_text))
    val offered = buildList {
        add("{project}")
        if (trigger is Trigger.SessionEvent) { add("{session}"); add("{result}"); add("{ask}") }
        if (trigger is Trigger.BatteryLow || trigger == Trigger.Plugged) add("{battery}")
        if (trigger is Trigger.Reset && trigger.window == Trigger.SEVEN_DAY) add("{week}")
        add("{time}")
    }
    ParamLabel(stringResource(R.string.au_placeholders))
    PlaceholderChips(offered, onInsert = { token ->
        val sel = value.selection
        val before = value.text.substring(0, sel.min)
        val after = value.text.substring(sel.max)
        val spaced = (if (before.isNotEmpty() && !before.endsWith(' ')) " " else "") + token
        val next = before + spaced + after
        value = TextFieldValue(next, TextRange(before.length + spaced.length))
        onChange(next.take(500))
    })
}

/** A multi-line text box in [Field]'s style that keeps its cursor, so a chip inserts where it is. */
@Composable
private fun TextBox(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, placeholder: String, label: String) {
    val shape = RoundedCornerShape(14.dp)
    val style = Type.body.copy(color = HN.ink)
    BasicTextField(
        value, onChange,
        textStyle = style,
        cursorBrush = SolidColor(HN.accent),
        minLines = 2, maxLines = 6,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(HN.bg800.copy(alpha = 0.6f)).border(1.dp, HN.ink.copy(alpha = 0.10f), shape).padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.text.isEmpty()) HText(placeholder, style = style, color = HN.inkGhost)
                inner()
            }
        },
    )
}

// ─── Permissions ───────────────────────────────────────────────────────────

/**
 * Five modes, title and one line each; the chosen one opens its detail: "Allowed only" its rules
 * (removable chips and a field to add one), "Auto" and "No asking" a warning. Choosing either of
 * those two first shows the think-twice card, as the session modes do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PermissionsBlock(a: Action.Start, error: String?, editable: Boolean, framed: Boolean = true, onChange: (Action) -> Unit) {
    if (a.providerId == AgentKind.CODEX_PROVIDER) return CodexPermissions(a, error, editable, framed, onChange)
    val context = LocalContext.current
    var asking by remember { mutableStateOf<String?>(null) }
    var incidentId by remember { mutableStateOf<String?>(null) }
    var rule by remember { mutableStateOf("") }
    // The rules apply in Allowed only, Ask me and Edits on its own; Auto and No asking keep none
    // (the Mac drops them), so they wait here in case the owner comes back (A9).
    var stash by remember { mutableStateOf<List<String>>(emptyList()) }
    fun switchTo(m: String) {
        val keep = Rules.kept(m)
        when {
            keep && !Rules.kept(a.permissionMode) -> onChange(a.copy(permissionMode = m, allowedTools = (a.allowedTools + stash).distinct().take(20)))
            !keep && a.allowedTools.isNotEmpty() -> { stash = a.allowedTools; onChange(a.copy(permissionMode = m, allowedTools = emptyList())) }
            else -> onChange(a.copy(permissionMode = m))
        }
    }
    fun choose(m: String) {
        if (m == a.permissionMode || !editable) return
        if (m == AutoMode.AUTO || m == AutoMode.NO_ASKING) {
            val pick = Incidents.next(context)
            if (pick == null) switchTo(m) else { incidentId = pick.id; asking = m }
        } else switchTo(m)
    }
    Frame(framed, error != null) {
        Column(Modifier.padding(horizontal = if (framed) 16.dp else 0.dp, vertical = 8.dp)) {
            for (m in AutoMode.ALL) {
                val on = m == a.permissionMode
                val warn = when (m) { AutoMode.AUTO -> HN.amber; AutoMode.NO_ASKING -> HN.danger; else -> HN.accent }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(on, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = Role.RadioButton) { choose(m) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.padding(top = 2.dp)) { Radio(on, color = warn) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        HText(modeTitle(m), style = Type.body, color = if (on) HN.ink else HN.inkMuted)
                        HText(stringResource(modeBody(m)), style = Type.small, color = HN.inkDim)
                    }
                }
                AnimatedVisibility(on, enter = fadeIn(tween(Motion.FAST)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo)), exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG))) {
                    Column(Modifier.padding(start = 32.dp, bottom = 10.dp)) {
                        when (m) {
                            AutoMode.ALLOWED, AutoMode.ASK, AutoMode.EDITS -> {
                                // Ask me and Edits on its own: what it may do besides, without asking.
                                if (m != AutoMode.ALLOWED) ParamLabel(stringResource(R.string.au_rules_also), Modifier.padding(top = 0.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (r in a.allowedTools) {
                                        RuleChip(r, stringResource(R.string.au_rule_remove, r), trailing = Glyph.Cross, onClick = { onChange(a.copy(allowedTools = a.allowedTools - r)) })
                                    }
                                }
                                if (a.allowedTools.size < 20) {
                                    Spacer(Modifier.height(10.dp))
                                    val checked = rule.trim().takeIf { it.isNotEmpty() }?.let { Rules.check(it) }
                                    fun add() {
                                        val r = rule.trim()
                                        if (r.isEmpty() || Rules.check(r) != null) return
                                        if (r !in a.allowedTools) onChange(a.copy(allowedTools = a.allowedTools + r))
                                        rule = ""
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f)) {
                                            Field(
                                                rule, { rule = it }, placeholder = stringResource(R.string.au_rule_hint), mono = true,
                                                label = stringResource(R.string.au_rule_add), error = checked != null,
                                                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                                                onDone = ::add,
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Pill(stringResource(R.string.au_rule_add), ::add, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Plus)
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    HText(
                                        when (checked) {
                                            Rules.BROAD -> stringResource(R.string.au_err_rule_broad)
                                            Rules.BAD -> stringResource(R.string.au_err_rule)
                                            // Edits under a folder are a rule too: Edit(src/**).
                                            else -> stringResource(if (m == AutoMode.ALLOWED) R.string.au_rules_note else R.string.au_rules_note_edits)
                                        },
                                        style = Type.small, color = if (checked != null) HN.amber else HN.inkDim,
                                    )
                                }
                            }
                            AutoMode.AUTO -> WarningBox(stringResource(R.string.au_warn_auto), HN.amber)
                            AutoMode.NO_ASKING -> WarningBox(stringResource(R.string.au_warn_no_asking), HN.danger)
                            else -> Unit
                        }
                    }
                }
                if (m != AutoMode.ALL.last()) Hairline(Modifier.fillMaxWidth())
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                HText(error, style = Type.small, color = HN.amber)
                Spacer(Modifier.height(6.dp))
            }
        }
    }
    val m = asking
    val incident = Incidents.byId(context, incidentId)
    if (m != null && incident != null) {
        ThinkTwiceCard(if (m == AutoMode.NO_ASKING) "bypassPermissions" else "auto", incident, onKeep = { asking = null }, onGoOn = { asking = null; switchTo(m) })
    }
}

/**
 * A Codex step's permissions (docs/PROTOCOL.md, Automations' start step): its two sandboxes as radio
 * rows, Read only (the default) and Can edit. In an automation Codex asks nobody, so there are no
 * rules: what its sandbox doesn't allow, it doesn't do.
 */
@Composable
private fun CodexPermissions(a: Action.Start, error: String?, editable: Boolean, framed: Boolean, onChange: (Action) -> Unit) {
    Frame(framed, error != null) {
        Column(Modifier.padding(horizontal = if (framed) 16.dp else 0.dp, vertical = 8.dp)) {
            val current = if (a.permissionMode == AgentKind.CAN_EDIT) AgentKind.CAN_EDIT else AgentKind.READ_ONLY
            for (m in AgentKind.CODEX_MODES) {
                val on = m == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .selectable(on, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, role = Role.RadioButton) {
                            if (editable && !on) onChange(a.copy(permissionMode = m, allowedTools = emptyList()))
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.padding(top = 2.dp)) { Radio(on) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        HText(modeTitle(m), style = Type.body, color = if (on) HN.ink else HN.inkMuted)
                        HText(stringResource(if (m == AgentKind.CAN_EDIT) R.string.cx_au_can_edit_body else R.string.cx_au_read_only_body), style = Type.small, color = HN.inkDim)
                    }
                }
                if (m != AgentKind.CODEX_MODES.last()) Hairline(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(6.dp))
            HText(stringResource(R.string.cx_au_asks_nobody), style = Type.small, color = HN.inkDim)
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                HText(error, style = Type.small, color = HN.amber)
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** A glass card around [content], or (inside a step's own card) nothing. */
@Composable
private fun Frame(framed: Boolean, error: Boolean, content: @Composable () -> Unit) {
    if (framed) GlassCard(Modifier.fillMaxWidth(), accent = if (error) HN.amber else null) { content() } else content()
}

private fun modeBody(m: String): Int = when (m) {
    AutoMode.ASK -> R.string.au_mode_ask_body
    AutoMode.EDITS -> R.string.au_mode_edits_body
    AutoMode.AUTO -> R.string.au_mode_auto_body
    AutoMode.NO_ASKING -> R.string.au_mode_no_asking_body
    else -> R.string.au_mode_allowed_body
}

// ─── Leaving with changes ──────────────────────────────────────────────────

/** "Discard changes?" with Discard (danger) and Keep editing: the app's confirmation (ConfirmDialog). */
@Composable
private fun DiscardDialog(visible: Boolean, onDiscard: () -> Unit, onKeep: () -> Unit) {
    com.prudnikweb.hypernova.ui.components.ConfirmDialog(
        visible = visible,
        title = stringResource(R.string.au_discard_q),
        body = stringResource(R.string.au_discard_body),
        confirm = stringResource(R.string.au_discard),
        onConfirm = onDiscard,
        onDismiss = onKeep,
        danger = true,
        dismiss = stringResource(R.string.au_keep_editing),
    )
}
