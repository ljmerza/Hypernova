package com.prudnikweb.hypernova.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.graphics.BlurMaskFilter
import android.os.Build
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings
import com.prudnikweb.hypernova.data.id
import com.prudnikweb.hypernova.data.strings
import com.prudnikweb.hypernova.ui.components.EnterKeyPref
import com.prudnikweb.hypernova.ui.components.MermaidBlock
import com.prudnikweb.hypernova.ui.components.phoneEnterKey
import com.prudnikweb.hypernova.ui.platform.ClockStyle
import com.prudnikweb.hypernova.ui.platform.Copier
import com.prudnikweb.hypernova.ui.platform.Haptics
import com.prudnikweb.hypernova.ui.platform.UiClock
import com.prudnikweb.hypernova.ui.platform.UiDevice
import com.prudnikweb.hypernova.ui.platform.UiDiagrams
import com.prudnikweb.hypernova.ui.platform.UiFonts
import com.prudnikweb.hypernova.ui.platform.UiInput
import com.prudnikweb.hypernova.ui.platform.UiPlatform
import com.prudnikweb.hypernova.ui.platform.UiWords
import java.util.Locale

/** The shared drawing on the phone: each call the one its screens always made, so moving them changed nothing. */
object AndroidUiPlatform : UiPlatform {
    override val words: UiWords = PhoneWords
    override val fonts: UiFonts = PhoneFonts
    override val device: UiDevice = PhoneDevice
    override val input: UiInput = PhoneInput
    override val clock: UiClock = PhoneClock
    override val diagrams: UiDiagrams = PhoneDiagrams
}

private object PhoneWords : UiWords {
    /** These Resources' words, the one object while they're the same Resources: what a screen remembers against it stays. */
    private var last: Pair<Resources, Strings>? = null

    @Composable @ReadOnlyComposable
    override fun text(key: Str): String = stringResource(key.id)

    @Composable @ReadOnlyComposable
    override fun text(key: Str, vararg args: Any): String = stringResource(key.id, *args)

    @Composable @ReadOnlyComposable
    override fun quantity(key: Plural, count: Int): String = pluralStringResource(key.id, count)

    @Composable @ReadOnlyComposable
    override fun quantity(key: Plural, count: Int, vararg args: Any): String = pluralStringResource(key.id, count, *args)

    @Composable @ReadOnlyComposable
    override fun strings(): Strings {
        val res = LocalContext.current.resources
        last?.takeIf { it.first === res }?.let { return it.second }
        return res.strings.also { last = res to it }
    }
}

private object PhoneFonts : UiFonts {
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    private fun font(id: Int, weight: Int) = Font(id, weight = FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

    override fun sans(weight: Int): Font = font(R.font.manrope, weight)
    override fun mono(weight: Int): Font = font(R.font.jetbrains_mono, weight)
    override val monoFeatures: String? = null
}

private class ViewHaptics(private val view: View) : Haptics {
    override fun longPress() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    override fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    override fun confirm() { view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY) }
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

private object PhoneDevice : UiDevice {
    @Composable
    override fun haptics(): Haptics {
        val view = LocalView.current
        return remember(view) { ViewHaptics(view) }
    }

    @Composable
    override fun copier(): Copier {
        val context = LocalContext.current
        return remember(context) { ContextCopier(context) }
    }

    @Composable
    override fun windowHeightDp(): Int = LocalConfiguration.current.screenHeightDp

    override fun blurOutside(paint: Paint, radius: Float) {
        // Drawn by the GPU since Android 9; the app needs 10.
        paint.asFrameworkPaint().maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.OUTER)
    }
}

private object PhoneInput : UiInput {
    override val minTouchTarget: Dp = 48.dp

    @Composable
    override fun pillPointer(modifier: Modifier, interaction: MutableInteractionSource, enabled: Boolean): Modifier = modifier

    @Composable
    override fun hovered(interaction: MutableInteractionSource): Boolean = false

    @Composable
    override fun focusRing(interaction: MutableInteractionSource, shape: Shape): Modifier = Modifier

    override fun longPressCopies(modifier: Modifier, key: Any, copy: () -> Unit): Modifier = modifier.pointerInput(key) { detectTapGestures(onLongPress = { copy() }) }

    @Composable
    override fun SelectableOutput(content: @Composable () -> Unit) = content()

    @Composable
    override fun enterSetting(): EnterKey {
        val context = LocalContext.current
        return remember { EnterKeyPref.state(context) }.value
    }

    override fun enterKey(modifier: Modifier, setting: EnterKey, canSend: () -> Boolean, onSend: () -> Unit, shiftEnter: (Boolean) -> Unit): Modifier =
        modifier.phoneEnterKey(setting, canSend, onSend, shiftEnter)
}

private object PhoneClock : UiClock {
    @Composable
    override fun style(): ClockStyle = ClockStyle(LocalConfiguration.current.locales[0], DateFormat.is24HourFormat(LocalContext.current))

    override fun bestDatePattern(locale: Locale, skeleton: String): String = DateFormat.getBestDateTimePattern(locale, skeleton)
}

private object PhoneDiagrams : UiDiagrams {
    @Composable
    override fun Diagram(source: String) = MermaidBlock(source)
}
