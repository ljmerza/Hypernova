package com.prudnikweb.hypernova.bridge

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The bridge's connections, in pools of their own (LensBridge), so what anything on the phone can
 * open never crowds out Lens:
 *
 * - **Open**: every connection until it shows a grant's signature (a ping, an ask, a preview's
 *   page, and each of Lens's own calls while its head is read). Something knocking can fill only this one, and
 *   when it's full the oldest connection still reading its request goes to make room: a sender
 *   trickling its head (or a small body) can't keep Lens's next call out.
 * - **Calls** and **feeds**: connections whose signature checked out, counted apart (Lens keeps two
 *   live feeds and makes a call or two at a time).
 * - **Carried**: a link that is its own key (a preview's page or file) being fetched from the Mac
 *   without a signature. Counted apart, and few: a slow Mac (or many of them asked
 *   for) can't hold every open slot meanwhile.
 *
 * Nothing of Android in here (unit-tested).
 */
class ConnectionSlots(private val maxOpen: Int, private val maxCalls: Int, private val maxFeeds: Int, private val maxCarried: Int = 8) {
    enum class Pool { Open, Call, Feed, Carried }

    class Slot internal constructor(internal val close: () -> Unit) {
        /** Still reading its request (head, or a small body): it may be closed to make room. */
        @Volatile internal var reading = true
        @Volatile var pool = Pool.Open
            internal set
    }

    private val open = LinkedHashSet<Slot>()
    private var calls = 0
    private var feeds = 0
    private var carried = 0

    /**
     * A new connection's slot; when the open pool is full, the oldest connection there still
     * reading its request is closed to make room ([Slot.close], outside the lock). Null when
     * there's none to close: this one is refused.
     */
    fun admit(close: () -> Unit): Slot? {
        val evicted: Slot?
        val slot = Slot(close)
        synchronized(this) {
            evicted = if (open.size >= maxOpen) open.firstOrNull { it.reading } ?: return null else null
            if (evicted != null) open.remove(evicted)
            open += slot
        }
        evicted?.let { runCatching { it.close() } }
        return slot
    }

    /** Its request is read: from now on it isn't one to close for room. */
    fun read(s: Slot) { s.reading = false }

    /** Its signature checked out: it moves to the calls' pool (or the feeds'); false when that one's full. */
    fun grant(s: Slot, feed: Boolean): Boolean = synchronized(this) {
        if (s.pool != Pool.Open) return true
        if (feed && feeds >= maxFeeds || !feed && calls >= maxCalls) return false
        if (!open.remove(s)) return false
        s.reading = false
        if (feed) { feeds++; s.pool = Pool.Feed } else { calls++; s.pool = Pool.Call }
        true
    }

    /** Its request is read and it goes to the Mac without a signature: out of the open pool, into the carried one; false when that one's full. */
    fun carry(s: Slot): Boolean = synchronized(this) {
        if (s.pool != Pool.Open) return s.pool == Pool.Carried
        if (carried >= maxCarried || !open.remove(s)) return false
        s.reading = false
        carried++
        s.pool = Pool.Carried
        true
    }

    /** The connection ended. */
    fun release(s: Slot): Unit = synchronized(this) {
        when (s.pool) {
            Pool.Open -> open.remove(s)
            Pool.Call -> calls--
            Pool.Feed -> feeds--
            Pool.Carried -> carried--
        }
        s.pool = Pool.Open
        s.reading = false
    }

    /** How many in each pool now. */
    fun counts(): Map<Pool, Int> = synchronized(this) { mapOf(Pool.Open to open.size, Pool.Call to calls, Pool.Feed to feeds, Pool.Carried to carried) }
}

/**
 * What's under way for each grant (a call carried to the Mac, a preview a Lens opened, streaming),
 * so a Disconnect, the Mac forgetting the glasses or the phone pairing again ends it at once rather
 * than whenever it finishes by itself.
 */
class InFlight {
    class Entry internal constructor(val grant: String, internal val cancel: () -> Unit)

    private val all = LinkedHashSet<Entry>()

    fun add(grant: String, cancel: () -> Unit): Entry = Entry(grant, cancel).also { synchronized(all) { all += it } }

    fun remove(e: Entry) { synchronized(all) { all -= e } }

    /** Ends everything under way for [grant]. */
    fun cancel(grant: String) = end(synchronized(all) { all.filter { it.grant == grant }.also { all.removeAll(it.toSet()) } })

    fun cancelAll() = end(synchronized(all) { all.toList().also { all.clear() } })

    fun count(grant: String): Int = synchronized(all) { all.count { it.grant == grant } }

    private fun end(gone: List<Entry>) { for (e in gone) runCatching { e.cancel() } }
}

/**
 * Does [work] on one thread of its own with only the latest value: a value offered while another
 * still waits replaces it. A burst of settings changes is one message to each feed, never a thread
 * apiece (one stuck feed used to keep a new thread waiting at each change).
 */
class Latest<T : Any>(name: String, private val work: (T) -> Unit) {
    private val next = AtomicReference<T?>(null)
    private val runner = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }

    fun offer(v: T) {
        if (next.getAndSet(v) != null) return
        runner.execute { next.getAndSet(null)?.let { runCatching { work(it) } } }
    }
}

/**
 * Keeps the bridge's port for as long as the app runs (LensBridge). Another app may hold
 * 127.0.0.1:47380 first (after a reboot, before this app's process starts): then [onBusy] says so
 * once, and the port is tried again every few seconds ([delayMs]) until it's this app's, when
 * [onFree] says that. Each opening is served ([serve]) with a listener id of its own ([newId]), so a
 * call signed for whoever listened before is no good on it; when serving ends (the socket failed),
 * the port is opened again.
 *
 * Nothing Lens sends to whoever holds the port is any use to it (BridgeCrypto); this only gets the
 * glasses back as soon as it can, and tells the owner meanwhile.
 */
class PortKeeper(
    private val open: () -> java.net.ServerSocket,
    private val serve: (java.net.ServerSocket, String) -> Unit,
    private val onBusy: (Exception) -> Unit,
    private val onFree: () -> Unit,
    private val newId: () -> String,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    /** Runs until [stop] says so (for ever, in the app). */
    fun run(stop: () -> Boolean = { false }) {
        var tries = 0
        while (!stop()) {
            val server = try {
                open()
            } catch (e: Exception) {
                if (tries == 0) onBusy(e)
                tries++
                sleep(delayMs(tries))
                continue
            }
            if (tries > 0) {
                tries = 0
                onFree()
            }
            try {
                serve(server, newId())
            } finally {
                runCatching { server.close() }
            }
        }
    }

    companion object {
        const val RETRY_MS = 3_000L
        const val RETRY_LATER_MS = 15_000L
        /** About three minutes of trying every few seconds; less often after that. */
        const val RETRIES_SOON = 60

        fun delayMs(tries: Int): Long = if (tries < RETRIES_SOON) RETRY_MS else RETRY_LATER_MS
    }
}
