package com.prudnikweb.hypernova.desktop.ui

import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DeleteKind
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.MenuOffer
import com.prudnikweb.hypernova.data.SESSION_NAME_MAX
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.desktop.offersRename
import com.prudnikweb.hypernova.desktop.removeBody
import kotlinx.coroutines.launch

/** The tag of the pane's Rename, Stop and Remove, for the tests: the status bar has a Stop of its own. */
const val ACTIONS_TAG = "session-actions"

/**
 * The phone's session menu (ui/home/SessionMenu.kt) as buttons in the pane: Rename, Stop and Remove from list, as
 * [MenuOffer] has them, in its words. Remove asks first and says what it closes; Stop goes at once, as on the phone.
 * The server's words come back when it can't. docs/DECISIONS.md, "The desktop client's Remove from list and Rename".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionActions(hub: Hub, s: Session, shell: Shell, modifier: Modifier = Modifier) {
    val offer = MenuOffer.of(s)
    val rename = offersRename(s, offer)
    if (!rename && !offer.stop && !offer.delete) return
    var renaming by remember(s.id) { mutableStateOf(false) }
    var removing by remember(s.id) { mutableStateOf(false) }
    GlassCard(modifier.testTag(ACTIONS_TAG)) {
        Column(Modifier.padding(16.dp)) {
            if (renaming) RenameForm(hub, s) { renaming = false }
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rename) Pill(Ui.session_rename.text(), { renaming = true }, kind = PillKind.Outline, height = 36.dp)
                if (offer.stop) {
                    Pill(Ui.session_stop.text(), {
                        Core.scope.launch { hub.stopSession(s.id).onFailure { shell.say(it.message ?: Ui.err_stop_session.text()) } }
                    }, kind = PillKind.Outline, height = 36.dp)
                }
                if (offer.delete) Pill(Ui.session_remove.text(), { removing = true }, kind = PillKind.Danger, height = 36.dp)
            }
        }
    }
    if (removing) RemoveConfirm(hub, s, shell) { removing = false }
}

/** Remove from list: what it will close, and that the conversation stays in History. Where nothing can be done from here, why. */
@Composable
private fun RemoveConfirm(hub: Hub, s: Session, shell: Shell, onDismiss: () -> Unit) {
    val kind = DeleteKind.of(s)
    if (kind == DeleteKind.OpenElsewhere) {
        ConfirmModal(Ui.session_delete_cant_q.format(s.name), Ui.session_delete_cant_body.text(), Ui.fl_ok.text(), onDismiss, onDismiss, cancel = null)
        return
    }
    ConfirmModal(
        Ui.session_delete_q.format(s.name), removeBody(kind), Ui.session_remove_short.text(),
        onConfirm = {
            onDismiss()
            Core.scope.launch { hub.deleteSession(s.id).onFailure { shell.say(it.message ?: Ui.err_delete_session.text()) } }
        },
        onDismiss = onDismiss, danger = true,
    )
}

/** The new name, starting from the old one, chosen whole. It stays open while the server renames it, and says why when it can't. */
@Composable
private fun RenameForm(hub: Hub, s: Session, onDone: () -> Unit) {
    var value by remember(s.id) { mutableStateOf(TextFieldValue(s.name, TextRange(0, s.name.length))) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val canSave = value.text.isNotBlank() && value.text.trim() != s.name && !saving
    fun save() {
        if (!canSave) return
        saving = true
        error = null
        Core.scope.launch {
            hub.renameSession(s.id, value.text)
                .onSuccess { onDone() }
                .onFailure {
                    error = it.message ?: Ui.err_rename.text()
                    saving = false
                }
        }
    }
    val shape = RoundedCornerShape(10.dp)
    val label = Ui.session_name.text()
    HText(label.uppercase(), style = Type.label, color = HN.inkDim)
    Spacer(Modifier.height(8.dp))
    BasicTextField(
        value,
        { value = it.copy(text = it.text.take(SESSION_NAME_MAX)); error = null },
        singleLine = true,
        textStyle = Type.body.copy(color = HN.ink),
        cursorBrush = SolidColor(HN.accent),
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { save(); true }
                    Key.Escape -> { onDone(); true }
                    else -> false
                }
            },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().background(HN.bg900.copy(alpha = 0.9f), shape)
                    .border(1.dp, if (error != null) HN.danger else if (focused) HN.accentDim else HN.line, shape)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
            ) { inner() }
        },
    )
    error?.let {
        Spacer(Modifier.height(8.dp))
        HText(it, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = Type.small, color = HN.danger)
    }
    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        Pill(Ui.cancel.text(), onDone, kind = PillKind.Ghost, height = 36.dp)
        Pill(Ui.save.text(), ::save, kind = PillKind.Primary, height = 36.dp, enabled = canSave)
    }
}
