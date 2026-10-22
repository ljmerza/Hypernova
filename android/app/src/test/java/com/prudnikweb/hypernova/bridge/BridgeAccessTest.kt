package com.prudnikweb.hypernova.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who may use the phone bridge (BridgeAccess): only the ping and the ask until the owner allows a
 * Lens; the token agreed by both sides and never sent (only its SHA-256 kept here); every call signed
 * with that key, good once, for this listener, within a minute; revoked at once by Disconnect; asks
 * limited per page and in all, so nothing on the phone can keep the prompt coming or spend Lens's turn.
 */
class BridgeAccessTest {
    private class MemStore : BridgeAccess.Store {
        var saved: List<BridgeAccess.Grant> = emptyList()
        var saves = 0
        override fun load() = saved
        override fun save(grants: List<BridgeAccess.Grant>) { saved = grants; saves++ }
    }

    private var now = 1_800_000_000_000L
    private var seed = 1
    /** Different bytes each call, so ids, keys and nonces differ. */
    private val random: (Int) -> ByteArray = { n -> ByteArray(n) { i -> (seed * 31 + i * 7).toByte() }.also { seed++ } }
    private fun access(store: MemStore = MemStore()) = BridgeAccess(store, clock = { now }, random = random)

    private val page = "http://127.0.0.1:40123"
    private var lenses = 1
    private var nonces = 0

    /** Lens's side of an ask, worked out as data/bridge.ts does it (BridgeCryptoTest holds both to the same numbers). */
    private inner class Lens(n: Int = lenses++) {
        val priv = ByteArray(32) { (n * 13 + it * 3 + 1).toByte() }
        val pub: ByteArray = BridgeCrypto.x25519Public(priv)
        val nonce = ByteArray(16) { (n * 5 + it).toByte() }
        val commit = BridgeCrypto.commitment(nonce)
        lateinit var token: String
        lateinit var code: String

        /** The app's half came: the code to show and the token, worked out here, never sent. */
        fun finish(ok: BridgeAccess.Asked.Ok) {
            val appKey = BridgeCrypto.unb64(ok.appKey)!!
            val appNonce = BridgeCrypto.unb64(ok.nonce)!!
            code = BridgeCrypto.code(pub, appKey, nonce, appNonce)
            token = BridgeCrypto.token(BridgeCrypto.x25519(priv, appKey), pub, appKey, nonce, appNonce)
        }

        val key: ByteArray get() = BridgeCrypto.sha256(token.toByteArray())

        fun sign(listener: String, method: String, path: String, query: String? = null, ts: Long = now, nonce: String = "nonce-${++nonces}-abcdefghij", body: String = BridgeCrypto.EMPTY_BODY): BridgeAccess.Signed =
            BridgeAccess.Signed(BridgeCrypto.kid(key), ts.toString(), nonce, body, BridgeCrypto.signature(key, listener, method, path, BridgeCrypto.canonicalQuery(query), ts.toString(), nonce, body))
    }

    private fun ask(a: BridgeAccess, lens: Lens = Lens(), origin: String = page, name: String? = "Hypernova Lens", version: String? = "0.1.9"): Pair<BridgeAccess.Request, Lens> {
        val ok = a.request(origin, name, version, BridgeCrypto.b64(lens.pub), lens.commit, BridgeAccess.PROTOCOL) as BridgeAccess.Asked.Ok
        lens.finish(ok)
        return ok.request to lens
    }

    /** Asked and Lens's nonce shown: the owner sees the question now. */
    private fun shown(a: BridgeAccess, origin: String = page, name: String? = "Hypernova Lens", version: String? = "0.1.9"): Pair<BridgeAccess.Request, Lens> {
        val (r, lens) = ask(a, origin = origin, name = name, version = version)
        val s = (a.reveal(r.id, BridgeCrypto.b64(lens.nonce)) as BridgeAccess.Revealed.Ok).request
        return s to lens
    }

    private fun allowAndCollect(a: BridgeAccess, origin: String = page): Pair<BridgeAccess.Poll.Allowed, Lens> {
        val (r, lens) = shown(a, origin)
        a.answer(r.id, allow = true)
        return (a.poll(r.id) as BridgeAccess.Poll.Allowed) to lens
    }

    /** An ask with a well-formed key and commitment, for the limits (nothing worked out on Lens's side). */
    private fun bare(a: BridgeAccess, origin: String = page): BridgeAccess.Asked {
        val l = Lens(1)
        return a.request(origin, "x", "0.1.9", BridgeCrypto.b64(l.pub), l.commit, BridgeAccess.PROTOCOL)
    }

    // ─── Agreeing access ────────────────────────────────────────────────────

    @Test fun nothingIsShownUntilLensShowsItsNonceThenBothScreensShowOneCode() {
        val a = access()
        val (r, lens) = ask(a)
        assertEquals(BridgeAccess.State.Opened, r.state)
        assertEquals("", r.code)
        assertNull("nothing to show the owner yet", a.pending)
        assertEquals(BridgeAccess.Poll.Pending, a.poll(r.id))
        val shown = (a.reveal(r.id, BridgeCrypto.b64(lens.nonce)) as BridgeAccess.Revealed.Ok).request
        assertEquals(BridgeAccess.State.Pending, shown.state)
        assertTrue(Regex("^\\d{6}$").matches(shown.code))
        assertEquals("the code the glasses show", lens.code, shown.code)
        assertEquals(shown, a.pending)
        assertEquals(BridgeAccess.Poll.Pending, a.poll(r.id))
        assertEquals("only once", BridgeAccess.Revealed.Unknown, a.reveal(r.id, BridgeCrypto.b64(lens.nonce)))
        assertEquals(BridgeAccess.Poll.Unknown, a.poll("not-a-request"))
        assertEquals(BridgeAccess.Revealed.Unknown, a.reveal("not-a-request", BridgeCrypto.b64(lens.nonce)))
    }

    /** Something in between can't swap its own nonce in once it saw the app's half: the ask is over. */
    @Test fun aNonceOtherThanTheCommittedOneEndsTheAsk() {
        val a = access()
        val (r, lens) = ask(a)
        assertEquals(BridgeAccess.Revealed.Mismatch, a.reveal(r.id, BridgeCrypto.b64(ByteArray(16) { 9 })))
        assertEquals(BridgeAccess.Revealed.Unknown, a.reveal(r.id, BridgeCrypto.b64(lens.nonce)))
        assertNull(a.pending)
        assertEquals(BridgeAccess.Poll.Expired, a.poll(r.id))
        now += 1000
        val (r2, _) = ask(a)
        assertEquals(BridgeAccess.Revealed.Mismatch, a.reveal(r2.id, null))
        val (r3, _) = ask(a)
        assertEquals(BridgeAccess.Revealed.Mismatch, a.reveal(r3.id, "not base64!"))
    }

    @Test fun theCodeIsWesternDigitsInAnyLanguage() {
        // A phone in Arabic or Persian formats numbers with its own digits by default; the glasses
        // show 0–9, so the phone must too, or the two codes would never look the same.
        val was = java.util.Locale.getDefault()
        try {
            for (tag in listOf("ar", "fa", "hi-IN-u-nu-deva")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                val (r, _) = shown(access())
                assertTrue("$tag: ${r.code}", Regex("^[0-9]{6}$").matches(r.code))
            }
        } finally {
            java.util.Locale.setDefault(was)
        }
    }

    @Test fun allowSaysSoWithTheAgreedKeyAndOnlyTheTokensHashIsKept() {
        val store = MemStore()
        val a = access(store)
        val (r, lens) = shown(a)
        val answered = a.answer(r.id, allow = true)!!
        assertEquals(BridgeAccess.State.Allowed, answered.state)
        assertNull("answered: no longer waiting", a.pending)
        val got = a.poll(r.id) as BridgeAccess.Poll.Allowed
        // Lens checks this before it keeps the token: made with the key both sides worked out.
        assertEquals(BridgeCrypto.confirmation(lens.key, r.id), got.confirm)
        assertEquals("Hypernova Lens", got.grant.name)
        assertEquals("0.1.9", got.grant.version)
        // Asked again (a poll whose answer was lost): the same word, the same grant, no second one.
        val again = a.poll(r.id) as BridgeAccess.Poll.Allowed
        assertEquals(got.confirm, again.confirm)
        assertEquals(got.grant.id, again.grant.id)
        assertEquals(1, a.grants().size)
        // Only the token's hash is kept: the key calls are signed with.
        assertEquals(1, store.saved.size)
        assertEquals(BridgeAccess.sha256(lens.token), store.saved[0].hash)
        assertFalse(store.saved.toString().contains(lens.token))
        assertFalse(store.saved.toString().contains(lens.token.removePrefix(BridgeAccess.TOKEN_PREFIX)))
        // An answer to a request that isn't waiting any more changes nothing.
        assertNull(a.answer(r.id, allow = false))
    }

    @Test fun anAskWithoutAKeyThatAgreesIsRefusedAndStillSpendsItsTurn() {
        val a = access()
        val l = Lens()
        val b64 = BridgeCrypto.b64(l.pub)
        val P = BridgeAccess.PROTOCOL
        assertEquals(BridgeAccess.Asked.Refused, a.request(page, "x", "0.1.9", null, l.commit, P))
        assertEquals(BridgeAccess.Asked.Refused, a.request(page, "x", "0.1.9", "short", l.commit, P))
        assertEquals(BridgeAccess.Asked.Refused, a.request(page, "x", "0.1.9", b64, null, P))
        assertEquals(BridgeAccess.Asked.Refused, a.request(page, "x", "0.1.9", b64, "not a commitment", P))
        // A Lens of an older protocol (no `bridge` in its ask, or a smaller one): told to update, nobody asked.
        assertEquals(BridgeAccess.Asked.Refused, a.request(page, "x", "0.1.9", b64, l.commit))
        assertEquals(BridgeAccess.Asked.Refused, a.request(page, "x", "0.1.9", b64, l.commit, P - 1))
        // A point of small order agrees on zero, which anyone knows: refused, but counted.
        repeat(BridgeAccess.PER_MINUTE) { assertEquals(BridgeAccess.Asked.Refused, a.request(page, "x", "0.1.9", BridgeCrypto.b64(ByteArray(32)), l.commit, P)) }
        assertTrue(a.request(page, "x", "0.1.9", b64, l.commit, P) is BridgeAccess.Asked.TooMany)
        assertNull(a.pending)
    }

    // ─── Signed calls ───────────────────────────────────────────────────────

    @Test fun aSignedCallIsGoodOnceForThisListenerMethodPathAndQueryWithinAMinute() {
        val a = access()
        val (got, lens) = allowAndCollect(a)
        fun verify(s: BridgeAccess.Signed?, listener: String = "L1", method: String = "GET", path: String = "/v1/snapshot", query: String? = null) =
            a.verify(s, listener, method, path, query)
        val s = lens.sign("L1", "GET", "/v1/snapshot")
        val ok = verify(s) as BridgeAccess.Verdict.Ok
        assertEquals(got.grant.id, ok.grant.id)
        // The answer is sealed under this call's key (BridgeSeal), which Lens works out the same way.
        assertArrayEquals(BridgeCrypto.callKey(lens.key, "L1", s.nonce), ok.sealer.key)
        assertEquals("L1", ok.sealer.listener)
        assertEquals(s.nonce, ok.sealer.nonce)
        assertEquals(BridgeCrypto.EMPTY_BODY, ok.sealer.body)
        // The body is signed too: the same call with another body is another signature.
        val prompt = BridgeCrypto.bodyHash("{\"text\":\"hi\"}".toByteArray())
        val withBody = lens.sign("L1", "POST", "/v1/sessions/s1/prompt", body = prompt)
        assertEquals(BridgeAccess.Verdict.BadSignature, verify(withBody.copy(body = BridgeCrypto.bodyHash("{\"text\":\"rm -rf ~\"}".toByteArray())), method = "POST", path = "/v1/sessions/s1/prompt"))
        assertEquals(prompt, (verify(lens.sign("L1", "POST", "/v1/sessions/s1/prompt", body = prompt), method = "POST", path = "/v1/sessions/s1/prompt") as BridgeAccess.Verdict.Ok).sealer.body)
        assertEquals("the same signature again: a replay", BridgeAccess.Verdict.BadSignature, verify(s))
        assertEquals("another listener (the port was opened again, or someone else held it)", BridgeAccess.Verdict.BadSignature, verify(lens.sign("L0", "GET", "/v1/snapshot")))
        assertEquals(BridgeAccess.Verdict.BadSignature, verify(lens.sign("L1", "GET", "/v1/snapshot"), method = "DELETE"))
        assertEquals(BridgeAccess.Verdict.BadSignature, verify(lens.sign("L1", "GET", "/v1/snapshot"), path = "/v1/sessions"))
        assertEquals(BridgeAccess.Verdict.BadSignature, verify(lens.sign("L1", "GET", "/v1/snapshot", "a=1"), query = "a=2"))
        assertTrue(verify(lens.sign("L1", "GET", "/v1/snapshot", "a=1"), query = "a=1&hnb=whatever") is BridgeAccess.Verdict.Ok)
        assertEquals("a minute late", BridgeAccess.Verdict.BadSignature, verify(lens.sign("L1", "GET", "/v1/snapshot", ts = now - 61_000)))
        assertEquals("a minute early", BridgeAccess.Verdict.BadSignature, verify(lens.sign("L1", "GET", "/v1/snapshot", ts = now + 61_000)))
        assertTrue(verify(lens.sign("L1", "GET", "/v1/snapshot", ts = now - 59_000)) is BridgeAccess.Verdict.Ok)
        val forged = lens.sign("L1", "GET", "/v1/snapshot").let { it.copy(mac = it.mac.reversed()) }
        assertEquals(BridgeAccess.Verdict.BadSignature, verify(forged))
        // A bad signature doesn't use up its nonce: the real one with it still goes.
        val real = lens.sign("L1", "GET", "/v1/snapshot", nonce = "nonce-shared-0001")
        assertEquals(BridgeAccess.Verdict.BadSignature, verify(real.copy(mac = real.mac.reversed())))
        assertTrue(verify(real) is BridgeAccess.Verdict.Ok)
        // No signature, or a key the bridge doesn't know: no grant (Lens asks again).
        assertEquals(BridgeAccess.Verdict.NoGrant, verify(null))
        assertEquals(BridgeAccess.Verdict.NoGrant, verify(Lens().also { it.token = "hnb_never-allowed" }.sign("L1", "GET", "/v1/snapshot")))
    }

    /** The token itself, sent as a bearer or in the query (as Lens did before), opens nothing. */
    @Test fun theTokenItselfOpensNothing() {
        val a = access()
        val (_, lens) = allowAndCollect(a)
        for (r in listOf(
            "GET /v1/snapshot HTTP/1.1\r\nAuthorization: Bearer ${lens.token}\r\n\r\n",
            "GET /v1/ws?token=${lens.token} HTTP/1.1\r\n\r\n",
            "GET /v1/ws?hnb=${lens.token} HTTP/1.1\r\n\r\n",
            "GET /v1/snapshot HTTP/1.1\r\nAuthorization: HNB2 ${lens.token}\r\n\r\n",
        )) {
            val req = BridgeHttp.readHead(java.io.ByteArrayInputStream(r.toByteArray()))!!
            assertEquals(r, BridgeAccess.Verdict.NoGrant, a.verify(BridgeHttp.signed(req), "L1", req.method, req.path, req.query))
        }
        // Signed, through the same parsing: it goes.
        val s = lens.sign("L1", "GET", "/v1/ws", "x=1")
        val req = BridgeHttp.readHead(java.io.ByteArrayInputStream("GET /v1/ws?x=1&hnb=${s.kid}.${s.ts}.${s.nonce}.${s.body}.${s.mac} HTTP/1.1\r\n\r\n".toByteArray()))!!
        assertTrue(a.verify(BridgeHttp.signed(req), "L1", req.method, req.path, req.query) is BridgeAccess.Verdict.Ok)
    }

    /** Before Lens signs anything, the listener proves it holds the key: only for a key it knows. */
    @Test fun thePingProvesAKeyOnlyWhenItHoldsIt() {
        val a = access()
        val (got, lens) = allowAndCollect(a)
        val kid = BridgeCrypto.kid(lens.key)
        assertEquals(BridgeCrypto.pingProof(lens.key, "L1", "challenge-0001-abcd"), a.proof(kid, "L1", "challenge-0001-abcd"))
        assertNotEquals(a.proof(kid, "L1", "challenge-0001-abcd"), a.proof(kid, "L2", "challenge-0001-abcd"))
        assertNull(a.proof("AAAAAAAAAAAAAAAA", "L1", "challenge-0001-abcd"))
        assertNull(a.proof(null, "L1", "challenge-0001-abcd"))
        assertNull("a challenge is a nonce, nothing else", a.proof(kid, "L1", "short"))
        assertNull(a.proof(kid, "L1", "line\nbreak-0001-abcd"))
        a.revoke(got.grant.id)
        assertNull(a.proof(kid, "L1", "challenge-0001-abcd"))
    }

    // ─── Limits ─────────────────────────────────────────────────────────────

    /** A native app can ask with any origin, so a hold on the denied page alone held nothing: after a Deny everyone waits, longer each time. */
    @Test fun denyHoldsOffEveryAskerLongerEachTime() {
        val a = access()
        val (r, _) = shown(a)
        a.answer(r.id, allow = false)
        assertEquals(BridgeAccess.Poll.Denied, a.poll(r.id))
        assertTrue(a.grants().isEmpty())
        val again = bare(a)
        assertTrue(again is BridgeAccess.Asked.TooMany)
        assertTrue((again as BridgeAccess.Asked.TooMany).retryAfterSeconds in 1..30)
        assertTrue("another origin waits too", bare(a, origin = "http://127.0.0.1:5555") is BridgeAccess.Asked.TooMany)
        now += BridgeAccess.AFTER_DENY_MS + 1
        assertTrue(bare(a) is BridgeAccess.Asked.Ok)
        // A second Deny within the hour: twice as long.
        val (r2, _) = shown(a, origin = "http://127.0.0.1:5556")
        a.answer(r2.id, allow = false)
        now += BridgeAccess.AFTER_DENY_MS + 1
        val still = bare(a)
        assertTrue(still is BridgeAccess.Asked.TooMany)
        assertTrue((still as BridgeAccess.Asked.TooMany).retryAfterSeconds in 1..30)
        now += BridgeAccess.AFTER_DENY_MS
        assertTrue(bare(a) is BridgeAccess.Asked.Ok)
        assertEquals(BridgeAccess.MAX_DENY_HOLD_MS, BridgeAccess.holdAfterDeny(30))
        assertEquals(0L, BridgeAccess.holdAfterDeny(0))
        // An hour without a Deny forgets the count.
        now += 3_600_000
        val (r3, _) = shown(a)
        a.answer(r3.id, allow = false)
        now += BridgeAccess.AFTER_DENY_MS + 1
        assertTrue(bare(a) is BridgeAccess.Asked.Ok)
    }

    /** The pairing changed (or the Mac forgot the glasses): an Allow given under it never becomes a grant under the next. */
    @Test fun revokeAllEndsAsksUnderWayToo() {
        val a = access()
        val (shownOne, _) = shown(a)
        val (allowed, _) = shown(a, origin = "http://127.0.0.1:5557")
        a.answer(allowed.id, allow = true)
        assertTrue(a.revokeAll().isEmpty())
        assertNull(a.pending)
        assertEquals(BridgeAccess.Poll.Expired, a.poll(shownOne.id))
        assertEquals(BridgeAccess.Poll.Expired, a.poll(allowed.id))
        assertTrue(a.grants().isEmpty())
    }

    @Test fun asksAreLimitedPerPage() {
        val a = access()
        repeat(BridgeAccess.PER_MINUTE) { assertTrue(bare(a) is BridgeAccess.Asked.Ok); now += 1000 }
        val busy = bare(a)
        assertTrue(busy is BridgeAccess.Asked.TooMany)
        assertTrue((busy as BridgeAccess.Asked.TooMany).retryAfterSeconds in 1..60)
        // Another page isn't held up by this one's asks.
        assertTrue(bare(a, origin = "http://127.0.0.1:41000") is BridgeAccess.Asked.Ok)
        // A minute on, asks go through again, until the hour's limit.
        var ok = BridgeAccess.PER_MINUTE
        repeat(40) {
            now += 21_000
            if (bare(a) is BridgeAccess.Asked.Ok) ok++
        }
        assertEquals(BridgeAccess.PER_HOUR, ok)
        now += 3_600_000
        assertTrue(bare(a) is BridgeAccess.Asked.Ok)
    }

    /** Pages on many ports (one app making up origins) still share one budget: the owner isn't flooded. */
    @Test fun asksAreLimitedInAllToo() {
        val a = access()
        var port = 30000
        repeat(BridgeAccess.ALL_PER_MINUTE) { assertTrue(bare(a, "http://127.0.0.1:${port++}") is BridgeAccess.Asked.Ok) }
        assertTrue(bare(a, "http://127.0.0.1:${port++}") is BridgeAccess.Asked.TooMany)
        var ok = BridgeAccess.ALL_PER_MINUTE
        repeat(200) {
            now += 11_000
            if (bare(a, "http://127.0.0.1:${port++}") is BridgeAccess.Asked.Ok) ok++
        }
        // Over 200 × 11 s, a little over 36 minutes: the hour's limit holds.
        assertEquals(BridgeAccess.ALL_PER_HOUR, ok)
    }

    @Test fun aNewerAskReplacesOneShown() {
        val a = access()
        val (first, _) = shown(a)
        now += 1000
        val (second, _) = shown(a)
        assertNotEquals(first.id, second.id)
        assertNotEquals("the code changes with it", first.code, second.code)
        assertEquals(second.id, a.pending?.id)
        assertEquals(BridgeAccess.Poll.Expired, a.poll(first.id))
        assertNull("the owner can't allow the replaced one", a.answer(first.id, allow = true))
    }

    /** Other pages' asks can't push out the one the owner allowed before Lens collected it. */
    @Test fun anAllowedAskOutlastsOthersAsks() {
        val a = access()
        val (r, lens) = shown(a)
        a.answer(r.id, allow = true)
        var port = 30000
        repeat(12) { now += 11_000; assertTrue(bare(a, "http://127.0.0.1:${port++}") is BridgeAccess.Asked.Ok) }
        val got = a.poll(r.id) as BridgeAccess.Poll.Allowed
        assertEquals(BridgeCrypto.confirmation(lens.key, r.id), got.confirm)
    }

    @Test fun unansweredAsksAndUncollectedAllowsRunOut() {
        val a = access()
        // Never finished by Lens.
        val (opened, _) = ask(a)
        now += BridgeAccess.REQUEST_TTL_MS + 1
        assertEquals(BridgeAccess.Poll.Expired, a.poll(opened.id))
        // Shown, never answered.
        val (r, _) = shown(a)
        now += BridgeAccess.REQUEST_TTL_MS + 1
        assertNull(a.pending)
        assertEquals(BridgeAccess.Poll.Expired, a.poll(r.id))
        assertNull(a.answer(r.id, allow = true))
        // Allowed in the app while the page that asked was paused: still there when it comes back.
        val (r2, _) = shown(a)
        a.answer(r2.id, allow = true)
        now += BridgeAccess.REQUEST_TTL_MS + 60_000
        assertTrue(a.poll(r2.id) is BridgeAccess.Poll.Allowed)
        // But not for ever: an allow never collected leaves no grant.
        val (r3, _) = shown(a)
        a.answer(r3.id, allow = true)
        now += BridgeAccess.COLLECT_TTL_MS + 1
        assertEquals(BridgeAccess.Poll.Expired, a.poll(r3.id))
        assertEquals(1, a.grants().size)
    }

    // ─── Grants ─────────────────────────────────────────────────────────────

    @Test fun disconnectRevokesAtOnce() {
        val a = access()
        val (one, l1) = allowAndCollect(a)
        now += 61_000
        val (two, l2) = allowAndCollect(a)
        assertEquals(2, a.grants().size)
        assertEquals(one.grant.id, a.revoke(one.grant.id)?.id)
        assertEquals(BridgeAccess.Verdict.NoGrant, a.verify(l1.sign("L", "GET", "/v1/x"), "L", "GET", "/v1/x", null))
        assertTrue(a.verify(l2.sign("L", "GET", "/v1/x"), "L", "GET", "/v1/x", null) is BridgeAccess.Verdict.Ok)
        assertNull("revoking twice", a.revoke(one.grant.id))
        assertFalse(a.has(one.grant.id))
        assertTrue(a.has(two.grant.id))
        assertEquals(1, a.revokeAll().size)
        assertFalse(a.has(two.grant.id))
        assertEquals(BridgeAccess.Verdict.NoGrant, a.verify(l2.sign("L", "GET", "/v1/x"), "L", "GET", "/v1/x", null))
        assertTrue(a.revokeAll().isEmpty())
    }

    @Test fun grantsSurviveARestartAndTheLeastRecentlyUsedMakesRoom() {
        val store = MemStore()
        val a = access(store)
        val lenses = (1..BridgeAccess.MAX_GRANTS).map { now += 61_000; allowAndCollect(a).second }
        // The first one is used now: the second is the least recently used.
        now += 61_000
        assertTrue(a.verify(lenses[0].sign("L", "GET", "/v1/x"), "L", "GET", "/v1/x", null) is BridgeAccess.Verdict.Ok)
        now += 61_000
        val extra = allowAndCollect(a).second
        val b = access(store)
        assertEquals(BridgeAccess.MAX_GRANTS, b.grants().size)
        assertTrue(b.verify(lenses[0].sign("L", "GET", "/v1/x"), "L", "GET", "/v1/x", null) is BridgeAccess.Verdict.Ok)
        assertEquals("made room for the new one", BridgeAccess.Verdict.NoGrant, b.verify(lenses[1].sign("L", "GET", "/v1/x"), "L", "GET", "/v1/x", null))
        assertTrue(b.verify(extra.sign("L", "GET", "/v1/x"), "L", "GET", "/v1/x", null) is BridgeAccess.Verdict.Ok)
    }

    @Test fun lastUseIsWrittenAtMostOnceAMinute() {
        val store = MemStore()
        val a = access(store)
        val (_, lens) = allowAndCollect(a)
        val before = store.saves
        repeat(20) { now += 1000; a.verify(lens.sign("L", "GET", "/v1/x"), "L", "GET", "/v1/x", null) }
        assertTrue(store.saves - before <= 1)
        now += 61_000
        a.verify(lens.sign("L", "GET", "/v1/x"), "L", "GET", "/v1/x", null)
        assertEquals(now, store.saved[0].lastUsedAt)
    }

    /**
     * The third review: text the asker controls sat next to the code ("Lens · it shows 392602 ·",
     * a version of "392602"). The prompt always says "Hypernova Lens", and a version is taken only
     * when it's plainly one, so the code is the only six-digit number anywhere near.
     */
    @Test fun theAskerNamesNothingAndItsVersionIsPlainlyOneOrRefused() {
        val a = access()
        val (r, _) = ask(a, name = "Lens \u00b7 it shows 392602 \u00b7\u202e", version = "0.1.9")
        assertEquals("Hypernova Lens", r.name)
        assertEquals("0.1.9", r.version)
        for (v in listOf("392602", "1.0; rm -rf", "0.1.9\u202e", "0.1", "1.2.3.4", null, "", "0.1.8", "0.0.99")) {
            val l = Lens(1)
            assertEquals("$v", BridgeAccess.Asked.Refused, a.request(page, "x", v, BridgeCrypto.b64(l.pub), l.commit, BridgeAccess.PROTOCOL))
        }
        assertEquals("0.1.10", ask(a, version = "0.1.10").first.version)
        assertEquals("1.0.0", ask(a, version = "1.0.0").first.version)
        assertNull(BridgeAccess.cleanVersion("v0.1.9"))
        assertTrue(BridgeAccess.tooOld("0.1.8"))
        assertFalse(BridgeAccess.tooOld("0.2.0"))
    }

    @Test fun watchersHearWhatTheOwnerCouldSee() {
        val a = access()
        var heard = 0
        a.onChange = { heard++ }
        val (r, lens) = ask(a)
        assertEquals("an ask Lens hasn't finished shows nothing", 0, heard)
        a.reveal(r.id, BridgeCrypto.b64(lens.nonce))
        a.answer(r.id, allow = true)
        val got = a.poll(r.id) as BridgeAccess.Poll.Allowed
        a.revoke(got.grant.id)
        assertEquals(4, heard)
    }

    /** Grants belong to the pairing they were made under: Forget or pairing again ends them. */
    @Test fun grantsFollowThePairingTheyWereMadeUnder() {
        assertTrue("the same pairing", BridgeAccess.stillFor("dev-1", "dev-1"))
        assertFalse("forgotten", BridgeAccess.stillFor("dev-1", null))
        assertFalse("paired again (a new device id)", BridgeAccess.stillFor("dev-1", "dev-2"))
        assertTrue("made before any pairing: the first one takes them", BridgeAccess.stillFor(null, "dev-1"))
        assertTrue("still no pairing", BridgeAccess.stillFor(null, null))
    }

    @Test fun eachNonceIsGoodOnceAndTheirMemoryIsBounded() {
        val n = Nonces(window = 60_000, max = 8)
        val t = 1_000_000L
        assertTrue(n.fresh("a", t, t))
        assertFalse(n.fresh("a", t, t + 59_000))
        repeat(7) { assertTrue(n.fresh("b$it", t, t)) }
        // Full, and none has run out: a new one waits (only a grant's signed calls ever get here).
        assertFalse(n.fresh("c", t, t + 1))
        // Once they've run out, they make room; one that ran out can't be used again in time anyway.
        assertTrue(n.fresh("c", t + 61_000, t + 61_000 + 1))
        assertTrue(n.fresh("a", t + 61_000, t + 61_000 + 1))
    }

    @Test fun onlyPreviewsALensWithAGrantOpenedGoWithoutASignature() {
        var saved: String? = null
        val p = OpenPreviews(clock = { now }, load = { saved }, save = { saved = it })
        assertFalse(p.known("tok-1"))
        assertFalse(p.known(null))
        p.add("tok-1", "g1")
        p.add("tok-2", "g2")
        p.add("bad token!", "g1")
        assertTrue(p.known("tok-1"))
        assertEquals("g1", p.grantOf("tok-1"))
        assertEquals("g2", p.grantOf("tok-2"))
        assertNull(p.grantOf("tok-3"))
        assertFalse(p.known("bad token!"))
        assertFalse(p.known("tok-3"))
        // Kept between runs.
        assertTrue(OpenPreviews(clock = { now }, load = { saved }).known("tok-1"))
        // A grant revoked takes its previews with it.
        p.drop("g1")
        assertFalse(p.known("tok-1"))
        assertTrue(p.known("tok-2"))
        // As long as the Mac keeps a preview, no longer.
        now += OpenPreviews.TTL_MS
        assertFalse(p.known("tok-2"))
        // A few at most: the oldest goes.
        repeat(OpenPreviews.MAX + 1) { p.add("t$it", "g"); now += 1 }
        assertFalse(p.known("t0"))
        assertTrue(p.known("t${OpenPreviews.MAX}"))
        p.clear()
        assertFalse(p.known("t${OpenPreviews.MAX}"))
        assertFalse(OpenPreviews(clock = { now }, load = { saved }).known("t${OpenPreviews.MAX}"))
    }

    // ─── After Allow, and grants nobody uses ───────────────────────────────

    @Test fun anAllowWaitsForTheGlassesTapUntilCollectedOrRunOut() {
        val a = access()
        val (r, _) = shown(a)
        assertFalse(a.collecting())
        a.answer(r.id, allow = true)
        assertTrue("allowed, not collected yet", a.collecting())
        a.poll(r.id)
        assertFalse("collected", a.collecting())
        val (r2, _) = shown(a)
        a.answer(r2.id, allow = true)
        assertTrue(a.collecting())
        now += BridgeAccess.COLLECT_TTL_MS + 1
        assertFalse("run out", a.collecting())
        val (r3, _) = shown(a)
        a.answer(r3.id, allow = false)
        assertFalse("a Deny waits for nothing", a.collecting())
    }

    @Test fun aGrantNobodyUsedFor30DaysGoes() {
        val store = MemStore()
        val a = access(store)
        val (first, lens) = allowAndCollect(a)
        now += 10 * 24 * 3_600_000L
        val (second, _) = allowAndCollect(a)
        assertEquals(2, a.grants().size)
        // The first is used now and then: it stays.
        now += 15 * 24 * 3_600_000L
        val listener = "l1"
        assertTrue(a.verify(lens.sign(listener, "GET", "/v1/snapshot"), listener, "GET", "/v1/snapshot", null) is BridgeAccess.Verdict.Ok)
        // The second, allowed 15 days ago and never used: still there.
        assertEquals(2, a.grants().size)
        now += 16 * 24 * 3_600_000L
        val left = a.grants()
        assertEquals("unused for 31 days, gone", listOf(first.grant.id), left.map { it.id })
        assertEquals("and gone from the store", listOf(first.grant.id), store.saved.map { it.id })
        assertFalse(second.grant.id in store.saved.map { it.id })
    }
}
