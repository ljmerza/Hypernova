package com.prudnikweb.hypernova.ui.platform

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.unit.Dp
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings
import java.util.Locale

/*
 * What the shared drawing asks of the app it's in: the only way out of this module to an operating system, as Seams.kt is for
 * :shared's data. :app implements it (ui/AndroidUiPlatform.kt), each call the one the phone's screens always made; :desktop
 * implements it (DesktopUiPlatform.kt). Nothing here imports android.* or java.awt. docs/PLATFORMS.md, "The shared drawing".
 */

/** What the app hands over, once, before anything draws. */
interface UiPlatform {
    val words: UiWords
    val fonts: UiFonts
    val device: UiDevice
    val input: UiInput
    val clock: UiClock
    val diagrams: UiDiagrams
}

/** The one in this process. */
object SharedUi {
    internal var installed: UiPlatform? = null
        private set

    val platform: UiPlatform get() = checkNotNull(installed) { "SharedUi.install hasn't run: the app does it as it starts" }

    fun install(platform: UiPlatform) {
        installed = platform
    }
}

/** The app's words, by resource name ([Str], [Plural]). On the phone, Compose's own resource calls; on the desktop the same text from the same files. */
interface UiWords {
    @Composable @ReadOnlyComposable fun text(key: Str): String
    @Composable @ReadOnlyComposable fun text(key: Str, vararg args: Any): String
    @Composable @ReadOnlyComposable fun quantity(key: Plural, count: Int): String
    @Composable @ReadOnlyComposable fun quantity(key: Plural, count: Int, vararg args: Any): String
    /** These words as an object, for a line built from several. On the phone it follows `LocalContext.current.resources`. */
    @Composable @ReadOnlyComposable fun strings(): Strings
}

/** Atlas's two fonts: the same files, each app's own way to them. */
interface UiFonts {
    fun sans(weight: Int): Font
    fun mono(weight: Int): Font
    /** What a mono style asks of its font: nothing on the phone, as it always was; the desktop turns ligatures off. */
    val monoFeatures: String?
}

/** The machine under the drawing: its vibrations, its clipboard, how tall it is. */
interface UiDevice {
    @Composable fun haptics(): Haptics
    @Composable fun copier(): Copier
    /** How tall the screen or the window is: the share an approval may take of it goes by this. */
    @Composable fun windowHeightDp(): Int
    /** A glow round a filled capsule: its own shape, blurred, outside it. */
    fun blurOutside(paint: Paint, radius: Float)
}

/** A small physical answer to a press. */
interface Haptics {
    /** A long press took. */
    fun longPress()
    /** A button's light tick. */
    fun tick()
    /** Something weighty went: an Allow, a plan approved. */
    fun confirm()
}

/** The system's clipboard. */
interface Copier {
    fun copy(label: String, text: String)
    /** [words] where the system doesn't show its own word for it. */
    fun sayCopied(words: String)
}

/** Fingers and a keyboard, or a pointer and a keyboard: what differs in how a thing is pressed, selected and typed in. */
interface UiInput {
    /** A pill is at least this tall to the touch, whatever is drawn. */
    val minTouchTarget: Dp
    /** A pill under a pointer: a hand over it. */
    @Composable fun pillPointer(modifier: Modifier, interaction: MutableInteractionSource, enabled: Boolean): Modifier
    /** The pointer is over it, so it lights. */
    @Composable fun hovered(interaction: MutableInteractionSource): Boolean
    /** A pill with the keyboard on it: a ring. */
    @Composable fun focusRing(interaction: MutableInteractionSource, shape: Shape): Modifier
    /** A code block's long press that copies it whole. Where a mouse selects, a held press is the start of a selection. */
    fun longPressCopies(modifier: Modifier, key: Any, copy: () -> Unit): Modifier
    /** Output or a plan a mouse can select. The phone's chat leaves these to a long press on the row. */
    @Composable fun SelectableOutput(content: @Composable () -> Unit)
    /** Settings' Enter key: one state for the app, so a change reaches every open box at once. */
    @Composable fun enterSetting(): EnterKey
    /**
     * A hardware Enter in a box that sends, decided by [com.prudnikweb.hypernova.data.EnterKeys.decide]: a send happens on the key's press,
     * once (not while it repeats); Nothing swallows it. [shiftEnter] is told when a Shift+Enter is let through to the field in Send mode.
     */
    fun enterKey(modifier: Modifier, setting: EnterKey, canSend: () -> Boolean, onSend: () -> Unit, shiftEnter: (Boolean) -> Unit): Modifier
}

/** This device's way of telling the time. */
interface UiClock {
    /** Its locale and whether its clock is 24 hours. */
    @Composable fun style(): ClockStyle
    /** This locale's own order for [skeleton]'s fields, as a SimpleDateFormat pattern. */
    fun bestDatePattern(locale: Locale, skeleton: String): String
}

@Immutable
data class ClockStyle(val locale: Locale, val is24Hour: Boolean)

/** A closed ```mermaid block: the phone draws it in a web view; what has none says what it is. */
interface UiDiagrams {
    @Composable fun Diagram(source: String)
}
