package com.prudnikweb.hypernova.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.prudnikweb.hypernova.R

/**
 * Something to tell the owner: a session needs them, finished, stopped on an error, or the
 * Mac has a newer app. Shown as a banner while the app is open (unless that session is on
 * screen) and as a system notification while it isn't.
 */
@Immutable
data class Notice(
    val id: String,
    val kind: Kind,
    /** The session it's about (null for the app update). */
    val sessionId: String?,
    val title: String,
    val text: String,
    /** A button on the banner ([ACTION_EXTEND]: Backpack's "+2 h"); null: the banner only opens something. */
    val action: String? = null,
    /** An automation's note (data/AutomationsLive.kt): a tap with no session opens it ("" its tab). */
    val automationId: String? = null,
) {
    /**
     * [Limit]: Claude rests at a usage limit (calm, it carries on by itself). [Account]: the
     * account needs the owner (signed out, no plan, out of credits, on hold). One per episode.
     * [Power]: a Mac about to run out of battery or to sleep; on screen, a banner like an approval's.
     */
    enum class Kind { Approval, Question, Plan, Waiting, Finished, Error, Update, Limit, Account, Power, Backpack, Automation, ServerUpdateFailed }

    companion object {
        const val ACTION_EXTEND = "extend"
    }
}

/**
 * A newer Android app on the Mac than the one running here. [url]: the Mac's "Get Hypernova" page;
 * the app downloads and installs the APK itself (data/AppUpdater.kt), the page is only a way round
 * where a browser can open it.
 */
/**
 * Fork: banners the owner put away (swiped or ×) a moment ago. Another of the same kind for the
 * same session within [QUIET_MS] doesn't come back over the screen: parallel agents each asking, say,
 * were a new banner every few seconds however often it was swiped. Its card is in the session and on
 * Home either way, and another kind (a question after an approval) still shows.
 */
class PutAway {
    private val at = HashMap<Pair<String, Notice.Kind>, Long>()

    fun put(n: Notice, now: Long) {
        n.sessionId?.let { at[it to n.kind] = now }
    }

    fun quiet(n: Notice, now: Long): Boolean {
        val t = n.sessionId?.let { at[it to n.kind] } ?: return false
        return now - t < QUIET_MS
    }

    companion object {
        const val QUIET_MS = 30_000L
    }
}

/** A newer app a paired computer offers; [server]: which one (data/Servers.kt), so it's downloaded from there. Null: the one on screen. */
@Immutable
data class AppUpdate(val versionName: String, val versionCode: Int, val url: String, val server: String? = null)

/** True the first time it's asked about this version (remembered on the phone), so an update is announced once. */
fun updateNoticed(context: Context, versionCode: Int): Boolean {
    val prefs = context.getSharedPreferences("hypernova", Context.MODE_PRIVATE)
    if (prefs.getInt("updateNoticed", 0) >= versionCode) return false
    prefs.edit().putInt("updateNoticed", versionCode).apply()
    return true
}

fun updateNotice(context: Context, u: AppUpdate) = Notice(
    "update:${u.versionCode}", Notice.Kind.Update, null,
    context.getString(R.string.update_title),
    context.getString(R.string.update_body, u.versionName),
)

/** The id of a server's update-failed notice: one per server, its next taking its place. */
const val SERVER_UPDATE_NOTICE = "server-update-failed"

/**
 * True the first time it's asked about this failure of [server]'s update (remembered on the phone,
 * per server). Said once when a release first fails, and once more when the server gives up on it:
 * the tries between would say the same again, up to a day apart.
 */
fun serverUpdateFailNoticed(context: Context, server: String, f: ServerUpdate.Failure): Boolean {
    val prefs = context.getSharedPreferences("hypernova", Context.MODE_PRIVATE)
    val key = Servers.scoped("serverUpdateFailNoticed", server)
    val episode = "${f.tag}:${f.next == null}"
    if (prefs.getString(key, null) == episode) return false
    prefs.edit().putString(key, episode).apply()
    return true
}

/** "z2 · Couldn't update to 0.14.0: why. It tries again by itself." */
fun serverUpdateFailedNotice(res: android.content.res.Resources, macName: String, f: ServerUpdate.Failure) = Notice(
    SERVER_UPDATE_NOTICE, Notice.Kind.ServerUpdateFailed, null, macName, serverUpdateFailedText(res, f),
)

/** What failed and what happens next: the notice's text and Settings' line. */
fun serverUpdateFailedText(res: android.content.res.Resources, f: ServerUpdate.Failure): String {
    val what = if (f.why != null) res.getString(R.string.su_failed_why, f.version, f.why.trimEnd('.')) else res.getString(R.string.su_failed, f.version)
    return what + " " + res.getString(if (f.next == null) R.string.su_failed_final else R.string.su_failed_again)
}

/** A web page (claude.ai's usage or billing, the sign-in page) in the phone's browser. */
fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * True the first time it's asked about this account episode (remembered on the phone), so a
 * limit or a sign-out is announced once, not once per session nor again after a restart.
 */
fun accountNoticed(context: Context, episode: String): Boolean {
    val prefs = context.getSharedPreferences("hypernova", Context.MODE_PRIVATE)
    if (prefs.getString("accountNoticed", null) == episode) return false
    prefs.edit().putString("accountNoticed", episode).apply()
    return true
}

/**
 * The account in words (docs/PROTOCOL.md, "Account"): the same phrases on the home card, a
 * session, the banner and the permanent notification. Times are the phone's, in its 12- or
 * 24-hour style.
 */
object AccountWords {
    private const val HOUR = 3_600_000L
    const val USAGE_URL = "https://claude.ai/settings/usage"
    const val RENEW_URL = "https://claude.ai/upgrade"
    const val API_BILLING_URL = "https://console.anthropic.com/settings/billing"
    const val HOLD_URL = "https://claude.ai/restricted"
    /** The id of every account episode's notice: a new one's notification takes the place of the last. */
    const val NOTICE_ID = "account"
    private val HIT = Regex("hit your ([^·]+?)\\s*(·|$)")
    private val LINK = Regex("https://\\S+")

    /** Within the next 20 hours the time alone ("2:00"); later, with the day ("Fri 10:00"). */
    private fun soon(at: Long, now: Long) = at - now < 20 * HOUR

    /** "2:00", or "Fri 10:00" when it isn't soon. */
    fun clock(context: Context, at: Long, now: Long = System.currentTimeMillis()): String {
        val h24 = android.text.format.DateFormat.is24HourFormat(context)
        val skeleton = (if (soon(at, now)) "" else "EEE") + (if (h24) "Hm" else "hm")
        val locale = java.util.Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        return java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(at))
    }

    /** "at 2:00" or "Fri 10:00", to follow "back" or "carries on". */
    fun atClock(context: Context, at: Long, now: Long = System.currentTimeMillis()): String =
        if (soon(at, now)) context.getString(R.string.at_clock, clock(context, at, now)) else clock(context, at, now)

    /** "Session limit", "Weekly limit", "Opus limit": Claude Code's own words when it gave them. */
    fun label(context: Context, limit: Account.Limit?): String {
        val own = limit?.label?.trim()?.takeIf { it.isNotEmpty() }
        if (own != null) return own.replaceFirstChar { it.uppercase() }
        return context.getString(
            when (limit?.window) {
                "seven_day" -> R.string.limit_weekly
                "model" -> R.string.limit_model
                "credits" -> R.string.limit_credits
                else -> R.string.limit_session
            },
        )
    }

    /** The model a one-model limit is about ("Opus" from "Opus limit"), or null. */
    fun model(limit: Account.Limit?): String? {
        val l = limit?.label?.trim() ?: return null
        if (limit.window != "model" || !l.endsWith(" limit", ignoreCase = true)) return null
        return l.dropLast(" limit".length).trim().takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
    }

    /** The limit a session's block names: the account's when it's the same one, else Claude Code's line ("hit your session limit"). */
    fun label(context: Context, b: Blocked, account: Account): String {
        val own = account.limit?.takeIf { (b.kind == Blocked.MODEL) == (it.window == "model") }
        if (own != null) return label(context, own)
        HIT.find(b.text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.replaceFirstChar { c -> c.uppercase() } }
        return context.getString(if (b.kind == Blocked.MODEL) R.string.limit_model else R.string.limit_session)
    }

    /** The page Claude Code named ("View details or appeal: https://…"), else [fallback]. */
    fun link(text: String?, fallback: String): String = text?.let { LINK.find(it)?.value?.trimEnd('.', ')', ',') } ?: fallback

    /**
     * A blocked session's line on its card, in mono, as a short phrase (never "·"-joined fields):
     * "session limit until 2:00", "session limit until 2:00, then carries on", "signed out on the
     * Mac". [provider]: the session's provider's name, for its own trouble; [p]: that provider,
     * whose state it tells (data/Providers.kt).
     */
    fun activity(context: Context, b: Blocked, account: Account, provider: String?, now: Long = System.currentTimeMillis(), p: Provider? = null): String {
        val r = context.resources
        return when (b.kind) {
            Blocked.LIMIT, Blocked.MODEL -> {
                val l = label(context, b, account).lowercase()
                when {
                    // The limit is over and it didn't carry on by itself: it waits for Continue.
                    b.resume?.ready == true -> r.getString(R.string.activity_limit_ready, l)
                    b.autoResumeAt != null && b.autoResumeAt > now -> r.getString(R.string.activity_limit_auto, l, clock(context, b.autoResumeAt, now))
                    b.resetsAt != null && b.resetsAt > now -> r.getString(R.string.activity_limit, l, clock(context, b.resetsAt, now))
                    // A model this plan can't use, or one that needs usage credits: no reset to wait for.
                    b.kind == Blocked.MODEL && b.resetsAt == null -> r.getString(R.string.activity_model_unavailable)
                    else -> r.getString(R.string.activity_limit_paused, l)
                }
            }
            Blocked.LOGGED_OUT -> r.getString(R.string.activity_signed_out)
            Blocked.NO_SUBSCRIPTION -> r.getString(R.string.activity_no_plan)
            Blocked.ON_HOLD -> r.getString(R.string.activity_on_hold)
            Blocked.NO_CREDIT -> r.getString(R.string.activity_no_credit)
            else -> if (p != null) ProviderWords.activity(context, b, p, now) else provider?.let { r.getString(R.string.activity_provider, it) } ?: b.text
        }
    }

    /**
     * The permanent notification's first line while Claude can't reply on the subscription:
     * "Session limit until 2:00", "Weekly limit until Fri 10:00", "Signed out on Mac".
     * [usage] covers a limit the status line reports before any session hit it. Null when all's well.
     */
    fun headline(context: Context, account: Account, usage: Usage, macName: String, now: Long = System.currentTimeMillis(), both: Boolean = false): String? {
        val r = context.resources
        // [both]: Codex is in use too, so the trouble is said as Claude's ("Claude Code signed out on Mac"), not the app's.
        when (account.state) {
            Account.LOGGED_OUT -> return r.getString(if (both) R.string.headline_signed_out_claude else R.string.headline_signed_out, macName)
            Account.NO_SUBSCRIPTION -> return r.getString(R.string.headline_no_plan)
            Account.ON_HOLD -> return r.getString(if (both) R.string.headline_on_hold_claude else R.string.headline_on_hold)
            Account.NO_CREDIT -> return r.getString(if (both) R.string.headline_no_credit_claude else R.string.headline_no_credit)
        }
        // Running on usage credits past a limit, Claude carries on: nothing to lead with, not even
        // the 100% the status line still shows for that window.
        if (account.state == Account.LIMITED && account.limit?.usingCredits == true) return null
        val rest = account.resting(now)
        fun back(label: String, at: Long) = r.getString(if (both) R.string.headline_limit_claude else R.string.headline_limit, label, clock(context, at, now))
        // The weekly limit first: it outlasts the 5-hour one.
        if (rest?.window == "seven_day" && rest.resetsAt != null) return back(label(context, rest), rest.resetsAt)
        if (usage.weekLimited && usage.sevenDayResetAt > now) return back(r.getString(R.string.limit_weekly), usage.sevenDayResetAt)
        if (rest?.window == "five_hour" && rest.resetsAt != null) return back(label(context, rest), rest.resetsAt)
        if (usage.limited && usage.fiveHourResetAt > now) return back(r.getString(R.string.limit_session), usage.fiveHourResetAt)
        // A one-model limit, or the extra usage's own.
        if (rest?.resetsAt != null) return back(label(context, rest), rest.resetsAt)
        return null
    }

    /**
     * The banner (or, for what needs the owner, the notification) for a new account episode, or
     * null when there's nothing to say. Once per episode: see [accountNoticed].
     */
    fun notice(context: Context, account: Account, macName: String, now: Long = System.currentTimeMillis(), both: Boolean = false): Notice? {
        val r = context.resources
        fun n(kind: Notice.Kind, text: String) = Notice(NOTICE_ID, kind, null, macName, text)
        // [both]: Codex is in use too and carries on, so the words say whose sessions can't reply.
        return when (account.state) {
            Account.LOGGED_OUT -> n(Notice.Kind.Account, r.getString(if (both) R.string.notice_signed_out_both else R.string.notice_signed_out))
            Account.NO_SUBSCRIPTION -> n(Notice.Kind.Account, r.getString(R.string.notice_no_plan))
            Account.ON_HOLD -> n(Notice.Kind.Account, r.getString(if (both) R.string.notice_on_hold_both else R.string.notice_on_hold))
            Account.NO_CREDIT -> n(Notice.Kind.Account, r.getString(if (both) R.string.notice_no_credit_both else R.string.notice_no_credit))
            Account.LIMITED -> {
                val l = account.resting(now)?.takeIf { !it.usingCredits } ?: return null
                val label = label(context, l)
                val auto = l.autoResumeAt?.takeIf { it > now }
                val at = auto ?: l.resetsAt
                n(
                    Notice.Kind.Limit,
                    when {
                        l.window == "model" -> if (at != null) r.getString(R.string.notice_model, label, atClock(context, at, now)) else label
                        at == null -> label
                        auto != null -> r.getString(R.string.notice_limit_auto, label, atClock(context, at, now))
                        else -> r.getString(R.string.notice_limit, label, atClock(context, at, now))
                    },
                )
            }
            else -> null
        }
    }
}
