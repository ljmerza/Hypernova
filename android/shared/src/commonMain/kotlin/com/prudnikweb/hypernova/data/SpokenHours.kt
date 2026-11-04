package com.prudnikweb.hypernova.data

import java.util.Locale
import kotlin.math.abs

/** How long, for a screen reader: "4 minutes", "1 hour 12 minutes", "120 hours". */
fun spokenHours(res: Strings, ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0).toInt()
    val h = m / 60
    val min = m % 60
    val hs = res.getQuantityString(Plural.stats_said_hours, h, h)
    val ms2 = res.getQuantityString(Plural.stats_said_minutes, min, min)
    return when {
        h == 0 -> res.getQuantityString(Plural.stats_said_minutes, m, m)
        h >= 100 || min == 0 -> hs
        else -> res.getString(Str.stats_said_hm, hs, ms2)
    }
}

/** A count, short: 950, 12.4k, 9.2M, 3.6B (one decimal under ten). */
fun compactCount(res: Strings, n: Long): String {
    fun one(v: Double) = if (abs(v) < 10) String.format(Locale.ENGLISH, "%.1f", v).removeSuffix(".0") else String.format(Locale.ENGLISH, "%.0f", v)
    // The unit is chosen after rounding, so 999,999 reads "1M", not "1000k".
    fun rounded(v: Double) = one(v).toDouble()
    val a = abs(n).toDouble()
    return when {
        a < 1_000 -> n.toString()
        abs(rounded(a / 1e3)) < 1_000 -> res.getString(Str.stats_k, one(n / 1e3))
        abs(rounded(a / 1e6)) < 1_000 -> res.getString(Str.stats_mn, one(n / 1e6))
        else -> res.getString(Str.stats_bn, one(n / 1e9))
    }
}

/** A count in full, grouped: 21,310. */
fun groupedCount(n: Long): String = String.format(Locale.ENGLISH, "%,d", n)
