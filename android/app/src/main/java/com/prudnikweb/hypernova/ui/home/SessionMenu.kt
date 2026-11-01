package com.prudnikweb.hypernova.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.OutdatedJobs
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.AttentionAction
import com.prudnikweb.hypernova.ui.components.AttentionCard
import com.prudnikweb.hypernova.ui.components.ConfirmDialog
import com.prudnikweb.hypernova.ui.components.DialogButton
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.GlassDialog
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.session.ChatGlyph
import com.prudnikweb.hypernova.ui.session.SheetAction
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * What the menu's Delete does to a [Session], and so what its confirm says (DECISIONS, "Delete for
 * every finished session", 2026-11-01). A job and a Codex session have it always, as they did. Any
 * other session has it once it's at rest (Home's Completed, ended ones too): at work or waiting on the
 * owner it has none.
 */
enum class DeleteKind {
    /** At work or waiting on the owner, and neither a job nor a Codex session: not offered. */
    None,
    /** A job with no process: `claude rm`. */
    Job,
    /** A job whose process still runs: `claude rm` stops it, and a terminal open on it closes. */
    RunningJob,
    /** Codex's own delete, for good. */
    Codex,
    /** At rest in a terminal Hypernova holds: the terminal closes, then Hypernova forgets it. */
    Terminal,
    /** Nothing runs: Hypernova forgets it. */
    Forget,
    /** Idle in a terminal Hypernova doesn't hold: it can't close that one, and the dialog says so. */
    OpenElsewhere;

    companion object {
        fun of(s: Session): DeleteKind = when {
            s.codex -> Codex
            s.agentJob != null -> if (s.agentRunning == true && s.phase != Phase.Ended) RunningJob else Job
            homeGroup(s) != HomeGroup.Completed -> None
            s.term != null -> Terminal
            s.origin == "terminal" && s.phase != Phase.Ended -> OpenElsewhere
            else -> Forget
        }
    }
}

/**
 * What the menu's Restart session does to a [Session] (DECISIONS, "Restart session", 2026-11-01): a
 * new claude on the same conversation, so its MCP servers and settings load afresh. Where something
 * runs that Hypernova can restart; nothing running needs none, its next prompt loads them.
 */
enum class RestartKind {
    /** Nothing to restart: ended, no process, or a Codex session. */
    None,
    /** A `claude agents` job whose process runs: `claude respawn`. */
    Job,
    /** A shared terminal Hypernova holds: it closes and the conversation resumes in a new one. */
    Terminal,
    /** A terminal Hypernova doesn't hold: it can't restart that one, and the dialog says so. */
    OpenElsewhere;

    companion object {
        fun of(s: Session): RestartKind = when {
            s.codex || s.phase == Phase.Ended -> None
            s.agentJob != null -> if (s.agentOutdated != null || s.state != SessionState.Idle || s.agentRunning == true) Job else None
            s.term != null -> Terminal
            s.origin == "terminal" -> OpenElsewhere
            else -> None
        }

        /**
         * Whether it asks first: a turn at work, agents running or an approval waiting would stop, and
         * a terminal open on it on the server would close. At rest and unwatched it just goes.
         */
        fun asksFirst(s: Session): Boolean = s.state != SessionState.Idle || s.agentsRunning > 0 || (s.term?.mac ?: 0) > 0
    }
}

/**
 * What a Home card's long-press menu offers for [Session] (docs/PROTOCOL.md, "Session menu"): Rename
 * for any session; Delete as [DeleteKind] says, Restart session as [RestartKind] does; Stop for a `claude agents` job ([Session.agentJob]) or
 * a Codex session (its turn's interrupt; 2026-10-31). Stop while it works or waits on the owner,
 * or, for a job, idles with its process still running ([Session.agentRunning]; owner, 2026-10-30:
 * idle jobs couldn't be stopped). Not for one stopped or done already, nor, from an older Mac that
 * doesn't say, for an idle one.
 */
data class MenuOffer(val rename: Boolean, val stop: Boolean, val delete: Boolean, val restart: Boolean = false) {
    val any: Boolean get() = rename || stop || delete || restart

    companion object {
        fun of(s: Session): MenuOffer {
            val job = s.agentJob != null
            val live = s.phase != Phase.Ended && (s.state != SessionState.Idle || s.agentRunning == true)
            return MenuOffer(rename = true, stop = (job || s.codex) && live, delete = DeleteKind.of(s) != DeleteKind.None, restart = RestartKind.of(s) != RestartKind.None)
        }
    }
}

/**
 * A Home card's long-press menu: Rename (Claude Code's /rename, so claude agents shows it too),
 * Stop a `claude agents` job or a Codex session, and Delete ([DeleteKind]). Delete asks first, saying
 * what it will close. The Mac's own words come back when it can't (Claude Code keeps a worktree with
 * unpushed work). [start]: the page it opens on (the chat's session sheet lists the actions itself,
 * then opens Rename or Delete here).
 */
@Composable
internal fun SessionMenu(hub: Hub, s: Session, onDismiss: () -> Unit, start: MenuPage = MenuPage.Actions) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    var page by rememberSaveable(s.id) { mutableStateOf(start) }
    when (page) {
        MenuPage.Actions -> GlassSheet(s.name, onDismiss) {
            HText(s.project.uppercase(), style = Type.label, color = HN.inkDim)
            Spacer(Modifier.height(6.dp))
            HText(s.name, style = Type.small, color = HN.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            MenuActions(hub, s, { page = it }, onDismiss)
        }
        MenuPage.Rename -> RenameSheet(hub, s, onDismiss)
        MenuPage.Delete -> when (val kind = DeleteKind.of(s)) {
            // There's nothing to ask: it says why, and what would let it go.
            DeleteKind.OpenElsewhere -> GlassDialog(
                onDismiss = onDismiss,
                title = stringResource(R.string.session_delete_cant_q, s.name),
                line = stringResource(R.string.session_delete_cant_body),
                primary = DialogButton(stringResource(R.string.fl_ok), onDismiss, PillKind.Primary),
            )
            else -> ConfirmDialog(
                visible = true,
                title = stringResource(R.string.session_delete_q, s.name),
                body = stringResource(deleteBody(kind)),
                confirm = stringResource(R.string.delete),
                danger = true,
                onConfirm = {
                    onDismiss()
                    appScope.launch { hub.deleteSession(s.id).onFailure { toast(it.message ?: context.getString(R.string.err_delete_session)) } }
                },
                onDismiss = onDismiss,
            )
        }
        MenuPage.Restart -> RestartConfirm(hub, s, onDismiss)
    }
}

internal enum class MenuPage { Actions, Rename, Delete, Restart }

/** What Delete's confirm says it will do. */
private fun deleteBody(kind: DeleteKind): Int = when (kind) {
    DeleteKind.Codex -> R.string.session_delete_body_codex
    DeleteKind.RunningJob -> R.string.session_delete_body_running
    DeleteKind.Terminal -> R.string.session_delete_body_terminal
    DeleteKind.Forget -> R.string.session_delete_body_plain
    DeleteKind.Job, DeleteKind.None, DeleteKind.OpenElsewhere -> R.string.session_delete_body
}

/**
 * Restart session ([RestartKind]): `claude respawn` for a job, a new shared terminal otherwise. It
 * asks first where something would stop ([RestartKind.asksFirst]), else goes at once with a toast; what
 * it can't restart says why. The Mac's words come back when it can't.
 */
@Composable
private fun RestartConfirm(hub: Hub, s: Session, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val kind = RestartKind.of(s)
    val go = {
        onDismiss()
        appScope.launch {
            (if (kind == RestartKind.Job) hub.respawnSession(s.id) else hub.restartSession(s.id))
                .onSuccess { toast(context.getString(R.string.session_restarting, s.name)) }
                .onFailure { toast(it.message ?: context.getString(R.string.err_restart_session)) }
        }
        Unit
    }
    when {
        kind == RestartKind.OpenElsewhere -> GlassDialog(
            onDismiss = onDismiss,
            title = stringResource(R.string.session_restart_cant_q, s.name),
            line = stringResource(R.string.session_restart_cant_body),
            primary = DialogButton(stringResource(R.string.fl_ok), onDismiss, PillKind.Primary),
        )
        RestartKind.asksFirst(s) -> ConfirmDialog(
            visible = true,
            title = stringResource(R.string.session_restart_q, s.name),
            body = stringResource(if (s.state != SessionState.Idle || s.agentsRunning > 0) R.string.session_restart_body_work else R.string.session_restart_body_watched),
            confirm = stringResource(R.string.session_restart),
            onConfirm = go,
            onDismiss = onDismiss,
        )
        else -> LaunchedEffect(s.id) { go() }
    }
}

/** Home's notice about jobs on an older Claude Code: its key in the list and for its dismissal. */
internal const val OUTDATED_CARD = "jobs:outdated"

/**
 * Home's quiet notice while `claude agents` jobs run an older Claude Code (data/OutdatedJobs.kt):
 * how many, the version installed, and Restart all (Restart for one), asked first since a job at
 * work is interrupted. Each is `claude respawn <job>`, never `--all`. Claude Code's words come back
 * for any it refused. Swiped away, it stays away until another job falls behind.
 */
@Composable
internal fun OutdatedJobsCard(hub: Hub, jobs: List<Session>, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val confirm = LocalConfirm.current
    var busy by remember { mutableStateOf(false) }
    val n = jobs.size
    val ids = jobs.map { it.id }
    val restart = stringResource(if (n == 1) R.string.session_restart else R.string.jobs_restart_all)
    AttentionCard(
        id = OUTDATED_CARD, state = OutdatedJobs.attention(jobs), tone = HN.accentDim, glyph = Glyph.Reload,
        title = pluralStringResource(R.plurals.jobs_outdated_title, n, n),
        line = jobs.firstNotNullOfOrNull { it.agentOutdated?.installed }?.let { stringResource(R.string.jobs_outdated_line, it) },
        action = AttentionAction(restart, {
            if (!busy) confirm(
                Confirm(res.getQuantityString(R.plurals.jobs_restart_q, n, n), res.getString(R.string.jobs_restart_body), restart, danger = false) {
                    busy = true
                    appScope.launch {
                        hub.respawnOutdated(ids)
                            .onSuccess { r ->
                                val refused = r.filter { !it.ok }
                                if (refused.isNotEmpty()) toast(res.getQuantityString(R.plurals.jobs_restart_refused, refused.size, refused.size, refused.first().said).trim())
                            }
                            .onFailure { toast(it.message ?: res.getString(R.string.err_restart_jobs)) }
                        busy = false
                    }
                },
            )
        }),
        modifier = modifier,
    )
}

/**
 * The menu's actions as [MenuOffer] has them, in a sheet: Rename and Delete go to their page
 * ([onPage]), Stop goes at once and closes the sheet ([onDismiss]).
 */
@Composable
internal fun MenuActions(hub: Hub, s: Session, onPage: (MenuPage) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val offer = MenuOffer.of(s)
    val restart: @Composable () -> Unit = {
        SheetAction(ChatGlyph.Restart, stringResource(R.string.session_restart_full), { onPage(MenuPage.Restart) })
        // Under its words: the row's own padding and icon, then its gap.
        HText(stringResource(R.string.session_restart_line), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(start = 38.dp, end = 6.dp, bottom = 6.dp))
    }
    // First on a job that runs an old Claude Code, as the sheet's notice above says so.
    if (offer.restart && s.agentOutdated != null) restart()
    if (offer.rename) SheetAction(ChatGlyph.Edit, stringResource(R.string.session_rename), { onPage(MenuPage.Rename) })
    if (offer.stop) {
        SheetAction(ChatGlyph.Stop, stringResource(R.string.session_stop), {
            onDismiss()
            appScope.launch { hub.stopSession(s.id).onFailure { toast(it.message ?: context.getString(R.string.err_stop_session)) } }
        })
    }
    if (offer.restart && s.agentOutdated == null) restart()
    if (offer.delete) SheetAction(ChatGlyph.Remove, stringResource(R.string.delete), { onPage(MenuPage.Delete) }, color = HN.danger)
}

/** The new name, prefilled with the old one. It stays open while the Mac renames it, and says why when it can't. */
@Composable
private fun RenameSheet(hub: Hub, s: Session, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val appScope = LocalAppScope.current
    var value by rememberSaveable(s.id) { mutableStateOf(s.name) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canSave = value.isNotBlank() && value.trim() != s.name && !saving
    val save = {
        saving = true
        error = null
        appScope.launch {
            hub.renameSession(s.id, value)
                .onSuccess { onDismiss() }
                .onFailure {
                    error = it.message ?: context.getString(R.string.err_rename)
                    saving = false
                }
        }
        Unit
    }
    GlassSheet(stringResource(R.string.session_rename), onDismiss) {
        Field(
            value, { value = it.take(NAME_MAX); error = null },
            label = stringResource(R.string.session_name),
            error = error != null,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            onDone = { if (canSave) save() },
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            HText(it, style = Type.small, color = HN.danger)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Pill(stringResource(R.string.cancel), onDismiss, kind = PillKind.Ghost, height = 44.dp)
            Pill(stringResource(R.string.save), save, kind = PillKind.Primary, height = 44.dp, enabled = canSave)
        }
    }
}

/** The Mac keeps a name to 80 characters (daemon/src/http.ts, "Session menu"). */
private const val NAME_MAX = 80
