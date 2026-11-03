// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

// A session's metadata drawn, not "·"-joined (owner, 2026-10-18): the model as a chip, the
// permission mode as its glyph and words, the session's short id and age as quiet marks, and in
// the chat's strip its cost and tokens as figures with their glyphs.

/**
 * The model as a small chip: neutral frosted glass (the state chips' family, without their point
 * of light, so it never reads as a state), its name in tracked caps, muted. [name] as
 * [com.prudnikweb.hypernova.data.ModelNames] shortens it. The chip carries no words of its own for
 * TalkBack: whoever places it says the whole of it in one sentence.
 */
@Composable
fun ModelChip(name: String, modifier: Modifier = Modifier, compact: Boolean = false) = TagChip(name, modifier, compact)

/**
 * A name as a small chip of neutral frosted glass, tracked caps, muted: a model ([ModelChip]), an
 * agent's kind. Never a state (no point of light). [compact] sits inside a line of small type (a
 * meter's label row) without making it taller than it was by much: 18dp instead of 22.
 */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    Box(
        modifier
            .frostedGlass(null, glowX = 0.dp)
            // Grows with the font size instead of clipping the label.
            .heightIn(min = if (compact) 18.dp else 22.dp)
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 1.dp else 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        HText(text.uppercase(), style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.6f), color = HN.inkMuted, maxLines = 1)
    }
}

/**
 * A quiet mark for a seam's trail: a 10dp [glyph] and its figure in mono 10sp ("◷ 1m", "⏱ 2m 14s",
 * "⬡ 8.4k"), as the session stamp draws its age. [glyph] null: the figure alone.
 */
@Composable
fun Stamp(glyph: Glyph?, text: String, modifier: Modifier = Modifier, color: Color = HN.inkDim) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (glyph != null) {
            Icon(glyph, size = 10.dp, color = color, stroke = 1.1.dp)
            Spacer(Modifier.width(3.dp))
        }
        HText(text, style = StampText, color = color, maxLines = 1)
    }
}

/** Words in a seam's trail ("stopped", "2 of 5 running"), in the trail's own style. */
@Composable
fun SeamWords(text: String, modifier: Modifier = Modifier, color: Color = HN.inkDim) {
    HText(text, style = TrailText, color = color, maxLines = 1, modifier = modifier)
}

/**
 * A count with its state's point of light, still, as the state chips carry theirs ("● 2 need you"
 * in amber, "● 5 live" in mint): several side by side read as a legend, never a "·"-joined list.
 */
@Composable
fun CountMark(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(8.dp)) {
            val u = 1.dp.toPx()
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(color.copy(alpha = 0.4f), color.copy(alpha = 0f)), center, 4f * u), 4f * u, center)
            drawCircle(color, 1.9f * u, center)
        }
        Spacer(Modifier.width(4.dp))
        HText(text, style = TrailText, color = color, maxLines = 1)
    }
}

/**
 * A hollow diamond struck through, [size] wide: Claude can't reply for a while (a limit, the
 * account). The chat's blocked line and a card's leads with it.
 */
@Composable
fun EclipseMark(color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f
        struckDiamond(center, r * 0.78f, color.copy(alpha = 0.9f), 1.dp.toPx())
    }
}

/**
 * The permission mode: its glyph ([modeGlyph]: a shield for asking first, a pencil for accepting
 * edits…) and, when there's room, its words ([label], "ask first"), quiet.
 */
@Composable
fun ModeTag(mode: String, label: String?, modifier: Modifier = Modifier, color: Color = HN.inkDim) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(modeGlyph(mode), size = 12.dp, color = color, stroke = 1.2.dp)
        if (label != null) {
            Spacer(Modifier.width(4.dp))
            HText(label, style = MetaText, color = color, maxLines = 1)
        }
    }
}

/**
 * A figure with its glyph, quiet: the session's cost after a banknote, its tokens after a token.
 * [color] is the figure's; the glyph is a step dimmer.
 */
@Composable
fun GlyphFigure(glyph: Glyph, text: String, modifier: Modifier = Modifier, color: Color = HN.inkMuted, size: Dp = 12.dp) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(glyph, size = size, color = HN.inkDim, stroke = 1.1.dp)
        Spacer(Modifier.width(4.dp))
        HText(text, style = Type.monoSmall, color = color, maxLines = 1)
    }
}

/**
 * How long since a session was last active, as a quiet mark: a small clock and the age ("◷ 1m").
 * TalkBack hears [said] ([sessionSaid]).
 */
@Composable
fun SessionStamp(age: String, said: String, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { contentDescription = said }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyph.Clock, size = 10.dp, color = HN.inkDim, stroke = 1.1.dp)
        Spacer(Modifier.width(3.dp))
        HText(age, style = StampText, color = HN.inkDim, maxLines = 1)
    }
}

/** The mode's words beside its glyph: small, in Manrope (they're words, not a value). */
private val MetaText = Type.small.copy(fontSize = 12.sp, lineHeight = 16.sp)

/** The age: mono, a little under the strip's figures. */
private val StampText = Type.monoSmall.copy(fontSize = 10.sp, lineHeight = 14.sp)

/** A seam's trail in words: the label's face, half its tracking, as [Seam] draws a trail. */
private val TrailText = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.5f)
