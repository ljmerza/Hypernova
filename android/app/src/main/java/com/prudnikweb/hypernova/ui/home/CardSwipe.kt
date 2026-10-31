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

/**
 * What a swipe on a Home card does (docs/IDEAS.md, HM2): a Needs input card opens; a Completed
 * `claude agents` job stops while its process still runs, else it's deleted (asked first, as the
 * long-press menu's Delete); a Working card has none, and in the demo only Open. What the Mac can
 * do is the long-press menu's own rule ([MenuOffer]).
 */
internal enum class CardSwipe { Open, Stop, Delete;
    companion object {
        fun of(group: HomeGroup, s: Session, demo: Boolean = false): CardSwipe? = when (group) {
            HomeGroup.NeedsInput -> Open
            HomeGroup.Working -> null
            HomeGroup.Completed -> {
                val offer = MenuOffer.of(s)
                when {
                    demo -> null
                    offer.stop -> Stop
                    offer.delete -> Delete
                    else -> null
                }
            }
        }
    }
}

/** How far a card slides before letting go does its swipe: the attention cards' share of its width. */
private const val SWIPE_AT = 0.38f

/**
 * A reveal swipe for a Home card, as the modifier for the card itself: dragged left, the card
 * slides off its action, drawn under it (its word in tracked caps in its colour, on a faint wash of
 * it over what the card uncovers); past [SWIPE_AT] of its width a haptic tick says letting go will
 * do it, and the card springs back either way. Right only gives a little, so it reads as the wrong
 * way. The drag takes over only past the touch slop sideways, so the list still scrolls, a tap still
 * opens, a long-press still opens the menu and the card's own buttons keep their taps. TalkBack has
 * the action on the card. No [swipe]: nothing.
 */
@Composable
internal fun cardSwipe(swipe: CardSwipe?, onSwipe: () -> Unit): Modifier {
    if (swipe == null) return Modifier
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val reduced = LocalMotion.current.reduced
    val offset = remember { Animatable(0f) }
    val latest by rememberUpdatedState(onSwipe)
    val label = stringResource(
        when (swipe) {
            CardSwipe.Open -> R.string.swipe_open
            CardSwipe.Stop -> R.string.session_stop
            CardSwipe.Delete -> R.string.delete
        },
    )
    val said = if (swipe == CardSwipe.Open) stringResource(R.string.open_session) else label
    val tone: Color = when (swipe) {
        CardSwipe.Open -> HN.accent
        CardSwipe.Stop -> HN.amber
        CardSwipe.Delete -> HN.danger
    }
    val measurer = rememberTextMeasurer(cacheSize = 3)
    val word = remember(label, measurer) { measurer.measure(label.uppercase(), Type.label) }
    return Modifier
        .semantics { customActions = listOf(CustomAccessibilityAction(said) { latest(); true }) }
        .pointerInput(swipe) {
            val tracker = VelocityTracker()
            var crossed = false
            fun back() = scope.launch { if (reduced) offset.snapTo(0f) else offset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 500f)) }
            detectHorizontalDragGestures(
                onDragStart = { tracker.resetTracking(); crossed = false },
                onDragEnd = {
                    val v = tracker.calculateVelocity().x
                    val far = -offset.value > size.width * SWIPE_AT || (v < -1400f && -offset.value > 24.dp.toPx())
                    if (far) {
                        if (!crossed) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        latest()
                    }
                    back()
                },
                onDragCancel = { back() },
            ) { change, dx ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                val next = offset.value + dx
                val shown = if (next <= 0f) next.coerceAtLeast(-size.width.toFloat()) else (next * 0.3f).coerceAtMost(16.dp.toPx())
                val over = -shown > size.width * SWIPE_AT
                if (over != crossed) {
                    crossed = over
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                scope.launch { offset.snapTo(shown) }
            }
        }
        // Under the card, where it was: the wash over what it uncovers, and the word at the end.
        // Read while drawing only, so a drag redraws, never recomposes.
        .drawBehind {
            val shown = (-offset.value).coerceIn(0f, size.width)
            if (shown <= 0f) return@drawBehind
            drawRoundRect(tone.copy(alpha = 0.14f), Offset(size.width - shown, 0f), Size(shown, size.height), CornerRadius(16.dp.toPx()))
            val k = (shown / (size.width * SWIPE_AT)).coerceIn(0f, 1f)
            val x = size.width - 22.dp.toPx() - word.size.width
            drawText(word, color = tone, alpha = k, topLeft = Offset(x, (size.height - word.size.height) / 2f))
        }
        .graphicsLayer { translationX = offset.value }
}
