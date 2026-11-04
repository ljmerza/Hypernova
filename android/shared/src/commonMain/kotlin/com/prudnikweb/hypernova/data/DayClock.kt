package com.prudnikweb.hypernova.data

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** "9:00", or "9:00 AM" on a 12-hour device. */
fun clockTime(t: LocalTime, h24: Boolean, locale: Locale): String = DateTimeFormatter.ofPattern(if (h24) "H:mm" else "h:mm a", locale).format(t)

/** When something was or is due, as the widgets say it: "9:00" today, "Mon 9:00" this week, "17 Oct 9:00" further off. [time] says the clock's part, [dayAnd] joins a day to it. */
fun dayClock(t: Long, now: Long, zone: ZoneId, locale: Locale, time: (LocalTime) -> String, dayAnd: (day: String, time: String) -> String): String {
    val at = Instant.ofEpochMilli(t).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val clock = time(at.toLocalTime())
    val days = ChronoUnit.DAYS.between(today, at.toLocalDate())
    return when {
        days == 0L -> clock
        // This week either way: a reset ahead, or one a skipped run waited for.
        days in -6..6 -> dayAnd(DateTimeFormatter.ofPattern("EEE", locale).format(at), clock)
        else -> dayAnd(DateTimeFormatter.ofPattern("d MMM", locale).format(at), clock)
    }
}
