package com.prudnikweb.hypernova.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DebugBundle
import com.prudnikweb.hypernova.data.DebugLog
import com.prudnikweb.hypernova.data.DebugLogger
import com.prudnikweb.hypernova.data.DebugSendFlow
import com.prudnikweb.hypernova.data.DebugSendState
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.strings
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** The one file a share hands over, in a folder that only ever holds it (res/xml/file_paths.xml). */
object DebugShare {
    private fun folder(context: Context) = File(context.cacheDir, "debuglog")

    /** The log gathered into one new file, whichever one went before gone. Off the main thread. */
    fun prepare(context: Context, logger: DebugLogger): File {
        val dir = folder(context)
        dir.deleteRecursively()
        val file = File(dir, DebugBundle.name(System.currentTimeMillis()))
        logger.bundle(file)
        return file
    }

    /**
     * The share sheet, with a read grant for exactly [file]'s URI: a receiver can open that one file, and
     * no URI exists for the log's own folder (DebugLogSectionTest).
     */
    fun intent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.debuglog_share_title))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(file.name, uri)
        return Intent.createChooser(send, context.getString(R.string.debuglog_share_title))
    }

    fun clean(context: Context) {
        folder(context).deleteRecursively()
    }
}

/**
 * Settings → Debug logging (data/DebugLog.kt): the switch, which turns itself off after 24 hours and says
 * when; what a log holds, in words; its size; Send logs, Share and Clear. Nothing leaves without a tap, and
 * only to the server picked or where the share sheet sends it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DebugLogSection(
    logger: DebugLogger? = DebugLog.logger,
    servers: () -> List<Pair<ServerStatus, Hub>> = { Core.everyHub },
) {
    val l = logger ?: return
    val context = LocalContext.current
    val toast = LocalToast.current
    val confirm = LocalConfirm.current
    val scope = rememberCoroutineScope()
    val flow = remember(l) { DebugSendFlow(scope, { l }, servers) }
    LaunchedEffect(l) { withContext(Dispatchers.IO) { l.refresh() } }
    val on = l.until > 0
    val shareFailed = stringResource(R.string.debuglog_share_failed)
    val cleared = stringResource(R.string.debuglog_cleared)
    val clearQ = stringResource(R.string.debuglog_clear_q)
    val clearBody = stringResource(R.string.debuglog_clear_body)
    val clearWord = stringResource(R.string.debuglog_clear)
    Seam(stringResource(R.string.debuglog_title), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            val body = if (on) {
                val at = SimpleDateFormat(context.strings.timePattern(withDay = true), context.resources.configuration.locales[0]).format(Date(l.until))
                stringResource(R.string.debuglog_on_until, at)
            } else {
                stringResource(R.string.debuglog_off)
            }
            ToggleRow(stringResource(R.string.debuglog_switch), body, on, { if (it) l.turnOn() else l.turnOff() })
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.debuglog_holds), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(8.dp))
            HText(stringResource(R.string.debuglog_crash_note), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(12.dp))
            Hairline(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            val size = when {
                l.empty -> stringResource(R.string.debuglog_none)
                l.hasCrash -> stringResource(R.string.debuglog_size_crash, DebugLog.size(l.sizeBytes))
                else -> stringResource(R.string.debuglog_size, DebugLog.size(l.sizeBytes))
            }
            HText(size, style = Type.small, color = HN.ink)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (servers().isNotEmpty()) {
                    Pill(stringResource(R.string.debuglog_send), flow::send, kind = PillKind.Primary, height = 40.dp, enabled = !l.empty && flow.state !is DebugSendState.Sending)
                }
                Pill(
                    stringResource(R.string.debuglog_share),
                    {
                        scope.launch {
                            val failed = runCatching {
                                val file = withContext(Dispatchers.IO) { DebugShare.prepare(context, l) }
                                context.startActivity(DebugShare.intent(context, file))
                            }.exceptionOrNull()
                            if (failed != null) {
                                DebugLog.w("log", failed) { "couldn't share the log" }
                                toast(shareFailed)
                            }
                        }
                    },
                    kind = PillKind.Outline, height = 40.dp, enabled = !l.empty,
                )
                Pill(
                    clearWord,
                    {
                        confirm(
                            Confirm(title = clearQ, body = clearBody, action = clearWord, danger = true) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        l.clear()
                                        DebugShare.clean(context)
                                    }
                                    flow.dismiss()
                                    toast(cleared)
                                }
                            },
                        )
                    },
                    kind = PillKind.Ghost, height = 40.dp, enabled = !l.empty,
                )
            }
            SendState(flow)
        }
    }
}

/** Where Send logs stands: which server, sending, where it landed, or what stopped it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendState(flow: DebugSendFlow) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val copied = stringResource(R.string.debuglog_copied)
    val state = flow.state
    if (state == DebugSendState.Idle) return
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        when (state) {
            DebugSendState.Idle -> Unit
            is DebugSendState.Choosing -> {
                HText(stringResource(R.string.debuglog_send_to), style = Type.small, color = HN.ink)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in state.servers) Pill(s.name, { flow.sendTo(s.id) }, kind = PillKind.Outline, height = 40.dp)
                    Pill(stringResource(R.string.cancel), flow::dismiss, kind = PillKind.Ghost, height = 40.dp)
                }
            }
            is DebugSendState.Sending -> HText(stringResource(R.string.debuglog_sending, state.server), style = Type.small, color = HN.inkMuted)
            is DebugSendState.Sent -> {
                HText(stringResource(R.string.debuglog_sent, state.server), style = Type.small, color = HN.accent)
                Spacer(Modifier.height(4.dp))
                SelectionContainer { HText(state.saved.path, style = Type.monoSmall, color = HN.ink) }
                Spacer(Modifier.height(8.dp))
                Pill(
                    stringResource(R.string.debuglog_copy_path),
                    {
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Hypernova debug log", state.saved.path))
                        toast(copied)
                    },
                    kind = PillKind.Outline, height = 40.dp,
                )
            }
            is DebugSendState.TooOld -> HText(stringResource(R.string.debuglog_too_old, state.server), style = Type.small, color = HN.amber)
            is DebugSendState.Failed -> HText(state.why.ifBlank { stringResource(R.string.debuglog_send_failed) }, style = Type.small, color = HN.danger)
        }
    }
}
