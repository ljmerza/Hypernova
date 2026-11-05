package com.prudnikweb.hypernova.ui.components

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.platform.SharedUi
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

// One floating glass card for every dialog and sheet, on both systems (the owner, 2026-10-18; the phone's Glass.kt has why). What's each system's own
// is the window it floats in: UiOverlays.

/** A pill in a [GlassDialog]: its words, what it does, its kind (Ghost, Outline, Primary, Danger), and whether it can be tapped yet. */
class DialogButton(val text: String, val onClick: () -> Unit, val kind: PillKind = PillKind.Outline, val enabled: Boolean = true)

/**
 * A choice, as a floating glass card near the bottom of the screen, in reach of the thumb: [eyebrow]
 * in small tracked caps (in [tint], the only colour), [title], one short [line], then [more] behind
 * a quiet "More" ([moreLabel]), and the two pills side by side, [secondary] on the left and
 * [primary] on the right. Back and a tap outside call [onDismiss]. [tint] also lights the card's top
 * edge, faintly; null keeps it neutral. [warm] warms the scrim toward [tint] (Bypass).
 */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    title: String,
    primary: DialogButton,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    line: String? = null,
    secondary: DialogButton? = null,
    tint: Color? = null,
    warm: Boolean = false,
    moreLabel: String? = null,
    more: (@Composable ColumnScope.() -> Unit)? = null,
    /** What the choice shows besides words, between [line] and More. */
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    PopupWindow(onDismiss, eyebrow ?: title, if (warm) tint else null) { shown ->
        PopupEnter(shown) {
            Column(
                modifier
                    .padding(horizontal = 12.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .heightIn(max = 720.dp)
                    .popupGlass(tint)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .verticalScroll(rememberScrollState())
                    .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp),
            ) {
                if (eyebrow != null) {
                    HText(eyebrow.uppercase(), style = Type.label, color = tint ?: HN.inkMuted)
                    Spacer(Modifier.height(10.dp))
                }
                HText(title, style = Type.title, color = HN.ink, modifier = Modifier.semantics { heading() })
                if (line != null) {
                    Spacer(Modifier.height(8.dp))
                    HText(line, style = Type.body, color = HN.inkMuted)
                }
                if (body != null) {
                    Spacer(Modifier.height(16.dp))
                    body()
                }
                if (more != null) {
                    var open by rememberSaveable { mutableStateOf(false) }
                    MoreToggle(moreLabel ?: stringResource(Str.more), open) { open = !open }
                    AnimatedVisibility(open, enter = fadeIn(tween(Motion.DIALOG)) + expandVertically(tween(Motion.DIALOG)), exit = fadeOut(tween(Motion.FAST)) + shrinkVertically(tween(Motion.DIALOG))) {
                        Column(Modifier.padding(top = 2.dp, bottom = 4.dp)) { more() }
                    }
                }
                Spacer(Modifier.height(if (more != null) 14.dp else 22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (secondary != null) Pill(secondary.text, secondary.onClick, Modifier.weight(1f), kind = secondary.kind, height = 48.dp, enabled = secondary.enabled)
                    Pill(primary.text, primary.onClick, Modifier.weight(1f), kind = primary.kind, height = 48.dp, enabled = primary.enabled)
                }
            }
        }
    }
}

/** "More" with a chevron that turns down while it's open: details a card keeps out of the way. */
@Composable
fun MoreToggle(label: String, open: Boolean, onClick: () -> Unit) {
    val turn by animateFloatAsState(if (open) 90f else 0f, tween(Motion.DIALOG), label = "more")
    Row(
        Modifier
            .padding(top = 6.dp)
            .heightIn(min = 44.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(label, style = Type.small.copy(fontWeight = FontWeight(600)), color = HN.accentDim)
        Spacer(Modifier.width(6.dp))
        Icon(Glyph.Chevron, Modifier.rotate(turn), size = 11.dp, color = HN.accentDim)
    }
}

/**
 * A sheet (a picker, a menu, a small form) as the same floating glass card, rising from the bottom
 * above the keyboard, with a handle; [content] brings its own title. [height]: a height of its own (a
 * picker whose list doesn't jump as it's searched), shrunk to fit. [scroll] false: [content] scrolls
 * by itself (a lazy list). A tap on the scrim or Back calls [onDismiss].
 */
@Composable
fun GlassSheet(
    title: String,
    onDismiss: () -> Unit,
    height: Dp? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    PopupWindow(onDismiss, title, null) { shown ->
        PopupEnter(shown) {
            Column(
                Modifier
                    .padding(horizontal = 10.dp)
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .then(if (height != null) Modifier.height(height) else Modifier.heightIn(max = 640.dp))
                    .popupGlass(null)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = if (scroll) 16.dp else 0.dp),
            ) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(HN.ink.copy(alpha = 0.18f)))
                }
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

/**
 * The app's message as it stands, for a pop-up's window: it's over where the app draws its message, so it draws it again on its card.
 * Null where nothing provides it (a test's screen).
 */
class ToastNow(val text: () -> String?, val dismiss: () -> Unit)
val LocalToastNow = staticCompositionLocalOf<ToastNow?> { null }

/** Each system's window for a card, with the app's message on it: the window is over where the app draws one, which would go unseen (the owner, 2026-11-04). */
@Composable
private fun PopupWindow(onDismiss: () -> Unit, pane: String, tint: Color?, content: @Composable (shown: Boolean) -> Unit) {
    SharedUi.platform.overlays.Window(onDismiss, pane, tint) { shown ->
        Box(contentAlignment = Alignment.TopCenter) {
            content(shown)
            PopupToast()
        }
    }
}

/**
 * The app's message, on the card's top edge: a line of it covers the handle and leaves the title, and it's clear of the buttons and a keyboard. Solid, as the card's words
 * would read through the glass. The windows keep room above a card for its rise. A tap puts it away, as the app's own.
 */
@Composable
private fun PopupToast() {
    val now = LocalToastNow.current ?: return
    val text = now.text()
    val last = remember { arrayOf<String?>(null) }
    if (text != null) last[0] = text
    val reduced = LocalMotion.current.reduced
    AnimatedVisibility(
        text != null,
        enter = if (reduced) EnterTransition.None else fadeIn(tween(Motion.DIALOG)),
        exit = if (reduced) ExitTransition.None else fadeOut(tween(Motion.DIALOG)),
    ) {
        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .offset(y = (-24).dp)
                .background(HN.bg900, RoundedCornerShape(18.dp))
                .popupGlass(null, radius = 18.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = now.dismiss)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            HText(last[0].orEmpty(), style = Type.small, color = HN.ink)
        }
    }
}

/** The card's arrival: a fade and a short rise with a hint of scale; nothing under Reduce Motion. */
@Composable
private fun PopupEnter(shown: Boolean, content: @Composable () -> Unit) {
    val reduced = LocalMotion.current.reduced
    AnimatedVisibility(
        shown,
        enter = if (reduced) EnterTransition.None else fadeIn(tween(Motion.DIALOG)) +
            slideInVertically(tween(Motion.REVEAL, easing = Motion.Expo)) { it / 4 } +
            scaleIn(tween(Motion.REVEAL, easing = Motion.Expo), initialScale = 0.96f),
    ) { content() }
}

/**
 * The pop-up's glass: the state chips' and pills' frosted body at a card's size (denser, so the
 * blurred app behind only tints it, and an unblurred one can't read through), a light along the top edge ([tint]'s, faintly, when given:
 * the card's one colour), a hairline ring, and radius 28.
 */
internal fun Modifier.popupGlass(tint: Color?, radius: Dp = 28.dp): Modifier = this
    .clip(RoundedCornerShape(radius))
    .drawWithCache {
        val r = radius.toPx()
        val corner = CornerRadius(r)
        val body = Brush.verticalGradient(listOf(lerp(HN.bg800, HN.ink, 0.06f).copy(alpha = 0.93f), HN.bg900.copy(alpha = 0.95f)))
        val wash = tint?.let { Brush.verticalGradient(0f to it.copy(alpha = 0.07f), 0.45f to it.copy(alpha = 0f)) }
        val sw = 1.dp.toPx()
        val edgeC = tint ?: HN.ink
        val edge = Brush.verticalGradient(0f to edgeC.copy(alpha = if (tint != null) 0.50f else 0.24f), 0.35f to edgeC.copy(alpha = 0f), 1f to HN.ink.copy(alpha = 0.06f))
        val ring = Stroke(sw)
        onDrawBehind {
            drawRoundRect(body, cornerRadius = corner)
            if (wash != null) drawRoundRect(wash, cornerRadius = corner)
            drawRoundRect(HN.ink.copy(alpha = 0.07f), topLeft = Offset(sw / 2f, sw / 2f), size = Size(size.width - sw, size.height - sw), cornerRadius = CornerRadius(r - sw / 2f), style = ring)
            drawRoundRect(edge, topLeft = Offset(sw / 2f, sw / 2f), size = Size(size.width - sw, size.height - sw), cornerRadius = CornerRadius(r - sw / 2f), style = ring)
        }
    }
