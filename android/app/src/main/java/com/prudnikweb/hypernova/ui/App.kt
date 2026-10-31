package com.prudnikweb.hypernova.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.HubViewModel
import com.prudnikweb.hypernova.data.extendBackpack
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.LinkState
import kotlinx.coroutines.flow.first
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Route
import com.prudnikweb.hypernova.data.Usage
import com.prudnikweb.hypernova.ui.components.ConfirmDialog
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.NoticeHost
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.ui.components.ToastHost
import com.prudnikweb.hypernova.ui.components.Z
import com.prudnikweb.hypernova.ui.files.FileViewer
import com.prudnikweb.hypernova.ui.files.FilesScreen
import com.prudnikweb.hypernova.ui.fx.Cosmos
import com.prudnikweb.hypernova.ui.fx.Grain
import com.prudnikweb.hypernova.ui.fx.ProvideAmbientClock
import com.prudnikweb.hypernova.ui.home.HomeScreen
import com.prudnikweb.hypernova.ui.home.NewSessionSheet
import com.prudnikweb.hypernova.ui.home.SheetScrim
import com.prudnikweb.hypernova.ui.onboarding.Onboarding
import com.prudnikweb.hypernova.ui.onboarding.PairScreen
import com.prudnikweb.hypernova.ui.onboarding.UnpairedScreen
import com.prudnikweb.hypernova.ui.session.SessionScreen
import com.prudnikweb.hypernova.ui.settings.ProviderEditScreen
import com.prudnikweb.hypernova.ui.settings.ProvidersScreen
import com.prudnikweb.hypernova.ui.settings.SettingsTab
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.web.WebScreen
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import com.prudnikweb.hypernova.ui.fx.SkyFilter
import com.prudnikweb.hypernova.ui.fx.SkyPlanner
import com.prudnikweb.hypernova.data.AgentLead
import com.prudnikweb.hypernova.data.bothAgents
import com.prudnikweb.hypernova.data.codexLeads
import com.prudnikweb.hypernova.ui.fx.SkySplit
import kotlinx.coroutines.launch

/** Show a short message over everything (errors, confirmations). */
val LocalToast = staticCompositionLocalOf<(String) -> Unit> { {} }

/** What screens can ask the app to do: open things, which may need the Mac first. */
class Opener(
    val session: (String) -> Unit,
    /** Swap the current session screen for another (a terminal session that forked); the same rule as [session]. */
    val switchSession: (String) -> Unit,
    val files: (root: String, title: String) -> Unit,
    val file: (String) -> Unit,
    /** Ask the Mac for a signed preview URL ([path] of an HTML file, or a dev server [port]), then open it. */
    val preview: (path: String?, port: Int?, title: String) -> Unit,
    val web: (url: String, title: String) -> Unit,
    /** A session's live terminal (sessions running in a shared terminal). */
    val terminal: (sessionId: String) -> Unit = {},
    /** One of a session's agents, read-only. */
    val agent: (sessionId: String, agentId: String) -> Unit = { _, _ -> },
    /** Settings › Providers, over whatever is open (the new-session sheet closes first). */
    val providers: () -> Unit = {},
    /** One provider's settings (its key, its models): the way to fix its trouble. */
    val provider: (id: String, kind: String) -> Unit = { _, _ -> },
    /** The editor on the "reset" template, in this folder: from a limited session. */
    val runAtReset: (project: String) -> Unit = {},
    /** An automation's editor (an automated session's status line opens the one that started it). */
    val automation: (String) -> Unit = {},
    /** Settings › Mac › Connection log: what the app tried to reach the Mac (the offline card links to it). */
    val connectionLog: () -> Unit = {},
    /** The Settings tab, scrolled to one part ([com.prudnikweb.hypernova.ui.settings.SettingsJump]): "Turn on in Settings". */
    val settings: (key: String) -> Unit = {},
)

val LocalOpener = staticCompositionLocalOf<Opener> { error("No Opener") }

/** A scope that outlives screens: a prompt being sent finishes even if you leave the session. */
val LocalAppScope = staticCompositionLocalOf<kotlinx.coroutines.CoroutineScope> { error("No app scope") }

/** Ask for notification permission (or open the system settings if it was refused). */
val LocalAskNotifications = staticCompositionLocalOf<() -> Unit> { {} }

/** Ask before doing something irreversible. */
val LocalConfirm = staticCompositionLocalOf<(com.prudnikweb.hypernova.data.Confirm) -> Unit> { {} }

/**
 * Layers, back to front:
 *  1. the cosmos (one instance for the whole app, retained across rotation, so it never restarts),
 *  2. the current screen (onboarding when nothing is paired),
 *  3. the sheet (dim and panel animate separately),
 *  4. pairing / unpaired overlays, toasts,
 *  5. grain over everything.
 */
@Composable
fun App(vm: HubViewModel, askNotifications: () -> Unit) {
    val hub = vm.hub
    val cosmos = vm.cosmos
    val nav = vm.nav
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    val screens = rememberSaveableStateHolder()
    var sheet by rememberSaveable { mutableStateOf(false) }
    // The project the new-session sheet opens on (a path, or the filtered project's name), and the
    // launch that asked for it: its own, not the list's filter, which lets go of a project with
    // nothing live in it at once (a launcher shortcut's project is often one).
    var sheetProject by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetLaunch by rememberSaveable { mutableStateOf(0L) }
    // What the home list shows: one project (its planet framed in the sky), the quiet ones (the
    // belt framed), or everything (both off).
    var focusProject by rememberSaveable { mutableStateOf<String?>(null) }
    var quietFilter by rememberSaveable { mutableStateOf(false) }
    // An account banner was tapped: Home scrolls to the account card (and clears this).
    val toAccount = remember { mutableStateOf(false) }
    // A note from several automations was tapped: Home shows the Automations tab (and clears this).
    val toAutomations = remember { mutableStateOf(false) }
    val sessions = hub?.sessions.orEmpty()
    // Ended sessions live in History; the sky shows what's alive. A conversation that switched
    // agents shows as its newest session, as on Home.
    val sky = remember(sessions) { sessions.filter { it.phase != Phase.Ended && it.switchedTo == null } }
    // Both agents in use (data/AgentLead.kt): Codex has a star of its own, its sessions go round it.
    val codexStar = hub?.bothAgents == true
    // Which projects are planets and which are quiet (the sun's sessions only: the companion star
    // has the other providers', Codex's star Codex's while both agents are in use). Looked at again
    // each minute, as a project goes quiet a day after its last activity; a plan that hasn't
    // changed doesn't redraw anything.
    val plan = remember { mutableStateOf(SkyPlanner.Plan.Empty) }
    LaunchedEffect(sky, codexStar) {
        val own = SkySplit.sun(sky, codexStar)
        while (true) {
            plan.value = SkyPlanner.plan(own, System.currentTimeMillis())
            delay(60_000)
        }
    }
    val filter: SkyFilter? = focusProject?.let { SkyFilter.Project(it) } ?: if (quietFilter) SkyFilter.Quiet else null
    val onFilter: (SkyFilter?) -> Unit = { f ->
        focusProject = (f as? SkyFilter.Project)?.name
        quietFilter = f == SkyFilter.Quiet
    }
    // A project that's gone, or a belt that's emptied, ends its filter (not before the first plan).
    if (hub != null && plan.value !== SkyPlanner.Plan.Empty && !SkyFilter.stillThere(filter, sky, plan.value.quietNames)) onFilter(null)
    if (focusProject != null && sky.none { it.project == focusProject }) focusProject = null
    if (hub == null && sheet) sheet = false

    val route = nav.top
    val unpaired = !vm.demo && vm.live?.link?.state == LinkState.Unpaired
    // The solar system steps back behind full-screen states, so its labels never sit under their text.
    val onHome = route == Route.Home && hub != null && vm.pairFlow == null && !unpaired && !vm.addingServer
    val focus by animateFloatAsState(if (onHome) 1f else 0f, tween(Motion.SECTION, easing = Motion.Expo), label = "focus")
    // Written by the home screen: the solar systems fade as the list scrolls over them,
    // and hide on the History and Settings tabs. Read only while drawing.
    val skyVisibility = remember { mutableFloatStateOf(1f) }
    // Before pairing there's no usage: a calm young star (hidden anyway, focus is 0).
    val idleUsage = remember { Usage(0.05f, 0.05f, System.currentTimeMillis() + 5 * 3_600_000L, System.currentTimeMillis() + 7 * 86_400_000L, known = false) }
    // The sun is the leading agent's usage: Claude Code's, or Codex's where Claude Code isn't installed.
    val codexLeads = hub?.codexLeads == true
    val codexState = hub?.codex
    val sunUsage = remember(codexLeads, codexState) { if (codexLeads) AgentLead.codexSunUsage(codexState, System.currentTimeMillis()) else null }

    // A limit you can feel: a heavy thud as the disk drains, and a second just after.
    val view = LocalView.current
    val blasts = cosmos.blastCount
    LaunchedEffect(blasts) {
        if (blasts <= cosmos.blastsFelt) return@LaunchedEffect // already felt (e.g. before a rotation)
        cosmos.blastsFelt = blasts
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        delay(140)
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }

    // A tapped approval notification opens its session once the hub knows it.
    // An automation's notification: its editor, or the Automations tab for several, once the hub
    // knows the Mac's automations.
    val pendingAutomation = vm.pendingAutomation
    val autosKnown = hub?.automations?.state != null
    LaunchedEffect(pendingAutomation, hub, autosKnown) {
        val id = pendingAutomation ?: return@LaunchedEffect
        if (hub == null || !autosKnown) return@LaunchedEffect
        sheet = false
        nav.reset()
        if (hub.automations.automation(id) != null) nav.push(Route.AutomationEdit(id)) else toAutomations.value = true
        vm.pendingAutomation = null
    }

    val pendingOpen = vm.pendingOpen
    LaunchedEffect(pendingOpen, hub, sessions) {
        val id = pendingOpen ?: return@LaunchedEffect
        if (hub?.session(id) != null) {
            sheet = false
            nav.openSession(id, fresh = true)
            vm.pendingOpen = null
        }
    }
    // One the hub doesn't know (it ended and left, audit P11): it isn't waited for forever, nor
    // opened minutes later out of nowhere. Once the hub has caught up and 10 s more have passed,
    // or after 30 s without reaching the Mac, it's said and let go.
    val openContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(pendingOpen, hub) {
        val id = pendingOpen ?: return@LaunchedEffect
        val h = hub ?: return@LaunchedEffect
        val synced = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
            androidx.compose.runtime.snapshotFlow { h.link.state }.first { it == LinkState.Connected || it == LinkState.Demo }
        }
        if (synced != null) kotlinx.coroutines.delay(10_000L)
        if (vm.pendingOpen == id && h.session(id) == null) {
            vm.pendingOpen = null
            vm.showToast(openContext.getString(if (synced != null) R.string.open_gone else R.string.open_unreachable))
        }
    }

    // A launcher shortcut or Share to Hypernova asks for the new-session sheet, on a project or not.
    val launch = com.prudnikweb.hypernova.ui.share.ShareLaunch.newSession
    LaunchedEffect(launch, hub) {
        if (launch == null || hub == null) return@LaunchedEffect
        nav.reset()
        focusProject = hub.projects.firstOrNull { it.path == launch.projectPath }?.name
        sheetProject = launch.projectPath
        sheetLaunch = launch.id
        sheet = true
        com.prudnikweb.hypernova.ui.share.ShareLaunch.newSession = null
    }

    BackHandler(enabled = sheet || nav.depth > 1 || filter != null) {
        when {
            sheet -> sheet = false
            nav.pop() -> Unit
            else -> onFilter(null) // the whole list again
        }
    }

    val scope = rememberCoroutineScope()
    var previewBusy by remember { mutableStateOf(false) }
    val opener = remember(hub, nav) {
        Opener(
            session = { nav.openSession(it) },
            switchSession = { nav.openSession(it) },
            files = { root, title -> nav.push(Route.Files(root, title)) },
            file = { nav.push(Route.FileView(it)) },
            preview = { path, port, title ->
                val h = hub
                if (h != null && !previewBusy) {
                    previewBusy = true
                    scope.launch {
                        h.preview(path, port)
                            .onSuccess { nav.push(Route.Web(it, title)) }
                            .onFailure { vm.showToast(it.message.orEmpty()) }
                        previewBusy = false
                    }
                }
            },
            web = { url, title -> nav.push(Route.Web(url, title)) },
            terminal = { nav.push(Route.Terminal(it)) },
            // Fork: an agent opened from another agent's screen takes its place rather than stacking
            // on it, so Back always lands on the session, where its agents are chosen.
            agent = { sessionId, agentId ->
                val r = Route.Agent(sessionId, agentId)
                if (nav.top is Route.Agent) nav.replaceTop(r) else nav.push(r)
            },
            providers = {
                sheet = false
                nav.push(Route.Providers)
            },
            provider = { id, kind ->
                sheet = false
                nav.push(Route.ProviderEdit(id, kind))
            },
            automation = { nav.push(Route.AutomationEdit(it)) },
            connectionLog = { nav.push(Route.ConnectionLog) },
            settings = { key ->
                sheet = false
                nav.reset()
                com.prudnikweb.hypernova.ui.settings.SettingsJump.show(key)
            },
            runAtReset = { project -> nav.push(Route.AutomationEdit(null, com.prudnikweb.hypernova.ui.automations.Templates.RESET, project = project)) },
        )
    }

    // Every link the chat draws comes here when tapped: the web asks first, a dev server opens its preview.
    val links = remember(opener) { com.prudnikweb.hypernova.ui.components.LinkHandler(opener) }
    ProvideAmbientClock(vm.clock) {
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalUriHandler provides links,
            LocalHaze provides haze, LocalToast provides vm::showToast, LocalOpener provides opener,
            LocalConfirm provides { vm.confirm = it },
            LocalAppScope provides vm.appScope,
            LocalAskNotifications provides askNotifications,
        ) {
            Box(Modifier.fillMaxSize()) {
                // The sky band starts just below the real top bar (status bar + brand row),
                // in dp, so it never slides under the blurred chrome on short screens.
                val density = LocalDensity.current
                val winH = LocalWindowInfo.current.containerSize.height
                val topPx = WindowInsets.statusBars.getTop(density) + with(density) { 96.dp.toPx() }
                val skyTop = if (winH > 0) (topPx / winH).coerceIn(0.08f, 0.3f) else 0.12f
                Cosmos(
                    sessions = sky,
                    usage = sunUsage ?: hub?.usage ?: idleUsage,
                    state = cosmos,
                    skyTop = skyTop,
                    plan = plan.value,
                    selected = if (route == Route.Home) filter else null,
                    focus = { focus * skyVisibility.floatValue },
                    modifier = Modifier.fillMaxSize(),
                    // The bars blur only the far sky (and their screen): it changes a few times a
                    // second, the planets up to 30, and blurring the bars again for each planet
                    // frame was work nobody could see (the system sits below the top bar).
                    backdrop = Modifier.hazeSource(haze, zIndex = Z.COSMOS),
                    providers = hub?.providers.orEmpty(),
                    codex = codexState,
                    codexStar = codexStar,
                )

                // A full-screen state (pairing, unpaired) replaces the screen instead of covering
                // it, so nothing underneath can read through; the sky stays.
                // "Add a server" (the switcher) shows the onboarding's steps in the screen's place.
                val adding = vm.addingServer && vm.pairFlow == null
                val covered = vm.pairFlow != null || unpaired || adding
                if (adding || (!covered && hub == null)) {
                    Onboarding(
                        onPaste = vm::handleLink,
                        onDemo = { vm.switchDemo(true) },
                        onClose = if (adding && hub != null) ({ vm.addServer(false) }) else null,
                    )
                } else if (!covered && hub != null) {
                    Screens(hub, vm, route, nav.depth, screens, cosmos, skyVisibility, filter, onFilter, plan.value, { sheetProject = focusProject; sheet = true }, toAccount, toAutomations)
                }

                if (hub != null && !covered) {
                    AnimatedVisibility(visible = sheet, enter = fadeIn(tween(Motion.DIALOG)), exit = fadeOut(tween(Motion.DIALOG))) {
                        SheetScrim(onDismiss = { sheet = false })
                    }
                    AnimatedVisibility(
                        visible = sheet,
                        enter = slideInVertically(tween(Motion.REVEAL, easing = Motion.Expo)) { it / 3 } + fadeIn(tween(Motion.DIALOG)),
                        exit = slideOutVertically(tween(Motion.DIALOG)) { it / 4 } + fadeOut(tween(Motion.DIALOG)),
                    ) {
                        NewSessionSheet(
                            hub = hub,
                            initialProject = sheetProject,
                            launch = sheetLaunch,
                            onStarted = { id ->
                                sheet = false
                                focusProject = null
                                nav.openSession(id)
                            },
                        )
                    }
                }

                // The Mac forgot this phone (401): say so, and offer the way back.
                val live = vm.live
                if (unpaired && live != null && !adding) {
                    UnpairedScreen(
                        macName = live.mac.name,
                        onPairAgain = vm::pairAgain,
                        onDemo = { vm.switchDemo(true) },
                    )
                }

                val flow = vm.pairFlow
                if (flow != null) {
                    PairScreen(
                        flow = flow,
                        onRetry = vm::pair,
                        onClose = vm::closePairFlow,
                        onDone = {
                            vm.closePairFlow()
                            askNotifications()
                        },
                    )
                }

                val confirm = vm.confirm
                val lastConfirm = remember { arrayOfNulls<com.prudnikweb.hypernova.data.Confirm>(1) }
                if (confirm != null) lastConfirm[0] = confirm
                val shown = lastConfirm[0]
                if (shown != null) {
                    ConfirmDialog(
                        visible = confirm != null,
                        title = shown.title, body = shown.body, confirm = shown.action, danger = shown.danger,
                        onConfirm = { vm.confirm = null; shown.onConfirm() },
                        onDismiss = { vm.confirm = null },
                    )
                }

                // A tapped link in the chat: "Open this link?" (ui/components/LinkConfirm.kt).
                com.prudnikweb.hypernova.ui.components.LinkConfirmHost(links, vm::showToast)

                ToastHost(vm.toast, onDismiss = vm::dismissToast)

                // "Speaking · Stop" while anything is read aloud on this phone; a session's chat shows
                // its own beside Interrupt (ui/voice/Speaking.kt).
                if (hub != null && !covered) com.prudnikweb.hypernova.ui.voice.SpeakingBarHost(hidden = route is Route.Session)

                // Banners for what happens elsewhere: not for the session that's on screen.
                val context = androidx.compose.ui.platform.LocalContext.current
                val onScreen by androidx.compose.runtime.rememberUpdatedState(
                    when (val r = route) {
                        is Route.Session -> r.id
                        is Route.Terminal -> r.sessionId
                        // An agent's screen is part of its session.
                        is Route.Agent -> r.sessionId
                        else -> null
                    },
                )
                // On Home the cards already say what a session's banner would (owner, 2026-10-30):
                // its banners wait for another screen; the app's own (an update, the account) still show.
                val atHome by androidx.compose.runtime.rememberUpdatedState(route == Route.Home)
                LaunchedEffect(hub) {
                    hub?.notices?.collect { n ->
                        if (n.sessionId == null || (n.sessionId != onScreen && !atHome)) {
                            if (!vm.showNotice(n)) return@collect
                            if (n.kind != Notice.Kind.Finished && n.kind != Notice.Kind.Update) view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                }
                // A newer app on the Mac: a banner once per version.
                val update = hub?.appUpdate
                LaunchedEffect(update?.versionCode) {
                    if (update != null && com.prudnikweb.hypernova.data.updateNoticed(context, update.versionCode)) {
                        vm.showNotice(com.prudnikweb.hypernova.data.updateNotice(context, update))
                    }
                }
                // Opening that session (or the chat it's in) takes the banner away; so does Home, for any session's.
                LaunchedEffect(onScreen) { if (onScreen != null && vm.notice?.sessionId == onScreen) vm.dismissNotice() }
                LaunchedEffect(atHome) { if (atHome && vm.notice?.sessionId != null) vm.dismissNotice() }
                // A session's chat on screen is read up to its last activity, and stays read while
                // replies come in (data/Unread.kt): its Completed card on Home has no unread dot.
                val readId = (route as? Route.Session)?.id
                val readAt = readId?.let { hub?.session(it)?.updatedAt }
                LaunchedEffect(hub, readId, readAt) {
                    val server = hub?.let(com.prudnikweb.hypernova.data.ReadState::server)
                    if (server != null && readId != null && readAt != null) com.prudnikweb.hypernova.data.ReadState.mark(context, server, readId, readAt)
                }
                NoticeHost(
                    vm.notice,
                    onOpen = { n ->
                        vm.dismissNotice()
                        val id = n.sessionId
                        when {
                            // The update itself, in the app (data/AppUpdater.kt): its banner takes over.
                            n.kind == Notice.Kind.Update -> hub?.appUpdate?.let { com.prudnikweb.hypernova.data.AppUpdater.start(context, it) }
                            // Backpack ending: the button is the point; the banner itself just goes.
                            n.kind == Notice.Kind.Backpack -> Unit
                            // A limit or a sign-out: Home's account card says why and what to do.
                            n.kind == Notice.Kind.Limit || n.kind == Notice.Kind.Account -> {
                                sheet = false
                                nav.reset()
                                toAccount.value = true
                            }
                            id != null && hub?.session(id) != null -> {
                                sheet = false
                                nav.openSession(id)
                            }
                            // An automation's note with no session to open: the automation, or its tab.
                            n.kind == Notice.Kind.Automation -> {
                                sheet = false
                                val aid = n.automationId
                                if (!aid.isNullOrEmpty() && hub?.automations?.automation(aid) != null) nav.push(Route.AutomationEdit(aid))
                                else { nav.reset(); toAutomations.value = true }
                            }
                        }
                    },
                    onDismiss = vm::putAwayNotice,
                    onAction = { n ->
                        vm.dismissNotice()
                        if (n.action == Notice.ACTION_EXTEND) vm.extendBackpack(vm::showToast)
                    },
                )
                // The update under way (its progress, Install, what stopped it), where a notice's banner sits, giving way to one.
                com.prudnikweb.hypernova.ui.update.UpdateBannerHost(covered = vm.notice != null)

                Grain(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun Screens(
    hub: Hub,
    vm: HubViewModel,
    route: Route,
    depth: Int,
    screens: androidx.compose.runtime.saveable.SaveableStateHolder,
    cosmos: com.prudnikweb.hypernova.ui.fx.CosmosState,
    skyVisibility: androidx.compose.runtime.MutableFloatState,
    filter: SkyFilter?,
    onFilter: (SkyFilter?) -> Unit,
    plan: SkyPlanner.Plan,
    onNewSession: () -> Unit,
    toAccount: androidx.compose.runtime.MutableState<Boolean>,
    toAutomations: androidx.compose.runtime.MutableState<Boolean>,
) {
    val nav = vm.nav
    val opener = LocalOpener.current
    // Opening goes deeper (scale up from slightly small); going back settles out.
    var lastDepth by remember { mutableStateOf(depth) }
    val opening = depth >= lastDepth
    LaunchedEffect(depth) { lastDepth = depth }
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            (fadeIn(tween(Motion.REVEAL, easing = Motion.Expo)) +
                scaleIn(tween(Motion.REVEAL, easing = Motion.Expo), initialScale = if (opening) 0.97f else 1.02f)) togetherWith
                (fadeOut(tween(Motion.DIALOG)) + scaleOut(tween(Motion.DIALOG), targetScale = if (opening) 1.02f else 0.98f))
        },
        contentKey = { it.key },
        label = "screen",
    ) { r ->
        // Each screen keeps its saveable state (list scroll, tab) while it's off screen.
        // Home starts fresh (Sessions tab, top of the list) for each Mac and for demo mode.
        val key = if (r == Route.Home) "home:${vm.pairing?.token?.hashCode()}:${vm.demo}" else r.key
        screens.SaveableStateProvider(key) {
            when (r) {
                Route.Home -> HomeScreen(
                    hub, cosmos, skyVisibility,
                    filter = filter, onFilter = onFilter, quiet = plan.quietNames,
                    onOpen = opener.session, onNewSession = onNewSession,
                    settings = { top -> SettingsTab(vm, top) },
                    toAccount = toAccount,
                    toAutomations = toAutomations,
                    onAutomation = { id, template, runs -> nav.push(Route.AutomationEdit(id, template, runs)) },
                    servers = com.prudnikweb.hypernova.ui.home.ServerChoice(vm.servers, vm.demo, vm::switchServer, { vm.addServer(true) }),
                )
                // Fork: a new route each time it's opened, so each opening lands at the latest (SessionScreen).
                is Route.Session -> SessionScreen(hub, r.id, onBack = { nav.pop() }, opening = System.identityHashCode(r))
                is Route.Files -> FilesScreen(hub, r, onBack = { nav.pop() })
                is Route.FileView -> FileViewer(hub, r.path, onBack = { nav.pop() })
                is Route.Web -> WebScreen(r.url, r.title, onBack = { nav.pop() })
                is Route.Terminal -> com.prudnikweb.hypernova.ui.term.TerminalScreen(hub, r.sessionId, onBack = { nav.pop() })
                is Route.Agent -> com.prudnikweb.hypernova.ui.session.AgentScreen(hub, r.sessionId, r.agentId, onBack = { nav.pop() })
                Route.Providers -> ProvidersScreen(hub, onBack = { nav.pop() }, onEdit = { id, kind -> nav.push(Route.ProviderEdit(id, kind)) })
                Route.ConnectionLog -> com.prudnikweb.hypernova.ui.settings.ConnectionLogScreen(onBack = { nav.pop() })
                is Route.ProviderEdit -> ProviderEditScreen(hub, r.id, r.kind, onBack = { nav.pop() })
                is Route.AutomationEdit -> com.prudnikweb.hypernova.ui.automations.AutomationEditor(
                    hub, r.id, r.template, r.runs, r.project,
                    onBack = { nav.pop() },
                    // A new one, once saved, carries on as itself: its runs and Run now.
                    onSaved = { id -> nav.replaceTop(Route.AutomationEdit(id)) },
                    onOpenSession = opener.session,
                )
            }
        }
    }
}
