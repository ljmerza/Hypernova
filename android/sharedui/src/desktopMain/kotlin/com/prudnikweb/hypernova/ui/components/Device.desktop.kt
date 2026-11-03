package com.prudnikweb.hypernova.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.theme.HN
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

private object NoHaptics : Haptics {
    override fun longPress() {}
    override fun tick() {}
    override fun confirm() {}
}

@Composable
actual fun rememberHaptics(): Haptics = NoHaptics

private object AwtCopier : Copier {
    // No clipboard (no display): nothing to copy to, and nothing worth stopping for.
    override fun copy(label: String, text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override fun sayCopied(words: String) {}
}

@Composable
actual fun rememberCopier(): Copier = AwtCopier

@Composable
actual fun windowHeightDp(): Int {
    val px = LocalWindowInfo.current.containerSize.height
    return (px / LocalDensity.current.density).toInt()
}

@Composable
actual fun Modifier.pillPointer(interaction: MutableInteractionSource, enabled: Boolean): Modifier =
    pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default).hoverable(interaction, enabled)

@Composable
actual fun pillFocusRing(interaction: MutableInteractionSource, shape: Shape): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    return if (focused) Modifier.border(1.5.dp, HN.ink, shape) else Modifier
}

internal actual fun Paint.blurredOutside(radius: Float) {
    skiaPaint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.OUTER, radius / 2f)
}
