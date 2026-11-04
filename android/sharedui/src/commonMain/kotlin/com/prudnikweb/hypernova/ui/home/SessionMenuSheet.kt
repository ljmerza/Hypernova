package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.ui.platform.wordsNow

import com.prudnikweb.hypernova.data.Str
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
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.SESSION_NAME_MAX
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.ConfirmDialog
import com.prudnikweb.hypernova.ui.components.DialogButton
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

// What the menu offers is plain Kotlin, in `:shared` so the desktop offers the same (data/SessionMenu.kt).
typealias DeleteKind = com.prudnikweb.hypernova.data.DeleteKind
typealias RestartKind = com.prudnikweb.hypernova.data.RestartKind
typealias MenuOffer = com.prudnikweb.hypernova.data.MenuOffer

/**
 * A Home card's long-press menu: Rename (Claude Code's /rename, so claude agents shows it too),
 * Stop a `claude agents` job or a Codex session, and Remove from list ([DeleteKind]), which asks first,
 * saying what it will close and that the conversation stays in History. The Mac's own words come back when it can't (Claude Code keeps a worktree with
 * unpushed work). [start]: the page it opens on (the chat's session sheet lists the actions itself,
 * then opens Rename or Delete here).
 */
@Composable
internal fun SessionMenu(hub: Hub, s: Session, onDismiss: () -> Unit, start: MenuPage = MenuPage.Actions) {
    val res = wordsNow()
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    var page by rememberSaveable(s.id) { mutableStateOf(start) }
    when (page) {
        MenuPage.Actions -> GlassSheet(s.name, onDismiss) {
            HText(s.project.uppercase(), style = Type.label, color = HN.inkDim)
            Spacer(Modifier.height(6.dp))
            HText(s.name, style = Type.small, color = HN.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            MenuActions(hub, s, { page = it }, onDismiss, mcp = true)
        }
        MenuPage.Rename -> RenameSheet(hub, s, onDismiss)
        MenuPage.Delete -> when (val kind = DeleteKind.of(s)) {
            // There's nothing to ask: it says why, and what would let it go.
            DeleteKind.OpenElsewhere -> GlassDialog(
                onDismiss = onDismiss,
                title = stringResource(Str.session_delete_cant_q, s.name),
                line = stringResource(Str.session_delete_cant_body),
                primary = DialogButton(stringResource(Str.fl_ok), onDismiss, PillKind.Primary),
            )
            else -> ConfirmDialog(
                visible = true,
                title = stringResource(Str.session_delete_q, s.name),
                body = stringResource(deleteBody(kind)),
                confirm = stringResource(Str.session_remove_short),
                danger = true,
                onConfirm = {
                    onDismiss()
                    appScope.launch { hub.deleteSession(s.id).onFailure { toast(it.message ?: res.getString(Str.err_delete_session)) } }
                },
                onDismiss = onDismiss,
            )
        }
        MenuPage.Restart -> RestartConfirm(hub, s, onDismiss)
        MenuPage.Mcp -> SessionMcpSheet(hub, s, onDismiss)
    }
}

internal enum class MenuPage { Actions, Rename, Delete, Restart, Mcp }

/** What Delete's confirm says it will do. */
private fun deleteBody(kind: DeleteKind): Str = when (kind) {
    DeleteKind.Codex -> Str.session_delete_body_codex
    DeleteKind.RunningJob -> Str.session_delete_body_running
    DeleteKind.Terminal -> Str.session_delete_body_terminal
    DeleteKind.Forget -> Str.session_delete_body_plain
    DeleteKind.Job, DeleteKind.None, DeleteKind.OpenElsewhere -> Str.session_delete_body
}

/**
 * Restart session ([RestartKind]): `claude respawn` for a job, a new shared terminal otherwise. It
 * asks first where something would stop ([RestartKind.asksFirst]), else goes at once with a toast; what
 * it can't restart says why. The Mac's words come back when it can't.
 */
@Composable
private fun RestartConfirm(hub: Hub, s: Session, onDismiss: () -> Unit) {
    val res = wordsNow()
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val kind = RestartKind.of(s)
    val go = {
        onDismiss()
        appScope.launch {
            (if (kind == RestartKind.Job) hub.respawnSession(s.id) else hub.restartSession(s.id))
                .onSuccess { toast(res.getString(Str.session_restarting, s.name)) }
                .onFailure { toast(it.message ?: res.getString(Str.err_restart_session)) }
        }
        Unit
    }
    when {
        kind == RestartKind.OpenElsewhere -> GlassDialog(
            onDismiss = onDismiss,
            title = stringResource(Str.session_restart_cant_q, s.name),
            line = stringResource(Str.session_restart_cant_body),
            primary = DialogButton(stringResource(Str.fl_ok), onDismiss, PillKind.Primary),
        )
        RestartKind.asksFirst(s) -> ConfirmDialog(
            visible = true,
            title = stringResource(Str.session_restart_q, s.name),
            body = stringResource(if (s.state != SessionState.Idle || s.agentsRunning > 0) Str.session_restart_body_work else Str.session_restart_body_watched),
            confirm = stringResource(Str.session_restart),
            onConfirm = go,
            onDismiss = onDismiss,
        )
        else -> LaunchedEffect(s.id) { go() }
    }
}

/**
 * The menu's actions as [MenuOffer] has them, in a sheet: Rename and Delete go to their page
 * ([onPage]), Stop goes at once and closes the sheet ([onDismiss]). [mcp]: MCP servers too, which the
 * Home card's menu has and the chat's session sheet doesn't.
 */
@Composable
internal fun MenuActions(hub: Hub, s: Session, onPage: (MenuPage) -> Unit, onDismiss: () -> Unit, mcp: Boolean = false) {
    val res = wordsNow()
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val offer = MenuOffer.of(s)
    val restart: @Composable () -> Unit = {
        SheetAction(ChatGlyph.Restart, stringResource(Str.session_restart_full), { onPage(MenuPage.Restart) })
        // Under its words: the row's own padding and icon, then its gap.
        HText(stringResource(Str.session_restart_line), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(start = 38.dp, end = 6.dp, bottom = 6.dp))
    }
    // First on a job that runs an old Claude Code, as the sheet's notice above says so.
    if (offer.restart && s.agentOutdated != null) restart()
    if (offer.rename) SheetAction(ChatGlyph.Edit, stringResource(Str.session_rename), { onPage(MenuPage.Rename) })
    if (mcp && offer.mcp) SheetAction(ChatGlyph.Servers, stringResource(Str.as_mcp), { onPage(MenuPage.Mcp) })
    if (offer.stop) {
        SheetAction(ChatGlyph.Stop, stringResource(Str.session_stop), {
            onDismiss()
            appScope.launch { hub.stopSession(s.id).onFailure { toast(it.message ?: res.getString(Str.err_stop_session)) } }
        })
    }
    if (offer.restart && s.agentOutdated == null) restart()
    if (offer.delete) SheetAction(ChatGlyph.Remove, stringResource(Str.session_remove), { onPage(MenuPage.Delete) }, color = HN.danger)
}

/** The new name, prefilled with the old one. It stays open while the Mac renames it, and says why when it can't. */
@Composable
private fun RenameSheet(hub: Hub, s: Session, onDismiss: () -> Unit) {
    val res = wordsNow()
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
                    error = it.message ?: res.getString(Str.err_rename)
                    saving = false
                }
        }
        Unit
    }
    GlassSheet(stringResource(Str.session_rename), onDismiss) {
        Field(
            value, { value = it.take(NAME_MAX); error = null },
            label = stringResource(Str.session_name),
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
            Pill(stringResource(Str.cancel), onDismiss, kind = PillKind.Ghost, height = 44.dp)
            Pill(stringResource(Str.save), save, kind = PillKind.Primary, height = 44.dp, enabled = canSave)
        }
    }
}

private const val NAME_MAX = SESSION_NAME_MAX
