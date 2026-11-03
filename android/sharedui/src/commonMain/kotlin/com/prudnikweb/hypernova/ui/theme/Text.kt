package com.prudnikweb.hypernova.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.prudnikweb.hypernova.ui.fx.Heat

/**
 * How much motion we can afford. The ambient clock, the grain and the edge bars read this
 * (frame rates and effects per level: ui/fx/Pace.kt):
 * - [reduced]: the system animator scale is 0 (accessibility "remove animations"),
 *   or the user turned Reduce Motion on. Static frames only.
 * - [powerSave]: battery saver is on. Lower frame rates, static grain.
 * - [heat]: how warm the phone is ([Heat]): effects step down as it warms and come back as it cools.
 */
@Immutable
data class MotionPrefs(val reduced: Boolean = false, val powerSave: Boolean = false, val heat: Int = Heat.COOL)

val LocalMotion = staticCompositionLocalOf { MotionPrefs() }

/** The one text primitive: BasicText with our defaults. */
@Composable
fun HText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body,
    color: Color = HN.ink,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
fun HText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = Type.body,
    color: Color = HN.ink,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow,
    )
}
