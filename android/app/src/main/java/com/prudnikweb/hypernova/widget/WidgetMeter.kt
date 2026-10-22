package com.prudnikweb.hypernova.widget

import com.prudnikweb.hypernova.data.UsageProjection
import kotlin.math.roundToInt

/**
 * The widgets' projection meters: the app's (ui/components/ProjectionMeter.kt) in what RemoteViews
 * can draw. The bar is a ProgressBar whose secondary progress is the pace's faint fill, the red
 * notch an image at its end, and the one tag a glyph and a clock time: the reset after the reset
 * glyph, dim, or, when the pace reaches the limit before the reset, the time it comes, in red after
 * a notch. Clock times, not countdowns: a widget isn't redrawn every minute. Pure, unit-tested.
 */
internal object WidgetMeter {
    /** The least a bar is drawn at (dp): shorter, it would read as a dash. */
    const val BAR_MIN = 24f
    /** Between the percentage, the bar and the tag (dp), as the layouts have it. */
    const val GAP = 7f
    /** The tag's glyph and the room after it (dp). */
    const val GLYPH = 13f

    /** What a meter row shows: everything, all but its tag, or only its percentage. */
    enum class Fit { All, NoTag, Percent }

    /**
     * What fits in a row [room] dp wide whose percentage takes [percent] dp and whose tag (its
     * glyph and time) takes [tag]: the tag goes first, then the bar; the percentage never does.
     */
    fun fit(room: Float, percent: Float, tag: Float): Fit = when {
        room - percent - GAP - tag - GAP >= BAR_MIN -> Fit.All
        room - percent - GAP >= BAR_MIN -> Fit.NoTag
        else -> Fit.Percent
    }

    /** The bar's levels, of 1000: the used part, and how far the pace's faint fill runs (as far as the used part at rest). */
    fun levels(p: UsageProjection): Pair<Int, Int> {
        val used = (p.used * 1000).roundToInt().coerceIn(0, 1000)
        val lead = ((p.projected ?: p.used) * 1000).roundToInt().coerceIn(used, 1000)
        return used to lead
    }

    /** When the limit comes at this pace, if before the reset: [now] plus its time, to the whole minute; 0 if it doesn't. */
    fun limitAt(p: UsageProjection, now: Long): Long {
        val t = p.tag as? UsageProjection.Tag.Limit ?: return 0L
        return (now + t.ms + 30_000L) / 60_000L * 60_000L
    }

    /** A pace (%/min) to the hundredth: finer changes wouldn't show, and would redraw the widget for nothing. */
    fun pace(p: UsageProjection): Float? = p.pace?.let { (it * 100f).roundToInt() / 100f }?.takeIf { it > 0f }
}
