package com.prudnikweb.hypernova.ui.home

import com.prudnikweb.hypernova.ui.platform.rememberHaptics

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.theme.HN

/* A picker's rows, on both systems. */

/** A row's end when it's the one chosen: a mint check; else the same room, empty. */
@Composable
internal fun ChosenMark(selected: Boolean) {
    Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterEnd) {
        if (selected) Icon(Glyph.Check, size = 16.dp, color = HN.accent, stroke = 1.8.dp)
    }
}

/** A list row a picker chooses with: 56dp at least, the chosen one on a faint mint wash. */
@Composable
internal fun Modifier.pickRow(selected: Boolean, said: String, onClick: () -> Unit, min: Dp = 56.dp): Modifier {
    val haptics = rememberHaptics()
    return this
        .fillMaxWidth()
        .heightIn(min = min)
        .clip(RoundedCornerShape(12.dp))
        .then(if (selected) Modifier.glassChoice(true, 12.dp) else Modifier)
        .selectable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) {
            haptics.tick()
            onClick()
        }
        .semantics { contentDescription = said }
        .padding(horizontal = 10.dp, vertical = 8.dp)
}

