package com.prudnikweb.hypernova.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.SendStatus
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * A prompt waiting for the Mac (data/OfflineQueue.kt): Edit its words, Remove it, Copy it, and,
 * once its session is gone, Start a new session with it. Reached by a long-press on its bubble.
 */
@Composable
internal fun WaitingMenu(hub: Hub, item: Item.User, onDismiss: () -> Unit) {
    val id = item.waitingId ?: return onDismiss()
    val context = LocalContext.current
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val opener = LocalOpener.current
    var editing by rememberSaveable(id) { mutableStateOf(false) }
    if (editing) {
        var value by rememberSaveable(id) { mutableStateOf(item.text) }
        ChatSheet(stringResource(R.string.waiting_edit), onDismiss) {
            Field(
                value, { value = it },
                singleLine = false, maxLines = 6, commands = true,
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                label = stringResource(R.string.waiting_edit),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Pill(stringResource(R.string.cancel), onDismiss, kind = PillKind.Ghost, height = 44.dp)
                Pill(
                    stringResource(R.string.waiting_save),
                    { hub.editWaiting(id, value); onDismiss() },
                    kind = PillKind.Primary, height = 44.dp, enabled = value.isNotBlank() || item.files.isNotEmpty(),
                )
            }
        }
        return
    }
    ChatSheet(stringResource(R.string.chat_menu_title), onDismiss) {
        HText(stringResource(R.string.you).uppercase(), style = Type.label, color = HN.inkDim)
        Spacer(Modifier.height(6.dp))
        HText(item.text, style = Type.small, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        if (item.status == SendStatus.SessionGone) {
            SheetAction(ChatGlyph.NewSession, stringResource(R.string.waiting_start_new), {
                onDismiss()
                appScope.launch { hub.startFromWaiting(id).onSuccess { opener.switchSession(it) }.onFailure { toast(it.message.orEmpty()) } }
            })
        }
        SheetAction(ChatGlyph.Edit, stringResource(R.string.waiting_edit), { editing = true })
        SheetAction(ChatGlyph.Copy, stringResource(R.string.chat_copy), {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_copy), item.text))
            if (android.os.Build.VERSION.SDK_INT < 33) toast(context.getString(R.string.chat_copied))
            onDismiss()
        })
        SheetAction(ChatGlyph.Remove, stringResource(R.string.waiting_remove), { hub.removeWaiting(id); onDismiss() }, color = HN.danger)
    }
}
