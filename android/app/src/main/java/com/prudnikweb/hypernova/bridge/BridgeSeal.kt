package com.prudnikweb.hypernova.bridge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * How a signed call's bytes are sealed between Lens and the bridge (docs/PROTOCOL.md, "The phone
 * bridge", 5–6), so that whatever relays or reads the loopback connection sees only ciphertext:
 *
 * - One key per call ([BridgeCrypto.callKey]: the agreed key, the listener, the call's nonce),
 *   AES-256-GCM. The 12-byte IV is the direction (1: from Lens, 2: from the app), three zero bytes,
 *   and a 64-bit counter: each direction counts its own pieces from 0, so no IV is used twice under
 *   a key, and a piece dropped, repeated or reordered fails to open.
 * - **A body** (a request's, or the whole answer) is a stream of records, each `flag ‖ length ‖
 *   ciphertext` (1 byte: 0 more, 1 last; 4 bytes big-endian; the ciphertext with its 16-byte tag),
 *   with `"hnb3-body\n" + "more" | "last"` as its associated data, so the end marker can't be moved:
 *   a stream that ends without a `last` record was cut off. An answer's first record is its head,
 *   `{"status": 200, "headers": [["content-type", "…"], …]}`; the records after it are the body.
 * - **A WebSocket message** is one piece, `"hnb3-frame"` as its associated data, whose plaintext
 *   starts with its kind: 1 text, 2 binary, 3 the end (a close code, big-endian, then the reason),
 *   sent before the socket closes, so a socket closed without it was cut off.
 *
 * Nothing of Android in here (unit-tested, with the same numbers Lens's tests use).
 */
object BridgeSeal {
    const val FROM_LENS = 1
    const val FROM_APP = 2

    const val TEXT = 1
    const val BINARY = 2
    const val END = 3

    /** A record's plaintext at most; a longer one is refused unread (a stream is cut into pieces this size). */
    const val MAX_PIECE = 1024 * 1024
    const val CHUNK = 64 * 1024
    private const val TAG_BYTES = 16
    private val MORE = "hnb3-body\nmore".toByteArray()
    private val LAST = "hnb3-body\nlast".toByteArray()
    private val FRAME = "hnb3-frame".toByteArray()

    /** A piece that doesn't open: not sealed with this call's key, changed, out of order, or cut. */
    class Broken(message: String) : IOException(message)

    fun iv(dir: Int, counter: Long): ByteArray = ByteArray(12).also {
        it[0] = dir.toByte()
        for (i in 0 until 8) it[4 + i] = (counter ushr (8 * (7 - i))).toByte()
    }

    fun seal(key: ByteArray, dir: Int, counter: Long, aad: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv(dir, counter)))
        c.updateAAD(aad)
        return c.doFinal(plain)
    }

    fun open(key: ByteArray, dir: Int, counter: Long, aad: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < TAG_BYTES) throw Broken("too short to be sealed")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, iv(dir, counter)))
        c.updateAAD(aad)
        return try { c.doFinal(sealed) } catch (e: AEADBadTagException) { throw Broken("doesn't open") }
    }

    private fun aad(last: Boolean) = if (last) LAST else MORE

    /** One record of a body stream: its flag, its length, its ciphertext. */
    fun record(key: ByteArray, dir: Int, counter: Long, last: Boolean, plain: ByteArray): ByteArray {
        val ct = seal(key, dir, counter, aad(last), plain)
        val out = ByteArray(5 + ct.size)
        out[0] = if (last) 1 else 0
        for (i in 0 until 4) out[1 + i] = (ct.size ushr (8 * (3 - i))).toByte()
        System.arraycopy(ct, 0, out, 5, ct.size)
        return out
    }

    /** An answer's head: its status and headers, as the first record carries them. */
    fun head(status: Int, headers: List<Pair<String, String>>): ByteArray {
        val h = org.json.JSONArray()
        for ((k, v) in headers) h.put(org.json.JSONArray().put(k.lowercase()).put(v))
        // Written out, not built: the status first, the headers in the order given, on every JSON library.
        return "{\"status\":$status,\"headers\":$h}".toByteArray()
    }

    /**
     * Writes a sealed body stream to [out], piece by piece as it comes ([data]), ending with a `last`
     * record ([end]); [head] first for an answer. Each is written whole: the caller flushes.
     */
    class Writer(private val out: OutputStream, private val key: ByteArray, private val dir: Int) {
        private var counter = 0L
        private var ended = false

        fun head(status: Int, headers: List<Pair<String, String>>): Unit = piece(BridgeSeal.head(status, headers), last = false)

        fun data(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
            var at = off
            while (at < off + len) {
                val n = minOf(CHUNK, off + len - at)
                piece(bytes.copyOfRange(at, at + n), last = false)
                at += n
            }
        }

        /** The end marker: an empty `last` record (a stream without one was cut off). */
        fun end(): Unit = piece(ByteArray(0), last = true)

        private fun piece(plain: ByteArray, last: Boolean) {
            check(!ended) { "ended" }
            out.write(record(key, dir, counter++, last, plain))
            if (last) ended = true
        }
    }

    /** A whole sealed body stream ([bytes]) opened, up to [max] bytes of plaintext; [Broken] when it isn't one, whole, under this key. */
    fun openStream(key: ByteArray, dir: Int, bytes: ByteArray, max: Int = MAX_PIECE): ByteArray {
        val out = ByteArrayOutputStream()
        var at = 0
        var counter = 0L
        while (true) {
            if (bytes.size - at < 5) throw Broken("cut off")
            val flag = bytes[at].toInt()
            if (flag != 0 && flag != 1) throw Broken("bad flag")
            var len = 0L
            for (i in 0 until 4) len = (len shl 8) or (bytes[at + 1 + i].toLong() and 0xff)
            if (len > MAX_PIECE + TAG_BYTES || bytes.size - at - 5 < len) throw Broken("cut off")
            val plain = open(key, dir, counter++, aad(flag == 1), bytes.copyOfRange(at + 5, at + 5 + len.toInt()))
            if (out.size() + plain.size > max) throw TooLarge("body too large")
            out.write(plain)
            at += 5 + len.toInt()
            if (flag == 1) break
        }
        if (at != bytes.size) throw Broken("bytes after the end")
        return out.toByteArray()
    }

    /** One WebSocket message sealed: its kind ([TEXT], [BINARY], [END]) and its payload. */
    fun frame(key: ByteArray, dir: Int, counter: Long, kind: Int, payload: ByteArray): ByteArray =
        seal(key, dir, counter, FRAME, ByteArray(1 + payload.size).also { it[0] = kind.toByte(); System.arraycopy(payload, 0, it, 1, payload.size) })

    class Opened(val kind: Int, val payload: ByteArray)

    fun openFrame(key: ByteArray, dir: Int, counter: Long, sealed: ByteArray): Opened {
        val plain = open(key, dir, counter, FRAME, sealed)
        if (plain.isEmpty() || plain[0].toInt() !in TEXT..END) throw Broken("not a frame")
        return Opened(plain[0].toInt(), plain.copyOfRange(1, plain.size))
    }

    /** The end marker's payload: a close code and its reason. */
    fun endPayload(code: Int, reason: String): ByteArray = WsFrames.closePayload(code, reason)
}
