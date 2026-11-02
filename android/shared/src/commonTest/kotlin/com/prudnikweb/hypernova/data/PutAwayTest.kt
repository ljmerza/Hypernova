package com.prudnikweb.hypernova.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fork: a banner put away doesn't come straight back for the same session and kind (data/Notices.kt). */
class PutAwayTest {
    private fun n(id: String, kind: Notice.Kind = Notice.Kind.Approval, session: String? = "s1") = Notice(id, kind, session, "title", "text")

    @Test fun theSameKindForTheSameSessionWaitsAWhile() {
        val p = PutAway()
        p.put(n("a:1"), now = 1_000L)
        assertTrue(p.quiet(n("a:2"), now = 5_000L))
        assertFalse(p.quiet(n("a:3"), now = 1_000L + PutAway.QUIET_MS))
    }

    @Test fun anotherKindAnotherSessionOrNoSessionStillShows() {
        val p = PutAway()
        p.put(n("a:1"), now = 1_000L)
        assertFalse(p.quiet(n("q:1", Notice.Kind.Question), now = 2_000L))
        assertFalse(p.quiet(n("a:2", session = "s2"), now = 2_000L))
        p.put(n("battery", session = null), now = 1_000L)
        assertFalse(p.quiet(n("battery", session = null), now = 2_000L))
    }
}
