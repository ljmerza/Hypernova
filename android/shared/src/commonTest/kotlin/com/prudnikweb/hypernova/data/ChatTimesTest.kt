package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the chat may show as a time (data/ChatTimes.kt): only what really has one. */
class ChatTimesTest {
    private fun tool(id: String, ts: Long) = WEvent.Tool(id, ts, null, "Read", null, emptyMap(), "done", null, null, null, null, null)

    @Test fun anEventWithATimeHasItUnderItsItemsKey() {
        val t = ChatTimes.of(listOf(WEvent.User("u", 1_000L, "hi"), tool("t", 2_000L), WEvent.Notice("n", 3_000L, "note", "info")))
        assertEquals(1_000L, t.at("u"))
        assertEquals(2_000L, t.at("t"))
        assertEquals(3_000L, t.at("n"))
        assertNull(t.at("queued:1"))
    }

    @Test fun aTimeOfZeroIsNoTime() {
        val t = ChatTimes.of(listOf(WEvent.User("u", 0L, "hi")))
        assertNull(t.at("u"))
        assertNull(t.lastMessage)
    }

    @Test fun anEventTheMacSentNoTimeForKeepsThePhonesClockForOrderButShowsNone() {
        val before = System.currentTimeMillis()
        val e = Wire.event(JSONObject().put("id", "u").put("kind", "user").put("text", "hi"))
        assertTrue("still ordered by the phone's clock", e.ts >= before)
        assertTrue(e.tsGuessed)
        val t = ChatTimes.of(listOf(e))
        assertNull(t.at("u"))
        assertNull(t.lastMessage)
    }

    @Test fun everyKindOfEventSaysWhenItsTimeWasGuessedAndNotOtherwise() {
        for (kind in listOf("user", "assistant", "thinking", "tool", "approval", "result", "notice", "something-new")) {
            val o = JSONObject().put("id", "e").put("kind", kind)
            assertTrue(kind, Wire.event(o).tsGuessed)
            assertFalse(kind, Wire.event(o.put("ts", 5_000L)).tsGuessed)
            assertEquals(kind, 5_000L, ChatTimes.of(listOf(Wire.event(o))).at("e"))
        }
    }

    @Test fun theLastMessageIsTheNewestOfTheOwnersAndClaudesNotAToolOrANote() {
        val t = ChatTimes.of(listOf(WEvent.User("u", 1_000L, "hi"), WEvent.Assistant("a", 2_000L, "hello", false), tool("t", 9_000L), WEvent.Notice("n", 9_500L, "note", "info")))
        assertEquals(2_000L, t.lastMessage)
    }

    @Test fun aSideQuestionsLineHasTheTimeItWasAsked() {
        val aside = Aside("x", "why?", Aside.ANSWERED, null, null, false, 3_000L)
        val all = Asides.into(listOf(WEvent.User("u", 1_000L, "hi")), listOf(Asides.Entry(aside, chat = true)))
        val t = ChatTimes.of(all)
        assertEquals(3_000L, t.at("${Asides.KEY}x"))
        assertEquals(3_000L, t.lastMessage)
    }
}
