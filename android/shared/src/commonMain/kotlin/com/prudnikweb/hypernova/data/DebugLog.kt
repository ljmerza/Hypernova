package com.prudnikweb.hypernova.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/*
 * The debug log (docs/PROTOCOL.md, "Client logs"; docs/DECISIONS.md, "A debug log"): off by default, turned
 * on in Settings, off again by itself after 24 hours. While it's on, what the app does goes in a rotating
 * file, each line through the one scrubber (DebugScrub). Send and Share take it out, and nothing else does.
 *
 * Off costs a read of one volatile field at each place that could log, and no string is built: the call
 * sites are inline lambdas behind [DebugLog.on]. On, a call site builds its line and queues it, and a
 * background thread does the scrubbing, the formatting and the disk: nothing waits for a file on the main
 * thread, and a burst past the queue is dropped and counted, rather than slowing anything down.
 */

enum class DebugLevel { D, I, W, E }

/**
 * The switch, the queue and the files. There's one for the app, [DebugLog.logger]; a test makes its own
 * with a temp folder and a clock of its own. [post] is how its Compose state reaches the main thread.
 */
class DebugLogger(
    val folder: File,
    private val prefs: Prefs,
    private val head: DebugHead,
    private val clock: () -> Long = System::currentTimeMillis,
    private val post: (() -> Unit) -> Unit = { it() },
    private val onState: (Boolean) -> Unit = {},
    private val capacity: Int = QUEUE,
    fileMax: Long = RotatingLog.FILE_MAX,
    files: Int = RotatingLog.FILES,
) {
    private val lock = Any()
    private val log = RotatingLog(folder, { head.text(clock()) }, fileMax, files)
    val crashes = CrashFile(folder, { head.version }, clock)

    /** When it turns itself off (epoch ms); 0: it's off. State, so Settings says so as it happens. */
    var until by mutableLongStateOf(0L)
        private set
    /** The log's and the crash file's size on disk, as of the last write or [refresh]. */
    var sizeBytes by mutableLongStateOf(0L)
        private set
    /** A crash is saved. */
    var hasCrash by mutableStateOf(false)
        private set

    val isOn: Boolean get() = session != null

    @Volatile private var session: Session? = null
    @Volatile private var stopped: CountDownLatch? = null

    /** Picks up where the last run left it: still on if its 24 hours aren't over. A clock that puts it further off than that counts as over. */
    fun start() {
        val kept = prefs.getLong(KEY, 0L)
        if (kept == 0L) return
        val now = clock()
        if (now >= kept || kept > now + DURATION_MS) {
            prefs.edit().remove(KEY).apply()
            return
        }
        begin(kept)
    }

    /** The switch, on: 24 hours from now. On already, it's 24 hours from now again. */
    fun turnOn() {
        val at = clock() + DURATION_MS
        prefs.edit().putLong(KEY, at).apply()
        if (session != null) {
            until = at
            put(DebugLevel.I, "log", "debug logging stays on: 24 hours from now", null)
            return
        }
        begin(at)
        put(DebugLevel.I, "log", "debug logging turned on, until ${clockWords(at)}: ${head.said()}", null)
    }

    /** The switch, off. Nothing more is written once what's queued has been. */
    fun turnOff() {
        put(DebugLevel.I, "log", "debug logging turned off", null)
        end()
        prefs.edit().remove(KEY).apply()
    }

    @Synchronized private fun begin(at: Long) {
        until = at
        val s = Session(at)
        session = s
        onState(true)
        s.thread.start()
    }

    @Synchronized private fun end() {
        val s = session ?: return
        session = null
        onState(false)
        stopped = s.done
        s.stop()
        until = 0
    }

    /** A line for the file. Any thread; it never blocks. */
    fun put(level: DebugLevel, tag: String, message: String, t: Throwable?) {
        session?.offer(Line(clock(), level, tag, message, t))
    }

    /**
     * Waits up to [timeoutMs] for what's queued to reach the file. A crash handler gives it a moment; Send
     * and Share, a little longer. False: it didn't get there in time.
     */
    fun flush(timeoutMs: Long = 2_000): Boolean {
        val s = session
        if (s == null) return stopped?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: true
        if (Thread.currentThread() === s.thread) return false
        val mark = CountDownLatch(1)
        if (!s.offer(Line(clock(), DebugLevel.D, "", "", null, mark))) return false
        return mark.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** The sizes, read from the disk. Off the main thread: Settings asks when it opens. */
    fun refresh() {
        val (size, crash) = synchronized(lock) { log.flush(); log.size() + crashes.size() to (crashes.size() > 0) }
        publish(size, crash)
    }

    private fun publish(size: Long, crash: Boolean) {
        post {
            sizeBytes = size
            hasCrash = crash
        }
    }

    /** Off the main thread. The files go; if it's on, it starts a new one with its head. */
    fun clear() {
        flush()
        synchronized(lock) {
            log.clear()
            crashes.clear()
        }
        put(DebugLevel.I, "log", "the log was cleared: ${head.said()}", null)
        flush()
        refresh()
    }

    /** Off the main thread. One file of the crashes and the log, at [to]; its size. */
    fun bundle(to: File): Long {
        flush()
        return synchronized(lock) {
            log.flush()
            DebugBundle.write(to, log, crashes, clock(), head.device)
        }
    }

    /** Nothing to send or share yet. */
    val empty: Boolean get() = sizeBytes == 0L

    /**
     * A crash, on the dying thread: into the crash file whether it's on or off, and, if on, the log too, with
     * a moment for the queue to reach the disk.
     */
    fun crashed(thread: Thread, t: Throwable) {
        runCatching { synchronized(lock) { crashes.record(thread, t) } }
        if (session != null) {
            put(DebugLevel.E, "crash", "the app crashed on thread ${thread.name}", t)
            flush(CRASH_FLUSH_MS)
        }
    }

    private fun clockWords(at: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(at))

    private class Line(val at: Long, val level: DebugLevel, val tag: String, val message: String, val t: Throwable?, val mark: CountDownLatch? = null)

    /** One stretch of being on: a queue and the thread that empties it. */
    private inner class Session(private val ends: Long) {
        private val queue = ArrayBlockingQueue<Line>(capacity)
        private val dropped = AtomicInteger()
        private val stopping = AtomicBoolean(false)
        val done = CountDownLatch(1)
        val thread = Thread({ run() }, "hypernova-debug-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
        private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        private var lastSize = 0L
        private var published = -1L
        private var publishedAt = 0L

        fun offer(l: Line): Boolean = queue.offer(l).also { if (!it) dropped.incrementAndGet() }

        fun stop() {
            stopping.set(true)
            // Wakes it: full, it's awake anyway.
            queue.offer(Line(0, DebugLevel.D, "", "", null))
        }

        private fun run() {
            val batch = ArrayList<Line>(256)
            try {
                while (true) {
                    val first = queue.poll(idleMs(), TimeUnit.MILLISECONDS)
                    batch.clear()
                    if (first != null) batch += first
                    queue.drainTo(batch)
                    if (clock() >= ends && !stopping.get()) {
                        expire(batch)
                        return
                    }
                    write(batch)
                    if (stopping.get() && queue.isEmpty()) return
                }
            } catch (_: InterruptedException) {
                // Only a test does this.
            } finally {
                finish()
                done.countDown()
            }
        }

        /** Until it's next due to look at the clock, with nothing to write. A phone's clock can move while it sleeps, so not all the way. */
        private fun idleMs(): Long = (ends - clock()).coerceIn(1, IDLE_MS)

        private fun write(batch: List<Line>) {
            if (batch.isEmpty()) return
            synchronized(lock) {
                val lost = dropped.getAndSet(0)
                if (lost > 0) writeOne(Line(clock(), DebugLevel.W, "log", "$lost lines dropped: too many at once", null))
                for (l in batch) if (l.mark == null && l.tag.isNotEmpty()) writeOne(l)
                // Out to the system at the end of each batch: a crash loses only what's still queued.
                runCatching { log.flush() }
                lastSize = runCatching { log.size() + crashes.size() }.getOrDefault(lastSize)
            }
            for (l in batch) l.mark?.countDown()
            publishSize()
        }

        private fun writeOne(l: Line) {
            runCatching { log.write(format(l)) }
        }

        private fun format(l: Line): String = buildString {
            append(time.format(Date(l.at))).append(' ').append(l.level.name).append(' ').append(l.tag.padEnd(6)).append(' ')
            append(DebugScrub.line(l.message)).append('\n')
            if (l.t != null) for (s in DebugScrub.throwable(l.t)) append(s).append('\n')
        }

        /** Settings' number, at most once a second: nothing recomposes for every line. */
        private fun publishSize() {
            val now = clock()
            if (lastSize == published || now - publishedAt < 1_000) return
            published = lastSize
            publishedAt = now
            publish(lastSize, crashes.size() > 0)
        }

        /** Its 24 hours are up: what came after isn't written, and this says so. */
        private fun expire(batch: List<Line>) {
            for (l in batch) l.mark?.countDown()
            synchronized(lock) {
                writeOne(Line(clock(), DebugLevel.I, "log", "debug logging turned itself off after 24 hours", null))
            }
            synchronized(this@DebugLogger) {
                if (session === this) {
                    session = null
                    stopped = done
                    onState(false)
                }
            }
            prefs.edit().remove(KEY).apply()
            post { until = 0 }
        }

        private fun finish() {
            // A mark still queued when this stops must not hold its waiter for the whole of its timeout.
            while (true) (queue.poll() ?: break).mark?.countDown()
            synchronized(lock) {
                runCatching { log.flush() }
                log.close()
                lastSize = runCatching { log.size() + crashes.size() }.getOrDefault(lastSize)
            }
            publish(lastSize, crashes.size() > 0)
        }
    }

    companion object {
        const val DURATION_MS = 24 * 3_600_000L
        const val KEY = "until"
        const val PREFS = "debuglog"
        /** A burst's worth, well over a second of the busiest feed. */
        const val QUEUE = 8_192
        private const val IDLE_MS = 30_000L
        const val CRASH_FLUSH_MS = 300L
    }
}

/** The app's one debug log. Every line of it comes through here; it does nothing until [init], and costs nothing off. */
object DebugLog {
    /** Read at every call site: nothing else happens with it off, not even building the words. */
    @JvmField @Volatile var on = false

    @Volatile var logger: DebugLogger? = null
        private set

    private val crashHandler = AtomicBoolean(false)

    inline fun d(tag: String, message: () -> String) {
        if (on) put(DebugLevel.D, tag, message(), null)
    }

    inline fun i(tag: String, message: () -> String) {
        if (on) put(DebugLevel.I, tag, message(), null)
    }

    inline fun w(tag: String, t: Throwable? = null, message: () -> String) {
        if (on) put(DebugLevel.W, tag, message(), t)
    }

    inline fun e(tag: String, t: Throwable? = null, message: () -> String) {
        if (on) put(DebugLevel.E, tag, message(), t)
    }

    fun put(level: DebugLevel, tag: String, message: String, t: Throwable?) {
        logger?.put(level, tag, message, t)
    }

    /** From [Core.init], as soon as there's a platform: crashes are kept from here on, the switch or not. */
    fun init(p: Platform) {
        if (logger != null) return
        // A desktop has no number of its own: it says as high as it goes (DesktopAppInfo).
        val version = p.appInfo.versionName + if (p.appInfo.versionCode == Int.MAX_VALUE) "" else " (${p.appInfo.versionCode})"
        val head = DebugHead(version, p.appInfo.build, p.device.os, p.device.model)
        val made = DebugLogger(File(p.storage.filesDir, FOLDER), p.storage.prefs(DebugLogger.PREFS), head, post = p.main::post, onState = { on = it })
        logger = made
        keepCrashes()
        made.start()
        i("app") { "started: ${head.said()}" }
    }

    /** A test's own logger in place of the app's; null: none. */
    fun use(l: DebugLogger?) {
        logger = l
        on = l?.isOn == true
    }

    /**
     * Saved, then handed on to whoever was there before, the system's handler on a phone: what a crash does
     * doesn't change, and nothing here can stop it reaching them. Once per process.
     */
    fun keepCrashes() {
        if (!crashHandler.compareAndSet(false, true)) return
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(chained(before) { logger })
    }

    /** The handler: [keep]'s logger gets the crash and [before] gets it next, whatever happened to the first. */
    fun chained(before: Thread.UncaughtExceptionHandler?, keep: () -> DebugLogger?) = Thread.UncaughtExceptionHandler { thread, t ->
        try {
            keep()?.crashed(thread, t)
        } catch (_: Throwable) {
            // Saving it must never be what changes how the app goes down.
        } finally {
            if (before != null) {
                before.uncaughtException(thread, t)
            } else {
                // No one before (a desktop JVM): what the JVM does itself when nobody's set.
                System.err.print("Exception in thread \"${thread.name}\" ")
                t.printStackTrace()
            }
        }
    }

    /** The app is leaving: a desktop's window closing, or a phone's last screen finishing. */
    fun stopped(why: String) {
        val l = logger ?: return
        i("app") { "stopped ($why)" }
        if (on) l.flush(500)
    }

    /** A screen came up, by its kind and ids alone: what's in a title or a path stays out. */
    fun screen(r: Route) {
        i("screen") { r.debugWords }
    }

    /** A message on the live feed: its type and size, and the session's id if it names one. Never what's in it. */
    fun feed(text: String, o: JSONObject?) {
        d("feed") {
            val type = DebugScrub.word(o?.optString("t")) ?: "unreadable"
            val session = DebugScrub.id(o?.optString("sessionId")?.takeIf { it.isNotEmpty() })
            "\"$type\", ${size(text.length.toLong())}" + (session?.let { ", session $it" } ?: "")
        }
    }

    const val FOLDER = "debug-logs"

    /** A size in the words a person reads. */
    fun size(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", n / (1024.0 * 1024.0))
    }
}

/** A route as the log says it: what kind of screen, and ids. Every new screen has to choose here: a title or a path isn't one of the choices. */
val Route.debugWords: String
    get() = when (this) {
        Route.Home -> "Home"
        is Route.Session -> "session $id"
        is Route.Files -> "files"
        is Route.FileView -> "a file"
        is Route.Web -> if (preview) "a preview" else "a web page"
        is Route.Terminal -> "terminal of session $sessionId"
        is Route.Agent -> "agent $agentId of session $sessionId"
        Route.Providers -> "providers"
        is Route.AgentSettings -> "agent settings"
        is Route.McpServers -> "MCP servers"
        is Route.McpServerPage -> "an MCP server"
        is Route.McpEdit -> "editing an MCP server"
        is Route.Plugins -> "plugins"
        is Route.PluginPage -> "a plugin"
        is Route.PluginBrowse -> "browsing plugins"
        is Route.Skills -> "skills"
        is Route.AgentEnv -> "agent environment"
        is Route.AgentRaw -> "agent raw settings"
        Route.ConnectionLog -> "connection log"
        is Route.ProviderEdit -> "editing a provider"
        is Route.AutomationEdit -> "automation ${id ?: "new"}"
    }
