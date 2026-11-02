package com.prudnikweb.hypernova.data

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * The debug log's files (data/DebugLog.kt): a rotating log, about 5 MB in all, and a small file of crashes
 * apart from it. Neither is touched from the main thread, and neither creates anything until there's
 * something to write. Folders and files are their owner's alone, which on a phone the app's private
 * folder is anyway, and on a desktop has to be said.
 */

private fun File.ownerOnly(folder: Boolean = false) {
    setReadable(false, false)
    setWritable(false, false)
    setExecutable(false, false)
    setReadable(true, true)
    setWritable(true, true)
    if (folder) setExecutable(true, true)
}

private fun ensureFolder(dir: File) {
    if (!dir.isDirectory) dir.mkdirs()
    dir.ownerOnly(folder = true)
}

/** What's at the head of each log file: which app on which device, so a file read alone says what it's from. */
class DebugHead(val version: String, val build: String, val os: String, val device: String) {
    /** The same facts in one sentence, for each start and stop. */
    fun said(): String = "version $version, $build, $os, $device"

    fun text(now: Long): String = buildString {
        appendLine("# Hypernova debug log")
        appendLine("# App: version ${DebugScrub.line(version)}, ${DebugScrub.line(build)}")
        appendLine("# System: ${DebugScrub.line(os)}")
        appendLine("# Device: ${DebugScrub.line(device)}")
        appendLine("# Times are this device's clock, ${SimpleDateFormat("'UTC'XXX", Locale.US).format(Date(now))}")
        appendLine("# No prompts, replies, commands, file contents, session or project names, tokens or keys: ids, sizes and what happened.")
    }
}

/**
 * The log: `debug.log`, then `debug.1.log` and on, the oldest going first, [fileMax] each and [files] of
 * them, so never more than their product. Not thread-safe: [DebugLogger] holds a lock around it.
 */
class RotatingLog(
    private val dir: File,
    private val head: () -> String,
    private val fileMax: Long = FILE_MAX,
    private val files: Int = FILES,
) {
    private var out: OutputStream? = null
    private var written = 0L

    private val current get() = File(dir, "debug.log")
    private fun older(n: Int) = File(dir, "debug.$n.log")

    fun write(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var o = out ?: open()
        if (written + bytes.size > fileMax) {
            rotate()
            o = checkNotNull(out)
        }
        o.write(bytes)
        written += bytes.size
    }

    private fun open(): OutputStream {
        ensureFolder(dir)
        val f = current
        val fresh = !f.exists() || f.length() == 0L
        val o = BufferedOutputStream(FileOutputStream(f, true), 64 * 1024)
        f.ownerOnly()
        out = o
        written = f.length()
        if (written >= fileMax) {
            // A run before this one filled it.
            rotate()
            return checkNotNull(out)
        }
        if (fresh) {
            val h = head().toByteArray(Charsets.UTF_8)
            o.write(h)
            written += h.size
        }
        return o
    }

    private fun rotate() {
        close()
        older(files - 1).delete()
        for (n in files - 2 downTo 1) older(n).takeIf { it.exists() }?.renameTo(older(n + 1))
        current.takeIf { it.exists() }?.renameTo(older(1))
        open()
    }

    fun flush() {
        out?.flush()
    }

    fun close() {
        runCatching { out?.close() }
        out = null
        written = 0
    }

    fun clear() {
        close()
        for (f in inOrder()) f.delete()
    }

    /** The files that exist, oldest first: the order a story is read in. */
    fun inOrder(): List<File> = (files - 1 downTo 1).map(::older).filter { it.exists() } + listOf(current).filter { it.exists() }

    /** What's on disk, with what's still in the buffer. */
    fun size(): Long = inOrder().sumOf { it.length() }

    companion object {
        const val FILE_MAX = 1024L * 1024
        const val FILES = 5
        /** All of them at most, in the words the screens use. */
        const val TOTAL = FILE_MAX * FILES
    }
}

/**
 * Crashes, stack traces only, saved with the switch on or off: [record] runs in the uncaught exception
 * handler, on the thread that's dying, so it's small, direct and bounded. The newest are kept when it
 * would pass [max].
 */
class CrashFile(private val dir: File, private val version: () -> String, private val clock: () -> Long = System::currentTimeMillis, private val max: Int = MAX) {
    val file: File get() = File(dir, "crashes.log")

    fun record(thread: Thread, t: Throwable) {
        val text = buildString {
            append(MARK).append(' ').append(stamp(clock())).append(", version ").append(DebugScrub.line(version()))
            append(", thread ").appendLine(DebugScrub.line(thread.name))
            for (l in DebugScrub.throwable(t, messages = false)) appendLine(l)
        }
        ensureFolder(dir)
        val had = runCatching { file.readText() }.getOrDefault("")
        val all = trimmed(had + text)
        FileOutputStream(file, false).use { it.write(all.toByteArray(Charsets.UTF_8)) }
        file.ownerOnly()
    }

    /** At most [max], from a crash's start so what's left reads whole; one crash alone past [max] keeps its top, where the cause is. */
    private fun trimmed(all: String): String {
        if (all.toByteArray(Charsets.UTF_8).size <= max) return all
        var at = all.indexOf(MARK, 1)
        while (at > 0) {
            val rest = all.substring(at)
            if (rest.toByteArray(Charsets.UTF_8).size <= max) return rest
            at = all.indexOf(MARK, at + 1)
        }
        val last = all.lastIndexOf(MARK).coerceAtLeast(0)
        return all.substring(last).take(max / 2)
    }

    fun size(): Long = if (file.exists()) file.length() else 0L

    fun clear() {
        file.delete()
    }

    private fun stamp(at: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'XXX", Locale.US).format(Date(at))

    companion object {
        const val MAX = 64 * 1024
        const val MARK = "=== Crash"
    }
}

/** One text file of everything to send or share: the crashes first, then the log, oldest first. */
object DebugBundle {
    fun write(to: File, log: RotatingLog, crashes: CrashFile, now: Long, device: String): Long {
        ensureFolder(checkNotNull(to.parentFile))
        FileOutputStream(to, false).buffered().use { out ->
            out.write("# Hypernova debug logs from ${DebugScrub.line(device)}, gathered ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'XXX", Locale.US).format(Date(now))}\n\n".toByteArray())
            if (crashes.size() > 0) {
                out.write("# Crashes, saved whether debug logging was on or not\n".toByteArray())
                crashes.file.inputStream().use { it.copyTo(out) }
                out.write("\n".toByteArray())
            }
            for (f in log.inOrder()) f.inputStream().use { it.copyTo(out) }
        }
        to.ownerOnly()
        return to.length()
    }

    /** The name a bundle goes under: nothing in it but the moment. */
    fun name(now: Long): String = "hypernova-debug-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(now)) + ".log"
}
