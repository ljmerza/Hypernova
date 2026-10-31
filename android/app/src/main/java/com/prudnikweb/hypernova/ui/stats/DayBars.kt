package com.prudnikweb.hypernova.ui.stats

import kotlin.math.ceil

/**
 * The last 30 days' bar chart, without drawing: how tall each day's bar is, where its slot lies,
 * which day a touch at x lands on, and where the axis labels go. Plain numbers, so it's tested on
 * the JVM ([DayBarsTest]); `StatsScreen`'s `DaysSection` draws it.
 */
internal object DayBars {
    private const val HOUR = 3_600_000L

    /** The scale's top before 24h; past it, the next multiple of 10h. */
    private val STEPS = intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24)

    /** The bar's share of its slot; the rest is the gap between bars. */
    const val BAR_SHARE = 0.62f

    /**
     * The scale's top in whole hours, at or over the busiest day ([maxMs]): a round figure (1h, 2h,
     * …, 8h, 10h, 12h, 16h, 20h, 24h, then 30h, 40h, …), so its mark reads cleanly. At least 1h,
     * also when no day has work.
     */
    fun scaleHours(maxMs: Long): Int {
        val h = ceil(maxMs.coerceAtLeast(0L).toDouble() / HOUR).toInt().coerceAtLeast(1)
        return STEPS.firstOrNull { it >= h } ?: (((h + 9) / 10) * 10)
    }

    /** Each day's bar as a share of the scale (0..1): its hours over [scaleHours] of the busiest day. 0 for a day without work. */
    fun heights(ms: List<Long>): FloatArray {
        val top = scaleHours(ms.maxOrNull() ?: 0L) * HOUR.toFloat()
        return FloatArray(ms.size) { i -> (ms[i].coerceAtLeast(0L) / top).coerceIn(0f, 1f) }
    }

    /** The day under x in a chart [width] wide with [n] slots, oldest at the left; touches past an end take the end's day. -1 with no days. */
    fun indexAt(x: Float, width: Float, n: Int): Int {
        if (n <= 0 || width <= 0f) return -1
        return (x / width * n).toInt().coerceIn(0, n - 1)
    }

    /** Where day [i]'s bar starts: centred in its slot, [barWidth] wide. */
    fun barLeft(i: Int, width: Float, n: Int): Float {
        val slot = width / n
        return slot * i + slot * (1f - BAR_SHARE) / 2f
    }

    fun barWidth(width: Float, n: Int): Float = width / n * BAR_SHARE

    /** The middle of day [i]'s slot. */
    fun centre(i: Int, width: Float, n: Int): Float = width / n * (i + 0.5f)

    /**
     * How far day [i] of [n] is in the chosen range (0..1): the latest [lit] days are, the others
     * not. [lit] glides between ranges, so the edge eases across one bar at a time.
     */
    fun lit(i: Int, n: Int, lit: Float): Float = (lit - (n - 1 - i)).coerceIn(0f, 1f)

    /** The busiest day's index (the latest of equals), or -1 when no day has work. */
    fun busiest(ms: List<Long>): Int {
        var best = -1
        for (i in ms.indices) if (ms[i] > 0L && (best < 0 || ms[i] >= ms[best])) best = i
        return best
    }

    /**
     * The axis labels under the bars: the first day, the middle one and the last ("Today"), as
     * indices into the days; one or two when there are fewer days.
     */
    fun labelDays(n: Int): IntArray = when {
        n <= 0 -> IntArray(0)
        n == 1 -> intArrayOf(0)
        n == 2 -> intArrayOf(0, 1)
        else -> intArrayOf(0, (n - 1) / 2, n - 1)
    }

    /**
     * Where each label starts (its left edge) under a chart [width] wide with [n] days, for the
     * labels of [labelDays] [widths] wide: the first starts at the left edge, the last ends at the
     * right one, the others (and a lone day's) sit centred under their bar but inside the chart. A middle label that
     * would come within [gap] of a neighbour is dropped (NaN), so narrow phones and large fonts keep
     * the ends.
     */
    fun labelLefts(n: Int, width: Float, widths: FloatArray, gap: Float): FloatArray {
        val days = labelDays(n)
        require(widths.size == days.size) { "a width per label" }
        val out = FloatArray(days.size)
        for (k in days.indices) {
            out[k] = when {
                // A lone day: its label under its bar.
                days.size == 1 -> (centre(0, width, n) - widths[k] / 2f).coerceAtLeast(0f)
                k == 0 -> 0f
                k == days.lastIndex -> width - widths[k]
                else -> (centre(days[k], width, n) - widths[k] / 2f).coerceIn(0f, (width - widths[k]).coerceAtLeast(0f))
            }
        }
        for (k in 1 until days.lastIndex) {
            val clearsLeft = out[k] >= out[0] + widths[0] + gap
            val clearsRight = out[k] + widths[k] + gap <= out[days.lastIndex]
            if (!clearsLeft || !clearsRight) out[k] = Float.NaN
        }
        return out
    }
}
