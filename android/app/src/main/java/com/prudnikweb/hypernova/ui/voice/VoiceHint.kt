package com.prudnikweb.hypernova.ui.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.VoiceProblem
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type

/** A one-line note above the composer (spec 3.3): why a dictation stopped, a first-time tip, the microphone. */
@Immutable
sealed interface VoiceHint {
    /** How long it stays (a tap dismisses it sooner). */
    val ms: Long

    data class Problem(val problem: VoiceProblem) : VoiceHint { override val ms = 4_000L }
    /** Nothing was said: quiet, and short. */
    data object Nothing : VoiceHint { override val ms = 2_500L }
    data object First : VoiceHint { override val ms = 6_000L }
    data object More : VoiceHint { override val ms = 6_000L }
    /** [forGood]: Android won't ask again, so the way is the app's settings. */
    data class Permission(val forGood: Boolean) : VoiceHint { override val ms = 8_000L }
}

@Composable
fun voiceProblemText(p: VoiceProblem): String = stringResource(
    when (p) {
        VoiceProblem.Offline -> R.string.voice_err_offline
        VoiceProblem.NoKey -> R.string.voice_err_no_key
        VoiceProblem.BadKey -> R.string.voice_err_bad_key
        VoiceProblem.NoCredit -> R.string.voice_err_no_credit
        VoiceProblem.Busy -> R.string.voice_err_busy
        VoiceProblem.Unreachable -> R.string.voice_err_unreachable
        VoiceProblem.Soniox -> R.string.voice_err_soniox
        VoiceProblem.TooLong -> R.string.voice_err_too_long
        VoiceProblem.MicBusy -> R.string.voice_err_mic_busy
        VoiceProblem.Mic -> R.string.voice_err_mic
        VoiceProblem.Companion -> R.string.voice_err_companion
        VoiceProblem.CompanionBusy -> R.string.voice_err_companion_busy
    },
)

/**
 * The note on screen, if any: one line of dense glass with a point of light (amber for a problem,
 * dim when nothing was heard, mint for a tip), and for the microphone an Allow or Settings pill.
 * It eases in and out without moving the composer under it (the column grows upward).
 */
@Composable
fun VoiceHintHost(hint: VoiceHint?, onDismiss: () -> Unit, onAllow: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        hint != null,
        modifier,
        enter = fadeIn(tween(Motion.DIALOG)) + expandVertically(tween(Motion.DIALOG, easing = Motion.Expo), expandFrom = Alignment.Bottom),
        exit = fadeOut(tween(Motion.DIALOG)) + shrinkVertically(tween(Motion.DIALOG, easing = Motion.Expo), shrinkTowards = Alignment.Bottom),
    ) {
        // Keeps the last note on screen while it folds away.
        val last = remember { arrayOfNulls<VoiceHint>(1) }
        if (hint != null) last[0] = hint
        val h = last[0] ?: return@AnimatedVisibility
        val (text, color) = when (h) {
            is VoiceHint.Problem -> voiceProblemText(h.problem) to HN.amber
            VoiceHint.Nothing -> stringResource(R.string.voice_nothing) to HN.inkDim
            VoiceHint.First -> stringResource(R.string.voice_hint_first) to HN.accent
            VoiceHint.More -> stringResource(R.string.voice_hint_more) to HN.accent
            is VoiceHint.Permission -> stringResource(if (h.forGood) R.string.voice_perm_off else R.string.voice_perm) to HN.amber
        }
        Box(Modifier.fillMaxWidth().padding(bottom = 10.dp), contentAlignment = Alignment.Center) {
            Row(
                Modifier
                    .wrapContentWidth()
                    .denseGlass(color)
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = stringResource(R.string.voice_a11y_dismiss), role = Role.Button, onClick = onDismiss)
                    .heightIn(min = 44.dp)
                    .padding(start = 16.dp, end = if (h is VoiceHint.Permission) 6.dp else 18.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).drawWithCache { onDrawBehind { drawCircle(color) } })
                Spacer(Modifier.width(10.dp))
                HText(text, style = Type.small, color = HN.ink, modifier = Modifier.weight(1f, fill = false).padding(vertical = 6.dp))
                if (h is VoiceHint.Permission) {
                    Spacer(Modifier.width(10.dp))
                    Pill(
                        stringResource(if (h.forGood) R.string.voice_perm_settings else R.string.voice_perm_allow),
                        if (h.forGood) onSettings else onAllow,
                        kind = PillKind.Primary, height = 34.dp,
                    )
                }
            }
        }
    }
}

/**
 * Dense glass for a note over the transcript: near-solid so the text keeps its contrast whatever
 * scrolls behind, its top edge catching the light, and a soft light of [glow] round its dot.
 */
private fun Modifier.denseGlass(glow: Color): Modifier = drawWithCache {
    val r = 22.dp.toPx().coerceAtMost(size.height / 2f)
    val corner = CornerRadius(r)
    val body = Brush.verticalGradient(listOf(lerp(HN.bg800, HN.ink, 0.06f).copy(alpha = 0.94f), HN.bg900.copy(alpha = 0.92f)))
    val light = Brush.radialGradient(listOf(glow.copy(alpha = 0.16f), glow.copy(alpha = 0.04f), Color.Transparent), center = Offset(19.dp.toPx(), size.height / 2f), radius = 46.dp.toPx())
    val sw = 1.dp.toPx()
    val edge = Brush.verticalGradient(0f to HN.ink.copy(alpha = 0.22f), 0.5f to HN.ink.copy(alpha = 0.05f), 1f to HN.ink.copy(alpha = 0.08f))
    onDrawBehind {
        drawRoundRect(body, cornerRadius = corner)
        drawRoundRect(light, cornerRadius = corner)
        drawRoundRect(edge, topLeft = Offset(sw / 2f, sw / 2f), size = Size(size.width - sw, size.height - sw), cornerRadius = CornerRadius(r - sw / 2f), style = Stroke(sw))
    }
}
