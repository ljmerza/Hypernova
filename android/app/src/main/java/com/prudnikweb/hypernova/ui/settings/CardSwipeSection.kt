package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.NavRow
import com.prudnikweb.hypernova.ui.components.Radio
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.home.CardSwipePref
import com.prudnikweb.hypernova.ui.home.SwipeChoice
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Settings → Card swipes (owner, 2026-10-31): what a swipe left and a swipe right on a Home card do
 * (ui/home/CardSwipe.kt), a row each with the pick and what it does; a tap opens the choices in a
 * sheet. Kept on this phone and applied to Home at once. By default left is Smart and right Nothing.
 */
@Composable
internal fun CardSwipeSection() {
    val context = LocalContext.current
    val swipes by remember { CardSwipePref.state(context) }
    // The way whose choices are open: true left, false right.
    var picking by rememberSaveable { mutableStateOf<Boolean?>(null) }
    Seam(stringResource(R.string.st_card_swipes), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            HText(stringResource(R.string.st_card_swipes_desc), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(6.dp))
            NavRow(stringResource(R.string.st_swipe_left), stringResource(choiceBody(swipes.left)), { picking = true }, trailing = stringResource(choiceName(swipes.left)))
            Hairline(Modifier.fillMaxWidth())
            NavRow(stringResource(R.string.st_swipe_right), stringResource(choiceBody(swipes.right)), { picking = false }, trailing = stringResource(choiceName(swipes.right)))
        }
    }
    picking?.let { left ->
        val title = stringResource(if (left) R.string.st_swipe_left else R.string.st_swipe_right)
        val now = if (left) swipes.left else swipes.right
        GlassSheet(title, { picking = null }) {
            HText(title, style = Type.heading, color = HN.ink)
            Spacer(Modifier.height(4.dp))
            HText(stringResource(R.string.st_card_swipes_desc), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(8.dp))
            SwipeChoice.entries.forEachIndexed { i, c ->
                if (i > 0) Hairline(Modifier.fillMaxWidth())
                ChoiceLine(c, c == now) {
                    CardSwipePref.set(context, if (left) swipes.copy(left = c) else swipes.copy(right = c))
                    picking = null
                }
            }
        }
    }
}

/** One choice: a radio, its name and what it does (the whole row picks it). */
@Composable
private fun ChoiceLine(c: SwipeChoice, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected, interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Radio(selected)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            HText(stringResource(choiceName(c)), style = Type.body, color = if (selected) HN.ink else HN.inkMuted)
            Spacer(Modifier.height(2.dp))
            HText(stringResource(choiceBody(c)), style = Type.small, color = HN.inkDim)
        }
    }
}

private fun choiceName(c: SwipeChoice): Int = when (c) {
    SwipeChoice.Smart -> R.string.st_swipe_smart
    SwipeChoice.Open -> R.string.st_swipe_open
    SwipeChoice.Stop -> R.string.st_swipe_stop
    SwipeChoice.Delete -> R.string.st_swipe_delete
    SwipeChoice.Rename -> R.string.st_swipe_rename
    SwipeChoice.Restart -> R.string.st_swipe_restart
    SwipeChoice.MarkRead -> R.string.st_swipe_mark_read
    SwipeChoice.Nothing -> R.string.st_swipe_nothing
}

private fun choiceBody(c: SwipeChoice): Int = when (c) {
    SwipeChoice.Smart -> R.string.st_swipe_smart_desc
    SwipeChoice.Open -> R.string.st_swipe_open_desc
    SwipeChoice.Stop -> R.string.st_swipe_stop_desc
    SwipeChoice.Delete -> R.string.st_swipe_delete_desc
    SwipeChoice.Rename -> R.string.st_swipe_rename_desc
    SwipeChoice.Restart -> R.string.st_swipe_restart_desc
    SwipeChoice.MarkRead -> R.string.st_swipe_mark_read_desc
    SwipeChoice.Nothing -> R.string.st_swipe_nothing_desc
}
