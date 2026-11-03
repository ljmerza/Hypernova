package com.prudnikweb.hypernova.ui.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Account
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.GlassSheet
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * Signing in from anywhere (a /login from the box or the Commands tab, Sign in here on a session's
 * card): subscription or API, then [SignInPanel]'s address and code. It closes when the sign-in has
 * finished, and a sign-in left running stays on the server for this sheet to show again.
 */
@Composable
fun SignInSheet(hub: Hub, onDismiss: () -> Unit) {
    val toast = LocalToast.current
    val done = stringResource(R.string.signin_done)
    val login = hub.account.login
    var started by remember { mutableStateOf(login != null) }
    var cancelled by remember { mutableStateOf(false) }
    LaunchedEffect(login != null) {
        if (login != null) started = true
        else if (started) {
            if (!cancelled) toast(done)
            onDismiss()
        }
    }
    // The app's toasts draw behind a sheet: what the panel has to say (a code turned away) goes in it.
    var said by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(said) {
        if (said != null) {
            delay(8_000)
            said = null
        }
    }
    GlassSheet(stringResource(R.string.signin_sheet_title), onDismiss, null, true) {
        said?.let {
            HText(it, style = Type.small, color = HN.danger)
            Spacer(Modifier.height(10.dp))
        }
        if (login == null && hub.account.state == Account.OK) {
            HText(stringResource(R.string.signin_replaces, hub.mac.name), style = Type.small, color = HN.inkMuted)
            Spacer(Modifier.height(12.dp))
        }
        CompositionLocalProvider(LocalToast provides { said = it }) {
            SignInPanel(
                hub, hub.mac.name, choosing = true,
                onCancel = {
                    cancelled = true
                    if (hub.account.login == null) onDismiss()
                },
            )
        }
    }
}
