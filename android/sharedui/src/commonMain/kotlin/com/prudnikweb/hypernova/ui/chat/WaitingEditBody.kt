package com.prudnikweb.hypernova.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.WaitingEdit
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.platform.stringResource

/**
 * A waiting message's words being changed, with Cancel and Save: inside the phone's sheet and the desktop's
 * card. While this is up nothing sends it ([com.prudnikweb.hypernova.data.Hub.openWaitingEdit]).
 */
@Composable
fun WaitingEditBody(edit: WaitingEdit, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var value by remember(edit) { mutableStateOf(edit.text) }
    Field(
        value, { value = it },
        singleLine = false, maxLines = 6, commands = true,
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        label = stringResource(Str.waiting_edit),
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        Pill(stringResource(Str.cancel), onCancel, kind = PillKind.Ghost, height = 44.dp)
        Pill(stringResource(Str.waiting_save), { onSave(value) }, kind = PillKind.Primary, height = 44.dp, enabled = value.isNotBlank() || edit.emptyOk)
    }
}
