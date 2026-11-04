// Modified by Leonardo Merza, 2026. See NOTICE.
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

/** How far the app behind a pop-up is blurred, at full strength. */
private val BLUR = 14.dp

/**
 * The dialog window every pop-up shares: full screen and edge to edge (no black band under the
 * status bar or over the navigation bar), clear, with our own scrim; the app behind it is blurred
 * while it's open. [content] gets whether the card should be on screen (it arrives a frame after
 * the window). [tint] warms the scrim (Bypass).
 */
@Composable
internal fun GlassWindow(onDismiss: () -> Unit, pane: String, tint: Color?, content: @Composable (shown: Boolean) -> Unit) {
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
