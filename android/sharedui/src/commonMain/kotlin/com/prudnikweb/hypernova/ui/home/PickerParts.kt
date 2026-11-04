package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.data.Str
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

// ─── Shared parts of the pickers ───

/**
 * A picker's search box: the house field's well, a lens before the words, a cross to clear them.
 * [onClose]: the cross always shows and closes the search instead (Home's, [closeLabel] for TalkBack).
 */
@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null, closeLabel: String? = null) {
    val shape = RoundedCornerShape(14.dp)
    val clear = closeLabel ?: stringResource(Str.close)
    BasicTextField(
        value, onChange,
        singleLine = true,
        textStyle = Type.body.copy(color = HN.ink),
        cursorBrush = SolidColor(HN.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Search),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(HN.bg800.copy(alpha = 0.6f))
                    .border(1.dp, HN.ink.copy(alpha = 0.10f), shape)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Glyph.Search, size = 16.dp, color = HN.inkDim, stroke = 1.4.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f).padding(vertical = 11.dp)) {
                    if (value.isEmpty()) HText(placeholder, style = Type.body, color = HN.inkGhost, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty() || onClose != null) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = clear) { if (onClose != null) onClose() else onChange("") }
                            .semantics { contentDescription = clear },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Glyph.Cross, size = 12.dp, color = HN.inkMuted) }
                } else Spacer(Modifier.width(8.dp))
            }
        },
    )
}

/** A part of a picker's list: its name (tracked caps, or a folder in mono), a hairline, a count. */
@Composable
internal fun PartHeader(label: String, mono: Boolean = false, trail: String? = null, trailColor: Color = HN.inkDim, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp).semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
        val most = Modifier.widthIn(max = 240.dp)
        if (mono) HText(label, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = most)
        else HText(label.uppercase(), style = Type.label, color = HN.inkMuted, maxLines = 1, modifier = most)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(HN.line))
        if (trail != null) {
            Spacer(Modifier.width(10.dp))
            HText(trail, style = Type.label.copy(letterSpacing = Type.label.letterSpacing * 0.5f), color = trailColor, maxLines = 1)
        }
    }
}

/** The nav bar's height, below a picker's list. */
@Composable
internal fun listBottom(): androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(bottom = 14.dp)
