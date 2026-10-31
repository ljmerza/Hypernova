package com.prudnikweb.hypernova.ui.components

import android.content.Context
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import com.prudnikweb.hypernova.data.EnterDoes
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.data.EnterKeys

/**
 * Settings → Typing → Enter key ([EnterKey]), kept on this phone. One state for the whole app, so a
 * change reaches every open box at once, as TextSize does.
 */
object EnterKeyPref {
    private const val PREFS = "hypernova_typing"
    private const val KEY = "enter_key"
    private var current: MutableState<EnterKey>? = null

    fun state(context: Context): State<EnterKey> = holder(context)

    fun set(context: Context, value: EnterKey) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
        holder(context).value = value
    }

    private fun holder(context: Context): MutableState<EnterKey> = current
        ?: mutableStateOf(EnterKey.of(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))).also { current = it }
}

/** The soft keyboard's Enter key for [setting]: Send, or the usual new line. */
fun EnterKey.imeAction(): ImeAction = if (this == EnterKey.Send) ImeAction.Send else ImeAction.Default

/**
 * A hardware Enter (or a soft keyboard's that comes as a key) in a box that sends, decided by
 * [EnterKeys.decide]: a send happens on the key's press, once (not while it repeats); Nothing
 * swallows it. [shiftEnter] is told when a Shift+Enter is let through to the field in Send mode, so
 * the box's new-line guard lets that one new line in.
 */
fun Modifier.enterKey(setting: EnterKey, canSend: () -> Boolean, onSend: () -> Unit, shiftEnter: (Boolean) -> Unit = {}): Modifier = onPreviewKeyEvent { e ->
    val code = e.nativeKeyEvent.keyCode
    if (code != AndroidKeyEvent.KEYCODE_ENTER && code != AndroidKeyEvent.KEYCODE_NUMPAD_ENTER) return@onPreviewKeyEvent false
    val down = e.type == KeyEventType.KeyDown
    when (EnterKeys.decide(setting, e.isShiftPressed, canSend())) {
        EnterDoes.NewLine -> {
            if (setting == EnterKey.Send) shiftEnter(down)
            false
        }
        EnterDoes.Send -> {
            if (down && e.nativeKeyEvent.repeatCount == 0) onSend()
            true
        }
        EnterDoes.Nothing -> true
    }
}
