package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Unread replies and "finished 5m ago" on Home's Completed cards (data/Unread.kt). */
class UnreadTest {
    private class Map : KeyValue {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) {
            if (value == null) m.remove(key) else m[key] = value
        }
    }

    private fun s(id: String, at: Long) =
        Session(hex = id, id = id, name = id, project = "p", state = SessionState.Idle, model = "opus", mode = "default", activity = "", updatedAt = at)

    @Test fun aReplyAfterTheLastOpeningIsUnread() {
        assertTrue(Unread.unread(updatedAt = 200, readUpTo = 100, since = 0))
        assertFalse(Unread.unread(updatedAt = 100, readUpTo = 100, since = 0))
        assertFalse(Unread.unread(updatedAt = 90, readUpTo = 100, since = 0))
    }

    @Test fun neverOpenedCountsFromWhenThePhoneStartedKeepingTrack() {
        // The first run: every old job is read; one that finishes after is not.
        assertFalse(Unread.unread(updatedAt = 50, readUpTo = null, since = 100))
        assertTrue(Unread.unread(updatedAt = 150, readUpTo = null, since = 100))
    }

    @Test fun theStoreKeepsSinceFromItsFirstLook() {
        val kv = Map()
        var clock = 1_000L
        val marks = ReadMarks(kv) { clock }
        assertFalse(marks.unread(s("old", 900)))
        assertTrue(marks.unread(s("new", 1_100)))
        // Later looks keep the first time, a new store over the same keys too.
        clock = 5_000L
        assertEquals(1_000L, marks.since())
        assertEquals(1_000L, ReadMarks(kv) { 9_999L }.since())
    }

    @Test fun aRewindMovesTheMarkOnceAndOnlyIfThereWasOne() {
        val marks = ReadMarks(Map()) { 0L }
        assertFalse("never opened: nothing to move", marks.moved("old", "new", 500))
        assertNull(marks.readUpTo("new"))
        marks.mark("old", 200)
        assertTrue(marks.moved("old", "new", 500))
        assertNull(marks.readUpTo("old"))
        assertFalse(marks.unread(s("new", 500)))
        assertFalse("told again", marks.moved("old", "new", 900))
        assertTrue(marks.unread(s("new", 900)))
    }

    @Test fun openingMarksItReadAndNeverBack() {
        val marks = ReadMarks(Map()) { 0L }
        assertNull(marks.readUpTo("a"))
        assertTrue(marks.mark("a", 200))
        assertFalse(marks.unread(s("a", 200)))
        // A later reply: unread again until it's opened.
        assertTrue(marks.unread(s("a", 300)))
        assertFalse(marks.mark("a", 150))
        assertEquals(200L, marks.readUpTo("a"))
        assertTrue(marks.mark("a", 300))
        assertFalse(marks.unread(s("a", 300)))
    }

    @Test fun eachSessionItsOwn() {
        val marks = ReadMarks(Map()) { 0L }
        marks.mark("a", 500)
        assertFalse(marks.unread(s("a", 500)))
        assertTrue(marks.unread(s("b", 500)))
    }

    private val zone = ZoneId.of("America/New_York")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun finishedShort() {
        val now = at(2026, 10, 30, 15)
        assertEquals(Finished.Now, Finished.of(now - 10_000, now, zone))
        assertEquals(Finished.Minutes(1), Finished.of(now - 50_000, now, zone))
        assertEquals(Finished.Minutes(5), Finished.of(now - 5 * 60_000, now, zone))
        assertEquals(Finished.Hours(2), Finished.of(now - 2 * 3_600_000 - 60_000, now, zone))
        // Under a day it's hours, even across midnight.
        assertEquals(Finished.Hours(20), Finished.of(at(2026, 10, 29, 19), now, zone))
        // After that by the calendar.
        assertEquals(Finished.Yesterday, Finished.of(at(2026, 10, 29, 9), now, zone))
        assertEquals(Finished.Days(2), Finished.of(at(2026, 10, 28, 23), now, zone))
        assertEquals(Finished.Days(9), Finished.of(at(2026, 10, 21, 15), now, zone))
        // The Mac's clock a little ahead: just now, not a negative time.
        assertEquals(Finished.Now, Finished.of(now + 5_000, now, zone))
    }
}
