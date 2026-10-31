package com.prudnikweb.hypernova.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Images pasted into the prompt box (owner, 2026-10-30): from the clipboard, or a keyboard's image
 * (Gboard's or Samsung's screenshot suggestion, a GIF), they become attachments as the paperclip
 * adds them (ui/session/Attach.kt). Anything else pasted (text) stays the box's own paste.
 */
object Pasted {
    /**
     * Whether a pasted item is an image to attach: by its own type ([itemMime], the content
     * resolver's), else by the clip's types ([clipMimes]) when they're all images. SVG is text.
     */
    fun isImage(itemMime: String?, clipMimes: List<String>): Boolean {
        val m = itemMime?.lowercase(Locale.ROOT)
        if (m != null) return image(m)
        return clipMimes.isNotEmpty() && clipMimes.all { image(it.lowercase(Locale.ROOT)) }
    }

    private fun image(m: String) = m.startsWith("image/") && m != "image/svg+xml" && m != "image/*"

    /** The file extension for an image type: "png" for image/png, "jpg" for a JPEG, "img" for a type it doesn't know. */
    fun extension(mime: String?): String = when (mime?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/bmp" -> "bmp"
        else -> "img"
    }

    /** A pasted image's name, as the Mac keeps it: "pasted-20261030-142501-1.png". */
    fun name(mime: String?, at: Date, n: Int): String =
        "pasted-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(at)}${if (n > 1) "-$n" else ""}.${extension(mime)}"

    /**
     * [uri] copied into the app's cache at once (a keyboard's grant may not outlast the upload),
     * as a Uri of the app's own; null when it couldn't be read. Old copies are cleared.
     */
    suspend fun keep(context: Context, uri: Uri, n: Int, at: Date): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "pasted").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
            val mime = context.contentResolver.getType(uri)
            // Never over one still on its way (two pastes in the same second).
            var k = n
            var f = File(dir, name(mime, at, k))
            while (f.exists()) f = File(dir, name(mime, at, ++k))
            (context.contentResolver.openInputStream(uri) ?: return@runCatching null).use { input -> f.outputStream().use { input.copyTo(it) } }
            FileProvider.getUriForFile(context, "${context.packageName}.files", f)
        }.getOrNull()
    }
}
