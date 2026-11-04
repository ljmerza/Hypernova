// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.session

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.thumbImage
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.DraftFile
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.PasteTo
import com.prudnikweb.hypernova.data.Pasted
import com.prudnikweb.hypernova.data.PastedImage
import com.prudnikweb.hypernova.data.Picked
import com.prudnikweb.hypernova.data.Thumbs
import com.prudnikweb.hypernova.data.isImage
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One composer's attachments ([key]: a session id, or the new-session sheet). A picked file
 * shows at once and starts uploading to the Mac in the app's scope, so leaving the screen
 * doesn't stop it; Send waits until every file is there.
 */
@Stable
class AttachState(
    val key: String,
    private val hub: Hub,
    private val context: Context,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
) {
    val files: List<DraftFile> get() = AttachDrafts.of(key)
    /** Every file is on the Mac (none still uploading, none failed). */
    val ready: Boolean get() = files.all { it.attachment != null }
    /** A file is still on its way. */
    val uploading: Boolean get() = files.any { it.uploading }
    /** The first file whose upload failed, if any: Send waits until it's sent again or removed. */
    val failed: DraftFile? get() = files.firstOrNull { it.failed != null }

    /** [temporary]: our own camera file, deleted once it's on the Mac. */
    fun add(uris: List<Uri>, temporary: Boolean = false) {
        val room = (AttachDrafts.MAX - files.size).coerceAtLeast(0)
        if (uris.size > room) toast(context.getString(R.string.attach_limit, AttachDrafts.MAX))
        for (uri in uris.take(room)) {
            val (name, size) = Picked.info(context, uri)
            val image = isImage(Picked.mime(context, uri), name)
            // Too big (and not a photo, which is made smaller): say so now, by its name, not after a wait.
            if (!image && size > Picked.MAX_BYTES) {
                toast(context.getString(R.string.attach_too_big_named, name))
                continue
            }
            val f = DraftFile(AttachDrafts.nextId(), name, image)
            f.source = uri
            f.temporary = temporary
            AttachDrafts.add(key, f)
            upload(f, uri)
        }
    }

    private fun upload(f: DraftFile, uri: Uri) {
        f.failed = null
        f.job = scope.launch {
            val thumb = if (f.isImage && f.thumb == null) launch { f.thumb = Picked.thumbnail(context, uri) } else null
            try {
                val a = uploads.withPermit { hub.upload(Picked.read(context, uri)).getOrThrow() }
                thumb?.join()
                f.thumbImage?.let { Thumbs.put(a.path, it) }
                f.attachment = a
                if (f.temporary) withContext(Dispatchers.IO) { runCatching { context.contentResolver.delete(uri, null, null) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // The chip stays, marked, to try again or remove; the message names the file.
                val why = if (macAway(hub)) context.getString(R.string.attach_mac_away) else e.message ?: context.getString(R.string.err_upload)
                f.failed = why
                toast(context.getString(R.string.attach_failed_named, f.name, why))
            }
        }
    }

    /**
     * Images pasted into the box ([images], a keyboard's or the clipboard's): copied into the app's
     * cache first, while their grant lasts, then added as the paperclip adds them. One that can't be
     * read, or turns out not to be an image, says so.
     */
    fun paste(images: List<PastedImage>) {
        val at = Date()
        scope.launch {
            val kept = images.mapIndexed { i, image -> Pasted.keep(context, image, i + 1, at) }
            if (kept.any { it is Pasted.Kept.Unreadable }) toast(context.getString(R.string.paste_failed))
            else if (kept.any { it is Pasted.Kept.NotImage }) pasteRefused()
            val saved = kept.filterIsInstance<Pasted.Kept.Saved>().map { it.uri }
            if (saved.isNotEmpty()) add(saved, temporary = true)
        }
    }

    /** What was pasted is neither words nor an image. */
    fun pasteRefused() = toast(context.getString(R.string.paste_unusable))

    /** Another try for a file whose upload failed. */
    fun retry(f: DraftFile) {
        val uri = f.source as? Uri ?: return
        if (f.failed != null) upload(f, uri)
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
 * What a box with a paperclip takes when something is pasted or a keyboard inserts content, each
 * item as [Pasted.route] says: images to [onImages], words of an image and web addresses to [onText],
 * text left for the box's own paste. What nothing can use goes to [onRefused]: unconsumed, Compose
 * would read it for text, find none, and do nothing at all.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun pastedImages(
    resolver: ContentResolver,
    onImages: (List<PastedImage>) -> Unit,
    onText: (String) -> Unit,
    onRefused: () -> Unit,
): ReceiveContentListener = object : ReceiveContentListener {
    override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
        val d = transferableContent.clipMetadata.clipDescription
        val clip = (0 until d.mimeTypeCount).map { d.getMimeType(it) }
        val images = ArrayList<PastedImage>()
        var refused = false
        val rest = transferableContent.consume { item ->
            val uri = item.uri
            val text = item.text?.toString().orEmpty()
            val kind = uri?.let { Pasted.kind(runCatching { resolver.getType(it) }.getOrNull(), clip) }
            when (Pasted.route(uri?.scheme, text.isNotEmpty(), kind)) {
                PasteTo.Box -> false
                PasteTo.Attach -> { images += PastedImage(uri!!, kind!!); true }
                PasteTo.AttachAndText -> { images += PastedImage(uri!!, kind!!); onText(text); true }
                PasteTo.Address -> { onText(uri.toString()); true }
                PasteTo.Refuse -> { refused = true; true }
            }
        }
        if (images.isNotEmpty()) onImages(images)
        if (refused) onRefused()
        return rest
    }
}

/** [pastedImages] for [field] and [attach]: words it was handed go in at the cursor. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberPasteReceiver(attach: AttachState, field: TextFieldState): ReceiveContentListener {
    val context = LocalContext.current
    return remember(attach, field) {
        pastedImages(
            context.contentResolver,
            onImages = attach::paste,
            onText = { words -> field.edit { val at = selection.min; replace(at, selection.max, words); placeCursorBeforeCharAt(at + words.length) } },
            onRefused = attach::pasteRefused,
        )
    }
}

/** The pickers: the system photo picker, the camera app, and the files picker. */
class AttachPickers(val photos: () -> Unit, val camera: () -> Unit, val files: () -> Unit)

@Composable
fun rememberAttach(key: String, hub: Hub): Pair<AttachState, AttachPickers> {
    val context = LocalContext.current
    val scope = LocalAppScope.current
    val toast = LocalToast.current
    val state = remember(key, hub) { AttachState(key, hub, context.applicationContext, scope, toast) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachDrafts.MAX)) { state.add(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { state.add(it) }
    // Survives the activity being recreated while the camera app is in front.
    var shot by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = shot
        shot = null
        if (ok && uri != null) state.add(listOf(uri), temporary = true)
    }
    val noCamera = stringResource(R.string.attach_no_camera)
    val cameraRefused = stringResource(R.string.attach_camera_refused)
    val shoot = {
        val uri = cameraFile(context)
        shot = uri
        // A SecurityException too: Android refuses the camera app to an app that declares the camera
        // permission (Scan does) without holding it, and only asking first keeps that from happening.
        try { camera.launch(uri) } catch (e: ActivityNotFoundException) { shot = null; toast(noCamera) } catch (e: SecurityException) { shot = null; toast(cameraRefused) }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) shoot() else toast(cameraRefused)
    }
    val pickers = remember(state) {
        AttachPickers(
            photos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            camera = {
                val held = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (held) shoot() else askCamera.launch(Manifest.permission.CAMERA)
            },
            files = { files.launch(arrayOf("*/*")) },
        )
    }
    return state to pickers
}

/** Where the camera app saves a photo for us (the app's cache; old ones are cleared). */
private fun cameraFile(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
    val f = File(dir, "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
}

/** The paperclip at the start of the composer; a cross while the tray is open. */
@Composable
fun AttachButton(open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(if (open) R.string.attach_close else R.string.attach)
    Box(
        modifier
            .size(48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (open) HN.bg700 else Color.Transparent), contentAlignment = Alignment.Center) {
            Crossfade(open, label = "attach") { o ->
                if (o) Icon(Glyph.Cross, size = 14.dp, color = HN.ink) else Icon(Glyph.Clip, size = 20.dp, color = HN.inkMuted)
            }
        }
    }
}

/** Photos, Camera, Files. */
@Composable
fun AttachTray(pickers: AttachPickers, modifier: Modifier = Modifier, kind: PillKind = PillKind.Outline, height: Dp = 40.dp, onPicked: () -> Unit = {}) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.attach_photos), { onPicked(); pickers.photos() }, kind = kind, height = height, leading = Glyph.Image)
        Pill(stringResource(R.string.attach_camera), { onPicked(); pickers.camera() }, kind = kind, height = height, leading = Glyph.Camera)
        Pill(stringResource(R.string.attach_files), { onPicked(); pickers.files() }, kind = kind, height = height, leading = Glyph.File)
    }
}

