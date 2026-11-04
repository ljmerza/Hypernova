// Modified by Leonardo Merza, 2026. See NOTICE.
package com.prudnikweb.hypernova.ui.session

import com.prudnikweb.hypernova.ui.platform.SharedUi
import com.prudnikweb.hypernova.ui.platform.rememberCopier
import com.prudnikweb.hypernova.ui.platform.wordsNow

import com.prudnikweb.hypernova.data.Str
import com.prudnikweb.hypernova.data.Plural
import com.prudnikweb.hypernova.ui.chat.MessageTimeLine
import com.prudnikweb.hypernova.data.Rewound
import com.prudnikweb.hypernova.data.ChatRepo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.prudnikweb.hypernova.ui.platform.pluralStringResource
import com.prudnikweb.hypernova.ui.platform.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prudnikweb.hypernova.data.Hub
import com.prudnikweb.hypernova.data.Item
import com.prudnikweb.hypernova.ui.LocalAppScope
import com.prudnikweb.hypernova.ui.LocalToast
import com.prudnikweb.hypernova.ui.chat.Markdown
import com.prudnikweb.hypernova.ui.components.Pill
import com.prudnikweb.hypernova.ui.components.PillKind
import com.prudnikweb.hypernova.ui.theme.HN
import com.prudnikweb.hypernova.ui.theme.HText
import com.prudnikweb.hypernova.ui.theme.Type
import kotlinx.coroutines.launch

/** [text] as a quote at the head of a prompt: "> " lines, cut to a sensible length, then a blank line. */
internal fun quoted(text: String, max: Int = 1200): String {
    var t = text.trim()
    if (t.length > max) t = t.substring(0, t.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max).trimEnd() + " …"
    return t.lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" } + "\n\n"
}

/**
 * What a long-press on a message offers: Copy, Quote (into the prompt box), Read aloud, Select
 * text, and for the owner's own messages Rewind to here (no Save as quick prompt: the chips that
 * showed them left the chat in 8a36f54, so it saved to a list nothing shows). [rewind]: the
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
    /** A waiting message opened to change ([WaitingEditSheet]). */
    onWaitingEdit: (com.prudnikweb.hypernova.data.WaitingEdit) -> Unit = {},
) {
    val res = wordsNow()
    val copier = rememberCopier()
    val reading = SharedUi.platform.reading
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    if (item is Item.User && item.changeable) return WaitingMenu(hub, item, onDismiss, sessionId, onWaitingEdit)
    val text = item.menuText() ?: return onDismiss()
    val mine = item is Item.User
    var page by remember(item.key) { mutableStateOf(Page.Actions) }
    when (page) {
        Page.Actions -> ChatSheet(stringResource(Str.chat_menu_title), onDismiss) {
            // When it was said, first; nothing for one with no real time (ChatTimeLabel.kt).
            sessionId?.let { hub.chatTimes(it).at(item.key) }?.let { MessageTimeLine(it) }
            val codex = sessionId?.let { hub.session(it)?.codex } == true
            HText(stringResource(if (mine) Str.you else if (codex) Str.cx_agent_codex else Str.claude).uppercase(), style = Type.label, color = if (mine) HN.inkDim else HN.accentDim)
            Spacer(Modifier.height(6.dp))
            // Claude's reply as words, not its markdown.
            HText(if (mine) text else spokenText(text).ifBlank { text }, style = Type.small, color = HN.inkMuted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            SheetAction(ChatGlyph.Copy, stringResource(Str.chat_copy), {
                copier.copy(res.getString(Str.chat_copy), text)
                // Where the system doesn't show its own confirmation.
                if (!copier.saysCopied) toast(res.getString(Str.chat_copied))
                onDismiss()
            })
            SheetAction(ChatGlyph.Quote, stringResource(Str.chat_quote), { onQuote(quoted(text)); onDismiss() })
            if (reading.canRead) {
                val readingThis = reading.now == item.key
                SheetAction(
                    if (readingThis) ChatGlyph.Stop else ChatGlyph.Speaker,
                    stringResource(if (readingThis) Str.chat_read_stop else Str.chat_read),
                    { reading.toggle(hub, item.key, text, appScope, toast, sessionId); onDismiss() },
                    hint = if (readingThis) null else stringResource(Str.chat_read_hint),
                )
            }
            // Where the menu took the press that would have selected; a mouse selects the words where they are.
            if (SharedUi.platform.input.menuTakesThePress) SheetAction(ChatGlyph.Select, stringResource(Str.chat_select), { page = Page.Select })
            if (rewind != null) SheetAction(ChatGlyph.Rewind, stringResource(Str.chat_rewind), { page = Page.Rewind })
        }
        Page.Select -> ChatSheet(stringResource(Str.chat_select), onDismiss) {
            HText(stringResource(Str.chat_select_hint), style = Type.small, color = HN.inkDim)
            Spacer(Modifier.height(12.dp))
            SelectionContainer {
                if (mine) HText(text, style = Type.body, color = HN.ink) else Markdown(text, color = HN.ink)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Pill(stringResource(Str.chat_close), onDismiss, kind = PillKind.Ghost, height = 44.dp)
            }
        }
        Page.Rewind -> RewindConfirm(item, rewind ?: return onDismiss(), onRewound, onDismiss)
    }
}

private enum class Page { Actions, Select, Rewind }

/** Whether the session is at something a rewind stops: at work, or waiting for the owner's answer. */
enum class RewindRunning { No, Working, Waiting }

/**
 * What a rewind to a message would leave behind: the owner's messages from it on, and Claude's
 * replies. [running]: what it would stop. [terminalCloses]: it has a terminal, which closes.
 * [stays]: it's open in a terminal nothing here can close, so it stays beside the new one.
 */
data class RewindAsk(
    val sessionId: String, val yours: Int, val replies: Int,
    val running: RewindRunning = RewindRunning.No, val terminalCloses: Boolean = false, val stays: Boolean = false,
)

/**
 * Asks before rewinding, saying what goes and what doesn't: the conversation from this message on
 * goes from the app, anything still running stops and its terminal closes, but the files Claude
 * changed stay as they are.
 */
@Composable
private fun RewindConfirm(item: Item, ask: RewindAsk, onRewound: (Rewound) -> Unit, onDismiss: () -> Unit) {
    val toast = LocalToast.current
    val appScope = LocalAppScope.current
    var busy by remember { mutableStateOf(false) }
    ChatSheet(stringResource(Str.chat_rewind_title), { if (!busy) onDismiss() }) {
        HText(stringResource(Str.chat_rewind_title), style = Type.title, color = HN.ink)
        Spacer(Modifier.height(10.dp))
        when (ask.running) {
            RewindRunning.No -> null
            RewindRunning.Working -> Str.chat_rewind_working
            RewindRunning.Waiting -> Str.chat_rewind_waiting
        }?.takeUnless { ask.stays }?.let {
            HText(stringResource(it), style = Type.body, color = HN.amber)
            Spacer(Modifier.height(10.dp))
        }
        val yours = pluralStringResource(Plural.chat_rewind_yours, ask.yours, ask.yours)
        val lost = if (ask.replies > 0) stringResource(Str.chat_rewind_both, yours, pluralStringResource(Plural.chat_rewind_replies, ask.replies, ask.replies)) else yours
        HText(stringResource(Str.chat_rewind_body, lost), style = Type.body, color = HN.inkMuted)
        Spacer(Modifier.height(10.dp))
        HText(stringResource(Str.chat_rewind_files), style = Type.small, color = HN.amber)
        if (ask.stays || ask.terminalCloses) {
            Spacer(Modifier.height(6.dp))
            HText(stringResource(if (ask.stays) Str.chat_rewind_stays else Str.chat_rewind_closes), style = Type.small, color = HN.inkDim)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill(stringResource(Str.cancel), { if (!busy) onDismiss() }, Modifier.weight(1f), kind = PillKind.Ghost, height = 48.dp)
            Pill(stringResource(if (busy) Str.chat_rewinding else Str.chat_rewind_go), {
                if (busy) return@Pill
                busy = true
                appScope.launch {
                    ChatRepo.rewind(ask.sessionId, item.key)
                        .onSuccess { onDismiss(); onRewound(it) }
                        .onFailure { toast(it.message.orEmpty()); busy = false }
                }
            }, Modifier.weight(1f), kind = PillKind.Primary, height = 48.dp)
        }
    }
}
