package com.prudnikweb.hypernova.ui.fx

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.Usage
import com.prudnikweb.hypernova.ui.components.PlanetLight
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.JetBrainsMono
import com.prudnikweb.hypernova.ui.theme.Manrope
import com.prudnikweb.hypernova.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/** Strings the renderer needs, resolved from resources by the composable. */
class SolarText(
    /**
     * The one number under the sun, in its ring: "72%". When the pace reaches the limit before the
     * reset, the time to it ([duration], "47m") stands there instead, in red.
     */
    val readout: (Int) -> String,
    /** Shown instead of the readout until the Mac has official numbers. */
    val unknown: String = "",
    /** Under a quiet disk, the 5-hour window's ("refills in 0:24") or the week's ("refills in 2d 4h"). */
    val refills: (String) -> String,
    val duration: (Long) -> String,         // ms → "1h 12m"
    /** A planet's second line: what its project is doing ("needs you", "2 working", "4m ago"); the last value is ms since it was active. */
    val status: (SkyPlanner.Doing, Int, Long) -> String = { _, _, _ -> "" },
    /** The belt's words: "+5 quiet". */
    val quiet: (Int) -> String = { "+$it" },
)

sealed interface CosmosHit {
    data class Project(val name: String) : CosmosHit
    /** The belt of quiet projects, or its words. */
    data object Quiet : CosmosHit
    data class Session(val id: String) : CosmosHit
    data object Sun : CosmosHit
    /** Codex's star (both agents in use): Home shows its usage card. */
    data object CodexStar : CosmosHit
}

private const val TAU = 6.2831855f
private const val PI_F = 3.1415927f
// The camera looks down on the system's plane from 20° above it, so a circular orbit in that
// plane shows as an ellipse 0.34 as tall as it is wide.
private const val SIN_E = SkyLayout.SIN_E
private const val COS_E = 0.94f
/**
 * A lifted limit only counts once its reset time has come, or once it has held this long.
 * Without this, one stale or missing reading (a reconnect, a restarted daemon, a status line
 * from an older window) lifted the limit for a moment: the disk flared, then drained again.
 */
private const val HOLD_MS = 20_000L
/** Two limits whose windows end within this of each other are the same limit (reset times jitter a little between readings). */
private const val SAME_WINDOW_MS = 120_000L
/** A reset this long past when we notice it happened while nobody watched: its outburst isn't replayed. */
private const val MISSED_MS = 10_000L
/** Seconds the moon takes to slide over (or off) the sun in an eclipse. */
private const val ECL = 3.2f
/** At the limit, seconds the last hot gas takes to drain onto the white dwarf (watched; found later it's simply quiet). */
private const val DRAIN = 2.5f
/** At the reset, seconds the heating front takes to sweep the disk from its rim to its inner edge. */
private const val FLARE = 2.2f
/** Seconds after the heating front that the outburst's first flush of light takes to settle to its steady glow. */
private const val SETTLE = 1.5f
/** Seconds from the ignition until the outburst has settled and the names and the number are back. */
private const val OUTBURST = FLARE + SETTLE + 0.5f
/** The week's outburst flushes the inner edge this much harder than the 5-hour reset's: the whole disk refilled at once. */
private const val WEEK_FLUSH = 1.5f
/** Round a quiet disk the names and the orbits step back this much: a quiescent system lights less than a disk in outburst. */
private const val QUIET_DIM = 0.3f
/** The 5-hour window, ms: the sun's ring's clock, and the donor's orbit. */
private const val FIVE_HOURS = 5 * 3_600_000L
/** A planet comes into the sky (or leaves it) in place over about this many seconds. */
private const val ARRIVE = 0.7f
/** The belt's dust: faint grains along the outermost ring. */
private const val BELT_DUST = 120
/** The belt's rocks: one for each quiet project, up to this many. */
private const val BELT_ROCKS = 12
/** A planet the list isn't showing steps back to this, while one project is shown. */
private const val UNSELECTED = 0.42f
/** The moons' innermost shell goes round in this many seconds; the outer shells follow Kepler from it. */
private const val MOON_T0 = 6f

private fun smooth(x: Float): Float { val k = x.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }
private fun expo(x: Float): Float = Motion.Expo.transform(x.coerceIn(0f, 1f))
/**
 * [x] rounded to steps of 1/[n]. Colours that change through a transition (a cooling, an
 * ignition) are taken from stepped values, so the gradient caches are reused for a few frames at
 * a time instead of a new gradient being built every frame. The steps are too small to see.
 * (round(), unlike roundToInt(), passes a NaN through rather than throwing mid-frame.)
 */
private fun steps(x: Float, n: Int): Float = round(x * n) / n
/** [from] moved toward [to] by at most [step]. */
private fun approach(from: Float, to: Float, step: Float): Float = if (from < to) min(to, from + step) else max(to, from - step)

/**
 * The one solar system on the home screen: a calm sky (owner, 2026-10-18: the old one, every
 * project a moving planet with its name riding along, looked "very overwhelming").
 *
 * - **The sun is an accretion disk, and the disk is your 5-hour usage** (owner, 2026-10-18: the
 *   supernova, black hole and reborn star weren't physics; "this should be truly from physics
 *   point of view"). A white dwarf with a disk fed by a small companion star, the way a dwarf
 *   nova is ([AccretionDisk]). The disk's hot, bright part is the budget left: a fresh window is a
 *   full disk, white-gold inside and orange to its rim; as you spend, the cooling front moves in
 *   from the rim and the spent part cools to deep red, until at the limit the last hot gas drains
 *   onto the white dwarf and the disk is quiet: cool, dim, red. The spend rate is the light: while
 *   something works, knots of gas spiral in through the hot disk at Kepler speeds and its inner
 *   edge glows; at rest nothing flows and nothing moves. Through the wait the stream from the
 *   companion refills the quiet disk, its bright spot the brightest warm thing in the system; at
 *   the reset the disk ignites again from its rim inward, the outburst, and the next window is a
 *   full disk. The companion goes once round per window: its place is the window's clock. The
 *   white dwarf survives all of it; nothing is born and nothing dies.
 * - **The week's limit is the disk's low state** (owner, 2026-10-18: "Switch to the physically
 *   honest one"; [LowState]): the companion stops feeding the disk (a VY Sculptoris low state),
 *   its stream goes out and the disk drains to nothing, the white dwarf a faint point with the
 *   companion still on its orbit. Nothing else ends: the far sky and the planets stay as they are.
 *   At the week's reset the stream comes back, refills the disk and it ignites, the outburst.
 *   The far sky thins a little as the week fills.
 * - **Planets only for what matters now** ([SkyPlanner]): a project that needs you, one at work,
 *   or one active in the last day, four at most. Each has a fixed place round the sun
 *   ([SkyLayout]) on its own orbit, drawn as a faint ring, and **stands still**: at a planet's
 *   distance a year is days, so nothing of its orbit shows in five hours. Only a working
 *   project moves (its planet turns, its moons go round, the outer shells slower by Kepler), and
 *   one that needs you holds an amber bracket. A project coming or going grows or fades where its
 *   place is. Sessions are moons. Every other project is a grain of the **belt** at the system's
 *   edge, with "+5 quiet".
 * - **Names in fixed slots** that can't overlap, each with what its project is doing ("working",
 *   "needs you", "2h ago"), never riding on a planet.
 * - **An eclipse** (signed out, plan ended): a dark disc slides over the sun, leaving the disk's
 *   scattered light and a thin bright edge, with the caller's line under it; a diamond ring when
 *   it comes back.
 * - **Selection:** the project (or the belt) the home list is showing gets a mint frame; the rest
 *   step back.
 *
 * Timelines (the drain, the outburst, the low state's drain and refill, the eclipse) run on wall-clock time, so they
 * finish even when the animation clock is frozen (Reduce Motion) or the sky is hidden. Animation
 * phases (spins, moons, the knots) run on the shared ambient clock.
 */
internal class Solar {
    var text: SolarText? = null
    /** The band's bottom (window px) for what's in the sky now: the home list starts under it. */
    var onUsedBottom: (Float) -> Unit = {}
    /** Where the sun's readout ends (window px): the hint's line goes under it. */
    var onReadoutBottom: (Float) -> Unit = {}
    /** The limit was hit while we watched: the disk drains. */
    var onBlast: () -> Unit = {}
    /** Something happened in the sky while we watched (a limit, the week's low state, an outburst). */
    var onEvent: () -> Unit = {}
    /** The sun is eclipsed (signed out, plan ended), with this line under it; null when it isn't. */
    var eclipse: String? = null
    /** The line under the readout ("Tap the sun for your statistics") is shown: the band leaves room for it. */
    var hint = false

    private var w = 0f
    private var h = 0f
    private var dp = 1f
    private var fontScale = 1f
    private var lastT = -1f
    private var dt = 0f
    private var epoch0 = -1L
    private var wt = 0f // wall-clock seconds since this Solar was created

    // A soft dark halo keeps the words legible over orbits, the belt and the disk's light.
    private val halo = Shadow(Color(0xE014110E), blurRadius = 10f)
    private val nameStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = 9.sp, letterSpacing = 0.2.em, shadow = halo)
    private val statusStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 10.sp, letterSpacing = 0.02.em, fontFeatureSettings = "ss01", shadow = halo)
    private val readoutStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, shadow = halo)
    private val bornStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = 9.sp, letterSpacing = 0.22.em, shadow = halo)

    // ─── Bodies ─────────────────────────────────────────────────────────────

    private class Moon {
        var born = -100f
        var r = Float.NaN // eased radius, so a moon never jumps in size
    }

    /**
     * A project in the sky. Its look is seeded from the name, so the sky is always *your* sky: the
     * tilt of its spin axis, its shade and where its moons start. Where it stands comes
     * from its place ([SkyLayout]): it doesn't move from there.
     */
    private class Planet(val project: String) {
        private val rnd = Random(project.hashCode())
        // The draws keep the order they had when planets orbited, so each project keeps its shade,
        // its axis and its moons' start from before; the orbit's own draws are thrown away.
        init { rnd.nextFloat() }
        val shade = rnd.nextFloat()
        init { rnd.nextFloat(); rnd.nextFloat() }
        var moonPhase = rnd.nextFloat() * 6f
        var spin = rnd.nextFloat() * 6f
        init { rnd.nextFloat(); rnd.nextFloat(); rnd.nextFloat(); rnd.nextBoolean() }
        private val roll = (0.12f + rnd.nextFloat() * 0.45f) * (if (rnd.nextBoolean()) 1f else -1f)
        private val lean = 0.32f + rnd.nextFloat() * 0.34f

        // Its spin axis A (the north pole), and the equator's basis: F faces us, G lies in the screen.
        val ax: Float; val ay: Float; val az: Float
        val fx: Float; val fy: Float; val fz: Float
        val gx: Float; val gy: Float

        init {
            val st = sin(roll); val ct = cos(roll); val sl = sin(lean); val cl = cos(lean)
            ax = st * cl; ay = -ct * cl; az = sl
            fx = -sl * st; fy = sl * ct; fz = cl
            gx = -ct; gy = -st
        }

        /** Its place round the sun (see [SkyLayout]), and where on its orbit that is. */
        var slot = 0
        var index = 0
        var angle = 0f
        /** In the sky's plan; false while it fades out of it. */
        var wanted = true
        /** How far it's in the sky (0..1): it grows in and fades out in place. */
        var pres = 0f
        /** How far it's the one the list shows (0..1, eased). */
        var sel = 0f
        var info: SkyPlanner.Project? = null
        var sessions: List<Session> = emptyList()
        var aTarget = 0f
        var aCur = Float.NaN
        var x = Float.NaN; var y = Float.NaN; var z = 0f // z toward the viewer
        var radius = 0f   // drawn radius, px (with perspective)
        var rBase = Float.NaN // eased size from its sessions' lengths, before perspective
        var ring = 0f
        val moons = HashMap<String, Moon>()
        var mx = FloatArray(0); var my = FloatArray(0); var mz = FloatArray(0)
        // Its words: the name measured for its slot's width, the status for one wording.
        var nameL: TextLayoutResult? = null
        var nameW = -1
        var statusL: TextLayoutResult? = null
        var statusKey = -1L
        /** Where its words were drawn last (px) and how visible they were: only words you can see take taps. */
        var lx = Float.NaN; var ly = 0f; var lw = 0f; var lh = 0f
        var vis = 0f
        var needs = false
        var working = false
        var base = HN.accent
        /** Minutes its sessions have run, all together. */
        var length = 0f
        var spinV = 0f

        fun refresh(seen: HashMap<String, Float>) {
            needs = sessions.any { it.state == SessionState.NeedsYou }
            working = sessions.any { SkyPlanner.working(it) }
            var len = 0f
            for (s in sessions) {
                // Sessions only age. A shorter reading is stale or not known yet (no start
                // time), not a reason for the planet to shrink.
                val l = max(seen[s.id] ?: 0f, s.lengthMin)
                seen[s.id] = l
                len += l
            }
            length = len
            base = lerp(HN.accentDim, HN.accentBright, 0.35f + 0.5f * shade)
            if (moons.size > sessions.size + 8) { val live = sessions.mapTo(HashSet()) { it.id }; moons.keys.retainAll(live) }
        }
    }

    private val planets = LinkedHashMap<String, Planet>()
    private val plist = ArrayList<Planet>() // by place
    private val order = ArrayList<Planet>() // by depth, far to near, rebuilt each frame
    private val lenSeen = HashMap<String, Float>()
    private var plan = SkyPlanner.Plan.Empty
    private var planKey: SkyPlanner.Plan? = null
    private var sessKey: List<Session>? = null
    /** Planets have been in the sky before: from now on they arrive by growing in. */
    private var shownAny = false

    private var geo: SkyLayout.Geometry? = null
    /** The layout with every place taken: what mustn't change with the sky's contents (the far sky's band). */
    private var full: SkyLayout.Geometry? = null
    private var geoBits = -1
    /** One line of the labels' text, and the readout's (dp), measured for this font size. */
    private var lineDp = 0f
    private var readoutDp = 0f

    private var cx = 0f
    private var cy = Float.NaN
    private var cyT = 0f
    private var bandTop = 0f
    private var aMin = 0f
    private var aMax = 0f

    // The belt of quiet projects: its grains (relative to the sun, px), its rocks, its words.
    private var beltPres = 0f
    private var beltSel = 0f
    private val beltX = FloatArray(BELT_DUST)
    private val beltY = FloatArray(BELT_DUST)
    private val beltR = FloatArray(BELT_DUST)
    private val beltAl = FloatArray(BELT_DUST)
    private val rockX = FloatArray(BELT_ROCKS)
    private val rockY = FloatArray(BELT_ROCKS)
    private var rocks = 0
    private var rockKey: List<SkyPlanner.Project>? = null
    private var beltL: TextLayoutResult? = null
    private var beltN = -1
    private var beltBoxL = Float.NaN; private var beltBoxT = 0f; private var beltBoxW = 0f; private var beltBoxH = 0f
    private var beltVis = 0f
    /** The project the list shows, or the belt: everything else steps back. */
    private var selK = 0f

    // The motion of the last frame, which says how often the next one is needed (see [moving]).
    private var movingNow = false
    private var signalNow = false

    private var uCur = Float.NaN
    private var readoutL: TextLayoutResult? = null
    // Where the readout sits this frame (px, its top left).
    private var rdX = 0f
    private var rdY = 0f
    private var rdBottomSent = -1f
    /** Which line the readout is this frame: 0 usage (the ring's number), 1 measuring, 2 refills in, 3 the eclipse's. */
    private var rdMode = -1
    // The 5-hour window as a ring round the sun (see drawUsageRing): its projection, looked at
    // again when the usage changes or each second, and its pace and notch, eased.
    private var proj: com.prudnikweb.hypernova.data.UsageProjection? = null
    private var projUsage: Usage? = null
    private var projAt = 0L
    private var ringLead = Float.NaN
    private var ringNotch = 0f
    private var ringTrack = Stroke(1f)
    private var ringArc = Stroke(2f)
    private var ringTime = Stroke(1f)
    // What the readout was last measured for, compared as plain values so frames don't build strings.
    private var rkMode = -1
    private var rkPct = -1
    private var rkLeft = -1L
    /** The time to the limit the readout was measured for, as [formatDuration] steps it (-1: none). */
    private var rkLimit = -1L
    private var rkLine: String? = null
    /** The words the readout was last measured for: a countdown in days is rebuilt each second but measured only when its words change. */
    private var rkLabel: String? = null
    // The readout for this frame, laid out before the bodies are drawn: shown or not, its alpha and colour.
    private var rdShow = false
    private var rdA = 0f
    private var rdColor = HN.inkDim

    // ─── Life cycle (wall-clock seconds) ────────────────────────────────────

    /** The window is spent: the disk is quiet until the reset. */
    private var dead = false
    /** The week is spent: the disk's low state (no stream, no disk) until the week's reset. Outranks [dead]. */
    private var low = false
    /** The low state began while we watched, so its drain plays; found later it settles at once. */
    private var lowWatched = false
    private var t0 = -1e6f       // the limit: the last hot gas drains
    private var tEnter = -1e6f   // when we learned of the limit (a quiet one fades in from here)
    private var tBirth = -1e6f   // the outburst: the disk ignites
    private var tLow = -1e6f     // the week's low state began (its drain)
    /** Seconds the returning stream refills the disk before [tBirth]: the week's reset has it, the 5-hour one doesn't. */
    private var preIgnite = 0f
    /** The outburst is the week's: a larger flush. */
    private var weekOutburst = false
    private var loudLimit = false
    private var spanAtLimit = 0f // how far the hot disk reached when the limit hit: what drains
    private var limitAt = 0L     // when this limit began
    private var limitReset = 0L  // when it ends (the latest reading)
    private var lastLimitAt = -1L
    private var lastReset = -1L
    private var lift = -1L       // when the data first said "not limited" during a limit
    private var weekReset = 0L
    private var lastWeekReset = -1L
    private var weekLift = -1L
    private var progress = 0f    // 0..1 through the wait: how far the stream has refilled the disk

    // Per-frame phase values, shared by the drawing code below.
    private var sb = 1e6f        // seconds since the limit
    private var sr = 1e6f        // seconds since the outburst began (negative while the stream refills the disk before it)
    private var lowK = 0f        // 0..1 through the low state's drain
    private var enterK = 1f      // 0..1: a limit that happened while we weren't watching fades in
    private var birthing = false // from the refill (the week's) or the ignition until the outburst has settled
    private var gas = 1f         // how much of the disk's gas there is (0 in the low state): the planets' warm tint goes with it
    private var starLight = 1f   // how much light the planets get from the disk
    private var cover = 0f       // how much of the sun the eclipse covers
    private var night = 0f       // planets show only their night side
    // The disk this frame: the hot part's limits (shares of its width), the spent part's density,
    // how hard the gas flows (eased), the inner edge's glow, how much light the disk gives (0..1),
    // the donor's angle, and its stream's and bright spot's brightness.
    private var hotOut = 1f
    private var hotIn = 0f
    private var coolDens = 0f
    private var flow = 0f
    private var glow = 0f
    private var diskLight = 1f
    private var donorAng = -PI_F / 2f
    private var streamA = 0f
    private var spotA = 0f
    private var wdLit = 1f       // the white dwarf's light (a faint point in the low state)
    private var dimK = 0f        // how far the names (and the needs-you brackets) have stepped back this frame
    private val disk = AccretionDisk()

    // Eclipse.
    private var eclOn = false
    private var tEclOn = -1e6f
    private var tEclOff = -1e6f
    private var eclText: String? = null
    private var moonOff = 99f    // the moon's offset from the sun's centre, in rim radii (signed along its path)

    private var hitsEnabled = false

    // ─── Cached brushes, paths and strokes (frames shouldn't allocate) ──────

    private val spikePath = Path()
    private val merPath = Path()

    /**
     * A sphere's day-side glow for one colour, in unit space. Every planet has its own shade, so
     * the cache holds enough for a busy sky plus the moons' few colours: with fewer slots than
     * colours drawn each frame, it would miss on every one.
     */
    private val bodyCache = arrayOfNulls<Brush>(32)
    private val bodyArgbs = IntArray(32)
    private var bodyNext = 0
    private fun bodyBrush(col: Color): Brush {
        val argb = col.toArgb()
        for (i in bodyCache.indices) if (bodyCache[i] != null && bodyArgbs[i] == argb) return bodyCache[i]!!
        val b = Brush.radialGradient(listOf(col.copy(alpha = 0.75f), col.copy(alpha = 0.22f), col.copy(alpha = 0.04f)), center = Offset.Zero, radius = 1.7f)
        bodyCache[bodyNext] = b; bodyArgbs[bodyNext] = argb; bodyNext = (bodyNext + 1) % bodyCache.size
        return b
    }

    private val spikeWhite = Brush.radialGradient(0f to Color.White, 0.15f to Color.White.copy(alpha = 0.55f), 0.5f to Color(0xFFDCE6FF).copy(alpha = 0.15f), 1f to Color.Transparent, center = Offset.Zero, radius = 1f)
    private val glowWhite = Brush.radialGradient(0f to Color.White, 0.2f to Color(0xFFE6EEFF).copy(alpha = 0.55f), 0.5f to Color(0xFFBFD4FF).copy(alpha = 0.14f), 1f to Color.Transparent, center = Offset.Zero, radius = 1f)
    /**
     * The disk's light in the space round it (unit space, radius 1): gold near the disk, through
     * orange to deep red where it thins out, as light scattered by the gas and dust of the system.
     * It fades with the disk as the window is spent: a quiet system lights less of its space.
     */
    private val diskHaze = Brush.radialGradient(
        0f to Color(0xFFFFD49A).copy(alpha = 0.2f), 0.12f to Color(0xFFFFB46A).copy(alpha = 0.14f), 0.32f to Color(0xFFF08A4A).copy(alpha = 0.08f),
        0.6f to Color(0xFFC0502C).copy(alpha = 0.035f), 1f to Color(0xFF7A2414).copy(alpha = 0f), center = Offset.Zero, radius = 1f,
    )
    private val pearl = Color(0xFFFFE2C4)
    // The light scattered beyond the moon's edge in an eclipse, brightest at the limb (radius 1.9 rim radii).
    private val innerCorona = Brush.radialGradient(0.5f to pearl.copy(alpha = 0.95f), 0.62f to pearl.copy(alpha = 0.45f), 0.8f to pearl.copy(alpha = 0.12f), 1f to Color.Transparent, center = Offset.Zero, radius = 1.9f)
    private val pearlBrush = Brush.radialGradient(0f to pearl.copy(alpha = 0.6f), 0.3f to pearl.copy(alpha = 0.32f), 0.55f to pearl.copy(alpha = 0.08f), 1f to Color.Transparent, center = Offset.Zero, radius = 3.6f)
    // A thin bright edge of the disk's light, white into orange, just outside the moon's limb.
    private val edgeBrush = Brush.radialGradient(0.88f to Color(0xFFFFD2C8).copy(alpha = 0f), 0.95f to Color(0xFFFFE6DC), 0.975f to Color(0xFFFFB07A).copy(alpha = 0.7f), 1f to Color.Transparent, center = Offset.Zero, radius = 1.1f)

    private val rimStroke = Stroke(0.12f)
    private val merStroke = Stroke(0.12f)
    private var strokeDp = -1f
    private var orbitStroke = Stroke(1f)
    private fun ensureStrokes() {
        if (strokeDp == dp) return
        strokeDp = dp
        orbitStroke = Stroke(1f * dp)
        ringTrack = Stroke(1f * dp)
        ringArc = Stroke(2f * dp, cap = StrokeCap.Round)
        ringTime = Stroke(1f * dp, cap = StrokeCap.Round)
    }

    // ─── Orbits and places ──────────────────────────────────────────────────

    // Scratch output of orbitAt(): a point relative to the sun (px), z toward the viewer.
    private var ox = 0f
    private var oy = 0f
    private var oz = 0f

    /** The point at [angle] on a circular orbit of radius [a] in the system's plane, as the camera sees it. */
    private fun orbitAt(a: Float, angle: Float) {
        val d = a * sin(angle)
        ox = a * cos(angle)
        oy = d * SIN_E
        oz = d * COS_E
    }

    /**
     * Brings the planets in line with [plan]: each planned project gets (or keeps) its place, one
     * that leaves fades out where it is, one that arrives grows in at a free place. [sessions] are
     * the sun's (the companion has the other providers'), for the moons.
     */
    private fun arrange(sessions: List<Session>, plan: SkyPlanner.Plan, reduced: Boolean) {
        this.plan = plan
        val groups = sessions.groupBy { it.project }
        val prev = HashMap<String, Int>(planets.size)
        for (p in planets.values) prev[p.project] = p.slot
        val slots = SkyPlanner.assignSlots(prev, plan.planetNames)
        // The sky's first planets are simply there; after that they arrive by growing in.
        val instant = !shownAny || reduced
        for (info in plan.planets) {
            val slot = slots[info.name] ?: continue
            val p = planets.getOrPut(info.name) { Planet(info.name).also { it.pres = if (instant) 1f else 0f } }
            if (p.slot != slot) {
                // One coming back while it faded, its place taken meanwhile: it grows in at its new one.
                if (p.aCur == p.aCur && !instant) p.pres = 0f
                p.slot = slot
                p.aCur = Float.NaN
                p.nameW = -1
            }
            p.wanted = true
            p.info = info
            p.sessions = groups[info.name].orEmpty()
        }
        for (p in planets.values) if (p.project !in slots) {
            p.wanted = false
            groups[p.project]?.let { p.sessions = it }
        }
        if (plan.planets.isNotEmpty()) shownAny = true
        relist()
        // The belt's rocks: one for each quiet project, where its name puts it.
        if (rockKey !== plan.quiet) { rockKey = plan.quiet; geoBits = -1 }
    }

    private fun relist() {
        plist.clear()
        plist.addAll(planets.values)
        plist.sortBy { it.slot }
        plist.forEachIndexed { i, p -> p.index = i }
        for (p in plist) p.refresh(lenSeen)
        if (lenSeen.size > 300) { val live = plist.flatMapTo(HashSet()) { p -> p.sessions.map { it.id } }; lenSeen.keys.retainAll(live) }
    }

    /**
     * The places for what's in the sky now ([SkyLayout]): recomputed when a place fills or empties,
     * the belt or the companion comes or goes, or the hint's line does. The sun eases to its new
     * height; the home list's top follows the band's bottom ([onUsedBottom]).
     */
    private fun ensureGeometry(companion: Boolean, codex: Boolean, measurer: TextMeasurer) {
        var bits = 0
        for (p in plist) bits = bits or (1 shl p.slot)
        if (plan.quiet.isNotEmpty() || beltPres > 0.004f) bits = bits or 16
        if (companion) bits = bits or 32
        if (hint) bits = bits or 64
        if (codex) bits = bits or 128
        geo?.let { g ->
            if (bits != geoBits) return@let
            // The same places: only a planet that just arrived needs its own.
            for (p in plist) if (p.aCur.isNaN()) { p.aTarget = g.orbit[p.slot] * dp; p.angle = g.angle[p.slot]; p.aCur = p.aTarget }
            return
        }
        geoBits = bits
        if (lineDp <= 0f) {
            lineDp = max(measurer.measure("Ag", nameStyle).size.height, measurer.measure("Ag", statusStyle).size.height) / dp
            readoutDp = measurer.measure("0", readoutStyle).size.height / dp
        }
        val wd = w / dp
        // The hint is Type.small at 11sp on a 14sp line, with 2dp above and below it.
        val hintDp = 14f * fontScale + 4f
        val occ = BooleanArray(SkyLayout.SLOTS) { bits and (1 shl it) != 0 }
        val g = SkyLayout.compute(wd, occ, bits and 16 != 0, companion, hint, lineDp, readoutDp, hintDp, codex = codex)
        val f = SkyLayout.compute(wd, BooleanArray(SkyLayout.SLOTS) { true }, belt = true, companion = false, hint = false, line = lineDp, readout = readoutDp, hintH = hintDp)
        geo = g
        full = f
        cx = g.sunX * dp
        cyT = bandTop + g.sunY * dp
        if (cy.isNaN()) cy = cyT
        aMin = f.orbit.min() * dp
        aMax = f.orbit.max() * dp
        for (p in plist) {
            p.aTarget = g.orbit[p.slot] * dp
            p.angle = g.angle[p.slot]
            if (p.aCur.isNaN()) p.aCur = p.aTarget
        }
        buildBelt(g)
        onUsedBottom(bandTop + g.height * dp)
    }

    /** The belt's grains, scattered round the outermost ring (the same every time), and a rock for each quiet project. */
    private fun buildBelt(g: SkyLayout.Geometry) {
        val a = g.beltA * dp
        val rnd = Random(0x5EA7)
        for (i in 0 until BELT_DUST) {
            val ang = i * 2.3999631f + (rnd.nextFloat() - 0.5f) * 0.05f
            val rr = a * (1f + (rnd.nextFloat() - 0.5f) * 0.07f)
            beltX[i] = cos(ang) * rr
            beltY[i] = sin(ang) * rr * SIN_E + (rnd.nextFloat() - 0.5f) * 3f * dp
            beltR[i] = (0.45f + rnd.nextFloat().pow(2f) * 0.6f) * dp
            beltAl[i] = 0.12f + rnd.nextFloat() * 0.2f
        }
        val quiet = plan.quiet
        rocks = min(quiet.size, BELT_ROCKS)
        for (i in 0 until rocks) {
            val ang = (quiet[i].name.hashCode().toLong() and 0xffffffffL) / 4294967296f * TAU
            rockX[i] = cos(ang) * a
            rockY[i] = sin(ang) * a * SIN_E
        }
    }

    // ─── Per-frame ──────────────────────────────────────────────────────────

    /**
     * One frame of the system: the sun's [usage], [sessions] (the sun's own, as moons) grouped into
     * planets by [plan], [selected] what the home list shows. [companion]: the other providers' star
     * is in the sky, so the band keeps a row for it; [codex]: so is Codex's star (both agents in use).
     */
    fun draw(
        s: DrawScope, t: Float, sessions: List<Session>, plan: SkyPlanner.Plan, usage: Usage, focus: Float,
        skyTop: Float, selected: SkyFilter?, companion: Boolean, measurer: TextMeasurer, reduced: Boolean, now: Long,
        codex: Boolean = false,
    ) = with(s) {
        if (epoch0 < 0) epoch0 = now
        wt = (now - epoch0) / 1000f
        if (size.width != w || size.height != h || density != dp || fontScale != this@Solar.fontScale || bandTop != h * skyTop) {
            w = size.width; h = size.height; dp = density; this@Solar.fontScale = fontScale
            geo = null; lineDp = 0f; rkMode = -1; rkLabel = null; beltN = -1; cy = Float.NaN
            for (p in plist) { p.nameW = -1; p.statusKey = -1L }
            bandTop = h * skyTop
        }
        ensureStrokes()
        // Up to a quarter second: the sky may run at a few frames a second (a calm sky, a warm
        // phone), and a longer gap (the sky was hidden) just resumes instead of jumping ahead.
        dt = if (lastT < 0) 0f else (t - lastT).coerceIn(0f, 0.25f)
        lastT = t
        if (planKey !== plan || sessKey !== sessions) {
            planKey = plan; sessKey = sessions
            arrange(sessions, plan, reduced)
        }
        // Arriving and leaving, in place: a planet grows in, or fades where it stands and goes.
        var gone = false
        for (i in 0 until plist.size) {
            val p = plist[i]
            val target = if (p.wanted) 1f else 0f
            p.pres = if (reduced) target else approach(p.pres, target, dt / ARRIVE)
            if (!p.wanted && p.pres <= 0f) gone = true
        }
        if (gone) { planets.values.removeAll { !it.wanted && it.pres <= 0f }; relist() }
        val beltWant = if (plan.quiet.isNotEmpty()) 1f else 0f
        beltPres = if (reduced) beltWant else approach(beltPres, beltWant, dt / ARRIVE)
        ensureGeometry(companion, codex, measurer)
        cy = if (reduced) cyT else cy + (cyT - cy) * (1f - exp(-dt * 4f))
        if (abs(cy - cyT) < 0.3f) cy = cyT

        transitions(usage, now, reduced)
        eclipseStep(reduced)
        // A spent window reads full; in the week's low state the number keeps what it had (its
        // line under the sun says the week).
        val target = when {
            dead -> 1f
            low -> uCur.takeIf { !it.isNaN() } ?: usage.fiveHour.coerceIn(0f, 1f)
            else -> usage.fiveHour.coerceIn(0f, 1f)
        }
        uCur = if (uCur.isNaN() || reduced) target else uCur + (target - uCur) * (1f - exp(-dt * 4f))
        // The window's projection for the ring (no frame builds it: a new reading, or each second).
        if (usage !== projUsage || now - projAt >= 1000L) {
            projUsage = usage; projAt = now
            proj = com.prudnikweb.hypernova.data.UsageProjection.fiveHour(usage, now)
        }
        proj?.let { pr ->
            val lead = pr.projected ?: uCur
            val notch = if (pr.limitFirst) 1f else 0f
            if (ringLead.isNaN() || reduced) { ringLead = lead; ringNotch = notch }
            else {
                ringLead += (lead - ringLead) * (1f - exp(-dt * 4f))
                ringNotch = approach(ringNotch, notch, dt * 2f)
                if (abs(ringLead - lead) < 0.001f) ringLead = lead
            }
        }

        // Where each timeline is.
        sb = wt - t0
        sr = wt - tBirth
        enterK = if (reduced) 1f else smooth((wt - tEnter) / 1.4f)
        lowK = if (low) LowState.drained(wt - tLow, lowWatched, reduced) else 0f
        birthing = !dead && !low && sr >= -preIgnite && sr <= OUTBURST
        diskStep(reduced, now)
        // The disk is the light of the system: it lights the planets, less as it's spent. A quiet
        // disk, or none in the week's low state, still lights them a little (the white dwarf shines
        // on); the planets are never dark.
        starLight = 0.6f + 0.4f * diskLight
        cover = if (dead || low) 0f else eclipseCover()
        night = cover

        // How far the names and the orbits step back: to 70% as the disk drains at a limit (a
        // quiet system lights less), back to full as it ignites at the reset.
        var deadK = when {
            birthing -> QUIET_DIM * (1f - smooth(sr / 1.5f))
            !dead -> 0f
            loudLimit && !reduced -> QUIET_DIM * smooth(sb / 1.5f)
            else -> QUIET_DIM * enterK
        }
        if (low) deadK = max(deadK, QUIET_DIM * lowK)
        val labelsLive = 1f - deadK
        dimK = deadK

        // ── Positions: each planet stands at its place ──
        val selP = (selected as? SkyFilter.Project)?.let { planets[it.name] }
        val anySel = selected != null
        selK = if (reduced) (if (anySel) 1f else 0f) else approach(selK, if (anySel) 1f else 0f, dt * 4f)
        beltSel = if (reduced) (if (selected == SkyFilter.Quiet) 1f else 0f) else approach(beltSel, if (selected == SkyFilter.Quiet) 1f else 0f, dt * 4f)
        var moving = t - lastBorn < 1.5f || abs(cy - cyT) > 0.3f || abs(beltPres - beltWant) > 0f ||
            abs(selK - (if (anySel) 1f else 0f)) > 0f ||
            proj.let { pr -> pr != null && (abs(ringLead - (pr.projected ?: uCur)) > 0.001f || ringNotch != (if (pr.limitFirst) 1f else 0f)) } ||
            // Gas flows through the disk: its knots spiral in. Motion means work.
            flow > 0.02f
        var signal = false
        for (i in 0 until plist.size) {
            val p = plist[i]
            p.aCur = if (reduced) p.aTarget else p.aCur + (p.aTarget - p.aCur) * (1f - exp(-dt * 2.5f))
            if (abs(p.aCur - p.aTarget) > 0.3f) moving = true
            p.sel = if (reduced) (if (p === selP) 1f else 0f) else approach(p.sel, if (p === selP) 1f else 0f, dt * 4f)
            // Only a project at work moves: its moons go round (its planet turns, see drawPlanet).
            // A limit leaves the planets be: the white dwarf holds the same orbits whether its disk
            // is bright, quiet or gone.
            if (!reduced && p.working) p.moonPhase += dt
            if (p.working && p.pres > 0f) moving = true
            if (p.spinV > 0.002f || p.pres != (if (p.wanted) 1f else 0f)) moving = true
            if (p.needs && p.pres > 0f) signal = true
            orbitAt(p.aCur, p.angle)
            p.x = cx + ox; p.y = cy + oy; p.z = oz
        }
        movingNow = moving && !reduced
        signalNow = signal && !reduced

        hitsEnabled = focus > 0.35f
        if (focus <= 0.01f) {
            for (i in 0 until plist.size) { plist[i].vis = 0f }
            beltVis = 0f
            return@with
        }
        val f2 = focus * focus
        val bodyLight = focus

        // Far to near, so a planet behind another (or behind the sun) is drawn first.
        order.clear()
        for (i in 0 until plist.size) {
            val p = plist[i]
            var j = order.size
            order.add(p)
            while (j > 0 && order[j - 1].z > p.z) { order[j] = order[j - 1]; j-- }
            order[j] = p
        }

        // The words are laid out first: the belt keeps clear of them.
        val txt = text
        prepareReadout(txt, usage, now, measurer, reduced, f2)
        layoutLabels(txt, measurer, now)

        // ── The system ──
        // The disk's light fills the space round it, warm gold to red, as much as the disk is lit.
        val haze = diskLight * (0.55f + 0.45f * min(1f, glow))
        if (haze > 0.004f) {
            val hr = aMin * 0.85f
            // No taller than the band: above it the top bar shows a blur of the far sky alone,
            // so the glow would stop short at the bar's edge.
            val hv = max(1f, min(hr * 0.62f, cy - bandTop))
            withTransform({ translate(cx, cy); scale(hr, hv, Offset.Zero) }) { drawCircle(diskHaze, 1f, Offset.Zero, alpha = haze * focus * (1f - 0.94f * cover)) }
        }
        // Each planet's orbit, a faint ring: still, like the planet on it. Round a quiet disk the
        // rings step back a little with the names, lit by its lesser light.
        val orbitA = 0.13f * bodyLight * (1f - 0.75f * deadK)
        if (orbitA > 0.004f) for (i in 0 until plist.size) {
            val p = plist[i]
            val k = smooth(p.pres) * (0.25f + 0.75f * starLight) * stepBack(p)
            if (p.x.isNaN() || k < 0.02f) continue
            val a = p.aCur
            val col = lerp(HN.star, if (p.needs) HN.amber else p.base, 0.45f)
            drawOval(col, Offset(cx - a, cy - a * SIN_E), Size(2f * a, 2f * a * SIN_E), alpha = orbitA * k, style = orbitStroke)
        }
        drawBelt(bodyLight * (1f - 0.75f * deadK) * (0.25f + 0.75f * starLight))
        var drewSun = false
        for (i in 0 until order.size) {
            val p = order[i]
            if (!drewSun && p.z >= 0f) { drawSun(t, focus, reduced, 1f); drewSun = true }
            drawPlanet(t, p, bodyLight, reduced, 1f, isFocus = p === selP)
        }
        if (!drewSun) drawSun(t, focus, reduced, 1f)

        // ── The words ──
        // The sun's own line first: its ring and number, the countdown to the outburst, or the eclipse's line.
        if (rdShow && (rdMode == 0 || rdMode == 1)) drawUsageRing(rdA, empty = rdMode == 1)
        if (rdShow) readoutL?.let { l -> if (rdA > 0.01f) drawText(l, color = rdColor, alpha = 0.95f * rdA, topLeft = Offset(rdX, rdY)) }
        drawLabels(selP, labelsLive, f2)
    }

    /** The ring's radius round the sun: clear of the disk and the donor's orbit. */
    private fun ringRadius(): Float = max(20f * dp, sunRadiusNow() * 1.55f + 6f * dp)

    /**
     * The 5-hour window as a ring round the sun (owner, 2026-10-18: spending drawn, not written):
     * a faint track, the **used** arc lit from the top clockwise (mint, amber from 80%), the **pace**
     * as a faint arc on from it to where the current pace leads by the reset, and outside it a thin
     * arc of the **window's time** gone, from the top clockwise: the top is the reset. When the pace
     * reaches the limit before the reset, a **red notch** on that outer arc marks when. The one
     * number under it is the percentage, or then the time to the limit, in red ("47m"). Drawn as
     * a gauge, flat on the screen, not in the system's tilted plane. Nothing in it moves on its
     * own: it changes as the numbers do. The donor, inside it, keeps the same clock.
     */
    private fun DrawScope.drawUsageRing(a: Float, empty: Boolean = false) {
        if (a < 0.01f) return
        val r = ringRadius()
        val c = Offset(cx, cy)
        if (empty) {
            // No numbers yet (or the window has just turned over): the gauge alone, its track and
            // its clock's circle, nothing lit, with "waiting for numbers" under it.
            drawCircle(HN.ink, r, c, alpha = 0.09f * a, style = ringTrack)
            drawCircle(HN.inkMuted, r + 4f * dp, c, alpha = 0.12f * a, style = ringTime)
            return
        }
        val pr = proj ?: return
        val used = uCur.coerceIn(0f, 1f)
        val lead = ringLead.takeIf { !it.isNaN() }?.coerceIn(used, 1f) ?: used
        val colour = when { used >= 0.999f -> HN.danger; used >= 0.8f -> HN.amber; else -> HN.accent }
        val tl = Offset(cx - r, cy - r)
        val sz = Size(2f * r, 2f * r)
        drawCircle(HN.ink, r, c, alpha = 0.09f * a, style = ringTrack)
        if (lead > used + 0.002f) drawArc(colour, -90f + 360f * used, 360f * (lead - used), false, tl, sz, alpha = 0.3f * a, style = ringArc)
        if (used > 0.002f) drawArc(colour, -90f, 360f * used, false, tl, sz, alpha = 0.9f * a, style = ringArc)
        // The window's clock: how much of its five hours has gone (the top is the reset).
        val ro = r + 4f * dp
        val gone = if (pr.resetIn >= 0L) (1f - pr.resetIn / FIVE_HOURS.toFloat()).coerceIn(0f, 1f) else 0f
        // Its whole five hours a faint circle, so the notch below plainly sits on the clock, not on the usage.
        drawCircle(HN.inkMuted, ro, c, alpha = 0.12f * a, style = ringTime)
        if (gone > 0.002f) drawArc(HN.inkMuted, -90f, 360f * gone, false, Offset(cx - ro, cy - ro), Size(2f * ro, 2f * ro), alpha = 0.45f * a, style = ringTime)
        val t = pr.tag
        if (ringNotch > 0.01f && t is com.prudnikweb.hypernova.data.UsageProjection.Tag.Limit) {
            val at = (gone + t.ms / FIVE_HOURS.toFloat()).coerceIn(0f, 1f)
            val ang = (-90f + 360f * at) * PI_F / 180f
            val dx = cos(ang); val dy = sin(ang)
            drawLine(HN.danger, Offset(cx + dx * (ro - 3f * dp), cy + dy * (ro - 3f * dp)), Offset(cx + dx * (ro + 3.5f * dp), cy + dy * (ro + 3.5f * dp)), strokeWidth = 1.6f * dp, cap = StrokeCap.Round, alpha = ringNotch * a)
        }
    }

    /** How far [p] steps back (1 = not at all) while the list shows another project, or the belt. */
    private fun stepBack(p: Planet): Float = 1f - (1f - UNSELECTED) * selK * (1f - p.sel)

    /**
     * Lays out the sun's own line for this frame: usage, the countdown to the outburst (the 5-hour
     * window's, or the week's in its low state, which outranks it), or the eclipse's line. It sits
     * fixed just under the sun and never moves, so nothing near it jumps.
     */
    private fun prepareReadout(txt: SolarText?, usage: Usage, now: Long, measurer: TextMeasurer, reduced: Boolean, f2: Float) {
        rdShow = false
        if (txt == null) return
        val down = dead || low
        val ecl = eclText
        // Around an eclipse the usage line fades out, the eclipse's line fades in once the sun
        // is covered, and on the way back the other way round.
        var showEcl = false
        var eclA = 1f
        if (ecl != null && !down) {
            if (eclOn) {
                val e = wt - tEclOn
                showEcl = e >= 0.6f
                eclA = if (showEcl) smooth((e - 2.4f) / 1f) else 1f - smooth(e / 0.6f)
            } else if (tEclOff > -1e5f) {
                val e = wt - tEclOff
                showEcl = e < 0.8f
                eclA = if (showEcl) 1f - smooth(e / 0.8f) else smooth((e - 2.4f) / 1f)
            }
        }
        val left = max(0L, (when { low -> weekReset; dead -> limitReset; else -> usage.fiveHourResetAt }) - now)
        val pct = (uCur * 100).toInt().coerceIn(0, 100)
        // The window's end has passed but the Mac hasn't read the new one yet: the old percentage and
        // a countdown stuck at 0:00 would be wrong, so say "measuring" until fresh numbers arrive.
        val spent = !down && usage.fiveHourResetAt in 1..now
        val known = (usage.known && !spent) || down
        val mode = when { showEcl -> 3; down -> 2; !known -> 1; else -> 0 }
        rdMode = mode
        // Rebuilt when the text would change: whole seconds, rounded up as every countdown is
        // ([Countdown]), so it reads what the account card under the sky reads; measured again only
        // when its words do (the week's "2d 4h" holds for an hour). The usage line is only its
        // percentage now (the ring draws the rest), or the time to the limit.
        val leftS = if (mode == 2) Countdown.seconds(left, 0L) else 0L
        val pctK = if (mode == 0) pct else 0
        // At this pace the limit comes before the reset: the number is the time to it, in red
        // ("47m"), as the meters' tag has it. Keyed by what it reads: seconds under ten minutes
        // ("9:59"), minutes above.
        val toLimit = if (mode == 0) (proj?.tag as? com.prudnikweb.hypernova.data.UsageProjection.Tag.Limit)?.ms else null
        val limitK = toLimit?.let { Countdown.seconds(it, 0L) }?.let { if (it < 600L) it else 1_000_000L + it / 60L } ?: -1L
        if (mode != rkMode || leftS != rkLeft || pctK != rkPct || limitK != rkLimit || (mode == 3 && ecl != rkLine)) {
            val styleChanged = (mode >= 2) != (rkMode >= 2)
            rkMode = mode; rkLeft = leftS; rkPct = pctK; rkLine = ecl; rkLimit = limitK
            val label = when {
                showEcl -> ecl!!.uppercase()
                down -> txt.refills(txt.duration(left)).uppercase()
                !known -> txt.unknown
                toLimit != null -> txt.duration(toLimit)
                else -> txt.readout(pct)
            }
            if (label != rkLabel || styleChanged || readoutL == null) {
                rkLabel = label
                readoutL = measurer.measure(label, if (down || showEcl) bornStyle else readoutStyle, maxLines = 1)
            }
        }
        val l = readoutL ?: return
        rdX = cx - l.size.width / 2f
        // The number sits under the ring; the other lines under the sun (clear of the donor's orbit).
        rdY = if (mode == 0 || mode == 1) cy + ringRadius() + 9 * dp else cy + max(sunRadiusNow() * 1.9f, 15 * dp) + 4 * dp
        // The hint's line follows it (only when it moves: the band changes).
        val bottom = rdY + l.size.height
        if (abs(bottom - rdBottomSent) > 0.5f) { rdBottomSent = bottom; onReadoutBottom(bottom) }
        rdColor = when {
            showEcl -> HN.inkMuted
            // The week's wait has no refill to show: the stream is off until its reset.
            low -> HN.inkMuted
            down -> lerp(HN.inkMuted, HN.ink, progress)
            limitK >= 0L -> HN.danger
            uCur >= 0.8f -> HN.amber
            else -> HN.inkDim
        }
        // The countdown fades in as the disk drains; the new number as it ignites.
        rdA = f2 * (if (birthing) smooth((sr - 0.8f) / 1.2f) else 1f) * eclA * when {
            low -> if (lowWatched && !reduced) smooth((wt - tLow - 0.8f) / 1.2f) else 1f
            dead && loudLimit && !reduced -> smooth((sb - 0.8f) / 1.2f)
            else -> 1f
        }
        rdShow = true
    }

    /**
     * Each planet's words in its slot ([SkyLayout]): its name, cut short to fit the slot, over what
     * it's doing. Centred over the planet where they fit, pushed in from the slot's edge where they
     * don't; above an upper planet, below the readout for a lower one. They never move with
     * anything: the slots are fixed, and so are the planets.
     */
    private fun layoutLabels(txt: SolarText?, measurer: TextMeasurer, now: Long) {
        val g = geo ?: return
        for (i in 0 until plist.size) {
            val p = plist[i]
            val info = p.info
            if (info == null || txt == null) { p.lx = Float.NaN; continue }
            val reg = g.labels[p.slot]
            val rw = (reg.width * dp).toInt().coerceAtLeast(1)
            if (rw != p.nameW || p.nameL == null) {
                p.nameW = rw
                p.nameL = fitSkyName(measurer, p.project.uppercase(), nameStyle, rw)
                p.statusKey = -1L
            }
            // What it's doing, worded again only when the words would change ("4m ago" → "5m ago").
            val ago = max(0L, now - info.lastActive)
            val bucket = if (info.doing == SkyPlanner.Doing.Resting) agoBucket(ago) else 0L
            val key = (info.doing.ordinal.toLong() shl 48) or (info.count.toLong().coerceAtMost(0xffff) shl 32) or bucket
            if (key != p.statusKey) {
                p.statusKey = key
                p.statusL = measurer.measure(txt.status(info.doing, info.count, ago), statusStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = rw))
            }
            val nl = p.nameL ?: continue
            val sh = p.statusL?.size?.height ?: 0
            val bw = max(nl.size.width, p.statusL?.size?.width ?: 0).toFloat()
            val rl = reg.l * dp
            val rr = reg.r * dp
            val ww = min(bw, rr - rl)
            p.lx = (g.planetX[p.slot] * dp - ww / 2f).coerceIn(rl, max(rl, rr - ww))
            p.lw = ww
            p.lh = (nl.size.height + sh).toFloat()
            // In px from the sun, which eases to a new height when the band changes: the words go with it.
            val top = cy + (reg.t - g.sunY) * dp
            val bottom = cy + (reg.b - g.sunY) * dp
            // Upper words sit at the bottom of their slot (nearest their planet), lower ones at the top.
            p.ly = if (SkyLayout.upper(p.slot)) bottom - p.lh else top
        }
        // The belt's words, in the middle of the top row.
        val belt = g.belt
        val n = plan.quiet.size
        if (belt != null && txt != null && n > 0) {
            if (n != beltN || beltL == null) {
                beltN = n
                beltL = measurer.measure(txt.quiet(n), statusStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = (belt.width * dp).toInt().coerceAtLeast(1)))
            }
            val l = beltL!!
            beltBoxW = l.size.width.toFloat()
            beltBoxH = l.size.height.toFloat()
            beltBoxL = cx - beltBoxW / 2f
            beltBoxT = cy + (belt.t - g.sunY) * dp
        } else if (beltPres <= 0f) beltBoxL = Float.NaN
    }

    /** "4m ago" changes each minute, "2h ago" each hour, "3d ago" each day: a key for the words. */
    private fun agoBucket(ms: Long): Long {
        val s = ms / 1000L
        return when {
            s < 45 -> 0L
            s < 3600 -> 1_000L + s / 60
            s < 86_400 -> 100_000L + s / 3600
            else -> 1_000_000L + s / 86_400
        }
    }

    private fun DrawScope.drawLabels(selP: Planet?, labelsLive: Float, f2: Float) {
        for (i in 0 until plist.size) {
            val p = plist[i]
            p.vis = 0f
            val nl = p.nameL ?: continue
            if (p.lx.isNaN() || p.x.isNaN()) continue
            val sel = p === selP
            p.vis = smooth(p.pres) * labelsLive * stepBack(p)
            val a = p.vis * f2
            if (a < 0.01f) continue
            val upper = SkyLayout.upper(p.slot)
            val cxL = p.lx + p.lw / 2f
            val nameCol = when { sel -> HN.ink; p.needs -> HN.amber; else -> HN.inkMuted }
            drawText(nl, color = nameCol, alpha = 0.95f * a, topLeft = Offset(cxL - nl.size.width / 2f, p.ly))
            p.statusL?.let { sl ->
                val col = when {
                    p.needs -> HN.amber
                    p.working -> HN.accent
                    else -> HN.inkDim
                }
                drawText(sl, color = col, alpha = 0.9f * a, topLeft = Offset(cxL - sl.size.width / 2f, p.ly + nl.size.height))
            }
            // A short tick from the planet to its words: a star chart's annotation.
            val r = p.radius + 3 * dp
            val x0 = p.x
            val y0 = if (upper) p.y - r else p.y + r
            val y1 = if (upper) p.ly + p.lh + 2 * dp else p.ly - 2 * dp
            if (if (upper) y0 - y1 > 3 * dp else y1 - y0 > 3 * dp) {
                drawLine(nameCol, Offset(x0, y0), Offset(x0.coerceIn(p.lx, p.lx + p.lw), y1), strokeWidth = 0.8f * dp, alpha = 0.3f * a)
            }
            // The ring of moons already reaches past the planet: the frame sits just outside the larger.
            if (sel) drawFocusBracket(this, p.x, p.y, max(p.radius, p.ring) + 5 * dp, a * p.sel)
        }
        // The belt's words: "+5 quiet", ink and framed while the list shows those projects.
        val l = beltL
        beltVis = 0f
        if (l != null && !beltBoxL.isNaN() && beltPres > 0f) {
            beltVis = smooth(beltPres) * labelsLive * (1f - (1f - UNSELECTED) * selK * (1f - beltSel))
            val a = beltVis * f2
            if (a > 0.01f) {
                drawText(l, color = lerp(HN.inkDim, HN.ink, beltSel), alpha = 0.9f * a, topLeft = Offset(beltBoxL, beltBoxT))
                if (beltSel > 0.01f) {
                    val bx = beltBoxL + beltBoxW / 2f; val by = beltBoxT + beltBoxH / 2f
                    drawBracket(this, bx - beltBoxW / 2f - 6 * dp, by - beltBoxH / 2f - 3 * dp, bx + beltBoxW / 2f + 6 * dp, by + beltBoxH / 2f + 3 * dp, a * beltSel)
                }
            }
        }
    }

    /**
     * The quiet projects' belt: grains of dust round the outermost ring, and a rock for each
     * quiet project, all still. It breaks where words sit, as a star chart's lines do.
     */
    private fun DrawScope.drawBelt(light: Float) {
        val a = light * smooth(beltPres) * (1f - (1f - UNSELECTED) * selK * (1f - beltSel)) * (1f + 0.8f * beltSel)
        if (a < 0.01f) return
        val pad = 3 * dp
        for (i in 0 until BELT_DUST) {
            val x = cx + beltX[i]; val y = cy + beltY[i]
            if (underWords(x, y, pad)) continue
            drawCircle(HN.star, beltR[i], Offset(x, y), alpha = beltAl[i] * a)
        }
        for (i in 0 until rocks) {
            val x = cx + rockX[i]; val y = cy + rockY[i]
            if (underWords(x, y, pad)) continue
            drawCircle(lerp(HN.star, HN.inkMuted, 0.5f), 1.3f * dp, Offset(x, y), alpha = 0.5f * a)
        }
    }

    /** Whether (x, y) falls under any words drawn this frame (with [pad] round them). */
    private fun underWords(x: Float, y: Float, pad: Float): Boolean {
        for (i in 0 until plist.size) {
            val p = plist[i]
            if (p.lx.isNaN() || p.pres <= 0f) continue
            if (x >= p.lx - pad && x <= p.lx + p.lw + pad && y >= p.ly - pad && y <= p.ly + p.lh + pad) return true
        }
        if (!beltBoxL.isNaN() && x >= beltBoxL - pad && x <= beltBoxL + beltBoxW + pad && y >= beltBoxT - pad && y <= beltBoxT + beltBoxH + pad) return true
        val l = readoutL
        if (rdShow && l != null && x >= rdX - pad && x <= rdX + l.size.width + pad && y >= rdY - pad && y <= rdY + l.size.height + pad) return true
        return false
    }
    // ─── Transitions ────────────────────────────────────────────────────────

    private fun transitions(usage: Usage, now: Long, reduced: Boolean) {
        // The week first: its low state outranks the 5-hour window's quiet disk ([LowState.sun]).
        // At its limit the stream stops and the disk drains; at its reset the stream returns.
        if (usage.weekLimited) {
            weekLift = -1L
            weekReset = usage.sevenDayResetAt
            if (!low) {
                low = true
                val age = (now - (usage.weekLimitedAt ?: now)) / 1000f
                // The same week's limit coming back (after a glitch lifted it for long enough to
                // count) settles quietly, like one found later: the disk never drains twice.
                val same = lastWeekReset > now && abs(usage.sevenDayResetAt - lastWeekReset) < SAME_WINDOW_MS
                lowWatched = age < 3f && !reduced && !same
                tLow = wt
                tBirth = -1e6f
                if (lowWatched) onEvent()
            }
        } else if (low) {
            if (weekLift < 0) weekLift = now
            if (now >= weekReset - 1500 || now - weekLift >= HOLD_MS) weekEnds(usage, reduced, now)
        }
        if (low) {
            // The window's own cycle waits for the week; a quiet disk keeps its count.
            progress = if (dead) cooldown(now) else 0f
            return
        }

        // The 5-hour window: the disk drains at the limit, and ignites again at the reset.
        val at = usage.limitedAt ?: now
        // A reading from a window that has already reset (a stale status line) isn't a limit at all.
        val stale = !dead && lastReset > 0L && now >= lastReset - 1500 && (at == lastLimitAt || abs(usage.fiveHourResetAt - lastReset) < 5_000L)
        if (usage.limited && !stale) {
            lift = -1L
            limitReset = usage.fiveHourResetAt
            if (!dead) {
                // The same limit coming back (after a glitch lifted it for long enough to count)
                // returns quietly: the disk never drains twice. It's known by its start time or, as
                // the app re-stamps that after a gap, by its window: the reset it ends at is still ahead.
                val same = at == lastLimitAt || (lastReset > now && abs(usage.fiveHourResetAt - lastReset) < SAME_WINDOW_MS)
                dead = true
                lastLimitAt = at
                limitAt = at
                val age = (now - at) / 1000f
                val fresh = age < 3f && !reduced && !same
                t0 = if (fresh) wt else wt - max(age, 30f)
                tEnter = wt
                tBirth = -1e6f
                drain(fresh)
            }
        } else if (dead) {
            if (lift < 0) lift = now
            if (now >= limitReset - 1500 || now - lift >= HOLD_MS) ignite(usage, reduced, now)
        }
        progress = if (dead) cooldown(now) else 1f
    }

    /** 0..1 through the 5-hour wait: how far the stream has refilled the disk. */
    private fun cooldown(now: Long): Float = ((now - limitAt).toFloat() / max(1L, limitReset - limitAt)).coerceIn(0f, 1f)

    /** The limit: the last hot gas drains onto the white dwarf ([loud]: watched, so the drain plays). */
    private fun drain(loud: Boolean) {
        loudLimit = loud
        spanAtLimit = DiskModel.hotSpan(AccretionDisk.R_IN, AccretionDisk.R_OUT, 1f - uCur.coerceIn(0f, 1f))
        if (loud) { onBlast(); onEvent() }
    }

    /** The reset: the outburst. The heating front sweeps the refilled disk from its rim inward, and the next window is a full disk. */
    private fun ignite(usage: Usage, reduced: Boolean, now: Long) {
        dead = false
        lift = -1L
        lastReset = limitReset
        // A reset that passed while we weren't watching (the app was in the background) isn't
        // replayed: the full disk is simply there, as a limit or a low state found later is.
        val missed = now - limitReset > MISSED_MS
        val live = !reduced && !missed
        preIgnite = 0f
        weekOutburst = false
        tBirth = if (live) wt else wt - OUTBURST - 1f
        // The new window is young, not the spent one's 100%. Lifted by its time while the numbers still
        // read the old window (the next reading brings the new ones), it starts from empty.
        uCur = if (usage.limited) 0f else usage.fiveHour.coerceIn(0f, 1f)
        if (live) onEvent()
    }

    /**
     * The week's reset: the stream comes back and refills the disk over [LowState.REFILL] s, then
     * the disk ignites as at the 5-hour reset, its first flush a little larger. Found late, the
     * full disk is simply there. A 5-hour limit that still holds takes the sky over from the next
     * frame (its quiet disk fades in, as one found later does).
     */
    private fun weekEnds(usage: Usage, reduced: Boolean, now: Long) {
        low = false
        weekLift = -1L
        lastWeekReset = weekReset
        // The window's own quiet ends with the week; its limit, if it still holds, comes back as found.
        if (dead) { dead = false; lift = -1L; lastReset = limitReset }
        val live = !reduced && now - weekReset <= MISSED_MS
        preIgnite = if (live) LowState.REFILL else 0f
        weekOutburst = true
        tBirth = if (live) wt + preIgnite else wt - OUTBURST - 1f
        uCur = usage.fiveHour.coerceIn(0f, 1f)
        if (live) onEvent()
    }

    // ─── Eclipse ────────────────────────────────────────────────────────────

    private fun eclipseStep(reduced: Boolean) {
        val want = eclipse
        if (want != null) eclText = want
        if (want != null && !eclOn) { eclOn = true; tEclOn = if (reduced) wt - ECL - 2f else wt }
        else if (want == null && eclOn) { eclOn = false; tEclOff = if (reduced) wt - ECL - 2f else wt }
        // The moon comes in from the upper right, rests a hair off centre (so a thin edge of the
        // disk's light shows on one side), and leaves toward the lower left. It slows near
        // contact, so the diamond ring lingers for a moment.
        moonOff = if (eclOn) {
            val x = (wt - tEclOn) / ECL
            2.6f - 2.25f * smooth(x / 0.7f) - 0.32f * smooth((x - 0.6f) / 0.4f)
        } else if (tEclOff > -1e5f) {
            val x = (wt - tEclOff) / ECL
            -(0.03f + 0.32f * smooth(x / 0.4f) + 2.25f * smooth((x - 0.3f) / 0.7f))
        } else 99f
    }

    /** How much of the sun's face the moon hides (0..1), from how far apart their centres are. */
    private fun eclipseCover(): Float {
        val d = abs(moonOff)
        if (d >= 2.05f) return 0f
        return 1f - smooth((d - 0.04f) / 2.0f)
    }

    /** The diamond ring: the last (or first) bead of light at the limb, brightest just off totality. */
    private fun diamond(): Float {
        val d = abs(moonOff)
        val k = (d - 0.2f) / 0.1f
        return if (d > 0.6f) 0f else exp(-k * k) * smooth((d - 0.06f) / 0.06f)
    }

    // ─── Sun ────────────────────────────────────────────────────────────────

    /**
     * Where the disk is in its cycle this frame, from the window's numbers and the timelines.
     *
     * - The hot part reaches out to the cooling front, set by what's left of the budget
     *   ([DiskModel.hotSpan]); at the limit it's gone (the last hot gas drains over [DRAIN] s).
     * - At the reset the heating front sweeps in from the rim over [FLARE] s: the hot part is the
     *   outer annulus first, then the whole disk; a flush of extra light settles over [SETTLE] s.
     * - The spent part keeps its gas, cool and red; through the wait the stream thickens it.
     * - The gas flows as fast as the pace says ([DiskModel.flow]), eased; nothing flows at a
     *   limit, in the low state, or at rest. The knots move only then.
     * - The donor stands at the window's clock. Its stream and bright spot show most in
     *   quiescence; at the weekly limit the stream stops and the disk drains to nothing, the
     *   white dwarf a faint point ([LowState]); at the week's reset the stream comes back and
     *   refills the disk over [LowState.REFILL] s before it ignites.
     */
    private fun diskStep(reduced: Boolean, now: Long) {
        val left = (1f - uCur.coerceIn(0f, 1f))
        val span = DiskModel.hotSpan(AccretionDisk.R_IN, AccretionDisk.R_OUT, left)
        // The week's reset: the returning stream refills the disk before it ignites (sr < 0).
        val refilling = birthing && sr < 0f
        val refillK = if (refilling) LowState.refilled(sr + preIgnite, reduced) else 1f
        when {
            refilling -> { hotOut = 0f; hotIn = 0f }
            // The outburst: the heating front sweeps in from the rim.
            birthing && sr < FLARE -> { hotOut = max(span, 0.02f); hotIn = (1f - smooth(sr / FLARE)) * hotOut }
            // The week's low state: what hot gas there was drains onto the white dwarf.
            low -> { hotOut = (if (dead) 0f else span) * LowState.hot(lowK); hotIn = 0f }
            // Spent while we watched: the last hot gas drains over DRAIN s; found later, simply quiet.
            dead && loudLimit && !reduced && sb < DRAIN -> { hotOut = spanAtLimit * (1f - smooth(sb / DRAIN)); hotIn = 0f }
            // Spending: out to the cooling front (spent: the usage reads 100%, so none).
            else -> { hotOut = span; hotIn = 0f }
        }
        // The hot disk's light, by its area (0 = quiet, 1 = a full disk).
        val rin = AccretionDisk.R_IN / AccretionDisk.R_OUT
        fun r(k: Float) = rin + (1f - rin) * k
        val ro = r(hotOut); val ri = r(hotIn)
        diskLight = ((ro * ro - ri * ri) / (1f - rin * rin)).coerceIn(0f, 1f)
        // How much gas the disk has: all of it, but none in the low state, and filling up again
        // as the stream returns (in steps: the planets' tint follows it).
        gas = steps(when {
            low -> LowState.gas(lowK)
            refilling -> LowState.refillGas(refillK)
            else -> 1f
        }, 40)
        // The spent gas: there through the window, thicker as the stream refills it through the
        // wait (in steps, so the quiet disk isn't a new picture every slow frame).
        coolDens = (if (dead) 0.75f + 0.45f * steps(progress, 40) else 0.75f) * gas
        // How hard the gas flows: the spend pace while something works, eased over a second or so.
        val target = if (dead || low || hotOut <= 0.02f) 0f else DiskModel.flow(proj?.pace)
        flow = if (reduced) target else flow + (target - flow) * (1f - exp(-dt * 1.5f))
        if (abs(flow - target) < 0.005f) flow = target
        if (!reduced && flow > 0f) disk.advance(dt, flow)
        // The inner edge's glow: the rate, plus the outburst's first flush (the week's larger) and the drain's last.
        val flush = if (birthing && !refilling) 1f - smooth((sr - FLARE * 0.6f) / SETTLE) else 0f
        val last = if (dead && !low && loudLimit && !reduced) (1f - smooth(sb / DRAIN)) * smooth(sb / 0.2f) else 0f
        glow = min(1f, flow) * 0.85f + 0.6f * max(flush * (if (weekOutburst) WEEK_FLUSH else 1f), last)
        // The donor keeps the window's clock; its stream shows most while the disk is quiet. In
        // the low state the donor stays and its stream goes out; at the week's reset it comes back first.
        val pr = proj
        donorAng = DiskModel.donorAngle(if (dead) max(0L, limitReset - now) else pr?.resetIn ?: -1L, FIVE_HOURS)
        val quiet = 1f - diskLight
        val streamK = when {
            low -> LowState.stream(lowK)
            refilling -> LowState.refillStream(refillK)
            else -> 1f
        }
        streamA = (0.12f + 0.55f * quiet) * streamK
        spotA = (0.2f + 0.7f * quiet) * streamK
        // The white dwarf: a faint point in the low state, as its disk goes out.
        wdLit = when {
            low -> LowState.dwarf(lowK)
            refilling -> LowState.refillDwarf(refillK)
            else -> 1f
        }
    }

    /** Diffraction spikes of white light around [c], fading toward their tips: the diamond ring's. */
    private fun DrawScope.flare(c: Offset, len: Float, width: Float, a: Float) {
        if (a <= 0.005f || len <= 0.5f) return
        spikePath.rewind()
        val wd = width / len
        for (k in 0 until 4) {
            val ang = k * PI_F / 2f
            val l = if (k % 2 == 0) 1f else 0.62f
            val dx = cos(ang); val dy = sin(ang)
            spikePath.moveTo(-dy * wd, dx * wd)
            spikePath.lineTo(dx * l, dy * l)
            spikePath.lineTo(dy * wd, -dx * wd)
            spikePath.close()
        }
        withTransform({ translate(c.x, c.y); scale(len, len, Offset.Zero) }) { drawPath(spikePath, spikeWhite, alpha = a) }
    }

    /** The sun's radius for the layout: the disk's rim, which never changes size (in the low state too: the donor keeps its orbit). */
    private fun sunRadiusNow(): Float = AccretionDisk.R_OUT * dp

    /** The system at the centre: the disk, the white dwarf, the donor; the eclipse over it. */
    private fun DrawScope.drawSun(t: Float, focus: Float, reduced: Boolean, inv: Float) {
        val c = Offset(cx, cy)
        val unit = AccretionDisk.R_OUT * dp
        val cov = cover
        val ring = diamond()
        disk.draw(
            this, c, unit, dp, inv, focus * (1f - 0.94f * cov),
            hotOut = hotOut, hotIn = hotIn, cool = coolDens, glow = glow, flow = flow,
            donor = donorAng, stream = streamA, spot = spotA, wd = wdLit, reduced = reduced,
        )
        if (cov > 0.01f || ring > 0.01f) drawEclipse(c, unit, t, focus, reduced, inv, cov, ring)
    }

    /**
     * Totality: the disk's light scattered wide beyond the moon, a thin bright edge where it
     * peeks past, the moon's black disc, and the diamond ring as the light comes back. [r] is the
     * disk's rim; the moon is a hair larger, so the donor on its orbit outside still shows.
     */
    private fun DrawScope.drawEclipse(c: Offset, r: Float, t: Float, focus: Float, reduced: Boolean, inv: Float, cov: Float, ring: Float) {
        val tot = smooth((cov - 0.75f) / 0.25f)
        withTransform({ translate(c.x, c.y); scale(r, r, Offset.Zero) }) {
            if (tot > 0.01f) {
                scale(1.2f, 1.2f, Offset.Zero) { drawCircle(pearlBrush, 3.6f, Offset.Zero, alpha = 0.22f * tot * focus) }
                drawCircle(innerCorona, 1.9f, Offset.Zero, alpha = 0.4f * tot * focus)
                drawCircle(edgeBrush, 1.1f, Offset.Zero, alpha = 0.8f * tot * focus)
            }
            // The moon, a hair larger than the disk and a hair off centre. Against the night it
            // shows only where it covers the disk's light.
            val dirX = 0.86f; val dirY = -0.51f
            drawCircle(HN.bg950, 1.06f, Offset(dirX * moonOff, dirY * moonOff), alpha = focus * smooth(cov / 0.35f))
        }
        if (ring > 0.01f) {
            // The bead sits on the limb opposite the moon: the last (or first) point of light.
            val sgn = if (moonOff >= 0f) 1f else -1f
            val bx = c.x - 0.86f * sgn * r; val by = c.y + 0.51f * sgn * r
            val br = r * (2f + 5f * ring)
            withTransform({ translate(bx, by); scale(br, br, Offset.Zero) }) { drawCircle(glowWhite, 1f, Offset.Zero, alpha = ring * focus) }
            drawCircle(Color.White, r * 0.14f, Offset(bx, by), alpha = ring * focus)
            flare(Offset(bx, by), r * 8f * ring, max(r * 0.06f, 0.7f * dp * inv), 0.9f * ring * focus)
        }
    }

    // ─── Planets and moons ──────────────────────────────────────────────────

    /**
     * A planet or moon as a sphere lit by the sun, drawn in unit space. A faint night side, a glow
     * centred where the light falls (so a planet between us and the sun shows a crescent, one
     * beyond it its full face), meridians turning about its own tilted axis, and a rim brighter
     * on the day side. ([lx], [ly], [lz]) points toward the sun; [nightK] 1 leaves only the night side.
     */
    private fun DrawScope.sphere(c: Offset, r: Float, color: Color, alpha: Float, lx: Float, ly: Float, lz: Float, nightK: Float, axis: Planet?, spin: Float, light: Float) {
        if (alpha <= 0.004f || r <= 0.1f) return
        val day = (1f + lz) * 0.5f * (1f - nightK) * light
        val l2 = hypot(lx, ly)
        val dx = if (l2 > 1e-3f) lx / l2 else PlanetLight.x
        val dy = if (l2 > 1e-3f) ly / l2 else PlanetLight.y
        val m = 0.64f + 0.55f * (1f - day)
        val k = 0.62f + 0.38f * day
        val body = bodyBrush(color)
        withTransform({ translate(c.x, c.y); scale(r, r, Offset.Zero) }) {
            drawCircle(color, 1f, Offset.Zero, alpha = alpha * 0.11f)
            val litA = alpha * (0.2f + 0.8f * day) * (0.2f + 0.8f * light * (1f - nightK))
            translate(dx * m, dy * m) {
                scale(k, k, Offset.Zero) { drawCircle(body, 1f / k, Offset(-dx * m / k, -dy * m / k), alpha = litA) }
            }
            if (axis != null) meridians(axis, color, alpha * (0.3f + 0.7f * light * (1f - nightK)), spin, lx, ly, lz)
            drawCircle(color, 1f, Offset.Zero, alpha = alpha * 0.2f, style = rimStroke)
            val sweep = 90f + 140f * day
            val ang = atan2(dy, dx) * 57.29578f
            drawArc(color, ang - sweep / 2f, sweep, false, Offset(-1f, -1f), Size(2f, 2f), alpha = alpha * 0.45f * (0.35f + 0.65f * day), style = rimStroke)
        }
    }

    /**
     * Meridians about the planet's own axis: each is half a great circle from pole to pole,
     * P(φ) = cos φ·A + sin φ·M, seen from the front only. With the axis leaning toward us the
     * pole sits inside the disc and the meridians converge on it, so it reads as a globe.
     */
    private fun DrawScope.meridians(p: Planet, color: Color, alpha: Float, spin: Float, lx: Float, ly: Float, lz: Float) {
        for (k in 0 until 4) {
            val lam = spin + k * PI_F / 2f
            val cl = cos(lam); val sl = sin(lam)
            val mx = cl * p.fx + sl * p.gx; val my = cl * p.fy + sl * p.gy; val mz = cl * p.fz
            val face = cl // 1 on the central meridian, 0 at the limb
            if (face <= 0.04f) continue
            val star = atan2(mz, p.az)
            val f0 = max(0f, star - PI_F / 2f); val f1 = min(PI_F, star + PI_F / 2f)
            if (f1 - f0 < 0.05f) continue
            val lit = (mx * lx + my * ly + mz * lz).coerceAtLeast(0f)
            val a = (alpha * face * (0.18f + 0.72f * lit)).coerceAtMost(0.9f * alpha)
            merPath.rewind()
            halfEllipse(p.ax, p.ay, mx, my, f0, f1)
            drawPath(merPath, color, alpha = a, style = merStroke)
        }
    }

    /** The arc cos φ·A + sin φ·M for φ in [f0, f1], as cubic Béziers (an affine image of a circular arc). */
    private fun halfEllipse(ax: Float, ay: Float, mx: Float, my: Float, f0: Float, f1: Float) {
        val n = if (f1 - f0 > PI_F / 2f + 0.01f) 2 else 1
        val d = (f1 - f0) / n
        val kk = 4f / 3f * tan(d / 4f)
        var a0 = f0
        merPath.moveTo(cos(a0) * ax + sin(a0) * mx, cos(a0) * ay + sin(a0) * my)
        for (i in 0 until n) {
            val a1 = a0 + d
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val p0x = c0 * ax + s0 * mx; val p0y = c0 * ay + s0 * my
            val t0x = -s0 * ax + c0 * mx; val t0y = -s0 * ay + c0 * my
            val p1x = c1 * ax + s1 * mx; val p1y = c1 * ay + s1 * my
            val t1x = -s1 * ax + c1 * mx; val t1y = -s1 * ay + c1 * my
            merPath.cubicTo(p0x + kk * t0x, p0y + kk * t0y, p1x - kk * t1x, p1y - kk * t1y, p1x, p1y)
            a0 = a1
        }
    }

    private fun DrawScope.drawPlanet(t: Float, p: Planet, light: Float, reduced: Boolean, inv: Float, isFocus: Boolean) {
        if (p.x.isNaN()) return
        val pos = Offset(p.x, p.y)
        val n = p.sessions.size
        val needs = p.needs
        val working = p.working
        val am = max(1f, aMax)
        // Nearer planets are a little bigger and brighter: one continuous scale, never a jump.
        val persp = 1f + 0.16f * (p.z / am).coerceIn(-1.2f, 1.2f)
        val near = 0.88f + 0.12f * (0.5f + 0.5f * (p.z / am)).coerceIn(0f, 1f)
        // Bigger the longer its sessions have run, eased so it grows (or shrinks) smoothly.
        val crowd = sqrt(6f / max(6, plist.size).toFloat())
        val rT = (3.2f + 1.25f * ln(1f + p.length / 10f)).coerceAtMost(9f) * dp * crowd
        p.rBase = if (p.rBase.isNaN() || reduced) rT else p.rBase + (rT - p.rBase) * (1f - exp(-dt * 0.9f))
        // The sphere: whole on its orbit, growing in (or shrinking away) where it stands as its
        // project comes into the sky or leaves it.
        val arrive = smooth(p.pres)
        val r = p.rBase * persp * (0.3f + 0.7f * arrive)
        p.radius = r
        // A planet takes a little of the disk's colour: warm, and more so as the disk goes quiet
        // and red (in eighths, so each planet's gradient changes a few times over a window, not
        // every frame). With no disk (the week's low state) it has none: lit by the white dwarf
        // alone, it shows its own colour.
        val tint = (0.12f + 0.2f * steps(1f - diskLight, 8)) * steps(gas, 8)
        val starCol = lerp(AccretionDisk.WARM, Color(0xFFB03A1E), steps(1f - diskLight, 8))
        val base = if (needs) HN.amber else p.base
        val col = if (tint > 0f) lerp(base, starCol, tint) else base
        // In an eclipse they keep only their night side: darker, lit faintly by the scattered
        // light. One the list isn't showing steps back while another is.
        val a = (if (working) 1f else 0.8f) * light * near * (1f - 0.4f * night) * arrive * stepBack(p)
        val pl = starLight

        val sa = a
        if (sa <= 0.005f) { p.ring = 0f; return }
        // It turns while a session in it works: spins up, and slows to a stop when they're done.
        val want = if (working && !reduced) 1f else 0f
        p.spinV += (want - p.spinV) * (1f - exp(-dt * (if (want > p.spinV) 2.5f else 1.1f)))
        if (p.spinV < 0.002f && want == 0f) p.spinV = 0f
        p.spin += dt * p.spinV * TAU / 4f
        // Toward the sun, in 3D.
        val lx = cx - p.x; val ly = cy - p.y; val lz = -p.z
        val ld = sqrt(lx * lx + ly * ly + lz * lz).coerceAtLeast(1f)
        val ux = lx / ld; val uy = ly / ld; val uz = lz / ld

        // Moons on the planet's equator, in shells of 4, 7, 10…; many moons read as a ring. They
        // stand still with their planet unless its project works (see draw). The shells go round
        // by Kepler: the innermost in MOON_T0 s, each outer one slower by (r / r0)^1.5.
        if (p.mx.size != n) { p.mx = FloatArray(n); p.my = FloatArray(n); p.mz = FloatArray(n) }
        val moonK = arrive
        var shell = 0
        var done = 0
        var outer = 0f
        val r0 = r + 5f * dp * crowd
        while (done < n) {
            val capN = 4 + shell * 3
            val count = min(capN, n - done)
            val rr = (r0 + shell * 3.6f * dp * crowd) * (0.6f + 0.4f * moonK)
            outer = rr
            val omega = Kepler.omega(r0 + shell * 3.6f * dp * crowd, r0, MOON_T0)
            for (j in 0 until count) {
                val se = p.sessions[done + j]
                val m = p.moons.getOrPut(se.id) { Moon().also { it.born = if (se.isNew && !reduced) t else -100f; if (it.born > lastBorn) lastBorn = it.born } }
                val ignite = expo((t - m.born) / 1.2f)
                val th = j * TAU / count + shell * 0.7f + p.moonPhase * omega
                val ct = cos(th) * rr * ignite; val st = sin(th) * rr * ignite
                p.mx[done + j] = pos.x - ct * p.gx + st * p.fx
                p.my[done + j] = pos.y - ct * p.gy + st * p.fy
                p.mz[done + j] = st * p.fz
                // A moon grows with how long its session has run, smoothly.
                val mrT = (1f + 0.42f * ln(1f + max(lenSeen[se.id] ?: 0f, se.lengthMin) / 10f)).coerceAtMost(2.6f) * dp
                m.r = if (m.r.isNaN() || reduced) mrT else m.r + (mrT - m.r) * (1f - exp(-dt * 0.9f))
            }
            done += count
            shell++
        }
        p.ring = outer
        if (moonK > 0.01f) drawMoons(t, p, a * moonK, back = true, light = pl)
        planetGlow(pos, r, col, sa * (0.06f + 0.06f * p.spinV))
        sphere(pos, r, col, sa, ux, uy, uz, night, if (r >= 3.4f * dp) p else null, p.spin, pl)
        if (needs && !isFocus && dimK < 0.99f) {
            // It needs you: an amber bracket that draws in every 2.6 s.
            val k = if (reduced) 1f else expo((t % 2.6f) / 0.35f)
            val b = r + (8f - 3f * k) * dp
            val len = 3.5f * dp
            val st = 1.2f * dp * inv
            val ba = light * arrive * stepBack(p) * (1f - dimK)
            drawLine(HN.amber, Offset(pos.x - b, pos.y - b), Offset(pos.x - b + len, pos.y - b), st, StrokeCap.Square, alpha = ba)
            drawLine(HN.amber, Offset(pos.x - b, pos.y - b), Offset(pos.x - b, pos.y - b + len), st, StrokeCap.Square, alpha = ba)
            drawLine(HN.amber, Offset(pos.x + b, pos.y + b), Offset(pos.x + b - len, pos.y + b), st, StrokeCap.Square, alpha = ba)
            drawLine(HN.amber, Offset(pos.x + b, pos.y + b), Offset(pos.x + b, pos.y + b - len), st, StrokeCap.Square, alpha = ba)
        }
        if (moonK > 0.01f) drawMoons(t, p, a * moonK, back = false, light = pl)
    }

    private fun DrawScope.planetGlow(pos: Offset, r: Float, col: Color, a: Float) {
        if (a > 0.004f) drawCircle(col, r * 1.7f, pos, alpha = a)
    }

    /** The moons behind the planet ([back]) or in front of it: mint when working, dim when idle, amber when they need you. */
    private fun DrawScope.drawMoons(t: Float, p: Planet, a0: Float, back: Boolean, light: Float) {
        val n = min(p.sessions.size, p.mx.size)
        for (i in 0 until n) {
            if ((p.mz[i] < 0f) != back) continue
            val se = p.sessions[i]
            val m = p.moons[se.id] ?: continue
            val mc = when {
                se.state == SessionState.NeedsYou -> HN.amber
                SkyPlanner.working(se) -> HN.accentBright
                else -> HN.inkMuted
            }
            val ignite = expo((t - m.born) / 1.2f)
            val ma = when {
                se.state == SessionState.NeedsYou -> 1f
                SkyPlanner.working(se) -> 0.95f
                else -> 0.5f
            } * a0 * ignite
            val mpos = Offset(p.mx[i], p.my[i])
            drawCircle(mc, m.r * 2.4f, mpos, alpha = ma * 0.12f)
            drawCircle(mc, m.r, mpos, alpha = ma * (0.55f + 0.45f * light * (1f - night)))
        }
    }

    /** The mint frame round what the home list is showing: two corners of a square of half-side [b]. */
    private fun drawFocusBracket(s: DrawScope, x: Float, y: Float, b: Float, a: Float) =
        drawBracket(s, x - b, y - b, x + b, y + b, a)

    /** Two mint corners, top left and bottom right, of the box ([l], [t], [r], [b]). */
    private fun drawBracket(s: DrawScope, l: Float, t: Float, r: Float, b: Float, a: Float) = with(s) {
        if (a < 0.01f) return@with
        val len = 6 * dp
        val st = 1.3f * dp
        drawLine(HN.accent, Offset(l, t), Offset(l + len, t), st, StrokeCap.Square, alpha = a)
        drawLine(HN.accent, Offset(l, t), Offset(l, t + len), st, StrokeCap.Square, alpha = a)
        drawLine(HN.accent, Offset(r, b), Offset(r - len, b), st, StrokeCap.Square, alpha = a)
        drawLine(HN.accent, Offset(r, b), Offset(r, b - len), st, StrokeCap.Square, alpha = a)
    }

    // ─── How often it needs a frame (ui/fx/Pace.kt) ─────────────────────────

    /** When the newest moon was born (clock seconds): it ignites over 1.2 s. */
    private var lastBorn = -100f

    /**
     * A timeline is playing on the wall clock (the drain at the limit, the low state's drain, the
     * refill and the outburst at a reset, an eclipse sliding, a limit found late fading in): it
     * needs every frame, for the few seconds it lasts. As of the last frame drawn. Once it has
     * played, a quiet disk or the low state holds still.
     */
    val busy: Boolean get() = birthing ||
        (low && lowK < 0.999f) || (dead && loudLimit && sb < DRAIN + 0.5f) || enterK < 0.999f ||
        (eclOn && wt - tEclOn < ECL + 0.5f) || (!eclOn && tEclOff > -1e5f && wt - tEclOff < ECL + 0.5f)

    /**
     * A limit holds (the quiet disk, or the week's low state): its reset is due on the wall clock,
     * so even unseen the sky takes a look now and then instead of waiting for someone to draw it.
     */
    val waiting: Boolean get() = dead || low

    /**
     * Something in the system moves, as of the last frame: gas flows through the disk (its knots
     * spiral in), a working project's planet turns and its moons go round, a moon ignites, a
     * planet grows in or fades out, the sun settles at a new height, the list's selection eases
     * in. Motion means something: at rest nothing here moves.
     */
    val moving: Boolean get() = movingNow

    /**
     * Something signals without travelling: a needs-you bracket drawing in every 2.6 s. It needs
     * a smooth rate. (A quiet disk through the wait, the week's low state, and a full disk at
     * rest hold still: they ask for nothing beyond the far sky's slow frames.)
     */
    val signalling: Boolean get() = signalNow

    // ─── The far sky ────────────────────────────────────────────────────────

    private var gCur = Float.NaN

    /**
     * How much light the far sky has: it thins a little as the week fills, and steps back a
     * little round a quiet disk. The week's low state leaves it as it is: nothing out there
     * burns out or ends because our disk has gone quiet.
     */
    fun galaxyLight(usage: Usage, reduced: Boolean): Float {
        val base = 1f - 0.65f * usage.sevenDay.coerceIn(0f, 0.99f).pow(1.5f)
        // The far sky doesn't care about our disk: round a quiet one it steps back only a little,
        // so its light leads.
        val k = when {
            low -> 1f
            dead -> 1f - 0.1f * (if (loudLimit && !reduced) smooth((sb - 0.5f) / 2f) else enterK)
            birthing && !weekOutburst -> 0.9f + 0.1f * smooth(sr / 2f)
            else -> 1f
        }
        val target = base * k
        gCur = if (gCur.isNaN() || reduced) target else gCur + (target - gCur) * (1f - exp(-dt * 3f))
        return gCur
    }

    /**
     * Top and bottom of the system's band (px), for the far sky: the bottom is the band's with every
     * place taken, so the stars and the deep sky don't shift as projects come and go.
     */
    fun bandTopPx(): Float = bandTop
    fun bandBottomPx(): Float = bandTop + (full?.height ?: 0f) * dp

    /**
     * The companion's spot in the band (px): its centre, and the box its words keep to (left, top,
     * right, bottom). Null until the band has a row for it.
     */
    fun companionSpot(out: FloatArray): Boolean {
        val g = geo ?: return false
        val c = g.companion ?: return false
        val l = g.companionLabel ?: return false
        val y0 = if (cy.isNaN()) 0f else cy - g.sunY * dp
        out[0] = c[0] * dp; out[1] = y0 + c[1] * dp
        out[2] = l.l * dp; out[3] = y0 + l.t * dp; out[4] = l.r * dp; out[5] = y0 + l.b * dp
        return true
    }

    /**
     * Codex's star's spot in the band (px): its centre, and the box its words keep to (left, top,
     * right, bottom). Null until the band has a place for it.
     */
    fun codexSpot(out: FloatArray): Boolean {
        val g = geo ?: return false
        val c = g.codex ?: return false
        val l = g.codexLabel ?: return false
        val y0 = if (cy.isNaN()) 0f else cy - g.sunY * dp
        out[0] = c[0] * dp; out[1] = y0 + c[1] * dp
        out[2] = l.l * dp; out[3] = y0 + l.t * dp; out[4] = l.r * dp; out[5] = y0 + l.b * dp
        return true
    }

    // ─── Taps ───────────────────────────────────────────────────────────────

    /** What's under [pos] (screen): a planet's words, the belt's, then the nearest body (a planet or the sun). */
    fun hit(pos: Offset): CosmosHit? {
        if (!hitsEnabled) return null
        val padX = 8 * dp
        val padY = 10 * dp
        for (p in plist) {
            if (p.lx.isNaN() || p.vis <= 0.3f || !p.wanted) continue
            if (pos.x in (p.lx - padX)..(p.lx + p.lw + padX) && pos.y in (p.ly - padY)..(p.ly + p.lh + padY)) return CosmosHit.Project(p.project)
        }
        if (!beltBoxL.isNaN() && beltVis > 0.3f && plan.quiet.isNotEmpty() &&
            pos.x in (beltBoxL - padX)..(beltBoxL + beltBoxW + padX) && pos.y in (beltBoxT - padY)..(beltBoxT + beltBoxH + padY)
        ) return CosmosHit.Quiet
        // Otherwise the nearest body, relative to its size. The sun is the whole system at the
        // centre: the disk, the white dwarf and the donor on its orbit.
        var best: CosmosHit? = null
        var bestD = Float.MAX_VALUE
        val sunR = max(AccretionDisk.DONOR_A * dp * 1.2f, 16 * dp)
        val ds = hypot(pos.x - cx, pos.y - cy)
        if (ds < sunR) { best = CosmosHit.Sun; bestD = ds / sunR }
        for (p in plist) {
            if (p.x.isNaN() || !p.wanted || p.pres < 0.5f) continue
            val rr = max(p.radius + p.ring, 18 * dp)
            val d = hypot(p.x - pos.x, p.y - pos.y)
            if (d < rr && d / rr < bestD) { bestD = d / rr; best = CosmosHit.Project(p.project) }
        }
        return best
    }

    /** For TalkBack: the projects with planets, most urgent first, and the quiet ones. */
    fun projectNames(): List<String> = plan.planetNames
    fun quietNames(): List<String> = plan.quietNames
}
