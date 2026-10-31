package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.WApproval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "Allow these N" (AllowSeveral.kt): only when two tool asks wait, oldest first, each with its
 * command (or a tool's one line) and the agent that asks; questions and plans never count.
 */
class AllowSeveralTest {
    private fun ask(id: String, at: Long, tool: String = "Bash", command: String? = null, summary: String = "$tool: x", kind: String = "tool", agentId: String? = null, agentLabel: String? = null) = WApproval(
        id = id, sessionId = "s", createdAt = at, expiresAt = null, kind = kind, toolName = tool, summary = summary,
        input = if (command != null) mapOf("command" to command) else emptyMap(), reason = null, canAlways = true,
        questions = emptyList(), plan = null, agentId = agentId, agentLabel = agentLabel,
    )

    @Test fun oneAskIsNotSeveral() {
        assertNull(severalOf(emptyList()))
        assertNull(severalOf(listOf(ask("a", 1, command = "npm test"))))
        // A question beside it doesn't make two.
        assertNull(severalOf(listOf(ask("a", 1, command = "npm test"), ask("q", 2, tool = "AskUserQuestion", kind = "question"))))
    }

    @Test fun eachCommandOldestFirstWithItsAgent() {
        val s = severalOf(
            listOf(
                ask("c", 30, command = "rm -rf build", agentId = "a2", agentLabel = "implement:fix"),
                ask("a", 10, command = "npm test"),
                ask("w", 20, tool = "WebFetch", summary = "WebFetch: https://example.com"),
                ask("p", 5, tool = "ExitPlanMode", kind = "plan"),
                ask("n", 40, command = "git status", agentId = "a3"),
            ),
        )!!
        assertEquals(listOf("a", "w", "c", "n"), s.asks.map { it.id })
        assertEquals(listOf("npm test", "https://example.com", "rm -rf build", "git status"), s.asks.map { it.text })
        assertEquals(listOf(null, null, "implement:fix", ""), s.asks.map { it.agent })
        // The plan waits for its own answer.
        assertEquals(1, s.others)
    }
}
