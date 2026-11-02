package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Statistics with Codex (docs/PROTOCOL.md, "Statistics"; data/Stats.kt): `byAgent` and each day's
 * `codexMs` are read with defaults, an older Mac's answer reads as Claude's alone, and whose work
 * the screen speaks of follows the rule in [Stats.agents].
 */
class StatsTest {
    private fun parse(extra: String = "", day: String = """{"day":"2026-10-21","claudeMs":3600000,"wallMs":3000000,"tokens":1000,"sessions":2,"prompts":5}""") =
        StatsWire.parse(JSONObject("""{"range":"week","claudeMs":7200000,"tokens":{"input":1,"output":2,"cacheRead":3,"cacheWrite":4},"sessions":3,"days":[$day]$extra}"""))

    @Test fun anOlderMacsAnswerIsClaudesAlone() {
        val s = parse()
        assertNull(s.claude)
        assertNull(s.codex)
        assertEquals(0L, s.days.single().codexMs)
        assertEquals(LocalDate.of(2026, 10, 21), s.days.single().day)
        assertEquals(7_200_000L, s.claudeOwnMs)
        assertEquals(0L, s.codexOwnMs)
        assertEquals(StatsAgents.Claude, s.agents(codexLeads = false))
    }

    @Test fun anOlderServersBackfillTokensAreIgnored() {
        val s = parse(extra = ""","byAgent":{"claude":{"ms":7200000,"tokens":10,"sessions":3,"prompts":5,"toolCalls":4}},"backfill":{"since":100,"hoursSince":500,"sessions":7,"toolCalls":70,"tokens":999999,"tokensSince":300}""")
        assertEquals(10L, s.tokens.total)
        assertEquals(10L, s.claude?.tokens)
        // What it adds that is still taken.
        assertEquals(3 + 7, s.sessions)
        assertEquals(10, s.claude?.sessions)
        assertEquals(4 + 70, s.claude?.toolCalls)
        assertEquals(100L, s.since)
        assertEquals(500L, s.hoursSince)
    }

    @Test fun readsByAgentAndADaysCodexShare() {
        val s = parse(
            extra = ""","byAgent":{"claude":{"ms":5400000,"tokens":9,"sessions":2,"prompts":4,"toolCalls":30},"codex":{"ms":1800000,"tokens":1,"sessions":1,"prompts":2,"toolCalls":7}}""",
            day = """{"day":"2026-10-21","claudeMs":3600000,"codexMs":1200000,"wallMs":3000000,"tokens":1000,"sessions":2,"prompts":5}""",
        )
        val claude = assertNotNullAnd(s.claude)
        val codex = assertNotNullAnd(s.codex)
        assertEquals(5_400_000L, claude.ms)
        assertEquals(9L, claude.tokens)
        assertEquals(2, claude.sessions)
        assertEquals(4, claude.prompts)
        assertEquals(30, claude.toolCalls)
        assertEquals(1_800_000L, codex.ms)
        assertEquals(7, codex.toolCalls)
        assertEquals(1_200_000L, s.days.single().codexMs)
        // The totals stay the Mac's: both agents' already.
        assertEquals(7_200_000L, s.claudeMs)
        assertEquals(5_400_000L, s.claudeOwnMs)
        assertEquals(1_800_000L, s.codexOwnMs)
        assertEquals(StatsAgents.Both, s.agents(codexLeads = false))
    }

    @Test fun missingNumbersInAnAgentAreZero() {
        val s = parse(extra = ""","byAgent":{"claude":{"ms":60000},"codex":{}}""")
        assertEquals(60_000L, s.claude?.ms)
        assertEquals(0, s.claude?.sessions)
        assertEquals(0L, s.codex?.ms)
        assertEquals(0, s.codex?.prompts)
    }

    @Test fun codexInstalledButIdleIsStillClaudesScreen() {
        val s = parse(extra = ""","byAgent":{"claude":{"ms":7200000,"tokens":10,"sessions":3,"prompts":1,"toolCalls":1},"codex":{"ms":0,"tokens":0,"sessions":0,"prompts":0,"toolCalls":0}}""")
        assertEquals(StatsAgents.Claude, s.agents(codexLeads = false))
    }

    @Test fun codexAloneWhereItLeadsIsCodexs() {
        val s = parse(extra = ""","byAgent":{"claude":{"ms":0,"tokens":0,"sessions":0,"prompts":0,"toolCalls":0},"codex":{"ms":7200000,"tokens":10,"sessions":3,"prompts":1,"toolCalls":1}}""")
        assertEquals(StatsAgents.Codex, s.agents(codexLeads = true))
        // With Claude Code on the Mac, Codex's work alone is still split: Claude 0m, Codex 2h.
        assertEquals(StatsAgents.Both, s.agents(codexLeads = false))
    }

    @Test fun aCodexLedMacWithNoWorkYetSpeaksOfCodex() {
        val s = StatsWire.parse(JSONObject("""{"range":"today","claudeMs":0,"byAgent":{"claude":{"ms":0},"codex":{"ms":0}}}"""))
        assertEquals(StatsAgents.Codex, s.agents(codexLeads = true))
        assertEquals(StatsAgents.Claude, s.agents(codexLeads = false))
    }

    @Test fun claudesPastWorkOnACodexLedMacKeepsItsName() {
        // Claude Code uninstalled since: its earlier work still counts as Claude's, beside Codex's.
        val s = parse(extra = ""","byAgent":{"claude":{"ms":3600000,"tokens":5,"sessions":1,"prompts":1,"toolCalls":1},"codex":{"ms":3600000,"tokens":5,"sessions":1,"prompts":1,"toolCalls":1}}""")
        assertEquals(StatsAgents.Both, s.agents(codexLeads = true))
        val old = parse(extra = ""","byAgent":{"claude":{"ms":3600000,"tokens":5,"sessions":1,"prompts":1,"toolCalls":1}}""")
        assertEquals(StatsAgents.Claude, old.agents(codexLeads = true))
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
