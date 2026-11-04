package com.prudnikweb.hypernova.data

/**
 * The last 30 days as a chart, every sum in it and none of the drawing (docs/DESIGN.md, "Statistics"), so both apps draw
 * the same bars and a test can ask about them. Positions are shares of the chart's width, 0 to 1: the drawing knows pixels.
 * Bars are hours of work: a day only Claude Code's own summary knows has none.
 */
class DayChart(val days: List<StatDay>, private val range: StatsRange) {
    /** The most hours in a day, as the bars see it. */
    private val mostMs = days.maxOfOrNull { it.claudeMs } ?: 0L

    /** The busiest day; of several alike, the latest. Null with no work at all. */
    val busiest: StatDay? = days.filter { it.claudeMs > 0L }.lastOrNull { it.claudeMs == mostMs }

    /** Days with any hours. */
    val daysWithWork: Int = days.count { it.claudeMs > 0L }

    /** The top of the chart, in hours: a round figure at or over the busiest day. */
    val topHours: Int = roundedUp(mostMs)

    /** A day's bar, as a share of the chart's height. */
    fun height(i: Int): Float = (days[i].claudeMs / (topHours * HOUR_MS.toDouble())).toFloat().coerceIn(0f, 1f)

    /** The middle of day [i]'s slot. */
    fun centre(i: Int): Float = (i + 0.5f) / days.size

    /** The share of its slot a bar fills. */
    val barShare: Float get() = BAR_SHARE

    /** The day under a touch at [x], kept to the chart: a drag past an end stays on that end's day. */
    fun dayAt(x: Float): Int = (x * days.size).toInt().coerceIn(0, (days.size - 1).coerceAtLeast(0))

    /** Whether the chosen range covers day [i]; the others step back. YTD in January stops at this year's first. */
    fun inRange(i: Int): Boolean {
        val last = days.lastOrNull() ?: return false
        if (i <= days.lastIndex - range.days) return false
        return range != StatsRange.Ytd || days[i].day.year == last.day.year
    }

    /** Where the labels under the bars start, as shares of the width: null when one has no place. */
    data class Axis(val first: Float?, val middle: Float?, val today: Float)

    /** The middle label's day: the one a person would call the middle, on 30 days the 15th. */
    val middleDay: Int get() = (days.size - 1) / 2

    /**
     * The labels under the bars, given each one's width and the least gap between them, all as shares of the chart's width.
     * The ends hold the edges, the middle sits under its bar and gives way when it would crowd an end. One day alone is
     * today, under its bar.
     */
    fun axis(firstW: Float, middleW: Float, todayW: Float, gap: Float): Axis {
        if (days.size <= 1) return Axis(null, null, (0.5f - todayW / 2f).coerceAtLeast(0f))
        val today = 1f - todayW
        val middle = centre(middleDay) - middleW / 2f
        val fits = middle >= firstW + gap && middle + middleW <= today - gap
        return Axis(0f, middle.takeIf { fits }, today)
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
        const val BAR_SHARE = 0.62f
        val STEPS = intArrayOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24)

        fun roundedUp(ms: Long): Int {
            val hours = ms / HOUR_MS.toDouble()
            STEPS.firstOrNull { it >= hours }?.let { return it }
            // Past a day (several servers added up): tens.
            return (kotlin.math.ceil(hours / 10.0) * 10).toInt()
        }
    }
}
