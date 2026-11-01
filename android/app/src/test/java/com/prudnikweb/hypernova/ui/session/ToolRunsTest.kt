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
    private fun mcp(k: String, tool: String = "ha_get_state", target: String = "light.kitchen", server: String = "home-assistant", failed: Boolean = false, running: Boolean = false) =
        Item.Tool(k, tool, target, server = server, failed = failed, running = running)
    private fun bash(k: String, exit: Int? = 0, running: Boolean = false) =
        Item.Terminal(k, "npm test", emptyList(), running = running, exitCode = exit, elapsedMs = 0)
    private fun diff(k: String) = Item.Diff(k, "App.kt", 1, 1, emptyList(), verb = "Edit")

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

    @Test fun aRunKnowsWhenItStillRunsAndSaysItsLatestCall() {
        val run = ToolRuns.rows(listOf(tool("a"), tool("b", "Grep"), thinking("t"), mcp("c", running = true), thinking("t2"))).single() as ChatRow.Run
        assertTrue(run.running)
        assertEquals("ha_get_state · light.kitchen", run.hint)
        val built = ToolRuns.rows(listOf(tool("a"), tool("b"), bash("c", running = true))).single() as ChatRow.Run
        assertEquals("Bash · npm test", built.hint)
    }

    // ─── MCP calls ───

    @Test fun oneOrTwoMcpCallsFold() {
        val one = ToolRuns.rows(listOf(user("u"), mcp("m1"), reply("r")))
        assertEquals(listOf("u", "run:m1", "r"), one.map { it.key })
        assertEquals(1, (one[1] as ChatRow.Run).calls)
        val two = ToolRuns.rows(listOf(mcp("m1"), thinking("t"), mcp("m2", "ha_search", "kitchen")))
        assertEquals(listOf("run:m1"), two.map { it.key })
    }

    @Test fun mcpCallsWithLookupsFoldFromOne() {
        val rows = ToolRuns.rows(listOf(tool("a", "ToolSearch"), mcp("m1"), reply("r")))
        assertEquals(listOf("run:a", "r"), rows.map { it.key })
        assertEquals(listOf("ToolSearch" to 1, "home-assistant" to 1), (rows[0] as ChatRow.Run).verbs)
    }

    @Test fun mcpCallsWithOtherBuiltInsNeedThree() {
        assertTrue(ToolRuns.rows(listOf(mcp("m1"), bash("b"))).all { it is ChatRow.One })
        assertTrue(ToolRuns.rows(listOf(mcp("m1"), bash("b"), tool("w", "Write"))).single() is ChatRow.Run)
    }

    @Test fun builtInRunsStillNeedThree() {
        assertEquals(3, ToolRuns.MIN)
        assertTrue(ToolRuns.rows(listOf(tool("a"), tool("b", "Grep"))).all { it is ChatRow.One })
        assertTrue(ToolRuns.rows(listOf(bash("a"), bash("b"))).all { it is ChatRow.One })
    }

    @Test fun mcpCallsAreNamedByServerWithCounts() {
        val run = ToolRuns.rows(listOf(mcp("m1"), mcp("m2", "ha_search"), mcp("m3", "ha_call_service"), mcp("n1", "nodered_query", server = "nodered"), tool("r"))).single() as ChatRow.Run
        assertEquals(listOf("home-assistant" to 3, "nodered" to 1, "Read" to 1), run.verbs)
    }

    // ─── What never folds away ───

    @Test fun aDiffStandsBetweenTheFoldsAroundIt() {
        val rows = ToolRuns.rows(listOf(tool("a"), tool("b"), tool("c"), diff("d"), tool("e"), tool("f"), tool("g")))
        assertEquals(listOf("run:a", "d", "run:e"), rows.map { it.key })
        assertTrue(rows[1] is ChatRow.One)
    }

    @Test fun aFailedCallStandsOutAndSplitsTheRun() {
        val rows = ToolRuns.rows(listOf(mcp("m1"), mcp("m2", failed = true), mcp("m3")))
        assertEquals(listOf("run:m1", "m2", "run:m3"), rows.map { it.key })
        // A terminal that exited non-zero too; the two calls left on each side are too few to fold.
        val built = ToolRuns.rows(listOf(tool("a"), tool("b"), bash("x", exit = 1), tool("c"), tool("d")))
        assertEquals(listOf("a", "b", "x", "c", "d"), built.map { it.key })
    }

    @Test fun theLiveRunFoldsAndKeepsItsKeyAsItGrows() {
        val before = ToolRuns.rows(listOf(user("u"), mcp("m1", running = true)))
        val after = ToolRuns.rows(listOf(user("u"), mcp("m1"), mcp("m2", "ha_search", "kitchen", running = true)))
        assertEquals("run:m1", before.last().key)
        assertEquals(before.last().key, after.last().key)
        val run = after.last() as ChatRow.Run
        assertTrue(run.running)
        assertEquals("ha_search · kitchen", run.hint)
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
