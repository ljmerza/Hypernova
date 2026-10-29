package com.prudnikweb.hypernova.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Build
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.prudnikweb.hypernova.ui.fx.Heat
import com.prudnikweb.hypernova.ui.fx.HeatPolicy
import kotlinx.coroutines.delay

/**
 * How much motion we can afford. The ambient clock, the cosmos, the grain and the edge bars read this
 * (frame rates and effects per level: ui/fx/Pace.kt):
 * - [reduced]: the system animator scale is 0 (accessibility "remove animations"),
 *   or the user turned Reduce Motion on. Static frames only.
 * - [powerSave]: battery saver is on. Lower frame rates, static grain.
 * - [heat]: how warm the phone is ([Heat]): effects step down as it warms and come back as it cools.
 */
@Immutable
data class MotionPrefs(val reduced: Boolean = false, val powerSave: Boolean = false, val heat: Int = Heat.COOL)

val LocalMotion = staticCompositionLocalOf { MotionPrefs() }

@Composable
fun HypernovaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var prefs by remember { mutableStateOf(readMotion(context)) }
    DisposableEffect(context) {
        // Battery saver flips.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                prefs = readMotion(context).copy(heat = prefs.heat)
            }
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        // "Remove animations" / animator scale changes don't recreate the activity, so watch them.
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                prefs = readMotion(context).copy(heat = prefs.heat)
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
    // The phone's temperature (Android 10+), while the app is in front: the thermal status as it
    // changes, and on Android 11+ the forecast headroom every 10 s, which moves before the status
    // does. A level change recomposes everything that reads LocalMotion, which is fine minutes apart.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(context, lifecycle) {
        if (Build.VERSION.SDK_INT < 29) return@LaunchedEffect
        val pm = context.getSystemService(PowerManager::class.java) ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var status = pm.currentThermalStatus
            var headroom = Float.NaN
            fun update() {
                val level = HeatPolicy.level(status, headroom, prefs.heat)
                if (level != prefs.heat) prefs = prefs.copy(heat = level)
            }
            val listener = PowerManager.OnThermalStatusChangedListener { s -> status = s; update() }
            pm.addThermalStatusListener(context.mainExecutor, listener)
            try {
                while (true) {
                    // NaN when the device can't forecast, or when asked too often (hence 10 s).
                    if (Build.VERSION.SDK_INT >= 30) headroom = pm.getThermalHeadroom(HEADROOM_FORECAST_S)
                    update()
                    delay(HEADROOM_POLL_MS)
                }
            } finally {
                pm.removeThermalStatusListener(listener)
            }
        }
    }
    // Fork: the app's text size (TextSize.kt) on top of the system's font scale.
    val textScale by remember { TextSize.state(context) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(
        LocalMotion provides prefs,
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale * textScale),
        // Mint selection handles in text fields instead of stock blue.
        LocalTextSelectionColors provides SelectionColors,
        content = content,
    )
}

/** How far ahead the thermal headroom looks (seconds), and how often it's asked (ms). */
private const val HEADROOM_FORECAST_S = 10
private const val HEADROOM_POLL_MS = 10_000L

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
