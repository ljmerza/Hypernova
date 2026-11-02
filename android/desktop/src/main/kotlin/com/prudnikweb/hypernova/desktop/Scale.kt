package com.prudnikweb.hypernova.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import com.prudnikweb.hypernova.data.Prefs
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.Toolkit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * How big everything is drawn: docs/DECISIONS.md, "The desktop app's size on a big screen". A scale is
 * pixels per dp, so 2 is everything twice as wide and as tall. The rule and the steps are plain functions.
 */

/** What can be read about the monitor a window is on. */
data class ScreenFacts(
    /** What the JVM already scales by: `GDK_SCALE`, `sun.java2d.uiScale`, a Retina screen, Windows' setting. */
    val system: Float,
    val widthPx: Int,
    val heightPx: Int,
    /** `Toolkit.getScreenResolution`. X servers often just say 96 here, whatever the panel is. */
    val dpi: Int? = null,
    /** The desktop's text DPI (`Xft/DPI`, from its settings daemon), which its scale setting raises. */
    val desktopDpi: Int? = null,
    val linux: Boolean = true,
)

/**
 * The scale when nothing's been chosen, first answer wins:
 *
 * 1. The JVM already scales: that, as it is.
 * 2. Not Linux: 1. A Mac and Windows tell the JVM their real scale, so 1 there is the owner's choice.
 * 3. The desktop's text DPI is 144 or more: that over 96. A desktop at 150% says so here even where the
 *    JVM doesn't look. Less than that is more often Large Text than a scale, so it decides nothing.
 * 4. The screen's DPI is 125 or more: that over 96. Below that it can't be told from the 96 X makes up.
 * 5. The monitor's short edge: 3240 pixels or more is 3, 2160 is 2 (4K), 1800 is 1.5, anything less is 1.
 *
 * 3 and 4 go to the nearest step and leave a short edge of at least 900 dp, so a 15 inch 1080p laptop is
 * still 1 whatever its DPI, and a window a compositor is already scaling up can't be scaled up much again.
 */
fun autoScale(s: ScreenFacts): Float {
    if (s.system > 1f) return s.system
    if (!s.linux) return 1f
    val edge = min(s.widthPx, s.heightPx)
    val told = s.desktopDpi?.takeIf { it >= DESKTOP_DPI_THAT_SAYS } ?: s.dpi?.takeIf { it >= SCREEN_DPI_THAT_SAYS }
    val scale = when {
        told != null -> min(nearestStep(told / BASE_DPI), floorStep(edge / MIN_EDGE_DP))
        edge >= 3240 -> 3f
        edge >= 2160 -> 2f
        edge >= 1800 -> 1.5f
        else -> 1f
    }
    return scale.coerceIn(1f, Zoom.MAX)
}

private fun nearestStep(v: Float): Float = (v / Zoom.STEP).roundToInt() * Zoom.STEP
private fun floorStep(v: Float): Float = floor(v / Zoom.STEP) * Zoom.STEP

/** The first window: its width and height in dp, at this scale, on what's free of a screen. In the units a window is sized in. */
fun firstWindowSize(scale: Float, system: Float, usable: Dimension): Dimension = sized(WIDTH_DP, HEIGHT_DP, scale, system, usable)

/** The smallest the window goes at this scale. A window is sized in the JVM's units, so its own scale comes off. */
fun minimumWindowSize(scale: Float, system: Float, usable: Dimension): Dimension = sized(MIN_WIDTH_DP, MIN_HEIGHT_DP, scale, system, usable)

private fun sized(widthDp: Int, heightDp: Int, scale: Float, system: Float, usable: Dimension): Dimension {
    val k = scale / system
    return Dimension(min(ceil(widthDp * k).toInt(), usable.width), min(ceil(heightDp * k).toInt(), usable.height))
}

/** What [autoScale] is given, for the monitor [gc] is. Nothing here can fail the window: what can't be read is just not known. */
fun screenFacts(gc: GraphicsConfiguration, os: String = System.getProperty("os.name").orEmpty()): ScreenFacts {
    val toolkit = Toolkit.getDefaultToolkit()
    val system = gc.defaultTransform.scaleX.toFloat()
    val bounds = gc.bounds
    return ScreenFacts(
        system = system,
        widthPx = (bounds.width * system).roundToInt(),
        heightPx = (bounds.height * system).roundToInt(),
        dpi = runCatching { toolkit.screenResolution }.getOrNull(),
        desktopDpi = runCatching { toolkit.getDesktopProperty(XFT_DPI) as? Int }.getOrNull()?.let { it / XFT_UNITS }?.takeIf { it > 0 },
        linux = os.lowercase().startsWith("linux"),
    )
}

/** What's free of the monitor [gc] is, without the panels and docks, in the units a window is sized in. */
fun usableArea(gc: GraphicsConfiguration): Dimension {
    val bounds = gc.bounds
    val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc) }.getOrNull()
    return Dimension(bounds.width - (insets?.let { it.left + it.right } ?: 0), bounds.height - (insets?.let { it.top + it.bottom } ?: 0))
}

enum class ZoomKey { In, Out, Reset }

/** Ctrl with +, - or 0, on either side of the keyboard. Shift doesn't matter, since + is a shifted = on many layouts. */
fun zoomKey(key: Key, ctrl: Boolean, alt: Boolean, meta: Boolean): ZoomKey? {
    if (!ctrl || alt || meta) return null
    return when (key) {
        Key.Equals, Key.Plus, Key.NumPadAdd -> ZoomKey.In
        Key.Minus, Key.NumPadSubtract -> ZoomKey.Out
        Key.Zero, Key.NumPad0 -> ZoomKey.Reset
        else -> null
    }
}

/**
 * The character a zoom key also sends, on its own, after the key itself: a focused text field would type it.
 * It has no key on it to go by, so this goes by the character.
 */
fun zoomKeyTyped(char: Char, ctrl: Boolean, alt: Boolean, meta: Boolean): Boolean = ctrl && !alt && !meta && char in "=+-0"

/** The owner's own choice of scale, saved, which beats [autoScale]. Reset forgets it. */
class Zoom(private val prefs: Prefs) {
    /** Null: nothing chosen. */
    var chosen by mutableStateOf(read())
        private set

    fun scale(auto: Float): Float = chosen ?: auto

    /** A key pressed while the window shows [auto] as its automatic scale. The scale it's at now. */
    fun press(key: ZoomKey, auto: Float): Float {
        val now = scale(auto)
        chosen = when (key) {
            ZoomKey.In -> stepUp(now)
            ZoomKey.Out -> stepDown(now)
            ZoomKey.Reset -> null
        }
        val edit = prefs.edit()
        chosen?.let { edit.putInt(KEY, percent(it)) } ?: edit.remove(KEY)
        edit.apply()
        return scale(auto)
    }

    private fun read(): Float? {
        val saved = if (prefs.contains(KEY)) prefs.getInt(KEY, 0) else return null
        // A hand-edited file: not a size to open a window at.
        return (saved / 100f).takeIf { it in MIN..MAX }
    }

    companion object {
        const val MIN = 0.75f
        const val MAX = 3f
        const val STEP = 0.25f
        /** Where it's kept: its own file, as forgetting a server clears that server's settings and not this. */
        const val PREFS = "window"
        private const val KEY = "zoomPercent"

        /** The next step up. A scale off the steps, as a system's can be, goes to the next one on them; past the top it stays. */
        fun stepUp(now: Float): Float = generateSequence(MIN) { it + STEP }.takeWhile { it <= MAX }.firstOrNull { it > now + EPSILON } ?: now

        fun stepDown(now: Float): Float = generateSequence(MAX) { it - STEP }.takeWhile { it >= MIN }.firstOrNull { it < now - EPSILON } ?: now

        fun percent(scale: Float): Int = (scale * 100).roundToInt()

        private const val EPSILON = 0.001f
    }
}

/** The window as it first opens and at its smallest, in dp. */
private const val WIDTH_DP = 960
private const val HEIGHT_DP = 720
private const val MIN_WIDTH_DP = 520
private const val MIN_HEIGHT_DP = 420
private const val BASE_DPI = 96f
private const val DESKTOP_DPI_THAT_SAYS = 144
private const val SCREEN_DPI_THAT_SAYS = 125
private const val MIN_EDGE_DP = 900f
/** AWT's name for the desktop's setting, whose value is in 1024ths of a DPI. */
private const val XFT_DPI = "gnome.Xft/DPI"
private const val XFT_UNITS = 1024
