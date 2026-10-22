package com.prudnikweb.hypernova.ui.share

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.prudnikweb.hypernova.data.Picked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Shared files are copied into the app's cache the moment the share screen opens. The sender
 * only lends them while that screen is alive, and the upload to the Mac may still be running
 * after the app has taken over, so a copy of our own is the one thing that can't be pulled away.
 * The files in the cache are removed by the composer once uploaded, and anything older than a
 * day here is swept at the next share.
 */
object ShareStage {
    private const val DIR = "shared"
    private const val DAY = 24 * 3600_000L

    /** What was staged, and what could not be (a file too big or unreadable). */
    class Result(val uris: List<Uri>, val failed: Int, val tooBig: Int)

    private fun root(context: Context) = File(context.cacheDir, DIR)

    fun sweep(context: Context) {
        val old = System.currentTimeMillis() - DAY
        root(context).listFiles()?.forEach { if (it.lastModified() < old) it.deleteRecursively() }
    }

    suspend fun stage(context: Context, sources: List<Uri>): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val out = ArrayList<Uri>()
        var failed = 0
        var tooBig = 0
        for (src in sources) {
            val (name, size) = Picked.info(app, src)
            // A photo is scaled down on its way, so its original size doesn't matter.
            val photo = Picked.mime(app, src)?.startsWith("image/") == true
            if (!photo && size > Picked.MAX_BYTES) { tooBig++; continue }
            val dir = File(root(app), UUID.randomUUID().toString().take(8)).apply { mkdirs() }
            val file = File(dir, name.replace('/', '_').take(120).ifBlank { "file" })
            try {
                var copied = 0L
                (app.contentResolver.openInputStream(src) ?: throw java.io.IOException("unreadable")).use { input ->
                    file.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            copied += n
                            if (!photo && copied > Picked.MAX_BYTES) throw java.io.IOException("too big")
                            o.write(buf, 0, n)
                        }
                    }
                }
                out += FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            } catch (e: Exception) {
                dir.deleteRecursively()
                if (e.message == "too big") tooBig++ else failed++
            }
        }
        Result(out, failed, tooBig)
    }
}
