package com.prudnikweb.hypernova.ui.automations

import com.prudnikweb.hypernova.data.Action
import com.prudnikweb.hypernova.data.AutomationInput
import com.prudnikweb.hypernova.data.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which automations cost money, as the Mac counts them: saving one needs the owner's yes. */
class PaidTest {
    private fun cont(target: String, providerId: String? = null) =
        AutomationInput("x", true, false, Trigger.Time("09:00", Trigger.WEEKDAYS), Action.Continue(target, "go on", "notify", providerId), null)
    private val sessions = mapOf("s-or" to "openrouter", "s-cc" to "anthropic", "s-old" to null)
    private val providerOf = { id: String -> sessions[id] }

    @Test fun `a continue of a session on another provider costs money`() {
        assertEquals("openrouter", cont("s-or").paidProvider("anthropic", providerOf))
    }

    @Test fun `a continue of a session on the built-in provider, or one not known here, does not`() {
        assertNull(cont("s-cc").paidProvider("anthropic", providerOf))
        assertNull(cont("s-old").paidProvider("anthropic", providerOf))
        assertNull(cont("gone").paidProvider("anthropic", providerOf))
    }

    @Test fun `the continue's own provider wins over its session's`() {
        assertNull(cont("s-or", providerId = "anthropic").paidProvider("anthropic", providerOf))
        assertEquals("openrouter", cont("s-cc", providerId = "openrouter").paidProvider("anthropic", providerOf))
    }

    @Test fun `a target that is not one session is checked by the Mac when it runs`() {
        for (t in listOf(Action.Continue.TRIGGER, Action.Continue.WORKING, Action.Continue.LIMITED)) assertNull(cont(t).paidProvider("anthropic", providerOf = { "openrouter" }))
    }
}
