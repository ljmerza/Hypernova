package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The provider editor's rules (data/Providers.kt, ProviderForm) and the Check's wire (docs/PROTOCOL.md, "Providers"). */
class ProviderFormTest {
    @Test fun plainHttpToTheInternetIsInsecureButNotOnThisNetworkOrTheTailnet() {
        assertTrue(ProviderForm.insecure("http://gateway.example.com/api"))
        assertTrue(ProviderForm.insecure("HTTP://8.8.8.8:4000"))
        assertFalse(ProviderForm.insecure("https://gateway.example.com/api"))
        assertFalse(ProviderForm.insecure("http://localhost:4000"))
        assertFalse(ProviderForm.insecure("http://127.0.0.1:4000"))
        assertFalse(ProviderForm.insecure("http://192.168.1.20:4000"))
        assertFalse(ProviderForm.insecure("http://10.0.0.5"))
        assertFalse(ProviderForm.insecure("http://172.20.1.1"))
        assertTrue(ProviderForm.insecure("http://172.40.1.1"))
        assertFalse(ProviderForm.insecure("http://100.101.102.103:8080"))
        assertFalse(ProviderForm.insecure("http://studio.tail1234.ts.net:4000"))
        assertFalse(ProviderForm.insecure("http://mac-mini.local:4000"))
        assertFalse(ProviderForm.insecure("http://[::1]:4000"))
        // Not an address yet: nothing to warn about.
        assertFalse(ProviderForm.insecure("http://"))
        assertFalse(ProviderForm.insecure(""))
    }

    @Test fun anAddressNeedsItsScheme() {
        assertTrue(ProviderForm.addressOk("https://openrouter.ai/api"))
        assertTrue(ProviderForm.addressOk(" http://localhost:4000 "))
        assertFalse(ProviderForm.addressOk("openrouter.ai/api"))
        assertFalse(ProviderForm.addressOk("https://"))
    }

    @Test fun refusalsGoUnderTheirField() {
        assertEquals(ProviderField.Name, ProviderForm.field("bad_name"))
        assertEquals(ProviderField.Address, ProviderForm.field("insecure_url"))
        assertEquals(ProviderField.Address, ProviderForm.field("bad_url"))
        assertEquals(ProviderField.Address, ProviderForm.field("missing_url"))
        assertEquals(ProviderField.Key, ProviderForm.field("bad_key_format"))
        assertEquals(ProviderField.Other, ProviderForm.field("no_provider"))
        assertEquals(ProviderField.Other, ProviderForm.field(null))
    }

    @Test fun saveStaysOpenOnARefusedOrDroppedKey() {
        val p = Provider("p1", "OpenRouter", "openrouter", "https://openrouter.ai/api", true, ProviderModels(), false)
        fun c(state: String) = ProviderCheck(state, "", null, null, 1L)
        assertTrue(ProviderForm.staysOpen(ProviderSaved(p, c(ProviderCheck.BAD_KEY), false)))
        assertTrue(ProviderForm.staysOpen(ProviderSaved(p, null, keyDropped = true)))
        assertFalse(ProviderForm.staysOpen(ProviderSaved(p, c(ProviderCheck.OK), false)))
        // Out of credits or unreachable: saved all the same, and said in the toast.
        assertFalse(ProviderForm.staysOpen(ProviderSaved(p, c(ProviderCheck.NO_CREDIT), false)))
        assertFalse(ProviderForm.staysOpen(ProviderSaved(p, null, false)))
    }

    @Test fun deleteCountsWhatStillRunsOnIt() {
        val start = Automation("au_1", "Brief", true, false, Trigger.Time("09:00", Trigger.WEEKDAYS), Action.Start("/p", "x", "p1", null, AutoMode.ALLOWED, emptyList(), Then.NONE), null)
        val move = Automation("au_2", "Switch", true, false, Trigger.Limit(Trigger.FIVE_HOUR), Action.Continue(Action.Continue.LIMITED, "", Then.NONE, "p1"), "switch_provider")
        val other = Automation("au_3", "Mine", true, false, Trigger.Plugged, Action.Start("/p", "x", "anthropic", null, AutoMode.ALLOWED, emptyList(), Then.NONE), null)
        assertEquals(2, ProviderForm.automationsOn("p1", listOf(start, move, other)))
        assertEquals(0, ProviderForm.automationsOn("p2", listOf(start, move, other)))
        val now = 1L
        val s = { id: String, provider: String -> Session("a1", id, "t", "p", SessionState.Idle, "m", "default", "", now, providerId = provider) }
        assertEquals(1, ProviderForm.sessionsOn("p1", listOf(s("s1", "p1"), s("s2", "anthropic"))))
    }

    @Test fun readsTheCheck() {
        val c = ProviderWire.check(JSONObject("""{"state":"ok","text":"Key works. $4.20 left.","models":null,"balance":{"remaining":4.2,"checkedAt":3},"checkedAt":3}"""))!!
        assertTrue(c.ok)
        assertEquals("Key works. $4.20 left.", c.text)
        assertEquals(4.2, c.balance!!.remaining!!, 1e-9)
        assertNull(c.models)
        val custom = ProviderWire.check(JSONObject("""{"state":"ok","text":"Key works. 2 models.","models":["gpt-5","qwen3"],"balance":null,"checkedAt":3}"""))!!
        assertEquals(listOf("gpt-5", "qwen3"), custom.models)
        assertNull(ProviderWire.check(JSONObject("""{"text":"x"}""")))
        assertNull(ProviderWire.check(null))
    }

    @Test fun readsWhatAProviderCosts() {
        fun p(json: String) = Wire.provider(JSONObject(json))
        assertTrue(p("""{"id":"p1","name":"OR","kind":"openrouter","hasKey":true,"builtIn":false}""").paid)
        assertFalse(p("""{"id":"p1","name":"Local","kind":"custom","hasKey":true,"builtIn":false,"paid":false}""").paid)
        assertFalse(p("""{"id":"anthropic","name":"Claude","kind":"anthropic","hasKey":false,"builtIn":true}""").paid)
        // Sent only when said: an edit that doesn't touch it keeps it.
        assertFalse(ProviderInput("x", "custom", "https://a", null, ProviderModels()).toJson().has("paid"))
        assertFalse(ProviderInput("x", "custom", "https://a", null, ProviderModels(), paid = false).toJson().getBoolean("paid"))
    }
}
