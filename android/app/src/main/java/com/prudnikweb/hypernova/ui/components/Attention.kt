package com.prudnikweb.hypernova.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Attention
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The cards swiped away, for the life of the process (data/Attention.kt holds the rules). State,
 * so a list that asks [show] recomposes when one goes or comes back.
 */
object AttentionStore {
    var gone by mutableStateOf<Map<String, Attention.Gone>>(emptyMap())
        private set

    fun show(id: String, state: String): Attention.Show = Attention.show(gone, id, state, System.currentTimeMillis())
    fun dismiss(id: String, state: String) { gone = Attention.dismiss(gone, id, state, System.currentTimeMillis()) }
    fun undo(id: String) { gone = Attention.undo(gone, id) }
    fun settle(id: String) { gone = Attention.settle(gone, id) }
    fun forgetChanged(id: String, state: String) {
        val next = Attention.forgetChanged(gone, id, state)
        if (next !== gone) gone = next
    }
}

/**
 * The computer the attention cards under it are about, while Home shows "All servers" (its cards
 * above the sessions are the one picked last's): a quiet name over each card's title. Null: one
 * computer on screen, nothing said.
 */
val LocalAttentionFrom = androidx.compose.runtime.staticCompositionLocalOf<String?> { null }

/** An action on an attention card: the pill on its right, or a quiet link under its line. */
class AttentionAction(val text: String, val onClick: () -> Unit, val glyph: Glyph? = null, val description: String? = null)

/**
 * Something that needs a look (owner, 2026-10-18: needs you, provider trouble, limits): the glass
 * of every other card, a [glyph] in [tone] in a small glass circle (an eclipse when null), and a
 * thin edge of [tone] down its left side instead of a tint. [title] and one [line]; [action] is
 * the small pill on the right (under the words, at the end, when its words are long); [stamp] a
 * quiet figure under the line (a countdown); [links] are
 * quieter ways on, as text under the line; [content] is anything the card grows (a sign-in in
 * progress). A tap on the card body calls [onClick].
 *
 * Swiped right it goes ([id] names it, [state] is what it says): an Undo stands in its place for a
 * few seconds, and it stays away until [state] changes (data/Attention.kt). TalkBack has Dismiss as
 * an action. [dismissible] false keeps it (it can't be put off).
 */
@Composable
fun AttentionCard(
    id: String,
    state: String,
    tone: Color,
    title: String,
    line: String?,
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    /** A quiet figure under the line ("resets in 3h 19m", "retrying in 4s"), after [stampGlyph]. */
    stamp: String? = null,
    stampGlyph: Glyph? = Glyph.Timer,
    action: AttentionAction? = null,
    links: List<AttentionAction> = emptyList(),
    onClick: (() -> Unit)? = null,
    dismissible: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    when (AttentionStore.show(id, state)) {
        Attention.Show.Undo -> { UndoRow(id, title, modifier); return }
        Attention.Show.Nothing -> return
        Attention.Show.Card -> Unit
    }
    // Showing again for something new: the old dismissal is over.
    SideEffect { AttentionStore.forgetChanged(id, state) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val reduced = LocalMotion.current.reduced
    val offset = remember(id) { Animatable(0f) }
    var width by remember { mutableIntStateOf(1) }
    val dismissLabel = stringResource(R.string.attention_dismiss)
    val latestState by rememberUpdatedState(state)
    val dismiss: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        AttentionStore.dismiss(id, latestState)
    }
    GlassCard(
        modifier
            .fillMaxWidth()
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .graphicsLayer {
                translationX = offset.value
                alpha = 1f - 0.85f * (offset.value / width).coerceIn(0f, 1f)
            }
            .then(
                if (!dismissible) Modifier else Modifier
                    .semantics { customActions = listOf(CustomAccessibilityAction(dismissLabel) { dismiss(); true }) }
                    .pointerInput(id) {
                        val tracker = VelocityTracker()
                        var crossed = false
                        detectHorizontalDragGestures(
                            onDragStart = { tracker.resetTracking(); crossed = false },
                            onDragEnd = {
                                val v = tracker.calculateVelocity().x
                                val far = offset.value > size.width * 0.38f || (v > 1400f && offset.value > 24.dp.toPx())
                                scope.launch {
                                    if (far) {
                                        if (!reduced) offset.animateTo(size.width.toFloat(), tween(Motion.DIALOG))
                                        dismiss()
                                    } else offset.animateTo(0f, spring(dampingRatio = 0.75f, stiffness = 500f))
                                }
                            },
                            onDragCancel = { scope.launch { offset.animateTo(0f) } },
                        ) { change, dx ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            // Right goes; left only gives a little, so it reads as the wrong way.
                            val next = offset.value + dx
                            val shown = if (next >= 0f) next else (next * 0.3f).coerceAtLeast(-16.dp.toPx())
                            val over = shown > size.width * 0.38f
                            if (over != crossed) {
                                crossed = over
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            scope.launch { offset.snapTo(shown) }
                        }
                    },
            ),
        edge = tone,
        onClick = onClick,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp)) {
            BesideOrBelow(
                main = {
                    Row(verticalAlignment = Alignment.Top) {
                        ToneBadge(glyph, tone)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f).padding(top = 1.dp)) {
                            LocalAttentionFrom.current?.let { from ->
                                HText(from, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                                Spacer(Modifier.height(2.dp))
                            }
                            HText(title, style = Type.heading, color = HN.ink)
                            if (line != null) {
                                Spacer(Modifier.height(3.dp))
                                HText(line, style = Type.small, color = HN.inkMuted, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            }
                            if (stamp != null) {
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (stampGlyph != null) {
                                        Icon(stampGlyph, size = 11.dp, color = HN.inkDim, stroke = 1.1.dp)
                                        Spacer(Modifier.width(5.dp))
                                    }
                                    HText(stamp, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
                                }
                            }
                        }
                    }
                },
                action = action?.let { a ->
                    {
                        val desc = a.description
                        Pill(
                            a.text, a.onClick,
                            if (desc != null) Modifier.semantics { contentDescription = desc } else Modifier,
                            kind = PillKind.Primary, height = 36.dp, leading = a.glyph,
                        )
                    }
                },
                links = if (links.isEmpty()) null else ({ AttentionLinks(links) }),
            )
            if (content != null) Column(Modifier.padding(start = 44.dp, top = 10.dp)) { content() }
        }
    }
}

/** The ways on that aren't the card's one pill: text in the accent, a gap apart, wrapping. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AttentionLinks(links: List<AttentionAction>, modifier: Modifier = Modifier) {
    val view = LocalView.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        for (l in links) {
            val desc = l.description
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        l.onClick()
                    }
                    .then(if (desc != null) Modifier.semantics { contentDescription = desc } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (l.glyph != null) {
                    Icon(l.glyph, size = 12.dp, color = HN.accentDim)
                    Spacer(Modifier.width(6.dp))
                }
                HText(l.text, style = Type.small.copy(fontWeight = FontWeight(600)), color = HN.accentDim, maxLines = 1)
            }
        }
    }
}

/** The card's glyph in its tone, in a 32dp circle of frosted glass lit by the same tone. */
@Composable
private fun ToneBadge(glyph: Glyph?, tone: Color) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).frostedGlass(tone, glowX = 16.dp, dense = true),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) Icon(glyph, size = 15.dp, color = tone, stroke = 1.4.dp)
        else EclipseMark(tone, size = 15.dp)
    }
}

/**
 * The words and the card's pill: side by side while the pill takes at most about two fifths of
 * the row, else the pill goes under the words, at the end. [links] go under the words, indented
 * to them; when the pill is under the words too, they share its row (links at the start, the pill
 * at the end) while they fit beside it.
 */
@Composable
private fun BesideOrBelow(main: @Composable () -> Unit, action: (@Composable () -> Unit)?, links: (@Composable () -> Unit)? = null) {
    Layout(content = { main(); action?.invoke() ?: Box(Modifier); links?.invoke() ?: Box(Modifier) }) { measurables, c ->
        val gap = 12.dp.roundToPx()
        val indent = 44.dp.roundToPx()
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val a = if (action != null) measurables[1].measure(Constraints(maxWidth = c.maxWidth)) else null
        val beside = a == null || a.width <= c.maxWidth * 0.42f
        val m = measurables[0].measure(loose.copy(maxWidth = if (a != null && beside) (c.maxWidth - a.width - gap).coerceAtLeast(0) else c.maxWidth))
        val linkW = (c.maxWidth - indent).coerceAtLeast(0)
        if (beside) {
            val l = if (links != null) measurables[2].measure(loose.copy(maxWidth = linkW)) else null
            val top = maxOf(m.height, a?.height ?: 0)
            val linksAt = top + 4.dp.roundToPx()
            layout(c.maxWidth, if (l != null) linksAt + l.height else top) {
                m.place(0, 0)
                a?.place(c.maxWidth - a.width, ((minOf(m.height, 48.dp.roundToPx()) - a.height) / 2).coerceAtLeast(0))
                l?.place(indent, linksAt)
            }
        } else {
            val rowAt = m.height + 8.dp.roundToPx()
            val room = (linkW - a!!.width - gap).coerceAtLeast(0)
            val l = if (links != null) measurables[2].measure(loose.copy(maxWidth = linkW)) else null
            val shared = l != null && l.width <= room
            val h = if (l == null) rowAt + a.height else if (shared) rowAt + maxOf(a.height, l.height) else rowAt + a.height + 4.dp.roundToPx() + l.height
            layout(c.maxWidth, h) {
                m.place(0, 0)
                a.place(c.maxWidth - a.width, rowAt)
                if (l != null) {
                    if (shared) l.place(indent, rowAt + (a.height - l.height) / 2)
                    else l.place(indent, rowAt + a.height + 4.dp.roundToPx())
                }
            }
        }
    }
}

/**
 * Where a card was swiped away: a slim glass line, the card's title, and Undo, for a few seconds;
 * then it goes too.
 */
@Composable
private fun UndoRow(id: String, title: String, modifier: Modifier) {
    LaunchedEffect(id) {
        delay(Attention.UNDO_MS)
        AttentionStore.settle(id)
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .frostedGlass(null, glowX = 0.dp, dense = true, radius = 16.dp)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(stringResource(R.string.attention_hidden).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        HText(title, style = Type.small, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Pill(stringResource(R.string.attention_undo), { AttentionStore.undo(id) }, kind = PillKind.Ghost, height = 36.dp)
    }
}
