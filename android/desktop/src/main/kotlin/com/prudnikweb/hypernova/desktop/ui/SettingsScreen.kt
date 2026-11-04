package com.prudnikweb.hypernova.desktop.ui

import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.HomeMark
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DebugLog
import com.prudnikweb.hypernova.data.EnterKey
import com.prudnikweb.hypernova.desktop.EnterKeyPref
import com.prudnikweb.hypernova.data.DebugLogger
import com.prudnikweb.hypernova.data.DebugSendFlow
import com.prudnikweb.hypernova.data.DebugSendState
import com.prudnikweb.hypernova.desktop.DesktopPlatform
import com.prudnikweb.hypernova.desktop.DesktopStrings
import com.prudnikweb.hypernova.desktop.DesktopTokens
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.desktop.FileWhy
import com.prudnikweb.hypernova.desktop.KeyPlace
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.TokenPlace
import com.prudnikweb.hypernova.desktop.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date

/** How wide its column gets. */
private val COLUMN = 640.dp

/**
 * Settings on this computer: whether alerts can show here, where its key and tokens are, and Debug logging as on a phone (data/DebugLog.kt),
 * except that there's no share sheet here: Open folder and Copy path instead.
 */
@Composable
fun SettingsScreen(shell: Shell) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = COLUMN).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HomeMark(shell::goHome, target = 32.dp)
                Spacer(Modifier.width(12.dp))
                HText(Ui.desk_settings.text(), Modifier.weight(1f).semantics { heading() }, style = Type.title)
                Pill(Ui.back.text(), { shell.settings = false }, kind = PillKind.Ghost, height = 32.dp)
            }
            Spacer(Modifier.height(16.dp))
            Alerts()
            Spacer(Modifier.height(16.dp))
            KeyPlaceSection()
            Spacer(Modifier.height(16.dp))
            DebugLogging(shell)
            Spacer(Modifier.height(16.dp))
            Typing()
            Core.live?.let { hub ->
                Spacer(Modifier.height(16.dp))
                RulesRow(shell, hub.mac.name)
            }
        }
    }
}

/** The way to each agent's Permission rules on the server on screen: all the phone's screen does, drawn by the same parts. */
@Composable
private fun RulesRow(shell: Shell, server: String) {
    Seam(Ui.as_rules.text())
    Spacer(Modifier.height(10.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            HText(Ui.desk_rules_row.format(server), style = Type.small)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(Ui.desk_rules_for_claude.text(), { shell.rulesAgent = AgentSettingsReply.CLAUDE }, kind = PillKind.Outline, height = 36.dp)
                Pill(Ui.desk_rules_for_codex.text(), { shell.rulesAgent = AgentSettingsReply.CODEX }, kind = PillKind.Ghost, height = 36.dp)
            }
        }
    }
}

/** Settings → Typing as on the phone: what Enter does in the chat's box. */
@Composable
private fun Typing() {
    val enter = EnterKeyPref.state().value
    Seam(Ui.st_enter_key.text())
    Spacer(Modifier.height(10.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            HText(Ui.st_enter_key_desc.text(), style = Type.small)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((key, words) in listOf(EnterKey.Send to Ui.st_enter_send, EnterKey.NewLine to Ui.st_enter_newline)) {
                    Pill(
                        words.text(), { EnterKeyPref.set(key) },
                        Modifier.semantics { selected = enter == key },
                        kind = if (enter == key) PillKind.Primary else PillKind.Ghost, height = 36.dp,
                    )
                }
            }
        }
    }
}

/** Whether alerts can reach the owner here, and that they stop with the app. */
@Composable
private fun Alerts() {
    Seam(Ui.desk_alerts_title.text())
    Spacer(Modifier.height(10.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            HText(if (Core.notifier.canPost()) Ui.desk_alerts_tray.text() else Ui.desk_alerts_no_tray.text(), style = Type.small)
            Spacer(Modifier.height(8.dp))
            HText(Ui.desk_alerts_only_open.text(), style = Type.small, color = HN.inkMuted)
        }
    }
}

/** Where this computer's key and sign-in tokens are, and in plain words what that doesn't protect from. */
@Composable
private fun KeyPlaceSection() {
    val platform = Core.platform as? DesktopPlatform ?: return
    val place = platform.keys.place
    val tokens = platform.tokens
    Seam(Ui.desk_key_title.text())
    Spacer(Modifier.height(10.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            // "No key yet" would be wrong with a keychain that hasn't said.
            if (place != KeyPlace.Nowhere || tokens.waiting.isEmpty()) {
                val words = when (place) {
                    KeyPlace.Nowhere -> Ui.desk_key_none
                    KeyPlace.InKeychain -> Ui.desk_key_keychain
                    is KeyPlace.InFile -> when (place.why) {
                        FileWhy.Down -> Ui.desk_key_file_down
                        FileWhy.NoHelper -> Ui.desk_key_file_no_helper
                        FileWhy.DidNotHold -> Ui.desk_key_file_did_not_hold
                        FileWhy.TurnedOff -> Ui.desk_key_file_off
                    }
                }
                HText(words.text(), style = Type.small, color = if (place is KeyPlace.InFile && place.why != FileWhy.TurnedOff) HN.amber else HN.ink)
                if (place is KeyPlace.InFile) {
                    Spacer(Modifier.height(8.dp))
                    HText(Ui.desk_key_file_note.text(), style = Type.small, color = HN.inkMuted)
                }
            }
            TokenPlaceWords(tokens)
            if (tokens.waiting.isNotEmpty() || canMove(place, tokens.place)) {
                Spacer(Modifier.height(12.dp))
                TryAgain(tokens)
            }
        }
    }
}

/** The keychain was there to try and may have come back. */
private fun canMove(key: KeyPlace, token: TokenPlace): Boolean =
    (key as? KeyPlace.InFile)?.why.let { it == FileWhy.Down || it == FileWhy.DidNotHold } ||
        (token as? TokenPlace.InFile)?.why.let { it == FileWhy.Down || it == FileWhy.DidNotHold }

@Composable
private fun TokenPlaceWords(tokens: DesktopTokens) {
    for (w in tokens.waiting) {
        Spacer(Modifier.height(8.dp))
        HText(waitWords(w), style = Type.small, color = HN.amber)
    }
    when (val place = tokens.place) {
        TokenPlace.None -> Unit
        TokenPlace.InKeychain -> {
            Spacer(Modifier.height(8.dp))
            HText(Ui.desk_token_keychain.text(), style = Type.small)
        }
        is TokenPlace.InFile -> {
            val words = when (place.why) {
                FileWhy.Down -> Ui.desk_token_file_down
                FileWhy.NoHelper -> Ui.desk_token_file_no_helper
                FileWhy.DidNotHold -> Ui.desk_token_file_did_not_hold
                FileWhy.TurnedOff -> Ui.desk_token_file_off
            }
            Spacer(Modifier.height(8.dp))
            HText(words.text(), style = Type.small, color = if (place.why != FileWhy.TurnedOff) HN.amber else HN.ink)
            Spacer(Modifier.height(8.dp))
            HText(Ui.desk_token_file_note.text(), style = Type.small, color = HN.inkMuted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DebugLogging(shell: Shell) {
    val l = DebugLog.logger ?: return
    val scope = rememberCoroutineScope()
    val flow = remember(l) { DebugSendFlow(scope, { l }) }
    LaunchedEffect(l) { withContext(Dispatchers.IO) { l.refresh() } }
    val confirming = remember { androidx.compose.runtime.mutableStateOf(false) }
    Seam(Ui.debuglog_title.text())
    Spacer(Modifier.height(10.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            LogSwitch(l)
            Spacer(Modifier.height(8.dp))
            HText(Ui.debuglog_holds.text(), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(8.dp))
            HText(Ui.debuglog_crash_note.text(), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(HN.line))
            Spacer(Modifier.height(12.dp))
            val size = when {
                l.empty -> Ui.debuglog_none.text()
                l.hasCrash -> Ui.debuglog_size_crash.format(DebugLog.size(l.sizeBytes))
                else -> Ui.debuglog_size.format(DebugLog.size(l.sizeBytes))
            }
            HText(size, style = Type.small)
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Core.servers.isNotEmpty()) {
                    Pill(Ui.debuglog_send.text(), flow::send, kind = PillKind.Primary, height = 36.dp, enabled = !l.empty && flow.state !is DebugSendState.Sending)
                }
                Pill(
                    Ui.desk_open_folder.text(),
                    {
                        scope.launch {
                            val opened = withContext(Dispatchers.IO) {
                                l.flush()
                                shell.desk.openFolder(l.folder)
                            }
                            if (!opened) shell.say(Ui.desk_folder_failed.text())
                        }
                    },
                    height = 36.dp, enabled = !l.empty,
                )
                Pill(
                    Ui.debuglog_copy_path.text(),
                    {
                        shell.desk.copy(l.folder.path)
                        shell.say(Ui.desk_folder_copied.text())
                    },
                    height = 36.dp, enabled = !l.empty,
                )
                Pill(Ui.debuglog_clear.text(), { confirming.value = true }, kind = PillKind.Ghost, height = 36.dp, enabled = !l.empty)
            }
            if (confirming.value) {
                Spacer(Modifier.height(12.dp))
                HText(Ui.debuglog_clear_q.text(), style = Type.small)
                Spacer(Modifier.height(4.dp))
                HText(Ui.debuglog_clear_body.text(), style = Type.small, color = HN.inkMuted)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(
                        Ui.debuglog_clear.text(),
                        {
                            confirming.value = false
                            scope.launch {
                                withContext(Dispatchers.IO) { l.clear() }
                                flow.dismiss()
                                shell.say(Ui.debuglog_cleared.text())
                            }
                        },
                        kind = PillKind.Danger, height = 36.dp,
                    )
                    Pill(Ui.cancel.text(), { confirming.value = false }, kind = PillKind.Ghost, height = 36.dp)
                }
            }
            SendState(flow, shell)
        }
    }
}

/** The switch: its words say until when, and a click anywhere on the row turns it over. */
@Composable
private fun LogSwitch(l: DebugLogger) {
    val on = l.until > 0
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Switch) { if (on) l.turnOff() else l.turnOn() }
            .semantics { toggleableState = ToggleableState(on) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            HText(Ui.debuglog_switch.text(), style = Type.body)
            Spacer(Modifier.height(2.dp))
            val body = if (on) Ui.debuglog_on_until.format(SimpleDateFormat(DesktopStrings.timePattern(withDay = true), DesktopStrings.locale).format(Date(l.until))) else Ui.debuglog_off.text()
            HText(body, style = Type.small, color = HN.inkDim)
        }
        Spacer(Modifier.width(12.dp))
        Canvas(Modifier.size(width = 36.dp, height = 20.dp)) {
            val r = size.height / 2
            drawRoundRect(if (on) HN.accent.copy(alpha = 0.55f) else HN.ink.copy(alpha = 0.16f), Offset.Zero, Size(size.width, size.height), CornerRadius(r))
            drawCircle(if (on) HN.accent else HN.inkMuted, r * 0.72f, Offset(if (on) size.width - r else r, r))
        }
    }
}

/** Where Send logs stands: which server, sending, where it landed, or what stopped it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendState(flow: DebugSendFlow, shell: Shell) {
    val state = flow.state
    if (state == DebugSendState.Idle) return
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        when (state) {
            DebugSendState.Idle -> Unit
            is DebugSendState.Choosing -> {
                HText(Ui.debuglog_send_to.text(), style = Type.small)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in state.servers) Pill(s.name, { flow.sendTo(s.id) }, height = 36.dp)
                    Pill(Ui.cancel.text(), flow::dismiss, kind = PillKind.Ghost, height = 36.dp)
                }
            }
            is DebugSendState.Sending -> HText(Ui.debuglog_sending.format(state.server), style = Type.small, color = HN.inkMuted)
            is DebugSendState.Sent -> {
                HText(Ui.debuglog_sent.format(state.server), style = Type.small, color = HN.accent)
                Spacer(Modifier.height(4.dp))
                SelectionContainer { HText(state.saved.path, style = Type.monoSmall) }
                Spacer(Modifier.height(8.dp))
                Pill(
                    Ui.debuglog_copy_path.text(),
                    {
                        shell.desk.copy(state.saved.path)
                        shell.say(Ui.debuglog_copied.text())
                    },
                    height = 36.dp,
                )
            }
            is DebugSendState.TooOld -> HText(Ui.debuglog_too_old.format(state.server), style = Type.small, color = HN.amber)
            is DebugSendState.Failed -> HText(state.why.ifBlank { Ui.debuglog_send_failed.text() }, style = Type.small, color = HN.danger)
        }
    }
}
