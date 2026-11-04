package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.AgentSettingsReply
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.platform.stringResource
import com.prudnikweb.hypernova.ui.settings.SettingsFormList
import com.prudnikweb.hypernova.ui.settings.rememberSettingsForm
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/**
 * Every one of an agent's settings on the desktop: the phone's form, in this window's frame. What runs code or loosens something asks
 * the same plain one-tap question as on the phone, since neither has a fingerprint here (docs/DECISIONS.md). A setting with a screen
 * of its own says which, and those screens aren't on the desktop yet (docs/PLATFORMS.md).
 */
@Composable
internal fun AgentFormPane(hub: Hub, agent: String, onBack: () -> Unit) {
    var asking by remember { mutableStateOf<Confirm?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val form = rememberSettingsForm(hub, agent, confirm = { asking = it }, toast = { notice = it })
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HText(
                    stringResource(if (agent == AgentSettingsReply.CODEX) Str.sf_title_codex else Str.sf_title_claude),
                    Modifier.weight(1f).semantics { heading() },
                    style = Type.title,
                )
                Pill(Ui.back.text(), onBack, kind = PillKind.Ghost, height = 32.dp)
            }
            notice?.let {
                Spacer(Modifier.height(6.dp))
                HText(it, style = Type.small, color = HN.inkMuted)
            }
            Spacer(Modifier.height(12.dp))
            SettingsFormList(form, onScreen = {}, modifier = Modifier.fillMaxWidth())
        }
    }
    asking?.let { c ->
        ConfirmModal(
            c.title,
            c.body,
            c.action,
            onConfirm = {
                asking = null
                c.onConfirm()
            },
            onDismiss = { asking = null },
            danger = c.danger,
        )
    }
}

/** Settings' way in: one for each agent. */
@Composable
internal fun AgentFormLinks(onOpen: (String) -> Unit) {
    Seam(stringResource(Str.sf_row))
    Spacer(Modifier.height(10.dp))
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            HText(stringResource(Str.sf_row_body), style = Type.small)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(stringResource(Str.sf_title_claude), { onOpen(AgentSettingsReply.CLAUDE) }, kind = PillKind.Outline, height = 36.dp)
                Pill(stringResource(Str.sf_title_codex), { onOpen(AgentSettingsReply.CODEX) }, kind = PillKind.Outline, height = 36.dp)
            }
        }
    }
}
