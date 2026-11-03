package com.prudnikweb.hypernova.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/** A JVM can't read a 12 or 24 hour setting, so it goes by what this locale's own short time does. */
private fun is24(locale: Locale): Boolean = (DateFormat.getTimeInstance(DateFormat.SHORT, locale) as? SimpleDateFormat)?.toPattern()?.contains('a') != true

@Composable
internal actual fun rememberChatClock(): ChatClock {
    val locale = Locale.getDefault()
    return remember(locale) { ChatClock(locale, is24(locale)) }
}

/**
 * What Android's `getBestDateTimePattern` gives in English, for the skeletons the chat asks for. The JDK has this only from 19 on
 * and this app builds for 17, so in another locale the words are that locale's and their order is this one.
 */
internal actual fun bestDatePattern(locale: Locale, skeleton: String): String = when (skeleton) {
    "Hm" -> "HH:mm"
    "hm" -> "h:mm a"
    "Hms" -> "HH:mm:ss"
    "hms" -> "h:mm:ss a"
    "EEEMMMd" -> "EEE MMM d"
    "EEEMMMdy" -> "EEE MMM d, y"
    "MMMd" -> "MMM d"
    "MMMdy" -> "MMM d, y"
    "EEEEMMMMd" -> "EEEE, MMMM d"
    "EEEEMMMMdy" -> "EEEE, MMMM d, y"
    else -> skeleton
}
