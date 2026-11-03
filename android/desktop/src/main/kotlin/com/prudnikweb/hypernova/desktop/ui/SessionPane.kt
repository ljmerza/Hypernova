package com.prudnikweb.hypernova.desktop.ui

import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.inHistory
import com.prudnikweb.hypernova.desktop.CardOffer
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.desktop.answerCard
import com.prudnikweb.hypernova.desktop.doingLine
import com.prudnikweb.hypernova.desktop.factsOf
import com.prudnikweb.hypernova.desktop.inChatWords
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The tag of the pane beside the list, for the tests. */
const val PANE_TAG = "session-pane"
/** The pane's width at most: past this a line of a command is too long to read. */
internal val PANE_MAX = 800.dp

/**
 * The right pane: [session], or why there's none. It's never another session than the one chosen: if [chosen] goes,
 * it says so and waits, so an Allow can't move under the pointer onto something nobody picked.
 */
@Composable
internal fun SessionPane(hub: Hub, chosen: String?, session: Session?, shell: Shell, stale: Boolean, onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier.testTag(PANE_TAG)) {
        when {
            session != null -> key(session.id) { Details(hub, session, shell, stale, onBack) }
            chosen != null -> PaneWords(Ui.desk_pane_gone.text(), Ui.desk_pane_gone_body.text(), onBack)
            else -> PaneWords(Ui.desk_pane_none.text(), null, onBack)
        }
    }
}

/** Nothing to show: a line or two, plainly, in the middle of the pane. */
@Composable
private fun PaneWords(title: String, body: String?, onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (onBack != null) BackPill(onBack)
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            HText(title, style = Type.body.copy(textAlign = TextAlign.Center), color = HN.inkMuted)
            if (body != null) {
                Spacer(Modifier.height(4.dp))
                HText(body, style = Type.small.copy(textAlign = TextAlign.Center), color = HN.inkDim)
            }
        }
    }
}

@Composable
private fun BackPill(onBack: () -> Unit) {
    Pill(Ui.back.text(), onBack, kind = PillKind.Ghost, height = 32.dp)
}

/**
 * What it is and what it asks, always in view, then the chat, then the box. The facts and what can be done to it are at the start of the chat, where
 * they don't take room from it: a short conversation has them on screen, and a long one is a scroll up. docs/DECISIONS.md, "The chat on the desktop".
 * What has ended is the same: opening it only reads it, and only writing in it resumes it, which the box and the note say.
 */
@Composable
private fun Details(hub: Hub, s: Session, shell: Shell, stale: Boolean, onBack: (() -> Unit)?) {
    val following = remember { ChatFollowing() }
    val links = remember { DeskLinks(shell) }
    val box = remember { FocusRequester() }
    CompositionLocalProvider(LocalUriHandler provides links) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val askMax = maxHeight * ASK_SHARE
            // The box always has its room, in the smallest window too: the card gives way, what's asked scrolling above its pills.
            val rest = maxHeight - BOX_ROOM - if (onBack != null) BACK_ROOM else 0.dp
            Column(
                Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onBack != null) BackPill(onBack)
                val card = Modifier.widthIn(max = PANE_MAX).fillMaxWidth().graphicsLayer { alpha = if (stale) STALE_ALPHA else 1f }
                AskCard(hub, s, shell, askMax, card.heightIn(max = rest.coerceAtLeast(0.dp)))
                ChatList(hub, s, shell, following, box, Modifier.weight(1f).widthIn(max = PANE_MAX).fillMaxWidth()) {
                    Facts(s, Modifier.fillMaxWidth())
                    SessionActions(hub, s, shell, Modifier.fillMaxWidth())
                    val note = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    HText(if (s.inHistory) Ui.desk_pane_in_history.text() else Ui.desk_pane_phone_only.text(), note, style = Type.small, color = HN.inkDim)
                    s.automation?.takeIf { s.inHistory }?.let { HText(Ui.desk_pane_runs_phone.format(it.name), note, style = Type.small, color = HN.inkDim) }
                }
                ChatBox(hub, s, shell, following, box, Modifier.widthIn(max = PANE_MAX).fillMaxWidth())
            }
            LinkAsk(links, shell)
        }
    }
}

/** Its project and short id, its name, what it's doing, its state, and what it asks, in full, with Deny and Allow. */
@Composable
private fun AskCard(hub: Hub, s: Session, shell: Shell, askMax: Dp, modifier: Modifier = Modifier) {
    val ask = hub.approval(s.id)
    val offer = remember(ask, s) { CardOffer.of(ask, s) }
    val needs = s.state == SessionState.NeedsYou
    val block = s.activeBlock(hub.account, hub.providers, System.currentTimeMillis())
    GlassCard(modifier, edge = if (needs) HN.amber else null) {
        Column(Modifier.padding(16.dp)) {
            Seam(
                s.project, activity = if (needs) Activity.Alert else if (s.busy) Activity.Live else Activity.Rest,
                color = if (needs) HN.amber else HN.inkMuted,
                trailContent = { HText(s.hex, style = Type.monoSmall, color = HN.inkDim, maxLines = 1) },
            )
            Spacer(Modifier.height(8.dp))
            HText(s.name, Modifier.semantics { heading() }, style = Type.heading)
            Spacer(Modifier.height(4.dp))
            // The whole of what it asks, however long, and the pills stay in view under it: past a share of the window this scrolls.
            val askScroll = rememberScrollState()
            Box(Modifier.weight(1f, fill = false).heightIn(max = askMax)) {
                Column(Modifier.verticalScroll(askScroll)) {
                    when (offer) {
                        is CardOffer.Whole -> WholeCommand(offer)
                        is CardOffer.NotWhole -> NotWholeCommand(offer)
                        is CardOffer.InChat -> HText(inChatWords(offer), style = Type.small, color = HN.amber)
                        null -> when {
                            s.diedLine -> HText(Ui.card_job_died.text(), style = Type.monoSmall, color = HN.danger)
                            // A job's question in words: the reply goes in the box.
                            needs && s.question != null -> {
                                HText(s.question.orEmpty(), style = Type.monoSmall, color = HN.amber)
                                Spacer(Modifier.height(6.dp))
                                HText(Ui.desk_reply_below.text(), style = Type.small, color = HN.amber)
                            }
                            needs -> HText(s.activity, style = Type.monoSmall, color = HN.amber)
                            else -> HText(doingLine(s), style = Type.monoSmall, color = HN.inkMuted)
                        }
                    }
                }
                if (askScroll.maxValue > 0) {
                    VerticalScrollbar(
                        rememberScrollbarAdapter(askScroll),
                        Modifier.matchParentSize().wrapContentWidth(Alignment.End),
                        style = LocalScrollbarStyle.current.copy(unhoverColor = HN.ink.copy(alpha = 0.12f), hoverColor = HN.ink.copy(alpha = 0.3f)),
                    )
                }
            }
            ask?.takeIf { it.agentId != null }?.agentLabel?.let { agent ->
                Spacer(Modifier.height(4.dp))
                HText(Ui.desk_from_agent.format(agent), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            }
            (hub.waitingAsks(s.id).size - 1).takeIf { it > 0 }?.let { more ->
                Spacer(Modifier.height(4.dp))
                HText(Ui.desk_more_asks.format(more), style = Type.small, color = HN.inkDim)
            }
            block?.text?.takeIf { it.isNotBlank() }?.let { why ->
                Spacer(Modifier.height(6.dp))
                HText(why, style = Type.small, color = HN.inkMuted)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { StateChip(s, block) }
                if (offer is CardOffer.Whole || offer is CardOffer.NotWhole) {
                    Spacer(Modifier.width(8.dp))
                    AskPills(hub, s.id, offer, shell)
                }
            }
        }
    }
}

/** Where it runs and for how long. The times go on by themselves, so this reads the clock each second. */
@Composable
private fun Facts(s: Session, modifier: Modifier = Modifier) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    GlassCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (fact in factsOf(s, now)) {
                Row {
                    HText(fact.label, Modifier.width(FACT_LABEL), style = Type.small, color = HN.inkDim, maxLines = 1)
                    HText(fact.value, Modifier.weight(1f), style = Type.small, color = HN.ink)
                }
            }
        }
    }
}

private val FACT_LABEL = 128.dp

/** The share of the pane what's asked may take before it scrolls: the chat and the box need the rest. */
private const val ASK_SHARE = 0.4f

/** What the pane keeps for the box and the gaps round it, and for Back. */
private val BOX_ROOM = 88.dp
private val BACK_ROOM = 52.dp

private fun annotatedCommand(tool: String, text: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = HN.amber)) { append(if (tool.startsWith("Bash", ignoreCase = true)) "$ " else "› ") }
    append(text)
}

/** Allow promises this exact command: the tool, then all of it as it will run, line breaks and all, never cut. */
@Composable
private fun WholeCommand(offer: CardOffer.Whole) {
    HText(offer.tool.uppercase(), style = Type.label, color = HN.amber)
    Spacer(Modifier.height(4.dp))
    MouseSelectable {
        androidx.compose.foundation.text.BasicText(annotatedCommand(offer.tool, offer.text), style = Type.monoSmall.copy(color = HN.ink))
    }
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
