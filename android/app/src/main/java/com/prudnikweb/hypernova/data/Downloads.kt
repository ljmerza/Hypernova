package com.prudnikweb.hypernova.data

import java.io.File
import java.text.Normalizer
import java.util.Locale

/** Where a download stands. Pure: the screen's job feeds it [DlEvent]s through [reduce]. */
sealed interface DlState {
    data object Idle : DlState
    /** [total] is unknown (-1) until the Mac answers. */
    data class Running(val done: Long, val total: Long) : DlState
    data class Done(val bytes: Long) : DlState
    /** Stopped by a failure; [done] bytes are kept for a resume when the problem is [FileProblem.retryable]. */
    data class Failed(val problem: FileProblem, val done: Long) : DlState
}

sealed interface DlEvent {
    /** [kept]: bytes of an earlier try still on disk. */
    data class Start(val kept: Long = 0) : DlEvent
    data class Progress(val done: Long, val total: Long) : DlEvent
    data class Finished(val bytes: Long) : DlEvent
    data class Failure(val problem: FileProblem) : DlEvent
    data object Cancel : DlEvent
}

fun DlState.reduce(e: DlEvent): DlState = when (e) {
    is DlEvent.Start -> when (this) {
        is DlState.Running, is DlState.Done -> this
        is DlState.Failed -> DlState.Running(if (problem.retryable) maxOf(done, e.kept) else 0L, -1L)
        DlState.Idle -> DlState.Running(e.kept, -1L)
    }
    is DlEvent.Progress -> if (this is DlState.Running) DlState.Running(maxOf(done, e.done), if (e.total > 0) e.total else total) else this
    is DlEvent.Finished -> if (this is DlState.Running) DlState.Done(e.bytes) else this
    is DlEvent.Failure -> if (this is DlState.Running) DlState.Failed(e.problem, done) else this
    // Cancel throws the part away: nothing is left to resume from.
    DlEvent.Cancel -> if (this is DlState.Running) DlState.Idle else this
}

/** A resume: carry on from byte [from] of the file whose `ETag` was [etag] (`Range` + `If-Range`). */
data class DownloadResume(val from: Long, val etag: String)

/** What a finished request gave: the whole file's size, its `ETag`, and where the written part began. */
data class Downloaded(val total: Long, val etag: String?)

object Downloads {
    /** A resume only from a known file: a part on disk, the `ETag` it came with, and not already whole. */
    fun resumePlan(partial: Long, etag: String?, total: Long?): DownloadResume? =
        if (partial > 0 && !etag.isNullOrEmpty() && (total == null || total <= 0 || partial < total)) DownloadResume(partial, etag) else null

    /** Where writing starts for an answer: 206 carries on only from exactly the byte asked for; anything else is the whole file. */
    data class Placement(val offset: Long, val total: Long)

    fun place(status: Int, askedFrom: Long, contentRange: String?, contentLength: Long): Placement? {
        if (status == 206) {
            val m = Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").matchEntire(contentRange?.trim().orEmpty()) ?: return null
            val start = m.groupValues[1].toLong()
            val total = m.groupValues[3].toLongOrNull() ?: (start + contentLength)
            return if (start == askedFrom) Placement(start, total) else null
        }
        return Placement(0L, contentLength)
    }

    /** A local file name from the Mac's: one path segment, no characters a file system or the picker dislikes, any script kept. */
    fun safeName(raw: String, max: Int = 120): String {
        var n = Normalizer.normalize(raw.substringAfterLast('/').substringAfterLast('\\'), Normalizer.Form.NFC)
        n = n.map { if (it.code < 32 || it in "\\/:*?\"<>|") '_' else it }.joinToString("").trim().trim('.')
        if (n.isEmpty()) return "file"
        if (n.length > max) {
            val dot = n.lastIndexOf('.')
            val ext = if (dot > 0 && n.length - dot <= 12) n.substring(dot) else ""
            var cut = max - ext.length
            // Never between the halves of a surrogate pair.
            if (cut > 0 && cut < n.length && Character.isLowSurrogate(n[cut])) cut--
            n = n.substring(0, maxOf(cut, 1)) + ext
        }
        return n
    }

    /** A short stable folder name for a path, so two files of one name don't share a cache file. */
    fun key(path: String): String = String.format(Locale.ROOT, "%08x", path.hashCode())

    private val MIME = mapOf(
        "pdf" to "application/pdf", "zip" to "application/zip", "gz" to "application/gzip", "tar" to "application/x-tar",
        "json" to "application/json", "csv" to "text/csv", "txt" to "text/plain", "md" to "text/markdown", "html" to "text/html",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
        "mp4" to "video/mp4", "mov" to "video/quicktime", "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "wav" to "audio/wav",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "apk" to "application/vnd.android.package-archive",
    )

    /** The type to hand another app: the Mac's when it knows one, else by extension. */
    fun mimeFor(name: String, served: String?): String {
        if (!served.isNullOrBlank() && served != "application/octet-stream") return served
        return MIME[name.substringAfterLast('.', "").lowercase(Locale.ROOT)] ?: "application/octet-stream"
    }

    /** Extensions with no preview on the phone: the viewer goes straight to Share / Save / Open with, loading nothing. */
    private val NO_PREVIEW = setOf(
        "pdf", "zip", "gz", "tgz", "tar", "7z", "rar", "mp4", "mov", "mkv", "avi", "webm", "mp3", "m4a", "wav", "flac", "aac",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pages", "numbers", "key", "dmg", "pkg", "apk", "aab", "jar", "exe", "iso", "sqlite", "db",
    )

    fun previewable(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) !in NO_PREVIEW

    /** Deletes downloads older than [maxAgeMs] under [dir] (the cache keeps no copy of a big file for long). */
    fun sweep(dir: File, now: Long, maxAgeMs: Long = 24L * 3600_000L) {
        dir.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                sweep(f, now, maxAgeMs)
                if (f.listFiles().isNullOrEmpty()) f.delete()
            } else if (now - f.lastModified() > maxAgeMs) f.delete()
        }
    }
}
