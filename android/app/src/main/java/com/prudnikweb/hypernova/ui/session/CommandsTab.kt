package com.prudnikweb.hypernova.ui.session

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Aside
import com.prudnikweb.hypernova.data.Asides
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Session
import com.prudnikweb.hypernova.data.SlashCommand
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalOpener
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.LoadingLine
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.components.Seam
import com.prudnikweb.hypernova.ui.components.glassChoice
import com.prudnikweb.hypernova.ui.home.SearchField
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The session sheet's Commands tab (owner, 2026-10-30; docs/DECISIONS.md, "Commands in the session
 * sheet"): a side question first (Claude Code's /btw, typed in while Claude works too; its answer,
 * read off the terminal on the Mac, shows here; one asked from the prompt box has its answer in a
 * card over the box, and the chat has only its "/btw" line, data/Asides.kt), then Claude Code's commands
 * and the owner's skills for the project, as the "/" list has them: Recent and Pinned (kept on the
 * phone, CommandsTabModel.kt), then all of them, with a search. A tap sends a command that takes
 * nothing; one that takes something opens its field under it first. Long-press pins or unpins.
 * The ones that open a screen of their own in the terminal come last, and send then open the
 * live terminal. The session's own screen is untouched: its "/" list stays as it was.
 */
@Composable
internal fun CommandsTab(hub: Hub, session: Session, onDismiss: () -> Unit) {
    SheetPartSeam(stringResource(R.string.aside_seam))
    AsidePart(hub, session, onDismiss)
    SheetPartSeam(stringResource(R.string.commands_seam))
    CommandsPart(hub, session, onDismiss)
}

@Composable
private fun SheetPartSeam(label: String) {
    Seam(label, Modifier.padding(top = 18.dp, bottom = 10.dp))
}

/** How often an answer being read on the Mac is asked for while the tab is open. */
internal const val ASIDE_POLL_MS = 1200L

/** The side question: its field and Ask, then the last one's answer (or how it stands). */
@Composable
private fun AsidePart(hub: Hub, s: Session, onDismiss: () -> Unit) {
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val opener = LocalOpener.current
    if (!asideReach(s, hub.isDemo)) {
        HText(stringResource(R.string.aside_unreachable), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp))
        return
    }
    var question by remember(s.id) { mutableStateOf("") }
    // Kept with the ones asked from the prompt box (data/Asides.kt), so the chat has its "/btw" line.
    val aside = Asides.last(s.id)
    var sending by remember(s.id) { mutableStateOf(false) }
    LaunchedEffect(s.id) { hub.aside(s.id).onSuccess { a -> if (a != null) Asides.note(s.id, a) } }
    // Asked for again while the Mac is still reading the answer off the terminal.
    LaunchedEffect(s.id, aside?.id, aside?.asking) {
        while (Asides.last(s.id)?.asking == true) {
            delay(ASIDE_POLL_MS)
            hub.aside(s.id).onSuccess { a -> if (a != null) Asides.note(s.id, a) }
        }
    }
    val busy = sending || aside?.asking == true
    fun ask() {
        val q = question.trim()
        if (q.isEmpty() || busy) return
        sending = true
        appScope.launch {
            try {
                hub.askAside(s.id, q)
                    .onSuccess { Asides.note(s.id, it); question = "" }
                    .onFailure { toast(it.message.orEmpty()) }
            } finally {
                sending = false
            }
        }
    }
    Field(
        question, { question = it },
        placeholder = stringResource(R.string.aside_placeholder),
        singleLine = false, maxLines = 4,
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        label = stringResource(R.string.aside_seam),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        HText(stringResource(R.string.aside_intro), style = Type.small, color = HN.inkDim, modifier = Modifier.weight(1f).padding(start = 4.dp))
        Spacer(Modifier.width(10.dp))
        Pill(stringResource(R.string.aside_ask), ::ask, kind = PillKind.Primary, height = 44.dp, leading = Glyph.Send, enabled = question.isNotBlank() && !busy)
    }
    val a = aside ?: return
    Spacer(Modifier.height(14.dp))
    AsideAnswer(a, terminal = !hub.isDemo && terminalReach(s), onTerminal = { onDismiss(); opener.terminal(s.id) })
}

/** The last side question and its answer, selectable, or how it stands. */
@Composable
private fun AsideAnswer(a: Aside, terminal: Boolean, onTerminal: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .glassChoice(false, 14.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val said = stringResource(R.string.aside_question_said, a.question)
        HText(
            stringResource(R.string.aside_question, a.question), style = Type.monoSmall, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = said },
        )
        when (a.state) {
            Aside.ASKING -> LoadingLine(stringResource(R.string.aside_asking))
            Aside.ANSWERED -> {
                SelectionContainer { HText(a.answer.orEmpty(), style = Type.body, color = HN.ink) }
                if (a.clipped) HText(stringResource(R.string.aside_clipped), style = Type.small, color = HN.inkDim)
            }
            Aside.FAILED -> HText(stringResource(R.string.aside_failed, a.error.orEmpty()), style = Type.small, color = HN.amber)
            else -> HText(stringResource(R.string.aside_unseen), style = Type.small, color = HN.inkDim)
        }
        val more = a.state == Aside.UNSEEN || (a.state == Aside.ANSWERED && a.clipped)
        if (more && terminal) Pill(stringResource(R.string.open_terminal), onTerminal, kind = PillKind.Ghost, height = 40.dp, leading = Glyph.Terminal)
    }
}

/** The project's commands and skills: Recent, Pinned, then all, with a search. */
@Composable
private fun CommandsPart(hub: Hub, s: Session, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    val opener = LocalOpener.current
    val view = LocalView.current
    if (!commandReach(s, hub.isDemo)) {
        HText(stringResource(R.string.commands_unreachable), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp))
        return
    }
    val state = rememberCommands(hub, s.cwd, wanted = true)
    var picks by remember { mutableStateOf(CommandPicksStore.load(context)) }
    var query by remember(s.id) { mutableStateOf("") }
    // The command whose field is open, and what's typed in it.
    var picked by remember(s.id) { mutableStateOf<SlashCommand?>(null) }
    // The group it was opened in (Recent, Pinned or the list below): its field opens under that row only.
    var openIn by remember(s.id) { mutableStateOf("") }
    var args by remember(s.id) { mutableStateOf("") }
    var sending by remember(s.id) { mutableStateOf(false) }
    val res = context.resources
    val canTerminal = !hub.isDemo && terminalReach(s)

    fun send(c: SlashCommand, typed: String) {
        if (sending || !canSendCommand(c, typed)) return
        if (c.terminal && !canTerminal && !hub.isDemo) {
            toast(res.getString(R.string.commands_no_terminal))
            return
        }
        val line = commandLine(c, typed)
        sending = true
        appScope.launch {
            try {
                hub.send(s.id, line)
                    .onSuccess { id ->
                        picks = picks.used(c.name).also { CommandPicksStore.save(context, it) }
                        picked = null
                        args = ""
                        toast(res.getString(R.string.commands_sent, line))
                        // Its screen is in the terminal: open it there. A session that went on as another: that one.
                        if (c.terminal && canTerminal) { onDismiss(); opener.terminal(s.id) }
                        else if (id != s.id) { onDismiss(); opener.switchSession(id) }
                    }
                    .onFailure { toast(it.message.orEmpty()) }
            } finally {
                sending = false
            }
        }
    }
    fun pin(c: SlashCommand) {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val on = !picks.isPinned(c.name)
        picks = picks.pinToggled(c.name).also { CommandPicksStore.save(context, it) }
        toast(res.getString(if (on) R.string.commands_pinned_toast else R.string.commands_unpinned_toast, "/${c.name}"))
    }

    val list = state.list
    when {
        list == null && state.error != null -> HText(state.error, style = Type.small, color = HN.danger, modifier = Modifier.padding(horizontal = 4.dp))
        list == null -> LoadingLine(stringResource(R.string.commands_loading), Modifier.padding(start = 4.dp))
        else -> {
            SearchField(query, { query = it }, stringResource(R.string.commands_search))
            Spacer(Modifier.height(6.dp))
            HText(stringResource(R.string.commands_hint), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp))
            val parts = commandSections(list, picks, query)
            if (parts.isEmpty) {
                Spacer(Modifier.height(10.dp))
                HText(stringResource(R.string.commands_none), style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 4.dp))
            }
            // Each group under its heading (none over a search's matches), keyed by group: a command
            // both recent and listed below is two rows.
            val groups = buildList {
                add(Triple("recent", stringResource(R.string.commands_recent), parts.recent))
                add(Triple("pinned", stringResource(R.string.commands_pinned), parts.pinned))
                if (query.isNotBlank()) add(Triple("found", null, parts.all))
                else {
                    add(Triple("yours", stringResource(R.string.commands_yours), parts.all.filter { !it.builtin }))
                    add(Triple("builtin", stringResource(R.string.commands_builtin), parts.all.filter { it.builtin && !it.terminal }))
                    add(Triple("terminal", stringResource(R.string.commands_terminal), parts.all.filter { it.terminal }))
                }
            }
            for ((group, heading, cs) in groups) {
                if (cs.isEmpty()) continue
                if (heading != null) Heading(heading)
                for (c in cs) key(group, c.name) {
                    val open = picked == c && openIn == group
                    CommandLine(
                        c, pinned = picks.isPinned(c.name), dim = c.terminal && !canTerminal && !hub.isDemo,
                        onClick = {
                            when {
                                sendsOnTap(c) -> send(c, "")
                                open -> picked = null
                                else -> { picked = c; openIn = group; args = "" }
                            }
                        },
                        onLongClick = { pin(c) },
                    )
                    if (open) ArgsLine(c, args, { args = it }, enabled = !sending && canSendCommand(c, args), onSend = { send(c, args) }, onCancel = { picked = null })
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    HText(text.uppercase(), style = Type.label, color = HN.inkDim, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 4.dp))
}

/** A command as the "/" list draws it ("/name  hint", its description under it); a mark for a terminal one. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandLine(c: SlashCommand, pinned: Boolean, dim: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val pinLabel = stringResource(if (pinned) R.string.commands_unpin else R.string.commands_pin)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onLongClickLabel = pinLabel, onLongClick = onLongClick, onClick = onClick)
            .alpha(if (dim) 0.45f else 1f)
            .padding(horizontal = 4.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HText(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = HN.accent)) { append("/") }
                    withStyle(SpanStyle(color = HN.ink)) { append(c.name) }
                    if (c.argumentHint.isNotBlank()) withStyle(SpanStyle(color = HN.inkDim)) { append("  " + c.argumentHint) }
                },
                style = Type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (c.terminal) {
                Spacer(Modifier.width(8.dp))
                HText(stringResource(R.string.commands_terminal_mark), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
            }
        }
        if (c.description.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            HText(c.description, style = Type.small, color = HN.inkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What goes after a command that takes something: its hint as the field's, then Cancel and Send. */
@Composable
private fun ArgsLine(c: SlashCommand, value: String, onChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp)) {
        Field(
            value, onChange,
            placeholder = c.argumentHint, mono = true, singleLine = false, maxLines = 4,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            label = commandLine(c),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            Pill(stringResource(R.string.cancel), onCancel, kind = PillKind.Ghost, height = 40.dp)
            Pill(stringResource(R.string.send), onSend, kind = PillKind.Primary, height = 40.dp, leading = Glyph.Send, enabled = enabled)
        }
    }
}
