package com.prudnikweb.hypernova.ui.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.prudnikweb.hypernova.ui.components.clipboardText
import com.prudnikweb.hypernova.ui.stats.StatsScreen
import kotlin.math.roundToInt
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Account
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.AccountWords
import com.prudnikweb.hypernova.data.Blocked
import com.prudnikweb.hypernova.data.FakeHub
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.healthyOther
import com.prudnikweb.hypernova.data.openLink
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Problem
import com.prudnikweb.hypernova.data.problemText
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.onboarding.openTailscale
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.CodexState
import com.prudnikweb.hypernova.data.AgentLead
import com.prudnikweb.hypernova.data.Usage
import com.prudnikweb.hypernova.data.bothAgents
import com.prudnikweb.hypernova.data.claudeAvailable
import com.prudnikweb.hypernova.data.codexLeads
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.AttentionAction
import com.prudnikweb.hypernova.ui.components.AttentionCard
import com.prudnikweb.hypernova.ui.components.AttentionStore
import com.prudnikweb.hypernova.data.Attention
import com.prudnikweb.hypernova.ui.components.BlurredBottom
import com.prudnikweb.hypernova.ui.components.BlurredTop
import com.prudnikweb.hypernova.ui.components.ConnectionChip
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.NovaMark
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.StatusChip
import com.prudnikweb.hypernova.ui.components.Wordmark
import com.prudnikweb.hypernova.ui.components.Z
import com.prudnikweb.hypernova.ui.fx.CosmosHit
import com.prudnikweb.hypernova.ui.fx.CosmosState
import com.prudnikweb.hypernova.ui.fx.SkyFilter
import com.prudnikweb.hypernova.data.UsageProjection
import com.prudnikweb.hypernova.ui.components.ProjectionMeter
import com.prudnikweb.hypernova.ui.components.projectionSaid
import com.prudnikweb.hypernova.ui.components.frostedGlass
import com.prudnikweb.hypernova.ui.components.FitRow
import com.prudnikweb.hypernova.ui.components.CountMark
import com.prudnikweb.hypernova.ui.components.EclipseMark
import com.prudnikweb.hypernova.ui.components.SeamWords
import com.prudnikweb.hypernova.ui.components.ModeTag
import com.prudnikweb.hypernova.ui.components.SessionStamp
import com.prudnikweb.hypernova.ui.components.sessionSaid
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.size
import com.prudnikweb.hypernova.ui.fx.formatDuration
import com.prudnikweb.hypernova.ui.fx.rememberCountdownNow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import android.view.HapticFeedbackConstants
import kotlinx.coroutines.launch
import com.prudnikweb.hypernova.ui.fx.rememberScrambled
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * Home's bottom bar. Statistics isn't one of them: the sun opens it, as a screen of its own.
 * Automations sit next to Sessions (docs/specs/spec-automations.md §5).
 */
enum class Tab { Sessions, History, Automations, Settings }

const val HOME_SOURCE = "home"

@Composable
fun HomeScreen(
    hub: Hub,
    cosmos: CosmosState,
    skyVisibility: MutableFloatState,
    /** What the list shows: a project, the quiet ones, or everything (null). */
    filter: SkyFilter?,
    onFilter: (SkyFilter?) -> Unit,
    /** The projects in the sky's belt (no planet of their own now). */
    quiet: List<String>,
    onOpen: (String) -> Unit,
    onNewSession: () -> Unit,
    settings: @Composable (top: Dp) -> Unit,
    /** Set when an account banner is tapped: Home shows the account card, then clears it. */
    toAccount: MutableState<Boolean>? = null,
    /** Set by a note from several automations: Home shows the Automations tab, then clears it. */
    toAutomations: MutableState<Boolean>? = null,
    /** An automation's editor: [id] null for a new one (from a template, or blank); [runs]: at its runs. */
    onAutomation: (id: String?, template: String?, runs: Boolean) -> Unit = { _, _, _ -> },
) {
    val haze = LocalHaze.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val screenH = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    var tab by rememberSaveable { mutableStateOf(Tab.Sessions) }
    // Statistics, opened by tapping the sun: a screen of its own over Home, which fades back as it
    // comes forward (the way a session opens), and Back returns. Only what Home draws leaves, so
    // its tab and scroll are where they were.
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    val statsAnim = remember { Animatable(if (statsOpen) 1f else 0f) }
    LaunchedEffect(statsOpen) { statsAnim.animateTo(if (statsOpen) 1f else 0f, tween(Motion.REVEAL, easing = Motion.Expo)) }
    val homeShown by remember { derivedStateOf { !statsOpen || statsAnim.value < 1f } }
    val statsShown by remember { derivedStateOf { statsOpen || statsAnim.value > 0f } }
    BackHandler(enabled = statsOpen) { statsOpen = false }
    // The sun's tap is shown once ("Tap the sun for your statistics"), until Statistics is first opened.
    val context = LocalContext.current
    var sunHinted by remember { mutableStateOf(sunHintDone(context)) }
    val openStats = {
        statsOpen = true
        if (!sunHinted) {
            sunHinted = true
            markSunHintDone(context)
        }
    }
    val list = rememberLazyListState()
    // "2m ago" labels refresh on their own, not only when a session changes.
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    // As tall as what's in the sky (the calm sky's band ends under its last row of words), so with
    // less in it the list starts higher. The window (it's see-through) takes its new height at
    // once; every item under it glides there together (animateItem), so nothing lags or overlaps.
    val bandBottom = with(LocalDensity.current) { cosmos.usedBottomPx.toDp() }
    // Before the sky has drawn once (a fresh start), the band with every place taken: about right.
    val screenW = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val skyH = if (bandBottom > 0.dp) bandBottom + 4.dp
    else top + 96.dp + com.prudnikweb.hypernova.ui.fx.SkyLayout.compute(screenW.value, BooleanArray(4) { true }, belt = true, companion = false, hint = false).height.dp + 4.dp
    val skyPx = with(LocalDensity.current) { skyH.toPx() }
    // The systems step back as cards scroll over them, and hide away from the Sessions tab and
    // behind Statistics.
    val tabAnim = remember { Animatable(if (tab == Tab.Sessions && !statsOpen) 1f else 0f) }
    LaunchedEffect(tab, statsOpen) { tabAnim.animateTo(if (tab == Tab.Sessions && !statsOpen) 1f else 0f, tween(Motion.SECTION, easing = Motion.Expo)) }
    LaunchedEffect(list, skyPx) {
        snapshotFlow {
            val scrolled = if (list.firstVisibleItemIndex > 0) skyPx else list.firstVisibleItemScrollOffset.toFloat()
            val k = ((scrolled / skyPx - 0.04f) / (0.42f - 0.04f)).coerceIn(0f, 1f)
            (1f - k * k * (3f - 2f * k)) * tabAnim.value
        }.collect { skyVisibility.floatValue = it }
    }
    val scope = rememberCoroutineScope()
    // Something happens in the sky while you watch (a limit, the week's low state, the outburst at
    // a reset): bring the sky into view, so it isn't missed behind the cards. Events from before
    // this screen appeared don't count.
    val skyEvents = cosmos.eventCount
    val eventsBefore = remember { intArrayOf(-1) }
    LaunchedEffect(skyEvents) {
        if (eventsBefore[0] < 0) { eventsBefore[0] = skyEvents; return@LaunchedEffect }
        eventsBefore[0] = skyEvents
        val away = list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0
        if (tab == Tab.Sessions && !statsOpen && away && !list.isScrollInProgress) list.animateScrollToItem(0)
    }
    // The sun is eclipsed while the app can't follow usage (signed out, no plan, on hold), with
    // the reason under it; the eclipse ends with a diamond ring when the account is back. Leaving
    // for a session keeps it. In the demo a long press on the sun walks through the account's
    // states (see FakeHub).
    val demo = hub as? FakeHub
    val account = hub.account
    // The sun is the leading agent's (data/AgentLead.kt): Claude Code's account eclipses it only where
    // Claude Code is installed; where Codex leads, Codex signed out does.
    val claudeHere = hub.claudeAvailable
    val codexLeads = hub.codexLeads
    val codexSignedOut = hub.codex?.account?.signedIn == false
    val eclipse = if (claudeHere) eclipseLine(account) else if (codexLeads && codexSignedOut) stringResource(R.string.eclipse_signed_out) else null
    val eclipsed = if (claudeHere) account.eclipsed else codexLeads && codexSignedOut
    // The usage the sun shows: Claude's, or Codex's where Codex leads.
    val sunUsage = remember(codexLeads, hub.codex, hub.usage) { if (codexLeads) AgentLead.codexSunUsage(hub.codex, System.currentTimeMillis()) else hub.usage }
    LaunchedEffect(cosmos, eclipse) { cosmos.eclipse = eclipse }
    val density = LocalDensity.current
    // Tap a planet (or the belt's "+5 quiet"): the list shows only that project (or the quiet ones),
    // and a chip over it says so; tap the chip, the planet again, or Back for everything.
    // Ended sessions move to History; the home list is what's alive, and what a restart of the Mac
    // ended lately (nobody ended it): those stay here, to pick up where they left off. A conversation
    // that switched agents is its newest session; the one before it opens from there ("Continued from").
    val all = remember(hub.sessions) { hub.sessions.filter { (it.phase != Phase.Ended || it.cutByRestart) && it.switchedTo == null } }
    // Both agents in use (data/AgentLead.kt): Codex's sessions say CODEX. Never otherwise (where Codex
    // leads, every session is Codex's). Which usage cards show, and whether they say whose they are.
    val codexOn = hub.bothAgents
    val usageCards = UsageCards.of(hub.claude, hub.codex)
    val sessions = remember(all, filter, quiet) { SkyFilter.apply(all, filter, quiet) }
    // The line under the sun's readout ("Tap the sun for your statistics"): the sky keeps room for it.
    val hint = !sunHinted && !eclipsed && !sunUsage.weekLimited && !sunUsage.limited
    androidx.compose.runtime.SideEffect { cosmos.hint = hint }
    // A provider in trouble: a card that says what's wrong and how to fix it (not words in the sky).
    val troubled = remember(hub.providers, hub.sessions) { troubledProviders(hub.providers, hub.sessions) }
    val opener = LocalOpener.current
    // Who needs you here and elsewhere: the filter chip says how many wait outside it.
    val waiting = sessions.count { it.state == SessionState.NeedsYou }
    val waitingAll = all.count { it.state == SessionState.NeedsYou }
    // Claude Code's account: only where it's installed (a Mac where Codex leads has no Claude account to fix).
    val showAccount = claudeHere && accountCardShown(account, now)
    // The attention cards above the list, unless swiped away (an Undo stands in for a moment).
    val gone = AttentionStore.gone
    val showOffline = hub.link.state == LinkState.Offline && AttentionStore.show("offline", offlineAttention(hub.link)) != Attention.Show.Nothing
    val accountShown = showAccount && AttentionStore.show(ACCOUNT_CARD, accountAttention(account, now)) != Attention.Show.Nothing
    // Sessions whose provider was deleted (docs/PROTOCOL.md, "A deleted provider"): one card for them all.
    val orphans = remember(hub.providers, all) { removedProviderSessions(hub.providers, all) }
    val showOrphans = orphans.isNotEmpty() && AttentionStore.show(REMOVED_CARD, orphans.size.toString()) != Attention.Show.Nothing
    val shownTroubled = remember(troubled, gone) { troubled.filter { t -> t.provider.status?.let { AttentionStore.show("provider:${t.provider.id}", providerAttention(it)) } != Attention.Show.Nothing } }
    // Where the account card sits: after the sky, the seam, the filter chip and the offline card
    // when they're there (owner's report: a filter on, the tap landed on the filter chip).
    fun accountIndex() = homeHeadIndex(filter = filter != null, offline = hub.link.state == LinkState.Offline && AttentionStore.show("offline", offlineAttention(hub.link)) != Attention.Show.Nothing)
    // Scrolled to, it sits just under the brand row rather than beneath it.
    val underBar = with(density) { -(top + 72.dp).roundToPx() }
    // An account banner was tapped: the Sessions tab, scrolled to the card that says what to do.
    if (toAutomations != null) LaunchedEffect(toAutomations) {
        snapshotFlow { toAutomations.value }.filter { it }.collect {
            toAutomations.value = false
            tab = Tab.Automations
            statsOpen = false
        }
    }
    if (toAccount != null) LaunchedEffect(toAccount) {
        snapshotFlow { toAccount.value }.filter { it }.collect {
            toAccount.value = false
            val fromTab = tab != Tab.Sessions || statsOpen
            tab = Tab.Sessions
            statsOpen = false
            if (!accountCardShown(hub.account, System.currentTimeMillis())) return@collect
            // Asked for, the account card is back even if it was swiped away.
            AttentionStore.undo(ACCOUNT_CARD)
            val at = accountIndex()
            // From History, Settings or Statistics the list still holds its last layout, from before
            // it left the screen: scrolling on that would land wrong, so wait for the one it's about
            // to get.
            if (fromTab) snapshotFlow { list.layoutInfo }.drop(1).first()
            // Back from another screen, the list is only now being laid out.
            snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > at }
            list.animateScrollToItem(at, underBar)
        }
    }
    // Settings → Glasses was asked for (a hypernova.observer/glasses link, Lens's "Open Hypernova"):
    // the Settings tab, which scrolls to it (bridge/BridgeOpen.kt).
    val glassesAsked = com.prudnikweb.hypernova.bridge.BridgeOpen.glassesAsked
    LaunchedEffect(glassesAsked) {
        if (!com.prudnikweb.hypernova.bridge.BridgeOpen.glassesWanted()) return@LaunchedEffect
        tab = Tab.Settings
        statsOpen = false
    }
    // Another part of Settings was asked for ("Turn on in Settings" on a folder outside the projects folder).
    val settingsAsked = com.prudnikweb.hypernova.ui.settings.SettingsJump.asked
    LaunchedEffect(settingsAsked) {
        if (com.prudnikweb.hypernova.ui.settings.SettingsJump.wanted() == null) return@LaunchedEffect
        tab = Tab.Settings
        statsOpen = false
    }

    if (homeShown) Box(
        Modifier
            .fillMaxSize()
            // Closing Statistics, Home comes back over it.
            .zIndex(if (statsOpen) 0f else 1f)
            .graphicsLayer {
                val k = statsAnim.value
                alpha = 1f - k
                scaleX = 1f + 0.02f * k
                scaleY = scaleX
            },
    ) {
        when (tab) {
            Tab.Sessions -> LazyColumn(
                Modifier.fillMaxSize().hazeSource(haze, zIndex = Z.CONTENT, key = HOME_SOURCE),
                state = list,
                contentPadding = PaddingValues(bottom = bottom + 84.dp), // the last card clears the bottom bar (fork: tabs only)
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // The sky: an open window onto the cosmos. Planets filter the list; the sun jumps to usage.
                item(key = "sky") {
                    SkyWindow(
                        height = skyH, cosmos = cosmos,
                        // Time warp: drag the sun sideways to age it (right) or rejuvenate it (left).
                        usageNow = { hub.usage.fiveHour },
                        onWarp = if (hub.canWarp) hub::warpFiveHour else null,
                        onHoldSun = demo?.let { it::nextAccountState },
                        filter = filter,
                        a11y = skyDescription(hub, cosmos, quiet.size, sunUsage, eclipsed),
                        projects = cosmos.projects(),
                        quiet = quiet.isNotEmpty(),
                        onFilter = onFilter,
                        // The usage card, its per-model weeks freshly read off /usage on the Mac (data/UsageRefresh.kt).
                        onJumpToUsage = { com.prudnikweb.hypernova.data.UsageRefresh.ask(hub); scope.launch { list.animateScrollToItem(usageIndex(list, codex = false, usageCards)) } },
                        codexStar = codexOn,
                        onCodexUsage = { scope.launch { list.animateScrollToItem(usageIndex(list, codex = true, usageCards)) } },
                        onStats = openStats,
                        // Not while the sun's tap does something else: the account's card, or nothing
                        // in the dark of a weekly limit. Nor while the disk is quiet and its line
                        // counts down to the reset.
                        hint = hint,
                        visibility = { skyVisibility.floatValue },
                    ) { hit ->
                        when (hit) {
                            is CosmosHit.Project -> onFilter(if (filter == SkyFilter.Project(hit.name)) null else SkyFilter.Project(hit.name))
                            CosmosHit.Quiet -> onFilter(if (filter == SkyFilter.Quiet) null else SkyFilter.Quiet)
                            is CosmosHit.Session -> onOpen(hit.id)
                            CosmosHit.Sun -> when {
                                // Eclipsed, the sun's story is the account: its card says why and what to do.
                                showAccount && account.eclipsed -> {
                                    AttentionStore.undo(ACCOUNT_CARD)
                                    scope.launch { list.animateScrollToItem(accountIndex(), underBar) }
                                }
                                // Codex's sun eclipsed (signed out): its usage card says so, and what to run.
                                eclipsed && codexLeads -> scope.launch { list.animateScrollToItem(usageIndex(list, codex = true, usageCards)) }
                                // Otherwise it's the way into Statistics.
                                else -> openStats()
                            }
                            // Codex's star: its usage card.
                            CosmosHit.CodexStar -> scope.launch { list.animateScrollToItem(usageIndex(list, codex = true, usageCards)) }
                        }
                    }
                }
                // What the list shows, when it isn't everything: a chip that undoes it.
                if (filter != null) {
                    item(key = "filter") {
                        FilterRow(filter, quiet.size, elsewhere = waitingAll - waiting, onClear = { onFilter(null) }, modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 8.dp).animateItem())
                    }
                }
                // Honest connection state: what's wrong and what to do, above the (stale) list.
                val link = hub.link
                // A short reconnect is only a chip; the card appears once we're really offline.
                if (showOffline) {
                    item(key = "offline") {
                        OfflineCard(hub, Modifier.animateItem().padding(horizontal = 16.dp).padding(bottom = 6.dp))
                    }
                }
                // The account's trouble (a limit, signed out, no plan): why Claude isn't replying,
                // and what to do, above the sessions it stopped.
                if (accountShown) {
                    item(key = ACCOUNT_CARD) {
                        AccountCard(hub, onOpen = onOpen, onNewSession = onNewSession, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp).animateItem())
                    }
                }
                if (showOrphans) {
                    item(key = REMOVED_CARD) {
                        RemovedProviderCard(orphans, onOpen, Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp).animateItem())
                    }
                }
                // Prompts typed for a session the Mac no longer has: kept, with a way to start a new session with them.
                for (w in hub.orphanedPrompts()) {
                    item(key = "gone:${w.waitingId}") {
                        GonePromptCard(hub, w, onOpen, Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp).animateItem())
                    }
                }
                for (p in shownTroubled) {
                    item(key = "provider:${p.provider.id}") {
                        ProviderCard(p, Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp).animateItem())
                    }
                }
                // An automation's question (an ask step): its answers right here, swiped away like the others.
                for (r in hub.automations.state?.asks.orEmpty()) {
                    item(key = "ask:${r.id}") {
                        com.prudnikweb.hypernova.ui.automations.AskCard(
                            hub, r, Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp).animateItem(),
                            onOpen = { onAutomation(r.automationId, null, false) },
                        )
                    }
                }
                if (filter is SkyFilter.Project) {
                    val name = filter.name
                    val path = hub.projects.firstOrNull { it.name == name }?.path ?: sessions.firstOrNull { it.cwd != null }?.cwd
                    if (path != null && !hub.isDemo) {
                        item(key = "browse") {
                            Row(Modifier.animateItem().padding(horizontal = 16.dp).padding(bottom = 4.dp)) {
                                Pill(
                                    stringResource(R.string.browse_files), { opener.files(path, name) },
                                    kind = PillKind.Outline, height = 40.dp, leading = Glyph.Folder,
                                )
                            }
                        }
                    }
                }
                if (sessions.isEmpty() && filter == null) {
                    item(key = "empty") {
                        EmptyCard(hub, onHistory = { tab = Tab.History }, modifier = Modifier.animateItem().padding(horizontal = 16.dp))
                    }
                }
                // The sessions in three groups, as `claude agents` lists them (owner, 2026-10-29): needs
                // input, working, completed, each under its own seam with how many it holds. An empty
                // group has no seam. A card moves only when its group changes (homeGroups).
                for ((group, members) in homeGroups(sessions)) {
                    item(key = group.key) {
                        GroupSeam(
                            group, members.size,
                            Modifier.animateItem().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(members, key = { it.id }) { s ->
                        SessionCard(
                            s, now = now, scramble = remember(s.id) { hub.consumeReveal(s) },
                            onClick = { onOpen(s.id) },
                            modifier = Modifier.padding(horizontal = 16.dp).animateItem(),
                            block = s.activeBlock(account, hub.providers, now),
                            waiting = hub.waitingCount(s.id),
                            codex = codexOn && s.codex,
                            ask = hub.approval(s.id).let { a -> remember(a) { CardAsk.of(a) } },
                            onAnswer = hub::answerApproval,
                            menu = if (!hub.isDemo && MenuOffer.of(s).any) { { done -> SessionMenu(hub, s, done) } } else null,
                        )
                    }
                }
                // The usage cards (UsageCards.kt): Claude's, Codex's, or both, each saying whose it is only when both show.
                if (usageCards.claude) item(key = "usage") { UsageCard(hub, usageCards, Modifier.animateItem().padding(horizontal = 16.dp).padding(top = 8.dp)) }
                val codexState = hub.codex
                if (usageCards.codex && codexState != null) {
                    item(key = "codex-usage") { CodexUsageCard(codexState, usageCards, Modifier.animateItem().padding(horizontal = 16.dp).padding(top = 8.dp)) }
                }
            }
            Tab.Automations -> com.prudnikweb.hypernova.ui.automations.AutomationsTab(
                hub, top, bottom, HOME_SOURCE,
                onEdit = { id, template -> onAutomation(id, template, false) },
                onOpenSession = onOpen,
            )
            Tab.History -> HistoryTab(hub, top, bottom, now, onOpen, onRuns = { id -> onAutomation(id, null, true) })
            Tab.Settings -> settings(top)
        }

        // Top: status bar area + brand row, blurred over whatever scrolls beneath.
        BlurredTop(haze, HOME_SOURCE, Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(top = top + 10.dp, start = 20.dp, end = 16.dp, bottom = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NovaMark(size = 22.dp)
                Spacer(Modifier.width(10.dp))
                Wordmark()
                Spacer(Modifier.weight(1f))
                // Fork: the new-session (and new-automation) pill sits up here, small, instead of
                // over the tabs, so the bottom bar is just the tabs.
                if (tab == Tab.Sessions) {
                    Pill(stringResource(R.string.new_session), onNewSession, kind = PillKind.Glass, height = 36.dp, leading = Glyph.Plus)
                    Spacer(Modifier.width(8.dp))
                }
                // A Mac with automations: a new one from here (an old Mac's tab only says to update).
                if (tab == Tab.Automations && hub.automations.state != null) {
                    Pill(stringResource(R.string.au_new), { onAutomation(null, null, false) }, kind = PillKind.Glass, height = 36.dp, leading = Glyph.Plus)
                    Spacer(Modifier.width(8.dp))
                }
                LinkChip(hub, onClick = { tab = Tab.Settings })
            }
        }

        // Bottom: the text tabs (Hyperscribe's bottom nav). Fork: no pill over them (it's in the top
        // bar) and a short fade, so more of the list shows.
        BlurredBottom(haze, HOME_SOURCE, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), fade = 14.dp) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = bottom + 6.dp)) {
                HomeTabBar(
                    Tab.entries.map { t ->
                        when (t) {
                            Tab.Sessions -> stringResource(R.string.sessions)
                            Tab.Automations -> stringResource(R.string.au_tab)
                            Tab.History -> stringResource(R.string.history)
                            Tab.Settings -> stringResource(R.string.settings)
                        }
                    },
                    tab.ordinal, { tab = Tab.entries[it] },
                    Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }

    if (statsShown) Box(
        Modifier
            .fillMaxSize()
            // Opening, it comes forward over Home.
            .zIndex(if (statsOpen) 1f else 0f)
            .graphicsLayer {
                val k = statsAnim.value
                alpha = k
                scaleX = 0.97f + 0.03f * k
                scaleY = scaleX
            },
    ) {
        StatsScreen(hub, onBack = { statsOpen = false })
    }
}

/** Whether the sun's hint has done its job: Statistics has been opened on this phone. */
private fun sunHintDone(c: Context): Boolean = c.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("sunStatsHinted", false)

private fun markSunHintDone(c: Context) {
    c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("sunStatsHinted", true).apply()
}

/**
 * Where the account card is in Home's list: after the sky, then the filter chip and the offline
 * card when they're there. The groups' seams come later, with their sessions.
 */
internal fun homeHeadIndex(filter: Boolean, offline: Boolean): Int = 1 + (if (filter) 1 else 0) + (if (offline) 1 else 0)

/**
 * The index of a usage card ([codex]: Codex's, else the sun's), wherever the list has put it: they
 * are the list's last items, Claude's before Codex's. Where Codex leads, its card is the sun's.
 */
private fun usageIndex(list: androidx.compose.foundation.lazy.LazyListState, codex: Boolean, cards: UsageCards): Int {
    val last = list.layoutInfo.totalItemsCount - 1
    val back = if (!codex && cards.claude && cards.codex) 1 else 0
    return (last - back).coerceAtLeast(0)
}

/** Home's groups, in the list's order, each with its seam's item key. */
internal enum class HomeGroup(val key: String) { NeedsInput("group-needs"), Working("group-working"), Completed("group-done") }

internal fun homeGroup(s: Session) = when {
    s.state == SessionState.NeedsYou -> HomeGroup.NeedsInput
    // Working by the one rule: its agents count, though its main agent is idle.
    s.busy -> HomeGroup.Working
    // Idle or done, and what a restart of the Mac cut short.
    else -> HomeGroup.Completed
}

/**
 * Home's sessions by group, empty groups left out. Within a group by name, A to Z (then by id), not
 * by activity: the owner saw cards "keep swapping back and forth", so one moves only when its group
 * changes.
 */
internal fun homeGroups(sessions: List<Session>): List<Pair<HomeGroup, List<Session>>> {
    val byName = sessions.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Session::name).thenBy { it.id })
    return HomeGroup.entries.mapNotNull { g -> byName.filter { homeGroup(it) == g }.takeIf { it.isNotEmpty() }?.let { g to it } }
}

@Composable
private fun SkyWindow(
    height: Dp, cosmos: CosmosState,
    usageNow: () -> Float, onWarp: ((Float) -> Unit)?, onHoldSun: (() -> Unit)?,
    filter: SkyFilter?, a11y: String, projects: List<String>, quiet: Boolean,
    onFilter: (SkyFilter?) -> Unit, onJumpToUsage: () -> Unit,
    onStats: () -> Unit, hint: Boolean, visibility: () -> Float,
    /** Codex's star is in the sky (both agents in use): TalkBack can go to its usage card. */
    codexStar: Boolean = false,
    onCodexUsage: () -> Unit = {},
    onHit: (CosmosHit) -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val hit = rememberUpdatedState(onHit)
    val warp = rememberUpdatedState(onWarp)
    val hold = rememberUpdatedState(onHoldSun)
    val usage = rememberUpdatedState(usageNow)
    val showOnly = stringResource(R.string.a11y_show_only)
    val showQuiet = stringResource(R.string.a11y_show_quiet)
    val showAll = stringResource(R.string.a11y_filter_clear)
    val jump = stringResource(R.string.a11y_jump_usage)
    val stats = stringResource(R.string.a11y_open_stats)
    val codexUsage = stringResource(R.string.a11y_codex_usage)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .onGloballyPositioned { origin = it.positionInRoot() }
            // TalkBack: what the sky says, and the same actions as tapping it.
            .semantics {
                contentDescription = a11y
                customActions = listOf(CustomAccessibilityAction(stats) { onStats(); true }) +
                    projects.map { p -> CustomAccessibilityAction(showOnly.format(p)) { onFilter(SkyFilter.Project(p)); true } } +
                    listOfNotNull(
                        if (quiet) CustomAccessibilityAction(showQuiet) { onFilter(SkyFilter.Quiet); true } else null,
                        filter?.let { CustomAccessibilityAction(showAll) { onFilter(null); true } },
                        CustomAccessibilityAction(jump) { onJumpToUsage(); true },
                        if (codexStar) CustomAccessibilityAction(codexUsage) { onCodexUsage(); true } else null,
                    )
            }
            .pointerInput(cosmos) {
                detectTapGestures(
                    onLongPress = { p -> if (cosmos.hitAt(origin + p) == CosmosHit.Sun) hold.value?.invoke() },
                ) { p -> cosmos.hitAt(origin + p)?.let { hit.value(it) } }
            }
            .pointerInput(cosmos) {
                // Time warp (demo only): a horizontal drag that starts on the sun ages it (right)
                // or rejuvenates it (left). Anything else is left alone for the list to scroll.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (warp.value == null) return@awaitEachGesture
                    if (cosmos.hitAt(origin + down.position) != CosmosHit.Sun) return@awaitEachGesture
                    val start = usage.value()
                    var dx = 0f
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over -> change.consume(); dx += over } ?: return@awaitEachGesture
                    horizontalDrag(drag.id) { change ->
                        dx += change.positionChange().x
                        change.consume()
                        warp.value?.invoke((start + dx / (size.width * 0.7f)).coerceIn(0f, 1f))
                    }
                }
            },
    ) {
        SunHint(hint, cosmos, { origin.y }, visibility, onStats)
    }
}

/**
 * The hint's line: quiet, a size between the readout's 10sp and the app's small text, so it reads
 * as the readout's second line rather than a caption of its own, with the readout's dark halo so
 * stars and orbits behind don't cut through it.
 */
private val HintStyle = Type.small.copy(fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, shadow = Shadow(Color(0xE014110E), Offset.Zero, 10f))

/**
 * Once, until Statistics is first opened: the sun's readout gains a second line, "Tap the sun for
 * your statistics" (a tap on the line opens it too). It comes once the sky has settled, stays
 * with the sun as cards scroll over the sky and fades with it, and goes for good with the first tap.
 * [windowTop] is where the sky window's top is on screen now. The sky keeps room for it
 * ([CosmosState.hint]) and says where its readout ends ([CosmosState.readoutBottomPx]).
 */
@Composable
private fun BoxScope.SunHint(
    shown: Boolean, cosmos: CosmosState, windowTop: () -> Float,
    visibility: () -> Float, onClick: () -> Unit,
) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) {
            delay(1_500)
            alpha.animateTo(1f, tween(1_200, easing = Motion.Expo))
        } else alpha.animateTo(0f, tween(Motion.DIALOG))
    }
    val present by remember { derivedStateOf { alpha.value > 0f } }
    if (!shown && !present) return
    // Only a line you can see takes taps: faded out under scrolled cards, it mustn't catch theirs.
    val tappable by remember { derivedStateOf { alpha.value > 0.5f && visibility() > 0.5f } }
    val label = stringResource(R.string.a11y_open_stats)
    HText(
        stringResource(R.string.sun_stats_hint), style = HintStyle, color = HN.inkMuted, maxLines = 1,
        modifier = Modifier
            .align(Alignment.TopCenter)
            // Just under the readout, which the sky draws on the screen, not in this window: so it
            // stays put while the window scrolls, and so does this line.
            .offset { IntOffset(0, (cosmos.readoutBottomPx - windowTop() - 1.dp.toPx()).roundToInt()) }
            .graphicsLayer { this.alpha = alpha.value * visibility() }
            .then(
                if (tappable) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
                else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/**
 * One sentence for TalkBack about the sky: the sun's usage ([u]: the leading agent's), limits and
 * projects; then Codex's star while both agents are in use, its window and its sessions.
 */
@Composable
private fun skyDescription(hub: Hub, cosmos: CosmosState, quiet: Int, u: Usage, eclipsed: Boolean): String {
    val res = LocalContext.current.resources
    val now = System.currentTimeMillis()
    val usage = when {
        eclipsed && hub.claudeAvailable -> stringResource(R.string.a11y_eclipse, accountStateName(hub.account))
        eclipsed -> stringResource(R.string.a11y_eclipse, stringResource(R.string.state_signed_out))
        u.weekLimited -> stringResource(R.string.a11y_week_limit, formatDuration(res, (u.sevenDayResetAt - now).coerceAtLeast(0)))
        u.limited -> stringResource(R.string.a11y_limit, formatDuration(res, (u.fiveHourResetAt - now).coerceAtLeast(0)))
        !u.known -> stringResource(R.string.sun_unknown_said)
        // Codex leading on a plan with only the week: the sun's window is that week.
        else -> projectionSaid(res, stringResource(if (hub.codexLeads && hub.codex?.usage?.fiveHour == null) R.string.usage_name_seven else R.string.usage_name_five), UsageProjection.fiveHour(u, now))
    }
    val needs = hub.sessions.count { it.state == SessionState.NeedsYou }
    val sky = if (quiet > 0) stringResource(R.string.a11y_sky_quiet, usage, cosmos.projects().size, quiet, needs)
    else stringResource(R.string.a11y_sky, usage, cosmos.projects().size, needs)
    if (!hub.bothAgents) return sky
    // Codex's star: its window (the 5-hour one, or the week), and how many of its sessions go round it.
    val c = hub.codex
    val cu = c?.usage
    val win = AgentLead.codexWindow(cu)
    val star = when {
        c?.account?.signedIn == false -> stringResource(R.string.a11y_codex_signed_out)
        win == null -> stringResource(R.string.a11y_codex_waiting)
        else -> {
            val five = cu?.fiveHour != null
            val p = UsageProjection.of((win.pct / 100.0).toFloat(), win.resetsAt, now, perMin = null, limited = AgentLead.codexLimitedWindow(cu) === win)
            projectionSaid(res, stringResource(if (five) R.string.cx_usage_name_five else R.string.cx_usage_name_seven), p)
        }
    }
    val n = hub.sessions.count { it.codex && com.prudnikweb.hypernova.ui.fx.SkySplit.inUse(it, now) && it.switchedTo == null }
    val starSaid = if (n == 0) star else res.getQuantityString(R.plurals.a11y_codex_star, n, star, n)
    return "$sky $starSaid"
}

/**
 * [block]: what stops it replying now (a limit, signed out), shown as its chip. [codex]: a CODEX chip (only while Codex is in use here).
 * [ask]: the approval it waits on, answered right on the card ([onAnswer]; [CardAsk]). [menu]: what a
 * long-press opens ([SessionMenu]), closed by the call it's given.
 */
@Composable
fun SessionCard(
    s: Session, now: Long, scramble: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, block: Blocked? = null, waiting: Int = 0, codex: Boolean = false,
    ask: CardAsk? = null, onAnswer: suspend (id: String, answer: Answer) -> Boolean = { _, _ -> false },
    menu: (@Composable (onDismiss: () -> Unit) -> Unit)? = null,
) {
    val needs = s.state == SessionState.NeedsYou
    val res = LocalContext.current.resources
    var menuOpen by rememberSaveable(s.id) { mutableStateOf(false) }
    if (menuOpen && menu != null) menu { menuOpen = false }
    GlassCard(
        modifier.fillMaxWidth(), accent = if (needs) HN.amber else null,
        onClick = onClick, onClickLabel = stringResource(R.string.open_session),
        onLongClick = if (menu != null) { { menuOpen = true } } else null, onLongClickLabel = stringResource(R.string.session_menu),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Seam(
                label = s.project,
                // Its short id and age as quiet marks, not "c7 · 1m".
                trailContent = { SessionStamp(s.hex, ago(s.updatedAt, now), sessionSaid(res, s.hex, s.updatedAt, now)) },
                activity = when {
                    needs -> Activity.Alert
                    s.busy -> Activity.Live
                    else -> Activity.Rest
                },
            )
            Spacer(Modifier.height(12.dp))
            ScrambledName(s.name, scramble)
            Spacer(Modifier.height(4.dp))
            if (block != null) {
                // Stopped by a limit, the account or its provider: the eclipsed sun the chat's line
                // has, then what it is and when it lifts, in words ("session limit until 2:00").
                val tone = if (block.kind == Blocked.LIMIT || block.kind == Blocked.MODEL) HN.inkMuted else HN.amber
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EclipseMark(tone, size = 11.dp)
                    Spacer(Modifier.width(7.dp))
                    HText(s.activity, style = Type.monoSmall, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (ask is CardAsk.Exact) {
                // Allow promises this exact command: the tool, then all of it as it will run, line
                // breaks and all, never cut (CardAsk, the widget's rule).
                HText(ask.tool.uppercase(), style = Type.label, color = HN.amber)
                Spacer(Modifier.height(4.dp))
                HText(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = HN.amber)) { append(if (ask.tool == "Bash") "$ " else "› ") }
                        append(ask.text)
                    },
                    style = Type.monoSmall, color = HN.ink,
                )
            } else HText(s.activity, style = Type.monoSmall, color = if (needs) HN.amber else HN.inkMuted, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // With an ask, its pills keep their room and the chips give way.
                Row(if (ask != null) Modifier.weight(1f) else Modifier, verticalAlignment = Alignment.CenterVertically) {
                    PhaseChip(s, block)
                    // Prompts typed while the Mac was away, waiting on this phone to be sent.
                    if (waiting > 0) {
                        val said = stringResource(R.string.waiting_a11y, waiting)
                        Spacer(Modifier.width(6.dp))
                        StatusChip(stringResource(R.string.waiting_chip, waiting), HN.inkMuted, glyph = Glyph.Laptop, modifier = Modifier.clearAndSetSemantics { contentDescription = said })
                    }
                    // Started by an automation: its title already says which and when.
                    if (s.automation != null) {
                        Spacer(Modifier.width(6.dp))
                        com.prudnikweb.hypernova.ui.automations.AutoChip()
                    }
                    // Codex runs it (data/Codex.kt): a quiet outline chip, as AUTO is; Claude's cards have none.
                    if (codex) {
                        Spacer(Modifier.width(6.dp))
                        com.prudnikweb.hypernova.ui.automations.OutlineChip(stringResource(R.string.cx_agent_codex), HN.inkMuted)
                    }
                }
                if (ask != null) {
                    Spacer(Modifier.width(8.dp))
                    AskPills(ask, onAnswer, onOpen = onClick)
                } else {
                    // The permission mode as its glyph and words (no model chip or agents count: the
                    // owner found them no use on the card). Short of room it keeps only its glyph.
                    Spacer(Modifier.width(8.dp))
                    val mode = modeLabel(s.mode)
                    val said = res.getString(R.string.meta_mode_said, mode)
                    FitRow(
                        parts = listOf(
                            listOf({ ModeTag(s.mode, mode) }, { ModeTag(s.mode, null) }),
                        ),
                        giveWay = intArrayOf(0, 0),
                        modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = said },
                        end = true,
                    )
                }
            }
        }
    }
}

/**
 * A card's answer to its session's approval ([CardAsk]): Deny and Allow for a tool's exact command,
 * Deny and Open when it can't show whole (no blind Allow), Answer (opens the session) for a question
 * or a plan. While an answer goes, "Allowing…" or "Denying…" in their place; one that didn't get
 * through brings them back (the toast says why).
 */
@Composable
private fun AskPills(ask: CardAsk, onAnswer: suspend (id: String, answer: Answer) -> Boolean, onOpen: () -> Unit) {
    val view = LocalView.current
    val appScope = LocalAppScope.current
    var answering by remember(ask.id) { mutableStateOf<Answer?>(null) }
    fun send(answer: Answer) {
        if (answering != null) return
        answering = answer
        appScope.launch { if (!onAnswer(ask.id, answer)) answering = null }
    }
    val going = answering
    if (going != null) {
        Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterEnd) {
            HText(
                stringResource(if (going == Answer.Allow) R.string.widget_allowing else R.string.widget_denying),
                style = Type.small, color = if (going == Answer.Allow) HN.accent else HN.inkMuted, maxLines = 1,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (ask) {
            is CardAsk.Exact -> {
                Pill(stringResource(R.string.deny), { send(Answer.Deny) }, kind = PillKind.Danger, height = 40.dp)
                Pill(
                    stringResource(R.string.allow),
                    {
                        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                        send(Answer.Allow)
                    },
                    kind = PillKind.Primary, height = 40.dp,
                )
            }
            is CardAsk.Partial -> {
                Pill(stringResource(R.string.deny), { send(Answer.Deny) }, kind = PillKind.Danger, height = 40.dp)
                Pill(stringResource(R.string.open_session_short), onOpen, kind = PillKind.Outline, height = 40.dp)
            }
            is CardAsk.Open -> Pill(stringResource(R.string.home_answer), onOpen, kind = PillKind.Primary, height = 40.dp)
        }
    }
}

/**
 * A group's seam on Home: its planet (amber for needs input, turning while working, still once
 * done), its name, a hairline, and how many it holds as a count mark in its colour (`● 2`).
 * TalkBack: "Working, 3 sessions."
 */
@Composable
private fun GroupSeam(group: HomeGroup, count: Int, modifier: Modifier = Modifier) {
    val label = stringResource(
        when (group) {
            HomeGroup.NeedsInput -> R.string.group_needs
            HomeGroup.Working -> R.string.group_working
            HomeGroup.Completed -> R.string.group_done
        },
    )
    val color = when (group) {
        HomeGroup.NeedsInput -> HN.amber
        HomeGroup.Working -> HN.accent
        HomeGroup.Completed -> HN.inkDim
    }
    val said = stringResource(R.string.group_said, label, pluralStringResource(R.plurals.sessions_count, count, count))
    Seam(
        label = label,
        trailContent = { CountMark(count.toString(), color) },
        activity = when (group) {
            HomeGroup.NeedsInput -> Activity.Alert
            HomeGroup.Working -> Activity.Live
            HomeGroup.Completed -> Activity.Rest
        },
        modifier = modifier.clearAndSetSemantics { contentDescription = said; heading() },
    )
}

/** Its own restartable scope, so the 700ms scramble recomposes only the name, not the card. */
@Composable
private fun ScrambledName(name: String, scramble: Boolean) {
    HText(rememberScrambled(name, scramble), style = Type.heading, color = HN.ink, maxLines = 1)
}

@Composable
private fun UsageCard(hub: Hub, cards: UsageCards, modifier: Modifier = Modifier) {
    val u = hub.usage
    val res = LocalContext.current.resources
    // Each meter's countdown moves when its text changes, in step with the sky's and the account card's.
    val now = rememberCountdownNow(u.fiveHourResetAt, u.sevenDayResetAt)
    // Announce limits as they happen (TalkBack), not only by colour.
    GlassCard(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp)) {
            Seam(
                stringResource(cards.claudeTitle),
                trail = when {
                    u.stale -> stringResource(R.string.usage_stale_trail)
                    hub.canWarp -> stringResource(R.string.time_warp_hint)
                    u.known -> stringResource(R.string.usage_official)
                    else -> stringResource(R.string.usage_waiting)
                },
            )
            Spacer(Modifier.height(14.dp))
            if (u.stale) {
                // Signed out or stopped: the last numbers would look live, so none, and why.
                StaleMeter(stringResource(R.string.five_hours))
                Spacer(Modifier.height(14.dp))
                StaleMeter(stringResource(R.string.seven_days))
                Spacer(Modifier.height(14.dp))
                HText(staleNote(hub.account), style = Type.small, color = HN.inkDim)
                return@Column
            }
            // Each window as a projection meter: what's used, where the pace leads by its reset, and
            // one tag (the reset, or the time to the limit when that comes first).
            val five = UsageProjection.fiveHour(u, now)
            WarpMeter(
                stringResource(R.string.five_hours), five, projectionSaid(stringResource(cards.claudeFive), five),
                enabled = hub.canWarp && !u.anyLimited,
                onWarp = hub::warpFiveHour,
            )
            Spacer(Modifier.height(14.dp))
            val seven = UsageProjection.sevenDay(u, now)
            WarpMeter(
                stringResource(R.string.seven_days), seven, projectionSaid(stringResource(cards.claudeSeven), seven),
                enabled = hub.canWarp && !u.weekLimited,
                onWarp = hub::warpSevenDay,
            )
            // Fable's week and the like, which only /usage on the Mac shows (ModelWeekMeters.kt).
            ModelWeekMeters(u.models, now)
            if (!u.known && !hub.canWarp) {
                Spacer(Modifier.height(14.dp))
                HText(stringResource(R.string.usage_unknown_note), style = Type.small, color = HN.inkDim)
            }
        }
    }
}

/**
 * Codex's own limits (data/Codex.kt), under Claude's while Codex is in use here: its 5-hour window
 * and its week as the same projection meters (a plan may have only the week), plain (the Mac sends
 * no pace for Codex). A window used up says LIMITED in amber as the seam's trail; signed out, or
 * with no numbers yet, one quiet line instead. It moves with the live feed, never by asking.
 */
@Composable
private fun CodexUsageCard(c: CodexState, cards: UsageCards, modifier: Modifier = Modifier) {
    val u = c.usage
    val res = LocalContext.current.resources
    val now = rememberCountdownNow(u?.fiveHour?.resetsAt, u?.sevenDay?.resetsAt)
    GlassCard(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp)) {
            Seam(
                stringResource(cards.codexTitle),
                trailContent = if (u?.limited == true) ({ SeamWords(stringResource(R.string.cx_usage_limited), color = HN.amber) }) else null,
                color = if (u?.limited == true) HN.amber else HN.inkMuted,
            )
            Spacer(Modifier.height(14.dp))
            val line = when {
                c.account?.signedIn == false -> stringResource(R.string.cx_usage_signed_out)
                // Where Codex leads, its card shows before it's turned on: its first session does that.
                !c.enabled && c.available -> stringResource(R.string.cx_usage_off)
                u == null || (u.fiveHour == null && u.sevenDay == null) -> c.problem ?: stringResource(R.string.cx_usage_none)
                else -> null
            }
            if (line != null || u == null) {
                HText(line.orEmpty(), style = Type.small, color = HN.inkDim)
                return@Column
            }
            u.fiveHour?.let { w ->
                val p = UsageProjection.of((w.pct / 100.0).toFloat(), w.resetsAt, now, perMin = null, limited = w.pct >= 100.0)
                ProjectionMeter(stringResource(R.string.five_hours), p, projectionSaid(res, stringResource(cards.codexFive), p))
            }
            u.sevenDay?.let { w ->
                if (u.fiveHour != null) Spacer(Modifier.height(14.dp))
                val p = UsageProjection.of((w.pct / 100.0).toFloat(), w.resetsAt, now, perMin = null, limited = w.pct >= 100.0)
                ProjectionMeter(stringResource(R.string.seven_days), p, projectionSaid(res, stringResource(cards.codexSeven), p))
            }
        }
    }
}

/**
 * A usage meter you can drag to scrub (the demo time warp). Drags are relative (no jump to
 * the finger), the limit is only committed when you let go at the end, and TalkBack can
 * adjust it like a slider (it reads [spoken]).
 */
@Composable
private fun WarpMeter(label: String, p: UsageProjection, spoken: String, enabled: Boolean = true, onWarp: (Float) -> Unit) {
    var widthPx by remember { mutableIntStateOf(1) }
    val warp = rememberUpdatedState(onWarp)
    val value = p.used
    val current = rememberUpdatedState(value)
    val on = rememberUpdatedState(enabled)
    ProjectionMeter(
        label, p, spoken,
        modifier = Modifier
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .semantics(mergeDescendants = true) {
                // The meter's own sentence is its description; the slider adds only its value.
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                if (enabled) setProgress { v -> warp.value(v); true }
            }
            .pointerInput(enabled) {
                if (!on.value) return@pointerInput
                var start = 0f
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start = current.value; dx = 0f },
                    onDragEnd = { if (on.value && start + dx / widthPx >= 0.995f) warp.value(1f) },
                ) { change, delta ->
                    if (!on.value) return@detectHorizontalDragGestures
                    change.consume()
                    dx += delta
                    warp.value((start + dx / widthPx).coerceIn(0f, 0.99f))
                }
            },
    )
}

/**
 * The connection in the brand row: the Mac's planet with this phone as its moon, and the Mac's name
 * once linked (Reconnecting, Offline… otherwise), with a MacBook's battery after it. Tap for Settings.
 */
@Composable
private fun LinkChip(hub: Hub, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.settings), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ConnectionChip(hub.link.state, name = hub.mac.name, battery = hub.mac.battery, awake = hub.mac.awake, via = hub.mac.via)
    }
}

/** Permission modes in plain words: "ask first", "accept edits", "plan"… */
@Composable
fun modeLabel(mode: String): String = when (mode) {
    "default" -> stringResource(R.string.mode_label_default)
    "acceptEdits" -> stringResource(R.string.mode_label_accept)
    "plan" -> stringResource(R.string.mode_label_plan)
    "auto" -> stringResource(R.string.mode_label_auto)
    "bypassPermissions" -> stringResource(R.string.mode_label_bypass)
    "dontAsk" -> stringResource(R.string.mode_label_dont_ask)
    // Codex's sandboxes (data/Codex.kt).
    "read-only" -> stringResource(R.string.cx_mode_label_read_only)
    "workspace-write" -> stringResource(R.string.cx_mode_label_can_edit)
    else -> mode
}

/** The session's state as a chip; [block] (what stops it replying) wins: PAUSED at a limit, SIGNED OUT in amber. */
@Composable
fun PhaseChip(s: Session, block: Blocked? = null) {
    if (block != null) {
        when (block.kind) {
            Blocked.LIMIT, Blocked.MODEL -> StatusChip(stringResource(R.string.state_paused), HN.inkDim)
            Blocked.LOGGED_OUT -> StatusChip(stringResource(R.string.state_signed_out), HN.amber)
            Blocked.NO_SUBSCRIPTION -> StatusChip(stringResource(R.string.state_no_plan), HN.amber)
            Blocked.ON_HOLD -> StatusChip(stringResource(R.string.state_on_hold), HN.amber)
            Blocked.NO_CREDIT -> StatusChip(stringResource(R.string.state_no_credit), HN.amber)
            // Another provider's: calm while it carries on by itself or waits for Continue, amber
            // while it waits on you (its line says why: "OpenRouter is out of credits").
            else -> StatusChip(stringResource(R.string.state_paused), if (block.resume?.armed == true || block.resume?.ready == true) HN.inkDim else HN.amber)
        }
        return
    }
    when (s.phase) {
        Phase.Starting -> StatusChip(stringResource(R.string.state_starting), HN.accent, live = true)
        Phase.Error -> StatusChip(stringResource(R.string.state_error), HN.danger)
        // A hollow point: over, where idle's is a dot in the same grey. Ended by a restart of the
        // Mac, it says so, behind the resume arrow in place of its point: writing picks it up again.
        Phase.Ended -> if (s.cutByRestart) StatusChip(stringResource(R.string.state_mac_restarted), HN.inkMuted, glyph = Glyph.Reload)
        else StatusChip(stringResource(R.string.state_ended), HN.inkDim, hollow = true)
        else -> when (s.state) {
            SessionState.Working -> StatusChip(stringResource(R.string.state_working), HN.accent, live = true)
            SessionState.NeedsYou -> StatusChip(stringResource(R.string.state_needs_you), HN.amber, filled = true)
            // Its main agent rests while agents it started still run: working, as everywhere
            // (Session.busy), with the agents' chip beside it, never IDLE next to "11 AGENTS".
            SessionState.Idle -> if (s.busy) StatusChip(stringResource(R.string.state_working), HN.accent, live = true)
            else StatusChip(stringResource(R.string.state_idle), HN.inkDim)
        }
    }
}

/** What the offline card says, for its dismissal (data/Attention.kt): another problem brings it back. */
internal fun offlineAttention(link: com.prudnikweb.hypernova.data.Link): String = "${link.problem}:${link.failedOver}"

/**
 * Can't reach the Mac, as an attention card: why, and when we try again under it; Retry now; the
 * routes tried, drawn; Open Tailscale (where it's the way in) and the connection log as links.
 * Swiped away it stays away until the problem changes (the brand row's chip still says offline).
 */
@Composable
private fun OfflineCard(hub: Hub, modifier: Modifier = Modifier) {
    val link = hub.link
    val res = LocalContext.current.resources
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis(), link) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    val opener = com.prudnikweb.hypernova.ui.LocalOpener.current
    val retry = when {
        link.retrying -> stringResource(R.string.retrying_now)
        link.retryAt != null && link.retryAt - now >= 1000 -> stringResource(R.string.retrying_in, formatDuration(res, link.retryAt - now))
        link.retryAt != null -> stringResource(R.string.retrying_now)
        else -> null
    }
    // The Tailscale app only where it's the way in: not while this app's own node is the one.
    val tailscale = (link.problem == Problem.NoTailscale || link.problem == Problem.Unreachable) && link.tried == null &&
        link.failedOver != com.prudnikweb.hypernova.data.RouteKind.Local && !com.prudnikweb.hypernova.data.Tsnet.status.running
    AttentionCard(
        id = "offline", state = offlineAttention(link), tone = HN.danger, glyph = Glyph.Unlink,
        title = stringResource(R.string.cant_reach, hub.mac.name),
        line = com.prudnikweb.hypernova.data.linkProblemText(res, link),
        stamp = retry,
        action = AttentionAction(stringResource(R.string.retry_now), hub::retry),
        links = listOfNotNull(
            if (tailscale) AttentionAction(stringResource(R.string.open_tailscale), { openTailscale(context) }) else null,
            AttentionAction(stringResource(R.string.connlog_open), opener.connectionLog),
        ),
        modifier = modifier,
        content = link.tried?.let { tried ->
            // What the last round tried, drawn (`LOCAL  REFUSED`, `TAILSCALE  NO ANSWER`).
            { com.prudnikweb.hypernova.ui.settings.RouteTriedRow(tried, showBase = hub.mac.via == com.prudnikweb.hypernova.data.Mac.VIA_REMOTE) }
        },
    )
}

/** A usage meter with nothing measured: its label, a dash, an empty track. */
@Composable
private fun StaleMeter(label: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HText(label.uppercase(), style = Type.label, color = HN.inkDim)
            Spacer(Modifier.weight(1f))
            HText(stringResource(R.string.usage_dash), style = Type.monoSmall, color = HN.inkDim)
        }
        Spacer(Modifier.height(7.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(HN.line))
    }
}

@Composable
private fun staleNote(a: Account): String = stringResource(
    when (a.state) {
        Account.LOGGED_OUT -> R.string.usage_stale_signed_out
        Account.NO_SUBSCRIPTION -> R.string.usage_stale_no_plan
        Account.ON_HOLD -> R.string.usage_stale_on_hold
        else -> R.string.usage_stale
    },
)

/** The line under the eclipsed sun, or null when the sun isn't eclipsed. */
@Composable
private fun eclipseLine(a: Account): String? = when (a.state) {
    Account.LOGGED_OUT -> stringResource(R.string.eclipse_signed_out)
    Account.NO_SUBSCRIPTION -> stringResource(R.string.eclipse_no_plan)
    Account.ON_HOLD -> stringResource(R.string.eclipse_on_hold)
    else -> null
}

/** "Signed out", "No plan", "On hold": the account's state as its chips say it. */
@Composable
private fun accountStateName(a: Account): String = stringResource(
    when (a.state) {
        Account.LOGGED_OUT -> R.string.state_signed_out
        Account.NO_SUBSCRIPTION -> R.string.state_no_plan
        Account.ON_HOLD -> R.string.state_on_hold
        Account.NO_CREDIT -> R.string.state_no_credit
        else -> R.string.state_paused
    },
)

/** The account card's key in the list and for its dismissal. */
internal const val ACCOUNT_CARD = "account"

/**
 * What the account card says, for its dismissal (data/Attention.kt): another state, reason or
 * limit (a new reset is a new episode) brings it back.
 */
internal fun accountAttention(a: Account, now: Long): String {
    val l = a.resting(now)
    return "${a.state}:${a.reason}:${l?.window}:${l?.resetsAt}"
}

/** Whether Home shows the account card: something is wrong now (a limit whose reset has passed is over). */
private fun accountCardShown(a: Account, now: Long): Boolean = when (a.state) {
    Account.OK -> false
    Account.LIMITED -> a.resting(now) != null
    Account.UNKNOWN -> a.text != null
    else -> true
}

/** "Continue on OpenRouter", or on the subscription for a session that's elsewhere now. */
@Composable
fun continueLabel(p: Provider): String =
    if (p.builtIn) stringResource(R.string.account_continue_subscription) else stringResource(R.string.account_continue_on, p.name)

/** What the account card says: its seam, the countdown, a heading and one line. */
private class AccountText(val seam: String, val trail: String?, val heading: String, val body: String?)

/**
 * The account's trouble on Home, in the offline card's place and shape: what happened, what it
 * means for the sessions, and the one or two things to do. Amber only when it needs the owner
 * (signed out, no plan, out of credits, on hold); a plain limit is calm, it ends by itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountCard(hub: Hub, onOpen: (String) -> Unit, onNewSession: () -> Unit, modifier: Modifier = Modifier) {
    val a = hub.account
    val context = LocalContext.current
    val res = context.resources
    val opener = LocalOpener.current
    val appScope = LocalAppScope.current
    val toast = LocalToast.current
    // The countdown in the seam moves exactly when its text changes, as the sky's does
    // (ui/fx/Countdown.kt): it used to look every 20 s and read 6:06 under the sky's 5:59.
    val now = rememberCountdownNow(a.limit?.resetsAt)
    val mac = if (hub.isDemo) stringResource(R.string.your_mac) else hub.mac.name
    val limit = a.resting(now)
    // The session that hit it, while it's still stopped: the card's actions carry that one on.
    val hit = a.sessionId?.let(hub::session)?.takeIf { it.activeBlock(a, hub.providers, now) != null }
    val other = hub.providers.healthyOther(a, hit?.providerId, now)
    var busy by remember { mutableStateOf(false) }
    fun act(block: suspend () -> Result<*>) {
        if (busy) return
        busy = true
        appScope.launch {
            block().onFailure { toast(it.message.orEmpty()) }
            busy = false
        }
    }
    val left = limit?.resetsAt?.let { formatDuration(res, (it - now).coerceAtLeast(0)) }
    fun at(t: Long) = AccountWords.atClock(context, t, now)
    fun clock(t: Long) = AccountWords.clock(context, t, now)
    val text = when (a.state) {
        Account.LIMITED -> {
            val l = limit ?: return
            val label = AccountWords.label(context, l)
            val until = l.resetsAt
            when {
                l.usingCredits -> AccountText(label, left?.let { stringResource(R.string.resets_in, it) }, stringResource(R.string.account_on_credits_title), until?.let { stringResource(R.string.account_on_credits_body, clock(it)) })
                l.window == "seven_day" -> AccountText(label, left?.let { stringResource(R.string.account_returns_in, it) }, stringResource(R.string.account_week_title), until?.let { stringResource(R.string.account_week_body, at(it)) })
                l.window == "model" -> {
                    val model = AccountWords.model(l)
                    AccountText(
                        label, left?.let { stringResource(R.string.resets_in, it) },
                        if (model != null && until != null) stringResource(R.string.account_model_title, model, clock(until)) else stringResource(R.string.account_model_title_plain, label),
                        stringResource(R.string.account_model_body),
                    )
                }
                // The extra usage's own limit (a monthly cap, say): not the plan's 5 hours.
                l.window == "credits" -> AccountText(
                    label, left?.let { stringResource(R.string.resets_in, it) },
                    if (until != null) stringResource(R.string.account_rest_title, clock(until)) else label,
                    stringResource(R.string.account_credits_limit_body),
                )
                else -> AccountText(
                    label, left?.let { stringResource(R.string.resets_in, it) },
                    if (until != null) stringResource(R.string.account_rest_title, clock(until)) else label,
                    l.autoResumeAt?.takeIf { it > now }?.let { stringResource(R.string.account_rest_body_auto, at(it)) } ?: stringResource(R.string.account_rest_body),
                )
            }
        }
        Account.NO_CREDIT -> AccountText(
            stringResource(R.string.account_seam_credits), left?.let { stringResource(R.string.resets_in, it) },
            stringResource(R.string.account_credits_title),
            stringResource(
                when (a.reason) {
                    "spend_cap" -> R.string.account_credits_body_cap
                    "api_credit" -> R.string.account_credits_body_api
                    else -> R.string.account_credits_body
                },
            ),
        )
        Account.LOGGED_OUT -> AccountText(
            stringResource(R.string.account_seam_signed_out), null,
            stringResource(R.string.account_signed_out_title, mac),
            stringResource(
                when (a.reason) {
                    "expired" -> R.string.account_signed_out_expired
                    "revoked" -> R.string.account_signed_out_revoked
                    // Turned down: a lost login and an ended plan look the same from here.
                    "rejected" -> R.string.account_signed_out_rejected
                    else -> R.string.account_signed_out_body
                },
            ),
        )
        Account.NO_SUBSCRIPTION -> AccountText(
            stringResource(R.string.account_seam_no_plan), null,
            stringResource(
                when (a.reason) {
                    "ended", "trial_ended" -> R.string.account_no_plan_ended
                    "downgraded", "org_disabled" -> R.string.account_no_plan_cover
                    // A login the server turned down can't tell an ended plan from a lost login.
                    else -> R.string.account_no_plan_maybe
                },
            ),
            stringResource(if (a.reason == "org_disabled") R.string.account_no_plan_org else R.string.account_no_plan_body),
        )
        Account.ON_HOLD -> AccountText(
            stringResource(R.string.account_seam_on_hold), null,
            stringResource(R.string.account_on_hold_title), stringResource(R.string.account_on_hold_body),
        )
        else -> AccountText(stringResource(R.string.account_seam_unknown), null, stringResource(R.string.account_unknown_title), a.text)
    }
    val needsYou = a.needsYou
    // The conversation goes on elsewhere: the session that hit it, a new one, or first a provider.
    val goOn = when {
        other != null && hit != null -> AttentionAction(continueLabel(other), { act { hub.switchProvider(hit.id, other.id).onSuccess { onOpen(it) } } })
        other != null -> AttentionAction(stringResource(R.string.account_new_on, other.name), onNewSession)
        else -> AttentionAction(stringResource(R.string.account_add_provider), opener.providers, glyph = Glyph.Plus)
    }
    val usage = AttentionAction(stringResource(R.string.account_usage_link), { openLink(context, AccountWords.USAGE_URL) }, glyph = Glyph.Globe, description = stringResource(R.string.a11y_account_usage_link))
    var action: AttentionAction? = null
    var links = emptyList<AttentionAction>()
    val signingIn = a.login != null
    when {
        signingIn -> Unit
        a.state == Account.LOGGED_OUT -> {
            action = AttentionAction(stringResource(R.string.account_sign_in), { act { hub.signIn(onMac = false) } })
            // Signing in on the computer opens a terminal window there: a Mac's, never a server's.
            links = listOfNotNull(
                if (hub.mac.has(com.prudnikweb.hypernova.data.Mac.FEATURE_OPEN_WINDOW)) AttentionAction(stringResource(R.string.account_sign_in_mac), { act { hub.signIn(onMac = true) } }, glyph = Glyph.Laptop) else null,
                goOn,
            )
        }
        a.state == Account.LIMITED -> {
            val l = limit
            val model = AccountWords.model(l)
            when {
                l?.usingCredits == true -> links = listOf(usage)
                l?.window == "model" -> {
                    if (hit != null) {
                        // The other everyday model: Opus resting means Sonnet, and the other way round.
                        val to = if (model.equals("sonnet", ignoreCase = true)) "opus" else "sonnet"
                        action = AttentionAction(stringResource(R.string.account_switch_model, to.replaceFirstChar { it.uppercase() }), { act { hub.send(hit.id, "/model $to").onSuccess { onOpen(it) } } })
                    }
                    links = listOf(usage)
                }
                else -> { action = goOn; links = listOf(usage) }
            }
        }
        a.state == Account.NO_CREDIT -> {
            action = AttentionAction(stringResource(R.string.account_add_funds), { openLink(context, if (a.reason == "api_credit") AccountWords.API_BILLING_URL else AccountWords.USAGE_URL) })
            links = listOf(goOn)
        }
        a.state == Account.NO_SUBSCRIPTION -> {
            // Short, with a globe like Usage, so it fits beside the words.
            action = AttentionAction(stringResource(R.string.account_renew_short), { openLink(context, AccountWords.RENEW_URL) }, glyph = Glyph.Globe, description = stringResource(R.string.account_renew))
            links = listOf(goOn, AttentionAction(stringResource(R.string.account_sign_in_again), { act { hub.signIn(onMac = false) } }))
        }
        a.state == Account.ON_HOLD -> {
            action = AttentionAction(stringResource(R.string.account_view_details), { openLink(context, AccountWords.link(a.text, AccountWords.HOLD_URL)) })
            links = listOf(goOn, AttentionAction(stringResource(R.string.account_check), { act { hub.checkAccount() } }))
        }
        else -> action = AttentionAction(stringResource(R.string.account_check), { act { hub.checkAccount() } })
    }
    AttentionCard(
        id = ACCOUNT_CARD, state = accountAttention(a, now),
        tone = if (needsYou) HN.amber else HN.accentDim,
        glyph = when (a.state) {
            Account.NO_CREDIT -> Glyph.Note
            Account.LOGGED_OUT, Account.NO_SUBSCRIPTION, Account.ON_HOLD -> Glyph.Person
            else -> null
        },
        title = text.heading, line = text.body, stamp = text.trail,
        action = action, links = links,
        // A sign-in under way stays until it's done or cancelled.
        dismissible = !signingIn,
        modifier = modifier,
        content = if (signingIn) ({ SignInPanel(hub, mac) }) else null,
    )
}

/**
 * Signing Claude Code in again, from here: Sign in opens its sign-in page on this phone and takes
 * the code the page shows back to the Mac; or it runs in a terminal on the Mac. Each step of the
 * sign-in shows as it happens; once it works, the account card goes and the sun comes back.
 * [more]: pills for another way on (another provider), beside Sign in until a sign-in starts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignInPanel(hub: Hub, mac: String, modifier: Modifier = Modifier, more: (@Composable () -> Unit)? = null) {
    val login = hub.account.login
    val context = LocalContext.current
    val appScope = LocalAppScope.current
    val toast = LocalToast.current
    var busy by remember { mutableStateOf(false) }
    // A live, single-use credential: kept in memory only, never in the saved state.
    var code by remember { mutableStateOf("") }
    fun act(block: suspend () -> Result<*>) {
        if (busy) return
        busy = true
        appScope.launch {
            block().onFailure { toast(it.message.orEmpty()) }
            busy = false
        }
    }
    fun send() {
        val c = code.trim()
        if (c.isEmpty()) return
        act { hub.sendSignInCode(c).onSuccess { code = "" } }
    }
    val cancel: @Composable () -> Unit = {
        Pill(stringResource(R.string.cancel), { act { hub.cancelSignIn() } }, kind = PillKind.Ghost, height = 36.dp)
    }
    Column(modifier.fillMaxWidth()) {
        when {
            login == null -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(R.string.account_sign_in), { act { hub.signIn(onMac = false) } }, kind = PillKind.Primary, height = 40.dp)
                Pill(stringResource(R.string.account_sign_in_mac), { act { hub.signIn(onMac = true) } }, kind = PillKind.Ghost, height = 40.dp, leading = Glyph.Laptop)
                more?.invoke()
            }
            login.state == "starting" -> LoadingLine(stringResource(R.string.signin_starting, mac))
            login.state == "finishing" -> LoadingLine(stringResource(R.string.signin_finishing))
            login.state == "failed" -> {
                HText(login.error ?: stringResource(R.string.signin_failed), style = Type.small, color = HN.danger)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(stringResource(R.string.try_again), { act { hub.signIn(login.onMac) } }, kind = PillKind.Outline, height = 40.dp)
                    cancel()
                }
            }
            login.onMac -> {
                LoadingLine(stringResource(R.string.signin_on_mac))
                Spacer(Modifier.height(8.dp))
                cancel()
            }
            else -> {
                // A code the sign-in turned down says so here, and the field is ready for the next one.
                if (login.error != null) HText(login.error, style = Type.small, color = HN.danger)
                else HText(stringResource(R.string.signin_steps), style = Type.small, color = HN.inkMuted)
                Spacer(Modifier.height(12.dp))
                if (login.url != null) {
                    Pill(stringResource(R.string.signin_open_page), { openLink(context, login.url) }, Modifier.fillMaxWidth(), kind = PillKind.Primary, height = 44.dp, leading = Glyph.Globe)
                    Spacer(Modifier.height(10.dp))
                }
                val hint = stringResource(R.string.signin_code_hint)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Field(
                        code, { code = it }, Modifier.weight(1f), placeholder = hint, mono = true, label = hint,
                        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        onDone = ::send,
                    )
                    Spacer(Modifier.width(8.dp))
                    // Coming back from the browser with the code copied: one tap puts it in, the next sends it.
                    if (code.isBlank()) {
                        val nothing = stringResource(R.string.signin_nothing_to_paste)
                        Pill(stringResource(R.string.signin_paste), {
                            val clip = clipboardText(context)?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it }
                            if (clip != null) code = clip else toast(nothing)
                        }, kind = PillKind.Outline, height = 48.dp)
                    } else {
                        Pill(stringResource(R.string.send), ::send, kind = PillKind.Primary, height = 48.dp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                cancel()
            }
        }
    }
}

/**
 * What the list shows when it isn't everything (a planet or the belt was tapped): a glass chip with
 * the project's name (or "5 quiet projects") and a cross; tapping it shows everything again. Beside
 * it, in amber, how many sessions elsewhere need you, which also shows everything.
 */
@Composable
private fun FilterRow(filter: SkyFilter, quiet: Int, elsewhere: Int, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val label = when (filter) {
        is SkyFilter.Project -> filter.name
        SkyFilter.Quiet -> pluralStringResource(R.plurals.filter_quiet, quiet, quiet)
    }
    val view = LocalView.current
    val clear = stringResource(R.string.a11y_filter_clear)
    val showing = stringResource(R.string.a11y_filter_showing, label)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f, fill = false)
                .semantics { contentDescription = showing }
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = clear) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onClear()
                }
                // A 36dp capsule in a 48dp touch target.
                .padding(vertical = 6.dp)
                .heightIn(min = 36.dp)
                .frostedGlass(HN.accent, glowX = 16.dp, dense = true)
                .padding(start = 13.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(HN.accent, androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(9.dp))
            HText(label.uppercase(), style = Type.label, color = HN.ink, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(10.dp))
            com.prudnikweb.hypernova.ui.components.Icon(Glyph.Cross, size = 12.dp, color = HN.inkMuted)
        }
        if (elsewhere > 0) {
            Spacer(Modifier.width(12.dp))
            HText(
                pluralStringResource(R.plurals.filter_waiting_elsewhere, elsewhere, elsewhere),
                style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.5f), color = HN.amber, maxLines = 1,
                modifier = Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = clear, onClick = onClear)
                    .padding(vertical = 14.dp),
            )
        }
    }
}

/** A provider in trouble, and how many live sessions are on it. */
private class Troubled(val provider: Provider, val sessions: Int)

/**
 * The providers Home shows a card for: credit or a key are the owner's to fix, so always; a rate
 * limit or an outage pass by themselves, so only while sessions on it wait.
 */
private fun troubledProviders(providers: List<Provider>, sessions: List<Session>): List<Troubled> = providers.mapNotNull { p ->
    val st = p.status ?: return@mapNotNull null
    if (p.builtIn) return@mapNotNull null
    val on = sessions.count { it.providerId == p.id && it.phase != Phase.Ended }
    if (com.prudnikweb.hypernova.data.ProviderWords.needsYou(st) || on > 0) Troubled(p, on) else null
}

private const val OPENROUTER_CREDITS = "https://openrouter.ai/settings/credits"

/** The removed provider's card: its key in the list and for its dismissal. */
private const val REMOVED_CARD = "provider:removed"

/** Live sessions stopped because their provider was deleted: on a provider the list no longer has. */
internal fun removedProviderSessions(providers: List<Provider>, sessions: List<Session>): List<Session> {
    if (providers.isEmpty()) return emptyList()
    return sessions.filter { s ->
        s.phase != Phase.Ended && s.blocked?.kind == Blocked.PROVIDER && s.providerId != null && providers.none { it.id == s.providerId }
    }
}

/**
 * Sessions whose provider was removed, as one attention card: they can't reply until each is moved
 * to another provider (its chat offers that). One session: the pill opens it.
 */
@Composable
private fun RemovedProviderCard(sessions: List<Session>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val one = sessions.singleOrNull()
    AttentionCard(
        id = REMOVED_CARD, state = sessions.size.toString(), tone = HN.amber, glyph = Glyph.Key,
        title = stringResource(R.string.provider_removed_title),
        line = pluralStringResource(R.plurals.provider_removed_line, sessions.size, sessions.size),
        action = one?.let { s -> AttentionAction(stringResource(R.string.open_session_short), { onOpen(s.id) }) },
        modifier = modifier,
    )
}

/**
 * A prompt typed while the Mac was away, whose session the Mac no longer has: it stays here, as an
 * attention card, to start a new session with ("Start a new session with it") or to remove.
 */
@Composable
private fun GonePromptCard(hub: Hub, w: Item.User, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val id = w.waitingId ?: return
    val appScope = LocalAppScope.current
    val toast = LocalToast.current
    AttentionCard(
        id = "gone:$id", state = "gone", tone = HN.amber, glyph = Glyph.Laptop,
        title = stringResource(R.string.waiting_orphan_title),
        line = w.text,
        action = AttentionAction(stringResource(R.string.waiting_start_new), {
            appScope.launch { hub.startFromWaiting(id).onSuccess(onOpen).onFailure { toast(it.message.orEmpty()) } }
        }),
        links = listOf(AttentionAction(stringResource(R.string.waiting_remove), { hub.removeWaiting(id) })),
        dismissible = false,
        modifier = modifier,
    )
}

/** What a provider's card says, for its dismissal (data/Attention.kt): another kind of trouble brings it back. */
internal fun providerAttention(st: com.prudnikweb.hypernova.data.ProviderStatus): String = "${st.state}:${st.budget}"

/**
 * A provider's trouble on Home, as an attention card (owner, 2026-10-18: errors are a card with a
 * way to fix them, not words in the sky): what's wrong, what it means for the sessions on it, and
 * its one fix as the pill (Fix the key, Add credits). A tap on the card opens that provider's
 * settings. Amber while it's the owner's to fix; a rate limit or an outage passes by itself, calm.
 */
@Composable
private fun ProviderCard(t: Troubled, modifier: Modifier = Modifier) {
    val p = t.provider
    val st = p.status ?: return
    val context = LocalContext.current
    val opener = LocalOpener.current
    val needsYou = com.prudnikweb.hypernova.data.ProviderWords.needsYou(st)
    val now = rememberCountdownNow(st.resetsAt)
    val title = when (st.state) {
        com.prudnikweb.hypernova.data.ProviderStatus.BAD_KEY -> stringResource(R.string.provider_card_bad_key, p.name)
        com.prudnikweb.hypernova.data.ProviderStatus.NO_CREDIT -> stringResource(if (st.budget) R.string.provider_card_budget else R.string.provider_card_no_credit, p.name)
        com.prudnikweb.hypernova.data.ProviderStatus.LIMITED -> stringResource(R.string.provider_card_limited, p.name)
        com.prudnikweb.hypernova.data.ProviderStatus.DOWN -> stringResource(R.string.provider_card_down, p.name)
        else -> stringResource(R.string.provider_card_other, p.name)
    }
    val body = when (st.state) {
        com.prudnikweb.hypernova.data.ProviderStatus.BAD_KEY -> stringResource(R.string.provider_card_bad_key_body)
        com.prudnikweb.hypernova.data.ProviderStatus.NO_CREDIT -> stringResource(R.string.provider_card_no_credit_body)
        com.prudnikweb.hypernova.data.ProviderStatus.LIMITED -> st.resetsAt?.takeIf { it > now }
            ?.let { stringResource(R.string.provider_card_limited_body, com.prudnikweb.hypernova.data.AccountWords.atClock(context, it, now)) }
            ?: stringResource(R.string.provider_no_reset)
        com.prudnikweb.hypernova.data.ProviderStatus.DOWN -> stringResource(R.string.provider_down_line)
        else -> st.text.takeIf { it.isNotBlank() }
    }
    val credits = st.state == com.prudnikweb.hypernova.data.ProviderStatus.NO_CREDIT && !st.budget && p.kind == "openrouter"
    val settings = { opener.provider(p.id, p.kind) }
    AttentionCard(
        id = "provider:${p.id}", state = providerAttention(st),
        tone = if (needsYou) HN.amber else HN.accentDim,
        glyph = when (st.state) {
            com.prudnikweb.hypernova.data.ProviderStatus.BAD_KEY -> Glyph.Key
            com.prudnikweb.hypernova.data.ProviderStatus.NO_CREDIT -> Glyph.Note
            com.prudnikweb.hypernova.data.ProviderStatus.LIMITED -> Glyph.Timer
            com.prudnikweb.hypernova.data.ProviderStatus.DOWN -> Glyph.Unlink
            else -> null
        },
        title = title, line = body,
        // How many of the list's sessions it holds, as a figure under the line.
        stamp = if (t.sessions > 0) pluralStringResource(R.plurals.provider_card_paused, t.sessions, t.sessions) else null,
        stampGlyph = null,
        action = when {
            st.state == com.prudnikweb.hypernova.data.ProviderStatus.BAD_KEY -> AttentionAction(stringResource(R.string.provider_fix_key), settings)
            credits -> AttentionAction(stringResource(R.string.provider_add_credits), { openLink(context, OPENROUTER_CREDITS) }, glyph = Glyph.Globe)
            needsYou -> AttentionAction(stringResource(R.string.provider_fix), settings)
            // A rate limit or an outage passes by itself: no pill; the card opens its settings.
            else -> null
        },
        onClick = settings,
        modifier = modifier,
    )
}

/**
 * No sessions: say how to get one (or that we're still connecting). With ended ones in History,
 * "Nothing running right now" and the way there, rather than "No sessions yet".
 */
@Composable
private fun EmptyCard(hub: Hub, onHistory: () -> Unit, modifier: Modifier = Modifier) {
    val ended = remember(hub.sessions) { hub.sessions.any { it.phase == Phase.Ended } }
    GlassCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (hub.link.state == LinkState.Connecting) {
                com.prudnikweb.hypernova.ui.components.LoadingLine(stringResource(R.string.connecting_to, hub.mac.name))
            } else if (ended) {
                HText(stringResource(R.string.empty_quiet_title), style = Type.heading, color = HN.ink)
                Spacer(Modifier.height(4.dp))
                HText(stringResource(R.string.empty_quiet_body), style = Type.small, color = HN.inkMuted)
                com.prudnikweb.hypernova.ui.components.AttentionLinks(listOf(AttentionAction(stringResource(R.string.empty_open_history), onHistory)), Modifier.padding(top = 4.dp))
            } else {
                HText(stringResource(R.string.empty_title), style = Type.heading, color = HN.ink)
                Spacer(Modifier.height(4.dp))
                // What to run in a terminal: claude, codex, or either (the shell's codex is there only
                // while Codex is on; before that, the first Codex session turns it on).
                val claudeHere = com.prudnikweb.hypernova.data.AgentLead.claudeAvailable(hub.claude)
                val codexOn = com.prudnikweb.hypernova.data.AgentLead.codexInUse(hub.codex)
                val body = when {
                    claudeHere && codexOn -> R.string.empty_body_both
                    claudeHere -> R.string.empty_body
                    codexOn -> R.string.empty_body_codex
                    hub.codex?.available == true -> R.string.empty_body_codex_off
                    else -> R.string.empty_body
                }
                HText(stringResource(body), style = Type.small, color = HN.inkMuted)
            }
        }
    }
}

@Composable
fun ago(t: Long, now: Long): String {
    val s = ((now - t) / 1000).coerceAtLeast(0)
    return when {
        s < 45 -> stringResource(R.string.ago_now)
        s < 3600 -> stringResource(R.string.ago_minutes, maxOf(1L, s / 60))
        s < 86400 -> stringResource(R.string.ago_hours, s / 3600)
        else -> stringResource(R.string.ago_days, s / 86400)
    }
}
