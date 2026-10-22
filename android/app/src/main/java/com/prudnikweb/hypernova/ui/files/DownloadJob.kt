package com.prudnikweb.hypernova.ui.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.prudnikweb.hypernova.data.DlEvent
import com.prudnikweb.hypernova.data.DlState
import com.prudnikweb.hypernova.data.DownloadResume
import com.prudnikweb.hypernova.data.Downloaded
import com.prudnikweb.hypernova.data.Downloads
import com.prudnikweb.hypernova.data.FileProblem
import com.prudnikweb.hypernova.data.reduce
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where a fetch writes: into [File] (the part), carrying on from a resume, telling the ETag and progress. */
typealias Fetch = suspend (File, DownloadResume?, (String?) -> Unit, (Long, Long) -> Unit) -> Result<Downloaded>

/**
 * One file coming to the phone: streamed into the cache (`shared/dl/<key>/<name>`, which the FileProvider
 * exposes), with progress, Cancel, and a resume from the part already there when the Mac still has the same
 * file. [state] is the pure machine ([DlState]); [run] starts it (or uses the finished file) and then does [then].
 */
class DownloadJob(
    private val scope: CoroutineScope,
    val name: String,
    var servedMime: String?,
    dir: File,
    private val fetch: Fetch,
) {
    val fileName = Downloads.safeName(name)
    val mime: String get() = Downloads.mimeFor(fileName, servedMime)
    private val final = File(dir, fileName)
    private val part = File(dir, "$fileName.part")
    var state by mutableStateOf<DlState>(DlState.Idle)
        private set
    private var etag: String? = null
    private var total: Long? = null
    private var job: Job? = null
    private var then: ((File) -> Unit)? = null

    private fun send(e: DlEvent) = synchronized(this) { state = state.reduce(e) }

    /** The finished file now, or start (and carry on from a part) and do [then] with it when it's here. */
    fun run(then: ((File) -> Unit)? = null) {
        if (state is DlState.Done) {
            if (final.exists()) { then?.invoke(final); return }
            state = DlState.Idle
        }
        if (state is DlState.Running) { this.then = then ?: this.then; return }
        this.then = then
        val kept = part.length()
        val plan = Downloads.resumePlan(kept, etag, total)
        if (kept > 0 && plan == null) part.delete()
        send(DlEvent.Start(if (plan != null) kept else 0))
        job = scope.launch {
            val r = withContext(Dispatchers.IO) {
                final.delete()
                fetch(part, plan, { etag = it }, { d, t -> if (t > 0) total = t; send(DlEvent.Progress(d, t)) })
            }
            r.onSuccess { d ->
                final.delete()
                if (part.renameTo(final)) {
                    send(DlEvent.Finished(d.total))
                    val after = this@DownloadJob.then
                    this@DownloadJob.then = null
                    after?.invoke(final)
                } else send(DlEvent.Failure(FileProblem(com.prudnikweb.hypernova.data.FileKind.Other, retryable = false, message = "")))
            }.onFailure { e ->
                val p = FileProblem.of(e)
                if (!p.retryable) part.delete()
                send(DlEvent.Failure(p))
            }
        }
    }

    /** Stops and throws the part away. */
    fun cancel() {
        job?.cancel()
        part.delete()
        then = null
        send(DlEvent.Cancel)
    }

    /** Back to the start after a failure the person has read. */
    fun dismiss() {
        if (state is DlState.Failed) state = DlState.Idle
    }

    val bytesOnDisk: Long get() = part.length()
}

/** Share, Open with and Save for a downloaded file, and how the last one ended. */
class FileActions(private val context: Context, private val scope: CoroutineScope) {
    sealed interface Outcome {
        data class Saved(val uri: Uri, val mime: String) : Outcome
        data object SaveFailed : Outcome
        data object NoApp : Outcome
    }

    var outcome by mutableStateOf<Outcome?>(null)
        private set
    internal var picker: ActivityResultLauncher<String>? = null
    private var pending: Pair<File, String>? = null

    private fun uri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun share(file: File, mime: String) {
        outcome = null
        val u = uri(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, u)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = android.content.ClipData.newRawUri(file.name, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun openWith(file: File, mime: String) {
        outcome = null
        val u = uri(file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(u, mime)
            clipData = android.content.ClipData.newRawUri(file.name, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(Intent.createChooser(view, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    /** Opens the system's "save as" picker; no storage permission, the person picks the folder. */
    fun save(file: File, name: String) {
        outcome = null
        pending = file to name
        try { picker?.launch(name) } catch (_: ActivityNotFoundException) { outcome = Outcome.SaveFailed }
    }

    internal fun saved(target: Uri?) {
        val (file, _) = pending ?: return
        pending = null
        if (target == null) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(target, "wt")!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            outcome = if (ok) Outcome.Saved(target, Downloads.mimeFor(file.name, null)) else Outcome.SaveFailed
        }
    }

    fun openSaved(o: Outcome.Saved) {
        val view = Intent(Intent.ACTION_VIEW).apply { setDataAndType(o.uri, o.mime); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        launch(Intent.createChooser(view, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun clear() { outcome = null }

    private fun launch(i: Intent) {
        try { context.startActivity(i) } catch (_: ActivityNotFoundException) { outcome = Outcome.NoApp }
    }
}

@Composable
fun rememberFileActions(): FileActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = remember { FileActions(context, scope) }
    actions.picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { actions.saved(it) }
    return actions
}

/** The cache folder downloads go in (the FileProvider's `shared/`), tidied of anything a day old. */
fun downloadDir(context: Context, source: String): File {
    val root = File(context.cacheDir, "shared/dl")
    return File(root, Downloads.key(source))
}

fun sweepDownloads(context: Context) = Downloads.sweep(File(context.cacheDir, "shared/dl"), System.currentTimeMillis())
