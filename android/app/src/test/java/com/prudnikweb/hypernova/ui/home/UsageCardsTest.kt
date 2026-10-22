package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ClaudeState
import com.prudnikweb.hypernova.data.CodexState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's usage cards by scenario (ui/home/UsageCards.kt): named only when both agents' show. */
class UsageCardsTest {
    private fun codex(available: Boolean = true, enabled: Boolean = true) = CodexState(available = available, enabled = enabled, connected = true)
    private val claude = ClaudeState(true, "2.1.0")
    private val noClaude = ClaudeState(false, null)

    @Test fun claudeAloneIsUsageAsBefore() {
        for (c in listOf(null, codex(enabled = false), codex(available = false, enabled = false))) {
            for (cl in listOf(null, claude)) {
                val u = UsageCards.of(cl, c)
                assertTrue(u.claude)
                assertFalse(u.codex)
                assertEquals(R.string.usage, u.claudeTitle)
                assertEquals(R.string.usage_name_five, u.claudeFive)
                assertEquals(R.string.usage_name_seven, u.claudeSeven)
            }
        }
    }

    @Test fun bothInUseEachSaysWhoseItIs() {
        val u = UsageCards.of(claude, codex())
        assertTrue(u.claude && u.codex && u.named)
        assertEquals(R.string.usage_claude, u.claudeTitle)
        assertEquals(R.string.cx_usage, u.codexTitle)
        assertEquals(R.string.usage_name_five_claude, u.claudeFive)
        assertEquals(R.string.cx_usage_name_five, u.codexFive)
        assertEquals(R.string.cx_usage_name_seven, u.codexSeven)
        // An older Mac (no claude field) with Codex on is the same.
        assertEquals(u, UsageCards.of(null, codex()))
    }

    @Test fun codexAloneIsUsageAndClaudesCardGoes() {
        for (c in listOf(codex(), codex(enabled = false))) {
            val u = UsageCards.of(noClaude, c)
            assertFalse(u.claude)
            assertTrue(u.codex)
            assertFalse(u.named)
            assertEquals(R.string.usage, u.codexTitle)
            assertEquals(R.string.usage_name_five, u.codexFive)
            assertEquals(R.string.usage_name_seven, u.codexSeven)
        }
    }

    @Test fun neitherAgentKeepsClaudesCard() {
        val u = UsageCards.of(noClaude, null)
        assertTrue(u.claude)
        assertFalse(u.codex)
        assertEquals(R.string.usage, u.claudeTitle)
    }
}
