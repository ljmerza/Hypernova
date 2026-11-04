package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.ui.platform.wordsNow
import com.prudnikweb.hypernova.ui.components.glassChoice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/* The session's model: its line in the sheet, the picker, and the switch with its question, on both systems. */

/**
 * The session sheet's Model part: the model the session runs, as its chip, and a way to another
 * one (the picker below). TalkBack hears "Model: Opus 5.5" and the action.
 */
@Composable
internal fun ModelLine(model: String, onClick: () -> Unit) {
    val res = wordsNow()
    val name = com.prudnikweb.hypernova.data.ModelNames.chip(model)
    val said = res.getString(Str.meta_model_said, name)
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .glassChoice(false, 12.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(Str.model_change), onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(stringResource(Str.model).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        com.prudnikweb.hypernova.ui.components.ModelChip(name)
        Spacer(Modifier.width(10.dp))
        Icon(Glyph.Chevron, size = 11.dp, color = HN.inkDim)
    }
}

/**
 * The Start sheet's model picker for a running session: only its own provider's models (moving
 * to another provider is the limit card's "carry on elsewhere"), the one it runs marked. A pick
 * switches it ([Hub.setModel]); on a provider that bills it, after the owner's yes ("This costs
 * money", as automations ask).
 */
@Composable
private fun SessionModelPicker(hub: Hub, s: Session, onDismiss: () -> Unit) {
    val provider = hub.providers.firstOrNull { it.id == (s.providerId ?: "anthropic") } ?: hub.providers.firstOrNull { it.builtIn } ?: return
    var lists by remember { mutableStateOf<List<com.prudnikweb.hypernova.data.ModelList>?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(hub) {
        hub.models().onSuccess { lists = it }
        loading = false
    }
    val options = remember(provider, lists) { com.prudnikweb.hypernova.data.ModelCatalog.of(provider, lists) }
    val current = com.prudnikweb.hypernova.data.ModelCatalog.current(options, s.model)
    val opened = remember { System.currentTimeMillis() }
    com.prudnikweb.hypernova.ui.home.ModelPicker(
        listOf(provider), lists, loading, provider.id,
        // None of the list runs it (a model it was started on elsewhere): nothing is marked.
        model = if (current != null) current.value else NONE_MARKED,
        weeks = hub.usage.models, now = opened,
        providerTrail = { null },
        onPick = { _, value ->
            onDismiss()
            val o = com.prudnikweb.hypernova.data.ModelCatalog.find(options, value)
            if (o != null && o != current) {
                ModelSwitching.ask(s.id, o.value ?: o.model, o.name, paid = com.prudnikweb.hypernova.data.ModelCatalog.paid(provider, o))
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * A model switch on its way, and the "This costs money" question it may need first. Kept outside
 * the picker (which closes on the pick) so the question and the switch outlive it.
 */
private object ModelSwitching {
    /** The switch waiting for the owner's yes: session, model (null: the default), its name. */
    var asking by mutableStateOf<Triple<String, String?, String>?>(null)
    var pending by mutableStateOf<Pair<Triple<String, String?, String>, Boolean>?>(null)

    fun ask(sessionId: String, model: String?, name: String, paid: Boolean) {
        val t = Triple(sessionId, model, name)
        if (paid) asking = t else pending = t to false
    }
}

/**
 * Runs the switch [ModelSwitching] holds, and asks "This costs money" when the session's provider
 * bills it (or the Mac says so): the Mac switches only with that yes.
 */
@Composable
private fun ModelSwitchHost(hub: Hub, s: Session) {
    val appScope = com.prudnikweb.hypernova.ui.LocalAppScope.current
    val toast = LocalToast.current
    val res = wordsNow()
    val go = ModelSwitching.pending
    LaunchedEffect(go) {
        val (t, paid) = go ?: return@LaunchedEffect
        ModelSwitching.pending = null
        appScope.launch {
            hub.setModel(t.first, t.second, confirmPaid = paid)
                .onSuccess { sw -> if (sw.nextTurn) toast(res.getString(Str.model_next_turn, t.third)) }
                .onFailure { e -> if (e is com.prudnikweb.hypernova.data.PaidModel && !paid) ModelSwitching.asking = t else toast(e.message.orEmpty()) }
        }
    }
    val q = ModelSwitching.asking?.takeIf { it.first == s.id } ?: return
    val provider = hub.providers.firstOrNull { it.id == (s.providerId ?: "anthropic") }?.name ?: s.providerId.orEmpty()
    val dismiss = { ModelSwitching.asking = null }
    com.prudnikweb.hypernova.ui.components.GlassDialog(
        onDismiss = dismiss,
        eyebrow = q.third,
        title = stringResource(Str.model_paid_q),
        line = stringResource(Str.model_paid_body, provider),
        primary = com.prudnikweb.hypernova.ui.components.DialogButton(stringResource(Str.model_paid_ok), { ModelSwitching.pending = q to true; dismiss() }, PillKind.Primary),
        secondary = com.prudnikweb.hypernova.ui.components.DialogButton(stringResource(Str.cancel), dismiss),
        tint = HN.amber,
    )
}

/** The model picker, while it's [open], and the switch's question: both systems' session sheets open them the same way. */
@Composable
internal fun ModelPickerHost(hub: Hub, s: Session, open: Boolean, onDismiss: () -> Unit) {
    if (open && canChangeModel(s)) {
        if (s.codex) CodexModelPicker(hub, s, onDismiss = onDismiss)
        else SessionModelPicker(hub, s, onDismiss = onDismiss)
    }
    ModelSwitchHost(hub, s)
}
