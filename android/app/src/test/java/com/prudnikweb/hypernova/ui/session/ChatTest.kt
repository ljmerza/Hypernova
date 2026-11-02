package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.TodoStatus
import com.prudnikweb.hypernova.data.ChatRepo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chat's quick wins on the phone (docs/PROTOCOL.md, "Session usage, the to-do list and
 * Rewind"): what's read from the Mac, how a quote and a reply read aloud are made, and money.
 */
class ChatTest {
    @Test fun readsTheUsage() {
        val u = ChatRepo.parseUsage(JSONObject("""{"model":"claude-opus-5-5","modelName":"Opus 5.5","context":{"used":418000,"window":1000000,"pct":42,"exact":false},
            "tokens":{"input":10,"output":20,"cacheRead":300,"cacheWrite":40},"agentTokens":5,"costUsd":7.86,"costExact":false}"""))
        assertEquals("Opus 5.5", u.modelName)
        assertEquals(42, u.contextPct)
        assertFalse(u.contextExact)
        assertEquals(370L, u.tokens)
        assertEquals(7.86, u.costUsd!!, 1e-9)
        // Before the first reply, or on another provider: no context, no cost.
        val none = ChatRepo.parseUsage(JSONObject("""{"model":null,"context":null,"tokens":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0},"costUsd":null,"costExact":false}"""))
        assertNull(none.contextPct)
        assertNull(none.costUsd)
    }

    @Test fun theStripNamesTheModelTheSessionRunsNow() {
        val u = ChatRepo.parseUsage(JSONObject("""{"model":"claude-opus-5-5","modelName":"Opus 5.5","tokens":{}}"""))
        // The usage is about the model the session runs: its own name.
        assertEquals("Opus 5.5", stripModel(u, "claude-opus-5-5-20260101", "default model"))
        assertEquals("Opus 5.5", stripModel(u, "opus", "default model"))
        assertEquals("Opus 5.5", stripModel(u, "default model", "default model"))
        // /model sonnet in its terminal: the session's model until the usage is asked for again.
        assertEquals("claude-sonnet-5-5", stripModel(u, "claude-sonnet-5-5", "default model"))
        assertEquals("sonnet", stripModel(u, "sonnet", "default model"))
        // No usage name: the session's.
        val bare = ChatRepo.parseUsage(JSONObject("""{"tokens":{}}"""))
        assertEquals("opus", stripModel(bare, "opus", "default model"))
    }

    @Test fun readsTheTodoList() {
        val t = ChatRepo.parseTodos(JSONObject("""{"todos":[{"id":"1","text":"Read","active":"Reading","status":"completed"},
            {"id":"2","text":"Fix","active":null,"status":"in_progress"},{"id":"3","text":"Test","status":"pending"},{"id":"4","text":" "}],"updatedAt":5}"""))
        assertEquals(listOf(TodoStatus.Done, TodoStatus.Active, TodoStatus.Pending), t.map { it.status })
        assertNull(t[1].active)
        assertTrue(ChatRepo.parseTodos(JSONObject("""{"todos":[],"updatedAt":null}""")).isEmpty())
    }

    @Test fun quotesLineByLine() {
        assertEquals("> one\n>\n> two\n\n", quoted("one\n\ntwo"))
        val long = quoted("word ".repeat(400), max = 100)
        assertTrue(long.length < 120)
        assertTrue(long.trimEnd().endsWith("…"))
    }

    @Test fun readsARepliesWordsNotItsMarkdown() {
        val md = "## Done\n\nSplit into **billing** and `refunds`, see [the docs](https://x.y).\n\n```kotlin\nval x = 1\n```\n- one\n- two\n| a | b |\n|---|---|\n| 1 | 2 |"
        assertEquals("Done.\nSplit into billing and refunds, see the docs.\none. two. a, b 1, 2", spokenText(md))
    }

    @Test fun money() {
        assertEquals("$0.64", money(0.6449))
        assertEquals("$142", money(142.3))
        assertEquals("<$0.01", money(0.004))
    }
}
