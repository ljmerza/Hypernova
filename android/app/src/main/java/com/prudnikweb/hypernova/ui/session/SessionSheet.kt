package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.ClaudeEffort
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.ModelNames
import com.prudnikweb.hypernova.data.OutdatedClaude
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.WAgent
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.CountMark
import com.prudnikweb.hypernova.ui.components.FitRow
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.InfoRow
import com.prudnikweb.hypernova.ui.components.ModeTag
import com.prudnikweb.hypernova.ui.components.ModelChip
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.Stamp
import com.prudnikweb.hypernova.ui.components.agentName
import com.prudnikweb.hypernova.ui.components.frostedGlass
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
 * not just usage, it's all the information about this session and what you can change"). Under
 * its header a summary line, always in view, then tabs (owner, 2026-10-30; [SheetTab]): Overview
 * (the model, the plan's windows), Permissions (the mode, the blocked calls), Agents (the agents
 * at work) and Session (where it runs, the Home card's menu), each scrolling in the sheet. What
 * changes here, changes at once: the sheet stays open and shows it.
 *
 * [usage]: the screen's, null until the Mac has answered (the model's id). [onModel] opens the
 * model picker over the sheet, [onSwitchAgent] the switch to the other agent. [atBlocked]: opened
 * from the strip's blocked mark, so it opens on Permissions with the blocked calls in view.
 * [onRetried]: an Allow & retry went out (the sheet stays for the others; [onEmptied] closes it
 * once none is left).
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
    // The open tab, kept while the sheet is (a new one opens on Overview again).
    var tab by rememberSaveable { mutableStateOf(openingTab(atBlocked)) }
    // The blocked calls into view once, not each time Permissions is chosen again.
    var toBlocked by remember { mutableStateOf(atBlocked) }
    val agents = sheetAgents(hub.agents(session.id))
    val atWork = agentsAtWork(session, agents)
    ChatSheet(stringResource(R.string.session_sheet_title), onDismiss, scroll = false) {
        HText(session.name, style = Type.title, color = HN.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        // As the header has it: the project, and its short id as a quiet mark after it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            HText(session.project, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            HText(session.hex, style = Type.monoSmall.copy(fontSize = 10.sp), color = HN.inkDim.copy(alpha = 0.75f), maxLines = 1)
        }
        Spacer(Modifier.height(10.dp))
        val unknown = stringResource(R.string.model_default)
        SummaryLine(sheetSummary(session, atWork, if (usage == null) session.model else stripModel(usage, session.model, unknown)))
        Spacer(Modifier.height(14.dp))
        SheetTabs(tab, onTab = { tab = it })

        // The tab's parts, scrolling under the row, from the top for each tab; a floor under them
        // so the sheet doesn't jump much from one tab to the next.
        key(tab) {
            Column(Modifier.weight(1f, fill = false).heightIn(min = TAB_MIN_HEIGHT).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (tab) {
                    SheetTab.Overview -> {
                        SheetSeam(stringResource(R.string.model))
                        ModelPart(hub, session, usage, change, canChangeModel(hub, session), onModel, onSwitchAgent)
                        // Claude Code's effort (EffortPart.kt), on the subscription only; Codex's goes with its model.
                        if (ClaudeEffort.shown(session)) {
                            SheetSeam(stringResource(R.string.effort))
                            EffortPart(hub, session)
                        }
                        // Only the plan's windows: none for another provider, nor while they're unknown.
                        if (planShown(hub, session)) {
                            SheetSeam(stringResource(R.string.session_sheet_usage))
                            UsageDetails(hub)
                        }
                    }
                    SheetTab.Permissions -> {
                        SheetSeam(stringResource(R.string.session_sheet_permissions))
                        PermissionsPart(hub, session, canChangeMode(hub, session), toBlocked, onRetried, onEmptied, onBrought = { toBlocked = false })
                        // Several asks waiting: allowed together, each listed (AllowSeveral.kt).
                        AllowSeveralPart(hub, session)
                        // Claude Code's rules in effect, folded (RulesPart.kt); Codex has none.
                        if (!session.codex) RulesPart(hub, session)
                    }
                    SheetTab.Agents -> {
                        SheetSeam(stringResource(R.string.session_sheet_agents), trail = if (atWork > 0) stringResource(R.string.agents_running, atWork).uppercase() else null)
                        AgentsPart(agents, atWork, onOpen = { id -> onDismiss(); opener.agent(session.id, id) })
                    }
                    SheetTab.Commands -> CommandsTab(hub, session, onDismiss)
                    SheetTab.Session -> {
                        SheetSeam(stringResource(R.string.session_sheet_where))
                        WherePart(hub, session, onDismiss)
                        // The Home card's long-press menu (not in the demo, as on Home).
                        if (!hub.isDemo && MenuOffer.of(session).any) {
                            SheetSeam(stringResource(R.string.session_sheet_actions))
                            // Its job runs an older Claude Code: said quietly, above Restart.
                            session.agentOutdated?.let { OutdatedNotice(it) }
                            MenuActions(hub, session, { menuPage = it }, onDismiss)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp), horizontalArrangement = Arrangement.End) {
            Pill(stringResource(R.string.chat_close), onDismiss, kind = PillKind.Ghost, height = 44.dp)
        }
    }
    menuPage?.let { SessionMenu(hub, session, { menuPage = null }, start = it) }
}

/** The least a tab's parts take, so a short tab (no agents) doesn't shrink the sheet to a strip. */
private val TAB_MIN_HEIGHT = 320.dp

/** A part's seam, as the automation editor's blocks have them. */
@Composable
private fun SheetSeam(label: String, trail: String? = null) {
    Seam(label, Modifier.padding(top = 18.dp, bottom = 10.dp), trail = trail)
}

/**
 * What the session is, in one line under the header (owner, 2026-10-30), drawn as the app draws a
 * session's metadata, never "·"-joined: the mode's glyph and words, the model's chip, how long it
 * has run after a stopwatch, then the agents at work (mint) and the calls blocked (amber) as counts
 * with their point of light. Short of room, whole parts give way ([FitRow]): the time first, then
 * the chip keeps only the family, the mode only its glyph, then the chip goes. TalkBack reads it
 * as sentences.
 */
@Composable
private fun SummaryLine(sum: SheetSummary) {
    val res = LocalContext.current.resources
    val words = modeLabel(sum.mode)
    val ran = sum.ranMs?.let { formatDuration(res, it) }
    val agentsText = pluralStringResource(R.plurals.agents_count, sum.atWork, sum.atWork)
    val blockedText = pluralStringResource(R.plurals.blocked_chip, sum.blocked, sum.blocked)
    val said = buildString {
        append(res.getString(R.string.meta_mode_said, words))
        sum.model?.let { append(' ').append(res.getString(R.string.meta_model_said, it)) }
        ran?.let { append(' ').append(res.getString(R.string.ran_for, it)).append('.') }
        if (sum.showsAgents) append(' ').append(res.getQuantityString(R.plurals.session_sheet_agents_said, sum.atWork, sum.atWork))
        if (sum.showsBlocked) append(' ').append(res.getQuantityString(R.plurals.blocked_mark_said, sum.blocked, sum.blocked))
    }
    val mode = buildList<@Composable () -> Unit> {
        add { ModeTag(sum.mode, words, color = HN.inkMuted) }
        add { ModeTag(sum.mode, null, color = HN.inkMuted) }
    }
    val chips = buildList<@Composable () -> Unit> {
        val model = sum.model ?: return@buildList
        add { ModelChip(model) }
        ModelNames.family(model)?.let { family -> add { ModelChip(family) } }
    }
    val time = buildList<@Composable () -> Unit> { if (ran != null) add { Stamp(Glyph.Timer, ran) } }
    val counts = buildList<@Composable () -> Unit> { if (sum.showsAgents) add { CountMark(agentsText, HN.accent) } }
    val blocked = buildList<@Composable () -> Unit> { if (sum.showsBlocked) add { CountMark(blockedText, HN.amber) } }
    FitRow(
        parts = listOf(mode, chips, time, counts, blocked),
        // The time, then the chip to its family, the mode to its glyph, then the chip.
        giveWay = intArrayOf(2, 1, 0, 1),
        modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp).clearAndSetSemantics { contentDescription = said },
        gap = 10.dp,
    )
}

/**
 * The sheet's tabs as the segmented control draws its segments (components/Kit.kt): a glass
 * track, the open tab washed mint. As wide as the sheet when they fit ([tabWidths]), else the row
 * scrolls sideways and brings the open tab into view. TalkBack hears each as a tab, by its name.
 */
@Composable
private fun SheetTabs(tab: SheetTab, onTab: (SheetTab) -> Unit) {
    val bring = remember { SheetTab.entries.associateWith { BringIntoViewRequester() } }
    LaunchedEffect(tab) {
        withFrameNanos { }
        bring.getValue(tab).bringIntoView()
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .frostedGlass(null, glowX = 0.dp)
            .padding(4.dp),
    ) {
        val room = constraints.maxWidth
        Layout(
            content = {
                for (t in SheetTab.entries) {
                    val on = t == tab
                    Box(
                        Modifier
                            .bringIntoViewRequester(bring.getValue(t))
                            .heightIn(min = 40.dp)
                            .clip(CircleShape)
                            .glassChoice(on)
                            .selectable(on, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onTab(t) }
                            .padding(vertical = 9.dp, horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        HText(stringResource(t.label), style = Type.small, color = if (on) HN.accent else HN.inkMuted, maxLines = 1)
                    }
                }
            },
            modifier = Modifier.clip(CircleShape).horizontalScroll(rememberScrollState()).selectableGroup(),
        ) { measurables, constraints ->
            val widths = tabWidths(IntArray(measurables.size) { measurables[it].maxIntrinsicWidth(Constraints.Infinity) }, room)
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints(minWidth = widths[i], maxWidth = widths[i], maxHeight = constraints.maxHeight)) }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(placeables.sumOf { it.width }, height) {
                var x = 0
                for (p in placeables) {
                    p.placeRelative(x, (height - p.height) / 2)
                    x += p.width
                }
            }
        }
    }
}

/**
 * The model, as the status line's panel had it: its chip and a way to another one (or, where it
 * can't be changed from here, [changeModel] false, the chip alone), its full id when that says more,
 * and the way to the other agent (where [change]).
 */
@Composable
private fun ModelPart(hub: Hub, s: Session, usage: SessionUsage?, change: Boolean, changeModel: Boolean, onModel: () -> Unit, onSwitchAgent: (String) -> Unit) {
    val res = LocalContext.current.resources
    val id = modelId(usage, s.model, res.getString(R.string.model_default))
    if (changeModel) ModelLine(s.model, onClick = onModel)
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
 * one dismissed doesn't take the part away under the finger). Claude Code's rules in effect come
 * after it, read-only (RulesPart.kt). [atBlocked]: the calls into view once they're in, then
 * [onBrought].
 */
@Composable
private fun PermissionsPart(hub: Hub, s: Session, change: Boolean, atBlocked: Boolean, onRetried: () -> Unit, onEmptied: () -> Unit, onBrought: () -> Unit) {
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
            onBrought()
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

/**
 * A `claude agents` job on an older Claude Code than the one installed (it updated itself since):
 * "Runs Claude Code 2.1.312; 2.1.314 is installed", quiet, over the actions' Restart.
 */
@Composable
private fun OutdatedNotice(v: OutdatedClaude) {
    HText(
        stringResource(R.string.session_outdated, v.runs, v.installed),
        style = Type.small, color = HN.inkMuted, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
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
