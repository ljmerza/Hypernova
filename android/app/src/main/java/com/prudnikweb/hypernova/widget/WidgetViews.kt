package com.prudnikweb.hypernova.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.SuperscriptSpan
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Account
import com.prudnikweb.hypernova.data.AccountWords
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.UsageProjection
import com.prudnikweb.hypernova.widget.WidgetArt.Look
import com.prudnikweb.hypernova.widget.WidgetArt.Mood
import com.prudnikweb.hypernova.widget.WidgetModel.Conn
import java.util.Calendar
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Which of the two widgets. */
internal enum class WidgetKind { Sun, Sessions }

/**
 * Builds the widgets' RemoteViews from a [WidgetModel]. Each widget gets a layout per size it
 * may be shown at (Android 12+ picks the one that fits as it's resized, with no call back to
 * the app); older launchers get the one for the size they report.
 */
internal object WidgetViews {

    fun build(ctx: Context, mgr: AppWidgetManager, id: Int, kind: WidgetKind, m: WidgetModel): RemoteViews {
        val art = Art(ctx)
        if (Build.VERSION.SDK_INT >= 31) {
            val sizes = sizesFor(mgr.getAppWidgetOptions(id), kind)
            return RemoteViews(sizes.associateWith { s -> variant(ctx, kind, m, s.width, s.height, art, sizes) })
        }
        val o = mgr.getAppWidgetOptions(id)
        // Portrait: the width a launcher reports as the minimum and the height as the maximum.
        val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }?.toFloat() ?: default(kind).width
        val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }?.toFloat() ?: default(kind).height
        return variant(ctx, kind, m, w, h, art, listOf(SizeF(w, h)))
    }

    /** One widget at [w]×[h] dp. [all]: every size being built, so they can share one star. */
    fun variant(ctx: Context, kind: WidgetKind, m: WidgetModel, w: Float, h: Float, art: Art, all: List<SizeF>): RemoteViews = when (kind) {
        WidgetKind.Sun -> sun(ctx, m, w, h, art, all)
        WidgetKind.Sessions -> sessions(ctx, m, w, h, art)
    }

    private fun default(kind: WidgetKind) = if (kind == WidgetKind.Sun) SizeF(150f, 150f) else SizeF(300f, 260f)

    /**
     * The sizes this widget is shown at (portrait and landscape, as the launcher reports them),
     * plus a few breakpoints in case it doesn't, or while it's being resized. Android shows the
     * layout nearest the widget's size among those that fit in it, or the smallest when none
     * does, so each kind has one smaller than any widget will be: no layout is ever squeezed
     * narrower than it was built for (which could cut a command short beside its Allow).
     *
     * The reported sizes are taken [SLACK] dp smaller: launchers report a little more than the
     * view they give the widget (the Pixel launcher, 2–3 dp more height), and a layout that's a
     * dp too large doesn't fit, so a much smaller breakpoint would be shown instead.
     */
    @RequiresApi(31)
    @Suppress("DEPRECATION")
    private fun sizesFor(o: Bundle, kind: WidgetKind): List<SizeF> {
        val exact: List<SizeF> = runCatching {
            if (Build.VERSION.SDK_INT >= 33) o.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            else o.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        }.getOrNull().orEmpty().filter { it.width > SLACK && it.height > SLACK }.map { SizeF(it.width - SLACK, it.height - SLACK) }
        val breaks = when (kind) {
            WidgetKind.Sun -> listOf(SizeF(40f, 40f), SizeF(100f, 95f), SizeF(170f, 40f), SizeF(150f, 150f), SizeF(250f, 110f))
            WidgetKind.Sessions -> listOf(
                SizeF(100f, 60f), SizeF(180f, 100f), SizeF(250f, 100f), SizeF(180f, 170f), SizeF(250f, 170f),
                SizeF(250f, 220f), SizeF(250f, 270f), SizeF(250f, 320f), SizeF(250f, 380f),
            )
        }
        val out = ArrayList<SizeF>()
        for (s in exact + breaks) if (out.none { abs(it.width - s.width) < 1f && abs(it.height - s.height) < 1f }) out += s
        return out.take(16)
    }

    private const val SLACK = 6f

    private fun abs(f: Float) = kotlin.math.abs(f)

    // ─── The small widget: the sun ──────────────────────────────────────────

    private enum class SunShape { Tiny, Square, Wide }

    /** The least room (dp) the sun's words get beside the star: any less and "Weekly limit" reads "Weekly…". */
    private const val WORDS = 64f

    /** The least height (dp) of a square sun with a line for the other agent: under it the star would shrink to a dot. */
    private const val OTHER_SQUARE = 130f

    private fun sunShape(w: Float, h: Float) = when {
        // Wide only with room for words beside the star, which is as tall as the widget: a 2 × 1
        // cell on a tall grid would leave them 50dp, so it stacks them as a square. Under 95dp
        // there's no room to stack them, so a smaller star makes room instead ([wideStar]).
        w >= 170f && w >= h * 1.6f && (h < 95f || w - min(h - 8f, 128f) - 28f >= WORDS) -> SunShape.Wide
        w < 100f || h < 95f -> SunShape.Tiny
        else -> SunShape.Square
    }

    /** The star of a wide sun (dp): as tall as the widget, but never so wide the words get less than [WORDS]. */
    private fun wideStar(w: Float, h: Float): Float = min(min(h - 8f, 128f), w - 28f - WORDS)

    /** How big the star shows in a sun widget of this size (dp). */
    private fun sunStarDp(w: Float, h: Float): Float = when (sunShape(w, h)) {
        SunShape.Tiny -> min(w, h) - 10f
        SunShape.Wide -> wideStar(w, h)
        SunShape.Square -> min(w - 20f, h - 62f)
    }

    private fun sun(ctx: Context, m: WidgetModel, w: Float, h: Float, art: Art, all: List<SizeF>): RemoteViews {
        val shape = sunShape(w, h)
        val layout = when (shape) {
            SunShape.Tiny -> R.layout.widget_sun_tiny
            SunShape.Square -> R.layout.widget_sun
            SunShape.Wide -> R.layout.widget_sun_wide
        }
        val v = RemoteViews(ctx.packageName, layout)
        val res = ctx.resources
        // One star for every size: the largest any of them shows, so none is drawn blurry.
        val starDp = all.maxOf { sunStarDp(it.width, it.height) }.coerceIn(48f, 132f)
        v.setImageViewBitmap(R.id.w_star, art.star(starDp, m))
        v.setOnClickPendingIntent(android.R.id.background, Taps.openApp(ctx))
        if (shape == SunShape.Wide) {
            // The star is as wide as the widget is tall (up to 128dp, as widget_sun_wide has it),
            // unless the words need the room. Set every time: the launcher may reuse these views.
            val star = wideStar(w, h)
            val most = if (star < min(h - 8f, 128f)) star else 128f
            v.setInt(R.id.w_star, "setMaxWidth", (most * res.displayMetrics.density).roundToInt())
        }

        val u = m.usage
        val pct = (u.fiveHour * 100).roundToInt()
        val content = w - (if (shape == SunShape.Wide) wideStar(w, h) + 28f else 20f)
        // While Claude can't reply, the line beside the sun leads with why. One cell has no room
        // for words.
        val lead = if (shape == SunShape.Tiny) null else lead(ctx, m)
        // The other agent while both are in use ("CODEX 61%"): under the readout in a square (one
        // tall enough that the star keeps its size), at the end of its row when wide, where it gives
        // way before the bar does. One cell has no room.
        val other = m.other?.takeIf {
            m.conn != Conn.Unpaired && !m.unknown && (shape == SunShape.Wide || (shape == SunShape.Square && h >= OTHER_SQUARE))
        }
        val otherRoom = other?.let { otherWidth(ctx, it) + 8f } ?: 0f
        var showOther = other != null

        // The readout under the sun: while the numbers show, the 5-hour window as a projection
        // meter (the percentage, in amber past 80% as in the sky, its bar and one tag; [sunMeter]),
        // and otherwise one line of words ("refills 16:40").
        val readout = SpannableStringBuilder()
        val tail = when {
            m.conn == Conn.Unpaired || m.unknown -> null
            // A usage limit's lead says when it lifts, so the readout (which would say "refills
            // 16:40") steps aside. An account's lead ("Out of usage credits") doesn't say, so the
            // readout stays: it's the only place the reset shows.
            lead != null && !lead.amber -> null
            u.weekLimited -> res.getString(R.string.widget_refills, day(ctx, u.sevenResetAt))
            u.limited -> res.getString(R.string.widget_refills, clock(ctx, u.fiveResetAt))
            // Signed out, the numbers aren't coming until the owner acts, which the line says.
            !u.known && m.unmeasured -> null
            !u.known -> res.getString(R.string.widget_no_numbers)
            // The reset: the meter's tag (sunMeter).
            else -> ""
        }
        val head = when {
            m.conn == Conn.Unpaired -> ""
            u.weekLimited || u.limited || !u.known -> ""
            else -> res.getString(R.string.percent, pct)
        }
        val headColor = when {
            m.conn == Conn.Offline -> res.getColor(R.color.widget_ink_dim, null)
            pct >= 80 -> res.getColor(R.color.widget_amber, null)
            else -> res.getColor(R.color.widget_ink, null)
        }
        if (head.isNotEmpty()) readout.append(head, ForegroundColorSpan(headColor), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (shape == SunShape.Tiny) {
            if (head.isEmpty() && m.conn != Conn.Unpaired && !m.unknown) readout.append(if (u.limited || u.weekLimited) "—" else "··")
            // One cell has no room for words: an amber dot says someone (or the signed-out account) needs you.
            if ((m.needs > 0 || m.account != null || m.codexTrouble != null) && m.conn != Conn.Unpaired && m.conn != Conn.Offline) {
                readout.append(" ●", ForegroundColorSpan(res.getColor(R.color.widget_amber, null)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else if (head.isNotEmpty() && tail != null) {
            val wideOther = shape == SunShape.Wide && showOther
            if (wideOther && content - otherRoom < measure(ctx, head, MONO, 13f) + 7f + 24f) showOther = false
            sunMeter(ctx, v, m, pct, head, if (wideOther && showOther) content - otherRoom else content)
        } else {
            // A limit, or no numbers yet: its words, alone.
            if (tail != null) {
                readout.append(tail, ForegroundColorSpan(res.getColor(R.color.widget_ink_dim, null)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                readout.setSpan(RelativeSizeSpan(0.85f), 0, readout.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (shape == SunShape.Wide && showOther && measure(ctx, tail, MONO, 13f * 0.85f) + otherRoom > content) showOther = false
            }
            // Set every time: the launcher may apply this picture to the last one's views.
            v.setViewVisibility(R.id.w_bar, View.GONE)
            v.setViewVisibility(R.id.w_tag, View.GONE)
        }
        v.setTextViewText(R.id.w_readout, readout)
        v.setViewVisibility(R.id.w_readout, if (readout.isEmpty()) View.GONE else View.VISIBLE)
        // Wide, the other agent's readout is in the readout's row: the row stays for it alone.
        if (shape != SunShape.Tiny) v.setViewVisibility(R.id.w_meter, if (readout.isEmpty() && !(shape == SunShape.Wide && showOther)) View.GONE else View.VISIBLE)
        if (shape != SunShape.Tiny) otherReadout(ctx, v, other?.takeIf { showOther }, dimmed = m.conn == Conn.Offline)
        val said = starDescription(ctx, m)
        v.setContentDescription(R.id.w_star, other?.takeIf { showOther }?.let { said + " " + otherSaid(ctx, it) } ?: said)
        if (shape == SunShape.Tiny) return v

        // Who's working, and who needs you; at a limit, the limit first, in the readout's room as
        // well: a row more (a square tall enough gets a third, so the limit can take two and the
        // counts still show). A strip one row tall keeps to one line: two and the note under
        // them would be cut off at its bottom edge.
        val lines = when (shape) {
            // The other agent's line takes a row's room: a third line only on a taller square.
            SunShape.Square -> if (lead != null && h >= (if (showOther) 165f else 140f)) 3 else 2
            else -> if (lead != null && h >= 64f) 2 else 1
        }
        val status: CharSequence = when {
            m.conn == Conn.Unpaired || m.conn == Conn.Offline -> res.getString(R.string.widget_not_connected)
            m.connecting -> res.getString(R.string.widget_connecting)
            m.unknown -> res.getString(R.string.widget_app_only)
            lead != null -> leadLine(ctx, m, lead, content, lines)
            else -> counts(ctx, m, content, sans = 12f, caps = false)
        }
        v.setTextViewText(R.id.w_status, status)
        // Set every time: the launcher may keep the views of the last picture and apply this one to them.
        v.setInt(R.id.w_status, "setMaxLines", lines)

        // With the background connection off, a square with room says why it isn't moving, under
        // when it last heard: once the app opens, it's current again.
        val note = note(ctx, m)
        val why = m.conn == Conn.AppOnly && note != null && shape == SunShape.Square && h >= 150f
        v.setTextViewText(R.id.w_note, if (why) note + "\n" + res.getString(R.string.widget_app_only) else note ?: "")
        v.setInt(R.id.w_note, "setMaxLines", if (why) 2 else 1)
        v.setViewVisibility(R.id.w_note, if (note == null) View.GONE else View.VISIBLE)
        return v
    }

    /**
     * The sun's readout as the 5-hour window's projection meter ([WidgetMeter]) in [room] dp: the
     * percentage ([head]), the bar (used solid, on a live picture the pace a faint fill on to where
     * it leads by the reset, and a red notch at the end when that's the limit first), and one tag:
     * the reset after its glyph, dim, or the limit's time in red after a notch. Short of room the tag
     * goes, then the bar; the percentage never does.
     */
    private fun sunMeter(ctx: Context, v: RemoteViews, m: WidgetModel, pct: Int, head: String, room: Float) {
        val now = System.currentTimeMillis()
        val p = m.usage.five(now, live = m.conn == Conn.Live)
        val limitAt = WidgetMeter.limitAt(p, now)
        val at = if (limitAt > 0L) limitAt else m.usage.fiveResetAt
        // A Codex plan with only the week: the star's window is the week, so its reset is a day.
        val words = if (m.weekOnly) day(ctx, at) else clock(ctx, at)
        val fit = WidgetMeter.fit(room, measure(ctx, head, MONO, 13f), WidgetMeter.GLYPH + measure(ctx, words, MONO, 10.5f))
        val bar = fit != WidgetMeter.Fit.Percent
        v.setViewVisibility(R.id.w_bar, if (bar) View.VISIBLE else View.GONE)
        if (bar) {
            val amber = pct >= 80
            v.setViewVisibility(R.id.w_bar_mint, if (amber) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.w_bar_amber, if (amber) View.VISIBLE else View.GONE)
            val (used, lead) = WidgetMeter.levels(p)
            for (b in intArrayOf(R.id.w_bar_mint, R.id.w_bar_amber)) {
                v.setProgressBar(b, 1000, used, false)
                v.setInt(b, "setSecondaryProgress", lead)
            }
            v.setViewVisibility(R.id.w_bar_notch, if (p.limitFirst) View.VISIBLE else View.GONE)
        }
        meterTag(ctx, v, R.id.w_tag, R.id.w_tag_glyph, R.id.w_tag_text, fit == WidgetMeter.Fit.All, words, glyph = true, limit = p.limitFirst)
    }

    /**
     * A meter's one tag: [words] after the reset glyph, dim, or, when the [limit] comes first, the
     * time it comes in red after a notch. Without a [glyph] the words stand alone ("refills 16:40").
     */
    private fun meterTag(ctx: Context, v: RemoteViews, tag: Int, glyphId: Int, text: Int, shown: Boolean, words: String, glyph: Boolean, limit: Boolean) {
        v.setViewVisibility(tag, if (shown) View.VISIBLE else View.GONE)
        if (!shown) return
        v.setViewVisibility(glyphId, if (glyph) View.VISIBLE else View.GONE)
        v.setImageViewResource(glyphId, if (limit) R.drawable.widget_notch else R.drawable.widget_glyph_reset)
        v.setTextViewText(text, words)
        v.setTextColor(text, ctx.getColor(if (limit) R.color.widget_danger else R.color.widget_ink_dim))
    }

    /**
     * One sentence for TalkBack about a meter called [name] ("5-hour usage"): "5-hour usage 62%. At
     * this pace you reach the limit at 4:05 PM, before the reset at 4:40 PM." (or "… it lasts until
     * the reset, at 4:40 PM.", "5-hour usage 72%, resets at 4:40 PM.").
     */
    private fun meterSaid(ctx: Context, name: String, p: UsageProjection, resetAt: Long, now: Long): String {
        val pct = (p.used * 100).roundToInt().coerceIn(0, 100)
        if (resetAt <= 0L) return ctx.getString(R.string.widget_a11y_meter_plain, name, pct)
        val reset = AccountWords.atClock(ctx, resetAt, now)
        val limitAt = WidgetMeter.limitAt(p, now)
        return when {
            limitAt > 0L -> ctx.getString(R.string.widget_a11y_meter_limit, name, pct, AccountWords.atClock(ctx, limitAt, now), reset)
            p.projected != null -> ctx.getString(R.string.widget_a11y_meter_lasts, name, pct, reset)
            else -> ctx.getString(R.string.widget_a11y_meter, name, pct, reset)
        }
    }

    /**
     * What leads the widgets' line while Claude can't work, and when that lifts: the weekly limit
     * first (it outlasts the 5-hour one), then the 5-hour one. [amber]: the owner has to do
     * something (not just wait).
     */
    private class Lead(val what: String, val back: String?, val amber: Boolean)

    /**
     * The [Lead] for [m], or null when nothing stops Claude: the account first (signed out, it
     * waits for the owner, not a clock), then a usage limit, unless Claude carries on past it on
     * usage credits (as the permanent notification's headline has it; the star and its readout
     * still show the limit). None when the widget can't vouch for the numbers (no Mac, offline,
     * nothing heard yet): its star still shows a limit then.
     */
    private fun lead(ctx: Context, m: WidgetModel): Lead? {
        if (m.conn == Conn.Unpaired || m.conn == Conn.Offline || m.unknown) return null
        // Codex leads where Claude Code isn't installed: its trouble first (signed out, or the Mac's
        // words for what's wrong), then its limits. Claude's account says nothing true there.
        if (m.leadAgent == AgentKind.CODEX) {
            m.codexTrouble?.let { return Lead(codexTroubleWords(ctx, it), null, amber = true) }
            return limit(ctx, m)
        }
        // With Codex in use too, Claude's account trouble is Claude Code's, not the app's.
        val account = when (m.account) {
            Account.LOGGED_OUT -> if (m.both) R.string.widget_claude_signed_out else R.string.widget_signed_out
            Account.NO_SUBSCRIPTION -> R.string.widget_no_plan
            Account.ON_HOLD -> if (m.both) R.string.widget_claude_on_hold else R.string.widget_on_hold
            Account.NO_CREDIT -> if (m.both) R.string.widget_claude_no_credit else R.string.widget_no_credit
            else -> null
        }
        if (account != null) return Lead(ctx.getString(account), null, amber = true)
        if (m.usingCredits) return null
        return limit(ctx, m)
    }

    /** What stops Codex, in words: "Codex signed out", or the Mac's own words for what's wrong. */
    private fun codexTroubleWords(ctx: Context, t: String): String =
        if (t == WidgetModel.CODEX_SIGNED_OUT) ctx.getString(R.string.widget_codex_signed_out) else t

    /** The 5-hour limit's name: Claude Code's "Session limit", or "5-hour limit" for Codex. */
    private fun fiveLimitName(ctx: Context, m: WidgetModel): String =
        ctx.getString(if (m.leadAgent == AgentKind.CODEX) R.string.widget_codex_limit_five else R.string.limit_session)

    /** The usage limit the leading agent is at, if any: the weekly one first, as it outlasts the 5-hour one. */
    private fun limit(ctx: Context, m: WidgetModel): Lead? {
        val res = ctx.resources
        val u = m.usage
        val (what, at) = when {
            u.weekLimited -> res.getString(R.string.limit_weekly) to u.sevenResetAt
            u.limited -> fiveLimitName(ctx, m) to u.fiveResetAt
            else -> return null
        }
        return Lead(what, at.takeIf { it > 0L }?.let { back(ctx, it) }, amber = false)
    }

    /** TalkBack's name for the star's window: "5-hour usage" ("Claude 5-hour usage" while both agents are in use; the week for a week-only Codex). */
    private fun fiveName(ctx: Context, m: WidgetModel): String = ctx.getString(
        when {
            m.weekOnly -> R.string.widget_a11y_week
            m.both -> R.string.widget_a11y_claude_five
            else -> R.string.widget_a11y_five
        },
    )

    private fun weekName(ctx: Context, m: WidgetModel): String = ctx.getString(if (m.both) R.string.widget_a11y_claude_week else R.string.widget_a11y_week)

    // ─── The other agent (both in use) ──────────────────────────────────────

    /** "Claude" or "Codex": the readout's name (the layouts draw it in tracked caps). */
    private fun otherName(ctx: Context, o: WidgetModel.Other): String =
        ctx.getString(if (o.agent == AgentKind.CODEX) R.string.cx_agent_codex else R.string.claude)

    /**
     * The other agent's value and its colour: its percentage (amber past 80%), red 100% at its limit,
     * an amber dash when it's signed out or in trouble, a dim dash without numbers.
     */
    private fun otherValue(ctx: Context, o: WidgetModel.Other, dimmed: Boolean): Pair<String, Int> {
        val res = ctx.resources
        val pct = ((o.used ?: 0f) * 100).roundToInt().coerceIn(0, 100)
        val (text, color) = when {
            o.trouble != null -> res.getString(R.string.widget_other_none) to R.color.widget_amber
            o.limited -> res.getString(R.string.percent, 100) to R.color.widget_danger
            o.used == null -> res.getString(R.string.widget_other_none) to R.color.widget_ink_dim
            pct >= 80 -> res.getString(R.string.percent, pct) to R.color.widget_amber
            else -> res.getString(R.string.percent, pct) to R.color.widget_ink_muted
        }
        return text to ctx.getColor(if (dimmed) R.color.widget_ink_dim else color)
    }

    /** About how wide the other agent's readout draws (dp): its name in tracked caps, a gap, its value in mono. */
    private fun otherWidth(ctx: Context, o: WidgetModel.Other): Float =
        trackedWidth(otherName(ctx, o), 9.5f) + 5f + monoWidth(otherValue(ctx, o, dimmed = false).first, 11f)

    /** Draws [o] into the layout's w_other (its name and value), or hides it ([o] null). */
    private fun otherReadout(ctx: Context, v: RemoteViews, o: WidgetModel.Other?, dimmed: Boolean) {
        v.setViewVisibility(R.id.w_other, if (o == null) View.GONE else View.VISIBLE)
        if (o == null) return
        val (value, color) = otherValue(ctx, o, dimmed)
        v.setTextViewText(R.id.w_other_label, otherName(ctx, o))
        v.setTextColor(R.id.w_other_label, ctx.getColor(if (o.trouble != null && !dimmed) R.color.widget_amber else R.color.widget_ink_dim))
        v.setTextViewText(R.id.w_other_value, value)
        v.setTextColor(R.id.w_other_value, color)
    }

    /**
     * The other agent's sentence for TalkBack: "Codex 61% used, resets at 4:40 PM.", "Codex: limit
     * reached, refills at 4:40 PM.", "Codex: signed out on your Mac.", or the Mac's words for its trouble.
     */
    private fun otherSaid(ctx: Context, o: WidgetModel.Other): String {
        val name = otherName(ctx, o)
        val now = System.currentTimeMillis()
        val reset = o.resetAt.takeIf { it > now }?.let { AccountWords.atClock(ctx, it, now) }
        val pct = ((o.used ?: 0f) * 100).roundToInt().coerceIn(0, 100)
        return when {
            o.trouble == WidgetModel.CODEX_SIGNED_OUT -> ctx.getString(R.string.widget_a11y_other_signed_out, name)
            o.trouble != null -> ctx.getString(R.string.widget_a11y_other_trouble, name, o.trouble)
            o.limited -> reset?.let { ctx.getString(R.string.widget_a11y_other_limited, name, it) } ?: ctx.getString(R.string.widget_a11y_other_limited_plain, name)
            o.used == null -> ctx.getString(R.string.widget_a11y_other_none, name)
            else -> reset?.let { ctx.getString(R.string.widget_a11y_other, name, pct, it) } ?: ctx.getString(R.string.widget_a11y_other_plain, name, pct)
        }
    }

    /**
     * "until 2:00", or "until Fri 10:00" when it's 20 hours or more away: the permanent
     * notification's words for it (AccountWords.headline), so the two never disagree.
     */
    private fun back(ctx: Context, t: Long): String = ctx.getString(R.string.widget_back, AccountWords.clock(ctx, t))

    /** "Session limit until 2:00", "Weekly limit until Fri 10:00": a short phrase, no "·" (TalkBack: a sentence). */
    private fun Lead.line(ctx: Context, spoken: Boolean = false): String =
        back?.let { ctx.getString(if (spoken) R.string.widget_a11y_lead else R.string.widget_lead, what, it) } ?: what

    /**
     * The small widget's line at a limit, in up to [lines] lines of [room] dp: the limit and when
     * it lifts ("Session limit until 2:00", or the two over each other when one line can't hold
     * them), then who's working and who needs you, each after its state's point ([marks]). Short
     * of lines, who needs you outranks the time: a strip one row tall says "Session limit  ● 1
     * needs you", or just "● 1 needs you".
     */
    private fun leadLine(ctx: Context, m: WidgetModel, l: Lead, room: Float, lines: Int): CharSequence {
        fun fits(s: String) = measure(ctx, s, SANS_MEDIUM, 12f) <= room - 4f
        if (lines < 2) return leadOnOneLine(ctx, m, l, ::fits)
        val res = ctx.resources
        val one = l.line(ctx)
        val busy = m.working > 0 || m.needs > 0
        val head = when {
            l.back == null || fits(one) -> one
            // Two lines for the limit, and none left for the counts: who needs you comes first.
            lines < 3 && m.needs > 0 -> l.what
            // When it lifts, on a line of its own, unless even that cuts it short ("back Fri 1:…").
            fits(l.back) -> l.what + "\n" + l.back
            else -> l.what
        }
        val out = SpannableStringBuilder()
        out.append(head, ForegroundColorSpan(res.getColor(if (l.amber) R.color.widget_amber else R.color.widget_ink, null)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (busy && head.count { it == '\n' } + 1 < lines) out.append('\n').append(counts(ctx, m, room, sans = 12f, caps = false))
        return out
    }

    /**
     * A lead and the counts on one line, as much of them as [fits]: who's working gives way
     * first, then when the limit lifts, then the limit's name; who needs you never does (alone,
     * in amber, when nothing else fits). The lead in amber when the owner has to act; the counts
     * after it as [marks], a gap apart, never "·"-joined.
     */
    private fun leadOnOneLine(ctx: Context, m: WidgetModel, l: Lead, fits: (String) -> Boolean): CharSequence {
        val res = ctx.resources
        val working = m.working.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.notif_working, it, it) }
        val needs = m.needs.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.notif_needs_you, it, it) }
        val full = l.line(ctx)
        // Most first: (the lead as shown, whether who's working follows it).
        val options: List<Pair<String?, Boolean>> = if (needs != null) {
            listOfNotNull(working?.let { full to true }, full to false, l.what to false, null to false)
        } else {
            listOfNotNull(working?.let { full to true }, full to false, l.what to false)
        }
        fun plain(head: String?, withWorking: Boolean) =
            listOfNotNull(head, WidgetCounts.plain(listOfNotNull(working?.takeIf { withWorking }, needs)).ifEmpty { null }).joinToString(WidgetCounts.GAP)
        val (head, withWorking) = options.firstOrNull { (h, w) -> fits(plain(h, w)) } ?: options.last()
        val out = SpannableStringBuilder()
        if (head != null) out.append(head, ForegroundColorSpan(res.getColor(if (l.amber) R.color.widget_amber else R.color.widget_ink, null)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        marks(ctx, out, working?.takeIf { withWorking }, needs)
        return out
    }

    /**
     * Who's working and who needs you, each after its state's point of light as the app's count
     * marks draw it ("● 2 working  ● 1 needs you": a mint point, an amber one with its words in
     * amber), appended to [out] a gap after what's there.
     */
    private fun marks(ctx: Context, out: SpannableStringBuilder, working: String?, needs: String?) {
        val res = ctx.resources
        fun mark(words: String, color: Int, colourWords: Boolean) {
            if (out.isNotEmpty()) out.append(WidgetCounts.GAP)
            val at = out.length
            out.append(WidgetCounts.POINT)
            out.setSpan(ForegroundColorSpan(color), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(WidgetCounts.POINT_SIZE), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Raised to the words' middle, where the app's point sits, not on their baseline like a bullet.
            out.setSpan(SuperscriptSpan(), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(WidgetCounts.AFTER_POINT)
            val w = out.length
            out.append(words)
            if (colourWords) out.setSpan(ForegroundColorSpan(color), w, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (working != null) mark(working, res.getColor(R.color.widget_accent, null), colourWords = false)
        if (needs != null) mark(needs, res.getColor(R.color.widget_amber, null), colourWords = true)
    }

    private fun starDescription(ctx: Context, m: WidgetModel): String {
        val res = ctx.resources
        val u = m.usage
        val limit = limit(ctx, m)
        return when {
            m.conn == Conn.Unpaired -> res.getString(R.string.widget_not_connected)
            limit != null -> limit.line(ctx, spoken = true)
            !u.known -> res.getString(R.string.sun_unknown_said)
            else -> System.currentTimeMillis().let { now -> meterSaid(ctx, fiveName(ctx, m), u.five(now, m.conn == Conn.Live), u.fiveResetAt, now) }
        }
    }

    /** The quiet line about freshness: "Last updated 14:32", or how to get connected. */
    private fun note(ctx: Context, m: WidgetModel): String? {
        val res = ctx.resources
        return when (m.conn) {
            Conn.Live -> null
            // Never heard from the Mac yet: the main line says it's connecting, or when it updates.
            Conn.Stale, Conn.AppOnly -> if (m.updatedAt > 0) res.getString(R.string.widget_last_updated, stamp(ctx, m.updatedAt)) else null
            Conn.Offline -> if (m.updatedAt > 0) res.getString(R.string.widget_last_updated, stamp(ctx, m.updatedAt)) else res.getString(R.string.widget_retry)
            Conn.Unpaired -> res.getString(R.string.widget_pair)
        }
    }

    /**
     * Signed out (or with no plan, or on hold) Claude Code stops reporting usage: the numbers
     * aren't coming until the owner acts, which the widget's lead says, so no "no numbers yet".
     */
    private val WidgetModel.unmeasured: Boolean
        get() = account == Account.LOGGED_OUT || account == Account.NO_SUBSCRIPTION || account == Account.ON_HOLD ||
            (leadAgent == AgentKind.CODEX && codexTrouble == WidgetModel.CODEX_SIGNED_OUT)

    /** A process that has just started and hasn't heard from the Mac yet, with nothing kept from before. */
    private val WidgetModel.connecting: Boolean get() = conn == Conn.Stale && updatedAt == 0L

    /**
     * Nothing heard from the Mac and nothing kept from before: no numbers or sessions to show.
     * Either it's [connecting], or the background connection is off and the app hasn't been opened
     * since ("Updates while Hypernova is open").
     */
    private val WidgetModel.unknown: Boolean get() = (conn == Conn.Stale || conn == Conn.AppOnly) && updatedAt == 0L

    /**
     * The large widget's header while Claude can't reply: the lead first, then the counts, in
     * tracked caps in [room] dp, giving way as [leadOnOneLine] says.
     */
    private fun leadHeader(ctx: Context, m: WidgetModel, l: Lead, room: Float): CharSequence {
        // Measured as the header draws it (the lead is long, and the estimate would drop the
        // counts where they fit), a few dp to spare.
        val caps = paint(LABEL_FONT, spPx(ctx, 10f)).apply { letterSpacing = 0.18f }
        val density = ctx.resources.displayMetrics.density
        return leadOnOneLine(ctx, m, l) { caps.measureText(it.uppercase()) / density <= room - 4f }
    }

    /**
     * "● 2 working  ● 1 needs you" ([marks]), shortened to what matters most when the line has
     * [room] dp: who needs you, then who's working.
     */
    private fun counts(ctx: Context, m: WidgetModel, room: Float, sans: Float, caps: Boolean): CharSequence {
        val res = ctx.resources
        val working = m.working.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.notif_working, it, it) }
        val needs = m.needs.takeIf { it > 0 }?.let { res.getQuantityString(R.plurals.notif_needs_you, it, it) }
        if (working == null && needs == null) return res.getString(R.string.widget_quiet)
        val both = WidgetCounts.plain(listOfNotNull(working, needs))
        val width = if (caps) trackedWidth(both, sans) else sansWidth(both, sans)
        val keep = width <= room || working == null || needs == null
        return SpannableStringBuilder().also { marks(ctx, it, working?.takeIf { keep }, needs) }
    }

    // ─── The large widget: sessions ─────────────────────────────────────────

    // Heights in dp, as the layouts draw them (widget_sessions, widget_row*, widget_ask*).
    private const val PAD_V = 24f
    private const val HEADER = 20f
    private const val ROW = 40f
    private const val ROW_TALL = 50f
    private const val ROWS_GAP = 4f
    /** The approval block (widget_ask) without its text: band, paddings, buttons, margin. */
    private const val ASK_FRAME = 100f
    /** The command well's first line, and each line after it (at font scale 1). */
    private const val ASK_FIRST_LINE = 14f
    private const val ASK_LINE = 18f
    private const val ASK_INLINE = 76f
    /** The most lines the well grows to, to show a command whole (the session screen shows 12). */
    private const val ASK_MAX_LINES = 8
    /** The footer's small sun, and a smaller one beside the slim meters (Android 12+ can size it per layout). */
    private const val FOOTER_STAR = 34f
    private const val SLIM_STAR = 28f
    /** The least room the sun needs to stand alone in the middle of the widget. */
    private const val HERO = 72f
    /** The size the large widget's sun is drawn at (dp); it's scaled down to the room it gets. */
    private const val HERO_STAR = 128f

    /**
     * How the approval is drawn at one size: on one line beside its buttons ([inline]), or as a
     * block with [lines] lines of text. [answer]: the whole command shows, so Deny and Allow are
     * offered; otherwise only Open.
     */
    private data class AskFit(val inline: Boolean, val lines: Int, val answer: Boolean)

    private fun askHeight(lines: Int, fs: Float) = ASK_FRAME + (ASK_FIRST_LINE + (lines - 1) * ASK_LINE) * fs

    /**
     * The approval block's height (dp) as the launcher draws it: its frame and the lines its text
     * takes there, with the fonts and spacing widget_ask gives them (3dp between lines, none after
     * the last). What's left under it for the sessions and the meters is measured from this;
     * [askHeight], a little taller, only decides how many lines the well may have.
     */
    private fun askDrawn(ctx: Context, a: WidgetModel.Ask, fit: AskFit, header: Boolean, content: Float): Float {
        // Without the header the block loses its top margin, but only on Android 12+ (askView).
        val top = if (header || Build.VERSION.SDK_INT < 31) 8f else 0f
        if (fit.inline) return top + ASK_INLINE - 8f
        val tool = a.kind != "question" && a.kind != "plan"
        val (paint, text, width) = if (tool) {
            Triple(paint(MONO, wellPx(ctx)), promptSign(a) + if (fit.answer) a.detail else WidgetModel.oneLine(a.detail), content - WELL_INSET)
        } else {
            // A question or a plan: prose (a_text), inside the body's and its own margins.
            Triple(paint(SANS_MEDIUM, spPx(ctx, 13.5f)), a.detail, content - 24f)
        }
        val most = if (fit.answer) fit.lines + 1 else fit.lines
        val lines = (lineCount(ctx, paint, text, width) ?: most).coerceIn(1, most)
        // The frame less the well's padding (14dp), which prose doesn't have (it has 2dp above).
        return top + ASK_FRAME - 8f - 14f + (if (tool) 14f else 2f) + lines * lineDp(ctx, paint) + (lines - 1) * 3f
    }

    /** The footer's height (dp): its 6dp margin and the taller of its sun and the meters (font scale included). */
    private fun footerHeight(ctx: Context, slim: Boolean): Float {
        val label = lineDp(ctx, paint(LABEL_FONT, spPx(ctx, 9.5f)))
        // The label row, the bar (its 3dp fill in a 9dp row, for the notch), and under it the tag:
        // its 10dp glyph beside a line of mono.
        val meters = label + 2f + 9f + if (slim) 0f else 3f + max(10f, lineDp(ctx, paint(MONO, spPx(ctx, 10f))))
        // Before Android 12 the slim footer keeps the larger sun, and lets its faint lower rim
        // (4dp of glow) go under the edge rather than drop the meters.
        val star = when {
            !slim -> FOOTER_STAR
            Build.VERSION.SDK_INT >= 31 -> SLIM_STAR
            else -> FOOTER_STAR - 4f
        }
        return 6f + max(star, meters)
    }

    private fun sessions(ctx: Context, m: WidgetModel, w: Float, h: Float, art: Art): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_sessions)
        val res = ctx.resources
        val content = w - 28f
        // With the background connection off the Mac may well be there: an approval is offered
        // until the Mac would have given up on it, as when the picture is merely stale.
        val reachable = m.conn == Conn.Live || m.conn == Conn.Stale || m.conn == Conn.AppOnly
        v.setOnClickPendingIntent(android.R.id.background, Taps.openApp(ctx))
        val footerFull = footerHeight(ctx, slim = false)
        val footerSlim = footerHeight(ctx, slim = true)

        // What fits, most important first: the approval (whole, with its command), a session,
        // the meters, more sessions.
        val ask = m.ask?.takeIf { reachable }
        val fs = ctx.resources.configuration.fontScale
        var avail = h - PAD_V - HEADER
        var header = true
        // Deny and Allow only beside a command that shows in full: the lines it needs in the well
        // at this width, if it can be shown whole at all. Every other approval gets Open.
        val sign = ask?.let { promptSign(it) }.orEmpty()
        val need = ask?.takeIf { it.whole }?.let { wellLines(ctx, sign + it.detail, content - WELL_INSET) }?.takeIf { it <= ASK_MAX_LINES }
        val askFit: AskFit? = when {
            ask == null -> null
            // The whole command first, even if nothing else fits beside it: it's what you'd act on.
            need != null && avail >= askHeight(need, fs) -> AskFit(inline = false, lines = need, answer = true)
            avail >= askHeight(2, fs) + ROWS_GAP + ROW + footerFull -> AskFit(inline = false, lines = 2, answer = false)
            avail >= askHeight(1, fs) -> AskFit(inline = false, lines = 1, answer = false)
            else -> {
                if (avail < ASK_INLINE) { header = false; avail += HEADER }
                // One line beside the buttons: narrower, and no room for a second line.
                val inline = ask.takeIf { it.whole && '\n' !in it.detail }
                    ?.let { wellLines(ctx, sign + it.detail, inlineWell(ctx, content, answer = true)) }
                AskFit(inline = true, lines = 1, answer = inline == 1)
            }
        }
        // From here on, what's left is measured from the block as it will be drawn.
        if (ask != null && askFit != null) avail -= askDrawn(ctx, ask, askFit, header, content)
        val narrow = content < 232f

        // Nothing in orbit (no sessions, no Mac, or not heard from it yet): the sun alone, if
        // there's room for it.
        val noData = m.conn == Conn.Unpaired || m.unknown
        val sessions = if (noData) emptyList() else m.rows.filter { it.id != ask?.sessionId }
        val empty = noData || (m.rows.isEmpty() && ask == null)
        val hero = empty && avail - (if (noData) 0f else footerFull) >= HERO
        // Otherwise rows: a notice when the Mac can't be reached, then the sessions (the one
        // asking is in the block above), as many as leave room for at least the slim meters.
        val appOnly = res.getString(R.string.widget_app_only)
        val lone: Pair<String, String?> = when {
            m.conn == Conn.Unpaired -> res.getString(R.string.widget_not_connected) to res.getString(R.string.widget_pair)
            m.connecting -> res.getString(R.string.widget_connecting) to null
            m.unknown -> appOnly to null
            m.conn == Conn.Offline -> res.getString(R.string.widget_not_connected) to res.getString(R.string.widget_retry)
            // None as of when it last heard: there may be some by now.
            m.conn == Conn.AppOnly -> res.getString(R.string.widget_no_sessions) to appOnly
            else -> res.getString(R.string.widget_no_sessions) to res.getString(R.string.widget_start_one)
        }
        val notice = lone.takeIf { !hero && (empty || m.conn == Conn.Offline) }
        val wanted = if (hero) 0 else min(4, (if (notice != null) 1 else 0) + sessions.size)
        var fit = 0
        if (wanted > 0 && avail >= ROWS_GAP + ROW) {
            fit = ((avail - ROWS_GAP - footerSlim) / ROW).toInt().coerceIn(1, wanted)
            avail -= ROWS_GAP + fit * ROW
        }
        val footer = !noData && avail >= footerSlim
        val slim = narrow || avail < footerFull
        if (footer) avail -= if (slim) footerSlim else footerFull
        // A tall widget with room to spare puts the sun above the sessions (the app's sky over its
        // list); with a little room, the rows get more air rather than a gap.
        val sky = !hero && fit > 0 && ask == null && avail >= HERO
        val tall = !sky && fit > 0 && avail >= fit * (ROW_TALL - ROW)

        // Header: the seam.
        v.setViewVisibility(R.id.w_header, if (header) View.VISIBLE else View.GONE)
        val dimmed = m.conn == Conn.Offline || m.conn == Conn.Unpaired
        val mood = when {
            dimmed -> Mood.Idle
            // A signed-out account needs the owner as an approval does (the Mac's menu bar agrees);
            // so does a leading Codex that's signed out or in trouble.
            m.needs > 0 || m.account != null || m.codexTrouble != null -> Mood.NeedsYou
            m.working > 0 -> Mood.Working
            else -> Mood.Idle
        }
        v.setImageViewBitmap(R.id.w_meter, art.planet(12f, mood, bracket = false))
        val stale = note(ctx, m)?.takeIf { m.conn == Conn.Stale || m.conn == Conn.AppOnly || m.conn == Conn.Offline }
        val staleW = stale?.let { monoWidth(it, 10.5f) + 10f } ?: 0f
        val labelRoom = content - 22f - staleW
        val lead = lead(ctx, m)
        val label: CharSequence = when {
            noData -> res.getString(R.string.app_name)
            m.conn == Conn.Offline -> res.getString(R.string.widget_offline)
            // At a limit it leads, so the counts never read as if all were well.
            lead != null -> leadHeader(ctx, m, lead, labelRoom)
            else -> counts(ctx, m, labelRoom, sans = 10f, caps = true)
        }
        v.setTextViewText(R.id.w_label, label)
        v.setTextColor(R.id.w_label, res.getColor(if (dimmed || (m.working == 0 && m.needs == 0)) R.color.widget_ink_dim else R.color.widget_ink_muted, null))
        // The Mac's name where the counts leave room for all of it (they matter more), past the hairline.
        val trailRoom = labelRoom - trackedWidth(label.toString(), 10f) - 30f
        val trail = m.macName?.takeIf { m.conn == Conn.Live && trackedWidth(it, 10f, 0.09f) <= min(trailRoom, 200f) }
        v.setTextViewText(R.id.w_trail, trail ?: "")
        v.setViewVisibility(R.id.w_trail, if (trail == null) View.GONE else View.VISIBLE)
        v.setTextViewText(R.id.w_stale, stale ?: "")
        v.setViewVisibility(R.id.w_stale, if (stale == null) View.GONE else View.VISIBLE)

        // The approval block.
        v.removeAllViews(R.id.w_ask)
        if (ask != null && askFit != null) v.addView(R.id.w_ask, askView(ctx, ask, askFit, if (header) 8f else 0f, content, fs))

        // The rows.
        v.removeAllViews(R.id.w_rows)
        var shown = 0
        if (notice != null && fit > 0) {
            v.addView(R.id.w_rows, noticeRow(ctx, art, notice.first, notice.second, tall))
            shown++
        }
        for (r in sessions) {
            if (shown >= fit) break
            v.addView(R.id.w_rows, row(ctx, art, r, dimmed, tall))
            shown++
        }
        v.setViewVisibility(R.id.w_rows, if (shown > 0) View.VISIBLE else View.GONE)

        // Or the sun, large, in the room that's left: alone with a line about why, or over the list.
        val sun = hero || sky
        v.setViewVisibility(R.id.w_hero, if (sun) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.w_space, if (sun) View.GONE else View.VISIBLE)
        if (sun) {
            // One size for every layout of the widget, so they share one bitmap: the view scales
            // it to the room that's left.
            v.setImageViewBitmap(R.id.w_hero_star, art.star(HERO_STAR, m))
            // Without the footer (alone, with nothing in orbit) the other agent is said with the star.
            v.setContentDescription(R.id.w_hero_star, starDescription(ctx, m) + (m.other?.takeIf { !footer || content < OTHER_FOOTER }?.let { " " + otherSaid(ctx, it) } ?: ""))
            // Over the list the sun needs no words, but with the background connection off it says
            // under it, quietly, why the list isn't moving.
            val (title, sub) = if (sky) null to appOnly.takeIf { m.conn == Conn.AppOnly } else lone
            v.setTextViewText(R.id.w_hero_title, title ?: "")
            v.setTextViewText(R.id.w_hero_sub, sub ?: "")
            v.setViewVisibility(R.id.w_hero_title, if (title == null) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.w_hero_sub, if (sub == null) View.GONE else View.VISIBLE)
            // Both ways, every time: the launcher may apply this picture to the last one's views.
            v.setViewPadding(R.id.w_hero, 0, 0, 0, if (sky && sub == null) 0 else (6 * res.displayMetrics.density).roundToInt())
        }

        // The footer: the sun and the two meters (no small sun when the large one is above).
        v.setViewVisibility(R.id.w_footer, if (footer) View.VISIBLE else View.GONE)
        if (footer) {
            footer(ctx, v, m, art, slim, content)
            v.setViewVisibility(R.id.w_star, if (sun) View.GONE else View.VISIBLE)
        }
        return v
    }

    private fun row(ctx: Context, art: Art, r: WidgetModel.Row, dimmed: Boolean, tall: Boolean): RemoteViews {
        val res = ctx.resources
        val v = RemoteViews(ctx.packageName, if (tall) R.layout.widget_row_tall else R.layout.widget_row)
        val mood = when {
            dimmed -> Mood.Idle
            r.state == SessionState.NeedsYou -> Mood.NeedsYou
            r.state == SessionState.Working -> Mood.Working
            else -> Mood.Idle
        }
        v.setImageViewBitmap(R.id.r_planet, art.planet(if (tall) 28f else 26f, mood, bracket = true))
        v.setTextViewText(R.id.r_title, r.title)
        v.setTextColor(R.id.r_title, res.getColor(if (dimmed) R.color.widget_ink_dim else R.color.widget_ink, null))
        v.setTextViewText(R.id.r_project, r.project)
        // A Codex session while both agents are in use: CODEX after the project.
        v.setViewVisibility(R.id.r_agent, if (r.codex) View.VISIBLE else View.GONE)
        v.setTextColor(R.id.r_agent, res.getColor(if (dimmed) R.color.widget_ink_ghost else R.color.widget_ink_muted, null))
        // Its main agent is idle while agents it started work: that's what it's doing (its last
        // words would read as if it were done).
        val activity = if (r.agents > 0) res.getQuantityString(R.plurals.widget_agents_running, r.agents, r.agents) else r.activity
        v.setTextViewText(R.id.r_activity, activity)
        v.setTextColor(
            R.id.r_activity,
            res.getColor(
                when {
                    dimmed -> R.color.widget_ink_ghost
                    r.state == SessionState.NeedsYou -> R.color.widget_amber
                    r.state == SessionState.Working -> R.color.widget_ink_muted
                    else -> R.color.widget_ink_dim
                },
                null,
            ),
        )
        v.setContentDescription(
            R.id.r_root,
            when {
                r.project.isEmpty() && r.codex -> res.getString(R.string.widget_a11y_row_plain_codex, r.title, activity)
                r.project.isEmpty() -> res.getString(R.string.widget_a11y_row_plain, r.title, activity)
                r.codex -> res.getString(R.string.widget_a11y_row_codex, r.title, r.project, activity)
                else -> res.getString(R.string.widget_a11y_row, r.title, r.project, activity)
            },
        )
        v.setOnClickPendingIntent(R.id.r_root, Taps.openSession(ctx, r.id))
        return v
    }

    /** A row that isn't a session: "Not connected to your Mac", "No sessions running". Opens the app. */
    private fun noticeRow(ctx: Context, art: Art, title: String, sub: String?, tall: Boolean): RemoteViews {
        val res = ctx.resources
        val v = RemoteViews(ctx.packageName, if (tall) R.layout.widget_row_tall else R.layout.widget_row)
        v.setImageViewBitmap(R.id.r_planet, art.planet(if (tall) 28f else 26f, Mood.Idle, bracket = true))
        v.setTextViewText(R.id.r_title, title)
        v.setTextColor(R.id.r_title, res.getColor(R.color.widget_ink_muted, null))
        v.setViewVisibility(R.id.r_project, View.GONE)
        v.setTextViewText(R.id.r_activity, sub ?: "")
        v.setViewVisibility(R.id.r_activity, if (sub == null) View.GONE else View.VISIBLE)
        v.setTextColor(R.id.r_activity, res.getColor(R.color.widget_ink_dim, null))
        v.setContentDescription(R.id.r_root, listOfNotNull(title, sub).joinToString(". "))
        v.setOnClickPendingIntent(R.id.r_root, Taps.openApp(ctx))
        return v
    }

    /** The prompt sign before a command in its well: "$ " for a shell command, "› " for anything else. */
    private fun promptSign(a: WidgetModel.Ask) = if (a.tool == "Bash") "$ " else "› "

    /** Text room in the block's command well: the widget's content less the block's and the well's padding. */
    private const val WELL_INSET = 36f

    /**
     * Text room in the one-line block's well (widget_ask_inline), beside Deny and Allow
     * ([answer]) or Open: the content less the row's and the well's padding and the buttons.
     */
    private fun inlineWell(ctx: Context, content: Float, answer: Boolean): Float {
        val res = ctx.resources
        val buttons = if (answer) {
            8f + pill(ctx, res.getString(R.string.deny), 64f, 14f) + 6f + pill(ctx, res.getString(R.string.allow), 64f, 14f)
        } else {
            8f + pill(ctx, res.getString(R.string.widget_open), 64f, 14f)
        }
        return content - 16f - 20f - buttons
    }

    /** A pill button's width (dp): its label as the layouts set it (13sp, semibold), its padding, at least [min]. */
    private fun pill(ctx: Context, label: String, min: Float, pad: Float) =
        max(min, measure(ctx, label, PILL_FONT, 13f) + 2 * pad + 2f)

    private fun askView(ctx: Context, a: WidgetModel.Ask, fit: AskFit, top: Float, content: Float, fs: Float): RemoteViews {
        val res = ctx.resources
        val v = RemoteViews(ctx.packageName, if (fit.inline) R.layout.widget_ask_inline else R.layout.widget_ask)
        // Without the header above it, the block starts at the top edge.
        if (top == 0f && Build.VERSION.SDK_INT >= 31) v.setViewLayoutMargin(R.id.a_root, RemoteViews.MARGIN_TOP, 0f, android.util.TypedValue.COMPLEX_UNIT_DIP)
        val more = if (a.more > 0) "  " + res.getQuantityString(R.plurals.widget_more, a.more, a.more) else ""
        v.setTextViewText(R.id.a_project, a.project + more)
        val tool = a.kind != "question" && a.kind != "plan"
        v.setTextViewText(
            R.id.a_tool,
            when (a.kind) { "question" -> res.getString(R.string.widget_question); "plan" -> res.getString(R.string.widget_plan); else -> a.tool },
        )
        // A command in its well: "$ git push origin main", the prompt sign in amber, as the
        // session screen shows it. A question or a plan reads as prose.
        val body = if (tool) R.id.a_command else R.id.a_text
        if (tool) {
            val cmd = SpannableStringBuilder()
            cmd.append(promptSign(a), ForegroundColorSpan(res.getColor(R.color.widget_amber, null)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            // Whole, with its own line breaks, when it's answered here; otherwise as much of it as
            // fits on the lines there are, which Open shows in full.
            cmd.append(if (fit.answer) a.detail else WidgetModel.oneLine(a.detail))
            v.setTextViewText(R.id.a_command, cmd)
            // In pixels, as it was measured: the launcher resolves sp itself when it lays the view
            // out, so a font size changed while this picture is up would reflow it past its lines.
            v.setTextViewTextSize(R.id.a_command, TypedValue.COMPLEX_UNIT_PX, wellPx(ctx))
        } else {
            v.setTextViewText(R.id.a_text, a.detail)
            v.setViewVisibility(R.id.a_command, View.GONE)
            v.setViewVisibility(R.id.a_text, View.VISIBLE)
        }
        // A command answered here gets a line more than it was measured to need: if the estimate
        // is a character short, the last line still shows rather than an ellipsis.
        if (!fit.inline) v.setInt(body, "setMaxLines", if (fit.answer) fit.lines + 1 else fit.lines)
        val open = Taps.openSession(ctx, a.sessionId)
        v.setOnClickPendingIntent(R.id.a_root, open)
        v.setOnClickPendingIntent(body, open)

        // On one line a note ("Didn't go through") takes the command's place, and Allow never
        // shows without the command beside it: Open then, until the note goes.
        val answerHere = tool && fit.answer && !(fit.inline && a.answering != null)
        // The session's title beside the buttons, where a few words of it fit (a stub like
        // "Ship the ho…" says nothing).
        val buttons = if (answerHere) {
            pill(ctx, res.getString(R.string.deny), 72f, 16f) + 8f + pill(ctx, res.getString(R.string.allow), 72f, 16f)
        } else {
            pill(ctx, res.getString(R.string.widget_open), 72f, 16f)
        }
        val titleRoom = content - 16f - 12f - buttons
        val note: Pair<String, Int>? = when (a.answering) {
            WidgetModel.Answering.Allowing -> res.getString(R.string.widget_allowing) to R.color.widget_accent
            WidgetModel.Answering.Denying -> res.getString(R.string.widget_denying) to R.color.widget_ink_muted
            WidgetModel.Answering.Failed -> res.getString(R.string.widget_failed) to R.color.widget_danger
            null -> if (fit.inline || a.title.isEmpty() || titleRoom < sansWidth("x".repeat(15), 12f * fs)) null else a.title to R.color.widget_ink_dim
        }
        val sending = a.answering == WidgetModel.Answering.Allowing || a.answering == WidgetModel.Answering.Denying
        if (note != null) {
            v.setTextViewText(R.id.a_note, note.first)
            v.setTextColor(R.id.a_note, res.getColor(note.second, null))
            v.setViewVisibility(R.id.a_note, View.VISIBLE)
            // One line has room for the command or for what's happening to it, not both.
            if (fit.inline) v.setViewVisibility(body, View.GONE)
        } else if (fit.inline) {
            v.setViewVisibility(R.id.a_note, View.GONE)
        } else {
            // Empty, but still there: in the block it's what keeps the buttons to the right.
            v.setTextViewText(R.id.a_note, "")
            v.setViewVisibility(R.id.a_note, View.VISIBLE)
        }
        if (answerHere) {
            val gone = if (sending) View.GONE else View.VISIBLE
            v.setViewVisibility(R.id.a_deny, gone)
            v.setViewVisibility(R.id.a_allow, gone)
            v.setViewVisibility(R.id.a_open, View.GONE)
            v.setOnClickPendingIntent(R.id.a_allow, Taps.answer(ctx, a.id, allow = true))
            v.setOnClickPendingIntent(R.id.a_deny, Taps.answer(ctx, a.id, allow = false))
            v.setContentDescription(R.id.a_allow, res.getString(R.string.widget_a11y_allow, a.detail))
            v.setContentDescription(R.id.a_deny, res.getString(R.string.widget_a11y_deny, a.detail))
        } else {
            // A question, a plan, or a command that doesn't show in full at this size: the app
            // shows all of it, and answers it there.
            v.setViewVisibility(R.id.a_deny, View.GONE)
            v.setViewVisibility(R.id.a_allow, View.GONE)
            v.setViewVisibility(R.id.a_open, if (sending) View.GONE else View.VISIBLE)
            v.setOnClickPendingIntent(R.id.a_open, open)
            // Named by its project, or its session's title when it has none (a session outside
            // any project), so TalkBack never reads "Open the approval in" and stops.
            val where = a.project.ifBlank { a.title }
            v.setContentDescription(
                R.id.a_open,
                if (where.isBlank()) res.getString(R.string.widget_a11y_open_plain) else res.getString(R.string.widget_a11y_open, where),
            )
        }
        return v
    }

    /** The least content width (dp) at which the footer has room for the other agent's readout beside the meters. */
    private const val OTHER_FOOTER = 240f

    private fun footer(ctx: Context, v: RemoteViews, m: WidgetModel, art: Art, narrow: Boolean, content: Float) {
        val res = ctx.resources
        val u = m.usage
        val dimmed = m.conn == Conn.Offline
        v.setImageViewBitmap(R.id.w_star, art.star(FOOTER_STAR, m, icon = true))
        // Beside the slim meters (no tags under them) a smaller sun, so the footer is no
        // taller than they need: footerHeight counts on it.
        if (Build.VERSION.SDK_INT >= 31) {
            val star = if (narrow) SLIM_STAR else FOOTER_STAR
            v.setViewLayoutWidth(R.id.w_star, star, TypedValue.COMPLEX_UNIT_DIP)
            v.setViewLayoutHeight(R.id.w_star, star, TypedValue.COMPLEX_UNIT_DIP)
        }
        // Each window as the app's projection meter: the bar's used part, on a live picture the
        // pace's faint fill and the red notch when the limit comes first, and under it one tag.
        val now = System.currentTimeMillis()
        val live = m.conn == Conn.Live
        fun meter(ids: IntArray, p: UsageProjection, known: Boolean, limited: Boolean, words: String, glyph: Boolean) {
            val (value, mint, amber, danger, notch) = ids
            val pct = (p.used * 100).roundToInt()
            v.setTextViewText(value, if (known || limited) res.getString(R.string.percent, if (limited) 100 else pct) else "—")
            v.setTextColor(
                value,
                res.getColor(
                    when {
                        dimmed -> R.color.widget_ink_dim
                        limited -> R.color.widget_danger
                        pct >= 80 -> R.color.widget_amber
                        else -> R.color.widget_ink
                    },
                    null,
                ),
            )
            val bar = when { limited -> danger; pct >= 80 -> amber; else -> mint }
            val (used, lead) = WidgetMeter.levels(p)
            for (b in intArrayOf(mint, amber, danger)) {
                v.setViewVisibility(b, if (b == bar) View.VISIBLE else View.GONE)
                v.setProgressBar(b, 1000, if (limited) 1000 else used, false)
                v.setInt(b, "setSecondaryProgress", if (limited) 1000 else lead)
            }
            v.setViewVisibility(notch, if (p.limitFirst) View.VISIBLE else View.GONE)
            // The slim footer has no room under its bars: the notch says it.
            meterTag(ctx, v, ids[5], ids[6], ids[7], !narrow, words, glyph, limit = p.limitFirst)
        }
        // The week's numbers stay true when the 5-hour window resets (which leaves the 5-hour
        // meter waiting for new ones).
        val weekKnown = u.known || u.sevenDay > 0f
        val five = u.five(now, live)
        val fiveLimit = WidgetMeter.limitAt(five, now)
        val fiveTimed = !u.limited && u.known
        meter(
            intArrayOf(R.id.w_five_value, R.id.w_five_mint, R.id.w_five_amber, R.id.w_five_danger, R.id.w_five_notch, R.id.w_five_tag, R.id.w_five_glyph, R.id.w_five_reset),
            five, u.known, u.limited,
            when {
                u.limited -> res.getString(R.string.widget_refills, clock(ctx, u.fiveResetAt))
                !u.known && m.unmeasured -> ""
                !u.known -> res.getString(R.string.usage_waiting)
                fiveLimit > 0L -> clock(ctx, fiveLimit)
                else -> clock(ctx, u.fiveResetAt)
            },
            glyph = fiveTimed,
        )
        val seven = u.seven(now, live)
        val sevenLimit = WidgetMeter.limitAt(seven, now)
        // Not known yet, or the week has reset since the numbers came in: no time to show.
        val sevenTimed = !u.weekLimited && weekKnown && u.sevenResetAt > 0L
        meter(
            intArrayOf(R.id.w_seven_value, R.id.w_seven_mint, R.id.w_seven_amber, R.id.w_seven_danger, R.id.w_seven_notch, R.id.w_seven_tag, R.id.w_seven_glyph, R.id.w_seven_reset),
            seven, weekKnown, u.weekLimited,
            when {
                u.weekLimited -> res.getString(R.string.widget_refills, day(ctx, u.sevenResetAt))
                !sevenTimed -> ""
                sevenLimit > 0L -> day(ctx, sevenLimit)
                else -> day(ctx, u.sevenResetAt)
            },
            glyph = sevenTimed,
        )
        v.setContentDescription(
            R.id.w_five,
            when {
                u.limited -> Lead(fiveLimitName(ctx, m), u.fiveResetAt.takeIf { it > 0L }?.let { back(ctx, it) }, amber = false).line(ctx, spoken = true)
                !u.known -> res.getString(R.string.sun_unknown_said)
                else -> meterSaid(ctx, fiveName(ctx, m), five, u.fiveResetAt, now)
            },
        )
        v.setContentDescription(
            R.id.w_seven,
            when {
                u.weekLimited -> Lead(res.getString(R.string.limit_weekly), u.sevenResetAt.takeIf { it > 0L }?.let { back(ctx, it) }, amber = false).line(ctx, spoken = true)
                !weekKnown -> res.getString(R.string.widget_a11y_week_unknown)
                else -> meterSaid(ctx, weekName(ctx, m), seven, if (sevenTimed) u.sevenResetAt else 0L, now)
            },
        )
        // A Codex plan with only the week, leading: one meter, the week's (the 5-hour one would repeat it).
        v.setViewVisibility(R.id.w_five, if (m.weekOnly) View.GONE else View.VISIBLE)
        // The other agent while both are in use, where the meters keep their room: its name over its
        // value, lined up with theirs (the spacer stands where their tags are; none in the slim footer).
        val o = m.other?.takeIf { content >= OTHER_FOOTER }
        otherReadout(ctx, v, o, dimmed)
        if (o != null) {
            v.setViewVisibility(R.id.w_other_tag, if (narrow) View.GONE else View.INVISIBLE)
            v.setContentDescription(R.id.w_other, otherSaid(ctx, o))
        }
    }

    // ─── Times ──────────────────────────────────────────────────────────────

    /**
     * Times on a widget are clock times ("resets 16:40"), never countdowns: the widget isn't
     * redrawn every minute, and a clock time stays true until it passes.
     */
    fun clock(ctx: Context, t: Long): String = DateFormat.getTimeFormat(ctx).format(Date(t))

    /**
     * A reset in the next 20 hours is a clock time; later ones are a weekday ("Thu"): where the
     * permanent notification's times (AccountWords.clock) start saying the day, so a weekly limit
     * 22 hours off reads "back Fri 1:21 PM" above and "refills Fri" on its meter, never "1:21 PM".
     */
    fun day(ctx: Context, t: Long): String =
        if (t - System.currentTimeMillis() < SOON) clock(ctx, t)
        else DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)

    private const val SOON = 20 * DateUtils.HOUR_IN_MILLIS

    /** When the widget last heard: a time today, a weekday and time this week ("Wed 21:56"), else a date. */
    fun stamp(ctx: Context, t: Long): String {
        val now = System.currentTimeMillis()
        return when {
            sameDay(t, now) -> clock(ctx, t)
            now - t < 6 * DateUtils.DAY_IN_MILLIS ->
                DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY) + " " + clock(ctx, t)
            else -> DateUtils.formatDateTime(ctx, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
        }
    }

    /**
     * When [stamp] of [t] next reads differently, seen at [now]: at midnight a time from today
     * needs its weekday ("Wed 21:56"), and after six days a date. Null once it's a date.
     */
    fun stampChanges(t: Long, now: Long): Long? = when {
        sameDay(t, now) -> Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        now - t < 6 * DateUtils.DAY_IN_MILLIS -> t + 6 * DateUtils.DAY_IN_MILLIS
        else -> null
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance().apply { timeInMillis = a }
        val y = Calendar.getInstance().apply { timeInMillis = b }
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }

    // ─── Text widths ────────────────────────────────────────────────────────

    // Rough widths (dp) of a line, to choose what to say before the launcher lays it out. Measured
    // on Roboto and Droid Sans Mono, a little generous so a line that's "just fits" never clips.
    private fun monoWidth(s: String, sp: Float) = s.length * sp * 0.6f
    private fun sansWidth(s: String, sp: Float) = s.length * sp * 0.5f
    private fun trackedWidth(s: String, sp: Float, tracking: Float = 0.18f) = s.length * sp * (0.57f + tracking)

    private val MONO: Typeface = Typeface.MONOSPACE
    private val PILL_FONT: Typeface = Typeface.create(Typeface.SANS_SERIF, 600, false)
    /** The layouts' body text (sans-serif at weight 500). */
    private val SANS_MEDIUM: Typeface = Typeface.create(Typeface.SANS_SERIF, 500, false)
    /** The tracked caps labels (sans-serif at weight 600). */
    private val LABEL_FONT: Typeface = PILL_FONT

    /** [sp] at this phone's font scale, in pixels. */
    private fun spPx(ctx: Context, sp: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, ctx.resources.displayMetrics)

    private fun paint(font: Typeface, px: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        textSize = px
    }

    /** One line's height (dp) in [paint], as a TextView without font padding draws it. */
    private fun lineDp(ctx: Context, paint: TextPaint): Float {
        val fm = paint.fontMetricsInt
        return (fm.descent - fm.ascent) / ctx.resources.displayMetrics.density
    }

    /** [s]'s width (dp) in [font] at [sp], measured as the launcher will draw it (font scale included). */
    private fun measure(ctx: Context, s: String, font: Typeface, sp: Float): Float {
        val dm = ctx.resources.displayMetrics
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
        }
        return paint.measureText(s) / dm.density
    }

    /** The well's 12sp at this phone's font scale, in pixels (askView pins the view to it). */
    private fun wellPx(ctx: Context): Float = spPx(ctx, 12f)

    /**
     * How many lines [text] takes in an approval's command well [width] dp wide, or null when the
     * well has hardly any room. Allow depends on it, so it isn't a guess: the platform's own line
     * breaker lays the text out with the font and settings widget_ask gives the well (monospace
     * 12sp at this phone's font scale, simple breaks, no hyphens), as the launcher will. A
     * character narrower than the well, so a rounding difference can't push a word over.
     */
    private fun wellLines(ctx: Context, text: String, width: Float): Int? = lineCount(ctx, paint(MONO, wellPx(ctx)), text, width)

    /** How many lines [text] takes in [paint] in a view [width] dp wide, as [wellLines] counts them. */
    private fun lineCount(ctx: Context, paint: TextPaint, text: String, width: Float): Int? {
        val dm = ctx.resources.displayMetrics
        val cell = paint.measureText("M")
        val px = (width * dm.density - cell).toInt()
        if (px < cell * 6) return null
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, px)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setIncludePad(false)
            .build()
            .lineCount
    }

    // ─── Bitmaps ────────────────────────────────────────────────────────────

    /**
     * The bitmaps one update needs, each drawn once: every size of a widget shares them (Android
     * sends a bitmap used by several layouts only once), and the last few are kept between
     * updates, since a star only changes with usage.
     */
    class Art(private val ctx: Context) {
        private val density = ctx.resources.displayMetrics.density

        /** The sun [dp] wide; [icon]: as a small mark (the meters' footer), larger in its square and without the sky around it. */
        fun star(dp: Float, m: WidgetModel, icon: Boolean = false): Bitmap {
            val look = look(m)
            val px = max(16, (dp * density).roundToInt())
            // Usage in whole percent: finer steps wouldn't show, and would redraw for nothing.
            val u = (m.usage.fiveHour * 100).roundToInt() / 100f
            return cached("star:$look:$u:$px:$icon") { WidgetArt.star(px, u, look, icon) }
        }

        fun planet(dp: Float, mood: Mood, bracket: Boolean): Bitmap {
            val px = max(8, (dp * density).roundToInt())
            return cached("planet:$mood:$px:$bracket") { WidgetArt.planet(px, mood, bracket) }
        }

        private fun look(m: WidgetModel) = when {
            m.conn == Conn.Unpaired -> Look.Unpaired
            m.conn == Conn.Offline -> Look.Offline
            m.usage.weekLimited -> Look.WeekLimited
            m.usage.limited -> Look.Limited
            !m.usage.known -> Look.Unknown
            else -> Look.Live
        }

        private fun cached(key: String, draw: () -> Bitmap): Bitmap = synchronized(cache) {
            cache[key] ?: draw().also { cache[key] = it }
        }

        companion object {
            // A few planets and two or three suns: what two widgets show at once. A sun is the
            // largest at 3x density (a 132dp star is about 600 KB), so this holds 2 to 5 MB.
            private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 8
            }
        }
    }
}

/** Where the widgets' taps go. Distinct data per target, so their PendingIntents don't replace each other. */
internal object Taps {
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), FLAGS,
    )

    /**
     * The same intent an approval notification opens (MainActivity.ACTION_OPEN_SESSION). Its own
     * data per session, so two sessions whose ids hash alike don't share one PendingIntent.
     */
    fun openSession(ctx: Context, id: String): PendingIntent = PendingIntent.getActivity(
        ctx, id.hashCode(),
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .setAction(MainActivity.ACTION_OPEN_SESSION).putExtra(MainActivity.EXTRA_SESSION, id)
            .setData(Uri.parse("hypernova-widget://session/" + Uri.encode(id))),
        FLAGS,
    )

    /** The widgets' own wake-up when a picture goes out of date by itself (WidgetSync.schedule); null when [create] is false and none is set. */
    fun tick(ctx: Context, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, WidgetActions::class.java).setAction(WidgetActions.ACTION_TICK),
        if (create) FLAGS else PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    fun answer(ctx: Context, approvalId: String, allow: Boolean): PendingIntent = PendingIntent.getBroadcast(
        ctx, (approvalId + allow).hashCode(),
        Intent(ctx, WidgetActions::class.java)
            .setAction(if (allow) WidgetActions.ACTION_ALLOW else WidgetActions.ACTION_DENY)
            .setData(Uri.parse("hypernova-widget://approval/" + Uri.encode(approvalId)))
            .putExtra(WidgetActions.EXTRA_APPROVAL, approvalId),
        FLAGS,
    )
}
