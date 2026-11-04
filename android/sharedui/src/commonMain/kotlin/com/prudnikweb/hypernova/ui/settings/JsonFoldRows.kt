package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.FoldRow
import com.prudnikweb.hypernova.data.JsonRows
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.platform.pluralStringResource
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/** A row of JSON being edited: its number, a fold control big enough for a thumb where it has one, its text, and what's folded in words. The JSON tab's and the form's. */
@Composable
fun JsonRow(row: FoldRow, onEdit: (String) -> Unit, onFold: (String) -> Unit) {
    val region = row.region
    val isFolded = row.hidden != null
    val mono = Type.monoSmall.copy(color = HN.ink)
    val foldWords = if (region == null) "" else stringResource(if (isFolded) Str.raw_unfold else Str.raw_fold, region.path)
    Row(Modifier.fillMaxWidth(), verticalAlignment = if (region != null) Alignment.CenterVertically else Alignment.Top) {
        Box(
            Modifier
                .size(44.dp, if (region != null) 44.dp else 4.dp)
                .then(
                    if (region != null) Modifier
                        .semantics { contentDescription = foldWords }
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onFold(region.path) }
                    else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // A chevron pointing on when folded, up when it can close.
            if (region != null) Icon(if (isFolded) Glyph.Chevron else Glyph.Up, size = 12.dp, color = HN.inkDim)
        }
        HText((row.firstLine until row.firstLine + row.lines).joinToString("\n"), style = Type.monoSmall, color = HN.inkGhost, modifier = Modifier.width(30.dp))
        Spacer(Modifier.width(6.dp))
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = row.text,
                onValueChange = onEdit,
                textStyle = mono,
                cursorBrush = SolidColor(HN.accent),
                modifier = Modifier.semantics { contentDescription = "line ${row.firstLine}" },
            )
            if (region != null && isFolded) {
                val words = if (region.isObject) pluralStringResource(Plural.raw_keys, region.count, region.count) else pluralStringResource(Plural.raw_items, region.count, region.count)
                HText(" … $words ${JsonRows.closing(row)}", style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
            }
        }
    }
}
