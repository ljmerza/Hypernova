package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.desktop.Shell
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.settings.RulesHost
import com.prudnikweb.hypernova.ui.settings.agentRulesItems
import com.prudnikweb.hypernova.ui.settings.rememberAgentRulesState
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

private class Asked(val title: String, val body: String, val action: String, val go: () -> Unit)

/** The desktop's frame round the shared Permission rules: its column, its modal and its toast. It does all the phone does. */
@Composable
fun RulesScreen(hub: Hub, agent: String, shell: Shell) {
    var asked by remember { mutableStateOf<Asked?>(null) }
    val host = remember(shell) {
        object : RulesHost {
            override fun confirm(title: String, body: String, action: String, go: () -> Unit) {
                asked = Asked(title, body, action, go)
            }
            override fun toast(text: String) = shell.say(text)
        }
    }
    val server = hub.mac.name
    val state = rememberAgentRulesState(hub, agent, server, host)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val who = if (agent == AgentSettingsReply.CODEX) Ui.desk_rules_codex.format(server) else Ui.desk_rules_claude.format(server)
                HText(who, Modifier.weight(1f).semantics { heading() }, style = Type.title)
                Pill(Ui.back.text(), { shell.rulesAgent = null }, kind = PillKind.Ghost, height = 32.dp)
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                agentRulesItems(state, server)
            }
        }
    }
    asked?.let { a ->
        ConfirmModal(a.title, a.body, a.action, onConfirm = { asked = null; a.go() }, onDismiss = { asked = null }, danger = true)
    }
}
