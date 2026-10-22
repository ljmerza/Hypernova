package com.prudnikweb.hypernova.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.SlashCommand
import com.prudnikweb.hypernova.data.find
import com.prudnikweb.hypernova.data.noteCommands
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type

/** The command being typed: "/mo" → "mo"; null once a space follows its name (its arguments), or without a slash. */
fun commandQuery(text: String): String? = if (text.startsWith("/") && text.none { it.isWhitespace() }) text.drop(1) else null

/** The command a prompt starts with, once its name is complete ("/compact keep it short" → "compact"). */
fun commandOf(text: String): String? = Regex("^/(\\S+)\\s").find(text)?.groupValues?.get(1)

/** The "/" list for a project: fetched when it first opens, then cached by the hub. */
class CommandsState(val list: List<SlashCommand>?, val error: String?)

/** The project's "/" list once "/" is typed; a list fetched is kept for `knownCommands` too. */
@Composable
fun rememberCommands(hub: Hub, cwd: String?, wanted: Boolean): CommandsState {
    var state by remember(cwd) { mutableStateOf(CommandsState(null, null)) }
    LaunchedEffect(cwd, wanted) {
        if (!wanted || state.list != null) return@LaunchedEffect
        state = hub.commands(cwd).fold({ noteCommands(cwd, it); CommandsState(it, null) }, { CommandsState(null, it.message) })
    }
    return state
}

/** The command [text] names in full ("/compact " → its entry, an alias too), for its argument hint. */
fun CommandsState.picked(text: String): SlashCommand? =
    commandOf(text)?.takeIf { text.trimEnd() == "/$it" }?.let { list?.find(it) }

/**
 * The commands matching what's typed after "/". Nothing typed yet: all of them, the owner's own
 * (skills, commands, plugins') first, then Claude Code's. Otherwise the best matches first: a
 * name that starts with it, then an alias or a part of a name ("/cost" finds usage), then a
 * name that contains it, then (past two letters) a description; the owner's first within each.
 */
fun matching(all: List<SlashCommand>, q: String): List<SlashCommand> {
    val query = q.lowercase()
    fun rank(c: SlashCommand): Int? {
        val n = c.name.lowercase()
        return when {
            query.isEmpty() || n.startsWith(query) -> 0
            c.aliases.any { it.lowercase().startsWith(query) } || n.split(':', '-').any { it.startsWith(query) } -> 1
            n.contains(query) -> 2
            query.length > 2 && c.description.lowercase().contains(query) -> 3
            else -> null
        }
    }
    return all.mapNotNull { c -> rank(c)?.let { c to it } }
        .sortedWith(if (query.isEmpty()) compareBy({ it.first.builtin }, { it.first.name }) else compareBy({ it.second }, { it.first.builtin }, { it.first.name }))
        .map { it.first }
}

/** Over the prompt box while a command is being typed: tap one to put it in the box. */
@Composable
fun CommandSuggestions(state: CommandsState, query: String, onPick: (SlashCommand) -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier.fillMaxWidth()) {
        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            val list = state.list
            when {
                list == null && state.error != null -> Note(state.error)
                list == null -> Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Planet(Activity.Live)
                    Spacer(Modifier.width(10.dp))
                    HText(stringResource(R.string.commands_loading), style = Type.small, color = HN.inkDim)
                }
                else -> {
                    val shown = matching(list, query)
                    if (shown.isEmpty()) Note(stringResource(R.string.commands_none))
                    var group: Boolean? = null
                    for (c in shown) {
                        // Headings only over the whole list; filtered, it's one list, best first.
                        if (query.isEmpty() && c.builtin != group) {
                            group = c.builtin
                            HText(
                                stringResource(if (c.builtin) R.string.commands_builtin else R.string.commands_yours).uppercase(),
                                style = Type.label, color = HN.inkDim,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
                            )
                        }
                        CommandRow(c) { onPick(c) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    HText(text, style = Type.small, color = HN.inkDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun CommandRow(c: SlashCommand, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        HText(
            buildAnnotatedString {
                withStyle(SpanStyle(color = HN.accent)) { append("/") }
                withStyle(SpanStyle(color = HN.ink)) { append(c.name) }
                if (c.argumentHint.isNotBlank()) withStyle(SpanStyle(color = HN.inkDim)) { append("  " + c.argumentHint) }
            },
            style = Type.mono, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (c.description.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            HText(c.description, style = Type.small, color = HN.inkDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** After a command is picked: what it takes, until something's typed after it. */
@Composable
fun CommandHint(c: SlashCommand, modifier: Modifier = Modifier) {
    HText(
        buildAnnotatedString {
            withStyle(SpanStyle(color = HN.accent)) { append("/") }
            withStyle(SpanStyle(color = HN.inkMuted)) { append(c.name) }
            withStyle(SpanStyle(color = HN.inkDim)) { append("  " + c.argumentHint) }
        },
        style = Type.monoSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}
