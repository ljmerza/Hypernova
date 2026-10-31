package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Home's Restart all (data/OutdatedJobs.kt; docs/PROTOCOL.md, `POST /v1/jobs/respawn`). */
class OutdatedJobsTest {
    private fun job(id: String, runs: String? = "2.1.312", installed: String = "2.1.314", agentJob: String? = "j$id", phase: Phase = Phase.Idle) =
        Session(id, id, id, "p", SessionState.Idle, "claude-opus-5-5", "default", "", 0L, phase = phase, agentJob = agentJob, agentOutdated = runs?.let { OutdatedClaude(it, installed) })

    @Test fun onlyOpenJobsOnAnOlderClaudeCodeAreRestarted() {
        val list = listOf(job("a"), job("b", runs = null), job("c", agentJob = null), job("d", phase = Phase.Ended), job("e"))
        assertEquals(listOf("a", "e"), OutdatedJobs.of(list).map { it.id })
        assertEquals(emptyList<Session>(), OutdatedJobs.of(listOf(job("b", runs = null))))
    }

    /** Swiped away, the notice comes back for another job behind, or once Claude Code updates again; not for the same ones. */
    @Test fun theNoticeComesBackForAnotherJobOrANewerInstall() {
        val two = OutdatedJobs.attention(listOf(job("a"), job("e")))
        assertEquals(two, OutdatedJobs.attention(listOf(job("e"), job("a"))))
        assertNotEquals(two, OutdatedJobs.attention(listOf(job("a"), job("e"), job("f"))))
        assertNotEquals(two, OutdatedJobs.attention(listOf(job("a", installed = "2.1.320"), job("e", installed = "2.1.320"))))
    }

    @Test fun eachJobsAnswerIsRead() {
        val o = JSONObject("""{"results":[{"id":"a","job":"ja","ok":true,"said":"Respawned"},{"id":"e","job":"je","ok":false,"said":"Couldn't respawn je: it isn't running"},{"job":"x","ok":true}]}""")
        assertEquals(listOf(JobRestart("a", true, "Respawned"), JobRestart("e", false, "Couldn't respawn je: it isn't running")), OutdatedJobs.results(o))
        assertEquals(emptyList<JobRestart>(), OutdatedJobs.results(JSONObject("{}")))
    }
}
