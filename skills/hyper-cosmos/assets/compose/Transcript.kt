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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Answer
import com.prudnikweb.hypernova.data.ApprovalKind
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.data.Question
import com.prudnikweb.hypernova.data.Resolution
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

@Composable
fun UserMessage(item: Item.User, modifier: Modifier = Modifier, queued: Boolean = false) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HText(stringResource(R.string.you).uppercase(), style = Type.label, color = HN.inkDim)
            if (item.pending) {
                Spacer(Modifier.width(8.dp))
                // Waiting behind the current prompt on the Mac, or still on its way there.
                HText(stringResource(if (queued) R.string.queued_label else R.string.sending).uppercase(), style = Type.label, color = HN.inkGhost)
            }
        }
        Spacer(Modifier.height(6.dp))
        HText(
            item.text, style = Type.heading.copy(lineHeight = Type.heading.lineHeight * 1.08f),
            color = if (item.pending) HN.inkMuted else HN.ink,
        )
    }
}

@Composable
fun AssistantMessage(item: Item.Assistant, modifier: Modifier = Modifier, onPreview: (Int) -> Unit = {}) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NovaMark(size = 12.dp)
            Spacer(Modifier.width(6.dp))
            HText(stringResource(R.string.claude).uppercase(), style = Type.label, color = HN.accentDim)
            if (item.streaming) {
                Spacer(Modifier.width(8.dp))
                Planet(Activity.Live)
            }
        }
        Spacer(Modifier.height(6.dp))
        Markdown(item.text)
        PreviewPills(item.ports, onPreview)
    }
}

/** "Open preview · :5173" for each local dev server mentioned. */
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

private sealed interface Block {
    data class Para(val text: String) : Block
    data class Heading(val text: String, val level: Int) : Block
    data class Bullet(val text: String, val mark: String, val indent: Int) : Block
    data class Code(val text: String) : Block
}

private fun blocks(src: String): List<Block> {
    val out = ArrayList<Block>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += Block.Para(para.toString().trim()); para.clear() }
    val lines = src.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                out += Block.Code(code.toString().trimEnd())
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

/** Markdown for replies and plans: headings, lists, code blocks, **bold** and `code`. */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = HN.inkMuted) {
    val parsed = remember(text) { blocks(text) }
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
                is Block.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(HN.bg950.copy(alpha = 0.86f))
                        .border(1.dp, HN.line, RoundedCornerShape(10.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    HText(b.text, style = Type.monoSmall, color = HN.inkMuted, overflow = TextOverflow.Clip)
                }
            }
        }
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
        HText(
            if (item.chars > 0) stringResource(R.string.thought_chars, String.format(Locale.ROOT, "%,d", item.chars)) else stringResource(R.string.thinking),
            style = Type.small, color = HN.inkDim,
        )
    }
}

/** The end of a turn: a quiet line with turns, time and cost, or the error. */
@Composable
fun ResultRow(item: Item.Result, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    val color = if (item.ok) HN.accentDim else HN.danger
    Column(modifier.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (item.ok) Glyph.Check else Glyph.Cross, size = 12.dp, color = color)
            Spacer(Modifier.width(8.dp))
            HText(
                (if (item.ok) stringResource(R.string.result_done) else stringResource(R.string.result_failed)).uppercase(),
                style = Type.label, color = color,
            )
            Spacer(Modifier.width(10.dp))
            val parts = listOfNotNull(
                item.turns?.let { pluralStringResource(R.plurals.turns, it, it) },
                item.durationMs?.let { elapsed(it) },
                item.costUsd?.takeIf { it >= 0.005 }?.let { String.format(Locale.ROOT, res.getString(R.string.cost), it) },
            )
            HText(parts.joinToString(" · "), style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        }
        if (!item.ok && !item.error.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            HText(item.error, style = Type.small, color = HN.danger.copy(alpha = 0.85f), modifier = Modifier.padding(start = 20.dp))
        }
    }
}

@Composable
fun NoticeRow(item: Item.Notice, modifier: Modifier = Modifier) {
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

// ─── Mini terminal ─────────────────────────────────────────────────────────

private const val COLLAPSED_LINES = 7
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
            val trail = when {
                running -> stringResource(R.string.running_for, elapsed)
                item.stopped || item.exitCode == null -> stringResource(R.string.tool_stopped)
                else -> stringResource(R.string.exit_after, item.exitCode, elapsed)
            }
            Seam(
                label = stringResource(R.string.bash),
                trail = trail,
                activity = if (running) Activity.Live else Activity.Rest,
                color = if (running) HN.accent else HN.inkMuted,
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
            for (l in item.lines) {
                when (l.kind) {
                    '~' -> HText("⋯", style = Type.monoSmall, color = HN.inkGhost, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
                    '…' -> HText(
                        pluralStringResource(R.plurals.more_lines, l.text.toIntOrNull() ?: 0, l.text.toIntOrNull() ?: 0),
                        style = Type.monoSmall, color = HN.inkDim, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                    else -> {
                        val (bg, fg, mark) = when (l.kind) {
                            '+' -> Triple(HN.accent.copy(alpha = 0.07f), HN.accentBright, "+")
                            '-' -> Triple(HN.danger.copy(alpha = 0.07f), HN.danger.copy(alpha = 0.9f), "−")
                            else -> Triple(Color.Transparent, HN.inkDim, " ")
                        }
                        Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 14.dp, vertical = 1.dp)) {
                            HText(mark, style = Type.monoSmall, color = fg)
                            Spacer(Modifier.width(8.dp))
                            HText(l.text, style = Type.monoSmall, color = fg, maxLines = 1)
                        }
                    }
                }
            }
        }
        if (path != null && onPreviewFile != null && isWebPage(path)) {
            Spacer(Modifier.height(10.dp))
            Pill(stringResource(R.string.open_preview), { onPreviewFile(path) }, kind = PillKind.Outline, height = 40.dp, leading = Glyph.Globe)
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
        HText(stringResource(R.string.state_needs_you).uppercase(), style = Type.label.copy(fontWeight = FontWeight(700)), color = HN.bg)
        if (expires != null && expires > now) {
            Spacer(Modifier.width(10.dp))
            HText(stringResource(R.string.auto_deny_in, formatDuration(res, expires - now)), style = Type.monoSmall, color = HN.bg.copy(alpha = 0.7f), maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        val tag = when (item.kind) {
            ApprovalKind.Tool -> item.tool
            ApprovalKind.Question -> stringResource(R.string.question)
            ApprovalKind.Plan -> stringResource(R.string.plan)
        }
        HText(tag.uppercase(), style = Type.label.copy(fontWeight = FontWeight(700)), color = HN.bg.copy(alpha = 0.7f), maxLines = 1)
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
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(HN.bg.copy(alpha = 0.9f))
            .border(1.dp, HN.line, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        HText(
            buildAnnotatedString {
                withStyle(SpanStyle(color = HN.amber)) { append(if (item.tool == "Bash") "$ " else "› ") }
                withStyle(SpanStyle(color = HN.ink)) { append(item.detail) }
            },
            style = Type.mono,
            maxLines = 12,
        )
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
}

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
            Modifier.weight(1.6f).graphicsLayer { alpha = if (ready) 1f else 0.4f },
            kind = PillKind.Primary,
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
            .clickable(remember { MutableInteractionSource() }, indication = null, role = if (multi) Role.Checkbox else Role.RadioButton, onClick = onClick)
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
    val neutral = resolved == Resolution.AnsweredOnMac || resolved == Resolution.Closed
    val color = when (resolved) {
        Resolution.Denied, Resolution.TimedOut -> HN.danger
        Resolution.KeptPlanning, Resolution.AnsweredOnMac, Resolution.Closed -> HN.inkMuted
        else -> HN.accent
    }
    val text = when (resolved) {
        Resolution.Allowed -> stringResource(R.string.allowed)
        Resolution.AllowedAlways -> if (item.approvalId == null) stringResource(R.string.always_allowed, "${item.tool}(${item.detail.substringBefore(' ')} …)") else stringResource(R.string.always_allowed_short)
        Resolution.Denied -> stringResource(R.string.denied)
        Resolution.TimedOut -> stringResource(R.string.denied_timeout)
        Resolution.Answered -> stringResource(R.string.answered)
        Resolution.PlanApproved -> stringResource(R.string.plan_approved)
        Resolution.KeptPlanning -> stringResource(R.string.kept_planning)
        Resolution.AnsweredOnMac -> stringResource(R.string.answered_on_mac)
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
