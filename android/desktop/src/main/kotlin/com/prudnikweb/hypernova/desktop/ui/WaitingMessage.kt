package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.WaitingEdit
import com.prudnikweb.hypernova.data.WaitingTooLate
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.ui.chat.WaitingEditBody
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * The desktop's way to a waiting message ([Item.User.changeable]): a right click opens what a long press opens on
 * the phone, Edit or Remove. What's open is kept above the chat's items: a message taken out of the queue to
 * edit is no longer the item it was.
 */
internal class WaitingMenuState {
    var menu by mutableStateOf<Item.User?>(null)
    var edit by mutableStateOf<WaitingEdit?>(null)
}

/** Right-clicking the row opens [state]'s menu for [item], when it can still be changed. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
internal fun Modifier.waitingMenu(item: Item.User, state: WaitingMenuState): Modifier =
    if (!item.changeable) this else onPointerEvent(PointerEventType.Release) { e ->
        // On the way up, so the menu that opens doesn't take the same click's release.
        if (e.button == PointerButton.Secondary) state.menu = item
    }

/** The menu and the edit card, over the chat. Closed any way but Save, the message goes as it was. */
@Composable
internal fun WaitingMenuHost(hub: Hub, sessionId: String, state: WaitingMenuState, shell: Shell) {
    fun said(e: Throwable) { if (e !is WaitingTooLate) shell.say(e.message.orEmpty()) }
    state.menu?.let { item ->
        Modal({ state.menu = null }) {
            HText(item.text, Modifier.semantics { heading() }, style = Type.small, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                Pill(Ui.cancel.text(), { state.menu = null }, kind = PillKind.Ghost)
                Pill(stringResource(Str.waiting_remove), {
                    state.menu = null
                    Core.scope.launch { hub.removeWaitingMessage(sessionId, item).onFailure(::said) }
                }, kind = PillKind.Danger)
                Pill(stringResource(Str.waiting_edit), {
                    state.menu = null
                    Core.scope.launch { hub.openWaitingEdit(sessionId, item).onSuccess { state.edit = it }.onFailure(::said) }
                }, kind = PillKind.Primary)
            }
        }
    }
    state.edit?.let { edit ->
        var finished by remember(edit) { mutableStateOf(false) }
        DisposableEffect(edit) { onDispose { if (!finished) hub.cancelWaitingEdit(edit) } }
        val cancel = {
            finished = true
            hub.cancelWaitingEdit(edit)
            state.edit = null
        }
        Modal(cancel) {
            WaitingEditBody(
                edit,
                onSave = { words ->
                    finished = true
                    state.edit = null
                    Core.scope.launch { hub.saveWaitingEdit(edit, words).onFailure(::said) }
                },
                onCancel = cancel,
            )
        }
    }
}
