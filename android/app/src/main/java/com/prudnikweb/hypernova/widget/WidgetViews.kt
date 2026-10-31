package com.prudnikweb.hypernova.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import com.prudnikweb.hypernova.MainActivity
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.widget.WidgetModel.Answering
import com.prudnikweb.hypernova.widget.WidgetModel.Kind
import java.util.Calendar
import java.util.Date

/**
 * Builds the widget's RemoteViews from a [WidgetModel]: a header (NEEDS YOU and how many), then a
 * list that scrolls ([WidgetListService] makes its rows): each computer's name and count, then its
 * sessions that need you. Plain: the app's colours and type on a dark card, no pictures. One
 * layout for every size; the list takes whatever height the widget has.
 */
internal object WidgetViews {

    fun build(ctx: Context, id: Int, m: WidgetModel): RemoteViews {
        val res = ctx.resources
        val v = RemoteViews(ctx.packageName, R.layout.widget_sessions)
        v.setOnClickPendingIntent(android.R.id.background, Taps.openApp(ctx))
        v.setOnClickPendingIntent(R.id.w_header, Taps.openApp(ctx))

        val n = m.count
        v.setTextViewText(R.id.w_count, if (n > 0) n.toString() else "")
        v.setViewVisibility(R.id.w_count, if (n > 0) View.VISIBLE else View.GONE)
        val note = note(ctx, m)
        v.setTextViewText(R.id.w_note, note ?: "")
        v.setViewVisibility(R.id.w_note, if (note == null) View.GONE else View.VISIBLE)
        v.setContentDescription(
            R.id.w_header,
            listOfNotNull(
                if (n > 0) res.getQuantityString(R.plurals.widget_a11y_count, n, n) else res.getString(R.string.widget_nothing),
                note,
            ).joinToString(". "),
        )

        // The list: its rows come from the service, its taps go through one template (WidgetTap).
        @Suppress("DEPRECATION")
        v.setRemoteAdapter(R.id.w_list, Taps.list(ctx, id))
        v.setPendingIntentTemplate(R.id.w_list, Taps.template(ctx))
        v.setEmptyView(R.id.w_list, R.id.w_empty)

        // Shown while the list is empty: nothing waits anywhere, or nothing is known yet.
        val (title, sub) = empty(ctx, m)
        v.setTextViewText(R.id.w_empty_title, title)
        v.setTextViewText(R.id.w_empty_sub, sub ?: "")
        v.setViewVisibility(R.id.w_empty_sub, if (sub == null) View.GONE else View.VISIBLE)
        v.setOnClickPendingIntent(R.id.w_empty, Taps.openApp(ctx))
        return v
    }

    /** The quiet line in the header while nothing keeps the widget current: "Last updated 14:32". */
    private fun note(ctx: Context, m: WidgetModel): String? = when {
        !m.paired || m.live || m.updatedAt <= 0L -> null
        else -> ctx.getString(R.string.widget_last_updated, stamp(ctx, m.updatedAt))
    }

    /** What the empty list says, and a second line under it. */
    private fun empty(ctx: Context, m: WidgetModel): Pair<String, String?> {
        val res = ctx.resources
        return when {
            !m.paired -> res.getString(R.string.widget_no_computer) to res.getString(R.string.widget_pair)
            // Nothing heard from any computer yet, and nothing kept from before.
            !m.known -> res.getString(if (m.live) R.string.widget_connecting else R.string.widget_app_only) to null
            else -> res.getString(R.string.widget_nothing) to when {
                // Nothing needs you on the ones it can reach: said with the ones it can't.
                m.offline.isNotEmpty() -> res.getString(R.string.widget_cant_reach, m.offline.joinToString(", "))
                !m.live -> res.getString(R.string.widget_app_only)
                else -> null
            }
        }
    }

    // ─── The list ───────────────────────────────────────────────────────────

    /** One line of the list: a computer's name, or a session that needs you. */
    sealed interface Item {
        /** Stable across redraws, so the list keeps its place. */
        val key: String

        data class Header(val group: WidgetModel.Group) : Item {
            override val key: String get() = "h:" + group.server
        }

        data class Need(val row: WidgetModel.Row, val dimmed: Boolean) : Item {
            override val key: String get() = "r:" + row.server + ":" + row.sessionId
        }
    }

    /** The list as drawn: each computer's name and count, then its rows. Its rows are dim while the computer can't be reached. */
    fun items(m: WidgetModel): List<Item> =
        m.groups.flatMap { g -> listOf<Item>(Item.Header(g)) + g.rows.map { Item.Need(it, dimmed = g.offline || g.kept) } }

    fun item(ctx: Context, item: Item): RemoteViews = when (item) {
        is Item.Header -> header(ctx, item.group)
        is Item.Need -> need(ctx, item.row, item.dimmed)
    }

    /** A computer's name in tracked caps and its count in amber; "Offline" after it when it can't be reached. Opens the app there. */
    private fun header(ctx: Context, g: WidgetModel.Group): RemoteViews {
        val res = ctx.resources
        val v = RemoteViews(ctx.packageName, R.layout.widget_need_server)
        val name = g.name.ifBlank { res.getString(R.string.widget_computer) }
        v.setTextViewText(R.id.s_name, name)
        v.setTextViewText(R.id.s_count, g.rows.size.toString())
        v.setViewVisibility(R.id.s_state, if (g.offline) View.VISIBLE else View.GONE)
        v.setContentDescription(
            R.id.s_root,
            res.getQuantityString(R.plurals.widget_a11y_server, g.rows.size, g.rows.size, name) +
                if (g.offline) ". " + res.getString(R.string.widget_offline) else "",
        )
        v.setOnClickFillInIntent(R.id.s_root, Taps.open(g.server, null))
        return v
    }

    /** The prompt sign before a command: "$ " for a shell command, "› " for anything else. */
    private fun promptSign(r: WidgetModel.Row) = if (r.tool.startsWith("Bash", ignoreCase = true)) "$ " else "› "

    /** What a row's small amber label says it waits on: the tool, "Question", "Plan to review"… */
    private fun kindLabel(ctx: Context, r: WidgetModel.Row): String = when (r.kind) {
        Kind.Tool -> r.tool.ifBlank { ctx.getString(R.string.widget_kind_tool) }
        Kind.Terminal -> r.tool.ifBlank { ctx.getString(R.string.widget_kind_terminal) }
        Kind.Question -> ctx.getString(R.string.widget_question)
        Kind.Plan -> ctx.getString(R.string.widget_plan)
        Kind.Waiting -> ctx.getString(R.string.widget_kind_waiting)
    }

    /**
     * A session that needs you: its name (and "+2 more" when other approvals wait behind), what it
     * waits on in amber with its project, the detail (a command in a mono well, whole when Allow is
     * offered; a question or a plan as prose), and its buttons: Deny and Allow, Deny and Open, or
     * Open. A tap anywhere else opens the session, on its computer.
     */
    private fun need(ctx: Context, r: WidgetModel.Row, dimmed: Boolean): RemoteViews {
        val res = ctx.resources
        val v = RemoteViews(ctx.packageName, R.layout.widget_need)
        val title = r.title.ifBlank { r.project.ifBlank { res.getString(R.string.untitled_session) } }
        v.setTextViewText(R.id.n_title, title)
        v.setTextColor(R.id.n_title, res.getColor(if (dimmed) R.color.widget_ink_muted else R.color.widget_ink, null))
        val more = if (r.more > 0) res.getQuantityString(R.plurals.widget_more, r.more, r.more) else null
        v.setTextViewText(R.id.n_more, more ?: "")
        v.setViewVisibility(R.id.n_more, if (more == null) View.GONE else View.VISIBLE)
        val kind = kindLabel(ctx, r)
        v.setTextViewText(R.id.n_kind, kind)
        v.setTextColor(R.id.n_kind, res.getColor(if (dimmed) R.color.widget_ink_dim else R.color.widget_amber, null))
        // The project after the label, unless it's already the row's name.
        val project = r.project.takeIf { it.isNotBlank() && r.title.isNotBlank() }
        v.setTextViewText(R.id.n_project, project ?: "")
        v.setViewVisibility(R.id.n_project, if (project == null) View.GONE else View.VISIBLE)

        val command = r.kind == Kind.Tool || r.kind == Kind.Terminal
        if (command && r.detail.isNotEmpty()) {
            val cmd = SpannableStringBuilder()
            cmd.append(promptSign(r), ForegroundColorSpan(res.getColor(if (dimmed) R.color.widget_ink_dim else R.color.widget_amber, null)), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            cmd.append(r.detail)
            v.setTextViewText(R.id.n_command, cmd)
            // Whole, with its own line breaks, beside Allow: never cut (it's at most 400 characters).
            v.setInt(R.id.n_command, "setMaxLines", if (r.allow) WHOLE_LINES else 2)
            v.setViewVisibility(R.id.n_command, View.VISIBLE)
            v.setViewVisibility(R.id.n_text, View.GONE)
        } else {
            v.setTextViewText(R.id.n_text, r.detail)
            v.setViewVisibility(R.id.n_text, if (r.detail.isEmpty()) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.n_command, View.GONE)
        }

        val open = Taps.open(r.server, r.sessionId)
        v.setOnClickFillInIntent(R.id.n_root, open)
        val sending = r.answering == Answering.Allowing || r.answering == Answering.Denying
        val note: Pair<String, Int>? = when (r.answering) {
            Answering.Allowing -> res.getString(R.string.widget_allowing) to R.color.widget_accent
            Answering.Denying -> res.getString(R.string.widget_denying) to R.color.widget_ink_muted
            Answering.Failed -> res.getString(R.string.widget_failed) to R.color.widget_danger
            null -> null
        }
        // Every view set every time: the list may draw this row into another row's views.
        v.setTextViewText(R.id.n_note, note?.first ?: "")
        v.setTextColor(R.id.n_note, res.getColor(note?.second ?: R.color.widget_ink_dim, null))
        fun shown(b: Boolean) = if (b && !sending) View.VISIBLE else View.GONE
        v.setViewVisibility(R.id.n_deny, shown(r.deny))
        v.setViewVisibility(R.id.n_allow, shown(r.allow))
        // Open wherever Allow isn't: the app shows all of it, and answers it there.
        v.setViewVisibility(R.id.n_open, shown(!r.allow))
        if (r.deny) v.setOnClickFillInIntent(R.id.n_deny, Taps.answer(r, allow = false))
        if (r.allow) v.setOnClickFillInIntent(R.id.n_allow, Taps.answer(r, allow = true))
        v.setOnClickFillInIntent(R.id.n_open, open)
        v.setContentDescription(R.id.n_deny, res.getString(R.string.widget_a11y_deny, r.detail))
        v.setContentDescription(R.id.n_allow, res.getString(R.string.widget_a11y_allow, r.detail))
        v.setContentDescription(R.id.n_open, res.getString(R.string.widget_a11y_open, title))
        v.setContentDescription(
            R.id.n_root,
            if (r.detail.isEmpty()) res.getString(R.string.widget_a11y_row_plain, title, kind)
            else res.getString(R.string.widget_a11y_row, title, kind, r.detail),
        )
        return v
    }

    /** Lines enough for any whole command a row offers Allow for (400 characters, wrapped at a narrow widget's width). */
    private const val WHOLE_LINES = 60

    // ─── Times ──────────────────────────────────────────────────────────────

    /**
     * Times on a widget are clock times ("Last updated 16:40"), never countdowns: the widget isn't
     * redrawn every minute, and a clock time stays true until it passes.
     */
    fun clock(ctx: Context, t: Long): String = DateFormat.getTimeFormat(ctx).format(Date(t))

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
}

/** Where the widget's taps go. Distinct data per target, so their PendingIntents don't replace each other. */
internal object Taps {
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), FLAGS,
    )

    /**
     * The list's one template ([WidgetTap]), filled in per row by [open] and [answer]. Mutable, as a
     * template must be for its rows' extras to reach it; its target is fixed (an explicit component
     * a fill-in can't change).
     */
    fun template(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 2,
        Intent(ctx, WidgetTap::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** The list's rows for widget [id] ([WidgetListService]); its own data per widget, so each gets its own. */
    fun list(ctx: Context, id: Int): Intent = Intent(ctx, WidgetListService::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        .setData(Uri.parse("hypernova-widget://list/$id"))

    /** A row's (or a computer's) tap: the app on [server], at [session] when there is one. */
    fun open(server: String, session: String?): Intent = Intent()
        .putExtra(WidgetTap.EXTRA_DO, WidgetTap.DO_OPEN)
        .putExtra(WidgetActions.EXTRA_SERVER, server)
        .apply { if (session != null) putExtra(WidgetActions.EXTRA_SESSION, session) }

    /** Deny or Allow on [r], on its computer: an approval's id, or the terminal dialog's menu and option. */
    fun answer(r: WidgetModel.Row, allow: Boolean): Intent = Intent()
        .putExtra(WidgetTap.EXTRA_DO, if (allow) WidgetTap.DO_ALLOW else WidgetTap.DO_DENY)
        .putExtra(WidgetActions.EXTRA_SERVER, r.server)
        .putExtra(WidgetActions.EXTRA_SESSION, r.sessionId)
        .putExtra(WidgetActions.EXTRA_APPROVAL, r.askId)
        .putExtra(WidgetActions.EXTRA_MENU, r.kind == Kind.Terminal)
        .putExtra(WidgetActions.EXTRA_CHOICE, if (allow) r.allowChoice else r.denyChoice)

    /** The widget's own wake-up when a picture goes out of date by itself (WidgetSync.schedule); null when [create] is false and none is set. */
    fun tick(ctx: Context, create: Boolean): PendingIntent? = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, WidgetActions::class.java).setAction(WidgetActions.ACTION_TICK),
        if (create) FLAGS else PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )
}
