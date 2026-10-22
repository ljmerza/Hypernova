package com.prudnikweb.hypernova.ui.fx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalContext
import com.prudnikweb.hypernova.R
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Everything the cosmos needs to survive configuration changes: the ambient sky
 * (star positions, nebulae) and the solar system (planets, the disk's cycle, any
 * timeline in progress). Keep one per app in a ViewModel.
 */
@Stable
class CosmosState {
    private val used = mutableFloatStateOf(0f)
    private val blasts = mutableIntStateOf(0)
    private val events = mutableIntStateOf(0)
    private val eclipseLine = mutableStateOf<String?>(null)
    private val demoEclipseLine = mutableStateOf<String?>(null)
    private val readoutBottom = mutableFloatStateOf(0f)
    private val hintLine = mutableStateOf(false)
    internal val sky = Sky()
    /** The white dwarf sessions on other providers orbit (Companion.kt). */
    internal val companion = Companion()
    /** Codex's star, while both agents are in use (CodexStar.kt). */
    internal val codexStar = CodexStar()
    private var splitKey: List<Session>? = null
    private var splitStar = false
    private var splitOwn: List<Session> = emptyList()

    /**
     * The sun's sessions of [all] ([SkySplit]); the companion and Codex's star get theirs. The same
     * list comes back for the same list given (the sun lays itself out again only when it changes).
     */
    internal fun split(all: List<Session>, codex: Boolean, now: Long): List<Session> {
        if (all === splitKey && codex == splitStar) return splitOwn
        splitKey = all
        splitStar = codex
        splitOwn = SkySplit.sun(all, codex)
        companion.use(SkySplit.moons(all, SkySplit.Body.Companion, codex, now))
        codexStar.use(if (codex) SkySplit.moons(all, SkySplit.Body.Codex, codex, now) else emptyList())
        return splitOwn
    }
    internal val solar = Solar().also {
        it.onUsedBottom = { px -> if (px != used.floatValue) used.floatValue = px }
        it.onReadoutBottom = { px -> readoutBottom.floatValue = px }
        it.onBlast = { blasts.intValue++ }
        it.onEvent = { events.intValue++ }
    }

    /**
     * An eclipse of the sun, for states where the app can't follow usage (signed out, plan
     * ended): a dark disc slides over the sun, leaving its corona and a thin bright edge, and
     * this line (the caller's words) sits under it. Null ends it, with a diamond ring.
     */
    var eclipse: String?
        get() = eclipseLine.value
        set(v) { eclipseLine.value = v }

    /** The demo's eclipse (long-press the sun), kept apart so it never sticks to a real Mac's sky. */
    var demoEclipse: String?
        get() = demoEclipseLine.value
        set(v) { demoEclipseLine.value = v }

    /**
     * Increments when something happens in the sky while it's watched (a limit, the week's low
     * state, the outburst at a reset), so the home list can bring the sky into view.
     */
    val eventCount: Int get() = events.intValue

    /**
     * The band's bottom (window px) for what's in the sky now: the home list starts right under it,
     * so a sky with less in it is shorter. 0 until the sky has been drawn.
     */
    val usedBottomPx: Float get() = used.floatValue

    /** Where the sun's readout ends (window px), for the line under it; read it in a layout or draw block. */
    val readoutBottomPx: Float get() = readoutBottom.floatValue

    /** The line under the readout ("Tap the sun…") is shown: the band leaves room for it. */
    var hint: Boolean
        get() = hintLine.value
        set(v) { hintLine.value = v }

    /** Increments on every limit hit while the sky watched (the disk draining). */
    val blastCount: Int get() = blasts.intValue

    /** Limits already felt, kept here so a recreated Activity doesn't buzz again. */
    var blastsFelt = 0

    /** What's under [pos] (window coordinates): a companion's or Codex's moon, Codex's star, a planet or its words, the belt's words, or the sun. */
    fun hitAt(pos: Offset): CosmosHit? = companion.hit(pos) ?: codexStar.hit(pos) ?: solar.hit(pos)

    /** Projects with planets in the sky, most urgent first (for accessibility actions). */
    fun projects(): List<String> = solar.projectNames()
}

/**
 * The digital cosmos, ported from 0stems `DigitalCosmos.tsx` (same tiers, nebulae, wind and
 * breathing; not its hex labels, which read as session ids here) as the ambient sky, with the
 * solar system on top: the sun is an accretion disk that is your 5-hour usage, projects that
 * matter now are planets, the rest a belt of quiet ones, sessions are moons (see [Solar],
 * [AccretionDisk], [SkyPlanner]). The week's limit is the disk's low state ([LowState]); the far
 * sky thins a little as the week fills.
 *
 * Two layers, each redrawn only as often as it moves ([Pace]): the far sky (stars, nebulae,
 * the deep-sky objects), which drifts about a dp a second, and the solar system over it. The
 * far sky is kept as a picture of its own between its frames, and only it feeds the edge
 * bars' blur ([backdrop]), so a planet moving no longer means blurring the bars again.
 *
 * @param focus 1 on the home screen; 0 behind a session (the solar system fades out,
 *   and the far sky holds still behind the text).
 * @param skyTop where the system's band starts (fraction of height), below the top bar.
 * @param plan which projects are planets and which are quiet ([SkyPlanner.plan] of [sessions]).
 * @param selected what the home list is showing (a project, or the quiet ones), or null.
 * @param backdrop applied to the far sky's layer only: the blur source the edge bars read.
 */
@Composable
fun Cosmos(
    sessions: List<Session>,
    usage: Usage,
    state: CosmosState,
    modifier: Modifier = Modifier,
    focus: () -> Float = { 1f },
    skyTop: Float = 0.12f,
    plan: SkyPlanner.Plan = SkyPlanner.Plan.Empty,
    selected: SkyFilter? = null,
    /** The providers, for the companion star's words and trouble (sessions on others orbit it). */
    providers: List<com.prudnikweb.hypernova.data.Provider> = emptyList(),
    /** Codex on the Mac, for its star's light and words. */
    codex: com.prudnikweb.hypernova.data.CodexState? = null,
    /** Both agents in use (data/AgentLead.kt): Codex has a star of its own, and its sessions go round it. */
    codexStar: Boolean = false,
    backdrop: Modifier = Modifier,
) {
    val clock = LocalAmbient.current
    val motion = LocalMotion.current
    val reduced = motion.reduced
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val currentSessions = rememberUpdatedState(sessions)
    val currentUsage = rememberUpdatedState(usage)
    val currentSelected = rememberUpdatedState(selected)
    val currentPlan = rememberUpdatedState(plan)
    val res = LocalContext.current.resources
    state.solar.text = remember(res) {
        SolarText(
            readout = { pct -> res.getString(R.string.percent, pct) },
            unknown = res.getString(R.string.sun_unknown),
            refills = { left -> res.getString(R.string.refills_in, left) },
            duration = { ms -> formatDuration(res, ms) },
            status = { doing, n, ago -> skyStatus(res, doing, n, ago) },
            quiet = { n -> res.getString(R.string.sky_quiet, n) },
        )
    }
    state.companion.text = remember(res) {
        CompanionText(
            providers = { n -> res.getString(R.string.companion_providers, n) },
            status = { doing, n, ago -> skyStatus(res, doing, n, ago) },
        )
    }
    state.companion.providers = providers
    state.codexStar.text = remember(res) {
        CodexStarText(
            name = res.getString(R.string.cx_agent_codex).uppercase(),
            percent = { pct -> res.getString(R.string.percent, pct) },
            status = { doing, n, ago -> skyStatus(res, doing, n, ago) },
            signedOut = res.getString(R.string.cx_star_signed_out),
            refills = { left -> res.getString(R.string.refills_in, left) },
            duration = { ms -> formatDuration(res, ms) },
        )
    }
    state.codexStar.codex = codex
    state.codexStar.wanted = codexStar
    val currentStar = rememberUpdatedState(codexStar)
    // With Reduce Motion the ambient clock is frozen; countdowns and timelines still move
    // on the wall clock, so redraw once a second.
    val tick = remember { mutableIntStateOf(0) }
    if (reduced) LaunchedEffect(Unit) { while (true) { delay(1000); tick.intValue++ } }
    val sky = state.sky
    val solar = state.solar
    val currentFocus = rememberUpdatedState(focus)
    // The phone's tilt moves the sky's layers a little, only while the sky is in front of you
    // (and not on a hot phone: it means a gyroscope at 30Hz and live frames to show it).
    val parallax = rememberParallax(enabled = !reduced && FrameBudget.parallax(motion.heat)) { currentFocus.value() > 0.3f }
    val frame = remember { SkyFrame(sky) }
    // The usage without its percentages (the per-model weeks too, which the sky doesn't draw):
    // changes only when a limit starts, ends or moves its reset.
    val limits = remember { derivedStateOf { currentUsage.value.copy(fiveHour = 0f, sevenDay = 0f, models = emptyList()) } }
    val context = LocalContext.current
    Box(modifier) {
        // ── The far sky ──
        Spacer(
            Modifier
                .fillMaxSize()
                .then(backdrop)
                // Rendered into a picture of its own and reused until it changes (a few times a
                // second while it drifts, never while it holds still), instead of 200 stars, the
                // nebulae and the galaxies being drawn again on every frame of the planets.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawBehind {
                    // The latest tick's time; which frames to ask for is decided at the end.
                    val t = clock.now
                    if (reduced) tick.intValue
                    // A tilt redraws at once, even between this layer's own frames.
                    parallax.moved.intValue
                    val u = if (reduced) currentUsage.value else Snapshot.withoutReadObservation { currentUsage.value }
                    sky.pictures.prepare(context, density)
                    sky.ensure(size.width, size.height, this)
                    sky.step(t)
                    val f = focus().coerceIn(0f, 1f)
                    // Behind text (a transcript, files, onboarding) the ambient sky steps back too,
                    // so bright anchor stars never sit under a line of text.
                    val light = solar.galaxyLight(u, reduced)
                    parallax.step(t)
                    val shift = 9.dp.toPx()
                    val now = System.currentTimeMillis()
                    frame.set(
                        t, now, reduced, solar.bandTopPx() / size.height, solar.bandBottomPx() / size.height, light,
                        behind = 1f - f, parX = parallax.x * shift, parY = parallax.y * shift,
                    )
                    frame.draw(this)
                    if (!reduced) {
                        val pace = when {
                            // Pictures fading in at launch, or the sky's light changing with a limit or a reset.
                            sky.pictures.settling(now) || solar.busy -> Pace.Live
                            f <= 0.01f -> null // behind a screen's text: it holds still
                            parallax.gliding -> Pace.Live
                            else -> Pace.Slow // stars and nebulae drift about a dp a second
                        }
                        if (pace != null) clock.seconds(pace)
                    }
                },
        )
        // ── The solar system ──
        Spacer(
            Modifier.fillMaxSize().drawBehind {
                val t = clock.now
                if (reduced) tick.intValue
                // Read without asking to be redrawn: while the system is in view its own frames pick
                // up a new reading or session, and while it's hidden they change nothing on screen
                // (a busy Mac updates its sessions several times a second). Only a limit starting
                // or ending redraws a hidden sky, so its timeline is where it should be when it shows.
                limits.value
                val u = Snapshot.withoutReadObservation { currentUsage.value }
                val ss = Snapshot.withoutReadObservation { currentSessions.value }
                val plan = Snapshot.withoutReadObservation { currentPlan.value }
                // The selection and the hint's line are drawn the moment they change.
                val selected = currentSelected.value
                solar.hint = state.hint
                solar.eclipse = state.eclipse ?: state.demoEclipse
                val f = focus().coerceIn(0f, 1f)
                val now = System.currentTimeMillis()
                // Sessions on other providers orbit the companion, Codex's (while both agents are in use)
                // Codex's star, both in the bottom row; the rest are the sun's.
                val star = Snapshot.withoutReadObservation { currentStar.value }
                val own = state.split(ss, star, now)
                solar.draw(this, t, own, plan, u, f, skyTop, selected, state.companion.inSky, measurer, reduced, now, codex = state.codexStar.inSky)
                state.companion.draw(this, t, now, f, solar, measurer, reduced)
                state.codexStar.draw(this, now, f, solar, measurer, reduced)
                if (!reduced) {
                    val pace = when {
                        solar.busy -> Pace.Live
                        // Hidden: nothing to draw, unless a limit's reset is due on the wall clock.
                        f <= 0.01f -> if (solar.waiting) Pace.Calm else null
                        // Motion means something: a project at work turns, things arrive and leave.
                        solar.moving || state.companion.moving || state.companion.fading ||
                            state.codexStar.moving || state.codexStar.fading -> Pace.Live
                        // A bracket drawing in (needs you).
                        solar.signalling || state.companion.signalling || state.codexStar.signalling -> Pace.Calm
                        // At rest nothing moves: a still disk, still planets. The far sky's few frames do.
                        else -> Pace.Slow
                    }
                    if (pace != null) {
                        clock.seconds(pace)
                        // The whole sky is moving anyway, so the grain may change with it.
                        if (f > 0.3f && pace != Pace.Slow) clock.backdropMoving()
                    }
                }
            },
        )
    }
}

/**
 * One frame of the ambient sky, kept in fields so it can be recorded into the lens's layer
 * without building a new lambda every frame.
 */
private class SkyFrame(private val sky: Sky) {
    private var t = 0f
    private var now = 0L
    private var reduced = false
    private var top = 0f
    private var bottom = 1f
    private var light = 1f
    private var behind = 0f
    var parX = 0f
        private set
    var parY = 0f
        private set

    fun set(t: Float, now: Long, reduced: Boolean, top: Float, bottom: Float, light: Float, behind: Float, parX: Float, parY: Float) {
        this.t = t; this.now = now; this.reduced = reduced; this.top = top; this.bottom = bottom; this.light = light
        this.behind = behind; this.parX = parX; this.parY = parY
    }

    val draw: DrawScope.() -> Unit = {
        val f = this@SkyFrame
        sky.drawAmbient(this, f.t, f.now, f.reduced, f.top, f.bottom, f.light, f.behind, f.parX, f.parY)
    }
}

/**
 * A planet's (or the companion's) second line: "needs you" / "2 need you", "working" / "3 working",
 * or how long ago its project last did something ("just now", "4m ago", "2h ago", "3d ago").
 */
fun skyStatus(res: android.content.res.Resources, doing: SkyPlanner.Doing, n: Int, agoMs: Long): String = when (doing) {
    SkyPlanner.Doing.NeedsYou -> res.getQuantityString(R.plurals.sky_needs_you, n, n)
    SkyPlanner.Doing.Working -> res.getQuantityString(R.plurals.sky_working, n, n)
    SkyPlanner.Doing.Resting -> {
        val s = agoMs / 1000L
        when {
            s < 45 -> res.getString(R.string.sky_just_now)
            s < 3600 -> res.getString(R.string.sky_ago, res.getString(R.string.ago_minutes, maxOf(1L, s / 60)))
            s < 86_400 -> res.getString(R.string.sky_ago, res.getString(R.string.ago_hours, s / 3600))
            else -> res.getString(R.string.sky_ago, res.getString(R.string.ago_days, s / 86_400))
        }
    }
}

/**
 * "0:24", "12m", "1h 12m", "2d 4h". Seconds round up, as every countdown here does ([Countdown]):
 * two countdowns to the same moment then always read the same.
 */
fun formatDuration(res: android.content.res.Resources, ms: Long): String {
    val s = Countdown.seconds(ms, 0L)
    return when {
        s < 60 -> res.getString(R.string.dur_s, 0, s)
        s < 600 -> res.getString(R.string.dur_s, s / 60, s % 60)
        s < 3600 -> res.getString(R.string.dur_m, s / 60)
        s < 86400 -> res.getString(R.string.dur_hm, s / 3600, (s % 3600) / 60)
        else -> res.getString(R.string.dur_dh, s / 86400, (s % 86400) / 3600)
    }
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
        // Inside the solar system's band the ambient stars step back a little, so the system leads.
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
