// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.stats

import android.content.res.Resources
import com.prudnikweb.hypernova.R
import java.util.Locale
import kotlin.math.abs

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
