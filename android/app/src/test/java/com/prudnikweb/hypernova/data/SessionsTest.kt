package com.prudnikweb.hypernova.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * After a restart of the Mac (docs/PROTOCOL.md, "After a restart of the Mac"): a session the
 * restart ended says so (`endedBy`), and only while it's ended does Home keep it.
 */
class SessionsTest {
    private fun wire(extra: String) = Wire.session(JSONObject("""{"id":"s1","title":"t","cwd":"/p/alpha","state":"ended"$extra}"""))
    private fun session(phase: Phase, endedBy: String?) =
        Session("s1", "s1", "t", "alpha", SessionState.Idle, "opus", "default", "", 0L, phase = phase, endedBy = endedBy)

    @Test fun readsWhyItEnded() {
        assertEquals(MAC_RESTART, wire(""","endedBy":"mac_restart"""").endedBy)
        // Ended by its owner, or from a Mac too old to say.
        assertNull(wire(""","endedBy":null""").endedBy)
        assertNull(wire("").endedBy)
    }

    @Test fun homeKeepsOnlyWhatTheRestartEnded() {
        assertTrue(session(Phase.Ended, MAC_RESTART).cutByRestart)
        assertFalse(session(Phase.Ended, null).cutByRestart)
        // Running again (a prompt resumed it): an ordinary live session.
        assertFalse(session(Phase.Idle, MAC_RESTART).cutByRestart)
    }
}
