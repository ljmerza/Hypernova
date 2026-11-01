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

/** Whether a pasted file is an image, going by the types it says it has. */
enum class PasteKind {
    /** Its types say so. */
    Image,
    /** They don't say (none, or a generic one): its first bytes decide once it's read ([Pasted.typeOf]). */
    Maybe,
    /** Its own type says it's something else. */
    NotImage,
}

/** Where one pasted item goes. */
enum class PasteTo {
    /** The box's own paste: its text. */
    Box,
    /** An attachment, as the paperclip adds one. */
    Attach,
    /** Both: an image with words of its own, the words going in the box. */
    AttachAndText,
    /** A web address with no text of its own: it goes in the box as words. */
    Address,
    /** Nothing here can use it: the owner hears so rather than nothing happening. */
    Refuse,
}

/** A pasted image on its way to being attached, and what its types said. */
class PastedImage(val uri: Uri, val kind: PasteKind)

/**
 * Images pasted into a box with a paperclip (owner, 2026-10-30; 2026-11-01: not only ones that say
 * they are): from the clipboard, or a keyboard's image (Gboard's or Samsung's screenshot suggestion,
 * a GIF), they become attachments as the paperclip adds them (ui/session/Attach.kt). Anything else
 * pasted (text) stays the box's own paste, and what neither can use says so.
 */
object Pasted {
    /**
     * What a pasted file's types say: its own ([itemMime], the content resolver's) when it's
     * specific, else the clip's ([clipMimes]) when they're all images. A provider that answers with
     * nothing or a generic type (a screenshot copied from some apps), or a clip described as
     * `text/uri-list`, says nothing either way. SVG is text.
     */
    fun kind(itemMime: String?, clipMimes: List<String>): PasteKind {
        val m = itemMime?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()
        if (m != null && !generic(m)) return if (image(m)) PasteKind.Image else PasteKind.NotImage
        return if (clipMimes.isNotEmpty() && clipMimes.all { image(it.lowercase(Locale.ROOT)) }) PasteKind.Image else PasteKind.Maybe
    }

    private fun generic(m: String) = m.isEmpty() || m == "image/*" || m == "*/*" || m == "application/octet-stream" || m == "binary/octet-stream" || m == "application/unknown" || m == "content/unknown"

    private fun image(m: String) = m.startsWith("image/") && m != "image/svg+xml" && m != "image/*"

    /**
     * Where one item of a clip goes: [scheme] is its Uri's (null: it has none), [hasText] whether it
     * has words of its own, [kind] its Uri's [kind]. Words that may be all it is stay words; an image
     * that also has words keeps both.
     */
    fun route(scheme: String?, hasText: Boolean, kind: PasteKind?): PasteTo = when {
        scheme == null -> if (hasText) PasteTo.Box else PasteTo.Refuse
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true) -> if (hasText) PasteTo.Box else PasteTo.Address
        kind == PasteKind.Image -> if (hasText) PasteTo.AttachAndText else PasteTo.Attach
        kind == PasteKind.Maybe -> if (hasText) PasteTo.Box else PasteTo.Attach
        else -> if (hasText) PasteTo.Box else PasteTo.Refuse
    }

    /** An image's type from its first bytes (at least 12 of them say), or null when they aren't one it knows. */
    fun sniff(head: ByteArray): String? {
        fun at(i: Int, vararg b: Int) = head.size >= i + b.size && b.indices.all { head[i + it].toInt() and 0xFF == b[it] }
        fun word(i: Int, s: String) = at(i, *s.map { it.code }.toIntArray())
        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            word(0, "GIF8") -> "image/gif"
            word(0, "RIFF") && word(8, "WEBP") -> "image/webp"
            word(0, "BM") -> "image/bmp"
            word(4, "ftypavif") -> "image/avif"
            word(4, "ftyp") && listOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1").any { word(8, it) } -> "image/heic"
            else -> null
        }
    }

    /**
     * The type a pasted file is kept as: the resolver's when it names an image, else its first
     * bytes' ([head]). Null: not an image anyone can tell, which only matters for [PasteKind.Maybe].
     */
    fun typeOf(resolverMime: String?, head: ByteArray): String? =
        resolverMime?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()?.takeIf { image(it) } ?: sniff(head)

    /** The file extension for an image type: "png" for image/png, "jpg" for a JPEG, "img" for a type it doesn't know. */
    fun extension(mime: String?): String = when (mime?.lowercase(Locale.ROOT)?.substringBefore(';')?.trim()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        "image/bmp" -> "bmp"
        "image/avif" -> "avif"
        else -> "img"
    }

    /** A pasted image's name, as the Mac keeps it: "pasted-20261030-142501-1.png". */
    fun name(mime: String?, at: Date, n: Int): String =
        "pasted-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(at)}${if (n > 1) "-$n" else ""}.${extension(mime)}"

    /** What became of a pasted file: copied ([Saved]), not an image after all, or not readable (a grant that's gone). */
    sealed interface Kept {
        data class Saved(val uri: Uri) : Kept
        data object NotImage : Kept
        data object Unreadable : Kept
    }

    /**
     * [image] copied into the app's cache at once (a keyboard's grant may not outlast the upload), as
     * a Uri of the app's own, named by its real type. Old copies are cleared.
     */
    suspend fun keep(context: Context, image: PastedImage, n: Int, at: Date): Kept = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "pasted").apply { mkdirs() }
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
            val resolved = runCatching { context.contentResolver.getType(image.uri) }.getOrNull()
            (context.contentResolver.openInputStream(image.uri) ?: return@runCatching Kept.Unreadable).use { input ->
                val head = ByteArray(HEAD)
                // InputStream.readNBytes is API 33: a read may also come back short.
                var read = 0
                while (read < HEAD) {
                    val r = input.read(head, read, HEAD - read)
                    if (r < 0) break
                    read += r
                }
                val mime = typeOf(resolved, head.copyOf(read))
                if (mime == null && image.kind == PasteKind.Maybe) return@runCatching Kept.NotImage
                // Never over one still on its way (two pastes in the same second).
                var k = n
                var f = File(dir, name(mime, at, k))
                while (f.exists()) f = File(dir, name(mime, at, ++k))
                f.outputStream().use { out ->
                    out.write(head, 0, read)
                    input.copyTo(out)
                }
                Kept.Saved(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
            }
        }.getOrDefault(Kept.Unreadable)
    }

    /** How much of a file [sniff] looks at. */
    private const val HEAD = 16
}
