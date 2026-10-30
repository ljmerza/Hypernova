package com.prudnikweb.hypernova.ui.components

import android.graphics.Color as AColor
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

// ─── Pop-ups: one floating glass card for every dialog and sheet ─────────────
//
// The owner (2026-10-18), on the Auto warning and the old sheets: a floating glass card above the
// content, with margins and rounded corners, frosted like the pills; a light, blurred scrim rather
// than a black one; a small eyebrow and a title, one short line, longer details behind "More";
// two glass pills side by side, the quieter on the left; colour only as a thin accent; and no
// black band under the status bar. [GlassDialog] is a choice, [GlassSheet] a list or a form; both
// are a dialog window (so Back, focus and TalkBack's scope come from the system) over the app,
// which is blurred behind them where Android can (12 and later), and dimmed a little more where it
// can't.

/** A pill in a [GlassDialog]: its words, what it does, its kind (Ghost, Outline, Primary, Danger), and whether it can be tapped yet. */
class DialogButton(val text: String, val onClick: () -> Unit, val kind: PillKind = PillKind.Outline, val enabled: Boolean = true)

/** How far the app behind a pop-up is blurred, at full strength. */
private val BLUR = 14.dp

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
    GlassWindow(onDismiss, pane = eyebrow ?: title, tint = if (warm) tint else null) { shown ->
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
                    MoreToggle(moreLabel ?: stringResource(R.string.more), open) { open = !open }
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
    GlassWindow(onDismiss, pane = title, tint = null) { shown ->
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
 * The dialog window every pop-up shares: full screen and edge to edge (no black band under the
 * status bar or over the navigation bar), clear, with our own scrim; the app behind it is blurred
 * while it's open. [content] gets whether the card should be on screen (it arrives a frame after
 * the window). [tint] warms the scrim (Bypass).
 */
@Composable
private fun GlassWindow(onDismiss: () -> Unit, pane: String, tint: Color?, content: @Composable (shown: Boolean) -> Unit) {
    // The app behind: blurred while this is open (the view this pop-up was opened from).
    val host = LocalView.current
    val reduced = LocalMotion.current.reduced
    val blurPx = with(LocalDensity.current) { BLUR.toPx() }
    val canBlur = Build.VERSION.SDK_INT >= 31
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.let(::clearWindow) }
        var shown by remember { mutableStateOf(reduced) }
        val fade = remember { Animatable(if (reduced) 1f else 0f) }
        LaunchedEffect(Unit) {
            shown = true
            fade.animateTo(1f, tween(Motion.DIALOG))
        }
        if (canBlur) {
            LaunchedEffect(host) { snapshotFlow { fade.value }.collect { host.setRenderEffect(blurEffect(blurPx * it)) } }
            DisposableEffect(host) {
                host.setTag(R.id.glass_blur_count, ((host.getTag(R.id.glass_blur_count) as? Int) ?: 0) + 1)
                onDispose {
                    val left = ((host.getTag(R.id.glass_blur_count) as? Int) ?: 1) - 1
                    host.setTag(R.id.glass_blur_count, left)
                    if (left <= 0) host.setRenderEffect(null)
                }
            }
        }
        val scrim = HN.bg.copy(alpha = if (canBlur) 0.34f else 0.66f)
        val title = pane
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val warm = tint?.let { Brush.verticalGradient(listOf(Color.Transparent, it.copy(alpha = 0.10f))) }
                    onDrawBehind {
                        val a = fade.value
                        drawRect(scrim, alpha = a)
                        if (warm != null) drawRect(warm, alpha = a)
                    }
                }
                .pointerInput(Unit) { detectTapGestures { onDismiss() } }
                .semantics { paneTitle = title }
                .imePadding()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(top = 24.dp, bottom = 10.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            content(shown)
        }
    }
}

/**
 * A pop-up's window: as big as the screen and drawn under the status and navigation bars, with no
 * colour of its own there (the dialog theme's black was the band under the status bar), no dim
 * (ours is drawn) and the bars' icons light, as over the app.
 */
@Suppress("DEPRECATION")
private fun clearWindow(w: android.view.Window) {
    w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    w.setBackgroundDrawable(ColorDrawable(AColor.TRANSPARENT))
    w.setDimAmount(0f)
    w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    w.statusBarColor = AColor.TRANSPARENT
    w.navigationBarColor = AColor.TRANSPARENT
    w.isStatusBarContrastEnforced = false
    w.isNavigationBarContrastEnforced = false
    val lp = w.attributes
    val cutout = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    if (lp.layoutInDisplayCutoutMode != cutout) {
        lp.layoutInDisplayCutoutMode = cutout
        w.attributes = lp
    }
    WindowCompat.setDecorFitsSystemWindows(w, false)
    WindowCompat.getInsetsController(w, w.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}

/** A blur of [px] for the app behind a pop-up (Android 12 and later), or none at 0. */
private fun blurEffect(px: Float): RenderEffect? =
    if (Build.VERSION.SDK_INT < 31 || px < 0.5f) null else RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP)

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
