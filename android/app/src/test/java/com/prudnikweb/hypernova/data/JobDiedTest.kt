package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A `claude agents` job that died (data/JobDied.kt; docs/PROTOCOL.md, `{t:"job.died"}`, Session.died). */
class JobDiedTest {
    @Test fun theMessageIsRead() {
        val d = JobDied.parse(JSONObject("""{"t":"job.died","sessionId":"s1","job":"ab12","reason":"exit 1 — boom","at":1700000000000}"""))
        assertEquals(JobDied("s1", "exit 1 — boom", 1700000000000L), d)
        assertEquals(JobDied("s1", null, 0L), JobDied.parse(JSONObject("""{"sessionId":"s1","reason":"  "}""")))
        assertNull(JobDied.parse(JSONObject("""{"reason":"x"}""")))
    }

    /** Said as a session stopping on an error: its kind, the session it opens, why; one id per death. */
    @Test fun theNoticeIsAnErrorThatOpensTheSession() {
        val n = JobDied("s1", "SIGKILL (137)", 5L).notice("Crashy", "fallback")
        assertEquals(Notice.Kind.Error, n.kind)
        assertEquals("s1", n.sessionId)
        assertEquals("Crashy", n.title)
        assertEquals("SIGKILL (137)", n.text)
        assertEquals("fallback", JobDied("s1", null, 5L).notice("Crashy", "fallback").text)
        assertEquals(n.id, JobDied("s1", "other words", 5L).notice("Crashy", "x").id)
        assertTrue(n.id != JobDied("s1", "SIGKILL (137)", 6L).notice("Crashy", "x").id)
    }

    @Test fun theSessionSaysItDied() {
        val base = """"id":"s1","title":"t","cwd":"/w","projectName":"w","origin":"terminal","state":"idle","providerId":"anthropic""""
        assertTrue(Wire.session(JSONObject("""{$base,"died":{"reason":"exit 1"}}""")).died)
        assertTrue(Wire.session(JSONObject("""{$base,"died":{"reason":null}}""")).died)
        assertFalse(Wire.session(JSONObject("""{$base}""")).died)
    }

    private fun job(died: Boolean, state: SessionState = SessionState.Idle, phase: Phase = Phase.of(state), agents: Int = 0) =
        Session("s1", "s1", "t", "p", state, "opus", "default", "", 0L, phase = phase, agentJob = "j1", died = died, agentsRunning = agents)

    /** Only a Completed card says it: not one at work again, waiting on the owner, or gone to History. */
    @Test fun theCardLineIsForACompletedJobThatDied() {
        assertTrue(job(true).diedLine)
        assertFalse(job(false).diedLine)
        assertFalse(job(true, SessionState.Working).diedLine)
        assertFalse(job(true, SessionState.NeedsYou).diedLine)
        assertFalse(job(true, agents = 2).diedLine)
        assertFalse(job(true, phase = Phase.Ended).diedLine)
    }
}
