package com.prudnikweb.hypernova.ui.session

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.DraftFile
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Strings
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.platform.AttachSource
import com.prudnikweb.hypernova.ui.platform.SharedUi
import com.prudnikweb.hypernova.ui.platform.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * One composer's attachments ([key]: a session id, or the new-session sheet). A picked file
 * shows at once and starts uploading to the server in the app's scope, so leaving the screen
 * doesn't stop it; Send waits until every file is there. [picked] is how this system reads its files.
 */
@Stable
class AttachState(
    val key: String,
    private val hub: Hub,
    private val picked: AttachSource,
    private val words: Strings,
    internal val scope: CoroutineScope,
    internal val toast: (String) -> Unit,
) {
    val files: List<DraftFile> get() = AttachDrafts.of(key)
    /** Every file is on the server (none still uploading, none failed). */
    val ready: Boolean get() = files.all { it.attachment != null }
    /** A file is still on its way. */
    val uploading: Boolean get() = files.any { it.uploading }
    /** The first file whose upload failed, if any: Send waits until it's sent again or removed. */
    val failed: DraftFile? get() = files.firstOrNull { it.failed != null }

    /** [temporary]: our own copy, deleted once it's on the server. */
    fun add(sources: List<Any>, temporary: Boolean = false) {
        val room = (AttachDrafts.MAX - files.size).coerceAtLeast(0)
        if (sources.size > room) toast(words.getString(Str.attach_limit, AttachDrafts.MAX))
        for (source in sources.take(room)) {
            val (name, size) = picked.nameAndSize(source)
            val image = picked.isImage(source, name)
            // Too big: say so now, by its name, not after a wait.
            if (!picked.fits(image, size)) {
                toast(words.getString(Str.attach_too_big_named, name))
                continue
            }
            val f = DraftFile(AttachDrafts.nextId(), name, image)
            f.source = source
            f.temporary = temporary
            AttachDrafts.add(key, f)
            upload(f, source)
        }
    }

    private fun upload(f: DraftFile, source: Any) {
        f.failed = null
        f.job = scope.launch {
            val thumb = if (f.isImage && f.thumb == null) launch { f.thumb = picked.thumbnail(source) } else null
            try {
                val a = uploads.withPermit { hub.upload(picked.read(source)).getOrThrow() }
                thumb?.join()
                (f.thumb as? ImageBitmap)?.let { SharedUi.platform.images.keep(a.path, it) }
                f.attachment = a
                if (f.temporary) picked.discard(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The chip stays, marked, to try again or remove; the message names the file.
                val why = if (macAway(hub)) words.getString(Str.attach_mac_away) else e.message ?: words.getString(Str.err_upload)
                f.failed = why
                toast(words.getString(Str.attach_failed_named, f.name, why))
            }
        }
    }

    /** What was pasted is neither words nor an image. */
    fun pasteRefused() = toast(words.getString(Str.paste_unusable))

    /** Another try for a file whose upload failed. */
    fun retry(f: DraftFile) {
        val source = f.source ?: return
        if (f.failed != null) upload(f, source)
    }

    fun remove(f: DraftFile) {
        f.job?.cancel()
        AttachDrafts.remove(key, f)
    }

    private companion object {
        /** Two files at a time, however many were picked (each holds a photo's pixels while it's read). */
        val uploads = Semaphore(2)
    }
}

/**
 * A send never goes without a file that was attached: one that failed is sent again or removed first, and one still uploading is waited for
 * (the voice path can send meanwhile). [mayGo] says whether it can go now; [FilesFirstEffect] sends it once they're all there.
 */
@Stable
internal class FilesFirst(private val attach: AttachState, private val toast: (String) -> Unit, private val fixFirst: String) {
    /** A send waiting for files on their way: its "replace", until they're all on the server. */
    var waiting by mutableStateOf<Boolean?>(null)

    fun mayGo(replace: Boolean): Boolean {
        attach.failed?.let { f ->
            toast(String.format(fixFirst, f.name))
            return false
        }
        if (attach.uploading) {
            waiting = replace
            return false
        }
        return true
    }
}

@Composable
internal fun rememberFilesFirst(key: String, attach: AttachState): FilesFirst {
    val toast = LocalToast.current
    val fixFirst = stringResource(Str.attach_fix_first)
    return remember(key, attach) { FilesFirst(attach, toast, fixFirst) }
}

@Composable
internal fun FilesFirstEffect(first: FilesFirst, attach: AttachState, send: (Boolean) -> Unit) {
    LaunchedEffect(first.waiting, attach.uploading, attach.failed) {
        val replace = first.waiting ?: return@LaunchedEffect
        // A file failed meanwhile: its own message said so, and the text stays in the box.
        if (attach.failed != null) first.waiting = null
        else if (!attach.uploading) {
            first.waiting = null
            send(replace)
        }
    }
}
