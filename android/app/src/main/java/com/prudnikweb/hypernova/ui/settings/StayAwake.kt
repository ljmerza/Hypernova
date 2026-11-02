package com.prudnikweb.hypernova.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AwakeWords
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.MacAwake
import com.prudnikweb.hypernova.data.MacBattery
import com.prudnikweb.hypernova.data.strings
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.BatteryMark
import com.prudnikweb.hypernova.ui.components.FieldLabel
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The clock for Stay awake's words: still while nothing counts down, every 15 s while a guard does
 * ("Sleeps in 2 minutes" is precise enough at that pace).
 */
@Composable
private fun awakeClock(a: MacAwake): Long {
    val now by produceState(System.currentTimeMillis(), a.sleepAt) {
        value = System.currentTimeMillis()
        while (a.sleepAt != null) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * The Mac card's line under its name: how Stay awake holds it in a phrase ("Awake with the lid
 * closed"; amber while the Mac is about to sleep or let go), then its battery drawn, a small battery
 * filled to its level and the percentage: no "Awake · lid closed · 62%". Shown only while linked:
 * an unreachable Mac may be asleep already.
 */
@Composable
fun AwakeStatus(a: MacAwake, battery: MacBattery?, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val now = awakeClock(a)
    val warn = a.sleepAt != null || a.letGo
    val words = AwakeWords.status(res.strings, a, now)
    val said = battery?.let { res.getString(R.string.mac_battery_said_short, words, it.percent) } ?: words
    Row(modifier.clearAndSetSemantics { contentDescription = said }, verticalAlignment = Alignment.CenterVertically) {
        HText(words, style = Type.small, color = if (warn) HN.amber else HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (battery != null) {
            val tone = when {
                battery.critical -> HN.danger
                battery.low -> HN.amber
                else -> HN.inkDim
            }
            Spacer(Modifier.width(12.dp))
            BatteryMark(battery.percent, tone)
            Spacer(Modifier.width(4.dp))
            HText(res.getString(R.string.awake_state_percent, battery.percent), style = Type.small, color = tone, maxLines = 1)
        }
    }
}

/**
 * Stay awake on the Mac card: While working, Backpack (on a MacBook) or Off, then one line: what's
 * about to happen (amber), Backpack's hours, or what the chosen mode does. Backpack before its
 * setup says where to do it (the Mac's password is needed, so only the Mac can).
 */
@Composable
fun StayAwakeBlock(hub: Hub, a: MacAwake, battery: MacBattery?) {
    val res = LocalContext.current.resources
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    val now = awakeClock(a)
    // Shown at once while the Mac takes it; the Mac's word replaces it.
    var choice by remember { mutableStateOf<String?>(null) }
    var askedSetup by remember { mutableStateOf(false) }
    // The hint goes the moment the Mac says Backpack is ready (set up there, maybe already running):
    // it must never sit under a Backpack that works. Forgotten too, so a later removal doesn't bring it back.
    LaunchedEffect(a.backpackReady) { if (a.backpackReady) askedSetup = false }
    val needsSetup = askedSetup && !a.backpackReady
    val shown = choice ?: a.mode
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(14.dp))
        FieldLabel(stringResource(R.string.awake_title))
        Spacer(Modifier.height(8.dp))
        val modes = if (a.laptop) listOf(MacAwake.WORKING, MacAwake.BACKPACK, MacAwake.OFF) else listOf(MacAwake.WORKING, MacAwake.OFF)
        Segmented(
            modes.map { it to AwakeWords.modeLabel(res.strings, it) },
            if (needsSetup) MacAwake.BACKPACK else shown,
            { m ->
                if (m == MacAwake.BACKPACK && !a.backpackReady) {
                    askedSetup = true
                } else {
                    askedSetup = false
                    // Backpack chosen again starts its 8 hours over.
                    if (m != shown || m == MacAwake.BACKPACK) {
                        choice = m
                        scope.launch {
                            hub.setAwake(m).onFailure { toast(it.message ?: res.getString(R.string.err_awake)) }
                            choice = null
                        }
                    }
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        val line = AwakeWords.line(res.strings, a, battery, now)
        val (text, color) = when {
            needsSetup -> res.getString(R.string.awake_needs_setup) to HN.amber
            choice == null && line != null -> line.first to if (line.second) HN.amber else HN.inkMuted
            else -> AwakeWords.modeHelp(res.strings, shown, com.prudnikweb.hypernova.ui.components.byAgents(hub.claude, hub.codex, com.prudnikweb.hypernova.data.Str.awake_help_backpack, com.prudnikweb.hypernova.data.Str.awake_help_backpack_codex, com.prudnikweb.hypernova.data.Str.awake_help_backpack_both)) to HN.inkDim
        }
        AnimatedContent(text to color, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "awakeLine") { (t, c) ->
            HText(t, style = Type.small, color = c)
        }
        // Backpack's last ten minutes: "+2 h" (the Mac's other guards, battery and heat, still apply).
        if (a.extendable && a.mode == MacAwake.BACKPACK) {
            Spacer(Modifier.height(10.dp))
            var extending by remember { mutableStateOf(false) }
            Pill(
                stringResource(R.string.notice_extend), {
                    if (!extending) {
                        extending = true
                        scope.launch {
                            hub.extendAwake().onFailure { toast(it.message ?: res.getString(R.string.err_awake)) }
                            extending = false
                        }
                    }
                },
                kind = PillKind.Outline, height = 36.dp,
            )
        }
    }
}
