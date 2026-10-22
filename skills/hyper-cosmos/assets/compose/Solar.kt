package com.prudnikweb.hypernova.ui.fx

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Path
import kotlin.math.ln
import com.prudnikweb.hypernova.ui.components.planetSphere
import com.prudnikweb.hypernova.ui.components.PlanetLight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
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
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.JetBrainsMono
import com.prudnikweb.hypernova.ui.theme.Manrope
import com.prudnikweb.hypernova.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Strings the renderer needs, resolved from resources by the composable. */
class SolarText(
    val readout: (Int, String) -> String,   // "72% · 1h 12m"
    /** Shown instead of the readout until the Mac has official numbers. */
    val unknown: String = "",
    val reborn: (String) -> String,         // "reborn in 0:24"
    val universe: (String) -> String,       // "the universe returns in 0:24"
    val duration: (Long) -> String,         // ms → "1h 12m"
)

sealed interface CosmosHit {
    data class Project(val name: String) : CosmosHit
    data class Session(val id: String) : CosmosHit
    data object Sun : CosmosHit
}

/**
 * The one solar system on the home screen.
 *
 * - **The sun is your 5-hour usage.** A young mint star when the window is fresh, it grows
 *   and warms to pale gold, swells into an amber-red giant near the limit (orbits wobble),
 *   and at the limit goes **supernova**. Everything dies: labels and orbits fade, planets are
 *   thrown off and drift cold. A nebula collapses as the reset nears, a pulsar then a
 *   protostar forms, and at the reset a new star ignites and the planets are captured back.
 * - **The 7-day budget is the universe.** The galaxy thins out as the week fills; at the
 *   weekly limit everything fades to **total darkness**. At the reset: a **Big Bang**, a
 *   point of light, stars spreading out from the centre, the sun igniting, planets forming.
 * - **Projects are planets** (Kepler orbits; tilt and phase seeded from the name), **sessions
 *   are moons** in shells. A planet is the activity meter's planet, lit from the sun, turning
 *   while one of its sessions works; it grows with how long its sessions have run, and so do
 *   the moons. Behind each planet, a fading tail of its orbit instead of the whole ellipse.
 *   Labels float beside their planet like a star chart and pick a free side every frame,
 *   sliding away from anything that comes near, so they never overlap.
 * - **Camera:** tap a planet and the camera flies in and follows it; its moons get names.
 *
 * Timelines (blast, rebirth, darkness, Big Bang) run on wall-clock time, so they finish even
 * when the animation clock is frozen (Reduce Motion) or the sky is hidden. Animation phases
 * (orbits, pulses) run on the shared ambient clock.
 */
internal class Solar {
    var text: SolarText? = null
    var onUsedBottom: (Float) -> Unit = {}
    var onBlast: () -> Unit = {}
    var onOverlay: (Boolean) -> Unit = {}
    /** 0 = alive, 1 = dead: the whole app drains to grey-black (after a supernova, at the weekly limit). */
    var onDeath: (Float) -> Unit = {}
    private var deathSent = 0f
    private val COOL = 9f       // seconds for the universe to cool down at the weekly limit
    private var coolK = 0f      // 0..1 progress of that cooling

    private var w = 0f
    private var h = 0f
    private var dp = 1f
    private var fontScale = 1f
    private var lastT = -1f
    private var dt = 0f
    private var epoch0 = -1L
    private var wt = 0f // wall-clock seconds since this Solar was created

    private val nameStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = 9.sp, letterSpacing = 0.2.em)
    private val countStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 9.sp)
    private val moonStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 11.sp, fontFeatureSettings = "ss01")
    private val moonHexStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 9.sp)
    // A soft dark halo keeps the readout legible across the star's spikes and bloom.
    private val readoutStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.sp, shadow = Shadow(Color(0xE014110E), blurRadius = 10f))
    private val bornStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = 9.sp, letterSpacing = 0.22.em)

    // ─── Labels that place themselves ───────────────────────────────────────

    /** A floating label: picks the freest side of its anchor each frame and slides there. */
    private class Tag {
        var ang = Float.NaN // smoothed direction the label sits in, around its body
        var x = Float.NaN
        var y = Float.NaN
        var alpha = 0f
        var w = 0f
        var h = 0f
        var ax = 0f // anchor (where the tick meets the label), screen space
        var ay = 0f
    }

    // Candidate sides, in order of preference: upper-right, lower-right, upper-left, lower-left, right, left, above, below.
    private val ANGLES = floatArrayOf(-0.6f, 0.6f, -2.54f, 2.54f, 0f, PI.toFloat(), -PI.toFloat() / 2, PI.toFloat() / 2)

    private class R4(var l: Float = 0f, var t: Float = 0f, var r: Float = 0f, var b: Float = 0f)
    private val placed = ArrayList<R4>()
    private var placedN = 0
    private fun nextPlaced(): R4 { if (placedN == placed.size) placed += R4(); return placed[placedN++] }

    private fun overlap(l: Float, t: Float, r: Float, b: Float, q: R4): Float {
        val ow = min(r, q.r) - max(l, q.l)
        val oh = min(b, q.b) - max(t, q.t)
        return if (ow > 0 && oh > 0) ow * oh else 0f
    }

    /**
     * Places [tag] (tw × th) beside a body at ([px], [py]) of screen radius [pr], on the side
     * facing away from ([fromX], [fromY]) (the sun, or the planet for moons). The direction is
     * smoothed and the text alignment blends continuously with it, so as the body orbits the
     * label glides around it, never hopping between sides. If it overlaps a label placed
     * earlier this frame it softly fades instead of moving.
     */
    private fun placeRadial(tag: Tag, px: Float, py: Float, pr: Float, tw: Float, th: Float, fromX: Float, fromY: Float, reduced: Boolean) {
        val dx = px - fromX
        val dy = py - fromY
        var target = if (abs(dx) + abs(dy) < 1f) -0.6f else atan2(dy, dx)
        // Near a screen edge there's no room outward: bend smoothly toward above/below instead.
        run {
            val ct = cos(target)
            val room = if (ct < 0f) px - pr - 7 * dp - 8 * dp else w - 8 * dp - (px + pr + 7 * dp)
            val need = tw * abs(ct)
            if (room < need) {
                val k = ((need - room) / tw).coerceIn(0f, 1f)
                val vert = if (sin(target) > 0f) PI.toFloat() / 2f else -PI.toFloat() / 2f
                var d = vert - target
                while (d > PI) d -= 2f * PI.toFloat()
                while (d < -PI) d += 2f * PI.toFloat()
                target += d * k
            }
        }
        if (tag.ang.isNaN() || reduced) tag.ang = target else {
            var d = target - tag.ang
            while (d > PI) d -= 2f * PI.toFloat()
            while (d < -PI) d += 2f * PI.toFloat()
            tag.ang += d * (1f - exp(-dt * 3.5f))
        }
        val ca = cos(tag.ang); val sa = sin(tag.ang)
        val gap = 7 * dp
        val ax = px + ca * (pr + gap)
        val ay = py + sa * (pr + gap * 0.8f)
        val hx = (ca * 1.4f).coerceIn(-1f, 1f)
        val hy = (sa * 1.4f).coerceIn(-1f, 1f)
        tag.x = (ax - tw * (0.5f - 0.5f * hx)).coerceIn(8 * dp, max(8 * dp, w - 8 * dp - tw))
        tag.y = ay - th * (0.5f - 0.5f * hy)
        tag.ax = ax; tag.ay = ay; tag.w = tw; tag.h = th
        var ov = 0f
        for (i in 0 until placedN) ov += overlap(tag.x, tag.y, tag.x + tw, tag.y + th, placed[i])
        // Fully out when covered: with many projects a faint label under another still reads as overlap.
        val ta = 1f - (ov / (tw * th * 0.3f)).coerceIn(0f, 1f)
        tag.alpha = if (reduced || tag.alpha == 0f && ov == 0f) ta else tag.alpha + (ta - tag.alpha) * (1f - exp(-dt * 5f))
        nextPlaced().apply { l = tag.x; t = tag.y; r = tag.x + tw; b = tag.y + th }
    }

    // ─── Bodies ─────────────────────────────────────────────────────────────

    private class Planet(val project: String) {
        private val rnd = Random(project.hashCode())
        val tilt = 0.36f + rnd.nextFloat() * 0.08f
        val shade = rnd.nextFloat()
        val wobblePhase = rnd.nextFloat() * 6f
        var theta = rnd.nextFloat() * 2f * PI.toFloat()
        var moonPhase = rnd.nextFloat() * 6f
        var index = 0
        var sessions: List<Session> = emptyList()
        var aTarget = 0f
        var aCur = Float.NaN
        var x = Float.NaN; var y = Float.NaN    // world (unzoomed)
        var sx = Float.NaN; var sy = Float.NaN  // screen
        var radius = 0f
        var ring = 0f
        var free = false
        var fx = Float.NaN; var fy = Float.NaN; var vx = 0f; var vy = 0f
        var cap = 1f      // 1 = on its orbit, 0 = fully adrift
        var capFrom = 0f  // capture start (wall s)
        var capBase = 0f  // capture factor at capture start
        val moonBorn = HashMap<String, Float>()
        var mx = FloatArray(0); var my = FloatArray(0)
        val tag = Tag()
        var nameL: TextLayoutResult? = null
        var countL: TextLayoutResult? = null
        var labelKey = ""
        val moonTags = HashMap<String, Tag>()
        val moonL = HashMap<String, Pair<TextLayoutResult, TextLayoutResult>>()
        var needs = false
        var working = false
        var base = HN.accent
        /** Minutes its sessions have run, all together. */
        var length = 0f
        var spin = rnd.nextFloat() * 6f
        var spinV = 0f
        fun refresh() {
            needs = sessions.any { it.state == SessionState.NeedsYou }
            working = sessions.any { it.state == SessionState.Working }
            length = sessions.sumOf { it.lengthMin.toDouble() }.toFloat()
            base = lerp(HN.accentDim, HN.accentBright, 0.35f + 0.5f * shade)
            if (moonBorn.size > sessions.size + 8) { val live = sessions.mapTo(HashSet()) { it.id }; moonBorn.keys.retainAll(live) }
        }
    }

    private var labelOrder: List<Planet> = emptyList()
    private fun refreshOrder() {
        for (p in planets.values) p.refresh()
        labelOrder = planets.values.sortedBy { p -> if (p.needs) 1 else if (p.working) 2 else 3 }
    }

    private val planets = LinkedHashMap<String, Planet>()
    private var layoutKey: List<Session>? = null
    private var sigKey = ""

    private var cx = 0f
    private var cy = 0f
    private var bandTop = 0f
    private var aMin = 0f
    private var aMax = 0f
    private var bMax = 0f
    private var viewX = 0f
    private var viewY = 0f
    private var sunSx = 0f
    private var sunSy = 0f

    private var uCur = Float.NaN
    private var sunPhase = 0f
    private val sunTag = Tag()
    private var readoutL: TextLayoutResult? = null
    private var readoutKey = ""
    private var universeL: TextLayoutResult? = null
    private var universeKey = ""

    // Phases, wall-clock seconds.
    private var limitedSeen = false
    private var weekSeen = false
    private var t0 = -1e6f
    private var t1 = -1e6f
    private var tDark = -1e6f
    private var tBang = -1e6f
    private var loudBlast = false
    private var progress = 0f
    private var overlayUntil = -1e6f
    private var overlayOn = false

    // Camera.
    private var camK = 0f
    private var camX = 0f
    private var camY = 0f
    private var camProject: String? = null
    private var zs = 1f
    private val ZOOM = 2.6f

    private val DEBRIS = 110
    private val dvx = FloatArray(DEBRIS); private val dvy = FloatArray(DEBRIS)
    private val dLife = FloatArray(DEBRIS); private val dKind = IntArray(DEBRIS)

    private val NEB = 9
    private val nebAng = FloatArray(NEB); private val nebDist = FloatArray(NEB); private val nebR = FloatArray(NEB)
    private val nebSpin = FloatArray(NEB)
    private val nebHot = arrayOfNulls<Brush>(NEB)
    private val nebCool = arrayOfNulls<Brush>(NEB)
    private val NEB_R0 = 100f

    private var hitsEnabled = false

    // Strokes cached so frames don't allocate native path effects.
    private var strokeDp = -1f
    private val diskStrokes = arrayOfNulls<Stroke>(10)
    private fun ensureStrokes() {
        if (strokeDp == dp) return
        strokeDp = dp
        for (i in 0 until 10) diskStrokes[i] = Stroke(0.8f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * dp, 3f * dp), i * 0.5f * dp))
    }
    /** The accretion disk turns by cycling through precomputed dash phases. */
    private fun diskStroke(t: Float): Stroke {
        ensureStrokes()
        return diskStrokes[((t * 18f / 0.5f).toInt() % 10 + 10) % 10]!!
    }

    // ─── Layout ─────────────────────────────────────────────────────────────

    /**
     * A fading tail along an ellipse (centre [ox], [oy]; semi-axes [ra], [rb]) behind angle [th]:
     * [arc] radians long, brightest at the body, gone at its end. [dx], [dy] shift the head onto
     * the body where it's off its ellipse (wobbling, being captured); the tail stops [gap] short
     * of the body, so it never shows through it.
     */
    private fun DrawScope.tail(ox: Float, oy: Float, ra: Float, rb: Float, th: Float, arc: Float, color: Color, alpha: Float, width: Float, dx: Float = 0f, dy: Float = 0f, gap: Float = 0f) {
        val n = 40
        val hx = ox + ra * cos(th) + dx; val hy = oy + rb * sin(th) + dy
        var px = ox + ra * cos(th - arc)
        var py = oy + rb * sin(th - arc)
        for (i in 1..n) {
            val k = i / n.toFloat()
            val a = th - arc * (1f - k)
            val x = ox + ra * cos(a) + dx * k
            val y = oy + rb * sin(a) + dy * k
            if (hypot(x - hx, y - hy) < gap) break
            drawLine(color, Offset(px, py), Offset(x, y), strokeWidth = width * (0.35f + 0.65f * k), cap = StrokeCap.Butt, alpha = alpha * k * k)
            px = x; py = y
        }
    }

    private fun signature(sessions: List<Session>): String {
        val sb = StringBuilder(sessions.size * 24)
        for (s in sessions) sb.append(s.project).append('|').append(s.id).append('|').append(s.state.ordinal).append('|').append(s.name).append(';')
        return sb.toString()
    }

    private fun layout(sessions: List<Session>, skyTop: Float) {
        val groups = LinkedHashMap<String, MutableList<Session>>()
        for (s in sessions) groups.getOrPut(s.project) { mutableListOf() }.add(s)
        planets.keys.retainAll(groups.keys)
        for (p in groups.keys) planets.getOrPut(p) {
            Planet(p).also { np -> if (limitedSeen || weekSeen) { np.free = true; np.cap = 0f } } // born into a dead system
        }
        val list = planets.values.toList()
        list.forEachIndexed { i, p -> p.index = i; p.sessions = groups.getValue(p.project) }
        refreshOrder()
        val n = list.size
        bandTop = h * skyTop
        aMax = w * 0.5f - 34 * dp // room for the outermost planet's moons
        aMin = w * 0.15f
        bMax = aMax * 0.44f
        cx = w / 2f
        cy = bandTop + 30 * dp + bMax
        list.forEachIndexed { i, p ->
            p.aTarget = if (n == 1) w * 0.28f else aMin + (aMax - aMin) * i / (n - 1).toFloat()
            if (p.aCur.isNaN()) p.aCur = p.aTarget
        }
        onUsedBottom(cy + bMax + 40 * dp)
    }

    private fun measureLabels(p: Planet, measurer: TextMeasurer) {
        val key = "${p.project}|${p.sessions.size}"
        if (key != p.labelKey) {
            p.labelKey = key
            p.nameL = measurer.measure(p.project.uppercase(), nameStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = (w * 0.42f).toInt().coerceAtLeast(1)))
            p.countL = measurer.measure("${p.sessions.size}", countStyle, maxLines = 1)
        }
    }

    // ─── Per-frame ──────────────────────────────────────────────────────────

    fun draw(
        s: DrawScope, t: Float, sessions: List<Session>, usage: Usage, focus: Float,
        skyTop: Float, zoom: String?, measurer: TextMeasurer, reduced: Boolean, now: Long,
    ) = with(s) {
        if (epoch0 < 0) epoch0 = now
        wt = (now - epoch0) / 1000f
        if (size.width != w || size.height != h || density != dp || fontScale != this@Solar.fontScale || bandTop != h * skyTop) {
            w = size.width; h = size.height; dp = density; this@Solar.fontScale = fontScale
            layoutKey = null; sigKey = ""; readoutKey = ""; universeKey = ""
            for (p in planets.values) { p.labelKey = ""; p.moonL.clear() }
            bandTop = h * skyTop
        }
        dt = if (lastT < 0) 0f else (t - lastT).coerceIn(0f, 0.05f)
        lastT = t
        if (layoutKey !== sessions) {
            layoutKey = sessions
            val sig = signature(sessions)
            if (sig != sigKey) { sigKey = sig; layout(sessions, skyTop) } else {
                val by = sessions.groupBy { it.project }
                for (p in planets.values) by[p.project]?.let { p.sessions = it }
                refreshOrder()
            }
        }

        transitions(usage, now, reduced)
        val target = if (usage.anyLimited) 1f else usage.fiveHour.coerceIn(0f, 1f)
        uCur = if (uCur.isNaN() || reduced) target else uCur + (target - uCur) * (1f - exp(-dt * 4f))
        val period = 6f - 3.8f * uCur.coerceIn(0f, 1f) // continuous: 6s young → 2.2s at the limit
        if (!reduced) sunPhase += dt * 2f * PI.toFloat() / period

        val remnant = limitedSeen
        val sinceBlast = wt - t0
        val sinceBirth = wt - t1
        val sinceDark = wt - tDark
        val sinceBang = wt - tBang
        val reborn = !remnant && !weekSeen && sinceBirth in 0f..4f
        val banging = !weekSeen && sinceBang in 0f..6f
        // Total darkness at the weekly limit, and the Big Bang coming out of it.
        coolK = if (!weekSeen) 0f else if (reduced) 1f else smooth((sinceDark / COOL).coerceIn(0f, 1f))
        val dark = when {
            weekSeen -> coolK
            banging -> 1f - Motion.Expo.transform(((sinceBang - 0.6f) / 3f).coerceIn(0f, 1f))
            else -> 0f
        }
        // The whole app's colour: drains after a supernova and while the universe cools; returns with new life.
        val death = when {
            weekSeen -> coolK
            banging -> 1f - smooth(((sinceBang - 0.6f) / 2.4f).coerceIn(0f, 1f))
            limitedSeen -> if (reduced || !loudBlast) 1f else smooth(((sinceBlast - 0.4f) / 2.2f).coerceIn(0f, 1f))
            !limitedSeen && sinceBirth in 0f..4f -> 1f - smooth(((sinceBirth - 0.1f) / 2.2f).coerceIn(0f, 1f))
            else -> 0f
        }
        if (abs(death - deathSent) > 0.004f || (death == 0f && deathSent != 0f) || (death == 1f && deathSent != 1f)) { deathSent = death; onDeath(death) }
        // Everything dies after a supernova; the labels return as the new star settles.
        val dead = when {
            remnant -> if (reduced) 1f else sinceBlast.coerceIn(0f, 1f)
            reborn -> 1f - ((sinceBirth - 2f) / 1.5f).coerceIn(0f, 1f)
            else -> 0f
        }
        // Names go first: they're gone early in a death (supernova or cooling), back last after a birth.
        val namesGone = if (weekSeen) smooth((sinceDark / 1.5f).coerceIn(0f, 1f)) else dark
        val labelsLive = (1f - dead) * (1f - namesGone) * (if (banging) ((sinceBang - 3.5f) / 1.5f).coerceIn(0f, 1f) else 1f)
        if (overlayOn && wt > overlayUntil) { overlayOn = false; onOverlay(false) }

        // Orbits ease to their targets; planets advance by integrated angle (no racing).
        val wob = ((uCur - 0.8f) / 0.2f).coerceIn(0f, 1f) * 5f * dp * (if (remnant || weekSeen) 0f else 1f)
        for (p in planets.values) {
            p.aCur = if (reduced) p.aTarget else p.aCur + (p.aTarget - p.aCur) * (1f - exp(-dt * 2.5f))
            val omega = (2f * PI.toFloat() / 48f) * (aMin / p.aCur).pow(1.5f)
            if (!reduced) { p.theta += dt * omega; p.moonPhase += dt * (0.3f + 0.7f * p.cap) }
            val ox = cx + p.aCur * cos(p.theta) + (if (reduced) 0f else sin(t * 3.1f + p.wobblePhase) * wob)
            val oy = cy + p.aCur * p.tilt * sin(p.theta) + (if (reduced) 0f else cos(t * 2.3f + p.wobblePhase) * wob * 0.6f)
            if (p.free) {
                if (p.fx.isNaN()) { p.fx = ox; p.fy = oy }
                if (!reduced && weekSeen) {
                    // Heat death: drifting away into the emptiness, nothing pulls them back.
                    p.fx += p.vx * dt
                    p.fy += p.vy * dt
                } else if (!reduced) {
                    val damp = exp(-dt * 0.9f)
                    p.vx = p.vx * damp + sin(t * 0.7f + p.wobblePhase) * 8f * dp * dt
                    p.vy = p.vy * damp + cos(t * 0.5f + p.wobblePhase) * 6f * dp * dt
                    p.fx += p.vx * dt
                    p.fy += p.vy * dt
                    val l = 16 * dp + p.ring; val r = w - 16 * dp - p.ring
                    val tp = cy - bMax - 6 * dp; val bt = cy + bMax + 6 * dp
                    if (p.fx < l) { p.fx = l; p.vx = abs(p.vx) * 0.5f }
                    if (p.fx > r) { p.fx = r; p.vx = -abs(p.vx) * 0.5f }
                    if (p.fy < tp) { p.fy = tp; p.vy = abs(p.vy) * 0.5f }
                    if (p.fy > bt) { p.fy = bt; p.vy = -abs(p.vy) * 0.5f }
                }
                p.cap = when {
                    weekSeen -> 0f
                    remnant -> ((progress - 0.88f) / 0.12f).coerceIn(0f, 1f) * 0.25f
                    reduced -> 1f
                    else -> p.capBase + (1f - p.capBase) * Motion.Expo.transform(((wt - p.capFrom) / 2.3f).coerceIn(0f, 1f))
                }
                p.x = p.fx + (ox - p.fx) * p.cap
                p.y = p.fy + (oy - p.fy) * p.cap
                if (!remnant && !weekSeen && p.cap >= 0.999f) { p.free = false; p.cap = 1f; p.fx = Float.NaN }
            } else {
                p.cap = 1f
                p.x = ox; p.y = oy
            }
        }

        // Camera: fly to the focused planet and follow it; fly back out when unfocused.
        val focused = zoom?.let { planets[it] }
        if (focused != null) camProject = focused.project
        val camPlanet = camProject?.let { planets[it] }
        val camTarget = if (focused != null && !remnant && !weekSeen) 1f else 0f
        camK = if (reduced) camTarget else camK + (camTarget - camK) * (1f - exp(-dt * 3.2f))
        if (camK < 0.001f && focused == null) camProject = null
        val ck = Motion.Expo.transform(camK.coerceIn(0f, 1f))
        zs = 1f + (ZOOM - 1f) * ck
        val tx = camPlanet?.x?.takeIf { !it.isNaN() } ?: cx
        val ty = camPlanet?.y?.takeIf { !it.isNaN() } ?: cy
        camX = cx + (tx - cx) * ck
        camY = cy + (ty - cy) * ck
        viewX = cx
        viewY = cy + bMax * 0.15f * ck
        for (p in planets.values) { p.sx = viewX + (p.x - camX) * zs; p.sy = viewY + (p.y - camY) * zs }
        sunSx = viewX + (cx - camX) * zs
        sunSy = viewY + (cy - camY) * zs

        hitsEnabled = focus > 0.35f && dark < 0.5f
        if (focus <= 0.01f) {
            for (p in planets.values) { p.sx = Float.NaN; p.sy = Float.NaN }
            return@with
        }
        val f2 = focus * focus
        val life = 1f - dark
        val bodyLight = focus * life

        // ── World layer (zoomable) ──
        withTransform({ translate(viewX, viewY); scale(zs, zs, pivot = Offset.Zero); translate(-camX, -camY) }) {
            val inv = 1f / zs
            val orbitAlpha = when {
                remnant -> 0.05f * (1f - dead) + 0.03f * progress
                reborn -> 0.04f + 0.14f * Motion.Expo.transform((sinceBirth / 2.5f).coerceIn(0f, 1f))
                else -> 0.18f
            } * (1f - 0.6f * ck)
            if (orbitAlpha * bodyLight > 0.004f) {
                // Each planet trails a fading arc of its orbit, as if it left a wake.
                for (p in planets.values) {
                    if (p.x.isNaN() || p.cap < 0.02f) continue
                    val a = p.aCur
                    val ex = cx + a * cos(p.theta); val ey = cy + a * p.tilt * sin(p.theta)
                    val col = lerp(HN.star, if (p.needs) HN.amber else p.base, 0.45f)
                    tail(cx, cy, a, a * p.tilt, p.theta, 1.9f, col, (orbitAlpha * 2.6f).coerceAtMost(0.55f) * bodyLight * p.cap, 1.2f * dp * inv, p.x - ex, p.y - ey, gap = p.radius + 2f * dp * inv)
                }
            }
            if (remnant || reborn) drawNebula(this, t, focus * life, reduced)
            for (p in planets.values) if (p.y < cy) drawPlanet(this, t, p, bodyLight, back = true, reduced = reduced, banging = banging, inv = inv, isFocus = focused === p)
            drawSun(this, t, focus, reduced, remnant, reborn, banging, sinceBlast, sinceBirth, sinceBang, life, inv)
            for (p in planets.values) if (p.y >= cy) drawPlanet(this, t, p, bodyLight, back = false, reduced = reduced, banging = banging, inv = inv, isFocus = focused === p)
        }

        // ── Screen layer: labels never scale ──
        placedN = 0
        val sunR = sunRadiusNow(remnant, reborn, banging, sinceBirth, sinceBang) * zs
        val txt = text
        // The sun's own label first: usage, or the countdown to the next star.
        if (txt != null && !weekSeen) {
            val left = max(0L, usage.fiveHourResetAt - now)
            val pct = (uCur * 100).toInt().coerceIn(0, 100)
            val known = usage.known || remnant
            val key = "${if (remnant) 1 else 0}|$pct|${left / 1000}|$known"
            if (key != readoutKey) {
                readoutKey = key
                val label = when {
                    remnant -> txt.reborn(txt.duration(left)).uppercase()
                    !known -> txt.unknown
                    else -> txt.readout(pct, txt.duration(left))
                }
                readoutL = measurer.measure(label, if (remnant) bornStyle else readoutStyle, maxLines = 1)
            }
            readoutL?.let { l ->
                // Fixed just under the sun; it never moves, so nothing near it jumps.
                sunTag.x = sunSx - l.size.width / 2f
                sunTag.y = sunSy + max(sunR * 1.9f, 15 * dp) + 4 * dp
                sunTag.alpha = 1f
                nextPlaced().apply { this.l = sunTag.x; this.t = sunTag.y; r = sunTag.x + l.size.width; b = sunTag.y + l.size.height }
                val color = when {
                    remnant -> lerp(HN.amber, HN.accent, progress)
                    uCur >= 0.8f -> HN.amber
                    else -> HN.inkDim
                }
                val a = sunTag.alpha * f2 * (1f - ck) * (if (banging) ((sinceBang - 3f) / 1.5f).coerceIn(0f, 1f) else 1f)
                if (a > 0.01f) drawText(l, color = color, alpha = 0.95f * a, topLeft = Offset(sunTag.x, sunTag.y))
            }
        }
        // Planet labels in priority order: focused, needs you, working, the rest.
        if (labelsLive > 0.01f) {
            // The focused planet is labelled first; then needs-you, working, the rest.
            if (focused != null) labelPlanet(this, focused, focused, ck, labelsLive, f2, measurer, reduced)
            for (p in labelOrder) if (p !== focused) labelPlanet(this, p, focused, ck, labelsLive, f2, measurer, reduced)
            if (focused != null && ck > 0.55f) drawMoonLabels(this, focused, ((ck - 0.55f) / 0.45f) * labelsLive * f2, measurer, reduced)
        }
        // The dark universe: one barely visible line.
        if (weekSeen && txt != null) {
            val label = txt.universe(txt.duration(max(0L, usage.sevenDayResetAt - now))).uppercase()
            if (label != universeKey) { universeKey = label; universeL = measurer.measure(label, bornStyle, maxLines = 1) }
            universeL?.let { l -> drawText(l, color = HN.inkDim, alpha = 0.4f * smooth(((coolK - 0.8f) / 0.2f).coerceIn(0f, 1f)) * f2, topLeft = Offset(cx - l.size.width / 2f, cy - l.size.height / 2f)) }
        }
    }

    private fun labelPlanet(s: DrawScope, p: Planet, focused: Planet?, ck: Float, labelsLive: Float, f2: Float, measurer: TextMeasurer, reduced: Boolean) = with(s) {
        if (p.sx.isNaN()) return@with
        measureLabels(p, measurer)
        val nl = p.nameL ?: return@with
        val cl = p.countL ?: return@with
        val tw = nl.size.width + 5 * dp + cl.size.width
        val th = max(nl.size.height, cl.size.height).toFloat()
        val isFocus = p === focused
        if (isFocus && ck > 0.05f) {
            // Zoomed in: the name sits calmly above its planet.
            val above = (p.radius + p.ring) * zs + 12 * dp
            placeRadial(p.tag, p.sx, p.sy, 0f, tw, th, p.sx, p.sy + 1f, reduced)
            val tx = p.sx - tw / 2f; val ty = p.sy - above - th
            p.tag.x = p.tag.x + (tx - p.tag.x) * ck; p.tag.y = p.tag.y + (ty - p.tag.y) * ck
            p.tag.ax = p.sx; p.tag.ay = ty + th
        } else {
            placeRadial(p.tag, p.sx, p.sy, (p.radius + p.ring * 0.5f) * zs, tw, th, sunSx, sunSy, reduced)
        }
        val a = p.tag.alpha * labelsLive * f2 * (if (isFocus) 1f else 1f - ck) * (if (p.free) 0.5f else 1f)
        if (a < 0.01f) return@with
        val col = when { isFocus -> HN.ink; p.needs -> HN.amber; else -> HN.inkMuted }
        // A short tick from the planet's edge to its label: a star-chart annotation.
        val dirX = p.tag.ax - p.sx; val dirY = p.tag.ay - p.sy
        val len = max(1f, hypot(dirX, dirY))
        val e = (p.radius * zs + 2 * dp) / len
        drawLine(col, Offset(p.sx + dirX * e, p.sy + dirY * e), Offset(p.tag.ax, p.tag.ay), strokeWidth = 0.8f * dp, alpha = 0.45f * a)
        drawText(nl, color = col, alpha = 0.95f * a, topLeft = Offset(p.tag.x, p.tag.y + (th - nl.size.height) / 2))
        drawText(cl, color = if (p.needs) HN.amber else HN.inkDim, alpha = 0.85f * a, topLeft = Offset(p.tag.x + nl.size.width + 5 * dp, p.tag.y + (th - cl.size.height) / 2))
        if (isFocus) drawFocusBracket(this, p.sx, p.sy, (p.radius + p.ring) * zs + 6 * dp, a)
    }

    private fun transitions(usage: Usage, now: Long, reduced: Boolean) {
        val week = usage.weekLimited
        if (week && !weekSeen) {
            weekSeen = true
            tDark = wt - (now - (usage.weekLimitedAt ?: now)) / 1000f
            // Every planet starts drifting outward, slowly, from wherever it is.
            val rnd = Random(wt.toBits())
            for (p in planets.values) {
                p.free = true; p.cap = 0f
                if (!p.x.isNaN()) { p.fx = p.x; p.fy = p.y }
                val dx = (p.fx.takeIf { !it.isNaN() } ?: cx) - cx
                val dy = (p.fy.takeIf { !it.isNaN() } ?: cy) - cy
                val len = max(1f, hypot(dx, dy))
                val sp = (9f + rnd.nextFloat() * 9f) * dp
                p.vx = dx / len * sp + (rnd.nextFloat() - 0.5f) * 4f * dp
                p.vy = dy / len * sp * 0.6f + (rnd.nextFloat() - 0.5f) * 3f * dp
            }
        } else if (!week && weekSeen) {
            weekSeen = false
            tBang = if (reduced) wt - 6f else wt
            limitedSeen = usage.limited
            t1 = -1e6f
            uCur = usage.fiveHour.coerceIn(0f, 1f)
            // Planets form out of the newborn sun, one by one.
            for (p in planets.values) {
                p.free = true; p.fx = cx; p.fy = cy; p.vx = 0f; p.vy = 0f
                p.capBase = 0f; p.cap = 0f; p.capFrom = tBang + 1.6f + p.index * 0.22f
            }
            if (!reduced) startOverlay(6f)
        }
        if (weekSeen) { progress = 0f; return }
        val lim = usage.limited
        if (lim && !limitedSeen) {
            limitedSeen = true
            val age = (now - (usage.limitedAt ?: now)) / 1000f
            val fresh = age < 3f && !reduced
            t0 = if (fresh) wt else wt - max(age, 6f)
            blast(fresh)
        } else if (!lim && limitedSeen) {
            limitedSeen = false
            t1 = if (reduced) wt - 4f else wt
            uCur = usage.fiveHour.coerceIn(0f, 1f) // the newborn star is young, not the dead one's 100%
            for (p in planets.values) if (p.free) {
                p.capBase = p.cap // continue from wherever the late-cooldown pull brought it
                p.capFrom = t1 + p.index * 0.18f
            }
            if (!reduced) startOverlay(1.2f)
        }
        progress = if (lim) {
            val start = usage.limitedAt ?: now
            ((now - start).toFloat() / max(1L, usage.fiveHourResetAt - start)).coerceIn(0f, 1f)
        } else 1f
    }

    private fun startOverlay(seconds: Float) {
        overlayUntil = max(overlayUntil, wt + seconds)
        if (!overlayOn) { overlayOn = true; onOverlay(true) }
    }

    // ─── Sun ────────────────────────────────────────────────────────────────

    private val coronaPath = Path()
    private val spikePath = Path()
    private val diskPath = Path()

    /** Slow, uneven 0..1 wander from a few sines: scintillation, streamers, prominences. */
    private fun wav(t: Float, seed: Float) =
        0.5f + 0.25f * sin(t * 0.73f + seed * 3.1f) + 0.15f * sin(t * 1.37f + seed * 5.7f) + 0.1f * sin(t * 2.91f + seed * 1.3f)

    /**
     * A star as a telescope photographs it, from the outside in: a bloom of scattered light
     * falling off smoothly (no rings), a corona of soft uneven streamers that slowly breathe,
     * prominences looping off the limb when [active], a thin bright chromosphere, the photosphere
     * white-hot at the centre and darker toward the limb, granulation (and spots when [active])
     * once it's big enough on screen to show them, and faint diffraction spikes over it all. It
     * scintillates a little. [spikes] dims the spikes (a cooling star loses them).
     */
    private fun DrawScope.star(c: Offset, r: Float, col: Color, a: Float, t: Float, reduced: Boolean, inv: Float, active: Float = 0f, spikes: Float = 1f) {
        if (a <= 0.005f || r <= 0.1f) return
        val tw = if (reduced) 1f else 0.9f + 0.1f * wav(t * 1.8f, 0.3f)
        val hot = lerp(col, Color.White, 0.8f)
        // Bloom: smaller when zoomed in, or it would wash over the whole sky.
        val bloomR = r * 7.5f * (0.45f + 0.55f * inv)
        drawCircle(
            Brush.radialGradient(
                0f to col.copy(alpha = 0.32f * a * tw), 0.1f to col.copy(alpha = 0.17f * a * tw), 0.24f to col.copy(alpha = 0.065f * a * tw),
                0.5f to col.copy(alpha = 0.02f * a), 1f to col.copy(alpha = 0f), center = c, radius = bloomR,
            ),
            bloomR, c,
        )
        // Corona: streamers of uneven length, a few long ones, turning very slowly.
        coronaPath.rewind()
        val rot = if (reduced) 0f else t * 0.012f
        val n = 16
        var reach = 0f
        for (i in 0 until n) {
            val ang = rot + i * 2f * PI.toFloat() / n + 0.3f * sin(i * 2.7f)
            val len = r * (1.35f + 1.3f * (if (reduced) 0.5f else wav(t * 0.3f, i * 0.77f)) + (if (i % 4 == 1) 1.1f else 0f))
            reach = max(reach, len)
            val half = 0.16f + 0.06f * sin(i * 1.9f)
            val bend = 0.12f * sin(i * 3.3f + (if (reduced) 0f else t * 0.2f))
            val x1 = c.x + cos(ang - half) * r * 0.9f; val y1 = c.y + sin(ang - half) * r * 0.9f
            val x2 = c.x + cos(ang + half) * r * 0.9f; val y2 = c.y + sin(ang + half) * r * 0.9f
            val tx = c.x + cos(ang + bend) * len; val ty = c.y + sin(ang + bend) * len
            val mx = c.x + cos(ang + bend * 0.5f) * r * 1.25f; val my = c.y + sin(ang + bend * 0.5f) * r * 1.25f
            coronaPath.moveTo(x1, y1)
            coronaPath.quadraticTo(mx + (x1 - x2) * 0.25f, my + (y1 - y2) * 0.25f, tx, ty)
            coronaPath.quadraticTo(mx - (x1 - x2) * 0.25f, my - (y1 - y2) * 0.25f, x2, y2)
            coronaPath.close()
        }
        val cr = reach * 1.05f
        drawPath(coronaPath, Brush.radialGradient(0f to hot.copy(alpha = 0.3f * a), (r / cr) to col.copy(alpha = 0.24f * a), (r * 1.8f / cr).coerceAtMost(0.9f) to col.copy(alpha = 0.07f * a), 1f to col.copy(alpha = 0f), center = c, radius = cr))
        // Prominences: arches of glowing plasma rising off the limb, their feet on the surface.
        if (active > 0.01f) {
            val pc = lerp(col, Color(0xFFFF5A2A), 0.45f)
            val glow = Stroke(r * 0.13f + 1f * dp * inv, cap = StrokeCap.Round)
            val core = Stroke(r * 0.045f + 0.4f * dp * inv, cap = StrokeCap.Round)
            for (i in 0 until 3) {
                val th = i * 2.3f + 0.6f + (if (reduced) 0f else t * 0.03f + 0.15f * sin(t * 0.21f + i))
                val k = if (reduced) 0.6f else wav(t * 0.45f, i * 1.3f)
                val h = r * (0.1f + 0.26f * k)           // how high it rises
                val d = 0.3f + 0.1f * sin(i * 2.3f)       // half the angle between its feet
                coronaPath.rewind()
                coronaPath.moveTo(c.x + cos(th - d) * r * 0.96f, c.y + sin(th - d) * r * 0.96f)
                coronaPath.cubicTo(
                    c.x + cos(th - d * 0.9f) * (r + h * 1.35f), c.y + sin(th - d * 0.9f) * (r + h * 1.35f),
                    c.x + cos(th + d * 0.5f) * (r + h * 1.35f), c.y + sin(th + d * 0.5f) * (r + h * 1.35f),
                    c.x + cos(th + d) * r * 0.96f, c.y + sin(th + d) * r * 0.96f,
                )
                val pa = active * a * (0.55f + 0.45f * k)
                drawPath(coronaPath, pc, alpha = 0.16f * pa, style = glow)
                drawPath(coronaPath, lerp(pc, Color.White, 0.25f), alpha = 0.5f * pa, style = core)
            }
        }
        // Chromosphere: a thin bright rim of light just outside the limb.
        drawCircle(Brush.radialGradient(0.62f to hot.copy(alpha = 0.55f * a), 0.72f to col.copy(alpha = 0.32f * a), 1f to col.copy(alpha = 0f), center = c, radius = r * 1.45f), r * 1.45f, c)
        // Photosphere: white-hot in the middle, its colour toward the limb, a little darker at the edge.
        drawCircle(
            Brush.radialGradient(
                0f to lerp(col, Color.White, 0.94f), 0.5f to lerp(col, Color.White, 0.7f), 0.82f to lerp(col, Color.White, 0.32f),
                1f to lerp(col, Color(0xFF5A2A14), 0.22f), center = c, radius = r,
            ),
            r, c, alpha = a,
        )
        // The surface shows once it's big on screen: granulation, and spots on an active star.
        val detail = ((r / inv - 6f * dp) / (8f * dp)).coerceIn(0f, 1f)
        if (detail > 0.01f) {
            diskPath.rewind()
            diskPath.addOval(Rect(c, r * 0.97f))
            clipPath(diskPath) {
                val spin = if (reduced) 0f else t * 0.04f
                for (i in 0 until 11) {
                    val ang = i * 2.39996f + spin
                    val d = r * (0.18f + 0.62f * ((i * 0.618f) % 1f))
                    val gx = c.x + cos(ang) * d; val gy = c.y + sin(ang) * d * 0.95f
                    val gk = if (reduced) 0.5f else wav(t * 0.6f, i * 0.9f)
                    drawCircle(Color.White, r * (0.13f + 0.08f * gk), Offset(gx, gy), alpha = 0.09f * detail * a * gk)
                }
                if (active > 0.01f) for (i in 0 until 3) {
                    val ang = i * 2.1f + 0.4f + spin * 1.3f
                    val d = r * (0.35f + 0.2f * i)
                    val sx = c.x + cos(ang) * d; val sy = c.y + sin(ang) * d * 0.6f
                    drawCircle(Color(0xFF2A140C), r * (0.07f + 0.03f * i), Offset(sx, sy), alpha = 0.45f * active * detail * a)
                    drawCircle(Color(0xFF2A140C), r * (0.12f + 0.04f * i), Offset(sx, sy), alpha = 0.18f * active * detail * a)
                }
            }
        }
        // Diffraction spikes: a long horizontal pair and a shorter vertical one, over everything.
        if (spikes > 0.01f) {
            spikePath.rewind()
            val sl = r * (5.5f + 1.5f * tw)
            val wd = max(r * 0.13f, 0.5f * dp * inv)
            for (k in 0 until 4) {
                val ang = k * PI.toFloat() / 2f
                val len = if (k % 2 == 0) sl else sl * 0.62f
                val dx = cos(ang); val dy = sin(ang)
                spikePath.moveTo(c.x - dy * wd, c.y + dx * wd)
                spikePath.lineTo(c.x + dx * len, c.y + dy * len)
                spikePath.lineTo(c.x + dy * wd, c.y - dx * wd)
                spikePath.close()
            }
            drawPath(spikePath, Brush.radialGradient(0f to hot.copy(alpha = 0.7f * a * spikes * tw), 0.16f to col.copy(alpha = 0.3f * a * spikes * tw), 0.5f to col.copy(alpha = 0.07f * a * spikes), 1f to col.copy(alpha = 0f), center = c, radius = sl))
        }
    }

    private val sunStops = floatArrayOf(0f, 0.5f, 0.7f, 0.85f, 0.95f, 1f)
    private val sunColors = arrayOf(HN.accent, Color(0xFFCFFFB4), Color(0xFFF3E39A), HN.amber, Color(0xFFFF7A4A), Color(0xFFFF5A3C))
    private val sunSizes = floatArrayOf(4.5f, 6.5f, 8f, 10.5f, 14f, 16f)

    private fun keyframe(u: Float, values: FloatArray): Float {
        val x = u.coerceIn(0f, 1f)
        for (i in 1 until sunStops.size) if (x <= sunStops[i]) {
            val k = (x - sunStops[i - 1]) / (sunStops[i] - sunStops[i - 1])
            return values[i - 1] + (values[i] - values[i - 1]) * k
        }
        return values.last()
    }

    private fun sunColor(u: Float): Color {
        val x = u.coerceIn(0f, 1f)
        for (i in 1 until sunStops.size) if (x <= sunStops[i]) {
            val k = (x - sunStops[i - 1]) / (sunStops[i] - sunStops[i - 1])
            return lerp(sunColors[i - 1], sunColors[i], k)
        }
        return sunColors.last()
    }

    private val coolStops = floatArrayOf(0f, 0.25f, 0.55f, 0.8f, 1f)
    private val coolColors = arrayOf(Color(0xFFF2F6FF), Color(0xFFDDE8FF), Color(0xFFFF9A5A), Color(0xFF8A2A1A), Color(0xFF2A1410))

    /** A dying star's colour as it cools: white-hot → white-blue → orange → ember → black. */
    private fun coolColor(k: Float): Color {
        val x = k.coerceIn(0f, 1f)
        for (i in 1 until coolStops.size) if (x <= coolStops[i]) {
            val f = (x - coolStops[i - 1]) / (coolStops[i] - coolStops[i - 1])
            return lerp(coolColors[i - 1], coolColors[i], f)
        }
        return coolColors.last()
    }

    private fun smooth(x: Float) = x * x * (3f - 2f * x)

    private fun sunRadiusNow(remnant: Boolean, reborn: Boolean, banging: Boolean, sinceBirth: Float, sinceBang: Float): Float = when {
        remnant || weekSeen -> 6f * dp
        banging -> keyframe(uCur, sunSizes) * dp * Motion.Expo.transform(((sinceBang - 0.9f) / 1.7f).coerceIn(0f, 1f)).coerceAtLeast(0.3f)
        reborn -> 4.5f * dp + (keyframe(uCur, sunSizes) * dp - 4.5f * dp) * Motion.Expo.transform((sinceBirth / 1.6f).coerceIn(0f, 1f))
        else -> keyframe(uCur, sunSizes) * dp
    }

    private fun drawSun(
        s: DrawScope, t: Float, focus: Float, reduced: Boolean, remnant: Boolean, reborn: Boolean, banging: Boolean,
        sinceBlast: Float, sinceBirth: Float, sinceBang: Float, life: Float, inv: Float,
    ) = with(s) {
        val c = Offset(cx, cy)
        val a0 = focus * life
        if (weekSeen) {
            val k = coolK
            if (k >= 0.999f) return@with
            val col = coolColor(k)
            val r = keyframe(uCur, sunSizes) * dp * (1f - 0.55f * k)
            val a = focus * (1f - smooth(((k - 0.7f) / 0.3f).coerceIn(0f, 1f)))
            star(c, r, col, a, t, reduced, inv, spikes = 1f - k)
            return@with
        }
        if (remnant) {
            if (loudBlast && sinceBlast in 0f..0.35f && !reduced) {
                val k = sinceBlast / 0.35f
                val r = keyframe(1f, sunSizes) * dp * (1f + 2.5f * k)
                drawCircle(Color.White, r * 2.4f, c, alpha = 0.25f * (1 - k) * a0)
                drawCircle(lerp(sunColor(1f), Color.White, k), r, c, alpha = a0)
                return@with
            }
            val p = progress
            if (p < 0.45f) { // a pulsar: fast, sharp blinks
                val blink = if (reduced) 0.6f else (0.5f + 0.5f * sin(t * 12.6f)).pow(6)
                drawCircle(Color(0xFFCFE9FF), 1.4f * dp * 4f, c, alpha = 0.05f * a0)
                drawCircle(Color(0xFFCFE9FF), 1.4f * dp, c, alpha = (0.3f + 0.7f * blink) * a0 * (1f - p / 0.45f).coerceIn(0.3f, 1f))
            }
            if (p > 0.3f) { // a protostar with a turning accretion disk
                val k = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
                val flick = if (reduced) 1f else 0.88f + 0.12f * sin(t * 7f)
                val r = (1.2f + 3.3f * k) * dp
                val col = lerp(Color(0xFFCFE9FF), HN.accent, k)
                drawCircle(col, r * 4.5f, c, alpha = 0.06f * k * a0)
                drawCircle(col, r * 2.4f, c, alpha = 0.16f * k * a0)
                drawCircle(col, r, c, alpha = (0.35f + 0.6f * k) * flick * a0)
                val rx = (16f - 6f * k) * dp
                val disk = diskStroke(if (reduced) 0f else t)
                drawOval(col, topLeft = Offset(cx - rx, cy - rx * 0.32f), size = Size(rx * 2, rx * 0.64f), alpha = 0.35f * k * a0, style = disk)
            }
            return@with
        }
        val u = uCur
        val baseR = sunRadiusNow(false, reborn, banging, sinceBirth, sinceBang)
        val amp = 0.03f + 0.07f * ((u - 0.6f) / 0.4f).coerceIn(0f, 1f)
        val r = baseR * (if (reduced) 1f else 1f + amp * sin(sunPhase))
        // Out of the Big Bang the first star is white-hot, cooling to its usage colour.
        val col = if (banging) lerp(Color.White, sunColor(u), ((sinceBang - 1.2f) / 1.8f).coerceIn(0f, 1f)) else sunColor(u)
        val sa = if (banging) ((sinceBang - 0.8f) / 0.6f).coerceIn(0f, 1f) * focus else a0
        if (sa <= 0.01f) return@with
        // Past 80% it grows active: spots, and prominences looping off the limb.
        star(c, r, col, sa, t, reduced, inv, active = ((u - 0.75f) / 0.25f).coerceIn(0f, 1f))
        if (reborn && sinceBirth < 1.4f && !reduced) {
            val k = sinceBirth / 1.4f
            drawCircle(HN.accentBright, radius = Motion.Expo.transform(k) * w * 0.5f, center = c, alpha = 0.45f * (1 - k) * focus, style = Stroke(1.5f * dp * inv))
        }
    }

    // ─── Planets and moons ──────────────────────────────────────────────────

    private fun drawPlanet(s: DrawScope, t: Float, p: Planet, light: Float, back: Boolean, reduced: Boolean, banging: Boolean, inv: Float, isFocus: Boolean) = with(s) {
        if (p.x.isNaN()) return@with
        val pos = Offset(p.x, p.y)
        val n = p.sessions.size
        val needs = p.needs
        val working = p.working
        val depth = if (back) 0.85f else 1f
        val alive = 0.3f + 0.7f * p.cap // dim and cold while adrift, alive again once captured
        val crowd = sqrt(6f / max(6, planets.size).toFloat())
        // Bigger the longer its sessions have run: a few minutes, an hour, a long day.
        val r = (3.2f + 1.25f * ln(1f + p.length / 10f)).coerceAtMost(9f) * dp * crowd * depth
        p.radius = r
        val col = lerp(HN.inkDim, if (needs) HN.amber else p.base, p.cap)
        val form = if (banging) p.cap else 1f // out of the Big Bang, planets form as they're captured
        val a = (if (working) 1f else 0.8f) * light * depth * alive * form
        if (a <= 0.005f) { p.ring = 0f; return@with }
        // It turns while a session in it works: spins up, and slows to a stop when they're done.
        val want = if (working && !reduced && p.cap > 0.99f) 1f else 0f
        p.spinV += (want - p.spinV) * (1f - exp(-dt * (if (want > p.spinV) 2.5f else 1.1f)))
        p.spin += dt * p.spinV * 2f * PI.toFloat() / 4f
        // Lit from the sun.
        val lx = cx - p.x; val ly = cy - p.y
        val ld = hypot(lx, ly)
        val toSun = if (ld < 1f) PlanetLight else Offset(lx / ld, ly / ld)
        drawCircle(col, r * 1.7f, pos, alpha = a * (0.06f + 0.06f * p.spinV))
        planetSphere(pos, r, col, p.spin, toSun, alpha = a, stroke = r * 0.12f, meridians = r * zs >= 3.4f * dp)
        if (needs && p.cap > 0.5f && !isFocus) {
            val k = if (reduced) 1f else Motion.Expo.transform(((t % 2.6f) / 0.35f).coerceIn(0f, 1f))
            val b = r + (8f - 3f * k) * dp
            val len = 3.5f * dp
            val st = 1.2f * dp * inv
            drawLine(HN.amber, Offset(pos.x - b, pos.y - b), Offset(pos.x - b + len, pos.y - b), st, StrokeCap.Square, alpha = light)
            drawLine(HN.amber, Offset(pos.x - b, pos.y - b), Offset(pos.x - b, pos.y - b + len), st, StrokeCap.Square, alpha = light)
            drawLine(HN.amber, Offset(pos.x + b, pos.y + b), Offset(pos.x + b - len, pos.y + b), st, StrokeCap.Square, alpha = light)
            drawLine(HN.amber, Offset(pos.x + b, pos.y + b), Offset(pos.x + b, pos.y + b - len), st, StrokeCap.Square, alpha = light)
        }
        // Moons in shells of 4, 7, 10…; many moons read as a ring. They scatter while adrift.
        if (p.mx.size != n) { p.mx = FloatArray(n); p.my = FloatArray(n) }
        val spread = 1.7f - 0.7f * p.cap
        var shell = 0
        var done = 0
        var outer = 0f
        while (done < n) {
            val capN = 4 + shell * 3
            val count = min(capN, n - done)
            val rr = (r + 5f * dp * crowd + shell * 3.6f * dp * crowd) * spread
            outer = rr
            val omega = 2f * PI.toFloat() / (6f + shell * 3f)
            for (j in 0 until count) {
                val se = p.sessions[done + j]
                val bornAt = p.moonBorn.getOrPut(se.id) { if (se.isNew && !reduced) t else -100f }
                val ignite = Motion.Expo.transform(((t - bornAt) / 1.2f).coerceIn(0f, 1f))
                val th = j * 2f * PI.toFloat() / count + shell * 0.7f + p.moonPhase * omega
                val mx = pos.x + cos(th) * rr * ignite
                val my = pos.y + sin(th) * rr * 0.45f * ignite
                p.mx[done + j] = mx; p.my[done + j] = my
                val mc = when (se.state) {
                    SessionState.NeedsYou -> HN.amber
                    SessionState.Working -> HN.accentBright
                    SessionState.Idle -> HN.inkMuted
                }
                val ma = when (se.state) {
                    SessionState.NeedsYou -> 1f
                    SessionState.Working -> 0.95f
                    SessionState.Idle -> 0.5f
                } * light * depth * alive * ignite * form
                // A moon grows with how long its session has run.
                val mr = (1f + 0.42f * ln(1f + se.lengthMin / 10f)).coerceAtMost(2.6f) * dp * (if (isFocus) 1.15f else 1f)
                val m = Offset(mx, my)
                if (isFocus) {
                    // Zoomed in: each moon trails its own fading arc, and shows its lit side.
                    tail(pos.x, pos.y, rr * ignite, rr * 0.45f * ignite, th, 1.3f, mc, 0.32f * ma, 0.9f * dp * inv, gap = mr + 1.5f * dp * inv)
                    val sx = cx - mx; val sy = cy - my
                    val sd = hypot(sx, sy)
                    planetSphere(m, mr, mc, 0f, if (sd < 1f) PlanetLight else Offset(sx / sd, sy / sd), alpha = ma, stroke = mr * 0.14f, meridians = false)
                } else {
                    drawCircle(mc, mr * 2.4f, m, alpha = ma * 0.12f)
                    drawCircle(mc, mr, m, alpha = ma)
                }
            }
            done += count
            shell++
        }
        p.ring = outer
    }

    private fun drawFocusBracket(s: DrawScope, x: Float, y: Float, b: Float, a: Float) = with(s) {
        val len = 6 * dp
        val st = 1.3f * dp
        drawLine(HN.accent, Offset(x - b, y - b), Offset(x - b + len, y - b), st, StrokeCap.Square, alpha = a)
        drawLine(HN.accent, Offset(x - b, y - b), Offset(x - b, y - b + len), st, StrokeCap.Square, alpha = a)
        drawLine(HN.accent, Offset(x + b, y + b), Offset(x + b - len, y + b), st, StrokeCap.Square, alpha = a)
        drawLine(HN.accent, Offset(x + b, y + b), Offset(x + b, y + b - len), st, StrokeCap.Square, alpha = a)
    }

    /**
     * Zoomed in: the moons' sessions in a calm, fixed column beside the planet (hex + name),
     * each with a thin line to its moon. The moons spin; the names don't move.
     */
    private fun drawMoonLabels(s: DrawScope, p: Planet, a0: Float, measurer: TextMeasurer, reduced: Boolean) = with(s) {
        val n = min(p.sessions.size, min(p.mx.size, 10))
        if (n == 0) return@with
        val rowH = 22 * dp
        var colW = 0f
        val rows = ArrayList<Pair<TextLayoutResult, TextLayoutResult>>(n)
        // The column goes on the side with more room, and names shrink to fit it, so a long
        // real session title never runs off the screen. Widths step by 16dp to keep the cache warm.
        val reach = (p.radius + p.ring) * zs + 30 * dp
        val roomR = w - 12 * dp - (p.sx + reach)
        val roomL = p.sx - reach - 12 * dp
        val right = roomR >= roomL
        val step = 16 * dp
        val nameMax = (((min(w * 0.36f, max(roomR, roomL) - 26 * dp)) / step).toInt() * step).coerceAtLeast(48 * dp)
        for (i in 0 until n) {
            val se = p.sessions[i]
            val pair = p.moonL.getOrPut(se.id + "|" + se.name + "|" + nameMax.toInt()) {
                measurer.measure(se.hex, moonHexStyle) to measurer.measure(se.name, moonStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = nameMax.toInt().coerceAtLeast(1)))
            }
            rows += pair
            colW = max(colW, pair.first.size.width + 5 * dp + pair.second.size.width)
        }
        val x0 = if (right) p.sx + reach else p.sx - reach - colW
        val y0 = p.sy - n * rowH / 2f
        for (i in 0 until n) {
            val se = p.sessions[i]
            val (hexL, nameL) = rows[i]
            val rowY = y0 + i * rowH
            val col = when (se.state) {
                SessionState.NeedsYou -> HN.amber
                SessionState.Working -> HN.accentBright
                SessionState.Idle -> HN.inkMuted
            }
            val mx = viewX + (p.mx[i] - camX) * zs
            val my = viewY + (p.my[i] - camY) * zs
            val ex = if (right) x0 - 4 * dp else x0 + colW + 4 * dp
            val ey = rowY + rowH / 2f
            drawLine(col, Offset(mx, my), Offset(ex, ey), strokeWidth = 0.6f * dp, alpha = 0.28f * a0)
            drawCircle(col, 1.4f * dp, Offset(ex, ey), alpha = 0.7f * a0)
            val base = rowY + (rowH - nameL.size.height) / 2f + nameL.firstBaseline
            drawText(hexL, color = col, alpha = 0.9f * a0, topLeft = Offset(x0, base - hexL.firstBaseline))
            drawText(nameL, color = if (se.state == SessionState.NeedsYou) HN.amber else HN.ink, alpha = 0.92f * a0, topLeft = Offset(x0 + hexL.size.width + 5 * dp, base - nameL.firstBaseline))
            // Tap target for this row.
            p.moonTags.getOrPut(se.id) { Tag() }.apply { x = x0; y = rowY; this.w = colW; h = rowH; alpha = 1f }
        }
        val keep = p.sessions.take(n).mapTo(HashSet()) { it.id }
        p.moonTags.keys.retainAll(keep)
        if (p.moonL.size > 40) p.moonL.clear()
    }

    // ─── Supernova ──────────────────────────────────────────────────────────

    private fun blast(loud: Boolean) {
        loudBlast = loud
        val rnd = Random(wt.toBits())
        for (p in planets.values) {
            p.free = true
            p.capBase = 0f
            if (p.x.isNaN()) {
                val a = p.aCur.takeIf { !it.isNaN() } ?: aMin
                p.fx = cx + a * cos(p.theta); p.fy = cy + a * p.tilt * sin(p.theta)
            } else { p.fx = p.x; p.fy = p.y }
            val dx = p.fx - cx; val dy = p.fy - cy
            val len = max(1f, hypot(dx, dy))
            if (loud) {
                val speed = (70f + rnd.nextFloat() * 90f) * dp
                p.vx = dx / len * speed + (rnd.nextFloat() - 0.5f) * 80f * dp
                p.vy = dy / len * speed * 0.7f + (rnd.nextFloat() - 0.5f) * 60f * dp
            } else {
                // A limit from before we were watching: already scattered, quietly adrift.
                p.fx = cx + dx * 1.35f + (rnd.nextFloat() - 0.5f) * 40f * dp
                p.fy = cy + dy * 1.25f + (rnd.nextFloat() - 0.5f) * 20f * dp
                p.vx = (rnd.nextFloat() - 0.5f) * 10f * dp; p.vy = (rnd.nextFloat() - 0.5f) * 8f * dp
            }
        }
        for (i in 0 until DEBRIS) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val sp = (90f + rnd.nextFloat().pow(0.7f) * 380f) * dp
            dvx[i] = cos(a) * sp; dvy[i] = sin(a) * sp
            dLife[i] = 1.6f + rnd.nextFloat() * 3.4f
            dKind[i] = rnd.nextInt(3)
        }
        val hot = listOf(HN.amber, Color(0xFFFF6B4A), Color(0xFFF3E39A))
        val cool = listOf(HN.accent, Color(0xFF5FE0C8), Color(0xFF9DFFB8))
        for (i in 0 until NEB) {
            nebAng[i] = rnd.nextFloat() * 2f * PI.toFloat()
            nebDist[i] = (0.12f + rnd.nextFloat() * 0.45f) * aMax
            nebR[i] = (0.28f + rnd.nextFloat() * 0.42f) * aMax
            nebSpin[i] = (rnd.nextFloat() - 0.5f) * 0.06f
            nebHot[i] = Brush.radialGradient(0f to hot[i % 3], 0.45f to hot[i % 3].copy(alpha = 0.45f), 1f to hot[i % 3].copy(alpha = 0f), center = Offset.Zero, radius = NEB_R0)
            nebCool[i] = Brush.radialGradient(0f to cool[i % 3], 0.45f to cool[i % 3].copy(alpha = 0.45f), 1f to cool[i % 3].copy(alpha = 0f), center = Offset.Zero, radius = NEB_R0)
        }
        if (loud) { startOverlay(6f); onBlast() }
    }

    private fun drawNebula(s: DrawScope, t: Float, light: Float, reduced: Boolean) = with(s) {
        if (nebHot[0] == null) return@with
        val sinceBlast = wt - t0
        val sinceBirth = wt - t1
        val p = if (limitedSeen) progress else 1f
        val expand = if (reduced || !loudBlast) 1f else Motion.Expo.transform((sinceBlast / 3f).coerceIn(0f, 1f))
        val fadeIn = if (reduced || !loudBlast) 1f else (sinceBlast / 1.5f).coerceIn(0f, 1f)
        val fadeOut = if (limitedSeen) 1f else (1f - sinceBirth / 2.5f).coerceIn(0f, 1f)
        val baseA = 0.16f * fadeIn * fadeOut * light
        if (baseA <= 0.001f) return@with
        for (i in 0 until NEB) {
            val ang = nebAng[i] + (if (reduced) 0f else t * nebSpin[i])
            val dist = nebDist[i] * (0.3f + 0.9f * expand) * (1f - 0.75f * p)
            val rr = nebR[i] * (0.5f + 0.5f * expand) * (1f - 0.45f * p)
            translate(cx + cos(ang) * dist, cy + sin(ang) * dist * 0.55f) {
                scale(rr / NEB_R0, rr / NEB_R0, pivot = Offset.Zero) {
                    drawCircle(nebHot[i]!!, radius = NEB_R0, center = Offset.Zero, alpha = baseA * (1f - p))
                    drawCircle(nebCool[i]!!, radius = NEB_R0, center = Offset.Zero, alpha = baseA * p)
                }
            }
        }
    }

    private fun wallNow(): Float = if (epoch0 < 0) 0f else (System.currentTimeMillis() - epoch0) / 1000f

    /**
     * Over the whole screen: the supernova (white flash, two shockwaves, debris), the soft
     * mint flash of a rebirth, and the Big Bang (a point of light flooding the screen).
     */
    fun drawOverlay(s: DrawScope, reduced: Boolean) = with(s) {
        if (reduced) return@with
        val now = wallNow()
        val c = Offset(sunSx, sunSy)
        val diag = hypot(size.width, size.height)
        val sb = now - t0
        if (loudBlast && sb in 0f..6f) {
            if (sb < 0.55f) { val k = 1f - sb / 0.55f; drawRect(Color.White, alpha = 0.62f * k * k) }
            if (sb < 2.2f) drawRect(HN.amber, alpha = 0.10f * (1f - sb / 2.2f))
            if (sb < 1.5f) {
                val k = sb / 1.5f
                val r = Motion.Expo.transform(k) * diag
                drawCircle(lerp(Color.White, HN.amber, k), radius = r, center = c, alpha = 0.85f * (1f - k), style = Stroke(2.5f * dp))
                drawCircle(HN.amber, radius = r * 0.97f, center = c, alpha = 0.14f * (1f - k), style = Stroke(16f * dp))
            }
            val s2 = sb - 0.2f
            if (s2 in 0f..2.4f) {
                val k = s2 / 2.4f
                drawCircle(Color(0xFFFF6B4A), radius = Motion.Expo.transform(k) * diag * 0.8f, center = c, alpha = 0.5f * (1f - k), style = Stroke(1.5f * dp))
            }
            for (i in 0 until DEBRIS) {
                val life = dLife[i]
                if (sb > life) continue
                val drag = 1.3f
                val f = (1f - exp(-drag * sb)) / drag
                val x = c.x + dvx[i] * f
                val y = c.y + dvy[i] * f
                val vk = exp(-drag * sb)
                val col = when (dKind[i]) { 0 -> Color.White; 1 -> HN.amber; else -> Color(0xFFFF6B4A) }
                val a = (1f - sb / life).coerceIn(0f, 1f)
                drawLine(col, Offset(x - dvx[i] * vk * 0.035f, y - dvy[i] * vk * 0.035f), Offset(x, y), strokeWidth = 1.4f * dp, cap = StrokeCap.Round, alpha = a * 0.9f)
            }
        }
        val sr = now - t1
        if (sr in 0f..1.2f) drawRect(HN.accentBright, alpha = 0.14f * (1f - sr / 1.2f))
        // Big Bang: a singular point, then light floods outward.
        val bb = now - tBang
        if (bb in 0f..6f) {
            if (bb < 0.6f) drawCircle(Color.White, radius = (1.5f + 10f * (bb / 0.6f)) * dp, center = c, alpha = 0.9f * (bb / 0.6f))
            if (bb in 0.5f..2.4f) {
                val k = (bb - 0.5f) / 1.9f
                drawRect(Color.White, alpha = 0.85f * (1f - k).pow(2.2f))
                drawCircle(HN.accentBright, radius = Motion.Expo.transform(k) * diag, center = c, alpha = 0.6f * (1f - k), style = Stroke(3f * dp))
            }
            if (bb in 0.8f..4.2f) {
                val k = (bb - 0.8f) / 3.4f
                drawCircle(Color(0xFF5FE0C8), radius = Motion.Expo.transform(k) * diag * 0.85f, center = c, alpha = 0.35f * (1f - k), style = Stroke(1.5f * dp))
            }
        }
    }

    // ─── The universe (7-day usage) ─────────────────────────────────────────

    private var gCur = Float.NaN

    /** How much light the galaxy has: thins out as the week fills, gone at the weekly limit. */
    fun galaxyLight(usage: Usage, reduced: Boolean): Float {
        val base = 1f - 0.65f * usage.sevenDay.coerceIn(0f, 0.99f).pow(1.5f)
        val target = if (weekSeen) base * (1f - smooth(((coolK - 0.15f) / 0.85f).coerceIn(0f, 1f))) else base
        // While cooling, the light follows the cooling exactly; otherwise it eases.
        gCur = if (gCur.isNaN() || reduced || weekSeen) target else gCur + (target - gCur) * (1f - exp(-dt * 3f))
        return gCur
    }

    /** During the Big Bang, how far the light has spread from the centre (px); infinite otherwise. */
    fun revealRadius(): Float {
        if (weekSeen) return 0f
        val bb = wt - tBang
        if (bb !in 0f..6f) return Float.POSITIVE_INFINITY
        return Motion.Expo.transform(((bb - 0.5f) / 3.5f).coerceIn(0f, 1f)) * hypot(w, h)
    }

    fun revealCenter(): Offset = Offset(sunSx, sunSy)

    /** Top and bottom of the system's band (px), for the ambient sky. */
    fun bandTopPx(): Float = bandTop
    fun bandBottomPx(): Float = cy + bMax + 40 * dp

    // ─── Taps ───────────────────────────────────────────────────────────────

    /** The nearest thing under [pos] (screen): a moon (zoomed in), a planet or its label, or the sun. */
    fun hit(pos: Offset, zoom: String?): CosmosHit? {
        if (!hitsEnabled) return null
        val focused = zoom?.let { planets[it] }
        if (focused != null && camK > 0.5f) {
            var bestId: String? = null
            var bestD = 22 * dp
            for (i in 0 until min(focused.sessions.size, focused.mx.size)) {
                val id = focused.sessions[i].id
                focused.moonTags[id]?.let { tg ->
                    if (pos.x in tg.x..(tg.x + tg.w) && pos.y in (tg.y - 6 * dp)..(tg.y + tg.h + 6 * dp)) return CosmosHit.Session(id)
                }
                val sx = viewX + (focused.mx[i] - camX) * zs
                val sy = viewY + (focused.my[i] - camY) * zs
                val d = hypot(sx - pos.x, sy - pos.y)
                if (d < bestD) { bestD = d; bestId = id }
            }
            if (bestId != null) return CosmosHit.Session(bestId)
        }
        for (p in planets.values) {
            val tg = p.tag
            if (!tg.x.isNaN() && tg.alpha > 0.3f && pos.x in (tg.x - 6 * dp)..(tg.x + tg.w + 6 * dp) && pos.y in (tg.y - 10 * dp)..(tg.y + tg.h + 10 * dp)) return CosmosHit.Project(p.project)
        }
        // Otherwise the nearest body, relative to its size.
        var best: CosmosHit? = null
        var bestD = Float.MAX_VALUE
        val sunR = max(keyframe(uCur.takeIf { !it.isNaN() } ?: 0f, sunSizes) * dp * zs * 1.6f, 16 * dp)
        val ds = hypot(pos.x - sunSx, pos.y - sunSy)
        if (ds < sunR) { best = CosmosHit.Sun; bestD = ds / sunR }
        for (p in planets.values) {
            if (p.sx.isNaN()) continue
            val rr = max((p.radius + p.ring) * zs, 18 * dp)
            val d = hypot(p.sx - pos.x, p.sy - pos.y)
            if (d < rr && d / rr < bestD) { bestD = d / rr; best = CosmosHit.Project(p.project) }
        }
        return best
    }

    /** For TalkBack: the projects in the sky, in orbit order. */
    fun projectNames(): List<String> = planets.keys.toList()
}
