package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.ModelNames
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.WAgent
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.InfoRow
import com.prudnikweb.hypernova.ui.components.ModeTag
import com.prudnikweb.hypernova.ui.components.ModelChip
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.agentName
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.fx.formatDuration
import com.prudnikweb.hypernova.ui.home.MenuActions
import com.prudnikweb.hypernova.ui.home.MenuOffer
import com.prudnikweb.hypernova.ui.home.MenuPage
import com.prudnikweb.hypernova.ui.home.SessionMenu
import com.prudnikweb.hypernova.ui.home.modeLabel
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * The session sheet, a tap on the chat's usage strip or its status line (owner, 2026-10-29: "it's
 * not just usage, it's all the information about this session and what you can change"). Its
 * parts under seams: the model, the permissions (the mode, the blocked calls), the plan's windows,
 * the agents at work, where it runs, and the Home card's menu. What changes here, changes at once:
 * the sheet stays open and shows it.
 *
 * [usage]: the screen's, null until the Mac has answered (the model's id). [onModel] opens the
 * model picker over the sheet, [onSwitchAgent] the switch to the other agent. [atBlocked]: opened
 * from the strip's blocked mark, so the blocked calls are brought into view. [onRetried]: an Allow &
 * retry went out (the sheet stays for the others; [onEmptied] closes it once none is left).
 */
@Composable
internal fun SessionSheet(
    hub: Hub,
    session: Session,
    usage: SessionUsage?,
    onModel: () -> Unit,
    onSwitchAgent: (String) -> Unit,
    onRetried: () -> Unit,
    /** The last blocked call was retried or dismissed: the sheet goes. */
    onEmptied: () -> Unit = {},
    onDismiss: () -> Unit,
    atBlocked: Boolean = false,
) {
    val opener = LocalOpener.current
    val change = canChange(hub, session)
    // Rename or Delete from the actions, over the sheet (ui/home/SessionMenu.kt).
    var menuPage by remember { mutableStateOf<MenuPage?>(null) }
    ChatSheet(stringResource(R.string.session_sheet_title), onDismiss) {
        HText(session.name, style = Type.title, color = HN.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        // As the header has it: the project, and its short id as a quiet mark after it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            HText(session.project, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            HText(session.hex, style = Type.monoSmall.copy(fontSize = 10.sp), color = HN.inkDim.copy(alpha = 0.75f), maxLines = 1)
        }

        // Owner, 2026-10-30: the model, then the permissions, then the plan's windows.
        SheetSeam(stringResource(R.string.model))
        ModelPart(hub, session, usage, change, onModel, onSwitchAgent)

        SheetSeam(stringResource(R.string.session_sheet_permissions))
        PermissionsPart(hub, session, canChangeMode(hub, session), atBlocked, onRetried, onEmptied)

        // Only the plan's windows: none for another provider, nor while they're unknown.
        if (planShown(hub, session)) {
            SheetSeam(stringResource(R.string.session_sheet_usage))
            UsageDetails(hub)
        }

        val agents = sheetAgents(hub.agents(session.id))
        val atWork = agentsAtWork(session, agents)
        SheetSeam(stringResource(R.string.session_sheet_agents), trail = if (atWork > 0) stringResource(R.string.agents_running, atWork).uppercase() else null)
        AgentsPart(agents, atWork, onOpen = { id -> onDismiss(); opener.agent(session.id, id) })

        SheetSeam(stringResource(R.string.session_sheet_where))
        WherePart(hub, session, onDismiss)

        // The Home card's long-press menu (not in the demo, as on Home).
        if (!hub.isDemo && MenuOffer.of(session).any) {
            SheetSeam(stringResource(R.string.session_sheet_actions))
            MenuActions(hub, session, { menuPage = it }, onDismiss)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Pill(stringResource(R.string.chat_close), onDismiss, kind = PillKind.Ghost, height = 44.dp)
        }
    }
    menuPage?.let { SessionMenu(hub, session, { menuPage = null }, start = it) }
}

/** A part's seam, as the automation editor's blocks have them. */
@Composable
private fun SheetSeam(label: String, trail: String? = null) {
    Seam(label, Modifier.padding(top = 18.dp, bottom = 10.dp), trail = trail)
}

/**
 * The model, as the status line's panel had it: its chip and a way to another one (or, where it
 * can't be changed from here, the chip alone), its full id when that says more, and the way to the
 * other agent.
 */
@Composable
private fun ModelPart(hub: Hub, s: Session, usage: SessionUsage?, change: Boolean, onModel: () -> Unit, onSwitchAgent: (String) -> Unit) {
    val res = LocalContext.current.resources
    val id = modelId(usage, s.model, res.getString(R.string.model_default))
    if (change) ModelLine(s.model, onClick = onModel)
    else {
        val name = ModelNames.chip(s.model)
        Row(Modifier.clearAndSetSemantics { contentDescription = res.getString(R.string.meta_model_said, name) }) { ModelChip(name) }
    }
    if (id != null) {
        Spacer(Modifier.height(6.dp))
        HText(id, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
    }
    // The other agent goes on from here (docs/PROTOCOL.md, "Switching agents"): Codex once it's on
    // this Mac, and back to Claude from any Codex session.
    val other = if (change) switchTarget(hub, s) else null
    if (other != null) {
        Spacer(Modifier.height(10.dp))
        AgentLine(s.codex, onClick = { onSwitchAgent(other) })
    }
}

/**
 * What it may do: the permission mode, changed here as in the new-session sheet (Codex's two from
 * its next turn while one runs; Auto and Bypass behind the think-twice card, Bypass behind the
 * fingerprint too), or said with why it can't be; then the calls refused without the owner, with
 * Allow & retry and Dismiss, while any wait (and once shown, until the sheet closes, so the last
 * one dismissed doesn't take the part away under the finger). The phone knows nothing more of its
 * rules (allow lists are Claude Code's own, on the Mac).
 */
@Composable
private fun PermissionsPart(hub: Hub, s: Session, change: Boolean, atBlocked: Boolean, onRetried: () -> Unit, onEmptied: () -> Unit) {
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val context = LocalContext.current
    val codexNextTurn = stringResource(R.string.cx_mode_next_turn)
    val bypassNextTurn = stringResource(R.string.mode_bypass_next_turn)
    val bypassTitle = stringResource(R.string.check_bypass_title)
    val noLock = stringResource(R.string.check_no_lock)
    when {
        change && s.codex -> com.prudnikweb.hypernova.ui.components.Segmented(
            AgentKind.CODEX_MODES.map { it to com.prudnikweb.hypernova.ui.home.modeTitle(it) },
            s.mode,
            { m ->
                if (m != s.mode) appScope.launch {
                    hub.setMode(s.id, m).onSuccess { nextTurn -> if (nextTurn) toast(codexNextTurn) }.onFailure { toast(it.message.orEmpty()) }
                }
            },
        )
        change -> com.prudnikweb.hypernova.ui.components.ModePicker(
            s.mode,
            { m ->
                val go = {
                    appScope.launch {
                        hub.setMode(s.id, m).onSuccess { nextTurn -> if (nextTurn) toast(bypassNextTurn) }.onFailure { toast(it.message.orEmpty()) }
                    }
                }
                // Bypass waits for the fingerprint or the screen lock too (docs/DECISIONS.md).
                if (m != s.mode && m == "bypassPermissions") {
                    com.prudnikweb.hypernova.ui.components.OwnerCheck.ask(
                        context, bypassTitle, s.name, noLock, hub.isDemo,
                        onOk = { go() }, onFail = { msg -> if (msg != null) toast(msg) },
                    )
                } else if (m != s.mode) go()
            },
            bypassAvailable = s.bypassAvailable,
        )
        else -> {
            val words = modeLabel(s.mode)
            val said = context.resources.getString(R.string.meta_mode_said, words)
            ModeTag(s.mode, words, Modifier.padding(start = 4.dp).clearAndSetSemantics { contentDescription = said }, color = HN.inkMuted)
            Spacer(Modifier.height(6.dp))
            HText(
                stringResource(if (s.phase == Phase.Ended) R.string.session_sheet_mode_ended else R.string.session_sheet_mode_watched),
                style = Type.small, color = HN.inkDim, modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
    var seen by remember(s.id) { mutableStateOf(s.blockedCalls > 0) }
    LaunchedEffect(s.blockedCalls) { if (s.blockedCalls > 0) seen = true }
    if (s.blockedCalls == 0 && !seen) return
    // Opened from the strip's mark: the calls into view once they're in.
    val calls = remember { BringIntoViewRequester() }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(loaded) {
        if (atBlocked && loaded) {
            withFrameNanos { }
            calls.bringIntoView()
        }
    }
    Column(Modifier.bringIntoViewRequester(calls)) {
        Spacer(Modifier.height(14.dp))
        Hairline(Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HText(stringResource(R.string.session_sheet_blocked).uppercase(), style = Type.label, color = HN.inkDim, modifier = Modifier.weight(1f))
            if (s.blockedCalls > 0) HText("${s.blockedCalls}", style = Type.monoSmall, color = HN.amber, maxLines = 1)
        }
        Spacer(Modifier.height(8.dp))
        BlockedCalls(hub, s, onRetried, onLoaded = { loaded = true }, onEmptied = onEmptied)
    }
}

/**
 * The agents at work: each running workflow with its agents done of started ("review-changes
 * 1/7") and its agents at work under it, then the others. A tap on an agent opens its screen.
 */
@Composable
private fun AgentsPart(agents: SheetAgents, atWork: Int, onOpen: (String) -> Unit) {
    if (agents.isEmpty) {
        // The board isn't on this phone yet: the seam's count says it.
        if (atWork == 0) HText(stringResource(R.string.session_sheet_no_agents), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(start = 4.dp))
        return
    }
    // How long each has run, a second at a time while the sheet is open.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    for (w in agents.workflows) {
        WorkflowRow(w)
        for (a in w.agents) AgentRow(a, now, indent = true, onOpen = { onOpen(a.id) })
    }
    for (a in agents.loose) AgentRow(a, now, indent = false, onOpen = { onOpen(a.id) })
}

/** A running workflow: its name, and its agents done of those it has started so far. */
@Composable
private fun WorkflowRow(w: SheetWorkflow) {
    val said = stringResource(R.string.session_sheet_workflow_said, w.name, w.done, w.started)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).clearAndSetSemantics { contentDescription = said }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(w.name.uppercase(), style = Type.label, color = HN.accentDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(10.dp))
        HText("${w.done}/${w.started}", style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
    }
}

/** One agent at work: a mint dot, its label, what it does now and how long it has run; a tap opens it. */
@Composable
private fun AgentRow(a: WAgent, now: Long, indent: Boolean, onOpen: () -> Unit) {
    val res = LocalContext.current.resources
    val doing = a.activity?.takeIf { it.isNotBlank() } ?: stringResource(R.string.session_sheet_agent_working)
    val time = formatDuration(res, now - a.startedAt)
    val said = stringResource(R.string.session_sheet_agent_said, a.label, doing, time)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.session_sheet_open_agent), onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(start = if (indent) 14.dp else 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(HN.accent, RoundedCornerShape(50)))
        Spacer(Modifier.width(10.dp))
        HText(a.label, style = Type.monoSmall, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        HText(doing, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(10.dp))
        HText(time, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Icon(Glyph.Chevron, size = 10.dp, color = HN.inkDim)
    }
}

/**
 * Where it runs: its folder (and the file browser, where the Mac lets the phone browse), what holds
 * it (and its live terminal), the agent and provider, its `claude agents` job and its id.
 */
@Composable
private fun WherePart(hub: Hub, s: Session, onDismiss: () -> Unit) {
    val opener = LocalOpener.current
    val cwd = s.cwd
    if (cwd != null) InfoRow(stringResource(R.string.session_sheet_folder), cwd)
    // As the header's Folder button: quieter where only Browse outside projects would open it.
    if (cwd != null && !hub.isDemo && (s.browsable || s.outsideProjects)) {
        SheetLink(stringResource(R.string.files), null, { onDismiss(); opener.files(cwd, s.project) }, dim = !s.browsable)
        Spacer(Modifier.height(4.dp))
    }
    InfoRow(
        stringResource(R.string.session_sheet_runs_in),
        stringResource(
            when (runsIn(s)) {
                RunsIn.Terminal -> R.string.session_sheet_runs_terminal
                RunsIn.Mac -> R.string.session_sheet_runs_mac
                RunsIn.Watched -> R.string.session_sheet_runs_watched
            },
        ),
    )
    // A Codex session gets a terminal when it's asked for, as from the header.
    if ((s.term != null || s.codex) && !hub.isDemo) {
        SheetLink(stringResource(R.string.open_terminal), null, { onDismiss(); opener.terminal(s.id) })
        Spacer(Modifier.height(4.dp))
    }
    InfoRow(stringResource(R.string.session_sheet_agent), agentName(s.codex))
    if (!s.codex) {
        hub.providers.firstOrNull { it.id == (s.providerId ?: "anthropic") }?.let { InfoRow(stringResource(R.string.session_sheet_provider), it.name) }
    }
    s.agentJob?.let { InfoRow(stringResource(R.string.session_sheet_job), it) }
    InfoRow(stringResource(R.string.session_sheet_id), s.id)
}

/** A way somewhere from the sheet, as the model's line: its words, a quiet [trail], a chevron. */
@Composable
private fun SheetLink(text: String, trail: String?, onClick: () -> Unit, dim: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .glassChoice(false, 12.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(text, style = Type.small, color = if (dim) HN.inkDim else HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trail != null) {
            Spacer(Modifier.width(10.dp))
            HText(trail, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Glyph.Chevron, size = 11.dp, color = HN.inkDim)
    }
}

/**
 * What the header's line used to say, for TalkBack after the usage strip's own sentences: the
 * agents at work ([atWork]), the prompts queued, and the permission mode. Starts with a space.
 */
@Composable
internal fun stripMore(s: Session, atWork: Int): String {
    val res = LocalContext.current.resources
    return buildString {
        if (atWork > 0) append(' ').append(res.getQuantityString(R.plurals.session_sheet_agents_said, atWork, atWork))
        if (s.queued > 0) append(' ').append(res.getString(R.string.queued, s.queued)).append('.')
        append(' ').append(res.getString(R.string.meta_mode_said, modeLabel(s.mode)))
    }
}
