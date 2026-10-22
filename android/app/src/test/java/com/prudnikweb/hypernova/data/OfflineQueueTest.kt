package com.prudnikweb.hypernova.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prompts typed while the Mac is away (data/OfflineQueue.kt). */
class OfflineQueueTest {
    private class Mem(var json: String? = null) : QueueStore {
        override fun read() = json
        override fun write(json: String) { this.json = json }
    }

    private var t = 1_000L
    private fun queue(store: Mem = Mem()) = OfflineQueue(store) { t++ }
    private val file = Attachment("/m/att/a.png", "a.png", "image/png", 42)

    @Test fun promptsComeOutInTheOrderTheyWereTyped() {
        val q = queue()
        val a = q.add("s1", "first", emptyList())
        val b = q.add("s2", "other session", emptyList())
        val c = q.add("s1", "second", emptyList())
        assertEquals(listOf("first", "second"), q.of("s1").map { it.text })
        // One per session at a time, the oldest session's first.
        assertEquals(listOf(a.id, b.id), q.due().map { it.id })
        assertTrue(q.sending(a.id))
        // s1's second waits for its first; s2 can go.
        assertEquals(listOf(b.id), q.due().map { it.id })
        q.sent(a.id)
        assertEquals(listOf(b.id, c.id), q.due().map { it.id })
    }

    @Test fun eachPromptGoesOnceAndKeepsItsKeyAcrossAFailedTry() {
        val q = queue()
        val a = q.add("s1", "hello", emptyList())
        assertTrue(q.sending(a.id))
        assertFalse("already on its way", q.sending(a.id))
        // On its way it is hidden, and can't be edited or removed.
        assertTrue(q.of("s1").isEmpty())
        assertFalse(q.edit(a.id, "changed"))
        assertFalse(q.remove(a.id))
        q.failed(a.id)
        assertEquals(listOf(a.id), q.of("s1").map { it.id })
        assertEquals(a.id, q.due().single().id)
        assertTrue(q.sending(a.id))
        q.sent(a.id)
        assertTrue(q.all.isEmpty())
        assertFalse(q.sending(a.id))
    }

    @Test fun aSendKeepsTheNameItAlreadyHad() {
        val q = queue()
        val w = q.add("s1", "x", emptyList(), id = "client-123")
        assertEquals("client-123", w.id)
    }

    @Test fun survivesTheAppClosing() {
        val store = Mem()
        val q = queue(store)
        q.add("s1", "keep me", listOf(file), cwd = "/home/p", mode = "default")
        val b = q.add("s1", "and me", emptyList())
        q.markEnded(b.id)
        val again = OfflineQueue(store) { t++ }
        assertEquals(listOf("keep me", "and me"), again.all.map { it.text })
        val first = again.all[0]
        assertEquals(listOf(file), first.files)
        assertEquals("/home/p", first.cwd)
        assertEquals("default", first.mode)
        assertTrue(again.all[1].ended)
        assertFalse(first.ended)
    }

    @Test fun aDamagedStoreReadsAsEmpty() {
        assertTrue(OfflineQueue(Mem("{not json")) { 1 }.all.isEmpty())
        assertTrue(OfflineQueue(Mem("[{\"id\":\"\"},{\"session\":\"s\"}]")) { 1 }.all.isEmpty())
    }

    @Test fun anEditChangesTheWordsButNotTheOrderOrTheFiles() {
        val q = queue()
        val a = q.add("s1", "one", listOf(file))
        q.add("s1", "two", emptyList())
        assertTrue(q.edit(a.id, "  one, better "))
        assertEquals(listOf("one, better", "two"), q.of("s1").map { it.text })
        assertEquals(listOf(file), q.of("s1")[0].files)
        // Words may go if a file stays, but not both.
        assertTrue(q.edit(a.id, ""))
        q.dropFiles(a.id)
        assertFalse(q.edit(a.id, " "))
        assertFalse(q.edit("nope", "x"))
    }

    @Test fun removeTakesItOut() {
        val store = Mem()
        val q = queue(store)
        val a = q.add("s1", "one", emptyList())
        q.add("s1", "two", emptyList())
        assertTrue(q.remove(a.id))
        assertFalse(q.remove(a.id))
        assertEquals(listOf("two"), OfflineQueue(store) { 1 }.all.map { it.text })
    }

    @Test fun aSessionThatEndedKeepsItsPromptAndIsNotSent() {
        val q = queue()
        val a = q.add("gone", "still mine", emptyList(), cwd = "/p")
        val b = q.add("alive", "fine", emptyList())
        q.markEnded(a.id)
        assertEquals(listOf(b.id), q.due().map { it.id })
        assertEquals(0, q.count("gone"))
        assertEquals(1, q.count("alive"))
        assertEquals(listOf(a.id), q.orphans().map { it.id })
        assertEquals(SendStatus.SessionGone, q.get(a.id)!!.item().status)
        assertEquals(SendStatus.WaitingMac, q.get(b.id)!!.item().status)
        // It can still be edited, and removed.
        assertTrue(q.edit(a.id, "reworded"))
        assertTrue(q.remove(a.id))
        assertNull(q.get(a.id))
    }

    @Test fun theBubbleIsTheUsersOwnAndSaysWhatItWaitsFor() {
        val q = queue()
        val w = q.add("s1", "do the thing", listOf(file))
        val item = w.item()
        assertEquals("waiting:${w.id}", item.key)
        assertEquals(w.id, item.waitingId)
        assertEquals("do the thing", item.text)
        assertEquals(listOf(file), item.files)
        assertTrue(item.waiting)
        assertTrue(item.pending)
        assertFalse(item.queued)
    }

    @Test fun everyChangeBumpsTheVersionTheScreensWatch() {
        val q = queue()
        val v0 = q.version
        val a = q.add("s1", "x", emptyList())
        val v1 = q.version
        q.edit(a.id, "y")
        val v2 = q.version
        q.sending(a.id)
        val v3 = q.version
        q.failed(a.id)
        assertTrue(v0 < v1 && v1 < v2 && v2 < v3 && v3 < q.version)
    }
}
