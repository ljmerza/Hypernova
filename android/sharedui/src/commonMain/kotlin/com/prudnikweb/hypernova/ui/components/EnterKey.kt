package com.prudnikweb.hypernova.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.prudnikweb.hypernova.data.EnterKey

/** Settings' Enter key on this device (phone: EnterKeyPref): one state for the app, so a change reaches every open box at once. */
@Composable
internal expect fun enterSetting(): EnterKey

/** The soft keyboard's Enter key for [setting]: Send, or the usual new line. */
fun EnterKey.imeAction(): ImeAction = if (this == EnterKey.Send) ImeAction.Send else ImeAction.Default

/**
 * A hardware Enter (or a soft keyboard's that comes as a key) in a box that sends, decided by [com.prudnikweb.hypernova.data.EnterKeys.decide]:
 * a send happens on the key's press, once (not while it repeats); Nothing swallows it. [shiftEnter] is told when a Shift+Enter is let
 * through to the field in Send mode, so the box's new-line guard lets that one new line in.
 */
expect fun Modifier.enterKey(setting: EnterKey, canSend: () -> Boolean, onSend: () -> Unit, shiftEnter: (Boolean) -> Unit = {}): Modifier
