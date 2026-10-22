package com.prudnikweb.hypernova.ui.fx

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.data.AgentLead
import com.prudnikweb.hypernova.data.CodexState
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.JetBrainsMono
import com.prudnikweb.hypernova.ui.theme.Manrope
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Words Codex's star draws, resolved from resources by the composable. */
class CodexStarText(
    /** Its name over its words: "CODEX". */
    val name: String,
    /** The share of its window used, after its name: "61%". */
    val percent: (Int) -> String,
    /** Its second line, as a planet's: "working", "needs you", "4m ago" (the last value is ms since it was active). */
    val status: (SkyPlanner.Doing, Int, Long) -> String,
    /** Signed out on the Mac. */
    val signedOut: String,
    /** A used-up window: "refills in 2h 4m" (its argument the time left, in words). */
    val refills: (String) -> String,
    val duration: (Long) -> String,
)

private const val TAU = 6.2831855f
private const val SIN_E = SkyLayout.SIN_E
/** The star is the sun's disk at this scale: its rim 7.8 dp, its donor's orbit 11.7 dp. */
private const val SCALE = 0.6f
/** Its moons' innermost shell goes round in this many seconds; the outer ones follow Kepler. */
private const val MOON_T0 = 8f
/** Watched, a limit's last hot gas drains over this long; at the reset the heating front sweeps in over [FLARE] s and settles over [SETTLE] s. */
private const val DRAIN = 2.5f
private const val FLARE = 2.2f
private const val SETTLE = 1.5f
/** The week's low state drains the whole disk over this long, watched. */
private const val LOW = 4f
/** A lifted limit counts at its reset, or once it has held this long (as the sun's: no flapping). */
private const val HOLD_MS = 20_000L

/**
 * Codex's star (docs/DESIGN.md, "Codex's star"): the second agent's, in the sky only while both
 * agents are in use (Claude Code leads and its usage is the sun; data/AgentLead.kt). Where Codex
 * leads, it is the sun, and there's no second star.
 *
 * It's the sun's own kind of system at 0.6 its size ([AccretionDisk]): a white dwarf, the disk
 * round it that is Codex's window (its 5-hour one, or the week where a plan has only that), the
 * donor feeding it on the window's clock. Its palette is its own and calmer ([DiskPalette.Codex]):
 * white-hot inside and silver to its rim, its spent gas the app's ghost grey, so it never reads as
 * a second sun. What the window does, it does as the sun's disk does: the hot part is the budget
 * left; spent, the last hot gas drains (watched) and the disk is quiet until the reset, when the
 * heating front sweeps in from the rim; the week spent is the low state, the disk gone. The Mac
 * sends no pace for Codex, so its gas flows (knots, the inner edge's glow) while one of its
 * sessions works, at the even pace, and nothing moves at rest.
 *
 * Its sessions are its moons, as the companion's are (shells of 4, 7, 10 at 16, 24, 32 dp, tilted
 * like the view, going round by Kepler only while one works), plain like a planet's, not ringed
 * like the companion's. Its words sit in its own slot in the band's bottom row ([SkyLayout]):
 * `CODEX 61%` over what its sessions do (`working`, `needs you`, `4m ago`), or `signed out`, or
 * `refills in 2h 4m` while its window is spent. Trouble (signed out, too old, unreachable) dims it
 * and lines its rim in amber; what's wrong is said on Home's Codex usage card, not in the sky.
 * It ignites over 1.2 s and fades over 2 s on the wall clock; Reduce Motion holds it still.
 */
internal class CodexStar {
    var text: CodexStarText? = null
    /** Codex as the Mac has it now. */
    var codex: CodexState? = null
    /** Both agents in use: the star is in the sky. */
    var wanted = false

    private val disk = AccretionDisk(DiskPalette.Codex)
    private var dp = 1f
    private var mine: List<Session> = emptyList()
    private var working = false
    private var needsYou = false
    private var lit = 0f
    private var lastNow = -1L
    private var wt = 0f
    private var phase = 0f
    private var hitsOn = false
    private var cx = 0f
    private var cy = 0f
    private var mx = FloatArray(0)
    private var my = FloatArray(0)
    private var mz = FloatArray(0)
    private val born = HashMap<String, Float>()
    private var lastBorn = -100f
    private val spot = FloatArray(6)

    // The disk's cycle (wall-clock seconds), as the sun's, simpler: a limit, the outburst, the low state.
    private var used = Float.NaN
    private var limited = false
    private var low = false
    private var loud = false
    private var tLimit = -1e6f
    private var tBirth = -1e6f
    private var lift = -1L
    private var limitReset = 0L
    private var limitAt = 0L
    private var spanAtLimit = 0f
    private var flow = 0f
    private var busyNow = false
    private var movingNow = false

    private val halo = Shadow(Color(0xE014110E), blurRadius = 10f)
    private val labelStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(600), fontSize = 9.sp, letterSpacing = 0.2.em, shadow = halo)
    private val statusStyle = TextStyle(fontFamily = Manrope, fontWeight = FontWeight(500), fontSize = 10.sp, letterSpacing = 0.02.em, fontFeatureSettings = "ss01", shadow = halo)
    private val pctStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 9.sp, shadow = halo)
    private var nameL: TextLayoutResult? = null
    private var nameW = -1
    /** The share and sign-in the percentage was worded for: formatted again only when they change. */
    private var pctFor = Int.MIN_VALUE
    private var pctL: TextLayoutResult? = null
    private var statusKey = ""
    private var statusL: TextLayoutResult? = null
    private var wordsW = -1
    private var wordsMine: List<Session>? = null
    private var wordsAt = 0L
    private var rimStroke = Stroke(1f)

    /** Its sessions, those in use (SkySplit): its moons. */
    fun use(sessions: List<Session>) {
        mine = sessions
        working = sessions.any { SkyPlanner.working(it) }
        needsYou = sessions.any { it.state == SessionState.NeedsYou }
    }

    /** Whether it's in the sky now (lit, or fading), or is about to be: the band keeps its place. */
    val inSky: Boolean get() = wanted || lit > 0.004f

    /** Igniting or fading out (on the wall clock): it needs frames until it's done. */
    val fading: Boolean get() = if (wanted) lit < 1f else lit > 0f

    /** Something in it moves: gas flows, its moons go round, a moon or the star ignites, a limit drains or an outburst plays. */
    val moving: Boolean get() = lit > 0.004f && (movingNow || busyNow)

    /** A moon of its needs you: the bracket draws in every 2.6 s. */
    val signalling: Boolean get() = lit > 0.004f && needsYou

    /** Draws it at its place in the band ([Solar.codexSpot]), its words to its right. */
    fun draw(s: DrawScope, now: Long, focus: Float, solar: Solar, measurer: TextMeasurer, reduced: Boolean) = with(s) {
        if (density != dp) {
            dp = density
            rimStroke = Stroke(1f * dp)
            nameW = -1
        }
        val dt = if (lastNow < 0) 0f else ((now - lastNow) / 1000f).coerceIn(0f, 0.25f)
        lastNow = now
        wt += dt
        val target = if (wanted) 1f else 0f
        lit = if (reduced) target else if (target > lit) min(1f, lit + dt / 1.2f) else max(0f, lit - dt / 2f)
        hitsOn = false
        if (lit <= 0.004f && !wanted) {
            // Gone: the next time it comes, its words are worded afresh.
            nameL = null; pctL = null; statusL = null; pctFor = Int.MIN_VALUE; statusKey = ""; wordsMine = null
            movingNow = false; busyNow = false
            return@with
        }
        step(now, dt, reduced)
        if (!solar.codexSpot(spot)) return@with
        cx = spot[0]
        cy = spot[1]
        val a = lit * focus.coerceIn(0f, 1f)
        if (a < 0.004f) return@with
        if (!reduced && working) phase += dt

        val c = codex
        val trouble = c != null && (c.problem != null || c.account?.signedIn == false)
        val bodyA = a * (if (trouble) 0.55f else 1f)

        // Moons: placed first, the far half drawn behind the star.
        val n = mine.size
        if (mx.size != n) { mx = FloatArray(n); my = FloatArray(n); mz = FloatArray(n) }
        var shell = 0
        var done = 0
        val r0 = 16f * dp
        while (done < n) {
            val count = min(4 + shell * 3, n - done)
            val rr = (16f + 8f * shell) * dp
            val omega = Kepler.omega(rr, r0, MOON_T0)
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

        // The star: the sun's kind of system, smaller, in its own colours.
        val unit = AccretionDisk.R_OUT * SCALE * dp
        disk.draw(
            this, Offset(cx, cy), unit, dp, 1f, bodyA,
            hotOut = hotOut, hotIn = hotIn, cool = cool, glow = glow, flow = flow,
            donor = donorAng, stream = streamA, spot = spotA, wd = wdLit, reduced = reduced,
        )
        // Trouble: its rim lined in amber, in the disk's plane.
        if (trouble) {
            val rw = unit + 1.5f * dp
            drawOval(HN.amber, Offset(cx - rw, cy - rw * SIN_E), Size(2f * rw, 2f * rw * SIN_E), alpha = 0.8f * a, style = rimStroke)
        }
        drawMoons(back = false, a = a, now = now)
        hitsOn = focus > 0.35f && lit > 0.5f

        drawWords(c, now, a, measurer)
    }

    // The disk this frame.
    private var hotOut = 1f
    private var hotIn = 0f
    private var cool = 0.75f
    private var glow = 0f
    private var donorAng = -PI_HALF
    private var streamA = 0f
    private var spotA = 0f
    private var wdLit = 1f

    /** Where the disk is in its cycle: Codex's numbers, the limit and its reset, and its sessions' work. */
    private fun step(now: Long, dt: Float, reduced: Boolean) {
        val u = codex?.usage
        val win = AgentLead.codexWindow(u)
        val limWin = AgentLead.codexLimitedWindow(u)
        // The week spent while the star shows its 5-hour window: the low state (the week outranks).
        val weekLow = limWin != null && u?.fiveHour != null && limWin === u.sevenDay
        // Watched: in the sky, lit, as the change comes.
        val watched = lit >= 0.999f && !reduced
        if (limWin != null) {
            lift = -1L
            limitReset = limWin.resetsAt
            if (!limited || low != weekLow) {
                val fresh = !limited
                limited = true
                low = weekLow
                limitAt = u?.observedAt ?: System.currentTimeMillis()
                loud = watched && fresh
                tLimit = if (loud) wt else wt - 60f
                spanAtLimit = DiskModel.hotSpan(AccretionDisk.R_IN, AccretionDisk.R_OUT, 1f - (used.takeIf { !it.isNaN() } ?: 0f))
                tBirth = -1e6f
            }
        } else if (limited) {
            if (lift < 0) lift = now
            if (now >= limitReset - 1500 || now - lift >= HOLD_MS) {
                limited = false
                low = false
                lift = -1L
                // The outburst, watched; found later the full disk is simply there.
                tBirth = if (watched && now - limitReset <= 10_000L) wt else -1e6f
                used = AgentLead.codexUsed(u) ?: 0f
            }
        }
        val usedT = if (limited && !low) 1f else AgentLead.codexUsed(u) ?: 0f
        used = if (used.isNaN() || reduced) usedT else used + (usedT - used) * (1f - exp(-dt * 4f))
        if (abs(used - usedT) < 0.001f) used = usedT
        val span = DiskModel.hotSpan(AccretionDisk.R_IN, AccretionDisk.R_OUT, 1f - used)
        val sb = wt - tLimit
        val sr = wt - tBirth
        val birthing = !limited && sr >= 0f && sr <= FLARE + SETTLE + 0.5f
        val lowK = if (low) (if (loud) smooth(sb / LOW) else 1f) else 0f
        when {
            birthing && sr < FLARE -> { hotOut = max(span, 0.02f); hotIn = (1f - smooth(sr / FLARE)) * hotOut }
            low -> { hotOut = span * (1f - lowK); hotIn = 0f }
            limited && loud && sb < DRAIN -> { hotOut = spanAtLimit * (1f - smooth(sb / DRAIN)); hotIn = 0f }
            limited -> { hotOut = 0f; hotIn = 0f }
            else -> { hotOut = span; hotIn = 0f }
        }
        // Through the wait the stream refills the quiet disk (in steps, so it isn't a new gradient each frame).
        // A window Codex gave no reset for (resetsAt 0) has no wait to show.
        val progress = if (limited && !low && limitReset > 0) ((now - limitAt).toFloat() / max(1L, limitReset - limitAt)).coerceIn(0f, 1f) else 0f
        cool = if (low) 0.75f * (1f - lowK) else if (limited) 0.75f + 0.45f * (Math.round(progress * 40f) / 40f) else 0.75f
        // No pace from the Mac for Codex: its gas flows at the even pace while one of its sessions works.
        val flowT = if (working && !limited && hotOut > 0.02f) 1f else 0f
        flow = if (reduced) flowT else flow + (flowT - flow) * (1f - exp(-dt * 1.5f))
        if (abs(flow - flowT) < 0.005f) flow = flowT
        if (!reduced && flow > 0f) disk.advance(dt, flow)
        val flush = if (birthing) 1f - smooth((sr - FLARE * 0.6f) / SETTLE) else 0f
        glow = min(1f, flow) * 0.85f + 0.6f * flush
        val rin = AccretionDisk.R_IN / AccretionDisk.R_OUT
        fun r(k: Float) = rin + (1f - rin) * k
        val ro = r(hotOut); val ri = r(hotIn)
        val light = ((ro * ro - ri * ri) / (1f - rin * rin)).coerceIn(0f, 1f)
        val quiet = 1f - light
        val streamK = if (low) 1f - lowK else 1f
        streamA = (0.12f + 0.55f * quiet) * streamK
        spotA = (0.2f + 0.7f * quiet) * streamK
        wdLit = if (low) 1f - 0.65f * lowK else 1f
        // The donor keeps the window's clock (the limited window's while it waits).
        val resetAt = if (limited) limitReset else win?.resetsAt ?: 0L
        donorAng = DiskModel.donorAngle(if (resetAt > 0L) max(0L, resetAt - now) else -1L, AgentLead.codexWindowMs(u))
        busyNow = birthing || (limited && loud && sb < (if (low) LOW else DRAIN) + 0.5f) || abs(used - usedT) > 0.001f
        movingNow = !reduced && (working || flow > 0.02f || wt - lastBorn < 1.3f)
    }

    /** Its words in its slot, to its right: `CODEX 61%` over what its sessions do, worded again only when they change. */
    private fun DrawScope.drawWords(c: CodexState?, now: Long, a: Float, measurer: TextMeasurer) {
        val txt = text ?: return
        val room = (spot[4] - spot[2]).toInt().coerceAtLeast(1)
        if (nameL == null || nameW != room) {
            nameW = room
            nameL = measurer.measure(txt.name, labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = room))
            pctFor = Int.MIN_VALUE; statusKey = ""
        }
        val u = c?.usage
        val usedNow = AgentLead.codexUsed(u)
        // The window it's lit by; while a window is spent (the week's too), that one: 100%.
        val pct = if (limited) 100 else usedNow?.let { (it * 100f).toInt().coerceIn(0, 100) }
        // Keyed on the number before it's formatted: frames allocate nothing.
        val shown = if (pct != null && c?.account?.signedIn != false) pct else -1
        val nl = nameL!!
        val pctRoom = room - nl.size.width - (5f * dp).toInt()
        if (shown != pctFor) {
            pctFor = shown
            val pctText = if (shown >= 0) txt.percent(shown) else ""
            pctL = if (pctText.isEmpty() || pctRoom < 12 * dp) null else measurer.measure(pctText, pctStyle, maxLines = 1, constraints = Constraints(maxWidth = pctRoom.coerceAtLeast(1)))
        }
        // Worded again when its sessions change, or each second while it counts down to a refill (20 s otherwise, for "4m ago").
        val every = if (limited) 1_000L else 20_000L
        if (mine !== wordsMine || room != wordsW || now - wordsAt > every) {
            wordsMine = mine
            wordsAt = now
            wordsW = room
            val pr = if (mine.isEmpty()) null else SkyPlanner.project("", mine)
            val status = when {
                pr != null && pr.doing != SkyPlanner.Doing.Resting -> txt.status(pr.doing, pr.count, 0L)
                c?.account?.signedIn == false -> txt.signedOut
                limited && limitReset > 0 -> txt.refills(txt.duration(max(0L, limitReset - now)))
                pr != null -> txt.status(pr.doing, pr.count, (now - pr.lastActive).coerceAtLeast(0L))
                else -> ""
            }
            if (status != statusKey) {
                statusKey = status
                statusL = if (status.isEmpty()) null else measurer.measure(status, statusStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = room))
            }
        }
        val left = spot[2]
        val lh = nl.size.height
        val sh = statusL?.size?.height ?: 0
        val top = cy - (lh + sh) / 2f
        val signedOut = c?.account?.signedIn == false
        val nameCol = if (needsYou) HN.amber else HN.inkMuted
        drawText(nl, color = nameCol, alpha = 0.9f * a, topLeft = Offset(left, top))
        pctL?.let { l ->
            val col = when {
                limited -> HN.danger
                (usedNow ?: 0f) >= 0.8f -> HN.amber
                else -> HN.inkDim
            }
            drawText(l, color = col, alpha = 0.9f * a, topLeft = Offset(left + nl.size.width + 5f * dp, top + (lh - l.size.height) / 2f))
        }
        statusL?.let { l ->
            val col = when {
                needsYou -> HN.amber
                working -> HN.accent
                signedOut -> HN.amber
                limited -> HN.inkMuted
                else -> HN.inkDim
            }
            drawText(l, color = col, alpha = 0.9f * a, topLeft = Offset(left, top + lh))
        }
    }

    /** The moons behind the star ([back]) or in front of it, lit on the side that faces it: mint working, dim idle, amber with the bracket. */
    private fun DrawScope.drawMoons(back: Boolean, a: Float, now: Long) {
        for (i in 0 until min(mine.size, mx.size)) {
            if ((mz[i] < 0f) != back) continue
            val se = mine[i]
            val needs = se.state == SessionState.NeedsYou
            val busy = SkyPlanner.working(se)
            val mc = when { needs -> HN.amber; busy -> HN.accentBright; else -> HN.inkMuted }
            val ma = when { needs -> 1f; busy -> 0.95f; else -> 0.55f } * a
            val mr = (1.3f + 0.35f * ln(1f + se.lengthMin / 10f)).coerceAtMost(2.4f) * dp
            val p = Offset(mx[i], my[i])
            drawCircle(mc, mr * 2.4f, p, alpha = ma * 0.12f)
            drawCircle(mc, mr, p, alpha = ma * 0.8f)
            val dx = cx - p.x
            val dy = cy - p.y
            val d = hypot(dx, dy).coerceAtLeast(1f)
            drawCircle(HN.ink, mr * 0.55f, Offset(p.x + dx / d * mr * 0.38f, p.y + dy / d * mr * 0.38f), alpha = ma * 0.35f)
            if (needs) {
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

    /** What's under [pos] (window coordinates): one of its moons (its session), or the star itself. Null when it isn't lit. */
    fun hit(pos: Offset): CosmosHit? {
        if (!hitsOn) return null
        var best = -1
        var bestD = 18f * dp
        for (i in 0 until min(mine.size, mx.size)) {
            val d = hypot(pos.x - mx[i], pos.y - my[i])
            if (d < bestD) { bestD = d; best = i }
        }
        if (best >= 0) return CosmosHit.Session(mine[best].id)
        return if (hypot(pos.x - cx, pos.y - cy) < 16f * dp) CosmosHit.CodexStar else null
    }

    private fun smooth(x: Float): Float { val k = x.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }

    private companion object {
        const val PI_HALF = 1.5707964f
    }
}
