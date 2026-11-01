package com.prudnikweb.hypernova.ui.stats

import android.content.res.Resources
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.StatsRange
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// English only for now (DECISIONS: "English only for now"), so dates use English names.
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DATE_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val DATE_ONLY_DAY = DateTimeFormatter.ofPattern("d", Locale.ENGLISH)

internal fun dayName(d: LocalDate): String = DAY.format(d)
internal fun dateName(d: LocalDate): String = DATE.format(d)
internal fun dateWithYear(d: LocalDate): String = DATE_YEAR.format(d)
internal fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

/** Claude's working time: "34h 26m", "152h" past 100 hours, "26m" under one. */
internal fun hours(res: Resources, ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0)
    return when {
        m < 60 -> res.getString(R.string.stats_m, m.toInt())
        m < 100 * 60 -> res.getString(R.string.stats_hm, (m / 60).toInt(), (m % 60).toInt())
        else -> res.getString(R.string.stats_h, (m / 60).toInt())
    }
}

/** [hours] for TalkBack, in full words: "5 hours 10 minutes", "26 minutes", "152 hours". */
internal fun hoursSaid(res: Resources, ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0).toInt()
    val h = m / 60
    val min = m % 60
    val hs = res.getQuantityString(R.plurals.stats_said_hours, h, h)
    val ms2 = res.getQuantityString(R.plurals.stats_said_minutes, min, min)
    return when {
        h == 0 -> res.getQuantityString(R.plurals.stats_said_minutes, m, m)
        h >= 100 || min == 0 -> hs
        else -> res.getString(R.string.stats_said_hm, hs, ms2)
    }
}

/** The same, with the units smaller and dimmer than the digits ("34h 26m" in the hero). */
internal fun hoursStyled(res: Resources, ms: Long, unitSize: TextUnit, unitColor: Color): AnnotatedString {
    val s = hours(res, ms)
    return buildAnnotatedString {
        for (ch in s) {
            // The space too: at the digits' size it would push "26m" a whole digit away.
            if (ch.isLetter() || ch.isWhitespace()) withStyle(SpanStyle(fontSize = unitSize, color = unitColor)) { append(ch) } else append(ch)
        }
    }
}

/** A count, short: 950, 12.4k, 9.2M, 3.6B (one decimal under ten). */
internal fun compact(res: Resources, n: Long): String {
    fun one(v: Double) = if (abs(v) < 10) String.format(Locale.ENGLISH, "%.1f", v).removeSuffix(".0") else String.format(Locale.ENGLISH, "%.0f", v)
    // The unit is chosen after rounding, so 999,999 reads "1M", not "1000k".
    fun rounded(v: Double) = one(v).toDouble()
    val a = abs(n).toDouble()
    return when {
        a < 1_000 -> n.toString()
        abs(rounded(a / 1e3)) < 1_000 -> res.getString(R.string.stats_k, one(n / 1e3))
        abs(rounded(a / 1e6)) < 1_000 -> res.getString(R.string.stats_mn, one(n / 1e6))
        else -> res.getString(R.string.stats_bn, one(n / 1e9))
    }
}

/** A count in full, grouped: 21,310. */
internal fun grouped(n: Long): String = String.format(Locale.ENGLISH, "%,d", n)

/**
 * What the range covers, under the screen's title: "Wed 15 Oct", "9 – 15 Oct", "since 13 Oct".
 * [today] is the Mac's (the last of an answer's days), since the days are the Mac's calendar, not
 * the phone's; [since] is its first day with work, the same in every answer. All names its year
 * (it answers "since when?"), YTD's is plain from the word.
 */
internal fun rangeSpan(res: Resources, range: StatsRange, today: LocalDate, since: Long?): String {
    val a = today.minusDays(range.days - 1L)
    return when (range) {
        StatsRange.Today -> dayName(today)
        StatsRange.Ytd -> res.getString(R.string.stats_since, dateName(today.withDayOfYear(1)))
        StatsRange.All -> res.getString(R.string.stats_since, dateWithYear(since?.let(::localDate) ?: today))
        else -> res.getString(R.string.stats_span, if (a.month == today.month) DATE_ONLY_DAY.format(a) else dateName(a), dateName(today))
    }
}

/** How many of the chart's last days [range] covers, [today] being the Mac's: YTD only those of this year. */
internal fun litDays(range: StatsRange, today: LocalDate?): Int =
    if (range == StatsRange.Ytd && today != null) minOf(range.days, today.dayOfYear) else range.days
