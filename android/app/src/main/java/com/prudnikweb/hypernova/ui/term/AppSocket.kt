package com.prudnikweb.hypernova.ui.term

import android.os.Handler
import android.os.Looper
import com.prudnikweb.hypernova.data.TermTicket
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The terminal's WebSocket when the app has to open it: on the local route, where the page's own
 * socket can't trust the Mac's pinned certificate or show this phone's device certificate
 * ([TermTicket]). The page uses a stand-in with a WebSocket's face (assets/term/index.html,
 * `appSocket`): what it sends comes here through the page's bridge, and what arrives goes to the
 * page with `hn.rx(id, [...])`, in order, gathered by [TermFrames].
 *
 * Only one call to the page is in flight at a time (the page says when it has taken one: [took]),
 * and whatever arrives meanwhile goes in the next one: a keystroke's echo goes at once, and a fast
 * build log goes in a few large calls instead of a queue of small ones.
 */
internal class AppSocket(
    /** Runs a script in the terminal's page (main thread). */
    private val run: (js: String) -> Unit,
) {
    /**
     * Asynchronous messages, so a frame on its way to the page doesn't wait behind the main
     * thread's next drawing pass (the screen's animations hold ordinary messages back for up to a
     * frame, which doubled a keystroke's echo time).
     */
    private val main = Handler.createAsync(Looper.getMainLooper())
    private val lock = Any()
    /** The ticket's URL and client the next [open] uses: once, like the ticket itself. */
    private var link: Pair<String, OkHttpClient>? = null
    private var conn: Conn? = null
    private var nextId = 1

    private val inFlight = AtomicBoolean(false)
    private val posted = AtomicBoolean(false)

    /** The ticket for the page's next `connect`: a socket only the app can open. */
    fun prepare(url: String, client: OkHttpClient) {
        synchronized(lock) { link = url to client }
    }

    /** The page opens its own socket this time (the Tailscale route): nothing for [open]. */
    fun forget() {
        synchronized(lock) { link = null }
    }

    /**
     * From the page: open the prepared socket at the terminal's size. Its id, which the page's
     * stand-in sends with and [TermFrames] answers with; -1 when nothing was prepared (the page then
     * reports the socket closed, and the screen asks for a new link).
     */
    fun open(cols: Int, rows: Int): Int = synchronized(lock) {
        conn?.let { it.shut(); conn = null }
        val (url, client) = link ?: return -1
        link = null
        val c = Conn(nextId++)
        conn = c
        c.socket = client.newWebSocket(Request.Builder().url(TermTicket.sized(url, cols, rows)).build(), c)
        c.id
    }

    /** From the page: terminal bytes (keystrokes, a paste), base64. */
    fun send(id: Int, base64: String) {
        val bytes = base64.decodeBase64() ?: return
        current(id)?.socket?.send(bytes)
    }

    /** From the page: one JSON message (its size). */
    fun sendText(id: Int, text: String) {
        current(id)?.socket?.send(text)
    }

    /** From the page: it's done with this socket; nothing more of it reaches the page. */
    fun shut(id: Int) {
        synchronized(lock) {
            val c = conn?.takeIf { it.id == id } ?: return
            conn = null
            c.shut()
        }
    }

    /** The screen went to the background or away: close whatever is open, without telling the page. */
    fun closeAll() {
        synchronized(lock) {
            link = null
            conn?.shut()
            conn = null
        }
    }

    /** From the page: it has handled the last call, so the next may go. */
    fun took() {
        main.removeCallbacks(stuck)
        inFlight.set(false)
        wake()
    }

    private fun current(id: Int): Conn? = synchronized(lock) { conn?.takeIf { it.id == id } }

    /** Something waits for the page: pass it on as soon as the main thread is free. */
    private fun wake() {
        if (posted.compareAndSet(false, true)) main.post(flush)
    }

    private val flush: Runnable = Runnable {
        posted.set(false)
        // When the call in flight is taken, [took] comes back here for all that came since.
        if (inFlight.get()) return@Runnable
        val c = synchronized(lock) { conn } ?: return@Runnable
        val js = c.frames.drain(c.id) ?: return@Runnable
        inFlight.set(true)
        // A call the page never takes (it went away) mustn't hold the next ones back for good.
        main.postDelayed(stuck, STUCK_MS)
        run(js)
    }

    private val stuck = Runnable {
        inFlight.set(false)
        flush.run()
    }

    private inner class Conn(val id: Int) : WebSocketListener() {
        val frames = TermFrames()
        @Volatile var socket: WebSocket? = null
        @Volatile private var opened = false

        /** Closed from this side: politely once open, at once while still connecting. */
        fun shut() {
            val s = socket ?: return
            if (opened) s.close(1000, null) else s.cancel()
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened = true
            frames.opened()
            wake()
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (!frames.bytes(bytes)) behind(webSocket)
            wake()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!frames.text(text)) behind(webSocket)
            wake()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            frames.closed(code)
            wake()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            frames.closed(code)
            wake()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // What a page's own WebSocket reports for a link that broke without a closing frame.
            frames.closed(1006)
            wake()
        }

        /**
         * The page fell far behind (it was frozen, say): as the Mac does with a phone 8 MB behind,
         * start over, and the screen reconnects with a fresh copy of the screen.
         */
        private fun behind(webSocket: WebSocket) {
            webSocket.cancel()
            frames.overflowed()
        }
    }

    private companion object {
        const val STUCK_MS = 2_000L
    }
}

/**
 * What the app's terminal socket has for the page, in the order it happened: the socket opened,
 * terminal bytes, the holder's JSON messages, the socket closed (with its code; nothing after it).
 * A run of byte frames is joined into one: the page writes bytes to xterm as one stream, which
 * puts a character split across frames back together, so joining changes nothing on screen.
 *
 * Nothing of Android in it (unit-tested); safe from any thread.
 */
internal class TermFrames(private val limit: Long = LIMIT) {
    private sealed interface Item
    private object Opened : Item
    private class Bytes(val buf: Buffer) : Item
    private class Text(val text: String) : Item
    private class Closed(val code: Int) : Item

    private val items = ArrayList<Item>()
    private var waiting = 0L
    private var ended = false

    @Synchronized fun opened() {
        if (!ended) items.add(Opened)
    }

    /** False when more than [limit] would be waiting: the page has stopped taking them. */
    @Synchronized fun bytes(b: ByteString): Boolean {
        if (ended) return true
        val last = items.lastOrNull()
        if (last is Bytes) last.buf.write(b) else items.add(Bytes(Buffer().write(b)))
        waiting += b.size
        return waiting <= limit
    }

    /** False when more than [limit] would be waiting. */
    @Synchronized fun text(s: String): Boolean {
        if (ended) return true
        items.add(Text(s))
        waiting += s.length
        return waiting <= limit
    }

    /** The socket closed; only the first word counts. */
    @Synchronized fun closed(code: Int) {
        if (ended) return
        ended = true
        items.add(Closed(code))
    }

    /** Too much waited: drop it all, and tell the page the socket closed as the Mac's "behind" does. */
    @Synchronized fun overflowed() {
        items.clear()
        waiting = 0
        ended = false
        closed(BEHIND)
    }

    /**
     * Everything waiting as one call to the page, `hn.rx(<id>, [items])`, and nothing waits after;
     * null when nothing does. Items: `"o"` opened, `"b<base64>"` bytes, `"t<text>"` a message,
     * `"c<code>"` closed.
     */
    @Synchronized fun drain(id: Int): String? {
        if (items.isEmpty()) return null
        val js = StringBuilder(32 + waiting.toInt().coerceAtMost(1 shl 24) * 4 / 3)
        js.append("hn.rx(").append(id).append(",[")
        items.forEachIndexed { i, it ->
            if (i > 0) js.append(',')
            when (it) {
                Opened -> js.append("\"o\"")
                // Base64 needs no escaping in a JS string.
                is Bytes -> js.append("\"b").append(it.buf.readByteString().base64()).append('"')
                is Text -> js.append(JSONObject.quote("t" + it.text))
                is Closed -> js.append("\"c").append(it.code).append('"')
            }
        }
        js.append("])")
        items.clear()
        waiting = 0
        return js.toString()
    }

    companion object {
        /** As far behind as the page may fall before starting over: the Mac's own limit for a phone. */
        const val LIMIT = 8L * 1024 * 1024
        /** The close code for a phone that fell behind (docs/PROTOCOL.md, "Shared terminals"). */
        const val BEHIND = 4008
    }
}
