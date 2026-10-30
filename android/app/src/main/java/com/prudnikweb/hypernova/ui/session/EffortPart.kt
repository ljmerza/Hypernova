package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.ClaudeEffort
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.TagChip
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.home.EffortSheet
import com.prudnikweb.hypernova.ui.home.claudeEffortTitle
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * The session sheet's Effort part, on Overview (a Claude Code session's; Codex's effort goes with
 * its model): the effort it runs at, as the Mac knows it (Claude Code's status line, else what it
 * was started with or last set to), and, while it can be changed from here, a way to another one:
 * the effort picker, and the Mac types `/effort` into its terminal ([Hub.setEffort]). Shown at once;
 * a failure puts the old one back and says why. Where it can't be changed, why, as for the mode.
 */
@Composable
internal fun EffortPart(hub: Hub, s: Session) {
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val res = LocalContext.current.resources
    val nextTurn = stringResource(R.string.effort_next_turn)
    val change = ClaudeEffort.changeable(s, canChangeMode(hub, s))
    // What was picked, until the Mac says otherwise (its answer, or the status line later).
    var shown by remember(s.id, s.effort) { mutableStateOf(s.effort) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val name = claudeEffortTitle(shown)
    val said = res.getString(R.string.ns_effort_said, name)
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .glassChoice(false, 12.dp)
            .then(
                if (change) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.ns_change)) { picking = true }
                else Modifier,
            )
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(stringResource(R.string.effort).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        TagChip(name)
        if (change) {
            Spacer(Modifier.width(10.dp))
            Icon(Glyph.Chevron, size = 11.dp, color = HN.inkDim)
        }
    }
    // Not said yet (Claude Code's default until its status line says), and why it can't change here.
    val lines = listOfNotNull(
        R.string.session_sheet_effort_unknown.takeIf { shown == null },
        when {
            change -> null
            s.phase == Phase.Ended -> R.string.session_sheet_mode_ended
            else -> R.string.session_sheet_mode_watched
        },
    )
    for (line in lines) {
        Spacer(Modifier.height(6.dp))
        HText(stringResource(line), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(start = 4.dp))
    }
    if (picking) EffortSheet(
        shown,
        onPick = { level ->
            picking = false
            if (level != null && level != shown) {
                val before = shown
                shown = level
                appScope.launch {
                    hub.setEffort(s.id, level)
                        .onSuccess { later -> if (later) toast(nextTurn) }
                        .onFailure { shown = before; toast(it.message.orEmpty()) }
                }
            }
        },
        onDismiss = { picking = false },
        // A running session has no "default" to go back to: the levels alone.
        withDefault = false,
    )
}
