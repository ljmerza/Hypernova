package com.prudnikweb.hypernova.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.prudnikweb.hypernova.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** A [DraftFile]'s preview on Android. */
val DraftFile.thumbImage: ImageBitmap? get() = thumb as? ImageBitmap

/**
 * What the owner picked (a content:// Uri), read for upload. Photos are scaled to 2048 px on
 * the long side and saved as JPEG (PNG when they have transparency), which also stands them
 * upright and drops their location; PNG screenshots that are already small enough go as they
 * are. Other files stream as they are, up to 30 MB.
 */
object Picked {
    const val MAX_BYTES = 30L * 1024 * 1024
    private const val MAX_SIDE = 2048

    /** The file's name and size as the picker reports them (size -1 when unknown). */
    fun info(context: Context, uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        var n = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        // The photo picker names a photo by its number ("20.jpg").
        if (uri.authority == "media" && n.substringBeforeLast('.').all { it.isDigit() }) n = "photo." + n.substringAfterLast('.', "jpg")
        return n to size
    }

    fun mime(context: Context, uri: Uri): String? = context.contentResolver.getType(uri)

    suspend fun read(context: Context, uri: Uri): Upload = withContext(Dispatchers.IO) {
        val (name, size) = info(context, uri)
        val mime = mime(context, uri) ?: "application/octet-stream"
        if (isImage(mime, name) && mime != "image/gif") photo(context, uri, name, mime, size)?.let { return@withContext it }
        if (size > MAX_BYTES) throw HubException(context.getString(R.string.attach_too_big))
        val cr = context.contentResolver
        // Fails now (a Drive document that isn't a file, say) rather than halfway through the upload.
        val readable = try { cr.openInputStream(uri)?.use { it.read() >= 0 } ?: false } catch (e: Exception) { false }
        if (!readable) throw HubException(context.getString(R.string.attach_unreadable))
        Upload(name, mime, -1) { out ->
            (cr.openInputStream(uri) ?: throw IOException("unreadable")).use { it.copyTo(out, 64 * 1024) }
        }
    }

    /** A photo scaled down and re-encoded, or null when it should go as it is (or can't be decoded). */
    private fun photo(context: Context, uri: Uri, name: String, mime: String, size: Long): Upload? {
        val cr = context.contentResolver
        val png = mime == "image/png"
        if (png && size in 1..5L * 1024 * 1024) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } }
            if (o.outWidth > 0 && max(o.outWidth, o.outHeight) <= MAX_SIDE) return null
        }
        // Decoded straight from the picker at the size it ends up, so even a huge photo is fine.
        val bmp = runCatching { decode(ImageDecoder.createSource(cr, uri), MAX_SIDE) }.getOrNull() ?: return null
        val lossless = png || bmp.hasAlpha()
        val out = ByteArrayOutputStream()
        bmp.compress(if (lossless) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 88, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        val base = name.substringBeforeLast('.', name)
        return if (lossless) Upload("$base.png", "image/png", bytes.size.toLong()) { it.write(bytes) }
        else Upload("$base.jpg", "image/jpeg", bytes.size.toLong()) { it.write(bytes) }
    }

    /** A small preview for the composer. */
    suspend fun thumbnail(context: Context, uri: Uri, px: Int = 256): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching { decode(ImageDecoder.createSource(context.contentResolver, uri), px).asImageBitmap() }.getOrNull()
    }

    /** Decoded upright, at most [side] px on its long side. */
    private fun decode(src: ImageDecoder.Source, side: Int): Bitmap = ImageDecoder.decodeBitmap(src) { d, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val k = side.toFloat() / max(w, h)
        if (k < 1f) d.setTargetSize((w * k).roundToInt().coerceAtLeast(1), (h * k).roundToInt().coerceAtLeast(1))
        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }

    /** A thumbnail of image bytes from the Mac. */
    fun thumbnail(bytes: ByteArray, px: Int = 320): ImageBitmap? = runCatching {
        decode(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), px).asImageBitmap()
    }.getOrNull()
}

/** Thumbnails of attached pictures by their path on the Mac, so a sent one shows at once. */
object Thumbs {
    private val cache = LruCache<String, ImageBitmap>(40)
    fun get(path: String): ImageBitmap? = cache.get(path)
    fun put(path: String, image: ImageBitmap) {
        cache.put(path, image)
    }
}
