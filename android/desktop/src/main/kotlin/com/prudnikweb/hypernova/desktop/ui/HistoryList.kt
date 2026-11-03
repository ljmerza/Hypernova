package com.prudnikweb.hypernova.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.AgentLead
import com.prudnikweb.hypernova.data.FullText
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.TextHit
import com.prudnikweb.hypernova.desktop.DesktopStrings
import com.prudnikweb.hypernova.desktop.HistoryLine
import com.prudnikweb.hypernova.desktop.HistoryModel
import com.prudnikweb.hypernova.desktop.HistoryView
import com.prudnikweb.hypernova.desktop.Ui
import com.prudnikweb.hypernova.desktop.agoWords
import com.prudnikweb.hypernova.desktop.runsOf

/**
 * History on the desktop's left pane, the phone's list (ui/home/History.kt): every ended session by the day it ended,
 * a search by title, project and last line and, from 3 characters, the words inside, project chips, and an automation's
 * runs in one row. "History acts like the resume slash command for all" (the owner). The search and chips stay put over the list.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistoryList(
    hub: Hub, view: HistoryView, model: HistoryModel, full: FullText, stale: Boolean, chosen: String?, cursor: String?, now: Long,
    modifier: Modifier = Modifier, onArrow: (Int) -> Boolean, onChoose: (String) -> Unit,
) {
    Column(modifier.testTag(LIST_TAG)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Seam(Ui.history.text(), MarkKind.Idle, Modifier.semantics { heading() }) {
                HText(Ui.history_trail.format(model.endedCount), style = Type.label, color = HN.inkDim, maxLines = 1)
            }
            if (model.endedCount == 0) {
                HText(Ui.history_empty.text(), Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = Type.title, color = HN.inkMuted)
                return@Column
            }
            SearchBox(view.query, { view.query = it }, Ui.history_search.text(), onArrow)
            if (model.projects.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in model.projects) {
                        val on = p == model.narrowed
                        Pill(p, { view.project = if (on) null else p }, Modifier.semantics { selected = on }, kind = if (on) PillKind.Primary else PillKind.Ghost, height = 30.dp)
                    }
                }
            }
            // A quiet line while the server looks, and when it couldn't look everywhere in time.
            val note = when {
                full.searching -> Ui.history_searching.text()
                full.hits?.partial == true -> Ui.history_partial.text()
                else -> null
            }
            if (note != null) HText(note, Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = Type.small, color = HN.inkDim)
            if (model.lines.isEmpty() && !full.searching) {
                val none = if (view.query.isBlank()) Ui.history_none_in.format(model.narrowed.orEmpty()) else Ui.history_no_match.format(view.query.trim())
                HText(none, Modifier.padding(horizontal = 4.dp, vertical = 4.dp), style = Type.small, color = HN.inkDim)
            }
        }
        if (model.endedCount == 0) return@Column
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = view.list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(model.lines, key = { it.key }) { line ->
                    val width = Modifier.widthIn(max = 720.dp).fillMaxWidth()
                    when (line) {
                        is HistoryLine.Day -> HText(line.label.uppercase(), width.padding(start = 8.dp, end = 8.dp, top = 8.dp).semantics { heading() }, style = Type.label, color = HN.inkDim, maxLines = 1)
                        is HistoryLine.Row -> {
                            val id = line.entry.session.id
                            HistoryRow(hub, line, now, id == chosen, id == cursor, { onChoose(id) }, width.graphicsLayer { alpha = if (stale) STALE_ALPHA else 1f })
                        }
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(view.list),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
                style = LocalScrollbarStyle.current.copy(unhoverColor = HN.ink.copy(alpha = 0.12f), hoverColor = HN.ink.copy(alpha = 0.3f)),
            )
        }
    }
}

/** A session: its project and age, its name, and its last line, or what the search found inside it. An automation's: its name, how many runs, and the Auto chip. */
@Composable
private fun HistoryRow(hub: Hub, line: HistoryLine.Row, now: Long, chosen: Boolean, marked: Boolean, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    val e = line.entry
    val s = e.session
    val age = agoWords((now - s.updatedAt).coerceAtLeast(0L))
    val trail = @Composable {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (s.codex && AgentLead.both(hub.claude, hub.codex)) {
                HText(Ui.cx_agent_codex.text().uppercase(), style = Type.label, color = HN.inkDim, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            HText(age, style = Type.monoSmall, color = HN.inkDim, maxLines = 1)
        }
    }
    RowCard(chosen, marked, onChoose, modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            val automation = e.automation
            if (automation != null) {
                val (runs, gone) = runsOf(hub, e)
                Seam(hub.automations.automation(automation.id)?.name ?: automation.name, MarkKind.Idle, trail = trail)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HText(DesktopStrings.quantityNamed("au_history_runs", runs).format(runs), style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
                    if (gone) {
                        Spacer(Modifier.width(12.dp))
                        HText(Ui.au_history_deleted.text(), style = Type.small, color = HN.inkDim, maxLines = 1)
                    }
                    Spacer(Modifier.weight(1f))
                    StatusChip(Ui.au_session_chip.text(), HN.accentDim)
                }
            } else {
                Seam(s.project, MarkKind.Idle, trail = trail)
                Spacer(Modifier.height(6.dp))
                HText(s.name, style = Type.heading, maxLines = 2)
                val hit = line.hit
                if (hit != null) {
                    Spacer(Modifier.height(4.dp))
                    Snippet(hit)
                } else if (s.activity.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    HText(s.activity, style = Type.monoSmall, color = HN.inkMuted, maxLines = 1)
                }
            }
        }
    }
}

/** The words found inside it, in the accent. */
@Composable
private fun Snippet(hit: TextHit) {
    val accent = HN.accent
    val text = remember(hit, accent) {
        buildAnnotatedString {
            append(hit.snippet)
            for (m in hit.marks) addStyle(SpanStyle(color = accent), m.first, m.last + 1)
        }
    }
    BasicText(text, style = Type.monoSmall.copy(color = HN.inkMuted), maxLines = 3)
}

/** A one-line box to search by: the arrows walk the list from here, so typing and choosing need no click between. Escape empties it first. */
@Composable
internal fun SearchBox(value: String, onChange: (String) -> Unit, placeholder: String, onArrow: (Int) -> Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value, onChange,
        singleLine = true,
        textStyle = Type.body.copy(color = HN.ink),
        cursorBrush = SolidColor(HN.accent),
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = placeholder }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionDown -> onArrow(1)
                    Key.DirectionUp -> onArrow(-1)
                    Key.Escape -> if (value.isNotEmpty()) { onChange(""); true } else false
                    else -> false
                }
            },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp)
                    .background(HN.bg800.copy(alpha = 0.6f), shape)
                    .border(1.dp, if (focused) HN.accentDim else HN.ink.copy(alpha = 0.10f), shape)
                    .padding(start = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 9.dp)) {
                    if (value.isEmpty()) HText(placeholder, style = Type.body, color = HN.inkDim, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    val close = Ui.close.text()
                    Box(
                        Modifier.size(28.dp).clickable(role = Role.Button, onClickLabel = close) { onChange("") }.semantics { contentDescription = close },
                        contentAlignment = Alignment.Center,
                    ) {
                        val ink = HN.inkMuted
                        Canvas(Modifier.size(10.dp)) {
                            val w = 1.2.dp.toPx()
                            drawLine(ink, Offset.Zero, Offset(size.width, size.height), w)
                            drawLine(ink, Offset(0f, size.height), Offset(size.width, 0f), w)
                        }
                    }
                }
            }
        },
    )
}
