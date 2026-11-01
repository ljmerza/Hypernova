package com.prudnikweb.hypernova.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Notice
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlin.math.abs

/**
 * A notice as a banner at the top, over everything: what happened and in which session. Tap
 * it to go there; swipe it up or sideways, or tap ×, to dismiss. One at a time, the newest
 * replacing the last.
 */
@Composable
fun NoticeHost(notice: Notice?, onOpen: (Notice) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, onAction: (Notice) -> Unit = {}) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            notice != null,
            enter = fadeIn(tween(Motion.DIALOG)) + slideInVertically(tween(Motion.REVEAL, easing = Motion.Expo)) { -it },
            exit = fadeOut(tween(Motion.DIALOG)) + slideOutVertically(tween(Motion.DIALOG)) { -it / 2 },
        ) {
            // Keeps the last notice on screen while it slides away.
            val last = remember { arrayOfNulls<Notice>(1) }
            if (notice != null) last[0] = notice
            val n = last[0] ?: return@AnimatedVisibility
            var drag by remember(n.id) { mutableStateOf(Offset.Zero) }
            val color = noticeColor(n.kind)
            Row(
                Modifier
                    .padding(top = top + 8.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
                    // Fork: the drag is read outside the moving layer (read inside it, the banner's own
                    // move took back half of each step), and sideways goes too, as Android's own do.
                    .pointerInput(n.id) {
                        detectDragGestures(
                            onDragEnd = { if (drag.y < -40f || abs(drag.x) > size.width * 0.3f) onDismiss() else drag = Offset.Zero },
                            onDragCancel = { drag = Offset.Zero },
                        ) { change, d ->
                            change.consume()
                            drag += d
                        }
                    }
                    .graphicsLayer {
                        translationX = drag.x
                        translationY = drag.y.coerceAtMost(0f)
                        alpha = 1f - 0.6f * (abs(drag.x) / size.width).coerceIn(0f, 1f)
                    }
                    // The pop-ups' glass, its colour only a thin edge (owner, 2026-10-18).
                    .popupGlass(null, radius = 18.dp)
                    .accentEdge(color)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onOpen(n) }
                    // Fork: less on the right, where the × has its own 48dp.
                    .padding(start = 16.dp, end = 4.dp, top = 13.dp, bottom = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HText(noticeLabel(n.kind).uppercase(), style = Type.label, color = color, maxLines = 1)
                        if (n.kind != Notice.Kind.Update) {
                            Spacer(Modifier.width(8.dp))
                            HText(n.title, style = Type.label, color = HN.inkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    HText(n.text, style = Type.small, color = HN.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // Backpack's "+2 h": one tap on the banner itself, no screen to open first.
                if (n.action == Notice.ACTION_EXTEND) {
                    Spacer(Modifier.width(12.dp))
                    Pill(stringResource(R.string.notice_extend), { onAction(n) }, kind = PillKind.Primary, height = 36.dp)
                }
                // Fork: × puts it away (the owner couldn't swipe it away; a tap is sure, and TalkBack's).
                val dismiss = stringResource(R.string.notice_dismiss)
                Box(
                    Modifier
                        .size(48.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = dismiss, onClick = onDismiss)
                        .semantics { contentDescription = dismiss },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyph.Cross, size = 12.dp, color = HN.inkMuted)
                }
            }
        }
    }
}

fun noticeColor(kind: Notice.Kind): Color = when (kind) {
    // A limit is calm (Claude carries on by itself); what needs the owner stays amber.
    // An automation's note is what it found, never a call to act.
    Notice.Kind.Finished, Notice.Kind.Update, Notice.Kind.Limit, Notice.Kind.Automation -> HN.accent
    Notice.Kind.Error, Notice.Kind.ServerUpdateFailed -> HN.danger
    else -> HN.amber
}

@Composable
fun noticeLabel(kind: Notice.Kind): String = stringResource(
    when (kind) {
        Notice.Kind.Approval -> R.string.notice_kind_needs_you
        Notice.Kind.Question -> R.string.notice_kind_question
        Notice.Kind.Plan -> R.string.notice_kind_plan
        Notice.Kind.Waiting -> R.string.notice_kind_waiting
        Notice.Kind.Finished -> R.string.notice_kind_finished
        Notice.Kind.Error -> R.string.notice_kind_error
        Notice.Kind.Update -> R.string.update_title
        Notice.Kind.Limit -> R.string.notice_kind_limit
        Notice.Kind.Account -> R.string.notice_kind_account
        Notice.Kind.Backpack -> R.string.notice_kind_backpack
        Notice.Kind.Automation -> R.string.au_notice_label
        Notice.Kind.ServerUpdateFailed -> R.string.su_notice_label
    },
)
