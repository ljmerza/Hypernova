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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private const val TILES = 1
private const val TILE = 192 // noise cells per tile side

/**
 * The noise tile, generated once per process on a background thread (≈37K cells, a 144KB
 * bitmap), so the first frame never waits for it. One is enough: every new pattern is this one
 * shifted by a random number of cells, which is a new pattern at every pixel.
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
 * alpha 0..90/255, overlay blend, opacity 0.32, a new pattern 12 to 24 times a second.
 *
 * - Cell size: 0stems' 960×540 canvas is stretched to the viewport, so one cell is
 *   ~1.5 CSS px on a desktop at ~60cm. A phone is held at roughly half that distance,
 *   so the same apparent grain is ~1.1dp. The tile is drawn scaled with bilinear
 *   filtering, which gives the same soft, upscaled look as the browser.
 * - Cost: instead of regenerating a full-screen bitmap per frame, the noise is drawn once
 *   into its own layer, a tile's width larger than the screen, and each new pattern only
 *   moves that layer by a random whole number of cells. Moving a layer is a property of it:
 *   nothing is drawn again on the main thread (not this layer, not the screen around it),
 *   the frame is only composited again. Where the sky draws anyway (Home) the pattern
 *   changes with its frames, 24 a second. Elsewhere (a chat, Settings) it has a gentle lane of
 *   its own in the [AmbientClock] ([AmbientClock.grain], 12 a second, 8 on a warm phone): 24
 *   there, on every screen, is what kept the phone warm, and with none the grain froze. On
 *   battery saver and a hot phone it's a still texture.
 * - The layer stays non-offscreen (alpha 1, Auto compositing), so the Overlay blend still
 *   applies to what's under it.
 */
@Composable
fun Grain(modifier: Modifier = Modifier, opacity: Float = 0.32f) {
    val clock = LocalAmbient.current
    val brushes by produceState(GrainTiles.ready) {
        if (value == null) value = withContext(Dispatchers.Default) { GrainTiles.generate() }
    }
    Spacer(
        modifier
            .graphicsLayer {
                // Read here, not while drawing: a new pattern then changes only where the layer
                // sits, which redraws nothing (see the cost note above).
                val r = Random(clock.grain * 31 + 7)
                val cell = 1.1.dp.toPx()
                translationX = -r.nextInt(TILE) * cell
                translationY = -r.nextInt(TILE) * cell
            }
            .drawBehind {
                val tiles = brushes ?: return@drawBehind
                val cell = 1.1.dp.toPx()
                // A tile wider and taller than the screen, so however the layer is moved (up to a
                // tile's width up and left) the screen stays covered.
                scale(cell, cell, pivot = Offset.Zero) {
                    drawRect(
                        tiles[0],
                        topLeft = Offset.Zero,
                        size = Size(size.width / cell + TILE, size.height / cell + TILE),
                        alpha = opacity,
                        blendMode = BlendMode.Overlay,
                    )
                }
            },
    )
}
