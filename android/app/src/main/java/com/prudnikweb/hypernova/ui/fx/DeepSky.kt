package com.prudnikweb.hypernova.ui.fx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.edit
import androidx.core.graphics.withScale
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.random.Random

/**
 * The background sky's pictures, made on one background thread per screen density: first the
 * sky's seed (read, or made the first time, so every sky is its own and the same at every
 * launch), then the stars' sprites, then the deep-sky objects one by one. The sky draws what's
 * ready and fades each in as it arrives, so the main thread never waits for a picture.
 */
internal class SkyPictures {
    /** The sky's own seed, once read. The star field and the deep-sky objects come from it. */
    @Volatile var seed: Long? = null
        private set
    @Volatile var stars: StarSprites? = null
        private set
    private val deep = AtomicReferenceArray<Bitmap?>(Deep.COUNT)
    @Volatile private var dp = -1f
    /** When the last picture was made (wall-clock ms); 0 while they're still being made. */
    @Volatile var doneAt = 0L
        private set

    /** The deep-sky object of [kind] ([Deep]), or null until it's drawn. */
    fun deep(kind: Int): Bitmap? = deep.get(kind)

    /**
     * Pictures are still arriving or fading in (the stars over 0.7 s, each deep-sky object over
     * 2.5 s): the sky needs frames until they're all in, even where it otherwise holds still.
     */
    fun settling(now: Long): Boolean = doneAt == 0L || now - doneAt < 2_800L

    /** Starts making the pictures for [dp] px per dp, unless they're made or being made already. Cheap to call every frame. */
    fun prepare(context: Context, dp: Float) {
        if (this.dp == dp) return
        this.dp = dp
        doneAt = 0L
        val app = context.applicationContext
        thread(name = "hypernova-sky", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            val s = seed ?: (runCatching { readSeed(app) }.getOrNull() ?: Random.nextLong()).also { seed = it }
            runCatching { StarSprites(starAtlas(dp)) }.getOrNull()?.let { if (this.dp == dp) stars = it }
            for (k in 0 until Deep.COUNT) {
                if (this.dp != dp) return@thread // the density changed: a newer thread makes them
                val art = runCatching { deepArt(k, s, dp) }.getOrNull() ?: continue
                val b = runCatching { Bitmap.createBitmap(art.px, art.w, art.h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: continue
                b.prepareToDraw()
                deep.set(k, b)
            }
            if (this.dp == dp) doneAt = System.currentTimeMillis()
        }
    }

    private fun readSeed(c: Context): Long {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.contains(KEY)) return p.getLong(KEY, 0L)
        return Random.nextLong().also { p.edit { putLong(KEY, it) } }
    }

    private companion object {
        const val PREFS = "sky"
        const val KEY = "seed"
    }
}

/**
 * The deep-sky objects: a few faint galaxies, nebulae and a cluster in the farthest layer of the
 * sky, as a long exposure records them ([deepArt]). They never compete with the sessions:
 * - two of the big ones (a face-on spiral, an edge-on galaxy, star-forming pillars) share the
 *   open stretch of sky below the solar system, one on each side;
 * - the rest (the third, a globular cluster, a soft elliptical, a ring nebula) lie lower, behind
 *   the list, showing between its cards and behind a session's transcript;
 * - a corner of deep field sits against one edge of the screen, fading toward the middle.
 * Where each goes, and which way round, comes from the sky's seed. They don't drift with the
 * stars; they shift with the phone's tilt the most of anything, being the farthest.
 */
internal class DeepSky {
    private val x = FloatArray(Deep.COUNT)
    private val y = FloatArray(Deep.COUNT)
    /** The most of its height (px) an object may take where it's placed; it's scaled down to fit. */
    private val room = FloatArray(Deep.COUNT)
    private val alpha = FloatArray(Deep.COUNT)
    /** When each object's picture arrived (wall-clock ms), so it fades in; 0 = not yet. */
    private val arrived = LongArray(Deep.COUNT)
    private var mirrorField = false
    private var keyW = -1f
    private var keyH = -1f
    private var keyBand = -1f
    private var keySeed = 0L
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    /** Places the objects on a [w] × [h] sky whose solar system ends at [bandBottom] (px). */
    private fun place(w: Float, h: Float, dp: Float, bandBottom: Float, seed: Long) {
        if (w == keyW && h == keyH && bandBottom == keyBand && seed == keySeed) return
        keyW = w; keyH = h; keyBand = bandBottom; keySeed = seed
        val rnd = Random(seed xor 0x6A09E667F3BCC909L)
        // The open sky between the solar system and where the list usually starts.
        val stage = (h * 0.56f - bandBottom).coerceIn(90f * dp, 220f * dp)
        val stageY = bandBottom + stage * 0.42f
        val show = intArrayOf(Deep.SPIRAL, Deep.EDGE_ON, Deep.PILLARS)
        show.shuffle(rnd)
        val leftFirst = rnd.nextBoolean()
        for (j in 0 until 2) {
            val k = show[j]
            val left = (j == 0) == leftFirst
            x[k] = w * ((if (left) 0.25f else 0.75f) + rnd.range(-0.04f, 0.04f))
            y[k] = stageY + rnd.range(-0.08f, 0.08f) * stage
            room[k] = stage * 0.9f
        }
        // Lower down, behind the list: the third big one and the small ones, alternating sides.
        val lower = intArrayOf(show[2], Deep.GLOBULAR, Deep.ELLIPTICAL, Deep.RING)
        lower.shuffle(rnd)
        val top = bandBottom + stage + 40f * dp
        val span = (h - 50f * dp - top).coerceAtLeast(120f * dp)
        val xs = floatArrayOf(0.22f, 0.76f, 0.3f, 0.7f)
        val flip = rnd.nextBoolean()
        for (i in lower.indices) {
            val k = lower[i]
            val fx = if (flip) 1f - xs[i] else xs[i]
            x[k] = w * (fx + rnd.range(-0.05f, 0.05f))
            y[k] = top + span * (i + 0.5f) / lower.size + rnd.range(-0.1f, 0.1f) * span / lower.size
            room[k] = span * 0.5f
        }
        // The deep field against one edge, a little beyond it, so the tilt never shows its cut.
        mirrorField = rnd.nextBoolean()
        x[Deep.FIELD] = if (mirrorField) w + 14f * dp else -14f * dp
        y[Deep.FIELD] = top + span * rnd.range(0.2f, 0.8f)
        room[Deep.FIELD] = Float.MAX_VALUE
        for (k in 0 until Deep.COUNT) alpha[k] = when (k) {
            Deep.PILLARS -> 0.72f
            Deep.FIELD -> 0.8f
            Deep.RING, Deep.GLOBULAR -> 0.72f
            else -> 0.66f
        }
    }

    /**
     * Draws what's ready. [light] is the far sky's (it thins a little as the week fills), [behind]
     * 1 behind text, ([parX], [parY]) the farthest layer's parallax (px).
     */
    fun draw(
        canvas: Canvas, pics: SkyPictures, w: Float, h: Float, dp: Float, bandBottom: Float,
        light: Float, behind: Float, parX: Float, parY: Float, now: Long, reduced: Boolean,
    ) {
        val seed = pics.seed ?: return
        if (light <= 0.002f) return
        place(w, h, dp, bandBottom, seed)
        for (k in 0 until Deep.COUNT) {
            val b = pics.deep(k) ?: continue
            if (arrived[k] == 0L) arrived[k] = now
            // They come out slowly, as the eye adapts to the dark.
            val fadeIn = if (reduced) 1f else ((now - arrived[k]) / 2500f).coerceIn(0f, 1f)
            val a = alpha[k] * light * (1f - 0.7f * behind) * fadeIn * fadeIn
            val ai = (a * 255f + 0.5f).toInt()
            if (ai <= 0) continue
            paint.alpha = ai.coerceAtMost(255)
            val s = min(1f, room[k] / b.height)
            val hw = b.width * s / 2f; val hh = b.height * s / 2f
            val cx = x[k] + parX; val cy = y[k] + parY
            if (k == Deep.FIELD) {
                // Its art fades away from its left edge, which goes against the screen's edge.
                if (mirrorField) {
                    dst.set(cx - b.width, cy - hh, cx, cy + hh)
                    canvas.withScale(-1f, 1f, cx - b.width / 2f, cy) { drawBitmap(b, null, dst, paint) }
                } else {
                    dst.set(cx, cy - hh, cx + b.width, cy + hh)
                    canvas.drawBitmap(b, null, dst, paint)
                }
                continue
            }
            dst.set(cx - hw, cy - hh, cx + hw, cy + hh)
            canvas.drawBitmap(b, null, dst, paint)
        }
    }

    private fun Random.range(a: Float, b: Float) = a + nextFloat() * (b - a)
}
