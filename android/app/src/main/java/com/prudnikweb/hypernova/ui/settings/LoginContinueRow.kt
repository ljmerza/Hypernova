package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.LiveHub
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.ToggleRow
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * A server's own **Continue after signing in** (docs/PROTOCOL.md, "After signing in again"; owner,
 * 2026-11-03: "default on"): kept on that server's daemon. An older one says it needs updating; one
 * without Claude Code has nothing to carry on.
 */
@Composable
internal fun LoginContinueRow(live: LiveHub) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var asked by remember { mutableStateOf<Boolean?>(null) }
    val claude = live.claude
    if (claude?.available == false) return
    val name = live.mac.name.ifBlank { live.pairing.macName }
    val on = claude?.loginContinue
    if (on == null) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            HText(stringResource(R.string.lc_title), style = Type.body, color = HN.ink)
            Spacer(Modifier.height(2.dp))
            HText(stringResource(R.string.lc_too_old, name), style = Type.small, color = HN.amber)
        }
        return
    }
    ToggleRow(stringResource(R.string.lc_title), stringResource(R.string.lc_body, name), asked ?: on, { want ->
        if (asked != null) return@ToggleRow
        asked = want
        scope.launch {
            live.setLoginContinue(want).onFailure { toast(it.message?.takeIf { m -> m.isNotBlank() } ?: context.getString(R.string.ag_claude_agents_err)) }
            asked = null
        }
    }, Modifier.alpha(if (asked != null) 0.6f else 1f))
}

/**
 * The same switch for every server at once, on the Settings tab's list of servers. It only asks
 * [connected]: one that can't be reached keeps what it has, and so does one too old to have it.
 */
@Composable
internal fun LoginContinueAllRow(connected: List<LiveHub>) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var asked by remember { mutableStateOf<Boolean?>(null) }
    val having = connected.filter { it.claude?.available != false }
    val all = SettingsPlan.allSwitch(having.map { it.claude?.loginContinue })
    val line = when {
        all.can == 0 -> stringResource(R.string.lc_all_none)
        all.mixed -> stringResource(R.string.lc_all_some, all.on, all.can)
        all.checked -> stringResource(R.string.lc_all_on)
        else -> stringResource(R.string.lc_all_off)
    }
    val body = if (all.can > 0 && all.old > 0) line + " " + stringResource(R.string.lc_all_old) else line
    ToggleRow(stringResource(R.string.lc_all_title), body, asked ?: all.checked, { want ->
        if (asked != null || all.can == 0) return@ToggleRow
        asked = want
        scope.launch {
            for (hub in having) {
                if (hub.claude?.loginContinue == null) continue
                hub.setLoginContinue(want).onFailure { toast(it.message?.takeIf { m -> m.isNotBlank() } ?: context.getString(R.string.ag_claude_agents_err)) }
            }
            asked = null
        }
    }, Modifier.alpha(if (asked != null) 0.6f else 1f))
}
