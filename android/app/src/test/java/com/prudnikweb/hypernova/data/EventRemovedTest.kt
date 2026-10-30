package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `{t:"event.removed"}`: the Mac takes back an event it made, the notice that an ask is waiting in
 * the terminal once that ask is answered. The chat drops it, also from a history that was loading
 * when it went (LiveHub's dropEvent and load).
 */
class EventRemovedTest {
    private val notice = WEvent.Notice("n:1", 10L, "Still waiting for your answer below.", "info")
    private val reply = WEvent.Assistant("t:1", 5L, "Running the tests.", false)

    @Test fun theMessageNamesTheSessionAndTheEvent() {
        assertEquals("s1" to "n:1", removedEvent(JSONObject("""{"t":"event.removed","sessionId":"s1","id":"n:1"}""")))
        assertNull(removedEvent(JSONObject("""{"t":"event.removed","id":"n:1"}""")))
        assertNull(removedEvent(JSONObject("""{"t":"event.removed","sessionId":"s1","id":""}""")))
    }

    @Test fun theEventGoesTheRestStays() {
        assertEquals(listOf(reply), withoutEvents(listOf(reply, notice), listOf("n:1")))
    }

    @Test fun nothingToTakeKeepsTheSameList() {
        val list = listOf(reply, notice)
        assertSame(list, withoutEvents(list, listOf("n:2")))
        assertSame(list, withoutEvents(list, emptySet()))
    }
}
