package com.prudnikweb.hypernova.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Aside
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.GlyphButton
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The answer to a side question asked from the prompt box ("/btw …"; data/Asides.kt): a card that
 * slides up over the box, not a pop-up, so the chat stays in reach. "Answering…" until the Mac has
 * read it off the terminal, then the answer, selectable; Claude Code's error words; or, where it
 * couldn't be read or ran past the terminal's screen, Open the live terminal. Swiped sideways or
 * pulled down it goes ([onDismiss], on this phone only); the Commands tab still has the last one.
 */
@Composable
internal fun AsideCardHost(aside: Aside?, terminal: Boolean, onTerminal: () -> Unit, onDismiss: (Aside) -> Unit) {
    AnimatedVisibility(
        aside != null,
        enter = fadeIn(tween(Motion.DIALOG)) + slideInVertically(tween(Motion.REVEAL, easing = Motion.Expo)) { it },
        exit = fadeOut(tween(Motion.DIALOG)) + slideOutVertically(tween(Motion.DIALOG)) { it / 2 },
    ) {
        // Keeps the last one on screen while the card goes.
        val last = remember { arrayOfNulls<Aside>(1) }
        if (aside != null) last[0] = aside
        val a = last[0] ?: return@AnimatedVisibility
        Column {
            // A new question is a new card: its own swipe from nothing.
            androidx.compose.runtime.key(a.id) { AsideCard(a, terminal, onTerminal, onDismiss = { onDismiss(a) }) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** How far a pull or a swipe takes the card before it goes, and how fast a flick does it. */
private val PULL_TO_DISMISS = 72.dp
private const val FLICK = 1400f

@Composable
private fun AsideCard(a: Aside, terminal: Boolean, onTerminal: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val pullPx = with(density) { PULL_TO_DISMISS.toPx() }
    var width by remember { mutableFloatStateOf(1f) }
    var height by remember { mutableFloatStateOf(1f) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var gone by remember { mutableFloatStateOf(0f) }
    fun settleY(velocity: Float) {
        scope.launch {
            if (dy > pullPx || velocity > FLICK) {
                animate(dy, height + pullPx, animationSpec = tween(Motion.DIALOG)) { v, _ -> dy = v }
                onDismiss()
            } else animate(dy, 0f, animationSpec = tween(Motion.DIALOG, easing = Motion.Expo)) { v, _ -> dy = v }
        }
    }
    fun settleX(velocity: Float) {
        scope.launch {
            if (abs(dx) > width * 0.3f || abs(velocity) > FLICK) {
                val to = if ((if (dx != 0f) dx else velocity) > 0) width else -width
                animate(dx, to, animationSpec = tween(Motion.DIALOG)) { v, _ -> dx = v }
                gone = 1f
                onDismiss()
            } else animate(dx, 0f, animationSpec = tween(Motion.DIALOG, easing = Motion.Expo)) { v, _ -> dx = v }
        }
    }
    // A pull down: what the answer's own scroll leaves (at its top, or when it all fits) moves the card.
    val pull = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0f || dy <= 0f) return Offset.Zero
                val take = maxOf(available.y, -dy)
                dy += take
                return Offset(0f, take)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                dy += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dy <= 0f) return Velocity.Zero
                settleY(available.y)
                return available
            }
        }
    }
    val dismissLabel = stringResource(R.string.aside_card_dismiss)
    GlassCard(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f); height = it.height.toFloat().coerceAtLeast(1f) }
            .graphicsLayer {
                translationX = dx
                translationY = dy
                alpha = (1f - 0.7f * maxOf(abs(dx) / width, dy / (height + pullPx))).coerceIn(0f, 1f) * (1f - gone)
            }
            .draggable(rememberDraggableState { dx += it }, Orientation.Horizontal, onDragStopped = { v -> settleX(v) })
            .semantics { customActions = listOf(CustomAccessibilityAction(dismissLabel) { onDismiss(); true }) },
        accent = when (a.state) {
            Aside.FAILED -> HN.amber
            else -> HN.accent
        },
    ) {
        Column(
            Modifier
                .nestedScroll(pull)
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText(
                    stringResource(R.string.aside_question, a.question), style = Type.monoSmall, color = HN.inkMuted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                GlyphButton(Glyph.Cross, dismissLabel, onDismiss)
            }
            Column(Modifier.padding(end = 10.dp)) {
                when (a.state) {
                    Aside.ASKING -> LoadingLine(stringResource(R.string.aside_asking))
                    Aside.ANSWERED -> {
                        SelectionContainer { HText(a.answer.orEmpty(), style = Type.body, color = HN.ink) }
                        if (a.clipped) {
                            Spacer(Modifier.height(6.dp))
                            HText(stringResource(R.string.aside_clipped), style = Type.small, color = HN.inkDim)
                        }
                    }
                    Aside.FAILED -> HText(stringResource(R.string.aside_failed, a.error.orEmpty()), style = Type.small, color = HN.amber)
                    else -> HText(stringResource(R.string.aside_unseen), style = Type.small, color = HN.inkDim)
                }
                val more = a.state == Aside.UNSEEN || (a.state == Aside.ANSWERED && a.clipped)
                if (more && terminal) {
                    Spacer(Modifier.height(10.dp))
                    Pill(stringResource(R.string.open_terminal), onTerminal, kind = PillKind.Ghost, height = 40.dp, leading = Glyph.Terminal)
                }
            }
        }
    }
}
