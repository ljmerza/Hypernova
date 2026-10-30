package com.prudnikweb.hypernova.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.AgentState
import com.prudnikweb.hypernova.data.AgentView
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.PhaseState
import com.prudnikweb.hypernova.data.PhaseView
import com.prudnikweb.hypernova.data.CommandKind
import com.prudnikweb.hypernova.data.CommandOutput
import com.prudnikweb.hypernova.data.CommandRun
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Question
import com.prudnikweb.hypernova.data.Resolution
import com.prudnikweb.hypernova.data.SendStatus
import com.prudnikweb.hypernova.data.SlashCommand
import com.prudnikweb.hypernova.data.Tone
import com.prudnikweb.hypernova.ui.fx.LocalAmbient
import com.prudnikweb.hypernova.ui.fx.formatDuration
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.JetBrainsMono
import com.prudnikweb.hypernova.ui.theme.LocalMotion
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay
import java.util.Locale

// ─── Messages ──────────────────────────────────────────────────────────────

/** A message's text: a step above body, as the owner's own words. */
/**
 * Whether a long-press on a message selects its text. A session's chat turns it off for the
 * messages it gives a long-press menu of their own (Copy, Quote, Read aloud…), whose "Select text"
 * brings selection back; an agent's transcript keeps it.
 */
val LocalSelectableText = androidx.compose.runtime.staticCompositionLocalOf { true }

/** A message's text, selectable unless [LocalSelectableText] says not. */
@Composable
private fun Selectable(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (LocalSelectableText.current) SelectionContainer(modifier, content = content) else Box(modifier) { content() }
}

private val MessageText = Type.heading.copy(lineHeight = Type.heading.lineHeight * 1.08f)

/**
 * What the owner sent: a message ("YOU" and the text), or a command drawn as what it is. Claude
 * Code's own commands and shell lines aren't messages, so they're slim mono rows with what they
 * printed under them; skills and the owner's other commands are prompts, so they stay messages,
 * led by a mint chip with their name. [known]: the project's "/" list when this app has it, so a
 * command typed here draws as its kind before the Mac's copy says. Sets its own top spacing.
 *
 * Not Claude's yet ([Item.User.status]): "◷ SENDING…" while it's on its way, or "◷ QUEUED" with
 * one quiet line on when Claude gets it, while it waits until Claude can take it.
 */
@Composable
fun UserMessage(
    item: Item.User,
    modifier: Modifier = Modifier,
    known: List<SlashCommand>? = null,
    files: (@Composable () -> Unit)? = null,
    /** A prompt whose session is gone: starts a new session with it. */
    onStartNew: (() -> Unit)? = null,
    /** A Codex session's: its queue waits for the turn to end (Codex takes nothing mid-turn). */
    codex: Boolean = false,
) {
    val cmd = remember(item.command, known) { item.command?.refined(known) }
    val status = when (item.status) {
        SendStatus.Sending -> stringResource(R.string.sending)
        SendStatus.QueuedStep, SendStatus.QueuedReply -> stringResource(R.string.queued_label)
        SendStatus.WaitingMac -> stringResource(R.string.waiting_mac_label)
        SendStatus.SessionGone -> stringResource(R.string.waiting_gone_label)
        SendStatus.Held -> stringResource(R.string.held_label)
        null -> null
    }
    // Claude Code hands a queued prompt to Claude when the step it's on finishes; an SDK session
    // runs one prompt at a time, so there it waits for the whole reply, and so does a Codex
    // session's (its terminal too: the Mac queues it behind the turn).
    val hint = when (item.status) {
        SendStatus.QueuedStep -> stringResource(if (codex) R.string.queued_hint_reply else R.string.queued_hint_step)
        SendStatus.QueuedReply -> stringResource(R.string.queued_hint_reply)
        SendStatus.WaitingMac -> stringResource(R.string.waiting_mac_hint)
        SendStatus.SessionGone -> stringResource(R.string.waiting_gone_hint)
        SendStatus.Held -> stringResource(R.string.held_hint)
        else -> null
    }
    val attached = files?.takeIf { item.files.isNotEmpty() }
    when (cmd?.type) {
        null -> TextMessage(item, status, hint, attached, modifier.padding(top = 10.dp), onStartNew)
        CommandKind.Local -> LocalCommand(cmd, item, status, hint, attached, modifier)
        CommandKind.Bash -> ShellCommand(cmd, item, status, hint, attached, modifier)
        CommandKind.Skill, CommandKind.Prompt -> PromptCommand(cmd, item, status, hint, attached, modifier.padding(top = 10.dp))
    }
}

@Composable
private fun TextMessage(item: Item.User, status: String?, hint: String?, files: (@Composable () -> Unit)?, modifier: Modifier, onStartNew: (() -> Unit)? = null) {
    Column(modifier) {
        Eyebrow(stringResource(R.string.you), status, glyph = if (item.waiting) Glyph.Laptop else Glyph.Clock)
        Spacer(Modifier.height(6.dp))
        if (files != null) {
            files()
            if (item.text.isNotBlank()) Spacer(Modifier.height(10.dp))
        }
        if (item.text.isNotBlank()) {
            Selectable { HText(item.text, style = MessageText, color = if (item.pending) HN.inkMuted else HN.ink) }
        }
        QueuedHint(hint)
        if (item.status == SendStatus.SessionGone && onStartNew != null) {
            Spacer(Modifier.height(10.dp))
            Pill(stringResource(R.string.waiting_start_new), onStartNew, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Plus)
        }
        Outputs(item.output, tail = false)
    }
}

/**
 * Claude Code's own command ("/effort ultracode"): not a message but a slim row, a COMMAND
 * eyebrow over the command in mono, with what it printed right under it, as in the terminal.
 */
@Composable
private fun LocalCommand(cmd: CommandRun, item: Item.User, status: String?, hint: String?, files: (@Composable () -> Unit)?, modifier: Modifier) {
    val line = remember(cmd, item.pending) { commandLine(cmd, item.pending) }
    val said = spoken(stringResource(R.string.command_desc, line.text), status)
    Column(modifier) {
        // One stop for TalkBack; the output below stays its own (it unfolds).
        Column(Modifier.clearAndSetSemantics { contentDescription = said }) {
            Eyebrow(stringResource(R.string.command_label), status)
            Spacer(Modifier.height(4.dp))
            Selectable { HText(line, style = Type.mono) }
        }
        Attached(files)
        QueuedHint(hint)
        Outputs(item.output, tail = false)
    }
}

/** A "!" line run in bash mode: SHELL, "$ command", and the last lines of its output, as a terminal shows them. */
@Composable
private fun ShellCommand(cmd: CommandRun, item: Item.User, status: String?, hint: String?, files: (@Composable () -> Unit)?, modifier: Modifier) {
    val said = spoken(stringResource(R.string.shell_desc, cmd.args), status)
    Column(modifier) {
        Column(Modifier.clearAndSetSemantics { contentDescription = said }) {
            Eyebrow(stringResource(R.string.shell_label), status)
            Spacer(Modifier.height(4.dp))
            // The prompt sign apart from the command: a long one wraps under itself, and a copy
            // is just the command.
            Row {
                HText("$", style = Type.mono, color = HN.accent)
                Spacer(Modifier.width(7.dp))
                Selectable(Modifier.weight(1f)) {
                    HText(cmd.args, style = Type.mono, color = if (item.pending) HN.inkMuted else HN.ink)
                }
            }
        }
        Attached(files)
        QueuedHint(hint)
        Outputs(item.output, tail = true)
    }
}

/**
 * A skill or one of the owner's own commands: a prompt, so a message (YOU), led by a mint chip that
 * says what it is and its name ("SKILL hyper-cosmos"), then what was typed after it, as a
 * message's text.
 */
@Composable
private fun PromptCommand(cmd: CommandRun, item: Item.User, status: String?, hint: String?, files: (@Composable () -> Unit)?, modifier: Modifier) {
    val skill = cmd.type == CommandKind.Skill
    val label = stringResource(R.string.you)
    // What it is rides in its chip, before its name; typed here and not on the Mac yet, a skill or
    // a command, nobody knows which, so it says neither.
    val kind = when {
        skill -> stringResource(R.string.skill_label)
        cmd.guessed -> null
        else -> stringResource(R.string.command_label)
    }
    val args = cmd.args.trim()
    val said = spoken(
        when {
            skill && args.isEmpty() -> stringResource(R.string.skill_desc, cmd.bare)
            skill -> stringResource(R.string.skill_desc_args, cmd.bare, args)
            args.isEmpty() -> stringResource(R.string.command_desc, cmd.bare)
            else -> stringResource(R.string.command_desc_args, cmd.bare, args)
        },
        status,
    )
    Column(modifier) {
        // One stop for TalkBack (the files in it stay their own, to open them); long-press still
        // selects the text.
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = said }) {
            Eyebrow(label, status, Modifier.clearAndSetSemantics { })
            Spacer(Modifier.height(6.dp))
            if (files != null) {
                files()
                Spacer(Modifier.height(10.dp))
            }
            CommandChip(cmd.bare, item.pending, kind, Modifier.clearAndSetSemantics { })
            if (args.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.clearAndSetSemantics { }) {
                    Selectable { HText(args, style = MessageText, color = if (item.pending) HN.inkMuted else HN.ink) }
                }
            }
        }
        QueuedHint(hint)
        Outputs(item.output, tail = false)
    }
}

/**
 * "YOU", "COMMAND": tiny tracked caps; [status] ("SENDING…", "QUEUED") after it, fainter, behind a
 * small clock: it's on its way, not Claude's yet.
 */
@Composable
private fun Eyebrow(label: String, status: String?, modifier: Modifier = Modifier, glyph: Glyph = Glyph.Clock) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        HText(label.uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
        if (status != null) {
            Spacer(Modifier.width(10.dp))
            Icon(glyph, size = if (glyph == Glyph.Clock) 9.dp else 11.dp, color = HN.inkGhost, stroke = 1.1.dp)
            Spacer(Modifier.width(4.dp))
            HText(status.uppercase(), style = Type.label, color = HN.inkGhost, maxLines = 1)
        }
    }
}

/** What TalkBack says for a command: what it is, then whether it's still on its way. */
private fun spoken(what: String, status: String?) = if (status != null) "$what, $status" else what

/** Under a queued prompt: when Claude gets it, in one quiet line. */
@Composable
private fun QueuedHint(hint: String?) {
    if (hint == null) return
    Spacer(Modifier.height(6.dp))
    HText(hint, style = Type.small, color = HN.inkDim)
}

/**
 * A skill or command as a chip of frosted glass, like the state chips: a tiny still planet, lit mint
 * inside the glass, what it is in tracked caps ([kind]: "SKILL", "COMMAND"; none while nobody knows
 * which), and its name as typed after the slash ("hyper-cosmos", "telegram:access"). No outline;
 * the mint lives in the planet and the name. Dimmer while it's on its way, like a message's text.
 */
@Composable
private fun CommandChip(name: String, pending: Boolean, kind: String?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .graphicsLayer { alpha = if (pending) 0.7f else 1f }
            // The light sits on the planet: 8dp in, 11dp wide.
            .frostedGlass(HN.accent, glowX = 13.5.dp)
            .padding(start = 8.dp, end = 11.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NovaMark(size = 11.dp)
        Spacer(Modifier.width(6.dp))
        if (kind != null) {
            HText(kind.uppercase(), style = Type.label, color = HN.accentDim, maxLines = 1)
            Spacer(Modifier.width(7.dp))
        }
        HText(name, style = Type.mono, color = HN.accent, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    }
}

/** Files sent with a command, under it. */
@Composable
private fun Attached(files: (@Composable () -> Unit)?) {
    if (files == null) return
    Spacer(Modifier.height(8.dp))
    files()
}

/** What a command printed, each piece under it; a shell's folds to its last lines. */
@Composable
private fun Outputs(output: List<CommandOutput>, tail: Boolean) {
    for (o in output) {
        Spacer(Modifier.height(4.dp))
        CommandOutputRow(o.key, o.text, o.tone, tail = tail)
    }
}

/** "/effort ultracode" as the "/" list draws it: the slash in accent, the name in ink, the args quieter. */
private fun commandLine(cmd: CommandRun, pending: Boolean): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(color = HN.accent)) { append("/") }
    withStyle(SpanStyle(color = if (pending) HN.inkMuted else HN.ink)) { append(cmd.bare) }
    if (cmd.args.isNotBlank()) withStyle(SpanStyle(color = if (pending) HN.inkDim else HN.inkMuted)) { append(" " + cmd.args) }
}

private const val OUTPUT_LINES = 5

/**
 * What a command printed, under it as the terminal draws it ("└ Ultracode on …"): quiet mono,
 * red when it failed. Long output folds to its first lines, or a shell command's to its last
 * (where the errors are) under a fade; a tap unfolds it.
 */
@Composable
fun CommandOutputRow(key: String, text: String, tone: Tone, modifier: Modifier = Modifier, tail: Boolean = false) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    // Set once the folded text doesn't fit (long lines wrap too, so counting lines isn't enough).
    var clipped by remember(key, text) { mutableStateOf(false) }
    val lines = remember(text) { text.lines() }
    val color = when (tone) {
        Tone.Info -> HN.inkMuted
        Tone.Warn -> HN.amber
        Tone.Error -> HN.danger
    }
    val styled = remember(text, color, tail) { if (tail) AnnotatedString(text) else outputText(text, color, tone) }
    val folded = tail && lines.size > OUTPUT_LINES
    val expandable = expanded || clipped || folded
    val action = stringResource(if (expanded) R.string.collapse else R.string.show_all)
    val style = Type.monoSmall.copy(color = color)
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (expandable) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = action) { expanded = !expanded }
                else Modifier,
            )
            .animateContentSize(tween(Motion.REVEAL, easing = Motion.Expo))
            .padding(start = 2.dp),
    ) {
        HText("└", style = Type.monoSmall, color = HN.inkGhost, modifier = Modifier.clearAndSetSemantics { })
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            when {
                expanded -> BasicText(styled, style = style)
                // A shell's output reads like a terminal's: its last lines, one row each.
                tail -> Column(if (folded) Modifier.fadeTop() else Modifier) {
                    for (l in lines.takeLast(OUTPUT_LINES)) {
                        BasicText(l, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, onTextLayout = { if (it.hasVisualOverflow) clipped = true })
                    }
                }
                else -> BasicText(styled, style = style, maxLines = OUTPUT_LINES, overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow })
            }
            if (expandable) {
                Spacer(Modifier.height(2.dp))
                // The row's click label says it to TalkBack already.
                HText(action, style = Type.monoSmall, color = HN.inkDim, modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}

/** Claude Code's messages mark names in `backticks`: drop the marks and brighten what they mark. */
private fun outputText(src: String, color: Color, tone: Tone): AnnotatedString = buildAnnotatedString {
    val mark = if (tone == Tone.Info) HN.ink else color
    var i = 0
    while (i < src.length) {
        val open = src.indexOf('`', i)
        val close = if (open >= 0) src.indexOf('`', open + 1) else -1
        if (close < 0) { withStyle(SpanStyle(color = color)) { append(src.substring(i)) }; break }
        withStyle(SpanStyle(color = color)) { append(src.substring(i, open)) }
        withStyle(SpanStyle(color = mark)) { append(src.substring(open + 1, close)) }
        i = close + 1
    }
}

/**
 * A reply: CLAUDE over it, or CODEX in a Codex session ([codex]; data/Codex.kt). Fork: [named] false
 * leaves the name off a reply that follows another of Claude's (the chat names the first after each
 * message); a streaming reply keeps the line, for its planet.
 */
@Composable
fun AssistantMessage(item: Item.Assistant, modifier: Modifier = Modifier, onPreview: (Int) -> Unit = {}, codex: Boolean = false, named: Boolean = true) {
    Column(modifier) {
        if (named || item.streaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NovaMark(size = 12.dp)
                Spacer(Modifier.width(6.dp))
                HText(stringResource(if (codex) R.string.cx_agent_codex else R.string.claude).uppercase(), style = Type.label, color = HN.accentDim)
                if (item.streaming) {
                    Spacer(Modifier.width(8.dp))
                    Planet(Activity.Live)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        // Long-press to select and copy (in a session's chat, its menu instead).
        Selectable { Markdown(item.text, streaming = item.streaming) }
        PreviewPills(item.ports, onPreview)
    }
}

/** "Open preview on :5173" for each local dev server mentioned. */
@Composable
fun PreviewPills(ports: List<Int>, onPreview: (Int) -> Unit) {
    if (ports.isEmpty()) return
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in ports) {
            Pill(stringResource(R.string.open_preview_port, p), { onPreview(p) }, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Globe)
        }
    }
}

/** Just enough markdown for replies: **bold** and `code`. */
fun markdownLite(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < src.length) {
        when {
            src.startsWith("**", i) -> {
                val end = src.indexOf("**", i + 2)
                if (end < 0) { append(src.substring(i)); break }
                withStyle(SpanStyle(color = HN.ink, fontWeight = FontWeight(600))) { append(src.substring(i + 2, end)) }
                i = end + 2
            }
            src[i] == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end < 0) { append(src.substring(i)); break }
                withStyle(SpanStyle(fontFamily = JetBrainsMono, color = HN.accentBright, background = HN.accent.copy(alpha = 0.08f))) {
                    append(src.substring(i + 1, end))
                }
                i = end + 1
            }
            else -> { append(src[i]); i++ }
        }
    }
}

internal sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val text: String, val level: Int) : Block
    data class Bullet(val text: String, val mark: String, val indent: Int) : Block
    data class Code(val text: String) : Block
    /** A ```mermaid block whose closing fence has arrived: drawn as a diagram (Mermaid.kt). */
    data class Mermaid(val source: String) : Block
    data class Table(val table: MarkdownTable) : Block
    data object Rule : Block
}

/** A fence's language, the first word after its backticks ("```Mermaid title" is "mermaid"); null for none. */
internal fun fenceLanguage(line: String): String? =
    line.trimStart().trimStart('`').trim().substringBefore(' ').lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }

internal fun blocks(src: String, streaming: Boolean = false): List<Block> {
    val out = ArrayList<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
    val lines = src.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        // Tables after fences, so one inside ``` stays code, and before headings and lists, so a
        // header cell starting with "#" or "-" doesn't become one. See MarkdownTable.kt.
        val table = if (t.startsWith("```")) null else tableAt(lines, i, streaming)
        when {
            t.startsWith("```") -> {
                flush()
                val lang = fenceLanguage(t)
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                // A diagram only once its closing fence is in: until then it's still being written.
                val closed = i < lines.size
                out += if (lang == "mermaid" && closed && code.isNotBlank()) Block.Mermaid(code.toString().trimEnd()) else Block.Code(code.toString().trimEnd())
            }
            // It may follow a sentence with no blank line between. One only just begun ("|")
            // draws nothing yet.
            table != null -> {
                flush()
                table.table?.let { out += Block.Table(it) }
                i = table.end - 1
            }
            isRule(t) -> {
                flush()
                out += Block.Rule
            }
            t.startsWith("#") -> {
                flush()
                val level = t.takeWhile { it == '#' }.length
                out += Block.Heading(t.drop(level).trim(), level)
            }
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> {
                flush()
                out += Block.Bullet(t.drop(2), "•", (line.length - t.length) / 2)
            }
            Regex("^\\d+[.)] ").containsMatchIn(t) -> {
                flush()
                val m = Regex("^(\\d+)[.)] ").find(t)!!
                out += Block.Bullet(t.substring(m.value.length), m.groupValues[1] + ".", (line.length - t.length) / 2)
            }
            t.isBlank() -> flush()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flush()
    return out
}

/**
 * Markdown for replies and plans: headings, lists, code blocks, tables, rules, **bold** and
 * `code`. [streaming]: the text is still arriving, so a table already shows from its first line.
 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = HN.inkMuted, streaming: Boolean = false) {
    val parsed = remember(text, streaming) { blocks(text, streaming) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in parsed) {
            when (b) {
                is Block.Para -> HText(markdownLite(b.text), style = Type.body, color = color)
                is Block.Heading -> HText(
                    markdownLite(b.text),
                    style = if (b.level <= 2) Type.heading.copy(fontWeight = FontWeight(600)) else Type.body.copy(fontWeight = FontWeight(600)),
                    color = HN.ink,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Bullet -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    HText(b.mark, style = Type.body, color = HN.accentDim, modifier = Modifier.width(if (b.mark.length > 1) 24.dp else 16.dp))
                    HText(markdownLite(b.text), style = Type.body, color = color, modifier = Modifier.weight(1f))
                }
                is Block.Code -> CodeBlock(b.text)
                is Block.Mermaid -> MermaidBlock(b.source)
                is Block.Table -> MdTable(b.table, color)
                Block.Rule -> MdRule()
            }
        }
    }
}

/** A fenced code block: mono on the terminal's dark, scrolling sideways. [endPad] leaves room for a control over its corner. */
@Composable
internal fun CodeBlock(text: String, modifier: Modifier = Modifier, endPad: androidx.compose.ui.unit.Dp = 12.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(HN.bg950.copy(alpha = 0.86f))
            .border(1.dp, HN.line, RoundedCornerShape(10.dp))
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = endPad, top = 10.dp, bottom = 10.dp),
    ) {
        HText(text, style = Type.monoSmall, color = HN.inkMuted, overflow = TextOverflow.Clip)
    }
}

@Composable
fun ToolRow(item: Item.Tool, modifier: Modifier = Modifier, onOpen: ((String) -> Unit)? = null, onPreview: (Int) -> Unit = {}) {
    val path = item.path
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (path != null && onOpen != null) Modifier.heightIn(min = 32.dp).clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onOpen(path) }
                    else Modifier,
                )
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                item.running -> Planet(Activity.Live)
                item.failed -> Icon(Glyph.Cross, size = 10.dp, color = HN.danger)
                else -> Icon(Glyph.Chevron, size = 10.dp, color = HN.accentDim)
            }
            Spacer(Modifier.width(8.dp))
            HText(item.verb.uppercase(), style = Type.label, color = if (item.failed) HN.danger else HN.inkDim, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            HText(
                item.target, style = Type.monoSmall, color = if (path != null && onOpen != null) HN.ink else HN.inkMuted,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (item.error != null) {
            Spacer(Modifier.height(4.dp))
            HText(item.error, style = Type.monoSmall, color = HN.danger.copy(alpha = 0.85f), maxLines = 3, modifier = Modifier.padding(start = 18.dp))
        }
        PreviewPills(item.ports, onPreview)
    }
}

@Composable
fun ThinkingRow(item: Item.Thinking, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Glyph.Chevron, size = 10.dp, color = HN.inkGhost)
        Spacer(Modifier.width(8.dp))
        // Shimmers while Claude is at it; once it's over, one constant colour and what it took.
        ThinkingText(
            when {
                item.ongoing -> stringResource(R.string.thinking)
                item.tookMs != null -> stringResource(R.string.thought_for, item.tookMs.let { ms -> val s = ms / 1000; if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s" })
                item.chars > 0 -> stringResource(R.string.thought_chars, String.format(Locale.ROOT, "%,d", item.chars))
                else -> stringResource(R.string.thinking)
            },
            live = item.ongoing, style = Type.small,
        )
    }
}

/**
 * The end of a turn: a quiet line, its mark and DONE, then what it took drawn rather than
 * "·"-joined: how many turns, how long after a stopwatch, what it cost after a banknote. Short of
 * room the turns go, then the time; the cost stays. Or the error under it.
 */
@Composable
fun ResultRow(item: Item.Result, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val color = if (item.ok) HN.accentDim else HN.danger
    val head = stringResource(if (item.ok) R.string.result_done else R.string.result_failed)
    val turns = item.turns?.let { pluralStringResource(R.plurals.turns, it, it) }
    val took = item.durationMs?.let { elapsed(it) }
    val cost = item.costUsd?.takeIf { it >= 0.005 }?.let { String.format(Locale.ROOT, res.getString(R.string.cost), it) }
    val said = listOfNotNull(turns, took?.let { res.getString(R.string.result_took, it) }, cost?.let { res.getString(R.string.result_cost, it) })
        .takeIf { it.isNotEmpty() }?.let { res.getString(R.string.result_said, head, it.joinToString(", ")) } ?: head
    Column(modifier.padding(vertical = 2.dp)) {
        Row(Modifier.clearAndSetSemantics { contentDescription = said }, verticalAlignment = Alignment.CenterVertically) {
            Icon(if (item.ok) Glyph.Check else Glyph.Cross, size = 12.dp, color = color)
            Spacer(Modifier.width(8.dp))
            HText(head.uppercase(), style = Type.label, color = color)
            Spacer(Modifier.width(12.dp))
            FitRow(
                parts = listOf(
                    if (turns != null) fitPart({ HText(turns, style = Type.monoSmall, color = HN.inkDim, maxLines = 1) }) else NoPart,
                    if (took != null) fitPart({ GlyphFigure(Glyph.Timer, took, color = HN.inkDim) }) else NoPart,
                    if (cost != null) fitPart({ GlyphFigure(Glyph.Note, cost, color = HN.inkDim) }) else NoPart,
                ),
                giveWay = intArrayOf(0, 1),
                gap = 12.dp,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (!item.ok && !item.error.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            HText(item.error, style = Type.small, color = HN.danger.copy(alpha = 0.85f), modifier = Modifier.padding(start = 20.dp))
        }
    }
}

@Composable
fun NoticeRow(item: Item.Notice, modifier: Modifier = Modifier) {
    if (item.blocker != null) return BlockerRow(item, modifier)
    val color = when (item.tone) {
        Tone.Info -> HN.inkMuted
        Tone.Warn -> HN.amber
        Tone.Error -> HN.danger
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color.copy(alpha = 0.06f))
            .border(1.dp, color.copy(alpha = 0.25f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        HText(item.text, style = Type.small, color = if (item.tone == Tone.Info) HN.inkMuted else color)
    }
}

/**
 * Claude Code's API error that stopped the session (a limit, signed out): its own line verbatim,
 * never a Claude bubble. A slim row with a small eclipsed sun; what follows the first " · " (the
 * reset, "resets 2am (Europe/Moscow)") in mono. Amber when it needs the owner, quiet for a limit.
 */
@Composable
private fun BlockerRow(item: Item.Notice, modifier: Modifier) {
    val limit = item.blocker == com.prudnikweb.hypernova.data.Blocked.LIMIT || item.blocker == com.prudnikweb.hypernova.data.Blocked.MODEL
    val color = if (limit) HN.inkMuted else HN.amber
    val head = item.text.substringBefore(" · ").trim()
    val rest = item.text.substringAfter(" · ", "").trim()
    val text = remember(item.text, color) {
        buildAnnotatedString {
            append(head)
            if (rest.isNotEmpty()) {
                append("  ")
                withStyle(SpanStyle(fontFamily = JetBrainsMono, fontSize = Type.monoSmall.fontSize, color = HN.inkDim)) { append(rest) }
            }
        }
    }
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A sun with a dark disc over it: the light is blocked, nothing burned out.
        EclipseMark(color)
        Spacer(Modifier.width(10.dp))
        HText(text, style = Type.small, color = color)
    }
}

// ─── Mini terminal ─────────────────────────────────────────────────────────

private const val COLLAPSED_LINES = 7
/** Fork: how many lines a diff card shows before "N more lines". */
private const val DIFF_COLLAPSED_LINES = 8
private val RunningWash = Brush.verticalGradient(listOf(HN.accent.copy(alpha = 0.06f), Color.Transparent))
private val TopFadeMask = Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black, 1f to Color.Black)

/**
 * A Bash call as a small live terminal: seam header with the activity bars and
 * elapsed time, the command, streaming output with a blinking block cursor, and
 * the exit status. Collapsed it shows the last lines under a top fade; tap to
 * expand. Lines are coloured by meaning: errors red, warnings amber, success mint.
 */
@Composable
fun MiniTerminal(item: Item.Terminal, modifier: Modifier = Modifier, onPreview: (Int) -> Unit = {}) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val running = item.running
    val failed = item.exitCode != null && item.exitCode != 0
    val ring by animateColorAsState(
        when {
            running -> HN.accent.copy(alpha = 0.28f)
            failed -> HN.danger.copy(alpha = 0.35f)
            else -> HN.ink.copy(alpha = 0.08f)
        },
        tween(Motion.SECTION), label = "ring",
    )
    val shape = RoundedCornerShape(14.dp)
    val shown = if (expanded) item.lines else item.lines.takeLast(COLLAPSED_LINES)
    val clipped = !expanded && item.lines.size > COLLAPSED_LINES
    val expandable = item.lines.size > COLLAPSED_LINES

    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(HN.bg950.copy(alpha = 0.86f))
                .then(if (running) Modifier.background(RunningWash) else Modifier)
                .border(1.dp, ring, shape)
                .then(
                    if (expandable) {
                        Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { expanded = !expanded }
                    } else Modifier,
                )
                // Animate expand/collapse only; while output streams, growth follows the lines directly.
                .then(if (!running) Modifier.animateContentSize(tween(Motion.REVEAL, easing = Motion.Expo)) else Modifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            // A running command's clock ticks on its own (the daemon reports only start and end).
            val started = item.startedAt
            val ms by produceState(item.elapsedMs, item.elapsedMs, started, running) {
                value = item.elapsedMs
                if (running && started != null) while (true) { value = (System.currentTimeMillis() - started).coerceAtLeast(0); delay(250) }
            }
            val elapsed = elapsed(ms)
            // How long it ran, after a stopwatch: the turning planet says it still runs, and the
            // exit code has its own line at the bottom, drawn ("✓ exit 0").
            val stopped = !running && (item.stopped || item.exitCode == null)
            val res = LocalContext.current.resources
            Seam(
                label = stringResource(R.string.bash),
                activity = if (running) Activity.Live else Activity.Rest,
                color = if (running) HN.accent else HN.inkMuted,
                trailContent = {
                    if (stopped) SeamWords(stringResource(R.string.tool_stopped))
                    else Stamp(Glyph.Timer, elapsed, Modifier.clearAndSetSemantics { contentDescription = res.getString(if (running) R.string.running_for else R.string.ran_for, elapsed) })
                },
            )
            Spacer(Modifier.height(10.dp))
            HText(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = HN.accent)) { append("$ ") }
                    withStyle(SpanStyle(color = HN.ink)) { append(item.command) }
                },
                style = Type.mono,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
            )
            if (shown.isNotEmpty() || running) {
                Spacer(Modifier.height(6.dp))
                Column(Modifier.fillMaxWidth().then(if (clipped) Modifier.fadeTop() else Modifier)) {
                    shown.forEachIndexed { i, line -> TerminalLine(line, cursor = running && i == shown.lastIndex) }
                    if (running && shown.isEmpty()) TerminalLine("", cursor = true)
                }
            }
            if (!running && item.exitCode != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val c = if (failed) HN.danger else HN.accent
                    Icon(if (failed) Glyph.Cross else Glyph.Check, size = 12.dp, color = c)
                    Spacer(Modifier.width(6.dp))
                    HText(stringResource(R.string.exit_code, item.exitCode), style = Type.monoSmall, color = c)
                    if (expandable) {
                        Spacer(Modifier.weight(1f))
                        HText(
                            if (expanded) stringResource(R.string.collapse) else pluralStringResource(R.plurals.lines, item.lines.size, item.lines.size),
                            style = Type.monoSmall, color = HN.inkDim,
                        )
                    }
                }
            }
        }
        PreviewPills(item.ports, onPreview)
    }
}

@Composable
private fun TerminalLine(line: String, cursor: Boolean) {
    val color = when {
        line.contains("error", ignoreCase = true) || line.contains("FAILED") || line.startsWith("e: ") -> HN.danger
        line.startsWith("w: ") || line.contains("warning", ignoreCase = true) -> HN.amber
        line.contains("SUCCESS") || line.startsWith("✓") -> HN.accent
        else -> HN.inkMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HText(line, style = Type.monoSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (cursor) BlinkCursor()
    }
}

/** A hard on/off block cursor, like a real terminal, on the shared ambient clock. */
@Composable
private fun BlinkCursor() {
    val reduced = LocalMotion.current.reduced
    val clock = LocalAmbient.current
    Box(
        Modifier
            .padding(start = 2.dp)
            .width(6.dp)
            .height(12.dp)
            .graphicsLayer { alpha = if (reduced || clock.blink(1.06f)) 1f else 0f }
            .background(HN.accent),
    )
}

private fun Modifier.fadeTop() = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(TopFadeMask, blendMode = BlendMode.DstIn)
    }

/** Elapsed time from resources, always with a dot decimal ("1.5s", never "1,5s"). */
@Composable
private fun elapsed(ms: Long): String {
    val res = LocalContext.current.resources
    return if (ms < 60_000) {
        String.format(Locale.ROOT, res.getString(R.string.elapsed_secs), ms / 1000f)
    } else {
        String.format(Locale.ROOT, res.getString(R.string.elapsed_min_secs), ms / 60_000, (ms / 1000) % 60)
    }
}

// ─── Agents ────────────────────────────────────────────────────────────────

/**
 * An agent the main agent started, under the call that started it, in the mini terminal's frame:
 * its own planet (spinning while it works, amber while it asks, still once done, a cross when it
 * failed), ITS KIND AGENT with how long it has run, its tokens and tools drawn; what it's for; then,
 * while it runs, a live line of what it does now, and once done the start of its report. Agents it
 * started itself sit under it, indented. A tap opens its own transcript.
 */
@Composable
fun AgentCard(item: Item.AgentCard, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val a = item.agent
    val running = a.running
    val failed = a.state == AgentState.Failed
    val ring by animateColorAsState(
        when {
            a.asking -> HN.amber.copy(alpha = 0.45f)
            running -> HN.accent.copy(alpha = 0.28f)
            failed -> HN.danger.copy(alpha = 0.35f)
            else -> HN.ink.copy(alpha = 0.08f)
        },
        tween(Motion.SECTION), label = "ring",
    )
    val shape = RoundedCornerShape(14.dp)
    val kind = stringResource(R.string.agent_kind, kindLabel(a.type))
    val ms = agentElapsed(a)
    val said = agentSaid(a, kind, ms)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HN.bg950.copy(alpha = 0.86f))
            .then(if (running) Modifier.background(RunningWash) else Modifier)
            .border(1.dp, ring, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = stringResource(R.string.agent_open), role = Role.Button) { onOpen(a.id) }
            .animateContentSize(tween(Motion.REVEAL, easing = Motion.Expo))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(Modifier.clearAndSetSemantics { contentDescription = said }) {
            CardSeam({ AgentDot(a) }, kind, { AgentTrail(a, ms) }, agentColor(a, label = true))
            Spacer(Modifier.height(8.dp))
            HText(a.label, style = Type.body, color = HN.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            AgentBody(a)
        }
        if (item.nested.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Hairline(Modifier.fillMaxWidth(), HN.lineSoft)
            Spacer(Modifier.height(4.dp))
            for (n in item.nested) AgentRow(n, onOpen, indent = (n.depth - 1).coerceAtLeast(0) * 14)
        }
    }
}

/** Under an agent's label: what it does now (live, with a cursor), or the start of its report, or why it stopped. */
@Composable
private fun AgentBody(a: AgentView) {
    when {
        a.running -> {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                HText("▸ ", style = Type.monoSmall, color = if (a.asking) HN.amber else HN.accentDim)
                HText(
                    a.activity ?: stringResource(if (a.asking) R.string.agent_asking else R.string.agent_starting),
                    style = Type.monoSmall, color = HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                BlinkCursor()
            }
        }
        a.result != null -> {
            Spacer(Modifier.height(6.dp))
            val lines = remember(a.result) { reportLines(a.result, 3) }
            HText(markdownLite(lines), style = Type.small, color = if (a.state == AgentState.Failed) HN.danger.copy(alpha = 0.85f) else HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        a.state == AgentState.Failed || a.state == AgentState.Stopped -> {
            Spacer(Modifier.height(6.dp))
            HText(
                stringResource(if (a.state == AgentState.Failed) R.string.agent_failed else R.string.agent_stopped),
                style = Type.small, color = if (a.state == AgentState.Failed) HN.danger.copy(alpha = 0.85f) else HN.inkDim,
            )
        }
    }
}

/**
 * A workflow run under its call: WORKFLOW, how many agents run and how long; its name; its
 * summary; a rail of its phases (done in mint, the live one turning, the rest ghosts); then its
 * agents by phase, one line each, a finished phase folded to "+4 done". A row opens that agent.
 */
@Composable
fun WorkflowCard(item: Item.WorkflowCard, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val w = item.workflow
    val running = w.state == AgentState.Running
    val asking = w.phases.any { p -> p.agents.any { it.asking } } || w.loose.any { it.asking }
    val failed = w.state == AgentState.Failed
    val ring by animateColorAsState(
        when {
            asking -> HN.amber.copy(alpha = 0.45f)
            running -> HN.accent.copy(alpha = 0.28f)
            failed -> HN.danger.copy(alpha = 0.35f)
            else -> HN.ink.copy(alpha = 0.08f)
        },
        tween(Motion.SECTION), label = "ring",
    )
    val shape = RoundedCornerShape(14.dp)
    val ms = elapsedSince(w.startedAt, w.endedAt, running)
    val res = LocalContext.current.resources
    // While it runs, how many of its agents work ("2 of 5 running"); once done, how many there were
    // and what they spent after a token; how long, after a stopwatch. Short of room the tokens go,
    // then the words; the time stays.
    val agents = if (running && w.running > 0) stringResource(R.string.workflow_running_of, w.running, w.agentCount)
    else pluralStringResource(R.plurals.agents_count, w.agentCount, w.agentCount)
    val tokens = if (running) null else w.tokens?.let { tokensFigure(it) }
    val took = clock(res, ms)
    val trailSaid = listOfNotNull(
        agents,
        tokens?.let { res.getString(R.string.stats_day_tokens, it) },
        res.getString(R.string.ran_for, took),
    ).joinToString(", ")
    val trail: @Composable () -> Unit = {
        FitRow(
            modifier = Modifier.clearAndSetSemantics { contentDescription = trailSaid },
            parts = listOf(
                fitPart({ SeamWords(agents) }),
                if (tokens != null) fitPart({ Stamp(Glyph.Token, tokens) }) else NoPart,
                fitPart({ Stamp(Glyph.Timer, took) }),
            ),
            giveWay = intArrayOf(1, 0),
            gap = 10.dp,
        )
    }
    // Phases folded while done; a tap unfolds one.
    var open by rememberSaveable(item.key) { mutableStateOf(setOf<String>()) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(HN.bg950.copy(alpha = 0.86f))
            .then(if (running) Modifier.background(RunningWash) else Modifier)
            .border(1.dp, ring, shape)
            .animateContentSize(tween(Motion.REVEAL, easing = Motion.Expo))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        CardSeam(
            {
                Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                    if (failed) Icon(Glyph.Cross, size = 9.dp, color = HN.danger)
                    else Planet(if (asking) Activity.Alert else if (running) Activity.Live else Activity.Rest)
                }
            },
            stringResource(R.string.workflow_label), trail,
            when {
                asking -> HN.amber
                running -> HN.accent
                failed -> HN.danger
                else -> HN.inkMuted
            },
        )
        // Its name is the script's own identifier ("feedback-survey"): mono, on a line of its own.
        Spacer(Modifier.height(8.dp))
        HText(w.name, style = Type.mono, color = HN.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!w.summary.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            HText(w.summary, style = Type.small, color = HN.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (w.phases.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            PhaseRail(w.phases)
        }
        // One phase needs no headings of its own; several are grouped.
        val grouped = w.phases.count { it.agents.isNotEmpty() } > 1
        for (p in w.phases) {
            if (p.agents.isEmpty()) continue
            val folded = p.state == PhaseState.Done && p.title !in open && grouped
            Spacer(Modifier.height(8.dp))
            if (grouped) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 28.dp)
                        .then(
                            if (p.state == PhaseState.Done) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClickLabel = stringResource(if (folded) R.string.show_all else R.string.collapse)) {
                                open = if (p.title in open) open - p.title else open + p.title
                            } else Modifier,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HText(p.title.uppercase(), style = Type.label, color = phaseColor(p.state), maxLines = 1)
                    if (folded) {
                        Spacer(Modifier.width(8.dp))
                        DoneChip(pluralStringResource(R.plurals.phase_done, p.done, p.done))
                    }
                }
            }
            if (!folded) for (a in p.agents) AgentRow(a, onOpen, phase = p.title)
        }
        if (w.loose.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            for (a in w.loose) AgentRow(a, onOpen)
        }
    }
}

/**
 * A workflow's phases on a rail, as tracked labels joined by short hairlines: "SURVEY 4/4" in mint,
 * the live one turning, "VERIFY" a ghost; the line into a phase lit once the one before it is done.
 */
@Composable
private fun PhaseRail(phases: List<PhaseView>) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        phases.forEachIndexed { i, p ->
            if (i > 0) {
                val lit = phases[i - 1].state == PhaseState.Done
                Spacer(Modifier.width(8.dp))
                Box(Modifier.width(14.dp).height(1.dp).background(if (lit) HN.accentDim.copy(alpha = 0.6f) else HN.lineStrong))
                Spacer(Modifier.width(8.dp))
            }
            if (p.state == PhaseState.Live) {
                Planet(Activity.Live)
                Spacer(Modifier.width(6.dp))
            }
            val count = if (p.agents.isEmpty()) "" else " ${p.done}/${p.agents.size}"
            HText(p.title.uppercase() + count, style = Type.label, color = phaseColor(p.state), maxLines = 1)
        }
    }
}

private fun phaseColor(s: PhaseState) = when (s) {
    PhaseState.Done -> HN.accent
    PhaseState.Live -> HN.ink
    PhaseState.Failed -> HN.danger
    PhaseState.Waiting -> HN.inkGhost
}

/** "+4 done": a finished phase, folded, on a chip of frosted glass with no outline (the heading beside it is the mint). */
@Composable
private fun DoneChip(text: String) {
    Box(
        Modifier
            .frostedGlass(null, glowX = 0.dp)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        HText(text, style = Type.monoSmall, color = HN.accentDim, maxLines = 1)
    }
}

/**
 * One agent on one line (in a workflow, or under the agent that started it): its planet, its
 * label in mono (a workflow's "phase:" prefix dropped under that phase), and what it does now or
 * how long it took. A tap opens it.
 */
@Composable
fun AgentRow(a: AgentView, onOpen: (String) -> Unit, modifier: Modifier = Modifier, phase: String? = null, indent: Int = 0) {
    val res = LocalContext.current.resources
    val ms = agentElapsed(a)
    val label = remember(a.label, phase) { shortLabel(a.label, phase) }
    val trail = when {
        a.running -> a.activity ?: clock(res, ms)
        a.state == AgentState.Failed -> stringResource(R.string.agent_failed_short)
        a.state == AgentState.Stopped -> stringResource(R.string.tool_stopped)
        else -> clock(res, ms)
    }
    val said = agentSaid(a, stringResource(R.string.agent_kind, kindLabel(a.type)))
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClickLabel = stringResource(R.string.agent_open), role = Role.Button) { onOpen(a.id) }
            .clearAndSetSemantics { contentDescription = said }
            .padding(start = indent.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indent > 0 || a.depth > 0) {
            HText("└", style = Type.monoSmall, color = HN.inkGhost)
            Spacer(Modifier.width(6.dp))
        }
        AgentDot(a)
        Spacer(Modifier.width(10.dp))
        HText(label, style = Type.monoSmall, color = if (a.running) HN.ink else HN.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        HText(
            trail, style = Type.monoSmall, color = if (a.asking) HN.amber else if (a.state == AgentState.Failed) HN.danger.copy(alpha = 0.85f) else HN.inkDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * A card's seam: its planet, a tracked label ("EXPLORE AGENT"), a hairline, and a trail of drawn
 * figures that gives way first when the row is short (a [FitRow]; the label never does).
 */
@Composable
private fun CardSeam(dot: @Composable () -> Unit, label: String, trail: @Composable () -> Unit, color: Color) {
    val labelColor by animateColorAsState(color, tween(900), label = "seam")
    Row(verticalAlignment = Alignment.CenterVertically) {
        dot()
        Spacer(Modifier.width(10.dp))
        HText(label.uppercase(), style = Type.label, color = labelColor, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(HN.line))
        Spacer(Modifier.width(10.dp))
        trail()
    }
}

/** An agent's planet: turning while it works, amber while it asks, still once done; a cross when it failed. */
@Composable
private fun AgentDot(a: AgentView) {
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        if (a.state == AgentState.Failed) Icon(Glyph.Cross, size = 9.dp, color = HN.danger)
        else Planet(if (a.running && a.asking) Activity.Alert else if (a.running) Activity.Live else Activity.Rest)
    }
}

private fun agentColor(a: AgentView, label: Boolean) = when {
    a.running && a.asking -> HN.amber
    a.running -> if (label) HN.accent else HN.ink
    a.state == AgentState.Failed -> HN.danger
    else -> HN.inkMuted
}

/** "general-purpose" → "general", "Explore" → "explore": an agent's kind, short, for a tracked label. */
fun kindLabel(type: String): String = when (type) {
    "general-purpose" -> "general"
    "workflow-subagent" -> "workflow"
    else -> type
}

/** A workflow agent's label under its phase heading: "survey:agents" → "agents" (the phase says the rest). */
private fun shortLabel(label: String, phase: String?): String {
    if (phase == null) return label
    val i = label.indexOf(':')
    if (i <= 0 || i >= label.length - 1 || label.substring(0, i).contains(' ')) return label
    return label.substring(i + 1).trim().ifBlank { label }
}

/** The first [n] lines of a report worth reading: no blank lines, headings and list marks off. */
fun reportLines(text: String, n: Int): String =
    text.lineSequence().map { it.trim().trimStart('#', '>').trim().removePrefix("- ").removePrefix("* ") }.filter { it.isNotBlank() && !it.all { c -> c == '-' || c == '|' || c == ':' || c == ' ' } }.take(n).joinToString("\n")

/** How long an agent has run: ticking each second while it does (the Mac reports start and end). */
@Composable
private fun agentElapsed(a: AgentView): Long = elapsedSince(a.startedAt, a.endedAt, a.running)

@Composable
private fun elapsedSince(start: Long, end: Long?, running: Boolean): Long {
    val ms by produceState((end ?: System.currentTimeMillis()) - start, start, end, running) {
        value = ((end ?: System.currentTimeMillis()) - start).coerceAtLeast(0)
        while (running) {
            delay(1000)
            value = (System.currentTimeMillis() - start).coerceAtLeast(0)
        }
    }
    return ms.coerceAtLeast(0)
}

/** "14s", "2m 14s", "1h 3m": whole seconds, calm for a clock that ticks once a second. */
fun clock(res: android.content.res.Resources, ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> res.getString(R.string.clock_secs, s)
        s < 3600 -> res.getString(R.string.elapsed_min_secs, s / 60, s % 60)
        else -> res.getString(R.string.clock_hours, s / 3600, (s / 60) % 60)
    }
}

/** "980", "8.4k", "171k", "1.2M": a token count beside its glyph. */
fun tokensFigure(n: Long): String = when {
    n < 1000 -> n.toString()
    n < 10_000 -> String.format(Locale.ROOT, "%.1fk", n / 1000f)
    n < 1_000_000 -> "${n / 1000}k"
    else -> String.format(Locale.ROOT, "%.1fM", n / 1_000_000f)
}

/**
 * An agent card's trail, drawn: how long after a stopwatch, its tokens after a token, its tool
 * calls after a wrench ("stopped" first when it was). Short of room the tools go, then the tokens.
 */
@Composable
private fun AgentTrail(a: AgentView, ms: Long) {
    val res = LocalContext.current.resources
    val stopped = a.state == AgentState.Stopped
    val took = clock(res, ms)
    val tokens = a.tokens?.let { tokensFigure(it) }
    val tools = a.toolUses?.takeIf { it > 0 }?.toString()
    FitRow(
        parts = listOf(
            if (stopped) fitPart({ SeamWords(stringResource(R.string.tool_stopped)) }) else NoPart,
            fitPart({ Stamp(Glyph.Timer, took) }),
            if (tokens != null) fitPart({ Stamp(Glyph.Token, tokens) }) else NoPart,
            if (tools != null) fitPart({ Stamp(Glyph.Tool, tools) }) else NoPart,
        ),
        giveWay = intArrayOf(3, 2),
        gap = 10.dp,
    )
}

/** What TalkBack says for an agent: its kind, what it's for, where it is, and what it took ("Ran for 2m 14s, 8.4k tokens, 12 tool calls."). */
@Composable
private fun agentSaid(a: AgentView, kind: String, ms: Long? = null): String {
    val res = LocalContext.current.resources
    val where = when {
        a.running && a.asking -> stringResource(R.string.agent_asking)
        a.running -> a.activity?.let { stringResource(R.string.agent_running_doing, it) } ?: stringResource(R.string.state_working)
        a.state == AgentState.Failed -> stringResource(R.string.agent_failed)
        a.state == AgentState.Stopped -> stringResource(R.string.agent_stopped)
        else -> stringResource(R.string.agent_done)
    }
    val took = ms?.let {
        listOfNotNull(
            res.getString(R.string.ran_for, clock(res, it)),
            a.tokens?.let { t -> res.getString(R.string.stats_day_tokens, tokensFigure(t)) },
            a.toolUses?.takeIf { n -> n > 0 }?.let { n -> res.getQuantityString(R.plurals.agent_tool_calls, n, n) },
        ).joinToString(", ") + "."
    }
    return listOfNotNull("$kind. ${a.label}. $where", took).joinToString(" ")
}

// ─── Diff ──────────────────────────────────────────────────────────────────

@Composable
fun DiffCard(item: Item.Diff, modifier: Modifier = Modifier, onOpen: ((String) -> Unit)? = null, onPreviewFile: ((String) -> Unit)? = null) {
    val shape = RoundedCornerShape(14.dp)
    val path = item.path
    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(HN.bg950.copy(alpha = 0.78f))
                .border(1.dp, HN.ink.copy(alpha = 0.08f), shape)
                .then(
                    if (path != null && onOpen != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { onOpen(path) }
                    else Modifier,
                )
                .padding(vertical = 12.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                HText((item.verb ?: stringResource(R.string.edit)).uppercase(), style = Type.label, color = HN.inkDim)
                Spacer(Modifier.width(10.dp))
                HText(item.file, style = Type.monoSmall, color = HN.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                HText("+${item.added}", style = Type.monoSmall, color = HN.accent)
                Spacer(Modifier.width(6.dp))
                HText("−${item.removed}", style = Type.monoSmall, color = HN.danger)
            }
            Spacer(Modifier.height(10.dp))
            // Fork: a long diff shows its first lines and opens on a tap, as the mini terminal does.
            var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
            val long = item.lines.size > DIFF_COLLAPSED_LINES
            DiffLines(if (expanded || !long) item.lines else item.lines.take(DIFF_COLLAPSED_LINES))
            if (long) {
                val more = item.lines.size - DIFF_COLLAPSED_LINES
                HText(
                    if (expanded) stringResource(R.string.collapse) else pluralStringResource(R.plurals.more_lines, more, more),
                    style = Type.monoSmall, color = HN.accentDim, maxLines = 1,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button) { expanded = !expanded }
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                )
            }
        }
        if (path != null && onPreviewFile != null && isWebPage(path)) {
            Spacer(Modifier.height(10.dp))
            Pill(stringResource(R.string.open_preview), { onPreviewFile(path) }, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Globe)
        }
    }
}

/**
 * Diff lines as the diff card draws them: + on mint, − on red, the rest dim; "⋯" between hunks.
 * [wrap]: how many lines a long one may wrap to (an approval's, so its end isn't cut).
 */
@Composable
internal fun DiffLines(lines: List<com.prudnikweb.hypernova.data.DiffLine>, side: androidx.compose.ui.unit.Dp = 14.dp, wrap: Int = 1) {
    for (l in lines) {
        when (l.kind) {
            '~' -> HText("⋯", style = Type.monoSmall, color = HN.inkGhost, modifier = Modifier.padding(horizontal = side, vertical = 2.dp))
            '…' -> HText(
                pluralStringResource(R.plurals.more_lines, l.text.toIntOrNull() ?: 0, l.text.toIntOrNull() ?: 0),
                style = Type.monoSmall, color = HN.inkDim, modifier = Modifier.padding(horizontal = side, vertical = 4.dp),
            )
            else -> {
                val (bg, fg, mark) = when (l.kind) {
                    '+' -> Triple(HN.accent.copy(alpha = 0.07f), HN.accentBright, "+")
                    '-' -> Triple(HN.danger.copy(alpha = 0.07f), HN.danger.copy(alpha = 0.9f), "−")
                    else -> Triple(Color.Transparent, HN.inkDim, " ")
                }
                Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = side, vertical = 1.dp)) {
                    HText(mark, style = Type.monoSmall, color = fg)
                    Spacer(Modifier.width(8.dp))
                    HText(l.text, style = Type.monoSmall, color = fg, maxLines = wrap)
                }
            }
        }
    }
}

fun isWebPage(path: String): Boolean = path.endsWith(".html", true) || path.endsWith(".htm", true)

// ─── Approval ──────────────────────────────────────────────────────────────

/**
 * "Needs you": Hypernavigate's filled block for urgent states. An amber band, then what's
 * asked (a command, questions with options, or a plan), then the answers. Once answered it
 * folds into one line.
 */
@Composable
fun ApprovalBlock(item: Item.Approval, onAnswer: (Answer) -> Unit, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = item.resolved,
        transitionSpec = { fadeIn(tween(Motion.REVEAL)) togetherWith fadeOut(tween(Motion.FAST)) },
        label = "approval",
        modifier = modifier.fillMaxWidth(),
    ) { resolved ->
        if (resolved != null) {
            Folded(item, resolved)
        } else {
            val shape = RoundedCornerShape(16.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(HN.bg950.copy(alpha = 0.9f))
                    .background(HN.amber.copy(alpha = 0.05f))
                    .border(1.dp, HN.amber.copy(alpha = 0.45f), shape),
            ) {
                Band(item)
                Column(Modifier.padding(14.dp)) {
                    when (item.kind) {
                        ApprovalKind.Tool -> ToolAsk(item, onAnswer)
                        ApprovalKind.Question -> QuestionAsk(item, onAnswer)
                        ApprovalKind.Plan -> PlanAsk(item, onAnswer)
                    }
                }
            }
        }
    }
}

@Composable
private fun Band(item: Item.Approval) {
    val res = LocalContext.current.resources
    val expires = item.expiresAt
    val now by produceState(System.currentTimeMillis(), expires) {
        while (expires != null) { value = System.currentTimeMillis(); delay(15_000) }
    }
    Row(
        Modifier.fillMaxWidth().background(HN.amber).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HText(stringResource(R.string.state_needs_you).uppercase(), style = Type.label.copy(fontWeight = FontWeight(700)), color = HN.bg, maxLines = 1)
        if (expires != null && expires > now) {
            Spacer(Modifier.width(10.dp))
            HText(stringResource(R.string.auto_deny_in, formatDuration(res, expires - now)), style = Type.monoSmall, color = HN.bg.copy(alpha = 0.7f), maxLines = 1)
        }
        Spacer(Modifier.width(10.dp))
        val tag = when (item.kind) {
            // "mcp__github__create_issue" → "create issue".
            ApprovalKind.Tool -> item.tool.substringAfterLast("__").replace('_', ' ').ifBlank { item.tool }
            ApprovalKind.Question -> stringResource(R.string.question)
            ApprovalKind.Plan -> stringResource(R.string.plan)
        }
        // Takes what's left of the band and gives way first, with an ellipsis, never clipped.
        HText(
            tag.uppercase(), style = Type.label.copy(fontWeight = FontWeight(700), textAlign = androidx.compose.ui.text.style.TextAlign.End), color = HN.bg.copy(alpha = 0.7f),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // An agent the main agent started asks: which one, as it's called ("survey:agents"), in a
        // small dark capsule of its own on the band (no "BASH · survey:agents").
        val agent = item.agent
        if (agent != null) {
            Spacer(Modifier.width(8.dp))
            HText(
                agent.ifBlank { stringResource(R.string.agent_label) }, style = Type.monoSmall, color = HN.bg.copy(alpha = 0.8f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 120.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(HN.bg.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ToolAsk(item: Item.Approval, onAnswer: (Answer) -> Unit) {
    val view = LocalView.current
    if (item.reason.isNotBlank()) {
        HText(item.reason, style = Type.small, color = HN.inkMuted)
        Spacer(Modifier.height(10.dp))
    } else if (item.partial) {
        HText(stringResource(R.string.approval_loading), style = Type.small, color = HN.inkDim)
        Spacer(Modifier.height(10.dp))
    }
    // Never cut silently (the end of a script may be its rm -rf): what doesn't fit says how much
    // more there is, and "Show all" opens the whole of it, scrollable and selectable.
    var all by rememberSaveable(item.key) { mutableStateOf(false) }
    val change = item.change
    if (change != null) {
        AskedChange(item.detail, change, all, onAll = { all = !all })
    } else {
        AskedCommand(item, all, onAll = { all = !all })
    }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.deny), { onAnswer(Answer.Deny) }, Modifier.weight(1f), kind = PillKind.Danger)
        if (item.canAlways) {
            Pill(stringResource(R.string.always), { onAnswer(Answer.Always) }, Modifier.weight(1f), kind = PillKind.Outline)
        }
        Pill(
            stringResource(R.string.allow),
            {
                view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                onAnswer(Answer.Allow)
            },
            Modifier.weight(1.2f), kind = PillKind.Primary,
        )
    }
    // Research fetches dozens of sites, each asked about once: one answer for all of them.
    if (item.canSession) {
        Spacer(Modifier.height(8.dp))
        Pill(stringResource(R.string.allow_session, sessionToolLabel(item.tool)), { onAnswer(Answer.Session) }, Modifier.fillMaxWidth(), kind = PillKind.Ghost, height = 40.dp)
    }
    // What Always covers: Claude Code's own rule for it, as its terminal offers it.
    if (item.canAlways) {
        Spacer(Modifier.height(8.dp))
        HText(stringResource(R.string.always_means), style = Type.small, color = HN.inkDim)
    }
}

/** Lines of a command or a change the approval card shows before "Show all". */
private const val ASK_LINES = 12

/** The screen's share an approval shown whole may take before it scrolls. */
private const val ASK_TALL = 0.6f

/** The box an approval's command or change is drawn in; [tall]: whole, scrolling past [ASK_TALL] of the screen. */
@Composable
private fun AskBox(tall: Boolean, content: @Composable () -> Unit) {
    val max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * ASK_TALL).dp
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(HN.bg.copy(alpha = 0.9f))
            .border(1.dp, HN.line, RoundedCornerShape(10.dp))
            .then(if (tall) Modifier.heightIn(max = max).verticalScroll(rememberScrollState()) else Modifier),
    ) {
        if (tall) SelectionContainer { content() } else content()
    }
}

/** "Show all (40 lines)" under a cut command or change, "Show less" once it's whole. */
@Composable
private fun ShowAll(all: Boolean, lines: Int, onClick: () -> Unit) {
    val text = if (all) stringResource(R.string.ask_show_less) else pluralStringResource(R.plurals.ask_show_all, lines, lines)
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        HText(text, style = Type.small, color = HN.amber, maxLines = 1)
    }
}

/** A command (or a tool's one line of detail) to approve: its first lines, all of it on "Show all". */
@Composable
private fun AskedCommand(item: Item.Approval, all: Boolean, onAll: () -> Unit) {
    val lines = remember(item.detail) { item.detail.lines().size }
    // Long lines wrap: a short command can still run past the box's lines.
    var cut by remember(item.detail) { mutableStateOf(false) }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = HN.amber)) { append(if (item.tool == "Bash") "$ " else "› ") }
        withStyle(SpanStyle(color = HN.ink)) { append(item.detail) }
    }
    AskBox(all) {
        BasicText(
            text, style = Type.mono,
            maxLines = if (all) Int.MAX_VALUE else ASK_LINES, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!all) cut = it.hasVisualOverflow },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    if (all || cut || lines > ASK_LINES) ShowAll(all, lines, onAll)
}

/** An edit to approve: the file, then what goes out (−) and comes in (+), its start or all of it. */
@Composable
private fun AskedChange(path: String, change: List<com.prudnikweb.hypernova.data.DiffLine>, all: Boolean, onAll: () -> Unit) {
    HText(
        buildAnnotatedString {
            withStyle(SpanStyle(color = HN.amber)) { append("› ") }
            withStyle(SpanStyle(color = HN.ink)) { append(path) }
        },
        style = Type.monoSmall, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
    )
    Spacer(Modifier.height(6.dp))
    val shown = if (all || change.size <= ASK_LINES) change else change.take(ASK_LINES)
    AskBox(all) {
        // A long line wraps (to three lines until it's shown whole), so its end isn't cut.
        Column(Modifier.padding(vertical = 8.dp)) { DiffLines(shown, side = 10.dp, wrap = if (all) Int.MAX_VALUE else 3) }
    }
    if (change.size > ASK_LINES || change.any { it.text.length > ASK_LONG_LINE }) ShowAll(all, change.size, onAll)
}

/** A line longer than this may not fit in three wrapped lines on a small phone: "Show all" is offered. */
private const val ASK_LONG_LINE = 120

/** "web pages", "web searches", "file reads": what an "all this session" answer covers. */
@Composable
private fun sessionToolLabel(tool: String): String = stringResource(
    when (tool) {
        "WebFetch" -> R.string.session_tool_fetch
        "WebSearch" -> R.string.session_tool_search
        else -> R.string.session_tool_read
    },
)

/** AskUserQuestion: each question with its options (radio or checkboxes), plus "Other…". */
@Composable
private fun QuestionAsk(item: Item.Approval, onAnswer: (Answer) -> Unit) {
    val view = LocalView.current
    // Chosen labels per question index; "other" text per question.
    var chosen by remember(item.key) { mutableStateOf(mapOf<Int, Set<String>>()) }
    var other by remember(item.key) { mutableStateOf(mapOf<Int, String>()) }
    var otherOn by remember(item.key) { mutableStateOf(setOf<Int>()) }
    val qs = item.questions
    if (qs.isEmpty()) {
        HText(item.detail.ifBlank { stringResource(R.string.approval_loading) }, style = Type.body, color = HN.ink)
    }
    qs.forEachIndexed { qi, q ->
        if (qi > 0) Spacer(Modifier.height(18.dp))
        Seam(q.header.ifBlank { stringResource(R.string.question) }, trail = if (q.multiSelect) stringResource(R.string.pick_many) else stringResource(R.string.pick_one))
        Spacer(Modifier.height(8.dp))
        HText(q.question, style = Type.heading, color = HN.ink)
        Spacer(Modifier.height(6.dp))
        val sel = chosen[qi].orEmpty()
        for (o in q.options) {
            val on = o.label in sel
            OptionRow(o, on, q.multiSelect) {
                chosen = chosen + (qi to toggle(sel, o.label, q.multiSelect))
                if (!q.multiSelect) otherOn = otherOn - qi
            }
        }
        val isOther = qi in otherOn
        OptionRow(Question.Option(stringResource(R.string.other), null), isOther, q.multiSelect) {
            otherOn = if (isOther) otherOn - qi else otherOn + qi
            if (!q.multiSelect && !isOther) chosen = chosen + (qi to emptySet())
        }
        if (isOther) {
            Spacer(Modifier.height(6.dp))
            Field(other[qi].orEmpty(), { other = other + (qi to it) }, placeholder = stringResource(R.string.other_hint), singleLine = false, maxLines = 4)
        }
    }
    val answers = qs.mapIndexedNotNull { qi, q ->
        val labels = q.options.map { it.label }.filter { it in chosen[qi].orEmpty() } +
            listOfNotNull(other[qi]?.trim()?.takeIf { qi in otherOn && it.isNotEmpty() })
        if (labels.isEmpty()) null else q.question to labels.joinToString(", ")
    }.toMap()
    val ready = qs.isNotEmpty() && answers.size == qs.size
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.skip), { onAnswer(Answer.Deny) }, Modifier.weight(1f), kind = PillKind.Ghost)
        Pill(
            if (qs.size > 1) stringResource(R.string.send_answers) else stringResource(R.string.send_answer),
            {
                if (ready) {
                    view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                    onAnswer(Answer.Answers(answers))
                }
            },
            Modifier.weight(1.6f),
            kind = PillKind.Primary,
            enabled = ready,
        )
    }
}

private fun toggle(sel: Set<String>, label: String, multi: Boolean): Set<String> = when {
    multi && label in sel -> sel - label
    multi -> sel + label
    else -> setOf(label)
}

@Composable
private fun OptionRow(o: Question.Option, selected: Boolean, multi: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // TalkBack hears whether it's chosen: a checkbox or a radio button with its state.
            .then(
                if (multi) Modifier.toggleable(selected, remember { MutableInteractionSource() }, indication = null, role = Role.Checkbox, onValueChange = { onClick() })
                else Modifier.selectable(selected, remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton, onClick = onClick),
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multi) CheckBox(selected, HN.amber) else Radio(selected, HN.amber)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            HText(o.label, style = Type.body, color = if (selected) HN.ink else HN.inkMuted)
            if (o.description != null) HText(o.description, style = Type.small, color = HN.inkDim)
        }
    }
}

/** ExitPlanMode: the plan, then Keep planning / Approve. */
@Composable
private fun PlanAsk(item: Item.Approval, onAnswer: (Answer) -> Unit) {
    val view = LocalView.current
    val plan = item.plan
    if (plan.isNullOrBlank()) {
        HText(item.detail.ifBlank { stringResource(R.string.approval_loading) }, style = Type.body, color = HN.inkMuted)
    } else {
        Markdown(plan, color = HN.inkMuted)
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill(stringResource(R.string.keep_planning), { onAnswer(Answer.KeepPlanning) }, Modifier.weight(1f), kind = PillKind.Outline)
        Pill(
            stringResource(R.string.approve_plan),
            {
                view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
                onAnswer(Answer.ApprovePlan)
            },
            Modifier.weight(1.2f), kind = PillKind.Primary,
        )
    }
}

@Composable
private fun Folded(item: Item.Approval, resolved: Resolution) {
    val bad = resolved == Resolution.Denied || resolved == Resolution.TimedOut || resolved == Resolution.KeptPlanning
    val neutral = resolved == Resolution.AnsweredOnMac || resolved == Resolution.AnsweredElsewhere || resolved == Resolution.Closed
    val color = when (resolved) {
        Resolution.Denied, Resolution.TimedOut -> HN.danger
        Resolution.KeptPlanning, Resolution.AnsweredOnMac, Resolution.AnsweredElsewhere, Resolution.Closed -> HN.inkMuted
        else -> HN.accent
    }
    val text = when (resolved) {
        Resolution.Allowed -> stringResource(R.string.allowed)
        Resolution.AllowedSession -> stringResource(R.string.session_allowed, sessionToolLabel(item.tool))
        Resolution.AllowedAlways -> if (item.approvalId == null) stringResource(R.string.always_allowed, "${item.tool}(${item.detail.substringBefore(' ')} …)") else stringResource(R.string.always_allowed_short)
        Resolution.Denied -> stringResource(R.string.denied)
        Resolution.TimedOut -> stringResource(R.string.denied_timeout)
        Resolution.Answered -> stringResource(R.string.answered)
        Resolution.PlanApproved -> stringResource(R.string.plan_approved)
        Resolution.KeptPlanning -> stringResource(R.string.kept_planning)
        Resolution.AnsweredOnMac -> stringResource(R.string.answered_on_mac)
        Resolution.AnsweredElsewhere -> stringResource(R.string.answered_elsewhere)
        Resolution.Closed -> stringResource(R.string.approval_closed)
    }
    val by = when {
        neutral -> null
        item.by == "mac" -> stringResource(R.string.by_mac)
        item.by == "terminal" -> stringResource(R.string.by_terminal)
        else -> null
    }
    Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (neutral) Glyph.Chevron else if (bad) Glyph.Cross else Glyph.Check, size = 12.dp, color = color)
        Spacer(Modifier.width(8.dp))
        HText(if (by != null) "$text $by" else text, style = Type.small, color = color, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        val detail = when (item.kind) {
            ApprovalKind.Question -> item.questions.firstOrNull()?.question ?: item.detail
            ApprovalKind.Plan -> ""
            ApprovalKind.Tool -> item.detail
        }
        HText(detail, style = Type.monoSmall, color = HN.inkDim, maxLines = 1, modifier = Modifier.weight(1f))
    }
}

