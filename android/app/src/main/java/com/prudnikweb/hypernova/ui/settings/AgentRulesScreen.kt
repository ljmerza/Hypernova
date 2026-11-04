package com.prudnikweb.hypernova.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Confirm
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.ui.LocalConfirm
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.ScreenScaffold
import com.prudnikweb.hypernova.ui.components.screenBottom

/** The phone's frame round the shared Permission rules ([agentRulesItems]): its scaffold, its confirm and its toast. */
@Composable
fun AgentRulesScreen(hub: Hub, agent: String, onBack: () -> Unit) {
    val bottom = screenBottom()
    val server = hub.mac.name
    val ask by rememberUpdatedState(LocalConfirm.current)
    val tell by rememberUpdatedState(LocalToast.current)
    val host = remember {
        object : RulesHost {
            override fun confirm(title: String, body: String, action: String, go: () -> Unit) = ask(Confirm(title, body, action, danger = true) { go() })
            override fun toast(text: String) = tell(text)
        }
    }
    val state = rememberAgentRulesState(hub, agent, server, host)
    ScreenScaffold(sourceKey = "agent-rules:$agent", title = stringResource(R.string.as_rules), subtitle = server, onBack = onBack) { top, source ->
        LazyColumn(
            Modifier.fillMaxSize().then(source),
            contentPadding = PaddingValues(top = top, bottom = bottom + 32.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            agentRulesItems(state, server)
        }
    }
}
