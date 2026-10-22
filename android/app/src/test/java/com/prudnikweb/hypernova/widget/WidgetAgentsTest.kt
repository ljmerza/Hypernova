package com.prudnikweb.hypernova.widget

import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.ClaudeState
import com.prudnikweb.hypernova.data.CodexAccount
import com.prudnikweb.hypernova.data.CodexState
import com.prudnikweb.hypernova.data.CodexUsage
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.Window
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widgets with Codex as a second agent (widget/WidgetModel.kt): which agent the star follows,
 * the other agent's readout while both are in use, and the model kept on disk, which a build from
 * before Codex wrote without any of it and must load exactly as it did.
 */
class WidgetAgentsTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    /** What toJson wrote before Codex (no leadAgent, codexTrouble, weekOnly, other, nor a row's codex). */
    private val old = """
        {"conn":"Stale","macName":"Studio","updatedAt":1800000000000,"usingCredits":false,"working":1,"needs":1,
         "usage":{"fiveHour":0.42,"sevenDay":0.18,"fiveResetAt":1800007260000,"sevenResetAt":1800219600000,"known":true,"limited":false,"weekLimited":false},
         "rows":[{"id":"c7","title":"release landing copy","project":"0stems","activity":"Bash: npm run deploy","state":"NeedsYou","agents":0},
                 {"id":"a3","title":"cosmos renderer","project":"hypernova","activity":"Bash: ./gradlew build","state":"Working","agents":2}],
         "ask":{"id":"q1","sessionId":"c7","kind":"tool","tool":"Bash","detail":"npm run deploy","project":"0stems","title":"release landing copy","more":0,"whole":true,"expiresAt":1800000540000}}
    """.trimIndent()

    private val oldModel = WidgetModel(
        conn = WidgetModel.Conn.Stale,
        macName = "Studio",
        usage = WidgetModel.UsageView(0.42f, 0.18f, 1_800_007_260_000L, 1_800_219_600_000L, known = true, limited = false, weekLimited = false),
        working = 1,
        needs = 1,
        rows = listOf(
            WidgetModel.Row("c7", "release landing copy", "0stems", "Bash: npm run deploy", SessionState.NeedsYou, 0),
            WidgetModel.Row("a3", "cosmos renderer", "hypernova", "Bash: ./gradlew build", SessionState.Working, 2),
        ),
        ask = WidgetModel.Ask("q1", "c7", "tool", "Bash", "npm run deploy", "0stems", "release landing copy", 0, whole = true, expiresAt = 1_800_000_540_000L),
        updatedAt = 1_800_000_000_000L,
    )

    @Test fun aModelKeptBeforeCodexLoadsAsItWas() {
        val m = WidgetModel.fromJson(JSONObject(old))
        assertEquals(oldModel, m)
        m!!
        assertEquals(AgentKind.CLAUDE, m.leadAgent)
        assertNull(m.codexTrouble)
        assertFalse(m.weekOnly)
        assertNull(m.other)
        assertFalse(m.both)
        assertTrue(m.rows.none { it.codex })
    }

    @Test fun aClaudeOnlyModelWritesWhatItAlwaysDid() {
        // Nothing of Codex's shows on a Mac without it, and nothing of it is kept but its defaults.
        val o = oldModel.toJson()
        assertFalse(o.has("codexTrouble"))
        assertFalse(o.has("other"))
        assertEquals(oldModel, WidgetModel.fromJson(JSONObject(o.toString())))
    }

    @Test fun bothAgentsRoundTrip() {
        val m = oldModel.copy(
            conn = WidgetModel.Conn.Live,
            rows = oldModel.rows + WidgetModel.Row("d2", "lens settings sync", "hypernova", "Edit: lens/src/settings.ts", SessionState.Working, codex = true),
            other = WidgetModel.Other(AgentKind.CODEX, used = 0.61f, resetAt = now + 3 * hour, limited = false),
        )
        assertEquals(m, WidgetModel.fromJson(JSONObject(m.toJson().toString())))
        assertTrue(m.both)
    }

    @Test fun codexLeadingRoundTrips() {
        val m = oldModel.copy(
            leadAgent = AgentKind.CODEX, codexTrouble = WidgetModel.CODEX_SIGNED_OUT, weekOnly = true,
            other = null,
        )
        assertEquals(m, WidgetModel.fromJson(JSONObject(m.toJson().toString())))
        // The other's readout without numbers, and in trouble, survives too.
        val n = oldModel.copy(other = WidgetModel.Other(AgentKind.CODEX, used = null, resetAt = 0L, limited = true, trouble = "Codex is too old."))
        assertEquals(n, WidgetModel.fromJson(JSONObject(n.toJson().toString())))
    }

    @Test fun theOtherReadoutIsFreshAgainPastItsReset() {
        val o = WidgetModel.Other(AgentKind.CODEX, used = 1f, resetAt = now + hour, limited = true)
        assertEquals(o, o.at(now))
        val later = o.at(now + 2 * hour)
        assertNull(later.used)
        assertFalse(later.limited)
        assertEquals(0L, later.resetAt)
        // The model passes it on.
        val m = oldModel.copy(usage = oldModel.usage.copy(fiveResetAt = now + 4 * hour, sevenResetAt = now + 40 * hour), other = o)
        assertNull(m.at(now + 2 * hour).other!!.used)
    }

    // ─── Which agent leads, and the other's readout ─────────────────────────

    private fun codex(
        enabled: Boolean = true, five: Double? = 61.0, week: Double? = 20.0, limited: Boolean = false,
        signedIn: Boolean = true, problem: String? = null,
    ) = CodexState(
        available = true, enabled = enabled, connected = true, version = "0.163.0", problem = problem,
        account = CodexAccount(signedIn, "chatgpt", "plus"),
        usage = CodexUsage(five?.let { Window(it, now + 3 * hour) }, week?.let { Window(it, now + 50 * hour) }, limited, now),
    )

    @Test fun claudeAloneLooksAsBefore() {
        for (claude in listOf(null, ClaudeState(true, "2.1.0"))) {
            val a = WidgetModel.agents(claude, null)
            assertEquals(AgentKind.CLAUDE, a.leadAgent)
            assertNull(a.other)
            assertNull(a.codexTrouble)
            assertFalse(a.weekOnly)
            // Codex installed but not in use: still Claude alone.
            assertNull(WidgetModel.agents(claude, codex(enabled = false)).other)
        }
    }

    @Test fun bothInUseGiveCodexItsReadout() {
        val a = WidgetModel.agents(null, codex())
        assertEquals(AgentKind.CLAUDE, a.leadAgent)
        assertTrue(a.both)
        val o = a.other!!
        assertEquals(AgentKind.CODEX, o.agent)
        assertEquals(0.61f, o.used!!, 0.0001f)
        assertEquals(now + 3 * hour, o.resetAt)
        assertFalse(o.limited)
        assertNull(o.trouble)
        // Claude leads: Codex's trouble is its readout's, never the lead's.
        val out = WidgetModel.agents(ClaudeState(true, null), codex(signedIn = false))
        assertNull(out.codexTrouble)
        assertEquals(WidgetModel.CODEX_SIGNED_OUT, out.other!!.trouble)
    }

    @Test fun aLimitedCodexReadsFullUntilTheWindowItIsLimitedBy() {
        // The week used up: limited until the week's reset, though its 5-hour window isn't full.
        val o = WidgetModel.agents(null, codex(five = 40.0, week = 100.0, limited = true)).other!!
        assertTrue(o.limited)
        assertEquals(now + 50 * hour, o.resetAt)
        // The 5-hour window used up.
        val f = WidgetModel.agents(null, codex(five = 100.0, limited = true)).other!!
        assertTrue(f.limited)
        assertEquals(1f, f.used!!, 0.0001f)
        assertEquals(now + 3 * hour, f.resetAt)
    }

    @Test fun codexLeadsWhereClaudeCodeIsNotInstalled() {
        val none = ClaudeState(false, null)
        val a = WidgetModel.agents(none, codex())
        assertEquals(AgentKind.CODEX, a.leadAgent)
        assertTrue(a.codexLeads)
        // Not two agents: one, and it's Codex. No second readout, no CODEX marks.
        assertNull(a.other)
        assertNull(a.codexTrouble)
        assertFalse(a.weekOnly)
        assertEquals(WidgetModel.CODEX_SIGNED_OUT, WidgetModel.agents(none, codex(signedIn = false)).codexTrouble)
        assertEquals("Codex 0.150.0 is too old for Hypernova.", WidgetModel.agents(none, codex(problem = "Codex 0.150.0 is too old\n for Hypernova.")).codexTrouble)
        // A plan with only the week: the star's window is the week.
        assertTrue(WidgetModel.agents(none, codex(five = null)).weekOnly)
        // Neither agent here: Claude Code's rule stands (an empty sky, as before).
        assertEquals(AgentKind.CLAUDE, WidgetModel.agents(none, null).leadAgent)
    }

    @Test fun theReadoutKeepsWholePercents() {
        val o = WidgetModel.agents(null, codex(five = 61.37)).other
        assertNotNull(o)
        assertEquals(0.61f, o!!.used!!, 0.0001f)
    }
}
