package com.prudnikweb.hypernova.desktop.ui

import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.desktop.DesktopTokens
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.desktop.WaitWhy
import com.prudnikweb.hypernova.desktop.WaitingServer

/**
 * The window. A server whose token is in a keychain that hasn't given it is said above everything else, and with nothing
 * else to show it's the whole screen: never the pairing field, which would read as being unpaired. docs/DECISIONS.md,
 * "The desktop client's tokens in the keychain".
 */
@Composable
fun Root(shell: Shell, tokens: DesktopTokens, reduced: Boolean = false) {
    when {
        tokens.waiting.isEmpty() -> App(shell, reduced)
        Core.live == null && !shell.settings -> AtlasTheme(reduced) {
            Box(Modifier.fillMaxSize().background(HN.bg)) {
                AtlasBackground(week = 0f, strength = 0.45f)
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Pill(Ui.desk_settings.text(), { shell.settings = true }, Modifier.align(Alignment.BottomCenter), kind = PillKind.Ghost, height = 32.dp)
                    WaitCard(tokens, Modifier.padding(bottom = 40.dp).verticalScroll(rememberScrollState()))
                }
            }
        }
        else -> Column(Modifier.fillMaxSize()) {
            AtlasTheme(reduced) {
                Box(Modifier.fillMaxWidth().background(HN.bg).padding(12.dp), contentAlignment = Alignment.Center) {
                    WaitCard(tokens)
                }
            }
            Box(Modifier.weight(1f)) { App(shell, reduced) }
        }
    }
}

/** Which servers wait and why, and what can be done: try again, and only as the owner's own choice, forget one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaitCard(tokens: DesktopTokens, modifier: Modifier = Modifier) {
    GlassCard(Modifier.widthIn(max = 560.dp).fillMaxWidth().then(modifier), edge = HN.amber) {
        Column(Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Seam(Ui.desk_wait_title.text(), color = HN.amber)
            Spacer(Modifier.height(12.dp))
            for (w in tokens.waiting) {
                HText(waitWords(w), style = Type.small)
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TryAgain(tokens)
                for (w in tokens.waiting) {
                    Pill(Ui.desk_wait_forget.format(w.name), { tokens.forget(w.id) }, kind = PillKind.Ghost, height = 36.dp)
                }
            }
            Spacer(Modifier.height(10.dp))
            HText(Ui.desk_wait_forget_note.text(), style = Type.small, color = HN.inkMuted)
        }
    }
}

@Composable
internal fun TryAgain(tokens: DesktopTokens) {
    val busy = tokens.retrying
    Pill(if (busy) Ui.desk_token_retrying.text() else Ui.desk_token_retry.text(), tokens::retry, kind = PillKind.Primary, height = 36.dp, enabled = !busy)
}

internal fun waitWords(w: WaitingServer): String = when (w.why) {
    WaitWhy.Locked -> Ui.desk_wait_locked
    WaitWhy.Gone -> Ui.desk_wait_gone
    WaitWhy.NoHelper -> Ui.desk_wait_no_helper
    WaitWhy.TurnedOff -> Ui.desk_wait_off
}.format(w.name)
