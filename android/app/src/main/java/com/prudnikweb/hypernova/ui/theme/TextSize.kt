package com.prudnikweb.hypernova.ui.theme

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * Fork: the app's own text size (Settings → Display), kept on this phone. It multiplies the
 * system's font scale for everything Hypernova draws (HypernovaTheme), so Small fits more of a
 * session on the screen without changing the rest of the phone. One state for the whole app, so a
 * change shows at once.
 */
object TextSize {
    const val SMALL = 0.85f
    const val DEFAULT = 1f
    const val LARGE = 1.15f
    private val SCALES = listOf(SMALL, DEFAULT, LARGE)
    private const val PREFS = "hypernova_display"
    private const val KEY = "text_scale"
    private var current: MutableState<Float>? = null

    fun state(context: Context): State<Float> = holder(context)

    fun set(context: Context, scale: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY, scale).apply()
        holder(context).value = scale
    }

    private fun holder(context: Context): MutableState<Float> = current ?: mutableStateOf(read(context)).also { current = it }

    private fun read(context: Context): Float {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY, DEFAULT)
        return if (saved in SCALES) saved else DEFAULT
    }
}
