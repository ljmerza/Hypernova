package com.prudnikweb.hypernova.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.BuildConfig
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AppUpdate
import com.prudnikweb.hypernova.data.AppUpdater
import com.prudnikweb.hypernova.data.UpdateFailure
import com.prudnikweb.hypernova.data.UpdateState
import com.prudnikweb.hypernova.data.openLink
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

/*
 * The in-app update on screen (data/AppUpdater.kt): a banner at the top while it's under way, and the
 * card in Settings. Both say the same: the download's progress as a hairline bar and its percent,
 * Android's "Install unknown apps" once, Install when Android's dialog waits for a tap, and what
 * stopped it, with Try again.
 */

/** The failure in a sentence. */
@Composable
fun updateFailureText(f: UpdateFailure): String = stringResource(
    when (f) {
        UpdateFailure.Network -> R.string.update_fail_network
        UpdateFailure.Missing -> R.string.update_fail_missing
        UpdateFailure.Damaged -> R.string.update_fail_damaged
        UpdateFailure.OtherApp -> R.string.update_fail_other_app
        UpdateFailure.NotNewer -> R.string.update_fail_not_newer
        UpdateFailure.Storage -> R.string.update_fail_storage
        UpdateFailure.OtherKey -> R.string.update_fail_other_key
        UpdateFailure.Blocked -> R.string.update_fail_blocked
        UpdateFailure.Install -> R.string.update_fail_install
    },
)

/**
 * The download as a hairline: a faint 2dp track and the part done in mint (amber for a failure), with
 * round ends. [fraction] null: the size isn't known yet, so only the track.
 */
@Composable
fun UpdateBar(fraction: Float?, modifier: Modifier = Modifier, color: Color = HN.accent) {
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(HN.ink.copy(alpha = 0.09f), cornerRadius = r)
                val f = fraction ?: return@drawBehind
                if (f > 0f) drawRoundRect(color, Offset.Zero, Size(size.width * f, size.height), r)
            },
    )
}

/** "18.4 MB of 45.1 MB" (or only what has come while the Mac hasn't said the size). */
@Composable
private fun sizeText(read: Long, total: Long): String {
    val res = LocalContext.current.resources
    fun mb(b: Long) = res.getString(R.string.update_mb, b / 1_048_576.0)
    return if (total > 0) res.getString(R.string.update_size, mb(read), mb(total)) else mb(read)
}

/**
 * The banner while an update is under way (never while idle). It sits where a notice's banner
 * does, in its look, and gives way to one: [covered] while a notice shows. Swiped up, it stays
 * away until the update reaches its next step (Install waiting, a failure).
 */
@Composable
fun UpdateBannerHost(covered: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state = AppUpdater.state
    val update = AppUpdater.target
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // The step it was put away at: the same step stays away, the next one shows.
    var hiddenAt by remember { mutableStateOf<Class<*>?>(null) }
    val step = state::class.java
    val show = state != UpdateState.Idle && update != null && !covered && hiddenAt != step
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            show,
            enter = fadeIn(tween(Motion.DIALOG)) + slideInVertically(tween(Motion.REVEAL, easing = Motion.Expo)) { -it },
            exit = fadeOut(tween(Motion.DIALOG)) + slideOutVertically(tween(Motion.DIALOG)) { -it / 2 },
        ) {
            // Keeps the last picture while it slides away.
            val last = remember { arrayOfNulls<UpdateState>(1) }
            if (state != UpdateState.Idle) last[0] = state
            val s = last[0] ?: return@AnimatedVisibility
            val failed = s is UpdateState.Failed
            val color = if (failed) HN.amber else HN.accent
            var drag by remember { mutableFloatStateOf(0f) }
            val shape = RoundedCornerShape(18.dp)
            Column(
                Modifier
                    .padding(top = top + 8.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
                    .graphicsLayer { translationY = drag.coerceAtMost(0f) }
                    .clip(shape)
                    .background(HN.bg900.copy(alpha = 0.97f))
                    .border(1.dp, color.copy(alpha = 0.35f), shape)
                    .pointerInput(step) {
                        detectVerticalDragGestures(onDragEnd = {
                            if (drag < -40f) {
                                hiddenAt = step
                                // A failure is put away with it; a step that waits on the user stays in Settings.
                                if (s is UpdateState.Failed) AppUpdater.dismiss(context)
                            }
                            drag = 0f
                        }) { _, dy -> drag += dy }
                    }
                    .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HText(stringResource(if (failed) R.string.update_failed else R.string.update_title).uppercase(), style = Type.label, color = color, maxLines = 1)
                            Spacer(Modifier.width(8.dp))
                            HText(update?.versionName.orEmpty(), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                        }
                        Spacer(Modifier.height(3.dp))
                        HText(bannerLine(s), style = Type.small, color = HN.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    when {
                        s is UpdateState.Downloading -> {
                            val pct = s.fraction?.let { (it * 100).toInt() }
                            if (pct != null) HText(stringResource(R.string.update_percent, pct), style = Type.monoSmall, color = HN.accent, maxLines = 1)
                        }
                        s is UpdateState.NeedsPermission -> Pill(stringResource(R.string.update_allow), { AppUpdater.allowInstalls(context) }, kind = PillKind.Primary, height = 36.dp)
                        s is UpdateState.Confirming -> update?.let { u -> Pill(stringResource(R.string.update_install), { AppUpdater.start(context, u) }, kind = PillKind.Primary, height = 36.dp) }
                        s is UpdateState.Failed -> update?.let { u -> Pill(stringResource(R.string.try_again), { AppUpdater.start(context, u) }, kind = PillKind.Outline, height = 36.dp) }
                        else -> Unit
                    }
                }
                if (s is UpdateState.Downloading || s is UpdateState.Checking) {
                    Spacer(Modifier.height(10.dp))
                    UpdateBar(if (s is UpdateState.Downloading) s.fraction else 1f, Modifier.progress(s))
                }
            }
        }
    }
}

/** What the banner says at [s]. */
@Composable
private fun bannerLine(s: UpdateState): String = when (s) {
    is UpdateState.Downloading -> stringResource(R.string.update_downloading)
    is UpdateState.Checking -> stringResource(R.string.update_checking)
    is UpdateState.NeedsPermission -> stringResource(R.string.update_permission)
    is UpdateState.Confirming -> stringResource(if (s.shown) R.string.update_confirming else R.string.update_ready)
    is UpdateState.Failed -> updateFailureText(s.failure)
    UpdateState.Idle -> ""
}

/** TalkBack hears the download as a progress bar, politely. */
private fun Modifier.progress(s: UpdateState): Modifier = semantics {
    liveRegion = LiveRegionMode.Polite
    val f = (s as? UpdateState.Downloading)?.fraction ?: if (s is UpdateState.Checking) 1f else 0f
    progressBarRangeInfo = ProgressBarRangeInfo(f, 0f..1f)
}

/**
 * Settings' update card: what's on the Mac, and the way to it in the app. [browserPage]: the
 * Mac's page when a browser could open it (data/UpdatePolicy.kt `browserPage`), as a quiet way
 * round; null keeps the update in the app.
 */
@Composable
fun UpdateCard(update: AppUpdate, browserPage: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // The flow is about another version (an older offer): this card starts afresh.
    val s = AppUpdater.state.takeIf { AppUpdater.target?.versionCode == update.versionCode } ?: UpdateState.Idle
    Column(modifier) {
        Seam(stringResource(R.string.update_title), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp), color = HN.accent)
        GlassCard(Modifier.fillMaxWidth(), accent = HN.accent) {
            Column(Modifier.padding(16.dp)) {
                HText(stringResource(R.string.update_heading), style = Type.heading, color = HN.ink)
                Spacer(Modifier.height(4.dp))
                HText(stringResource(R.string.update_detail, update.versionName, update.versionCode, BuildConfig.VERSION_CODE), style = Type.small, color = HN.inkMuted)
                Spacer(Modifier.height(14.dp))
                when (s) {
                    is UpdateState.Downloading, is UpdateState.Checking -> {
                        val fraction = if (s is UpdateState.Downloading) s.fraction else 1f
                        val pct = ((fraction ?: 0f) * 100).toInt()
                        val said = stringResource(R.string.update_said_progress, pct)
                        Column(Modifier.clearAndSetSemantics { contentDescription = said; progressBarRangeInfo = ProgressBarRangeInfo(fraction ?: 0f, 0f..1f) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HText(stringResource(if (s is UpdateState.Checking) R.string.update_checking else R.string.update_downloading), style = Type.small, color = HN.inkMuted, maxLines = 1, modifier = Modifier.weight(1f))
                                if (s is UpdateState.Downloading) {
                                    HText(sizeText(s.read, s.total), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                                    if (s.fraction != null) {
                                        Spacer(Modifier.width(10.dp))
                                        HText(stringResource(R.string.update_percent, pct), style = Type.monoSmall, color = HN.accent, maxLines = 1)
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            UpdateBar(fraction)
                        }
                    }
                    is UpdateState.NeedsPermission -> {
                        HText(stringResource(R.string.update_permission_body), style = Type.small, color = HN.inkMuted)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill(stringResource(R.string.update_allow_installs), { AppUpdater.allowInstalls(context) }, kind = PillKind.Primary, height = 44.dp)
                            Pill(stringResource(R.string.update_not_now), { AppUpdater.dismiss(context) }, kind = PillKind.Ghost, height = 44.dp)
                        }
                    }
                    is UpdateState.Confirming -> {
                        HText(stringResource(if (s.shown) R.string.update_confirming else R.string.update_ready_body), style = Type.small, color = HN.inkMuted)
                        Spacer(Modifier.height(14.dp))
                        Pill(stringResource(R.string.update_install), { AppUpdater.start(context, update) }, kind = PillKind.Primary, height = 44.dp)
                    }
                    is UpdateState.Failed -> {
                        HText(updateFailureText(s.failure), style = Type.small, color = HN.amber)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill(stringResource(R.string.try_again), { AppUpdater.start(context, update) }, kind = PillKind.Primary, height = 44.dp)
                            browserPage?.let { Pill(stringResource(R.string.update_open_browser), { openLink(context, it) }, kind = PillKind.Ghost, height = 44.dp) }
                        }
                    }
                    UpdateState.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Pill(stringResource(R.string.update_get), { AppUpdater.start(context, update) }, kind = PillKind.Primary, height = 44.dp)
                        browserPage?.let { Pill(stringResource(R.string.update_open_browser), { openLink(context, it) }, kind = PillKind.Ghost, height = 44.dp) }
                    }
                }
            }
        }
    }
}
