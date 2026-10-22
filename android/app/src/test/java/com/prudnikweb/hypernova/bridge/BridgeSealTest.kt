package com.prudnikweb.hypernova.bridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a call's bytes are sealed (BridgeSeal): the same numbers Lens's tests hold its own code to
 * (lens/test/bridge.test.ts, "the seal's numbers", there checked against node:crypto too), so a
 * body Lens seals opens here and an answer sealed here opens there, or both tests fail; and that a
 * piece changed, moved, repeated or cut doesn't open.
 */
class BridgeSealTest {
    private val key = BridgeCrypto.unhex("b1e7d263d5e495f5b65eacc11d330ea1146cfc736bed7e984fe371dff56256c6")
    private fun hex(b: ByteArray) = BridgeCrypto.hex(b)

    @Test fun theSealsNumbersAreLenss() {
        assertEquals("b1e7d263d5e495f5b65eacc11d330ea1146cfc736bed7e984fe371dff56256c6", hex(BridgeCrypto.callKey(BridgeCrypto.unhex("ad0b3896686cf715ed395d1379592b6ef2d185dd883b8bf0543c2063705476a0"), "listener-1", "nonce-0001")))
        assertEquals("010000000000000000000005", hex(BridgeSeal.iv(1, 5)))
        assertEquals("0200000000000000000000ff", hex(BridgeSeal.iv(2, 255)))
        // A request body as Lens sends it: one record, the last.
        val request = BridgeCrypto.unhex("010000001d67f122c41afc2b8316a9f2c6c2a1d2c93d493be450fcc5a647dbe9c15b")
        assertArrayEquals(request, BridgeSeal.record(key, BridgeSeal.FROM_LENS, 0, true, "{\"text\":\"hi\"}".toByteArray()))
        assertEquals("{\"text\":\"hi\"}", String(BridgeSeal.openStream(key, BridgeSeal.FROM_LENS, request)))
        // An answer as the bridge sends it: its head, then its body, then the end.
        val out = java.io.ByteArrayOutputStream()
        val w = BridgeSeal.Writer(out, key, BridgeSeal.FROM_APP)
        w.head(200, listOf("Content-Type" to "application/json; charset=utf-8"))
        w.data("{\"ok\":true}".toByteArray())
        w.end()
        val response = "000000005dbe5c7190b614a886f6f1b7bf47e1a9dac9bdc9543cb5640737c3f1f5c01d59fb00b164ab19b378113875f40760f723b84ac01e25f1dbacafab0dc72673cf708b19f3c0e28f0dc4a9538cbf1b91b17a934de18701bdb8d2bb73a033f213" +
            "010000001b3c519bb7a221007fe6eb1f9aa97c5de61c8e96edd68e0a469be22f"
        // (The vector has the body as the last record; the writer ends with an empty one after it: both open the same.)
        val head = "{\"status\":200,\"headers\":[[\"content-type\",\"application/json; charset=utf-8\"]]}"
        assertEquals(head + "{\"ok\":true}", String(BridgeSeal.openStream(key, BridgeSeal.FROM_APP, BridgeCrypto.unhex(response))))
        assertEquals(head + "{\"ok\":true}", String(BridgeSeal.openStream(key, BridgeSeal.FROM_APP, out.toByteArray())))
        assertEquals(response.substring(0, 2 * (5 + 0x5d)), hex(out.toByteArray()).substring(0, 2 * (5 + 0x5d)))
        // A message to Lens, and Lens's end marker.
        assertEquals("c4052090f55aff97a6a2e1e812e3f8d7d8a8c45f29b5644026ca6a4c4c00670ab1e2bc3bfdb2487f", hex(BridgeSeal.frame(key, BridgeSeal.FROM_APP, 0, BridgeSeal.TEXT, "{\"t\":\"bridge.settings\"}".toByteArray())))
        val end = BridgeSeal.openFrame(key, BridgeSeal.FROM_LENS, 5, BridgeCrypto.unhex("9460f3dfb40aed4858fcae8db5c80fae52ba98"))
        assertEquals(BridgeSeal.END, end.kind)
        assertEquals(1000, WsFrames.closeCode(end.payload))
    }

    @Test fun aPieceChangedMovedRepeatedOrCutDoesntOpen() {
        val body = ByteArray(200_000) { (it % 251).toByte() }
        val out = java.io.ByteArrayOutputStream()
        val w = BridgeSeal.Writer(out, key, BridgeSeal.FROM_APP)
        w.head(201, listOf("Content-Type" to "audio/wav", "X-Sample-Rate" to "22050"))
        w.data(body)
        w.end()
        val sealed = out.toByteArray()
        assertTrue("in pieces", sealed.size > body.size + 5 * 4)
        val opened = BridgeSeal.openStream(key, BridgeSeal.FROM_APP, sealed, max = body.size + 1024)
        val head = String(opened.copyOf(opened.indexOf('}'.code.toByte()) + 1))
        assertEquals("{\"status\":201,\"headers\":[[\"content-type\",\"audio/wav\"],[\"x-sample-rate\",\"22050\"]]}", head)
        assertArrayEquals(body, opened.copyOfRange(head.length, opened.size))
        fun broken(b: ByteArray) = runCatching { BridgeSeal.openStream(key, BridgeSeal.FROM_APP, b, max = body.size + 1024) }.exceptionOrNull() is BridgeSeal.Broken
        // A byte changed, anywhere.
        assertTrue(broken(sealed.copyOf().also { it[100] = (it[100] + 1).toByte() }))
        assertTrue(broken(sealed.copyOf().also { it[sealed.size - 1] = (it[sealed.size - 1] + 1).toByte() }))
        // Cut off before the end marker, at a record's edge or inside one.
        val firstLen = 5 + ((sealed[1].toInt() and 0xff shl 24) or (sealed[2].toInt() and 0xff shl 16) or (sealed[3].toInt() and 0xff shl 8) or (sealed[4].toInt() and 0xff))
        assertTrue(broken(sealed.copyOf(firstLen)))
        assertTrue(broken(sealed.copyOf(sealed.size - 1)))
        // The end marker's flag turned into "more" (or a middle record's into "last").
        assertTrue(broken(sealed.copyOf().also { it[sealed.size - 21] = 0 }))
        assertTrue(broken(sealed.copyOf().also { it[0] = 1 }))
        // The other direction's key, another call's key.
        assertTrue(runCatching { BridgeSeal.openStream(key, BridgeSeal.FROM_LENS, sealed, max = body.size + 1024) }.exceptionOrNull() is BridgeSeal.Broken)
        assertTrue(runCatching { BridgeSeal.openStream(BridgeCrypto.callKey(key, "L", "other"), BridgeSeal.FROM_APP, sealed, max = body.size + 1024) }.exceptionOrNull() is BridgeSeal.Broken)
        // Two records swapped (each still opens alone, under another counter): the stream doesn't.
        val second = firstLen + 5 + ((sealed[firstLen + 1].toInt() and 0xff shl 24) or (sealed[firstLen + 2].toInt() and 0xff shl 16) or (sealed[firstLen + 3].toInt() and 0xff shl 8) or (sealed[firstLen + 4].toInt() and 0xff))
        val swapped = sealed.copyOfRange(firstLen, second) + sealed.copyOfRange(0, firstLen) + sealed.copyOfRange(second, sealed.size)
        assertTrue(broken(swapped))
        // A record repeated.
        assertTrue(broken(sealed.copyOfRange(0, firstLen) + sealed))
        // Bytes after the end.
        assertTrue(broken(sealed + byteArrayOf(1)))
        // More plaintext than the reader takes.
        assertTrue(runCatching { BridgeSeal.openStream(key, BridgeSeal.FROM_APP, sealed, max = 1000) }.exceptionOrNull() is TooLarge)
    }

    @Test fun framesOpenOnlyInOrderAndOnlyAsTheirKind() {
        val a = BridgeSeal.frame(key, BridgeSeal.FROM_LENS, 0, BridgeSeal.TEXT, "{\"type\":\"start\"}".toByteArray())
        val b = BridgeSeal.frame(key, BridgeSeal.FROM_LENS, 1, BridgeSeal.BINARY, ByteArray(1280) { 7 })
        val o = BridgeSeal.openFrame(key, BridgeSeal.FROM_LENS, 0, a)
        assertEquals(BridgeSeal.TEXT, o.kind)
        assertEquals("{\"type\":\"start\"}", String(o.payload))
        assertEquals(1280, BridgeSeal.openFrame(key, BridgeSeal.FROM_LENS, 1, b).payload.size)
        assertTrue(runCatching { BridgeSeal.openFrame(key, BridgeSeal.FROM_LENS, 1, a) }.exceptionOrNull() is BridgeSeal.Broken)
        assertTrue(runCatching { BridgeSeal.openFrame(key, BridgeSeal.FROM_LENS, 0, b) }.exceptionOrNull() is BridgeSeal.Broken)
        assertTrue(runCatching { BridgeSeal.openFrame(key, BridgeSeal.FROM_APP, 0, a) }.exceptionOrNull() is BridgeSeal.Broken)
        assertTrue(runCatching { BridgeSeal.openFrame(key, BridgeSeal.FROM_LENS, 0, a.copyOf(a.size - 1)) }.exceptionOrNull() is BridgeSeal.Broken)
        // A body record is never a frame, nor a frame a body record.
        val rec = BridgeSeal.seal(key, BridgeSeal.FROM_LENS, 0, "hnb3-body\nlast".toByteArray(), byteArrayOf(1, 2))
        assertTrue(runCatching { BridgeSeal.openFrame(key, BridgeSeal.FROM_LENS, 0, rec) }.exceptionOrNull() is BridgeSeal.Broken)
        // The end marker carries its code.
        val end = BridgeSeal.openFrame(key, BridgeSeal.FROM_APP, 9, BridgeSeal.frame(key, BridgeSeal.FROM_APP, 9, BridgeSeal.END, BridgeSeal.endPayload(4001, "forgotten")))
        assertEquals(BridgeSeal.END, end.kind)
        assertEquals(4001, WsFrames.closeCode(end.payload))
    }
}
