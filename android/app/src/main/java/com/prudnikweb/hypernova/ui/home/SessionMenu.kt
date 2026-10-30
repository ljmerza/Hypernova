package com.prudnikweb.hypernova.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.ConfirmDialog
import com.prudnikweb.hypernova.ui.components.Field
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
 * What a Home card's long-press menu offers for [Session] (docs/PROTOCOL.md, "Session menu"): Rename
 * for any session but Codex's; Stop and Delete only for a `claude agents` job ([Session.agentJob]),
 * Stop while it works or waits on the owner, or idles with its process still running
 * ([Session.agentRunning]; owner, 2026-10-30: idle jobs couldn't be stopped). Not for one stopped or
 * done already, nor, from an older Mac that doesn't say, for an idle one.
 */
data class MenuOffer(val rename: Boolean, val stop: Boolean, val delete: Boolean) {
    val any: Boolean get() = rename || stop || delete

    companion object {
        fun of(s: Session): MenuOffer {
            val job = s.agentJob != null
            val live = s.phase != Phase.Ended && (s.state != SessionState.Idle || s.agentRunning == true)
            return MenuOffer(rename = !s.codex, stop = job && live, delete = job)
        }
    }
}

/**
 * A Home card's long-press menu: Rename (Claude Code's /rename, so claude agents shows it too),
 * Stop and Delete a `claude agents` job. Delete asks first. The Mac's own words come back when it
 * can't (Claude Code keeps a worktree with unpushed work). [start]: the page it opens on (the chat's
 * session sheet lists the actions itself, then opens Rename or Delete here).
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
        MenuPage.Delete -> ConfirmDialog(
            visible = true,
            title = stringResource(R.string.session_delete_q, s.name),
            body = stringResource(R.string.session_delete_body),
            confirm = stringResource(R.string.delete),
            danger = true,
            onConfirm = {
                onDismiss()
                appScope.launch { hub.deleteSession(s.id).onFailure { toast(it.message ?: context.getString(R.string.err_delete_session)) } }
            },
            onDismiss = onDismiss,
        )
    }
}

internal enum class MenuPage { Actions, Rename, Delete }

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
    if (offer.rename) SheetAction(ChatGlyph.Edit, stringResource(R.string.session_rename), { onPage(MenuPage.Rename) })
    if (offer.stop) {
        SheetAction(ChatGlyph.Stop, stringResource(R.string.session_stop), {
            onDismiss()
            appScope.launch { hub.stopSession(s.id).onFailure { toast(it.message ?: context.getString(R.string.err_stop_session)) } }
        })
    }
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
