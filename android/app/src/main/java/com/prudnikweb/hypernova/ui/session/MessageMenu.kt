package com.prudnikweb.hypernova.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.R
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.components.Markdown
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/**
 * A long-press opens a message's menu ([onOpen]), with a haptic tick; TalkBack offers it as the
 * message's long-press action, [label].
 */
internal fun Modifier.messageMenu(view: View, label: String, onOpen: () -> Unit): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onLongPress = {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onOpen()
        })
    }
    .semantics { onLongClick(label) { onOpen(); true } }

/** The words a message's menu acts on: what was sent, or Claude's reply as written. */
internal fun Item.menuText(): String? = when (this) {
    is Item.User -> text.takeIf { it.isNotBlank() }
    is Item.Assistant -> text.takeIf { it.isNotBlank() }
    else -> null
}

/** [text] as a quote at the head of a prompt: "> " lines, cut to a sensible length, then a blank line. */
internal fun quoted(text: String, max: Int = 1200): String {
    var t = text.trim()
    if (t.length > max) t = t.substring(0, t.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max).trimEnd() + " …"
    return t.lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" } + "\n\n"
}

/**
 * What a long-press on a message offers: Copy, Quote (into the prompt box), Read aloud, Select
 * text, and for the owner's own messages Save as quick prompt and Rewind to here. [rewind]: the
 * session can go back to before this message (null when it can't: the first message, one not on
 * the Mac yet); it asks first, saying what's lost.
 */
@Composable
internal fun MessageMenu(
    hub: Hub,
    item: Item,
    rewind: RewindAsk?,
    onQuote: (String) -> Unit,
    onRewound: (Rewound) -> Unit,
    onDismiss: () -> Unit,
    /** The session it's in: what the Mac's speaking wave and this phone's status line show it on. */
    sessionId: String? = null,
) {
    val context = LocalContext.current
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    if (item is Item.User && (item.waiting || item.held)) return WaitingMenu(hub, item, onDismiss, sessionId)
    val text = item.menuText() ?: return onDismiss()
    val mine = item is Item.User
    var page by remember(item.key) { mutableStateOf(Page.Actions) }
    when (page) {
        Page.Actions -> ChatSheet(stringResource(R.string.chat_menu_title), onDismiss) {
            val codex = sessionId?.let { hub.session(it)?.codex } == true
            HText(stringResource(if (mine) R.string.you else if (codex) R.string.cx_agent_codex else R.string.claude).uppercase(), style = Type.label, color = if (mine) HN.inkDim else HN.accentDim)
            Spacer(Modifier.height(6.dp))
            // Claude's reply as words, not its markdown.
            HText(if (mine) text else spokenText(text).ifBlank { text }, style = Type.small, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            SheetAction(ChatGlyph.Copy, stringResource(R.string.chat_copy), {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_copy), text))
                // Android 13+ shows its own confirmation.
                if (android.os.Build.VERSION.SDK_INT < 33) toast(context.getString(R.string.chat_copied))
                onDismiss()
            })
            SheetAction(ChatGlyph.Quote, stringResource(R.string.chat_quote), { onQuote(quoted(text)); onDismiss() })
            val reading = ReadAloud.reading == item.key
            SheetAction(
                if (reading) ChatGlyph.Stop else ChatGlyph.Speaker,
                stringResource(if (reading) R.string.chat_read_stop else R.string.chat_read),
                { ReadAloud.toggle(hub, item.key, text, appScope, toast, sessionId); onDismiss() },
                hint = if (reading || hub.isDemo) null else hub.speech?.settings?.takeIf { it.using == "soniox" }?.let { stringResource(R.string.read_aloud_hint_soniox, it.voice) }
                    ?: stringResource(R.string.chat_read_hint),
            )
            SheetAction(ChatGlyph.Select, stringResource(R.string.chat_select), { page = Page.Select })
            if (mine) {
                SheetAction(ChatGlyph.Bookmark, stringResource(R.string.chat_save_prompt), {
                    toast(context.getString(if (QuickPrompts.add(context, text)) R.string.chat_saved_prompt else R.string.chat_prompts_full))
                    onDismiss()
                })
            }
            if (rewind != null) SheetAction(ChatGlyph.Rewind, stringResource(R.string.chat_rewind), { page = Page.Rewind })
        }
        Page.Select -> ChatSheet(stringResource(R.string.chat_select), onDismiss) {
            HText(stringResource(R.string.chat_select_hint), style = Type.small, color = HN.inkDim)
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                if (mine) HText(text, style = Type.body, color = HN.ink) else Markdown(text, color = HN.ink)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pill(stringResource(R.string.chat_close), onDismiss, kind = PillKind.Ghost, height = 44.dp)
            }
        }
        Page.Rewind -> RewindConfirm(hub, item, rewind ?: return onDismiss(), onRewound, onDismiss)
    }
}

private enum class Page { Actions, Select, Rewind }

/** What a rewind to a message would leave behind: the owner's messages from it on, and Claude's replies. */
data class RewindAsk(val sessionId: String, val yours: Int, val replies: Int)

/**
 * Asks before rewinding, saying what goes and what doesn't: the conversation from this message on
 * (in the new session; this one stays as it was), but not the files Claude changed.
 */
@Composable
private fun RewindConfirm(hub: Hub, item: Item, ask: RewindAsk, onRewound: (Rewound) -> Unit, onDismiss: () -> Unit) {
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    var busy by remember { mutableStateOf(false) }
    ChatSheet(stringResource(R.string.chat_rewind_title), { if (!busy) onDismiss() }) {
        HText(stringResource(R.string.chat_rewind_title), style = Type.title, color = HN.ink)
        Spacer(Modifier.height(10.dp))
        val yours = pluralStringResource(R.plurals.chat_rewind_yours, ask.yours, ask.yours)
        val lost = if (ask.replies > 0) stringResource(R.string.chat_rewind_both, yours, pluralStringResource(R.plurals.chat_rewind_replies, ask.replies, ask.replies)) else yours
        HText(stringResource(R.string.chat_rewind_body, lost), style = Type.body, color = HN.inkMuted)
        Spacer(Modifier.height(10.dp))
        HText(stringResource(R.string.chat_rewind_files), style = Type.small, color = HN.amber)
        Spacer(Modifier.height(6.dp))
        HText(stringResource(R.string.chat_rewind_keep), style = Type.small, color = HN.inkDim)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(stringResource(R.string.cancel), { if (!busy) onDismiss() }, Modifier.weight(1f), kind = PillKind.Ghost, height = 48.dp)
            Pill(stringResource(if (busy) R.string.chat_rewinding else R.string.chat_rewind_go), {
                if (busy) return@Pill
                busy = true
                appScope.launch {
                    ChatRepo.rewind(hub, ask.sessionId, item.key)
                        .onSuccess { onDismiss(); onRewound(it) }
                        .onFailure { toast(it.message.orEmpty()); busy = false }
                }
            }, Modifier.weight(1f), kind = PillKind.Primary, height = 48.dp)
        }
    }
}
