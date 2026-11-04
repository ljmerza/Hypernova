package com.prudnikweb.hypernova.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.AskBeforeTabs
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.NavRow
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.screenBottom

/** The phone's frame for the form (the parts: :sharedui's ui.settings). [onScreen]: a setting with a screen of its own. */
@Composable
fun AgentFormScreen(hub: Hub, agent: String, onBack: () -> Unit, onScreen: (String) -> Unit) {
    val context = LocalContext.current
    val bottom = screenBottom()
    val form = rememberSettingsForm(hub, agent, LocalConfirm.current, LocalToast.current)
    val confirm = LocalConfirm.current
    /** Leaving loses what's typed and not saved, so it asks first, as the JSON tab does. */
    fun leaving(go: () -> Unit) {
        if (!form.dirty) return go()
        confirm(Confirm(context.getString(R.string.raw_discard_title), context.getString(R.string.raw_discard_body), context.getString(R.string.raw_discard_action), danger = true) { go() })
    }
    AskBeforeTabs { go -> leaving(go) }
    BackHandler(enabled = form.dirty) { leaving(onBack) }
    ScreenScaffold(
        sourceKey = "agent-form:$agent",
        title = stringResource(if (agent == AgentSettingsReply.CODEX) R.string.sf_title_codex else R.string.sf_title_claude),
        subtitle = hub.mac.name,
        onBack = { leaving(onBack) },
    ) { top, source ->
        SettingsFormList(
            form,
            onScreen = { screen -> leaving { onScreen(screen) } },
            modifier = Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
        )
    }
}

/** The one row on an agent's settings screen that opens the form. */
@Composable
internal fun AllSettingsRow(onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            NavRow(stringResource(R.string.sf_row), stringResource(R.string.sf_row_body), onClick = onClick)
        }
    }
}
