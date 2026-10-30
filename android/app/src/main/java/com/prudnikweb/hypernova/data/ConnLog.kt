package com.prudnikweb.hypernova.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.prudnikweb.hypernova.BuildConfig
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/*
 * The connection log (Settings → Mac → Connection log): how the app went about reaching the Mac,
 * as it happened. The network it saw, each probe of a route and its answer (Local by address and
 * port, the paired base URL), the route each round chose and why, the live socket opening and
 * closing, the waits before the next try and what the Mac said about its own addresses. The last [ConnLog.CAPACITY] entries in memory,
 * a short tail on disk (a phone that lost the Mac overnight still has the story in the morning),
 * and logcat in debug builds.
 *
 * Never a secret: no tokens, keys, pairing codes or URLs (a host and port at most);
 * every entry passes [ConnScrub] on its way in.
 */

/** What an entry is about. */
enum class ConnKind {
    /** The phone's network as a round saw it (Wi-Fi, mobile data, the hotspot, the same subnet as the Mac). */
    Network,
    /** One route tried: its answer. */
    Probe,
    /** The route a round chose, and why. */
    Route,
    /** The link: an attempt to connect, connected, failed. */
    Link,
    /** The live socket opening or closing. */
    Socket,
    /** A wait: the next try, a re-check. */
    Wait,
    /** What the Mac said about itself: its local addresses. */
    Mac,
}

/** How it went. [Info] is news without a verdict. */
enum class ConnResult {
    Ok,
    /** Something answered at the address, but nothing listens on the port. */
    Refused,
    /** No answer in time. */
    Timeout,
    /** No way to the address (no route, the network down). */
    Unreachable,
    /** The name doesn't resolve. */
    NoName,
    /** A server answered with a certificate that isn't the paired one. */
    PinWrong,
    /** The paired certificate, out of its dates. */
    PinExpired,
    /** The secure connection failed otherwise. */
    Tls,
    /** The daemon answered with an error status (the detail says which). */
    Http,
    /** A socket closed. */
    Closed,
    /** Failed some other way (the detail says how). */
    Failed,
    /** Not tried (the detail says why). */
    Skipped,
    Info;

    val ok: Boolean get() = this == Ok
    /** A verdict that the route doesn't work right now. */
    val failed: Boolean get() = this != Ok && this != Skipped && this != Info
}

/** One line of the log. */
@Immutable
data class ConnEntry(
    /** When (epoch ms). */
    val at: Long,
    val kind: ConnKind,
    val result: ConnResult = ConnResult.Info,
    val route: RouteKind? = null,
    /** Where: `192.168.0.149:47323`, `10.0.2.2:47321`. A host and port, never a URL. */
    val target: String? = null,
    /** A short phrase: what happened, or why. */
    val text: String? = null,
    /** How long it took. */
    val ms: Long? = null,
)

/** The newest [capacity] entries, oldest first inside (nothing of Android: unit-tested). */
class ConnRing(private val capacity: Int) {
    private val items = ArrayDeque<ConnEntry>()

    @Synchronized fun add(e: ConnEntry) {
        items.addLast(e)
        while (items.size > capacity) items.removeFirst()
    }

    /** Older entries (from disk) in front of what's here, still at most [capacity]. */
    @Synchronized fun prepend(older: List<ConnEntry>) {
        val first = items.firstOrNull()?.at ?: Long.MAX_VALUE
        val keep = older.filter { it.at <= first }
        for (e in keep.asReversed()) items.addFirst(e)
        while (items.size > capacity) items.removeFirst()
    }

    @Synchronized fun newestFirst(): List<ConnEntry> = items.reversed()

    @Synchronized fun last(n: Int): List<ConnEntry> = items.toList().takeLast(n)

    @Synchronized fun clear() = items.clear()

    val size: Int @Synchronized get() = items.size
}

/**
 * Keeps secrets out of the log: a URL becomes its host and port, bearer and basic credentials, the
 * daemon's tokens and any long run of key-like characters are cut, and a phrase is
 * at most [MAX] characters.
 */
object ConnScrub {
    const val MAX = 200
    private val url = Regex("""\b[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/\s?#]*@)?([^/\s?#]+)[^\s]*""")
    private val credential = Regex("""(?i)\b(bearer|basic)\s+\S+""")
    private val token = Regex("""\b(?:hn[a-z]?_[A-Za-z0-9_-]{4,})""")
    private val query = Regex("""[?&][A-Za-z0-9_.-]+=[^\s&]*""")
    private val longRun = Regex("""[A-Za-z0-9_+/=-]{32,}""")

    fun text(s: String?): String? {
        if (s == null) return null
        var t = url.replace(s) { it.groupValues[1] }
        t = credential.replace(t) { "${it.groupValues[1]} …" }
        t = token.replace(t, "…")
        t = query.replace(t, "")
        t = longRun.replace(t) { it.value.take(6) + "…" }
        t = t.replace(Regex("\\s+"), " ").trim()
        return t.take(MAX).ifEmpty { null }
    }

    /** `host:port` of a base URL (`https://192.168.0.149:47323` → `192.168.0.149:47323`; an IPv6 host in brackets). */
    fun hostPort(base: String?): String? {
        val o = base?.let(RoutePolicy::originOf) ?: return null
        val h = if (o.host.contains(':')) "[${o.host}]" else o.host
        return "$h:${o.port}"
    }

    fun entry(e: ConnEntry): ConnEntry = e.copy(target = text(e.target), text = text(e.text))
}

/** The log as text (copied to send to someone) and its times (nothing of Android: unit-tested). */
object ConnText {
    /** "now", "12s", "4m", "2h", "3d": how long ago, in the screen's mono. */
    fun ago(now: Long, at: Long): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        return when {
            s < 5 -> "now"
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86_400 -> "${s / 3600}h"
            else -> "${s / 86_400}d"
        }
    }

    /** A route's name in the copied text. */
    fun routeName(r: RouteKind?): String = when (r) {
        RouteKind.Local -> "local"
        RouteKind.Remote -> "base-url"
        null -> "-"
    }

    /** One line: time, kind, route, target, result, duration, phrase; columns apart by two spaces, nothing secret. */
    fun line(e: ConnEntry, time: (Long) -> String = ::clock): String = listOfNotNull(
        time(e.at),
        e.kind.name.lowercase().padEnd(7),
        routeName(e.route).padEnd(8),
        e.result.name.lowercase().padEnd(11),
        e.target,
        e.ms?.let { "${it}ms" },
        e.text,
    ).joinToString("  ").trimEnd()

    /** The whole log, oldest first (the order a reader follows a story in), under a header. */
    fun copy(entries: List<ConnEntry>, header: String, time: (Long) -> String = ::clock): String =
        buildString {
            appendLine(header)
            for (e in entries.sortedBy { it.at }) appendLine(line(e, time))
        }

    private fun clock(at: Long): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(at))

    fun encode(e: ConnEntry): String = JSONObject().apply {
        put("at", e.at)
        put("k", e.kind.name)
        put("r", e.result.name)
        e.route?.let { put("rt", it.name) }
        e.target?.let { put("t", it) }
        e.text?.let { put("x", it) }
        e.ms?.let { put("ms", it) }
    }.toString()

    fun decode(line: String): ConnEntry? = runCatching {
        val o = JSONObject(line)
        ConnEntry(
            at = o.getLong("at"),
            kind = ConnKind.valueOf(o.getString("k")),
            result = runCatching { ConnResult.valueOf(o.getString("r")) }.getOrDefault(ConnResult.Info),
            route = o.optString("rt").takeIf { it.isNotEmpty() }?.let { r -> runCatching { RouteKind.valueOf(r) }.getOrNull() },
            target = o.optString("t").takeIf { it.isNotEmpty() },
            text = o.optString("x").takeIf { it.isNotEmpty() },
            ms = if (o.has("ms")) o.getLong("ms") else null,
        )
    }.getOrNull()
}

/** The app's one connection log. Entries may come from any thread. */
object ConnLog {
    const val CAPACITY = 600
    /** How many of the newest are kept on disk. */
    const val KEPT = 250
    private const val SAVE_AFTER_MS = 3_000L

    private val ring = ConnRing(CAPACITY)
    /** Bumped (on the main thread) with every new entry: the screen reads [entries] again. */
    var version by mutableIntStateOf(0)
        private set
    private var file: File? = null
    private val main by lazy { runCatching { Handler(Looper.getMainLooper()) }.getOrNull() }
    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "connlog").apply { isDaemon = true } }
    private val bumpPosted = AtomicBoolean(false)
    private val savePosted = AtomicBoolean(false)

    /** Reads the tail kept on disk (in the background); entries added meanwhile stay after it. */
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "connlog.jsonl")
        file = f
        io.execute {
            val older = runCatching { f.readLines().mapNotNull(ConnText::decode) }.getOrDefault(emptyList())
            if (older.isNotEmpty()) {
                ring.prepend(older)
                bump()
            }
        }
    }

    fun add(e: ConnEntry) {
        val clean = ConnScrub.entry(e)
        ring.add(clean)
        if (BuildConfig.DEBUG) runCatching { android.util.Log.d("HypernovaConn", ConnText.line(clean)) }
        bump()
        save()
    }

    fun add(kind: ConnKind, result: ConnResult = ConnResult.Info, route: RouteKind? = null, target: String? = null, text: String? = null, ms: Long? = null) =
        add(ConnEntry(System.currentTimeMillis(), kind, result, route, target, text, ms))

    /** Newest first. */
    fun entries(): List<ConnEntry> = ring.newestFirst()

    private fun bump() {
        val h = main ?: return
        if (!bumpPosted.compareAndSet(false, true)) return
        h.post {
            bumpPosted.set(false)
            version++
        }
    }

    private fun save() {
        val f = file ?: return
        if (!savePosted.compareAndSet(false, true)) return
        io.schedule({
            savePosted.set(false)
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(ring.last(KEPT).joinToString("\n", postfix = "\n", transform = ConnText::encode))
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }, SAVE_AFTER_MS, TimeUnit.MILLISECONDS)
    }
}
