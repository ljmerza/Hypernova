package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.Toggle
import com.prudnikweb.hypernova.ui.session.TopBarChoice
import com.prudnikweb.hypernova.ui.session.TopBarItem
import com.prudnikweb.hypernova.ui.session.TopBarPref
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Settings → Session top bar (owner, 2026-10-31): which parts the chat's top bar shows
 * (ui/session/TopBar.kt), each with a switch, in the order of the list; up and down arrows move a
 * part (arrows rather than dragging: the list sits in Settings' own scrolling list, and arrows are
 * TalkBack's too). Kept on this phone and applied at once. **Reset to default** while it differs.
 */
@Composable
internal fun TopBarSection() {
    val context = LocalContext.current
    val choice by remember { TopBarPref.state(context) }
    fun set(c: TopBarChoice) = TopBarPref.set(context, c)
    Seam(stringResource(R.string.st_top_bar), modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 6.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            HText(stringResource(R.string.st_top_bar_desc), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(6.dp))
            val order = choice.order
            order.forEachIndexed { i, item ->
                if (i > 0) Hairline(Modifier.fillMaxWidth())
                TopBarRow(
                    item, item in choice.shown, first = i == 0, last = i == order.lastIndex,
                    onToggle = { set(choice.set(item, it)) },
                    onMove = { set(choice.move(item, it)) },
                )
            }
            if (!choice.isDefault) {
                Spacer(Modifier.height(10.dp))
                Pill(stringResource(R.string.st_top_bar_reset), { set(TopBarChoice.DEFAULT) }, kind = PillKind.Outline, height = 44.dp)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** One part: its name and what it is, up and down arrows, and its switch (the name and switch toggle it). */
@Composable
private fun TopBarRow(item: TopBarItem, on: Boolean, first: Boolean, last: Boolean, onToggle: (Boolean) -> Unit, onMove: (Int) -> Unit) {
    val name = stringResource(itemName(item))
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { onToggle(!on) }
                .semantics { toggleableState = ToggleableState(on) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                HText(name, style = Type.body, color = if (on) HN.ink else HN.inkMuted)
                Spacer(Modifier.height(2.dp))
                HText(stringResource(itemBody(item)), style = Type.small, color = HN.inkDim)
            }
            Spacer(Modifier.width(12.dp))
            Toggle(on)
        }
        Spacer(Modifier.width(6.dp))
        MoveArrow(up = true, enabled = !first, name = name) { onMove(-1) }
        MoveArrow(up = false, enabled = !last, name = name) { onMove(1) }
    }
}

@Composable
private fun MoveArrow(up: Boolean, enabled: Boolean, name: String, onClick: () -> Unit) {
    val said = stringResource(if (up) R.string.st_top_bar_up else R.string.st_top_bar_down, name)
    val off = stringResource(R.string.st_top_bar_end)
    Box(
        Modifier
            .size(40.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = said
                if (!enabled) stateDescription = off
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Glyph.Up, Modifier.rotate(if (up) 0f else 180f), size = 14.dp, color = if (enabled) HN.inkMuted else HN.lineStrong)
    }
}

private fun itemName(item: TopBarItem): Int = when (item) {
    TopBarItem.CONTEXT -> R.string.st_top_bar_context
    TopBarItem.MODE -> R.string.st_top_bar_mode
    TopBarItem.FIVE_HOUR -> R.string.st_top_bar_five
    TopBarItem.SEVEN_DAY -> R.string.st_top_bar_seven
    TopBarItem.MODEL -> R.string.st_top_bar_model
    TopBarItem.TOKENS -> R.string.st_top_bar_tokens
    TopBarItem.AGENTS -> R.string.st_top_bar_agents
    TopBarItem.TURN -> R.string.st_top_bar_turn
}

private fun itemBody(item: TopBarItem): Int = when (item) {
    TopBarItem.CONTEXT -> R.string.st_top_bar_context_desc
    TopBarItem.MODE -> R.string.st_top_bar_mode_desc
    TopBarItem.FIVE_HOUR -> R.string.st_top_bar_five_desc
    TopBarItem.SEVEN_DAY -> R.string.st_top_bar_seven_desc
    TopBarItem.MODEL -> R.string.st_top_bar_model_desc
    TopBarItem.TOKENS -> R.string.st_top_bar_tokens_desc
    TopBarItem.AGENTS -> R.string.st_top_bar_agents_desc
    TopBarItem.TURN -> R.string.st_top_bar_turn_desc
}
