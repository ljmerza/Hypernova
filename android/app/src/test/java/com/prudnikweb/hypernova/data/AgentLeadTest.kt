package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which agent leads (data/AgentLead.kt; docs/PROTOCOL.md, "Which agent leads"): one rule in every client. */
class AgentLeadTest {
    private val now = 1_000_000_000L
    private fun codex(available: Boolean = true, enabled: Boolean = true, usage: CodexUsage? = null, signedIn: Boolean = true) =
        CodexState(available = available, enabled = enabled, connected = true, account = CodexAccount(signedIn, "chatgpt", "plus"), usage = usage)

    @Test fun anOlderMacThatDoesntSayHasClaudeCode() {
        assertTrue(AgentLead.claudeAvailable(null))
        assertFalse(AgentLead.codexLeads(null, codex()))
        assertEquals(AgentKind.CLAUDE, AgentLead.leading(null, codex()))
    }

    @Test fun claudeCodeLeadsWhereverItsInstalled() {
        val claude = ClaudeState(true, "2.1.0")
        assertFalse(AgentLead.codexLeads(claude, codex()))
        assertEquals(AgentKind.CLAUDE, AgentLead.leading(claude, null))
        assertTrue(AgentLead.both(claude, codex()))
        assertFalse(AgentLead.both(claude, codex(enabled = false)))
        assertFalse(AgentLead.both(claude, null))
    }

    @Test fun codexLeadsOnlyWithoutClaudeCode() {
        val none = ClaudeState(false, null)
        assertTrue(AgentLead.codexLeads(none, codex()))
        // Installed but not turned on yet: it still leads (its first session turns it on).
        assertTrue(AgentLead.codexLeads(none, codex(enabled = false)))
        assertEquals(AgentKind.CODEX, AgentLead.leading(none, codex()))
        assertFalse(AgentLead.both(none, codex()))
        // Too old (not available): nothing leads but the rule's fallback, Claude Code.
        assertFalse(AgentLead.codexLeads(none, codex(available = false)))
        assertFalse(AgentLead.codexLeads(none, null))
    }

    @Test fun theSnapshotsClaudeIsParsedForgivingly() {
        assertEquals(ClaudeState(false, null), ClaudeState.parse(JSONObject("""{"available":false,"version":null}""")))
        assertEquals(ClaudeState(true, "2.1.3"), ClaudeState.parse(JSONObject("""{"available":true,"version":"2.1.3"}""")))
        assertNull(ClaudeState.parse(null))
        val snap = Wire.snapshot(JSONObject("""{"mac":{"id":"m","name":"Mac"},"claude":{"available":false,"version":null},"codex":{"available":true,"enabled":true,"connected":true,"min":"0.163.0"}}"""))
        assertEquals(false, snap.claude?.available)
        assertEquals("0.163.0", snap.codex?.min)
        assertNull(Wire.snapshot(JSONObject("""{"mac":{"id":"m"}}""")).claude)
    }

    @Test fun codexsStarIsLitByItsFiveHourWindowElseItsWeek() {
        val five = Window(40.0, now + 3_600_000L)
        val week = Window(70.0, now + 86_400_000L)
        assertEquals(0.4f, AgentLead.codexUsed(CodexUsage(five, week, false, now))!!, 0.001f)
        assertEquals(0.7f, AgentLead.codexUsed(CodexUsage(null, week, false, now))!!, 0.001f)
        assertEquals(7 * 86_400_000L, AgentLead.codexWindowMs(CodexUsage(null, week, false, now)))
        assertNull(AgentLead.codexUsed(CodexUsage(null, null, false, now)))
        assertNull(AgentLead.codexUsed(null))
        // Limited without a window at 100%: the star's own window reads full.
        assertEquals(1f, AgentLead.codexUsed(CodexUsage(five, null, true, now))!!, 0.001f)
    }

    @Test fun whereCodexLeadsTheSunIsCodexsUsage() {
        val u = AgentLead.codexSunUsage(codex(usage = CodexUsage(Window(25.0, now + 3_600_000L), Window(60.0, now + 86_400_000L), false, now)), now)
        assertTrue(u.known)
        assertEquals(0.25f, u.fiveHour, 0.001f)
        assertEquals(0.6f, u.sevenDay, 0.001f)
        assertEquals(now + 3_600_000L, u.fiveHourResetAt)
        assertFalse(u.anyLimited)
        // No pace for Codex.
        assertNull(u.rate)
    }

    @Test fun aUsedUpCodexWindowIsTheSunsLimit() {
        val five = AgentLead.codexSunUsage(codex(usage = CodexUsage(Window(100.0, now + 3_600_000L), Window(60.0, now + 86_400_000L), true, now)), now)
        assertTrue(five.limited)
        assertFalse(five.weekLimited)
        assertEquals(1f, five.fiveHour, 0.001f)
        val week = AgentLead.codexSunUsage(codex(usage = CodexUsage(Window(30.0, now + 3_600_000L), Window(100.0, now + 86_400_000L), true, now)), now)
        assertTrue(week.weekLimited)
        assertFalse(week.limited)
        // Limited, no window at 100%: the 5-hour window is the one spent.
        val said = AgentLead.codexSunUsage(codex(usage = CodexUsage(Window(97.0, now + 3_600_000L), Window(50.0, now + 86_400_000L), true, now)), now)
        assertTrue(said.limited)
        assertEquals(1f, said.fiveHour, 0.001f)
        // A plan with only the week: the disk is the week.
        val weekOnly = AgentLead.codexSunUsage(codex(usage = CodexUsage(null, Window(45.0, now + 86_400_000L), false, now)), now)
        assertEquals(0.45f, weekOnly.fiveHour, 0.001f)
        assertEquals(now + 86_400_000L, weekOnly.fiveHourResetAt)
    }

    @Test fun noNumbersOrSignedOut() {
        assertFalse(AgentLead.codexSunUsage(codex(), now).known)
        assertFalse(AgentLead.codexSunUsage(null, now).known)
        assertTrue(AgentLead.codexSunUsage(codex(signedIn = false, usage = CodexUsage(Window(10.0, now + 1), null, false, now)), now).stale)
    }
}
