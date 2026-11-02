package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Blocked
import com.prudnikweb.hypernova.data.ConnResult
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Link
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Mac
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.RouteKind
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.linkProblemText
import com.prudnikweb.hypernova.desktop.APP_NAME
import com.prudnikweb.hypernova.desktop.CardOffer
import com.prudnikweb.hypernova.desktop.Group
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.desktop.answerCard
import com.prudnikweb.hypernova.desktop.doingLine
import com.prudnikweb.hypernova.desktop.groupsOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How wide the list gets, however wide the window: past this a line of a command is too long to read. */
private val COLUMN = 720.dp
/** Off-link cards stay, as last heard, and step back. */
private const val STALE_ALPHA = 0.55f

/** The sessions on one server, with its link's state at the top. */
@Composable
fun SessionsScreen(hub: Hub, statuses: List<ServerStatus>, shell: Shell) {
    Column(Modifier.fillMaxSize()) {
        TopBar(hub, statuses, shell)
        val link = hub.link
        val groups = groupsOf(hub.sessions)
        // Over the list, not in it: scrolled down, a card that came in above would never be seen.
        if (link.state == LinkState.Unpaired || link.state == LinkState.Offline) {
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), contentAlignment = Alignment.TopCenter) {
                val m = Modifier.widthIn(max = COLUMN).fillMaxWidth()
                if (link.state == LinkState.Unpaired) UnpairedCard(hub, shell, m) else OfflineCard(hub, hasSessions = groups.isNotEmpty(), m)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val state = rememberLazyListState()
            val stale = link.state != LinkState.Connected && link.state != LinkState.Connecting
            LazyColumn(
                Modifier.fillMaxSize(),
                state = state,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (groups.isEmpty() && link.state == LinkState.Connected) {
                    item(key = "none") { NoSessions(Modifier.widthIn(max = COLUMN).fillMaxWidth()) }
                }
                for ((group, members) in groups) {
                    item(key = group.name) { GroupSeam(group, members.size, Modifier.widthIn(max = COLUMN).fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) }
                    items(members, key = { it.id }) { s ->
                        SessionCard(
                            hub, s, shell,
                            Modifier.widthIn(max = COLUMN).fillMaxWidth().graphicsLayer { alpha = if (stale) STALE_ALPHA else 1f },
                        )
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(state),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
                style = LocalScrollbarStyle.current.copy(unhoverColor = HN.ink.copy(alpha = 0.12f), hoverColor = HN.ink.copy(alpha = 0.3f)),
            )
        }
    }
}

/** The logo, the name, then where the link stands in a chip that's always there. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBar(hub: Hub, statuses: List<ServerStatus>, shell: Shell) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            NovaMark()
            HText(APP_NAME, style = Type.wordmark, maxLines = 1)
            ConnectionChip(hub)
            Spacer(Modifier.weight(1f))
            Pill(Ui.desk_pair_another.text(), { shell.adding = true }, kind = PillKind.Ghost, height = 32.dp)
        }
        if (statuses.size > 1) {
            Spacer(Modifier.height(8.dp))
            Switcher(statuses)
        }
    }
}

/** One pill per paired server: a tap puts it on screen. [Core] already watches each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Switcher(statuses: List<ServerStatus>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // In the order they were paired, so a pill doesn't jump when it's picked.
        for (s in statuses.sortedBy { it.name.lowercase() }) {
            val word = if (s.needsYou > 0) "${s.name}  ${s.needsYou}" else s.name
            Pill(
                word, { Core.switchTo(s.id) },
                kind = if (s.active) PillKind.Primary else if (s.needsYou > 0) PillKind.Outline else PillKind.Ghost,
                height = 32.dp,
            )
        }
    }
}

/** The word, the mark and the colour for a link. */
private data class LinkWords(val word: String, val mark: LinkMark, val color: Color)

private fun linkWords(link: Link): LinkWords = when (link.state) {
    LinkState.Connected -> LinkWords(Ui.desk_link_linked.text(), LinkMark.Linked, HN.accent)
    LinkState.Connecting -> LinkWords(Ui.desk_link_connecting.text(), LinkMark.Looking, HN.inkMuted)
    LinkState.Reconnecting -> LinkWords(Ui.desk_link_reconnecting.text(), LinkMark.Looking, HN.amber)
    LinkState.Offline -> LinkWords(Ui.desk_link_offline.text(), LinkMark.Offline, HN.danger)
    LinkState.Unpaired -> LinkWords(Ui.desk_link_unpaired.text(), LinkMark.Unpaired, HN.danger)
}

/** The server's name and its link: `◆ z2  LINKED  LOCAL`. */
@Composable
private fun ConnectionChip(hub: Hub) {
    val link = hub.link
    val words = linkWords(link)
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinkGlyph(words.mark, words.color)
        Spacer(Modifier.width(6.dp))
        HText(hub.mac.name, style = Type.small, color = HN.inkMuted, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        HText(words.word.uppercase(), style = Type.label, color = words.color, maxLines = 1)
        if (link.state == LinkState.Connected) {
            Spacer(Modifier.width(8.dp))
            val route = if (hub.mac.via == Mac.VIA_REMOTE) Ui.connlog_route_base else Ui.connlog_route_local
            HText(route.text().uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        }
    }
}

/** Offline, and why: the server's silence as the link says it, when it tries again, what it tried. */
@Composable
private fun OfflineCard(hub: Hub, hasSessions: Boolean, modifier: Modifier = Modifier) {
    val link = hub.link
    val now by produceState(System.currentTimeMillis(), link) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    GlassCard(modifier, edge = HN.danger) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            HText(Ui.cant_reach.format(hub.mac.name), style = Type.heading)
            Spacer(Modifier.height(4.dp))
            HText(linkProblemText(Core.platform.strings, link), style = Type.small, color = HN.inkMuted)
            link.tried?.let { tried ->
                val shown = tried.tries.groupBy { it.route }.mapNotNull { (_, list) -> list.lastOrNull() }.sortedBy { it.route.ordinal }
                if (shown.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        for (t in shown) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HText((if (t.route == RouteKind.Local) Ui.connlog_route_local else Ui.connlog_route_base).text().uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                                resultWord(t.result)?.let {
                                    Spacer(Modifier.width(6.dp))
                                    HText(it.uppercase(), style = Type.label, color = if (t.result == ConnResult.Skipped) HN.inkDim else HN.danger, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val retry = when {
                    link.retrying -> Ui.retrying_now.text()
                    link.retryAt != null && (link.retryAt ?: 0L) - now >= 1_000 -> Ui.retrying_in.format(duration((link.retryAt ?: 0L) - now))
                    link.retryAt != null -> Ui.retrying_now.text()
                    else -> null
                }
                HText(retry.orEmpty(), Modifier.weight(1f), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                Pill(Ui.retry_now.text(), hub::retry, kind = PillKind.Outline, height = 36.dp)
            }
            if (hasSessions) {
                Spacer(Modifier.height(8.dp))
                HText(Ui.desk_stale.text(), style = Type.small, color = HN.inkDim)
            }
        }
    }
}

/** How long, as the phone's Countdown words it. */
private fun duration(ms: Long): String {
    val s = (ms + 999) / 1000
    return when {
        s < 60 -> Ui.dur_s.format(0, s)
        s < 600 -> Ui.dur_s.format(s / 60, s % 60)
        s < 3600 -> Ui.dur_m.format(s / 60)
        s < 86400 -> Ui.dur_hm.format(s / 3600, (s % 3600) / 60)
        else -> Ui.dur_dh.format(s / 86400, (s % 86400) / 3600)
    }
}

private fun resultWord(r: ConnResult): String? = when (r) {
    ConnResult.Ok -> Ui.connlog_result_ok.text()
    ConnResult.Refused -> Ui.connlog_result_refused.text()
    ConnResult.Timeout -> Ui.connlog_result_timeout.text()
    ConnResult.Unreachable -> Ui.connlog_result_unreachable.text()
    ConnResult.NoName -> Ui.connlog_result_no_name.text()
    ConnResult.PinWrong -> Ui.connlog_result_pin_wrong.text()
    ConnResult.PinExpired -> Ui.connlog_result_pin_expired.text()
    ConnResult.Tls -> Ui.connlog_result_tls.text()
    ConnResult.Http -> Ui.connlog_result_http.text()
    ConnResult.Closed -> Ui.connlog_result_closed.text()
    ConnResult.Failed -> Ui.connlog_result_failed.text()
    ConnResult.Skipped -> Ui.connlog_result_skipped.text()
    ConnResult.Info -> null
}

@Composable
private fun UnpairedCard(hub: Hub, shell: Shell, modifier: Modifier = Modifier) {
    GlassCard(modifier, edge = HN.danger) {
        Column(Modifier.padding(16.dp)) {
            HText(Ui.desk_unpaired_title.format(hub.mac.name), style = Type.heading)
            Spacer(Modifier.height(4.dp))
            HText(Ui.desk_unpaired_body.text(), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(10.dp))
            Pill(Ui.desk_pair_again.text(), shell::pairAgain, kind = PillKind.Primary, height = 36.dp)
        }
    }
}

@Composable
private fun NoSessions(modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            HText(Ui.desk_no_sessions.text(), style = Type.heading)
            Spacer(Modifier.height(4.dp))
            HText(Ui.desk_no_sessions_body.text(), style = Type.small, color = HN.inkMuted)
        }
    }
}

@Composable
private fun GroupSeam(group: Group, count: Int, modifier: Modifier = Modifier) {
    val (label, color, mark) = when (group) {
        Group.NeedsInput -> Triple(Ui.group_needs.text(), HN.amber, MarkKind.NeedsYou)
        Group.Working -> Triple(Ui.group_working.text(), HN.accent, MarkKind.Working)
        Group.Completed -> Triple(Ui.group_done.text(), HN.inkDim, MarkKind.Idle)
    }
    Seam(label, mark, modifier.semantics { heading() }, color = if (group == Group.Completed) HN.inkMuted else color) {
        CountMark(count.toString(), color)
    }
}

/** A card: its project and short id, its name, what it's doing, its state, and what it asks. */
@Composable
private fun SessionCard(hub: Hub, s: Session, shell: Shell, modifier: Modifier = Modifier) {
    val offer = hub.approval(s.id).let { a -> remember(a, s) { CardOffer.of(a, s) } }
    val needs = s.state == SessionState.NeedsYou
    GlassCard(modifier, edge = if (needs) HN.amber else null) {
        Column(Modifier.padding(16.dp)) {
            Seam(
                s.project, if (needs) MarkKind.NeedsYou else if (s.busy) MarkKind.Working else MarkKind.Idle,
                color = if (needs) HN.amber else HN.inkMuted,
            ) { HText(s.hex, style = Type.monoSmall, color = HN.inkDim, maxLines = 1) }
            Spacer(Modifier.height(8.dp))
            HText(s.name, style = Type.heading, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            when (offer) {
                is CardOffer.Whole -> WholeCommand(offer)
                is CardOffer.NotWhole -> NotWholeCommand(offer)
                is CardOffer.OnPhone -> HText(
                    if (offer.plan) Ui.desk_answer_on_phone_plan.text() else Ui.desk_answer_on_phone_question.text(),
                    style = Type.small, color = HN.amber,
                )
                null -> when {
                    s.diedLine -> HText(Ui.card_job_died.text(), style = Type.monoSmall, color = HN.danger, maxLines = 1)
                    needs -> HText(s.activity, style = Type.monoSmall, color = HN.amber, maxLines = 2)
                    else -> HText(doingLine(s), style = Type.monoSmall, color = HN.inkMuted, maxLines = 2)
                }
            }
            hub.approval(s.id)?.takeIf { it.agentId != null }?.agentLabel?.let { agent ->
                Spacer(Modifier.height(4.dp))
                HText(Ui.desk_from_agent.format(agent), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StateChip(s, s.activeBlock(hub.account, hub.providers, System.currentTimeMillis())) }
                if (offer is CardOffer.Whole || offer is CardOffer.NotWhole) {
                    Spacer(Modifier.width(8.dp))
                    AskPills(hub, s.id, offer, shell)
                }
            }
        }
    }
}

private fun annotatedCommand(tool: String, text: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = HN.amber)) { append(if (tool.startsWith("Bash", ignoreCase = true)) "$ " else "› ") }
    append(text)
}

/** Allow promises this exact command: the tool, then all of it as it will run, line breaks and all, never cut. */
@Composable
private fun WholeCommand(offer: CardOffer.Whole) {
    HText(offer.tool.uppercase(), style = Type.label, color = HN.amber)
    Spacer(Modifier.height(4.dp))
    androidx.compose.foundation.text.BasicText(annotatedCommand(offer.tool, offer.text), style = Type.monoSmall.copy(color = HN.ink))
}

/** What can be said of a command that can't be read whole, masked, and why there's no Allow. */
@Composable
private fun NotWholeCommand(offer: CardOffer.NotWhole) {
    HText(offer.tool.uppercase(), style = Type.label, color = HN.amber)
    Spacer(Modifier.height(4.dp))
    HText(offer.detail, style = Type.monoSmall, color = HN.inkMuted, maxLines = 3)
    Spacer(Modifier.height(6.dp))
    HText(Ui.desk_not_whole.text(), style = Type.small, color = HN.amber)
}

/**
 * Deny, and Allow only beside a whole command ([CardOffer]). While an answer goes it says "Allowing…" or
 * "Denying…" in their place; one that didn't get through brings them back, and says so on the card and in the toast.
 */
@Composable
private fun AskPills(hub: Hub, sessionId: String, offer: CardOffer, shell: Shell) {
    var going by remember(offer.id) { mutableStateOf<Boolean?>(null) }
    var failed by remember(offer.id) { mutableStateOf(false) }
    fun send(allow: Boolean) {
        if (going != null) return
        going = allow
        failed = false
        Core.scope.launch {
            val ok = runCatching { answerCard(hub, sessionId, offer, allow, shell::say) }.getOrDefault(false)
            // Answered: the card goes with the hub's next word on it, and these stay until then.
            if (!ok) {
                going = null
                failed = true
            }
        }
    }
    val now = going
    if (now != null) {
        Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterEnd) {
            HText(
                if (now) Ui.widget_allowing.text() else Ui.widget_denying.text(),
                style = Type.small, color = if (now) HN.accent else HN.inkMuted, maxLines = 1,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        return
    }
    Column(horizontalAlignment = Alignment.End) {
        if (failed) {
            HText(
                Ui.desk_answer_failed.text(), style = Type.small, color = HN.danger, maxLines = 2,
                modifier = Modifier.widthIn(max = 280.dp).semantics { liveRegion = LiveRegionMode.Assertive },
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(Ui.deny.text(), { send(false) }, kind = PillKind.Danger)
            if (offer is CardOffer.Whole) Pill(Ui.allow.text(), { send(true) }, kind = PillKind.Primary)
        }
    }
}

/** The state chip, by the phone's rules (HomeScreen's PhaseChip). */
@Composable
private fun StateChip(s: Session, block: Blocked?) {
    if (block != null) {
        when (block.kind) {
            Blocked.LIMIT, Blocked.MODEL -> StatusChip(Ui.state_paused.text(), HN.inkMuted)
            Blocked.LOGGED_OUT -> StatusChip(Ui.state_signed_out.text(), HN.amber)
            Blocked.NO_SUBSCRIPTION -> StatusChip(Ui.state_no_plan.text(), HN.amber)
            Blocked.ON_HOLD -> StatusChip(Ui.state_on_hold.text(), HN.amber)
            Blocked.NO_CREDIT -> StatusChip(Ui.state_no_credit.text(), HN.amber)
            else -> StatusChip(Ui.state_paused.text(), if (block.resume?.armed == true) HN.inkMuted else HN.amber)
        }
        return
    }
    when (s.phase) {
        Phase.Starting -> StatusChip(Ui.state_starting.text(), HN.accent, live = true)
        Phase.Error -> StatusChip(Ui.state_error.text(), HN.danger)
        Phase.Ended -> if (s.cutByRestart) StatusChip(Ui.state_mac_restarted.text(), HN.inkMuted) else StatusChip(Ui.state_ended.text(), HN.inkDim, hollow = true)
        else -> when (s.state) {
            SessionState.Working -> StatusChip(Ui.state_working.text(), HN.accent, live = true)
            SessionState.NeedsYou -> StatusChip(Ui.state_needs_you.text(), HN.amber, filled = true)
            SessionState.Idle -> if (s.busy) StatusChip(Ui.state_working.text(), HN.accent, live = true) else StatusChip(Ui.state_idle.text(), HN.inkDim)
        }
    }
}

