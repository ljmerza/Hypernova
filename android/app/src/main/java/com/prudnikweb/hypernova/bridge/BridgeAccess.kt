package com.prudnikweb.hypernova.bridge

import java.util.Base64
import kotlin.math.abs

/**
 * Who may use the phone bridge (docs/PROTOCOL.md, "The phone bridge"). Any app on the phone can
 * reach 127.0.0.1, and any app could hold its port before this one does, so the bridge answers
 * little more than a ping until its owner said yes, and nothing worth stealing ever crosses it:
 *
 * 1. Lens asks (`POST /bridge/request`) with its half of a key agreement ([BridgeCrypto]): its
 *    X25519 key and a commitment to a nonce. The bridge answers with its own key and nonce.
 * 2. Lens shows its nonce ([reveal]); both sides work out the same six-digit code and the same
 *    token, and only now does the owner see "Allow Hypernova Lens to connect?" with that code,
 *    which Lens shows too. The token never travels; only its SHA-256 is kept here.
 * 3. Allow, and Lens's poll gets the bridge's word for it, made with that key ([Poll.Allowed]).
 * 4. Every other call is signed with that key ([verify]) and sealed under a key of its own
 *    ([Sealer], [BridgeSeal]); the ping proves to Lens that this is the app that holds it ([proof]).
 *    Settings → Glasses lists the grants and Disconnect revokes one.
 *
 * Links that are their own key go without (a Telescope picture, a preview this Lens opened:
 * [OpenPreviews]). Asks are limited per page (its origin) and in all, so one page can't keep the
 * prompt coming or spend Lens's turn.
 *
 * Nothing of Android in here (unit-tested): the clock, the random bytes and where grants are kept
 * come in from outside.
 */
class BridgeAccess(
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: (Int) -> ByteArray = { n -> ByteArray(n).also { java.security.SecureRandom().nextBytes(it) } },
) {
    /** Where grants live between runs (the app's preferences; a list in memory in tests). */
    interface Store {
        fun load(): List<Grant>
        fun save(grants: List<Grant>)
    }

    /** A Lens that was allowed: [hash] is its token's SHA-256 (hex), the key its calls are signed with; [id] what Settings revokes it by. */
    data class Grant(val id: String, val hash: String, val name: String, val version: String?, val createdAt: Long, val lastUsedAt: Long)

    enum class State {
        /** Asked, waiting for Lens's nonce: nothing to show the owner yet. */
        Opened,
        /** Shown to the owner with its code. */
        Pending, Allowed, Denied, Expired,
        /** Allowed, and its grant made. */
        Claimed,
    }

    /**
     * One "may I connect?" from Lens: [code] is what both screens show (empty until Lens showed its
     * nonce), so the owner allows the one in front of them. [origin]: the page that asked.
     */
    data class Request(
        val id: String, val code: String, val name: String, val version: String?, val at: Long, val state: State,
        val answeredAt: Long? = null, val origin: String = "",
    )

    /** The secret half of a request, never shown: the agreement's material until the code is made, then the key. */
    private class Exchange(val lensKey: ByteArray, val appKey: ByteArray, val appNonce: ByteArray, val commit: String, var shared: ByteArray?) {
        /** SHA-256 (hex) of the agreed token, once Lens showed its nonce. */
        var keyHash: String? = null
    }

    sealed interface Asked {
        /** The bridge's half of the agreement for Lens: its public key and nonce (base64url). */
        data class Ok(val request: Request, val appKey: String, val nonce: String) : Asked
        /** Too many asks lately (from this page, or in all), or one was just denied: try again in [retryAfterSeconds]. */
        data class TooMany(val retryAfterSeconds: Int) : Asked
        /** No key or commitment that can be one: a Lens from before the agreement, or something else. */
        data object Refused : Asked
    }

    sealed interface Revealed {
        data class Ok(val request: Request) : Revealed
        /** No such request waiting for its nonce (over, already shown, or never). */
        data object Unknown : Revealed
        /** Not the nonce the ask committed to: the request is over. */
        data object Mismatch : Revealed
    }

    sealed interface Poll {
        data object Pending : Poll
        /** Allowed: [confirm] is made with the agreed key, so Lens knows it's this app that allowed it. */
        data class Allowed(val confirm: String, val grant: Grant) : Poll
        data object Denied : Poll
        data object Expired : Poll
        data object Unknown : Poll
    }

    /**
     * A call's signature: `Authorization: HNB2 <kid>.<ts>.<nonce>.<body>.<mac>`, or `?hnb=` on a
     * socket; [body] is the SHA-256 of the body it signs (BridgeCrypto.bodyHash).
     */
    data class Signed(val kid: String, val ts: String, val nonce: String, val body: String, val mac: String) {
        companion object {
            private val FORM = Regex("""^([A-Za-z0-9_-]{16})\.(\d{1,15})\.([A-Za-z0-9_-]{16,64})\.([A-Za-z0-9_-]{43})\.([A-Za-z0-9_-]{43})$""")
            fun parse(s: String?): Signed? = s?.trim()?.let(FORM::find)?.groupValues?.let { Signed(it[1], it[2], it[3], it[4], it[5]) }
        }
    }

    /**
     * One signed call's seal: the key its body opens with and its answer (or its socket's messages)
     * are sealed with (BridgeCrypto.callKey: the grant's key, this listener, the call's nonce, good
     * once). [body]: the hash the call signed, which what it carries must match before it's opened.
     */
    class Sealer internal constructor(val key: ByteArray, val listener: String, val nonce: String, val body: String) {
        private var used: String? = null

        /** A whole request body as Lens sent it (BridgeSeal's stream), opened; [BridgeSeal.Broken] when it isn't one. */
        fun openBody(sealed: ByteArray): ByteArray = BridgeSeal.openStream(key, BridgeSeal.FROM_LENS, sealed)

        /**
         * The one writer of this call's answer, or the one key of its socket's messages ([feedKey]):
         * each counts the app's pieces from 0, so a second of either would seal under an IV used
         * before; by construction there is none (IllegalStateException).
         */
        fun writer(out: java.io.OutputStream): BridgeSeal.Writer {
            once("an answer")
            return BridgeSeal.Writer(out, key, BridgeSeal.FROM_APP)
        }

        /** The key a feed (LiveFeed) seals its messages with, once, and never alongside a sealed answer. */
        fun feedKey(): ByteArray {
            once("a feed")
            return key
        }

        @Synchronized private fun once(what: String) {
            check(used == null) { "this call's key already sealed ${used}; a second ${what} would reuse an IV" }
            used = what
        }
    }

    sealed interface Verdict {
        /** [sealer]: how this call's body opens and its answers are sealed (BridgeSeal). */
        data class Ok(val grant: Grant, val sealer: Sealer) : Verdict
        /** No signature, or one by a key the bridge doesn't know (never allowed, or revoked): 403. */
        data object NoGrant : Verdict
        /** A known key, but not a good signature here and now (another listener, too old, used before): 401. */
        data object BadSignature : Verdict
    }

    private val lock = Any()
    private var grants: List<Grant> = runCatching { store.load() }.getOrDefault(emptyList())
    private val requests = LinkedHashMap<String, Request>()
    private val exchanges = HashMap<String, Exchange>()
    /** When the recent asks came, from each page and in all, for the limits. */
    private val asks = HashMap<String, ArrayDeque<Long>>()
    private val allAsks = ArrayDeque<Long>()
    /** The last Deny, and how many came lately: every asker waits after one, longer each time ([holdAfterDeny]). */
    private var deniedAt = 0L
    private var denies = 0
    /** When each grant's last use was written (it's written at most once a minute). */
    private val savedUse = HashMap<String, Long>()
    private val kids = HashMap<String, String>()
    private val nonces = Nonces(SIGNED_WINDOW_MS)

    /** Hears every change the owner could see: a request coming or going, a grant made or revoked. */
    @Volatile var onChange: (() -> Unit)? = null

    /** The request waiting for the owner's answer, if any (the in-app prompt and the notification show it). */
    val pending: Request?
        get() = synchronized(lock) { expire(); requests.values.lastOrNull { it.state == State.Pending } }

    fun grants(): List<Grant> = synchronized(lock) { prune(); grants.sortedByDescending { it.lastUsedAt } }

    /**
     * An Allow given and not yet collected by Lens: the glasses still need their tap (or Connect in
     * Lens) to finish, within [COLLECT_TTL_MS]. Settings → Glasses says so meanwhile.
     */
    fun collecting(): Boolean = synchronized(lock) { expire(); requests.values.any { it.state == State.Allowed } }

    /**
     * Grants nobody used for [IDLE_GRANT_MS] go (an Even app reinstalled, a second pair of glasses
     * long put away): their Lens asks again, and Settings never lists a row of old copies.
     */
    private fun prune() {
        val now = clock()
        val keep = grants.filter { now - maxOf(it.lastUsedAt, it.createdAt) <= IDLE_GRANT_MS }
        if (keep.size != grants.size) {
            grants = keep
            save()
        }
    }

    /**
     * Lens asks to connect, from the page [origin], with its X25519 key [lensKey] and the commitment
     * [commit] to its nonce (base64url). Limited per page ([PER_MINUTE] a minute, [PER_HOUR] an
     * hour) and in all ([ALL_PER_MINUTE], [ALL_PER_HOUR]), and after a Deny nobody may ask for a
     * while, longer at each Deny within the hour ([holdAfterDeny]: a native app can invent origins,
     * so a hold on the denied page alone held nothing), so nothing on the phone can keep the prompt
     * coming, or spend Lens's turn. [name] is Lens's word for itself and isn't shown (the prompt
     * always says "Hypernova Lens": whatever asks could call itself anything); a [version] that
     * isn't plainly one (`0.1.9`), or is older than the first Lens that speaks this protocol
     * ([MIN_LENS]), is refused with `update_lens`, and so is an ask whose [protocol] (`bridge`) is
     * older than [PROTOCOL].
     */
    fun request(origin: String?, @Suppress("UNUSED_PARAMETER") name: String?, version: String?, lensKey: String?, commit: String?, protocol: Int = 0): Asked {
        val theirs = BridgeCrypto.unb64(lensKey)?.takeIf { it.size == 32 } ?: return Asked.Refused
        val committed = commit?.takeIf { COMMIT.matches(it) } ?: return Asked.Refused
        val v = cleanVersion(version)?.takeIf { !tooOld(it) } ?: return Asked.Refused
        // The protocol Lens speaks (`bridge` in its ask): older than this app's, it's told to update before anyone is asked.
        if (protocol < PROTOCOL) return Asked.Refused
        val page = origin?.trim()?.lowercase()?.take(200).orEmpty()
        synchronized(lock) {
            val now = clock()
            expire()
            // Pages that haven't asked for an hour are forgotten, so the count of pages stays small.
            for (d in asks.values) while (d.isNotEmpty() && now - d.first() >= HOUR_MS) d.removeFirst()
            asks.entries.removeAll { it.value.isEmpty() }
            if (now - deniedAt >= HOUR_MS) denies = 0
            val mine = asks.getOrPut(page) { ArrayDeque() }
            val wait = listOfNotNull(
                (deniedAt + holdAfterDeny(denies) - now).takeIf { denies > 0 && it > 0 },
                wait(mine, now, PER_MINUTE, PER_HOUR),
                wait(allAsks, now, ALL_PER_MINUTE, ALL_PER_HOUR),
            ).maxOrNull()
            if (wait != null && wait > 0) return Asked.TooMany(((wait + 999) / 1000).toInt().coerceAtLeast(1))
            // Counted before the agreement is worked out: an ask whose key agrees on nothing still
            // spends its page's turn, so nothing can keep the bridge working them out.
            mine.addLast(now)
            allAsks.addLast(now)
            val priv = random(32)
            val appKey = BridgeCrypto.x25519Public(priv)
            val shared = BridgeCrypto.x25519(priv, theirs)
            priv.fill(0)
            // A key of small order agrees on zero, which anyone knows.
            if (shared.all { it == 0.toByte() }) return Asked.Refused
            val nonce = random(16)
            val r = Request(id = b64(random(16)), code = "", name = LENS, version = v, at = now, state = State.Opened, origin = page)
            requests[r.id] = r
            exchanges[r.id] = Exchange(theirs, appKey, nonce, committed, shared)
            // A few recent ones are enough to answer their polls. Finished ones make room first, then
            // ones never shown, so other pages' asks can't push out the one the owner is answering or
            // the one Lens is collecting.
            while (requests.size > 8) {
                val gone = requests.values.firstOrNull { it.state == State.Expired || it.state == State.Denied || it.state == State.Claimed }
                    ?: requests.values.firstOrNull { it.state == State.Opened && it.id != r.id }
                    ?: requests.values.first()
                requests.remove(gone.id)
                exchanges.remove(gone.id)?.shared?.fill(0)
            }
            return Asked.Ok(r, BridgeCrypto.b64(appKey), BridgeCrypto.b64(nonce))
        }
    }

    /** How long until [times] has room for one more ask; null when it has now. */
    private fun wait(times: ArrayDeque<Long>, now: Long, perMinute: Int, perHour: Int): Long? {
        while (times.isNotEmpty() && now - times.first() >= HOUR_MS) times.removeFirst()
        val minute = times.filter { now - it < MINUTE_MS }
        return listOfNotNull(
            if (minute.size >= perMinute) minute[minute.size - perMinute] + MINUTE_MS - now else null,
            if (times.size >= perHour) times[times.size - perHour] + HOUR_MS - now else null,
        ).maxOrNull()
    }

    /**
     * Lens shows the nonce its ask committed to: the code and the token are worked out, the token's
     * SHA-256 kept, and the owner is asked. A newer ask replaces one still waiting (Lens reopened
     * meanwhile), so the owner only ever answers the latest.
     */
    fun reveal(id: String, lensNonce: String?): Revealed {
        val shown: Request
        synchronized(lock) {
            expire()
            val r = requests[id]?.takeIf { it.state == State.Opened } ?: return Revealed.Unknown
            val x = exchanges[id] ?: return Revealed.Unknown
            val na = BridgeCrypto.unb64(lensNonce)?.takeIf { it.size in 16..64 }
            if (na == null || !BridgeCrypto.same(BridgeCrypto.commitment(na), x.commit)) {
                requests[id] = r.copy(state = State.Expired)
                exchanges.remove(id)
                return Revealed.Mismatch
            }
            val shared = x.shared ?: return Revealed.Unknown
            val code = BridgeCrypto.code(x.lensKey, x.appKey, na, x.appNonce)
            x.keyHash = sha256(BridgeCrypto.token(shared, x.lensKey, x.appKey, na, x.appNonce))
            shared.fill(0)
            x.shared = null
            val now = clock()
            for ((k, q) in requests.toList()) if (q.state == State.Pending) requests[k] = q.copy(state = State.Expired, answeredAt = now)
            shown = r.copy(code = code, state = State.Pending, at = now)
            requests[id] = shown
        }
        onChange?.invoke()
        return Revealed.Ok(shown)
    }

    /** The owner's answer, from the notification or the app. Null when that request isn't waiting any more. */
    fun answer(id: String, allow: Boolean): Request? {
        val r: Request
        synchronized(lock) {
            expire()
            val cur = requests[id]?.takeIf { it.state == State.Pending } ?: return null
            val now = clock()
            r = cur.copy(state = if (allow) State.Allowed else State.Denied, answeredAt = now)
            requests[id] = r
            if (!allow) {
                if (now - deniedAt >= HOUR_MS) denies = 0
                deniedAt = now
                denies++
            }
        }
        onChange?.invoke()
        return r
    }

    /**
     * Lens asks how its request went. An allowed one makes its grant the first time (one never
     * collected leaves nothing behind), and says so with the agreed key every time; [MAX_GRANTS] at
     * most, the least recently used one going to make room.
     */
    fun poll(id: String): Poll {
        val out: Poll
        synchronized(lock) {
            expire()
            val r = requests[id] ?: return Poll.Unknown
            out = when (r.state) {
                State.Opened, State.Pending -> return Poll.Pending
                State.Denied -> return Poll.Denied
                State.Expired -> return Poll.Expired
                State.Claimed -> {
                    val hash = exchanges[id]?.keyHash ?: return Poll.Expired
                    val g = grants.firstOrNull { it.hash == hash } ?: return Poll.Expired
                    return Poll.Allowed(BridgeCrypto.confirmation(BridgeCrypto.unhex(hash), id), g)
                }
                State.Allowed -> {
                    val hash = exchanges[id]?.keyHash ?: return Poll.Expired
                    val now = clock()
                    val g = Grant(id = b64(random(9)), hash = hash, name = r.name, version = r.version, createdAt = now, lastUsedAt = now)
                    grants = grants.filter { it.hash != hash }.sortedByDescending { it.lastUsedAt }.take(MAX_GRANTS - 1) + g
                    requests[id] = r.copy(state = State.Claimed)
                    save()
                    Poll.Allowed(BridgeCrypto.confirmation(BridgeCrypto.unhex(hash), id), g)
                }
            }
        }
        onChange?.invoke()
        return out
    }

    /**
     * The ping's answer to Lens's challenge [n] for the key named [kid]: that the app listening as
     * [listener] holds it. Null for a key it doesn't know (Lens then asks again).
     */
    fun proof(kid: String?, listener: String, n: String?): String? {
        if (kid == null || n == null || !CHALLENGE.matches(n)) return null
        val g = synchronized(lock) { grants.firstOrNull { kidOf(it.hash) == kid } } ?: return null
        return BridgeCrypto.pingProof(BridgeCrypto.unhex(g.hash), listener, n)
    }

    /**
     * Whose call this is, by its signature [s]: made with a grant's key for this [listener], this
     * [method], [path] and [query] (as sent), within [SIGNED_WINDOW_MS] of now, and with a nonce
     * not seen before. The body it signed ([Signed.body]) is checked as it's read (LensBridge).
     */
    fun verify(s: Signed?, listener: String, method: String, path: String, query: String?): Verdict {
        if (s == null) return Verdict.NoGrant
        synchronized(lock) {
            prune()
            val g = grants.firstOrNull { kidOf(it.hash) == s.kid } ?: return Verdict.NoGrant
            val now = clock()
            val ts = s.ts.toLongOrNull() ?: return Verdict.BadSignature
            if (abs(now - ts) > SIGNED_WINDOW_MS) return Verdict.BadSignature
            val want = BridgeCrypto.signature(BridgeCrypto.unhex(g.hash), listener, method, path, BridgeCrypto.canonicalQuery(query), s.ts, s.nonce, s.body)
            if (!BridgeCrypto.same(want, s.mac)) return Verdict.BadSignature
            if (!nonces.fresh(s.nonce, ts, now)) return Verdict.BadSignature
            val used = g.copy(lastUsedAt = now)
            grants = grants.map { if (it.id == g.id) used else it }
            if (now - (savedUse[g.id] ?: 0L) > MINUTE_MS) {
                savedUse[g.id] = now
                save()
            }
            val key = BridgeCrypto.unhex(g.hash)
            return Verdict.Ok(used, Sealer(BridgeCrypto.callKey(key, listener, s.nonce), listener, s.nonce, s.body))
        }
    }

    private fun kidOf(hash: String): String = kids.getOrPut(hash) { BridgeCrypto.kid(BridgeCrypto.unhex(hash)) }

    /** Disconnect in Settings → Glasses (or Lens leaving by itself): that key stops working at once. */
    fun revoke(grantId: String): Grant? {
        val gone: Grant
        synchronized(lock) {
            gone = grants.firstOrNull { it.id == grantId } ?: return null
            grants = grants - gone
            save()
        }
        onChange?.invoke()
        return gone
    }

    /** Whether [grantId] still stands (a live feed checks again once the Mac has answered, a carried call once it's under way). */
    fun has(grantId: String): Boolean = synchronized(lock) { grants.any { it.id == grantId } }

    /**
     * Every grant at once: the Mac forgot these glasses, or this phone's pairing changed (Forget, or
     * paired again, with this Mac or another; see [stillFor]). Asks under way go too (one waiting
     * for its answer, one allowed and not yet collected): an Allow given under the pairing before
     * mustn't become a grant under the next.
     */
    fun revokeAll(): List<Grant> {
        val gone: List<Grant>
        synchronized(lock) {
            gone = grants
            val now = clock()
            for ((k, r) in requests.toList()) if (r.state == State.Opened || r.state == State.Pending || r.state == State.Allowed) {
                requests[k] = r.copy(state = State.Expired, answeredAt = now)
                exchanges[k]?.shared?.fill(0)
                exchanges.remove(k)
            }
            if (gone.isEmpty()) {
                onChange?.invoke()
                return gone
            }
            grants = emptyList()
            save()
        }
        onChange?.invoke()
        return gone
    }

    /** Requests nobody answered (or finished, or collected) in time are over. */
    private fun expire() {
        val now = clock()
        for ((k, r) in requests.toList()) {
            val over = when (r.state) {
                State.Opened, State.Pending -> now - r.at > REQUEST_TTL_MS
                State.Allowed -> now - (r.answeredAt ?: r.at) > COLLECT_TTL_MS
                else -> false
            }
            if (over) {
                requests[k] = r.copy(state = State.Expired)
                exchanges[k]?.shared?.fill(0)
                exchanges.remove(k)
            }
        }
    }

    private fun save() = runCatching { store.save(grants) }

    companion object {
        const val TOKEN_PREFIX = "hnb_"
        const val MAX_GRANTS = 4
        const val REQUEST_TTL_MS = 120_000L
        /**
         * How long an Allow waits for Lens to collect it. Longer than the ask itself: the owner often
         * allows in this app, and the page that asked may be paused meanwhile (a browser pauses a
         * page in the background) until they go back to it.
         */
        const val COLLECT_TTL_MS = 10 * 60_000L
        /** A grant unused this long goes ([prune]): 30 days. */
        const val IDLE_GRANT_MS = 30 * 24 * 3_600_000L
        /** Asks from one page. */
        const val PER_MINUTE = 3
        const val PER_HOUR = 12
        /** Asks from every page together. */
        const val ALL_PER_MINUTE = 6
        const val ALL_PER_HOUR = 30
        /** How long every asker waits after a Deny: this at the first within an hour, twice as long at each after, up to [MAX_DENY_HOLD_MS]. */
        const val AFTER_DENY_MS = 30_000L
        const val MAX_DENY_HOLD_MS = 10 * 60_000L
        fun holdAfterDeny(denies: Int): Long = if (denies <= 0) 0L else (AFTER_DENY_MS shl minOf(denies - 1, 10)).coerceAtMost(MAX_DENY_HOLD_MS)
        /** What the prompt and Settings call any Lens: whatever asks could call itself anything, so its own word isn't shown. */
        const val LENS = "Hypernova Lens"
        /** The first Lens that signs and seals as this app does; an older one is told to update at its ask. */
        val MIN_LENS = intArrayOf(0, 1, 9)
        /**
         * The bridge's protocol (LensBridge.PROTOCOL): 3 signed and sealed every call; 4 asks for
         * pictures by name in the seal, keeps the glasses' settings out of the unsealed poll, and
         * takes a Lens's `bridge` in its ask. A Lens must say at least this to be asked about.
         */
        const val PROTOCOL = 4
        /** How far a signed call's time may be from now, either side (Lens and the bridge share the phone's clock). */
        const val SIGNED_WINDOW_MS = 60_000L
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
        private val COMMIT = Regex("^[A-Za-z0-9_-]{43}$")
        private val CHALLENGE = Regex("^[A-Za-z0-9_-]{16,64}$")

        /** SHA-256 of [s], hex: what's kept of a token. */
        fun sha256(s: String): String = BridgeCrypto.hex(BridgeCrypto.sha256(s.toByteArray()))
        private fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

        /**
         * A version as Lens gives it, only when it's plainly one: three numbers (`0.1.9`), so it
         * can't be mistaken for the six-digit code, or carry anything else (digits, bidi marks) into
         * the prompt or the Mac's device list. It's never shown next to the code anyway.
         */
        fun cleanVersion(v: String?): String? = v?.trim()?.takeIf { VERSION.matches(it) }

        /** Whether a (clean) version is older than [MIN_LENS]. */
        fun tooOld(v: String): Boolean {
            val n = v.split('.').map { it.toInt() }
            for (i in 0 until 3) if (n[i] != MIN_LENS[i]) return n[i] < MIN_LENS[i]
            return false
        }

        private val VERSION = Regex("""^\d{1,3}(\.\d{1,3}){2}$""")

        /**
         * Whether grants made under the pairing [madeFor] still stand with the pairing [now] (each a
         * key for one pairing, null for none). A Lens is allowed to reach the computer this phone was
         * paired with when the owner said yes: Forget, or pairing again (this Mac or another), ends
         * every grant, and Lens asks again. Grants made while the phone had no pairing go with the
         * first one it gets.
         */
        fun stillFor(madeFor: String?, now: String?): Boolean = madeFor == null || madeFor == now
    }
}

/**
 * The nonces of signed calls lately: each is good once. Only a call with a good signature gets
 * here, so only a Lens with a grant can fill it; a nonce is kept as long as its call's time could
 * still pass, and when [max] are kept and none has run out, a new one waits (the call is refused).
 */
class Nonces(private val window: Long, private val max: Int = 8192) {
    private val seen = HashMap<String, Long>()

    @Synchronized
    fun fresh(nonce: String, ts: Long, now: Long): Boolean {
        if (seen.size >= max / 2) seen.entries.removeAll { now - it.value > window }
        if (nonce in seen || seen.size >= max) return false
        seen[nonce] = ts
        return true
    }
}

/**
 * The previews a Lens with a grant opened through the bridge (`POST /v1/preview`, whose answer names
 * the preview's token), and so the only ones the bridge carries without a signature: a preview's
 * page and its files are loaded by a web view frame, which can't sign. Any other `/p/` link, or
 * preview cookie, gets nothing from the bridge. As long as the Mac keeps a preview (12 hours), [MAX]
 * at most; a grant's go with it. Kept between runs by [save] (the app's preferences).
 */
class OpenPreviews(
    private val clock: () -> Long = System::currentTimeMillis,
    load: () -> String? = { null },
    private val save: (String) -> Unit = {},
) {
    private class Entry(val token: String, val grant: String, val at: Long)

    private val lock = Any()
    private val entries = ArrayList<Entry>()

    init {
        runCatching {
            val a = org.json.JSONArray(load() ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val t = o.optString("t")
                if (TOKEN.matches(t)) entries += Entry(t, o.optString("g"), o.optLong("at"))
            }
        }
    }

    /** A Lens with grant [grantId] opened the preview [token]. */
    fun add(token: String, grantId: String) {
        if (!TOKEN.matches(token)) return
        synchronized(lock) {
            entries.removeAll { it.token == token }
            entries += Entry(token, grantId, clock())
            while (entries.size > MAX) entries.removeAt(0)
            write()
        }
    }

    /** Whether [token] is a preview a Lens with a grant opened here, still current. */
    fun known(token: String?): Boolean = grantOf(token) != null

    /** The grant whose Lens opened the preview [token], while it's current: its streams end with that grant. */
    fun grantOf(token: String?): String? {
        if (token == null) return null
        val now = clock()
        return synchronized(lock) { entries.lastOrNull { it.token == token && now - it.at < TTL_MS }?.grant }
    }

    /** That grant was revoked: its previews go too. */
    fun drop(grantId: String) = synchronized(lock) { if (entries.removeAll { it.grant == grantId }) write() }

    fun clear() = synchronized(lock) { if (entries.isNotEmpty()) { entries.clear(); write() } }

    private fun write() {
        val now = clock()
        entries.removeAll { now - it.at >= TTL_MS }
        val a = org.json.JSONArray()
        for (e in entries) a.put(org.json.JSONObject().put("t", e.token).put("g", e.grant).put("at", e.at))
        runCatching { save(a.toString()) }
    }

    companion object {
        /** As long as the Mac's preview links last. */
        const val TTL_MS = 12 * 3_600_000L
        const val MAX = 32
        private val TOKEN = Regex("""^[\w-]{1,128}$""")
    }
}
