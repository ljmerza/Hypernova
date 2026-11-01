package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.NotificationPrefs
import com.prudnikweb.hypernova.data.NotificationPrefsStore
import com.prudnikweb.hypernova.data.NotifyGroup
import com.prudnikweb.hypernova.data.ServerNotify
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Hairline
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Which notifications this phone posts (docs/DECISIONS.md, "Which notifications";
 * data/NotificationPrefs.kt): the defaults for all servers in Settings' Notifications section, a row
 * in each server's card. Anything that would leave a server silent about approvals asks first,
 * whichever switch does it ([NotificationPrefs.silencesAnswers]).
 */
@Composable
private fun rememberNotifyChange(): (NotificationPrefs, String) -> Unit {
    val context = LocalContext.current
    val confirm = LocalConfirm.current
    val res = context.resources
    return remember(confirm) {
        { next, title ->
            if (NotificationPrefsStore.get(context).silencesAnswers(next, Core.servers.map { it.id })) {
                confirm(Confirm(title, res.getString(R.string.nc_ask_body), res.getString(R.string.nc_ask_action), danger = true) { Core.setNotifications(next) })
            } else {
                Core.setNotifications(next)
            }
        }
    }
}

private fun groupTitle(g: NotifyGroup): Int = when (g) {
    NotifyGroup.Answers -> R.string.nc_answers
    NotifyGroup.Finished -> R.string.nc_finished
    NotifyGroup.Waiting -> R.string.nc_waiting
    NotifyGroup.Errors -> R.string.nc_errors
    NotifyGroup.Limits -> R.string.nc_limits
    NotifyGroup.Automations -> R.string.nc_automations
    NotifyGroup.Updates -> R.string.nc_updates
}

private fun groupBody(g: NotifyGroup): Int = when (g) {
    NotifyGroup.Answers -> R.string.nc_answers_desc
    NotifyGroup.Finished -> R.string.nc_finished_desc
    NotifyGroup.Waiting -> R.string.nc_waiting_desc
    NotifyGroup.Errors -> R.string.nc_errors_desc
    NotifyGroup.Limits -> R.string.nc_limits_desc
    NotifyGroup.Automations -> R.string.nc_automations_desc
    NotifyGroup.Updates -> R.string.nc_updates_desc
}

/** A switch per group, [off] the ones turned off: the defaults' and a server's Custom. */
@Composable
private fun GroupSwitches(off: Set<NotifyGroup>, onChange: (NotifyGroup, Boolean) -> Unit) {
    for (g in NotifyGroup.entries) {
        Hairline(Modifier.fillMaxWidth())
        ToggleRow(stringResource(groupTitle(g)), stringResource(groupBody(g)), g !in off, { on -> onChange(g, on) })
    }
}

/**
 * The defaults, at the top of the Notifications card: the master switch, then the groups folded
 * under one row that says how many are on, so the tab stays scannable. With the master off there's
 * nothing to choose, and the row is gone.
 */
@Composable
internal fun NotificationDefaults() {
    val context = LocalContext.current
    val res = context.resources
    val prefs by remember { NotificationPrefsStore.state(context) }
    val change = rememberNotifyChange()
    var open by rememberSaveable { mutableStateOf(false) }
    ToggleRow(
        stringResource(R.string.notifications),
        stringResource(if (prefs.master) R.string.nc_master_desc else R.string.nc_master_off_desc),
        prefs.master,
        { on -> change(prefs.copy(master = on), res.getString(R.string.nc_ask_master)) },
    )
    if (prefs.master) {
        Hairline(Modifier.fillMaxWidth())
        val total = NotifyGroup.entries.size
        val on = total - prefs.off.size
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HText(stringResource(R.string.nc_which), style = Type.body, color = HN.ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            HText(if (on == total) stringResource(R.string.nc_which_all) else stringResource(R.string.nc_which_some, on, total), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Icon(Glyph.Chevron, Modifier.rotate(if (open) 90f else 0f), size = 12.dp, color = HN.inkDim)
        }
        if (open) {
            GroupSwitches(prefs.off) { g, want ->
                change(prefs.withDefault(g, want), res.getString(R.string.nc_ask_answers))
            }
        }
    }
    Hairline(Modifier.fillMaxWidth())
}

/**
 * A server's own row, in its card: Same as all, Custom or Off, kept on the phone, so it's here too
 * while the server can't be reached. Custom shows the same switches for this server only.
 */
@Composable
internal fun ServerNotifyRow(server: String, name: String) {
    val context = LocalContext.current
    val res = context.resources
    val prefs by remember { NotificationPrefsStore.state(context) }
    val change = rememberNotifyChange()
    val mode = prefs.modeOf(server)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        HText(stringResource(R.string.notifications), style = Type.body, color = HN.ink)
        Spacer(Modifier.height(2.dp))
        HText(
            stringResource(
                when {
                    !prefs.master -> R.string.nc_server_master_off
                    mode == ServerNotify.Custom -> R.string.nc_server_custom_desc
                    mode == ServerNotify.Off -> R.string.nc_server_off_desc
                    else -> R.string.nc_server_same_desc
                },
            ),
            style = Type.small,
            color = HN.inkDim,
        )
        Spacer(Modifier.height(10.dp))
        Segmented(
            listOf(
                ServerNotify.Same to stringResource(R.string.nc_same),
                ServerNotify.Custom to stringResource(R.string.nc_custom),
                ServerNotify.Off to stringResource(R.string.nc_off),
            ),
            mode,
            { m ->
                val title = if (m == ServerNotify.Off) R.string.nc_ask_server else R.string.nc_ask_answers_server
                change(prefs.withMode(server, m), res.getString(title, name))
            },
        )
        if (mode == ServerNotify.Custom) {
            Spacer(Modifier.height(8.dp))
            GroupSwitches(prefs.customOf(server)) { g, want ->
                change(prefs.withCustom(server, g, want), res.getString(R.string.nc_ask_answers_server, name))
            }
        }
    }
}
