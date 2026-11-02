package com.prudnikweb.hypernova.data

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

import kotlinx.coroutines.Job
import java.io.OutputStream
import java.util.Locale

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp")

/** True for pictures the phone can show (SVG is text). */
fun isImage(mime: String?, name: String): Boolean =
    mime?.startsWith("image/") == true && mime != "image/svg+xml" ||
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXT

/** A file attached to a prompt, once it's on the Mac at [path]. */
@Immutable
data class Attachment(val path: String, val name: String, val mime: String?, val size: Long) {
    val isImage: Boolean get() = isImage(mime, name)
}

/**
 * A picked file, ready to upload: [write] sends its bytes, streaming a file from where it is
 * (never whole in memory). [length] is -1 when it isn't known exactly.
 */
class Upload(val name: String, val mime: String, val length: Long, val write: (OutputStream) -> Unit)

/**
 * A file in a composer: a preview now, and its [attachment] once it's on the Mac. [failed]: why its
 * upload didn't go; it stays in the composer, to try again or remove.
 */
@Stable
class DraftFile(val id: Long, val name: String, val isImage: Boolean) {
    /** The preview, as the platform draws it (on Android, an ImageBitmap: `thumbImage`). */
    var thumb by mutableStateOf<Any?>(null)
    var attachment by mutableStateOf<Attachment?>(null)
    var failed by mutableStateOf<String?>(null)
    var job: Job? = null
    /** Where it was picked, for another try. */
    var source: Any? = null
    var temporary = false
    /** On its way to the Mac now. */
    val uploading: Boolean get() = attachment == null && failed == null
}

/**
 * The files waiting in each composer (a session's, or the new-session sheet's), kept while you
 * move around the app and uploading in the background meanwhile.
 */
object AttachDrafts {
    const val MAX = 10
    private val map = mutableStateMapOf<String, List<DraftFile>>()
    private var seq = 0L

    fun of(key: String): List<DraftFile> = map[key].orEmpty()
    fun nextId(): Long = ++seq
    fun add(key: String, f: DraftFile) { map[key] = of(key) + f }
    fun remove(key: String, f: DraftFile) {
        val rest = of(key) - f
        if (rest.isEmpty()) map.remove(key) else map[key] = rest
    }
    /** Takes the files for sending (they come back with [restore] if it fails). */
    fun take(key: String): List<DraftFile> = map.remove(key).orEmpty()
    fun restore(key: String, files: List<DraftFile>) {
        if (files.isNotEmpty()) map[key] = (files + of(key)).distinctBy { it.id }
    }

    /** Text of a send that failed, waiting for its composer (which may be closed by then). */
    private val returned = mutableStateMapOf<String, String>()
    fun giveBack(key: String, text: String) {
        if (text.isNotBlank()) returned[key] = listOfNotNull(returned[key], text).joinToString("\n")
    }
    fun givenBack(key: String): String? = returned[key]
    fun takeBack(key: String): String? = returned.remove(key)
}

/**
 * What this phone keeps by session id only in memory, moved when a rewind replaces a session
 * (docs/PROTOCOL.md, "Rewind"): the composer's files, its text, and the side questions. Read marks
 * and prompts waiting for the Mac are the hub's to move (LiveHub.replaced).
 */
object SessionCarry {
    /** Each open composer's words as last drawn: a closing screen's saved state can't be read from outside. */
    private val typing = HashMap<String, String>()

    fun typing(sessionId: String, text: String) {
        if (text.isEmpty()) typing.remove(sessionId) else typing[sessionId] = text
    }

    fun moved(old: String, to: String) {
        AttachDrafts.restore(to, AttachDrafts.take(old))
        AttachDrafts.takeBack(old)?.let { AttachDrafts.giveBack(to, it) }
        typing.remove(old)?.let { AttachDrafts.giveBack(to, it) }
        Asides.moved(old, to)
    }
}
