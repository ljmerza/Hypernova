package com.prudnikweb.hypernova.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BlurMaskFilter
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView

private class ViewHaptics(private val view: View) : Haptics {
    override fun longPress() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    override fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    override fun confirm() { view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY) }
}

@Composable
actual fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { ViewHaptics(view) }
}

private class ContextCopier(private val context: Context) : Copier {
    override fun copy(label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    // Android 13 and newer show their own "copied" preview.
    override fun sayCopied(words: String) {
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, words, Toast.LENGTH_SHORT).show()
    }
}

@Composable
actual fun rememberCopier(): Copier {
    val context = LocalContext.current
    return remember(context) { ContextCopier(context) }
}

@Composable
actual fun windowHeightDp(): Int = LocalConfiguration.current.screenHeightDp

@Composable
actual fun Modifier.pillPointer(interaction: MutableInteractionSource, enabled: Boolean): Modifier = this

@Composable
actual fun pillFocusRing(interaction: MutableInteractionSource, shape: Shape): Modifier = Modifier

internal actual fun Paint.blurredOutside(radius: Float) {
    // Drawn by the GPU since Android 9; the app needs 10.
    asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER)
}

actual fun Modifier.longPressCopies(key: Any, copy: () -> Unit): Modifier = pointerInput(key) { detectTapGestures(onLongPress = { copy() }) }

@Composable
internal actual fun DiagramBlock(source: String) = com.prudnikweb.hypernova.ui.theme.AndroidUi.diagram(source)
