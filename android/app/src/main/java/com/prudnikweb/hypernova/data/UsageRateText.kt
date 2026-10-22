package com.prudnikweb.hypernova.data

import java.util.Locale

/** The pace as it reads: "0.8" from 0.1 up, "0.05" below (the week moves in hundredths). Null when nothing is being spent. */
fun ratePerMin(v: Float?): String? {
    if (v == null || v < 0.005f) return null
    return String.format(Locale.ENGLISH, if (v >= 0.1f) "%.1f" else "%.2f", v)
}
