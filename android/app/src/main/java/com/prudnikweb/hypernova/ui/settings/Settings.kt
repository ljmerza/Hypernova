// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.HubViewModel
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.LiveHub
import com.prudnikweb.hypernova.data.MacBattery
import com.prudnikweb.hypernova.data.Route
import com.prudnikweb.hypernova.data.strings
import com.prudnikweb.hypernova.data.storage
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.ConnectionChip
import com.prudnikweb.hypernova.ui.components.BatteryMark
import com.prudnikweb.hypernova.ui.components.Bolt
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.routeWord
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.InfoRow
import com.prudnikweb.hypernova.ui.components.LocalHaze
import com.prudnikweb.hypernova.ui.components.NavRow
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.screenBottom
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.components.Z
import com.prudnikweb.hypernova.ui.home.HOME_SOURCE
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** "charging", "on power", "3h 5m left", or "on battery" while macOS estimates. */
private fun batteryState(res: android.content.res.Resources, b: MacBattery): String = when {
    b.charging -> res.getString(R.string.mac_battery_charging)
    b.onAC -> res.getString(R.string.mac_battery_on_power)
    b.minutesLeft != null -> res.getString(
        R.string.mac_battery_left,
        if (b.minutesLeft < 60) res.getString(R.string.dur_m, b.minutesLeft) else res.getString(R.string.dur_hm, b.minutesLeft / 60, b.minutesLeft % 60),
    )
    else -> res.getString(R.string.mac_battery_draining)
}

/**
 * The Mac's battery row, drawn: how it's powered after its glyph (a bolt charging, a plug on
 * power, a stopwatch with the time left), then a small battery filled to its level and the
 * percentage, amber when low, red when critical. No "62% · charging". TalkBack: one sentence.
 */
@Composable
private fun BatteryRow(b: MacBattery) {
    val res = LocalContext.current.resources
    val state = batteryState(res, b)
    val tone = when {
        b.critical -> HN.danger
        b.low -> HN.amber
        else -> HN.inkMuted
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp).clearAndSetSemantics { contentDescription = res.getString(R.string.mac_battery_said, b.percent, state) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(stringResource(R.string.mac_battery), style = Type.small, color = HN.inkDim)
        Spacer(Modifier.weight(1f))
        when {
            b.charging -> Bolt(HN.accent.copy(alpha = 0.85f))
            b.onAC -> Icon(Glyph.Plug, size = 12.dp, color = HN.inkDim, stroke = 1.2.dp)
            b.minutesLeft != null -> Icon(Glyph.Timer, size = 12.dp, color = HN.inkDim, stroke = 1.2.dp)
        }
        Spacer(Modifier.width(5.dp))
        HText(state, style = Type.small, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(14.dp))
        BatteryMark(b.percent, tone)
        Spacer(Modifier.width(5.dp))
        HText("${b.percent}%", style = Type.monoSmall, color = tone, maxLines = 1)
    }
}

/**
 * Brings the item [key] to the top of [list] (just under the bar, where its padding ends): the list
 * says which index a key has only once it's laid out, so it steps a screen at a time until it shows.
 */
private suspend fun scrollToKey(list: androidx.compose.foundation.lazy.LazyListState, key: Any) {
    // Laid out by the first frame after the tab appears.
    androidx.compose.runtime.snapshotFlow { list.layoutInfo.totalItemsCount > 0 }.first { it }
    repeat(40) {
        val info = list.layoutInfo
        info.visibleItemsInfo.firstOrNull { it.key == key }?.let { list.animateScrollToItem(it.index); return }
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: return
        if (last >= info.totalItemsCount - 1) return
        list.scrollToItem(last)
    }
}

/**
 * The Settings tab: a row per server (each opening [ServerSettingsScreen]), then what this phone
 * keeps, the longer parts behind rows of their own (SettingsPlan). The same page with one server and
 * with "All servers": one server just has one row.
 */
@Composable
fun SettingsTab(vm: HubViewModel, top: Dp) {
    val haze = LocalHaze.current
    // All servers (owner, 2026-10-31): nothing here acts on the server picked last; each server's settings are its own page.
    val allMode = vm.allServers
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val live = vm.live
    val hub = vm.hub
    val context = LocalContext.current
    var testing by remember { mutableStateOf(TestingTools.unlocked(context)) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()

    // The servers listed: every paired one with All servers (in the order they were paired), else the one on screen.
    val every = vm.everyHub
    val listed = if (allMode) every else every.filter { it.first.id == live?.serverId }
    val parts = SettingsPlan.parts(listed.map { it.first.id }, BuildConfig.DEBUG || testing)

    // Another part was asked for (SettingsJump: "Turn on in Settings"): those live on a server's page,
    // the one of the server it was asked on. That page scrolls to the part and says it's done.
    val jumpAsked = SettingsJump.asked
    LaunchedEffect(jumpAsked, hub != null) {
        if (SettingsJump.wanted() == null || hub == null) return@LaunchedEffect
        val server = SettingsJump.server?.takeIf { id -> every.any { it.first.id == id } } ?: live?.serverId
        if (server != null) vm.nav.push(Route.ServerSettings(server)) else SettingsJump.shown()
    }

    LazyColumn(
        Modifier.fillMaxSize().hazeSource(haze, zIndex = Z.CONTENT, key = HOME_SOURCE),
        state = list,
        contentPadding = PaddingValues(top = top + 84.dp, bottom = bottom + 120.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (part in parts) item(key = part.key) {
            when (part) {
                is SettingsPlan.Servers -> {
                    GroupHeading(stringResource(if (allMode) R.string.st_servers else R.string.mac), first = true)
                    if (listed.isEmpty()) GlassCard(Modifier.fillMaxWidth()) {
                        HText(stringResource(R.string.not_paired), style = Type.heading, color = HN.ink, modifier = Modifier.padding(16.dp))
                    } else GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            listed.forEachIndexed { i, (status, _) ->
                                if (i > 0) Hairline(Modifier.fillMaxWidth())
                                NavRow(status.name, stringResource(R.string.st_server_row_desc), { vm.nav.push(Route.ServerSettings(status.id)) }, end = { ConnectionChip(status.state) })
                            }
                            // Continue after signing in on every server at once (LoginContinueRow.kt).
                            if (listed.size > 1) {
                                Hairline(Modifier.fillMaxWidth())
                                LoginContinueAllRow(listed.filter { it.first.state == LinkState.Connected }.map { it.second })
                            }
                        }
                    }
                }
                is SettingsPlan.Phone -> {
                    GroupHeading(stringResource(R.string.st_all_phone), first = false)
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            part.pages.forEachIndexed { i, page ->
                                if (i > 0) Hairline(Modifier.fillMaxWidth())
                                PhonePageRow(page) { vm.nav.push(it) }
                            }
                        }
                    }
                }
                // Fork: Display, the app's text size (DisplaySection below), kept on this phone.
                SettingsPlan.Display -> {
                    Spacer(Modifier.height(10.dp))
                    DisplaySection()
                }
                // The Ghostty terminal trial (TerminalEngineSection.kt), with the testing checklist.
                SettingsPlan.TerminalEngine -> {
                    Spacer(Modifier.height(10.dp))
                    TerminalEngineSection()
                }
                SettingsPlan.About -> {
                    // A newer app: with All servers, the newest any connected server offers (the one
                    // picked first on a tie), downloaded from that server (AppUpdate.server); else the one on screen's.
                    val offer = if (allMode) {
                        com.prudnikweb.hypernova.data.AboutUpdate.newest(
                            every.filter { it.first.state == LinkState.Connected }.sortedBy { it.second !== live }.map { it.second.appUpdate },
                        )
                    } else live?.appUpdate
                    AboutSection(offer, testing = testing, onTestingUnlocked = { testing = true })
                }
            }
        }
    }
}

/** The row that opens one of the phone's sub pages: its name and, in a few words, what's inside. */
@Composable
private fun PhonePageRow(page: SettingsPlan.PhonePage, onOpen: (Route) -> Unit) {
    val (title, body, route) = when (page) {
        SettingsPlan.PhonePage.Notifications -> Triple(R.string.notifications, R.string.st_page_notifications_desc, Route.NotificationSettings)
        SettingsPlan.PhonePage.TopBar -> Triple(R.string.st_top_bar, R.string.st_page_top_bar_desc, Route.TopBarSettings)
        SettingsPlan.PhonePage.CardSwipes -> Triple(R.string.st_card_swipes, R.string.st_page_card_swipes_desc, Route.CardSwipeSettings)
        SettingsPlan.PhonePage.TypingAndVoice -> Triple(R.string.st_typing_voice, R.string.st_page_typing_voice_desc, Route.TypingVoiceSettings)
        SettingsPlan.PhonePage.Logs -> Triple(R.string.st_logs, R.string.st_page_logs_desc, Route.LogSettings)
    }
    NavRow(stringResource(title), stringResource(body), { onOpen(route) })
}

/** Settings' sub pages, as App.kt shows them: a server's own, and each of the phone's. */
@Composable
internal fun SettingsSubPage(r: Route, vm: HubViewModel, onBack: () -> Unit) {
    when (r) {
        is Route.ServerSettings -> ServerSettingsScreen(vm, r.server, onBack)
        Route.NotificationSettings -> PhonePage(R.string.notifications, r.key, onBack) { NotificationsSection(vm) }
        Route.TopBarSettings -> PhonePage(R.string.st_top_bar, r.key, onBack) { TopBarSection() }
        Route.CardSwipeSettings -> PhonePage(R.string.st_card_swipes, r.key, onBack) { CardSwipeSection() }
        Route.TypingVoiceSettings -> PhonePage(R.string.st_typing_voice, r.key, onBack) {
            TypingSection()
            VoiceRecognitionSection()
        }
        Route.LogSettings -> PhonePage(R.string.st_logs, r.key, onBack) {
            // The connection log is the phone's, every server's lines in it.
            Seam(stringResource(R.string.connlog_title), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                NavRow(stringResource(R.string.connlog_title), stringResource(R.string.connlog_desc), { vm.nav.push(Route.ConnectionLog) }, Modifier.padding(horizontal = 16.dp))
            }
            Spacer(Modifier.height(20.dp))
            // Debug logging (DebugLogSection.kt): this phone's, whichever server is on screen.
            DebugLogSection()
        }
        else -> Unit
    }
}

/** A sub page of what this phone keeps: its title, Back, and its sections in a list of their own. */
@Composable
private fun PhonePage(title: Int, sourceKey: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val bottom = screenBottom()
    ScreenScaffold(sourceKey = sourceKey, title = stringResource(title), onBack = onBack) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            // A seam's small words are the first thing on some pages: clear of the bar's blur.
            contentPadding = PaddingValues(top = top + 10.dp, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
        ) {
            item(key = "page") { content() }
        }
    }
}

/**
 * A server's own page (owner, 2026-11-02: "a subpage for each server"): everything of that one
 * server, bound to its hub alone, the same page with one server and with All servers. Its name and
 * link are the title. A jump (SettingsJump) lands here and scrolls to the part asked for.
 */
@Composable
private fun ServerSettingsScreen(vm: HubViewModel, server: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val res = context.resources
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val bottom = screenBottom()
    val allMode = vm.allServers
    val entry = vm.everyHub.firstOrNull { it.first.id == server }
    if (entry == null) {
        // Forgotten, here or by the server: there's nothing left of it to show.
        LaunchedEffect(Unit) { if (vm.nav.top == Route.ServerSettings(server)) onBack() }
        return
    }
    val (status, h) = entry
    val testing = remember { TestingTools.unlocked(context) }
    val parts = SettingsPlan.serverParts(serverFacts(h, status.state), allMode, BuildConfig.DEBUG || testing)
    val list = androidx.compose.foundation.lazy.rememberLazyListState()

    // The part another screen asked for: scrolled to, once. Not there (the server isn't connected): the page's top.
    val jumpAsked = SettingsJump.asked
    LaunchedEffect(jumpAsked) {
        val key = SettingsJump.wanted() ?: return@LaunchedEffect
        try {
            if (parts.any { it.key == key }) scrollToKey(list, key)
        } finally {
            if (SettingsJump.asked == jumpAsked) SettingsJump.shown()
        }
    }

    ScreenScaffold(
        sourceKey = "server-settings",
        title = status.name,
        onBack = onBack,
        actions = {
            Spacer(Modifier.width(10.dp))
            ConnectionChip(status.state)
        },
    ) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            state = list,
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (part in parts) item(key = part.key) {
                when (part) {
                    is SettingsPlan.Server -> ServerCard(
                        h, part.waiting,
                        // Forget asks first, naming the server; it forgets this one, not the one picked.
                        onForget = {
                            confirm(
                                Confirm(
                                    title = res.getString(R.string.forget_q, status.name),
                                    body = res.getString(R.string.forget_body),
                                    action = res.getString(R.string.forget),
                                    danger = true,
                                ) {
                                    vm.forget(part.server) { reached -> if (!reached) toast(res.getString(R.string.forgot_offline)) }
                                },
                            )
                        },
                    )
                    is SettingsPlan.Providers -> ProvidersCard(h) {
                        // With All servers its screens are that server's (as a session's from Home), until Home is back.
                        if (allMode) vm.visit(part.server)
                        vm.nav.push(Route.Providers)
                    }
                    // Its Read aloud (ReadAloudSection.kt): its voices and speed, from a server that reads aloud this way.
                    is SettingsPlan.ReadAloud -> ReadAloudSection(h)
                    // Files: Browse outside projects (FilesSection below), from a server that has it.
                    is SettingsPlan.Files -> {
                        Spacer(Modifier.height(10.dp))
                        FilesSection(h)
                    }
                    // The owner's own test list: in debug builds, or after seven taps on the app's build (P19).
                    is SettingsPlan.Checklist -> ChecklistCard(h)
                }
            }
        }
    }
}

private fun serverFacts(h: LiveHub, link: LinkState) = SettingsPlan.ServerFacts(
    id = h.serverId,
    link = link,
    readAloud = h.speech.settings != null,
    files = h.mac.browseOutsideProjects != null,
)

/** The headings over the tab's two groups, its servers and what this phone keeps: one step above the sections' seams. */
@Composable
private fun GroupHeading(text: String, first: Boolean) {
    HText(
        text, style = Type.title, color = HN.ink, maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = if (first) 0.dp else 18.dp, bottom = 8.dp).semantics { heading() },
    )
}

/**
 * A server's card, the first thing on its page: Stay awake and its battery, the route and address,
 * its daemon, Claude Code and Codex versions, its update and debug switches, its notifications, Retry
 * while it can't be reached, Forget. [waiting]: it isn't connected, so its other settings are left
 * out and one line says they come back (Retry and Forget stay).
 */
@Composable
private fun ServerCard(live: LiveHub, waiting: Boolean, onForget: () -> Unit) {
    val res = LocalContext.current.resources
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val link = live.link
            // Stay awake in one line ("Awake with the lid closed" and the battery drawn), while
            // linked; a Linux server has no Stay awake (Mac.FEATURE_AWAKE).
            val awake = live.mac.awake?.takeIf { link.state == LinkState.Connected && live.mac.has(com.prudnikweb.hypernova.data.Mac.FEATURE_AWAKE) }
            if (awake != null) {
                AwakeStatus(awake, live.mac.battery)
                Spacer(Modifier.height(10.dp))
            }
            // The route in use, drawn: Local or Remote (and its address below).
            if (link.state == LinkState.Connected && routeWord(live.mac.via) != null) {
                com.prudnikweb.hypernova.ui.onboarding.RouteRow(stringResource(R.string.mac_route), live.mac.via)
            }
            InfoRow(stringResource(R.string.mac_address), live.mac.url ?: live.pairing.baseUrl)
            if (waiting) {
                Spacer(Modifier.height(6.dp))
                HText(stringResource(R.string.st_all_waiting), style = Type.small, color = HN.inkMuted)
            } else ServerVersions(live)
            // A MacBook's charge; a desktop Mac has no battery and no row. Only while
            // linked: one that can't be reached may have been plugged in or run down since.
            val battery = live.mac.battery?.takeIf { link.state == LinkState.Connected }
            if (battery != null) BatteryRow(battery)
            if (awake != null) StayAwakeBlock(live, awake, live.mac.battery)
            // The server updating itself (AutoUpdateRow.kt), from a daemon that can say; not from a last snapshot.
            if (!waiting) live.mac.update?.let { AutoUpdateRow(live, it) }
            // Carrying on after signing in again (LoginContinueRow.kt), or that it needs updating for that.
            if (!waiting && link.state == LinkState.Connected) LoginContinueRow(live)
            // The server's debug log (ServerDebugRow.kt): the switch and when it ends.
            if (!waiting) live.mac.debug?.let { ServerDebugRow(live, it) }
            // Its notifications, kept on the phone: here while it can't be reached too.
            ServerNotifyRow(live.serverId, live.mac.name.ifBlank { live.pairing.macName })
            if (link.state == LinkState.Offline || link.state == LinkState.Reconnecting) {
                Spacer(Modifier.height(6.dp))
                HText(com.prudnikweb.hypernova.data.linkProblemText(res.strings, link), style = Type.small, color = HN.inkMuted)
                link.tried?.let {
                    Spacer(Modifier.height(10.dp))
                    RouteTriedRow(it, showBase = live.mac.via == com.prudnikweb.hypernova.data.Mac.VIA_REMOTE)
                }
                Spacer(Modifier.height(12.dp))
                Pill(stringResource(R.string.retry_now), live::retry, kind = PillKind.Outline, height = 44.dp)
            }
            Spacer(Modifier.height(14.dp))
            Pill(stringResource(R.string.forget_mac), onForget, kind = PillKind.Danger, height = 44.dp)
        }
    }
}

/** Agents & providers: the row that opens [hub]'s list (Providers.kt). */
@Composable
private fun ProvidersCard(hub: com.prudnikweb.hypernova.data.Hub, onOpen: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Seam(stringResource(R.string.providers), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        NavRow(
            stringResource(R.string.providers),
            // Codex on the Mac too: the row names both agents; otherwise it reads as it always has.
            if (hub.codex != null) androidx.compose.ui.res.pluralStringResource(R.plurals.ag_settings_desc, hub.providers.size, hub.providers.size)
            else stringResource(R.string.providers_desc, hub.providers.size),
            onOpen, Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** The testing checklist of [hub]'s server, opened in the app (its own page on that server). */
@Composable
private fun ChecklistCard(hub: com.prudnikweb.hypernova.data.Hub) {
    val opener = LocalOpener.current
    val toast = LocalToast.current
    val res = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    var checklistBusy by remember { mutableStateOf(false) }
    Spacer(Modifier.height(10.dp))
    Seam(stringResource(R.string.testing_checklist), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        NavRow(
            stringResource(R.string.testing_checklist), stringResource(R.string.checklist_desc),
            {
                if (!checklistBusy) {
                    checklistBusy = true
                    scope.launch {
                        hub.checklist()
                            .onSuccess { opener.web(it, res.getString(R.string.testing_checklist)) }
                            .onFailure { toast(it.message.orEmpty()) }
                        checklistBusy = false
                    }
                }
            },
            Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** Notifications: which ones (the defaults), Stay connected in the background (every server's link), the system's permission, the battery exemption. */
@Composable
private fun NotificationsSection(vm: HubViewModel) {
    val askNotifications = com.prudnikweb.hypernova.ui.LocalAskNotifications.current
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            // Which notifications, on this phone (NotificationControls.kt).
            NotificationDefaults()
            ToggleRow(stringResource(R.string.bg_title), stringResource(R.string.bg_desc), vm.background, { vm.stayConnected(it) })
            // Re-checked every few seconds: the answer can change in system settings.
            val canPost by produceState(com.prudnikweb.hypernova.data.Core.notifier.canPost()) {
                while (true) { value = com.prudnikweb.hypernova.data.Core.notifier.canPost(); kotlinx.coroutines.delay(2000) }
            }
            if (!canPost) {
                Hairline(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                HText(stringResource(R.string.notif_off), style = Type.small, color = HN.amber)
                Spacer(Modifier.height(10.dp))
                Pill(stringResource(R.string.notif_allow), askNotifications, kind = PillKind.Outline, height = 44.dp)
                Spacer(Modifier.height(14.dp))
            }
            // Android may stop the background connection to save battery: one line and the way to
            // allow it (P8), while it's on and the app isn't exempt.
            if (vm.background) BatteryExemption()
        }
    }
}

/**
 * About: the app's build (seven taps unlock the testing checklist), a newer app when [offer] is one.
 * Each server's daemon, Claude Code and Codex versions are in its own card.
 */
internal const val GITHUB_REPO = "https://github.com/ljmerza/Hypernova"

@Composable
private fun AboutSection(offer: com.prudnikweb.hypernova.data.AppUpdate?, testing: Boolean, onTestingUnlocked: () -> Unit) {
    val context = LocalContext.current
    val res = context.resources
    val toast = LocalToast.current
    var taps by remember { mutableStateOf(0) }
    Spacer(Modifier.height(10.dp))
    Seam(stringResource(R.string.about), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            InfoRow(
                stringResource(R.string.about_app), stringResource(R.string.about_app_build, AboutBuild.name, AboutBuild.code),
                Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                    if (testing || BuildConfig.DEBUG) return@clickable
                    taps++
                    if (taps >= 7) {
                        onTestingUnlocked()
                        TestingTools.unlock(context)
                        toast(res.getString(R.string.st_checklist_unlocked))
                    }
                },
            )
            // A newer app on the Mac: "Update available · 0.6.0" and Update, the banner's flow
            // (ui/update AboutUpdateRow); nothing when there's none.
            com.prudnikweb.hypernova.data.AboutUpdate.of(
                offer,
                com.prudnikweb.hypernova.data.AppUpdater.state,
                com.prudnikweb.hypernova.data.AppUpdater.target,
            )?.let { com.prudnikweb.hypernova.ui.update.AboutUpdateRow(it) }
            // The project's page (owner, 2026-11-01), straight to the browser: its own row, nothing to confirm.
            NavRow(
                stringResource(R.string.about_github), stringResource(R.string.about_github_repo),
                {
                    val page = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_REPO))
                    runCatching { context.startActivity(page) }.onFailure { toast(res.getString(R.string.about_github_no_browser)) }
                },
            )
        }
    }
}

/** A server's daemon, Claude Code (or that it isn't installed) and Codex (only where it's installed). */
@Composable
private fun ServerVersions(live: com.prudnikweb.hypernova.data.Hub) {
    val unknown = stringResource(R.string.unknown)
    InfoRow(stringResource(R.string.about_daemon), live.mac.daemonVersion ?: unknown)
    // Claude Code's version, or that it isn't installed (Codex leads then: data/AgentLead.kt).
    val claude = if (!com.prudnikweb.hypernova.data.AgentLead.claudeAvailable(live.claude)) stringResource(R.string.ag_about_not_installed)
    else live.claude?.version ?: live.mac.claudeVersion ?: unknown
    InfoRow(stringResource(R.string.about_claude), claude)
    // Codex's, only where it's installed.
    live.codex?.let { c -> InfoRow(stringResource(R.string.ag_about_codex), c.version ?: unknown) }
}

/**
 * The app's build as About shows it: this build's. A screen test pins it, since every build has a
 * versionCode of its own (the minute it was built) and a picture of Settings would differ each time.
 */
internal object AboutBuild {
    var name: String = BuildConfig.VERSION_NAME
    var code: Int = BuildConfig.VERSION_CODE
}

/** The testing checklist's switch on the phone: off for everyone but debug builds, until seven taps on the build. */
object TestingTools {
    private const val KEY = "testingTools"
    fun unlocked(context: android.content.Context): Boolean = context.getSharedPreferences("hypernova", android.content.Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun unlock(context: android.content.Context) = context.getSharedPreferences("hypernova", android.content.Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

/**
 * "Android may stop the connection to save battery" and **Allow in background**, while the app isn't
 * exempt from battery optimisation (the system list of apps it lets run; checked again as the
 * owner comes back from it).
 */
@Composable
private fun BatteryExemption() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(android.os.PowerManager::class.java) }
    val exempt by produceState(power?.isIgnoringBatteryOptimizations(context.packageName) != false) {
        while (true) { value = power?.isIgnoringBatteryOptimizations(context.packageName) != false; kotlinx.coroutines.delay(2000) }
    }
    if (exempt) return
    Hairline(Modifier.fillMaxWidth())
    Spacer(Modifier.height(12.dp))
    HText(stringResource(R.string.st_battery_line), style = Type.small, color = HN.inkMuted)
    Spacer(Modifier.height(10.dp))
    Pill(stringResource(R.string.st_battery_allow), {
        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }, kind = PillKind.Outline, height = 44.dp)
    Spacer(Modifier.height(14.dp))
}

/**
 * Settings → Files: **Browse outside projects** (docs/PROTOCOL.md, "Files"), off by default. On, the
 * Mac opens sessions' folders in the home folder to the phone, read only; key folders and files with
 * keys stay locked whatever it says. One calm line, "Why it matters" behind a quiet toggle. Turning it
 * on asks first, saying what it opens; turning it off asks nothing.
 */
@Composable
private fun DisplaySection() {
    val context = LocalContext.current
    val scale by remember { com.prudnikweb.hypernova.ui.theme.TextSize.state(context) }
    Seam(stringResource(R.string.st_display), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            HText(stringResource(R.string.st_text_size), style = Type.body, color = HN.ink)
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.st_text_size_desc), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(12.dp))
            com.prudnikweb.hypernova.ui.components.Segmented(
                listOf(
                    com.prudnikweb.hypernova.ui.theme.TextSize.SMALL to stringResource(R.string.st_text_small),
                    com.prudnikweb.hypernova.ui.theme.TextSize.DEFAULT to stringResource(R.string.st_text_default),
                    com.prudnikweb.hypernova.ui.theme.TextSize.LARGE to stringResource(R.string.st_text_large),
                ),
                scale,
                { com.prudnikweb.hypernova.ui.theme.TextSize.set(context, it) },
            )
        }
    }
}

/**
 * Settings → Typing → **Enter key** (data/EnterKey.kt): Send, the default, or New line. It reaches
 * every box that sends at once (EnterKeyPref is one state for the app).
 */
@Composable
internal fun TypingSection() {
    val context = LocalContext.current
    val enter by remember { com.prudnikweb.hypernova.ui.components.EnterKeyPref.state(context) }
    Seam(stringResource(R.string.st_typing), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            HText(stringResource(R.string.st_enter_key), style = Type.body, color = HN.ink)
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.st_enter_key_desc), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(12.dp))
            com.prudnikweb.hypernova.ui.components.Segmented(
                listOf(
                    com.prudnikweb.hypernova.data.EnterKey.Send to stringResource(R.string.st_enter_send),
                    com.prudnikweb.hypernova.data.EnterKey.NewLine to stringResource(R.string.st_enter_newline),
                ),
                enter,
                { com.prudnikweb.hypernova.ui.components.EnterKeyPref.set(context, it) },
            )
        }
    }
}

@Composable
private fun FilesSection(hub: com.prudnikweb.hypernova.data.Hub) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val on = hub.mac.browseOutsideProjects == true
    val confirm = LocalConfirm.current
    var busy by remember { mutableStateOf(false) }
    var why by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    fun set(value: Boolean) {
        busy = true
        scope.launch {
            hub.setBrowseOutsideProjects(value).onFailure { toast(it.message.orEmpty()) }
            busy = false
        }
    }
    Seam(stringResource(R.string.st_files), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            ToggleRow(
                stringResource(R.string.st_browse_title), stringResource(R.string.st_browse_desc), on,
                { want ->
                    if (busy) return@ToggleRow
                    if (want) {
                        confirm(Confirm(context.getString(R.string.st_browse_check_title), context.getString(R.string.st_browse_check_sub), context.getString(R.string.st_browse_check_action), danger = true) { set(true) })
                    } else set(false)
                },
            )
            com.prudnikweb.hypernova.ui.components.MoreToggle(stringResource(R.string.why_it_matters), why) { why = !why }
            androidx.compose.animation.AnimatedVisibility(why) {
                HText(stringResource(R.string.st_browse_why), style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}
