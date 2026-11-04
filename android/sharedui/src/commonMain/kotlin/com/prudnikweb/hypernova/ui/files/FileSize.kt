package com.prudnikweb.hypernova.ui.files

import com.prudnikweb.hypernova.data.Str
import androidx.compose.runtime.Composable
import com.prudnikweb.hypernova.ui.platform.wordsNow
import java.util.Locale

/** "12.4 MB" in the app's units, up to gigabytes. */
@Composable
fun sizeText(bytes: Long): String {
    val res = wordsNow()
    val kb = 1024.0
    return when {
        bytes < 1024 -> res.getString(Str.size_b, bytes.toInt())
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, res.getString(Str.size_kb), bytes / kb)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, res.getString(Str.size_mb), bytes / (kb * kb))
        else -> String.format(Locale.ROOT, res.getString(Str.size_gb), bytes / (kb * kb * kb))
    }
}
