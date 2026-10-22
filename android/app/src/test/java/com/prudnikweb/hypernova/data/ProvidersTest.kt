package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Other providers and carrying on after a limit on the phone (docs/PROTOCOL.md, "Providers",
 * "Auto-continue"): what's read from the Mac, and which blocks still show.
 */
class ProvidersTest {
    private val now = 1_800_000_000_000L
    private val account = Account()
    private fun session(b: Blocked, providerId: String = "anthropic") =
        Session("a3", "s1", "t", "p", SessionState.Idle, "opus", "default", "", now, providerId = providerId, blocked = b)

    @Test fun readsTheResumeTheBalanceAndTheStatus() {
        val r = ProviderWire.resume(JSONObject("""{"state":"armed","at":1800000060000,"by":"claude","canCancel":true,"canArm":false,"why":null}"""))!!
        assertTrue(r.armed)
        assertTrue(r.byClaude)
        assertEquals(1_800_000_060_000L, r.at)
        assertNull(ProviderWire.resume(JSONObject("""{"at":1}""")))
        val b = ProviderWire.balance(JSONObject("""{"remaining":4.2,"usedToday":0.31,"freeLeft":37,"freeLimit":50,"checkedAt":5,"error":null}"""))!!
        assertEquals(4.2, b.remaining!!, 1e-9)
        assertEquals(37, b.freeLeft)
        assertNull(b.error)
        // No cap: what's left isn't known.
        assertNull(ProviderWire.balance(JSONObject("""{"remaining":null,"usedToday":1.1,"checkedAt":5}"""))!!.remaining)
        val st = ProviderWire.status(JSONObject("""{"state":"no_credit","text":"x","since":3,"resetsAt":null,"budget":true}"""))!!
        assertTrue(st.budget)
        assertNull(st.resetsAt)
        // An older Mac's status, without the new fields.
        assertFalse(ProviderWire.status(JSONObject("""{"state":"limited","text":"x","since":3}"""))!!.budget)
        assertEquals("$4.20", ProviderWords.usd(4.2))
        assertEquals("$0.00", ProviderWords.usd(-0.001))
    }

    @Test fun aBlockStaysWhileItCarriesOnByItselfOrWaitsForContinue() {
        val past = now - 60_000
        // Its reset passed: without a resume it no longer stops anything.
        assertNull(session(Blocked(Blocked.LIMIT, "x", past)).activeBlock(account, emptyList(), now))
        // Armed (it carries on a moment after the reset), or ready (Continue): it stays.
        val armed = Blocked(Blocked.LIMIT, "x", past, now + 30_000, Resume(Resume.ARMED, now + 30_000, false, true, false, null))
        assertNotNull(session(armed).activeBlock(account, emptyList(), now))
        val ready = Blocked(Blocked.PROVIDER, "API Error: 402", null, null, Resume(Resume.READY, past, false, false, false, "back"))
        val or = Provider("or", "OpenRouter", "openrouter", null, true, ProviderModels(), builtIn = false)
        assertNotNull(session(ready, "or").activeBlock(account, listOf(or), now))
        // Another provider's block without a resume lasts as its trouble does.
        val credits = Blocked(Blocked.PROVIDER, "API Error: 402")
        assertNull(session(credits, "or").activeBlock(account, listOf(or), now))
        val out = or.copy(status = ProviderStatus(ProviderStatus.NO_CREDIT, "x", past))
        assertNotNull(session(credits, "or").activeBlock(account, listOf(out), now))
        assertTrue(ProviderWords.needsYou(out.status))
        assertFalse(ProviderWords.needsYou(ProviderStatus(ProviderStatus.LIMITED, "x", past)))
    }

    @Test fun theKeysCapIsNeverShownAsTheAccountsCredit() {
        val capped = Balance(3.0, 0.4, null, null, now, null)
        // The account ran out: the key's cap says nothing about it.
        assertNull(ProviderWords.capLeft(capped, ProviderStatus(ProviderStatus.NO_CREDIT, "x", now)))
        // The key's own budget ran out: the cap is the very thing.
        assertEquals(3.0, ProviderWords.capLeft(capped, ProviderStatus(ProviderStatus.NO_CREDIT, "x", now, budget = true))!!, 1e-9)
        // No trouble, or another kind: what's left under the cap.
        assertEquals(3.0, ProviderWords.capLeft(capped, null)!!, 1e-9)
        assertEquals(3.0, ProviderWords.capLeft(capped, ProviderStatus(ProviderStatus.LIMITED, "x", now))!!, 1e-9)
    }

    @Test fun aShortRateLimitSendsNoNotification() {
        // OpenRouter's per-minute free-model limit: it carries on by itself in a minute.
        assertFalse(ProviderWords.worthNotice(ProviderStatus(ProviderStatus.LIMITED, "x", now, now + 60_000), now))
        assertTrue(ProviderWords.worthNotice(ProviderStatus(ProviderStatus.LIMITED, "x", now, now + 3600_000), now))
        // No reset given: the owner has to try again.
        assertTrue(ProviderWords.worthNotice(ProviderStatus(ProviderStatus.LIMITED, "x", now), now))
        assertTrue(ProviderWords.worthNotice(ProviderStatus(ProviderStatus.NO_CREDIT, "x", now), now))
    }

    @Test fun sessionsOnOtherProvidersAreTheCompanionStars() {
        assertTrue(session(Blocked(Blocked.LIMIT, "x"), "or").onOtherProvider)
        assertFalse(session(Blocked(Blocked.LIMIT, "x")).onOtherProvider)
    }
}
