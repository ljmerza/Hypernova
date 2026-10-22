package com.prudnikweb.hypernova.ui.files

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.DlState
import com.prudnikweb.hypernova.data.Downloads
import com.prudnikweb.hypernova.data.FileKind
import com.prudnikweb.hypernova.data.FileProblem
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.isWebPage
import com.prudnikweb.hypernova.ui.home.tilde
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif", "ico")
private val TEXT_EXT = setOf(
    "txt", "md", "markdown", "json", "jsonl", "yaml", "yml", "toml", "xml", "html", "htm", "css", "scss", "js", "mjs", "cjs", "ts", "tsx", "jsx",
    "kt", "kts", "java", "swift", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "m", "sh", "zsh", "bash", "gradle", "properties", "ini", "cfg",
    "conf", "env", "sql", "csv", "tsv", "log", "gitignore", "lock", "svg", "vue", "svelte", "astro", "dart", "lua", "php", "pl", "r", "scala",
)
private const val MAX_TEXT = 512 * 1024

private fun ext(path: String) = path.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)

private sealed interface Shown {
    data class Image(val bitmap: ImageBitmap) : Shown
    data class Text(val lines: List<String>, val truncated: Boolean) : Shown
    /** No preview here: the file goes to another app or to a folder. */
    data object Binary : Shown
}

/**
 * One file: images zoom, text and code scroll in mono, web pages open as a preview. Everything else (PDFs, archives,
 * office files, videos, a file past what the viewer loads) offers Share, Save and Open with, which download it
 * (streamed to the cache, with progress and Cancel) first. Those three are there for images and text too.
 */
@Composable
fun FileViewer(hub: Hub, path: String, onBack: () -> Unit) {
    val opener = LocalOpener.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var shown by remember { mutableStateOf<Shown?>(null) }
    var problem by remember { mutableStateOf<FileProblem?>(null) }
    var reload by remember { mutableStateOf(0) }
    val e = ext(path)
    val name = path.substringAfterLast('/')
    val job = remember(path) { DownloadJob(scope, name, null, downloadDir(context, path)) { t, r, s, p -> hub.download(path, t, r, s, p) } }
    val actions = rememberFileActions()
    LaunchedEffect(path, reload) {
        problem = null
        withContext(Dispatchers.IO) { sweepDownloads(context) }
        if (!Downloads.previewable(name)) { shown = Shown.Binary; return@LaunchedEffect }
        hub.file(path)
            .onSuccess { f ->
                job.servedMime = f.mime
                shown = withContext(Dispatchers.Default) { decode(f.bytes, f.mime, e) }
            }
            .onFailure { problem = FileProblem.of(it) }
    }
    ScreenScaffold(
        sourceKey = "file:$path",
        title = name,
        subtitle = if ("/Hypernova/attachments/" in path) stringResource(R.string.attach_subtitle) else tilde(path.substringBeforeLast('/')),
        onBack = onBack,
        below = if (isWebPage(path)) {
            {
                Pill(
                    stringResource(R.string.open_as_page), { opener.preview(path, null, name) },
                    Modifier.padding(start = 12.dp), kind = PillKind.Primary, height = 40.dp, leading = Glyph.Globe,
                )
            }
        } else null,
    ) { top, source ->
        val p = problem
        val dockShown = p == null || job.state !is DlState.Idle
        val shift = bottom + if (dockShown) 112.dp else 0.dp
        when (val s = shown) {
            null -> Box(Modifier.fillMaxSize().padding(top = top, start = 16.dp, end = 16.dp)) {
                if (p != null) {
                    FileProblemCard(
                        p, forFolder = false, Modifier.padding(vertical = 8.dp), projectsRoot = hub.projectsRoot,
                        onOpenProjects = { r -> opener.files(r, r.trimEnd('/').substringAfterLast('/').ifEmpty { r }) },
                        onRetry = { reload++ },
                        onDownload = { job.run() },
                        onOpenOther = if (p.kind == FileKind.WrongKind) ({ opener.files(path, name) }) else null,
                        onTurnOn = if (hub.mac.browseOutsideProjects == false) ({ opener.settings(com.prudnikweb.hypernova.ui.settings.SettingsJump.FILES) }) else null,
                    )
                } else LoadingLine(stringResource(R.string.loading), Modifier.padding(8.dp))
            }
            is Shown.Image -> ZoomImage(s.bitmap, name, Modifier.fillMaxSize().padding(top = top, bottom = shift))
            is Shown.Binary -> Box(Modifier.fillMaxSize().padding(top = top, start = 16.dp, end = 16.dp)) {
                GlassCard(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 3.dp)) { Icon(Glyph.File, size = 18.dp, color = HN.inkMuted) }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            HText(stringResource(R.string.fv_noview_title), style = Type.heading, color = HN.ink)
                            Spacer(Modifier.height(4.dp))
                            HText(stringResource(R.string.fv_noview_body), style = Type.small, color = HN.inkMuted)
                        }
                    }
                }
            }
            is Shown.Text -> {
                val gutter = s.lines.size.toString().length
                LazyColumn(
                    Modifier.fillMaxSize().then(source),
                    contentPadding = PaddingValues(top = top, bottom = shift + 24.dp, start = 12.dp, end = 16.dp),
                ) {
                    itemsIndexed(s.lines) { i, line ->
                        Row(Modifier.fillMaxWidth()) {
                            HText((i + 1).toString().padStart(gutter), style = Type.monoSmall, color = HN.inkGhost)
                            Spacer(Modifier.width(10.dp))
                            HText(line.ifEmpty { " " }, style = Type.monoSmall, color = HN.inkMuted, modifier = Modifier.weight(1f))
                        }
                    }
                    if (s.truncated) {
                        item { HText(stringResource(R.string.file_too_big), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(top = 12.dp)) }
                    }
                }
            }
        }
        if (dockShown && (shown != null || p != null)) {
            DownloadDock(job, actions, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = bottom + 12.dp))
        }
    }
}

private fun decode(bytes: ByteArray, mime: String?, ext: String): Shown {
    val isImage = mime?.startsWith("image/") == true && mime != "image/svg+xml" || (ext in IMAGE_EXT)
    if (isImage) {
        // Sample big photos down so they fit in memory and on screen.
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 2560) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        if (bmp != null) return Shown.Image(bmp.asImageBitmap())
    }
    val texty = mime?.startsWith("text/") == true || mime in setOf("application/json", "application/xml", "application/javascript", "image/svg+xml") ||
        ext in TEXT_EXT || ext.isEmpty()
    val head = bytes.copyOf(minOf(bytes.size, 4096))
    if (!texty && head.any { it == 0.toByte() }) return Shown.Binary
    if (head.any { it == 0.toByte() }) return Shown.Binary
    val truncated = bytes.size > MAX_TEXT
    val text = String(if (truncated) bytes.copyOf(MAX_TEXT) else bytes, Charsets.UTF_8)
    return Shown.Text(text.replace("\t", "    ").lines(), truncated)
}

/** Pinch to zoom, drag to pan (as far as the zoomed picture reaches), double-tap to reset. */
@Composable
private fun ZoomImage(bitmap: ImageBitmap, description: String, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    val maxX = size.width * (scale - 1f) / 2f
                    val maxY = size.height * (scale - 1f) / 2f
                    offset = if (scale == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
                }
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                // Fills the area; Fit scales the picture up or down to the largest size that fits.
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, bottom = 40.dp)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        )
        HText(
            stringResource(R.string.image_zoom_hint), style = Type.monoSmall, color = HN.inkDim,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}
