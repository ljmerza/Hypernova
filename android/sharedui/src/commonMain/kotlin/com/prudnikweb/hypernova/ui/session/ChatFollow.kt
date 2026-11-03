package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.platform.stringResource
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext

/** What changes on an item as it grows: while it does, the chat following its tail scrolls on. */
internal fun chatTail(it: Item) = when (it) {
    is Item.Terminal -> "${it.key}:${it.lines.size}:${it.running}"
    is Item.Assistant -> "${it.key}:${it.text.length}"
    is Item.Approval -> "${it.key}:${it.resolved}:${it.partial}"
    // A command's output arrives under it, after it.
    is Item.User -> "${it.key}:${it.output.size}:${it.status}"
    // A running agent's card grows when it ends (its report) or starts one of its own.
    is Item.AgentCard -> "${it.key}:${it.agent.state}:${it.nested.size}"
    is Item.WorkflowCard -> "${it.key}:${it.workflow.state}:${it.workflow.phases.sumOf { p -> p.agents.size }}"
    else -> it.key
}

/**
 * Scroll so the end of the last item sits at the bottom of the visible area, gliding at [fps]
 * steps a second. Why not animateScrollBy: while output streams, every new line glided the list
 * for a third of a second on every vsync (120 frames a second on the owner's phone), and each of
 * those frames blurred both edge bars again; a chat with a working session was the hottest screen
 * in the app. Between steps the list doesn't move, so no frame is drawn.
 */
internal suspend fun followTail(list: LazyListState, fps: Int, reduced: Boolean) {
    withFrameNanos { } // let the new item be laid out first
    val info = list.layoutInfo
    if (info.totalItemsCount == 0) return
    val last = info.visibleItemsInfo.lastOrNull()
    if (last != null && last.index == info.totalItemsCount - 1) {
        val dist = (last.offset + last.size) - (info.viewportEndOffset - info.afterContentPadding)
        if (dist > 0) list.glideBy(dist.toFloat(), fps, reduced)
    } else {
        list.scrollToItem(info.totalItemsCount - 1, Int.MAX_VALUE)
    }
}

/** A transcript item's arrival: a short fade, and nothing that moves (see the list above). */
internal val ItemFadeIn = androidx.compose.animation.core.tween<Float>(Motion.DIALOG)

/** How long the tail's glide takes (ms), as the dialog tween it used to be. */
private const val GLIDE_MS = Motion.DIALOG * 2f

/**
 * Scrolls by [dist] over [GLIDE_MS] with [Motion.Expo], moving the list only every 1/[fps] s. It
 * honours the coroutine's [MotionDurationScale] as animateScrollBy did (system animations off:
 * one jump) and the app's Reduce Motion ([reduced]: the same).
 */
private suspend fun LazyListState.glideBy(dist: Float, fps: Int, reduced: Boolean) {
    val scale = if (reduced) 0f else coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    scroll { glide(dist, fps, scale, { scrollBy(it) }, { withFrameNanos { it } }) }
}

/**
 * The glide itself, apart from the list so it can be tested: [scrollBy] moves it and answers how
 * far it did, [frame] waits for the next vsync and answers its time. A [scale] of 0 jumps at once,
 * otherwise [GLIDE_MS] is stretched by it. Between steps it sleeps rather than waking on every vsync.
 */
internal suspend fun glide(dist: Float, fps: Int, scale: Float, scrollBy: (Float) -> Float, frame: suspend () -> Long) {
    if (scale <= 0f) { scrollBy(dist); return }
    val stepNs = 1_000_000_000L / fps.coerceAtLeast(1)
    val totalMs = GLIDE_MS * scale
    val start = frame()
    var moved = 0f
    var lastStep = start
    while (true) {
        val now = frame()
        val k = ((now - start) / 1e6f / totalMs).coerceIn(0f, 1f)
        // A step is due; a vsync that comes early passes without moving anything (2 ms of slack,
        // so a step never slips a whole vsync late).
        if (k < 1f && now - lastStep < stepNs - 2_000_000L) continue
        lastStep = now
        moved += scrollBy(dist * Motion.Expo.transform(k) - moved)
        if (k >= 1f) break
        // Sleep to just before the next step instead of asking for every frame in between.
        val leftMs = (totalMs - (now - start) / 1e6f).toLong()
        delay(minOf(stepNs / 1_000_000L - 3L, leftMs).coerceAtLeast(0L))
    }
}

/** The top of a long session's transcript: "Show earlier", or a loading line while it comes. */
@Composable
internal fun ShowEarlier(loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        if (loading) LoadingLine(stringResource(Str.earlier_loading))
        else Pill(stringResource(Str.earlier_show), onClick, kind = PillKind.Ghost, height = 40.dp)
    }
}
