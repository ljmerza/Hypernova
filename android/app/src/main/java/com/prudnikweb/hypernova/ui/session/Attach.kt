// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.session

import androidx.compose.ui.graphics.ImageBitmap
import com.prudnikweb.hypernova.data.Upload
import com.prudnikweb.hypernova.data.strings
import com.prudnikweb.hypernova.ui.platform.AttachSource

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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AttachDrafts
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.PasteTo
import com.prudnikweb.hypernova.data.Pasted
import com.prudnikweb.hypernova.data.PastedImage
import com.prudnikweb.hypernova.data.Picked
import com.prudnikweb.hypernova.data.isImage
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The system's files for [AttachState]: what the phone's pickers hand over, content:// addresses. */
private class UriSource(private val context: Context) : AttachSource {
    override fun nameAndSize(source: Any): Pair<String, Long> = Picked.info(context, source as Uri)
    override fun isImage(source: Any, name: String): Boolean = com.prudnikweb.hypernova.data.isImage(Picked.mime(context, source as Uri), name)
    /** A photo is made smaller, so only other files can be too big. */
    override fun fits(image: Boolean, size: Long): Boolean = image || size <= Picked.MAX_BYTES
    override suspend fun thumbnail(source: Any): ImageBitmap? = Picked.thumbnail(context, source as Uri)
    override suspend fun read(source: Any): Upload = Picked.read(context, source as Uri)
    override suspend fun discard(source: Any) {
        withContext(Dispatchers.IO) { runCatching { context.contentResolver.delete(source as Uri, null, null) } }
    }
}

/** A phone's [AttachState]. */
fun AttachState(key: String, hub: Hub, context: Context, scope: CoroutineScope, toast: (String) -> Unit): AttachState =
    AttachState(key, hub, UriSource(context), context.resources.strings, scope, toast)

/**
 * Images pasted into the box ([images], a keyboard's or the clipboard's): copied into the app's
 * cache first, while their grant lasts, then added as the paperclip adds them. One that can't be
 * read, or turns out not to be an image, says so.
 */
private fun AttachState.paste(context: Context, images: List<PastedImage>) {
    val at = Date()
    scope.launch {
        val kept = images.mapIndexed { i, image -> Pasted.keep(context, image, i + 1, at) }
        if (kept.any { it is Pasted.Kept.Unreadable }) toast(context.getString(R.string.paste_failed))
        else if (kept.any { it is Pasted.Kept.NotImage }) pasteRefused()
        val saved = kept.filterIsInstance<Pasted.Kept.Saved>().map { it.uri }
        if (saved.isNotEmpty()) add(saved, temporary = true)
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
            onImages = { attach.paste(context.applicationContext, it) },
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

/** Photos, Camera, Files. */
@Composable
fun AttachTray(pickers: AttachPickers, modifier: Modifier = Modifier, kind: PillKind = PillKind.Outline, height: Dp = 40.dp, onPicked: () -> Unit = {}) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.attach_photos), { onPicked(); pickers.photos() }, kind = kind, height = height, leading = Glyph.Image)
        Pill(stringResource(R.string.attach_camera), { onPicked(); pickers.camera() }, kind = kind, height = height, leading = Glyph.Camera)
        Pill(stringResource(R.string.attach_files), { onPicked(); pickers.files() }, kind = kind, height = height, leading = Glyph.File)
    }
}

