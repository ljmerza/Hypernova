package com.prudnikweb.hypernova.ui.fx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.random.Random

/**
 * The background stars' sprites ([starAtlas]) in one bitmap, baked once per screen density (off
 * the main thread, see [SkyPictures]), and one star drawn from them: a single bitmap draw at a
 * sub-pixel position, its brightness as the paint's alpha. A frame of the whole field is one image
 * per star from one texture, which the GPU batches, and it allocates nothing.
 */
internal class StarSprites(atlas: StarAtlas) {
    private val half = atlas.half
    private val bitmap: Bitmap = Bitmap.createBitmap(atlas.art.px, atlas.art.w, atlas.art.h, Bitmap.Config.ARGB_8888).also { it.prepareToDraw() }
    private val src = Array(STAR_TIERS.size * STAR_COLORS.size) { i ->
        val x = atlas.cell[i * 3]; val y = atlas.cell[i * 3 + 1]; val n = atlas.cell[i * 3 + 2]
        Rect(x, y, x + n, y + n)
    }
    private val dst = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** A star of [tier] and colour class [color] at ([x], [y]), [scale] times its sprite's size. */
    fun draw(canvas: Canvas, x: Float, y: Float, tier: Int, color: Int, scale: Float, alpha: Float) {
        val a = (alpha * 255f + 0.5f).toInt()
        if (a <= 0) return
        val h = half[tier] * scale
        dst.set(x - h, y - h, x + h, y + h)
        paint.alpha = a.coerceAtMost(255)
        canvas.drawBitmap(bitmap, src[tier * STAR_COLORS.size + color], dst, paint)
    }

    companion object {
        /**
         * How many stars of each tier (bright to very faint) a sky has, as a fraction: few bright,
         * many faint, as a real sky's counts rise about threefold with each step fainter.
         */
        private val TIER_SHARE = floatArrayOf(0.035f, 0.11f, 0.285f, 0.57f)

        /**
         * Which colours (hot blue-white to cool red-orange, [STAR_COLORS]) a star of each tier
         * takes. The bright ones are mostly hot, luminous stars and a few orange and red giants;
         * the faint ones are mostly the cool orange and red stars that make up most of a galaxy.
         */
        private val COLOR_SHARE = arrayOf(
            floatArrayOf(0.2f, 0.22f, 0.2f, 0.14f, 0.14f, 0.1f),
            floatArrayOf(0.08f, 0.16f, 0.2f, 0.22f, 0.22f, 0.12f),
            floatArrayOf(0.03f, 0.08f, 0.14f, 0.25f, 0.3f, 0.2f),
            floatArrayOf(0.01f, 0.04f, 0.1f, 0.22f, 0.33f, 0.3f),
        )

        fun pickTier(rnd: Random): Int {
            var u = rnd.nextFloat()
            for (t in TIER_SHARE.indices) { if (u < TIER_SHARE[t]) return t; u -= TIER_SHARE[t] }
            return TIER_SHARE.size - 1
        }

        fun pickColor(rnd: Random, tier: Int): Int {
            var u = rnd.nextFloat()
            val share = COLOR_SHARE[tier]
            for (c in share.indices) { if (u < share[c]) return c; u -= share[c] }
            return share.size - 1
        }
    }
}
