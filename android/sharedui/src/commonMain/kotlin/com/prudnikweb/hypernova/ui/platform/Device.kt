package com.prudnikweb.hypernova.ui.platform

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.ImeAction
import com.prudnikweb.hypernova.data.EnterKey

/* The drawing's short way to UiDevice and UiInput. */

@Composable
fun rememberHaptics(): Haptics = SharedUi.platform.device.haptics()

@Composable
fun rememberCopier(): Copier = SharedUi.platform.device.copier()

@Composable
fun windowHeightDp(): Int = SharedUi.platform.device.windowHeightDp()

internal fun Paint.blurredOutside(radius: Float) = SharedUi.platform.device.blurOutside(this, radius)

@Composable
internal fun Modifier.pillPointer(interaction: MutableInteractionSource, enabled: Boolean): Modifier = SharedUi.platform.input.pillPointer(this, interaction, enabled)

@Composable
internal fun pillHovered(interaction: MutableInteractionSource): Boolean = SharedUi.platform.input.hovered(interaction)

@Composable
internal fun pillFocusRing(interaction: MutableInteractionSource, shape: Shape): Modifier = SharedUi.platform.input.focusRing(interaction, shape)

internal fun Modifier.longPressCopies(key: Any, copy: () -> Unit): Modifier = SharedUi.platform.input.longPressCopies(this, key, copy)

@Composable
internal fun SelectableOutput(content: @Composable () -> Unit) = SharedUi.platform.input.SelectableOutput(content)

@Composable
internal fun enterSetting(): EnterKey = SharedUi.platform.input.enterSetting()

/** The soft keyboard's Enter key for [setting]: Send, or the usual new line. */
fun EnterKey.imeAction(): ImeAction = if (this == EnterKey.Send) ImeAction.Send else ImeAction.Default

/** A hardware Enter in a box that sends ([UiInput.enterKey]). */
fun Modifier.enterKey(setting: EnterKey, canSend: () -> Boolean, onSend: () -> Unit, shiftEnter: (Boolean) -> Unit = {}): Modifier =
    SharedUi.platform.input.enterKey(this, setting, canSend, onSend, shiftEnter)

@Composable
internal fun DiagramBlock(source: String) = SharedUi.platform.diagrams.Diagram(source)
