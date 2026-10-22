package com.prudnikweb.hypernova.data

import kotlinx.coroutines.delay
import java.io.File

/** The demo's Mac folders: a few projects, a locked key file, a 300 MB video, and one folder for each way a folder can fail. */
object FilesDemo {
    private const val ROOT = "~/Programming"
    private val now get() = System.currentTimeMillis()

    private fun dir(parent: String, name: String) = FileEntry(name, "$parent/$name", true, 0, now - 3_600_000, null)
    private fun file(parent: String, name: String, size: Long, mime: String?, agoMs: Long = 600_000, locked: String? = null) =
        FileEntry(name, "$parent/$name", false, size, now - agoMs, mime, locked)

    private const val APP = "$ROOT/hypernova"
    private val sizes = mapOf(
        "$APP/report.pdf" to 2_400_000L,
        "$APP/отчёт.pdf" to 880_000L,
        "$APP/build.mov" to 300L * 1024 * 1024,
        "$APP/export.zip" to 25L * 1024 * 1024,
        "$APP/dump.bin" to 700L * 1024 * 1024,
    )

    private fun fail(status: Int, code: String, message: String) =
        Result.failure<Nothing>(FileFailure(message, Problem.Server, status, code, projectsRoot = ROOT))

    fun listing(path: String): Result<Listing> {
        val p = path.trimEnd('/')
        return when (p) {
            ROOT -> Result.success(Listing(p, listOf(dir(p, "hypernova"), dir(p, "0stems"), dir(p, "notes"))))
            APP -> Result.success(
                Listing(
                    p,
                    listOf(
                        dir(p, "src"), dir(p, "docs"), dir(p, "many"), dir(p, "home-link"), dir(p, "old-build"), dir(p, "Documents"),
                        file(p, "README.md", 3_100, "text/markdown"),
                        file(p, "report.pdf", sizes.getValue("$APP/report.pdf"), "application/pdf", 3_600_000),
                        file(p, "отчёт.pdf", sizes.getValue("$APP/отчёт.pdf"), "application/pdf", 7_200_000),
                        file(p, "screenshot.png", 18_000, "image/png", 120_000),
                        file(p, "build.mov", sizes.getValue("$APP/build.mov"), "video/quicktime", 86_400_000),
                        file(p, "export.zip", sizes.getValue("$APP/export.zip"), "application/zip", 5_000_000),
                        file(p, "dump.bin", sizes.getValue("$APP/dump.bin"), null, 9_000_000),
                        file(p, ".env", 412, null, 9_000_000, locked = "secret"),
                        file(p, "notes-link", 0, null, 9_000_000, locked = "outside"),
                    ),
                ),
            )
            "$APP/many" -> Result.success(Listing(p, (1..40).map { file(p, "frame-%04d.png".format(it), 40_000L + it * 100, "image/png", it * 60_000L) }, truncated = true, total = 12_431))
            "$APP/home-link" -> fail(403, "outside_projects", "")
            "$APP/old-build" -> fail(404, "not_found", "")
            "$APP/Documents" -> fail(403, "mac_privacy", "")
            else -> Result.success(Listing(p, emptyList()))
        }
    }

    fun file(path: String): Result<FileBytes> = when {
        path.endsWith(".md") -> Result.success(FileBytes("# Hypernova\n\nA control room for Claude Code.\n\n- sessions\n- approvals\n- files".toByteArray(), "text/markdown"))
        path.endsWith(".png") -> Result.success(FileBytes(png(), "image/png"))
        path.endsWith("dump.bin") -> Result.failure(FileFailure("", Problem.Server, 413, "too_large", size = 700L * 1024 * 1024, max = 20L * 1024 * 1024))
        path.endsWith("gone.txt") -> fail(404, "attachment_gone", "")
        else -> Result.success(FileBytes(ByteArray(0), "application/octet-stream"))
    }

    private fun png(): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(640, 400, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(0xFF14110E.toInt())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFF7FFFA0.toInt(); c.drawCircle(320f, 200f, 60f, paint)
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = 0x887FFFA0.toInt()
        for (r in listOf(110f, 160f, 190f)) c.drawCircle(320f, 200f, r, paint)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** A slow pretend download (so progress, Cancel and resume can be seen); the file written is a few bytes. */
    suspend fun download(source: String, target: File, resume: DownloadResume?, onStart: (String?) -> Unit, onProgress: (Long, Long) -> Unit): Result<Downloaded> {
        val total = sizes[source] ?: return Result.failure(FileFailure("", Problem.Server, 404, "not_found"))
        if (source.endsWith("dump.bin")) return Result.failure(FileFailure("", Problem.Server, 413, "too_large", size = 700L * 1024 * 1024, max = 500L * 1024 * 1024))
        onStart("\"demo\"")
        var done = resume?.from ?: 0L
        val step = maxOf(total / 40, 1L)
        while (done < total) {
            delay(if (total > 100_000_000) 350 else 120)
            done = minOf(total, done + step)
            onProgress(done, total)
        }
        target.parentFile?.mkdirs()
        target.writeText("%PDF-1.4 demo of ${source.substringAfterLast('/')}\n")
        return Result.success(Downloaded(total, "\"demo\""))
    }
}
