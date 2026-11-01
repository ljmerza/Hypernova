package com.prudnikweb.hypernova.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * What a swipe on a Home card does. Each way is the owner's pick in Settings → Card swipes
 * ([SwipeChoice], CardSwipePref.kt; DECISIONS, "Card swipes in Settings", 2026-10-31); a pick that
 * doesn't fit the card leaves that way without a swipe. Stop, Delete, Rename and Restart only where
 * the long-press menu offers them ([MenuOffer]), so a swipe never does what the menu wouldn't.
 */
internal enum class CardSwipe { Open, Stop, Delete, Rename, Restart, MarkRead;
    companion object {
        /**
         * [SwipeChoice.Smart], the swipe as first decided (docs/IDEAS.md, HM2): a Needs input card
         * opens; a Completed `claude agents` job stops while its process still runs, else it's deleted,
         * and any other Completed card is deleted (asked first, as the menu's Delete); a Working card
         * has none.
         */
        fun of(group: HomeGroup, s: Session): CardSwipe? = when (group) {
            HomeGroup.NeedsInput -> Open
            HomeGroup.Working -> null
            // Not Codex (DECISIONS, HM2): its card's Delete is Codex's for good, from the menu only.
            HomeGroup.Completed -> if (s.codex) null else {
                val offer = MenuOffer.of(s)
                when {
                    offer.stop -> Stop
                    offer.delete -> Delete
                    else -> null
                }
            }
        }

        /**
         * One way's swipe for [s] in [group] with [choice] picked for it, or none. [unread]: the card
         * shows the unread mark (data/Unread.kt), the only time Mark read fits.
         */
        fun of(choice: SwipeChoice, group: HomeGroup, s: Session, unread: Boolean): CardSwipe? {
            val offer by lazy { MenuOffer.of(s) }
            return when (choice) {
                SwipeChoice.Smart -> of(group, s)
                SwipeChoice.Open -> Open
                SwipeChoice.Stop -> Stop.takeIf { offer.stop }
                SwipeChoice.Delete -> Delete.takeIf { offer.delete }
                SwipeChoice.Rename -> Rename.takeIf { offer.rename }
                SwipeChoice.Restart -> Restart.takeIf { offer.restart }
                SwipeChoice.MarkRead -> MarkRead.takeIf { unread }
                SwipeChoice.Nothing -> null
            }
        }
    }
}

/** The word drawn under the card as it slides. */
private fun word(a: CardSwipe): Int = when (a) {
    CardSwipe.Open -> R.string.swipe_open
    CardSwipe.Stop -> R.string.session_stop
    CardSwipe.Delete -> R.string.delete
    CardSwipe.Rename -> R.string.session_rename
    CardSwipe.Restart -> R.string.session_restart
    CardSwipe.MarkRead -> R.string.swipe_mark_read
}

/** What TalkBack's action on the card says. */
private fun said(a: CardSwipe): Int = if (a == CardSwipe.Open) R.string.open_session else word(a)

private fun tone(a: CardSwipe): Color = when (a) {
    CardSwipe.Open, CardSwipe.MarkRead -> HN.accent
    CardSwipe.Stop, CardSwipe.Restart -> HN.amber
    CardSwipe.Delete -> HN.danger
    CardSwipe.Rename -> HN.ink
}

/** How far a card slides before letting go does its swipe: the attention cards' share of its width. */
private const val SWIPE_AT = 0.38f

/**
 * A reveal swipe for a Home card, as the modifier for the card itself: dragged toward a way with a
 * swipe ([left], [right]), the card slides off its action, drawn under it at the end it uncovers (its
 * word in tracked caps in its colour, on a faint wash of it); past [SWIPE_AT] of its width a haptic
 * tick says letting go will do it, and the card springs back either way. A way without one only
 * gives a little, so it reads as the wrong way. The drag takes over only past the touch slop
 * sideways, so the list still scrolls, a tap still opens, a long-press still opens the menu and the
 * card's own buttons keep their taps. TalkBack has each swipe as an action on the card. Neither way:
 * nothing.
 */
@Composable
internal fun cardSwipe(left: CardSwipe?, right: CardSwipe?, onSwipe: (CardSwipe) -> Unit): Modifier {
    if (left == null && right == null) return Modifier
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val reduced = LocalMotion.current.reduced
    val offset = remember { Animatable(0f) }
    val latest by rememberUpdatedState(onSwipe)
    val leftLabel = left?.let { stringResource(word(it)) }
    val rightLabel = right?.let { stringResource(word(it)) }
    val leftSaid = left?.let { stringResource(said(it)) }
    val rightSaid = right?.let { stringResource(said(it)) }
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val leftWord = remember(leftLabel, measurer) { leftLabel?.let { measurer.measure(it.uppercase(), Type.label) } }
    val rightWord = remember(rightLabel, measurer) { rightLabel?.let { measurer.measure(it.uppercase(), Type.label) } }
    val actions = buildList {
        if (left != null && leftSaid != null) add(CustomAccessibilityAction(leftSaid) { latest(left); true })
        if (right != null && rightSaid != null && right != left) add(CustomAccessibilityAction(rightSaid) { latest(right); true })
    }
    return Modifier
        .semantics { customActions = actions }
        .pointerInput(left, right) {
            val tracker = VelocityTracker()
            var crossed = false
            fun back() = scope.launch { if (reduced) offset.snapTo(0f) else offset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 500f)) }
            // The swipe a card slid to [x] does once let go: past the threshold toward a way that has one.
            fun past(x: Float): CardSwipe? = when {
                x < 0f && -x > size.width * SWIPE_AT -> left
                x > 0f && x > size.width * SWIPE_AT -> right
                else -> null
            }
            detectHorizontalDragGestures(
                onDragStart = { tracker.resetTracking(); crossed = false },
                onDragEnd = {
                    val v = tracker.calculateVelocity().x
                    val x = offset.value
                    val nudge = 24.dp.toPx()
                    val flung = when {
                        v < -1400f && -x > nudge -> left
                        v > 1400f && x > nudge -> right
                        else -> null
                    }
                    val act = past(x) ?: flung
                    if (act != null) {
                        if (!crossed) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        latest(act)
                    }
                    back()
                },
                onDragCancel = { back() },
            ) { change, dx ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                val next = offset.value + dx
                val w = size.width.toFloat()
                val give = 16.dp.toPx()
                val shown = if (next <= 0f) {
                    if (left != null) next.coerceAtLeast(-w) else (next * 0.3f).coerceAtLeast(-give)
                } else {
                    if (right != null) next.coerceAtMost(w) else (next * 0.3f).coerceAtMost(give)
                }
                val over = past(shown) != null
                if (over != crossed) {
                    crossed = over
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                scope.launch { offset.snapTo(shown) }
            }
        }
        // Under the card, where it was: the wash over what it uncovers, and the word at that end.
        // Read while drawing only, so a drag redraws, never recomposes.
        .drawBehind {
            val x = offset.value
            if (x == 0f) return@drawBehind
            val toLeft = x < 0f
            val act = (if (toLeft) left else right) ?: return@drawBehind
            val word = (if (toLeft) leftWord else rightWord) ?: return@drawBehind
            val tone = tone(act)
            val shown = abs(x).coerceAtMost(size.width)
            val from = if (toLeft) size.width - shown else 0f
            drawRoundRect(tone.copy(alpha = 0.14f), Offset(from, 0f), Size(shown, size.height), CornerRadius(16.dp.toPx()))
            val k = (shown / (size.width * SWIPE_AT)).coerceIn(0f, 1f)
            val at = if (toLeft) size.width - 22.dp.toPx() - word.size.width else 22.dp.toPx()
            drawText(word, color = tone, alpha = k, topLeft = Offset(at, (size.height - word.size.height) / 2f))
        }
        .graphicsLayer { translationX = offset.value }
}
