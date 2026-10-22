package com.prudnikweb.hypernova.ui.automations

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AutomationError
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Trigger
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.DialogButton
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.GlassDialog
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.components.TagChip
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.home.tilde
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId

/** The new triggers' words that don't need the screen; unit-tested. */
object TriggerWords {
    /**
     * A time of day already picked, as cron, so switching to a cron schedule starts from it: "At 9:00
     * on weekdays" is "0 9 * * 1-5"; every 2 hours from 9:00 to 19:00 is "0 9-19/2 * * 1-5". Null for
     * any other trigger.
     */
    fun cronOf(t: Trigger): String? = when (t) {
        is Trigger.Time -> {
            val (h, m) = t.at.split(':').map { it.toInt() }
            "$m $h * * ${cronDays(t.days)}"
        }
        is Trigger.Every -> {
            val (h, m) = t.from.split(':').map { it.toInt() }
            val hours = if (t.to != null) "$h-${t.to.substringBefore(':').toInt()}/${t.hours}" else "${EditorRules.firstOfDay(t.from, t.hours).substringBefore(':').toInt()}/${t.hours}"
            "$m $hours * * ${cronDays(t.days)}"
        }
        else -> null
    }

    /** ISO days (1 Monday … 7 Sunday) as cron's day of the week: "*", "1-5", "0,6". */
    fun cronDays(days: List<Int>): String = when {
        days.size == 7 -> "*"
        days == Trigger.WEEKDAYS -> "1-5"
        else -> days.map { it % 7 }.sorted().joinToString(",")
    }

    /** The curl line that fires a webhook trigger with its token. */
    fun curl(url: String, token: String): String = "curl -fsS -X POST -H 'Authorization: Bearer $token' --data 'it is done' '$url'"
}

// ─── Cron ──────────────────────────────────────────────────────────────────

/**
 * A cron schedule: typed, checked on the Mac as it's typed (the same rules as Save), with its next
 * three times drawn as chips. The friendly pickers stay first in the tiles; this says so.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CronParams(hub: Hub, t: Trigger.Cron, zone: ZoneId, now: Long, w: Words, errorField: String?, onChange: (Trigger) -> Unit) {
    val autos = hub.automations
    var next by remember { mutableStateOf<List<Long>?>(null) }
    var bad by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(t.expr) {
        if (t.expr.isBlank()) { next = null; bad = null; return@LaunchedEffect }
        checking = true
        delay(450)
        autos.check(t)
            .onSuccess { next = it.next; bad = null }
            .onFailure { e ->
                next = null
                // Only the Mac's word on the schedule marks it; out of reach, it waits for Save.
                bad = (e as? AutomationError)?.takeIf { it.code in setOf("bad_cron", "too_often", "bad_trigger") }?.message
            }
        checking = false
    }
    Spacer(Modifier.height(4.dp))
    Field(
        t.expr, { onChange(t.copy(expr = it.take(120))) },
        placeholder = stringResource(R.string.wf_cron_hint), label = stringResource(R.string.wf_cron_expr), mono = true,
        error = errorField == "expr" || bad != null,
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
    )
    Spacer(Modifier.height(10.dp))
    val times = next
    when {
        bad != null -> HText(bad!!, style = Type.small, color = HN.amber)
        times != null && times.isNotEmpty() -> {
            val words = times.map { RunWords.clockWords(w, it, now, zone) }
            val said = stringResource(R.string.wf_cron_next, Summary.andList(w, words))
            FlowRow(
                Modifier.semantics(mergeDescendants = true) { contentDescription = said },
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                HText(stringResource(R.string.wf_next_label).uppercase(), style = Type.label, color = HN.inkDim)
                for (x in words) TagChip(x)
            }
        }
        checking -> HText(stringResource(R.string.wf_cron_checking), style = Type.monoSmall, color = HN.inkDim)
    }
    Spacer(Modifier.height(10.dp))
    HText(stringResource(R.string.wf_cron_note), style = Type.small, color = HN.inkDim)
    Spacer(Modifier.height(4.dp))
    HText(stringResource(R.string.wf_cron_friendly), style = Type.small, color = HN.inkDim)
}

// ─── Webhook ───────────────────────────────────────────────────────────────

/**
 * A webhook trigger: before its first save, what Save gives; after, its token's last four and
 * when it was made, the address, Copy the command, and New token (asked first: the old one stops).
 */
@Composable
internal fun WebhookParams(hub: Hub, t: Trigger.Webhook, savedId: String?, w: Words, zone: ZoneId, now: Long) {
    val context = LocalContext.current
    val scope = LocalAppScope.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    Spacer(Modifier.height(4.dp))
    if (t.last4.isEmpty() || savedId == null) {
        HText(stringResource(R.string.wf_wh_before), style = Type.small, color = HN.inkMuted)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HText(stringResource(R.string.wf_wh_token).uppercase(), style = Type.label, color = HN.inkDim)
        Spacer(Modifier.width(10.dp))
        KeyChip(t.last4)
        if (t.createdAt > 0) {
            Spacer(Modifier.width(10.dp))
            HText(stringResource(R.string.wf_wh_made, RunWords.at(w, t.createdAt, now, zone)), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        }
    }
    if (t.url.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        HText(t.url, style = Type.monoSmall, color = HN.inkMuted, maxLines = 2, overflow = TextOverflow.MiddleEllipsis)
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (t.command.isNotEmpty()) {
            Pill(stringResource(R.string.wf_wh_copy_command), { copy(context, t.command, sensitive = false); toast(context.getString(R.string.wf_copied)) }, kind = PillKind.Outline, height = 36.dp)
            Spacer(Modifier.width(8.dp))
        }
        Pill(stringResource(R.string.wf_wh_new_token), {
            confirm(
                Confirm(context.getString(R.string.wf_wh_new_q), context.getString(R.string.wf_wh_new_body), context.getString(R.string.wf_wh_new_ok), danger = true) {
                    scope.launch { hub.automations.newToken(savedId).onFailure { toast(it.message.orEmpty()) } }
                },
            )
        }, kind = PillKind.Ghost, height = 36.dp)
    }
}

/** Puts [text] on the clipboard; a token is marked sensitive, so the keyboard's preview hides it (Android 13+). */
internal fun copy(context: Context, text: String, sensitive: Boolean) {
    val clip = ClipData.newPlainText("Hypernova", text)
    if (sensitive && Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/**
 * A webhook trigger's token, just made, shown once in a floating glass card: the token, the
 * address, a curl line and the command for scripts on the Mac, each with Copy. Done puts it away.
 */
@Composable
internal fun TokenCard(t: Trigger.Webhook, token: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val curl = TriggerWords.curl(t.url, token)
    fun copied(text: String, sensitive: Boolean) { copy(context, text, sensitive); toast(context.getString(R.string.wf_copied)) }
    GlassDialog(
        onDismiss = onDone,
        eyebrow = stringResource(R.string.wf_tc_eyebrow),
        title = stringResource(R.string.wf_tc_title),
        line = stringResource(R.string.wf_tc_line),
        tint = HN.amber,
        secondary = DialogButton(stringResource(R.string.wf_tc_copy_all), {
            copied(listOf(token, t.url, curl, t.command).filter { it.isNotEmpty() }.joinToString("\n"), sensitive = true)
        }, PillKind.Ghost),
        primary = DialogButton(stringResource(R.string.wf_tc_done), onDone, PillKind.Primary),
        body = {
            CopyRow(stringResource(R.string.wf_wh_token), token) { copied(token, sensitive = true) }
            if (t.url.isNotEmpty()) CopyRow(stringResource(R.string.wf_tc_url), t.url) { copied(t.url, sensitive = false) }
            if (t.url.isNotEmpty()) CopyRow(stringResource(R.string.wf_tc_curl), curl) { copied(curl, sensitive = true) }
            if (t.command.isNotEmpty()) CopyRow(stringResource(R.string.wf_tc_cli), t.command) { copied(t.command, sensitive = false) }
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.wf_tc_local), style = Type.small, color = HN.inkDim)
        },
    )
}

/** A label, the value in mono, and a small Copy. */
@Composable
private fun CopyRow(label: String, value: String, onCopy: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        HText(label.uppercase(), style = Type.label, color = HN.inkDim)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HText(value, style = Type.monoSmall, color = HN.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Pill(stringResource(R.string.wf_copy), onCopy, kind = PillKind.Outline, height = 32.dp)
        }
    }
}

// ─── Files ─────────────────────────────────────────────────────────────────

/** A file watch: the project folder, which files (a glob, or any), and whether they're added, changed or deleted. */
@Composable
internal fun FileWatchParams(hub: Hub, t: Trigger.FileWatch, errorField: String?, onChange: (Trigger) -> Unit) {
    var pick by remember { mutableStateOf(false) }
    ProjectRow(hub, stringResource(R.string.wf_file_folder), t.path) { pick = true }
    ParamLabel(stringResource(R.string.wf_file_glob))
    Field(
        t.glob.orEmpty(), { onChange(t.copy(glob = it.take(200).ifBlank { null })) },
        placeholder = stringResource(R.string.wf_file_glob_hint), label = stringResource(R.string.wf_file_glob), mono = true, error = errorField == "glob",
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
    )
    ParamLabel(stringResource(R.string.wf_file_event))
    Segmented(
        listOf(Trigger.CREATED to stringResource(R.string.wf_fe_created), Trigger.CHANGED to stringResource(R.string.wf_fe_changed), Trigger.DELETED to stringResource(R.string.wf_fe_deleted)),
        t.event, { onChange(t.copy(event = it)) },
    )
    Spacer(Modifier.height(10.dp))
    HText(stringResource(R.string.wf_file_watch_note), style = Type.small, color = HN.inkDim)
    if (pick) ProjectSheet(hub, t.path, onPick = { pick = false; onChange(t.copy(path = it)) }, onDismiss = { pick = false })
}

// ─── GitHub ────────────────────────────────────────────────────────────────

/** A GitHub watch: the repository (a project with a GitHub remote), what to watch for, and how often to look. */
@Composable
internal fun GithubParams(hub: Hub, t: Trigger.Github, errorField: String?, onChange: (Trigger) -> Unit) {
    var pick by remember { mutableStateOf(false) }
    ProjectRow(hub, stringResource(R.string.wf_gh_repo), t.repoPath) { pick = true }
    ParamLabel(stringResource(R.string.wf_gh_when))
    val names = mapOf(
        Trigger.PR_OPENED to stringResource(R.string.wf_gh_e_pr),
        Trigger.REVIEW_COMMENT to stringResource(R.string.wf_gh_e_review),
        Trigger.CHECKS_FAILED to stringResource(R.string.wf_gh_e_checks),
    )
    for (e in Trigger.GITHUB_EVENTS) {
        val on = e in t.events
        // At least one stays on.
        ToggleRow(names.getValue(e), null, on, { v ->
            val next = if (v) Trigger.GITHUB_EVENTS.filter { it in t.events || it == e } else t.events - e
            if (next.isNotEmpty()) onChange(t.copy(events = next))
        })
    }
    if (errorField == "events") HText(stringResource(R.string.au_err_trigger), style = Type.small, color = HN.amber)
    ParamLabel(stringResource(R.string.wf_gh_every))
    Segmented(listOf(5, 10, 15).map { it to stringResource(R.string.wf_minutes, it) }, t.every, { onChange(t.copy(every = it)) })
    Spacer(Modifier.height(10.dp))
    HText(stringResource(R.string.wf_gh_note), style = Type.small, color = HN.inkDim)
    if (pick) ProjectSheet(hub, t.repoPath, onPick = { pick = false; onChange(t.copy(repoPath = it)) }, onDismiss = { pick = false })
}

// ─── Claude Code hooks ─────────────────────────────────────────────────────

/** A Claude Code hook: which event (and for a failed tool call, which tool), in which project, from whose sessions. */
@Composable
internal fun HookParams(hub: Hub, t: Trigger.Hook, onChange: (Trigger) -> Unit) {
    var pick by remember { mutableStateOf(false) }
    ParamLabel(stringResource(R.string.wf_hook_event), Modifier.padding(top = 4.dp))
    val names = mapOf(
        Trigger.H_STOP to stringResource(R.string.wf_he_stop),
        Trigger.H_TOOL_FAILURE to stringResource(R.string.wf_he_tool),
        Trigger.H_STOP_FAILURE to stringResource(R.string.wf_he_stop_failure),
        Trigger.H_PRE_COMPACT to stringResource(R.string.wf_he_compact),
        Trigger.H_SESSION_END to stringResource(R.string.wf_he_end),
    )
    for (e in Trigger.HOOK_EVENTS) ChoiceRow(names.getValue(e), null, t.event == e) { onChange(t.copy(event = e, tool = if (e == Trigger.H_TOOL_FAILURE) t.tool else null)) }
    if (t.event == Trigger.H_TOOL_FAILURE) {
        ParamLabel(stringResource(R.string.wf_hook_tool))
        Field(
            t.tool.orEmpty(), { onChange(t.copy(tool = it.trim().take(60).ifEmpty { null })) },
            placeholder = stringResource(R.string.wf_hook_tool_hint), label = stringResource(R.string.wf_hook_tool), mono = true,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
    }
    Spacer(Modifier.height(6.dp))
    PickRow(stringResource(R.string.au_project), t.projectPath?.let { p -> hub.projects.firstOrNull { it.path == p }?.name ?: p.substringAfterLast('/') } ?: stringResource(R.string.au_any_project), { pick = true })
    ParamLabel(stringResource(R.string.au_sessions_from))
    Segmented(
        listOf(Trigger.MINE to stringResource(R.string.au_from_mine), Trigger.AUTOMATED to stringResource(R.string.au_from_auto), Trigger.ANY to stringResource(R.string.au_from_any)),
        t.from, { onChange(t.copy(from = it)) },
    )
    Spacer(Modifier.height(10.dp))
    HText(stringResource(R.string.wf_hook_note), style = Type.small, color = HN.inkDim)
    if (pick) {
        val any = stringResource(R.string.au_any_project)
        ChoiceSheet(
            stringResource(R.string.au_project),
            listOf(Choice<String?>(null, any)) + hub.projects.map { Choice<String?>(it.path, it.name, tilde(it.path)) },
            t.projectPath,
            onPick = { p -> pick = false; onChange(t.copy(projectPath = p)) },
            onDismiss = { pick = false },
        )
    }
}

// ─── Shared ────────────────────────────────────────────────────────────────

@Composable
private fun ProjectRow(hub: Hub, label: String, path: String, onClick: () -> Unit) {
    val p = hub.projects.firstOrNull { it.path == path }
    PickRow(label, p?.name ?: path.trimEnd('/').substringAfterLast('/').ifEmpty { stringResource(R.string.au_err_project_needed) }, onClick, sub = path.takeIf { it.isNotEmpty() }?.let(::tilde))
}

@Composable
private fun ProjectSheet(hub: Hub, selected: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ChoiceSheet(
        stringResource(R.string.au_project),
        hub.projects.map { Choice(it.path, it.name, tilde(it.path)) },
        selected,
        onPick = onPick,
        onDismiss = onDismiss,
        empty = stringResource(R.string.au_no_projects),
    )
}
