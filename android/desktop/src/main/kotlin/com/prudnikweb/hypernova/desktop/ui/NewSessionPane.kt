package com.prudnikweb.hypernova.desktop.ui

import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.AgentLead
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.EnterDoes
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.data.EnterKeys
import com.prudnikweb.hypernova.data.FolderPick
import com.prudnikweb.hypernova.data.Folders
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.NewSessionMemory
import com.prudnikweb.hypernova.data.NewSessionRequest
import com.prudnikweb.hypernova.data.Project
import com.prudnikweb.hypernova.data.ProjectPick
import com.prudnikweb.hypernova.data.healthyOther
import com.prudnikweb.hypernova.desktop.DeskIncidents
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.desktop.modeLine
import com.prudnikweb.hypernova.desktop.modeTitle
import com.prudnikweb.hypernova.desktop.whyWords
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The project choice that names a new folder instead of a path, as on the phone's sheet. */
private const val NEW_PROJECT = "\u0000new"

/**
 * New session in the right pane, the phone's sheet (ui/home/NewSessionSheet.kt) with what the desktop can honestly
 * offer: the prompt first, the folder, the agent, the mode. What the phone remembers and how it starts come from
 * `:shared`; the server decides what's allowed. Bypass is chosen each time and is never remembered. Codex's
 * `danger-full-access` and approval `never` aren't offered: only [AgentKind.CODEX_MODES]. Auto and Bypass ask to think
 * twice, as on the phone. docs/DECISIONS.md, "The desktop client's New session" and "No fingerprints or screen locks".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewSessionPane(hub: Hub, shell: Shell, server: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val storage = Core.platform.storage
    val memory = remember { NewSessionMemory.load(storage) }
    val projects = hub.projects
    val providers = hub.providers
    var project by remember { mutableStateOf(ProjectPick.initial(projects, null, memory.project) ?: NEW_PROJECT) }
    var newName by remember { mutableStateOf("") }
    // A folder chosen in the folder browser that isn't a project (yet): kept though the list doesn't have it.
    var picked by remember { mutableStateOf<String?>(null) }
    // While the subscription can't take a session (a limit, signed out), start on a provider that can.
    val account = hub.account
    val opened = remember { System.currentTimeMillis() }
    val stopped = account.stopsNewSessions(opened)
    val healthy = if (stopped) providers.healthyOther(account, null, opened) else null
    val providerId = remember { healthy?.id ?: memory.provider?.takeIf { id -> providers.any { it.id == id } } ?: providers.firstOrNull()?.id }
    val provider = providers.firstOrNull { it.id == providerId } ?: providers.firstOrNull()
    // That server's mode for each agent (NewSessionMemory.startsIn); changed here, it's this session's only.
    var modePick by remember(hub) { mutableStateOf<String?>(null) }
    val mode = modePick ?: NewSessionMemory.startsIn(AgentKind.CLAUDE, hub, memory)
    var codexModePick by remember(hub) { mutableStateOf<String?>(null) }
    val codexMode = codexModePick ?: NewSessionMemory.startsIn(AgentKind.CODEX, hub, memory)
    var prompt by remember { mutableStateOf(TextFieldValue(NewSessionMemory.draft, TextRange(NewSessionMemory.draft.length))) }
    val codex = hub.codex
    val claudeHere = AgentLead.claudeAvailable(hub.claude)
    val codexHere = codex?.available == true
    val offerCodex = claudeHere && codexHere
    var agent by remember { mutableStateOf(memory.agent(AgentLead.leading(hub.claude, codex), claudeHere, codexHere)) }
    val onCodex = codexHere && (agent == AgentKind.CODEX || !claudeHere)
    var open by remember { mutableStateOf(true) }
    var starting by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickingFolder by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf<DeskIncidents.Asked?>(null) }
    val focus = remember { FocusRequester() }
    DisposableEffect(Unit) {
        onDispose {
            open = false
            // Closed with words in the box (or while they were on their way): kept for next time.
            NewSessionMemory.draft = starting ?: prompt.text
        }
    }
    // Nothing can start while the server is away; the words stay here.
    val away = hub.link.state != LinkState.Connected && hub.link.state != LinkState.Unpaired
    if (project != NEW_PROJECT && project != picked && projects.none { it.path == project }) project = ProjectPick.initial(projects, null, null) ?: NEW_PROJECT
    val ready = !busy && prompt.text.isNotBlank() && (project != NEW_PROJECT || ProjectPick.validName(newName))
    val canStart = ready && !away

    fun start() {
        // busy read now too: two Enters before the pane redraws start one session.
        if (!canStart || busy) return
        busy = true
        error = null
        val words = prompt.text
        starting = words
        val name = newName.trim()
        val path = project.takeIf { it != NEW_PROJECT }
        // The app's scope: putting the form away meanwhile doesn't lose the session.
        Core.scope.launch {
            try {
                hub.start(
                    if (onCodex) NewSessionRequest(projectPath = path, newProject = name.takeIf { path == null }, prompt = words, providerId = null, model = null, permissionMode = codexMode, agent = AgentKind.CODEX)
                    else NewSessionRequest(projectPath = path, newProject = name.takeIf { path == null }, prompt = words, providerId = provider?.id, model = null, permissionMode = mode),
                ).onSuccess { id ->
                    val kept = path ?: hub.projectsRoot?.let { "${it.trimEnd('/')}/$name" }
                    if (onCodex) NewSessionMemory.saveCodex(storage, kept, null, null, codexMode)
                    else NewSessionMemory.save(storage, kept, provider?.id, null, null)
                    val stillOpen = open
                    prompt = TextFieldValue("")
                    newName = ""
                    starting = null
                    NewSessionMemory.draft = ""
                    // Asked for and waited on: it's what's on screen next. Put away meanwhile, it's only in the list.
                    if (stillOpen) shell.started(server, id)
                }.onFailure {
                    error = it.message
                    starting = null
                    // Nobody sees the form's line once it's closed: said here, and the words wait in it.
                    if (!open) shell.say(it.message.orEmpty())
                }
            } finally {
                busy = false
            }
        }
    }

    Box(modifier.testTag(PANE_TAG)) {
        if (pickingFolder) {
            FolderPage(
                hub, project, newName,
                onPick = { project = it; picked = it; pickingFolder = false },
                onNew = { newName = it; project = NEW_PROJECT; pickingFolder = false },
                onBack = { pickingFolder = false },
            )
            return@Box
        }
        LaunchedEffect(Unit) {
            // A moment for the list's own focus to settle, then the box is ready to type into.
            delay(50)
            runCatching { focus.requestFocus() }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (onBack != null) Pill(Ui.back.text(), onBack, kind = PillKind.Ghost, height = 32.dp)
            GlassCard(Modifier.widthIn(max = PANE_MAX).fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Seam(Ui.new_session.text(), Modifier.semantics { heading() })
                    PromptBox(
                        prompt, { prompt = it; error = null }, Modifier.focusRequester(focus),
                        placeholder = if (onCodex) Ui.cx_prompt_new_hint.text() else Ui.prompt_new_hint.text(),
                        canStart = canStart, onStart = ::start,
                    )
                    if (offerCodex) {
                        Labelled(Ui.session_sheet_agent.text()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Choice(Ui.claude.text(), !onCodex) { agent = AgentKind.CLAUDE; error = null }
                                Choice(Ui.cx_agent_codex.text(), onCodex) { agent = AgentKind.CODEX; error = null }
                            }
                        }
                    }
                    Labelled(Ui.project.text()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val p = projects.firstOrNull { it.path == project }
                            val shown = when {
                                p != null -> ProjectPick.tilde(p.path)
                                project == NEW_PROJECT && newName.isNotEmpty() -> newName
                                project == NEW_PROJECT -> Ui.ns_no_project.text()
                                else -> ProjectPick.tilde(project)
                            }
                            HText(shown, Modifier.weight(1f), style = Type.monoSmall, color = HN.ink, maxLines = 2)
                            Spacer(Modifier.width(8.dp))
                            Pill(Ui.ns_change.text(), { pickingFolder = true }, kind = PillKind.Ghost, height = 30.dp)
                        }
                        if (project == NEW_PROJECT && newName.isNotEmpty()) {
                            val root = hub.projectsRoot?.let(ProjectPick::tilde) ?: "~"
                            Spacer(Modifier.height(4.dp))
                            HText(Ui.new_project_creates.format("$root/${newName.trim()}"), style = Type.monoSmall, color = HN.inkDim, maxLines = 2)
                        }
                    }
                    Labelled(Ui.mode.text()) {
                        val now = if (onCodex) codexMode else mode
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (m in if (onCodex) AgentKind.CODEX_MODES else NewSessionMemory.START_MODES + "bypassPermissions") {
                                Choice(modeTitle(m), m == now) {
                                    when {
                                        onCodex -> codexModePick = m
                                        m == mode -> Unit
                                        // Auto and Bypass stop at the think-twice card, every time; with no list to show, Auto goes on and Bypass doesn't.
                                        m == "auto" -> DeskIncidents.next()?.let { thinking = DeskIncidents.Asked(m, it) } ?: run { modePick = m }
                                        m == "bypassPermissions" -> DeskIncidents.next()?.let { thinking = DeskIncidents.Asked(m, it) }
                                        else -> modePick = m
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        HText(modeLine(now), style = Type.small, color = if (now == "bypassPermissions") HN.danger else if (now == "auto") HN.amber else HN.inkMuted)
                    }
                    HText(Ui.desk_new_defaults.text(), style = Type.small, color = HN.inkDim)
                    // The subscription's trouble is Claude Code's: Codex goes by its own account.
                    if (stopped && !onCodex) {
                        val why = whyWords(account, hub.mac.name, opened)
                        val onSubscription = provider == null || provider.builtIn
                        HText(
                            when {
                                healthy == null -> Ui.new_session_add_provider.format(why)
                                !onSubscription -> Ui.new_session_runs_on.format(why, provider?.name ?: healthy.name)
                                else -> Ui.new_session_why.format(why)
                            },
                            style = Type.small, color = if (onSubscription) HN.amber else HN.inkMuted,
                        )
                    }
                    if (away) HText(Ui.start_mac_away.text(), style = Type.small, color = HN.inkMuted)
                    error?.let { HText(it, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = Type.small, color = HN.danger) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        Pill(Ui.cancel.text(), { shell.newSession = false }, kind = PillKind.Ghost)
                        Pill(if (busy) Ui.starting.text() else Ui.start.text(), ::start, kind = PillKind.Primary, enabled = canStart || busy)
                    }
                }
            }
        }
    }
    thinking?.let { asked ->
        ThinkTwice(asked, onKeep = { thinking = null }, onGoOn = { modePick = asked.mode; thinking = null })
    }
}

/** What's asked for, under its name. */
@Composable
private fun Labelled(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        HText(label.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/** One of a few, lit when it's the one. */
@Composable
private fun Choice(word: String, on: Boolean, onClick: () -> Unit) {
    Pill(word, onClick, Modifier.semantics { selected = on }, kind = if (on) PillKind.Primary else PillKind.Ghost, height = 32.dp)
}

/** The prompt: the phone's Enter key, its default as there's no Typing setting here. Enter starts, Shift+Enter is a new line. A skill and the lines after it go as they're typed. */
@Composable
private fun PromptBox(value: TextFieldValue, onChange: (TextFieldValue) -> Unit, modifier: Modifier, placeholder: String, canStart: Boolean, onStart: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    val label = Ui.prompt.text()
    BasicTextField(
        value, onChange,
        minLines = 3,
        maxLines = 8,
        textStyle = Type.body.copy(color = HN.ink),
        cursorBrush = SolidColor(HN.accent),
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || (e.key != Key.Enter && e.key != Key.NumPadEnter)) return@onPreviewKeyEvent false
                when (EnterKeys.decide(EnterKey.DEFAULT, e.isShiftPressed, canStart)) {
                    EnterDoes.Send -> onStart()
                    EnterDoes.NewLine -> {
                        val s = value.selection
                        val text = value.text.replaceRange(s.min, s.max, "\n")
                        onChange(TextFieldValue(text, TextRange(s.min + 1)))
                    }
                    EnterDoes.Nothing -> Unit
                }
                true
            },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().background(HN.bg800.copy(alpha = 0.6f), shape)
                    .border(1.dp, if (focused) HN.accentDim else HN.ink.copy(alpha = 0.10f), shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.text.isEmpty()) HText(placeholder, style = Type.body, color = HN.inkDim, maxLines = 2)
                inner()
            }
        },
    )
}

/** Before Auto or Bypass, one real case of an agent with too much freedom, every time. "Keep asking" has the focus and is where Enter goes. */
@Composable
private fun ThinkTwice(asked: DeskIncidents.Asked, onKeep: () -> Unit, onGoOn: () -> Unit) {
    val focus = remember { FocusRequester() }
    val bypass = asked.mode == "bypassPermissions"
    Modal(onKeep) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        HText((if (bypass) Ui.think_kicker_bypass else Ui.think_kicker_auto).text().uppercase(), style = Type.label, color = if (bypass) HN.danger else HN.amber)
        Spacer(Modifier.height(8.dp))
        HText(asked.incident.headline, Modifier.semantics { heading() }, style = Type.title)
        Spacer(Modifier.height(8.dp))
        HText(asked.incident.what, style = Type.small, color = HN.inkMuted)
        Spacer(Modifier.height(8.dp))
        HText(Ui.think_ai_mistakes.text(), style = Type.small, color = HN.ink)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            Pill((if (bypass) Ui.think_use_bypass else Ui.think_use_auto).text(), onGoOn, kind = PillKind.Ghost)
            Pill(Ui.think_keep.text(), onKeep, Modifier.focusRequester(focus), kind = PillKind.Primary)
        }
    }
}

/**
 * Where it runs, the phone's project picker (ProjectPicker): search, the recent ones then the rest by folder, a new
 * project named in the projects folder, or any folder at all through the folder browser.
 */
@Composable
private fun FolderPage(hub: Hub, selected: String, newName: String, onPick: (String) -> Unit, onNew: (String) -> Unit, onBack: () -> Unit) {
    var browsing by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).widthIn(max = PANE_MAX)) {
        val from = browsing
        when {
            from != null -> FolderBrowser(from, selected, { p, h -> hub.folders(p, h) }, onPick) { browsing = null }
            naming -> NewProjectForm(newName, hub.projectsRoot, onNew) { naming = false }
            else -> ProjectList(hub, selected, onPick, onNew, { naming = true }, { browsing = it }, onBack)
        }
    }
}

@Composable
private fun PageTop(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Pill(Ui.back.text(), onBack, kind = PillKind.Ghost, height = 32.dp)
        Spacer(Modifier.width(10.dp))
        HText(title, Modifier.semantics { heading() }, style = Type.title, maxLines = 1)
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun ColumnScope.ProjectList(hub: Hub, selected: String, onPick: (String) -> Unit, onNew: (String) -> Unit, onName: () -> Unit, onBrowse: (String) -> Unit, onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val projects = hub.projects
    val rootT = hub.projectsRoot?.let(ProjectPick::tilde) ?: "~"
    val groups = remember(projects, hub.projectsRoot) { ProjectPick.groups(projects, hub.projectsRoot) }
    val found = remember(projects, query) { ProjectPick.search(projects, query) }
    val typed = ProjectPick.asName(query)
    val offer = typed.isNotEmpty() && ProjectPick.validName(typed) && projects.none { it.name.equals(typed, ignoreCase = true) }
    val typedPath = query.trim().takeIf { it.startsWith("/") || it.startsWith("~") }
    PageTop(Ui.project.text(), onBack)
    SearchBox(query, { query = it }, Ui.ns_search_projects.text(), { false })
    Spacer(Modifier.height(6.dp))
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
        if (query.isBlank() || found.isEmpty()) item(key = "new") {
            PickLine(Ui.new_project.text(), if (offer) "$rootT/$typed" else Ui.ns_new_project_in.format(rootT), false) { if (offer) onNew(typed) else onName() }
        }
        if (query.isBlank() || typedPath != null) item(key = "browse") {
            PickLine(Ui.ns_browse.text(), typedPath ?: Ui.ns_browse_sub.text(), false) {
                onBrowse(typedPath ?: selected.takeIf { !it.startsWith("\u0000") }?.let { it.trimEnd('/') + "/" } ?: "~/")
            }
        }
        if (query.isBlank()) {
            for (g in groups) {
                val label = when (g.kind) {
                    ProjectPick.Kind.Recent -> Ui.ns_recent.text()
                    ProjectPick.Kind.Worktrees -> Ui.ns_worktrees.format(g.label.orEmpty())
                    else -> g.label.orEmpty()
                }
                item(key = "h:${g.kind}:${g.label}") {
                    HText(label.uppercase(), Modifier.padding(start = 10.dp, top = 14.dp, bottom = 4.dp).semantics { heading() }, style = Type.label, color = HN.inkMuted, maxLines = 1)
                }
                items(g.projects, key = { "p:${g.kind}:${it.path}" }) { p -> ProjectLine(p, selected, onPick) }
            }
        } else {
            if (found.isEmpty()) item(key = "none") {
                HText(Ui.ns_no_match.format(query.trim()), Modifier.padding(horizontal = 10.dp, vertical = 14.dp), style = Type.small, color = HN.inkDim)
            }
            items(found, key = { "f:${it.path}" }) { p -> ProjectLine(p, selected, onPick) }
        }
    }
}

@Composable
private fun ProjectLine(p: Project, selected: String, onPick: (String) -> Unit) {
    PickLine(p.name, ProjectPick.tilde(p.path), p.path == selected) { onPick(p.path) }
}

/** A line to choose: Tab reaches it, Enter presses it, the chosen one is lit. */
@Composable
private fun PickLine(name: String, sub: String?, on: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .background(if (on) HN.accent.copy(alpha = 0.10f) else if (hovered) HN.ink.copy(alpha = 0.05f) else Color.Transparent, shape)
            .border(1.dp, if (focused) HN.ink else Color.Transparent, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverable(interaction)
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        HText(name, style = Type.body, color = if (on) HN.accent else HN.ink, maxLines = 1)
        if (sub != null) HText(sub, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
    }
}

/** A one-line box in mono, for a folder's name or path. */
@Composable
private fun LineBox(value: String, onChange: (String) -> Unit, label: String, onEnter: () -> Unit, modifier: Modifier = Modifier, error: Boolean = false) {
    val shape = RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value, onChange,
        singleLine = true,
        textStyle = Type.mono.copy(color = HN.ink),
        cursorBrush = SolidColor(HN.accent),
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                    onEnter()
                    true
                } else false
            },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().background(HN.bg900.copy(alpha = 0.9f), shape)
                    .border(1.dp, if (error) HN.danger else if (focused) HN.accentDim else HN.line, shape)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) {
                if (value.isEmpty()) HText(label, style = Type.mono, color = HN.inkDim, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
private fun NewProjectForm(initial: String, root: String?, onUse: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = ProjectPick.validName(name)
    val rootT = root?.let(ProjectPick::tilde) ?: "~"
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    PageTop(Ui.new_project.text(), onCancel)
    HText(Ui.ns_name_it.text(), style = Type.small, color = HN.inkMuted)
    Spacer(Modifier.height(8.dp))
    LineBox(name, { name = it.replace(' ', '-') }, Ui.new_project_hint.text(), { if (valid) onUse(name.trim()) }, Modifier.focusRequester(focus), error = name.isNotEmpty() && !valid)
    Spacer(Modifier.height(8.dp))
    HText(
        if (name.isNotEmpty() && !valid) Ui.new_project_invalid.text() else Ui.new_project_creates.format("$rootT/${name.trim().ifEmpty { Ui.new_project_hint.text() }}"),
        style = Type.monoSmall, color = if (name.isNotEmpty() && !valid) HN.danger else HN.inkDim,
    )
    Spacer(Modifier.height(14.dp))
    Pill(Ui.ns_use_name.text(), { if (valid) onUse(name.trim()) }, kind = PillKind.Primary, enabled = valid)
}

/** The phone's folder browser, by [FolderPick]: a path to type or paste, the folders in it to go into, and Use for the one it's on. */
@Composable
private fun ColumnScope.FolderBrowser(from: String, selected: String, load: suspend (String, Boolean) -> Result<Folders>, onUse: (String) -> Unit, onBack: () -> Unit) {
    var typed by remember { mutableStateOf(from) }
    var hidden by remember { mutableStateOf(false) }
    // The last folder listed, and the folder as typed it answers: an answer to an older one is never used.
    var listed by remember { mutableStateOf<Folders?>(null) }
    var listedFor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var jump by remember { mutableStateOf(true) }
    val (dir, start) = FolderPick.split(typed)
    LaunchedEffect(dir, hidden) {
        // Typing waits a moment, so not every letter asks the computer; a click goes at once.
        if (!jump) delay(250)
        jump = false
        load(dir, hidden)
            .onSuccess { listed = it; listedFor = dir; error = null }
            .onFailure { listedFor = null; error = it.message }
    }
    val home = listed?.home
    val current = listed?.takeIf { listedFor == dir && error == null }
    val shown = current?.let { FolderPick.matching(it.folders, start) }.orEmpty()
    val chosen = current?.let { FolderPick.chosen(it, start) }
    val go = { path: String -> jump = true; typed = FolderPick.field(path, home) }
    PageTop(Ui.ns_folder_title.text(), onBack)
    LineBox(typed, { typed = it }, Ui.ns_folder_path.text(), { chosen?.let(onUse) })
    error?.let {
        Spacer(Modifier.height(6.dp))
        HText(it, style = Type.small, color = HN.danger)
    }
    Spacer(Modifier.height(6.dp))
    Choice(Ui.ns_folder_hidden.text(), hidden) { jump = true; hidden = !hidden }
    Spacer(Modifier.height(6.dp))
    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
        if (current == null) {
            if (error == null) item(key = "loading") { HText(Ui.loading.text(), Modifier.padding(10.dp), style = Type.small, color = HN.inkDim) }
            return@LazyColumn
        }
        val parent = current.parent
        if (parent != null && start.isEmpty()) item(key = "up") { PickLine(Ui.up_one.text(), FolderPick.tilde(parent, home), false) { go(parent) } }
        items(shown, key = { it.path }) { f -> PickLine(f.name, null, f.path == selected) { go(f.path) } }
        if (shown.isEmpty()) item(key = "none") {
            HText(if (start.isEmpty()) Ui.ns_folder_empty.text() else Ui.ns_no_match.format(start), Modifier.padding(10.dp), style = Type.small, color = HN.inkDim)
        }
        if (current.truncated) item(key = "cut") {
            HText(Ui.ns_folder_truncated.format(current.folders.size, current.total), Modifier.padding(10.dp), style = Type.small, color = HN.inkDim)
        }
    }
    Spacer(Modifier.height(6.dp))
    HText(chosen?.let { FolderPick.tilde(it, home) } ?: " ", style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
    Spacer(Modifier.height(6.dp))
    Pill(
        chosen?.let { Ui.ns_use_folder.format(FolderPick.name(it)) } ?: Ui.ns_use_this_folder.text(),
        { chosen?.let(onUse) }, kind = PillKind.Primary, enabled = chosen != null,
    )
}
