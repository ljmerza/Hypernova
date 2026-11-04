// Modified by Leonardo Merza, 2026. See NOTICE.
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.HubViewModel
import com.prudnikweb.hypernova.data.extendBackpack
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.LinkState
import kotlinx.coroutines.flow.first
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Route
import com.prudnikweb.hypernova.data.isSettingsPage
import com.prudnikweb.hypernova.ui.components.ConfirmDialog
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.LocalTabsAsk
import com.prudnikweb.hypernova.ui.components.LocalTabsRoom
import com.prudnikweb.hypernova.ui.components.NoticeHost
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.ui.components.ToastHost
import com.prudnikweb.hypernova.ui.components.Z
import com.prudnikweb.hypernova.ui.files.FileViewer
import com.prudnikweb.hypernova.ui.files.FilesScreen
import com.prudnikweb.hypernova.ui.fx.AtlasBackground
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
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.web.WebScreen
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import com.prudnikweb.hypernova.data.AgentLead
import com.prudnikweb.hypernova.data.codexLeads
import com.prudnikweb.hypernova.data.strings
import com.prudnikweb.hypernova.data.storage
import kotlinx.coroutines.launch

/** Ask for notification permission (or open the system settings if it was refused). */
val LocalAskNotifications = staticCompositionLocalOf<() -> Unit> { {} }


/**
 * Layers, back to front:
 *  1. the chart (one instance for the whole app, so it never restarts),
 *  2. the current screen (onboarding when nothing is paired),
 *  3. the sheet (dim and panel animate separately),
 *  4. pairing / unpaired overlays, toasts,
 *  5. grain over everything.
 */
@Composable
fun App(vm: HubViewModel, askNotifications: () -> Unit) {
    val hub = vm.hub
    val nav = vm.nav
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    val screens = rememberSaveableStateHolder()
    // Home's tab stays as the switcher changes the computer (HomeScreen.kt, rememberHomeTab).
    val homeTab = com.prudnikweb.hypernova.ui.home.rememberHomeTab(if (vm.allServers) "home:all" else "home:${vm.pairing?.token?.hashCode()}")
    // Counts the tabs tapped on Settings' pages: each starts the main Settings page again, at its top.
    val settingsFresh = rememberSaveable { mutableStateOf(0) }
    // The logo, wherever it's shown: Home goes to the top of Sessions and clears this.
    val toTop = remember { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    // The project the new-session sheet opens on (a path, or the filtered project's name), and the
    // launch that asked for it: its own, not the list's filter, which lets go of a project with
    // nothing live in it at once (a launcher shortcut's project is often one).
    var sheetProject by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetLaunch by rememberSaveable { mutableStateOf(0L) }
    // The computer the sheet starts the session on, picked first while Home shows "All servers"
    // (ui/home/ServerSwitcher.kt, ServerPicker); null: the one on screen.
    var sheetServer by rememberSaveable { mutableStateOf<String?>(null) }
    // The one project the home list shows, from a launcher shortcut or Share; null shows everything.
    var focusProject by rememberSaveable { mutableStateOf<String?>(null) }
    // An account banner was tapped: Home scrolls to the account card (and clears this).
    val toAccount = remember { mutableStateOf(false) }
    // A note from several automations was tapped: Home shows the Automations tab (and clears this).
    val toAutomations = remember { mutableStateOf(false) }
    val sessions = hub?.sessions.orEmpty()
    // Ended sessions live in History. A conversation that switched agents shows as its newest session.
    val alive = remember(sessions) { sessions.filter { it.phase != Phase.Ended && it.switchedTo == null } }
    // A project with nothing live in it ends the filter.
    if (focusProject != null && alive.none { it.project == focusProject }) focusProject = null
    if (hub == null && sheet) sheet = false

    val route = nav.top
    // The debug log's line for each screen and tab: by kind and ids (Route.debugWords), never a title or a path.
    androidx.compose.runtime.LaunchedEffect(route.key) { com.prudnikweb.hypernova.data.DebugLog.screen(route) }
    androidx.compose.runtime.LaunchedEffect(homeTab.value) { com.prudnikweb.hypernova.data.DebugLog.i("screen") { "Home's ${homeTab.value.name} tab" } }
    val unpaired = vm.live?.link?.state == LinkState.Unpaired
    // The chart steps back behind a screen of text, as the far sky did (fx/Atlas.kt).
    val chartStrength by animateFloatAsState(if (route == Route.Home && hub != null) 1f else 0.45f, tween(Motion.SECTION, easing = Motion.Expo), label = "chart")
    // The usage that counts: the leading agent's, Claude Code's or Codex's where Claude Code isn't installed.
    val codexLeads = hub?.codexLeads == true
    val codexState = hub?.codex
    val leadUsage = remember(codexLeads, codexState, hub?.usage) { if (codexLeads) AgentLead.codexSunUsage(codexState, System.currentTimeMillis()) else hub?.usage }

    // A limit you can feel: a heavy thud as it's hit, and a second just after (ui/fx/LimitFeel.kt).
    val view = LocalView.current
    val reducedMotion = LocalMotion.current.reduced
    LaunchedEffect(leadUsage) {
        val u = leadUsage ?: return@LaunchedEffect
        if (!vm.limitFeel.see(u, System.currentTimeMillis(), reducedMotion)) return@LaunchedEffect
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
    // With "All servers", a notification's session is on its own computer, visited as it opens.
    val pendingServer = vm.pendingServer
    val openHub = pendingServer?.let(vm::hubOf) ?: hub
    LaunchedEffect(pendingOpen, openHub, pendingServer) {
        val id = pendingOpen ?: return@LaunchedEffect
        val h = openHub ?: return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { h.session(id) != null }.first { it }
        sheet = false
        if (pendingServer != null) vm.visit(pendingServer)
        nav.openSession(id, fresh = true)
        vm.pendingOpen = null
        vm.pendingServer = null
    }
    // One the hub doesn't know (it ended and left, audit P11): it isn't waited for forever, nor
    // opened minutes later out of nowhere. Once the hub has caught up and 10 s more have passed,
    // or after 30 s without reaching the Mac, it's said and let go.
    val openContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(pendingOpen, openHub) {
        val id = pendingOpen ?: return@LaunchedEffect
        val h = openHub ?: return@LaunchedEffect
        val synced = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
            androidx.compose.runtime.snapshotFlow { h.link.state }.first { it == LinkState.Connected }
        }
        if (synced != null) kotlinx.coroutines.delay(10_000L)
        if (vm.pendingOpen == id && h.session(id) == null) {
            vm.pendingOpen = null
            vm.pendingServer = null
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
        sheetServer = null
        sheet = true
        com.prudnikweb.hypernova.ui.share.ShareLaunch.newSession = null
    }

    // Home again with "All servers": the other tabs (and the cards above the sessions) are the one
    // picked last's again, after a session of another computer was open (Core.visit).
    val allServers = vm.allServers
    val activeNow = com.prudnikweb.hypernova.data.Core.activeId
    LaunchedEffect(route == Route.Home, allServers, activeNow) {
        if (route == Route.Home && allServers) vm.backToPicked()
    }

    BackHandler(enabled = sheet || nav.depth > 1 || focusProject != null) {
        when {
            sheet -> sheet = false
            nav.pop() -> Unit
            else -> focusProject = null // the whole list again
        }
    }

    val scope = rememberCoroutineScope()
    var previewBusy by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    val opener = remember(hub, nav) {
        Opener(
            signIn = { signingIn = true },
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
                            .onSuccess { nav.push(Route.Web(it, title, preview = true, port = port)) }
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
            mcpServers = { nav.push(Route.McpServers(it)) },
            settings = { key ->
                // The server on screen as it's asked (a session's own, opened from "All servers"), before Home brings the one picked back.
                val server = com.prudnikweb.hypernova.data.Core.activeId
                sheet = false
                nav.reset()
                com.prudnikweb.hypernova.ui.settings.SettingsJump.show(key, server)
            },
            runAtReset = { project -> nav.push(Route.AutomationEdit(null, com.prudnikweb.hypernova.ui.automations.Templates.RESET, project = project)) },
        )
    }

    // Every link the chat draws comes here when tapped: the web asks first, a dev server opens its preview.
    val links = remember(opener) { com.prudnikweb.hypernova.ui.chat.LinkHandler(opener) }
    ProvideAmbientClock(vm.clock) {
        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalUriHandler provides links,
            LocalHaze provides haze, LocalToast provides vm::showToast, LocalOpener provides opener,
            LocalConfirm provides { vm.confirm = it },
            LocalAppScope provides vm.appScope,
            LocalAskNotifications provides askNotifications,
        ) {
            Box(Modifier.fillMaxSize()) {
                AtlasBackground(
                    week = leadUsage?.sevenDay ?: 0f,
                    modifier = Modifier.fillMaxSize(),
                    strength = { chartStrength },
                    // The bars blur only this (and their screen): it hardly ever changes.
                    backdrop = Modifier.hazeSource(haze, zIndex = Z.COSMOS),
                )

                // A full-screen state (pairing, unpaired) replaces the screen instead of covering
                // it, so nothing underneath can read through; the chart stays.
                // "Add a server" (the switcher) shows the onboarding's steps in the screen's place.
                val adding = vm.addingServer && vm.pairFlow == null
                val covered = vm.pairFlow != null || unpaired || adding
                if (adding || (!covered && hub == null)) {
                    // While "Add this server?" asks: not behind it, with its camera on.
                    if (vm.offeredLink == null) Onboarding(
                        onPaste = vm::handleLink,
                        onClose = if (adding && hub != null) ({ vm.addServer(false) }) else null,
                    )
                } else if (!covered && hub != null) {
                    Screens(
                        hub, vm, route, nav.depth, screens, homeTab, settingsFresh, focusProject, { focusProject = null },
                        { sheetProject = focusProject; sheetServer = null; sheet = true }, toAccount, toAutomations, toTop,
                        onNewSessionOn = { server -> sheetProject = focusProject; sheetServer = server; sheet = true },
                    )
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
                        // From "All servers", the computer picked first: its folders, models and providers.
                        val onServer = sheetServer
                        val sheetHub = onServer?.let(vm::hubOf) ?: hub
                        androidx.compose.runtime.key(onServer) {
                            NewSessionSheet(
                                hub = sheetHub,
                                initialProject = sheetProject,
                                launch = sheetLaunch,
                                onStarted = { id ->
                                    sheet = false
                                    focusProject = null
                                    // Its screens are that computer's (Home stays on "All servers").
                                    if (onServer != null) vm.visit(onServer)
                                    nav.openSession(id)
                                },
                            )
                        }
                    }
                }

                // A /login from a session, or Sign in here on its card: this computer's sign-in.
                hub?.takeIf { signingIn }?.let { h -> com.prudnikweb.hypernova.ui.home.SignInSheet(h, onDismiss = { signingIn = false }) }

                // The Mac forgot this phone (401): say so, and offer the way back.
                val live = vm.live
                if (unpaired && live != null && !adding) {
                    UnpairedScreen(
                        macName = live.mac.name,
                        onPairAgain = vm::pairAgain,
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
                    if (update != null && com.prudnikweb.hypernova.data.updateNoticed(context.storage, update.versionCode)) {
                        vm.showNotice(com.prudnikweb.hypernova.data.updateNotice(context.strings, update))
                    }
                }
                // Opening that session (or the chat it's in) takes the banner away; so does Home, for any session's.
                LaunchedEffect(onScreen) { if (onScreen != null && vm.notice?.sessionId == onScreen) vm.dismissNotice() }
                LaunchedEffect(atHome) { if (atHome && vm.notice?.sessionId != null) vm.dismissNotice() }
                // A session's chat on screen is read up to its last activity, and stays read while
                // replies come in (data/Unread.kt): its Completed card on Home has no unread dot.
                com.prudnikweb.hypernova.data.MarkReadOnScreen(hub, (route as? Route.Session)?.id)
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
                            // A server's update that failed: its section of Settings says what and what next.
                            n.kind == Notice.Kind.ServerUpdateFailed -> opener.settings(com.prudnikweb.hypernova.ui.settings.SettingsJump.SERVER)
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

                // "Add this server?", above everything, which stays as it is underneath.
                vm.offeredLink?.let { com.prudnikweb.hypernova.ui.onboarding.OfferScreen(it, onAdd = vm::acceptOffer, onCancel = vm::declineOffer) }

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
    homeTab: androidx.compose.runtime.MutableState<com.prudnikweb.hypernova.ui.home.Tab>,
    settingsFresh: androidx.compose.runtime.MutableState<Int>,
    focusProject: String?,
    onClearProject: () -> Unit,
    onNewSession: () -> Unit,
    toAccount: androidx.compose.runtime.MutableState<Boolean>,
    toAutomations: androidx.compose.runtime.MutableState<Boolean>,
    toTop: androidx.compose.runtime.MutableState<Boolean>,
    /** New session from "All servers", on the computer picked for it. */
    onNewSessionOn: (String) -> Unit = {},
) {
    val nav = vm.nav
    val opener = LocalOpener.current
    // Opening goes deeper (scale up from slightly small); going back settles out.
    var lastDepth by remember { mutableStateOf(depth) }
    val opening = depth >= lastDepth
    LaunchedEffect(depth) { lastDepth = depth }
    // Settings' pages keep the tabs (owner, 2026-11-03; docs/DECISIONS.md, "The tabs on Settings' pages").
    val onPages = com.prudnikweb.hypernova.data.inSettings(nav.stack)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val navBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var tabsPx by remember { mutableStateOf(0) }
    // Before the bar has been measured: about what it measures, so a page doesn't start short of it.
    val tabsRoom = if (tabsPx > 0) with(density) { tabsPx.toDp() } else navBottom + 60.dp
    val tabsAsk = remember { mutableStateOf<((go: () -> Unit) -> Unit)?>(null) }
    val toast = LocalToast.current
    val noAutomations = stringResource(R.string.all_servers_no_automations)
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
        // Home starts at the top of its list for each computer, on the tab it was on (homeTab); "All
        // servers" is one Home of its own, kept while its cards' sessions (on their own computers) come and go.
        val all = vm.allServers
        val key = if (r == Route.Home) (if (all) "home:all" else "home:${vm.pairing?.token?.hashCode()}") else r.key
        // A screen over Home stays on the computer it opened on (a session from "All servers" is
        // its own computer's), though Home's hub changes back under it as it closes.
        val shown = if (r == Route.Home) hub else remember { hub }
        // Whether this screen has the tabs under it stays as it was while it fades out.
        val hasTabs = remember { onPages }
        // Settings' pages end where the keyboard starts, as the window doesn't resize: a focused field
        // scrolls clear of it. The tabs, drawn outside this, stay where they are behind it.
        CompositionLocalProvider(
            LocalTabsRoom provides if (hasTabs) tabsRoom else 0.dp,
            LocalTabsAsk provides tabsAsk,
        ) { screens.SaveableStateProvider(key) { Box(if (r.isSettingsPage) Modifier.fillMaxSize().imePadding() else Modifier.fillMaxSize()) {
            when (r) {
                Route.Home -> HomeScreen(
                    hub,
                    project = focusProject, onClearProject = onClearProject,
                    onOpen = opener.session, onNewSession = onNewSession,
                    settings = { top -> androidx.compose.runtime.key(settingsFresh.value) { SettingsTab(vm, top) } },
                    tabState = homeTab,
                    toAccount = toAccount,
                    toAutomations = toAutomations,
                    toTop = toTop,
                    onAutomation = { id, template, runs -> nav.push(Route.AutomationEdit(id, template, runs)) },
                    servers = com.prudnikweb.hypernova.ui.home.ServerChoice(vm.servers, vm::switchServer, { vm.addServer(true) }, all = all, onAll = vm::showAll),
                    everywhere = if (!all) null else com.prudnikweb.hypernova.ui.home.Everywhere(
                        vm.everyHub,
                        pickedName = hub.mac.name,
                        onOpen = { server, id ->
                            // Its chat, sheet and terminal are its own computer's; Back is Home on "All servers" again.
                            vm.visit(server)
                            nav.openSession(id)
                        },
                        onNewSession = onNewSessionOn,
                        onRuns = { server, id ->
                            // History's automation row: its runs on its own computer, as a card's session.
                            vm.visit(server)
                            nav.push(Route.AutomationEdit(id, null, true))
                        },
                    ),
                )
                // Fork: a new route each time it's opened, so each opening lands at the latest (SessionScreen).
                is Route.Session -> SessionScreen(shown, r.id, onBack = { nav.pop() }, opening = System.identityHashCode(r))
                // The one on screen's, or with All servers every one's, whichever is chosen when it's opened.
                Route.Statistics -> com.prudnikweb.hypernova.ui.stats.StatsScreen(
                    shown, onBack = { nav.pop() },
                    servers = if (all) vm.everyHub.map { (st, h) -> com.prudnikweb.hypernova.data.StatsServer(st.id, st.name, h) } else null,
                    onHome = {
                        nav.reset()
                        homeTab.value = com.prudnikweb.hypernova.ui.home.Tab.Sessions
                        toTop.value = true
                    },
                )
                is Route.Files -> FilesScreen(shown, r, onBack = { nav.pop() })
                is Route.FileView -> FileViewer(shown, r.path, onBack = { nav.pop() })
                is Route.Web -> WebScreen(r.url, r.title, onBack = { nav.pop() }, preview = r.preview, port = r.port)
                is Route.Terminal -> com.prudnikweb.hypernova.ui.term.TerminalScreen(shown, r.sessionId, onBack = { nav.pop() })
                is Route.Agent -> com.prudnikweb.hypernova.ui.session.AgentScreen(shown, r.sessionId, r.agentId, onBack = { nav.pop() })
                Route.Providers -> ProvidersScreen(shown, onBack = { nav.pop() }, onEdit = { id, kind -> nav.push(Route.ProviderEdit(id, kind)) }, onAgentSettings = { nav.push(Route.AgentSettings(it)) })
                is Route.AgentSettings -> com.prudnikweb.hypernova.ui.settings.AgentSettingsScreen(shown, r.agent, onBack = { nav.pop() }, onMcp = { nav.push(Route.McpServers(r.agent)) }, onPlugins = { nav.push(Route.Plugins(r.agent)) }, onSkills = { nav.push(Route.Skills(r.agent)) }, onEnv = { nav.push(Route.AgentEnv(r.agent)) }, onJson = { nav.push(Route.AgentRaw(r.agent)) }, onRules = { nav.push(Route.AgentRules(r.agent)) })
                is Route.AgentRaw -> com.prudnikweb.hypernova.ui.settings.AgentRawScreen(shown, r.agent, onBack = { nav.pop() })
                is Route.AgentRules -> com.prudnikweb.hypernova.ui.settings.AgentRulesScreen(shown, r.agent, onBack = { nav.pop() })
                is Route.AgentEnv -> com.prudnikweb.hypernova.ui.settings.AgentEnvScreen(shown, r.agent, onBack = { nav.pop() })
                is Route.Skills -> com.prudnikweb.hypernova.ui.settings.SkillsScreen(shown, r.agent, onBack = { nav.pop() })
                is Route.Plugins -> com.prudnikweb.hypernova.ui.settings.PluginsScreen(shown, r.agent, onBack = { nav.pop() }, onOpen = { id, scope, folder, on -> nav.push(Route.PluginPage(r.agent, id, scope, folder, on)) }, onBrowse = { folder -> nav.push(Route.PluginBrowse(r.agent, folder)) })
                is Route.PluginPage -> com.prudnikweb.hypernova.ui.settings.PluginScreen(shown, r.agent, r.id, r.scope, r.folder, r.enabled, onBack = { nav.pop() })
                is Route.PluginBrowse -> com.prudnikweb.hypernova.ui.settings.PluginBrowseScreen(shown, r.agent, r.folder, onBack = { nav.pop() })
                is Route.McpServers -> com.prudnikweb.hypernova.ui.settings.McpServersScreen(shown, r.agent, onBack = { nav.pop() }, onOpen = { name, scope, folder -> nav.push(Route.McpServerPage(r.agent, name, scope, folder)) }, onAdd = { nav.push(Route.McpEdit(r.agent, null, null, null)) })
                is Route.McpServerPage -> com.prudnikweb.hypernova.ui.settings.McpServerScreen(shown, r.agent, r.name, r.scope, r.folder, onBack = { nav.pop() }, onEdit = { nav.push(Route.McpEdit(r.agent, r.name, r.scope, r.folder)) })
                is Route.McpEdit -> com.prudnikweb.hypernova.ui.settings.McpEditScreen(shown, r.agent, r.name, r.scope, r.folder, onBack = { nav.pop() })
                Route.ConnectionLog -> com.prudnikweb.hypernova.ui.settings.ConnectionLogScreen(onBack = { nav.pop() })
                is Route.ServerSettings, Route.NotificationSettings, Route.TopBarSettings, Route.CardSwipeSettings, Route.TypingVoiceSettings, Route.LogSettings ->
                    com.prudnikweb.hypernova.ui.settings.SettingsSubPage(r, vm, onBack = { nav.pop() })
                is Route.ProviderEdit -> ProviderEditScreen(shown, r.id, r.kind, onBack = { nav.pop() })
                is Route.AutomationEdit -> com.prudnikweb.hypernova.ui.automations.AutomationEditor(
                    shown, r.id, r.template, r.runs, r.project,
                    onBack = { nav.pop() },
                    // A new one, once saved, carries on as itself: its runs and Run now.
                    onSaved = { id -> nav.replaceTop(Route.AutomationEdit(id)) },
                    onOpenSession = opener.session,
                )
            }
        } } }
    }
    com.prudnikweb.hypernova.ui.home.SettingsPageBar(
        visible = onPages,
        // A tab goes straight there, from any depth, and Settings is its main page from the top, the
        // same rule from wherever it's tapped; Back is the way up one level. Automations with All
        // servers says why not, as on Home; a page that asks before it loses what's typed asks first.
        onSelect = { to ->
            if (vm.allServers && to == com.prudnikweb.hypernova.ui.home.Tab.Automations) toast(noAutomations)
            else {
                val go = {
                    nav.reset()
                    settingsFresh.value++
                    homeTab.value = to
                }
                tabsAsk.value?.invoke(go) ?: go()
            }
        },
        modifier = Modifier.fillMaxSize().wrapContentHeight(androidx.compose.ui.Alignment.Bottom).onSizeChanged { if (it.height > 0) tabsPx = it.height },
    )
}
