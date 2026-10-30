package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentKind
import com.prudnikweb.hypernova.data.CodexModel
import com.prudnikweb.hypernova.data.CodexWire
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.NewSessionMemory
import com.prudnikweb.hypernova.data.Phase
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SessionState
import com.prudnikweb.hypernova.data.SwitchRefused
import com.prudnikweb.hypernova.data.pick
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.agentName
import com.prudnikweb.hypernova.ui.components.FieldLabel
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Segmented
import com.prudnikweb.hypernova.ui.components.TagChip
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.components.modeGlyph
import com.prudnikweb.hypernova.ui.home.ChipText
import com.prudnikweb.hypernova.ui.home.CodexModelSheet
import com.prudnikweb.hypernova.ui.home.MODES
import com.prudnikweb.hypernova.ui.home.ModeSheet
import com.prudnikweb.hypernova.ui.home.PickChip
import com.prudnikweb.hypernova.ui.home.codexModelName
import com.prudnikweb.hypernova.ui.home.effortTitle
import com.prudnikweb.hypernova.ui.home.modeLabel
import com.prudnikweb.hypernova.ui.home.modeTitle
import com.prudnikweb.hypernova.ui.home.modeTone
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

// Switching agents (docs/PROTOCOL.md, "Switching agents"): a conversation goes on with the other
// agent, Claude Code or Codex, in a new session in the same folder. The session's panel offers it,
// this sheet carries it out, and the two sessions point to each other under their titles.

/** How much of the conversation a switch carries over: the last messages, all of it, or nothing. */
private const val RECENT = "recent"
private const val ALL = "all"
private const val NONE = "none"

/** How long "Stop and switch" waits for the session to come to rest before it gives up. */
private const val STOP_WAIT_MS = 20_000L

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

/** Claude or Codex at work in [s] (a turn, an approval, prompts waiting): a switch stops it first. */
private fun atWork(s: Session): Boolean = s.phase != Phase.Ended && (s.state != SessionState.Idle || s.phase == Phase.Starting || s.queued > 0)

/**
 * Under a switched session's title, in the status line's place for automations: "CONTINUED FROM
 * CLAUDE ›" (the conversation before the switch, opened over this one) or, on the earlier one,
 * "CONTINUED IN CODEX ›" (the chat goes on there). Nothing for a session that never switched.
 */
@Composable
internal fun SwitchLine(hub: Hub, s: Session, modifier: Modifier = Modifier) {
    val opener = LocalOpener.current
    val to = s.switchedTo
    val from = s.switchedFrom
    val id = to ?: from ?: return
    // The other session's agent; one this phone no longer has was the other agent, as every switch is.
    val otherCodex = hub.session(id)?.codex ?: !s.codex
    val words = stringResource(if (to != null) R.string.cx_continued_in else R.string.cx_continued_from, agentName(otherCodex))
    Row(
        modifier
            .heightIn(min = 36.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.open_session)) {
                if (to != null) opener.switchSession(to) else opener.session(id)
            }
            .semantics(mergeDescendants = true) { contentDescription = words },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(words.uppercase(), style = Type.label, color = HN.accentDim, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Icon(Glyph.Chevron, size = 10.dp, color = HN.accentDim)
    }
}

/**
 * The status line's panel, after the model and the mode: the agent the session runs on, as its
 * chip, and the way to the other one. TalkBack hears "Switch to Codex".
 */
@Composable
internal fun AgentLine(codex: Boolean, onClick: () -> Unit) {
    val target = stringResource(if (codex) R.string.cx_switch_to_claude else R.string.cx_switch_to_codex)
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
        HText(stringResource(R.string.cx_agent).uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
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
    val res = LocalContext.current.resources
    var models by remember { mutableStateOf<List<CodexModel>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hub) { hub.codexModels().onSuccess { models = it }.onFailure { models = emptyList(); note = it.message } }
    CodexModelSheet(
        models, note, model = s.model, effort = null, withEffort = true,
        onDone = { m, e ->
            onDismiss()
            if (m != s.model || e != null) {
                val name = codexModelName(models, m, res.getString(R.string.model_label_default))
                appScope.launch {
                    hub.setCodexModel(s.id, m, e)
                        .onSuccess { sw -> if (sw.nextTurn) toast(res.getString(R.string.model_next_turn, name)) }
                        .onFailure { toast(it.message.orEmpty()) }
                }
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * The switch to [to] ([AgentKind.CLAUDE] or [AgentKind.CODEX]): what goes over (Recent, Entire or
 * None, drafted by the Mac) in a field to read and edit, with its size against the most a switch
 * takes, how the other agent starts (Codex's model, effort and mode; Claude's mode), and Switch. A
 * session at work is stopped first ("Stop and switch": Stop, then the switch once it rests). The
 * Mac's refusals show under the field; one that says the conversation went on already goes there.
 */
@Composable
internal fun AgentSwitchSheet(hub: Hub, s: Session, to: String, onSwitched: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val res = context.resources
    // The sheet's own: put away, a switch on its way stops waiting (the Mac may still make it, and
    // the session then says "Continued in" under its title).
    val scope = rememberCoroutineScope()
    val toCodex = to == AgentKind.CODEX
    val memory = remember { NewSessionMemory.load(context) }
    var carry by rememberSaveable { mutableStateOf(RECENT) }
    var text by remember { mutableStateOf("") }
    var messages by remember { mutableIntStateOf(0) }
    var maxBytes by remember { mutableIntStateOf(CodexWire.SWITCH_MAX_BYTES) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var switchingNow by remember { mutableStateOf(false) }
    // The Mac said it's at work after all: Stop first, then the switch.
    var mustStop by remember { mutableStateOf(false) }
    // How the other agent starts: as the new-session sheet last started it (never Auto or Bypass).
    var model by rememberSaveable { mutableStateOf(if (toCodex) memory.codexModel else null) }
    var effort by rememberSaveable { mutableStateOf(if (toCodex) memory.codexEffort else null) }
    var mode by rememberSaveable { mutableStateOf(if (toCodex) memory.codexMode else memory.mode) }
    var picking by rememberSaveable { mutableStateOf<Pick?>(null) }
    var models by remember { mutableStateOf<List<CodexModel>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(carry) {
        loading = true
        error = null
        hub.switchPreview(s.id, to, carry)
            .onSuccess { p -> text = p.text; messages = p.messages; maxBytes = p.maxBytes }
            .onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(hub, toCodex) {
        if (!toCodex) return@LaunchedEffect
        hub.codexModels().onSuccess { models = it }.onFailure { models = emptyList(); note = it.message }
        // A model (or effort) Codex no longer offers is its default again, as the new-session sheet does.
        val l = models.orEmpty()
        if (model != null && l.none { it.value == model }) model = null
        if (effort != null && l.pick(model)?.efforts?.contains(effort) != true) effort = null
    }
    val bytes = remember(text) { text.toByteArray(Charsets.UTF_8).size }
    val over = bytes > maxBytes
    val stopFirst = atWork(s) || mustStop
    val from = agentName(s.codex)
    val toName = agentName(toCodex)
    val title = stringResource(if (toCodex) R.string.cx_switch_to_codex else R.string.cx_switch_to_claude)
    val stopTimeout = stringResource(R.string.cx_stop_timeout, from)

    fun go() {
        if (switchingNow || loading || over) return
        switchingNow = true
        error = null
        scope.launch {
            try {
                if (stopFirst) {
                    hub.interrupt(s.id)
                    // At rest once it has stopped: the live link says so.
                    val rested = withTimeoutOrNull(STOP_WAIT_MS) { snapshotFlow { hub.session(s.id)?.let { !atWork(it) } ?: true }.first { it } }
                    if (rested == null) { error = stopTimeout; return@launch }
                }
                hub.switchAgent(s.id, to, text, model = model.takeIf { toCodex }, effort = effort.takeIf { toCodex }, mode = mode)
                    .onSuccess(onSwitched)
                    .onFailure { e ->
                        val r = e as? SwitchRefused
                        when (r?.code) {
                            // It went on once already: there, then.
                            "switched" -> r.to?.let(onSwitched) ?: run { error = e.message }
                            "busy" -> { mustStop = true; error = e.message }
                            "context_too_large" -> error = res.getString(R.string.cx_too_long, kb(r.maxBytes ?: maxBytes))
                            else -> error = e.message
                        }
                    }
            } finally {
                switchingNow = false
            }
        }
    }

    ChatSheet(title, onDismiss) {
        HText(title, style = Type.title, color = HN.ink)
        Spacer(Modifier.height(4.dp))
        HText(stringResource(R.string.cx_switch_line, toName), style = Type.small, color = HN.inkMuted)
        Spacer(Modifier.height(16.dp))

        // What goes over.
        FieldLabel(stringResource(R.string.cx_carry))
        Segmented(
            listOf(RECENT to stringResource(R.string.cx_carry_recent), ALL to stringResource(R.string.cx_carry_all), NONE to stringResource(R.string.cx_carry_none)),
            carry, { carry = it },
        )
        Spacer(Modifier.height(10.dp))
        if (loading) LoadingLine(stringResource(R.string.cx_reading), Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
        else {
            Field(
                text, { text = it; error = null },
                placeholder = stringResource(R.string.cx_carry_empty, toName), singleLine = false, minLines = 4, maxLines = 10,
                label = stringResource(R.string.cx_carry), error = over,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (messages > 0 && carry != NONE) HText(pluralStringResource(R.plurals.cx_messages, messages, messages), style = Type.small, color = HN.inkDim, maxLines = 1)
                Spacer(Modifier.weight(1f))
                val size = stringResource(R.string.cx_size, kb(bytes), kb(maxBytes))
                HText(size, style = Type.monoSmall, color = if (over) HN.amber else HN.inkDim, maxLines = 1)
            }
            if (over) {
                Spacer(Modifier.height(6.dp))
                HText(stringResource(R.string.cx_too_long, kb(maxBytes)), style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        Spacer(Modifier.height(16.dp))

        // How the other agent starts.
        FieldLabel(stringResource(R.string.cx_starts_with, toName))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (toCodex) {
                val name = codexModelName(models, model, stringResource(R.string.model_label_default))
                val effortWord = (effort ?: models?.pick(model)?.defaultEffort)?.let { effortTitle(it) }
                val said = if (effortWord != null) stringResource(R.string.cx_model_effort_said, name, effortWord) else stringResource(R.string.ns_model_said, name)
                PickChip(said, { picking = Pick.Model }) {
                    ChipText(name, modifier = Modifier.weight(1f, fill = false))
                    if (effortWord != null) {
                        Spacer(Modifier.width(6.dp))
                        HText(effortWord.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(8.dp))
            }
            val tone = modeTone(mode)
            PickChip(stringResource(R.string.ns_mode_said, modeLabel(mode)), { picking = Pick.Mode }, tint = tone) {
                Icon(modeGlyph(mode), size = 14.dp, color = tone ?: HN.inkMuted, stroke = 1.3.dp)
                Spacer(Modifier.width(6.dp))
                ChipText(modeTitle(mode), color = tone ?: HN.ink)
            }
        }

        if (stopFirst) {
            Spacer(Modifier.height(10.dp))
            HText(stringResource(R.string.cx_stop_first, from), style = Type.small, color = HN.amber, modifier = Modifier.padding(horizontal = 4.dp))
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            HText(it, style = Type.small, color = HN.danger, modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f), kind = PillKind.Ghost, height = 48.dp)
            Pill(
                stringResource(
                    when {
                        switchingNow -> R.string.cx_switching
                        stopFirst -> R.string.cx_stop_and_switch
                        else -> R.string.cx_switch
                    },
                ),
                ::go, Modifier.weight(1f), kind = PillKind.Primary, height = 48.dp,
                // "Switching…" stays lit; nothing to switch with yet, or too much, is a faded pill.
                enabled = switchingNow || (!loading && !over),
            )
        }
    }

    when (picking) {
        Pick.Model -> CodexModelSheet(
            models, note, model, effort, withEffort = true,
            onDone = { m, e -> model = m; effort = e; picking = null },
            onDismiss = { picking = null },
        )
        Pick.Mode -> ModeSheet(
            mode,
            onPick = { m ->
                picking = null
                // Bypass waits for the fingerprint or the screen lock, as everywhere (docs/DECISIONS.md).
                if (m == "bypassPermissions" && m != mode) {
                    com.prudnikweb.hypernova.ui.components.OwnerCheck.ask(
                        context, context.getString(R.string.check_bypass_title), s.name,
                        context.getString(R.string.check_no_lock), hub.isDemo,
                        onOk = { mode = m }, onFail = { msg -> if (msg != null) error = msg },
                    )
                } else mode = m
            },
            onDismiss = { picking = null },
            modes = if (toCodex) AgentKind.CODEX_MODES else MODES,
        )
        null -> Unit
    }
}

/** Which picker is open over the switch sheet. */
private enum class Pick { Model, Mode }

/** A size as the switch sheet says it: "0 KB", "2.4 KB", "18 KB". */
private fun kb(bytes: Int): String = when {
    bytes <= 0 -> "0 KB"
    bytes < 10 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024f)
    else -> "${(bytes + 512) / 1024} KB"
}
