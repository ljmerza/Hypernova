package com.prudnikweb.hypernova.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.ui.theme.HN

/**
 * Fork: a tap on the status line's mode tag steps to the next everyday mode, so switching between
 * them takes one tap instead of opening the picker. Accept edits and Bypass stay in the full picker
 * (a tap elsewhere on the status line); from either, the next tap goes back to the cycle's start.
 * Pure, unit-tested.
 */
object ModeCycle {
    /** Claude's three: ask first, then the safe Plan, then Auto (behind its think-twice card). */
    val CLAUDE = listOf("default", "plan", "auto")

    /** The mode after [mode] in its agent's cycle, or the cycle's first for a mode outside it. */
    fun next(mode: String, codex: Boolean): String {
        val cycle = if (codex) AgentKind.CODEX_MODES else CLAUDE
        val i = cycle.indexOf(mode)
        return cycle[if (i < 0) 0 else (i + 1) % cycle.size]
    }
}

/**
 * The mode tag, tappable when [onCycle] is given ([cycleLabel] is what TalkBack says the tap does).
 * The extra vertical padding only widens the tap: the status line it sits in is as tall as the
 * usage strip beside it anyway.
 */
@Composable
fun ModeCycleTag(mode: String, label: String?, onCycle: (() -> Unit)?, cycleLabel: String?, modifier: Modifier = Modifier) {
    if (onCycle == null) return ModeTag(mode, label, modifier)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    ModeTag(
        mode, label,
        modifier
            .clickable(interaction, indication = null, role = Role.Button, onClickLabel = cycleLabel, onClick = onCycle)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        color = if (pressed) HN.inkMuted else HN.inkDim,
    )
}

/**
 * The cycle's switch, with the picker's think-twice card in front of Auto (ModePicker's rule): call
 * the returned function with the next mode. Kept by the screen rather than inside the tag, since the
 * tag is one of FitRow's variants and FitRow wants exactly one layout node per variant.
 */
@Composable
fun rememberModeCycle(onSwitch: (String) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    var incidentId by rememberSaveable { mutableStateOf<String?>(null) }
    val m = asking
    val incident = Incidents.byId(context, incidentId)
    if (m != null && incident != null) {
        ThinkTwiceCard(m, incident, onKeep = { asking = null }, onGoOn = { asking = null; onSwitch(m) })
    }
    return { next ->
        if (needsThinkTwice(next)) {
            val pick = Incidents.next(context)
            // No list to show (the file is damaged): go on rather than block the choice, as the picker does.
            if (pick == null) onSwitch(next) else { incidentId = pick.id; asking = next }
        } else onSwitch(next)
    }
}
