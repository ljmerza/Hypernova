package com.prudnikweb.hypernova.ui.components

import com.prudnikweb.hypernova.ui.platform.enterSetting
import com.prudnikweb.hypernova.ui.platform.enterKey
import com.prudnikweb.hypernova.ui.platform.imeAction
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.runtime.Stable
import com.prudnikweb.hypernova.data.CommandRun
import com.prudnikweb.hypernova.ui.theme.JetBrainsMono
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

/** Quiet "something is loading" line: live bars and a label. */
@Composable
fun LoadingLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Planet(Activity.Live)
        Spacer(Modifier.width(10.dp))
        HText(text, style = Type.small, color = HN.inkMuted)
    }
}

/** A text field's value (text, cursor, composing text) kept for a String someone else owns: see [rememberFieldValue]. */
@Stable
class FieldValue(initial: TextFieldValue) {
    private var held = initial
    private var tick by mutableIntStateOf(0)
    var value: TextFieldValue
        get() = tick.let { held }
        set(v) { held = v; tick++ }
    /** The text changed from outside (a command put in, a draft given back): the cursor goes to its end. */
    fun sync(text: String) {
        if (held.text != text) held = TextFieldValue(text, TextRange(text.length))
    }
}

/**
 * A text field's value for a String the caller owns: the cursor and the keyboard's composing
 * text stay where they are while typing, and the cursor goes to the end when the text is
 * changed from outside (with a plain String field it would stay where it was).
 */
@Composable
fun rememberFieldValue(text: String): FieldValue {
    val f = remember { FieldValue(TextFieldValue(text, TextRange(text.length))) }
    f.sync(text)
    return f
}

/**
 * A prompt being typed, with the command it starts with ("/effort", "/hyper-cosmos", bash mode's
 * "!") marked as Claude Code will read it: mono mint on a faint mint wash. Only that token; the
 * text and its offsets stay as typed, so the cursor and selection are untouched.
 */
object CommandHighlight : VisualTransformation {
    /** The mark, also drawn by the session's prompt box (its TextFieldState field, SessionScreen.kt). */
    internal val Lead = SpanStyle(fontFamily = JetBrainsMono, color = HN.accent, background = HN.accent.copy(alpha = 0.10f))

    override fun filter(text: AnnotatedString): TransformedText {
        val at = CommandRun.lead(text.text) ?: return TransformedText(text, OffsetMapping.Identity)
        val marked = AnnotatedString.Builder(text).apply { addStyle(Lead, at.first, at.last + 1) }.toAnnotatedString()
        return TransformedText(marked, OffsetMapping.Identity)
    }
}

/**
 * Single-line or multi-line text input in the house style (bg800 well, ink 10% ring).
 * [commands]: a prompt for Claude Code, its leading "/command" marked ([CommandHighlight]).
 * [onSend]: a box that sends (or submits) as a whole; with Settings' Enter key on Send (EnterKeyPref)
 * the keyboard's Enter is [onSend] while [canSend], else nothing, and a hardware Shift+Enter is a
 * new line. On New line, Enter is a new line as in any box.
 */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 8,
    mono: Boolean = false,
    secret: Boolean = false,
    error: Boolean = false,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    label: String? = null,
    onDone: (() -> Unit)? = null,
    commands: Boolean = false,
    onSend: (() -> Unit)? = null,
    canSend: Boolean = true,
) {
    val style: TextStyle = (if (mono) Type.mono.copy(fontSize = Type.body.fontSize, lineHeight = Type.body.lineHeight) else Type.body).copy(color = HN.ink)
    val shape = RoundedCornerShape(14.dp)
    val field = rememberFieldValue(value)
    val sends = onSend != null && enterSetting() == com.prudnikweb.hypernova.data.EnterKey.Send
    // A Shift+Enter let through to the field: its one new line goes in.
    val shiftEnter = remember { booleanArrayOf(false) }
    val canSendNow by androidx.compose.runtime.rememberUpdatedState(canSend)
    val sendNow by androidx.compose.runtime.rememberUpdatedState(onSend)
    BasicTextField(
        value = field.value,
        onValueChange = change@{
            // A keyboard that types a new line for Enter instead of pressing Send: it's Enter.
            if (sends && !shiftEnter[0] && com.prudnikweb.hypernova.data.EnterKeys.typedNewline(field.value.text, it.text)) {
                if (canSend) onSend?.invoke()
                return@change
            }
            shiftEnter[0] = false
            field.value = it; if (it.text != value) onValueChange(it.text)
        },
        textStyle = style,
        cursorBrush = SolidColor(HN.accent),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = (if (secret) keyboard.copy(keyboardType = KeyboardType.Password) else keyboard)
            .let { if (sends) it.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Send) else it },
        visualTransformation = when {
            secret -> PasswordVisualTransformation()
            commands -> CommandHighlight
            else -> VisualTransformation.None
        },
        keyboardActions = when {
            sends -> KeyboardActions(onSend = { if (canSend) onSend?.invoke() })
            onDone != null -> KeyboardActions(onDone = { onDone() })
            else -> KeyboardActions.Default
        },
        modifier = modifier
            .then(if (sends) Modifier.enterKey(com.prudnikweb.hypernova.data.EnterKey.Send, { canSendNow }, { sendNow?.invoke() }, { shiftEnter[0] = it }) else Modifier)
            .fillMaxWidth()
            .semantics { if (label != null) contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(HN.bg800.copy(alpha = 0.6f))
                    .border(1.dp, if (error) HN.danger.copy(alpha = 0.6f) else HN.ink.copy(alpha = 0.10f), shape)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                // A box of several lines starts its words, and its hint, at the top, where the cursor is.
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    HText(placeholder, style = style, color = HN.inkGhost, maxLines = maxLines)
                }
                inner()
            }
        },
    )
}

/** Radio circle: 18dp ring, 10dp fill. */
@Composable
fun Radio(selected: Boolean, color: Color = HN.accent) {
    val fill by animateColorAsState(if (selected) color else HN.bg.copy(alpha = 0f), tween(Motion.FAST), label = "r")
    Box(
        Modifier.size(18.dp).clip(CircleShape).border(1.dp, if (selected) color else HN.lineStrong, CircleShape).padding(4.dp),
    ) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(fill))
    }
}

/** Checkbox for multi-select: a rounded square that fills with a drawn check. */
@Composable
fun CheckBox(checked: Boolean, color: Color = HN.accent) {
    val fill by animateColorAsState(if (checked) color else HN.bg.copy(alpha = 0f), tween(Motion.FAST), label = "c")
    Box(
        Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(fill).border(1.dp, if (checked) color else HN.lineStrong, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Glyph.Check, size = 14.dp, color = HN.bg, stroke = 1.8.dp)
    }
}
