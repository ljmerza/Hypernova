package com.prudnikweb.hypernova.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Account
import com.prudnikweb.hypernova.data.AccountWords
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.CodexModel
import com.prudnikweb.hypernova.data.pick
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.ClaudeEffort
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.ModelCatalog
import com.prudnikweb.hypernova.data.ModelList
import com.prudnikweb.hypernova.data.ModelNames
import com.prudnikweb.hypernova.data.ModelOption
import com.prudnikweb.hypernova.data.NewSessionMemory
import com.prudnikweb.hypernova.data.NewSessionRequest
import com.prudnikweb.hypernova.data.FolderPick
import com.prudnikweb.hypernova.data.ProjectPick
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.ProviderWords
import com.prudnikweb.hypernova.data.healthyOther
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphButton
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.modeGlyph
import com.prudnikweb.hypernova.ui.components.rememberEdgeInput
import com.prudnikweb.hypernova.ui.components.sheetBlur
import com.prudnikweb.hypernova.ui.session.CommandHint
import com.prudnikweb.hypernova.ui.session.CommandSuggestions
import com.prudnikweb.hypernova.ui.session.DraftStrip
import com.prudnikweb.hypernova.ui.session.commandQuery
import com.prudnikweb.hypernova.ui.session.picked
import com.prudnikweb.hypernova.ui.session.rememberAttach
import com.prudnikweb.hypernova.ui.session.rememberCommands
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import com.prudnikweb.hypernova.ui.voice.DictationCapsule
import com.prudnikweb.hypernova.ui.voice.MicButton
import com.prudnikweb.hypernova.ui.voice.VoiceHintHost
import com.prudnikweb.hypernova.ui.voice.askAgain
import com.prudnikweb.hypernova.ui.voice.openAppSettings
import com.prudnikweb.hypernova.ui.voice.rememberVoiceInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The project choice that names a new folder instead of a path. */
internal const val NEW_PROJECT = "\u0000new"
/** The sheet's attachments, as a composer draft. */
internal const val DRAFT = "\u0000new-session"

/** Full-screen dim behind the sheet. Fades on its own, so its edge never slides through the status bar. */
@Composable
fun SheetScrim(onDismiss: () -> Unit) {
    val closeLabel = stringResource(R.string.close)
    Box(
        Modifier
            .fillMaxSize()
            .background(HN.bg.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = stringResource(R.string.close), role = Role.Button, onClick = onDismiss)
            .semantics { contentDescription = closeLabel },
    )
}

/** Which picker is open over the sheet. */
private enum class Picking { Project, Model, Mode, Effort }

/**
 * New session, prompt first (owner, 2026-10-18): the box for what Claude should do, ready to type
 * into, then the choices as chips (the project, the model with its provider, Claude Code's effort,
 * the permission mode), each opening its picker, and Start always in reach at the foot with the
 * attachments and the mic. It starts on the project it was opened for ([initialProject]: a path or
 * a name), else the last one used, with the last model and effort, and in the mode chosen in
 * Settings → New sessions (Fork: Ask until one is; never Bypass). Frosted over the cosmos.
 */
@Composable
fun NewSessionSheet(hub: Hub, initialProject: String?, onStarted: (String) -> Unit, launch: Long = 0L) {
    val haze = LocalHaze.current
    val appScope = LocalAppScope.current
    val context = LocalContext.current
    val opener = LocalOpener.current
    val keyboard = LocalSoftwareKeyboardController.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val projects = hub.projects
    val providers = hub.providers
    val memory = remember { NewSessionMemory.load(context) }
    var project by rememberSaveable { mutableStateOf(ProjectPick.initial(projects, initialProject, memory.project) ?: NEW_PROJECT) }
    var newName by rememberSaveable { mutableStateOf("") }
    // A folder chosen in the folder browser that isn't a project (yet): kept though the list doesn't have it.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    // While the subscription can't take a session (a limit, signed out), start on a provider
    // that can; the owner can still pick another.
    val account = hub.account
    val opened = remember { System.currentTimeMillis() }
    val stopped = account.stopsNewSessions(opened)
    val healthy = if (stopped) providers.healthyOther(account, null, opened) else null
    var providerId by rememberSaveable {
        mutableStateOf(healthy?.id ?: memory.provider?.takeIf { id -> providers.any { it.id == id } } ?: providers.firstOrNull()?.id)
    }
    var model by rememberSaveable { mutableStateOf(memory.model(providerId)) }
    // Fork: Settings → New sessions' mode each time; changed here, it's this session's only.
    var mode by rememberSaveable { mutableStateOf(memory.mode) }
    // Claude Code's effort (data/Effort.kt): the last one started with; null, its own default.
    var effort by rememberSaveable { mutableStateOf(memory.effort) }
    // What was typed survives the sheet being put away (the scrim, Back) or a start that failed
    // after it closed: it comes back the next time it opens (P16).
    var prompt by rememberSaveable { mutableStateOf(NewSessionMemory.draft) }
    val toast = com.prudnikweb.hypernova.ui.LocalToast.current
    var open by remember { mutableStateOf(true) }
    var starting by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            open = false
            // Closed with words in the box (or while they were on their way): kept for next time.
            NewSessionMemory.draft = starting ?: prompt
        }
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf<Picking?>(null) }
    val (attach, pickers) = rememberAttach(DRAFT, hub)
    // Text shared to Hypernova for a new session (ui/share) opens in the box. A launcher shortcut or
    // a share that comes while the sheet is open brings its project (and text) to it too.
    var launched by rememberSaveable { mutableStateOf(launch) }
    LaunchedEffect(launch) {
        if (launch != launched) {
            launched = launch
            (projects.firstOrNull { it.path == initialProject } ?: projects.firstOrNull { it.name == initialProject })?.let { project = it.path }
        }
        com.prudnikweb.hypernova.ui.share.ShareLaunch.takeText()?.let { prompt = if (prompt.isBlank()) it else it + "\n" + prompt }
    }
    // Every model each provider offers (docs/PROTOCOL.md, "Models"), asked for as the sheet opens so
    // the picker has them by the time it's opened; meanwhile what's known here.
    var lists by remember { mutableStateOf<List<ModelList>?>(null) }
    var loadingModels by remember { mutableStateOf(true) }
    LaunchedEffect(hub) {
        hub.models().onSuccess { lists = it }
        loadingModels = false
    }
    // Codex next to Claude Code (data/Codex.kt): offered once it's installed (its first session turns
    // it on), with its own models, reasoning effort and two modes; the providers are Claude Code's.
    // The choice shows only where both agents are installed: a Mac with Claude Code alone never sees
    // it, and one with Codex alone (data/AgentLead.kt) goes straight to Codex.
    val codex = hub.codex
    val claudeHere = com.prudnikweb.hypernova.data.AgentLead.claudeAvailable(hub.claude)
    val codexHere = codex?.available == true
    val offerCodex = claudeHere && codexHere
    var agent by rememberSaveable { mutableStateOf(memory.agent(com.prudnikweb.hypernova.data.AgentLead.leading(hub.claude, codex), claudeHere, codexHere)) }
    val onCodex = codexHere && (agent == AgentKind.CODEX || !claudeHere)
    var codexModel by rememberSaveable { mutableStateOf(memory.codexModel) }
    var codexEffort by rememberSaveable { mutableStateOf(memory.codexEffort) }
    var codexMode by rememberSaveable { mutableStateOf(memory.codexMode) }
    var codexModels by remember { mutableStateOf<List<CodexModel>?>(null) }
    var codexNote by remember { mutableStateOf<String?>(null) }
    val codexOff = stringResource(R.string.cx_models_when_on)
    LaunchedEffect(hub, codexHere, codex?.enabled) {
        if (!codexHere) return@LaunchedEffect
        // Off until its first session: the Mac can't list its models yet, so its default it is.
        if (!codex.enabled) { codexModels = emptyList(); codexNote = codexOff; return@LaunchedEffect }
        hub.codexModels().onSuccess { codexModels = it; codexNote = null }.onFailure { codexModels = emptyList(); codexNote = it.message }
    }
    // What's shown is what's sent: a remembered model Codex no longer offers (or none listed) is its
    // default again, and an effort its model doesn't offer the model's own.
    LaunchedEffect(codexModels) {
        val l = codexModels ?: return@LaunchedEffect
        if (codexModel != null && l.none { it.value == codexModel }) codexModel = null
        if (codexEffort != null && l.pick(codexModel)?.efforts?.contains(codexEffort) != true) codexEffort = null
    }
    val codexChoice = codexModels?.pick(codexModel)
    val files = attach.files
    // Nothing can start while the Mac is away; the draft is kept (NewSessionMemory) and the sheet says so.
    val away = com.prudnikweb.hypernova.ui.session.macAway(hub)
    if (project != NEW_PROJECT && project != picked && projects.none { it.path == project }) project = ProjectPick.initial(projects, null, null) ?: NEW_PROJECT
    val provider = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()
    val options = remember(provider, lists) { provider?.let { ModelCatalog.of(it, lists) } ?: ModelCatalog.CLAUDE }
    // What's shown is what's sent: a remembered model the list no longer has is the default again
    // (while the list is still coming, it's shown by its name).
    val choice: ModelOption = ModelCatalog.find(options, model)
        ?: model?.takeIf { lists == null }?.let { ModelOption(it, ModelNames.chip(it), it) }
        ?: options.first()
    // Claude Code's effort only on the subscription: none is shown or sent for OpenRouter or a
    // custom provider (the remembered one waits for the next session on the subscription).
    val effortOffered = ClaudeEffort.offered(provider)

    // The sheet blurs the cosmos and the home list beneath it.
    val input = rememberEdgeInput(haze, HOME_SOURCE)
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)

    val nameValid = ProjectPick.validName(newName)
    val ready = !busy && (prompt.isNotBlank() || files.isNotEmpty()) && attach.ready && (project != NEW_PROJECT || nameValid)

    // The box is ready to type into as the sheet arrives; after a picker it takes the keyboard back
    // if it had it.
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var refocus by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(Motion.REVEAL.toLong())
        runCatching { focus.requestFocus(); keyboard?.show() }
    }
    LaunchedEffect(picking) {
        if (picking == null && refocus) {
            refocus = false
            delay(120)
            runCatching { focus.requestFocus(); keyboard?.show() }
        }
    }
    fun open(p: Picking) {
        refocus = focused
        picking = p
    }

    val title = stringResource(R.string.new_session)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)).padding(top = statusTop + 16.dp)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(shape)
                .sheetBlur(input, shape)
                .border(1.dp, HN.ink.copy(alpha = 0.10f), shape)
                // Swallow taps so they don't reach the scrim, without becoming a TalkBack button.
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { paneTitle = title }
                .padding(top = 12.dp, bottom = bottom + 12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(HN.lineStrong))
            Spacer(Modifier.height(14.dp))
            Seam(title, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(12.dp))

            // ─── The prompt: what grows scrolls; the choices and Start below it stay put ───
            val voice = rememberVoiceInput(hub, text = { prompt }, setText = { prompt = it; error = null })
            // Start (the pill's, set at the foot below); the prompt's Enter is it too (Settings' Enter key).
            lateinit var start: () -> Unit
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                VoiceHintHost(voice.hint, voice::dismissHint, onAllow = { askAgain(voice) }, onSettings = { openAppSettings(context) })
                // The field stays (hidden) under the capsule, so a keyboard that was open stays open.
                val fieldAlpha by animateFloatAsState(if (voice.listening) 0f else 1f, tween(Motion.DIALOG), label = "field")
                // Held, the capsule takes exactly the field's place; hands-free it takes the room it
                // needs, growing the sheet upward, so the mic under the finger at its foot never moves.
                Box(Modifier.zIndex(if (voice.listening) 1f else 0f)) {
                    Field(
                        prompt, { prompt = it; error = null },
                        modifier = Modifier
                            .focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused }
                            .graphicsLayer { alpha = fieldAlpha },
                        placeholder = stringResource(if (onCodex) R.string.cx_prompt_new_hint else R.string.prompt_new_hint), singleLine = false, minLines = 3, maxLines = 8,
                        label = stringResource(R.string.prompt), commands = true,
                        onSend = { start() }, canSend = ready && !away,
                    )
                    if (voice.listening) DictationCapsule(
                        voice,
                        (if (voice.handsFree) Modifier.fillMaxWidth() else Modifier.matchParentSize()).graphicsLayer { alpha = 1f - fieldAlpha },
                        maxLines = 4, compact = !voice.handsFree, opaque = voice.handsFree,
                    )
                }
                // "/" lists the project's commands and skills here too: a skill makes a good first prompt.
                // (Claude Code's: Codex has none of them.)
                val cmdQuery = if (onCodex) null else commandQuery(prompt)
                val commands = rememberCommands(hub, project.takeIf { it != NEW_PROJECT }, cmdQuery != null)
                // The list opens under the field: scroll it into view as it opens and fills.
                val bring = remember { BringIntoViewRequester() }
                LaunchedEffect(cmdQuery != null, commands.list) {
                    if (cmdQuery != null) {
                        delay(60)
                        bring.bringIntoView()
                    }
                }
                if (cmdQuery != null) {
                    Spacer(Modifier.height(10.dp))
                    CommandSuggestions(commands, cmdQuery, onPick = { prompt = "/${it.name} " }, Modifier.bringIntoViewRequester(bring))
                }
                val picked = commands.picked(prompt)
                if (picked != null && picked.argumentHint.isNotBlank()) CommandHint(picked, Modifier.padding(start = 4.dp, top = 8.dp))
                if (files.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    DraftStrip(files, attach::remove, side = 0.dp)
                }
            }

            // ─── The choices ───
            Spacer(Modifier.height(6.dp))
            Column(Modifier.padding(horizontal = 16.dp)) {
                // Which agent, once Codex is on this Mac; Claude-only Macs never see it.
                if (offerCodex) {
                    Spacer(Modifier.height(4.dp))
                    AgentChoice(agent, { agent = it; error = null }, Modifier.padding(horizontal = 4.dp))
                    Spacer(Modifier.height(2.dp))
                }
                SheetChips(
                    project = project, newName = newName, projects = projects, root = hub.projectsRoot,
                    provider = provider, choice = choice, options = options, mode = if (onCodex) codexMode else mode,
                    onProject = { open(Picking.Project) }, onModel = { open(Picking.Model) }, onMode = { open(Picking.Mode) },
                    effort = effort, onEffort = { open(Picking.Effort) }, offerEffort = effortOffered,
                    codex = if (!onCodex) null else CodexChips(
                        codexModelName(codexModels, codexModel, stringResource(R.string.model_label_default)),
                        (codexEffort ?: codexChoice?.defaultEffort)?.let { effortTitle(it) },
                    ),
                )
                if (project == NEW_PROJECT && newName.isNotEmpty()) {
                    val root = hub.projectsRoot?.let(ProjectPick::tilde) ?: "~"
                    HText(stringResource(R.string.new_project_creates, "$root/${newName.trim()}"), style = Type.monoSmall, color = HN.inkDim, maxLines = 1, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                }
                // The subscription's trouble is Claude Code's: Codex goes by its own account.
                if (stopped && !onCodex) {
                    // One line of why, and where it goes instead (or how to have somewhere to go).
                    val why = whyLine(account, hub.mac.name, opened)
                    val onSubscription = provider == null || provider.builtIn
                    HText(
                        when {
                            healthy == null -> stringResource(R.string.new_session_add_provider, why)
                            !onSubscription -> stringResource(R.string.new_session_runs_on, why, provider?.name ?: healthy.name)
                            else -> stringResource(R.string.new_session_why, why)
                        },
                        style = Type.small, color = if (onSubscription) HN.amber else HN.inkMuted,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
                    )
                    if (healthy == null) Pill(stringResource(R.string.account_add_provider), opener.providers, kind = PillKind.Outline, height = 36.dp, leading = Glyph.Plus)
                }
                // The Mac is away: starting waits for it, and what was typed stays here (it is kept anyway).
                if (away) HText(stringResource(R.string.start_mac_away), style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp))
                if (error != null) HText(error.orEmpty(), style = Type.small, color = HN.danger, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp))
            }

            // ─── The foot: attachments, the mic, Start ───
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphButton(Glyph.Image, stringResource(R.string.attach_photos), pickers.photos, color = HN.inkMuted)
                GlyphButton(Glyph.Camera, stringResource(R.string.attach_camera), pickers.camera, color = HN.inkMuted)
                GlyphButton(Glyph.File, stringResource(R.string.attach_files), pickers.files, color = HN.inkMuted)
                Spacer(Modifier.weight(1f))
                if (voice.available || voice.listening) {
                    MicButton(voice, send = false, canSend = false)
                    Spacer(Modifier.width(6.dp))
                }
                start = start@{
                        // busy read now too: two Enters before the sheet redraws start one session.
                        if (!ready || busy) return@start
                        busy = true
                        error = null
                        val sending = AttachDrafts.take(DRAFT)
                        starting = prompt
                        val name = newName.trim()
                        val path = project.takeIf { it != NEW_PROJECT }
                        // The app's scope: closing the sheet meanwhile doesn't lose the session or the files.
                        appScope.launch {
                            var started = false
                            try {
                                hub.start(
                                    if (onCodex) NewSessionRequest(
                                        projectPath = path,
                                        newProject = name.takeIf { path == null },
                                        prompt = prompt,
                                        providerId = null,
                                        model = codexModel,
                                        permissionMode = codexMode,
                                        files = sending.mapNotNull { it.attachment },
                                        agent = AgentKind.CODEX,
                                        effort = codexEffort,
                                    ) else NewSessionRequest(
                                        projectPath = path,
                                        newProject = name.takeIf { path == null },
                                        prompt = prompt,
                                        providerId = provider?.id,
                                        model = choice.value,
                                        permissionMode = mode,
                                        files = sending.mapNotNull { it.attachment },
                                        effort = effort.takeIf { effortOffered },
                                    ),
                                ).onSuccess { id ->
                                    started = true
                                    val kept = path ?: hub.projectsRoot?.let { "${it.trimEnd('/')}/$name" }
                                    if (onCodex) NewSessionMemory.saveCodex(context, kept, codexModel, codexEffort, codexMode)
                                    else NewSessionMemory.save(context, kept, provider?.id, choice.value, effort)
                                    prompt = ""
                                    newName = ""
                                    starting = null
                                    // Started (also after the sheet was put away): the words kept for it aren't needed.
                                    NewSessionMemory.draft = ""
                                    onStarted(id)
                                }.onFailure {
                                    error = it.message
                                    starting = null
                                    // Nobody sees the sheet's line once it's closed: say it here; the words wait in it.
                                    if (!open) toast(it.message.orEmpty())
                                }
                            } finally {
                                if (!started) AttachDrafts.restore(DRAFT, sending)
                                busy = false
                            }
                        }
                    }
                Pill(
                    if (busy) stringResource(R.string.starting) else stringResource(R.string.start),
                    start,
                    // Nothing to start with yet: a disabled pill, as TalkBack says (P14); "Starting…" stays lit.
                    kind = PillKind.Primary, height = 44.dp, leading = Glyph.Send, enabled = (ready && !away) || busy,
                )
            }
        }
    }

    when (picking) {
        Picking.Project -> ProjectPicker(
            projects, hub.projectsRoot, project, newName, opened,
            onPick = { project = it; picked = it; picking = null },
            onNew = { newName = it; project = NEW_PROJECT; picking = null },
            onDismiss = { picking = null },
            folders = { path, hidden -> hub.folders(path, hidden) },
        )
        Picking.Model -> if (onCodex) CodexModelSheet(
            codexModels, codexNote, codexModel, codexEffort, withEffort = true,
            onDone = { m, e -> codexModel = m; codexEffort = e; picking = null },
            onDismiss = { picking = null },
        ) else ModelPicker(
            providers, lists, loadingModels, provider?.id, choice.value, hub.usage.models, opened,
            providerTrail = { p -> providerTrail(p, account, hub, opened) },
            onPick = { id, value -> providerId = id; model = value; picking = null },
            onDismiss = { picking = null },
        )
        Picking.Mode -> if (onCodex) ModeSheet(
            codexMode,
            onPick = { m -> codexMode = m; picking = null },
            onDismiss = { picking = null },
            modes = AgentKind.CODEX_MODES,
        ) else ModeSheet(
            mode,
            onPick = { m ->
                // Starting in Bypass waits for the fingerprint or the screen lock, as switching to it does
                // (docs/DECISIONS.md, "The owner's morning answers", 2); cancelled, the mode stays as it was.
                // The picker closes first: its own window would hide the line said when the phone has no lock.
                if (m == "bypassPermissions" && m != mode) {
                    picking = null
                    com.prudnikweb.hypernova.ui.components.OwnerCheck.ask(
                        context, context.getString(R.string.check_bypass_title),
                        if (project == NEW_PROJECT) newName.trim() else project.trimEnd('/').substringAfterLast('/'),
                        context.getString(R.string.check_no_lock),
                        onOk = { mode = m }, onFail = { msg -> if (msg != null) toast(msg) },
                    )
                } else {
                    mode = m
                    picking = null
                }
            },
            onDismiss = { picking = null },
        )
        Picking.Effort -> EffortSheet(
            effort,
            onPick = { e -> effort = e; picking = null },
            onDismiss = { picking = null },
        )
        null -> Unit
    }
}

/** Codex's model chip: its model's name, and the reasoning effort after it in tracked caps when there's room. */
private class CodexChips(val model: String, val effort: String?)

/**
 * The chips: the project (a folder, or a plus for a new one), the model (with its provider's name
 * when it isn't the subscription), Claude Code's [effort] ("EFFORT" and its level, "Default" until
 * one is picked), the permission mode (its glyph and words; Bypass in red, Auto in amber). They give
 * way whole on a narrow phone (ChipRow). [codex]: a Codex session's model and effort instead of a
 * provider's model and Claude's effort (its mode is one of Codex's two). [offerEffort]: false on a
 * provider other than the subscription, which has no Effort chip.
 */
@Composable
private fun SheetChips(
    project: String,
    newName: String,
    projects: List<com.prudnikweb.hypernova.data.Project>,
    root: String?,
    provider: Provider?,
    choice: ModelOption,
    options: List<ModelOption>,
    mode: String,
    onProject: () -> Unit,
    onModel: () -> Unit,
    onMode: () -> Unit,
    effort: String?,
    onEffort: () -> Unit,
    codex: CodexChips? = null,
    offerEffort: Boolean = true,
) {
    val p = projects.firstOrNull { it.path == project }
    val rootT = root?.let(ProjectPick::tilde) ?: "~"
    val isNew = project == NEW_PROJECT
    val projectName = when {
        p != null -> p.name
        isNew && newName.isNotEmpty() -> newName
        isNew -> stringResource(R.string.new_project)
        // A folder from the folder browser: its own name.
        project.isNotBlank() -> FolderPick.name(project)
        else -> stringResource(R.string.ns_no_project)
    }
    val projectSaid = when {
        p != null -> stringResource(R.string.ns_project_said, p.name, ProjectPick.parent(p.path))
        !isNew && project.isNotBlank() -> stringResource(R.string.ns_project_said, FolderPick.name(project), ProjectPick.parent(project).ifEmpty { "/" })
        isNew && newName.isNotEmpty() -> stringResource(R.string.ns_new_project_said, newName, rootT)
        else -> stringResource(R.string.ns_new_project_unnamed_said)
    }
    val modelName = codex?.model ?: ModelCatalog.shown(choice, options)
    val other = codex == null && provider != null && !provider.builtIn
    val modelSaid = when {
        codex?.effort != null -> stringResource(R.string.cx_model_effort_said, modelName, codex.effort)
        other -> stringResource(R.string.ns_model_on_said, modelName, provider!!.name)
        else -> stringResource(R.string.ns_model_said, modelName)
    }
    val modeName = modeTitle(mode)
    val modeSaid = stringResource(R.string.ns_mode_said, modeLabel(mode))
    val tone = modeTone(mode)
    val modelChip = @Composable { withProvider: Boolean ->
        PickChip(modelSaid, onModel) {
            if (withProvider && other) {
                HText(provider!!.name.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                Spacer(Modifier.width(6.dp))
            }
            ChipText(modelName)
            // Codex's reasoning effort after its model, while there's room ("GPT-5.5  HIGH").
            if (withProvider && codex?.effort != null) {
                Spacer(Modifier.width(6.dp))
                HText(codex.effort.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
            }
        }
    }
    val modeChip = @Composable { words: Boolean ->
        PickChip(modeSaid, onMode, tint = tone) {
            Icon(modeGlyph(mode), size = 14.dp, color = tone ?: HN.inkMuted, stroke = 1.3.dp)
            if (words) {
                Spacer(Modifier.width(6.dp))
                ChipText(modeName, color = tone ?: HN.ink)
            }
        }
    }
    // Claude Code's effort: its label and level while there's room, then the level alone ("Effort"
    // for the default, which says nothing alone). Codex's is on its model chip instead.
    val effortName = claudeEffortTitle(effort)
    val effortSaid = stringResource(R.string.ns_effort_said, effortName)
    val effortWord = stringResource(R.string.effort)
    val effortChip = @Composable { label: Boolean ->
        PickChip(effortSaid, onEffort) {
            if (label) {
                HText(effortWord.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                Spacer(Modifier.width(6.dp))
            }
            ChipText(if (label || effort != null) effortName else effortWord)
        }
    }
    ChipRow(
        project = {
            PickChip(projectSaid, onProject) {
                Icon(if (isNew) Glyph.Plus else Glyph.Folder, size = 14.dp, color = if (isNew) HN.accent else HN.inkMuted, stroke = 1.3.dp)
                Spacer(Modifier.width(6.dp))
                ChipText(projectName, modifier = Modifier.weight(1f, fill = false))
            }
        },
        model = if (other || codex?.effort != null) listOf<@Composable () -> Unit>({ modelChip(true) }, { modelChip(false) }) else listOf<@Composable () -> Unit>({ modelChip(false) }),
        mode = listOf<@Composable () -> Unit>({ modeChip(true) }, { modeChip(false) }),
        modifier = Modifier.fillMaxWidth(),
        effort = if (codex != null || !offerEffort) emptyList() else listOf<@Composable () -> Unit>({ effortChip(true) }, { effortChip(false) }),
    )
}

/** A provider's line in the model picker: its trouble (amber), what's left on it, or why the subscription rests. */
@Composable
private fun providerTrail(p: Provider, account: Account, hub: Hub, now: Long): Pair<String, Boolean>? {
    val context = LocalContext.current
    if (p.builtIn) {
        if (!account.stopsNewSessions(now)) return null
        return whyLine(account, hub.mac.name, now) to true
    }
    return ProviderWords.trail(context, p, now)
}

/** Why the subscription can't take a session now: "Your subscription rests until 2:00". */
@Composable
private fun whyLine(a: Account, mac: String, now: Long): String {
    val context = LocalContext.current
    return when (a.state) {
        Account.LOGGED_OUT -> stringResource(R.string.why_signed_out, mac)
        Account.NO_SUBSCRIPTION -> stringResource(R.string.why_no_plan)
        Account.ON_HOLD -> stringResource(R.string.why_on_hold)
        Account.NO_CREDIT -> stringResource(R.string.why_credits)
        else -> {
            val l = a.resting(now)
            val at = l?.resetsAt
            when {
                at == null -> AccountWords.label(context, l)
                l.window == "seven_day" -> stringResource(R.string.why_week, AccountWords.clock(context, at, now))
                else -> stringResource(R.string.why_limit, AccountWords.clock(context, at, now))
            }
        }
    }
}

/** `/Users/me/Programming/x` → `~/Programming/x`. */
fun tilde(path: String): String = ProjectPick.tilde(path)
