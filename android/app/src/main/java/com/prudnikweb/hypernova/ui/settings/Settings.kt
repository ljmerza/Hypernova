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
 * The Settings tab: the Mac, providers, the testing checklist, versions. Which parts show, and for
 * which server, is SettingsPlan's: with "All servers", each server's own parts once per server under
 * its name, each bound to that server's hub, then what this phone keeps under "This phone".
 */
@Composable
fun SettingsTab(vm: HubViewModel, top: Dp) {
    val haze = LocalHaze.current
    val res = LocalContext.current.resources
    // All servers (owner, 2026-10-31): nothing here acts on the server picked last; each server's settings are its own block.
    val allMode = vm.allServers
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val live = vm.live
    val pairing = vm.pairing
    val hub = vm.hub
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    var testing by remember { mutableStateOf(TestingTools.unlocked(context)) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()

    // The servers shown: every paired one with All servers (in the order they were paired), else the one on screen.
    val every = if (allMode) vm.everyHub else emptyList()
    val hubs: Map<String, LiveHub> = if (allMode) every.associate { it.first.id to it.second } else live?.let { mapOf(it.serverId to it) }.orEmpty()
    val facts = if (allMode) every.map { (s, h) -> serverFacts(h, s.state) } else listOfNotNull(live?.let { serverFacts(it, it.link.state) })
    val parts = SettingsPlan.parts(allMode, facts, BuildConfig.DEBUG || testing)

    // Another part was asked for (SettingsJump: "Turn on in Settings"): scrolled to, once. With All
    // servers, the part of the server it was asked on.
    val jumpAsked = SettingsJump.asked
    LaunchedEffect(jumpAsked, hub != null) {
        val key = SettingsJump.wanted() ?: return@LaunchedEffect
        if (hub == null) return@LaunchedEffect
        try {
            scrollToKey(list, if (allMode) SettingsPlan.keyOf(key, SettingsJump.server ?: live?.serverId.orEmpty(), true) else key)
        } finally {
            if (SettingsJump.asked == jumpAsked) SettingsJump.shown()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().hazeSource(haze, zIndex = Z.CONTENT, key = HOME_SOURCE),
        state = list,
        contentPadding = PaddingValues(top = top + 84.dp, bottom = bottom + 120.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Owner, 2026-10-31: the first server's name read as the page's title, "z2", with All chosen.
        if (allMode) item(key = "allIntro") {
            HText(stringResource(R.string.st_all_intro), style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 4.dp))
        }
        for ((index, part) in parts.withIndex()) item(key = part.key) {
            when (part) {
                is SettingsPlan.Server -> {
                    // Forget asks first, naming the server; with All servers it forgets that one, not the one picked.
                    fun askForget(name: String, server: String?) = confirm(
                        Confirm(
                            title = res.getString(R.string.forget_q, name),
                            body = res.getString(R.string.forget_body),
                            action = res.getString(R.string.forget),
                            danger = true,
                        ) {
                            vm.forget(server) { reached -> if (!reached) toast(res.getString(R.string.forgot_offline)) }
                        },
                    )
                    if (allMode) {
                        // A server's block starts with its name and link.
                        val h = hubs[part.server] ?: return@item
                        val status = every.firstOrNull { it.first.id == part.server }?.first
                        val name = status?.name ?: h.mac.name
                        GroupHeading(name, status?.state ?: h.link.state, first = index == 0)
                        ServerCard(h, h.pairing.baseUrl, all = true, waiting = part.waiting, onConnectionLog = {}, onForget = { askForget(name, part.server) })
                    } else {
                        Seam(stringResource(R.string.mac), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
                        ServerCard(
                            live?.takeIf { pairing != null }, pairing?.baseUrl, all = false, waiting = false,
                            onConnectionLog = { vm.nav.push(Route.ConnectionLog) },
                            onForget = { askForget(live?.mac?.name.orEmpty(), null) },
                        )
                    }
                }
                is SettingsPlan.Providers -> hubs[part.server]?.let { h ->
                    // With All servers its screens are that server's (as a session's from Home), until Home is back.
                    ProvidersCard(h) {
                        if (allMode) vm.visit(part.server)
                        vm.nav.push(Route.Providers)
                    }
                }
                // Voice (VoiceSection.kt): the recognizer dictation uses, kept on this phone. Then, with one
                // server, Read aloud (ReadAloudSection.kt): only from a Mac that reads aloud this way.
                is SettingsPlan.Voice -> {
                    VoiceRecognitionSection()
                    part.readAloud?.let(hubs::get)?.let { ReadAloudSection(it) }
                }
                // With All servers, each server's own Read aloud: its voices and speed.
                is SettingsPlan.ReadAloud -> hubs[part.server]?.let { ReadAloudSection(it) }
                // Files: Browse outside projects (FilesSection below), from a Mac that has it.
                is SettingsPlan.Files -> hubs[part.server]?.let { h ->
                    Spacer(Modifier.height(10.dp))
                    FilesSection(h)
                }
                // The owner's own test list: in debug builds, or after seven taps on the app's build (P19).
                is SettingsPlan.Checklist -> hubs[part.server]?.let { ChecklistCard(it) }
                SettingsPlan.PhoneHeading -> GroupHeading(stringResource(R.string.st_all_phone), null, first = index == 0)
                // The connection log is the phone's, every server's lines in it: once, with All servers.
                SettingsPlan.ConnectionLog -> {
                    Spacer(Modifier.height(10.dp))
                    Seam(stringResource(R.string.connlog_title), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
                    GlassCard(Modifier.fillMaxWidth()) {
                        NavRow(stringResource(R.string.connlog_title), stringResource(R.string.connlog_desc), { vm.nav.push(Route.ConnectionLog) }, Modifier.padding(horizontal = 16.dp))
                    }
                }
                // Fork: New sessions, the mode they start in (NewSessionsSection below), kept on this phone.
                SettingsPlan.NewSessions -> {
                    Spacer(Modifier.height(10.dp))
                    NewSessionsSection()
                }
                // Fork: Display, the app's text size (DisplaySection below), kept on this phone.
                SettingsPlan.Display -> {
                    Spacer(Modifier.height(10.dp))
                    DisplaySection()
                }
                // Card swipes: what a swipe left and right on a Home card do (CardSwipeSection.kt), kept on this phone.
                SettingsPlan.CardSwipes -> {
                    Spacer(Modifier.height(10.dp))
                    CardSwipeSection()
                }
                // Session top bar: what the chat's top bar shows, in what order (TopBarSection.kt), kept on this phone.
                SettingsPlan.TopBar -> {
                    Spacer(Modifier.height(10.dp))
                    TopBarSection()
                }
                // Typing: what the keyboard's Enter does in the boxes that send (TypingSection below), kept on this phone.
                SettingsPlan.Typing -> {
                    Spacer(Modifier.height(10.dp))
                    TypingSection()
                }
                // Stay connected in the background: one setting for every server's link (Core.background).
                SettingsPlan.Notifications -> NotificationsSection(vm)
                SettingsPlan.About -> {
                    // A newer app: with All servers, the newest any connected server offers (the one
                    // picked first on a tie), downloaded from that server (AppUpdate.server); else the one on screen's.
                    val offer = if (allMode) {
                        com.prudnikweb.hypernova.data.AboutUpdate.newest(
                            every.filter { it.first.state == LinkState.Connected }.sortedBy { it.second !== live }.map { it.second.appUpdate },
                        )
                    } else live?.appUpdate
                    AboutSection(
                        offer,
                        // With All servers each server's versions are in its own card.
                        versionsOf = live.takeIf { !allMode },
                        testing = testing,
                        onTestingUnlocked = { testing = true },
                    )
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

/**
 * With All servers, the heading over a server's block (its name and link) or over what this phone
 * keeps ([state] null): one step above the sections' seams, so each block reads as one.
 */
@Composable
private fun GroupHeading(text: String, state: LinkState?, first: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = if (first) 0.dp else 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(text, style = Type.title, color = HN.ink, maxLines = 1, modifier = Modifier.weight(1f).semantics { heading() })
        if (state != null) {
            Spacer(Modifier.width(10.dp))
            ConnectionChip(state)
        }
    }
}

/**
 * A server's card: its name and link, Stay awake and its battery, the route and address, Retry while
 * it can't be reached, the connection log, Forget. [all]: in its block with All servers, under its
 * name ([GroupHeading]): no name row, its versions here (About is the phone's then), no connection
 * log (one for all, under This phone); [waiting]: it isn't connected, so its other settings are left
 * out and one line says they come back (Retry and Forget stay).
 */
@Composable
private fun ServerCard(
    live: LiveHub?,
    baseUrl: String?,
    all: Boolean,
    waiting: Boolean,
    onConnectionLog: () -> Unit,
    onForget: (() -> Unit)?,
) {
    val res = LocalContext.current.resources
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (baseUrl == null || live == null) {
                HText(stringResource(R.string.not_paired), style = Type.heading, color = HN.ink)
            } else {
                val link = live.link
                // Stay awake in one line under the name ("Awake with the lid closed" and the battery drawn), while
                // linked; a Linux server has no Stay awake (Mac.FEATURE_AWAKE).
                val awake = live.mac.awake?.takeIf { link.state == LinkState.Connected && live.mac.has(com.prudnikweb.hypernova.data.Mac.FEATURE_AWAKE) }
                if (!all) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HText(live.mac.name, style = Type.heading, color = HN.ink, maxLines = 1, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        // The Mac's name is the heading, so the chip says the state in a word.
                        ConnectionChip(link.state)
                    }
                    if (awake != null) {
                        Spacer(Modifier.height(4.dp))
                        AwakeStatus(awake, live.mac.battery)
                    }
                    Spacer(Modifier.height(10.dp))
                } else if (awake != null) {
                    AwakeStatus(awake, live.mac.battery)
                    Spacer(Modifier.height(10.dp))
                }
                // The route in use, drawn: Local or Remote (and its address below).
                if (link.state == LinkState.Connected && routeWord(live.mac.via) != null) {
                    com.prudnikweb.hypernova.ui.onboarding.RouteRow(stringResource(R.string.mac_route), live.mac.via)
                }
                InfoRow(stringResource(R.string.mac_address), live.mac.url ?: baseUrl)
                if (waiting) {
                    Spacer(Modifier.height(6.dp))
                    HText(stringResource(R.string.st_all_waiting), style = Type.small, color = HN.inkMuted)
                }
                if (all && link.state == LinkState.Connected) ServerVersions(live)
                // A MacBook's charge; a desktop Mac has no battery and no row. Only while
                // linked: one that can't be reached may have been plugged in or run down since.
                val battery = live.mac.battery?.takeIf { link.state == LinkState.Connected }
                if (battery != null) BatteryRow(battery)
                if (awake != null) StayAwakeBlock(live, awake, live.mac.battery)
                // The server updating itself (AutoUpdateRow.kt), from a daemon that can say; not from a last snapshot.
                if (!waiting) live.mac.update?.let { AutoUpdateRow(live, it) }
                if (link.state == LinkState.Offline || link.state == LinkState.Reconnecting) {
                    Spacer(Modifier.height(6.dp))
                    HText(com.prudnikweb.hypernova.data.linkProblemText(res, link), style = Type.small, color = HN.inkMuted)
                    link.tried?.let {
                        Spacer(Modifier.height(10.dp))
                        RouteTriedRow(it, showBase = live.mac.via == com.prudnikweb.hypernova.data.Mac.VIA_REMOTE)
                    }
                    Spacer(Modifier.height(12.dp))
                    Pill(stringResource(R.string.retry_now), live::retry, kind = PillKind.Outline, height = 44.dp)
                }
                if (!all) {
                    // What the app tried to reach the Mac, step by step (data/ConnLog.kt), in every state.
                    Spacer(Modifier.height(8.dp))
                    Hairline(Modifier.fillMaxWidth())
                    NavRow(stringResource(R.string.connlog_title), stringResource(R.string.connlog_desc), onConnectionLog)
                }
                if (onForget != null) {
                    Spacer(Modifier.height(14.dp))
                    Pill(stringResource(R.string.forget_mac), onForget, kind = PillKind.Danger, height = 44.dp)
                }
            }
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

/** Notifications: Stay connected in the background (every server's link), the system's permission, the battery exemption. */
@Composable
private fun NotificationsSection(vm: HubViewModel) {
    val askNotifications = com.prudnikweb.hypernova.ui.LocalAskNotifications.current
    Spacer(Modifier.height(10.dp))
    Seam(stringResource(R.string.notifications), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
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
 * About: the app's build (seven taps unlock the testing checklist), a newer app when [offer] is one,
 * and the versions of [versionsOf]'s daemon, Claude Code and Codex (null with All servers: they're
 * in each server's card).
 */
@Composable
private fun AboutSection(offer: com.prudnikweb.hypernova.data.AppUpdate?, versionsOf: com.prudnikweb.hypernova.data.Hub?, testing: Boolean, onTestingUnlocked: () -> Unit) {
    val context = LocalContext.current
    val res = context.resources
    val toast = LocalToast.current
    var taps by remember { mutableStateOf(0) }
    Spacer(Modifier.height(10.dp))
    Seam(stringResource(R.string.about), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            InfoRow(
                stringResource(R.string.about_app), stringResource(R.string.about_app_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
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
            if (versionsOf != null) ServerVersions(versionsOf)
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
 * on asks for the fingerprint or the screen lock first (OwnerCheck); turning it off asks nothing.
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

/**
 * Fork: Settings → New sessions: the mode the New session sheet opens on every time (Ask, Edits,
 * Plan or Auto; data/NewSessionMemory.kt). Auto goes through the think-twice card as it's chosen
 * here, as everywhere; Bypass isn't offered (it waits for the fingerprint, session by session).
 */
@Composable
private fun NewSessionsSection() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(com.prudnikweb.hypernova.data.NewSessionMemory.startMode(context)) }
    Seam(stringResource(R.string.st_new_sessions), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            HText(stringResource(R.string.st_start_mode), style = Type.body, color = HN.ink)
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.st_start_mode_desc), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(12.dp))
            com.prudnikweb.hypernova.ui.components.ModePicker(
                mode,
                { m ->
                    com.prudnikweb.hypernova.data.NewSessionMemory.setStartMode(context, m)
                    mode = com.prudnikweb.hypernova.data.NewSessionMemory.startMode(m)
                },
                bypass = false,
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
                        com.prudnikweb.hypernova.ui.components.OwnerCheck.ask(
                            context, context.getString(R.string.st_browse_check_title), context.getString(R.string.st_browse_check_sub),
                            context.getString(R.string.st_browse_no_lock),
                            onOk = { set(true) }, onFail = { msg -> if (msg != null) toast(msg) },
                        )
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
