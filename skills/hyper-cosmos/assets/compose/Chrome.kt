package com.prudnikweb.hypernova.ui.components

import android.os.Build
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.theme.HN
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/** One blur state for the whole app: the cosmos is source z=0, each screen's content z=1. */
val LocalHaze = staticCompositionLocalOf<HazeState> { error("No HazeState") }

object Z {
    const val COSMOS = 0f
    const val CONTENT = 1f
}

/** Real blur needs RenderEffect (Android 12+). Below that we draw a designed scrim instead. */
private val CanBlur = Build.VERSION.SDK_INT >= 31

/**
 * What an edge bar blurs: the cosmos plus *its own* screen's content. During a
 * screen transition both screens are composed at once, and without this filter
 * each screen's bars would also pick up the other screen's list.
 */
@Composable
fun rememberEdgeInput(haze: HazeState, sourceKey: String): HazeInput = remember(haze, sourceKey) {
    HazeInput.Sources(haze, HazeSourceSelection.All.where { it.zIndex == Z.COSMOS || it.key == sourceKey })
}

/**
 * Blur at full strength under the chrome, fading to sharp over the last [fade] of the bar
 * (its padding beyond the content), measured in dp from the bar's real height, so text in
 * the bar never sits over the fading part.
 */
private fun edgeStyle(fromTop: Boolean, heightPx: Float, fadePx: Float) = HazeBlurStyle {
    blurRadius(22.dp)
    noiseFactor(0f) // our own grain already sits on top
    backgroundColor(HN.bg)
    colorEffects(listOf(HazeColorEffect.tint(HN.bg.copy(alpha = 0.42f))))
    val hold = if (heightPx > 0f) ((heightPx - fadePx) / heightPx).coerceIn(0f, 1f) else 0.65f
    progressive(
        if (fromTop) HazeProgressive.verticalGradient(easing = Easing { f -> ((f - hold) / (1f - hold)).coerceIn(0f, 1f) }, startIntensity = 1f, endIntensity = 0f)
        else HazeProgressive.verticalGradient(easing = Easing { f -> (f / (1f - hold)).coerceIn(0f, 1f) }, startIntensity = 0f, endIntensity = 1f),
    )
}

/**
 * Full resolution only where Haze has its single-pass progressive shader (Android 13+).
 * On 12/12L full resolution switches Haze to a layered path (several full-res blurs per
 * bar, banded); the default downscaled, masked path looks and performs better there.
 */
private val EdgeMode: HazePerformanceMode? = if (Build.VERSION.SDK_INT >= 33) HazePerformanceMode.Quality else null

@Composable
private fun EdgeBar(
    haze: HazeState, sourceKey: String, fromTop: Boolean, fade: Dp,
    modifier: Modifier, content: @Composable BoxScope.() -> Unit,
) {
    val input = rememberEdgeInput(haze, sourceKey)
    val density = LocalDensity.current
    var heightPx by remember { mutableFloatStateOf(0f) }
    val fadePx = with(density) { fade.toPx() }
    val style = remember(fromTop, heightPx, fadePx) { edgeStyle(fromTop, heightPx, fadePx) }
    Box(
        modifier
            .onSizeChanged { heightPx = it.height.toFloat() }
            // Own layer: animated chrome (live bars) re-records only itself, not the blurred source.
            .graphicsLayer()
            .then(
                if (CanBlur) Modifier.hazeBlur(input, style, performanceMode = EdgeMode)
                else Modifier.drawBehind {
                    // Android 10/11: a designed scrim with the same shape as the blur.
                    val hold = ((size.height - fadePx) / size.height).coerceIn(0f, 1f)
                    val solid = HN.bg.copy(alpha = 0.94f)
                    drawRect(
                        if (fromTop) Brush.verticalGradient(0f to solid, hold to solid, 1f to Color.Transparent)
                        else Brush.verticalGradient(0f to Color.Transparent, (1f - hold) to solid, 1f to solid),
                    )
                },
            ),
        content = content,
    )
}

/**
 * Edge chrome, Telegram-style: whatever scrolls under the status bar is blurred,
 * strongest at the screen edge and fading to sharp, so there's no black strip and
 * no hard line.
 */
@Composable
fun BlurredTop(haze: HazeState, sourceKey: String, modifier: Modifier = Modifier, fade: Dp = 30.dp, content: @Composable BoxScope.() -> Unit) =
    EdgeBar(haze, sourceKey, fromTop = true, fade = fade, modifier = modifier, content = content)

@Composable
fun BlurredBottom(haze: HazeState, sourceKey: String, modifier: Modifier = Modifier, fade: Dp = 36.dp, content: @Composable BoxScope.() -> Unit) =
    EdgeBar(haze, sourceKey, fromTop = false, fade = fade, modifier = modifier, content = content)

private val SheetStyle = HazeBlurStyle {
    blurRadius(28.dp)
    noiseFactor(0f)
    backgroundColor(HN.bg)
    colorEffects(listOf(HazeColorEffect.tint(HN.bg900.copy(alpha = 0.62f))))
}

/** A full, heavier blur for sheets (not progressive). Solid on Android 10/11. */
fun Modifier.sheetBlur(input: HazeInput, shape: Shape): Modifier =
    // A uniform blur has no fade to show a seam, so the cheapest input scale is fine.
    if (CanBlur) this.hazeBlur(input, SheetStyle, performanceMode = HazePerformanceMode.Performance) else this.background(HN.bg900.copy(alpha = 0.97f), shape)
