package com.prudnikweb.hypernova.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.LinkState
import com.prudnikweb.hypernova.data.ServerStatus
import com.prudnikweb.hypernova.data.Servers
import com.prudnikweb.hypernova.ui.components.ConnectionChip
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.StatusChip
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/** What Home's chip needs for the switcher: the paired computers (the active one first), and what a row does. */
class ServerChoice(
    val servers: List<ServerStatus>,
    val onSwitch: (String) -> Unit,
    val onPair: () -> Unit,
    /** "All servers" is chosen: Home shows every computer's sessions together. */
    val all: Boolean = false,
    /** The switcher's "All servers" row. */
    val onAll: () -> Unit = {},
) {
    /** The chip names the computer in every state once there are several. */
    val several: Boolean get() = servers.size > 1
    /** The chip's amber count: the other computers' sessions that need the owner. */
    val elsewhere: Int get() = Servers.elsewhere(servers)
}

/**
 * The Home chip's sheet (data/Servers.kt): each paired computer with its link (the connection
 * chip's word and moon) and how many of its sessions need the owner, the one on screen ticked; a tap
 * on another shows it. The last row pairs another
 * computer without forgetting these. It opens with one computer too, for that row.
 *
 * With two or more paired, "All servers" comes first ([all]: chosen, ticked in place of the one on
 * screen): Home shows every computer's sessions together (AllHome.kt), the other tabs the one picked last.
 */
@Composable
internal fun ServerSwitcher(
    servers: List<ServerStatus>,
    onSwitch: (String) -> Unit,
    onPair: () -> Unit,
    onDismiss: () -> Unit,
    all: Boolean = false,
    onAll: () -> Unit = {},
) {
    GlassSheet(stringResource(R.string.servers_title), onDismiss) {
        HText(stringResource(R.string.servers_title).uppercase(), style = Type.label, color = HN.inkDim)
        Spacer(Modifier.height(8.dp))
        if (servers.size > 1) {
            // Its link the worst of theirs, its count all of theirs.
            ServerRow(
                stringResource(R.string.servers_all), Servers.worst(servers.map { it.state }), servers.sumOf { it.needsYou }, ticked = all,
                label = stringResource(R.string.servers_all_label),
            ) {
                onAll()
                onDismiss()
            }
        }
        for (s in servers) {
            ServerRow(s.name, s.state, s.needsYou, ticked = s.active && !all) {
                onSwitch(s.id)
                onDismiss()
            }
        }
        Spacer(Modifier.height(6.dp))
        Hairline(Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        PickRow(onClick = { onDismiss(); onPair() }) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Icon(Glyph.Plus, size = 16.dp, color = HN.accentDim) }
            Spacer(Modifier.width(12.dp))
            HText(stringResource(R.string.servers_pair_another), style = Type.body, color = HN.ink, maxLines = 1, modifier = Modifier.weight(1f))
        }
    }
}

/** One computer: the tick (or room for it), its name, its needs-you count in amber, and its link as the chip says it. */
@Composable
private fun ServerRow(name: String, state: LinkState, needsYou: Int, ticked: Boolean, label: String? = null, onClick: () -> Unit) {
    PickRow(
        onClick = onClick,
        label = if (ticked) stringResource(R.string.servers_on_screen) else label ?: stringResource(R.string.servers_switch_label, name),
        selected = ticked,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (ticked) Icon(Glyph.Check, size = 16.dp, color = HN.accent, stroke = 2.dp)
        }
        Spacer(Modifier.width(12.dp))
        HText(name, style = Type.body, color = if (ticked) HN.ink else HN.inkMuted, maxLines = 1, modifier = Modifier.weight(1f))
        if (needsYou > 0) {
            Spacer(Modifier.width(8.dp))
            StatusChip(pluralStringResource(R.plurals.notif_needs_you, needsYou, needsYou), HN.amber, filled = true)
        }
        Spacer(Modifier.width(8.dp))
        // The state in a word, as Settings' chip: the name is already the row's.
        ConnectionChip(state)
    }
}

/**
 * New session while Home shows "All servers": which computer it starts on, before the New session
 * sheet opens for it (its folders, models and providers are that one's). Every paired computer with
 * its link as the chip says it; one that isn't linked can't be picked.
 */
@Composable
internal fun ServerPicker(picks: List<ServerPick>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    GlassSheet(stringResource(R.string.servers_new_on), onDismiss) {
        HText(stringResource(R.string.servers_new_on).uppercase(), style = Type.label, color = HN.inkDim)
        Spacer(Modifier.height(8.dp))
        for (p in picks) {
            PickRow(
                onClick = { onDismiss(); onPick(p.id) },
                label = stringResource(R.string.servers_new_on_label, p.name),
                enabled = p.enabled,
            ) {
                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    Icon(Glyph.Plus, size = 16.dp, color = if (p.enabled) HN.accentDim else HN.inkDim)
                }
                Spacer(Modifier.width(12.dp))
                HText(p.name, style = Type.body, color = if (p.enabled) HN.ink else HN.inkDim, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ConnectionChip(p.state)
            }
        }
    }
}

/** A 56dp row of the sheet, pressed as the app's sheet actions are; [enabled] false: dimmed, and a tap does nothing. */
@Composable
private fun PickRow(onClick: () -> Unit, label: String? = null, selected: Boolean = false, enabled: Boolean = true, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (pressed) HN.ink.copy(alpha = 0.06f) else if (selected) HN.accent.copy(alpha = 0.06f) else Color.Transparent)
            .semantics { this.selected = selected }
            .then(if (enabled) Modifier else Modifier.alpha(0.55f))
            .clickable(interaction, indication = null, enabled = enabled, role = Role.Button, onClickLabel = label) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
