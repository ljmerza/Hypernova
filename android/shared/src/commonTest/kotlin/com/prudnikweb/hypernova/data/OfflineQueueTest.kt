// Modified by Leonardo Merza, 2026. See NOTICE.
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

    @Test fun oneBeingEditedIsNeverSentNorAnythingBehindItUntilTheOwnerHasFinished() {
        val store = Mem()
        val q = queue(store)
        val a = q.add("s1", "first", emptyList())
        val b = q.add("s1", "second", emptyList())
        val other = q.add("s2", "elsewhere", emptyList())
        assertTrue(q.startEditing(a.id))
        assertEquals("the other session goes, this one waits", listOf(other.id), q.due().map { it.id })
        assertFalse("what sends it is told no too", q.sending(a.id))
        assertTrue("it shows, saying so", q.of("s1").first().item().editing)
        // Closing the app doesn't send it either.
        val again = OfflineQueue(store) { t++ }
        assertEquals(listOf(other.id), again.due().map { it.id })
        // Cancelled: as it was, first in line.
        q.stopEditing(a.id)
        assertEquals(listOf(a.id, other.id), q.due().map { it.id })
        // Saved: the new words, and no longer being edited.
        assertTrue(q.startEditing(a.id))
        assertTrue(q.edit(a.id, "first, changed"))
        assertEquals("first, changed", q.get(a.id)?.text)
        assertEquals(listOf(a.id, other.id), q.due().map { it.id })
        // On its way already: too late to stop it, and it says so.
        assertTrue(q.sending(a.id))
        assertFalse(q.startEditing(a.id))
        assertFalse(q.startEditing("nope"))
        q.sent(a.id)
        assertEquals(b.id, q.due().first { it.sessionId == "s1" }.id)
    }

    @Test fun wordsThatComeBackNeverBecomeACommandsArguments() {
        // A send that came back goes ahead of what was typed since, as ever.
        assertEquals("said before\ntyped since", AttachDrafts.joined("said before", "typed since"))
        assertEquals("said before", AttachDrafts.joined("said before", "  "))
        // Unless it's a command: then everything after it would be its arguments.
        assertEquals("read and stop\n/clear", AttachDrafts.joined("/clear", "read and stop"))
        assertEquals("read and stop\n/compact keep it", AttachDrafts.joined("/compact keep it", "read and stop"))
        // A path isn't a command.
        assertEquals("/Users/me/a.png what is it\nand this", AttachDrafts.joined("/Users/me/a.png what is it", "and this"))
    }

    @Test fun whatWaitsForASessionARewindReplacedWaitsForTheNewOne() {
        val store = Mem()
        val q = queue(store)
        q.add("s1", "keep me", emptyList())
        q.add("other", "not me", emptyList())
        q.moved("s1", "s2")
        assertEquals(listOf("keep me"), q.of("s2").map { it.text })
        assertTrue(q.of("s1").isEmpty())
        assertEquals(listOf("not me"), q.of("other").map { it.text })
        assertEquals(listOf("keep me"), OfflineQueue(store) { t++ }.of("s2").map { it.text })
    }
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
