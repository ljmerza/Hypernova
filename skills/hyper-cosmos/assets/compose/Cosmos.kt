package com.prudnikweb.hypernova.ui.fx

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import com.prudnikweb.hypernova.data.Usage
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalContext
import com.prudnikweb.hypernova.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.JetBrainsMono
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Manrope
import com.prudnikweb.hypernova.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Everything the cosmos needs to survive configuration changes: the ambient sky
 * (star positions, nebulae) and the solar system (planets, the sun's life, any
 * supernova in progress). Keep one per app in a ViewModel.
 */
@Stable
class CosmosState {
    private val used = mutableFloatStateOf(0f)
    private val blasts = mutableIntStateOf(0)
    private val overlay = mutableStateOf(false)
    private val death = mutableFloatStateOf(0f)
    internal val sky = Sky()
    internal val solar = Solar().also {
        it.onUsedBottom = { px -> if (px != used.floatValue) used.floatValue = px }
        it.onBlast = { blasts.intValue++ }
        it.onOverlay = { on -> overlay.value = on }
        it.onDeath = { d -> death.floatValue = d }
    }

    /**
     * 0 = alive, 1 = dead. After a supernova and while the universe cools, the whole app
     * drains to grey-black; colour returns with the rebirth or the Big Bang. Read it only
     * inside a graphicsLayer block, so it never recomposes anything.
     */
    val deathLevel: Float get() = death.floatValue

    private var effectStep = -1
    private var effect: androidx.compose.ui.graphics.RenderEffect? = null

    /** The colour-drain effect for the current death level (cached per 1/40 step). */
    fun deathEffect(): androidx.compose.ui.graphics.RenderEffect? {
        val d = death.floatValue
        if (d <= 0.005f || android.os.Build.VERSION.SDK_INT < 31) return null
        val step = (d * 40).toInt()
        if (step != effectStep) {
            effectStep = step
            val k = step / 40f
            val m = android.graphics.ColorMatrix().apply { setSaturation(1f - 0.97f * k) }
            val dim = 1f - 0.42f * k
            m.postConcat(android.graphics.ColorMatrix().apply { setScale(dim, dim, dim, 1f) })
            effect = android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(m)).asComposeRenderEffect()
        }
        return effect
    }

    /** Lowest point (window px) the solar system occupies, so the home sky window can grow to fit. */
    val usedBottomPx: Float get() = used.floatValue

    /** Increments on every (fresh) supernova. */
    val blastCount: Int get() = blasts.intValue

    /** Supernovas already felt, kept here so a recreated Activity doesn't buzz again. */
    var blastsFelt = 0

    /** True while the supernova / rebirth / Big Bang overlay has something to draw. */
    val overlayOn: Boolean get() = overlay.value

    /** What's under [pos] (window coordinates): a moon, a planet or its label, or the sun. */
    fun hitAt(pos: Offset, zoom: String?): CosmosHit? = solar.hit(pos, zoom)

    /** Projects currently in the sky (for accessibility actions). */
    fun projects(): List<String> = solar.projectNames()
}

/**
 * The digital cosmos, ported from 0stems `DigitalCosmos.tsx` (same tiers, nebulae,
 * wind, breathing and hex labels) as the ambient sky, with the solar system on top:
 * the sun is your 5-hour usage, projects are planets, sessions are moons (see [Solar]).
 * The 7-day budget is the universe itself: it thins out, goes dark, and is reborn.
 *
 * @param focus 1 on the home screen; 0 behind a session (the solar system fades out,
 *   the ambient sky keeps drifting).
 * @param skyTop where the system's band starts (fraction of height), below the top bar.
 * @param zoom the project the camera is closing in on, or null.
 */
@Composable
fun Cosmos(
    sessions: List<Session>,
    usage: Usage,
    state: CosmosState,
    modifier: Modifier = Modifier,
    focus: () -> Float = { 1f },
    skyTop: Float = 0.12f,
    zoom: String? = null,
) {
    val clock = LocalAmbient.current
    val reduced = LocalMotion.current.reduced
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val currentSessions = rememberUpdatedState(sessions)
    val currentUsage = rememberUpdatedState(usage)
    val currentZoom = rememberUpdatedState(zoom)
    val res = LocalContext.current.resources
    state.solar.text = remember(res) {
        SolarText(
            readout = { pct, left -> res.getString(R.string.sun_readout, pct, left) },
            unknown = res.getString(R.string.sun_unknown),
            reborn = { left -> res.getString(R.string.reborn_in, left) },
            universe = { left -> res.getString(R.string.universe_returns, left) },
            duration = { ms -> formatDuration(res, ms) },
        )
    }
    // With Reduce Motion the ambient clock is frozen; countdowns and timelines still move
    // on the wall clock, so redraw once a second.
    val tick = remember { mutableIntStateOf(0) }
    if (reduced) LaunchedEffect(Unit) { while (true) { delay(1000); tick.intValue++ } }
    val sky = state.sky
    val solar = state.solar
    Spacer(
        modifier.drawBehind {
            val t = clock.seconds
            if (reduced) tick.intValue
            val u = if (reduced) currentUsage.value else Snapshot.withoutReadObservation { currentUsage.value }
            sky.ensure(size.width, size.height, this)
            sky.step(t)
            val f = focus().coerceIn(0f, 1f)
            // Behind text (a transcript, files, onboarding) the ambient sky steps back too,
            // so bright anchor stars never sit under a line of text.
            val light = solar.galaxyLight(u, reduced)
            sky.drawAmbient(this, t, measurer, solar.bandTopPx() / size.height, solar.bandBottomPx() / size.height, light, solar.revealCenter(), solar.revealRadius(), behind = 1f - f)
            val ss = if (reduced) currentSessions.value else Snapshot.withoutReadObservation { currentSessions.value }
            solar.draw(this, t, ss, u, f, skyTop, currentZoom.value, measurer, reduced, System.currentTimeMillis())
        },
    )
}

/** The supernova's flash, shockwaves and debris (and the Big Bang), over the whole UI. */
@Composable
fun SupernovaOverlay(state: CosmosState, modifier: Modifier = Modifier) {
    if (!state.overlayOn) return
    val reduced = LocalMotion.current.reduced
    // The dramatic moment runs at the panel's full rate (it lasts seconds), not the 30fps ambient clock.
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame.longValue = it } }
    Spacer(
        modifier
            .graphicsLayer()
            .drawBehind {
                frame.longValue
                state.solar.drawOverlay(this, reduced)
            },
    )
}

/** "0:24", "12m", "1h 12m", "2d 4h". */
fun formatDuration(res: android.content.res.Resources, ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> res.getString(R.string.dur_s, 0, s)
        s < 600 -> res.getString(R.string.dur_s, s / 60, s % 60)
        s < 3600 -> res.getString(R.string.dur_m, s / 60)
        s < 86400 -> res.getString(R.string.dur_hm, s / 3600, (s % 3600) / 60)
        else -> res.getString(R.string.dur_dh, s / 86400, (s % 86400) / 3600)
    }
}

private const val NUM_PARTICLES = 95
private const val NUM_CLUSTERS = 6
private const val NUM_NEBULAE = 4
private const val ANCHOR_RATIO = 0.07f
private const val MID_RATIO = 0.28f
private const val HEX = "0123456789abcdef"

private val NEBULA_COLORS = listOf(
    Color(127, 255, 160), Color(150, 255, 200), Color(180, 255, 140), Color(105, 255, 185),
)

private fun rnd(min: Float, max: Float) = min + Random.nextFloat() * (max - min)

internal class Sky {
    private var w = 0f
    private var h = 0f
    private var dp = 1f
    private var built = false
    private var lastT = -1f

    // Ambient stars, struct-of-arrays.
    private val x = FloatArray(NUM_PARTICLES)
    private val y = FloatArray(NUM_PARTICLES)
    private val starSize = FloatArray(NUM_PARTICLES)
    private val baseAlpha = FloatArray(NUM_PARTICLES)
    private val vx = FloatArray(NUM_PARTICLES)
    private val vy = FloatArray(NUM_PARTICLES)
    private val phase = FloatArray(NUM_PARTICLES)
    private val phaseSpeed = FloatArray(NUM_PARTICLES)
    private val twPhase = FloatArray(NUM_PARTICLES)
    private val twSpeed = FloatArray(NUM_PARTICLES)
    private val anchor = BooleanArray(NUM_PARTICLES)
    private val label = arrayOfNulls<String>(NUM_PARTICLES)
    private val labelLayout = arrayOfNulls<TextLayoutResult>(NUM_PARTICLES)

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


    private val hexStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 9.sp)

    private var fontScale = 1f

    fun ensure(width: Float, height: Float, density: Density) {
        if (width == w && height == h && density.density == dp && density.fontScale == fontScale) return
        val resized = width != w || height != h
        val oldW = w
        val oldH = h
        w = width; h = height; dp = density.density; fontScale = density.fontScale
        // Text was measured for the old density / font scale.
        labelLayout.fill(null)
        if (!built) {
            build()
            built = true
        } else if (resized) {
            // Resize (rotation, split screen): keep the same sky, scaled to the new bounds.
            val sx = w / oldW
            val sy = h / oldH
            for (i in 0 until NUM_PARTICLES) { x[i] *= sx; y[i] *= sy }
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

    private fun build() {
        val minSide = min(w, h)
        for (i in 0 until NUM_NEBULAE) {
            nx[i] = Random.nextFloat() * w
            ny[i] = Random.nextFloat() * h
            // 0stems scales the radius by dpr twice (once in minSide, once when drawing),
            // so on a phone (dpr capped at 1.5) clouds are minSide × 0.45–0.825.
            nr[i] = minSide * rnd(0.30f, 0.55f) * 1.5f
            nAlpha[i] = rnd(0.07f, 0.14f)
            nvx[i] = rnd(-0.25f, 0.25f)
            nvy[i] = rnd(-0.25f, 0.25f)
            nPhase[i] = Random.nextFloat() * 2 * PI.toFloat()
            nSpeed[i] = rnd(0.08f, 0.18f)
            nColor[i] = NEBULA_COLORS[i % NEBULA_COLORS.size]
            nBrush[i] = nebulaBrush(nColor[i]!!, nr[i])
        }
        val cx = FloatArray(NUM_CLUSTERS) { Random.nextFloat() * w }
        val cy = FloatArray(NUM_CLUSTERS) { Random.nextFloat() * h }
        val cr = FloatArray(NUM_CLUSTERS) { minSide * rnd(0.12f, 0.22f) }
        for (i in 0 until NUM_PARTICLES) {
            val r = Random.nextFloat()
            val isAnchor = r < ANCHOR_RATIO
            val isMid = !isAnchor && r < ANCHOR_RATIO + MID_RATIO
            if (Random.nextFloat() < 0.55f) {
                val c = Random.nextInt(NUM_CLUSTERS)
                val a = Random.nextFloat() * 2 * PI.toFloat()
                val d = Random.nextFloat().pow(0.6f) * cr[c]
                x[i] = ((cx[c] + cos(a) * d) % w + w) % w
                y[i] = ((cy[c] + sin(a) * d) % h + h) % h
            } else {
                x[i] = Random.nextFloat() * w
                y[i] = Random.nextFloat() * h
            }
            when {
                isAnchor -> { starSize[i] = rnd(2.4f, 3.6f); baseAlpha[i] = rnd(0.6f, 0.9f) }
                isMid -> { starSize[i] = rnd(1.1f, 2.0f); baseAlpha[i] = rnd(0.28f, 0.5f) }
                else -> { starSize[i] = rnd(0.5f, 1.0f); baseAlpha[i] = rnd(0.10f, 0.22f) }
            }
            val speed = if (isAnchor) rnd(0.08f, 0.18f) else rnd(0.15f, 0.55f)
            val a = Random.nextFloat() * 2 * PI.toFloat()
            vx[i] = cos(a) * speed
            vy[i] = sin(a) * speed
            phase[i] = Random.nextFloat() * 2 * PI.toFloat()
            phaseSpeed[i] = rnd(0.2f, 0.45f)
            twPhase[i] = Random.nextFloat() * 2 * PI.toFloat()
            twSpeed[i] = rnd(0.8f, 2.2f)
            anchor[i] = isAnchor
            label[i] = if (isAnchor || Random.nextFloat() < 0.15f) "${HEX[Random.nextInt(16)]}${HEX[Random.nextInt(16)]}" else null
            labelLayout[i] = null
        }
    }

    fun step(t: Float) {
        val dt = if (lastT < 0) 0f else (t - lastT).coerceIn(0f, 0.05f)
        lastT = t
        if (dt == 0f) return
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
        for (i in 0 until NUM_PARTICLES) {
            x[i] += (vx[i] + windX) * dt * dp
            y[i] += (vy[i] + windY) * dt * dp
            if (x[i] < 0) x[i] += w
            if (x[i] > w) x[i] -= w
            if (y[i] < 0) y[i] += h
            if (y[i] > h) y[i] -= h
        }
    }

    fun drawAmbient(
        s: DrawScope, t: Float, measurer: TextMeasurer, skyTop: Float, skyBottom: Float,
        light: Float = 1f, revealC: Offset = Offset.Zero, revealR: Float = Float.POSITIVE_INFINITY,
        /** 1 when the sky is behind text (a transcript, files, onboarding): stars and labels step back. */
        behind: Float = 0f,
    ) = with(s) {
        drawRect(HN.bg)
        // The 7-day budget: nebulae thin out faster than stars as the week fills, and at the
        // weekly limit both go out entirely (total darkness).
        val nebLight = light * (if (revealR.isInfinite()) 1f else (revealR / max(1f, hypot(w, h))).coerceIn(0f, 1f))
        val starLight = if (light <= 0.001f) 0f else (0.4f + 0.6f * light)
        val edge = 80 * dp
        for (i in 0 until NUM_NEBULAE) {
            val breath = 0.6f + 0.4f * sin(t * nSpeed[i] + nPhase[i])
            translate(nx[i], ny[i]) {
                drawCircle(nBrush[i]!!, radius = nr[i], center = Offset.Zero, alpha = nAlpha[i] * breath * nebLight)
            }
        }
        val gap = 7 * dp
        // Inside the session band the ambient hex labels step back, so they never read as sessions.
        val bandA = h * skyTop - 16 * dp
        val bandB = h * skyBottom + 16 * dp
        for (i in 0 until NUM_PARTICLES) {
            val breath = 0.7f + 0.3f * sin(t * phaseSpeed[i] + phase[i])
            val twinkle = 0.85f + 0.15f * sin(t * twSpeed[i] + twPhase[i])
            val inBand = y[i] in bandA..bandB
            // Inside the systems' band, ambient stars step back a little so the systems lead.
            // Big Bang: stars appear only where the light has already spread.
            val reveal = if (revealR.isInfinite()) 1f else ((revealR - hypot(x[i] - revealC.x, y[i] - revealC.y)) / edge).coerceIn(0f, 1f)
            val a = baseAlpha[i] * breath * twinkle * (if (inBand) 0.6f else 1f) * starLight * reveal * (1f - 0.55f * behind)
            if (a <= 0.002f) continue
            val c = Offset(x[i], y[i])
            val r = starSize[i] * dp
            if (anchor[i]) {
                drawCircle(HN.star, r * 4.5f, c, alpha = a * 0.05f)
                drawCircle(HN.star, r * 2.5f, c, alpha = a * 0.14f)
                drawCircle(HN.star, r * 1.4f, c, alpha = a * 0.45f)
            }
            drawCircle(HN.star, r, c, alpha = a)
            val text = label[i] ?: continue
            val layout = labelLayout[i] ?: measurer.measure(text, hexStyle).also { labelLayout[i] = it }
            drawText(
                layout, color = HN.star, alpha = a * (if (inBand) 0.2f else 0.55f) * (1f - 0.6f * behind),
                topLeft = Offset(x[i] + r + gap, y[i] - layout.size.height / 2f),
            )
        }
    }
}
