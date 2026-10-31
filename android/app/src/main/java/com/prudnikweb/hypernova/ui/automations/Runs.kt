package com.prudnikweb.hypernova.ui.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.Automation
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.Run
import com.prudnikweb.hypernova.data.WorkflowShape
import com.prudnikweb.hypernova.data.input
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * The editor's RUNS: the last runs, newest first, with Load more; a tap opens a run's session. Loaded
 * again whenever the Mac is back (and Try again when it failed). The seam counts every run the Mac
 * keeps, all from one window ("9 runs, 2 skipped"). A run that failed, stopped or was skipped has
 * **Retry**: it runs again with what fired it (A14, competitors' practice 10). "Allow next time"
 * saves the rule into the automation, asking first when it costs money (A12).
 */
@Composable
internal fun Runs(hub: Hub, a: Automation, w: Words, ctx: SummaryContext, now: Long, offline: Boolean, onOpenSession: (String) -> Unit, onAllowed: (String) -> Unit) {
    val context = LocalContext.current
    val res = context.resources
    val autos = hub.automations
    val scope = LocalAppScope.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val linked = hub.link.state == LinkState.Connected
    // The first page as it opens, and again each time the Mac comes back.
    LaunchedEffect(a.id, linked) { if (linked) autos.loadRuns(a.id) }
    val page = autos.runs(a.id)
    val runs = page?.runs.orEmpty().ifEmpty { listOfNotNull(a.lastRun) }
    // One window for both numbers: the Mac's counts of every run it keeps, else what's loaded.
    val total = page?.total ?: runs.size.takeIf { page != null } ?: a.runCount
    val skipped = page?.skipped ?: runs.count { it.state == Run.SKIPPED }
    // "9 runs, 2 skipped": a phrase, not "9 · 2 skipped".
    val trail = listOfNotNull(
        total.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.au_history_runs, it, it) },
        skipped.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.au_runs_skipped, it, it) },
    ).joinToString(", ").ifEmpty { null }
    // "Allow next time" goes into the one start step (a workflow with several says nothing: which one?).
    val starts = WorkflowShape.stepsOf(a).mapNotNull { it.action as? Action.Start }
    val start = starts.singleOrNull()
    val retry = rememberRetry(hub, w, ctx.zone)
    // A workflow run's steps: the newest open, the others on a tap.
    var opened by remember(a.id) { mutableStateOf<Set<String>>(emptySet()) }
    var closed by remember(a.id) { mutableStateOf<Set<String>>(emptySet()) }
    Column {
        Seam(stringResource(R.string.au_runs), Modifier.padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 4.dp), trail = trail)
        val err = page?.error
        when {
            err != null -> Row(Modifier.fillMaxWidth().padding(4.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                HText(err, style = Type.small, color = HN.amber, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                Pill(stringResource(R.string.try_again), { scope.launch { autos.loadRuns(a.id) } }, kind = PillKind.Outline, height = 36.dp, enabled = linked)
            }
            runs.isEmpty() && page?.loading != false -> LoadingLine(stringResource(R.string.au_runs), Modifier.padding(4.dp, 12.dp))
            runs.isEmpty() -> HText(stringResource(R.string.au_runs_none), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(4.dp, 12.dp))
        }
        for ((i, r) in runs.withIndex()) {
            val first = r.sessionIds.firstOrNull()
            val steps = r.steps.size > 1
            val shown = steps && (r.id in opened || (i == 0 && r.id !in closed))
            val used = r.budgetUsed
            RunRow(
                r, w, now, ctx.zone, askOf(hub, r), providerTrouble(context, hub, a), codex = startsOnCodex(a),
                onOpen = if (steps) ({
                    if (shown) { closed = closed + r.id; opened = opened - r.id } else { opened = opened + r.id; closed = closed - r.id }
                }) else first?.let { sid ->
                    {
                        // The phone lists the sessions the Mac does: one that left its list is said so.
                        if (hub.session(sid) != null) onOpenSession(sid) else toast(res.getString(R.string.au_transcript_gone))
                    }
                },
                openLabel = if (steps) stringResource(R.string.wf_steps_toggle) else null,
                detail = r.firedDetail,
                // A run with a budget says what it used, open or not ("12 of 30 min", a small meter).
                extra = when {
                    shown -> ({
                        Column {
                            RunTimeline(hub, r, a, w, now, onOpenSession, Modifier.padding(start = 0.dp, bottom = 8.dp))
                            used?.let { RunBudget(it, a.budget, Modifier.padding(bottom = 8.dp)) }
                        }
                    })
                    used != null -> ({ RunBudget(used, a.budget, Modifier.padding(bottom = 8.dp)) })
                    else -> null
                },
                onStop = { scope.launch { autos.stop(r.id).onFailure { toast(it.message.orEmpty()) } } },
                onRetry = if (offline) null else ({ retry(r) }),
                // Only a run that skipped what it wasn't allowed (dontAsk) offers this: no bypass to confirm,
                // and no command to check the fingerprint for.
                canAllow = start != null && Rules.kept(start.permissionMode) && WorkflowShape.stepsOf(a).none { it.action is Action.Shell },
                allowed = start?.allowedTools.orEmpty().toSet(),
                onAllow = { rule ->
                    val s = start ?: return@RunRow
                    if (rule in s.allowedTools || offline) return@RunRow
                    val input = a.input()
                    val next = input.steps?.let { list ->
                        val k = list.indexOfFirst { it.action is Action.Start }
                        input.copy(steps = list.toMutableList().also { it[k] = it[k].copy(action = s.copy(allowedTools = (s.allowedTools + rule).take(20))) })
                    } ?: input.copy(action = s.copy(allowedTools = (s.allowedTools + rule).take(20)))
                    val paidBy = next.paidProvider(hub.providers.firstOrNull { it.builtIn }?.id ?: "anthropic", providerOf(hub), costs(hub))
                    fun go() {
                        scope.launch {
                            autos.save(a.id, next.copy(confirmPaid = paidBy != null))
                                .onSuccess { onAllowed(rule); toast(res.getString(R.string.au_allowed_toast)) }
                                .onFailure { toast(it.message.orEmpty()) }
                        }
                    }
                    // A save that changes what runs on another provider asks, as every save does.
                    if (paidBy != null) {
                        val provider = hub.providers.firstOrNull { it.id == paidBy }?.name ?: paidBy
                        confirm(Confirm(res.getString(R.string.au_paid_q), res.getString(R.string.au_paid_body, provider), res.getString(R.string.au_paid_ok), danger = false) { go() })
                    } else go()
                },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (i < runs.lastIndex) Hairline(Modifier.fillMaxWidth())
        }
        if (page?.more == true) {
            Spacer(Modifier.height(10.dp))
            Pill(stringResource(R.string.au_load_more), { scope.launch { autos.loadRuns(a.id, more = true) } }, kind = PillKind.Ghost, height = 40.dp, enabled = linked)
        }
    }
}

/**
 * An automation that isn't on the Mac's list: still coming ([loading]), or deleted. Its sessions
 * stay in History, so they're listed here to open (A2).
 */
@Composable
internal fun GoneAutomation(hub: Hub, id: String, loading: Boolean, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val bottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val sessions = hub.sessions.filter { it.automation?.id == id }
    val name = sessions.firstOrNull()?.automation?.name
    com.prudnikweb.hypernova.ui.components.ScreenScaffold(
        sourceKey = "automation:$id",
        title = name ?: stringResource(R.string.au_tab),
        onBack = onBack,
    ) { top, source ->
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = top, bottom = bottom + 40.dp, start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (loading) {
                item(key = "loading") { LoadingLine(stringResource(R.string.au_loading_list), Modifier.padding(vertical = 8.dp)) }
                return@LazyColumn
            }
            item(key = "gone") {
                Column {
                    HText(stringResource(R.string.au_deleted_title), style = Type.heading, color = HN.ink)
                    Spacer(Modifier.height(6.dp))
                    HText(stringResource(if (sessions.isEmpty()) R.string.au_deleted_none else R.string.au_deleted_body), style = Type.small, color = HN.inkMuted)
                }
            }
            for (s in sessions) {
                item(key = s.id) {
                    com.prudnikweb.hypernova.ui.components.GlassCard(Modifier.fillMaxWidth(), onClick = { onOpenSession(s.id) }, onClickLabel = stringResource(R.string.au_open_run)) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                HText(s.name, style = Type.body, color = HN.ink, maxLines = 1)
                                HText(RunWords.at(rememberWords(), s.updatedAt, System.currentTimeMillis(), zoneOf(hub.automations.state?.tz)), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                            }
                            com.prudnikweb.hypernova.ui.components.Icon(Glyph.Chevron, size = 12.dp, color = HN.inkDim)
                        }
                    }
                }
            }
        }
    }
}
