package com.prudnikweb.hypernova.bridge

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.WebSocket

/**
 * The WebSocket the bridge serves to Lens on 127.0.0.1 (RFC 6455): the handshake's answer, frames
 * in (a browser masks every one) and out (a server never does), and messages made whole from
 * fragments. The Mac's side is OkHttp's; this side is a few frames, so no library is pulled in.
 * Nothing of Android in here (unit-tested).
 */
object WsFrames {
    const val TEXT = 0x1
    const val BINARY = 0x2
    const val CONTINUATION = 0x0
    const val CLOSE = 0x8
    const val PING = 0x9
    const val PONG = 0xA
    /** A message larger than this ends the connection: the glasses send 40 ms of audio a frame, text is small. */
    const val MAX_MESSAGE = 4 * 1024 * 1024

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** `Sec-WebSocket-Accept` for a client's `Sec-WebSocket-Key`. */
    fun accept(key: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).toByteArray(Charsets.ISO_8859_1)))

    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /** The next frame, unmasked; null when the connection ended between frames. */
    fun read(input: InputStream, max: Int = MAX_MESSAGE): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = byte(input)
        val fin = b0 and 0x80 != 0
        val opcode = b0 and 0x0f
        val masked = b1 and 0x80 != 0
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) len = ((byte(input) shl 8) or byte(input)).toLong()
        else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or byte(input).toLong() }
        }
        if (len < 0 || len > max) throw IOException("frame too large")
        // Control frames are short and never fragmented (RFC 6455, 5.5).
        if (opcode >= 0x8 && (len > 125 || !fin)) throw IOException("bad control frame")
        val mask = if (masked) ByteArray(4) { byte(input).toByte() } else null
        val payload = ByteArray(len.toInt())
        var off = 0
        while (off < payload.size) {
            val r = input.read(payload, off, payload.size - off)
            if (r < 0) throw IOException("frame cut off")
            off += r
        }
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(fin, opcode, payload)
    }

    private fun byte(input: InputStream): Int {
        val c = input.read()
        if (c < 0) throw IOException("frame cut off")
        return c
    }

    /**
     * One frame, whole, in a single write (callers hold the connection's lock, so frames from the
     * Mac's thread and the bridge's own never interleave). [mask]: a client's frame (tests).
     */
    fun write(out: OutputStream, opcode: Int, payload: ByteArray, fin: Boolean = true, mask: ByteArray? = null) {
        val head = ByteArrayOutputStream(14)
        head.write((if (fin) 0x80 else 0) or (opcode and 0x0f))
        val m = if (mask != null) 0x80 else 0
        when {
            payload.size < 126 -> head.write(m or payload.size)
            payload.size <= 0xffff -> { head.write(m or 126); head.write(payload.size shr 8); head.write(payload.size and 0xff) }
            else -> { head.write(m or 127); for (i in 7 downTo 0) head.write(((payload.size.toLong() shr (8 * i)) and 0xff).toInt()) }
        }
        val body = if (mask != null) {
            head.write(mask)
            ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        } else payload
        out.write(head.toByteArray() + body)
        out.flush()
    }

    /** A close frame's payload: the code, then the reason (cut to fit a control frame). */
    fun closePayload(code: Int, reason: String): ByteArray {
        val r = reason.toByteArray().let { if (it.size > 120) it.copyOf(120) else it }
        return byteArrayOf((code shr 8).toByte(), (code and 0xff).toByte()) + r
    }

    /** The code a close frame carries (1005: none given). */
    fun closeCode(payload: ByteArray): Int = if (payload.size >= 2) ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff) else 1005

    /** A whole message: text or binary, made of one frame or several. */
    class Message(val text: Boolean, val payload: ByteArray)

    /** Puts fragmented messages back together; control frames go by on their own. */
    class Assembler(private val max: Int = MAX_MESSAGE) {
        private var opcode = -1
        private val parts = ByteArrayOutputStream()

        /** A data frame in; a whole message out once its last fragment came. */
        fun add(f: Frame): Message? {
            when (f.opcode) {
                TEXT, BINARY -> {
                    if (opcode != -1) throw IOException("a new message inside a fragmented one")
                    if (f.fin) return Message(f.opcode == TEXT, f.payload)
                    opcode = f.opcode
                    parts.reset()
                    parts.write(f.payload)
                }
                CONTINUATION -> {
                    if (opcode == -1) throw IOException("a continuation with nothing to continue")
                    if (parts.size() + f.payload.size > max) throw IOException("message too large")
                    parts.write(f.payload)
                    if (f.fin) {
                        val m = Message(opcode == TEXT, parts.toByteArray())
                        opcode = -1
                        parts.reset()
                        return m
                    }
                }
                else -> throw IOException("not a data frame")
            }
            return null
        }
    }
}

/**
 * A Lens socket the bridge carries: its grant, the path, and how to end it ([close]: its
 * connection's). Every message to Lens is sealed under the socket's own key ([sealer]:
 * BridgeSeal), counted in the order written, and the end comes as a sealed marker before the close
 * frame, so Lens knows a close without one was cut. The Mac's first messages (its snapshot) can come
 * before Lens's handshake has its answer, so messages wait until [open] has written that answer: a
 * frame before the 101 would break the handshake. [open] and [end] see the same [ended], set before
 * either writes, so a feed ended while the Mac was still answering never opens.
 */
class LiveFeed(val grant: String, val path: String, private val out: OutputStream, private val sealer: BridgeAccess.Sealer?, private val close: () -> Unit) {
    @Volatile var upstream: WebSocket? = null
    /** Whoever writes holds it: one frame at a time, never two interleaved (and the seal's counter with it). */
    private val writing = ReentrantLock()
    @Volatile private var ready = false
    /** Ended (Disconnect, the Mac forgot the glasses, the phone paired again). */
    private val ended = AtomicBoolean(false)
    private val early = ArrayList<Pair<Int, ByteArray>>()
    /** Messages sealed so far (the seal's counter: BridgeSeal). */
    private var sealed = 0L
    /** The end marker and the close frame went out (under [writing]): once, by whichever side ended first. */
    private var finished = false
    /** The socket's key, taken from the sealer once the handshake is answered ([open]): a refusal before that is sealed by the answer's writer instead. */
    private var key: ByteArray? = null

    val isOpen: Boolean get() = ready && !ended.get()

    /** A message to Lens ([BridgeSeal.TEXT], [BridgeSeal.BINARY], [BridgeSeal.END]), sealed, as one binary frame. */
    fun message(kind: Int, payload: ByteArray) = writing.withLock {
        if (ended.get()) return@withLock
        if (!ready) early += kind to payload else put(kind, payload)
    }

    /** A control frame (a pong): not a message, nothing to seal. */
    fun frame(opcode: Int, payload: ByteArray) = writing.withLock {
        if (ended.get() || !ready) return@withLock
        WsFrames.write(out, opcode, payload)
    }

    /**
     * A message that waits at most [waitMs] for the one being written: a Lens that stopped reading
     * holds its writer, and the bridge's own messages (the settings) mustn't wait on it. False when
     * it didn't go.
     */
    fun offer(kind: Int, payload: ByteArray, waitMs: Long): Boolean {
        if (!writing.tryLock(waitMs, TimeUnit.MILLISECONDS)) return false
        try {
            if (ended.get()) return false
            if (!ready) early += kind to payload else put(kind, payload)
            return true
        } finally {
            writing.unlock()
        }
    }

    /** Under [writing]: the message sealed with the next counter (or, with no sealer, as it is: tests), as one frame. */
    private fun put(kind: Int, payload: ByteArray) {
        val k = key
        if (k == null) WsFrames.write(out, if (kind == BridgeSeal.TEXT) WsFrames.TEXT else WsFrames.BINARY, payload)
        else WsFrames.write(out, WsFrames.BINARY, BridgeSeal.frame(k, BridgeSeal.FROM_APP, sealed++, kind, payload))
    }

    /** The handshake's answer, then [first], then whatever the Mac sent meanwhile; false when the feed was ended before. */
    fun open(head: ByteArray, first: List<Pair<Int, ByteArray>>): Boolean = writing.withLock {
        if (ended.get()) return@withLock false
        key = sealer?.feedKey()
        out.write(head)
        out.flush()
        ready = true
        for ((kind, payload) in first + early) put(kind, payload)
        early.clear()
        true
    }

    /**
     * The end as Lens hears it: the sealed end marker with [code] and [reason], then the close
     * frame, once. The Mac's close (4001: the glasses were forgotten) and the app's own end
     * ([end]: 4003, Disconnect) can come in the same moment, from two threads; whichever writes
     * first says the end, and the other finds it said. Under [writing]; nothing once the feed
     * was never opened.
     */
    fun finish(code: Int, reason: String) = writing.withLock { finishLocked(code, reason) }

    private fun finishLocked(code: Int, reason: String) {
        if (!ready || finished) return
        finished = true
        runCatching {
            put(BridgeSeal.END, BridgeSeal.endPayload(code, reason))
            WsFrames.write(out, WsFrames.CLOSE, WsFrames.closePayload(code, reason))
        }
    }

    /**
     * Ends the feed from the app's side (Disconnect, the Mac forgot the glasses, the phone paired
     * again): the Mac's side is let go and Lens gets the sealed end marker with [code], then the
     * close frame, then the connection closes. The writer may be busy (the Mac's own close going
     * out: then that end stands) or stuck on a Lens that stopped reading: this waits for it a
     * moment at most, never for good, and the connection's close (which frees a stuck writer too)
     * follows either way.
     */
    fun end(code: Int, reason: String) {
        if (!ended.compareAndSet(false, true)) return
        val wasOpen = ready
        if (wasOpen) upstream?.close(1000, null) else upstream?.cancel()
        if (wasOpen) {
            // The safety net: closed in a moment whatever the writer does.
            closeAfter(CLOSE_GRACE_MS)
            if (writing.tryLock(CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)) {
                try { finishLocked(code, reason) } finally { writing.unlock() }
            }
        }
        runCatching { close() }
    }

    private fun closeAfter(ms: Long) {
        CLOSER.schedule({ runCatching { close() } }, ms, TimeUnit.MILLISECONDS)
    }

    private companion object {
        /** How long an end waits for the writer (a few bytes, unless Lens stopped reading), and until the connection is closed regardless. */
        const val CLOSE_GRACE_MS = 500L
        val CLOSER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lens-feed-closer").apply { isDaemon = true } }
    }
}

/**
 * The live feeds the bridge carries. A feed joins as soon as its grant is checked, before the Mac
 * is asked (which can take 12 s), so a Disconnect meanwhile finds it ([of]) and ends it; only open
 * ones count as "Connected now" ([openGrants]).
 */
class LiveFeeds {
    private val all = LinkedHashSet<LiveFeed>()

    operator fun plusAssign(f: LiveFeed) { synchronized(all) { all += f } }
    operator fun minusAssign(f: LiveFeed) { synchronized(all) { all -= f } }
    fun of(grant: String): List<LiveFeed> = synchronized(all) { all.filter { it.grant == grant } }
    fun on(path: String): List<LiveFeed> = synchronized(all) { all.filter { it.path == path } }
    fun openGrants(): Set<String> = synchronized(all) { all.filter { it.isOpen }.map { it.grant }.toSet() }
}
