package com.prudnikweb.hypernova.ui.fx

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val TILES = 4
private const val TILE = 192 // noise cells per tile side

/**
 * Noise tiles, generated once per process on a background thread (≈150K cells,
 * 4 × 144KB bitmaps), so the first frame never waits for them.
 */
private object GrainTiles {
    @Volatile private var cached: List<ShaderBrush>? = null
    val ready: List<ShaderBrush>? get() = cached

    @Synchronized
    fun generate(): List<ShaderBrush> = cached ?: List(TILES) { seed ->
        val rnd = Random(seed * 7_919 + 13)
        val px = IntArray(TILE * TILE)
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            val a = rnd.nextInt(91) // 0stems: alpha 0..90 of 255
            px[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bmp = Bitmap.createBitmap(px, TILE, TILE, Bitmap.Config.ARGB_8888)
        ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }.also { cached = it }
}

/**
 * Film grain over everything, after 0stems `GrainField.tsx`: grey noise with
 * alpha 0..90/255, overlay blend, opacity 0.32, a new pattern 24 times a second.
 *
 * - Cell size: 0stems' 960×540 canvas is stretched to the viewport, so one cell is
 *   ~1.5 CSS px on a desktop at ~60cm. A phone is held at roughly half that distance,
 *   so the same apparent grain is ~1.1dp. The tile is drawn scaled with bilinear
 *   filtering, which gives the same soft, upscaled look as the browser.
 * - Cost: instead of regenerating a full-screen bitmap per frame, each frame picks a
 *   tile and a random offset. Frames come from the shared [AmbientClock], so grain
 *   changes land on cosmos frames instead of adding their own.
 * - Its own graphics layer: a tick re-records only this layer, never the blurred
 *   content beneath. The layer stays non-offscreen (alpha 1, Auto compositing), so
 *   the Overlay blend still applies to what's under it.
 */
@Composable
fun Grain(modifier: Modifier = Modifier, opacity: Float = 0.32f) {
    val motion = LocalMotion.current
    val clock = LocalAmbient.current
    val brushes by produceState(GrainTiles.ready) {
        if (value == null) value = withContext(Dispatchers.Default) { GrainTiles.generate() }
    }
    val fps = if (motion.reduced) 0 else motion.grainFps
    Spacer(
        modifier
            .graphicsLayer()
            .drawBehind {
                val tiles = brushes ?: return@drawBehind
                val f = if (fps == 0) 0 else (clock.seconds * fps).toInt()
                val r = Random(f * 31 + 7)
                val cell = 1.1.dp.toPx()
                val ox = -r.nextInt(TILE) * cell
                val oy = -r.nextInt(TILE) * cell
                translate(ox, oy) {
                    scale(cell, cell, pivot = Offset.Zero) {
                        drawRect(
                            tiles[f % TILES],
                            topLeft = Offset.Zero,
                            size = Size(size.width / cell + TILE * 2, size.height / cell + TILE * 2),
                            alpha = opacity,
                            blendMode = BlendMode.Overlay,
                        )
                    }
                }
            },
    )
}
