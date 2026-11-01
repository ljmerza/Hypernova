package com.prudnikweb.hypernova.ui.session

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * The chat's sheets (a message's actions, a quick prompt's editor, the usage breakdown) and the
 * pickers': the app's one floating glass sheet ([com.prudnikweb.hypernova.ui.components.GlassSheet]),
 * rising from the bottom above the keyboard. A tap on the scrim or Back closes it.
 */
@Composable
internal fun ChatSheet(
    title: String,
    onDismiss: () -> Unit,
    /** A height of its own (a picker whose list doesn't jump as it's searched); it shrinks to fit. */
    height: Dp? = null,
    /** False: [content] scrolls by itself (a lazy list). */
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) = com.prudnikweb.hypernova.ui.components.GlassSheet(title, onDismiss, height, scroll, content)

/**
 * One action in a sheet: a hairline glyph and a label, 52dp tall, with a quiet [hint] on the
 * right. [danger] draws it in red (it can't be undone from here).
 */
@Composable
internal fun SheetAction(glyph: ChatGlyph, label: String, onClick: () -> Unit, hint: String? = null, color: Color = HN.ink) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (pressed) HN.ink.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(interaction, indication = null, role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatIcon(glyph, color = if (color == HN.ink) HN.accentDim else color)
        Spacer(Modifier.width(14.dp))
        HText(label, style = Type.body, color = color, maxLines = 1, modifier = Modifier.weight(1f))
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            HText(hint, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        }
    }
}

/** The chat's own hairline glyphs, drawn in the same stroke as the app's (components/Icons.kt). */
enum class ChatGlyph { Copy, Quote, Speaker, Stop, Rewind, Bookmark, Select, Edit, Remove, NewSession, Restart, Servers }

@Composable
internal fun ChatIcon(glyph: ChatGlyph, modifier: Modifier = Modifier, size: Dp = 18.dp, color: Color = HN.ink, stroke: Dp = 1.5.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val st = Stroke(width = stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * w, y * h)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), st.width, StrokeCap.Round)
        fun rect(x: Float, y: Float, rw: Float, rh: Float) = drawRoundRect(color, p(x, y), Size(rw * w, rh * h), androidx.compose.ui.geometry.CornerRadius(.08f * w), style = st)
        when (glyph) {
            ChatGlyph.Copy -> {
                rect(.32f, .30f, .48f, .54f)
                drawPath(Path().apply { moveTo(.22f * w, .70f * h); lineTo(.18f * w, .70f * h); lineTo(.18f * w, .16f * h); lineTo(.64f * w, .16f * h); lineTo(.64f * w, .22f * h) }, color, style = st)
            }
            ChatGlyph.Quote -> {
                line(.22f, .22f, .22f, .78f)
                line(.40f, .34f, .82f, .34f)
                line(.40f, .50f, .82f, .50f)
                line(.40f, .66f, .66f, .66f)
            }
            ChatGlyph.Speaker -> {
                drawPath(Path().apply { moveTo(.14f * w, .40f * h); lineTo(.30f * w, .40f * h); lineTo(.50f * w, .22f * h); lineTo(.50f * w, .78f * h); lineTo(.30f * w, .60f * h); lineTo(.14f * w, .60f * h); close() }, color, style = st)
                drawArc(color, -45f, 90f, false, p(.48f, .36f), Size(.20f * w, .28f * h), style = st)
                drawArc(color, -50f, 100f, false, p(.48f, .22f), Size(.38f * w, .56f * h), style = st)
            }
            ChatGlyph.Stop -> rect(.28f, .28f, .44f, .44f)
            ChatGlyph.Rewind -> {
                // A turn back: three quarters of a circle, its arrowhead at the top left.
                drawArc(color, 200f, 280f, false, p(.18f, .18f), Size(.64f * w, .64f * h), style = st)
                drawPath(Path().apply { moveTo(.14f * w, .22f * h); lineTo(.21f * w, .40f * h); lineTo(.39f * w, .34f * h) }, color, style = st)
                line(.50f, .36f, .50f, .52f)
                line(.50f, .52f, .62f, .60f)
            }
            ChatGlyph.Bookmark -> drawPath(Path().apply { moveTo(.28f * w, .16f * h); lineTo(.72f * w, .16f * h); lineTo(.72f * w, .84f * h); lineTo(.50f * w, .66f * h); lineTo(.28f * w, .84f * h); close() }, color, style = st)
            ChatGlyph.Edit -> {
                // A pencil, slanted, its tip at the lower left.
                drawPath(Path().apply { moveTo(.20f * w, .80f * h); lineTo(.26f * w, .60f * h); lineTo(.66f * w, .20f * h); lineTo(.80f * w, .34f * h); lineTo(.40f * w, .74f * h); close() }, color, style = st)
                line(.58f, .28f, .72f, .42f)
            }
            ChatGlyph.Remove -> {
                // A bin: lid, handle, body.
                line(.20f, .30f, .80f, .30f)
                line(.40f, .30f, .42f, .20f)
                line(.60f, .30f, .58f, .20f)
                line(.42f, .20f, .58f, .20f)
                drawPath(Path().apply { moveTo(.28f * w, .30f * h); lineTo(.32f * w, .82f * h); lineTo(.68f * w, .82f * h); lineTo(.72f * w, .30f * h) }, color, style = st)
            }
            ChatGlyph.NewSession -> {
                line(.50f, .22f, .50f, .78f)
                line(.22f, .50f, .78f, .50f)
            }
            ChatGlyph.Select -> {
                line(.50f, .18f, .50f, .82f)
                line(.36f, .18f, .64f, .18f)
                line(.36f, .82f, .64f, .82f)
            }
            ChatGlyph.Restart -> {
                // Round again: most of a circle, clockwise, its arrowhead at the top left.
                drawArc(color, -60f, 300f, false, p(.18f, .18f), Size(.64f * w, .64f * h), style = st)
                drawPath(Path().apply { moveTo(.27f * w, .38f * h); lineTo(.34f * w, .22f * h); lineTo(.17f * w, .21f * h) }, color, style = st)
            }
            ChatGlyph.Servers -> {
                // Two boxes in a rack, each with its light.
                rect(.18f, .20f, .64f, .24f)
                rect(.18f, .56f, .64f, .24f)
                line(.32f, .32f, .34f, .32f)
                line(.32f, .68f, .34f, .68f)
            }
        }
    }
}
