package com.prudnikweb.hypernova.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * How much motion we can afford. The cosmos and grain read this:
 * - [reduced]: the system animator scale is 0 (accessibility "remove animations"),
 *   or the user turned Reduce Motion on. Static frames only.
 * - [powerSave]: battery saver is on. Lower frame rates, static grain.
 */
@Immutable
data class MotionPrefs(val reduced: Boolean = false, val powerSave: Boolean = false) {
    val cosmosFps: Int get() = if (powerSave) 20 else 30
    val grainFps: Int get() = if (powerSave) 0 else 24
}

val LocalMotion = staticCompositionLocalOf { MotionPrefs() }

@Composable
fun HypernovaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(readMotion(context)) }
    DisposableEffect(context) {
        // Battery saver flips.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                prefs = readMotion(context)
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        // "Remove animations" / animator scale changes don't recreate the activity, so watch them.
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                prefs = readMotion(context)
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer,
        )
        onDispose {
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    CompositionLocalProvider(
        LocalMotion provides prefs,
        // Mint selection handles in text fields instead of stock blue.
        LocalTextSelectionColors provides SelectionColors,
        content = content,
    )
}

private val SelectionColors = TextSelectionColors(handleColor = HN.accent, backgroundColor = HN.accent.copy(alpha = 0.28f))

private fun readMotion(context: Context): MotionPrefs {
    val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val pm = context.getSystemService(PowerManager::class.java)
    return MotionPrefs(reduced = scale == 0f, powerSave = pm?.isPowerSaveMode == true)
}

/** The one text primitive: BasicText with our defaults. */
@Composable
fun HText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body,
    color: Color = HN.ink,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun HText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body,
    color: Color = HN.ink,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
