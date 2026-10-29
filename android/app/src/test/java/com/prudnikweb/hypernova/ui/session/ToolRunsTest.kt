package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chat's rows (ui/session/ToolRuns.kt): runs of work fold, messages stay as they are. */
class ToolRunsTest {
    private fun user(k: String) = Item.User(k, "hi")
    private fun reply(k: String) = Item.Assistant(k, "ok")
    private fun tool(k: String, verb: String = "Read", failed: Boolean = false, running: Boolean = false) =
        Item.Tool(k, verb, "file.kt", failed = failed, running = running)
    private fun thinking(k: String) = Item.Thinking(k, chars = 10)

    @Test fun threeCallsFoldIntoOneRunWithTheThinkingBetweenThem() {
        val rows = ToolRuns.rows(listOf(user("u"), tool("a"), thinking("t"), tool("b", "Grep"), tool("c"), reply("r")))
        assertEquals(listOf("u", "run:a", "r"), rows.map { it.key })
        val run = rows[1] as ChatRow.Run
        assertEquals(3, run.calls)
        assertEquals(listOf("Read" to 2, "Grep" to 1), run.verbs)
        assertTrue(run.has("t"))
        assertFalse(run.has("r"))
    }

    @Test fun twoCallsStayAsTheyAre() {
        val rows = ToolRuns.rows(listOf(tool("a"), thinking("t"), tool("b"), reply("r")))
        assertEquals(listOf("a", "t", "b", "r"), rows.map { it.key })
        assertTrue(rows.all { it is ChatRow.One })
    }

    @Test fun aRunCountsItsFailuresAndKnowsWhenItStillRuns() {
        val run = ToolRuns.rows(listOf(tool("a"), tool("b", failed = true), tool("c", running = true))).single() as ChatRow.Run
        assertEquals(1, run.failed)
        assertTrue(run.running)
    }

    @Test fun aGrowingRunKeepsItsKey() {
        val before = ToolRuns.rows(listOf(user("u"), tool("a"), tool("b"), tool("c")))
        val after = ToolRuns.rows(listOf(user("u"), tool("a"), tool("b"), tool("c"), tool("d")))
        assertEquals(before.last().key, after.last().key)
        assertEquals(4, (after.last() as ChatRow.Run).calls)
    }

    @Test fun onlyTheFirstReplyAfterEachMessageIsNamed() {
        val items = listOf(reply("r0"), user("u1"), reply("r1"), tool("a"), reply("r2"), user("u2"), reply("r3"))
        assertEquals(setOf("r0", "r1", "r3"), ToolRuns.labelled(items))
    }
}
