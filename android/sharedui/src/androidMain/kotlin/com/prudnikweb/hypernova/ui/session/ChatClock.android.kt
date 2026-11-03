package com.prudnikweb.hypernova.ui.session

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
internal actual fun rememberChatClock(): ChatClock {
    val configuration = LocalConfiguration.current
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    return remember(configuration, is24) { ChatClock(configuration.locales[0], is24) }
}

internal actual fun bestDatePattern(locale: Locale, skeleton: String): String = DateFormat.getBestDateTimePattern(locale, skeleton)
