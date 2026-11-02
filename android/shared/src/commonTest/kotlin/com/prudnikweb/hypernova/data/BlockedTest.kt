package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A session's Blocked tab (docs/PROTOCOL.md, "Blocked calls"; data/Blocked.kt): the count on the
 * session, and the list as the Mac sends it, read with defaults; an older Mac reads as nothing blocked.
 */
class BlockedTest {
    private fun session(extra: String) = Wire.session(JSONObject("""{"id":"s1","title":"t","cwd":"/p/alpha","state":"idle"$extra}"""))

    @Test fun theCountIsOnTheSession() {
        assertEquals(3, session(""","blockedCalls":3""").blockedCalls)
        assertEquals(0, session("").blockedCalls)
    }

    @Test fun readsTheListNewestFirst() {
        val list = BlockedWire.list(
            JSONObject(
                """{"blocked":[
                {"id":"b1","sessionId":"s1","tool":"Bash","target":"git push origin main","input":{"command":"git push origin main"},"reason":"auto_mode","why":"Pushes to the default branch","at":1000,"toolUseId":"toolu_1"},
                {"id":"b2","sessionId":"s1","tool":"WebFetch","target":"https://example.com","reason":"away","why":null,"at":2000,"toolUseId":null},
                {"sessionId":"s1","tool":"Bash"}
                ]}""",
            ),
        )
        assertEquals(listOf("b2", "b1"), list.map { it.id })
        assertEquals(BlockedCall.AWAY, list[0].reason)
        assertNull(list[0].why)
        assertEquals("Pushes to the default branch", list[1].why)
        assertEquals("git push origin main", list[1].target)
    }

    @Test fun anOlderMacSendsNone() {
        assertEquals(emptyList<BlockedCall>(), BlockedWire.list(JSONObject("{}")))
    }
}
