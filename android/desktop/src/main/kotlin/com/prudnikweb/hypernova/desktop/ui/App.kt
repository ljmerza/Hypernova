package com.prudnikweb.hypernova.desktop.ui

import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Core
import com.prudnikweb.hypernova.data.DebugLog
import com.prudnikweb.hypernova.desktop.LocalShell
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.Opener
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * The window's content. Nothing paired, or "Pair another": the pairing field. Otherwise the server on
 * screen's sessions. [reduced]: nothing turns (a test's screen, or a later setting).
 */
@Composable
fun App(shell: Shell, reduced: Boolean = false) {
    val opener = remember(shell) { deskOpener(shell) }
    var asking by remember { androidx.compose.runtime.mutableStateOf<com.prudnikweb.hypernova.data.Confirm?>(null) }
    AtlasTheme(reduced) {
        CompositionLocalProvider(com.prudnikweb.hypernova.ui.LocalConfirm provides { asking = it }, LocalToast provides shell::say, LocalOpener provides opener, LocalAppScope provides Core.scope, LocalShell provides shell) {
        asking?.let { c ->
            com.prudnikweb.hypernova.ui.components.ConfirmDialog(
                visible = true, title = c.title, body = c.body, confirm = c.action, danger = c.danger,
                onConfirm = { asking = null; c.onConfirm() }, onDismiss = { asking = null },
            )
        }
        Box(Modifier.fillMaxSize().background(HN.bg)) {
            val hub = Core.live
            val pairing = hub == null || shell.adding
            AtlasBackground(week = hub?.usage?.sevenDay ?: 0f, strength = if (pairing) 0.45f else 1f)
            // The debug log's line for each screen, as on a phone.
            val screen = if (shell.settings) "settings" else if (pairing) "pairing" else "sessions"
            LaunchedEffect(screen) { DebugLog.i("screen") { screen } }
            val rules = shell.rulesAgent
            if (shell.settings && rules != null && hub != null) RulesScreen(hub, rules, shell)
            else if (shell.settings) SettingsScreen(shell)
            else if (hub == null || shell.adding) PairScreen(shell, canCancel = hub != null)
            else SessionsScreen(hub, Core.statuses, shell)
            // Above the bottom's message box, so it doesn't cover what's being typed.
            shell.toast?.let { Toast(it, shell, Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)) }
            shell.zoomNote?.let { ZoomNote(it, Modifier.align(Alignment.TopCenter)) }
        }
        }
    }
}

/**
 * Where the shared drawing's ways out go in this window: a session it can show; what it has no screen for yet says it's on the phone, rather than doing nothing.
 * A file isn't one of those: a row with nothing to open just doesn't open ([Opener.opensFiles]).
 */
private fun deskOpener(shell: Shell): Opener {
    val onPhone = { words: Ui -> shell.say(words.text()) }
    val here = { id: String -> shell.choose(Core.activeId.orEmpty(), id) }
    return Opener(
        session = here,
        switchSession = here,
        files = { _, _ -> onPhone(Ui.desk_files_on_phone) },
        file = { onPhone(Ui.desk_files_on_phone) },
        preview = { _, _, _ -> onPhone(Ui.desk_preview_on_phone) },
        web = { _, _ -> onPhone(Ui.desk_preview_on_phone) },
        terminal = { onPhone(Ui.desk_terminal_on_phone) },
        agent = { _, _ -> onPhone(Ui.desk_agent_on_phone) },
        providers = { onPhone(Ui.desk_settings_on_phone) },
        provider = { _, _ -> onPhone(Ui.desk_settings_on_phone) },
        runAtReset = { onPhone(Ui.desk_settings_on_phone) },
        automation = { onPhone(Ui.desk_settings_on_phone) },
        connectionLog = { onPhone(Ui.desk_settings_on_phone) },
        settings = { onPhone(Ui.desk_settings_on_phone) },
        signIn = { onPhone(Ui.desk_settings_on_phone) },
        mcpServers = { onPhone(Ui.desk_settings_on_phone) },
        opensFiles = false,
    )
}

/**
 * Everything in [content] at [scale] pixels per dp. Only what dp and sp come to changes: layout, drawing and the
 * pointer are all in pixels, so a click lands where it's aimed and text is drawn at its size, not stretched.
 */
@Composable
fun Scaled(scale: Float, content: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    CompositionLocalProvider(LocalDensity provides Density(scale, fontScale), content = content)
}

/** The zoom level, for a moment, out of the way of a toast. */
@Composable
private fun ZoomNote(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .padding(12.dp)
            .background(HN.bg900.copy(alpha = 0.97f), shape)
            .border(1.dp, HN.lineStrong, shape)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        HText(text, style = Type.small, maxLines = 1)
    }
}

/** A hub's refusal or a lost link, in the glass of the app's toast. A click puts it away. */
@Composable
private fun Toast(text: String, shell: Shell, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .padding(16.dp)
            .widthIn(max = 560.dp)
            .background(HN.bg900.copy(alpha = 0.97f), shape)
            .border(1.dp, HN.danger.copy(alpha = 0.35f), shape)
            .clickable(onClick = shell::dismissToast)
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        HText(text, style = Type.small)
    }
}
