package com.prudnikweb.hypernova.ui.fx

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.data.Provider
import com.prudnikweb.hypernova.data.ProviderWords
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.Manrope
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/** Words the companion draws, resolved from resources by the composable. */
class CompanionText(
    /** "2 providers": its name when sessions run on more than one. */
    val providers: (Int) -> String,
    /** Its second line, as a planet's: "working", "needs you", "4m ago" (the last value is ms since it was active). */
    val status: (SkyPlanner.Doing, Int, Long) -> String,
)

private const val TAU = 6.2831855f
/** The view looks down 20° on the companion's shells, as on the solar system's plane. */
private const val SIN_E = 0.342f

/**
 * The companion star (docs/DESIGN.md, "The companion"; docs/specs/spec-providers.md, §6.3): the
 * star Claude Code's sessions on other providers (OpenRouter, a gateway) orbit, on the right of a
 * row of its own at the bottom of the sky ([SkyLayout]; Codex's sessions have a star of their own,
 * [CodexStar], on that row's left). It's there only while a provider is in use: a session on it
 * works, needs you, or did something in the last day, as a planet is.
 *
 * It's a **white dwarf**, honestly: the small, dense, white-hot core a star leaves behind, too
 * small to see as a disc, so a point of white light with a faint halo and four faint diffraction
 * spikes. It doesn't twinkle (there's no air in space), and it doesn't age: a white dwarf has no
 * fuel to burn through, so the subscription's usage means nothing to it. (The sun is a white
 * dwarf too, but one with a disk and a donor feeding it: the disk is the subscription's window.
 * This one is bare.) The sun's limits, its low state, its outbursts and the eclipse leave it
 * be. While the sun's disk is quiet, the sessions that still run elsewhere visibly keep moving here.
 *
 * Its sessions are **ringed moons** in shells of 4, 7, 10… at 16, 24 and 32 dp, inclined like the
 * view, lit from the white dwarf (their bright side faces it). Each wears a thin ring at 1.9× its
 * radius in its own colour, which tells them from the sun's moons at a glance. Colours as usual:
 * mint working, dim idle, amber when it needs you (with the bracket).
 *
 * Its moons go round only while one of them works, as a planet's do. When every provider with
 * sessions here is in trouble it dims; out of credit or a refused key adds a thin amber ring. What
 * the trouble is, and the way to fix it, is a card in the home list, not words in the sky. It
 * ignites over 1.2 s (a point brightening, no blast) when the first such session appears and fades
 * over 2 s after the last one ends. Reduce Motion holds it still.
 */
internal class Companion {
    var text: CompanionText? = null
    var providers: List<Provider> = emptyList()

    private var dp = 1f
    private var mine: List<Session> = emptyList()
    private var lit = 0f
    private var lastNow = -1L
    private var phase = 0f
    private var hitsOn = false
    private var cx = 0f
    private var cy = 0f
    private var mx = FloatArray(0)
    private var my = FloatArray(0)
    private var mz = FloatArray(0)
    private val born = HashMap<String, Float>()
    private var wt = 0f

    // As a planet's words: the name over what it's doing.
    private val labelStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = 9.sp, letterSpacing = 0.2.em, shadow = Shadow(Color(0xE014110E), blurRadius = 10f))
    private val statusStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 10.sp, letterSpacing = 0.02.em, fontFeatureSettings = "ss01", shadow = Shadow(Color(0xE014110E), blurRadius = 10f))
    private var labelKey = ""
    private var labelL: TextLayoutResult? = null
    private var statusKey = ""
    private var statusL: TextLayoutResult? = null
    private var wordsW = -1
    private var wordsMine: List<Session>? = null
    private var wordsFor: List<Provider>? = null
    private var wordsAt = 0L
    /** What its words were last worded for: the providers its sessions are on, by id. */
    private var wordsIds: Set<String?> = emptySet()
    private val spot = FloatArray(6)
    private var working = false
    private var needsYou = false
    private var ringStroke = Stroke(1f)
    private var amberStroke = Stroke(1f)
    private var halo: Brush? = null
    private var haloDim: Brush? = null

    /**
     * Its sessions ([SkySplit]): Claude Code's on other providers, those in use (one works, needs
     * you, or did something in the last day).
     */
    fun use(sessions: List<Session>) {
        mine = sessions
        working = mine.any { SkyPlanner.working(it) }
        needsYou = mine.any { it.state == SessionState.NeedsYou }
    }

    /** Whether it's in the sky now (lit, or fading), or is about to be: the band keeps a row for it. */
    val inSky: Boolean get() = mine.isNotEmpty() || lit > 0.004f

    /** Igniting or fading out (on the wall clock), as of the last frame drawn: it needs frames until it's done. */
    val fading: Boolean get() = if (mine.isNotEmpty()) lit < 1f else lit > 0f

    /** Its moons go round (one of its sessions works), or one ignites. */
    val moving: Boolean get() = lit > 0.004f && (working || wt - lastBorn < 1.3f)

    /** A moon of its needs you: the bracket draws in every 2.6 s. */
    val signalling: Boolean get() = lit > 0.004f && needsYou
    private var lastBorn = -100f

    /** Draws it at its spot in the band ([Solar.companionSpot]), with its words to its left. */
    fun draw(s: DrawScope, t: Float, now: Long, focus: Float, solar: Solar, measurer: TextMeasurer, reduced: Boolean) = with(s) {
        if (density != dp || halo == null) {
            dp = density
            ringStroke = Stroke(1f * dp)
            amberStroke = Stroke(1f * dp)
            halo = Brush.radialGradient(listOf(HN.ink.copy(alpha = 0.18f), HN.ink.copy(alpha = 0.05f), Color.Transparent), Offset.Zero, 10f * dp)
            haloDim = Brush.radialGradient(listOf(HN.ink.copy(alpha = 0.08f), HN.ink.copy(alpha = 0.02f), Color.Transparent), Offset.Zero, 10f * dp)
        }
        val dt = if (lastNow < 0) 0f else ((now - lastNow) / 1000f).coerceIn(0f, 0.25f)
        lastNow = now
        wt += dt
        // Ignites over 1.2 s, fades over 2 s, on the wall clock (so it finishes under Reduce Motion too).
        val target = if (mine.isNotEmpty()) 1f else 0f
        lit = if (reduced) target else if (target > lit) min(1f, lit + dt / 1.2f) else maxOf(0f, lit - dt / 2f)
        hitsOn = false
        if (mine.isEmpty() && lit <= 0.004f) {
            // Gone: its last words go with it, so they never come back stale with the next session.
            labelL = null; statusL = null; labelKey = ""; statusKey = ""; wordsMine = null; wordsFor = null; wordsIds = emptySet()
            return@with
        }
        if (!solar.companionSpot(spot)) return@with
        cx = spot[0]
        cy = spot[1]
        val a = lit * focus.coerceIn(0f, 1f)
        if (a < 0.004f) return@with
        // Its moons go round only while one of its sessions works.
        if (!reduced && working) phase += dt

        val c = Offset(cx, cy)
        // The providers its sessions are on (one removed since still counts, by its id).
        val ids = mine.mapTo(LinkedHashSet()) { it.providerId }
        val used = providers.filter { it.id in ids }
        val dim = used.isNotEmpty() && used.all { it.status != null }
        val trouble = used.any { ProviderWords.needsYou(it.status) }
        // A point brightening: its light grows faster than its halo.
        val core = if (reduced) 1f else lit * lit

        // Moons: placed first, the far half drawn behind the star.
        val n = mine.size
        if (mx.size != n) { mx = FloatArray(n); my = FloatArray(n); mz = FloatArray(n) }
        var shell = 0
        var done = 0
        while (done < n) {
            val count = min(4 + shell * 3, n - done)
            val rr = (16f + 8f * min(shell, 2)) * dp + 8f * dp * maxOf(0, shell - 2)
            val omega = TAU / (14f + shell * 6f)
            for (j in 0 until count) {
                val se = mine[done + j]
                val b0 = born.getOrPut(se.id) { (if (reduced) -100f else wt).also { b -> if (b > lastBorn) lastBorn = b } }
                val grow = if (reduced) 1f else smooth((wt - b0) / 1.2f)
                val th = j * TAU / count + shell * 0.7f + phase * omega
                mx[done + j] = cx + cos(th) * rr * grow
                my[done + j] = cy + sin(th) * rr * SIN_E * grow
                mz[done + j] = sin(th)
            }
            done += count
            shell++
        }
        if (born.size > n + 8) born.keys.retainAll(mine.mapTo(HashSet()) { it.id })
        drawMoons(back = true, a = a, now = now)

        // The white dwarf: a halo, four faint spikes, the core.
        translate(cx, cy) { drawCircle(if (dim) haloDim!! else halo!!, 10f * dp, Offset.Zero, alpha = a) }
        val spikeA = (if (dim) 0.05f else 0.10f) * a * core
        val sl = 8f * dp
        drawLine(HN.ink, Offset(cx - sl, cy), Offset(cx + sl, cy), 0.8f * dp, StrokeCap.Round, alpha = spikeA)
        drawLine(HN.ink, Offset(cx, cy - sl), Offset(cx, cy + sl), 0.8f * dp, StrokeCap.Round, alpha = spikeA)
        // A point, not a disc: a white dwarf is the size of the Earth. The 2.4 dp core is its light.
        drawCircle(if (dim) HN.inkDim else HN.ink, 2.4f * dp, c, alpha = 0.18f * a * core)
        drawCircle(if (dim) HN.inkDim else HN.ink, 1.2f * dp * (0.4f + 0.6f * core), c, alpha = a)
        if (trouble) drawCircle(HN.amber, 5.5f * dp, c, alpha = 0.8f * a, style = amberStroke)

        drawMoons(back = false, a = a, now = now)
        hitsOn = focus > 0.35f && lit > 0.5f

        // Its words, as a planet's: the provider's name over what its sessions are doing, right
        // aligned in the slot to its left. Worded and measured again only when they change. While it
        // fades after the last session, the last words fade with it.
        val txt = text ?: return@with
        val room = (spot[4] - spot[2]).toInt().coerceAtLeast(1)
        // Worded again when its sessions or providers change, the slot does, or every 20 s (for "4m ago").
        // Named for every provider its sessions are on, not only the ones still listed, so sessions
        // on two never read as one's.
        if (mine.isNotEmpty() && (mine !== wordsMine || used != wordsFor || ids != wordsIds || room != wordsW || now - wordsAt > 20_000L)) {
            wordsMine = mine
            wordsFor = used
            wordsIds = ids
            wordsAt = now
            val one = ids.singleOrNull()
            val label = if (ids.size > 1) txt.providers(ids.size).uppercase()
            else (used.firstOrNull { it.id == one }?.name ?: one ?: txt.providers(1)).uppercase()
            if (label != labelKey || room != wordsW) {
                labelKey = label
                labelL = measurer.measure(label, labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = room))
            }
            val pr = SkyPlanner.project("", mine)
            val status = txt.status(pr.doing, pr.count, (now - pr.lastActive).coerceAtLeast(0L))
            if (status != statusKey || room != wordsW) {
                statusKey = status
                statusL = measurer.measure(status, statusStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = room))
            }
            wordsW = room
        }
        val right = spot[4]
        val lh = labelL?.size?.height ?: 0
        val sh = statusL?.size?.height ?: 0
        val top = cy - (lh + sh) / 2f
        labelL?.let { l -> drawText(l, color = if (needsYou) HN.amber else HN.inkMuted, alpha = 0.9f * a, topLeft = Offset(right - l.size.width, top)) }
        statusL?.let { l ->
            val col = when { needsYou -> HN.amber; working -> HN.accent; else -> HN.inkDim }
            drawText(l, color = col, alpha = 0.9f * a, topLeft = Offset(right - l.size.width, top + lh))
        }
    }

    /** The moons behind the star ([back]) or in front of it, each with its ring and its lit side toward the star. */
    private fun DrawScope.drawMoons(back: Boolean, a: Float, now: Long) {
        for (i in 0 until min(mine.size, mx.size)) {
            if ((mz[i] < 0f) != back) continue
            val se = mine[i]
            val mc = when (se.state) {
                SessionState.NeedsYou -> HN.amber
                SessionState.Working -> HN.accentBright
                SessionState.Idle -> HN.inkMuted
            }
            val ma = when (se.state) {
                SessionState.NeedsYou -> 1f
                SessionState.Working -> 0.95f
                SessionState.Idle -> 0.55f
            } * a
            // A moon grows with how long its session has run, as the sun's moons do.
            val mr = (1.3f + 0.35f * ln(1f + se.lengthMin / 10f)).coerceAtMost(2.4f) * dp
            val p = Offset(mx[i], my[i])
            drawCircle(mc, mr * 2.4f, p, alpha = ma * 0.10f)
            // Its ring, tilted with the shell.
            val rw = mr * 1.9f
            drawOval(mc, Offset(p.x - rw, p.y - rw * SIN_E), Size(rw * 2f, rw * 2f * SIN_E), alpha = 0.45f * ma, style = ringStroke)
            drawCircle(mc, mr, p, alpha = ma * 0.75f)
            // Lit from the white dwarf: the side facing it is brighter.
            val dx = cx - p.x
            val dy = cy - p.y
            val d = hypot(dx, dy).coerceAtLeast(1f)
            drawCircle(HN.ink, mr * 0.55f, Offset(p.x + dx / d * mr * 0.38f, p.y + dy / d * mr * 0.38f), alpha = ma * 0.35f)
            if (se.state == SessionState.NeedsYou) {
                // The needs-you bracket, as round a planet.
                val k = 0.5f + 0.5f * exp(-((now % 2600L) / 350f))
                val b = mr + (6f - 2f * k) * dp
                val len = 3f * dp
                val st = 1.1f * dp
                drawLine(HN.amber, Offset(p.x - b, p.y - b), Offset(p.x - b + len, p.y - b), st, StrokeCap.Square, alpha = a)
                drawLine(HN.amber, Offset(p.x - b, p.y - b), Offset(p.x - b, p.y - b + len), st, StrokeCap.Square, alpha = a)
                drawLine(HN.amber, Offset(p.x + b, p.y + b), Offset(p.x + b - len, p.y + b), st, StrokeCap.Square, alpha = a)
                drawLine(HN.amber, Offset(p.x + b, p.y + b), Offset(p.x + b, p.y + b - len), st, StrokeCap.Square, alpha = a)
            }
        }
    }

    /** A moon under [pos] (window coordinates): its session. Null when none, or the star isn't lit. */
    fun hit(pos: Offset): CosmosHit? {
        if (!hitsOn) return null
        var best = -1
        var bestD = 18f * dp
        for (i in 0 until min(mine.size, mx.size)) {
            val d = hypot(pos.x - mx[i], pos.y - my[i])
            if (d < bestD) { bestD = d; best = i }
        }
        return if (best >= 0) CosmosHit.Session(mine[best].id) else null
    }

    private fun smooth(x: Float): Float { val k = x.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }
}
