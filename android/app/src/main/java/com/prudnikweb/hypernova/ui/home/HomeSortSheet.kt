package com.prudnikweb.hypernova.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/** A sort in a few words, as the sheet's rows and the sort button's TalkBack say it ("Name, A to Z"). */
@Composable
internal fun sortWord(sort: HomeSort): String = stringResource(
    when (sort) {
        HomeSort.Name -> R.string.sort_name
        HomeSort.Recent -> R.string.sort_recent
        HomeSort.Project -> R.string.sort_project
        HomeSort.Server -> R.string.sort_server
    },
)

/**
 * Home's sort sheet (owner, 2026-11-01; docs/DECISIONS.md, "Sorting Home's sessions"), from the sort
 * button beside the filter, in the switcher's glass and rows (ServerSwitcher.kt): one row per sort
 * [offered], the one in use ticked; a tap sorts and closes. A line under them says the groups stay.
 */
@Composable
internal fun HomeSortSheet(sort: HomeSort, offered: List<HomeSort>, onPick: (HomeSort) -> Unit, onDismiss: () -> Unit) {
    val title = stringResource(R.string.sort_title)
    GlassSheet(title, onDismiss) {
        HText(title.uppercase(), style = Type.label, color = HN.inkDim)
        Spacer(Modifier.height(8.dp))
        for (s in offered) {
            SortRow(sortWord(s), ticked = s == sort) {
                onPick(s)
                onDismiss()
            }
        }
        Spacer(Modifier.height(8.dp))
        HText(stringResource(R.string.sort_within_groups), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

/** A 56dp row: the tick (or room for it) and the sort's words, pressed as the switcher's rows are. */
@Composable
private fun SortRow(text: String, ticked: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (pressed) HN.ink.copy(alpha = 0.06f) else if (ticked) HN.accent.copy(alpha = 0.06f) else Color.Transparent)
            .selectable(ticked, interaction, indication = null, role = Role.RadioButton) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (ticked) Icon(Glyph.Check, size = 16.dp, color = HN.accent, stroke = 2.dp)
        }
        Spacer(Modifier.width(12.dp))
        HText(text, style = Type.body, color = if (ticked) HN.ink else HN.inkMuted, maxLines = 1, modifier = Modifier.weight(1f))
    }
}
