package com.prudnikweb.hypernova.data

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
