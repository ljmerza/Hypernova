package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fork: an approval, question or plan still waiting for an answer is the last thing in the
 * transcript (data/Items.kt), under whatever came after it; answered, it's back in its place.
 */
class AsksLastTest {
    private fun ask(id: String, kind: String = "tool", sessionId: String = "s") = WApproval(
        id, sessionId, createdAt = 1L, expiresAt = 600_001L, kind = kind, toolName = "Bash", summary = "Bash: ls",
        input = mapOf("command" to "ls"), reason = null, canAlways = true, questions = emptyList(), plan = null,
    )

    private fun build(events: List<WEvent>, approvals: Map<String, WApproval>, resolved: Map<String, Resolved> = emptyMap()) =
        Items.build(events, approvals, resolved, emptyMap(), "s", "/p", now = 10_000L, settled = true).map { it.key }

    private val prompt = WEvent.User("u", 1_000L, "go")
    private val later = WEvent.Assistant("r", 3_000L, "meanwhile", false)
    private val otherTool = WEvent.Tool("t", 4_000L, "tu", "Read", "Read: a.kt", mapOf("file_path" to "/p/a.kt"), "done", "x", null, 5L, null, "/p/a.kt")

    @Test fun aWaitingAskGoesUnderWhatCameAfterIt() {
        val events = listOf(prompt, WEvent.Approval("e1", 2_000L, "ap1", "Bash: ls", null), later, otherTool)
        assertEquals(listOf("u", "r", "t", "e1"), build(events, mapOf("ap1" to ask("ap1"))))
    }

    @Test fun answeredItIsBackInItsPlace() {
        val events = listOf(prompt, WEvent.Approval("e1", 2_000L, "ap1", "Bash: ls", null), later)
        assertEquals(listOf("u", "e1", "r"), build(events, emptyMap(), mapOf("ap1" to Resolved("allow", "phone"))))
    }

    @Test fun severalWaitingKeepTheirOrderAndOnesStillArrivingComeAfter() {
        val events = listOf(
            prompt, WEvent.Approval("e1", 2_000L, "ap1", "Bash: ls", null), later,
            WEvent.Approval("e2", 5_000L, "ap2", "Bash: ls", null), otherTool,
        )
        val approvals = mapOf("ap1" to ask("ap1"), "ap2" to ask("ap2", kind = "question"), "ap3" to ask("ap3"))
        // ap3's event hasn't come yet: it's drawn from the approval alone, at the very end.
        assertEquals(listOf("u", "r", "t", "e1", "e2", "approval-ap3"), build(events, approvals))
    }
}
