// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.SendStatus
import com.prudnikweb.hypernova.data.WaitingEdit
import com.prudnikweb.hypernova.data.WaitingTooLate
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.chat.WaitingEditBody
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * A message still waiting ([Item.User.changeable]): on this phone for the Mac (data/OfflineQueue.kt), held by
 * the Mac (data/Held.kt), or in the session's queue: Edit its words, Remove it, Copy it, and, once its session is
 * gone, Start a new session with it. Reached by a long-press on its bubble. Edit stops it being sent first
 * ([Hub.openWaitingEdit]) and then opens [WaitingEditSheet] through [onEdit].
 */
@Composable
internal fun WaitingMenu(hub: Hub, item: Item.User, onDismiss: () -> Unit, sessionId: String? = null, onEdit: (WaitingEdit) -> Unit = {}) {
    val session = sessionId.orEmpty()
    val context = LocalContext.current
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val opener = LocalOpener.current
    // Too late: the chat says so already.
    fun said(e: Throwable) { if (e !is WaitingTooLate) toast(e.message.orEmpty()) }
    ChatSheet(stringResource(R.string.chat_menu_title), onDismiss) {
        HText(stringResource(R.string.you).uppercase(), style = Type.label, color = HN.inkDim)
        Spacer(Modifier.height(6.dp))
        HText(item.text, style = Type.small, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        if (item.status == SendStatus.SessionGone) {
            val id = item.waitingId
            if (id != null) SheetAction(ChatGlyph.NewSession, stringResource(R.string.waiting_start_new), {
                onDismiss()
                appScope.launch { hub.startFromWaiting(id).onSuccess { opener.switchSession(it) }.onFailure { toast(it.message.orEmpty()) } }
            })
        }
        SheetAction(ChatGlyph.Edit, stringResource(R.string.waiting_edit), {
            onDismiss()
            appScope.launch { hub.openWaitingEdit(session, item).onSuccess(onEdit).onFailure(::said) }
        })
        SheetAction(ChatGlyph.Copy, stringResource(R.string.chat_copy), {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_copy), item.text))
            if (android.os.Build.VERSION.SDK_INT < 33) toast(context.getString(R.string.chat_copied))
            onDismiss()
        })
        SheetAction(ChatGlyph.Remove, stringResource(R.string.waiting_remove), {
            onDismiss()
            appScope.launch { hub.removeWaitingMessage(session, item).onFailure(::said) }
        }, color = HN.danger)
    }
}

/**
 * The sheet that changes a waiting message's words. Closed any way but Save, the message goes as it was.
 * It lives above the chat's items: a message taken out of the queue to edit is no longer the item it was.
 */
@Composable
internal fun WaitingEditSheet(hub: Hub, edit: WaitingEdit, onClose: () -> Unit) {
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    var finished by remember(edit) { mutableStateOf(false) }
    DisposableEffect(edit) { onDispose { if (!finished) hub.cancelWaitingEdit(edit) } }
    val cancel = {
        finished = true
        hub.cancelWaitingEdit(edit)
        onClose()
    }
    ChatSheet(stringResource(R.string.waiting_edit), cancel) {
        WaitingEditBody(
            edit,
            onSave = { words ->
                finished = true
                onClose()
                appScope.launch { hub.saveWaitingEdit(edit, words).onFailure { if (it !is WaitingTooLate) toast(it.message.orEmpty()) } }
            },
            onCancel = cancel,
        )
    }
}
