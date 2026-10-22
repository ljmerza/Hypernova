package com.prudnikweb.hypernova.ui.files

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.DlState
import com.prudnikweb.hypernova.data.FileKind
import com.prudnikweb.hypernova.data.FileProblem
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.home.tilde
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import java.util.Locale

/** "12.4 MB" in the app's units, up to gigabytes. */
@Composable
fun sizeText(bytes: Long): String {
    val res = LocalContext.current.resources
    val kb = 1024.0
    return when {
        bytes < 1024 -> res.getString(R.string.size_b, bytes.toInt())
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, res.getString(R.string.size_kb), bytes / kb)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, res.getString(R.string.size_mb), bytes / (kb * kb))
        else -> String.format(Locale.ROOT, res.getString(R.string.size_gb), bytes / (kb * kb * kb))
    }
}

/** A small drawn padlock (the shared glyph set has none). */
@Composable
fun LockGlyph(modifier: Modifier = Modifier, size: Dp = 18.dp, color: Color = HN.inkDim) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val st = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawRoundRect(color, Offset(.2f * w, .46f * h), Size(.6f * w, .4f * h), CornerRadius(.08f * w), style = st)
        drawArc(color, 180f, 180f, false, Offset(.3f * w, .14f * h), Size(.4f * w, .64f * h), style = st)
    }
}

/**
 * A calm card for a files failure: what happened, why, and the real next steps. Retry shows only when the
 * problem is the connection ([FileProblem.retryable]); "Turn on in Settings" ([onTurnOn]) only where the Mac
 * says Browse outside projects would open it ([FileProblem.settingOpens]); nothing here offers a button that
 * can't work.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileProblemCard(
    p: FileProblem,
    forFolder: Boolean,
    modifier: Modifier = Modifier,
    projectsRoot: String? = null,
    onOpenProjects: ((String) -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onUp: (() -> Unit)? = null,
    onOpenOther: (() -> Unit)? = null,
    onTurnOn: (() -> Unit)? = null,
) {
    val root = p.projectsRoot ?: projectsRoot
    // Past what the viewer loads (20 MB, an attachment 30): a download can still bring it. Past the download limit: nothing can.
    val viewLimit = (p.max ?: 0L) <= 32L * 1024 * 1024
    val dl = onDownload != null && p.kind == FileKind.TooLarge && viewLimit
    val steps = if (p.kind == FileKind.MacPrivacy) stringResource(R.string.fp_privacy_steps) else null
    val title: String
    val body: String
    when (p.kind) {
        FileKind.Outside -> {
            title = stringResource(R.string.fp_outside_title)
            body = if (root != null) stringResource(R.string.fp_outside_body, tilde(root)) else stringResource(R.string.fp_outside_body_none)
        }
        FileKind.Secret -> { title = stringResource(R.string.fp_secret_title); body = stringResource(R.string.fp_secret_body) }
        FileKind.LockedFolder -> { title = stringResource(R.string.fp_locked_title); body = stringResource(R.string.fp_locked_body) }
        FileKind.TooLarge -> {
            title = stringResource(if (viewLimit) R.string.fp_toolarge_title else R.string.fp_toolarge_dl_title)
            val size = p.size
            val max = p.max
            body = if (size != null && max != null) {
                stringResource(if (viewLimit) R.string.fp_toolarge_body else R.string.fp_toolarge_dl_body, sizeText(size), sizeText(max))
            } else stringResource(R.string.fp_toolarge_body_plain)
        }
        FileKind.NotFound -> {
            title = stringResource(if (forFolder) R.string.fp_notfound_dir_title else R.string.fp_notfound_file_title)
            body = stringResource(if (forFolder) R.string.fp_notfound_dir_body else R.string.fp_notfound_file_body)
        }
        FileKind.AttachmentGone -> { title = stringResource(R.string.fp_attach_title); body = stringResource(R.string.fp_attach_body) }
        FileKind.MacPrivacy -> { title = stringResource(R.string.fp_privacy_title); body = stringResource(R.string.fp_privacy_body) }
        FileKind.Unreadable -> { title = stringResource(R.string.fp_unreadable_title); body = stringResource(R.string.fp_unreadable_body) }
        FileKind.WrongKind -> {
            title = stringResource(if (p.asFolder) R.string.fp_wrong_dir_title else R.string.fp_wrong_file_title)
            body = stringResource(R.string.fp_wrong_body)
        }
        FileKind.Network -> { title = stringResource(R.string.fp_network_title); body = p.message }
        FileKind.Other -> { title = stringResource(R.string.fp_other_title); body = p.message }
    }
    GlassCard(modifier.fillMaxWidth(), accent = if (p.kind == FileKind.Network || p.kind == FileKind.Other) HN.danger else HN.amber) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 3.dp)) {
                when {
                    p.kind == FileKind.Secret || p.kind == FileKind.LockedFolder -> LockGlyph(color = HN.amber)
                    forFolder -> Icon(Glyph.Folder, size = 18.dp, color = HN.amber)
                    else -> Icon(Glyph.File, size = 18.dp, color = HN.amber)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                HText(title, style = Type.heading, color = HN.ink)
                if (body.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    HText(body, style = Type.small, color = HN.inkMuted)
                }
                if (steps != null) {
                    Spacer(Modifier.height(8.dp))
                    HText(steps, style = Type.monoSmall, color = HN.inkDim)
                }
                val actions = buildList<@Composable () -> Unit> {
                    if (p.kind == FileKind.Outside && root != null && onOpenProjects != null) add {
                        Pill(stringResource(R.string.fp_open_projects), { onOpenProjects(root) }, kind = PillKind.Primary, height = 40.dp)
                    }
                    // The setting that would open it, next to what was there (the owner turns it on in Settings, with the fingerprint).
                    if (p.settingOpens && onTurnOn != null) add {
                        Pill(stringResource(R.string.fp_turn_on), onTurnOn, kind = PillKind.Outline, height = 40.dp)
                    }
                    if (dl) add { Pill(stringResource(R.string.fp_download_instead), { onDownload?.invoke() }, kind = PillKind.Primary, height = 40.dp) }
                    if (p.kind == FileKind.NotFound && onUp != null) add { Pill(stringResource(R.string.fp_up_folder), onUp, kind = PillKind.Outline, height = 40.dp) }
                    if (p.kind == FileKind.WrongKind && onOpenOther != null) add {
                        Pill(stringResource(if (p.asFolder) R.string.fp_open_folder else R.string.fp_open_file), onOpenOther, kind = PillKind.Primary, height = 40.dp)
                    }
                    if (p.retryable && onRetry != null) add { Pill(stringResource(R.string.try_again), onRetry, kind = PillKind.Outline, height = 40.dp) }
                }
                if (actions.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    // Two pills side by side where they fit, else one under the other (a 360 dp phone).
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { actions.forEach { it() } }
                }
            }
        }
    }
}

/**
 * The floating card at the bottom of a file: Share, Save and Open with while idle or ready; the progress with
 * Cancel while it downloads; a calm line (and Try again only if it can resume) when it stopped.
 */
@Composable
fun DownloadDock(job: DownloadJob, actions: FileActions, modifier: Modifier = Modifier, onDone: (() -> Unit)? = null) {
    val state = job.state
    val outcome = actions.outcome
    GlassCard(modifier.fillMaxWidth(), accent = if (state is DlState.Failed) HN.amber else null) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            when {
                state is DlState.Running -> {
                    HText(stringResource(R.string.fv_downloading, job.fileName), style = Type.small, color = HN.ink, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(if (state.total > 0) state.done.toFloat() / state.total else null)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HText(
                            if (state.total > 0) stringResource(R.string.fv_progress, sizeText(state.done), sizeText(state.total))
                            else stringResource(R.string.fv_progress_unknown, sizeText(state.done)),
                            style = Type.monoSmall, color = HN.inkDim, modifier = Modifier.weight(1f),
                        )
                        Pill(stringResource(R.string.fv_cancel), { job.cancel() }, kind = PillKind.Outline, height = 40.dp)
                    }
                }
                state is DlState.Failed -> {
                    val pr = state.problem
                    HText(
                        when {
                            pr.kind == FileKind.TooLarge && pr.size != null && pr.max != null ->
                                stringResource(R.string.fp_toolarge_dl_body, sizeText(pr.size), sizeText(pr.max))
                            pr.retryable && state.done > 0 -> stringResource(R.string.fv_dl_interrupted, sizeText(state.done))
                            pr.retryable -> pr.message.ifBlank { stringResource(R.string.fv_dl_stopped) }
                            else -> stringResource(R.string.fv_dl_stopped)
                        },
                        style = Type.small, color = HN.inkMuted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Pill(stringResource(R.string.fl_ok), { job.dismiss() }, Modifier.weight(1f), kind = PillKind.Outline, height = 40.dp)
                        if (pr.retryable) Pill(stringResource(R.string.try_again), { job.run() }, Modifier.weight(1f), kind = PillKind.Primary, height = 40.dp)
                    }
                }
                outcome is FileActions.Outcome.Saved -> {
                    HText(stringResource(R.string.fv_saved), style = Type.small, color = HN.inkMuted)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Pill(stringResource(R.string.fl_ok), { actions.clear() }, Modifier.weight(1f), kind = PillKind.Outline, height = 40.dp)
                        Pill(stringResource(R.string.fv_open), { actions.openSaved(outcome) }, Modifier.weight(1f), kind = PillKind.Primary, height = 40.dp)
                    }
                }
                else -> {
                    when (outcome) {
                        FileActions.Outcome.SaveFailed -> HText(stringResource(R.string.fv_save_failed), style = Type.small, color = HN.inkMuted)
                        FileActions.Outcome.NoApp -> HText(stringResource(R.string.fv_no_app), style = Type.small, color = HN.inkMuted)
                        else -> if (state is DlState.Done) HText(stringResource(R.string.fv_ready, sizeText(state.bytes)), style = Type.monoSmall, color = HN.inkDim)
                    }
                    if (outcome != null || state is DlState.Done) Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Pill(stringResource(R.string.fv_share), { job.run { actions.share(it, job.mime) } }, Modifier.weight(1f), kind = PillKind.Outline, height = 44.dp)
                        Pill(stringResource(R.string.fv_save), { job.run { actions.save(it, job.fileName) } }, Modifier.weight(1f), kind = PillKind.Outline, height = 44.dp)
                        Pill(stringResource(R.string.fv_open_with), { job.run { actions.openWith(it, job.mime) } }, Modifier.weight(1f), kind = PillKind.Primary, height = 44.dp)
                    }
                }
            }
        }
    }
}

/** A thin drawn bar; [fraction] null: unknown length, a quiet track. */
@Composable
private fun ProgressBar(fraction: Float?) {
    Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(HN.ink.copy(alpha = 0.09f))) {
        if (fraction != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(HN.accent))
        else Box(Modifier.fillMaxWidth(0.25f).height(3.dp).background(HN.accent.copy(alpha = 0.6f)))
    }
}

