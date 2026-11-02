// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.fx

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The digital cosmos behind every screen, ported from 0stems `DigitalCosmos.tsx`: stars, nebulae and
 * the deep-sky objects. It thins a little as the week fills ([week], 0..1). It's rendered into a
 * picture of its own and reused until it changes, and only it feeds the edge bars' blur ([backdrop]).
 * It holds still behind the screens, so it draws no frames at rest.
 */
@Composable
fun Cosmos(week: Float, modifier: Modifier = Modifier, backdrop: Modifier = Modifier) {
    val clock = LocalAmbient.current
    val reduced = LocalMotion.current.reduced
    val currentWeek = rememberUpdatedState(week)
    // With Reduce Motion the ambient clock is frozen; its fades still move on the wall clock.
    val tick = remember { mutableIntStateOf(0) }
    if (reduced) LaunchedEffect(Unit) { while (true) { delay(1000); tick.intValue++ } }
    val sky = remember { Sky() }
    val context = LocalContext.current
    Spacer(
        modifier
            .fillMaxSize()
            .then(backdrop)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                val t = clock.now
                if (reduced) tick.intValue
                sky.pictures.prepare(context, density)
                sky.ensure(size.width, size.height, this)
                sky.step(t)
                val light = 1f - 0.65f * currentWeek.value.coerceIn(0f, 0.99f).pow(1.5f)
                val now = System.currentTimeMillis()
                sky.drawAmbient(this, t, now, reduced, 0f, 0f, light, behind = 1f)
                // Pictures fading in at launch; after that it holds still.
                if (!reduced && sky.pictures.settling(now)) clock.seconds(Pace.Live)
            },
    )
}

/**
 * Stars in the background field. More than 0stems' 95, as a real sky shows: most of them are
 * faint, and drawn from sprites they cost little more than dots did.
 */
private const val NUM_STARS = 200
private const val NUM_CLUSTERS = 6
private const val NUM_NEBULAE = 4

private val NEBULA_COLORS = listOf(
    Color(127, 255, 160), Color(150, 255, 200), Color(180, 255, 140), Color(105, 255, 185),
)

/** How far each tier shifts with the tilt (1 = the farthest layer): bright stars are the nearest. */
private val TIER_DEPTH = floatArrayOf(0.4f, 0.65f, 1f, 1f)

/**
 * The ambient sky behind everything: 0stems' drifting digital cosmos (mint nebulae, wind) with real
 * stars in it and a few deep-sky objects far behind ([DeepSky]). 0stems' two-character hex labels
 * beside the stars are gone (owner, 2026-10-18: "stray ids" over the sky): in an app whose sessions
 * have hex ids, they read as sessions.
 *
 * The stars are drawn as a telescope photographs them, in the same family as the sun ([Solar]'s
 * star): each a white-hot core in its own colour, a soft halo, and for the brighter ones thin
 * diffraction spikes. Their colours follow temperature in a real sky's proportions (a few
 * blue-white, more white and yellow, most of them faint orange and red), and so does their
 * brightness: a few bright, many faint. They don't twinkle: there's no air in space. The sky
 * comes from its own seed ([SkyPictures]), so it's the same sky at every launch.
 */
internal class Sky {
    val pictures = SkyPictures()
    private val deep = DeepSky()
    private var w = 0f
    private var h = 0f
    private var dp = 1f
    private var built = false
    private var lastT = -1f
    /** When the stars' sprites were first drawn (wall-clock ms): they fade in. 0 = not yet. */
    private var starsAt = 0L

    // Stars, struct-of-arrays.
    private val x = FloatArray(NUM_STARS)
    private val y = FloatArray(NUM_STARS)
    private val tier = IntArray(NUM_STARS)
    private val colour = IntArray(NUM_STARS)
    private val scale = FloatArray(NUM_STARS)
    private val baseAlpha = FloatArray(NUM_STARS)
    private val vx = FloatArray(NUM_STARS)
    private val vy = FloatArray(NUM_STARS)

    // Nebulae.
    private val nx = FloatArray(NUM_NEBULAE)
    private val ny = FloatArray(NUM_NEBULAE)
    private val nr = FloatArray(NUM_NEBULAE)
    private val nAlpha = FloatArray(NUM_NEBULAE)
    private val nvx = FloatArray(NUM_NEBULAE)
    private val nvy = FloatArray(NUM_NEBULAE)
    private val nPhase = FloatArray(NUM_NEBULAE)
    private val nSpeed = FloatArray(NUM_NEBULAE)
    private val nColor = arrayOfNulls<Color>(NUM_NEBULAE)
    private val nBrush = arrayOfNulls<Brush>(NUM_NEBULAE)

    private var fontScale = 1f
    /** When the field was built (wall-clock ms): the nebulae fade in with the stars. 0 = not yet. */
    private var builtAt = 0L

    fun ensure(width: Float, height: Float, density: Density) {
        if (!built) {
            w = width; h = height; dp = density.density; fontScale = density.fontScale
            // The field comes from the sky's seed, read on the pictures' thread in a moment.
            val seed = pictures.seed ?: return
            build(Random(seed))
            built = true
            return
        }
        if (width == w && height == h && density.density == dp && density.fontScale == fontScale) return
        val resized = width != w || height != h
        val oldW = w
        val oldH = h
        w = width; h = height; dp = density.density; fontScale = density.fontScale
        if (resized) {
            // Resize (rotation, split screen): keep the same sky, scaled to the new bounds.
            val sx = w / oldW
            val sy = h / oldH
            for (i in 0 until NUM_STARS) { x[i] *= sx; y[i] *= sy }
            for (i in 0 until NUM_NEBULAE) {
                nx[i] *= sx; ny[i] *= sy; nr[i] *= min(sx, sy)
                nBrush[i] = nebulaBrush(nColor[i]!!, nr[i])
            }
        }
    }

    private fun nebulaBrush(c: Color, r: Float) = Brush.radialGradient(
        0f to c, 0.3f to c.copy(alpha = 0.55f), 0.6f to c.copy(alpha = 0.2f), 1f to c.copy(alpha = 0f),
        center = Offset.Zero, radius = r,
    )

    private fun Random.range(min: Float, max: Float) = min + nextFloat() * (max - min)

    private fun build(rnd: Random) {
        val minSide = min(w, h)
        for (i in 0 until NUM_NEBULAE) {
            nx[i] = rnd.nextFloat() * w
            ny[i] = rnd.nextFloat() * h
            // 0stems scales the radius by dpr twice (once in minSide, once when drawing),
            // so on a phone (dpr capped at 1.5) clouds are minSide × 0.45–0.825.
            nr[i] = minSide * rnd.range(0.30f, 0.55f) * 1.5f
            nAlpha[i] = rnd.range(0.07f, 0.14f)
            nvx[i] = rnd.range(-0.25f, 0.25f)
            nvy[i] = rnd.range(-0.25f, 0.25f)
            nPhase[i] = rnd.nextFloat() * 2 * PI.toFloat()
            nSpeed[i] = rnd.range(0.08f, 0.18f)
            nColor[i] = NEBULA_COLORS[i % NEBULA_COLORS.size]
            nBrush[i] = nebulaBrush(nColor[i]!!, nr[i])
        }
        val cx = FloatArray(NUM_CLUSTERS) { rnd.nextFloat() * w }
        val cy = FloatArray(NUM_CLUSTERS) { rnd.nextFloat() * h }
        val cr = FloatArray(NUM_CLUSTERS) { minSide * rnd.range(0.12f, 0.22f) }
        for (i in 0 until NUM_STARS) {
            if (rnd.nextFloat() < 0.5f) {
                val c = rnd.nextInt(NUM_CLUSTERS)
                val a = rnd.nextFloat() * 2 * PI.toFloat()
                val d = rnd.nextFloat().pow(0.6f) * cr[c]
                x[i] = ((cx[c] + cos(a) * d) % w + w) % w
                y[i] = ((cy[c] + sin(a) * d) % h + h) % h
            } else {
                x[i] = rnd.nextFloat() * w
                y[i] = rnd.nextFloat() * h
            }
            val t = StarSprites.pickTier(rnd)
            tier[i] = t
            colour[i] = StarSprites.pickColor(rnd, t)
            scale[i] = rnd.range(0.85f, 1.15f)
            baseAlpha[i] = when (t) {
                0 -> rnd.range(0.8f, 1f)
                1 -> rnd.range(0.6f, 0.9f)
                2 -> rnd.range(0.5f, 0.85f)
                else -> rnd.range(0.35f, 0.7f)
            }
            val speed = if (t == 0) rnd.range(0.08f, 0.18f) else rnd.range(0.15f, 0.55f)
            val a = rnd.nextFloat() * 2 * PI.toFloat()
            vx[i] = cos(a) * speed
            vy[i] = sin(a) * speed
            // These draws picked when each star burned out (the old weekly limit ended the sky)
            // and the hex labels the sky no longer has; they're still made, so the stars after
            // this one stay where they were (it's the same sky as before).
            rnd.nextFloat()
            val labelled = when (t) { 0 -> true; 1 -> rnd.nextFloat() < 0.4f; 2 -> rnd.nextFloat() < 0.08f; else -> false }
            if (labelled) { rnd.nextInt(16); rnd.nextInt(16) }
        }
    }

    fun step(t: Float) {
        // Up to a quarter second: the far sky drifts at a few frames a second, and after it held
        // still behind a screen it carries on from where it was instead of jumping.
        val dt = if (lastT < 0) 0f else (t - lastT).coerceIn(0f, 0.25f)
        lastT = t
        if (dt == 0f || !built) return
        val windA = t * 0.04f
        val windX = cos(windA) * 0.4f
        val windY = sin(windA) * 0.4f
        for (i in 0 until NUM_NEBULAE) {
            nx[i] += (nvx[i] + windX * 0.5f) * dt * dp
            ny[i] += (nvy[i] + windY * 0.5f) * dt * dp
            val m = nr[i]
            if (nx[i] < -m) nx[i] = w + m
            if (nx[i] > w + m) nx[i] = -m
            if (ny[i] < -m) ny[i] = h + m
            if (ny[i] > h + m) ny[i] = -m
        }
        for (i in 0 until NUM_STARS) {
            x[i] += (vx[i] + windX) * dt * dp
            y[i] += (vy[i] + windY) * dt * dp
            if (x[i] < 0) x[i] += w
            if (x[i] > w) x[i] -= w
            if (y[i] < 0) y[i] += h
            if (y[i] > h) y[i] -= h
        }
    }

    fun drawAmbient(
        s: DrawScope, t: Float, now: Long, reduced: Boolean, skyTop: Float, skyBottom: Float,
        light: Float = 1f,
        /** 1 when the sky is behind text (a transcript, files, onboarding): stars and labels step back. */
        behind: Float = 0f,
        /** Depth parallax (px) for the deepest layer; nearer layers shift less. */
        parX: Float = 0f, parY: Float = 0f,
    ) = with(s) {
        drawRect(HN.bg)
        if (!built) return@with
        val canvas = drawContext.canvas.nativeCanvas
        // The week: nebulae thin out faster than stars as it fills.
        val nebLight = light
        val starLight = if (light <= 0.001f) 0f else (0.4f + 0.6f * light)
        // The field waits for the sky's seed at launch; when it arrives the nebulae come out over
        // a moment, as the stars do, rather than popping in at full strength.
        if (builtAt == 0L) builtAt = now
        val nebIn = if (reduced) 1f else ((now - builtAt) / 700f).coerceIn(0f, 1f)
        // Nebulae are the farthest layer, so they shift the most as the phone tilts.
        for (i in 0 until NUM_NEBULAE) {
            val breath = 0.6f + 0.4f * sin(t * nSpeed[i] + nPhase[i])
            translate(nx[i] + parX * 1.3f, ny[i] + parY * 1.3f) {
                drawCircle(nBrush[i]!!, radius = nr[i], center = Offset.Zero, alpha = nAlpha[i] * breath * nebLight * nebIn)
            }
        }
        // Galaxies, nebulae and a cluster far behind everything: as far away as the nebulae's layer.
        deep.draw(canvas, pictures, w, h, dp, h * skyBottom, light, behind, parX * 1.3f, parY * 1.3f, now, reduced)
        val sprites = pictures.stars ?: return@with
        if (starsAt == 0L) starsAt = now
        // They come out over a moment at launch, while their sprites are made.
        val starsIn = if (reduced) 1f else ((now - starsAt) / 700f).coerceIn(0f, 1f)
        val bandA = h * skyTop - 16 * dp
        val bandB = h * skyBottom + 16 * dp
        for (i in 0 until NUM_STARS) {
            val tr = tier[i]
            val inBand = y[i] in bandA..bandB
            // Inside the systems' band, ambient stars step back a little so the systems lead.
            val a = baseAlpha[i] * (if (inBand) 0.6f else 1f) * starLight * (1f - 0.55f * behind) * starsIn
            if (a <= 0.002f) continue
            // Bright stars are the nearest layer, faint ones the farthest: they shift the most.
            // The shift is a few dp at most, so a star near an edge just slides off it a little;
            // wrapping it round would make it jump across the screen as the phone moves.
            val depth = TIER_DEPTH[tr]
            val sx = x[i] + parX * depth
            val sy = y[i] + parY * depth
            sprites.draw(canvas, sx, sy, tr, colour[i], scale[i], min(1f, a))
        }
    }
}
