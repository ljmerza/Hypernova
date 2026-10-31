package com.prudnikweb.hypernova.ui.session

import android.content.Context
import com.prudnikweb.hypernova.ui.components.glassChoice
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.ui.components.Field
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import org.json.JSONArray

/**
 * The owner's quick prompts, kept on this phone: a few useful ones to start with, then his own
 * (saved from a sent message, edited with a long-press on the chip). Until he changes the list,
 * it's the defaults in the app's language.
 */
object QuickPrompts {
    private const val PREFS = "chat"
    private const val KEY = "quick_prompts"
    /** Chips beyond this many would only scroll away. */
    const val MAX = 12

    private var saved by mutableStateOf<List<String>?>(null)
    private var loaded = false

    private fun defaults(c: Context) = listOf(R.string.chat_qp_continue, R.string.chat_qp_tests, R.string.chat_qp_explain, R.string.chat_qp_commit).map(c::getString)

    fun list(c: Context): List<String> {
        if (!loaded) {
            loaded = true
            saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let { s ->
                runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
            }
        }
        return saved ?: defaults(c)
    }

    private fun save(c: Context, l: List<String>) {
        saved = l
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, JSONArray(l).toString()).apply()
    }

    /** Adds [text] at the front (moved there if it's already in). False when the list is full. */
    fun add(c: Context, text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val now = list(c).filter { it != t }
        if (now.size >= MAX) return false
        save(c, listOf(t) + now)
        return true
    }

    fun replace(c: Context, index: Int, text: String) {
        val t = text.trim()
        val now = list(c).toMutableList()
        if (index !in now.indices) return
        if (t.isEmpty()) now.removeAt(index) else now[index] = t
        save(c, now.distinct())
    }

    fun remove(c: Context, index: Int) = replace(c, index, "")
}

/**
 * The quick prompts as chips over the prompt box: a tap sends one, a long-press edits it. Shown
 * only while the box is empty and Claude isn't at work (the owner's call), so they never get in
 * the way of typing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickPromptChips(onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = LocalView.current
    val prompts = QuickPrompts.list(context)
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    if (prompts.isEmpty()) return
    val editLabel = stringResource(R.string.chat_qp_edit)
    LazyRow(
        // Out to the screen's edges, so chips scroll away under them rather than being cut at the
        // composer's margin; the first and last still line up with the box.
        modifier.fillMaxWidth().bleed(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        itemsIndexed(prompts, key = { _, p -> p }) { i, p ->
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            editing = i
                        },
                        onLongClickLabel = editLabel,
                    ) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onSend(p)
                    }
                    .semantics { onLongClick(editLabel) { editing = i; true } },
                contentAlignment = Alignment.Center,
            ) {
                HText(
                    p,
                    style = Type.small,
                    color = HN.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .graphicsLayer { translationY = if (pressed) 1.dp.toPx() else 0f }
                        .clip(CircleShape)
                        .glassChoice(pressed)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
    val e = editing
    if (e != null && e in prompts.indices) {
        QuickPromptEditor(prompts[e], onSave = { QuickPrompts.replace(context, e, it); editing = null }, onDelete = { QuickPrompts.remove(context, e); editing = null }, onDismiss = { editing = null })
    }
}

/** A quick prompt's words, changed or deleted. */
@Composable
private fun QuickPromptEditor(text: String, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(text) { mutableStateOf(text) }
    ChatSheet(stringResource(R.string.chat_qp_edit), onDismiss) {
        HText(stringResource(R.string.chat_qp_edit), style = Type.title, color = HN.ink)
        Spacer(Modifier.height(6.dp))
        HText(stringResource(R.string.chat_qp_edit_body), style = Type.small, color = HN.inkMuted)
        Spacer(Modifier.height(14.dp))
        Field(
            value, { value = it },
            singleLine = false, maxLines = 5, commands = true,
            keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            label = stringResource(R.string.chat_qp_edit),
            onSend = { onSave(value) }, canSend = value.isNotBlank(),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(stringResource(R.string.delete), onDelete, kind = PillKind.Danger, height = 44.dp)
            Spacer(Modifier.weight(1f))
            Pill(stringResource(R.string.cancel), onDismiss, kind = PillKind.Ghost, height = 44.dp)
            Pill(stringResource(R.string.save), { onSave(value) }, kind = PillKind.Primary, height = 44.dp)
        }
    }
}

/** Wider than the parent by [x] on each side (the parent's padding), drawn from its left edge minus [x]. */
private fun Modifier.bleed(x: Dp): Modifier = layout { m, c ->
    val extra = x.roundToPx() * 2
    val p = m.measure(c.copy(minWidth = c.minWidth + extra, maxWidth = if (c.hasBoundedWidth) c.maxWidth + extra else c.maxWidth))
    layout((p.width - extra).coerceAtLeast(0), p.height) { p.place(-extra / 2, 0) }
}
