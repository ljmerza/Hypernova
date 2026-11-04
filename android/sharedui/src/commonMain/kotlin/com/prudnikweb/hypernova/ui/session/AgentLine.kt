package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.platform.wordsNow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.CodexModel
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.agentName
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.TagChip
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.home.CodexModelSheet
import com.prudnikweb.hypernova.ui.home.codexModelName
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * The agent [s] can switch to from its panel: Claude from any Codex session; Codex from a Claude one
 * once Codex is in use on this Mac; none once the conversation went on elsewhere.
 */
internal fun switchTarget(hub: Hub, s: Session): String? = when {
    s.switchedTo != null -> null
    s.codex -> AgentKind.CLAUDE
    hub.codex?.canSwitchTo == true -> AgentKind.CODEX
    else -> null
}

/**
 * The status line's panel, after the model and the mode: the agent the session runs on, as its
 * chip, and the way to the other one. TalkBack hears "Switch to Codex".
 */
@Composable
internal fun AgentLine(codex: Boolean, onClick: () -> Unit) {
    val target = stringResource(if (codex) Str.cx_switch_to_claude else Str.cx_switch_to_codex)
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .glassChoice(false, 12.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = target, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = target }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(stringResource(Str.cx_agent).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        TagChip(agentName(codex))
        Spacer(Modifier.width(10.dp))
        HText(target, style = Type.small, color = HN.accentDim, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        Icon(Glyph.Chevron, size = 11.dp, color = HN.inkDim)
    }
}

/**
 * A Codex session's model and reasoning effort, from the session sheet: Codex's own list (the one it
 * runs marked), its effort under it, and Done switches it ([Hub.setCodexModel]; from its next turn
 * while one runs).
 */
@Composable
internal fun CodexModelPicker(hub: Hub, s: Session, onDismiss: () -> Unit) {
    val appScope = LocalAppScope.current
    val toast = LocalToast.current
    val res = wordsNow()
    var models by remember { mutableStateOf<List<CodexModel>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hub) { hub.codexModels().onSuccess { models = it }.onFailure { models = emptyList(); note = it.message } }
    CodexModelSheet(
        models, note, model = s.model, effort = null, withEffort = true,
        onDone = { m, e ->
            onDismiss()
            if (m != s.model || e != null) {
                val name = codexModelName(models, m, res.getString(Str.model_label_default))
                appScope.launch {
                    hub.setCodexModel(s.id, m, e)
                        .onSuccess { sw -> if (sw.nextTurn) toast(res.getString(Str.model_next_turn, name)) }
                        .onFailure { toast(it.message.orEmpty()) }
                }
            }
        },
        onDismiss = onDismiss,
    )
}

