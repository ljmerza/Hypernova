package com.prudnikweb.hypernova.ui.term

import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * What the app's terminal socket hands the page (ui/term/AppSocket.kt): every frame in order, the
 * binary ones as base64 and joined when they come in a row, the text ones as they were.
 */
class TermFramesTest {
    /** `hn.rx(id, [...])` → the id and the items, read back as JSON (so the call is valid JS too). */
    private fun parse(js: String): Pair<Int, List<String>> {
        val m = Regex("""^hn\.rx\((-?\d+),(\[.*])\)$""", RegexOption.DOT_MATCHES_ALL).find(js) ?: error("not a call: $js")
        val arr = JSONArray(m.groupValues[2])
        return m.groupValues[1].toInt() to List(arr.length()) { arr.getString(it) }
    }

    private fun bytesOf(item: String): ByteArray {
        assertTrue(item.startsWith("b"))
        return Base64.getDecoder().decode(item.substring(1))
    }

    @Test fun nothingWaitingIsNoCall() {
        assertNull(TermFrames().drain(1))
    }

    @Test fun framesKeepTheirOrderAndBytesInARowAreJoined() {
        val f = TermFrames()
        f.opened()
        f.text("""{"t":"hello","cols":80,"rows":24}""")
        f.bytes(byteArrayOf(0x1b, '['.code.toByte(), 'H'.code.toByte()).toByteString())
        f.bytes("hello ".encodeUtf8())
        f.bytes("world".encodeUtf8())
        f.text("""{"t":"state","cols":80,"rows":24,"tty":1}""")
        f.bytes("!".encodeUtf8())
        f.closed(1000)
        val (id, items) = parse(f.drain(7)!!)
        assertEquals(7, id)
        assertEquals(6, items.size)
        assertEquals("o", items[0])
        assertEquals("t{\"t\":\"hello\",\"cols\":80,\"rows\":24}", items[1])
        assertEquals("\u001b[Hhello world", String(bytesOf(items[2])))
        assertEquals("t{\"t\":\"state\",\"cols\":80,\"rows\":24,\"tty\":1}", items[3])
        assertEquals("!", String(bytesOf(items[4])))
        assertEquals("c1000", items[5])
        // All taken: nothing waits after.
        assertNull(f.drain(7))
    }

    @Test fun aCharacterSplitAcrossFramesIsWholeAgain() {
        val f = TermFrames()
        val star = "✻ Thinking".toByteArray()
        f.bytes(star.copyOfRange(0, 2).toByteString())
        f.bytes(star.copyOfRange(2, star.size).toByteString())
        assertEquals("✻ Thinking", String(bytesOf(parse(f.drain(1)!!).second.single())))
    }

    @Test fun everyByteSurvives() {
        val f = TermFrames()
        val all = ByteArray(256) { it.toByte() }
        f.bytes(all.toByteString())
        assertTrue(all.contentEquals(bytesOf(parse(f.drain(1)!!).second.single())))
    }

    @Test fun textWithQuotesNewlinesAndEscapesArrivesAsItWas() {
        val f = TermFrames()
        // A snapshot: the screen's escape sequences inside a JSON string, and characters JS is picky about.
        val snap = """{"t":"snapshot","data":"\u001b[1mbold\u001b[0m \"quoted\" back\\slash </script> line
sep"}"""
        f.text(snap)
        assertEquals("t$snap", parse(f.drain(1)!!).second.single())
    }

    @Test fun nothingAfterTheCloseAndOnlyTheFirstCloseCounts() {
        val f = TermFrames()
        f.bytes("a".encodeUtf8())
        f.closed(4008)
        f.closed(1006)
        f.bytes("late".encodeUtf8())
        f.text("{}")
        f.opened()
        val items = parse(f.drain(1)!!).second
        assertEquals(listOf("c4008"), items.drop(1))
        assertEquals("a", String(bytesOf(items[0])))
    }

    @Test fun fallingTooFarBehindStartsOver() {
        val f = TermFrames(limit = 10)
        assertTrue(f.bytes("12345".encodeUtf8()))
        assertTrue(f.text("67890"))
        assertFalse(f.bytes("x".encodeUtf8()))
        f.overflowed()
        // What waited is dropped: the page reconnects and gets a fresh screen anyway.
        assertEquals(listOf("c${TermFrames.BEHIND}"), parse(f.drain(1)!!).second)
    }

    @Test fun eachDrainStartsAfresh() {
        val f = TermFrames(limit = 10)
        assertTrue(f.bytes("1234567890".encodeUtf8()))
        f.drain(1)
        // What was taken no longer counts towards the limit.
        assertTrue(f.bytes("1234567890".encodeUtf8()))
        assertEquals("1234567890", String(bytesOf(parse(f.drain(1)!!).second.single())))
    }
}
