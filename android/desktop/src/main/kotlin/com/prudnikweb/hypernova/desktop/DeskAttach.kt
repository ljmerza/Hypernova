package com.prudnikweb.hypernova.desktop

import androidx.compose.ui.graphics.ImageBitmap
import com.prudnikweb.hypernova.data.Upload
import com.prudnikweb.hypernova.data.isImage as picture
import com.prudnikweb.hypernova.ui.platform.AttachSource
import com.prudnikweb.hypernova.ui.platform.SharedUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.net.URLConnection
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Files from a computer, for a prompt, by the phone's rules (its data/Picked.kt): a photo goes at most 2048 px on its long side as a JPEG, or a PNG if it was one; a PNG
 * small enough goes as it is, and so does a GIF. Anything else streams as it is, up to 30 MB. What Skia can't read goes as it is too.
 */
object DeskAttach : AttachSource {
    const val MAX_BYTES = 30L * 1024 * 1024
    private const val MAX_SIDE = 2048
    private const val SMALL_PNG = 5L * 1024 * 1024
    private const val JPEG_QUALITY = 88

    private fun mime(f: File): String =
        runCatching { Files.probeContentType(f.toPath()) }.getOrNull() ?: URLConnection.guessContentTypeFromName(f.name) ?: "application/octet-stream"

    override fun nameAndSize(source: Any): Pair<String, Long> = (source as File).let { it.name to it.length() }

    override fun isImage(source: Any, name: String): Boolean = picture(mime(source as File), name)

    /** A photo is made smaller, as on the phone, so only other files can be too big. */
    override fun fits(image: Boolean, size: Long): Boolean = image || size <= MAX_BYTES

    override suspend fun thumbnail(source: Any): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { SharedUi.platform.images.thumbnail((source as File).readBytes()) }.getOrNull()
    }

    override suspend fun read(source: Any): Upload = withContext(Dispatchers.IO) {
        val f = source as File
        val mime = mime(f)
        if (picture(mime, f.name) && mime != "image/gif") photo(f, mime)?.let { return@withContext it }
        Upload(f.name, mime, f.length()) { out -> f.inputStream().use { it.copyTo(out, 64 * 1024) } }
    }

    /** A photo scaled down and encoded again, or null when it goes as it is. */
    private fun photo(f: File, mime: String): Upload? {
        val png = mime == "image/png"
        val image = runCatching { Image.makeFromEncoded(f.readBytes()) }.getOrNull() ?: return null
        val side = max(image.width, image.height)
        if (png && f.length() <= SMALL_PNG && side <= MAX_SIDE) return null
        val k = minOf(1f, MAX_SIDE.toFloat() / side)
        val w = (image.width * k).roundToInt().coerceAtLeast(1)
        val h = (image.height * k).roundToInt().coerceAtLeast(1)
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(image, Rect.makeWH(w.toFloat(), h.toFloat()))
        val bytes = surface.makeImageSnapshot().encodeToData(if (png) EncodedImageFormat.PNG else EncodedImageFormat.JPEG, JPEG_QUALITY)?.bytes ?: return null
        val base = f.name.substringBeforeLast('.', f.name)
        return if (png) Upload("$base.png", "image/png", bytes.size.toLong()) { it.write(bytes) }
        else Upload("$base.jpg", "image/jpeg", bytes.size.toLong()) { it.write(bytes) }
    }

    override suspend fun discard(source: Any) {
        withContext(Dispatchers.IO) { runCatching { (source as File).delete() } }
    }
}
