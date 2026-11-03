package com.prudnikweb.hypernova.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.EnterDoes
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.data.EnterKeys

/** Settings' Enter key, kept on this computer under the phone's names. One state for the window, as on the phone. */
object EnterKeyPref {
    private const val PREFS = "hypernova_typing"
    private const val KEY = "enter_key"
    private var current: MutableState<EnterKey>? = null

    fun state(): State<EnterKey> = holder()

    fun set(value: EnterKey) {
        Core.platform.storage.prefs(PREFS).edit().putString(KEY, value.name).apply()
        holder().value = value
    }

    private fun holder(): MutableState<EnterKey> = current
        ?: mutableStateOf(EnterKey.of(Core.platform.storage.prefs(PREFS).getString(KEY, null))).also { current = it }
}

@Composable
internal actual fun enterSetting(): EnterKey = EnterKeyPref.state().value

actual fun Modifier.enterKey(setting: EnterKey, canSend: () -> Boolean, onSend: () -> Unit, shiftEnter: (Boolean) -> Unit): Modifier {
    // AWT doesn't count repeats, so a held Enter is known by no key-up since the last press.
    val down = booleanArrayOf(false)
    return onPreviewKeyEvent { e ->
        if (e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        val pressed = e.type == KeyEventType.KeyDown
        when (EnterKeys.decide(setting, e.isShiftPressed, canSend())) {
            EnterDoes.NewLine -> {
                if (setting == EnterKey.Send) shiftEnter(pressed)
                false
            }
            EnterDoes.Send -> {
                if (pressed && !down[0]) onSend()
                down[0] = pressed
                true
            }
            EnterDoes.Nothing -> {
                down[0] = pressed
                true
            }
        }
    }
}
