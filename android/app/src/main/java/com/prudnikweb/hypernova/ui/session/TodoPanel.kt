package com.prudnikweb.hypernova.ui.session

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.ui.components.Activity
import com.prudnikweb.hypernova.ui.components.GlassCard
import com.prudnikweb.hypernova.ui.components.Glyph
import com.prudnikweb.hypernova.ui.components.Icon
import com.prudnikweb.hypernova.ui.components.Planet
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Motion
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.delay

/** The tools that write Claude's to-do list (the older TodoWrite, the newer task list). */
private val TODO_TOOLS = setOf("TodoWrite", "TaskCreate", "TaskUpdate")

/**
 * Claude's to-do list, asked of the Mac when the session opens and whenever one of its to-do
 * tools runs or finishes (they're in the transcript as tool rows), never on a timer.
 */
@Composable
internal fun rememberTodos(sessionId: String, items: List<Item>): List<Todo> {
    val key = remember(items) {
        items.filter { it is Item.Tool && it.verb in TODO_TOOLS }.joinToString("|") { "${it.key}:${(it as Item.Tool).running}" }
    }
    var todos by remember(sessionId) { mutableStateOf(emptyList<Todo>()) }
    LaunchedEffect(sessionId, key) {
        // A to-do tool's result lands a moment after its row.
        if (key.isNotEmpty()) delay(300)
        ChatRepo.todos(sessionId).onSuccess { todos = it }
    }
    return todos
}

/**
 * The checklist inside the status bar's card (StatusBar.kt) once the bar is opened: what's done
 * ticked, what Claude is on lit (in its "-ing" words), what's next quiet.
 */
@Composable
internal fun TodoChecklist(todos: List<Todo>, modifier: Modifier = Modifier) {
    val current = todos.firstOrNull { it.status == TodoStatus.Active } ?: todos.firstOrNull { it.status == TodoStatus.Pending }
    val list = rememberLazyListState()
    val at = todos.indexOf(current)
    // Keep what Claude is on in view as the list moves on.
    LaunchedEffect(at) { if (at > 1) list.animateScrollToItem((at - 1).coerceAtLeast(0)) }
    LazyColumn(
        modifier.fillMaxWidth().heightIn(max = 164.dp),
        state = list,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 8.dp),
    ) {
        itemsIndexed(todos, key = { _, t -> t.id }) { _, t -> TodoRow(t, t === current) }
    }
}

/** One line: ticked when done, lit while it's the one Claude is on, a hollow ring when it waits. */
@Composable
private fun TodoRow(t: Todo, current: Boolean) {
    val doneWord = stringResource(R.string.chat_todo_done)
    val nowWord = stringResource(R.string.chat_todo_now)
    val said = when {
        t.status == TodoStatus.Done -> "$doneWord: ${t.text}"
        current -> "$nowWord: ${t.active ?: t.text}"
        else -> t.text
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (current) HN.accent.copy(alpha = 0.07f) else HN.bg.copy(alpha = 0f))
            .semantics(mergeDescendants = true) { contentDescription = said }
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when {
                t.status == TodoStatus.Done -> Icon(Glyph.Check, size = 13.dp, color = HN.accentDim, stroke = 1.6.dp)
                current -> Planet(Activity.Live)
                else -> Canvas(Modifier.size(9.dp)) { drawCircle(HN.inkGhost, radius = size.minDimension / 2 - 0.5.dp.toPx(), style = Stroke(1.dp.toPx())) }
            }
        }
        Spacer(Modifier.width(10.dp))
        HText(
            if (current) t.active ?: t.text else t.text,
            style = if (t.status == TodoStatus.Done) Type.small.copy(textDecoration = TextDecoration.LineThrough) else Type.small,
            color = when {
                current -> HN.ink
                t.status == TodoStatus.Done -> HN.inkDim
                else -> HN.inkMuted
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
