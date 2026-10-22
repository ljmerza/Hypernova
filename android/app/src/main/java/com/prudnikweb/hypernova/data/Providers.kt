package com.prudnikweb.hypernova.data

import android.content.Context
import androidx.compose.runtime.Immutable
import com.prudnikweb.hypernova.R
import org.json.JSONObject
import java.util.Locale

/**
 * Whether a session a limit stopped carries on by itself (docs/PROTOCOL.md, "Auto-continue"):
 * [ARMED] at [at], [OFF] (back at [at], when known), or [READY]: what stopped it is over and it
 * didn't carry on, so Continue. [byClaude]: Claude Code decides, in its terminal; else the Mac does.
 * [why]: one of the daemon's reasons ("cancelled", "slept", "back"…), unknown ones shown generically.
 */
@Immutable
data class Resume(val state: String, val at: Long?, val byClaude: Boolean, val canCancel: Boolean, val canArm: Boolean, val why: String?) {
    val armed: Boolean get() = state == ARMED
    val off: Boolean get() = state == OFF
    val ready: Boolean get() = state == READY

    companion object {
        const val ARMED = "armed"
        const val OFF = "off"
        const val READY = "ready"
    }
}

/**
 * What's left to spend on a provider, as far as its API tells (OpenRouter): [remaining] dollars
 * under its key's cap (null: no cap, so the account's balance can't be told), [usedToday] dollars
 * spent today, free-model requests left. [error]: the last read failed ("bad_key", "unreachable").
 */
@Immutable
data class Balance(val remaining: Double?, val usedToday: Double?, val freeLeft: Int?, val freeLimit: Int?, val checkedAt: Long, val error: String?)

/**
 * An address and a key tried without saving them, or what the Mac found when it saved them
 * (docs/PROTOCOL.md, "Providers", Check): [state] is [OK], [BAD_KEY], [NO_CREDIT], [LIMITED],
 * [NOT_FOUND] or [UNREACHABLE] (an unknown one reads like unreachable); [text] the Mac's own words
 * ("Key works. $4.20 left."); [models] a custom provider's model ids when it answered; [balance]
 * OpenRouter's, when read.
 */
@Immutable
data class ProviderCheck(val state: String, val text: String, val models: List<String>?, val balance: Balance?, val checkedAt: Long) {
    val ok: Boolean get() = state == OK

    companion object {
        const val OK = "ok"
        const val BAD_KEY = "bad_key"
        const val NO_CREDIT = "no_credit"
        const val LIMITED = "limited"
        const val NOT_FOUND = "not_found"
        const val UNREACHABLE = "unreachable"
    }
}

/** A provider saved: as the Mac has it now, what it found when it tried the key, and whether the key was dropped (the address moved to another host). */
data class ProviderSaved(val provider: Provider, val check: ProviderCheck?, val keyDropped: Boolean)

/**
 * The Mac refused a provider's address, key or name: [code] is its own ("insecure_url",
 * "bad_url", "bad_key_format", "bad_name", "missing_url"…), so the editor puts it under its field.
 */
class ProviderRefused(val code: String?, message: String) : HubException(message)

/** Which field of the provider editor a refusal belongs under. */
enum class ProviderField { Name, Address, Key, Other }

/** The provider editor's own sense of its fields and the Mac's answers; pure, unit-tested. */
object ProviderForm {
    fun field(code: String?): ProviderField = when (code) {
        "bad_name" -> ProviderField.Name
        "bad_url", "insecure_url", "missing_url" -> ProviderField.Address
        "bad_key_format" -> ProviderField.Key
        else -> ProviderField.Other
    }

    /**
     * The address would carry the key unencrypted: plain http to anywhere but this computer, a
     * private network, Tailscale or a .local name (the Mac refuses it then, `insecure_url`; the
     * editor says so as it's typed). Null host or not http: false.
     */
    fun insecure(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://", ignoreCase = true)) return false
        val host = runCatching { java.net.URI(u).host }.getOrNull()?.lowercase()?.trim('[', ']') ?: return false
        return !local(host)
    }

    /** This computer, a private network (10/8, 172.16/12, 192.168/16, link-local, fc00::/7), Tailscale (100.64/10, *.ts.net) or a .local name. */
    fun local(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".ts.net")) return true
        if (h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return h.contains(':')
        val p = h.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        if (p.size != 4 || h.split('.').size != 4) return false
        return p[0] == 127 || p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254) || (p[0] == 100 && p[1] in 64..127)
    }

    /** The address has a scheme the Mac takes: something to save or check. */
    fun addressOk(url: String): Boolean = url.trim().let { (it.startsWith("https://", true) || it.startsWith("http://", true)) && it.length > 8 }

    /** Save leaves the editor only when the key wasn't refused and wasn't dropped: else the verdict stays in view. */
    fun staysOpen(saved: ProviderSaved): Boolean = saved.keyDropped || saved.check?.state == ProviderCheck.BAD_KEY

    /** The automations that run on a provider: start one there, or carry sessions on to it. */
    fun automationsOn(id: String, list: List<Automation>): Int = list.count { a ->
        when (val x = a.action) {
            is Action.Start -> x.providerId == id
            is Action.Continue -> x.providerId == id
            else -> false
        }
    }

    /** The sessions on a provider that go on after it's deleted only once moved (ended ones too: they resume on it). */
    fun sessionsOn(id: String, sessions: List<Session>): Int = sessions.count { it.providerId == id }
}

/** Reading the providers' and resumes' parts of the protocol; anything malformed reads as absent. */
object ProviderWire {
    /** `ProviderCheck`; null when it isn't one. */
    fun check(o: JSONObject?): ProviderCheck? {
        val state = o?.str("state")?.takeIf { it.isNotBlank() } ?: return null
        val models = o.arr("models")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { m -> m.isNotBlank() } } }
        return ProviderCheck(state, o.str("text").orEmpty(), models, balance(o.obj("balance")), o.long("checkedAt") ?: 0L)
    }

    fun resume(o: JSONObject?): Resume? {
        if (o == null) return null
        val state = o.str("state")?.takeIf { it.isNotBlank() } ?: return null
        return Resume(state, o.long("at")?.takeIf { it > 0 }, o.str("by") == "claude", o.bool("canCancel"), o.bool("canArm"), o.str("why"))
    }

    fun balance(o: JSONObject?): Balance? {
        if (o == null) return null
        return Balance(o.dbl("remaining"), o.dbl("usedToday"), o.int("freeLeft"), o.int("freeLimit"), o.long("checkedAt") ?: 0L, o.str("error"))
    }

    fun status(o: JSONObject?): ProviderStatus? {
        val state = o?.str("state")?.takeIf { it.isNotBlank() } ?: return null
        return ProviderStatus(state, o.str("text").orEmpty(), o.long("since"), o.long("resetsAt")?.takeIf { it > 0 }, o.bool("budget"))
    }
}

/**
 * True for a Claude Code session on a provider other than the built-in subscription (OpenRouter, a
 * gateway): the companion star's. A Codex session isn't one, though its providerId is "codex": it's
 * the other agent's (ui/fx/SkySplit.kt).
 */
val Session.onOtherProvider: Boolean get() = providerId != null && providerId != "anthropic" && !codex

/**
 * Other providers in words: their trouble, what's left, a session's line, and the notices
 * (docs/specs/spec-providers.md, §6). Dollars are always written as dollars ("$4.20"): it's what
 * OpenRouter bills in, whatever the phone's language.
 */
object ProviderWords {
    fun usd(v: Double): String = String.format(Locale.US, "$%.2f", v.coerceAtLeast(0.0))

    /** "Out of credits", "Budget reached", "Key refused", "Rate limited", "Not answering". */
    fun state(context: Context, st: ProviderStatus): String = context.getString(
        when (st.state) {
            ProviderStatus.NO_CREDIT -> if (st.budget) R.string.provider_state_budget else R.string.provider_state_no_credit
            ProviderStatus.BAD_KEY -> R.string.provider_state_bad_key
            ProviderStatus.LIMITED -> R.string.provider_state_limited
            ProviderStatus.DOWN -> R.string.provider_state_down
            else -> R.string.provider_state_other
        },
    )

    /** The provider needs the owner (credit, a key), rather than time. */
    fun needsYou(st: ProviderStatus?): Boolean = st != null && (st.state == ProviderStatus.NO_CREDIT || st.state == ProviderStatus.BAD_KEY)

    /**
     * What's left under the key's cap, when it may be shown beside [st]. The cap says nothing about
     * the account's own credit: when the account ran out (no_credit, not a budget), "$3.00 left"
     * beside "out of credits" would be untrue, so it isn't shown.
     */
    fun capLeft(b: Balance?, st: ProviderStatus?): Double? =
        b?.remaining?.takeUnless { st?.state == ProviderStatus.NO_CREDIT && !st.budget }

    /**
     * What's left, in a few words: "$4.20 left", "$1.10 today" (no cap), "37 of 50 free today".
     * Null when nothing's known. [st]: the provider's trouble, if any (see [capLeft]).
     */
    fun balance(context: Context, b: Balance?, st: ProviderStatus? = null): String? {
        if (b == null) return null
        val left = capLeft(b, st)
        return when {
            left != null -> context.getString(R.string.balance_left, usd(left))
            b.usedToday != null && b.usedToday > 0.0 -> context.getString(R.string.balance_today, usd(b.usedToday))
            b.freeLeft != null && b.freeLimit != null && b.freeLimit > 0 -> context.getString(R.string.balance_free, b.freeLeft, b.freeLimit)
            b.usedToday != null -> context.getString(R.string.balance_today, usd(b.usedToday))
            else -> null
        }
    }

    /**
     * A provider row's trail: its trouble (amber when it needs the owner), else what's left. The
     * second value: amber.
     */
    fun trail(context: Context, p: Provider, now: Long = System.currentTimeMillis()): Pair<String, Boolean>? {
        val st = p.status
        if (st != null) {
            if (st.state == ProviderStatus.LIMITED && st.resetsAt != null && st.resetsAt > now) {
                return context.getString(R.string.provider_limited_back, AccountWords.clock(context, st.resetsAt, now)) to true
            }
            return state(context, st) to true
        }
        return balance(context, p.balance)?.let { it to false }
    }

    /** After a new key is saved, what the Mac's read of it says: "Key works. $4.20 left.", or "Key refused". Null when it couldn't tell. */
    fun keyVerdict(context: Context, p: Provider): String? = when {
        p.status?.state == ProviderStatus.BAD_KEY || p.balance?.error == "bad_key" -> context.getString(R.string.provider_state_bad_key)
        p.balance != null && p.balance.error == null ->
            balance(context, p.balance)?.let { context.getString(R.string.balance_key_works, it) } ?: context.getString(R.string.balance_key_works_plain)
        else -> null
    }

    /**
     * The provider's trouble as a sentence: "OpenRouter is out of credits", "… refused its key",
     * "… is rate limited", "… isn't answering", "… can't reply" (Home's provider card says the same).
     */
    fun sentence(context: Context, name: String, st: ProviderStatus): String = context.getString(
        when (st.state) {
            ProviderStatus.NO_CREDIT -> if (st.budget) R.string.provider_card_budget else R.string.provider_card_no_credit
            ProviderStatus.BAD_KEY -> R.string.provider_is_bad_key
            ProviderStatus.LIMITED -> R.string.provider_card_limited
            ProviderStatus.DOWN -> R.string.provider_card_down
            else -> R.string.provider_card_other
        },
        name,
    )

    /** "OpenRouter is out of credits": the blocked card's title. */
    fun title(context: Context, name: String, st: ProviderStatus?): String =
        if (st == null) context.getString(R.string.blocked_provider, name) else sentence(context, name, st)

    /**
     * A session's activity line while its provider stops it, a short phrase: "OpenRouter is out of
     * credits", "OpenRouter carries on by itself at 14:05", "OpenRouter is ready to continue".
     */
    fun activity(context: Context, b: Blocked, provider: Provider?, now: Long = System.currentTimeMillis()): String {
        val name = provider?.name ?: return b.text
        val r = b.resume
        return when {
            r?.armed == true && r.at != null -> context.getString(R.string.activity_provider_auto, name, AccountWords.clock(context, r.at, now))
            r?.ready == true -> context.getString(R.string.activity_provider_ready, name)
            provider.status != null -> sentence(context, name, provider.status)
            else -> context.getString(R.string.activity_provider, name)
        }
    }

    /**
     * The notice for a provider's new trouble, or its credit coming back ([before] → [after]),
     * once per episode (provider and since); null when there's nothing to say. [paused]: sessions
     * on it that can't reply now.
     */
    fun notice(context: Context, before: Provider?, after: Provider, paused: Int, now: Long = System.currentTimeMillis()): Notice? {
        if (after.builtIn) return null
        val r = context.resources
        val st = after.status
        val was = before?.status
        val name = after.name
        if (st == null) {
            // Over, said once, when sessions were waiting on it.
            if (was?.state != ProviderStatus.NO_CREDIT || paused == 0) return null
            if (!episodeNoticed(context, "${after.id}:back:${was.since}")) return null
            // Only a budget's end is the balance saying there's credit: anything else (a reply on a
            // free model, an edited key) just says it answers again.
            val title = if (was.budget) R.string.notice_provider_back_title else R.string.notice_provider_answering_title
            return Notice("provider:${after.id}", Notice.Kind.Limit, null, r.getString(title, name), r.getString(R.string.notice_provider_back))
        }
        if (was != null && was.state == st.state && was.since == st.since) return null
        // Old news (a reconnect bringing an episode long begun) says nothing.
        if (st.since != null && now - st.since > 10 * 60_000L) return null
        if (!worthNotice(st, now)) return null
        if (!episodeNoticed(context, "${after.id}:${st.state}:${st.since}")) return null
        val n = { kind: Notice.Kind, title: String, text: String -> Notice("provider:${after.id}", kind, null, title, text) }
        return when (st.state) {
            ProviderStatus.NO_CREDIT -> n(
                Notice.Kind.Account,
                r.getString(if (st.budget) R.string.notice_provider_budget_title else R.string.notice_provider_no_credit_title, name),
                if (paused > 0) r.getQuantityString(R.plurals.notice_provider_no_credit, paused, paused) else r.getString(R.string.notice_provider_no_credit_none),
            )
            ProviderStatus.BAD_KEY -> n(Notice.Kind.Account, r.getString(R.string.notice_provider_bad_key_title, name), r.getString(R.string.notice_provider_bad_key))
            ProviderStatus.LIMITED -> n(
                Notice.Kind.Limit,
                r.getString(R.string.notice_provider_limited_title, name),
                st.resetsAt?.let { r.getString(R.string.notice_provider_limited, AccountWords.atClock(context, it, now)) } ?: r.getString(R.string.notice_provider_limited_unknown),
            )
            // Not answering comes and goes: the card says so, no notification.
            else -> null
        }
    }

    /**
     * A rate limit over within a few minutes carries on by itself, and OpenRouter's per-minute
     * free-model limit starts a new one every minute: no notification for those. One with no
     * reset (Try again) or a longer wait still says so.
     */
    fun worthNotice(st: ProviderStatus, now: Long): Boolean =
        st.state != ProviderStatus.LIMITED || st.resetsAt == null || st.resetsAt - now > SHORT_LIMIT_MS

    private const val SHORT_LIMIT_MS = 5 * 60_000L

    /** True the first time it's asked about this episode (remembered on the phone, the last 20). */
    private fun episodeNoticed(context: Context, episode: String): Boolean {
        val prefs = context.getSharedPreferences("hypernova", Context.MODE_PRIVATE)
        val seen = prefs.getString("providerNoticed", "").orEmpty().split('\n').filter { it.isNotBlank() }
        if (episode in seen) return false
        prefs.edit().putString("providerNoticed", (seen + episode).takeLast(20).joinToString("\n")).apply()
        return true
    }
}
