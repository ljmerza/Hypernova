package com.prudnikweb.hypernova.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.skiaPaint
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.ComputerWords
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.EnterDoes
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.data.EnterKeys
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings
import com.prudnikweb.hypernova.ui.components.CodeBlock
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
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter

/** The shared drawing on a computer: a pointer and a keyboard, no vibrations, this app's own copies of the fonts and the words. */
object DesktopUiPlatform : UiPlatform {
    override val words: UiWords = DeskWords
    override val fonts: UiFonts = DeskFonts
    override val device: UiDevice = DeskDevice
    override val input: UiInput = DeskInput
    override val clock: UiClock = DeskClock
    override val diagrams: UiDiagrams = DeskDiagrams
}

/** The phone's words from DesktopStrings, reworded for the computer on screen as the phone's Resources are. */
private object DeskWords : UiWords {
    /** The last computer's words: it changes when another server says what it is, which is seldom. */
    private var worded: Pair<String, Strings>? = null

    @Composable @ReadOnlyComposable
    override fun strings(): Strings {
        val computer = ComputerWords.platform
        worded?.takeIf { it.first == computer }?.let { return it.second }
        return ComputerWords.wordedFor(Core.platform.strings, computer).also { worded = computer to it }
    }

    @Composable @ReadOnlyComposable
    override fun text(key: Str): String = strings().getString(key)

    @Composable @ReadOnlyComposable
    override fun text(key: Str, vararg args: Any): String = strings().getString(key, *args)

    @Composable @ReadOnlyComposable
    override fun quantity(key: Plural, count: Int): String = strings().getQuantityString(key, count)

    @Composable @ReadOnlyComposable
    override fun quantity(key: Plural, count: Int, vararg args: Any): String = strings().getQuantityString(key, count, *args)
}

/** Loaded from the resources the build copies from the app's res/font (their licence: third_party/fonts). */
private object DeskFonts : UiFonts {
    @OptIn(ExperimentalTextApi::class)
    private fun font(file: String, weight: Int): Font = Font(
        identity = "$file-$weight",
        getData = { checkNotNull(DeskFonts::class.java.classLoader.getResourceAsStream("fonts/$file")) { "no font $file" }.use { it.readBytes() } },
        weight = FontWeight(weight),
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    override fun sans(weight: Int): Font = font("manrope.ttf", weight)
    override fun mono(weight: Int): Font = font("jetbrains_mono.ttf", weight)

    /** JetBrains Mono joins `--`, `->` and such by default: a command must read as the characters it is. */
    override val monoFeatures: String? = "liga 0, calt 0"
}

private object NoHaptics : Haptics {
    override fun longPress() {}
    override fun tick() {}
    override fun confirm() {}
}

private object AwtCopier : Copier {
    // No clipboard (no display): nothing to copy to, and nothing worth stopping for.
    override fun copy(label: String, text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override fun sayCopied(words: String) {}
}

private object DeskDevice : UiDevice {
    @Composable
    override fun haptics(): Haptics = NoHaptics

    @Composable
    override fun copier(): Copier = AwtCopier

    @Composable
    override fun windowHeightDp(): Int = (LocalWindowInfo.current.containerSize.height / LocalDensity.current.density).toInt()

    override fun blurOutside(paint: Paint, radius: Float) {
        paint.skiaPaint.maskFilter = MaskFilter.makeBlur(FilterBlurMode.OUTER, radius / 2f)
    }
}

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

private object DeskInput : UiInput {
    /** A pointer is exact: a pill is as tall as it's drawn. */
    override val minTouchTarget: Dp = 0.dp

    @Composable
    override fun pillPointer(modifier: Modifier, interaction: MutableInteractionSource, enabled: Boolean): Modifier =
        modifier.pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default).hoverable(interaction, enabled)

    @Composable
    override fun hovered(interaction: MutableInteractionSource): Boolean {
        val over by interaction.collectIsHoveredAsState()
        return over
    }

    @Composable
    override fun focusRing(interaction: MutableInteractionSource, shape: Shape): Modifier {
        val focused by interaction.collectIsFocusedAsState()
        return if (focused) Modifier.border(1.5.dp, HN.ink, shape) else Modifier
    }

    /** A mouse selects: a held press is the start of a selection, not a copy. Selecting and Ctrl+C copy a block here. */
    override fun longPressCopies(modifier: Modifier, key: Any, copy: () -> Unit): Modifier = modifier

    @Composable
    override fun SelectableOutput(content: @Composable () -> Unit) = SelectionContainer(content = content)

    @Composable
    override fun enterSetting(): EnterKey = EnterKeyPref.state().value

    override fun enterKey(modifier: Modifier, setting: EnterKey, canSend: () -> Boolean, onSend: () -> Unit, shiftEnter: (Boolean) -> Unit): Modifier {
        // AWT doesn't count repeats, so a held Enter is known by no key-up since the last press.
        val down = booleanArrayOf(false)
        return modifier.onPreviewKeyEvent { e ->
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
}

private object DeskClock : UiClock {
    /** A JVM can't read a 12 or 24 hour setting, so it goes by what this locale's own short time does. */
    private fun is24(locale: Locale): Boolean = (DateFormat.getTimeInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern()?.contains('a') != true

    @Composable
    override fun style(): ClockStyle {
        val locale = Locale.getDefault()
        return ClockStyle(locale, is24(locale))
    }

    /**
     * What Android's `getBestDateTimePattern` gives in English, for the skeletons the chat asks for. The JDK has this only from 19 on
     * and this app builds for 17, so in another locale the words are that locale's and their order is this one.
     */
    override fun bestDatePattern(locale: Locale, skeleton: String): String = when (skeleton) {
        "Hm" -> "HH:mm"
        "hm" -> "h:mm a"
        "Hms" -> "HH:mm:ss"
        "hms" -> "h:mm:ss a"
        "EEEMMMd" -> "EEE MMM d"
        "EEEMMMdy" -> "EEE MMM d, y"
        "MMMd" -> "MMM d"
        "MMMdy" -> "MMM d, y"
        "EEEEMMMMd" -> "EEEE, MMMM d"
        "EEEEMMMMdy" -> "EEEE, MMMM d, y"
        else -> skeleton
    }
}

/** No web view here to draw it with (the phone's Mermaid.kt): this says it's a diagram and shows what it's made from, which can be read and copied. */
private object DeskDiagrams : UiDiagrams {
    @Composable
    override fun Diagram(source: String) {
        Column {
            HText(Ui.desk_diagram_note.text(), style = Type.small, color = HN.inkDim)
            Spacer(Modifier.height(6.dp))
            CodeBlock(source)
        }
    }
}
